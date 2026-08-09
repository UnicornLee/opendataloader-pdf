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

import java.util.List;

/**
 * Detects bar-chart regions inside the grouped {@link
 * org.opendataloader.pdf.entities.content.ShapeChunk}s produced by
 * {@link ShapeRecognizer#groupShapes}. For every group whose entries include
 * a {@link org.opendataloader.pdf.entities.content.ShapeChunk#TYPE_BAR_CHART}
 * shape, the group's bounding box is rendered as a single screenshot, the
 * original contents inside that area are removed, and the screenshot is
 * inserted as an {@link ImageChunk}.
 */
public final class BarChartProcessor {

    private BarChartProcessor() {
    }

    /**
     * Processes every shape group from {@code groupedShapeChunks}, replacing
     * any group that contains a bar-chart shape with a single
     * {@link ImageChunk} covering the union bbox of the group.
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
        for (List<IObject> group : groupedShapeChunks) {
            if (group == null || group.isEmpty() || !BoundingBoxGroupUtils.containsBarChart(group)) {
                continue;
            }
            BoundingBox groupBox = BoundingBoxGroupUtils.unionBoundingBoxes(group, pageNumber);
            if (groupBox == null || groupBox.isEmpty()) {
                continue;
            }
            pageContents.removeIf(content -> BoundingBoxGroupUtils.isVerticallyMostlyInside(groupBox, content.getBoundingBox()));
            ImageChunk imageChunk = new ImageChunk(groupBox);
            imagesUtils.saveImageChunk(imageChunk);
            pageContents.add(imageChunk);
        }
    }
}
