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
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

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

    @Test
    public void testEmptyContents() {
        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(null);
        Assertions.assertTrue(bookmarks.isEmpty());

        bookmarks = PageBookmarkProcessor.extractPageBookmarks(new ArrayList<>());
        Assertions.assertTrue(bookmarks.isEmpty());
    }

}
