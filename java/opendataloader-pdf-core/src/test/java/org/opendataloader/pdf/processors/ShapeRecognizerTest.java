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
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class ShapeRecognizerTest {

    @Test
    void emptyArtifactsYieldsNoShapes() {
        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, Collections.emptyList());
        Assertions.assertTrue(shapes.isEmpty());
    }

    @Test
    void recognizesSingleFilledRectangle() {
        List<IChunk> artifacts = new ArrayList<>();
        // A 10x8 filled rectangle, represented as a thick horizontal line
        LineChunk line = new LineChunk(0, 5, 4, 45, 4, 8, new double[]{0.2, 0.4, 0.6});
        artifacts.add(line);

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        Assertions.assertEquals(1, shapes.size());
        ShapeChunk shape = shapes.get(0);
        Assertions.assertEquals(ShapeChunk.TYPE_RECTANGLE, shape.getShapeType());
        Assertions.assertArrayEquals(new double[]{0.2, 0.4, 0.6}, shape.getColor(), 0.0001);
        Assertions.assertEquals(1, shape.getComponentCount());
        Assertions.assertEquals(48, shape.getWidth(), 0.0001);
        Assertions.assertEquals(8, shape.getHeight(), 0.0001);
    }

    @Test
    void recognizesBarChartFromAlignedRectangles() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.1, 0.5, 0.9};
        // Three vertical bars with same bottom, same width, different heights
        artifacts.add(new LineChunk(0, 10, 5, 12, 25, 2, color)); // bar 1, height 20
        artifacts.add(new LineChunk(0, 15, 5, 17, 35, 2, color)); // bar 2, height 30
        artifacts.add(new LineChunk(0, 20, 5, 22, 20, 2, color)); // bar 3, height 15

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        Assertions.assertEquals(1, shapes.size());
        ShapeChunk shape = shapes.get(0);
        Assertions.assertEquals(ShapeChunk.TYPE_BAR_CHART, shape.getShapeType());
        Assertions.assertEquals(3, shape.getComponentCount());
    }

    @Test
    void recognizesPolylineFromConnectedLineSegments() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        // Three connected thin segments forming an ascending line
        artifacts.add(new LineChunk(0, 0, 0, 10, 10, 0.5, color));
        artifacts.add(new LineChunk(0, 10, 10, 20, 20, 0.5, color));
        artifacts.add(new LineChunk(0, 20, 20, 35, 25, 0.5, color));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        Assertions.assertEquals(1, shapes.size());
        ShapeChunk shape = shapes.get(0);
        Assertions.assertEquals(ShapeChunk.TYPE_POLYLINE, shape.getShapeType());
        Assertions.assertEquals(3, shape.getComponentCount());
    }

    @Test
    void separatesShapesByColor() {
        List<IChunk> artifacts = new ArrayList<>();
        artifacts.add(new LineChunk(0, 0, 0, 10, 10, 0.5, new double[]{1.0, 0.0, 0.0}));
        artifacts.add(new LineChunk(0, 10, 10, 20, 20, 0.5, new double[]{1.0, 0.0, 0.0}));
        artifacts.add(new LineChunk(0, 0, 20, 10, 30, 0.5, new double[]{0.0, 1.0, 0.0}));
        artifacts.add(new LineChunk(0, 10, 30, 20, 40, 0.5, new double[]{0.0, 1.0, 0.0}));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        Assertions.assertEquals(2, shapes.size());
    }

    @Test
    void pullsLineChunksFromLineArtChunk() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.5, 0.5, 0.5};
        LineChunk line1 = new LineChunk(0, 0, 0, 10, 10, 0.5, color);
        LineChunk line2 = new LineChunk(0, 10, 10, 20, 20, 0.5, color);
        org.verapdf.wcag.algorithms.entities.content.LineArtChunk lineArt =
                new org.verapdf.wcag.algorithms.entities.content.LineArtChunk(
                        new BoundingBox(0, 0, 0, 20, 20),
                        Arrays.asList(line1, line2));
        artifacts.add(lineArt);

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        Assertions.assertEquals(1, shapes.size());
        Assertions.assertEquals(ShapeChunk.TYPE_POLYLINE, shapes.get(0).getShapeType());
        Assertions.assertEquals(2, shapes.get(0).getComponentCount());
    }

    @Test
    void recognizesSingleSegmentConnectorBetweenShapes() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        // Two filled rectangles (boxes)
        artifacts.add(new LineChunk(0, 10, 10, 40, 12, 2, color)); // bottom box
        artifacts.add(new LineChunk(0, 10, 20, 40, 22, 2, color)); // top box
        // Single thin vertical line between the boxes (arrow/connector)
        artifacts.add(new LineChunk(0, 25, 12, 25, 20, 0.5, color));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        long connectorCount = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()) && s.getComponentCount() == 1)
                .count();
        Assertions.assertEquals(1, connectorCount, "Single-segment connector between two boxes should be recognized as arrow");
        Assertions.assertEquals(3, shapes.size(), "Should have two rectangles and one arrow");
    }

    @Test
    void arrowBoundingBoxIncludesFilledArrowhead() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        // Two boxes the connector bridges, straddling a vertical shaft at x=300.
        // (Box bbox is inflated by width/2, so these are clearly filled rects.)
        artifacts.add(new LineChunk(0, 280, 150, 320, 150, 20, color)); // box below shaft start
        artifacts.add(new LineChunk(0, 280, 190, 320, 190, 20, color)); // box above shaft end
        // Thin vertical shaft from y=162 to y=178 (bbox 161.5..178.5).
        artifacts.add(new LineChunk(0, 300, 162, 300, 178, 1.0, color));
        // Filled arrowhead (bbox-only LineArtChunk) extending below the shaft bottom.
        artifacts.add(new LineArtChunk(new BoundingBox(0, 295, 157, 305, 178.5)));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an arrow"));
        // Arrowhead tip at y=157 is below the shaft bottom (161.5): arrow bbox must reach it.
        Assertions.assertEquals(157.0, arrow.getBoundingBox().getBottomY(), 0.0001,
                "Arrow bbox must include the filled arrowhead below the shaft");
        Assertions.assertEquals(295.0, arrow.getBoundingBox().getLeftX(), 0.0001);
        Assertions.assertEquals(305.0, arrow.getBoundingBox().getRightX(), 0.0001);
        Assertions.assertEquals(178.5, arrow.getBoundingBox().getTopY(), 0.0001);
    }

    @Test
    void arrowWithoutArrowheadKeepsShaftBoundingBox() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        artifacts.add(new LineChunk(0, 280, 150, 320, 150, 20, color));
        artifacts.add(new LineChunk(0, 280, 190, 320, 190, 20, color));
        // Thin shaft with no accompanying filled arrowhead.
        artifacts.add(new LineChunk(0, 300, 162, 300, 178, 1.0, color));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Arrow expected"));
        // No arrowhead region: bbox is exactly the shaft.
        Assertions.assertEquals(161.5, arrow.getBoundingBox().getBottomY(), 0.0001);
        Assertions.assertEquals(178.5, arrow.getBoundingBox().getTopY(), 0.0001);
    }

    @Test
    void largeContainerFillIsNotTreatedAsArrowhead() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        artifacts.add(new LineChunk(0, 280, 150, 320, 150, 20, color));
        artifacts.add(new LineChunk(0, 280, 190, 320, 190, 20, color));
        artifacts.add(new LineChunk(0, 300, 162, 300, 178, 1.0, color));
        // A large filled container that happens to contain the shaft: must be ignored.
        artifacts.add(new LineArtChunk(new BoundingBox(0, 100, 100, 500, 300)));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected arrow"));
        Assertions.assertEquals(161.5, arrow.getBoundingBox().getBottomY(), 0.0001,
                "Large container must not inflate the arrow bbox");
    }

    @Test
    void midLineFillIsNotTreatedAsArrowhead() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        artifacts.add(new LineChunk(0, 280, 150, 320, 150, 20, color));
        artifacts.add(new LineChunk(0, 280, 190, 320, 190, 20, color));
        artifacts.add(new LineChunk(0, 300, 162, 300, 178, 1.0, color));
        // A small filled dot in the middle of the shaft (extends neither end): ignored.
        artifacts.add(new LineArtChunk(new BoundingBox(0, 297, 165, 303, 175)));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("arrow expected"));
        Assertions.assertEquals(161.5, arrow.getBoundingBox().getBottomY(), 0.0001);
        Assertions.assertEquals(178.5, arrow.getBoundingBox().getTopY(), 0.0001);
    }

    @Test
    void arrowheadRecoveredFromPdfBoxFillWhenArtifactMerged() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        artifacts.add(new LineChunk(0, 280, 100, 320, 100, 20, color)); // box below
        artifacts.add(new LineChunk(0, 280, 220, 320, 220, 20, color)); // box above
        // Thin vertical shaft from y=110 to y=210 (bbox 109.5..210.5).
        artifacts.add(new LineChunk(0, 300, 110, 300, 210, 1.0, color));
        // The arrowhead's stroke segments were absorbed into an MCID container, so
        // there is no bbox-only LineArtChunk for it in the artifact layer — only the
        // merged segments (here reconstructed as a triangle outline).
        artifacts.add(new LineArtChunk(
                new BoundingBox(0, 295.5, 103.5, 304.5, 118.5),
                Arrays.asList(
                        new LineChunk(0, 300, 104, 296, 118, 1.0, color),
                        new LineChunk(0, 296, 118, 304, 118, 1.0, color),
                        new LineChunk(0, 304, 118, 300, 104, 1.0, color))));
        // PDFBox raw fill for the whole closed arrow path (shaft + triangle), y-up.
        List<BoundingBox> fillBoxes =
                Collections.singletonList(new BoundingBox(0, 296, 104, 302, 210.5));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts, fillBoxes);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an arrow"));
        // Triangle tip at y=104 is below the shaft bottom (109.5): the fallback fill
        // must extend the arrow bbox to it even though the artifact head was merged.
        Assertions.assertEquals(104.0, arrow.getBoundingBox().getBottomY(), 0.0001,
                "PDFBox fallback fill must extend the arrow bbox to the arrowhead tip");
        Assertions.assertEquals(296.0, arrow.getBoundingBox().getLeftX(), 0.0001);
        Assertions.assertEquals(302.0, arrow.getBoundingBox().getRightX(), 0.0001);
        Assertions.assertEquals(210.5, arrow.getBoundingBox().getTopY(), 0.0001);
    }

    @Test
    void rectangleNodeFillIsNotTreatedAsArrowheadViaFallback() {
        List<IChunk> artifacts = new ArrayList<>();
        double[] color = new double[]{0.0, 0.0, 0.0};
        // Small filled node box the shaft starts from (bbox 294..306 x 147..161).
        artifacts.add(new LineChunk(0, 297, 150, 303, 158, 6, color));
        artifacts.add(new LineChunk(0, 280, 195, 320, 195, 20, color)); // box above
        // Thin vertical shaft from y=158 to y=195 (bbox 157.5..195.5).
        artifacts.add(new LineChunk(0, 300, 158, 300, 195, 1.0, color));
        // PDFBox raw fill for the small node box itself (overlaps the shaft start).
        List<BoundingBox> fillBoxes =
                Collections.singletonList(new BoundingBox(0, 297, 150, 303, 160));

        List<ShapeChunk> shapes = ShapeRecognizer.recognizePage(0, artifacts, fillBoxes);
        ShapeChunk arrow = shapes.stream()
                .filter(s -> ShapeChunk.TYPE_ARROW.equals(s.getShapeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an arrow"));
        // The node fill coincides with the recognized node rectangle, so it must not
        // be consumed as an arrowhead: the arrow bbox stays exactly the shaft.
        Assertions.assertEquals(157.5, arrow.getBoundingBox().getBottomY(), 0.0001,
                "Coincident node fill must not inflate the arrow bbox");
        Assertions.assertEquals(195.5, arrow.getBoundingBox().getTopY(), 0.0001);
    }

    @Test
    void groupShapesMergesShapesWithTwoUnitVerticalGap() {
        // Two shapes overlapping in x, separated vertically by exactly the 2-unit tolerance.
        ShapeChunk bottom = shape(0, 10, 10, 30, 20);
        ShapeChunk top = shape(0, 10, 22, 30, 32);

        List<List<IObject>> groups = ShapeRecognizer.groupShapes(Arrays.asList(bottom, top));

        Assertions.assertEquals(1, groups.size(), "A vertical gap of 2 should still be grouped");
        Assertions.assertEquals(2, groups.get(0).size());
    }

    @Test
    void groupShapesKeepsShapesWithGapLargerThanTwoSeparate() {
        // Vertical gap of 3 exceeds the 2-unit tolerance: must stay separate.
        ShapeChunk bottom = shape(0, 10, 10, 30, 20);
        ShapeChunk top = shape(0, 10, 23, 30, 33);

        List<List<IObject>> groups = ShapeRecognizer.groupShapes(Arrays.asList(bottom, top));

        Assertions.assertEquals(2, groups.size(), "A vertical gap of 3 should not be grouped");
    }

    @Test
    void groupShapesMergesOverlappingShapes() {
        ShapeChunk a = shape(0, 10, 10, 30, 20);
        ShapeChunk b = shape(0, 15, 15, 35, 25);

        List<List<IObject>> groups = ShapeRecognizer.groupShapes(Arrays.asList(a, b));

        Assertions.assertEquals(1, groups.size());
        Assertions.assertEquals(2, groups.get(0).size());
    }

    @Test
    void groupShapesIgnoresShapesDisjointInX() {
        // Same y tolerance applies, but x-ranges are far apart: keep separate.
        ShapeChunk left = shape(0, 10, 10, 30, 20);
        ShapeChunk right = shape(0, 50, 22, 70, 32);

        List<List<IObject>> groups = ShapeRecognizer.groupShapes(Arrays.asList(left, right));

        Assertions.assertEquals(2, groups.size());
    }

    private static ShapeChunk shape(int page, double left, double bottom, double right, double top) {
        return new ShapeChunk(
                new BoundingBox(page, left, bottom, right, top),
                ShapeChunk.TYPE_RECTANGLE,
                new double[]{0.0, 0.0, 0.0},
                1);
    }
}
