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
 * Detects line-chart regions and renders them as a single screenshot, mirroring the
 * behaviour of {@link BarChartProcessor} but for {@link ShapeChunk#TYPE_LINE_CHART}
 * groups.
 *
 * <p>The actual recognition happens upstream in {@link ShapeRecognizer}: a bent line
 * that spans most of the plot area enclosed by an axis frame is retyped as
 * {@code line_chart}. This processor only turns the recognized group into a cropped
 * image. It reuses the shared chart-region logic in
 * {@link BarChartProcessor#processChartGroups} so that a line-chart screenshot keeps
 * the same boundary policy as a bar chart: plot + axes + tick / category labels +
 * legend, while the chart title, the axis unit caption and a "数据来源：…" note follow
 * the same rules as for every other chart.</p>
 *
 * <p>Without this pass a line chart is not recognized at all: its data path is a plain
 * polyline, so no chart processor claims it, and the axis frame — which the line
 * preprocessing turns into a one-cell {@code TableBorder} — vetoes the flowchart pass
 * through {@code isRegularTable} (measured: the frame covered 83 % of the cluster).
 * The page then lost the chart entirely and emitted the frame as a {@code lattice_table}.</p>
 */
public final class LineChartProcessor {

    private LineChartProcessor() {
    }

    /**
     * Processes every shape group that contains a line-chart data path, replacing it
     * with a single {@link org.verapdf.wcag.algorithms.entities.content.ImageChunk}.
     *
     * <p>Must run after {@link BarChartProcessor} and {@link PieChartProcessor} (so bar
     * and pie charts are consumed first, and their columns cannot be mistaken for a data
     * path) and before {@link FlowchartProcessor} (whose regular-table veto would
     * otherwise drop the plot frame). All three processors share the underlying shape
     * groups produced by {@link ShapeRecognizer#groupShapes}.</p>
     *
     * @param pageContents       the current page contents (will be modified)
     * @param groupedShapeChunks groups of overlapping {@link ShapeChunk}s
     * @param imagesUtils        image renderer / saver
     * @param pageNumber         0-based page number
     */
    public static void processLineChartGroups(List<IObject> pageContents,
                                              List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils,
                                              int pageNumber) {
        BarChartProcessor.processChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber,
                ShapeChunk.TYPE_LINE_CHART);
    }
}
