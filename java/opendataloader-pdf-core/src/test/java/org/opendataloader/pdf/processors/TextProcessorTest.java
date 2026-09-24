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
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class TextProcessorTest {

    @Test
    public void testReplaceUndefinedCharacters() {
        // Simulate backend results containing U+FFFD (replacement character)
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "Hello \uFFFD World", 10, 10.0));
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 30.0, 100.0, 40.0),
            "No issues here", 10, 10.0));

        TextProcessor.replaceUndefinedCharacters(contents, "?");

        Assertions.assertEquals("Hello ? World", ((TextChunk) contents.get(0)).getValue());
        Assertions.assertEquals("No issues here", ((TextChunk) contents.get(1)).getValue());
    }

    @Test
    public void testReplaceUndefinedCharactersSkipsWhenDefault() {
        // When replacement string equals REPLACEMENT_CHARACTER_STRING, should be a no-op
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "Hello \uFFFD World", 10, 10.0));

        TextProcessor.replaceUndefinedCharacters(contents, "\uFFFD");

        // Should remain unchanged
        Assertions.assertEquals("Hello \uFFFD World", ((TextChunk) contents.get(0)).getValue());
    }

    @Test
    public void testReplaceUndefinedCharactersMultipleOccurrences() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "\uFFFD first \uFFFD second \uFFFD", 10, 10.0));

        TextProcessor.replaceUndefinedCharacters(contents, "*");

        Assertions.assertEquals("* first * second *", ((TextChunk) contents.get(0)).getValue());
    }

    @Test
    public void testReplaceUndefinedCharactersWithRegexSpecialChars() {
        // Verify that regex-special characters in replacement string work correctly
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "Hello \uFFFD World", 10, 10.0));

        TextProcessor.replaceUndefinedCharacters(contents, "$");

        Assertions.assertEquals("Hello $ World", ((TextChunk) contents.get(0)).getValue());
    }

    @Test
    public void testReplaceUndefinedCharactersSkipsNonTextChunks() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new ImageChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0)));
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 30.0, 100.0, 40.0),
            "Hello \uFFFD", 10, 10.0));

        TextProcessor.replaceUndefinedCharacters(contents, "?");

        Assertions.assertTrue(contents.get(0) instanceof ImageChunk);
        Assertions.assertEquals("Hello ?", ((TextChunk) contents.get(1)).getValue());
    }

    @Test
    public void testRemoveSameTextChunks() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0),
            "test", 10, 10.0));
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0),
            "test", 10, 10.0));
        TextProcessor.removeSameTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);
        Assertions.assertEquals(1, contents.size());
    }

    /**
     * The first three lines of {@code docs/pdf/200812311782183951489043113-1.pdf} paint every
     * glyph four times at {@code (x +-0.24, baseline +-0.24)}. All four copies of "表格" carry the
     * same value and bbox, so this mirrors the grid observed in the real file.
     */
    @Test
    public void testRemoveOverprintedTextChunksCollapsesFourCopies() {
        List<IObject> contents = new ArrayList<>();
        contents.add(overprintChunk("表格", 186.960, 206.994, 759.375, 761.360,
            Arrays.asList(186.960, 196.914, 206.994)));
        contents.add(overprintChunk("表格", 187.200, 207.234, 759.375, 761.360,
            Arrays.asList(187.200, 197.154, 207.234)));
        contents.add(overprintChunk("表格", 186.960, 206.994, 759.615, 761.600,
            Arrays.asList(186.960, 196.914, 206.994)));
        contents.add(overprintChunk("表格", 187.200, 207.234, 759.615, 761.600,
            Arrays.asList(187.200, 197.154, 207.234)));

        TextProcessor.removeOverprintedTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);

        Assertions.assertEquals("表格", renderByLeftX(contents));
        Assertions.assertEquals(1, contents.size(), "the surviving characters must stay in a single chunk");
        Assertions.assertEquals(1, countChar(contents, '表'));
        Assertions.assertEquals(1, countChar(contents, '格'));
    }

    /**
     * Real geometry of the "」)" run of the same file: one copy carries the "」" as two halves
     * (5.097pt each), another one carries it whole (9.954pt) plus a ")" (6.658pt) and two
     * ")" copies that are only 1.675pt wide, plus a zero-advance ")" phantom.
     */
    @Test
    public void testRemoveOverprintedTextChunksKeepsWholeGlyphOverSplitHalves() {
        List<IObject> contents = new ArrayList<>();
        TextChunk halves = overprintChunk("」」", 398.040, 408.234, 759.375, 761.360,
            Arrays.asList(398.040, 403.137, 408.234));
        TextChunk whole = overprintChunk("」", 398.280, 408.234, 759.615, 761.600,
            Arrays.asList(398.280, 408.234));
        TextChunk withParenthesis = overprintChunk("」)", 398.040, 411.356, 759.615, 761.600,
            Arrays.asList(398.040, 404.698, 411.356));
        TextChunk narrowParentheses = overprintChunk("))", 408.000, 411.351, 759.375, 761.360,
            Arrays.asList(408.000, 409.675, 411.351));
        TextChunk phantomParenthesis = overprintChunk("))", 411.240, 411.480, 759.375, 761.360,
            Arrays.asList(411.240, 411.360, 411.480));
        contents.add(halves);
        contents.add(whole);
        contents.add(withParenthesis);
        contents.add(narrowParentheses);
        contents.add(phantomParenthesis);

        TextProcessor.removeOverprintedTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);

        Assertions.assertEquals("」)", renderByLeftX(contents));
        Assertions.assertEquals(1, countChar(contents, '」'));
        Assertions.assertEquals(1, countChar(contents, ')'));
    }

    /**
     * Real geometry of the "致香港聯合交易所有限公司" run: one copy absorbed the leading
     * "  :  " and the tail is also painted on its own, so the surviving chunks are one "  :  "
     * (front of the merged copy) and one "致" (the separate copy).
     */
    @Test
    public void testRemoveOverprintedTextChunksKeepsFrontMergedCopyOnce() {
        List<IObject> contents = new ArrayList<>();
        TextChunk merged = overprintChunk("  :  致", 207.000, 229.921, 759.615, 761.600,
            Arrays.asList(207.000, 209.588, 212.042, 214.640, 217.273, 219.967, 229.921));
        TextChunk tail = overprintChunk("致", 219.720, 229.674, 759.375, 761.360,
            Arrays.asList(219.720, 229.674));
        contents.add(merged);
        contents.add(tail);

        TextProcessor.removeOverprintedTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);

        Assertions.assertEquals("  :  致", renderByLeftX(contents));
        Assertions.assertEquals(1, countChar(contents, ':'));
        Assertions.assertEquals(1, countChar(contents, '致'));
    }

    /**
     * Ordinary text must never be touched: repeated characters that are simply adjacent
     * (letter spacing / kerning) and lone narrow glyphs are not an overprint signature.
     */
    @Test
    public void testRemoveOverprintedTextChunksLeavesNormalTextUntouched() {
        List<IObject> contents = new ArrayList<>();
        TextChunk repeated = overprintChunk("aa bb", 10.0, 60.0, 90.0, 100.0,
            Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0, 60.0));
        TextChunk narrowGlyph = overprintChunk(".", 70.0, 70.4, 90.0, 100.0,
            Arrays.asList(70.0, 70.4));
        contents.add(repeated);
        contents.add(narrowGlyph);

        TextProcessor.removeOverprintedTextChunks(contents);

        Assertions.assertEquals(2, contents.size());
        Assertions.assertEquals("aa bb", ((TextChunk) contents.get(0)).getValue());
        Assertions.assertEquals(".", ((TextChunk) contents.get(1)).getValue());
    }

    /**
     * Regression test: the extractor reports collapsed widths for some ordinary characters — in
     * {@code docs/pdf/202504291785149927447006139.pdf} the second "「" of "提升「技防」「智控」水平。"
     * comes back 0.135pt wide while the first one is 4.635pt. Such a character has no equal
     * character painted next to it, so it must never be treated as an overprint phantom.
     */
    @Test
    public void testRemoveOverprintedTextChunksKeepsCollapsedWidthCharacterWithoutCopy() {
        List<IObject> contents = new ArrayList<>();
        contents.add(overprintChunk("提升「技防」「智控」", 255.480, 333.330, 638.114, 647.114,
            Arrays.asList(255.480, 264.615, 273.750, 278.385, 287.520, 296.655, 305.790, 305.925,
                315.060, 324.195, 333.330)));
        TextChunk second = (TextChunk) contents.get(0);

        TextProcessor.removeOverprintedTextChunks(contents);

        Assertions.assertEquals(1, contents.size());
        Assertions.assertSame(second, contents.get(0));
        Assertions.assertEquals("提升「技防」「智控」", ((TextChunk) contents.get(0)).getValue());
    }

    /** Chunks without symbol geometry (e.g. OCR / hybrid results) are ignored. */
    @Test
    public void testRemoveOverprintedTextChunksIgnoresChunksWithoutSymbolEnds() {
        List<IObject> contents = new ArrayList<>();
        TextChunk withoutGeometry = new TextChunk(new BoundingBox(1, 10.0, 90.0, 20.0, 100.0),
            "表格", 9.96, 100.0);
        contents.add(withoutGeometry);

        TextProcessor.removeOverprintedTextChunks(contents);

        Assertions.assertEquals(1, contents.size());
        Assertions.assertSame(withoutGeometry, contents.get(0));
    }

    private static TextChunk overprintChunk(String value, double left, double right, double bottomY,
                                            double baseLine, List<Double> symbolEnds) {
        TextChunk chunk = new TextChunk(new BoundingBox(1, left, bottomY, right, bottomY + 10.0),
            value, 9.96, baseLine);
        chunk.setFontName("TT491A9C96tCID");
        chunk.setFontWeight(400);
        chunk.setSymbolEnds(new ArrayList<>(symbolEnds));
        return chunk;
    }

    private static String renderByLeftX(List<IObject> contents) {
        return contents.stream()
            .filter(object -> object instanceof TextChunk)
            .map(object -> (TextChunk) object)
            .sorted(Comparator.comparingDouble(TextChunk::getLeftX))
            .map(TextChunk::getValue)
            .collect(Collectors.joining());
    }

    private static int countChar(List<IObject> contents, char value) {
        int count = 0;
        for (IObject object : contents) {
            if (object instanceof TextChunk) {
                for (char current : ((TextChunk) object).getValue().toCharArray()) {
                    if (current == value) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Test
    public void testRemoveTextDecorationImages() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0),
            "test", 10, 10.0));
        contents.add(new ImageChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0)));
        TextProcessor.removeTextDecorationImages(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);
        Assertions.assertEquals(1, contents.size());
        Assertions.assertTrue(contents.get(0) instanceof TextChunk);
    }

    /**
     * Regression test for issue #150: text chunks with a large horizontal gap
     * should remain separate.
     */
    @Test
    public void testMergeCloseTextChunksSeparatedByLargeGapNotMerged() {
        List<IObject> contents = new ArrayList<>();
        String fontName = "Arial";

        // First chunk: "4" at x=180, physically in one table cell
        TextChunk chunk1 = new TextChunk(new BoundingBox(0, 180.0, 100.0, 190.0, 110.0),
            "4", 10, 100.0);
        chunk1.adjustSymbolEndsToBoundingBox(null);
        chunk1.setFontName(fontName);
        chunk1.setFontWeight(400);

        // Second chunk: "6" at x=350, physically in a different table cell
        TextChunk chunk2 = new TextChunk(new BoundingBox(0, 350.0, 100.0, 360.0, 110.0),
            "6", 10, 100.0);
        chunk2.adjustSymbolEndsToBoundingBox(null);
        chunk2.setFontName(fontName);
        chunk2.setFontWeight(400);

        contents.add(chunk1);
        contents.add(chunk2);

        TextProcessor.mergeCloseTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);

        Assertions.assertEquals(2, contents.size(),
            "Text chunks separated by a large gap should not be merged");
        Assertions.assertEquals("4", ((TextChunk) contents.get(0)).getValue());
        Assertions.assertEquals("6", ((TextChunk) contents.get(1)).getValue());
    }

    /**
     * Regression test for issue #150: adjacent text chunks should still be merged.
     */
    @Test
    public void testMergeCloseTextChunksAdjacentMerged() {
        List<IObject> contents = new ArrayList<>();
        String fontName = "Arial";

        // First chunk: "Hel" at x=10
        TextChunk chunk1 = new TextChunk(new BoundingBox(0, 10.0, 100.0, 30.0, 110.0),
            "Hel", 10, 100.0);
        chunk1.adjustSymbolEndsToBoundingBox(null);
        chunk1.setFontName(fontName);
        chunk1.setFontWeight(400);
        chunk1.setTextEnd(30.0);

        // Second chunk: "lo" at x=30, immediately adjacent
        TextChunk chunk2 = new TextChunk(new BoundingBox(0, 30.0, 100.0, 45.0, 110.0),
            "lo", 10, 100.0);
        chunk2.adjustSymbolEndsToBoundingBox(null);
        chunk2.setFontName(fontName);
        chunk2.setFontWeight(400);
        chunk2.setTextStart(30.0);

        contents.add(chunk1);
        contents.add(chunk2);

        TextProcessor.mergeCloseTextChunks(contents);
        contents = DocumentProcessor.removeNullObjectsFromList(contents);

        // Adjacent chunks should be merged
        Assertions.assertEquals(1, contents.size(),
            "Adjacent text chunks should be merged");
        Assertions.assertEquals("Hello", ((TextChunk) contents.get(0)).getValue());
    }

    @Test
    public void testMeasureReplacementCharRatioAllReplacement() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "\uFFFD\uFFFD\uFFFD", 10, 10.0));

        double ratio = TextProcessor.measureReplacementCharRatio(contents);
        Assertions.assertEquals(1.0, ratio, 0.001);
    }

    @Test
    public void testMeasureReplacementCharRatioNoReplacement() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "Hello World", 10, 10.0));

        double ratio = TextProcessor.measureReplacementCharRatio(contents);
        Assertions.assertEquals(0.0, ratio, 0.001);
    }

    @Test
    public void testMeasureReplacementCharRatioMixed() {
        List<IObject> contents = new ArrayList<>();
        // 3 replacement chars out of 10 total = 0.3
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0),
            "\uFFFD\uFFFD\uFFFDAbcdefg", 10, 10.0));

        double ratio = TextProcessor.measureReplacementCharRatio(contents);
        Assertions.assertEquals(0.3, ratio, 0.001);
    }

    @Test
    public void testMeasureReplacementCharRatioEmptyContents() {
        List<IObject> contents = new ArrayList<>();

        double ratio = TextProcessor.measureReplacementCharRatio(contents);
        Assertions.assertEquals(0.0, ratio, 0.001);
    }

    @Test
    public void testMeasureReplacementCharRatioNonTextChunksIgnored() {
        List<IObject> contents = new ArrayList<>();
        contents.add(new ImageChunk(new BoundingBox(1, 10.0, 10.0, 100.0, 20.0)));
        contents.add(new TextChunk(new BoundingBox(1, 10.0, 30.0, 100.0, 40.0),
            "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD", 10, 10.0));

        double ratio = TextProcessor.measureReplacementCharRatio(contents);
        // Only TextChunks counted: 5/5 = 1.0
        Assertions.assertEquals(1.0, ratio, 0.001);
    }
}
