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
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.json.JsonName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests for {@link CatalogBookmarkProcessor#extractCatalogBookmarksFromJson}.
 *
 * <p>Older versions of the JSON writer (and some external producers) wrote
 * {@code item_type == "text"} without a {@code source_type} field. With a
 * strict {@code source_type}-only filter those items were silently dropped
 * during title resolution, so catalog bookmarks kept the initial
 * {@code pageNum} derived from the printed TOC page number instead of the
 * actual physical page. These tests pin the legacy-schema path down so the
 * regression cannot come back unnoticed.</p>
 */
public class CatalogBookmarkProcessorTest {

    private static final double LEFT_X = 70.0;
    private static final double FONT_SIZE = 12.0;

    @BeforeEach
    public void setUp() {
        StaticLayoutContainers.clearContainers();
    }

    @AfterEach
    public void tearDown() {
        StaticLayoutContainers.clearContainers();
    }

    /**
     * Builds a JSON item that carries exactly one text line. Caller decides
     * whether {@code source_type} is present (current schema) or absent
     * (legacy schema). Layout mirrors what {@code JsonWriter} emits for a
     * standalone paragraph / heading item:
     * {@code {id, item_type, [source_type], content: [{content: [...]}], x0, y0, font_size}}.
     *
     * @param sourceType {@code null} to omit the field (legacy); otherwise
     *                   one of {@link JsonName#SOURCE_TYPE_HEADING},
     *                   {@link JsonName#SOURCE_TYPE_PARAGRAPH}.
     */
    private static Map<String, Object> legacyTextItem(int id, String text, String sourceType, double y0) {
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.ITEM_TYPE, "text");
        if (sourceType != null) {
            item.put(JsonName.SOURCE_TYPE, sourceType);
        }
        Map<String, Object> line = new HashMap<>();
        line.put(JsonName.CONTENT, Arrays.asList(text));
        line.put(JsonName.X0, LEFT_X);
        line.put(JsonName.Y0, y0);
        line.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        item.put(JsonName.CONTENT, Arrays.asList(line));
        item.put(JsonName.X0, LEFT_X);
        item.put(JsonName.Y0, y0);
        item.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        return item;
    }

    /**
     * Builds a TOC entry line item. The text must end with a page number so
     * that {@link CatalogBookmarkProcessor}'s ARABIC_TOC_PATTERN matches it
     * and the page is recognised as a TOC page.
     */
    private static Map<String, Object> tocLineItem(int id, String tocText, double y0) {
        // Same shape as a body item, but the line text uses the TOC pattern
        // "Title .... pageNum" — matchTocLine parses the trailing number.
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.ITEM_TYPE, "text");
        item.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_PARAGRAPH);
        Map<String, Object> line = new HashMap<>();
        line.put(JsonName.CONTENT, Arrays.asList(tocText));
        line.put(JsonName.X0, LEFT_X);
        line.put(JsonName.Y0, y0);
        line.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        item.put(JsonName.CONTENT, Arrays.asList(line));
        item.put(JsonName.X0, LEFT_X);
        item.put(JsonName.Y0, y0);
        item.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        return item;
    }

    private static Map<String, Object> page(int pageIndex, Map<String, Object>... items) {
        Map<String, Object> page = new HashMap<>();
        page.put(JsonName.PAGE_INDEX, pageIndex);
        page.put(JsonName.ITEMS, new ArrayList<>(Arrays.asList(items)));
        return page;
    }

    /**
     * Builds a single-line "目录/目錄" heading item. A catalog page is only
     * accepted as the first page of a catalog range when such a heading sits
     * above its first TOC line, so every TOC fixture starts with one.
     *
     * @param headingText heading text, e.g. {@code "目錄"}, {@code "目  录"}
     */
    private static Map<String, Object> catalogHeadingItem(int id, String headingText, double y0) {
        return legacyTextItem(id, headingText, JsonName.SOURCE_TYPE_HEADING, y0);
    }

    /**
     * Builds a text item holding several lines, i.e. a multi-line paragraph.
     * Used to prove that a "目录" mention buried inside such a paragraph is not
     * a catalog heading.
     */
    private static Map<String, Object> multiLineTextItem(int id, List<String> lines, double y0) {
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.ITEM_TYPE, "text");
        item.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_PARAGRAPH);
        List<Map<String, Object>> content = new ArrayList<>();
        double lineY = y0;
        for (String text : lines) {
            Map<String, Object> line = new HashMap<>();
            line.put(JsonName.CONTENT, Arrays.asList(text));
            line.put(JsonName.X0, LEFT_X);
            line.put(JsonName.Y0, lineY);
            line.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
            content.add(line);
            lineY += 20.0;
        }
        item.put(JsonName.CONTENT, content);
        item.put(JsonName.X0, LEFT_X);
        item.put(JsonName.Y0, y0);
        item.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        return item;
    }

    /**
     * Synthetic document:
     * <ul>
     *   <li>Page 0 (TOC): three TOC lines with printed pages 1, 5, 9.</li>
     *   <li>Page 1 (body): legacy item whose text is the chapter-1 title.
     *       Without the fix this item is invisible to title resolution, so
     *       the first bookmark's pageNum stays at the printed value 1.</li>
     *   <li>Pages 2..3 (body): legacy items for chapter-2 and chapter-3.</li>
     * </ul>
     * With the fix all three bookmarks must resolve to physical pages 2, 3, 4
     * (1-indexed) instead of falling back to printed 1, 5, 9.
     */
    private static List<Map<String, Object>> legacyDocument() {
        return new ArrayList<>(Arrays.asList(
                // Page 0: TOC — 目錄 heading + three TOC lines (ratio 3/4 = 0.75 ≥ 0.4,
                // lines 3 ≥ 3); the heading must sit above the first TOC line.
                page(0,
                        catalogHeadingItem(0, "目  錄", 50.0),
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                // Page 1: body — chapter-1 title, LEGACY schema (no source_type).
                page(1, legacyTextItem(10, "第一章 總則", null, 100.0)),
                // Page 2: body — chapter-2 title, LEGACY schema.
                page(2, legacyTextItem(11, "第二章 分則", null, 100.0)),
                // Page 3: body — chapter-3 title, LEGACY schema.
                page(3, legacyTextItem(12, "第三章 細則", null, 100.0))));
    }

    /**
     * Same layout as {@link #legacyDocument()} but every body item carries
     * {@code source_type: "heading"}. Used as a control case so we know the
     * legacy test exercises the new backward-compat path rather than some
     * unrelated behaviour.
     */
    private static List<Map<String, Object>> currentSchemaDocument() {
        return new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目录", 50.0),
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第一章 總則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(3, legacyTextItem(12, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0))));
    }

    /**
     * Legacy JSON (no {@code source_type}) must still resolve catalog
     * bookmarks to the physical body pages. Without the fix every body item
     * was filtered out, leaving each bookmark's {@code pageNum} at the
     * printed TOC value (1, 5, 9) — and the first chapter's bookmark would
     * stay on page 1, pointing back at the TOC itself.
     */
    @Test
    public void testLegacyJson_resolvesToPhysicalPage() {
        Config config = new Config();
        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(legacyDocument(), config);

        List<Bookmark> bookmarks = result.getBookmarks();
        Assertions.assertEquals(3, bookmarks.size(),
                "All three TOC entries should be extracted as bookmarks");
        Assertions.assertEquals(0, result.getStartPage(),
                "TOC range should start at page 0");
        Assertions.assertEquals(0, result.getEndPage(),
                "TOC range should span exactly one page in this layout");

        assertBookmarkPage(bookmarks.get(0), "第一章 總則", 2);
        assertBookmarkPage(bookmarks.get(1), "第二章 分則", 3);
        assertBookmarkPage(bookmarks.get(2), "第三章 細則", 4);
    }

    /**
     * Control: when {@code source_type} is present (current schema) the
     * resolver must produce identical physical-page results. If this test
     * diverges from the legacy one, the backward-compat path is silently
     * behaving differently from the supported path.
     */
    @Test
    public void testCurrentSchema_resolvesToPhysicalPage() {
        Config config = new Config();
        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(currentSchemaDocument(), config);

        List<Bookmark> bookmarks = result.getBookmarks();
        Assertions.assertEquals(3, bookmarks.size());

        assertBookmarkPage(bookmarks.get(0), "第一章 總則", 2);
        assertBookmarkPage(bookmarks.get(1), "第二章 分則", 3);
        assertBookmarkPage(bookmarks.get(2), "第三章 細則", 4);
    }

    /**
     * Non-text items (e.g. {@code source_type: image}) without a
     * {@code source_type} must remain rejected even after the backward-compat
     * widening. The check is on {@code item_type == "text"} as well, so an
     * image-shaped entry with {@code item_type: "image"} is filtered out
     * regardless of {@code source_type}.
     */
    @Test
    public void testLegacyJson_imageItemStillExcluded() {
        // Body layout:
        //   Page 1: IMAGE item with alt text "第一章 總則" (legacy,
        //           item_type=image, no source_type)
        //   Page 2: text item "第二章 分則" (legacy, no source_type)
        //   Page 3: text item "第三章 細則" (legacy, no source_type)
        //
        // If isParagraphOrHeadingItem forgot the item_type guard, the
        // chapter-1 candidate set would include the image on page 1 and the
        // bookmark would resolve to page 2 (|2 - 1| = 1, the closest match).
        // The fallback path gives chapter-1 an initial pageNum of
        // resolvePageIndex("1") + 1 = 1 (because rawPage "1" parses to 0).
        // With the guard in place chapter-1 keeps the fallback value (1)
        // rather than being silently re-routed to the image's page (2).
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目 錄", 50.0),
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyImageItem(20, "第一章 總則", 100.0)),
                page(2, legacyTextItem(11, "第二章 分則", null, 100.0)),
                page(3, legacyTextItem(12, "第三章 細則", null, 100.0))));

        Config config = new Config();
        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);

        List<Bookmark> bookmarks = result.getBookmarks();
        Assertions.assertEquals(3, bookmarks.size());

        // Chapter-1 has no text-typed candidate, so its pageNum must stay at
        // the fallback (1). If the image were allowed through, the resolver
        // would pick it as the closest match (distance |2-1|=1) and the
        // bookmark would resolve to physical page 2.
        Assertions.assertEquals(1, bookmarks.get(0).getPageNum(),
                "Image-flavoured legacy item must not be a resolution candidate for chapter 1");
        Assertions.assertEquals("第一章 總則", bookmarks.get(0).getText());

        assertBookmarkPage(bookmarks.get(1), "第二章 分則", 3);
        assertBookmarkPage(bookmarks.get(2), "第三章 細則", 4);
    }

    /**
     * A page full of page-number-terminated lines without a "目录/目錄"
     * heading must not be a catalog page — this is the price-table /
     * financial-data-row false positive that used to outscore the real table
     * of contents (more matched lines ⇒ higher range score).
     */
    @Test
    public void testPageWithoutCatalogHeading_isNotACatalogPage() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                // Page 0: real TOC (heading + 2 entries = 2 lines, below the
                // default minTocLines=3, so it cannot be detected on its own).
                page(0, catalogHeadingItem(0, "目 錄", 50.0),
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0)),
                // Page 1: price table page — 4 rows ending with digits, no heading.
                page(1,
                        tocLineItem(3, "九月                        0.218 0.115", 100.0),
                        tocLineItem(4, "十月                        0.169 0.14", 200.0),
                        tocLineItem(5, "十一月                      0.165 0.134", 300.0),
                        tocLineItem(6, "十二月                      0.153 0.126", 400.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Assertions.assertEquals(-1, result.getStartPage(),
                "A heading-less page must not be reported as the catalog page");
        Assertions.assertEquals(-1, result.getEndPage());
        Assertions.assertTrue(result.getBookmarks().isEmpty(),
                "No catalog bookmarks may be extracted from a heading-less page");
    }

    /**
     * Simplified and traditional headings are both accepted, and spaces
     * between the two characters are tolerated ("目 錄", "目  录").
     */
    @Test
    public void testCatalogHeading_simplifiedTraditionalAndSpaces() {
        String[] headings = {"目录", "目錄", "目 录", "目  錄", "目   录"};
        for (String heading : headings) {
            List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                    page(0,
                            catalogHeadingItem(0, heading, 50.0),
                            tocLineItem(1, "第一章 總則 .... 1", 100.0),
                            tocLineItem(2, "第二章 分則 .... 5", 200.0),
                            tocLineItem(3, "第三章 細則 .... 9", 300.0))));
            CatalogBookmarkProcessor.CatalogResult result =
                    CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());
            Assertions.assertEquals(0, result.getStartPage(),
                    "Heading '" + heading + "' should qualify the page as a catalog page");
        }
    }

    /**
     * The heading has to be a paragraph of its own: a "目录" line buried inside
     * a multi-line paragraph is a running text mention, not a catalog heading.
     */
    @Test
    public void testCatalogHeadingInsideMultiLineParagraph_doesNotQualify() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        multiLineTextItem(0, Arrays.asList(
                                "預期時間表. . . . . . . . . i",
                                "目錄. . . . . . . . . . . iv",
                                "概要. . . . . . . . . . . 1"), 40.0),
                        tocLineItem(1, "第一章 總則 .... 1", 200.0),
                        tocLineItem(2, "第二章 分則 .... 5", 300.0),
                        tocLineItem(3, "第三章 細則 .... 9", 400.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Assertions.assertEquals(-1, result.getStartPage(),
                "A multi-line paragraph containing 目录 must not qualify as the catalog heading");
    }

    /**
     * The heading must precede the TOC entries: the same page without it (or
     * with the heading placed below them) is not a catalog page.
     */
    @Test
    public void testCatalogHeadingBelowFirstTocLine_doesNotQualify() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0),
                        catalogHeadingItem(0, "目 錄", 400.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Assertions.assertEquals(-1, result.getStartPage(),
                "A heading below the first TOC line must not qualify the page");
    }

    /**
     * Only the first page of a range needs the heading: the continuation page
     * of a two-page table of contents extends an already open range.
     */
    @Test
    public void testContinuationPageWithoutHeading_extendsTheRange() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                // Page 0: heading + three entries.
                page(0,
                        catalogHeadingItem(0, "目  錄", 50.0),
                        tocLineItem(1, "第一章 總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                // Page 1: continuation of the same table of contents, no heading.
                page(1,
                        tocLineItem(4, "第四章 附則 .... 13", 100.0),
                        tocLineItem(5, "第五章 釋義 .... 17", 200.0),
                        tocLineItem(6, "第六章 生效 .... 21", 300.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Assertions.assertEquals(0, result.getStartPage());
        Assertions.assertEquals(1, result.getEndPage(),
                "The heading-less continuation page must extend the range, not split it");
        Assertions.assertEquals(6, result.getBookmarks().size());
    }

    /**
     * Reported case: the catalog entry carries a separator ({@code －}) that is
     * missing from the body heading. Exact and prefix matching both fail, so
     * the fuzzy fallback has to pair them — the strings share the prefix
     * {@code 附錄一} and the suffix {@code 購回授權之說明函件}, leaving one
     * unmatched character on the catalog side.
     */
    @Test
    public void testFuzzyMatch_catalogHasExtraSeparator() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目  錄", 50.0),
                        tocLineItem(1, "附錄一  －  購回授權之說明函件 .... 3", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                // Body running heading: same title without the "－" separator.
                page(3, legacyTextItem(12, "附錄一                  購回授權之說明函件",
                        JsonName.SOURCE_TYPE_HEADING, 100.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Bookmark appendix = result.getBookmarks().get(0);
        Assertions.assertEquals("附錄一  －  購回授權之說明函件", appendix.getText());
        Assertions.assertEquals(4, appendix.getPageNum(),
                "Fuzzy match must resolve the entry to the body heading on page 4");
        Assertions.assertEquals(12, appendix.getRelatedId(),
                "Fuzzy match must point at the body item, not at the catalog line");
    }

    /**
     * Mirror case: the extra separator sits on the body side
     * ({@code 第一章總則} vs {@code 第一章 － 總則}).
     */
    @Test
    public void testFuzzyMatch_bodyHasExtraSeparator() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目录", 50.0),
                        tocLineItem(1, "第一章總則 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(3, legacyTextItem(12, "第一章 － 總則", JsonName.SOURCE_TYPE_HEADING, 100.0))));

        CatalogBookmarkProcessor.CatalogResult result =
                CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, new Config());

        Bookmark chapter = result.getBookmarks().get(0);
        Assertions.assertEquals(4, chapter.getPageNum(),
                "The separator on the body side must still be tolerated");
        Assertions.assertEquals(12, chapter.getRelatedId());
    }

    /**
     * Two unmatched characters are only tolerated for long enough strings:
     * 8/10 characters pass, 6/8 characters do not.
     */
    @Test
    public void testFuzzyMatch_twoCharacterGapLengthFloors() {
        // 8 vs 10 characters -> accepted (floors 8 / 10).
        List<Map<String, Object>> accepted = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目录", 50.0),
                        tocLineItem(1, "財務報表附註摘要 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(3, legacyTextItem(12, "財務報表－：附註摘要", JsonName.SOURCE_TYPE_HEADING, 100.0))));
        Bookmark longMatch = CatalogBookmarkProcessor
                .extractCatalogBookmarksFromJson(accepted, new Config()).getBookmarks().get(0);
        Assertions.assertEquals(4, longMatch.getPageNum());

        // 6 vs 8 characters -> rejected (floors would be 8 / 10).
        List<Map<String, Object>> rejected = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目录", 50.0),
                        tocLineItem(1, "財務報表附註 .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(3, legacyTextItem(12, "財務報表－：附註", JsonName.SOURCE_TYPE_HEADING, 100.0))));
        Bookmark shortMatch = CatalogBookmarkProcessor
                .extractCatalogBookmarksFromJson(rejected, new Config()).getBookmarks().get(0);
        Assertions.assertEquals(1, shortMatch.getPageNum(),
                "A two-character gap on 6/8-character titles must not match");
    }

    /**
     * One-character gap on very short titles is rejected (floors 4 / 5) so that
     * a single overlapping character cannot pair unrelated headings.
     */
    @Test
    public void testFuzzyMatch_oneCharacterGapLengthFloors() {
        List<Map<String, Object>> data = new ArrayList<>(Arrays.asList(
                page(0,
                        catalogHeadingItem(0, "目录", 50.0),
                        tocLineItem(1, "AB .... 1", 100.0),
                        tocLineItem(2, "第二章 分則 .... 5", 200.0),
                        tocLineItem(3, "第三章 細則 .... 9", 300.0)),
                page(1, legacyTextItem(10, "第二章 分則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(2, legacyTextItem(11, "第三章 細則", JsonName.SOURCE_TYPE_HEADING, 100.0)),
                page(3, legacyTextItem(12, "A－B", JsonName.SOURCE_TYPE_HEADING, 100.0))));

        Bookmark bookmark = CatalogBookmarkProcessor
                .extractCatalogBookmarksFromJson(data, new Config()).getBookmarks().get(0);

        Assertions.assertEquals(1, bookmark.getPageNum(),
                "Titles shorter than 4 characters must not be paired by a one-character gap");
    }

    private static Map<String, Object> legacyImageItem(int id, String altText, double y0) {
        Map<String, Object> item = new HashMap<>();
        item.put(JsonName.ID, id);
        item.put(JsonName.ITEM_TYPE, "image");
        // No source_type on purpose: this is the cross-product case the
        // backward-compat guard must still exclude.
        item.put(JsonName.ALT, altText);
        item.put(JsonName.X0, LEFT_X);
        item.put(JsonName.Y0, y0);
        item.put(JsonName.FONT_UNDERLINE_SIZE, FONT_SIZE);
        return item;
    }

    private static void assertBookmarkPage(Bookmark bookmark, String expectedTitle, int expectedPageNum) {
        Assertions.assertEquals(expectedTitle, bookmark.getText(),
                "Bookmark title should be preserved from the TOC entry");
        Assertions.assertEquals(expectedPageNum, bookmark.getPageNum(),
                "Bookmark '" + expectedTitle + "' should resolve to physical page "
                        + expectedPageNum + " (1-indexed), not the printed TOC value");
    }
}
