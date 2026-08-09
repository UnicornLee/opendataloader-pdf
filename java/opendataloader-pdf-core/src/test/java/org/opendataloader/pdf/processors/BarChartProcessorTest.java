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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class BarChartProcessorTest {

    private static final double[] GRAY = new double[]{0.5, 0.5, 0.5};

    @Test
    void detectsAndReplacesBarChartGroup() {
        List<IObject> pageContents = new ArrayList<>();
        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 200, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 210, 100, 310, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk frame = new ShapeChunk(new BoundingBox(0, 100, 100, 310, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        // Caption inside the vertical extent of the bar chart group, so it is also removed.
        ShapeChunk caption = new ShapeChunk(new BoundingBox(0, 100, 130, 310, 150), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        // Far-away logo stays untouched (vertically outside the group bbox).
        ImageChunk logo = new ImageChunk(new BoundingBox(0, 400, 400, 440, 440));

        List<IObject> group = Arrays.asList(bar1, bar2, frame);
        pageContents.addAll(group);
        pageContents.add(caption);
        pageContents.add(logo);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        BarChartProcessor.processBarChartGroups(pageContents, Collections.singletonList(group), imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size(), "Bar chart group should produce exactly one screenshot");
        // The 3 group shapes + the inner caption are removed; the far logo and the new screenshot remain.
        Assertions.assertEquals(2, pageContents.size(),
                "Original group + inner caption should be removed; far-away logo and the new screenshot remain");
        // The new screenshot's bbox equals the union bbox of the group.
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(100.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(310.0, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(100.0, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(300.0, bbox.getTopY(), 0.0001);
        Assertions.assertTrue(pageContents.contains(logo), "Far-away logo should be preserved");
        Assertions.assertTrue(pageContents.stream().anyMatch(o -> o == imagesUtils.saved.get(0)),
                "The new screenshot should be appended to pageContents");
    }

    @Test
    void ignoresGroupsWithoutBarChart() {
        List<IObject> pageContents = new ArrayList<>();
        ShapeChunk rect = new ShapeChunk(new BoundingBox(0, 100, 100, 200, 200), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        ShapeChunk line = new ShapeChunk(new BoundingBox(0, 100, 100, 200, 200), ShapeChunk.TYPE_POLYLINE, GRAY, 1);

        List<IObject> group = Arrays.asList(rect, line);
        pageContents.addAll(group);

        int sizeBefore = pageContents.size();
        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        BarChartProcessor.processBarChartGroups(pageContents, Collections.singletonList(group), imagesUtils, 0);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "No bar chart means no screenshot");
        Assertions.assertEquals(sizeBefore, pageContents.size(), "pageContents should be untouched");
    }

    @Test
    void nullOrEmptyInputsAreNoOp() {
        List<IObject> pageContents = new ArrayList<>();
        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();

        Assertions.assertDoesNotThrow(() ->
                BarChartProcessor.processBarChartGroups(null, null, imagesUtils, 0));
        Assertions.assertDoesNotThrow(() ->
                BarChartProcessor.processBarChartGroups(pageContents, null, imagesUtils, 0));
        Assertions.assertDoesNotThrow(() ->
                BarChartProcessor.processBarChartGroups(pageContents, Collections.emptyList(), imagesUtils, 0));
        Assertions.assertEquals(0, imagesUtils.saved.size());
    }
}
