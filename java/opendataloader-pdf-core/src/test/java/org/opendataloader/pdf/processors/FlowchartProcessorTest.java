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
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class FlowchartProcessorTest {

    private static final double[] GRAY = new double[]{0.5, 0.5, 0.5};
    private static final double[] BLACK = new double[]{0.0, 0.0, 0.0};

    @Test
    void detectsMixedShapesFlowchart() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Three wide boxes + two connectors, all overlapping
        ShapeChunk box1 = new ShapeChunk(new BoundingBox(0, 100, 400, 180, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box2 = new ShapeChunk(new BoundingBox(0, 100, 300, 180, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box3 = new ShapeChunk(new BoundingBox(0, 100, 200, 180, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk line1 = new ShapeChunk(new BoundingBox(0, 140, 240, 142, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk line2 = new ShapeChunk(new BoundingBox(0, 140, 340, 142, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        TextChunk label = new TextChunk("start");
        label.setBoundingBox(new BoundingBox(0, 110, 410, 170, 430));

        List<IObject> group = Arrays.asList(box1, box2, box3, line1, line2);
        grouped.add(group);
        pageContents.addAll(group);
        pageContents.add(label);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size(), "Flowchart should be saved as one image");
        Assertions.assertEquals(1, pageContents.size(), "Original shapes and label should be replaced by one image");
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
    }

    @Test
    void screenshotBoundingBoxExpandsVerticallyByTolerance() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Union bbox of the group is (100, 200)-(180, 440).
        ShapeChunk box1 = new ShapeChunk(new BoundingBox(0, 100, 400, 180, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box2 = new ShapeChunk(new BoundingBox(0, 100, 300, 180, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box3 = new ShapeChunk(new BoundingBox(0, 100, 200, 180, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk line1 = new ShapeChunk(new BoundingBox(0, 140, 240, 142, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk line2 = new ShapeChunk(new BoundingBox(0, 140, 340, 142, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);

        List<IObject> group = Arrays.asList(box1, box2, box3, line1, line2);
        grouped.add(group);
        pageContents.addAll(group);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        // Horizontal margin 5: 100-5=95, 180+5=185. Y tolerance: topY+1=441, bottomY-1=199.
        Assertions.assertEquals(95.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(185.0, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(199.0, bbox.getBottomY(), 0.0001, "bottomY should be reduced by the vertical tolerance");
        Assertions.assertEquals(441.0, bbox.getTopY(), 0.0001, "topY should be increased by the vertical tolerance");
    }

    @Test
    void detectsCompositeContentFlowchart() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Two images + one table + one text, with a few connecting lines around them
        ImageChunk img1 = new ImageChunk(new BoundingBox(0, 100, 400, 200, 480));
        ImageChunk img2 = new ImageChunk(new BoundingBox(0, 100, 200, 200, 280));
        TextChunk text = new TextChunk("label");
        text.setBoundingBox(new BoundingBox(0, 110, 300, 190, 360));
        TableBorder table = new TableBorder(1, 1);
        table.setBoundingBox(new BoundingBox(0, 100, 100, 200, 180));
        ShapeChunk line1 = new ShapeChunk(new BoundingBox(0, 150, 280, 152, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 3);
        ShapeChunk line2 = new ShapeChunk(new BoundingBox(0, 150, 180, 152, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 3);

        List<IObject> shapeGroup = Arrays.asList(line1, line2);
        grouped.add(shapeGroup);
        pageContents.add(img1);
        pageContents.add(img2);
        pageContents.add(text);
        pageContents.add(table);
        pageContents.addAll(shapeGroup);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size(), "Composite flowchart should be saved as one image");
        Assertions.assertEquals(1, pageContents.size());
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
    }

    @Test
    void skipsRegularTable() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // 3x3 table with border-like shapes around it
        TableBorder table = new TableBorder(3, 3);
        table.setBoundingBox(new BoundingBox(0, 100, 100, 300, 300));
        ShapeChunk border1 = new ShapeChunk(new BoundingBox(0, 100, 100, 300, 102), ShapeChunk.TYPE_RECTANGLE, GRAY, 2);
        ShapeChunk border2 = new ShapeChunk(new BoundingBox(0, 100, 100, 102, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 2);
        ShapeChunk border3 = new ShapeChunk(new BoundingBox(0, 298, 100, 300, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 2);
        ShapeChunk border4 = new ShapeChunk(new BoundingBox(0, 100, 298, 300, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 2);

        List<IObject> group = Arrays.asList(border1, border2, border3, border4);
        grouped.add(group);
        pageContents.add(table);
        pageContents.addAll(group);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "Regular table should not be treated as flowchart");
        Assertions.assertEquals(5, pageContents.size(), "Original contents should be untouched");
    }

    @Test
    void skipsSinglePolylineOrLineChart() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // A single connected line with many segments, but only one shape
        ShapeChunk line = new ShapeChunk(new BoundingBox(0, 100, 100, 300, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 8);
        grouped.add(Collections.singletonList(line));
        pageContents.add(line);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "Single polyline should not be treated as flowchart");
        Assertions.assertEquals(1, pageContents.size(), "Original content should be untouched");
    }

    @Test
    void skipsSingleImageWithCaption() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        ImageChunk img = new ImageChunk(new BoundingBox(0, 100, 100, 300, 300));
        TextChunk caption = new TextChunk("Figure 1");
        caption.setBoundingBox(new BoundingBox(0, 100, 80, 300, 100));
        pageContents.add(img);
        pageContents.add(caption);
        // No shape group

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "Image + caption without shapes should not be treated as flowchart");
        Assertions.assertEquals(2, pageContents.size(), "Original contents should be untouched");
    }

    @Test
    void skipsBarChartGroup() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        ShapeChunk bar1 = new ShapeChunk(new BoundingBox(0, 100, 100, 120, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar2 = new ShapeChunk(new BoundingBox(0, 130, 100, 150, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        ShapeChunk bar3 = new ShapeChunk(new BoundingBox(0, 160, 100, 180, 300), ShapeChunk.TYPE_BAR_CHART, GRAY, 1);
        grouped.add(Arrays.asList(bar1, bar2, bar3));
        pageContents.addAll(grouped.get(0));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "Bar chart group should be skipped");
        Assertions.assertEquals(3, pageContents.size(), "Bar chart contents should be untouched");
    }

    @Test
    void mergesSubsequentGroupIntersectingScreenshotBox() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Group 1: flowchart, union bbox (100,200)-(180,440), screenshot (95,199)-(185,441).
        ShapeChunk box1 = new ShapeChunk(new BoundingBox(0, 100, 400, 180, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box2 = new ShapeChunk(new BoundingBox(0, 100, 300, 180, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box3 = new ShapeChunk(new BoundingBox(0, 100, 200, 180, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk line1 = new ShapeChunk(new BoundingBox(0, 140, 240, 142, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk line2 = new ShapeChunk(new BoundingBox(0, 140, 340, 142, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        List<IObject> group1 = Arrays.asList(box1, box2, box3, line1, line2);

        // Group 2: own flowchart, disjoint from group1 in x but intersecting
        // group1's screenshot margin; union bbox (181,200)-(250,440).
        ShapeChunk g2a = new ShapeChunk(new BoundingBox(0, 181, 400, 250, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2b = new ShapeChunk(new BoundingBox(0, 181, 300, 250, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2c = new ShapeChunk(new BoundingBox(0, 181, 200, 250, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2l1 = new ShapeChunk(new BoundingBox(0, 200, 240, 202, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk g2l2 = new ShapeChunk(new BoundingBox(0, 200, 340, 202, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        List<IObject> group2 = Arrays.asList(g2a, g2b, g2c, g2l1, g2l2);

        grouped.add(group1);
        grouped.add(group2);
        pageContents.addAll(group1);
        pageContents.addAll(group2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(1, imagesUtils.saved.size(), "Intersecting later group should be merged into one screenshot");
        Assertions.assertEquals(1, pageContents.size(), "Absorbed shapes should be replaced by one image");
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(95.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(250.0, bbox.getRightX(), 0.0001, "Screenshot should cover the absorbed group");
        Assertions.assertEquals(199.0, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(441.0, bbox.getTopY(), 0.0001);
    }

    @Test
    void skipsAbsorbedGroupAndStillProcessesRemainingGroups() {
        List<IObject> pageContents = new ArrayList<>();
        List<List<IObject>> grouped = new ArrayList<>();

        // Group 1: flowchart at x 100-180.
        ShapeChunk box1 = new ShapeChunk(new BoundingBox(0, 100, 400, 180, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box2 = new ShapeChunk(new BoundingBox(0, 100, 300, 180, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk box3 = new ShapeChunk(new BoundingBox(0, 100, 200, 180, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk line1 = new ShapeChunk(new BoundingBox(0, 140, 240, 142, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk line2 = new ShapeChunk(new BoundingBox(0, 140, 340, 142, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        List<IObject> group1 = Arrays.asList(box1, box2, box3, line1, line2);

        // Group 2: flowchart that will be absorbed into group 1 (x 181-250).
        ShapeChunk g2a = new ShapeChunk(new BoundingBox(0, 181, 400, 250, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2b = new ShapeChunk(new BoundingBox(0, 181, 300, 250, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2c = new ShapeChunk(new BoundingBox(0, 181, 200, 250, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk g2l1 = new ShapeChunk(new BoundingBox(0, 200, 240, 202, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk g2l2 = new ShapeChunk(new BoundingBox(0, 200, 340, 202, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        List<IObject> group2 = Arrays.asList(g2a, g2b, g2c, g2l1, g2l2);

        // Group 3: independent flowchart far away (x 400-480), must still be processed.
        ShapeChunk b1 = new ShapeChunk(new BoundingBox(0, 400, 400, 480, 440), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk b2 = new ShapeChunk(new BoundingBox(0, 400, 300, 480, 340), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk b3 = new ShapeChunk(new BoundingBox(0, 400, 200, 480, 240), ShapeChunk.TYPE_RECTANGLE, GRAY, 4);
        ShapeChunk l1 = new ShapeChunk(new BoundingBox(0, 430, 240, 432, 300), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        ShapeChunk l2 = new ShapeChunk(new BoundingBox(0, 430, 340, 432, 400), ShapeChunk.TYPE_POLYLINE, BLACK, 2);
        List<IObject> group3 = Arrays.asList(b1, b2, b3, l1, l2);

        grouped.add(group1);
        grouped.add(group2);
        grouped.add(group3);
        pageContents.addAll(group1);
        pageContents.addAll(group2);
        pageContents.addAll(group3);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        FlowchartProcessor.processFlowchartGroups(pageContents, grouped, imagesUtils, 0);

        Assertions.assertEquals(2, imagesUtils.saved.size(),
                "Absorbed group 2 must be skipped; group 1 and group 3 each produce a screenshot");
        Assertions.assertEquals(2, pageContents.size(), "Two independent screenshots should remain");
    }

}
