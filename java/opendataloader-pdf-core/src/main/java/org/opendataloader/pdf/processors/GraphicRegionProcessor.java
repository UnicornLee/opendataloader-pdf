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

import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
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
 *       {@link #FULL_SPAN_RULE_RATIO} of the region. A graphic is a cluster of
 *       short strokes; a table grid is one long rule.</li>
 *   <li><b>No text</b> — a text block intersecting the box vetoes the capture.
 *       The text layer is never emptied here, so cropping a region that still
 *       holds text would render that text twice; and a vector-graphic region is
 *       by definition drawn without text objects, so any text inside it means the
 *       box is really table rows or body text (see
 *       {@link #TEXT_VETO_MIN_OVERLAP_SHARE}).</li>
 * </ol>
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

        List<String> skipped = new ArrayList<>();
        for (LineArtChunk candidate : candidates) {
            // A previous capture may already have removed this chunk.
            if (!pageContents.contains(candidate)) {
                continue;
            }
            BoundingBox box = candidate.getBoundingBox();
            String veto = findVetoReason(box, pageContents, rawLines, pageWidth, pageHeight);
            if (veto != null) {
                skipped.add(describe(box) + " (" + veto + ")");
                continue;
            }
            captureRegion(pageContents, candidate, box, imagesUtils, pageNumber);
        }
        if (!skipped.isEmpty()) {
            LOGGER.log(Level.INFO, "Page {0}: skipped {1} vector line-art region(s): {2}",
                new Object[]{pageNumber + 1, skipped.size(), String.join("; ", skipped)});
        }
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
        return Math.max(candidate.getWidth(), candidate.getHeight()) / boxSpan >= FULL_SPAN_RULE_RATIO;
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
            || content instanceof org.opendataloader.pdf.custom.entities.CustomSemanticParagraph
            || content instanceof org.verapdf.wcag.algorithms.entities.content.TextLine;
    }

    /**
     * Screenshots the region and swaps it for the resulting {@link ImageChunk}: the
     * {@link LineArtChunk} and every {@link ShapeChunk} the crop fully covers are
     * removed (neither reaches the JSON otherwise), then the image is inserted in
     * top-to-bottom order.
     */
    private static void captureRegion(List<IObject> pageContents, LineArtChunk lineArt,
                                      BoundingBox box, ImagesUtils imagesUtils, int pageNumber) {
        ImageChunk imageChunk = new ImageChunk(new BoundingBox(box));
        imagesUtils.saveImageChunk(imageChunk);

        pageContents.remove(lineArt);
        pageContents.removeIf(content -> {
            if (!(content instanceof ShapeChunk)) {
                return false;
            }
            BoundingBox shapeBox = content.getBoundingBox();
            return shapeBox != null && !shapeBox.isEmpty() && box.contains(shapeBox);
        });

        addInOrder(pageContents, imageChunk);
        LOGGER.log(Level.INFO, "Page {0}: captured vector graphic region {1} as image #{2}",
            new Object[]{pageNumber + 1, describe(box), imageChunk.getIndex()});
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