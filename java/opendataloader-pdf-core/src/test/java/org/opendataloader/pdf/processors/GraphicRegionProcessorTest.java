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
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the fragment clustering of {@link GraphicRegionProcessor}: a tagged PDF
 * gives every part of a logo lockup its own marked group, so the lockup arrives as
 * several {@link LineArtChunk}s that are each vetoed on their own while their
 * envelope passes every gate.
 */
class GraphicRegionProcessorTest {

    private static final double[] GRAY = new double[]{0.5, 0.5, 0.5};
    private static final double PAGE_WIDTH = 595.276;
    private static final double PAGE_HEIGHT = 841.89;

    @Test
    void capturesIndividuallyVetoedFragmentsAsOneLockup() {
        // Measured on the "前標誌" lockup of a three-page announcement: a pictorial
        // mark, a glyph outline below MIN_REGION_WIDTH and a wordmark, each rejected
        // by itself and all three passing as the 170.6 x 50.2 pt envelope.
        LineArtChunk mark = lineArt(92.1, 496.4, 136.0, 545.6);
        LineArtChunk glyph = lineArt(139.1, 517.3, 162.6, 541.2);
        LineArtChunk wordmark = lineArt(138.4, 495.4, 262.7, 540.2);
        ShapeChunk underline = new ShapeChunk(new BoundingBox(0, 92.5, 496.8, 135.6, 502.1),
                ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        List<IObject> pageContents = new ArrayList<>(List.of(mark, glyph, wordmark, underline));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 1, imagesUtils,
                shortLines(10, 100, 500), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "The three fragments should be captured as one region");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(92.1, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(495.4, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(262.7, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(545.6, bbox.getTopY(), 0.0001);
        Assertions.assertFalse(pageContents.contains(mark));
        Assertions.assertFalse(pageContents.contains(glyph));
        Assertions.assertFalse(pageContents.contains(wordmark));
        Assertions.assertFalse(pageContents.contains(underline),
                "A shape fully covered by the crop should not survive it");
        Assertions.assertTrue(pageContents.contains(imagesUtils.saved.get(0)),
                "The screenshot should replace the fragments");
    }

    @Test
    void judgesDistantFragmentsSeparately() {
        // A 60 pt gutter is beyond the fragment tolerance, so the sparse second box
        // must not borrow the density of the first one.
        LineArtChunk dense = lineArt(50, 500, 90, 540);
        LineArtChunk sparse = lineArt(150, 500, 190, 540);
        List<IObject> pageContents = new ArrayList<>(List.of(dense, sparse));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 55, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(50.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(90.0, bbox.getRightX(), 0.0001);
        Assertions.assertTrue(pageContents.contains(sparse),
                "The vetoed fragment should stay untouched");
    }

    @Test
    void fallsBackToIndividualJudgementWhenTheEnvelopeReachesAnImage() {
        // Growing an envelope is the point of clustering, so it has an extra gate: it
        // may not swallow a neighbouring raster figure, which would then be rendered
        // twice. The fragments are still worth judging on their own.
        LineArtChunk left = lineArt(100, 500, 160, 540);
        LineArtChunk right = lineArt(165, 500, 205, 540);
        ImageChunk raster = new ImageChunk(new BoundingBox(0, 170, 495, 210, 545));
        List<IObject> pageContents = new ArrayList<>(List.of(left, right, raster));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 105, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "Only the fragment that passes on its own should be captured");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(100.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(160.0, bbox.getRightX(), 0.0001);
        Assertions.assertTrue(pageContents.contains(raster), "The raster image must survive");
    }

    @Test
    void capturesASingleDenseFragment() {
        // Regression guard: the pre-clustering behaviour, one self-sufficient region.
        LineArtChunk single = lineArt(100, 500, 300, 600);
        List<IObject> pageContents = new ArrayList<>(List.of(single));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 110, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertEquals(1, pageContents.size());
        Assertions.assertTrue(pageContents.get(0) instanceof ImageChunk);
    }

    private static LineArtChunk lineArt(double leftX, double bottomY, double rightX, double topY) {
        return new LineArtChunk(new BoundingBox(0, leftX, bottomY, rightX, topY));
    }

    /** {@code count} 10 x 3 pt strokes starting at {@code (x, y)} and stepping right/down. */
    private static List<IObject> shortLines(int count, double x, double y) {
        List<IObject> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(new LineChunk(0, x + i, y + (i % 5) * 8, x + 10 + i, y + (i % 5) * 8 + 3));
        }
        return lines;
    }
}
