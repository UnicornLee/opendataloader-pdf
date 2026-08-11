package org.opendataloader.pdf.custom.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.custom.entities.Bookmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BookmarkQualitySelectorTest {

    private static Bookmark bookmark(String text, int pageNum, int relatedId) {
        Bookmark bookmark = new Bookmark();
        bookmark.setText(text);
        bookmark.setPageNum(pageNum);
        bookmark.setRelatedId(relatedId);
        bookmark.setChildren(new ArrayList<>());
        return bookmark;
    }

    private static List<Bookmark> flat(String... texts) {
        List<Bookmark> bookmarks = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            bookmarks.add(bookmark(texts[i], i + 1, i + 1));
        }
        return bookmarks;
    }

    @Test
    void allSourcesEmptyReturnsEmptySelection() {
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        Assertions.assertNull(selection.getSource());
        Assertions.assertTrue(selection.getBookmarks().isEmpty());
    }

    @Test
    void nullSourcesAreTreatedAsEmpty() {
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            null, null, null, null);
        Assertions.assertNull(selection.getSource());
        Assertions.assertTrue(selection.getBookmarks().isEmpty());
    }

    @Test
    void emptySourceNeverWinsOverNonEmpty() {
        List<Bookmark> page = flat("第一章", "第二章");
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            new ArrayList<>(), page, new ArrayList<>(), null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_PAGE, selection.getSource());
        Assertions.assertSame(page, selection.getBookmarks());
    }

    @Test
    void comparableQualityKeepsCatalogByDefault() {
        // catalog 与 page 数量接近、无惩罚；page/catalog ≈ 0.86，远低于 2.0 强胜率阈值，catalog 保留
        List<Bookmark> catalog = flat("甲", "乙", "丙", "丁");
        List<Bookmark> page = flat("一", "二", "三");
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            catalog, page, new ArrayList<>(), null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_CATALOG, selection.getSource());
    }

    @Test
    void comparableQualityFallsBackToPageOverSelf() {
        // catalog 被淘汰（高重复+页码乱序），page 与 self 评分接近时按优先级选 page
        List<Bookmark> catalog = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Bookmark dup = bookmark("重复标题", i % 2 == 0 ? i + 2 : i, i + 1);
            catalog.add(dup);
        }
        List<Bookmark> page = flat("一", "二", "三", "四", "五");
        List<Bookmark> self = flat("壹", "贰", "叁", "肆", "伍", "陆");
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            catalog, page, self, null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_PAGE, selection.getSource());
    }

    @Test
    void clearlyBetterScoreWinsOverPriority() {
        // page 条目远多于 catalog 且双方都无惩罚时，分数差距超过阈值，page 胜出
        List<Bookmark> catalog = flat("甲", "乙");
        List<Bookmark> page = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            page.add(bookmark("条目" + (i + 1), i + 1, i + 1));
        }
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            catalog, page, new ArrayList<>(), null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_PAGE, selection.getSource());
    }

    @Test
    void allBadSourcesReturnEmptyBookmarks() {
        // 全部来源 penalty >= 0.5（全部未关联 + 全部重复）→ 空目录
        List<Bookmark> bad1 = new ArrayList<>();
        List<Bookmark> bad2 = new ArrayList<>();
        List<Bookmark> bad3 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bad1.add(bookmark("同一标题", 10 - i, 0));
            bad2.add(bookmark("同一标题", 10 - i, 0));
            bad3.add(bookmark("同一标题", 10 - i, 0));
        }
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            bad1, bad2, bad3, null);
        Assertions.assertNull(selection.getSource());
        Assertions.assertTrue(selection.getBookmarks().isEmpty());
    }

    @Test
    void invalidRelatedIdIsPenalized() {
        Map<Integer, Set<Integer>> pageItemIds = new HashMap<>();
        Set<Integer> ids = new HashSet<>();
        ids.add(1);
        pageItemIds.put(1, ids);

        List<Bookmark> valid = flat("有效");
        BookmarkQualitySelector.QualityMetrics validMetrics =
            BookmarkQualitySelector.evaluate(valid, pageItemIds);
        Assertions.assertEquals(0.0, validMetrics.invalidLinkRatio, 1e-9);

        List<Bookmark> invalid = new ArrayList<>();
        invalid.add(bookmark("无效链接", 1, 999));
        BookmarkQualitySelector.QualityMetrics invalidMetrics =
            BookmarkQualitySelector.evaluate(invalid, pageItemIds);
        Assertions.assertEquals(1.0, invalidMetrics.invalidLinkRatio, 1e-9);
    }

    @Test
    void strangeCharDetection() {
        Assertions.assertFalse(BookmarkQualitySelector.containsStrangeChars("正常标题"));
        Assertions.assertTrue(BookmarkQualitySelector.containsStrangeChars("Ŀ¼"));
        Assertions.assertTrue(BookmarkQualitySelector.containsStrangeChars("标题控制"));
        Assertions.assertFalse(BookmarkQualitySelector.containsStrangeChars("第一章 总则（一）"));
        Assertions.assertFalse(BookmarkQualitySelector.containsStrangeChars("Chapter 1. Introduction"));
    }

    @Test
    void emptyTextCountsAsStrangeAndDuplicateCounts() {
        List<Bookmark> bookmarks = new ArrayList<>();
        bookmarks.add(bookmark("", 1, 1));
        bookmarks.add(bookmark("标题", 2, 2));
        bookmarks.add(bookmark("标 题", 3, 3));
        BookmarkQualitySelector.QualityMetrics metrics =
            BookmarkQualitySelector.evaluate(bookmarks, null);
        Assertions.assertEquals(3, metrics.total);
        Assertions.assertEquals(1, metrics.effectiveCount);
        Assertions.assertTrue(metrics.dupRatio > 0.6);
        Assertions.assertTrue(metrics.strangeRatio > 0.3);
    }

    // ---------- trimOverlongNodes ----------

    @Test
    void trimOverlongNodesRemovesOverlongL1() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            big.append('x');
        }
        Bookmark l1Bad = bookmark(big.toString(), 1, 1);
        Bookmark l1Ok = bookmark("短标题", 2, 2);
        l1Ok.getChildren().add(bookmark("短子", 2, 3));

        List<Bookmark> roots = new ArrayList<>();
        roots.add(l1Bad);
        roots.add(l1Ok);

        BookmarkUtils.trimOverlongNodes(roots);

        Assertions.assertEquals(1, roots.size());
        Assertions.assertEquals("短标题", roots.get(0).getText());
    }

    @Test
    void trimOverlongNodesRemovesAllL2WhenAnyL2Overlong() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            big.append('y');
        }
        Bookmark parent = bookmark("第一章", 1, 1);
        parent.getChildren().add(bookmark("第一节", 1, 2));
        parent.getChildren().add(bookmark(big.toString(), 1, 3));
        parent.getChildren().add(bookmark("第三节", 1, 4));

        List<Bookmark> roots = new ArrayList<>();
        roots.add(parent);

        BookmarkUtils.trimOverlongNodes(roots);

        Assertions.assertEquals(1, roots.size());
        Assertions.assertEquals("第一章", roots.get(0).getText());
        Assertions.assertTrue(roots.get(0).getChildren().isEmpty());
    }

    @Test
    void trimOverlongNodesRemovesAllL3WhenAnyL3Overlong() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            big.append('z');
        }
        Bookmark l1 = bookmark("第一章", 1, 1);
        Bookmark l2 = bookmark("第一节", 1, 2);
        l2.getChildren().add(bookmark("1.1", 1, 3));
        l2.getChildren().add(bookmark(big.toString(), 1, 4));
        l2.getChildren().add(bookmark("1.3", 1, 5));
        l1.getChildren().add(l2);

        List<Bookmark> roots = new ArrayList<>();
        roots.add(l1);

        BookmarkUtils.trimOverlongNodes(roots);

        Assertions.assertEquals(1, roots.size());
        Bookmark keptL1 = roots.get(0);
        Assertions.assertEquals("第一章", keptL1.getText());
        Assertions.assertEquals(1, keptL1.getChildren().size());
        Bookmark keptL2 = keptL1.getChildren().get(0);
        Assertions.assertEquals("第一节", keptL2.getText());
        Assertions.assertTrue(keptL2.getChildren().isEmpty());
    }

    @Test
    void trimOverlongNodesHandlesNullAndEmpty() {
        Assertions.assertNull(BookmarkUtils.trimOverlongNodes(null));
        List<Bookmark> empty = new ArrayList<>();
        Assertions.assertSame(empty, BookmarkUtils.trimOverlongNodes(empty));
    }

    @Test
    void trimOverlongNodesKeepsAllWhenAllShort() {
        Bookmark l1 = bookmark("第一章", 1, 1);
        Bookmark l2 = bookmark("第一节", 1, 2);
        Bookmark l3 = bookmark("1.1", 1, 3);
        l2.getChildren().add(l3);
        l1.getChildren().add(l2);

        List<Bookmark> roots = new ArrayList<>();
        roots.add(l1);

        BookmarkUtils.trimOverlongNodes(roots);

        Assertions.assertEquals(1, roots.size());
        Assertions.assertEquals("第一章", roots.get(0).getText());
        Assertions.assertEquals(1, roots.get(0).getChildren().size());
        Assertions.assertEquals(1, roots.get(0).getChildren().get(0).getChildren().size());
    }

    // ---------- catalog strong-win rule (2.0x) ----------

    @Test
    void catalogWinsByDefaultWhenNonCatalogIsBelow2x() {
        // catalog 3 条 → ln(4) ≈ 1.386；page 10 条 → ln(11) ≈ 2.398；
        // ratio page/catalog ≈ 1.73 < 2.0 → catalog 仍赢
        List<Bookmark> catalog = flat("甲", "乙", "丙");
        List<Bookmark> page = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            page.add(bookmark("条目" + (i + 1), i + 1, i + 1));
        }
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            catalog, page, new ArrayList<>(), null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_CATALOG, selection.getSource());
    }

    @Test
    void nonCatalogWinsWhenAtLeast2xCatalog() {
        // catalog 4 条，page 大量 → page score 远高于 catalog×2 → page 赢
        List<Bookmark> catalog = flat("甲", "乙", "丙", "丁");
        List<Bookmark> page = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            page.add(bookmark("条目" + (i + 1), i + 1, i + 1));
        }
        BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
            catalog, page, new ArrayList<>(), null);
        Assertions.assertEquals(BookmarkQualitySelector.SOURCE_PAGE, selection.getSource());
    }
}
