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
import org.verapdf.wcag.algorithms.entities.content.IChunk;
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
}
