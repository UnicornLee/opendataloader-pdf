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
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.enums.TextAlignment;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.List;

public class ParagraphProcessorTest {

    @Test
    public void testProcessParagraphs() {
        StaticContainers.setIsDataLoader(true);
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 30.0, 20.0, 40.0),
            "test", 10, 30.0)));
        contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 20.0, 20.0, 30.0),
            "test", 10, 20.0)));
        contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0),
            "test", 10, 10.0)));
        contents = ParagraphProcessor.processParagraphs(contents);
        Assertions.assertEquals(1, contents.size());
        Assertions.assertTrue(contents.get(0) instanceof SemanticParagraph);
    }

    /**
     * Same-row fragments (a definition term in the left margin column and its definition
     * in the right column of an HKEX-style definitions page) share the same y range and
     * must end up in ONE paragraph consisting of a single merged TextLine.
     */
    @Test
    public void testSameRowFragmentsAreMergedIntoSingleTextLine() {
        StaticContainers.setIsDataLoader(true);
        List<IObject> contents = new ArrayList<>();
        TextLine term = new TextLine(new TextChunk(
            new BoundingBox(1, 79.789, 279.29, 168.252, 293.118), "「《中 央 結 算 系 統", 10.5, 279.29));
        TextLine definition = new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 279.29, 510.239, 293.118),
            "規範中央結算系統使用的條款和條件（經不時修訂或修", 10.5, 279.29));
        contents.add(term);
        contents.add(definition);

        contents = ParagraphProcessor.processParagraphs(contents, 595.276);

        Assertions.assertEquals(1, contents.size(),
            "Same-row fragments should be merged into one paragraph");
        Assertions.assertInstanceOf(SemanticParagraph.class, contents.get(0));
        SemanticParagraph paragraph = (SemanticParagraph) contents.get(0);
        Assertions.assertEquals(1, paragraph.getLinesNumber(),
            "The two same-row TextLines should be merged into a single TextLine");
        String value = paragraph.getFirstLine().getValue();
        Assertions.assertTrue(value.contains("「《中 央 結 算 系 統"));
        Assertions.assertTrue(value.contains("規範中央結算系統使用的條款和條件（經不時修訂或修"));
    }

    /**
     * A line that continues the right column of a composite (row-merged) line — e.g.
     * "序 規 則》" continuing "…應包括《中央結算系統運作程" — starts at the right-column
     * x, not at the composite line's leftX, so it must still be merged into the
     * preceding paragraph.
     */
    @Test
    public void testCompositeRowContinuationLineMergesIntoPrecedingParagraph() {
        StaticContainers.setIsDataLoader(true);
        List<IObject> contents = new ArrayList<>();
        // Row 1: term fragment (left column) + definition (right column)
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 79.789, 279.29, 168.252, 293.118), "「《中 央 結 算 系 統", 10.5, 279.29)));
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 279.29, 510.239, 293.118),
            "規範中央結算系統使用的條款和條件（經不時修訂或修", 10.5, 279.29)));
        // Row 2: term continuation (left column, indented) + definition continuation
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 95.528, 296.289, 159.315, 310.118), "一 般 規 則》」", 10.5, 296.289)));
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 296.289, 510.233, 310.118),
            "改），在 文 義 允 許 的 情 況 下，應 包 括《中 央 結 算 系 統 運 作 程", 10.5, 296.289)));
        // Row 3: right-column-only continuation line
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 313.289, 258.535, 327.117), "序 規 則》", 10.5, 313.289)));

        contents = ParagraphProcessor.processParagraphs(contents, 595.276);

        Assertions.assertEquals(1, contents.size(),
            "The continuation line should be merged into the preceding paragraph");
        Assertions.assertInstanceOf(SemanticParagraph.class, contents.get(0));
        SemanticParagraph paragraph = (SemanticParagraph) contents.get(0);
        Assertions.assertEquals(3, paragraph.getLinesNumber(),
            "All three rows should belong to one paragraph");
        String value = paragraph.getLastLine().getValue();
        Assertions.assertTrue(value.contains("序 規 則》"));
    }

    /**
     * "一、" right after a line ending with "之" is the word "之一" carried over, not a
     * list label — the line must be merged into the preceding paragraph (HKEX definition
     * "「劉 先 生」…控股股東之 / 一、曲 女 士 的 配 偶").
     */
    @Test
    public void testZhiNumeralLabelContinuationMergesIntoPrecedingParagraph() {
        StaticContainers.setIsDataLoader(true);
        List<IObject> contents = new ArrayList<>();
        // y-up coordinates: the second row sits BELOW the first one (smaller baseline).
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 79.789, 602.283, 510.236, 616.112),
            "「劉 先 生」        劉建輝先生，執行董事、包銷商唯一股東、控股股東之", 10.5, 604.3)));
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 585.4, 306.31, 599.2), "一、曲 女 士 的 配 偶", 10.5, 587.4)));

        contents = ParagraphProcessor.processParagraphs(contents, 595.276);

        Assertions.assertEquals(1, contents.size(),
            "The '之一' continuation line should be merged into the preceding paragraph");
        SemanticParagraph paragraph = (SemanticParagraph) contents.get(0);
        Assertions.assertEquals(2, paragraph.getLinesNumber());
        Assertions.assertTrue(paragraph.getLastLine().getValue().contains("一、曲 女 士 的 配 偶"));
    }

    /**
     * Without the "之" carry-over, a "一、" line remains a genuine list label and starts
     * its own paragraph.
     */
    @Test
    public void testGenuineChineseNumeralLabelStillStartsNewParagraph() {
        StaticContainers.setIsDataLoader(true);
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 79.789, 602.283, 510.236, 616.112),
            "「劉 先 生」        劉建輝先生，執行董事、包銷商唯一股東、控股股東，", 10.5, 604.3)));
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 212.598, 585.4, 306.31, 599.2), "一、曲 女 士 的 配 偶", 10.5, 587.4)));

        contents = ParagraphProcessor.processParagraphs(contents, 595.276);

        Assertions.assertEquals(2, contents.size(),
            "A genuine '一、' list label must still open its own paragraph");
    }

    /**
     * Regression test for PR `#567`: right-alignment detection must claim adjacent single-line
     * blocks before the two-line paragraph heuristic.
     *
     * <p>Two adjacent single-line TextLines with right-aligned geometry (identical rightX,
     * differing leftX) satisfy BOTH detection predicates:
     * <ul>
     *   <li>{`@code` areLinesOfParagraphsWithRightAlignments} → would set {`@link` TextAlignment#RIGHT}</li>
     *   <li>{`@code` isTwoLinesParagraph} → would set {`@link` TextAlignment#LEFT}</li>
     * </ul>
     *
     * <p>After the reorder ({`@code` detectParagraphsWithRightAlignments} before
     * {`@code` detectTwoLinesParagraphs}), the right-alignment pass must claim the blocks first,
     * so the merged paragraph carries {`@link` TextAlignment#RIGHT}.
     * If the detection order were reversed the block would be LEFT-aligned instead.
     */
    @Test
    public void testRightAlignmentTakesPrecedenceOverTwoLineHeuristic() {
        StaticContainers.setIsDataLoader(true);

        // Line 1 – shorter, flush right: leftX=15, rightX=20
        // Line 2 – longer,  flush right: leftX=10, rightX=20
        //
        // Geometry properties:
        //   • Both lines share rightX=20                  → ChunksMergeUtils.getAlignment() == RIGHT
        //   • line1.leftX(15) ≥ line2.leftX(10)          → isTwoLinesParagraph leftX  check passes
        //   • line1.rightX(20) ≥ line2.rightX(20)        → isTwoLinesParagraph rightX check passes
        //   • Lines are vertically adjacent (line height = font size = 10)
        //   • Same font size (10)                         → areTextBlocksHaveSameTextSize passes
        //
        // Both heuristics would match, so the test verifies which one wins after the reorder.
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 15.0, 20.0, 20.0, 30.0), "short", 10, 20.0)));
        contents.add(new TextLine(new TextChunk(
            new BoundingBox(1, 10.0, 10.0, 20.0, 20.0), "longer line", 10, 10.0)));

        contents = ParagraphProcessor.processParagraphs(contents);

        // The two right-aligned lines must be merged into exactly one paragraph.
        Assertions.assertEquals(1, contents.size(),
            "Two right-aligned single lines should be merged into one paragraph");
        Assertions.assertInstanceOf(SemanticParagraph.class, contents.get(0));

        // The merged block must carry TextAlignment.RIGHT because detectParagraphsWithRightAlignments
        // now runs before detectTwoLinesParagraphs.  If the order were reversed, the block would
        // be LEFT-aligned (set by isTwoLinesParagraph).
        SemanticParagraph para = (SemanticParagraph) contents.get(0);
        TextBlock block = para.getLastColumn().getBlocks().get(0);
        Assertions.assertEquals(TextAlignment.RIGHT, block.getTextAlignment(),
            "detectParagraphsWithRightAlignments must claim the blocks before " +
                "detectTwoLinesParagraphs; expected TextAlignment.RIGHT but got: " +
                block.getTextAlignment());
    }
}
