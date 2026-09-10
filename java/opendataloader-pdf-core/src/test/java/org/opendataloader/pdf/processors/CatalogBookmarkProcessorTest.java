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
                // Page 0: TOC — three TOC lines, ratio=3/3=1.0 ≥ 0.4 and lines=3 ≥ 3.
                page(0,
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
