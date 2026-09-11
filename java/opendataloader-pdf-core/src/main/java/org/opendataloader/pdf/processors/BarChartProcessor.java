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

import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects bar-chart regions inside the grouped {@link
 * org.opendataloader.pdf.entities.content.ShapeChunk}s produced by
 * {@link ShapeRecognizer#groupShapes}. For every group whose entries include
 * a {@link org.opendataloader.pdf.entities.content.ShapeChunk#TYPE_BAR_CHART}
 * shape, the region's bounding box is iteratively grown by absorbing any
 * overlapping neighbouring shape groups and page contents, then the merged
 * area is rendered as a single screenshot and inserted as an
 * {@link ImageChunk}.
 *
 * <h2>Chart region expansion</h2>
 * The bars alone are not the chart: the y-axis line sits a few points to the
 * left of the leftmost bar, the tick numbers further left still, the category
 * labels below the plot area and the legend below / beside it. All of those are
 * separate page objects that never touch the bars' bounding box, so the plain
 * {@link #COLLECTION_MARGIN} growth would crop them away.
 *
 * <p>After the growth loop the region is therefore expanded by
 * {@link #expandToChartRegion}:</p>
 * <ul>
 *   <li><b>axes</b> — thin, long shapes attached to the left / bottom edge of
 *       the plot area;</li>
 *   <li><b>tick labels</b> — short single-line texts right-aligned to the
 *       y-axis;</li>
 *   <li><b>category labels</b> — short single-line texts in the band directly
 *       below the x-axis;</li>
 *   <li><b>legend</b> — a run of small equally-sized colour swatches next to
 *       the plot area plus the labels drawn on their row / column.</li>
 * </ul>
 * <p>Everything that is pulled into the screenshot is removed from the page
 * contents, so the image covers plot + axes + tick / category labels + legend —
 * and nothing else: chart titles, unit captions ("单位：万元") and source notes
 * ("数据来源：…") stay in the text flow.</p>
 */
public final class BarChartProcessor {

    /** Margin used when collecting neighbouring page contents around the region. */
    private static final double COLLECTION_MARGIN = 1.0;
    /** Horizontal expansion applied to the final bar-chart screenshot bbox. */
    private static final double SCREENSHOT_HORIZONTAL_MARGIN = 5.0;
    /** Vertical tolerance (pt) added to the final bar-chart screenshot bbox:
     *  topY is increased by this amount and bottomY is decreased by it. */
    private static final double SCREENSHOT_VERTICAL_TOLERANCE = 1.0;
    /** Safety cap on the do-while growth loop to avoid runaway iteration. */
    private static final int MAX_GROWTH_ITERATIONS = 30;

    /**
     * Maximum thickness (pt) of a shape that still counts as an axis line.
     * An axis with tick marks is a little thicker than the stroke itself
     * (observed range 2.9 - 3.2 pt), so the threshold has to stay comfortably
     * above that while still rejecting anything block-shaped.
     */
    private static final double AXIS_MAX_THICKNESS = 6.0;
    /** Minimum length (pt) of a shape that counts as an axis line. */
    private static final double AXIS_MIN_LENGTH = 20.0;
    /** Maximum distance (pt) between the plot area and an axis line that still belongs to the chart. */
    private static final double AXIS_ATTACH_GAP = 20.0;
    /** Minimum share of an axis line's length that must run alongside the plot area. */
    private static final double AXIS_OVERLAP_RATIO = 0.5;

    /** Width (pt) of the band left of the y-axis searched for tick labels. */
    private static final double TICK_LABEL_BAND = 55.0;
    /** Height (pt) of the band below the x-axis searched for category labels. */
    private static final double CATEGORY_LABEL_BAND = 30.0;
    /** Maximum height (pt) of a text that can be absorbed as a chart label. */
    private static final double MAX_LABEL_HEIGHT = 18.0;
    /**
     * Maximum character count of a text that can be absorbed as a chart label.
     * Generous enough for a full category row such as
     * "2021 2022 2023 2024E 2025E 2026E 2027E 2028E 2029E", which ends up as a
     * single merged text item on many charts.
     */
    private static final int MAX_LABEL_CHARS = 64;
    /**
     * Sentence punctuation. A text containing any of these is treated as body
     * text (or as a source note such as "数据来源：…") rather than as a chart
     * label. The ASCII comma and full stop are deliberately absent so that
     * numeric tick labels like "25,000.00" are still recognized.
     */
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[。，、；：！？;!?]");

    /** Maximum edge length (pt) of a legend colour swatch. */
    private static final double MAX_SWATCH_SIZE = 12.0;
    /** Minimum edge length (pt) of a legend colour swatch. */
    private static final double MIN_SWATCH_SIZE = 1.5;
    /** Maximum width/height ratio of a legend colour swatch. */
    private static final double MAX_SWATCH_ASPECT = 2.5;
    /** Maximum distance (pt) between the plot area and a legend swatch run. */
    private static final double LEGEND_ATTACH_GAP = 40.0;
    /** Tolerance (pt) when deciding that two legend swatches share a row / column. */
    private static final double LEGEND_ALIGN_TOLERANCE = 3.0;
    /** Minimum number of aligned swatches that make up a legend run. */
    private static final int MIN_LEGEND_SWATCHES = 2;

    private BarChartProcessor() {
    }

    /**
     * Processes every shape group from {@code groupedShapeChunks}, replacing
     * any group that contains a bar-chart shape with a single
     * {@link ImageChunk} covering the iteratively grown bbox.
     *
     * <p>The growth loop absorbs any later shape groups whose bbox overlaps
     * the current screenshot box, and any page contents whose bbox overlaps
     * the screenshot box within {@link #COLLECTION_MARGIN} points. After each
     * absorption the screenshot box is recomputed via {@code union}; the loop
     * terminates when no new content is absorbed or when
     * {@link #MAX_GROWTH_ITERATIONS} is reached. A final expansion step then
     * adds the chart's axes, tick / category labels and legend.</p>
     *
     * @param pageContents       the current page contents (will be modified)
     * @param groupedShapeChunks groups of overlapping {@link
     *                           org.opendataloader.pdf.entities.content.ShapeChunk}s
     * @param imagesUtils        image renderer / saver
     * @param pageNumber         0-based page number
     */
    public static void processBarChartGroups(List<IObject> pageContents,
                                              List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils,
                                              int pageNumber) {
        if (pageContents == null || imagesUtils == null || groupedShapeChunks == null) {
            return;
        }
        boolean[] skipped = new boolean[groupedShapeChunks.size()];
        for (int i = 0; i < groupedShapeChunks.size(); i++) {
            List<IObject> group = groupedShapeChunks.get(i);
            if (skipped[i] || group == null || group.isEmpty()
                    || !BoundingBoxGroupUtils.containsBarChart(group)) {
                continue;
            }
            BoundingBox groupBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(group, pageNumber);
            if (groupBox == null || groupBox.isEmpty()) {
                continue;
            }

            // Initial screenshot box already carries the horizontal / vertical margin so the
            // first iteration can find neighbouring groups and content that touch the bar chart.
            BoundingBox screenshotBox = expandWithMargin(groupBox,
                    SCREENSHOT_HORIZONTAL_MARGIN, SCREENSHOT_VERTICAL_TOLERANCE);

            List<IObject> absorbedShapes = new ArrayList<>(group);
            List<IObject> absorbedContents = new ArrayList<>();
            boolean expanded;
            int iterations = 0;
            do {
                expanded = false;
                iterations++;

                // 1. Absorb any later shape group whose bbox overlaps the current screenshot box.
                for (int j = i + 1; j < groupedShapeChunks.size(); j++) {
                    if (skipped[j]) {
                        continue;
                    }
                    List<IObject> laterGroup = groupedShapeChunks.get(j);
                    if (laterGroup == null || laterGroup.isEmpty()) {
                        continue;
                    }
                    BoundingBox laterBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(laterGroup, pageNumber);
                    if (laterBox == null || !screenshotBox.overlaps(laterBox)) {
                        continue;
                    }
                    absorbedShapes.addAll(laterGroup);
                    screenshotBox.union(laterBox);
                    skipped[j] = true;
                    expanded = true;
                }

                // 2. Absorb any page content whose bbox overlaps the screenshot box within the
                //    collection margin. Re-check after every growth so newly reachable content is
                //    picked up in the next iteration.
                List<IObject> snapshot = new ArrayList<>(pageContents);
                for (IObject content : snapshot) {
                    if (absorbedContents.contains(content)) {
                        continue;
                    }
                    BoundingBox contentBox = content.getBoundingBox();
                    if (contentBox == null || contentBox.isEmpty()) {
                        continue;
                    }
                    if (contentBox.overlaps(screenshotBox, COLLECTION_MARGIN)) {
                        absorbedContents.add(content);
                        screenshotBox.union(contentBox);
                        expanded = true;
                    }
                }
            } while (expanded && iterations < MAX_GROWTH_ITERATIONS);

            // 3. Pull in the parts of the chart that never touch the bars' bbox:
            //    axes, tick / category labels and the legend.
            expandToChartRegion(pageContents, screenshotBox, absorbedShapes, absorbedContents,
                    groupedShapeChunks, skipped);

            pageContents.removeAll(absorbedContents);
            pageContents.removeAll(absorbedShapes);
            ImageChunk imageChunk = new ImageChunk(screenshotBox);
            imagesUtils.saveImageChunk(imageChunk);
            pageContents.add(imageChunk);
        }
    }

    /**
     * Grows {@code chartBox} so it covers the whole chart instead of only the
     * bars: the y-/x-axis lines, the tick labels left of the y-axis, the
     * category labels below the x-axis and a legend run (colour swatches plus
     * their labels).
     *
     * <p>Every element that is pulled in is added to {@code absorbedShapes} /
     * {@code absorbedContents} so the caller can remove it from the page
     * contents; shape groups whose shapes were absorbed are marked in
     * {@code skipped} to keep the flowchart pass from re-processing them.</p>
     */
    private static void expandToChartRegion(List<IObject> pageContents, BoundingBox chartBox,
                                            List<IObject> absorbedShapes, List<IObject> absorbedContents,
                                            List<List<IObject>> groupedShapeChunks, boolean[] skipped) {
        // The plot area as it stands after the growth loop. Label bands are measured
        // against it (not against the already expanded box) so that absorbing the legend
        // cannot push the category-label band below the category labels themselves.
        BoundingBox plotBox = new BoundingBox(chartBox);
        List<IObject> newlyAbsorbedShapes = new ArrayList<>();

        // 1. Axis lines: thin, long shapes attached to the left / bottom edge of the plot area.
        BoundingBox labelBox = new BoundingBox(plotBox);
        double axisLeftX = plotBox.getLeftX();
        for (IObject content : new ArrayList<>(pageContents)) {
            if (!(content instanceof ShapeChunk) || absorbedShapes.contains(content)) {
                continue;
            }
            ShapeChunk shape = (ShapeChunk) content;
            if (ShapeChunk.TYPE_BAR_CHART.equals(shape.getShapeType())) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty()) {
                continue;
            }
            boolean verticalAxis = isVerticalAxis(box, plotBox);
            if (verticalAxis || isHorizontalAxis(box, plotBox)) {
                absorbedShapes.add(shape);
                newlyAbsorbedShapes.add(shape);
                chartBox.union(box);
                labelBox.union(box);
                if (verticalAxis) {
                    axisLeftX = Math.min(axisLeftX, box.getLeftX());
                }
            }
        }

        // 2. Legend: a run of equally sized colour swatches next to the plot area, plus their labels.
        expandToLegend(pageContents, chartBox, absorbedShapes, absorbedContents, newlyAbsorbedShapes);

        // 3. Tick labels (left of the y-axis) and category labels (below the x-axis).
        expandToAxisLabels(pageContents, chartBox, plotBox, labelBox, axisLeftX, absorbedContents);

        if (!newlyAbsorbedShapes.isEmpty()) {
            markConsumedGroups(groupedShapeChunks, skipped, newlyAbsorbedShapes);
        }
    }

    /**
     * Absorbs a legend run (at least {@link #MIN_LEGEND_SWATCHES} aligned
     * swatches of similar size attached to the plot area) together with the
     * labels drawn on the same row / column as the swatches.
     */
    private static void expandToLegend(List<IObject> pageContents, BoundingBox chartBox,
                                       List<IObject> absorbedShapes, List<IObject> absorbedContents,
                                       List<IObject> newlyAbsorbedShapes) {
        List<IObject> swatches = new ArrayList<>();
        for (IObject content : new ArrayList<>(pageContents)) {
            if (!(content instanceof ShapeChunk) || absorbedShapes.contains(content)) {
                continue;
            }
            BoundingBox box = content.getBoundingBox();
            if (box == null || box.isEmpty() || !isSwatchSized(box)) {
                continue;
            }
            if (isLegendAdjacent(box, chartBox)) {
                swatches.add(content);
            }
        }
        if (swatches.size() < MIN_LEGEND_SWATCHES) {
            return;
        }

        // Only aligned runs of at least MIN_LEGEND_SWATCHES count as a legend.
        for (List<IObject> run : collectAlignedLegendRuns(swatches)) {
            BoundingBox runBox = new BoundingBox(run.get(0).getBoundingBox());
            for (IObject swatch : run) {
                runBox.union(swatch.getBoundingBox());
            }
            absorbedShapes.addAll(run);
            newlyAbsorbedShapes.addAll(run);
            chartBox.union(runBox);

            // Labels of the legend: drawn on the same row / column as the swatches and
            // starting no further right than just after the swatch run.
            double verticalTolerance = runBox.getHeight() / 2 + 4.0;
            double runCenterY = runBox.getCenterY();
            for (IObject content : new ArrayList<>(pageContents)) {
                if (content instanceof ShapeChunk || absorbedContents.contains(content)
                        || !isShortSingleLineText(content)) {
                    continue;
                }
                BoundingBox box = content.getBoundingBox();
                if (Math.abs(box.getCenterY() - runCenterY) > verticalTolerance) {
                    continue;
                }
                if (box.getLeftX() > runBox.getRightX() + LEGEND_ALIGN_TOLERANCE
                        || box.getRightX() < runBox.getLeftX()) {
                    continue;
                }
                absorbedContents.add(content);
                chartBox.union(box);
            }
        }
    }

    /**
     * Absorbs the chart's tick / category labels: short single-line texts
     * right-aligned to the y-axis (tick numbers) and short single-line texts
     * inside the band directly below the x-axis (category names).
     *
     * @param chartBox  the region to grow with the absorbed labels
     * @param plotBox   the plot area; anchors the band below the x-axis
     * @param labelBox  the plot area widened by the attached axes; anchors the tick labels
     * @param axisLeftX left edge of the y-axis (or of the plot area when no axis was found)
     * @param absorbedContents collects the absorbed labels
     */
    private static void expandToAxisLabels(List<IObject> pageContents, BoundingBox chartBox,
                                           BoundingBox plotBox, BoundingBox labelBox, double axisLeftX,
                                           List<IObject> absorbedContents) {
        double tickRightLimit = axisLeftX + 2.0;
        double tickLeftLimit = tickRightLimit - TICK_LABEL_BAND;
        double bottomTopLimit = plotBox.getBottomY() + 2.0;
        double bottomBottomLimit = bottomTopLimit - CATEGORY_LABEL_BAND;

        for (IObject content : new ArrayList<>(pageContents)) {
            if (content instanceof ShapeChunk || absorbedContents.contains(content)
                    || !isShortSingleLineText(content)) {
                continue;
            }
            BoundingBox box = content.getBoundingBox();
            // Tick label: right-aligned to the y-axis, vertically inside the plot area.
            boolean tickLabel = box.getRightX() >= tickLeftLimit && box.getRightX() <= tickRightLimit
                    && box.getTopY() >= labelBox.getBottomY() - 2.0
                    && box.getBottomY() <= labelBox.getTopY() + 2.0;
            // Category label: single line sitting in the band right below the x-axis.
            boolean categoryLabel = box.getBottomY() >= bottomBottomLimit
                    && box.getBottomY() <= bottomTopLimit
                    && box.getRightX() >= plotBox.getLeftX() - 2.0
                    && box.getLeftX() <= plotBox.getRightX() + 5.0;
            if (tickLabel || categoryLabel) {
                absorbedContents.add(content);
                chartBox.union(box);
                labelBox.union(box);
            }
        }
    }

    /**
     * Returns true when {@code box} is a thin, long shape running alongside the
     * left edge of the plot area within {@link #AXIS_ATTACH_GAP} — i.e. the
     * chart's y-axis (together with its tick marks, which extend the same bbox).
     */
    private static boolean isVerticalAxis(BoundingBox box, BoundingBox plotBox) {
        if (box.getWidth() > AXIS_MAX_THICKNESS || box.getHeight() < AXIS_MIN_LENGTH) {
            return false;
        }
        if (box.getRightX() > plotBox.getLeftX() + 0.5
                || plotBox.getLeftX() - box.getRightX() > AXIS_ATTACH_GAP) {
            return false;
        }
        return verticalOverlapRatio(box, plotBox) >= AXIS_OVERLAP_RATIO;
    }

    /**
     * Returns true when {@code box} is a thin, long shape running underneath the
     * bottom edge of the plot area within {@link #AXIS_ATTACH_GAP} — i.e. the
     * chart's x-axis.
     */
    private static boolean isHorizontalAxis(BoundingBox box, BoundingBox plotBox) {
        if (box.getHeight() > AXIS_MAX_THICKNESS || box.getWidth() < AXIS_MIN_LENGTH) {
            return false;
        }
        if (box.getTopY() > plotBox.getBottomY() + 0.5
                || plotBox.getBottomY() - box.getTopY() > AXIS_ATTACH_GAP) {
            return false;
        }
        return horizontalOverlapRatio(box, plotBox) >= AXIS_OVERLAP_RATIO;
    }

    /** Share of {@code box}'s height that overlaps {@code other} vertically. */
    private static double verticalOverlapRatio(BoundingBox box, BoundingBox other) {
        double overlap = Math.min(box.getTopY(), other.getTopY()) - Math.max(box.getBottomY(), other.getBottomY());
        return box.getHeight() <= 0 ? 0.0 : overlap / box.getHeight();
    }

    /** Share of {@code box}'s width that overlaps {@code other} horizontally. */
    private static double horizontalOverlapRatio(BoundingBox box, BoundingBox other) {
        double overlap = Math.min(box.getRightX(), other.getRightX()) - Math.max(box.getLeftX(), other.getLeftX());
        return box.getWidth() <= 0 ? 0.0 : overlap / box.getWidth();
    }

    /** Small, roughly square shape — a legend colour swatch. */
    private static boolean isSwatchSized(BoundingBox box) {
        double width = box.getWidth();
        double height = box.getHeight();
        if (width < MIN_SWATCH_SIZE || height < MIN_SWATCH_SIZE
                || width > MAX_SWATCH_SIZE || height > MAX_SWATCH_SIZE) {
            return false;
        }
        double ratio = Math.max(width / height, height / width);
        return ratio <= MAX_SWATCH_ASPECT;
    }

    /**
     * Returns true when a swatch-sized box sits within {@link #LEGEND_ATTACH_GAP}
     * of the plot area, below it or to its right.
     */
    private static boolean isLegendAdjacent(BoundingBox box, BoundingBox plotBox) {
        boolean below = box.getTopY() <= plotBox.getBottomY() + 0.5
                && plotBox.getBottomY() - box.getTopY() <= LEGEND_ATTACH_GAP
                && horizontalOverlapRatio(box, plotBox) > 0.0;
        boolean right = box.getLeftX() >= plotBox.getRightX() - 0.5
                && box.getLeftX() - plotBox.getRightX() <= LEGEND_ATTACH_GAP
                && verticalOverlapRatio(box, plotBox) > 0.0;
        return below || right;
    }

    /**
     * Splits the swatch candidates into runs of swatches sharing the same
     * centerY (horizontal legend) or the same centerX (vertical legend) and
     * keeps the runs with at least {@link #MIN_LEGEND_SWATCHES} members.
     */
    private static List<List<IObject>> collectAlignedLegendRuns(List<IObject> swatches) {
        List<List<IObject>> runs = new ArrayList<>();
        List<IObject> remaining = new ArrayList<>(swatches);
        while (!remaining.isEmpty()) {
            IObject seed = remaining.remove(0);
            BoundingBox seedBox = seed.getBoundingBox();
            List<IObject> run = new ArrayList<>();
            run.add(seed);
            for (int i = remaining.size() - 1; i >= 0; i--) {
                BoundingBox box = remaining.get(i).getBoundingBox();
                boolean sameRow = Math.abs(box.getCenterY() - seedBox.getCenterY()) <= LEGEND_ALIGN_TOLERANCE;
                boolean sameColumn = Math.abs(box.getCenterX() - seedBox.getCenterX()) <= LEGEND_ALIGN_TOLERANCE;
                if (sameRow || sameColumn) {
                    run.add(remaining.remove(i));
                }
            }
            if (run.size() >= MIN_LEGEND_SWATCHES) {
                runs.add(run);
            }
        }
        return runs;
    }

    /** Marks every shape group that contributed an absorbed shape. */
    private static void markConsumedGroups(List<List<IObject>> groupedShapeChunks, boolean[] skipped,
                                           List<IObject> consumedShapes) {
        for (int j = 0; j < groupedShapeChunks.size(); j++) {
            if (skipped[j]) {
                continue;
            }
            List<IObject> group = groupedShapeChunks.get(j);
            if (group == null) {
                continue;
            }
            for (IObject shape : group) {
                if (containsIdentity(consumedShapes, shape)) {
                    skipped[j] = true;
                    break;
                }
            }
        }
    }

    private static boolean containsIdentity(List<IObject> list, IObject target) {
        for (IObject item : list) {
            if (item == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when {@code content} is a text element small enough to be a
     * chart label: a single short line that does not read like a sentence (or
     * like a source note such as "数据来源：…").
     */
    private static boolean isShortSingleLineText(IObject content) {
        if (!(content instanceof TextChunk || content instanceof TextLine
                || content instanceof SemanticTextNode || content instanceof CustomSemanticParagraph)) {
            return false;
        }
        BoundingBox box = content.getBoundingBox();
        if (box == null || box.isEmpty() || box.getHeight() > MAX_LABEL_HEIGHT) {
            return false;
        }
        String text = getTextValue(content);
        if (text == null) {
            return false;
        }
        text = text.trim();
        if (text.isEmpty() || text.length() > MAX_LABEL_CHARS) {
            return false;
        }
        return !SENTENCE_PUNCTUATION.matcher(text).find();
    }

    /** Concatenates the text of the supported text-bearing element types. */
    private static String getTextValue(IObject content) {
        if (content instanceof TextChunk) {
            return ((TextChunk) content).getValue();
        }
        if (content instanceof TextLine) {
            StringBuilder builder = new StringBuilder();
            for (TextChunk chunk : ((TextLine) content).getTextChunks()) {
                builder.append(getTextValue(chunk));
            }
            return builder.toString();
        }
        if (content instanceof CustomSemanticParagraph) {
            StringBuilder builder = new StringBuilder();
            for (TextLine line : ((CustomSemanticParagraph) content).getTextLines()) {
                builder.append(getTextValue(line));
            }
            return builder.toString();
        }
        if (content instanceof SemanticTextNode) {
            return ((SemanticTextNode) content).getValue();
        }
        return null;
    }

    /**
     * Returns a new {@link BoundingBox} expanded by {@code xMargin} on both
     * horizontal sides and by {@code yMargin} on both vertical sides (topY is
     * increased, bottomY is decreased).
     */
    private static BoundingBox expandWithMargin(BoundingBox box, double xMargin, double yMargin) {
        BoundingBox expanded = new BoundingBox(box);
        expanded.setLeftX(box.getLeftX() - xMargin);
        expanded.setRightX(box.getRightX() + xMargin);
        expanded.setTopY(box.getTopY() + yMargin);
        expanded.setBottomY(box.getBottomY() - yMargin);
        return expanded;
    }
}
