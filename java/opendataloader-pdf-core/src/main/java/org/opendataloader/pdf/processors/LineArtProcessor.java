/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.processors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Merges {@link LineArtChunk}s with their significantly overlapping
 * neighbours, renders the merged area as an image, and replaces the merged
 * elements with a single {@link ImageChunk}. When a PaddleOCR endpoint is
 * configured the screenshot is OCR'd and, if the result looks like a LaTeX
 * formula, replaced with a {@link TextChunk} carrying the formula.
 *
 * <h3>Two OCR strategies</h3>
 * The naive approach — one HTTP call per {@code LineArtChunk} group — is far
 * too slow for scanned PDFs with many small line-art regions (a 400-page
 * report can easily produce 100+ groups, i.e. 100+ serial 3–15 s paddle
 * calls). To bound the wall-clock cost, the processor now uses one of two
 * strategies based on a single pre-scan of the page:
 *
 * <ul>
 *   <li><b>Few groups (≤ {@value #PAGE_LEVEL_OCR_THRESHOLD})</b>: each group
 *       is OCR'd independently, in parallel via {@link PaddleOcrClient}. This
 *       keeps per-group precision and avoids dragging a whole-page raster
 *       through the network when only a handful of formulas are present.</li>
 *   <li><b>Many groups (> {@value #PAGE_LEVEL_OCR_THRESHOLD})</b>: render the
 *       whole page once, send it to Paddle once, then walk the OCR result
 *       looking for LaTeX-shaped entries. Each entry's bounding box is matched
 *       against the original candidate group ranges via IoU; entries that
 *       intersect a candidate range (above {@link #FORMULA_IOU_THRESHOLD})
 *       replace the corresponding merged {@link ImageChunk} with a
 *       {@link TextChunk}. Non-formula OCR results are dropped — they would
 *       otherwise duplicate text that the rest of the pipeline has already
 *       extracted from the page.</li>
 * </ul>
 *
 * <p>The document processor short-circuits pages with more than
 * {@value #DocumentProcessor#LINEART_TOO_MANY_THRESHOLD} candidate chunks
 * anyway, so we never enter an unbounded retry storm.
 */
public final class LineArtProcessor {

    private static final Logger LOGGER = Logger.getLogger(LineArtProcessor.class.getCanonicalName());

    /**
     * Below this count, we OCR each candidate group individually. Above this
     * count, we OCR the whole page once and match formula entries back to the
     * candidates. Two is intentionally low: most pages have at most one or two
     * formulas, so anything beyond that strongly suggests a layout that the
     * per-group path will struggle with.
     */
    private static final int PAGE_LEVEL_OCR_THRESHOLD = 2;

    /**
     * IoU threshold for matching an OCR-returned formula bounding box against
     * a candidate group range. Below this we treat the formula as not matching
     * the group and drop it (rather than overwriting unrelated text). The
     * value is intentionally permissive because OCR bounding boxes are
     * pixel-space and candidate ranges are PDF-space, so exact IoU rarely
     * exceeds 0.7 even on good matches.
     */
    private static final double FORMULA_IOU_THRESHOLD = 0.3;

    /** DPI used when rasterising a page for the page-level fallback OCR. */
    private static final float PAGE_LEVEL_RENDER_DPI = 200.0f;

    /**
     * IoU threshold used by the page-level entry fallback
     * ({@link #applyPageLevelEntryFallback}) to decide whether an OCR-returned
     * formula entry overlaps an existing page object enough to replace it.
     * A tighter threshold than {@link #FORMULA_IOU_THRESHOLD} because the
     * fallback has no candidate-range bbox to lean on.
     */
    private static final double PAGE_LEVEL_ENTRY_IOU_THRESHOLD = 0.5;

    /** Default font size (pt) used when a formula bbox gives no usable size. */
    private static final double DEFAULT_FORMULA_FONT_SIZE = 12.0;

    private static final Pattern LATEX_COMMAND_PATTERN = Pattern.compile("\\\\[a-zA-Z]+");
    private static final Pattern LATEX_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*\\{");
    private static final Pattern LATEX_SIMPLE_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*[a-zA-Z0-9]");

    private LineArtProcessor() {
    }

    /**
     * Walks {@code pageContents} once, merging any
     * {@link LineArtChunk}/{@link ShapeChunk} cluster with overlapping
     * neighbours into one screenshot. When {@code paddleUrl} is set, the
     * screenshot is OCR'd and may be replaced by a LaTeX formula
     * {@link TextChunk}.
     *
     * @param pageContents   the current page contents (will be replaced)
     * @param pageNumber     0-based page number
     * @param imagesUtils    image renderer / saver
     * @param paddleUrl      PaddleOCR endpoint URL, or {@code null}/empty to skip OCR
     * @param pdfPath        absolute path of the source PDF; required for the
     *                        page-level fallback OCR (may be {@code null} when
     *                        paddle is disabled)
     * @param sourceWidth    page width in PDF user units; required for IoU
     *                        matching of page-level OCR results
     * @param sourceHeight   page height in PDF user units
     */
    public static void processLineArtGroups(List<IObject> pageContents, int pageNumber,
                                             ImagesUtils imagesUtils, String paddleUrl,
                                             String pdfPath, double sourceWidth, double sourceHeight) {
        if (pageContents == null || pageContents.isEmpty() || imagesUtils == null) {
            return;
        }
        // Sort page contents from top to bottom (topY descending) so the forward/backward
        // overlap scan finds neighbours in the correct vertical order, regardless of
        // how earlier processors may have rearranged the list.
        pageContents.sort(Comparator.comparingDouble(IObject::getTopY).reversed());

        boolean paddleEnabled = paddleUrl != null && !"".equals(paddleUrl);

        // ------------------------------------------------------------------
        // Pass 1 — collect candidate formula ranges AND, in-place, swap the
        // candidate groups in pageContents for placeholder ImageChunks. After
        // this pass:
        //   - candidates holds every LineArtChunk group with its union bbox;
        //   - pageContents is already "merged" (each group replaced by a
        //     single ImageChunk) so the per-group and page-level paths
        //     converge on the same intermediate state.
        // Merging / screenshotting is performed even when Paddle is disabled
        // so that decorative line-art is still collapsed into a single image.
        // ------------------------------------------------------------------
        List<CandidateRange> candidates = scanAndMerge(pageContents, pageNumber, imagesUtils);

        if (candidates.isEmpty()) {
            return; // pageContents already in final form
        }

        if (paddleEnabled && candidates.size() > PAGE_LEVEL_OCR_THRESHOLD) {
            applyPageLevelOcr(pageContents, candidates, pageNumber, paddleUrl, pdfPath,
                sourceWidth, sourceHeight);
        } else if (paddleEnabled) {
            applyGroupLevelOcr(pageContents, candidates, pageNumber, paddleUrl);
        }
        // When Paddle is disabled the merged ImageChunks remain in pageContents unchanged.
    }

    /**
     * Single scan that does both candidate discovery and in-place merging.
     * Returns the list of candidate ranges so pass-2 can decide which OCR
     * strategy to apply. The placeholder ImageChunk (or original group, if
     * merging is impossible) replaces the group inside {@code pageContents}
     * before this method returns, so callers can rely on
     * {@code pageContents.size() == original.size() - (groups * (size - 1))}.
     *
     * <p>Note: merging applies to every {@link LineArtChunk} regardless of
     * size. The size-based filter (height ≤ 3 and width ≤ 300) is applied
     * later via {@link #isFormulaCandidate} when deciding whether to OCR —
     * that way screenshots are still produced for non-formula line-art
     * (decorative borders, dividers, etc.) without paying for a paddle call.
     */
    private static List<CandidateRange> scanAndMerge(List<IObject> pageContents, int pageNumber,
                                                      ImagesUtils imagesUtils) {
        List<CandidateRange> candidates = new ArrayList<>();
        // We walk top-to-bottom (pageContents is already sorted descending) and
        // build candidate groups by pulling "before" neighbours from the
        // accumulated result list (mirrors the legacy contract: once an
        // element is merged into an earlier group, it leaves result).
        List<IObject> result = new ArrayList<>(pageContents.size());

        for (int i = 0; i < pageContents.size(); i++) {
            IObject current = pageContents.get(i);
            if (!(current instanceof LineArtChunk && current.getHeight() <= 3 && current.getWidth() <= 300)) {
                result.add(current);
                continue;
            }

            CandidateRange range = new CandidateRange();
            range.group = new ArrayList<>();
            range.group.add(current);

            // Pull overlapping "before" neighbours already added to result.
            BoundingBox lineArtBox = new BoundingBox(current.getBoundingBox());
            for (int j = result.size() - 1; j >= 0; j--) {
                IObject candidate = result.get(j);
                if (candidate instanceof ShapeChunk || HeaderFooterProcessor.isHeaderOrFooter(candidate)) {
                    continue;
                }
                if (BoundingBoxGroupUtils.hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    range.group.add(0, candidate);
                    result.remove(j);
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }

            // Collect overlapping "after" neighbours from the original list.
            int forwardCount = 0;
            for (int j = i + 1; j < pageContents.size(); j++) {
                IObject candidate = pageContents.get(j);
                if (candidate instanceof ShapeChunk || HeaderFooterProcessor.isHeaderOrFooter(candidate)) {
                    continue;
                }
                if (BoundingBoxGroupUtils.hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    range.group.add(candidate);
                    forwardCount++;
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }
            i += forwardCount;

            if (range.group.size() < 2) {
                result.add(current);
                continue;
            }
            BoundingBox union = BoundingBoxGroupUtils.unionBoundingBoxes(range.group, pageNumber);
            if (union == null || union.isEmpty()) {
                result.addAll(range.group);
                continue;
            }
            range.union = union;
            ImageChunk imageChunk = new ImageChunk(union);
            imagesUtils.saveImageChunk(imageChunk);
            range.imageChunk = imageChunk;
            result.add(imageChunk);
            candidates.add(range);
        }

        pageContents.clear();
        pageContents.addAll(result);
        return candidates;
    }

    // ------------------------------------------------------------------
    // Per-group OCR (parallel via PaddleOcrClient)
    // ------------------------------------------------------------------

    private static void applyGroupLevelOcr(List<IObject> pageContents, List<CandidateRange> candidates,
                                            int pageNumber, String paddleUrl) {
        // Submit all OCR calls before draining — this is the key to the speedup:
        // while the paddle service is busy with group 1, our local executor can
        // keep submitting groups 2, 3, … so they pipeline on the network.
        // Note: scanAndMerge has already filtered out non-formula-sized
        // LineArtChunks (height ≤ 3 and width ≤ 300), so every candidate here
        // is a formula candidate by construction — no extra gate is needed.
        List<Future<TextInOcrAnalysisResultDto>> futures = new ArrayList<>(candidates.size());
        for (CandidateRange range : candidates) {
            ImageChunk imageChunk = range.imageChunk;
            String imageFileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT,
                StaticLayoutContainers.getImagesDirectory(), File.separator,
                imageChunk.getIndex(), StaticLayoutContainers.getImageFormat());
            File imageFile = new File(imageFileName);
            futures.add(PaddleOcrClient.submitOcrTask(imageFile, 1, paddleUrl));
        }

        // Patch the already-merged ImageChunks in pageContents in place. This
        // path is only taken for candidate counts <= PAGE_LEVEL_OCR_THRESHOLD
        // (the public {@link processLineArtGroups} gates it), so we never
        // exceed a handful of OCR results here — no per-page counter needed.
        for (int idx = 0; idx < candidates.size(); idx++) {
            CandidateRange range = candidates.get(idx);
            TextInOcrAnalysisResultDto ocr = drainFuture(futures.get(idx));
            if (ocr == null) {
                replaceMergedChunkWithGroup(pageContents, range);
                continue;
            }
            TextChunk formulaChunk = tryCreateFormulaTextChunk(ocr, range.union);
            if (formulaChunk == null) {
                // OCR succeeded but no formula recognised: undo the merge,
                // restore the original group elements (same as legacy).
                replaceMergedChunkWithGroup(pageContents, range);
                continue;
            }
            Double fontSize = range.union.getHeight() / 2.0 >= DEFAULT_FORMULA_FONT_SIZE ?
                Math.round(100.0 * range.union.getHeight() / 2.0) / 100.0 : DEFAULT_FORMULA_FONT_SIZE;
            List<Double> fontSizes = new ArrayList<>();
            for (IObject item : range.group) {
                if (item instanceof CustomSemanticParagraph) {
                    fontSizes.add(((CustomSemanticParagraph) item).getFontSize());
                }
            }
            if (!fontSizes.isEmpty()) {
                fontSize = Collections.max(fontSizes);
            }
            formulaChunk.setFontSize(fontSize);
            replaceMergedChunkWithObject(pageContents, range, formulaChunk);
        }
    }

    // ------------------------------------------------------------------
    // Page-level OCR (new fast path for pages with many candidates)
    // ------------------------------------------------------------------

    private static void applyPageLevelOcr(List<IObject> pageContents, List<CandidateRange> candidates,
                                          int pageNumber, String paddleUrl,
                                          String pdfPath, double sourceWidth, double sourceHeight) {
        RenderedPage rendered;
        try {
            rendered = renderPageToImage(pdfPath, pageNumber);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                "Page-level OCR: failed to render page " + (pageNumber + 1) + " of " + pdfPath, e);
            restoreAllGroups(pageContents, candidates); // Same as the per-group failure path: restore original text.
            return;
        }

        try {
            Future<TextInOcrAnalysisResultDto> future = PaddleOcrClient.submitOcrTask(rendered.imageFile, 1, paddleUrl);
            TextInOcrAnalysisResultDto ocr = drainFuture(future);

            if (ocr == null || ocr.getDetail() == null || ocr.getDetail().isEmpty()) {
                LOGGER.log(Level.INFO,
                    "Page {0} - page-level OCR returned no detail; falling back to per-group ImageChunks",
                    pageNumber + 1);
                restoreAllGroups(pageContents, candidates);
                return;
            }

            List<OcrEntry> entries = buildOcrEntries(ocr, sourceWidth, sourceHeight,
                rendered.imageWidth, rendered.imageHeight, pageNumber);
            if (entries.isEmpty()) {
                restoreAllGroups(pageContents, candidates);
                return;
            }

            // Filter out non-LaTeX entries once, up front. Every remaining entry is
            // a formula candidate by construction, so {@link #findBestIouMatch} and
            // the call sites below no longer need to re-check {@code isLatex}.
            List<OcrEntry> latexEntries = entries.stream()
                .filter(e -> e.isLatex)
                .collect(Collectors.toList());
            if (latexEntries.isEmpty()) {
                restoreAllGroups(pageContents, candidates);
                return;
            }

            // PAGE_LEVEL_OCR_THRESHOLD only limits the number of OCR calls (at most one whole-page
            // call per page on this path), not the number of formulas actually recognised. Unmatched
            // LaTeX entries are appended to pageContents via applyPageLevelEntryFallback with no
            // per-page upper bound. scanAndMerge already filtered out non-formula-sized LineArtChunks,
            // so every candidate here is a formula candidate and needs no extra check.
            // Pass 1: for each candidate range, try to find a matching LaTeX
            // entry. Hits get replaced inline (and the entry is flagged
            // consumed). Ranges that miss simply put their group back into
            // pageContents — the Pass 2 fallback then decides what to do with
            // each remaining LaTeX entry.
            for (CandidateRange range : candidates) {
                OcrEntry bestMatch = findBestIouMatch(range.union, latexEntries, FORMULA_IOU_THRESHOLD);
                if (bestMatch == null) {
                    // No LaTeX entry matched this range — just restore the
                    // group. Pass 2 will deal with whatever the un-matched
                    // LaTeX entries end up wanting to replace.
                    replaceMergedChunkWithGroup(pageContents, range);
                    continue;
                }
                TextChunk formulaChunk = createTextChunk(
                    "$$" + bestMatch.text.replace("$", "").trim() + "$$", range.union);
                Double fontSize = range.union.getHeight() / 2 >= DEFAULT_FORMULA_FONT_SIZE ?
                    Math.round(100.0 * range.union.getHeight() / 2) / 100.0 : DEFAULT_FORMULA_FONT_SIZE;
                List<Double> fontSizes = new ArrayList<>();
                for (IObject item : range.group) {
                    if (item instanceof CustomSemanticParagraph) {
                        fontSizes.add(((CustomSemanticParagraph) item).getFontSize());
                    }
                }
                if (!fontSizes.isEmpty()) {
                    fontSize = Collections.max(fontSizes);
                }
                formulaChunk.setFontSize(fontSize);
                replaceMergedChunkWithObject(pageContents, range, formulaChunk);
                bestMatch.consumed = true;
            }

            // Pass 2: for each LaTeX entry that no candidate range picked up,
            // walk pageContents and look for any IObject that overlaps the
            // entry's bbox by IoU > 0.5 (a tighter threshold than the
            // per-group path because we have no range bbox to lean on). Matched
            // chunks are removed and the formula is spliced in at the first
            // removed slot, sized to the largest font size among the matched
            // chunks (or 12 pt as a sensible default). When nothing matched,
            // build a default 12 pt CustomSemanticParagraph from the entry
            // itself and append it to pageContents.
            for (OcrEntry entry : latexEntries) {
                if (entry.consumed) {
                    continue;
                }
                applyPageLevelEntryFallback(pageContents, entry);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "Page-level OCR: failed to process page " + (pageNumber + 1), e);
            restoreAllGroups(pageContents, candidates);
        } finally {
            rendered.imageFile.delete();
        }
    }

    /**
     * Fallback path for {@link #applyPageLevelOcr}: a LaTeX entry that no
     * candidate range picked up gets one more chance to find chunks to
     * replace in {@code pageContents}. Rather than drop the formula on
     * the floor, we:
     * <ol>
     *   <li>walk {@code pageContents} and drop any IObject that overlaps the
     *       entry's bbox by IoU > 0.5 (a tighter threshold than the
     *       per-group path because we have no range bbox to lean on);</li>
     *   <li>if anything was dropped, splice a {@link CustomSemanticParagraph}
     *       in the first removed slot, sized to the largest font size of
     *       the matched chunks (or 12 pt as a sensible default);</li>
     *   <li>otherwise, build a default 12 pt {@link CustomSemanticParagraph}
     *       directly from the entry and append it to pageContents.</li>
     * </ol>
     *
     * <p>Note: this method has no upper bound on the number of unmatched LaTeX entries —
     * every LaTeX entry returned by whole-page OCR will be appended as a formula. Add a
     * per-page counter here if a limit is required.</p>
     */
    private static void applyPageLevelEntryFallback(List<IObject> pageContents, OcrEntry entry) {
        // 1. Walk pageContents backwards and remove every IObject whose
        //    bbox overlaps the entry by IoU > 0.5. Track the largest
        //    font size so we can size the replacement appropriately.
        double matchedFontSize = 0D;
        int insertIndex = -1;
        for (int i = pageContents.size() - 1; i >= 0; i--) {
            IObject obj = pageContents.get(i);
            BoundingBox objBox = obj.getBoundingBox();
            if (objBox == null || objBox.isEmpty()) {
                continue;
            }
            BoundingBox intersection = entry.bbox.cross(objBox);
            if (intersection == null || intersection.isEmpty()) {
                continue;
            }
            double interArea = intersection.getArea();
            if (interArea <= 0) {
                continue;
            }
            double objArea = objBox.getArea();
            double entryArea = entry.bbox.getArea();
            if (objArea <= 0 || entryArea <= 0) {
                continue;
            }
            double unionArea = objArea + entryArea - interArea;
            if (unionArea <= 0) {
                continue;
            }
            double iou = interArea / unionArea;
            if (iou > PAGE_LEVEL_ENTRY_IOU_THRESHOLD) {
                if (insertIndex < 0) {
                    // The first element we remove (lowest index, because we
                    // walk backwards) is where the formula should land.
                    insertIndex = i;
                }
                if (obj instanceof CustomSemanticParagraph) {
                    double fs = ((CustomSemanticParagraph) obj).getFontSize();
                    if (fs > matchedFontSize) {
                        matchedFontSize = fs;
                    }
                }
                pageContents.remove(i);
            }
        }

        // 2. Pick the font size. Prefer the largest size of the matched
        //    elements; fall back to 12 pt when nothing matched.
        double fontSize = matchedFontSize > 0 ? matchedFontSize : DEFAULT_FORMULA_FONT_SIZE;

        // 3. Build the CustomSemanticParagraph that the OCR entry describes
        //    (text + bbox). This is the chunk that ends up in pageContents
        //    whether the IoU pass matched anything or not.
        CustomSemanticParagraph formula = new CustomSemanticParagraph();
        formula.setBoundingBox(new BoundingBox(entry.bbox));
        String wrapped = "$$" + (entry.text == null ? "" : entry.text.replace("$", "").trim()) + "$$";
        TextLine line = new TextLine();
        TextChunk chunk = new TextChunk(wrapped);
        chunk.setFontSize(fontSize);
        line.getTextChunks().add(chunk);
        formula.addTextLine(line);

        if (insertIndex < 0) {
            pageContents.add(formula);
        } else {
            pageContents.add(insertIndex, formula);
        }
    }

    // ------------------------------------------------------------------
    // Mutators on the already-merged pageContents
    // ------------------------------------------------------------------

    /**
     * Replace the merged ImageChunk for {@code range} with the original group
     * elements (used when OCR succeeded but no formula was detected — same
     * behaviour as the legacy "undo merge" path).
     */
    private static void replaceMergedChunkWithGroup(List<IObject> pageContents, CandidateRange range) {
        List<IObject> restored = new ArrayList<>(range.group);
        restored.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
        replaceMergedChunkWithGroupOrdered(pageContents, range, restored);
    }

    private static void replaceMergedChunkWithObject(List<IObject> pageContents, CandidateRange range,
                                                      IObject replacement) {
        List<IObject> single = new ArrayList<>(1);
        single.add(replacement);
        replaceMergedChunkWithGroupOrdered(pageContents, range, single);
    }

    private static void replaceMergedChunkWithGroupOrdered(List<IObject> pageContents, CandidateRange range,
                                                            List<IObject> replacement) {
        for (int i = 0; i < pageContents.size(); i++) {
            if (pageContents.get(i) == range.imageChunk) {
                pageContents.remove(i);
                pageContents.addAll(i, replacement);
                return;
            }
        }
    }

    /**
     * Restore every merged ImageChunk from the candidate groups back to the original group elements.
     * Called by the page-level OCR failure paths before returning, so that "OCR failed, restore original text"
     * behaves the same as the per-group path.
     */
    private static void restoreAllGroups(List<IObject> pageContents, List<CandidateRange> candidates) {
        for (CandidateRange range : candidates) {
            replaceMergedChunkWithGroup(pageContents, range);
        }
    }

    // ------------------------------------------------------------------
    // Data carriers
    // ------------------------------------------------------------------

    private static class CandidateRange {
        List<IObject> group;
        BoundingBox union;
        ImageChunk imageChunk;
    }

    private static class OcrEntry {
        final BoundingBox bbox;       // bbox in PDF source coordinates
        final boolean isLatex;
        final String text;
        final Double fontSizeHint;
        boolean consumed;

        OcrEntry(BoundingBox bbox, boolean isLatex, String text, Double fontSizeHint) {
            this.bbox = bbox;
            this.isLatex = isLatex;
            this.text = text;
            this.fontSizeHint = fontSizeHint;
        }
    }

    private static class RenderedPage {
        final File imageFile;
        final int imageWidth;
        final int imageHeight;

        RenderedPage(File imageFile, int imageWidth, int imageHeight) {
            this.imageFile = imageFile;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    // ------------------------------------------------------------------
    // OCR post-processing helpers
    // ------------------------------------------------------------------

    /**
     * Convert paddle's pixel-space positions back to PDF user units and keep
     * only entries whose text looks like LaTeX. Non-LaTeX entries are kept
     * here so we can match IoU against any candidate range; the caller then
     * decides whether to keep them based on {@code isLatex}.
     */
    private static List<OcrEntry> buildOcrEntries(TextInOcrAnalysisResultDto ocr,
                                                  double sourceWidth, double sourceHeight,
                                                  int imageWidth, int imageHeight, int pageNumber) {
        List<OcrEntry> out = new ArrayList<>();
        if (imageWidth <= 0 || imageHeight <= 0) {
            return out;
        }
        double widthRatio = sourceWidth / imageWidth;
        double heightRatio = sourceHeight / imageHeight;

        for (TextInOcrDetailDto detail : ocr.getDetail()) {
            List<Double> pos = detail.getPosition();
            if (pos == null || pos.size() < 8) {
                continue;
            }
            double leftX0 = pos.get(0) * widthRatio;
            double leftY0 = pos.get(1) * heightRatio;
            double leftX1 = pos.get(2) * widthRatio;
            double rightY1 = pos.get(7) * heightRatio;

            // Paddle coordinates: y increases downward; PDF: y increases upward.
            BoundingBox bbox = new BoundingBox(pageNumber, leftX0, sourceHeight - rightY1,
                leftX1, sourceHeight - leftY0);

            String text = detail.getText();
            boolean isLatex = text != null && isLatexExpression(text);

            Double fontSizeHint = null;
            if (detail.getOutlineLevel() != null) {
                switch (detail.getOutlineLevel()) {
                    case 0: fontSizeHint = 13D; break;
                    case 1: fontSizeHint = 12D; break;
                    case 2: fontSizeHint = 11D; break;
                    default: fontSizeHint = 10D;
                }
            }

            out.add(new OcrEntry(bbox, isLatex, text, fontSizeHint));
        }
        return out;
    }

    /**
     * Find the OCR entry with the highest IoU against {@code candidate}.
     * Returns {@code null} if no entry exceeds {@code iouThreshold}.
     */
    private static OcrEntry findBestIouMatch(BoundingBox candidate, List<OcrEntry> entries, double iouThreshold) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        double candArea = candidate.getArea();
        if (candArea <= 0) {
            return null;
        }
        OcrEntry best = null;
        double bestIou = iouThreshold;
        for (OcrEntry e : entries) {
            if (e.consumed) {
                continue;
            }
            BoundingBox intersection = candidate.cross(e.bbox);
            if (intersection == null) {
                continue;
            }
            double interArea = intersection.getArea();
            if (interArea <= 0) {
                continue;
            }
            double entryArea = e.bbox.getArea();
            if (entryArea <= 0) {
                continue;
            }
            double unionArea = candArea + entryArea - interArea;
            if (unionArea <= 0) {
                continue;
            }
            double iou = interArea / unionArea;
            if (iou > bestIou) {
                bestIou = iou;
                best = e;
            }
        }
        return best;
    }

    private static TextInOcrAnalysisResultDto drainFuture(Future<TextInOcrAnalysisResultDto> future) {
        try {
            return future.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            LOGGER.log(Level.WARNING, "Paddle OCR task timed out", te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Paddle OCR task interrupted", ie);
        } catch (ExecutionException ee) {
            LOGGER.log(Level.WARNING, "Paddle OCR task failed", ee.getCause());
        }
        return null;
    }

    private static RenderedPage renderPageToImage(String pdfPath, int pageNumber) throws IOException {
        File source = new File(pdfPath);
        String baseName = source.getName();
        int dotIdx = baseName.lastIndexOf('.');
        String stem = (dotIdx > 0) ? baseName.substring(0, dotIdx) : baseName;
        File outputFile = new File(System.getProperty("java.io.tmpdir"),
            stem + "-page-" + pageNumber + "-" + UUID.randomUUID() + "-lineart-page.png");
        try (PDDocument sourceDoc = Loader.loadPDF(source)) {
            PDFRenderer renderer = new PDFRenderer(sourceDoc);
            BufferedImage pageImage = renderer.renderImageWithDPI(pageNumber, PAGE_LEVEL_RENDER_DPI);
            ImageIO.write(pageImage, "PNG", outputFile);
            return new RenderedPage(outputFile, pageImage.getWidth(), pageImage.getHeight());
        }
    }

    // ------------------------------------------------------------------
    // Legacy formula-text helpers (kept identical to the previous version
    // for behavioural compatibility on the per-group path).
    // ------------------------------------------------------------------

    private static TextChunk tryCreateFormulaTextChunk(TextInOcrAnalysisResultDto resultDto, BoundingBox bbox) {
        if (resultDto == null || resultDto.getDetail() == null || resultDto.getDetail().isEmpty()) {
            return null;
        }
        TextInOcrDetailDto detail = resultDto.getDetail().get(0);
        if (!"paragraph".equals(detail.getType())) {
            return null;
        }
        String text = detail.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!isLatexExpression(text)) {
            return null;
        }
        text = text.replace("$", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        text = "$$" + text + "$$";
        return createTextChunk(text, bbox);
    }

    private static TextChunk createTextChunk(String text, BoundingBox bbox) {
        TextChunk textChunk = new TextChunk(text);
        textChunk.setBoundingBox(new BoundingBox(bbox));
        textChunk.setFontSize(bbox.getHeight());
        textChunk.setBaseLine(bbox.getCenterY());
        return textChunk;
    }

    private static boolean isLatexExpression(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("$") || trimmed.endsWith("$") || trimmed.contains("$$")) {
            return true;
        }
        if (LATEX_COMMAND_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (LATEX_SUBSUP_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        return LATEX_SIMPLE_SUBSUP_PATTERN.matcher(trimmed).find();
    }
}
