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
import java.util.List;
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
    /** Horizontal expansion applied to the final flowchart screenshot bbox. */
    private static final double SCREENSHOT_HORIZONTAL_MARGIN = 5.0;
    /** Vertical tolerance (pt) added to the final flowchart screenshot bbox:
     *  topY is increased by this amount and bottomY is decreased by it. */
    private static final double SCREENSHOT_VERTICAL_TOLERANCE = 1.0;

    private static final double MIN_WIDTH = 80.0;
    private static final double MIN_HEIGHT = 40.0;
    private static final double MAX_ASPECT_RATIO = 6.0;
    private static final int MIN_SHAPE_COUNT = 2;
    private static final int MIN_TOTAL_COMPONENTS = 5;

    /** A regular table with at least this many cells is not treated as a flowchart. */
    private static final int REGULAR_TABLE_CELL_THRESHOLD = 6;
    /** A regular table must occupy more than this ratio of the cluster to be skipped. */
    private static final double REGULAR_TABLE_AREA_RATIO = 0.4;

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
        if (pageContents == null || groupedShapeChunks == null || imagesUtils == null) {
            return;
        }
        boolean[] skipped = new boolean[groupedShapeChunks.size()];
        for (int i = 0; i < groupedShapeChunks.size(); i++) {
            List<IObject> group = groupedShapeChunks.get(i);
            if (skipped[i] || group == null || group.isEmpty()
                    || BoundingBoxGroupUtils.containsBarChart(group)) {
                continue;
            }
            Cluster cluster = collectCluster(pageContents, group, pageNumber);
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
            do {
                expanded = false;
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
                    Cluster laterCluster = collectCluster(pageContents, laterGroup, pageNumber);
                    if (laterCluster != null) {
                        screenshotBox.union(laterCluster.boundingBox);
                        mergedBox.union(laterCluster.boundingBox);
                        mergedContents.addAll(laterCluster.collectedContents);
                    }
                    mergedShapes.addAll(laterGroup);
                    skipped[j] = true;
                    expanded = true;
                }
            } while (expanded);

            Cluster mergedCluster = new Cluster(mergedShapes, mergedContents, mergedBox);
            if (isFlowchartCluster(mergedCluster)) {
                LOGGER.log(Level.INFO, "Page {0}: detected flowchart cluster with screenshot bbox {1}",
                        new Object[]{pageNumber + 1, screenshotBox});
                pageContents.removeAll(mergedContents);
                pageContents.removeAll(mergedShapes);
                ImageChunk imageChunk = new ImageChunk(screenshotBox);
                imagesUtils.saveImageChunk(imageChunk);
                pageContents.add(imageChunk);
            }
        }
    }

    private static Cluster collectCluster(List<IObject> pageContents, List<IObject> shapeGroup, int pageNumber) {
        BoundingBox shapeBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(shapeGroup, pageNumber);
        if (shapeBox == null || shapeBox.isEmpty()) {
            return null;
        }

        List<IObject> collected = new ArrayList<>();
        BoundingBox clusterBox = new BoundingBox(shapeBox);
        collectOverlappingContents(pageContents, shapeBox, collected, clusterBox);
        // One growth step: if we found contents adjacent to the shape group, expand the
        // search box to include them and pull in any further contents that are now in range.
        // This lets a few connecting lines capture the images/text/tables inside a flowchart.
        if (!collected.isEmpty()) {
            collectOverlappingContents(pageContents, clusterBox, collected, clusterBox);
        }
        if (clusterBox.isEmpty()) {
            return null;
        }
        return new Cluster(shapeGroup, collected, clusterBox);
    }

    private static void collectOverlappingContents(List<IObject> pageContents, BoundingBox searchBox,
                                                   List<IObject> collected, BoundingBox clusterBox) {
        for (IObject content : pageContents) {
            if (content instanceof ShapeChunk || collected.contains(content)) {
                continue;
            }
            BoundingBox contentBox = content.getBoundingBox();
            if (contentBox == null || contentBox.isEmpty()) {
                continue;
            }
            if (contentBox.overlaps(searchBox, COLLECTION_MARGIN)) {
                collected.add(content);
                clusterBox.union(contentBox);
            }
        }
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
        if (cluster == null || cluster.boundingBox == null || cluster.boundingBox.isEmpty()) {
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
        if (isRegularTable(cluster)) {
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

    private static boolean isRegularTable(Cluster cluster) {
        if (cluster.tableCount == 0) {
            return false;
        }
        int maxCells = 0;
        double maxTableArea = 0.0;
        for (IObject content : cluster.collectedContents) {
            if (content instanceof TableBorder) {
                TableBorder table = (TableBorder) content;
                int currentCells = table.getNumberOfRows() * table.getNumberOfColumns();
                if (currentCells > maxCells) {
                    maxCells = currentCells;
                }
                double currentTableArea = table.getBoundingBox().getArea();
                if (currentTableArea > maxTableArea) {
                    maxTableArea = currentTableArea;
                }
            } else if (content instanceof Table) {
                Table table = (Table) content;
                int currentCells = table.getNumberOfRows() * table.getNumberOfColumns();
                if (currentCells > maxCells) {
                    maxCells = currentCells;
                }
                double currentTableArea = table.getBoundingBox().getArea();
                if (currentTableArea > maxTableArea) {
                    maxTableArea = currentTableArea;
                }
            }
        }
        if (maxCells < REGULAR_TABLE_CELL_THRESHOLD) {
            double clusterArea = cluster.boundingBox.getArea();
            return clusterArea > 0 && maxTableArea / clusterArea > REGULAR_TABLE_AREA_RATIO;
        } else {
            return true;
        }
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

        Cluster(List<IObject> shapeGroup, List<IObject> collectedContents, BoundingBox boundingBox) {
            this.collectedContents = new ArrayList<>(collectedContents);
            this.boundingBox = new BoundingBox(boundingBox);

            int shapeCount = 0;
            int rectangleCount = 0;
            int polylineCount = 0;
            int arrowCount = 0;
            int totalComponents = 0;
            for (IObject obj : shapeGroup) {
                if (obj instanceof ShapeChunk) {
                    ShapeChunk shape = (ShapeChunk) obj;
                    shapeCount++;
                    totalComponents += shape.getComponentCount();
                    String type = shape.getShapeType();
                    if (ShapeChunk.TYPE_RECTANGLE.equals(type)) {
                        rectangleCount++;
                    } else if (ShapeChunk.TYPE_POLYLINE.equals(type)) {
                        polylineCount++;
                    } else if (ShapeChunk.TYPE_ARROW.equals(type)) {
                        arrowCount++;
                    }
                }
            }
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
