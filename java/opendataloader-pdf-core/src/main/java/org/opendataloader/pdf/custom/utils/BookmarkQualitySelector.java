package org.opendataloader.pdf.custom.utils;

import org.opendataloader.pdf.custom.entities.Bookmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 在 catalog_bookmarks / page_bookmarks / self_bookmarks 三种目录来源中选出质量最高者。
 *
 * <p>指标（递归统计全部层级）：</p>
 * <ul>
 *   <li>effectiveCount — 去重后的有效条目数（文本去空白判重）</li>
 *   <li>dupRatio — 重复文本率</li>
 *   <li>unlinkedRatio — related_id==0 占比</li>
 *   <li>strangeRatio — 含奇怪字符占比（Unicode 异常 + GBK 误判码模式）</li>
 *   <li>nonMonoRatio — DFS 中序遍历页码非单调占比</li>
 *   <li>invalidLinkRatio — related_id 指向不存在 item 的占比</li>
 * </ul>
 *
 * <p>评分：score = ln(1 + effectiveCount) × (1 − penalty)，penalty 为上述 5 项比率之和（封顶 1）。
 * 空来源最差；penalty ≥ {@link #BAD_PENALTY} 淘汰。当 {@code catalog_bookmarks} 通过淘汰线时，
 * 其它来源必须 score ≥ catalog.score × {@value #CATALOG_STRONG_WIN_RATIO} 才能击败 catalog
 * （强胜率规则）；catalog 缺席时，最高分与次高分相对差 &lt; {@link #COMPARABLE_THRESHOLD}
 * 时按优先级 page &gt; self 决胜。全部淘汰则返回空列表。</p>
 */
public class BookmarkQualitySelector {

    private static final Logger LOGGER = Logger.getLogger(BookmarkQualitySelector.class.getCanonicalName());

    public static final String SOURCE_CATALOG = "catalog_bookmarks";
    public static final String SOURCE_PAGE = "page_bookmarks";
    public static final String SOURCE_SELF = "self_bookmarks";

    /** penalty 达到该值即淘汰。 */
    private static final double BAD_PENALTY = 0.5;
    /** 前两名 score 相对差小于该值视为质量相当，启用优先级。 */
    private static final double COMPARABLE_THRESHOLD = 0.10;
    /** 文本中连续出现该数量的 Latin-1 补充区字符即视为乱码。 */
    private static final int MOJIBAKE_RUN_LENGTH = 2;
    /**
     * 当 {@code catalog_bookmarks} 存在且通过淘汰线时，其它来源的 score
     * 必须至少是 catalog 的 {@code CATALOG_STRONG_WIN_RATIO} 倍才能赢。
     * 1.0 关闭强胜率（沿用旧规则），2.0 要求其它来源 ≥ catalog×2 才获胜。
     */
    static final double CATALOG_STRONG_WIN_RATIO = 2.0;

    private BookmarkQualitySelector() {
    }

    /**
     * 选择结果：来源名 + 目录列表 + 指标。
     */
    public static final class Selection {
        private final String source;
        private final List<Bookmark> bookmarks;
        private final QualityMetrics metrics;

        Selection(String source, List<Bookmark> bookmarks, QualityMetrics metrics) {
            this.source = source;
            this.bookmarks = bookmarks;
            this.metrics = metrics;
        }

        public String getSource() {
            return source;
        }

        public List<Bookmark> getBookmarks() {
            return bookmarks;
        }

        public QualityMetrics getMetrics() {
            return metrics;
        }
    }

    /**
     * 单个来源的质量指标。
     */
    public static final class QualityMetrics {
        int total;
        int effectiveCount;
        double dupRatio;
        double unlinkedRatio;
        double strangeRatio;
        double nonMonoRatio;
        double invalidLinkRatio;
        double penalty;
        double score;

        public boolean isBad() {
            return total == 0 || effectiveCount == 0 || penalty >= BAD_PENALTY;
        }

        @Override
        public String toString() {
            return String.format(
                "total=%d, effective=%d, dup=%.3f, unlinked=%.3f, strange=%.3f, nonMono=%.3f, invalidLink=%.3f, penalty=%.3f, score=%.3f",
                total, effectiveCount, dupRatio, unlinkedRatio, strangeRatio, nonMonoRatio,
                invalidLinkRatio, penalty, score);
        }
    }

    /**
     * 从三个来源中选出质量最高的目录。三者全被淘汰时返回空目录的 Selection（source 为 null）。
     *
     * @param catalogBookmarks catalog 来源（优先级最高）
     * @param pageBookmarks    page 来源（优先级次之）
     * @param selfBookmarks    self 来源（优先级最低）
     * @param pageItemIds      页码(1基) → 该页 item id 集合，用于校验 related_id 有效性；可为 null（跳过校验）
     */
    public static Selection select(List<Bookmark> catalogBookmarks,
                                   List<Bookmark> pageBookmarks,
                                   List<Bookmark> selfBookmarks,
                                   Map<Integer, Set<Integer>> pageItemIds) {
        List<Bookmark> catalog = catalogBookmarks != null ? catalogBookmarks : new ArrayList<>();
        List<Bookmark> page = pageBookmarks != null ? pageBookmarks : new ArrayList<>();
        List<Bookmark> self = selfBookmarks != null ? selfBookmarks : new ArrayList<>();

        // Drop overlong nodes from every source before scoring so the metrics
        // and downstream JSON reflect the cleaned tree. Mirrors the rule that
        // a bookmark entry longer than MAX_TITLE_LENGTH chars is body text,
        // not a real heading.
        BookmarkUtils.trimOverlongNodes(catalog);
        BookmarkUtils.trimOverlongNodes(page);
        BookmarkUtils.trimOverlongNodes(self);

        QualityMetrics catalogMetrics = evaluate(catalog, pageItemIds);
        QualityMetrics pageMetrics = evaluate(page, pageItemIds);
        QualityMetrics selfMetrics = evaluate(self, pageItemIds);

        LOGGER.info(String.format("[BookmarkQualitySelector] catalog: %s", catalogMetrics));
        LOGGER.info(String.format("[BookmarkQualitySelector] page: %s", pageMetrics));
        LOGGER.info(String.format("[BookmarkQualitySelector] self: %s", selfMetrics));

        // 优先级顺序：catalog > page > self
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(SOURCE_CATALOG, catalog, catalogMetrics, 0));
        candidates.add(new Candidate(SOURCE_PAGE, page, pageMetrics, 1));
        candidates.add(new Candidate(SOURCE_SELF, self, selfMetrics, 2));

        List<Candidate> alive = new ArrayList<>();
        List<String> eliminated = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.metrics.isBad()) {
                alive.add(candidate);
            } else {
                eliminated.add(String.format("%s(%s)", candidate.source, badReason(candidate.metrics)));
                LOGGER.info(String.format("[BookmarkQualitySelector] eliminated %s: %s",
                    candidate.source, badReason(candidate.metrics)));
            }
        }
        if (alive.isEmpty()) {
            LOGGER.warning(String.format(
                "[BookmarkQualitySelector] no winner: all sources bad (%s), bookmarks will be empty",
                String.join(", ", eliminated)));
            return new Selection(null, new ArrayList<>(), null);
        }

        // 按 score 降序、同分按优先级升序
        alive.sort((a, b) -> {
            int cmp = Double.compare(b.metrics.score, a.metrics.score);
            return cmp != 0 ? cmp : Integer.compare(a.priority, b.priority);
        });

        Candidate winner;
        String reason;
        Candidate catalogCandidate = findCandidate(alive, SOURCE_CATALOG);
        if (catalogCandidate != null) {
            // catalog 存在并通过淘汰线，应用"强胜率"规则：其它来源必须
            // score >= catalog.score * CATALOG_STRONG_WIN_RATIO 才能击败 catalog。
            Candidate strongestNonCatalog = null;
            for (Candidate c : alive) {
                if (!SOURCE_CATALOG.equals(c.source)) {
                    strongestNonCatalog = c;
                    break;
                }
            }
            if (strongestNonCatalog == null) {
                winner = catalogCandidate;
                reason = String.format("only catalog survives (eliminated: %s)",
                    eliminated.isEmpty() ? "none" : String.join(", ", eliminated));
            } else {
                double catalogScore = catalogCandidate.metrics.score;
                double otherScore = strongestNonCatalog.metrics.score;
                double ratio = otherScore / Math.max(catalogScore, 1e-9);
                if (ratio >= CATALOG_STRONG_WIN_RATIO) {
                    winner = strongestNonCatalog;
                    reason = String.format(
                        "non-catalog %s score %.3f is %.2fx catalog %.3f (>=%.1fx threshold), wins",
                        strongestNonCatalog.source, otherScore, ratio, catalogScore,
                        CATALOG_STRONG_WIN_RATIO);
                } else {
                    winner = catalogCandidate;
                    reason = String.format(
                        "catalog %.3f wins by default (strongest non-catalog %s %.3f, ratio %.2fx < %.1fx threshold)",
                        catalogScore, strongestNonCatalog.source, otherScore, ratio,
                        CATALOG_STRONG_WIN_RATIO);
                }
            }
        } else if (alive.size() == 1) {
            winner = alive.get(0);
            reason = String.format("only surviving source, no catalog (eliminated: %s)",
                eliminated.isEmpty() ? "none" : String.join(", ", eliminated));
        } else {
            // 无 catalog：沿用旧规则——score 相差 <10% 时按 page>self 优先级决胜。
            winner = alive.get(0);
            double best = winner.metrics.score;
            double second = alive.get(1).metrics.score;
            double base = Math.max(Math.abs(best), Math.abs(second));
            boolean comparable = base == 0.0 || (best - second) / base < COMPARABLE_THRESHOLD;
            if (comparable) {
                Candidate byScore = winner;
                for (Candidate candidate : alive) {
                    double diff = base == 0.0 ? 0.0 : (best - candidate.metrics.score) / base;
                    if (diff < COMPARABLE_THRESHOLD && candidate.priority < winner.priority) {
                        winner = candidate;
                    }
                }
                reason = String.format(
                    "no catalog; top scores comparable (best=%.3f[%s], runner-up=%.3f, diff %.1f%% < %.0f%% threshold), "
                        + "priority page>self applied -> %s",
                    best, byScore.source, second,
                    base == 0.0 ? 0.0 : (best - second) / base * 100.0,
                    COMPARABLE_THRESHOLD * 100.0, winner.source);
            } else {
                reason = String.format(
                    "no catalog; highest score %.3f, runner-up %s %.3f (diff %.1f%% >= %.0f%% threshold, clear win)",
                    best, alive.get(1).source, second,
                    (best - second) / base * 100.0, COMPARABLE_THRESHOLD * 100.0);
            }
        }

        LOGGER.info(String.format("[BookmarkQualitySelector] selected %s (score=%.3f): %s",
            winner.source, winner.metrics.score, reason));
        return new Selection(winner.source, winner.bookmarks, winner.metrics);
    }

    private static Candidate findCandidate(List<Candidate> candidates, String source) {
        for (Candidate c : candidates) {
            if (source.equals(c.source)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 来源被淘汰的原因说明。
     */
    private static String badReason(QualityMetrics metrics) {
        if (metrics.total == 0) {
            return "empty source (total=0)";
        }
        if (metrics.effectiveCount == 0) {
            return String.format("no distinct titles (total=%d, effective=0)", metrics.total);
        }
        return String.format(
            "penalty %.3f >= %.1f (dup=%.3f, unlinked=%.3f, strange=%.3f, nonMono=%.3f, invalidLink=%.3f)",
            metrics.penalty, BAD_PENALTY, metrics.dupRatio, metrics.unlinkedRatio,
            metrics.strangeRatio, metrics.nonMonoRatio, metrics.invalidLinkRatio);
    }

    /**
     * 计算单个来源的质量指标。
     */
    public static QualityMetrics evaluate(List<Bookmark> bookmarks, Map<Integer, Set<Integer>> pageItemIds) {
        QualityMetrics metrics = new QualityMetrics();
        if (bookmarks == null || bookmarks.isEmpty()) {
            metrics.score = Double.NEGATIVE_INFINITY;
            return metrics;
        }
        WalkState state = new WalkState(pageItemIds);
        walk(bookmarks, state);
        metrics.total = state.total;
        metrics.effectiveCount = state.distinctTexts.size();
        metrics.dupRatio = 1.0 - (double) metrics.effectiveCount / metrics.total;
        metrics.unlinkedRatio = (double) state.unlinked / metrics.total;
        metrics.strangeRatio = (double) state.strange / metrics.total;
        metrics.nonMonoRatio = (double) state.nonMonotonic / metrics.total;
        metrics.invalidLinkRatio = (double) state.invalidLink / metrics.total;
        metrics.penalty = Math.min(1.0,
            metrics.dupRatio + metrics.unlinkedRatio + metrics.strangeRatio
                + metrics.nonMonoRatio + metrics.invalidLinkRatio);
        metrics.score = Math.log(1.0 + metrics.effectiveCount) * (1.0 - metrics.penalty);
        return metrics;
    }

    private static void walk(List<Bookmark> bookmarks, WalkState state) {
        for (Bookmark bookmark : bookmarks) {
            state.total++;
            String text = bookmark.getText();
            String normalized = text != null ? text.replaceAll("\\s+", "") : "";
            if (normalized.isEmpty()) {
                state.strange++;
            } else {
                state.distinctTexts.add(normalized);
                if (containsStrangeChars(text)) {
                    state.strange++;
                }
            }
            Integer relatedId = bookmark.getRelatedId();
            if (relatedId == null || relatedId == 0) {
                state.unlinked++;
            } else if (state.pageItemIds != null) {
                Integer pageNum = bookmark.getPageNum();
                Set<Integer> ids = pageNum != null ? state.pageItemIds.get(pageNum) : null;
                if (ids == null || !ids.contains(relatedId)) {
                    state.invalidLink++;
                }
            }
            int pageNum = bookmark.getPageNum() != null ? bookmark.getPageNum() : 0;
            if (pageNum < state.lastPageNum) {
                state.nonMonotonic++;
            } else {
                state.lastPageNum = pageNum;
            }
            List<Bookmark> children = bookmark.getChildren();
            if (children != null && !children.isEmpty()) {
                walk(children, state);
            }
        }
    }

    /**
     * 奇怪字符判定：Unicode 异常（替换符、控制字符、私用区、未分配、孤立代理对）
     * 或乱码模式（连续 {@link #MOJIBAKE_RUN_LENGTH} 个 Latin-1 补充区/Latin 扩展区字符
     * U+0080–U+024F，典型 GBK 误判码，如 "Ŀ¼"）。
     */
    static boolean containsStrangeChars(String text) {
        int latin1Run = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int type = Character.getType(ch);
            if (ch == '\uFFFD'
                || type == Character.CONTROL
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || type == Character.SURROGATE) {
                return true;
            }
            if (ch >= '\u0080' && ch <= '\u024F') {
                latin1Run++;
                if (latin1Run >= MOJIBAKE_RUN_LENGTH) {
                    return true;
                }
            } else {
                latin1Run = 0;
            }
        }
        return false;
    }

    /**
     * 从 JSON data 构建页码(1基) → item id 集合的映射。
     */
    public static Map<Integer, Set<Integer>> buildPageItemIds(List<Map<String, Object>> data) {
        Map<Integer, Set<Integer>> pageItemIds = new HashMap<>();
        if (data == null) {
            return pageItemIds;
        }
        for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
            Map<String, Object> page = data.get(pageIndex);
            Object itemsObj = page != null ? page.get("items") : null;
            Set<Integer> ids = new HashSet<>();
            if (itemsObj instanceof List) {
                for (Object itemObj : (List<?>) itemsObj) {
                    if (itemObj instanceof Map) {
                        Object id = ((Map<?, ?>) itemObj).get("id");
                        if (id instanceof Number) {
                            ids.add(((Number) id).intValue());
                        }
                    }
                }
            }
            pageItemIds.put(pageIndex + 1, ids);
        }
        return pageItemIds;
    }

    private static final class Candidate {
        final String source;
        final List<Bookmark> bookmarks;
        final QualityMetrics metrics;
        final int priority;

        Candidate(String source, List<Bookmark> bookmarks, QualityMetrics metrics, int priority) {
            this.source = source;
            this.bookmarks = bookmarks;
            this.metrics = metrics;
            this.priority = priority;
        }
    }

    private static final class WalkState {
        final Map<Integer, Set<Integer>> pageItemIds;
        final Set<String> distinctTexts = new HashSet<>();
        int total;
        int unlinked;
        int strange;
        int nonMonotonic;
        int invalidLink;
        int lastPageNum;

        WalkState(Map<Integer, Set<Integer>> pageItemIds) {
            this.pageItemIds = pageItemIds;
        }
    }
}
