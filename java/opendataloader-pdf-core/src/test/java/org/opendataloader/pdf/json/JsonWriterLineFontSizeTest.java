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
package org.opendataloader.pdf.json;

import org.junit.jupiter.api.Test;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonWriterLineFontSizeTest {

    /**
     * A table-of-contents line such as {@code 釋義 . . . . 1}: the title and the page number are
     * 11.0, while the dot leader is drawn many times at the smaller 8.0. Counting per chunk (the old
     * behaviour) let the leader dominate and returned 8.0. The per-character rule collects only
     * CJK / letter / digit characters, so the leader dots are ignored and the line resolves to 11.0.
     */
    @Test
    void dotLeaderDoesNotDominateAtoCLine() throws Exception {
        List<TextChunk> line = new ArrayList<>();
        line.add(chunk("釋義", 11.0));
        for (int i = 0; i < 20; i++) {
            line.add(chunk(" .", 8.0));
        }
        line.add(chunk("1", 11.0));
        assertEquals(11.0, computeLineFontSize(line));
    }

    /**
     * A plain line where every chunk shares one size must keep reporting that size.
     */
    @Test
    void uniformLineKeepsItsSize() throws Exception {
        List<TextChunk> line = Arrays.asList(
            chunk("截至二零二四年", 11.0),
            chunk("十二月三十一日", 11.0));
        assertEquals(11.0, computeLineFontSize(line));
    }

    /**
     * A line with a small number of content characters against a single differently-sized chunk:
     * the character-weighted mode still follows the dominant content size.
     */
    @Test
    void contentSizeWinsAgainstSingleOversizedChunk() throws Exception {
        List<TextChunk> line = Arrays.asList(
            chunk("重選退任董事", 11.0),
            chunk("。", 14.0));
        assertEquals(11.0, computeLineFontSize(line));
    }

    /**
     * When a line has no CJK / letter / digit character at all, the fallback uses the sizes of all
     * non-whitespace characters, so the leader dots (8.0) still win over the single em dash (9.0).
     */
    @Test
    void fallbackUsesNonWhitespaceCharactersWhenNoContentCharacter() throws Exception {
        List<TextChunk> line = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            line.add(chunk(" .", 8.0));
        }
        line.add(chunk("—", 9.0));
        assertEquals(8.0, computeLineFontSize(line));
    }

    /**
     * Whitespace must never contribute, even in the fallback path: a run of spaces at an outlier
     * size sitting alongside a couple of dots resolves to the dot size, not the space size.
     */
    @Test
    void whitespaceIsNeverCountedInFallback() throws Exception {
        List<TextChunk> line = Arrays.asList(
            chunk("      ", 24.0),
            chunk(" ..", 8.0));
        assertEquals(8.0, computeLineFontSize(line));
    }

    /**
     * A title carrying a footnote superscript on its right, e.g. {@code 預 期 時 間 表<sup>(1)</sup>}:
     * the five title characters are drawn at 16.0 while the marker is a small 8.0. Before the fix the
     * {@code <sup>}/{@code </sup>} markup's letters (s/u/p) were counted as content at 8.0, giving the
     * marker seven characters against the title's five and flipping the mode down to 8.0. Now the tag
     * letters are stripped and the whole marker is excluded from the main statistic, so the line
     * resolves to the title size 16.0.
     */
    @Test
    void superscriptMarkerIsIgnoredForATitleLine() throws Exception {
        List<TextChunk> line = Arrays.asList(
            chunk("預 期 時 間 表", 16.0),
            chunk("<sup>( 1 )</sup>", 8.0));
        assertEquals(16.0, computeLineFontSize(line));
    }

    /**
     * Same exclusion applies to a subscript marker sitting beside normal content.
     */
    @Test
    void subscriptMarkerIsIgnoredForATitleLine() throws Exception {
        List<TextChunk> line = Arrays.asList(
            chunk("化 學 式", 16.0),
            chunk("<sub>( 2 )</sub>", 8.0));
        assertEquals(16.0, computeLineFontSize(line));
    }

    /**
     * When the entire line is nothing but a superscript marker, that marker is all the line has, so
     * its own size (8.0) must still be reported rather than collapsing to 0.0.
     */
    @Test
    void lineMadeEntirelyOfSuperscriptKeepsTheMarkerSize() throws Exception {
        List<TextChunk> line = new ArrayList<>();
        line.add(chunk(" ", 16.0));
        line.add(chunk("<sup>( 1 )</sup>", 8.0));
        assertEquals(8.0, computeLineFontSize(line));
    }

    private static double computeLineFontSize(List<TextChunk> line) throws Exception {
        Method method = JsonWriter.class.getDeclaredMethod("computeLineFontSize", List.class);
        method.setAccessible(true);
        return (double) method.invoke(null, line);
    }

    private static TextChunk chunk(String value, double fontSize) {
        BoundingBox box = new BoundingBox(0, 0, 0, value.length() * fontSize, fontSize);
        return new TextChunk(box, value, fontSize, fontSize / 2);
    }
}
