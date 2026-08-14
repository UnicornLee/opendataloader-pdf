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
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
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
        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 100, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 210, 100, 100, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
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
        // The new screenshot's bbox equals the union bbox of the group plus the 5pt horizontal / 1pt vertical margin.
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(95.0, bbox.getLeftX(), 0.0001, "leftX should be reduced by the horizontal margin");
        Assertions.assertEquals(315.0, bbox.getRightX(), 0.0001, "rightX should be increased by the horizontal margin");
        Assertions.assertEquals(99.0, bbox.getBottomY(), 0.0001, "bottomY should be reduced by the vertical tolerance");
        Assertions.assertEquals(301.0, bbox.getTopY(), 0.0001, "topY should be increased by the vertical tolerance");
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

    @Test
    void absorbsAdjacentOverlappingShapeGroup() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Group 1: bar chart, union bbox (100,100)-(170,300), screenshot after margin (95,99)-(175,301).
        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 130, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 140, 100, 170, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        List<IObject> group1 = Arrays.asList(bar1, bar2);

        // Group 2: rectangles that intersect group 1's screenshot margin (bbox 171-250 in x).
        ShapeChunk rect1 = new ShapeChunk(new BoundingBox(0, 171, 100, 220, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        ShapeChunk rect2 = new ShapeChunk(new BoundingBox(0, 225, 100, 250, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        List<IObject> group2 = Arrays.asList(rect1, rect2);

        grouped.add(group1);
        grouped.add(group2);
        pageContents.addAll(group1);
        pageContents.addAll(group2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        BarChartProcessor.processBarChartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size(), "Adjacent group should be merged into one screenshot");
        Assertions.assertEquals(1, pageContents.size(), "All absorbed shapes should be replaced by one image");
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(95.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(250.0, bbox.getRightX(), 0.0001, "Screenshot should cover the absorbed group");
        Assertions.assertEquals(99.0, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(301.0, bbox.getTopY(), 0.0001);
    }

    @Test
    void absorbsOverlappingPageContents() {
        List<IObject> pageContents = new ArrayList<>();

        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 130, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 140, 100, 170, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        List<IObject> group = Arrays.asList(bar1, bar2);

        // Label inside the screenshot box (overlaps within 2pt collection margin).
        TextChunk label = new TextChunk("y-axis label");
        label.setBoundingBox(new BoundingBox(0, 80, 150, 95, 250));

        // Footer far below, not absorbed.
        TextChunk footer = new TextChunk("Footer");
        footer.setBoundingBox(new BoundingBox(0, 105, 20, 200, 40));

        pageContents.addAll(group);
        pageContents.add(label);
        pageContents.add(footer);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        BarChartProcessor.processBarChartGroups(pageContents, Collections.singletonList(group), imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertFalse(pageContents.contains(label), "Overlapping label should be absorbed");
        Assertions.assertTrue(pageContents.contains(footer), "Distant footer should be preserved");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        // Group bbox (100,100)-(170,300) + margin (5,1) → (95,99)-(175,301); union with label (80,150)-(95,250)
        // extends leftX to 80.
        Assertions.assertEquals(80.0, bbox.getLeftX(), 0.0001, "leftX should extend to cover the absorbed label");
        Assertions.assertEquals(175.0, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(99.0, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(301.0, bbox.getTopY(), 0.0001);
    }

    @Test
    void keepsDistantBarChartGroupsSeparate() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 130, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 140, 100, 170, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        List<IObject> group1 = Arrays.asList(bar1, bar2);

        ShapeChunk bar3 = new ShapeChunk(new BoundingBox(0, 400, 100, 430, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar4 = new ShapeChunk(new BoundingBox(0, 440, 100, 470, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        List<IObject> group2 = Arrays.asList(bar3, bar4);

        grouped.add(group1);
        grouped.add(group2);
        pageContents.addAll(group1);
        pageContents.addAll(group2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        BarChartProcessor.processBarChartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(2, imagesUtils.saved.size(), "Distant groups should produce two separate screenshots");
        Assertions.assertEquals(2, pageContents.size(), "Two independent screenshots should remain");
        Assertions.assertTrue(pageContents.stream().allMatch(o -> o instanceof ImageChunk),
                "Only the two new image chunks should remain");
    }
}