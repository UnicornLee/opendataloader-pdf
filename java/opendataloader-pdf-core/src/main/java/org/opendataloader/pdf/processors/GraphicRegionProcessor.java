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

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.custom.utils.CustomChunksMergeUtils;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures vector-graphic regions (company logos, seals, signatures, decorative
 * marks) as a single screenshot.
 *
 * <h2>Why this processor exists</h2>
 * <p>A logo drawn as vector art reaches the pipeline as a {@link LineArtChunk}
 * plus a set of raw {@link LineChunk}s. None of the existing processors claims
 * such a region:</p>
 * <ul>
 *   <li>{@code BarChartProcessor} / {@code PieChartProcessor} /
 *       {@code LineChartProcessor} key off {@code bar_chart} / {@code pie_chart}
 *       / {@code line_chart} shapes, which a logo never produces;</li>
 *   <li>{@code FlowchartProcessor} rejects the wide-and-short proportions of a
 *       logo lockup (measured 401.5 x 47.6 pt, aspect 8.44, against a limit of
 *       {@code MAX_ASPECT_RATIO = 6.0});</li>
 *   <li>{@code LineArtProcessor} only accepts thin, narrow line art as a merge
 *       seed ({@code height <= 3 && width <= 300}), because it exists to find
 *       formula fragments, and it restores the original elements anyway.</li>
 * </ul>
 * <p>Because {@code JsonWriter} and every serializer skip {@code LineArtChunk}
 * and {@code ShapeChunk}, such a region produced <em>no output at all</em>: the
 * logo was neither an image nor text in the JSON.</p>
 *
 * <h2>What the screenshot box is taken from</h2>
 * <p>The box is the {@link LineArtChunk}'s own bounding box. That is deliberate.
 * When the document is parsed without a structure tree
 * ({@code StaticStorages.setIsIgnoreMCIDs(true)}), veraPDF unions
 * <em>every</em> line-art box of the page into a single {@code LineArtChunk},
 * and filled paths and curves contribute their box only, without producing a
 * {@code LineChunk}. On a measured one-page announcement the logo mark was
 * therefore present <em>only</em> in that union box: the union of all
 * {@code LineChunk} and {@code ShapeChunk} boxes started at x = 159.9 pt and
 * cut the mark off entirely, while the {@code LineArtChunk} box
 * (x0 = 97.0 pt) covered the complete lockup. Re-clustering the raw lines would
 * have silently dropped it.</p>
 *
 * <h2>Gates</h2>
 * <p>The LineArtChunk granularity is far too coarse to trust on its own — one
 * box can span a whole page — so a region is only captured when it looks like a
 * graphic rather than page furniture:</p>
 * <ol>
 *   <li><b>Size</b> — at least {@link #MIN_REGION_WIDTH} x
 *       {@link #MIN_REGION_HEIGHT} pt, and an aspect ratio within
 *       {@link #MAX_ASPECT_RATIO}. The limit is deliberately looser than
 *       {@code FlowchartProcessor}'s: a logo lockup is wide and short.</li>
 *   <li><b>Page share</b> — at most {@link #MAX_PAGE_AREA_RATIO} of the page,
 *       so a page-spanning line-art union cannot swallow the text layer.</li>
 *   <li><b>Not a table</b> — the box must not overlap a detected
 *       {@code TableBorder}; table rules are line art too.</li>
 *   <li><b>Density</b> — the box must contain at least
 *       {@link #MIN_RAW_LINE_COUNT} raw lines or
 *       {@link #MIN_SHAPE_COMPONENTS} shape components. This is what keeps
 *       header rules, section dividers and table borders out: those are one or
 *       two strokes.</li>
 *   <li><b>No full-span stroke</b> — no raw line or non-group shape may span
 *       {@link #FULL_SPAN_RULE_RATIO} of the region <em>while staying thin</em>
 *       ({@link #RULE_MAX_THICKNESS_RATIO}). A graphic is a cluster of short
 *       strokes; a table grid is one long rule. The thinness half of the test
 *       matters: a pictorial mark is enclosed by one outline path whose box is
 *       the mark itself, and that box is by construction square-ish, so a rule
 *       measured only by length would veto every house / shield / roundel logo
 *       (measured: the 51.2 x 48.3 pt 屋 house mark of a 2007 announcement, whose
 *       51.03 x 47.67 pt outline spans 99.6 % of its own region).</li>
 *   <li><b>No text</b> — a text block intersecting the box vetoes the capture.
 *       The text layer is never emptied here, so cropping a region that still
 *       holds text would render that text twice; and a vector-graphic region is
 *       by definition drawn without text objects, so any text inside it means the
 *       box is really table rows or body text (see
 *       {@link #TEXT_VETO_MIN_OVERLAP_SHARE}).</li>
 * </ol>
 *
 * <h2>Fragment clustering</h2>
 * <p>Every gate above measures <em>one</em> {@code LineArtChunk}, but a tagged PDF
 * wraps each part of a logo lockup in its own marked group, so the lockup reaches
 * this processor as several small boxes. Measured on a three-page announcement
 * that compares the old and the new company logo: the old lockup arrived as
 * <ul>
 *   <li>a 43.9 x 49.2 pt pictorial mark (3 raw lines, 3 shape components),</li>
 *   <li>a 23.5 x 23.9 pt glyph outline (below {@link #MIN_REGION_WIDTH}), and</li>
 *   <li>a 124.3 x 44.7 pt wordmark (6 raw lines, 1 shape component),</li>
 * </ul>
 * <p>and each fragment was vetoed on its own while the whole lockup — 170.6 x 50.2 pt
 * with 10 raw lines inside — passes every gate. Closely placed boxes are
 * therefore agglomerated into one region before the gates are applied
 * ({@link #clusterFragments}); the envelope is judged first and, when it is
 * vetoed, the members fall back to being judged individually, so a region that
 * is captured today is never lost by the clustering.</p>
 *
 * <h2>Inline marks</h2>
 * <p>The gates above size regions in <em>paragraph</em> terms, so the small vector
 * mark set in front of a company name — measured: a 23.21 x 23.77 pt pictorial
 * signature ahead of “新百利有限公司” — is rejected by {@link #MIN_REGION_WIDTH}
 * and by the density gate at once and disappears without trace. Such a mark is not
 * a region of its own right: it belongs to the text line it is set into. A
 * separate pass therefore claims a box that is small, dense, mark-shaped and
 * directly adjacent to one text line, screenshots it, and injects the result as an
 * inline {@code <img>} chunk of that line — the same shape
 * {@link TextLineProcessor} already produces for raster figures on a text line
 * ({@link #captureInlineMark}). It runs before the clustering so a consumed mark
 * cannot also be captured as a region.</p>
 *
 * <h2>Position in the pipeline</h2>
 * <p>Runs at the end of Loop 4, after the chart and flowchart passes (which
 * consume the regions they own) and before
 * {@link ConsecutiveImageProcessor#processConsecutiveImages} (so adjacent logo
 * and figure screenshots still merge). Unconditional, like the chart and
 * flowchart passes: there is no configuration switch.</p>
 */
public final class GraphicRegionProcessor {

    private static final Logger LOGGER = Logger.getLogger(GraphicRegionProcessor.class.getCanonicalName());

    /** Minimum width (pt) of a graphic region; below this it is a rule, not a graphic. */
    private static final double MIN_REGION_WIDTH = 24.0;
    /** Minimum height (pt) of a graphic region; below this it is a rule, not a graphic. */
    private static final double MIN_REGION_HEIGHT = 12.0;
    /**
     * Largest accepted width/height (or height/width) ratio. Much looser than
     * {@code FlowchartProcessor.MAX_ASPECT_RATIO} (6.0) on purpose: a logo
     * lockup pairs a small mark with a long wordmark, measured 401.5 x 47.6 pt
     * (ratio 8.44) on a real announcement.
     */
    private static final double MAX_ASPECT_RATIO = 20.0;
    /**
     * Largest share of the page area a graphic region may cover. Guards against
     * the ignore-MCIDs union box spanning a text-heavy page.
     */
    private static final double MAX_PAGE_AREA_RATIO = 0.30;
    /** Raw {@link LineChunk}s required inside the box when no shape qualifies. */
    private static final int MIN_RAW_LINE_COUNT = 8;
    /** {@link ShapeChunk} components required inside the box when too few lines qualify. */
    private static final int MIN_SHAPE_COMPONENTS = 8;
    /**
     * Share of the region's own long side that a single stroke must span before the
     * region is treated as ruled structure rather than a graphic.
     * <p>
     * A vector-graphic region is a cluster of short strokes: on the measured logo
     * lockup the longest raw line was 15.7 pt and the largest non-group shape
     * 22.0 pt against a 401.5 pt box, i.e. under 6 % of it. A table row band is the
     * opposite — its rule or its polyline runs the full width of the band (measured
     * 506 pt against a 506 pt box) and the band holds nothing else, so no text veto
     * can catch it. Requiring every stroke to be short keeps such bands out.
     */
    private static final double FULL_SPAN_RULE_RATIO = 0.80;
    /**
     * Largest short/long side ratio a stroke may have and still count as a rule.
     * <p>
     * {@link #FULL_SPAN_RULE_RATIO} alone asks only "how long is it relative to the
     * region", which cannot tell a table rule from the outline of a pictorial mark:
     * both are the widest thing in the box. It can tell them apart by thickness — a
     * rule is a line, so its box is long and narrow (measured row bands: 506 x 141 pt
     * and 506 x 242 pt, ratios 0.28 and 0.48), while an enclosing outline has a box as
     * tall as it is wide (the 屋 house mark: 51.03 x 47.67 pt, ratio 0.93).
     */
    private static final double RULE_MAX_THICKNESS_RATIO = 0.60;
    /**
     * Smallest share of the smaller of the two boxes that the intersection between
     * the region and a text block must cover before the text vetoes the capture.
     * <p>
     * Deliberately tiny. A vector-graphic region is drawn as vectors and carries no
     * text object at all — measured on a real announcement logo, the whole lockup
     * (mark plus both wordmarks) had zero text chunks intersecting it. A ruled table
     * row does carry text, but the text occupies only the middle third of the row
     * band, so an intersection measured against the *region* would score ~0.3 and let
     * every row through: that produced 15 bogus screenshots on one wireless-table
     * document. Measuring against the smaller box and using a near-zero threshold
     * keeps slivers of an adjacent line from vetoing a real logo while rejecting any
     * band that actually holds words.
     */
    private static final double TEXT_VETO_MIN_OVERLAP_SHARE = 0.05;

    /**
     * Smallest gap (pt) at which two line-art boxes are still fragments of one
     * graphic. A lockup's mark, wordmark and glyph outlines sit within a few points
     * of each other; the measured old logo separated them by 2.4 - 3.1 pt.
     */
    private static final double FRAGMENT_GAP_MIN = 6.0;
    /**
     * Gap tolerance as a share of the smaller fragment's short side, on top of
     * {@link #FRAGMENT_GAP_MIN}: a large mark may keep a wider distance to its
     * wordmark than two small glyphs do.
     */
    private static final double FRAGMENT_GAP_RATIO = 0.25;
    /**
     * Share of the smaller box an envelope may cover before it is considered to
     * have grown over a raster image rather than around vector art.
     */
    private static final double IMAGE_VETO_MIN_OVERLAP_SHARE = 0.05;

    /**
     * Smallest side (pt) of an inline mark. Below this the box is a bullet, a
     * dagger or a punctuation glyph drawn as line art, not a company mark.
     */
    private static final double INLINE_MARK_MIN_SIZE = 8.0;
    /**
     * Largest side (pt) of an inline mark. The mark is set into a text line, so it
     * stays within a few times the line height; the 屋 house logo (51.2 x 48.3 pt)
     * is a region in its own right and must go through the regular gates instead.
     */
    private static final double INLINE_MARK_MAX_SIZE = 40.0;
    /** Aspect ratio beyond which the box is a rule or a bar rather than a mark. */
    private static final double INLINE_MARK_MAX_ASPECT_RATIO = 4.0;
    /**
     * Largest horizontal distance (pt) between the mark and the text line it may be
     * set into. Measured on the 新百利有限公司 lockup: 4.38 pt. Anything further
     * away is a separate object on the page, not an initial of that line.
     */
    private static final double INLINE_MARK_MAX_GAP = 12.0;
    /**
     * Strokes (raw lines or shape components) required inside an inline mark.
     * Lower than {@link #MIN_RAW_LINE_COUNT} because a mark is a fraction of the
     * size of a lockup: measured 7 lines and 5 shape components for the
     * 新百利有限公司 signature, against a region gate of 8 for both.
     */
    private static final int INLINE_MARK_MIN_STROKES = 4;

    private GraphicRegionProcessor() {
    }

    /**
     * Replaces every qualifying vector-graphic region of the page with a single
     * {@link ImageChunk}.
     *
     * @param pageContents the current page contents; the captured regions are
     *                     removed from it and the screenshots inserted in their
     *                     place
     * @param pageNumber   0-based page number (used for logging)
     * @param imagesUtils  image renderer / saver
     * @param rawLines     the page's raw line geometry as returned by
     *                     {@code DocumentProcessor#collectRawLineChunks}; the
     *                     plain line layer was dropped from {@code pageContents}
     *                     in Loop 2, so the density gate has to read it back
     * @param pageWidth    page width (pt), used by the page-share gate; values
     *                     {@code <= 0} disable that gate
     * @param pageHeight   page height (pt), used by the page-share gate; values
     *                     {@code <= 0} disable that gate
     */
    public static void processGraphicRegions(List<IObject> pageContents, int pageNumber,
                                             ImagesUtils imagesUtils, List<IObject> rawLines,
                                             double pageWidth, double pageHeight) {
        if (pageContents == null || pageContents.isEmpty() || imagesUtils == null) {
            return;
        }
        List<LineArtChunk> candidates = new ArrayList<>();
        for (IObject content : pageContents) {
            if (content instanceof LineArtChunk) {
                candidates.add((LineArtChunk) content);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Top-to-bottom so a page with several graphics reports and captures them
        // in reading order.
        candidates.sort(Comparator.comparingDouble(IObject::getTopY).reversed());

        // Marks that belong to a text line are claimed first: they are too small for
        // the region gates below, and once inlined they must not be judged again.
        List<LineArtChunk> regions = new ArrayList<>();
        for (LineArtChunk candidate : candidates) {
            if (!pageContents.contains(candidate)) {
                continue;
            }
            if (!captureInlineMark(candidate, pageContents, imagesUtils, rawLines, pageNumber)) {
                regions.add(candidate);
            }
        }
        if (regions.isEmpty()) {
            return;
        }
        List<List<LineArtChunk>> clusters = clusterFragments(regions, pageWidth, pageHeight);

        List<String> skipped = new ArrayList<>();
        for (List<LineArtChunk> cluster : clusters) {
            if (cluster.size() > 1) {
                // Judge the lockup as a whole first: individually vetoed fragments often
                // add up to a region that clears the density gate.
                BoundingBox envelope = unionBox(cluster);
                String envelopeVeto = envelopeVetoReason(envelope, pageContents, rawLines,
                    pageWidth, pageHeight);
                if (envelopeVeto == null) {
                    captureRegion(pageContents, cluster, envelope, imagesUtils, pageNumber);
                    continue;
                }
                skipped.add(describe(envelope) + " (cluster of " + cluster.size()
                    + ": " + envelopeVeto + ")");
                // Fall through: a fragment that passes on its own is still worth capturing.
            }
            for (LineArtChunk candidate : cluster) {
                // A previous capture may already have removed this chunk.
                if (!pageContents.contains(candidate)) {
                    continue;
                }
                BoundingBox box = candidate.getBoundingBox();
                String veto = findVetoReason(box, pageContents, rawLines, pageWidth, pageHeight);
                if (veto != null) {
                    if (cluster.size() == 1) {
                        skipped.add(describe(box) + " (" + veto + ")");
                    }
                    continue;
                }
                captureRegion(pageContents, Collections.singletonList(candidate), box,
                    imagesUtils, pageNumber);
            }
        }
        if (!skipped.isEmpty()) {
            LOGGER.log(Level.INFO, "Page {0}: skipped {1} vector line-art region(s): {2}",
                new Object[]{pageNumber + 1, skipped.size(), String.join("; ", skipped)});
        }
    }

    /**
     * Agglomerates the page's line-art boxes into lockups.
     *
     * <p>Boxes are walked top-to-bottom and appended to the first existing cluster
     * whose envelope still touches them ({@link #fragmentsTouch}); a box that touches
     * none opens a cluster of its own. Growth is refused as soon as the resulting
     * envelope stops looking like a graphic ({@link #passesGeometryGates}), which is
     * what keeps a page of table rules — itself one long chain of touching boxes —
     * from being folded into a single page-spanning region.</p>
     *
     * @param candidates the page's line-art boxes, sorted top-to-bottom
     * @return one list per lockup, never empty, order preserved
     */
    private static List<List<LineArtChunk>> clusterFragments(List<LineArtChunk> candidates,
                                                             double pageWidth, double pageHeight) {
        List<List<LineArtChunk>> clusters = new ArrayList<>();
        List<BoundingBox> envelopes = new ArrayList<>();
        for (LineArtChunk candidate : candidates) {
            BoundingBox box = candidate.getBoundingBox();
            if (box == null || box.isEmpty()) {
                continue;
            }
            boolean appended = false;
            for (int i = 0; i < clusters.size(); i++) {
                BoundingBox envelope = envelopes.get(i);
                if (!fragmentsTouch(envelope, box) || !passesGeometryGates(unionBox(envelope, box),
                    pageWidth, pageHeight)) {
                    continue;
                }
                clusters.get(i).add(candidate);
                envelopes.set(i, unionBox(envelope, box));
                appended = true;
                break;
            }
            if (!appended) {
                List<LineArtChunk> cluster = new ArrayList<>();
                cluster.add(candidate);
                clusters.add(cluster);
                envelopes.add(new BoundingBox(box));
            }
        }
        return clusters;
    }

    /**
     * Why the envelope of a multi-fragment lockup must not be captured, or {@code null}
     * when it may. Adds {@link #overlapsImage} to the regular gates: unlike a single
     * box, an envelope is grown by design, so it has to be checked for reaching into a
     * neighbouring raster figure — whose {@link ImageChunk} stays in the text flow and
     * would then be rendered twice.
     */
    private static String envelopeVetoReason(BoundingBox envelope, List<IObject> pageContents,
                                             List<IObject> rawLines, double pageWidth,
                                             double pageHeight) {
        if (envelope == null) {
            return "empty bounding box";
        }
        String veto = findVetoReason(envelope, pageContents, rawLines, pageWidth, pageHeight);
        if (veto != null) {
            return veto;
        }
        if (overlapsImage(envelope, pageContents)) {
            return "envelope reaches into a raster image";
        }
        return null;
    }

    /**
     * True when an {@link ImageChunk} sits (nearly) inside {@code box}, i.e. when
     * cropping {@code box} would duplicate content the page already reports as an image.
     */
    private static boolean overlapsImage(BoundingBox box, List<IObject> pageContents) {
        for (IObject content : pageContents) {
            if (!(content instanceof ImageChunk)) {
                continue;
            }
            BoundingBox imageBox = content.getBoundingBox();
            if (imageBox == null || imageBox.isEmpty() || !box.overlaps(imageBox)) {
                continue;
            }
            BoundingBox intersection = box.cross(imageBox);
            double smaller = Math.min(box.getArea(), imageBox.getArea());
            if (intersection != null && smaller > 0
                && intersection.getArea() / smaller > IMAGE_VETO_MIN_OVERLAP_SHARE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cheap geometry gates shared by the clustering and {@link #findVetoReason}: a box
     * that fails them can never be captured, so the clustering must not grow one.
     */
    private static boolean passesGeometryGates(BoundingBox box, double pageWidth, double pageHeight) {
        if (box == null || box.isEmpty()) {
            return false;
        }
        double width = box.getWidth();
        double height = box.getHeight();
        if (width < MIN_REGION_WIDTH || height < MIN_REGION_HEIGHT) {
            return false;
        }
        if (Math.max(width / height, height / width) > MAX_ASPECT_RATIO) {
            return false;
        }
        if (pageWidth > 0 && pageHeight > 0) {
            double share = box.getArea() / (pageWidth * pageHeight);
            return !Double.isFinite(share) || share <= MAX_PAGE_AREA_RATIO;
        }
        return true;
    }

    /**
     * True when {@code a} and {@code b} overlap once both are widened by the fragment
     * gap tolerance, so two pieces of one lockup are recognised across the hairline
     * between a mark and its wordmark.
     */
    private static boolean fragmentsTouch(BoundingBox a, BoundingBox b) {
        double tolerance = Math.max(FRAGMENT_GAP_MIN,
            FRAGMENT_GAP_RATIO * Math.min(Math.min(a.getWidth(), a.getHeight()),
                Math.min(b.getWidth(), b.getHeight())));
        return a.getLeftX() - tolerance <= b.getRightX()
            && b.getLeftX() - tolerance <= a.getRightX()
            && a.getBottomY() - tolerance <= b.getTopY()
            && b.getBottomY() - tolerance <= a.getTopY();
    }

    private static BoundingBox unionBox(List<LineArtChunk> cluster) {
        BoundingBox union = null;
        for (LineArtChunk member : cluster) {
            union = union == null ? new BoundingBox(member.getBoundingBox())
                : union.union(member.getBoundingBox());
        }
        return union;
    }

    private static BoundingBox unionBox(BoundingBox a, BoundingBox b) {
        return new BoundingBox(a).union(b);
    }

    /**
     * Returns why {@code box} must not be captured, or {@code null} when it may be.
     * Gates run cheapest-first so the common rejects cost almost nothing.
     */
    private static String findVetoReason(BoundingBox box, List<IObject> pageContents,
                                         List<IObject> rawLines, double pageWidth, double pageHeight) {
        if (box == null || box.isEmpty()) {
            return "empty bounding box";
        }
        double width = box.getWidth();
        double height = box.getHeight();
        if (width < MIN_REGION_WIDTH || height < MIN_REGION_HEIGHT) {
            return String.format("smaller than %.0fx%.0f pt (%.1fx%.1f)",
                MIN_REGION_WIDTH, MIN_REGION_HEIGHT, width, height);
        }
        if (Math.max(width / height, height / width) > MAX_ASPECT_RATIO) {
            return String.format("aspect ratio %.2f above %.0f",
                Math.max(width / height, height / width), MAX_ASPECT_RATIO);
        }
        if (pageWidth > 0 && pageHeight > 0) {
            double share = box.getArea() / (pageWidth * pageHeight);
            if (Double.isFinite(share) && share > MAX_PAGE_AREA_RATIO) {
                return String.format("covers %.0f%% of the page, above %.0f%%",
                    share * 100, MAX_PAGE_AREA_RATIO * 100);
            }
        }
        if (overlapsTable(box)) {
            return "overlaps a table";
        }
        int lineCount = countLinesInside(box, rawLines);
        int shapeComponents = countShapeComponentsInside(box, pageContents);
        if (lineCount < MIN_RAW_LINE_COUNT && shapeComponents < MIN_SHAPE_COMPONENTS) {
            return String.format("too sparse (%d line(s), %d shape component(s))",
                lineCount, shapeComponents);
        }
        if (hasFullSpanStroke(box, rawLines, pageContents)) {
            return "ruled structure (a rule spans the region), not a graphic";
        }
        if (hasVetoingText(box, pageContents)) {
            return "text would be rendered twice";
        }
        return null;
    }

    /**
     * True when one stroke spans (nearly) the whole region, which means the region
     * is ruled structure — a table grid, a row band, a section divider — rather than
     * a cluster of strokes forming a graphic.
     *
     * <p>Both carriers count. A wireless table is usually drawn as a few
     * {@code polyline} shapes whose own bounding box is as wide as the text column
     * (measured on a 10-page disclosure: page 7 carried four polylines of
     * 506 x 141–242 pt), so checking only the raw {@link LineChunk}s misses it
     * entirely. {@link ShapeChunk#TYPE_GROUP} is exempt: a group is the envelope of
     * several separate parts rather than one stroke, and the logo lockup carries a
     * group of its own spanning 71 % of its box.</p>
     */
    private static boolean hasFullSpanStroke(BoundingBox box, List<IObject> rawLines,
                                             List<IObject> pageContents) {
        double boxSpan = Math.max(box.getWidth(), box.getHeight());
        if (boxSpan <= 0) {
            return false;
        }
        if (rawLines != null) {
            for (IObject line : rawLines) {
                if (line instanceof LineArtChunk) {
                    continue;
                }
                if (spansRegion(box, line.getBoundingBox(), boxSpan)) {
                    return true;
                }
            }
        }
        for (IObject content : pageContents) {
            if (!(content instanceof ShapeChunk)) {
                continue;
            }
            ShapeChunk shape = (ShapeChunk) content;
            if (ShapeChunk.TYPE_GROUP.equals(shape.getShapeType())) {
                continue;
            }
            if (spansRegion(box, shape.getBoundingBox(), boxSpan)) {
                return true;
            }
        }
        return false;
    }

    private static boolean spansRegion(BoundingBox box, BoundingBox candidate, double boxSpan) {
        if (candidate == null || candidate.isEmpty() || !box.overlaps(candidate)) {
            return false;
        }
        double longSide = Math.max(candidate.getWidth(), candidate.getHeight());
        double shortSide = Math.min(candidate.getWidth(), candidate.getHeight());
        if (longSide / boxSpan < FULL_SPAN_RULE_RATIO) {
            return false;
        }
        return shortSide / longSide <= RULE_MAX_THICKNESS_RATIO;
    }

    /**
     * True when the box overlaps a table detected by the line preprocessing.
     * Table rules are line art as well, so a line-art union that reaches into a
     * table must not be captured as a graphic.
     */
    private static boolean overlapsTable(BoundingBox box) {
        if (StaticContainers.getTableBordersCollection() == null) {
            return false;
        }
        try {
            return StaticContainers.getTableBordersCollection().getTableBorder(box) != null;
        } catch (RuntimeException e) {
            // The table-border lookup is best effort; a failure here must not
            // take the whole page down.
            LOGGER.log(Level.WARNING, "Table-border lookup failed for a vector line-art region", e);
            return false;
        }
    }

    private static int countLinesInside(BoundingBox box, List<IObject> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (IObject line : rawLines) {
            if (line instanceof LineArtChunk) {
                // The bbox-only container itself is the region under test, not evidence.
                continue;
            }
            BoundingBox lineBox = line.getBoundingBox();
            if (lineBox != null && !lineBox.isEmpty() && box.overlaps(lineBox)) {
                count++;
            }
        }
        return count;
    }

    private static int countShapeComponentsInside(BoundingBox box, List<IObject> pageContents) {
        int components = 0;
        for (IObject content : pageContents) {
            if (!(content instanceof ShapeChunk)) {
                continue;
            }
            BoundingBox shapeBox = content.getBoundingBox();
            if (shapeBox == null || shapeBox.isEmpty() || !box.overlaps(shapeBox)) {
                continue;
            }
            components += Math.max(1, ((ShapeChunk) content).getComponentCount());
        }
        return components;
    }

    /**
     * True when a text block sits inside the box. The text layer is never emptied
     * by this processor, so any text left there would appear both in the crop and
     * in the text flow.
     */
    private static boolean hasVetoingText(BoundingBox box, List<IObject> pageContents) {
        for (IObject content : pageContents) {
            if (content instanceof ShapeChunk || content instanceof LineArtChunk
                    || content instanceof LineChunk || content instanceof ImageChunk) {
                continue;
            }
            if (!isTextContent(content)) {
                continue;
            }
            BoundingBox textBox = content.getBoundingBox();
            if (textBox == null || textBox.isEmpty()) {
                continue;
            }
            if (overlapsEnough(box, textBox)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code box} and {@code other} share more than
     * {@link #TEXT_VETO_MIN_OVERLAP_SHARE} of the smaller box's area. Measuring
     * against the smaller box (rather than the region) is what makes a short text
     * line inside a tall row band count as an overlap.
     */
    private static boolean overlapsEnough(BoundingBox box, BoundingBox other) {
        BoundingBox intersection = box.cross(other);
        if (intersection == null || intersection.isEmpty()) {
            return false;
        }
        double smaller = Math.min(box.getArea(), other.getArea());
        return smaller > 0 && intersection.getArea() / smaller > TEXT_VETO_MIN_OVERLAP_SHARE;
    }

    /** True for anything that carries text: raw chunks as well as semantic nodes. */
    private static boolean isTextContent(IObject content) {
        return content instanceof TextChunk
            || content instanceof SemanticTextNode
            || content instanceof CustomSemanticParagraph
            || content instanceof TextLine;
    }

    /**
     * Screenshots the region and swaps it for the resulting {@link ImageChunk}: every
     * {@link LineArtChunk} of the region, every {@link ShapeChunk} the crop fully
     * covers and every content-free {@link TableBorder} it fully covers are removed
     * (the first two never reach the JSON anyway), then the image is inserted in
     * top-to-bottom order.
     *
     * <p>The table clause exists because the line preprocessing reads the strokes of
     * a pictorial mark as rules: on the measured announcement the 屋 character inside
     * the house logo became a 2 x 2 table whose four cells held nothing but the lines
     * that drew them. Leaving that behind would report a blank table where the crop now
     * shows the graphic, so a table with no text, image or nested content of its own is
     * dropped together with the strokes it was made of. A table holding anything
     * readable survives: the crop is never allowed to swallow real content.</p>
     */
    private static void captureRegion(List<IObject> pageContents, List<LineArtChunk> lineArt,
                                      BoundingBox box, ImagesUtils imagesUtils, int pageNumber) {
        ImageChunk imageChunk = new ImageChunk(new BoundingBox(box));
        imagesUtils.saveImageChunk(imageChunk);

        pageContents.removeAll(lineArt);
        pageContents.removeIf(content -> isCoveredBy(box, content) && isRemovableWithRegion(content));

        addInOrder(pageContents, imageChunk);
        LOGGER.log(Level.INFO, "Page {0}: captured vector graphic region {1} as image #{2}",
            new Object[]{pageNumber + 1, describe(box), imageChunk.getIndex()});
    }

    /**
     * True for the page objects a region crop subsumes: the vector carriers it renders
     * itself, plus tables that only restate its rules. Text, images and content-carrying
     * tables are never removed.
     */
    private static boolean isRemovableWithRegion(IObject content) {
        if (content instanceof ShapeChunk || content instanceof LineArtChunk) {
            return true;
        }
        return content instanceof TableBorder && isContentFreeTable((TableBorder) content);
    }

    /** True when {@code box} fully covers {@code content}'s own bounding box. */
    private static boolean isCoveredBy(BoundingBox box, IObject content) {
        BoundingBox contentBox = content.getBoundingBox();
        return contentBox != null && !contentBox.isEmpty() && box.contains(contentBox);
    }

    /**
     * True when no cell of {@code table} holds anything a reader could see — text, an
     * image, or a nested table that itself holds something. Line and shape content is
     * ignored: those are the rules the table was inferred from.
     */
    private static boolean isContentFreeTable(TableBorder table) {
        TableBorderRow[] rows = table.getRows();
        if (rows == null) {
            return true;
        }
        for (TableBorderRow row : rows) {
            TableBorderCell[] cells = row == null ? null : row.getCells();
            if (cells == null) {
                continue;
            }
            for (TableBorderCell cell : cells) {
                List<IObject> cellContents = cell == null ? null : cell.getContents();
                if (cellContents == null) {
                    continue;
                }
                for (IObject content : cellContents) {
                    if (content != null && carriesReadableContent(content)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean carriesReadableContent(IObject content) {
        if (content instanceof ImageChunk) {
            return true;
        }
        if (content instanceof TableBorder) {
            return !isContentFreeTable((TableBorder) content);
        }
        if (content instanceof TextChunk) {
            return !((TextChunk) content).isEmpty();
        }
        if (content instanceof SemanticTextNode) {
            SemanticTextNode node = (SemanticTextNode) content;
            if (!node.isEmpty()) {
                return true;
            }
            String value = node.getValue();
            return value != null && !value.trim().isEmpty();
        }
        return false;
    }

    /**
     * Screenshots a small vector mark that is set into a text line — a company signature
     * in front of the company name — and injects the result as an inline
     * {@code <img>} chunk of that line instead of emitting a standalone image.
     *
     * <p>The mark never reaches the regular gates: at 23.2 x 23.8 pt it is under
     * {@link #MIN_REGION_WIDTH} and, with 7 strokes, under {@link #MIN_RAW_LINE_COUNT},
     * so before this pass it produced no output at all. Being smaller than a region is
     * not a weakness here but the point: a mark this size has no meaning apart from the
     * words beside it, so it is reported beside them.</p>
     *
     * @return true when the mark was claimed and {@code candidate} left the page
     */
    private static boolean captureInlineMark(LineArtChunk candidate, List<IObject> pageContents,
                                            ImagesUtils imagesUtils, List<IObject> rawLines,
                                            int pageNumber) {
        BoundingBox box = candidate.getBoundingBox();
        if (box == null || box.isEmpty()) {
            return false;
        }
        double width = box.getWidth();
        double height = box.getHeight();
        if (width < INLINE_MARK_MIN_SIZE || height < INLINE_MARK_MIN_SIZE
            || width > INLINE_MARK_MAX_SIZE || height > INLINE_MARK_MAX_SIZE) {
            return false;
        }
        if (Math.max(width / height, height / width) > INLINE_MARK_MAX_ASPECT_RATIO) {
            return false;
        }
        if (countLinesInside(box, rawLines) < INLINE_MARK_MIN_STROKES
            && countShapeComponentsInside(box, pageContents) < INLINE_MARK_MIN_STROKES) {
            return false;
        }
        if (overlapsTable(box) || hasVetoingText(box, pageContents)) {
            return false;
        }
        TextLineHost host = findInlineMarkHost(box, pageContents);
        if (host == null) {
            return false;
        }

        ImageChunk imageChunk = new ImageChunk(new BoundingBox(box));
        imagesUtils.saveImageChunk(imageChunk);
        if (imageChunk.getIndex() == null) {
            // Nothing was rendered (no raster available); keep the mark for the
            // region gates rather than inject a dangling <img> reference.
            return false;
        }
        TextChunk inline = CustomChunksMergeUtils.convertImageChunkToTextChunk(imageChunk,
            host.line.getFontSize(), host.line.getBaseLine(), StaticLayoutContainers.getImagesDirectory());
        String hostText = host.line.getValue();
        insertInReadingOrder(host.line.getTextChunks(), inline);
        growTo(host.line, box);
        for (IObject ancestor : host.ancestors) {
            growTo(ancestor, box);
        }

        pageContents.remove(candidate);
        pageContents.removeIf(content -> isCoveredBy(box, content) && isRemovableWithRegion(content));
        LOGGER.log(Level.INFO,
            "Page {0}: captured inline vector mark {1} as image #{2} of the text line {3}",
            new Object[]{pageNumber + 1, describe(box), imageChunk.getIndex(), hostText});
        return true;
    }

    /**
     * The text line the mark is set into, or {@code null} when it is not inline with
     * any. A line qualifies when it vertically shares the mark's row and the mark sits
     * directly to its left or right — the measured signature is 4.38 pt from the name.
     * The closest candidate wins so a mark between two columns joins the one it touches.
     */
    private static TextLineHost findInlineMarkHost(BoundingBox box, List<IObject> pageContents) {
        TextLineHost best = null;
        double bestGap = Double.MAX_VALUE;
        for (TextLineHost host : collectTextLineHosts(pageContents)) {
            BoundingBox lineBox = host.line.getBoundingBox();
            if (lineBox == null || lineBox.isEmpty() || host.line.getTextChunks().isEmpty()) {
                continue;
            }
            if (!sharesTextLine(box, lineBox)) {
                continue;
            }
            double gap = horizontalGap(box, lineBox);
            if (gap < 0 || gap > INLINE_MARK_MAX_GAP || gap >= bestGap) {
                continue;
            }
            best = host;
            bestGap = gap;
        }
        return best;
    }

    /**
     * True when the mark is on the line's reading row: either centre falls inside the
     * other's vertical extent. A mark is usually taller than the glyphs beside it, so
     * testing the mark's centre against the line alone would miss it.
     */
    private static boolean sharesTextLine(BoundingBox mark, BoundingBox line) {
        return (mark.getCenterY() >= line.getBottomY() && mark.getCenterY() <= line.getTopY())
            || (line.getCenterY() >= mark.getBottomY() && line.getCenterY() <= mark.getTopY());
    }

    /** Distance between two boxes along x, or -1 when they already overlap. */
    private static double horizontalGap(BoundingBox a, BoundingBox b) {
        if (a.getRightX() <= b.getLeftX()) {
            return b.getLeftX() - a.getRightX();
        }
        if (b.getRightX() <= a.getLeftX()) {
            return a.getLeftX() - b.getRightX();
        }
        return -1;
    }

    /**
     * Walks the page's text carriers down to their lines, keeping the enclosing objects
     * so a line that gains an inline image can widen the paragraph / heading around it.
     */
    private static List<TextLineHost> collectTextLineHosts(List<IObject> pageContents) {
        List<TextLineHost> hosts = new ArrayList<>();
        for (IObject content : pageContents) {
            if (content instanceof TextLine) {
                hosts.add(new TextLineHost((TextLine) content, Collections.<IObject>emptyList()));
            } else if (content instanceof CustomSemanticParagraph) {
                List<IObject> ancestors = new ArrayList<>(1);
                ancestors.add(content);
                for (TextLine line : nullSafe(((CustomSemanticParagraph) content).getTextLines())) {
                    if (line != null) {
                        hosts.add(new TextLineHost(line, ancestors));
                    }
                }
            } else if (content instanceof SemanticTextNode) {
                List<IObject> nodeAncestors = new ArrayList<>(1);
                nodeAncestors.add(content);
                for (TextColumn column : nullSafe(((SemanticTextNode) content).getColumns())) {
                    if (column == null) {
                        continue;
                    }
                    List<IObject> columnAncestors = new ArrayList<>(nodeAncestors);
                    columnAncestors.add(column);
                    for (TextBlock block : nullSafe(column.getBlocks())) {
                        if (block == null) {
                            continue;
                        }
                        List<IObject> blockAncestors = new ArrayList<>(columnAncestors);
                        blockAncestors.add(block);
                        for (TextLine line : nullSafe(block.getLines())) {
                            if (line != null) {
                                hosts.add(new TextLineHost(line, blockAncestors));
                            }
                        }
                    }
                }
            }
        }
        return hosts;
    }

    /** Keeps the walk above total: a half-built semantic node must not stop the page. */
    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    /** Inserts {@code chunk} so that {@code chunks} stays ordered by left edge. */
    private static void insertInReadingOrder(List<TextChunk> chunks, TextChunk chunk) {
        int index = 0;
        while (index < chunks.size() && chunks.get(index).getLeftX() <= chunk.getLeftX()) {
            index++;
        }
        chunks.add(index, chunk);
    }

    /** Enlarges {@code content}'s bounding box so that it covers {@code box}. */
    private static void growTo(IObject content, BoundingBox box) {
        BoundingBox current = content.getBoundingBox();
        if (current == null) {
            content.setBoundingBox(new BoundingBox(box));
            return;
        }
        content.setBoundingBox(new BoundingBox(current).union(box));
    }

    /** A text line together with the objects enclosing it, outermost first. */
    private static final class TextLineHost {

        private final TextLine line;
        private final List<IObject> ancestors;

        private TextLineHost(TextLine line, List<IObject> ancestors) {
            this.line = line;
            this.ancestors = ancestors;
        }
    }

    /** Inserts {@code item} so that {@code pageContents} stays sorted by descending topY. */
    private static void addInOrder(List<IObject> pageContents, IObject item) {
        int index = 0;
        while (index < pageContents.size() && pageContents.get(index).getTopY() >= item.getTopY()) {
            index++;
        }
        pageContents.add(index, item);
    }

    private static String describe(BoundingBox box) {
        if (box == null) {
            return "[null]";
        }
        return String.format("[%.1f, %.1f, %.1f, %.1f] %.1fx%.1f pt",
            box.getLeftX(), box.getBottomY(), box.getRightX(), box.getTopY(),
            box.getWidth(), box.getHeight());
    }
}