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
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class LineArtProcessorTest {

    @Test
    void singleLineArtChunkPassesThrough() {
        List<IObject> pageContents = new ArrayList<>();
        BoundingBox bbox = new BoundingBox(0, 100, 100, 200, 102);
        LineArtChunk lineArt = new LineArtChunk();
        lineArt.setBoundingBox(bbox);
        pageContents.add(lineArt);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        LineArtProcessor.processLineArtGroups(pageContents, 0, imagesUtils, null, null, 0.0, 0.0);

        Assertions.assertEquals(0, imagesUtils.saved.size(),
                "A lone LineArtChunk has no neighbours and should not be screenshot");
        Assertions.assertEquals(1, pageContents.size(),
                "The lone LineArtChunk should remain as-is");
        Assertions.assertSame(lineArt, pageContents.get(0));
    }

    @Test
    void multipleAdjacentLineArtChunksMergeIntoImage() {
        List<IObject> pageContents = new ArrayList<>();
        // Two overlapping LineArtChunks that are formula-sized (height ≤ 3 and
        // width ≤ 300) and whose y ranges overlap. By the time the second
        // chunk is processed, the first is already in `result`, and the
        // forward scan picks up the second as an overlap.
        LineArtChunk lineArt1 = new LineArtChunk();
        lineArt1.setBoundingBox(new BoundingBox(0, 100, 100, 200, 102));
        LineArtChunk lineArt2 = new LineArtChunk();
        lineArt2.setBoundingBox(new BoundingBox(0, 150, 100, 250, 102));

        pageContents.add(lineArt1);
        pageContents.add(lineArt2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        LineArtProcessor.processLineArtGroups(pageContents, 0, imagesUtils, null, null, 0.0, 0.0);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "Two overlapping LineArtChunks should merge into a single image");
        Assertions.assertEquals(1, pageContents.size(), "Original chunks should be replaced by one image");
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
    }

    @Test
    void nullPaddleUrlStopsBeforeOcr() {
        List<IObject> pageContents = new ArrayList<>();
        LineArtChunk lineArt1 = new LineArtChunk();
        lineArt1.setBoundingBox(new BoundingBox(0, 100, 100, 200, 102));
        LineArtChunk lineArt2 = new LineArtChunk();
        lineArt2.setBoundingBox(new BoundingBox(0, 150, 100, 250, 102));

        pageContents.add(lineArt1);
        pageContents.add(lineArt2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        LineArtProcessor.processLineArtGroups(pageContents, 0, imagesUtils, null, null, 0.0, 0.0);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        // With paddleUrl null, no formula TextChunk is produced; the result is an ImageChunk.
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
        Assertions.assertFalse(pageContents.get(0) instanceof TextChunk,
                "Without paddleUrl the screenshot must remain an ImageChunk, not a formula TextChunk");
    }

    @Test
    void emptyPaddleUrlSkipsOcrBranch() {
        List<IObject> pageContents = new ArrayList<>();
        LineArtChunk lineArt1 = new LineArtChunk();
        lineArt1.setBoundingBox(new BoundingBox(0, 100, 100, 200, 102));
        LineArtChunk lineArt2 = new LineArtChunk();
        lineArt2.setBoundingBox(new BoundingBox(0, 150, 100, 250, 102));

        pageContents.add(lineArt1);
        pageContents.add(lineArt2);

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        LineArtProcessor.processLineArtGroups(pageContents, 0, imagesUtils, "", null, 0.0, 0.0);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertInstanceOf(ImageChunk.class, pageContents.get(0));
    }

    @Test
    void nullOrEmptyInputsAreNoOp() {
        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();

        Assertions.assertDoesNotThrow(() ->
                LineArtProcessor.processLineArtGroups(null, 0, imagesUtils, null, null, 0.0, 0.0));
        Assertions.assertDoesNotThrow(() ->
                LineArtProcessor.processLineArtGroups(new ArrayList<>(), 0, imagesUtils, null, null, 0.0, 0.0));
        Assertions.assertDoesNotThrow(() ->
                LineArtProcessor.processLineArtGroups(new ArrayList<>(), 0, null, null, null, 0.0, 0.0));
        Assertions.assertEquals(0, imagesUtils.saved.size());
    }
}
