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

import java.util.List;

/**
 * Detects pie-chart regions and renders them as a single screenshot, mirroring
 * the behaviour of {@link BarChartProcessor} but for {@link
 * ShapeChunk#TYPE_PIE_CHART} groups.
 *
 * <p>The actual recognition happens upstream in {@link ShapeRecognizer}
 * (corner-clustering on the PDFBox fill boxes); this processor only turns the
 * recognized pie group into a cropped image. It reuses the shared chart-region
 * logic in {@link BarChartProcessor#processChartGroups} so that pie screenshots
 * keep the same boundary policy as bar charts: the disc plus its leader lines,
 * percentage labels and legend (axes are absent for a pie, which the shared code
 * handles gracefully).</p>
 */
public final class PieChartProcessor {

    private PieChartProcessor() {
    }

    /**
     * Processes every shape group that contains a pie chart, replacing it with a
     * single {@link org.verapdf.wcag.algorithms.entities.content.ImageChunk}.
     *
     * <p>Must run after {@link BarChartProcessor} (so bar charts are consumed
     * first) and before {@link FlowchartProcessor} (which skips pie groups).
     * Both processors share the underlying shape groups produced by
     * {@link ShapeRecognizer#groupShapes}.</p>
     *
     * @param pageContents       the current page contents (will be modified)
     * @param groupedShapeChunks groups of overlapping {@link ShapeChunk}s
     * @param imagesUtils        image renderer / saver
     * @param pageNumber         0-based page number
     */
    public static void processPieChartGroups(List<IObject> pageContents,
                                             List<List<IObject>> groupedShapeChunks,
                                             ImagesUtils imagesUtils,
                                             int pageNumber) {
        BarChartProcessor.processChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber,
                ShapeChunk.TYPE_PIE_CHART);
    }
}
