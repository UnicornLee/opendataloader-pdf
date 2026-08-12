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
import org.opendataloader.pdf.json.JsonName;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.enums.SemanticType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PageBookmarkProcessorTest {

    private static CustomSemanticParagraph createParagraph(String text, int pageIndex, double leftX,
                                                           double topY, double bottomY, float fontSize) {
        TextLine line = new TextLine(new TextChunk(
                new BoundingBox(pageIndex, leftX, bottomY, leftX + 200, topY), text, fontSize, (topY + bottomY) / 2));
        TextBlock block = new TextBlock(line);
        return ParagraphProcessor.createParagraphFromTextBlock(block);
    }

    /**
     * Builds a {@link CustomSemanticParagraph} that spans multiple text lines.
     * Each entry in {@code lines} becomes a {@link TextLine} stacked vertically
     * by 10 units starting from {@code topY} downward, sharing the same
     * {@code fontSize}.
     */
    private static CustomSemanticParagraph createMultiLineParagraph(List<String> lines, int pageIndex,
                                                                    double leftX, double topY, float fontSize) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must be non-empty");
        }
        double currentTopY = topY;
        double currentBottomY = topY - 10;
        TextLine first = new TextLine(new TextChunk(
                new BoundingBox(pageIndex, leftX, currentBottomY, leftX + 200, currentTopY),
                lines.get(0), fontSize, (currentTopY + currentBottomY) / 2));
        TextBlock block = new TextBlock(first);
        for (int i = 1; i < lines.size(); i++) {
            currentTopY -= 10;
            currentBottomY -= 10;
            TextLine line = new TextLine(new TextChunk(
                    new BoundingBox(pageIndex, leftX, currentBottomY, leftX + 200, currentTopY),
                    lines.get(i), fontSize, (currentTopY + currentBottomY) / 2));
            block.add(line);
        }
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

    /**
     * Level-2 items should be preserved for every parent section. Within a
     * section, a stray duplicate value that sits between two contiguous runs
     * must be discarded and the two runs merged into a single contiguous
     * sequence.
     */
    @Test
    public void testStrayDuplicateBetweenRuns_isDropped() {
        // Sections 1-4 have short level-2 runs; section 5 "发行人基本情况"
        // uses full-width commas. In page order the values in section 5 are
        // 1,2,3,5,4,5,6,7,8,9,10,11. The first 5 (page 59) is a stray duplicate
        // between runs {1,2,3} and {4,5,6,7,8,9,10,11} and must be discarded.
        // (Half-width commas are converted to full-width before bookmark
        // extraction by DocumentProcessor.)
        List<List<IObject>> contents = multiPage(
                15, "第一节 释义", 15.96f, 900.0,
                15, "一、一般释义", 14.04f, 880.0,
                15, "二、行业专用释义", 14.04f, 860.0,
                21, "第二节 概览", 15.96f, 840.0,
                21, "一、发行人及本次发行", 14.04f, 820.0,
                21, "二、本次发行概况", 14.04f, 800.0,
                28, "第三节 本次发行概况", 15.96f, 780.0,
                28, "一、本次发行基本情况", 14.04f, 760.0,
                28, "二、本次发行的有关当事人", 14.04f, 740.0,
                32, "第四节 风险因素", 15.96f, 720.0,
                32, "一、创新风险", 14.04f, 700.0,
                32, "二、市场风险", 14.04f, 680.0,
                38, "第五节 发行人基本情况", 15.96f, 720.0,
                38, "一、基本情况", 14.04f, 700.0,
                38, "二、发行人的设立", 14.04f, 650.0,
                55, "三、报告期内的重大资产重组", 14.04f, 600.0,
                58, "五、发行人提交首发申请前一个会计年度", 12.0f, 550.0,
                63, "四、在其他证券市场的挂牌情况", 14.04f, 500.0,
                77, "五、发行人的股权结构", 14.04f, 450.0,
                77, "六、发行人控股子公司", 14.04f, 400.0,
                81, "七、持有发行人5%以上股份", 14.04f, 350.0,
                91, "八、公司股本情况", 14.04f, 300.0,
                96, "九、董事监事高管", 14.04f, 250.0,
                107, "十、股权激励", 14.04f, 200.0,
                107, "十一、发行人员工", 14.04f, 150.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(5, bookmarks.size(), "Section headings should be top-level");

        Bookmark section1 = bookmarks.get(0);
        Assertions.assertEquals("第一节 释义", section1.getText());
        Assertions.assertEquals(2, section1.getChildren().size(), "Section 1 level-2 items preserved");

        Bookmark section5 = bookmarks.get(4);
        Assertions.assertEquals("第五节 发行人基本情况", section5.getText());
        List<Bookmark> children = section5.getChildren();
        List<String> texts = new ArrayList<>();
        for (Bookmark child : children) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(Arrays.asList(
                "一、基本情况",
                "二、发行人的设立",
                "三、报告期内的重大资产重组",
                "四、在其他证券市场的挂牌情况",
                "五、发行人的股权结构",
                "六、发行人控股子公司",
                "七、持有发行人5%以上股份",
                "八、公司股本情况",
                "九、董事监事高管",
                "十、股权激励",
                "十一、发行人员工"), texts,
                "Stray duplicate '五' on page 59 must be discarded");
    }

    @Test
    public void testLocalTemplateConsistency() {
        // Verifies the local-consistency rule: all bookmarks under the same
        // parent share the same template, while sibling parents may use
        // different child templates depending on what is extracted between
        // consecutive parents.
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 绪论", 0, 50, 700, 690, 20),
                createParagraph("一、概述", 0, 60, 680, 670, 16),
                createParagraph("二、背景", 0, 60, 660, 650, 16),
                createParagraph("第2章 正文", 0, 50, 640, 630, 20),
                createParagraph("（一）方法", 0, 60, 620, 610, 16),
                createParagraph("1、步骤一", 0, 70, 600, 590, 14),
                createParagraph("2、步骤二", 0, 70, 580, 570, 14),
                createParagraph("（二）结果", 0, 60, 560, 550, 16),
                createParagraph("（1）结果1", 0, 70, 540, 530, 14),
                createParagraph("（2）结果2", 0, 70, 520, 510, 14));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(2, bookmarks.size(), "All top-level bookmarks must share the same template");
        Assertions.assertEquals("第1章 绪论", bookmarks.get(0).getText());
        Assertions.assertEquals("第2章 正文", bookmarks.get(1).getText());

        Bookmark chapter1 = bookmarks.get(0);
        Assertions.assertEquals(2, chapter1.getChildren().size());
        Assertions.assertEquals("一、概述", chapter1.getChildren().get(0).getText());
        Assertions.assertEquals("二、背景", chapter1.getChildren().get(1).getText());
        Assertions.assertTrue(chapter1.getChildren().get(0).getChildren().isEmpty());
        Assertions.assertTrue(chapter1.getChildren().get(1).getChildren().isEmpty());

        Bookmark chapter2 = bookmarks.get(1);
        Assertions.assertEquals(2, chapter2.getChildren().size());
        Assertions.assertEquals("（一）方法", chapter2.getChildren().get(0).getText());
        Assertions.assertEquals("（二）结果", chapter2.getChildren().get(1).getText());

        Bookmark method = chapter2.getChildren().get(0);
        Assertions.assertEquals(2, method.getChildren().size());
        Assertions.assertEquals("1、步骤一", method.getChildren().get(0).getText());
        Assertions.assertEquals("2、步骤二", method.getChildren().get(1).getText());

        Bookmark result = chapter2.getChildren().get(1);
        Assertions.assertEquals(2, result.getChildren().size());
        Assertions.assertEquals("（1）结果1", result.getChildren().get(0).getText());
        Assertions.assertEquals("（2）结果2", result.getChildren().get(1).getText());
    }

    @Test
    public void testTopLevelSelectsSingleTemplate() {
        // When multiple templates could serve as top-level markers, only one
        // is selected; the other template becomes a child of its preceding
        // top-level parent.
        List<List<IObject>> contents = singlePage(
                createParagraph("第1章 A", 0, 50, 700, 690, 20),
                createParagraph("一、B", 0, 60, 680, 670, 18),
                createParagraph("第2章 C", 0, 50, 660, 650, 20),
                createParagraph("二、D", 0, 60, 640, 630, 18),
                createParagraph("第3章 E", 0, 50, 620, 610, 20));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(3, bookmarks.size(), "Only chapter-style headings should be top-level");
        Assertions.assertEquals("第1章 A", bookmarks.get(0).getText());
        Assertions.assertEquals("第2章 C", bookmarks.get(1).getText());
        Assertions.assertEquals("第3章 E", bookmarks.get(2).getText());

        Assertions.assertEquals(1, bookmarks.get(0).getChildren().size());
        Assertions.assertEquals("一、B", bookmarks.get(0).getChildren().get(0).getText());
        Assertions.assertEquals(1, bookmarks.get(1).getChildren().size());
        Assertions.assertEquals("二、D", bookmarks.get(1).getChildren().get(0).getText());
        Assertions.assertTrue(bookmarks.get(2).getChildren().isEmpty());
    }

    /**
     * Within one parent there can be two sets of consecutive headings whose
     * value ranges overlap (e.g., pages 1-6 carry 一..六, then later pages
     * 17-30 restart at 一..四 as sub-items of a different group). The
     * selector must prefer the more complete set rather than blindly keeping
     * the latest occurrence per value.
     */
    @Test
    public void testPicksMoreCompleteSetWhenTwoOverlap() {
        // Set 1 (pages 1-6, near parent's start): 一..六 (complete, length 6).
        // Set 2 (pages 17-30, further into parent): 一..四 (incomplete, length 4).
        // Expected: Set 1 wins because its value range is wider (6 vs 4).
        List<List<IObject>> contents = multiPage(
                1, "第一节 投资者保护", 18.0f, 900.0,
                1, "一、信息披露制度", 14.0f, 880.0,
                2, "二、股利分配政策", 14.0f, 860.0,
                5, "三、滚存利润安排", 14.0f, 840.0,
                5, "四、股东投票机制", 14.0f, 820.0,
                6, "五、特别表决权", 14.0f, 800.0,
                6, "六、承诺事项", 14.0f, 780.0,
                // Gap simulates non-numbered body content; then Set 2 starts.
                17, "一、股价稳定措施", 14.0f, 760.0,
                28, "二、利润分配政策", 14.0f, 740.0,
                28, "三、分红回报规划", 14.0f, 720.0,
                30, "四、利润分配承诺", 14.0f, 700.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size(), "Only the section should be top-level");
        Bookmark section = bookmarks.get(0);
        Assertions.assertEquals("第一节 投资者保护", section.getText());

        List<String> texts = new ArrayList<>();
        for (Bookmark child : section.getChildren()) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(Arrays.asList(
                "一、信息披露制度",
                "二、股利分配政策",
                "三、滚存利润安排",
                "四、股东投票机制",
                "五、特别表决权",
                "六、承诺事项"), texts,
                "Should pick the more complete set (Set 1) closer to the parent");
    }

    /**
     * When two sets have equal value-range width, the chain whose first
     * candidate is on the earliest page wins (closer to the parent).
     */
    @Test
    public void testPicksChainCloserToParentWhenLengthsTie() {
        // Both sets cover values 1-3 (same length 3). Set 1 starts on page 5,
        // Set 2 starts on page 100. The selector should keep Set 1 because
        // its first candidate is closer to the parent's start page.
        List<List<IObject>> contents = multiPage(
                0, "第一节 测试", 18.0f, 900.0,
                5, "一、靠近父目录", 14.0f, 880.0,
                5, "二、靠近父目录", 14.0f, 860.0,
                5, "三、靠近父目录", 14.0f, 840.0,
                100, "一、远离父目录", 14.0f, 820.0,
                100, "二、远离父目录", 14.0f, 800.0,
                100, "三、远离父目录", 14.0f, 780.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark section = bookmarks.get(0);
        Assertions.assertEquals("第一节 测试", section.getText());

        List<String> texts = new ArrayList<>();
        for (Bookmark child : section.getChildren()) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(Arrays.asList(
                "一、靠近父目录",
                "二、靠近父目录",
                "三、靠近父目录"), texts,
                "Should pick the chain closer to the parent on tied length");
    }

    /**
     * A multi-line paragraph bookmark must expose the full text in
     * {@code bookmark.text} and report {@code isSingleLine() == false} so
     * downstream consumers can tell wrapped titles apart from one-line ones.
     */
    @Test
    public void testMultiLineBookmarkFullText() {
        // Standalone two-line paragraph; the first line matches "一、" so it
        // becomes a bookmark candidate. Both lines must end up in
        // bookmark.text after smart-space joining.
        List<List<IObject>> contents = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("一、信息披露制度", "与投资者关系"),
                        0, 50, 900, 14.0f));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark bookmark = bookmarks.get(0);
        Assertions.assertEquals("一、信息披露制度与投资者关系", bookmark.getText(),
                "Multi-line text joins without stray spaces (Chinese+Chinese)");
        Assertions.assertFalse(bookmark.getSingleLine(),
                "Multi-line bookmark must report isSingleLine=false");
    }

    /**
     * Single-line bookmarks keep their original text and {@code isSingleLine=true}
     * so the existing behaviour (and existing tests) are preserved.
     */
    @Test
    public void testSingleLineBookmarkUnchanged() {
        List<List<IObject>> contents = singlePage(
                createParagraph("一、信息披露制度与投资者关系", 0, 50, 700, 680, 14.0f));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Bookmark bookmark = bookmarks.get(0);
        Assertions.assertEquals("一、信息披露制度与投资者关系", bookmark.getText());
        Assertions.assertTrue(bookmark.getSingleLine());
    }

    /**
     * Smart-space boundary cases:
     * <ul>
     *   <li>Letter + letter across the line break: insert one space.</li>
     *   <li>Digit + digit across the line break: insert one space.</li>
     *   <li>Chinese + English across the line break: no space (rule requires
     *       both sides to be the same ASCII category).</li>
     *   <li>Letter + digit / digit + letter: no space (cross-category).</li>
     * </ul>
     */
    @Test
    public void testMultiLineSmartSpaceBoundary() {
        // First line ends with Chinese '题', second line starts with ASCII 'H'.
        // Both lines contribute to a single bookmark; rule leaves no space.
        List<List<IObject>> contents = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("一、英文标题", "HL GLOBAL"),
                        0, 50, 900, 14.0f));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Bookmark chineseThenEnglish = bookmarks.get(0);
        Assertions.assertEquals("一、英文标题HL GLOBAL", chineseThenEnglish.getText(),
                "Chinese line + English next-line: rule is letter+letter OR digit+digit; "
                        + "Chinese is neither, so no space is inserted");
        Assertions.assertFalse(chineseThenEnglish.getSingleLine());

        // Letter + letter: a single space is inserted. (Use "第1条" prefix so the
        // paragraph is a recognised bookmark candidate.)
        List<List<IObject>> letterThenLetter = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("第1条 Section", "Header"),
                        0, 50, 900, 14.0f));
        Bookmark letterThenLetterBm = PageBookmarkProcessor.extractPageBookmarks(letterThenLetter).get(0);
        Assertions.assertEquals("第1条 Section Header", letterThenLetterBm.getText(),
                "Letter+letter across lines should insert one space");

        // Digit + digit: a single space is inserted. ("（一）" is a recognised
        // bookmark prefix; the line ends with a digit so digit+digit applies
        // across the break.)
        List<List<IObject>> digitThenDigit = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("（一）42", "12"),
                        0, 50, 900, 14.0f));
        Bookmark digitThenDigitBm = PageBookmarkProcessor.extractPageBookmarks(digitThenDigit).get(0);
        Assertions.assertEquals("（一）42 12", digitThenDigitBm.getText(),
                "Digit+digit across lines should insert one space");

        // Letter + digit: no space (different categories).
        List<List<IObject>> letterThenDigit = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("一、Item", "12"),
                        0, 50, 900, 14.0f));
        Bookmark letterThenDigitBm = PageBookmarkProcessor.extractPageBookmarks(letterThenDigit).get(0);
        Assertions.assertEquals("一、Item12", letterThenDigitBm.getText(),
                "Letter+digit across lines: no space (different categories)");
        Assertions.assertFalse(letterThenDigitBm.getSingleLine());

        // Digit + letter: no space.
        List<List<IObject>> digitThenLetter = singlePage(
                createMultiLineParagraph(
                        Arrays.asList("（一）42", "Steps"),
                        0, 50, 900, 14.0f));
        Bookmark digitThenLetterBm = PageBookmarkProcessor.extractPageBookmarks(digitThenLetter).get(0);
        Assertions.assertEquals("（一）42Steps", digitThenLetterBm.getText(),
                "Digit+letter across lines: no space (different categories)");
    }

    @Test
    public void testEmptyContents() {
        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(null);
        Assertions.assertTrue(bookmarks.isEmpty());

        bookmarks = PageBookmarkProcessor.extractPageBookmarks(new ArrayList<>());
        Assertions.assertTrue(bookmarks.isEmpty());
    }

    /**
     * Builds a JSON item for {@link PageBookmarkProcessor#extractPageBookmarksFromJson}.
     * y0 is chosen so that, within a page, items are read top-to-bottom in the
     * order they were added (ascending y0). Only the id and text differ per item.
     */
    private static Map<String, Object> jsonItem(int id, String text, double y0) {
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_PARAGRAPH);
        item.put(JsonName.CONTENT, Arrays.asList(text));
        item.put(JsonName.FONT_UNDERLINE_SIZE, 14.0);
        item.put(JsonName.X0, 70.0);
        item.put(JsonName.Y0, y0);
        return item;
    }

    /**
     * Builds a single-page JSON document consisting of a level-1 chapter and its
     * level-2 children ({@code "一、…"}), all on page 0. The chapter carries a
     * larger font size so it always wins level-1 template selection, sending the
     * children through {@code cleanCandidatesLocal} (level >= 2).
     */
    private static List<Map<String, Object>> chapterWithChildren(List<String> childrenTexts, int[] childIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> chapter = new HashMap<>();
        chapter.put(JsonName.ID, 1);
        chapter.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_HEADING);
        chapter.put(JsonName.CONTENT, Arrays.asList("第1章 绪论"));
        chapter.put(JsonName.FONT_UNDERLINE_SIZE, 20.0);
        chapter.put(JsonName.X0, 50.0);
        chapter.put(JsonName.Y0, 100.0);
        items.add(chapter);
        double y0 = 200.0;
        for (int i = 0; i < childrenTexts.size(); i++) {
            items.add(jsonItem(childIds[i], childrenTexts.get(i), y0));
            y0 += 100.0;
        }
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> page = new HashMap<>();
        page.put(JsonName.ITEMS, items);
        data.add(page);
        return data;
    }

    @Test
    public void testTocFilter_overLongEntry_dropsChildrenChain() {
        StringBuilder sb = new StringBuilder("一、");
        for (int i = 0; i < 250; i++) {
            sb.append('长');
        }
        String longEntry = sb.toString();
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList(longEntry, "二、背景", "三、方法"),
                new int[]{1, 3, 5});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals("第1章 绪论", bookmarks.get(0).getText());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                "A chain with an over-long entry (>200 chars) must be dropped");
    }

    @Test
    public void testTocFilter_smallChainAdjacentPair_isDropped() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法"),
                new int[]{1, 2, 5});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                "2-5 entry chain containing an adjacent pair must be dropped");
    }

    @Test
    public void testTocFilter_smallChainNoAdjacency_isKept() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法"),
                new int[]{1, 3, 5});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        List<Bookmark> children = bookmarks.get(0).getChildren();
        Assertions.assertEquals(3, children.size());
        Assertions.assertEquals("一、概述", children.get(0).getText());
        Assertions.assertEquals("二、背景", children.get(1).getText());
        Assertions.assertEquals("三、方法", children.get(2).getText());
    }

    @Test
    public void testTocFilter_largeChainTwoAdjacentPairs_isDropped() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法", "四、结论", "五、附录", "六、索引"),
                new int[]{1, 2, 5, 6, 9, 10});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                ">5 entry chain with two adjacent pairs must be dropped");
    }

    @Test
    public void testTocFilter_largeChainRunOfThree_isDropped() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法", "四、结论", "五、附录", "六、索引"),
                new int[]{1, 2, 3, 6, 8, 10});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                ">5 entry chain with a same-page run of three must be dropped");
    }

    @Test
    public void testTocFilter_largeChainSingleAdjacentPair_isKept() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法", "四、结论", "五、附录", "六、索引"),
                new int[]{1, 2, 5, 7, 9, 11});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals(6, bookmarks.get(0).getChildren().size(),
                ">5 entry chain with a single adjacent pair must be kept");
    }

    @Test
    public void testTocFilter_largeChainNoAdjacency_isKept() {
        List<Map<String, Object>> data = chapterWithChildren(
                Arrays.asList("一、概述", "二、背景", "三、方法", "四、结论", "五、附录", "六、索引"),
                new int[]{1, 4, 7, 10, 13, 16});

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals(6, bookmarks.get(0).getChildren().size());
    }

    private static Map<String, Object> jsonChapter() {
        Map<String, Object> chapter = new HashMap<>();
        chapter.put(JsonName.ID, 1);
        chapter.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_HEADING);
        chapter.put(JsonName.CONTENT, Arrays.asList("第1章 绪论"));
        chapter.put(JsonName.FONT_UNDERLINE_SIZE, 20.0);
        chapter.put(JsonName.X0, 50.0);
        chapter.put(JsonName.Y0, 100.0);
        return chapter;
    }

    private static Map<String, Object> jsonNonTextItem(int id, String sourceType, double y0) {
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.SOURCE_TYPE, sourceType);
        item.put(JsonName.X0, 70.0);
        item.put(JsonName.Y0, y0);
        return item;
    }

    @SafeVarargs
    private static Map<String, Object> jsonPage(Map<String, Object>... items) {
        Map<String, Object> page = new HashMap<>();
        page.put(JsonName.ITEMS, new ArrayList<>(Arrays.asList(items)));
        return page;
    }

    @SafeVarargs
    private static List<Map<String, Object>> jsonDoc(Map<String, Object>... pages) {
        return new ArrayList<>(Arrays.asList(pages));
    }

    /**
     * Page 0's last item is a level-2 child ("一、概述", id 2) and page 1 begins
     * with "二、背景" as id 1. The chain ends at the page boundary and resumes on
     * the next page, so the pair must be treated as adjacent and dropped.
     */
    @Test
    public void testTocFilter_crossPageBridge_isDropped() {
        List<Map<String, Object>> data = jsonDoc(
                jsonPage(jsonChapter(), jsonItem(2, "一、概述", 200.0)),
                jsonPage(jsonItem(1, "二、背景", 100.0)));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals("第1章 绪论", bookmarks.get(0).getText());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                "Chain ending at a page's last element and resuming with id 1 on the next page must be dropped");
    }

    /**
     * Same layout as {@link #testTocFilter_crossPageBridge_isDropped} but the
     * next page's id-1 item is an image, not text. The rule requires the id-1
     * element to be text when the resume id is 2, so no cross-page adjacency
     * exists and the chain must be kept.
     */
    @Test
    public void testTocFilter_crossPageBridge_id1NotText_isKept() {
        List<Map<String, Object>> data = jsonDoc(
                jsonPage(jsonChapter(), jsonItem(2, "一、概述", 200.0)),
                jsonPage(
                        jsonNonTextItem(1, JsonName.SOURCE_TYPE_IMAGE, 100.0),
                        jsonItem(2, "二、背景", 200.0)));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals(2, bookmarks.get(0).getChildren().size(),
                "Resume id 2 with a non-text id-1 element must not form a cross-page pair");
    }

    /**
     * Page 0's chapter is followed by a lattice table (id 3), so the child with
     * id 2 is no longer the page's last element. No cross-page adjacency exists
     * and the chain must be kept.
     */
    @Test
    public void testTocFilter_crossPageBridge_prevNotLastElement_isKept() {
        List<Map<String, Object>> data = jsonDoc(
                jsonPage(
                        jsonChapter(),
                        jsonItem(2, "一、概述", 200.0),
                        jsonNonTextItem(3, JsonName.SOURCE_TYPE_LATTICE_TABLE, 300.0)),
                jsonPage(jsonItem(1, "二、背景", 100.0)));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertEquals(2, bookmarks.get(0).getChildren().size(),
                "A child that is not the page's last element must not form a cross-page pair");
    }

    /**
     * A 6-entry chain spanning two pages where page 0 ends at its last element
     * (id 3) and page 1 resumes at id 1. Same-page pairs on page 1 plus the
     * page-boundary pair exceed the threshold, so the chain must be dropped.
     */
    @Test
    public void testTocFilter_crossPageBridge_largeChain_isDropped() {
        List<Map<String, Object>> data = jsonDoc(
                jsonPage(
                        jsonChapter(),
                        jsonItem(2, "一、概述", 200.0),
                        jsonItem(3, "二、背景", 300.0)),
                jsonPage(
                        jsonItem(1, "三、方法", 100.0),
                        jsonItem(2, "四、结论", 200.0),
                        jsonItem(4, "五、附录", 300.0),
                        jsonItem(5, "六、索引", 400.0)));

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(data, -1, -1);
        Assertions.assertEquals(1, bookmarks.size());
        Assertions.assertTrue(bookmarks.get(0).getChildren().isEmpty(),
                ">5 entry chain with same-page and page-boundary adjacency must be dropped");
    }

    /**
     * Orphan singleton {@code value=1} candidates must be prepended onto a chain
     * whose first group starts at {@code value=2}, so the leading "一、" entry
     * survives the widest-chain selection. This mirrors the production failure
     * where "一、审计报告" was dropped because the same template also contained
     * "一、审计意见", forming a competing value=1-6 chain that lost to the
     * value=2..18 chain starting at "二、财务报表".
     */
    @Test
    public void testLonelyValueOnePrependsToValueTwoChain() {
        List<List<IObject>> contents = multiPage(
                // L1 chapter; use 第#章 with value=1 so that isValidGroup accepts
                // a single candidate at level 1.
                0, "第1章 财务报告", 20.0f, 1100.0,
                // Sub-page-1 candidates: 一、审计报告 alone, then 一、审计意见
                // and its value=2..6 sibling chain.
                0, "一、审计报告", 12.0f, 850.0,
                0, "一、审计意见", 9.12f, 800.0,
                0, "二、形成审计意见的基础", 9.12f, 750.0,
                0, "三、关键审计事项", 9.12f, 700.0,
                0, "四、其他信息", 9.12f, 650.0,
                0, "五、管理层和治理层的责任", 9.12f, 600.0,
                0, "六、注册会计师的责任", 9.12f, 550.0,
                // Page 2 starts the value=2..18 chain (二、财务报表...).
                1, "二、财务报表", 12.0f, 900.0,
                1, "三、公司基本情况", 12.0f, 850.0,
                1, "四、财务报表的编制基础", 12.0f, 800.0,
                1, "五、重要会计政策及会计估计", 12.0f, 750.0,
                1, "六、税项", 12.0f, 700.0,
                1, "七、合并财务报表项目注释", 12.0f, 650.0,
                1, "八、合并范围的变更", 12.0f, 600.0,
                1, "九、在其他主体中的权益", 12.0f, 550.0,
                1, "十、与金融工具相关的风险", 12.0f, 500.0,
                2, "十一、公允价值的披露", 12.0f, 900.0,
                2, "十二、关联方及关联交易", 12.0f, 850.0,
                2, "十三、股份支付", 12.0f, 800.0,
                2, "十四、承诺及或有事项", 12.0f, 750.0,
                2, "十五、资产负债表日后事项", 12.0f, 700.0,
                2, "十六、其他重要事项", 12.0f, 650.0,
                2, "十七、母公司财务报表主要项目注释", 12.0f, 600.0,
                2, "十八、补充资料", 12.0f, 550.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark section = bookmarks.get(0);
        Assertions.assertEquals("第1章 财务报告", section.getText());

        List<String> texts = new ArrayList<>();
        for (Bookmark child : section.getChildren()) {
            texts.add(child.getText());
        }
        // 一、审计报告 must be the FIRST child after the prepend pass; the
        // value=1..6 sub-chain (一、审计意见..六、注册会计师的责任) is dropped
        // because the merged value=1..18 chain (length 18) wins by width.
        Assertions.assertEquals(
                Arrays.asList(
                        "一、审计报告",
                        "二、财务报表",
                        "三、公司基本情况",
                        "四、财务报表的编制基础",
                        "五、重要会计政策及会计估计",
                        "六、税项",
                        "七、合并财务报表项目注释",
                        "八、合并范围的变更",
                        "九、在其他主体中的权益",
                        "十、与金融工具相关的风险",
                        "十一、公允价值的披露",
                        "十二、关联方及关联交易",
                        "十三、股份支付",
                        "十四、承诺及或有事项",
                        "十五、资产负债表日后事项",
                        "十六、其他重要事项",
                        "十七、母公司财务报表主要项目注释",
                        "十八、补充资料"),
                texts,
                "Orphan value=1 should be prepended onto the value=2 chain, producing values 1..18");
    }

    /**
     * If a {@code value=1} singleton exists but no separate chain starts at
     * {@code value=2} (e.g., the document's L2 sequence is just values
     * 1..N), the merge pass must NOT fabricate a value=2 chain. The natural
     * value-1..N chain keeps winning unchanged.
     */
    @Test
    public void testLonelyValueOneNotMergedWhenNoValueTwoChain() {
        List<List<IObject>> contents = multiPage(
                0, "第1章 测试", 20.0f, 1100.0,
                0, "一、A", 12.0f, 850.0,
                0, "一、B", 12.0f, 800.0,
                0, "二、C", 12.0f, 750.0,
                0, "三、D", 12.0f, 700.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark section = bookmarks.get(0);
        List<String> texts = new ArrayList<>();
        for (Bookmark child : section.getChildren()) {
            texts.add(child.getText());
        }
        // No value=2 chain exists, so the merge pass leaves "一、A" out (it
        // forms an orphan value=1 chain of length 1 while "一、B/二、C/三、D"
        // forms a chain of length 3). The natural 1..3 chain is selected,
        // matching pre-merge behavior for this input.
        Assertions.assertEquals(Arrays.asList("一、B", "二、C", "三、D"), texts,
                "Without a value=2 target chain, prepend pass is a no-op");
    }

    /**
     * Only chains whose single-candidate group has {@code value == 1} are
     * candidates for the prepend pass. An orphan with value != 1 (e.g. a lone
     * value=3 left over after a gap) must remain untouched by the merge pass
     * and compete normally in the widest-chain selection.
     */
    @Test
    public void testLonelyNonOneNotMerged() {
        // The 1..2 chain plus a separate value=5 candidate (with values 3 and
        // 4 missing) gives two disjoint groups. The value=5 group is its own
        // chain of length 1. The prepend pass must NOT treat it as a merge
        // target because its value is 5, not 1; the 1..2 chain wins on width.
        List<List<IObject>> contents = multiPage(
                0, "第1章 测试", 20.0f, 1100.0,
                0, "一、A", 12.0f, 850.0,
                0, "二、B", 12.0f, 800.0,
                0, "五、E", 12.0f, 750.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark section = bookmarks.get(0);
        List<String> texts = new ArrayList<>();
        for (Bookmark child : section.getChildren()) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(Arrays.asList("一、A", "二、B"), texts,
                "An orphan with value != 1 must not trigger the prepend pass");
    }

    /**
     * When the L3 candidates inside an L2 anchor's range share the L2
     * template (e.g. nested "一、" items under an "一、" L2 anchor), the L3
     * selection must be allowed to pick that template. Previously the L2
     * templateKey was added to {@code usedTemplates} when recursing into L3,
     * which silently dropped all "一、" candidates and let an unrelated
     * "（一）" template win on count.
     *
     * <p>Layout mirrors the production failure in
     * {@code 202303251679660111823147.pdf}: a single chapter
     * ({@code 第1章}) carries an orphan {@code 一、X} L2 entry, a sibling
     * value=1..6 {@code 一、} chain that gets DROPPED at L2 (because it
     * loses the widest-chain race), and a wider value=2..6 {@code 一、}
     * chain. The orphan L2 entry survives only because the value=2 chain
     * absorbs it via the value=1-prepend pass; we then verify the L3
     * selection under that anchor correctly reuses the L2 {@code 一、}
     * template for the value=1..6 candidates living in its range.</p>
     */
    @Test
    public void testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange() {
        // Single chapter 第1章 (font 20, value=1).
        // Page 0: orphan 一、X (font 12, value=1) — the L2 anchor.
        // Page 1: L3 candidates 一、A...六、F (font 9, value 1..6) AND
        // the "（一）" pair (font 9, value 1..2) live in 一、X's range.
        // Page 2: L2 siblings 二、Y...八、W (font 12, value 2..8) — this
        // wider chain absorbs 一、X via the value=1-prepend pass and
        // gives the L2 result a strict-width lead over the page-1 chain.
        //
        // Critical: the page-1 value=1 item ("一、A") must NOT be followed
        // by a value=2 item on the same page, otherwise Step-2 merges them
        // into one group and the page-1 chain absorbs the value=2..8 chain.
        List<List<IObject>> contents = multiPage(
                0, "第1章 测试", 20.0f, 1100.0,
                0, "一、X", 12.0f, 950.0,
                1, "一、A", 9.0f, 850.0,
                1, "二、B", 9.0f, 820.0,
                1, "三、C", 9.0f, 790.0,
                1, "四、D", 9.0f, 760.0,
                1, "五、E", 9.0f, 730.0,
                1, "六、F", 9.0f, 700.0,
                1, "（一）P", 9.0f, 600.0,
                1, "（二）Q", 9.0f, 570.0,
                2, "二、Y", 12.0f, 900.0,
                2, "三、Z", 12.0f, 870.0,
                2, "四、W", 12.0f, 840.0,
                2, "五、V", 12.0f, 810.0,
                2, "六、U", 12.0f, 780.0,
                2, "七、T", 12.0f, 750.0,
                2, "八、S", 12.0f, 720.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark chapter = bookmarks.get(0);
        Assertions.assertEquals("第1章 测试", chapter.getText());
        // L2 selection: 一、X (prepended onto value=2..8 chain) → 8 entries.
        Assertions.assertEquals(8, chapter.getChildren().size());

        Bookmark x = chapter.getChildren().get(0);
        Assertions.assertEquals("一、X", x.getText());

        // L3 selection under 一、X: the value=1..6 chain (font 9) AND the
        // "（一）" pair (font 9) live in this range. The "一、" group has 6
        // candidates vs 2 for "（一）"; with the L3-reuses-L2 fix the
        // "一、" group is no longer filtered out, so it must win.
        List<String> texts = new ArrayList<>();
        for (Bookmark child : x.getChildren()) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(
                Arrays.asList("一、A", "二、B", "三、C", "四、D", "五、E", "六、F"), texts,
                "L3 must reuse the L2 '一、' template when it forms a wider run than the alternative");
    }

    /**
     * Reverse of {@link #testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange}:
     * even after the L3 selection is allowed to reuse the L2 template, a
     * deeper-hierarchy alternative with strictly more candidates must still
     * win. This guards against a future change that would always prefer the
     * L2 template at L3 regardless of count.
     */
    @Test
    public void testLevel3PicksDeeperTemplateWhenL2TemplateHasFewerCandidates() {
        // Same L1 + L2 layout as the forward test (orphan 一、X survives
        // via prepend). Page 1 under 一、X carries a "1、..5、" run (5
        // items) — no "一、" candidate at all. The "1、" group must win
        // even though it differs from the L2 templateKey.
        List<List<IObject>> contents = multiPage(
                0, "第1章 测试", 20.0f, 1100.0,
                0, "一、X", 12.0f, 950.0,
                1, "1、B", 9.0f, 820.0,
                1, "2、C", 9.0f, 790.0,
                1, "3、D", 9.0f, 760.0,
                1, "4、E", 9.0f, 730.0,
                1, "5、F", 9.0f, 700.0,
                2, "二、Y", 12.0f, 900.0,
                2, "三、Z", 12.0f, 870.0,
                2, "四、W", 12.0f, 840.0,
                2, "五、V", 12.0f, 810.0,
                2, "六、U", 12.0f, 780.0,
                2, "七、T", 12.0f, 750.0,
                2, "八、S", 12.0f, 720.0);

        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarks(contents);
        Assertions.assertEquals(1, bookmarks.size());
        Bookmark chapter = bookmarks.get(0);
        // L2 result: 一、X + the value=2..8 chain (8 entries total).
        Assertions.assertEquals(8, chapter.getChildren().size());

        Bookmark x = chapter.getChildren().get(0);
        Assertions.assertEquals("一、X", x.getText());
        List<String> texts = new ArrayList<>();
        for (Bookmark child : x.getChildren()) {
            texts.add(child.getText());
        }
        Assertions.assertEquals(
                Arrays.asList("1、B", "2、C", "3、D", "4、E", "5、F"), texts,
                "L3 still prefers a deeper template with strictly more candidates");
    }

}
