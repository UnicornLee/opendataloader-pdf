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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.enums.SemanticType;

import java.util.ArrayList;
import java.util.List;

public class PageBookmarkProcessorTest {

    private static CustomSemanticParagraph createParagraph(String text, int pageIndex, double leftX,
                                                           double topY, double bottomY, float fontSize) {
        TextLine line = new TextLine(new TextChunk(
                new BoundingBox(pageIndex, leftX, bottomY, leftX + 200, topY), text, fontSize, (topY + bottomY) / 2));
        TextBlock block = new TextBlock(line);
        return ParagraphProcessor.createParagraphFromTextBlock(block);
    }

    private static List<List<IObject>> singlePage(CustomSemanticParagraph... paragraphs) {
        List<List<IObject>> contents = new ArrayList<>();
        List<IObject> page = new ArrayList<>();
        for (CustomSemanticParagraph paragraph : paragraphs) {
            page.add(paragraph);
        }
        contents.add(page);
        return contents;
    }

    /**
     * Builds a multi-page content list. The argument list alternates (pageIndex, text,
     * fontSize, topY) tuples. Unreferenced page slots remain empty. Page indices are
     * 0-based, matching the convention used by DocumentProcessor and PageBookmarkProcessor.
     */
    private static List<List<IObject>> multiPage(Object... entries) {
        List<List<IObject>> contents = new ArrayList<>();
        for (int i = 0; i < entries.length; i += 4) {
            int pageIndex = (Integer) entries[i];
            String text = (String) entries[i + 1];
            float fontSize = (Float) entries[i + 2];
            double topY = (Double) entries[i + 3];
            while (contents.size() <= pageIndex) {
                contents.add(new ArrayList<>());
            }
            contents.get(pageIndex).add(createParagraph(text, pageIndex, 50, topY, topY - 10, fontSize));
        }
        return contents;
    }

    @BeforeEach
    public void setUp() {
        StaticLayoutContainers.clearContainers();
    }

    @AfterEach
    public void tearDown() {
        StaticLayoutContainers.clearContainers();
    }

    @Test
    public void testSingleChapterBookmark() {
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 总则", 0, 50, 700, 680, 18));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals("第1章 总则", bookmarks.get(0).getText());
        Assertions.assertEquals(1, bookmarks.get(0).getPageNum());
        Assertions.assertTrue(bookmarks.get(0).getChildren() == null || bookmarks.get(0).getChildren().isEmpty());
    }

    @Test
    public void testSingleChapterBookmark_chineseNumber() {
        List<List<IObject>> contents = singlePage(
                createParagraph("第一章 总则", 0, 50, 700, 680, 18));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals("第一章 总则", bookmarks.get(0).getText());
    }

    @Test
    public void testThreeLevelBookmarks() {
        // Level 1: chapter, larger font, smaller leftX
        // Level 2: section, medium font, indented
        // Level 3: article, smaller font, more indented
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 总则", 0, 50, 700, 680, 20),
                createParagraph("第1节 一般规定", 0, 60, 650, 630, 16),
                createParagraph("第1条 条款一", 0, 70, 620, 600, 14),
                createParagraph("第2条 条款二", 0, 70, 590, 570, 14),
                createParagraph("第2节 特别规定", 0, 60, 560, 540, 16),
                createParagraph("第3条 条款三", 0, 70, 530, 510, 14),
                createParagraph("第2章 分则", 0, 50, 480, 460, 20),
                createParagraph("第3节 分则一般", 0, 60, 450, 430, 16),
                createParagraph("第4条 条款四", 0, 70, 420, 400, 14));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(2, bookmarks.size(), "Expected two top-level chapters");

        Bookmark chapter1 = bookmarks.get(0);
        Assertions.assertEquals("第1章 总则", chapter1.getText());
        Assertions.assertEquals(2, chapter1.getChildren().size(), "Chapter 1 should have two sections");

        Bookmark section1 = chapter1.getChildren().get(0);
        Assertions.assertEquals("第1节 一般规定", section1.getText());
        Assertions.assertEquals(2, section1.getChildren().size(), "Section 1 should have two articles");
        Assertions.assertEquals("第1条 条款一", section1.getChildren().get(0).getText());
        Assertions.assertEquals("第2条 条款二", section1.getChildren().get(1).getText());

        Bookmark section2 = chapter1.getChildren().get(1);
        Assertions.assertEquals("第2节 特别规定", section2.getText());
        Assertions.assertEquals(1, section2.getChildren().size());
        Assertions.assertEquals("第3条 条款三", section2.getChildren().get(0).getText());

        Bookmark chapter2 = bookmarks.get(1);
        Assertions.assertEquals("第2章 分则", chapter2.getText());
        Assertions.assertEquals(1, chapter2.getChildren().size());
        Bookmark section3 = chapter2.getChildren().get(0);
        Assertions.assertEquals("第3节 分则一般", section3.getText());
        Assertions.assertEquals(1, section3.getChildren().size());
        Assertions.assertEquals("第4条 条款四", section3.getChildren().get(0).getText());
    }

    @Test
    public void testNonConsecutiveSequence_isDropped() {
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 总则", 0, 50, 700, 680, 18),
                createParagraph("第3章 细则", 0, 50, 650, 630, 18));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertTrue(bookmarks.isEmpty(), "Non-consecutive sequence should be dropped");
    }

    @Test
    public void testSingleBookmarkNotOne_isDropped() {
        List<List<IObject>> contents = singlePage(
                createParagraph("第10章 总则", 0, 50, 700, 680, 18));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertTrue(bookmarks.isEmpty(), "Single bookmark not starting at 1 should be dropped");
    }

    @Test
    public void testArabicAndChineseAreSeparateLevels() {
        // 第1章 (Arabic) and 第一章 (Chinese) are treated as separate templates and
        // ranked by visual hierarchy. Arabic is larger -> level 1, Chinese is smaller -> level 2.
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 阿拉伯", 0, 50, 700, 680, 20),
                createParagraph("第一章 中文", 0, 60, 650, 630, 16));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size(), "Arabic chapter should be the only top-level bookmark");
        Assertions.assertEquals("第1章 阿拉伯", bookmarks.get(0).getText());
        Assertions.assertEquals(1, bookmarks.get(0).getChildren().size());
        Assertions.assertEquals("第一章 中文", bookmarks.get(0).getChildren().get(0).getText());
    }

    @Test
    public void testPureNumberTemplate() {
        List<List<IObject>> contents = singlePage(
                createParagraph("1 总则", 0, 50, 700, 680, 18),
                createParagraph("2 细则", 0, 50, 650, 630, 18),
                createParagraph("3 补充", 0, 50, 600, 580, 18));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(3, bookmarks.size());
        Assertions.assertEquals("1 总则", bookmarks.get(0).getText());
        Assertions.assertEquals("2 细则", bookmarks.get(1).getText());
        Assertions.assertEquals("3 补充", bookmarks.get(2).getText());
    }

    @Test
    public void testParenthesisTemplate() {
        List<List<IObject>> contents = singlePage(
                createParagraph("第1条 条款", 0, 50, 700, 680, 18),
                createParagraph("（一） 项目一", 0, 50, 650, 630, 16),
                createParagraph("（二） 项目二", 0, 50, 600, 580, 16));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        // 第1条 has larger font size -> level 1; （一） smaller -> level 2
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals("第1条 条款", bookmarks.get(0).getText());
        Assertions.assertEquals(2, bookmarks.get(0).getChildren().size());
        Assertions.assertEquals("（一） 项目一", bookmarks.get(0).getChildren().get(0).getText());
        Assertions.assertEquals("（二） 项目二", bookmarks.get(0).getChildren().get(1).getText());
    }

    @Test
    public void testOnlySecondLevel() {
        // Single valid group of 第1条/第2条 should be level 1 because there is no other group.
        List<List<IObject>> contents = singlePage(
                createParagraph("第1条 条款一", 0, 70, 700, 680, 14),
                createParagraph("第2条 条款二", 0, 70, 650, 630, 14));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(2, bookmarks.size());
        Assertions.assertEquals("第1条 条款一", bookmarks.get(0).getText());
        Assertions.assertEquals("第2条 条款二", bookmarks.get(1).getText());
    }

    /**
     * Builds a {@link SemanticHeading} wrapping a single-line text block. After
     * {@link HeadingProcessor#processHeadings} wraps headings in
     * {@link SemanticHeading} instead of {@link CustomSemanticParagraph}, these
     * remain valid candidates for page bookmarks.
     */
    private static SemanticHeading createHeading(String text, int pageIndex, double leftX,
                                                 double topY, double bottomY, float fontSize) {
        TextLine line = new TextLine(new TextChunk(
                new BoundingBox(pageIndex, leftX, bottomY, leftX + 200, topY), text, fontSize, (topY + bottomY) / 2));
        TextBlock block = new TextBlock(line);
        CustomSemanticParagraph paragraph = ParagraphProcessor.createParagraphFromTextBlock(block);
        paragraph.setSemanticType(SemanticType.HEADING);
        return new SemanticHeading(paragraph);
    }

    @Test
    public void testSemanticsHeadingIsPickedUp() {
        // HeadingProcessor wraps heading-style paragraphs as SemanticHeading; verify
        // these still flow through to page bookmarks.
        List<IObject> page = new ArrayList<>();
        page.add(createHeading("第一节 释义", 0, 50, 700, 680, 16));
        page.add(createHeading("第二节 概览", 0, 50, 600, 580, 16));
        page.add(createHeading("第三节 本次发行概况", 0, 50, 500, 480, 16));
        List<List<IObject>> contents = new ArrayList<>();
        contents.add(page);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(3, bookmarks.size());
        Assertions.assertEquals("第一节 释义", bookmarks.get(0).getText());
        Assertions.assertEquals("第二节 概览", bookmarks.get(1).getText());
        Assertions.assertEquals("第三节 本次发行概况", bookmarks.get(2).getText());
    }

    @Test
    public void testSkipsCatalogPageRange() {
        // Catalog (TOC) pages 0-1 contain TOC-style section entries that use the
        // same headings as the body sections on page 2+. Without the skip, the
        // SECTION group would have duplicate values [1, 1, 2, 2, 3, 3] and
        // isValidGroup would reject it. After skipping catalog pages 0-1, the
        // body values [1, 2, 3] form a valid consecutive group.
        List<List<IObject>> contents = multiPage(
                // Catalog page 0: TOC-style entries
                0, "第一节 释义 .... 1", 12.0f, 700.0,
                0, "第二节 概览 .... 1", 12.0f, 680.0,
                0, "第三节 本次发行概况 .... 1", 12.0f, 660.0,
                // Catalog page 1: more TOC-style entries
                1, "第一节 释义 .... 2", 12.0f, 700.0,
                1, "第二节 概览 .... 2", 12.0f, 680.0,
                1, "第三节 本次发行概况 .... 2", 12.0f, 660.0,
                // Body page 2: actual section headings
                2, "第一节 释义", 16.0f, 700.0,
                2, "第二节 概览", 16.0f, 650.0,
                2, "第三节 本次发行概况", 16.0f, 600.0);

        StaticLayoutContainers.setCatalogBookmarkPageRange(0, 1);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(3, bookmarks.size(), "Catalog pages should be skipped");
        Assertions.assertEquals("第一节 释义", bookmarks.get(0).getText());
        Assertions.assertEquals(3, bookmarks.get(0).getPageNum(), "First body section is on page 3 (1-indexed)");
        Assertions.assertEquals("第二节 概览", bookmarks.get(1).getText());
        Assertions.assertEquals("第三节 本次发行概况", bookmarks.get(2).getText());
    }

    @Test
    public void testEmptyContents() {
        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(null);
        Assertions.assertTrue(bookmarks.isEmpty());

        bookmarks = PageBookmarkProcessor.extractPageBookmarks(new ArrayList<>());
        Assertions.assertTrue(bookmarks.isEmpty());
    }

}
