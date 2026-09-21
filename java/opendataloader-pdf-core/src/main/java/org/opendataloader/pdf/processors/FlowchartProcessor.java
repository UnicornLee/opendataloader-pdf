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
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.Table;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects flowchart-like regions by analyzing a connected group of
 * {@link ShapeChunk}s together with the text, images, and tables that fall
 * inside the group's bounding box. Once a region is recognized as a flowchart,
 * all contents inside the expanded bounding box are replaced by a single
 * screenshot image.
 */
public class FlowchartProcessor {

    private static final Logger LOGGER = Logger.getLogger(FlowchartProcessor.class.getCanonicalName());

    /** Margin used when collecting neighboring contents around the shape group. */
    private static final double COLLECTION_MARGIN = 2.0;
    /**
     * Margin used for short single-line text labels (e.g. a value printed on a
     * connector line). Diagram labels regularly sit a few points away from the
     * shape they annotate — further than {@link #COLLECTION_MARGIN} — and leaving
     * them out splits the diagram: the label stays in the text layer while the
     * same label is visible inside the screenshot.
     *
     * <p>Measured cases: {@code 15.8% (consolidated interest)} sits 3.0–3.5 pt above
     * the shape group on one page and 6.0 pt above it on another, so the margin has
     * to cover the larger gap. Only short text blocks qualify (see
     * {@link #isDiagramLabel}), and runs of adjacent lines are excluded beforehand
     * (see {@link #findProseBlocks}), which is what keeps body text out.</p>
     */
    private static final double LABEL_COLLECTION_MARGIN = 8.0;
    /** Maximum height (pt) of a text block that may still count as a diagram label. */
    private static final double LABEL_MAX_HEIGHT = 12.0;
    /**
     * Tolerance (pt) used when deciding that a text block lies inside the diagram region
     * (see {@link #absorbInteriorText}), so labels sharing an edge with the boundary are
     * still absorbed.
     */
    private static final double INTERIOR_TEXT_TOLERANCE = 1.0;
    /**
     * Vertical gap between two stacked text blocks (as a ratio of their height) that
     * still makes them consecutive lines of one paragraph.
     */
    private static final double PROSE_LINE_GAP_RATIO = 1.5;
    /** How many following text blocks are inspected when looking for the next line. */
    private static final int PROSE_LOOKAHEAD = 8;
    /** Horizontal expansion applied to the final flowchart screenshot bbox. */
    private static final double SCREENSHOT_HORIZONTAL_MARGIN = 5.0;
    /** Vertical tolerance (pt) added to the final flowchart screenshot bbox:
     *  topY is increased by this amount and bottomY is decreased by it. */
    private static final double SCREENSHOT_VERTICAL_TOLERANCE = 1.0;
    /** Safety cap on the do-while absorption loop to avoid runaway iteration. */
    private static final int MAX_GROWTH_ITERATIONS = 30;

    private static final double MIN_WIDTH = 80.0;
    /**
     * Minimum height (pt) of a flowchart region. Lowered from 40 to 20 so the
     * decision no longer hinges on a sub-point margin: previously a region whose
     * merged box happened to be 40.6 pt tall would flip between "flowchart" and
     * "not" depending only on page-count-driven paragraph merging. Genuine
     * flowcharts are still gated by the shape / connector / text composition
     * checks below.
     */
    private static final double MIN_HEIGHT = 20.0;
    private static final double MAX_ASPECT_RATIO = 6.0;
    private static final int MIN_SHAPE_COUNT = 2;
    private static final int MIN_TOTAL_COMPONENTS = 5;

    /**
     * A regular table must occupy more than this ratio of the cluster for the
     * cluster to be treated as a table instead of a flowchart. Cell count is
     * deliberately not used as a standalone veto anymore — see
     * {@link #isRegularTable(Cluster)}.
     *
     * <p>Measured cases: a 22-shape organisation chart whose line preprocessing
     * produced 2x2/3x3 fragments peaks at 0.235 (must stay a diagram), while a
     * "table – caption – table" band reaches 0.35 and must keep its tables. The
     * threshold therefore sits at 0.30.</p>
     */
    private static final double REGULAR_TABLE_AREA_RATIO = 0.3;

    /**
     * A single shape whose bounding box covers more than this share of the page is
     * a border / background frame, not a diagram node. Such shapes are skipped as
     * seeds; otherwise the frame would turn the whole page into one "flowchart".
     */
    private static final double PAGE_FRAME_AREA_RATIO = 0.4;
    /**
     * A region covering more than this share of the page is never cropped as a
     * diagram: the diagram is the whole page (or a background frame already visible
     * in the text layer), and cropping it would remove the entire page's text.
     */
    /**
     * A cluster covering more than this share of the page is a page-level region — a table
     * block, a background frame or a full-page decoration — and is never cropped. Raised
     * clusters come from diagram labels (e.g. a table's "单位：万元" caption is only 1.5 pt
     * above the next table's frame) chaining several shape groups together; without this
     * guard such a page ends up as one screenshot with all its tables swallowed.
     * Measured values: a 4-table page reaches 0.55 while genuine diagrams stay low
     * (0.215 for the mid-page organisation chart, 0.047 for the page-83 chart).
     */
    private static final double MAX_CLUSTER_PAGE_AREA_RATIO = 0.5;

    /**
     * Processes every shape group that was produced by
     * {@link ShapeRecognizer#groupShapes}. For each group that looks like a
     * flowchart when combined with its enclosed text, images, and tables, the
     * region is rendered as a single screenshot and the original contents are
     * replaced by an {@link ImageChunk}.
     *
     * @param pageContents       the current page contents (will be modified)
     * @param groupedShapeChunks groups of overlapping {@link ShapeChunk}s
     * @param imagesUtils        image renderer / saver
     * @param pageNumber         0-based page number
     */
    public static void processFlowchartGroups(List<IObject> pageContents,
                                              List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils,
                                              int pageNumber) {
        // Without the page size the page-level guards stay disabled; callers that know the
        // media box should use the overload below.
        processFlowchartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber, 0.0, 0.0, false);
    }

    /**
     * Processes every shape group that was produced by
     * {@link ShapeRecognizer#groupShapes}, see
     * {@link #processFlowchartGroups(List, List, ImagesUtils, int)}.
     *
     * @param pageWidth  page width (pt); with {@code pageHeight} it enables the
     *                   page-level guards (page frames and page-sized regions)
     * @param pageHeight page height (pt)
     */
    public static void processFlowchartGroups(List<IObject> pageContents,
                                              List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils,
                                              int pageNumber,
                                              double pageWidth,
                                              double pageHeight) {
        processFlowchartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber,
                pageWidth, pageHeight, false);
    }

    /**
     * Processes every diagram group of a page, see
     * {@link #processFlowchartGroups(List, List, ImagesUtils, int)}.
     *
     * @param pageWidth   page width (pt); with {@code pageHeight} it enables the
     *                    page-level guards (page frames and page-sized regions)
     * @param pageHeight  page height (pt)
     * @param arrowDriven true when the groups were grown from the page's arrowheads
     *                    ({@link ShapeRecognizer#groupShapesByArrowHeaders}). Such a region
     *                    is already known to be a diagram: the arrows connect its parts, so
     *                    the "looks like a regular table" veto is skipped. Flow diagrams
     *                    drawn as rows of boxes are routinely turned into a few bogus
     *                    tables by the line preprocessing, and those tables must neither
     *                    veto the diagram nor survive the crop. Every other guard (size,
     *                    aspect ratio, page frame, page-sized region, body text) still
     *                    applies.
     */
    public static void processFlowchartGroups(List<IObject> pageContents,
                                              List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils,
                                              int pageNumber,
                                              double pageWidth,
                                              double pageHeight,
                                              boolean arrowDriven) {
        if (pageContents == null || groupedShapeChunks == null || imagesUtils == null) {
            return;
        }
        BoundingBox pageBox = pageWidth > 0 && pageHeight > 0
                ? new BoundingBox(pageNumber, 0, 0, pageWidth, pageHeight)
                : null;
        Set<IObject> proseBlocks = findProseBlocks(pageContents);
        boolean[] skipped = new boolean[groupedShapeChunks.size()];
        for (int i = 0; i < groupedShapeChunks.size(); i++) {
            List<IObject> group = groupedShapeChunks.get(i);
            if (skipped[i] || group == null || group.isEmpty()
                    || BoundingBoxGroupUtils.containsBarChart(group)
                    || BoundingBoxGroupUtils.containsPieChart(group)
                    || isPageFrameGroup(group, pageBox, pageNumber)
                    || !isStillOnPage(pageContents, group)) {
                continue;
            }
            Cluster cluster = collectCluster(pageContents, group, pageNumber, proseBlocks);
            if (cluster == null) {
                continue;
            }
            // Absorb any later shape groups that intersect the cluster area so the
            // whole connected diagram is evaluated (and potentially captured) as one
            // image. Absorbing before the flowchart decision prevents a single small
            // group from being rejected when the merged diagram would qualify.
            List<IObject> mergedShapes = new ArrayList<>(group);
            List<IObject> mergedContents = new ArrayList<>(cluster.collectedContents);
            BoundingBox mergedBox = new BoundingBox(cluster.boundingBox);
            BoundingBox screenshotBox = expandHorizontally(mergedBox, SCREENSHOT_HORIZONTAL_MARGIN,
                    SCREENSHOT_VERTICAL_TOLERANCE);
            boolean expanded;
            int iterations = 0;
            do {
                expanded = false;
                iterations++;
                for (int j = i + 1; j < groupedShapeChunks.size(); j++) {
                    if (skipped[j]) {
                        continue;
                    }
                    List<IObject> laterGroup = groupedShapeChunks.get(j);
                    if (laterGroup == null || laterGroup.isEmpty()) {
                        continue;
                    }
                    BoundingBox laterBox = BoundingBoxGroupUtils.unionBoundingBoxes(laterGroup, pageNumber);
                    if (laterBox == null || !screenshotBox.overlaps(laterBox)) {
                        continue;
                    }
                    Cluster laterCluster = collectCluster(pageContents, laterGroup, pageNumber, proseBlocks);
                    if (laterCluster != null) {
                        screenshotBox.union(laterCluster.boundingBox);
                        mergedBox.union(laterCluster.boundingBox);
                        mergedContents.addAll(laterCluster.collectedContents);
                    }
                    mergedShapes.addAll(laterGroup);
                    skipped[j] = true;
                    expanded = true;
                }
            } while (expanded && iterations < MAX_GROWTH_ITERATIONS);

            Cluster mergedCluster = dropBodyTextBlocks(
                    new Cluster(mergedShapes, mergedContents, mergedBox), mergedShapes, proseBlocks);
            if (coversWholePage(mergedCluster.boundingBox, pageBox)) {
                // A region spanning (almost) the whole page is a page border, a background
                // frame or a full-page decoration. Cropping it would swallow the entire page
                // text layer, so the contents are left untouched.
                continue;
            }
            // The body-text guard must run on the *collected* region, i.e. before the interior
            // text is absorbed: absorption deliberately takes back short blocks that the
            // collection step skipped for looking prose-like, so re-checking afterwards would
            // reject every diagram whose labels are a fraction taller than a label
            // (see absorbInteriorText). Collection and dropBodyTextBlocks already keep real
            // prose out of the region, so nothing is lost by checking here instead.
            // TEMPORARY A/B SWITCH (remove after the corpus comparison):
            // -DlegacyBodyTextGuard=true restores the previous behaviour (guard only inside
            // isFlowchartCluster, i.e. evaluated after absorbInteriorText) so both variants
            // can be diffed on the corpus.
            if (!Boolean.getBoolean("legacyBodyTextGuard") && containsBodyText(mergedCluster)) {
                continue;
            }
            // Text that lies inside the diagram region belongs to the diagram even when it is
            // part of a run of adjacent lines (e.g. a column of values inside a chart). Such
            // blocks were skipped during collection because they look like prose; absorb them
            // now so the screenshot becomes the only place where they appear.
            mergedCluster = absorbInteriorText(mergedCluster, mergedShapes, pageContents);
            // Recompute the screenshot box from the (possibly shrunk) region: when a page-wide
            // heading or paragraph was dropped, the region has to shrink with it, otherwise the
            // text would still be cropped into the screenshot while remaining in the text layer.
            BoundingBox finalScreenshotBox = expandHorizontally(mergedCluster.boundingBox,
                    SCREENSHOT_HORIZONTAL_MARGIN, SCREENSHOT_VERTICAL_TOLERANCE);
            if (isFlowchartCluster(mergedCluster, arrowDriven)) {
                LOGGER.log(Level.INFO, "Page {0}: detected flowchart cluster with screenshot bbox {1}",
                        new Object[]{pageNumber + 1, finalScreenshotBox});
                pageContents.removeAll(mergedCluster.collectedContents);
                pageContents.removeAll(mergedShapes);
                ImageChunk imageChunk = new ImageChunk(finalScreenshotBox);
                imagesUtils.saveImageChunk(imageChunk);
                addInOrder(pageContents, imageChunk);
            }
        }
    }

    /**
     * Absorbs every text block that lies inside the diagram region, so it is removed
     * from the text layer and only visible inside the screenshot.
     *
     * <p>{@link #findProseBlocks} keeps runs of adjacent lines out of the region because
     * they usually are body text. Inside the diagram the opposite is true: a stacked
     * column of short values is a chart label, not prose. Blocks fully contained in the
     * region (the region is built from shapes, so it does not grow because of prose) are
     * therefore taken back in.</p>
     *
     * @return the cluster extended by the interior text blocks; the bounding box is
     *         unchanged because every absorbed block is contained in it
     */
    private static Cluster absorbInteriorText(Cluster cluster, List<IObject> mergedShapes,
                                              List<IObject> pageContents) {
        if (cluster.boundingBox == null || cluster.boundingBox.isEmpty()) {
            return cluster;
        }
        List<IObject> added = null;
        for (IObject content : pageContents) {
            if (content instanceof ShapeChunk || !isTextContent(content)
                    || cluster.collectedContents.contains(content)) {
                continue;
            }
            BoundingBox box = content.getBoundingBox();
            if (box == null || box.isEmpty()
                    || !cluster.boundingBox.contains(box, INTERIOR_TEXT_TOLERANCE, INTERIOR_TEXT_TOLERANCE)) {
                continue;
            }
            if (added == null) {
                added = new ArrayList<>();
            }
            added.add(content);
        }
        if (added == null) {
            return cluster;
        }
        List<IObject> kept = new ArrayList<>(cluster.collectedContents);
        kept.addAll(added);
        return new Cluster(mergedShapes, kept, cluster.boundingBox);
    }

    /**
     * Inserts the new screenshot keeping {@code pageContents} in the descending-topY
     * order the rest of the pipeline relies on.
     *
     * <p>Appending instead would leave two screenshots of one page next to each other
     * in the list even when they belong to diagrams that are far apart on the page
     * (e.g. a heading in between). {@link ConsecutiveImageProcessor}, which merges
     * adjacent image fragments, would then glue them together and crop whatever lies
     * between them into a single screenshot.</p>
     */
    private static void addInOrder(List<IObject> pageContents, IObject item) {
        int index = 0;
        while (index < pageContents.size() && pageContents.get(index).getTopY() >= item.getTopY()) {
            index++;
        }
        pageContents.add(index, item);
    }

    /**
     * Returns true when at least one shape of {@code shapeGroup} is still part
     * of {@code pageContents}. {@link BarChartProcessor} runs first and replaces
     * a chart by a single screenshot, which removes the chart's shapes (bars,
     * axes, legend swatches) from the page. Such an already consumed group must
     * not be evaluated again here — otherwise the freshly created bar-chart
     * screenshot would be re-cropped (and duplicated) as a "flowchart".
     */
    private static boolean isStillOnPage(List<IObject> pageContents, List<IObject> shapeGroup) {
        for (IObject shape : shapeGroup) {
            for (IObject content : pageContents) {
                if (content == shape) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Cluster collectCluster(List<IObject> pageContents, List<IObject> shapeGroup, int pageNumber,
                                          Set<IObject> proseBlocks) {
        // The group may carry raw line chunks as well as shapes (arrow-driven regions do):
        // those lines are part of the region and must contribute to its bounding box.
        // For plain shape groups the result is the same union of shape boxes.
        BoundingBox shapeBox = BoundingBoxGroupUtils.unionBoundingBoxes(shapeGroup, pageNumber);
        if (shapeBox == null || shapeBox.isEmpty()) {
            return null;
        }

        List<IObject> collected = new ArrayList<>();
        BoundingBox clusterBox = new BoundingBox(shapeBox);
        collectOverlappingContents(pageContents, shapeBox, collected, clusterBox, proseBlocks);
        // One growth step: if we found contents adjacent to the shape group, expand the
        // search box to include them and pull in any further contents that are now in range.
        // This lets a few connecting lines capture the images/text/tables inside a flowchart.
        if (!collected.isEmpty()) {
            collectOverlappingContents(pageContents, clusterBox, collected, clusterBox, proseBlocks);
        }
        if (clusterBox.isEmpty()) {
            return null;
        }
        return new Cluster(shapeGroup, collected, clusterBox);
    }

    private static void collectOverlappingContents(List<IObject> pageContents, BoundingBox searchBox,
                                                   List<IObject> collected, BoundingBox clusterBox,
                                                   Set<IObject> proseBlocks) {
        for (IObject content : pageContents) {
            if (content instanceof ShapeChunk || collected.contains(content)) {
                continue;
            }
            BoundingBox contentBox = content.getBoundingBox();
            if (contentBox == null || contentBox.isEmpty()) {
                continue;
            }
            if (proseBlocks.contains(content) || isBodyTextBlock(content)) {
                // Body text is never part of the diagram: collecting it would grow the
                // region across the page (pulling in unrelated shapes and text) and take
                // the paragraph out of the text layer.
                continue;
            }
            double margin = isDiagramLabel(content) ? LABEL_COLLECTION_MARGIN : COLLECTION_MARGIN;
            if (contentBox.overlaps(searchBox, margin)) {
                collected.add(content);
                clusterBox.union(contentBox);
            }
        }
    }

    /**
     * Returns true when the given content is a diagram label: a short single-line
     * text block. Labels may sit a few points away from the shapes they belong to
     * (see {@link #LABEL_COLLECTION_MARGIN}) and may be cropped into the screenshot.
     *
     * <p>Body text is excluded through {@link #findProseBlocks}: a line of prose
     * always has neighbouring lines in the same column, a label floats alone. Width
     * is deliberately not part of the test — a diagram's caption line can be wider
     * than the node boxes it belongs to, while a line of two-column prose is about
     * as short and narrow as a label.</p>
     */
    private static boolean isDiagramLabel(IObject content) {
        if (!isTextContent(content)) {
            return false;
        }
        BoundingBox box = content.getBoundingBox();
        return box != null && !box.isEmpty() && box.getHeight() <= LABEL_MAX_HEIGHT;
    }

    /**
     * Returns true for every entity that contributes to the cluster's text count.
     */
    private static boolean isTextContent(IObject content) {
        return content instanceof TextChunk || content instanceof TextLine
                || content instanceof SemanticTextNode || content instanceof CustomSemanticParagraph
                || content instanceof SemanticHeading;
    }

    /**
     * Returns the set of text blocks that belong to a multi-line paragraph: blocks
     * that have another text block directly above or below them in the same column.
     *
     * <p>This is what separates prose from diagram labels. A label sits alone next to
     * the shapes it annotates, while body text always comes as a run of lines.
     * Geometry alone cannot tell them apart — a line of two-column prose is about as
     * short and as narrow as a longer label — so the neighbourhood decides.</p>
     *
     * <p>Runs in O(n log n + n·k): the blocks are sorted by topY and only the next few
     * entries are inspected, which suffices because consecutive lines are adjacent in
     * that order.</p>
     */
    private static Set<IObject> findProseBlocks(List<IObject> pageContents) {
        List<IObject> texts = new ArrayList<>();
        for (IObject content : pageContents) {
            if (content instanceof ShapeChunk || !isTextContent(content)) {
                continue;
            }
            BoundingBox box = content.getBoundingBox();
            if (box != null && !box.isEmpty()) {
                texts.add(content);
            }
        }
        if (texts.size() < 2) {
            return Collections.emptySet();
        }
        texts.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
        Set<IObject> prose = Collections.newSetFromMap(new IdentityHashMap<IObject, Boolean>());
        for (int i = 0; i < texts.size(); i++) {
            BoundingBox box = texts.get(i).getBoundingBox();
            for (int j = i + 1; j < texts.size() && j <= i + PROSE_LOOKAHEAD; j++) {
                BoundingBox otherBox = texts.get(j).getBoundingBox();
                // Vertical air between the two blocks; negative when they overlap.
                double gap = box.getBottomY() - otherBox.getTopY();
                if (gap > PROSE_LINE_GAP_RATIO * Math.min(box.getHeight(), otherBox.getHeight())) {
                    // Everything further down is even further away.
                    break;
                }
                if (horizontallyOverlaps(box, otherBox)) {
                    prose.add(texts.get(i));
                    prose.add(texts.get(j));
                }
            }
        }
        return prose;
    }

    private static boolean horizontallyOverlaps(BoundingBox a, BoundingBox b) {
        return a.getLeftX() <= b.getRightX() && b.getLeftX() <= a.getRightX();
    }

    /**
     * Returns true when the group consists of a single shape that spans most of the
     * page — a page border, background frame or similar decoration.
     */
    private static boolean isPageFrameGroup(List<IObject> group, BoundingBox pageBox, int pageNumber) {
        if (group.size() != 1 || pageBox == null || pageBox.getArea() <= 0) {
            return false;
        }
        BoundingBox shapeBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(group, pageNumber);
        return shapeBox != null && !shapeBox.isEmpty()
                && shapeBox.getArea() / pageBox.getArea() > PAGE_FRAME_AREA_RATIO;
    }

    private static boolean coversWholePage(BoundingBox clusterBox, BoundingBox pageBox) {
        if (clusterBox == null || clusterBox.isEmpty() || pageBox == null || pageBox.getArea() <= 0) {
            return false;
        }
        return clusterBox.getArea() / pageBox.getArea() > MAX_CLUSTER_PAGE_AREA_RATIO;
    }

    private static BoundingBox expandHorizontally(BoundingBox box, double xMargin, double yMargin) {
        BoundingBox expanded = new BoundingBox(box);
        expanded.setLeftX(box.getLeftX() - xMargin);
        expanded.setRightX(box.getRightX() + xMargin);
        expanded.setTopY(box.getTopY() + yMargin);
        expanded.setBottomY(box.getBottomY() - yMargin);
        return expanded;
    }

    private static boolean isFlowchartCluster(Cluster cluster) {
        return isFlowchartCluster(cluster, false);
    }

    /**
     * Decides whether the given cluster is a diagram.
     *
     * @param skipTableVeto true for regions grown from the page's arrowheads: an arrow
     *                      already connects the parts of the region, so the "a table
     *                      occupies more than {@link #REGULAR_TABLE_AREA_RATIO} of the
     *                      cluster" veto is not applicable. Rows of boxes in a flow
     *                      diagram are regularly split into bogus tables by the line
     *                      preprocessing, and those must not veto the diagram they are
     *                      part of.
     */
    private static boolean isFlowchartCluster(Cluster cluster, boolean skipTableVeto) {
        if (cluster == null || cluster.boundingBox == null || cluster.boundingBox.isEmpty()) {
            return false;
        }
        // Note: the body-text guard is applied in processFlowchartGroups *before*
        // absorbInteriorText runs. Keeping it here as well would reject every diagram whose
        // interior labels were taken back by that step, because a label a fraction taller
        // than LABEL_MAX_HEIGHT is indistinguishable from a prose line at this point.
        // TEMPORARY A/B SWITCH (remove after the corpus comparison).
        if (Boolean.getBoolean("legacyBodyTextGuard") && containsBodyText(cluster)) {
            return false;
        }
        double width = cluster.boundingBox.getWidth();
        double height = cluster.boundingBox.getHeight();
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return false;
        }
        if (Math.max(width / height, height / width) > MAX_ASPECT_RATIO) {
            return false;
        }
        if (!skipTableVeto && isRegularTable(cluster)) {
            return false;
        }
        if (cluster.shapeCount < MIN_SHAPE_COUNT || cluster.totalComponents < MIN_TOTAL_COMPONENTS) {
            return false;
        }

        int connectorCount = cluster.polylineCount + cluster.arrowCount;
        boolean mixedShapes = cluster.rectangleCount >= 1 && connectorCount >= 1 && cluster.totalComponents >= 6;
        boolean compositeContent = (cluster.imageCount + cluster.tableCount) >= 2
                && cluster.textCount >= 1 && cluster.shapeCount >= 2;
        boolean imageWithConnectors = cluster.imageCount >= 1 && connectorCount >= 2 && cluster.textCount >= 1;
        boolean labelsWithConnectors = cluster.textCount >= 3 && connectorCount >= 2;
        boolean boxesWithArrows = cluster.rectangleCount >= 2 && cluster.arrowCount >= 1;

        return mixedShapes || compositeContent || imageWithConnectors || labelsWithConnectors || boxesWithArrows;
    }

    /**
     * Drops body-text blocks (page-wide headings/paragraphs taller than a single
     * text line) from the collected contents and recomputes the region from the
     * remaining shapes and contents.
     *
     * <p>The cluster is grown by absorbing everything adjacent to it, which also
     * picks up a heading that merely sits right above the diagram. Keeping such a
     * block would crop the heading into the screenshot and remove it from the text
     * layer. Dropping it here (instead of rejecting the whole cluster) keeps the
     * diagram recognisable while the heading stays where it belongs.</p>
     *
     * @return the same cluster when nothing was dropped, otherwise a cluster with
     *         the body text removed and its bounding box shrunk accordingly
     */
    private static Cluster dropBodyTextBlocks(Cluster cluster, List<IObject> mergedShapes,
                                              Set<IObject> proseBlocks) {
        List<IObject> kept = new ArrayList<>(cluster.collectedContents.size());
        boolean dropped = false;
        for (IObject content : cluster.collectedContents) {
            if (proseBlocks.contains(content) || isBodyTextBlock(content)) {
                dropped = true;
            } else {
                kept.add(content);
            }
        }
        if (!dropped) {
            return cluster;
        }
        BoundingBox box = cluster.shapeBox == null ? null : new BoundingBox(cluster.shapeBox);
        for (IObject content : kept) {
            BoundingBox contentBox = content.getBoundingBox();
            if (contentBox == null || contentBox.isEmpty()) {
                continue;
            }
            if (box == null || box.isEmpty()) {
                box = new BoundingBox(contentBox);
            } else {
                box.union(contentBox);
            }
        }
        if (box == null || box.isEmpty()) {
            return cluster;
        }
        return new Cluster(mergedShapes, kept, box);
    }

    /**
     * Returns true when the cluster absorbed what looks like body text: a heading or
     * paragraph taller than a single text line. Such captures are almost always
     * over-eager growth swallowing surrounding prose (e.g. a section title right
     * above the diagram) rather than a real flowchart label.
     */
    private static boolean containsBodyText(Cluster cluster) {
        for (IObject content : cluster.collectedContents) {
            if (isBodyTextBlock(content)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the given content is a body-text block rather than a diagram
     * label (see {@link #isDiagramLabel}). Such a block has to stay in the text layer
     * — cropping it into the screenshot would duplicate it and lose the original
     * paragraph.
     */
    private static boolean isBodyTextBlock(IObject content) {
        if (!(content instanceof SemanticHeading) && !(content instanceof CustomSemanticParagraph)) {
            return false;
        }
        return !isDiagramLabel(content);
    }

    private static boolean isRegularTable(Cluster cluster) {
        if (cluster.tableCount == 0) {
            return false;
        }
        double maxTableArea = 0.0;
        for (IObject content : cluster.collectedContents) {
            if (content instanceof TableBorder) {
                TableBorder table = (TableBorder) content;
                double currentTableArea = table.getBoundingBox().getArea();
                if (currentTableArea > maxTableArea) {
                    maxTableArea = currentTableArea;
                }
            } else if (content instanceof Table) {
                Table table = (Table) content;
                double currentTableArea = table.getBoundingBox().getArea();
                if (currentTableArea > maxTableArea) {
                    maxTableArea = currentTableArea;
                }
            }
        }
        double clusterArea = cluster.boundingBox.getArea();
        if (clusterArea <= 0) {
            return false;
        }
        // The table has to cover a substantial part of the cluster to make the whole
        // region "a table". Counting cells alone is not enough: the line preprocessing
        // routinely splits a diagram's node boxes and connectors into several small
        // 2x2 / 3x3 "tables", and a single such fragment used to veto the entire
        // flowchart (the diagram stayed in the text layer and was emitted as a series
        // of bogus tables). A real table page has its table roughly coinciding with
        // the shape cluster, so the ratio there is close to (or above) 1.
        return maxTableArea / clusterArea > REGULAR_TABLE_AREA_RATIO;
    }

    private static final class Cluster {
        final int shapeCount;
        final int rectangleCount;
        final int polylineCount;
        final int arrowCount;
        final int totalComponents;
        final int textCount;
        final int imageCount;
        final int tableCount;
        final List<IObject> collectedContents;
        final BoundingBox boundingBox;
        /**
         * Union bounding box of the group itself (without the collected neighbours);
         * for arrow-driven regions the group also holds the raw lines it was grown
         * through, so this box is larger than the union of its shapes.
         */
        final BoundingBox shapeBox;

        Cluster(List<IObject> shapeGroup, List<IObject> collectedContents, BoundingBox boundingBox) {
            this.collectedContents = new ArrayList<>(collectedContents);
            this.boundingBox = new BoundingBox(boundingBox);

            int shapeCount = 0;
            int rectangleCount = 0;
            int polylineCount = 0;
            int arrowCount = 0;
            int totalComponents = 0;
            BoundingBox shapesUnion = null;
            for (IObject obj : shapeGroup) {
                if (obj instanceof ShapeChunk) {
                    ShapeChunk shape = (ShapeChunk) obj;
                    shapeCount++;
                    totalComponents += shape.getComponentCount();
                    BoundingBox shapeBBox = shape.getBoundingBox();
                    if (shapeBBox != null && !shapeBBox.isEmpty()) {
                        if (shapesUnion == null) {
                            shapesUnion = new BoundingBox(shapeBBox);
                        } else {
                            shapesUnion.union(shapeBBox);
                        }
                    }
                    String type = shape.getShapeType();
                    if (ShapeChunk.TYPE_RECTANGLE.equals(type)) {
                        rectangleCount++;
                    } else if (ShapeChunk.TYPE_POLYLINE.equals(type)) {
                        polylineCount++;
                    } else if (ShapeChunk.TYPE_ARROW.equals(type)
                            || ShapeChunk.TYPE_ARROW_HEADER.equals(type)) {
                        // A head is a part of an arrow: an arrow-driven group may hold the
                        // head without the shaft having been accepted as a connector.
                        arrowCount++;
                    }
                }
            }
            this.shapeBox = shapesUnion;
            this.shapeCount = shapeCount;
            this.rectangleCount = rectangleCount;
            this.polylineCount = polylineCount;
            this.arrowCount = arrowCount;
            this.totalComponents = totalComponents;

            int textCount = 0;
            int imageCount = 0;
            int tableCount = 0;
            for (IObject content : collectedContents) {
                if (content instanceof TextChunk || content instanceof TextLine
                        || content instanceof SemanticTextNode || content instanceof CustomSemanticParagraph
                        || content instanceof SemanticHeading) {
                    textCount++;
                } else if (content instanceof ImageChunk) {
                    imageCount++;
                } else if (content instanceof TableBorder || content instanceof Table) {
                    tableCount++;
                }
            }
            this.textCount = textCount;
            this.imageCount = imageCount;
            this.tableCount = tableCount;
        }
    }
}
