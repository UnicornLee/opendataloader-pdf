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
import java.util.List;

class ConsecutiveImageProcessorTest {

    private static final double[] GRAY = new double[]{0.5, 0.5, 0.5};

    @Test
    void mergesCloseStackedImagesIntoOneScreenshot() {
        // pageContents is sorted top-to-bottom by the caller.
        ImageChunk img1 = new ImageChunk(new BoundingBox(0, 100, 100, 200, 200));
        ImageChunk img2 = new ImageChunk(new BoundingBox(0, 100, 50, 200, 90));
        // Vertical gap = 100 - 90 = 10 <= 50% of the smaller height (100).
        List<IObject> pageContents = new ArrayList<>(List.of(img1, img2));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "Two close images should be replaced by a single screenshot");
        Assertions.assertEquals(1, pageContents.size(),
                "The merged screenshot should replace the two originals");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(100.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(200.0, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(50.0, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(200.0, bbox.getTopY(), 0.0001);
        Assertions.assertTrue(pageContents.contains(imagesUtils.saved.get(0)),
                "The merged screenshot should be placed into pageContents");
    }

    @Test
    void mergesSideBySideImages() {
        // Fragments of one figure: overlapping vertical extents and touching edges.
        ImageChunk left = new ImageChunk(new BoundingBox(0, 100, 100, 190, 200));
        ImageChunk right = new ImageChunk(new BoundingBox(0, 192, 100, 290, 200));
        List<IObject> pageContents = new ArrayList<>(List.of(left, right));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(100.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(290.0, bbox.getRightX(), 0.0001);
    }

    @Test
    void mergesSideBySideLogosOfOneComparison() {
        // Measured on an announcement comparing the old and the new company logo.
        // The right-hand logo is itself stored as two tiles, the second only 31.3 pt
        // wide; the 77.6 pt gutter to the left logo is 1.5 short sides of the figure
        // collected so far, so all three chunks become the single comparison figure
        // the page lays them out as.
        ImageChunk newLogoLeft = new ImageChunk(new BoundingBox(0, 340.3, 495.7, 464.8, 550.1));
        ImageChunk newLogoRight = new ImageChunk(new BoundingBox(0, 464.6, 495.7, 495.9, 550.1));
        ImageChunk oldLogo = new ImageChunk(new BoundingBox(0, 92.1, 495.4, 262.7, 545.6));
        List<IObject> pageContents = new ArrayList<>(List.of(newLogoLeft, newLogoRight, oldLogo));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertEquals(1, pageContents.size());
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(92.1, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(495.9, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(495.4, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(550.1, bbox.getTopY(), 0.0001);
    }

    @Test
    void keepsDistantSideBySideImagesSeparate() {
        // Measured on a page carrying the two crop marks of a print line: two 14 pt
        // marks at opposite ends of a 550 pt band. Their vertical extents overlap, so
        // only the horizontal check keeps them apart — and it has to scale with the
        // boxes, because a 521.6 pt gutter between two 14 pt marks is 37 short sides
        // while the same absolute distance between two 50 pt logos is 1.5.
        ImageChunk left = new ImageChunk(new BoundingBox(0, 22.7, 495.4, 36.9, 509.5));
        ImageChunk right = new ImageChunk(new BoundingBox(0, 558.5, 495.4, 572.9, 509.3));
        List<IObject> pageContents = new ArrayList<>(List.of(left, right));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "No screenshot should be produced");
        Assertions.assertEquals(2, pageContents.size());
        Assertions.assertTrue(pageContents.contains(left));
        Assertions.assertTrue(pageContents.contains(right));
    }

    @Test
    void keepsFarAwayImagesSeparate() {
        // Gap = 100 - 20 = 80 > 50% of the smaller height (100).
        ImageChunk img1 = new ImageChunk(new BoundingBox(0, 100, 100, 200, 200));
        ImageChunk img2 = new ImageChunk(new BoundingBox(0, 100, 0, 200, 20));
        List<IObject> pageContents = new ArrayList<>(List.of(img1, img2));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(0, imagesUtils.saved.size(), "No screenshot should be produced");
        Assertions.assertEquals(2, pageContents.size(), "Original images should be preserved");
        Assertions.assertTrue(pageContents.contains(img1));
        Assertions.assertTrue(pageContents.contains(img2));
    }

    @Test
    void nonImageElementBreaksTheRun() {
        ImageChunk img1 = new ImageChunk(new BoundingBox(0, 100, 100, 200, 200));
        ShapeChunk caption = new ShapeChunk(new BoundingBox(0, 100, 50, 200, 90), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        ImageChunk img2 = new ImageChunk(new BoundingBox(0, 100, 0, 200, 40));
        List<IObject> pageContents = new ArrayList<>(List.of(img1, caption, img2));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(0, imagesUtils.saved.size(),
                "A non-image element between images must prevent merging");
        Assertions.assertEquals(3, pageContents.size());
    }

    @Test
    void singleImageIsLeftUntouched() {
        ImageChunk img = new ImageChunk(new BoundingBox(0, 100, 100, 200, 200));
        List<IObject> pageContents = new ArrayList<>(List.of(img));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        ConsecutiveImageProcessor.processConsecutiveImages(pageContents, 0, imagesUtils);

        Assertions.assertEquals(0, imagesUtils.saved.size());
        Assertions.assertEquals(1, pageContents.size());
        Assertions.assertSame(img, pageContents.get(0));
    }

    @Test
    void nullOrEmptyInputsAreNoOp() {
        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();

        Assertions.assertDoesNotThrow(() ->
                ConsecutiveImageProcessor.processConsecutiveImages(null, 0, imagesUtils));
        Assertions.assertDoesNotThrow(() ->
                ConsecutiveImageProcessor.processConsecutiveImages(new ArrayList<>(), 0, imagesUtils));
        Assertions.assertDoesNotThrow(() ->
                ConsecutiveImageProcessor.processConsecutiveImages(null, 0, null));
        Assertions.assertEquals(0, imagesUtils.saved.size());
    }
}
