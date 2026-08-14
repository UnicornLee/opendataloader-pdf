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

import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects bar-chart regions inside the grouped {@link
 * org.opendataloader.pdf.entities.content.ShapeChunk}s produced by
 * {@link ShapeRecognizer#groupShapes}. For every group whose entries include
 * a {@link org.opendataloader.pdf.entities.content.ShapeChunk#TYPE_BAR_CHART}
 * shape, the region's bounding box is iteratively grown by absorbing any
 * overlapping neighbouring shape groups and page contents, then the merged
 * area is rendered as a single screenshot and inserted as an
 * {@link ImageChunk}.
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
     * {@link #MAX_GROWTH_ITERATIONS} is reached.</p>
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

            pageContents.removeAll(absorbedContents);
            pageContents.removeAll(absorbedShapes);
            ImageChunk imageChunk = new ImageChunk(screenshotBox);
            imagesUtils.saveImageChunk(imageChunk);
            pageContents.add(imageChunk);
        }
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
