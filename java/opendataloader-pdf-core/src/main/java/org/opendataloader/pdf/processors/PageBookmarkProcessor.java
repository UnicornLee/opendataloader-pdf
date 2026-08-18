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

import org.opendataloader.pdf.custom.constants.BookmarkConstant;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.json.JsonName;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.utils.SmartTextJoiner;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.content.TextLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Extracts page bookmarks from {@link CustomSemanticParagraph} contents.
 *
 * <p>A candidate paragraph must start with one of the public constants defined in
 * {@link BookmarkConstant}. Candidates are grouped by their structural template
 * <strong>and</strong> number system (Arabic vs. Chinese); e.g. {@code 第1章} and
 * {@code 第一章} belong to two different groups and will never be merged into the
 * same bookmark level.</p>
 *
 * <p>Hierarchy follows a local-consistency rule: all bookmarks directly under the
 * same parent share the same template (prefix/numbering style). Top-level
 * bookmarks share one template; within each parent, its children share one
 * template; children templates may differ between sibling parents, depending on
 * the candidates extracted between consecutive parents. Up to three levels are
 * emitted.</p>
 *
 * <p>Within each parent range, the next level is selected by visual hierarchy
 * (larger font / smaller left indentation = higher level) and consecutive-number
 * validation. For the first level only, a right-aligned chapter-like group
 * (第*章/第*节/第*条) may be promoted to top level when several such groups
 * coexist.</p>
 */
public class PageBookmarkProcessor {

    private static final Logger LOGGER = Logger.getLogger(PageBookmarkProcessor.class.getCanonicalName());

    private static final String TEMPLATE_NUMBER = "#";
    private static final String TEMPLATE_CHAPTER = "第#章";
    private static final String TEMPLATE_SECTION = "第#节";
    private static final String TEMPLATE_ARTICLE = "第#条";
    private static final String TEMPLATE_PAREN = "（#）";
    private static final String TEMPLATE_ASCII_PAREN = "(#)";
    private static final String TEMPLATE_CLOSE_PAREN = "#）";
    private static final String TEMPLATE_CLOSE_ASCII_PAREN = "#)";
    private static final String TEMPLATE_CHINESE_COMMA = "、";
    private static final String TEMPLATE_CHINESE_COMMA_CANONICAL = "#\u3001";
    private static final char CHINESE_COMMA_FULL_WIDTH = '\u3001';

    private static final char FULL_WIDTH_DOT = '．';

    /**
     * Maximum allowed length (in Java chars) of a bookmark entry's full text.
     * Real headings are short; an entry longer than this is treated as body
     * text that merely starts with a numbering prefix (e.g. a long numbered
     * paragraph) and is excluded as a table-of-contents residue.
     */
    private static final int MAX_ENTRY_TEXT_LENGTH = 200;

    /**
     * Filter threshold for the period-end consistency rule applied to L2/L3
     * candidates that share the same prefix template: keep the majority side
     * only when it holds strictly more than this fraction of candidates.
     */
    private static final double PERIOD_FILTER_RATIO = 0.8;

    /**
     * Minimum candidate count required to apply the period-end filter. Small
     * samples are skipped to avoid unstable 80/20 splits over too few items.
     */
    private static final int MIN_CANDIDATES_FOR_PERIOD_FILTER = 3;

    /** Chinese full-stop / period "\u3002". */
    private static final char CHINESE_PERIOD = '。';

    private enum NumberSystem {
        ARABIC, CHINESE
    }

    private static final class ConstantPattern {
        final String constant;
        final String template;
        final NumberSystem numberSystem;
        final int value;

        ConstantPattern(String constant, String template, NumberSystem numberSystem, int value) {
            this.constant = constant;
            this.template = template;
            this.numberSystem = numberSystem;
            this.value = value;
        }
    }

    private static final List<ConstantPattern> PATTERNS = buildPatterns();

    private static List<ConstantPattern> buildPatterns() {
        List<ConstantPattern> patterns = new ArrayList<>();
        for (int i = 0; i < BookmarkConstant.NUMBERS_1_TO_100.size(); i++) {
            patterns.add(new ConstantPattern(String.valueOf(BookmarkConstant.NUMBERS_1_TO_100.get(i)),
                TEMPLATE_NUMBER, NumberSystem.ARABIC, i + 1));
        }
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_1_TO_100, TEMPLATE_NUMBER, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_WITH_COMMA_1_TO_100, TEMPLATE_CHINESE_COMMA_CANONICAL, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_CHAPTERS_1_TO_100, TEMPLATE_CHAPTER, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_CHAPTERS_1_TO_100, TEMPLATE_CHAPTER, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_SECTIONS_1_TO_100, TEMPLATE_SECTION, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_SECTIONS_1_TO_100, TEMPLATE_SECTION, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_ARTICLES_1_TO_100, TEMPLATE_ARTICLE, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_ARTICLES_1_TO_100, TEMPLATE_ARTICLE, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_IN_PARENS_1_TO_100, TEMPLATE_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_IN_PARENS_1_TO_100, TEMPLATE_PAREN, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_IN_ASCII_PARENS_1_TO_100, TEMPLATE_ASCII_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_IN_ASCII_PARENS_1_TO_100, TEMPLATE_ASCII_PAREN, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_WITH_CLOSE_PAREN_1_TO_100, TEMPLATE_CLOSE_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.NUMBERS_WITH_CLOSE_ASCII_PAREN_1_TO_100, TEMPLATE_CLOSE_ASCII_PAREN, NumberSystem.ARABIC);
        // Longest first so that "10" is preferred over "1" and "第10章" over "第1章".
        patterns.sort(Comparator.comparingInt((ConstantPattern p) -> p.constant.length()).reversed());
        return patterns;
    }

    private static void addPatternList(List<ConstantPattern> patterns, List<String> constants,
                                       String template, NumberSystem numberSystem) {
        for (int i = 0; i < constants.size(); i++) {
            patterns.add(new ConstantPattern(constants.get(i), template, numberSystem, i + 1));
        }
    }

    private static final class TemplateKey {
        final String template;
        final NumberSystem numberSystem;

        TemplateKey(String template, NumberSystem numberSystem) {
            this.template = template;
            this.numberSystem = numberSystem;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TemplateKey)) return false;
            TemplateKey that = (TemplateKey) o;
            return Objects.equals(template, that.template) && numberSystem == that.numberSystem;
        }

        @Override
        public int hashCode() {
            return Objects.hash(template, numberSystem);
        }
    }

    private static final class Candidate {
        final int pageIndex;
        final String text;
        final String fullText;
        final boolean singleLine;
        final TemplateKey templateKey;
        final int value;
        final double fontSize;
        final double leftX;
        final double topY;
        final int relatedId;
        final int pageLastId;
        final boolean pageIdOneIsText;

        Candidate(int pageIndex, String text, String fullText, boolean singleLine,
                  TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY) {
            this(pageIndex, text, fullText, singleLine, templateKey, value,
                fontSize, leftX, topY, 0, 0, false);
        }

        Candidate(int pageIndex, String text, String fullText, boolean singleLine,
                  TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY, int relatedId) {
            this(pageIndex, text, fullText, singleLine, templateKey, value,
                fontSize, leftX, topY, relatedId, 0, false);
        }

        Candidate(int pageIndex, String text, String fullText, boolean singleLine,
                  TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY, int relatedId,
                  int pageLastId, boolean pageIdOneIsText) {
            this.pageIndex = pageIndex;
            this.text = text;
            this.fullText = fullText;
            this.singleLine = singleLine;
            this.templateKey = templateKey;
            this.value = value;
            this.fontSize = fontSize;
            this.leftX = leftX;
            this.topY = topY;
            this.relatedId = relatedId;
            this.pageLastId = pageLastId;
            this.pageIdOneIsText = pageIdOneIsText;
        }
    }

    private static final class Group {
        final TemplateKey templateKey;
        final List<Candidate> candidates = new ArrayList<>();
        double averageFontSize;
        double averageLeftX;
        int firstPageIndex;
        double firstTopY;
        // Number of unique pages occupied by the (cleaned) candidates. Drives
        // the density signal in selectTemplateForLevel so a sparse "one per
        // section" L2 candidate set beats a dense "many per page" L3 set even
        // when the L3 set has a larger absolute count.
        int pageSpan;

        Group(TemplateKey templateKey) {
            this.templateKey = templateKey;
        }

        void add(Candidate candidate) {
            candidates.add(candidate);
        }

        void computeStatistics() {
            averageFontSize = candidates.stream().mapToDouble(c -> c.fontSize).average().orElse(0.0);
            averageLeftX = candidates.stream().mapToDouble(c -> c.leftX).average().orElse(0.0);
            firstPageIndex = candidates.stream().mapToInt(c -> c.pageIndex).min().orElse(0);
            firstTopY = candidates.stream()
                .filter(c -> c.pageIndex == firstPageIndex)
                .mapToDouble(c -> c.topY)
                .max().orElse(0.0);
            Set<Integer> uniquePages = new HashSet<>();
            for (Candidate c : candidates) {
                uniquePages.add(c.pageIndex);
            }
            pageSpan = uniquePages.size();
        }
    }

    /**
     * Extracts a hierarchical bookmark tree from the given per-page contents.
     *
     * @param contents per-page document contents produced by the pipeline
     * @return list of top-level bookmarks, possibly empty
     */
    public static List<Bookmark> extractPageBookmarks(List<List<IObject>> contents) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }

        return buildBookmarksFromCandidates(collectCandidates(contents));
    }

    /**
     * Extracts a hierarchical bookmark tree from the JSON data produced by
     * {@link org.opendataloader.pdf.json.JsonWriter}. Catalog pages are skipped.
     *
     * @param data per-page JSON data array
     * @param catalogStartPage 0-based inclusive start of catalog page range, or -1
     * @param catalogEndPage 0-based inclusive end of catalog page range, or -1
     * @return list of top-level page bookmarks, each carrying the source JSON item id
     *         as {@code relatedId}
     */
    public static List<Bookmark> extractPageBookmarksFromJson(List<Map<String, Object>> data,
                                                              int catalogStartPage,
                                                              int catalogEndPage) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        return buildBookmarksFromCandidates(collectJsonCandidates(data, catalogStartPage, catalogEndPage));
    }

    /**
     * Builds the immediate children of an anchor bookmark emitted at the given
     * depth, reusing the exact candidate collection, cleaning and
     * level-selection pipeline of {@link #extractPageBookmarksFromJson}.
     *
     * <p>The anchor is resolved by its exact {@code page_num}/{@code related_id}
     * against the freshly collected candidate set. The child range starts right
     * after the anchor candidate and ends before the next same-depth sibling of
     * the anchor within its ancestor range — exactly the range the full-document
     * path would slice for that anchor — and the ancestor templates are
     * recomputed the same way. Children are therefore emitted at {@code level}
     * (2 for an L1 anchor, 3 for an L2 anchor) with the ancestor templates
     * excluded, so the result matches node-for-node what the page bookmark tree
     * contains below that anchor and the tree never grows beyond three levels.
     * The returned bookmarks are freshly built objects (never shared with any
     * previously built page tree); catalog page ranges are skipped during
     * collection exactly like the full-document path.</p>
     *
     * @param data per-page JSON data array
     * @param catalogStartPage 0-based inclusive start of catalog page range, or -1
     * @param catalogEndPage 0-based inclusive end of catalog page range, or -1
     * @param anchorPage 1-based page_num of the anchor bookmark
     * @param anchorRelatedId related_id of the anchor bookmark
     * @param level depth at which the children are emitted (2 or 3)
     * @return freshly built child bookmarks of the anchor, possibly empty
     */
    public static List<Bookmark> extractChildrenForAnchor(
            List<Map<String, Object>> data,
            int catalogStartPage, int catalogEndPage,
            int anchorPage, int anchorRelatedId,
            int level) {
        if (data == null || data.isEmpty() || level < 2 || level > 3) {
            return Collections.emptyList();
        }
        List<Candidate> all = collectJsonCandidates(data, catalogStartPage, catalogEndPage);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        all.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .thenComparing((Candidate c) -> -c.topY));

        int anchorIndex = indexOfCandidate(all, anchorPage, anchorRelatedId);
        if (anchorIndex < 0) {
            return Collections.emptyList();
        }

        // Recompute the ancestor templates exactly as the full-document path
        // does so deeper-level template selection matches it.
        TemplateKey levelOneTemplate = selectTemplateForLevel(
            all, 0, all.size() - 1, 1, Collections.emptySet());
        if (levelOneTemplate == null) {
            return Collections.emptyList();
        }
        Set<TemplateKey> usedTemplates = new HashSet<>();
        usedTemplates.add(levelOneTemplate);

        int childEnd;
        if (level == 2) {
            // Children of an L1 anchor: the range ends before the next L1 sibling.
            List<Integer> levelOneIndices = cleanedIndicesOf(
                all, 0, all.size() - 1, levelOneTemplate, 1);
            childEnd = nextIndexAfter(levelOneIndices, anchorIndex, all.size() - 1);
        } else {
            // Children of an L2 anchor: the range is bounded by the parent L1
            // range and the next L2 sibling inside it.
            List<Integer> levelOneIndices = cleanedIndicesOf(
                all, 0, all.size() - 1, levelOneTemplate, 1);
            int parentLevelOneIndex = lastIndexBefore(levelOneIndices, anchorIndex);
            if (parentLevelOneIndex < 0) {
                return Collections.emptyList();
            }
            int levelOneEnd = nextIndexAfter(levelOneIndices, parentLevelOneIndex, all.size() - 1);
            TemplateKey levelTwoTemplate = selectTemplateForLevel(
                all, parentLevelOneIndex + 1, levelOneEnd, 2, usedTemplates);
            if (levelTwoTemplate == null) {
                return Collections.emptyList();
            }
            // Same rationale as extractLevel: do NOT push levelTwoTemplate
            // into usedTemplates. L3 may legitimately reuse the L2 template
            // (e.g. nested "一、" items under an "一、" L2 anchor) and the
            // parent anchor is already excluded from the child range by
            // index slicing. extractLevel no longer propagates level-N
            // templates either, so both entry points stay consistent.
            List<Integer> levelTwoIndices = cleanedIndicesOf(
                all, parentLevelOneIndex + 1, levelOneEnd, levelTwoTemplate, 2);
            childEnd = nextIndexAfter(levelTwoIndices, anchorIndex, levelOneEnd);
        }

        return extractLevel(all, anchorIndex + 1, childEnd, level, usedTemplates);
    }

    /**
     * Returns the index of the candidate matching the exact (page, relatedId)
     * pair, or -1 if no such candidate exists.
     */
    private static int indexOfCandidate(List<Candidate> candidates, int page, int relatedId) {
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c.pageIndex + 1 == page && c.relatedId == relatedId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Collects, cleans and returns, in reading order, the original indices of
     * the candidates matching the given template within [start, end]. This
     * mirrors the bookkeeping done inside {@link #extractLevel} so that child
     * ranges are sliced identically to the full-document path.
     */
    private static List<Integer> cleanedIndicesOf(List<Candidate> candidates, int start, int end,
                                                   TemplateKey template, int level) {
        List<Candidate> selected = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            Candidate c = candidates.get(i);
            if (template.equals(c.templateKey)) {
                selected.add(c);
            }
        }
        List<Candidate> cleaned = (level == 1)
            ? cleanCandidates(selected)
            : cleanCandidatesLocal(selected, level);
        List<Integer> indices = new ArrayList<>();
        for (Candidate c : cleaned) {
            int idx = candidates.indexOf(c);
            indices.add(idx);
        }
        indices.sort(Comparator
            .comparingInt((Integer i) -> candidates.get(i).pageIndex)
            .thenComparing((Integer i) -> -candidates.get(i).topY));
        return indices;
    }

    /**
     * Returns the largest index in {@code indices} that is strictly less than
     * {@code index}, or -1 if none exists.
     */
    private static int lastIndexBefore(List<Integer> indices, int index) {
        for (int i = indices.size() - 1; i >= 0; i--) {
            if (indices.get(i) < index) {
                return indices.get(i);
            }
        }
        return -1;
    }

    /**
     * Returns one less than the smallest index in {@code indices} that is
     * strictly greater than {@code index} (an inclusive child range end), or
     * {@code fallback} if no such index exists.
     */
    private static int nextIndexAfter(List<Integer> indices, int index, int fallback) {
        for (int i = 0; i < indices.size(); i++) {
            if (indices.get(i) > index) {
                return indices.get(i) - 1;
            }
        }
        return fallback;
    }

    /**
     * Builds a hierarchical bookmark tree from already-collected candidates.
     *
     * <p>The tree is built recursively: for each parent range, the best template
     * (prefix/numbering style) is selected and used as the only child marker for
     * that parent. Children templates may differ between sibling parents, but
     * all children under the same parent share the same template. Up to three
     * levels are emitted.</p>
     */
    private static List<Bookmark> buildBookmarksFromCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort in reading order before recursing so that ranges are always sliced
        // by consecutive parent occurrences in document order.
        candidates.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .thenComparing((Candidate c) -> -c.topY));

        return extractLevel(candidates, 0, candidates.size() - 1, 1, Collections.emptySet());
    }

    /**
     * Recursively extracts bookmarks of the given level within the candidate
     * index range [start, end]. Templates listed in {@code usedTemplates} have
     * already been assigned to ancestor levels and are ignored.
     */
    private static List<Bookmark> extractLevel(List<Candidate> candidates, int start, int end,
                                                int level, Set<TemplateKey> usedTemplates) {
        if (start > end || level > 3) {
            return Collections.emptyList();
        }

        TemplateKey selectedTemplate = selectTemplateForLevel(candidates, start, end, level, usedTemplates);
        if (selectedTemplate == null) {
            return Collections.emptyList();
        }

        List<Candidate> selectedCandidates = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            Candidate c = candidates.get(i);
            if (selectedTemplate.equals(c.templateKey)) {
                selectedCandidates.add(c);
            }
        }

        List<Candidate> cleaned = (level == 1)
            ? cleanCandidates(selectedCandidates)
            : cleanCandidatesLocal(selectedCandidates, level);
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        // Map cleaned candidates back to their original indices and order them by
        // reading order so that child ranges are sliced correctly.
        List<Integer> cleanedIndices = new ArrayList<>();
        for (Candidate c : cleaned) {
            int idx = candidates.indexOf(c);
            cleanedIndices.add(idx);
        }
        cleanedIndices.sort(Comparator
            .comparingInt((Integer i) -> candidates.get(i).pageIndex)
            .thenComparing((Integer i) -> -candidates.get(i).topY));

        List<Bookmark> bookmarks = new ArrayList<>();
        // Only level-1's selected template is propagated to deeper levels. L1
        // selects across the whole document, so re-selecting it inside a
        // descendant range would recreate the L1 anchors as L2/L3 nodes. L2/L3
        // selections are range-bounded and the parent anchor is already
        // excluded from the child range by index slicing, so a template that
        // happens to also appear in the child range (e.g. nested "一、" items
        // under an "一、" L2 anchor) is a legitimate L3 candidate and must
        // not be filtered out here.
        Set<TemplateKey> newUsed = new HashSet<>(usedTemplates);
        if (level == 1) {
            newUsed.add(selectedTemplate);
        }

        for (int i = 0; i < cleanedIndices.size(); i++) {
            int idx = cleanedIndices.get(i);
            Candidate candidate = candidates.get(idx);
            int childStart = idx + 1;
            int childEnd = (i + 1 < cleanedIndices.size()) ? cleanedIndices.get(i + 1) - 1 : end;
            List<Bookmark> children = extractLevel(candidates, childStart, childEnd, level + 1, newUsed);
            Bookmark bookmark = createBookmark(candidate);
            bookmark.getChildren().addAll(children);
            bookmarks.add(bookmark);
        }

        return bookmarks;
    }

    /**
     * Selects the best template for the given level within the range
     * [start, end]. The best template is the one with the highest visual
     * hierarchy among templates that form a valid sequence.
     *
     * <p>For level 1, the template must form a contiguous sequence starting at
     * 1 across the whole document. For deeper levels, only a contiguous run
     * within the current parent range is required; it may start at any value,
     * which allows headings to continue their numbering across parent
     * sections.</p>
     */
    private static TemplateKey selectTemplateForLevel(List<Candidate> candidates, int start, int end,
                                                       int level, Set<TemplateKey> usedTemplates) {
        Map<TemplateKey, Group> groups = new HashMap<>();
        for (int i = start; i <= end; i++) {
            Candidate c = candidates.get(i);
            if (usedTemplates.contains(c.templateKey)) {
                continue;
            }
            groups.computeIfAbsent(c.templateKey, Group::new).add(c);
        }

        List<Group> validGroups = new ArrayList<>();
        for (Group group : groups.values()) {
            List<Candidate> cleaned;
            if (level == 1) {
                // Level 1: require a global consecutive-from-1 sequence.
                group.computeStatistics();
                if (!isValidGroup(group)) {
                    continue;
                }
                cleaned = cleanCandidates(group.candidates);
            } else {
                // Deeper levels: allow a local consecutive run starting anywhere.
                cleaned = cleanCandidatesLocal(group.candidates, level);
                if (cleaned.isEmpty()) {
                    continue;
                }
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            group.candidates.clear();
            group.candidates.addAll(cleaned);
            group.computeStatistics();
            validGroups.add(group);
        }

        if (validGroups.isEmpty()) {
            return null;
        }

        validGroups.sort((a, b) -> {
            int fontCmp = Double.compare(b.averageFontSize, a.averageFontSize);
            if (fontCmp != 0) {
                return fontCmp;
            }
            // Density-before-count is applied only at L2: a template whose
            // cleaned candidates are spread across many pages (one entry per
            // section, ~1 per page) is a more plausible L2 chapter sub-heading
            // than a template that packs many entries on each page (a dense
            // L3+ body pattern such as "(一)、(二)、(三)、..." appearing 3-5
            // times per page). Skipping this at L3+ preserves the existing
            // "prefer the wider run" behavior, because at L3 the sparser
            // template may simply be a short local run that should yield to a
            // longer one on the same page (see
            // testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange).
            if (level == 2) {
                double densityA = a.pageSpan > 0
                    ? (double) a.candidates.size() / a.pageSpan
                    : Double.POSITIVE_INFINITY;
                double densityB = b.pageSpan > 0
                    ? (double) b.candidates.size() / b.pageSpan
                    : Double.POSITIVE_INFINITY;
                int densityCmp = Double.compare(densityA, densityB);
                if (densityCmp != 0) {
                    return densityCmp;
                }
            }
            // Count-before-indent: a template that survives cleaning with more
            // entries is more likely to be the real chapter spine than a
            // template with fewer entries that happens to be slightly less
            // indented (e.g. body-text paragraphs starting with "1、" that
            // share the body text margin).
            int countCmp = Integer.compare(b.candidates.size(), a.candidates.size());
            if (countCmp != 0) {
                return countCmp;
            }
            int indentCmp = Double.compare(a.averageLeftX, b.averageLeftX);
            if (indentCmp != 0) {
                return indentCmp;
            }
            int pageCmp = Integer.compare(a.firstPageIndex, b.firstPageIndex);
            if (pageCmp != 0) {
                return pageCmp;
            }
            return Double.compare(b.firstTopY, a.firstTopY);
        });

        return validGroups.get(0).templateKey;
    }

    /**
     * Cleans a list of candidates of the same template by splitting at each
     * value-1 restart and trimming each run to a contiguous "from-1" sequence.
     * Duplicate values are resolved by keeping the latest occurrence.
     *
     * <p>After trimming, each section is checked for the overlong-entry rule
     * shared with {@link #isTocLikeGroup}: a section containing any entry whose
     * full text exceeds {@value #MAX_ENTRY_TEXT_LENGTH} characters is treated
     * as a numbered body paragraph (not a heading) and discarded. This stops
     * long numbered paragraphs ("1、品牌营销服务网络拓展项目：受外部环境...")
     * from masquerading as level-1 bookmarks.</p>
     *
     * <p>The surviving section is also routed through {@link #isTocLikeGroup}
     * so the same TOC-residue rules that gate L2/L3 (same-page adjacent
     * {@code relatedId} runs, cross-page bridges) gate L1 too. Without this,
     * a body section such as {@code (1)..(N)} audit procedures can defeat the
     * real {@code 一、..十五、} chapter headings at L1 simply because its font
     * happens to be larger than the financial-appendix heading font.</p>
     *
     * <p>Among the surviving sections only the largest contiguous run is kept,
     * which removes duplicate value-restart groups (e.g. two interleaved
     * {@code 一/二/...} sequences from a TOC page and a body page).</p>
     */
    private static List<Candidate> cleanCandidates(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .thenComparing((Candidate c) -> -c.topY));
        List<List<Candidate>> sections = splitByValueOne(sorted);

        List<Candidate> bestSection = null;
        int bestLength = 0;
        int bestStartPage = Integer.MAX_VALUE;

        for (List<Candidate> section : sections) {
            List<Candidate> trimmedSection = trimContiguousSection(section);
            if (trimmedSection == null || trimmedSection.isEmpty()) {
                continue;
            }
            if (hasOverlongEntry(trimmedSection)) {
                continue;
            }
            // Mirror L2/L3 TOC-residue gating at L1: drop sections whose trimmed
            // candidates look like a table-of-contents residue (consecutive
            // relatedIds on the same page, or a long cross-page bridge). Body
            // paragraph sequences (e.g. "(1)..(7)" audit procedures) frequently
            // satisfy these properties and would otherwise outrank a real
            // chapter spine when the body font happens to be larger than the
            // appendix heading font.
            if (isTocLikeGroup(trimmedSection)) {
                continue;
            }
            int length = trimmedSection.size();
            int startPage = trimmedSection.stream()
                .mapToInt(c -> c.pageIndex)
                .min()
                .orElse(Integer.MAX_VALUE);
            if (length > bestLength
                || (length == bestLength && startPage < bestStartPage)) {
                bestLength = length;
                bestStartPage = startPage;
                bestSection = trimmedSection;
            }
        }

        return bestSection != null ? bestSection : Collections.emptyList();
    }

    /**
     * Returns true when any candidate in {@code section} has a full text that
     * exceeds {@value #MAX_ENTRY_TEXT_LENGTH} characters. Mirrors Rule 1 of
     * {@link #isTocLikeGroup} so it can be reused by the level-1 path without
     * pulling in the adjacency-based rules, which would over-drop legitimate
     * Chinese-style level-1 sequences.
     */
    private static boolean hasOverlongEntry(List<Candidate> section) {
        for (Candidate c : section) {
            if (c.fullText != null && c.fullText.length() > MAX_ENTRY_TEXT_LENGTH) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a single-template candidate group (e.g. all "一、.." or all "(一)..."),
     * drop the minority side based on whether {@code c.fullText} ends with
     * the Chinese full-stop {@link #CHINESE_PERIOD}.
     *
     * <p>Uses {@code fullText} (the joined paragraph) rather than {@code text}
     * (the first line) because body paragraphs split across multiple text
     * chunks keep their trailing "。" on the last line; checking only the
     * first line would miss it and let the residue leak into the candidate
     * set.</p>
     *
     * <p>Skips the filter when neither side strictly exceeds
     * {@link #PERIOD_FILTER_RATIO}, or when filtering would leave the group
     * empty (defensive fallback to the original list). The caller already
     * gates on {@link #MIN_CANDIDATES_FOR_PERIOD_FILTER} so this method can
     * assume {@code candidates.size() >= 3}.</p>
     */
    private static List<Candidate> applyPeriodEndFilter(List<Candidate> candidates) {
        int total = candidates.size();
        int endsWithPeriod = 0;
        // Check c.fullText (joined paragraph text), NOT c.text (first line only):
        // body paragraphs split across multiple text chunks (e.g. a long "三、（十
        // 四）和（十八）..." spread over two lines) keep the period on the last
        // line; c.text only sees the first line and would miss the trailing "。".
        for (Candidate c : candidates) {
            String fullText = c.fullText;
            if (fullText != null && !fullText.isEmpty()
                    && fullText.charAt(fullText.length() - 1) == CHINESE_PERIOD) {
                endsWithPeriod++;
            }
        }
        double periodRatio = (double) endsWithPeriod / total;

        List<Candidate> filtered;
        if (periodRatio > PERIOD_FILTER_RATIO) {
            // >80% end with period -> drop the minority that do NOT.
            filtered = new ArrayList<>();
            for (Candidate c : candidates) {
                String fullText = c.fullText;
                if (fullText != null && !fullText.isEmpty()
                        && fullText.charAt(fullText.length() - 1) == CHINESE_PERIOD) {
                    filtered.add(c);
                }
            }
        } else if ((total - endsWithPeriod) > total * PERIOD_FILTER_RATIO) {
            // >80% do NOT end with period -> drop the minority that DO.
            filtered = new ArrayList<>();
            for (Candidate c : candidates) {
                String fullText = c.fullText;
                if (fullText == null || fullText.isEmpty()
                        || fullText.charAt(fullText.length() - 1) != CHINESE_PERIOD) {
                    filtered.add(c);
                }
            }
        } else {
            return candidates; // no clear majority -> keep original
        }
        return filtered.isEmpty() ? candidates : filtered;
    }

    /**
     * Cleans a list of candidates of the same template within a parent range
     * by grouping them into maximal runs of consecutive values, chaining runs
     * whose value ranges abut (previous.max + 1 == next.min), and selecting
     * the chain whose value range is the widest. On ties, the chain whose
     * first candidate is on the earliest page wins (closest to the parent).
     *
     * <p>Unlike {@link #cleanCandidates}, the run may start at any value,
     * allowing headings to continue their numbering across parent sections.</p>
     *
     * @param level depth at which the cleaning runs (1=L1, 2=L2, 3=L3);
     *              only L2+ applies the period-end consistency filter.
     */
    private static List<Candidate> cleanCandidatesLocal(List<Candidate> candidates, int level) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 1: Sort candidates by reading order.
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .thenComparing((Candidate c) -> -c.topY));

        // Step 1.5: Period-end consistency filter for L2+.
        //
        // When the same prefix template carries many candidates (e.g. all
        // "一、.." or all "(一)..."), they tend to end consistently: either
        // with a trailing "。" or without one. A small minority of body
        // paragraphs that mimic the prefix (e.g. "（十四）和（十八）...") can
        // leak into the candidate set; they usually break the period-end
        // pattern. Filter the minority when one side strictly exceeds the
        // 80% threshold, and only when the candidate count is large enough
        // to make the ratio meaningful.
        if (level >= 2 && sorted.size() >= MIN_CANDIDATES_FOR_PERIOD_FILTER) {
            sorted = applyPeriodEndFilter(sorted);
        }

        // Step 2: Group into consecutive runs. Each run is a maximal sequence
        // where every candidate's value is +1 from the previous one. A new run
        // starts when the next value is not consecutive to the current run's
        // last value. This preserves the "set" structure: two disjoint sets of
        // values stay in separate runs even if their values overlap, so the
        // selector below can distinguish them instead of blindly merging.
        List<List<Candidate>> groups = new ArrayList<>();
        List<Candidate> currentGroup = new ArrayList<>();
        int previousValue = 0;
        boolean currentGroupInitialized = false;
        for (Candidate c : sorted) {
            if (currentGroupInitialized && c.value != previousValue + 1) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(c);
            previousValue = c.value;
            currentGroupInitialized = true;
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        if (groups.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 3: Sort groups by start value; ties broken by reading order of
        // the first candidate so groups closer to the parent rank first.
        groups.sort((a, b) -> {
            int cmp = Integer.compare(a.get(0).value, b.get(0).value);
            if (cmp != 0) return cmp;
            return Integer.compare(a.get(0).pageIndex, b.get(0).pageIndex);
        });

        // Step 4: Build chains. A group extends the previous chain when its
        // first value is exactly previous_chain.last + 1. This handles the case
        // where a stray value sits between two value-contiguous runs in
        // reading order: the contiguous runs still merge into one chain while
        // the stray forms its own chain (or stays separate).
        List<List<List<Candidate>>> chains = new ArrayList<>();
        List<List<Candidate>> currentChain = null;
        int currentChainMax = 0;
        boolean currentChainInitialized = false;
        for (List<Candidate> group : groups) {
            int groupMin = group.get(0).value;
            int groupMax = group.get(group.size() - 1).value;
            if (currentChainInitialized && currentChainMax + 1 == groupMin) {
                currentChain.add(group);
                currentChainMax = groupMax;
            } else {
                currentChain = new ArrayList<>();
                currentChain.add(group);
                currentChainMax = groupMax;
                currentChainInitialized = true;
                chains.add(currentChain);
            }
        }

        // Step 4.5: Discard chains that look like table-of-contents residue.
        // These are short front-matter entries (over-long text, or same-page
        // items whose JSON ids are consecutive) and must not surface as page
        // bookmarks.
        chains.removeIf(chain -> isTocLikeGroup(flattenChain(chain)));

        // Step 4.7: Recover orphaned "value=1" singletons by prepending them
        // onto chains that start at value=2. When the same template carries
        // both a leading "一、" entry (e.g. "一、审计报告") AND a sibling "一、"
        // entry that begins a longer sub-chain (e.g. "一、审计意见", "二、形成…"
        // etc.), Step 2 splits those value=1 candidates into separate groups
        // and Step 4 builds them as competing chains. The chain starting at
        // value=2 (e.g. "二、财务报表") is then a separate chain. Without this
        // pass, the widest-chain rule would still pick a value=2 chain over a
        // shorter value=1-only chain, dropping the leading "一、" entry from
        // the resulting bookmark set.
        //
        // Prepending a value=1 orphan onto a value=2 chain extends its value
        // range from [2..max] to [1..max] (length grows by one), making it
        // more competitive against the sibling value=1 sub-chain and recovering
        // the leading "一、" as the first L2 child of the parent section.
        prependValueOneOrphansOntoValueTwoChains(chains);

        // Step 5: Pick the chain with the widest value range. Tie-break by the
        // earliest start page of the chain's first candidate (closest to the
        // parent's start page).
        List<List<Candidate>> bestChain = null;
        int bestLength = -1;
        int bestStartPage = Integer.MAX_VALUE;
        for (List<List<Candidate>> chain : chains) {
            int chainMin = chain.get(0).get(0).value;
            Candidate lastGroupLast = chain.get(chain.size() - 1)
                .get(chain.get(chain.size() - 1).size() - 1);
            int chainMax = lastGroupLast.value;
            int length = chainMax - chainMin + 1;
            int startPage = Integer.MAX_VALUE;
            for (List<Candidate> group : chain) {
                for (Candidate c : group) {
                    if (c.pageIndex < startPage) startPage = c.pageIndex;
                }
            }
            if (length > bestLength
                || (length == bestLength && startPage < bestStartPage)) {
                bestLength = length;
                bestStartPage = startPage;
                bestChain = chain;
            }
        }

        if (bestChain == null) {
            return Collections.emptyList();
        }

        // Step 6: Concatenate candidates from the chosen chain and sort by
        // value so callers receive a value-ordered list.
        List<Candidate> result = new ArrayList<>();
        for (List<Candidate> group : bestChain) {
            result.addAll(group);
        }
        result.sort(Comparator.comparingInt((Candidate c) -> c.value));
        return result;
    }

    /**
     * Returns true when the given candidate chain resembles a table-of-contents
     * residue that should be excluded from page bookmarks.
     *
     * <p><strong>Rule 1:</strong> any entry whose full text exceeds
     * {@value #MAX_ENTRY_TEXT_LENGTH} characters. A real heading is short; a
     * long numbered paragraph is almost certainly body text.</p>
     *
     * <p><strong>Rule 2:</strong> same-page entries whose
     * {@link Candidate#relatedId} values are consecutive. Items emitted
     * adjacently on the same page are consecutive in reading order, which is
     * exactly how a table of contents lists its entries. For small chains
     * (2-5 entries) a single adjacent pair is enough; for larger chains at
     * least two pairs, or a run of three consecutive ids on one page, are
     * required so that legitimate multi-level headings with a single
     * coincidental adjacency are not dropped.</p>
     */
    private static boolean isTocLikeGroup(List<Candidate> chain) {
        if (chain.isEmpty()) {
            return false;
        }
        for (Candidate c : chain) {
            if (c.fullText != null && c.fullText.length() > MAX_ENTRY_TEXT_LENGTH) {
                return true;
            }
        }
        int size = chain.size();
        if (size < 2) {
            return false;
        }
        int pairCount = 0;
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (isSamePageAdjacent(chain.get(i), chain.get(j))) {
                    pairCount++;
                } else if (j == i + 1 && isCrossPageAdjacent(chain.get(i), chain.get(j))) {
                    pairCount++;
                }
            }
        }
        if (size <= 5) {
            return pairCount >= 1;
        }
        return pairCount >= 2 || maxConsecutiveRelatedIdRun(chain) >= 3;
    }

    private static boolean isSamePageAdjacent(Candidate a, Candidate b) {
        return a.pageIndex == b.pageIndex
            && Math.abs(a.relatedId - b.relatedId) == 1;
    }

    /**
     * Returns true when {@code a} (on page N) is immediately followed by
     * {@code b} (on page N+1) as a page-boundary pair: {@code a} is the last
     * element of its page and {@code b} is the first element of the next page
     * (id 1), or id 2 provided the id-1 element on that page is text. Only
     * paragraph/heading candidates are collected, so {@code a.relatedId == 0}
     * (IObject path) never satisfies this and the rule stays JSON-only.
     */
    private static boolean isCrossPageAdjacent(Candidate a, Candidate b) {
        return b.pageIndex == a.pageIndex + 1
            && a.relatedId == a.pageLastId
            && (b.relatedId == 1 || (b.relatedId == 2 && b.pageIdOneIsText));
    }

    /**
     * Returns the longest run of strictly consecutive {@code relatedId} values
     * in the chain. Runs are computed per page because the JSON id counter
     * resets at the start of each page, and a page-boundary bridge (previous
     * page ends at its last element, next page starts at id 1 or 2 with the
     * id-1 element being text) joins the two pages' runs into a longer one.
     */
    private static int maxConsecutiveRelatedIdRun(List<Candidate> chain) {
        Map<Integer, List<Integer>> idsByPage = new TreeMap<>();
        Map<Integer, Integer> pageLastIdByPage = new HashMap<>();
        Map<Integer, Boolean> pageIdOneIsTextByPage = new HashMap<>();
        for (Candidate c : chain) {
            idsByPage.computeIfAbsent(c.pageIndex, k -> new ArrayList<>()).add(c.relatedId);
            pageLastIdByPage.putIfAbsent(c.pageIndex, c.pageLastId);
            pageIdOneIsTextByPage.putIfAbsent(c.pageIndex, c.pageIdOneIsText);
        }
        int maxRun = 1;
        Integer previousPage = null;
        int previousPageLastId = 0;
        int previousPageMaxId = 0;
        int previousTrailingRun = 0;
        for (Map.Entry<Integer, List<Integer>> entry : idsByPage.entrySet()) {
            int pageIndex = entry.getKey();
            List<Integer> distinct = new ArrayList<>(new HashSet<>(entry.getValue()));
            Collections.sort(distinct);
            int within = 1;
            int currentRun = 1;
            for (int i = 1; i < distinct.size(); i++) {
                if (distinct.get(i) == distinct.get(i - 1) + 1) {
                    currentRun++;
                } else {
                    currentRun = 1;
                }
                if (currentRun > within) {
                    within = currentRun;
                }
            }
            maxRun = Math.max(maxRun, within);
            int trailing = 1;
            for (int i = distinct.size() - 1; i > 0; i--) {
                if (distinct.get(i) == distinct.get(i - 1) + 1) {
                    trailing++;
                } else {
                    break;
                }
            }
            int leading = 1;
            for (int i = 1; i < distinct.size(); i++) {
                if (distinct.get(i) == distinct.get(i - 1) + 1) {
                    leading++;
                } else {
                    break;
                }
            }
            if (previousPage != null && pageIndex == previousPage + 1) {
                int pageLastId = pageLastIdByPage.getOrDefault(pageIndex, 0);
                boolean idOneIsText = Boolean.TRUE.equals(pageIdOneIsTextByPage.get(pageIndex));
                int minId = distinct.get(0);
                boolean bridge = previousPageMaxId == previousPageLastId
                    && (minId == 1 || (minId == 2 && idOneIsText));
                if (bridge) {
                    maxRun = Math.max(maxRun, previousTrailingRun + leading);
                }
            }
            previousPage = pageIndex;
            previousPageLastId = pageLastIdByPage.getOrDefault(pageIndex, 0);
            previousPageMaxId = distinct.get(distinct.size() - 1);
            previousTrailingRun = trailing;
        }
        return maxRun;
    }

    private static List<Candidate> flattenChain(List<List<Candidate>> chain) {
        List<Candidate> flattened = new ArrayList<>();
        for (List<Candidate> group : chain) {
            flattened.addAll(group);
        }
        return flattened;
    }

    /**
     * Recovers orphaned {@code value=1} singleton chains by prepending them
     * onto chains that start at {@code value=2}.
     *
     * <p>An "orphan" here is a chain consisting of a single group holding a
     * single candidate whose value equals 1. Such orphans arise when the same
     * template carries two leading value=1 candidates (e.g. "一、审计报告"
     * followed by "一、审计意见"): Step 2 splits them into distinct groups, so
     * neither chain can merge with the other. A separate chain later starting
     * at value=2 (e.g. "二、财务报表") remains a value=2 chain instead of a
     * value=1 chain, dropping the leading "一、" entry from the final bookmark
     * set.</p>
     *
     * <p>This pass moves each orphan onto the first chain whose first group
     * starts at value=2 (mutating that chain in place), thereby shifting the
     * chain's value range from [2..max] to [1..max] and gaining one entry of
     * width. The mutation is value-disjoint by construction: Step 2 forbids
     * any chain from containing both value=1 and value=2 inside the same
     * group, so prepending value=1 can never conflict with the value=2 chain's
     * existing values.</p>
     *
     * <p>If multiple orphans exist, each is matched against the next available
     * value=2 chain in chain-list order; surplus orphans stay as their own
     * value=1 chains and compete normally in Step 5.</p>
     */
    private static void prependValueOneOrphansOntoValueTwoChains(
            List<List<List<Candidate>>> chains) {
        if (chains == null || chains.size() < 2) {
            return;
        }
        List<List<Candidate>> valueOneOrphans = new ArrayList<>();
        List<List<List<Candidate>>> nonOrphans = new ArrayList<>(chains.size());
        for (List<List<Candidate>> chain : chains) {
            if (chain.size() == 1 && chain.get(0).size() == 1
                    && chain.get(0).get(0).value == 1) {
                valueOneOrphans.add(chain.get(0));
            } else {
                nonOrphans.add(chain);
            }
        }
        if (valueOneOrphans.isEmpty()) {
            return;
        }
        for (List<Candidate> orphan : valueOneOrphans) {
            List<List<Candidate>> target = null;
            for (List<List<Candidate>> chain : nonOrphans) {
                if (!chain.isEmpty() && !chain.get(0).isEmpty()
                        && chain.get(0).get(0).value == 2) {
                    target = chain;
                    break;
                }
            }
            if (target == null) {
                // No eligible target: keep the orphan as-is so Step 5 can
                // still consider it as a standalone value=1 chain.
                List<List<Candidate>> wrapped = new ArrayList<>();
                wrapped.add(orphan);
                nonOrphans.add(0, wrapped);
                continue;
            }
            target.add(0, orphan);
        }
        chains.clear();
        chains.addAll(nonOrphans);
    }

    private static List<Candidate> collectCandidates(List<List<IObject>> contents) {
        List<Candidate> candidates = new ArrayList<>();
        // When a catalog (table-of-contents) page range was detected by
        // CatalogBookmarkProcessor, skip those pages here: the same heading
        // text ("绗竴鑺?閲婁箟", "(涓€) ..." etc.) appears on both catalog and body
        // pages. Catalog entries are already emitted as catalog_bookmarks, so
        // collecting them as page_bookmarks candidates would create duplicates
        // and break the strict consecutive-number validation in isValidGroup.
        int catalogStart = StaticLayoutContainers.getCatalogBookmarkStartPage();
        int catalogEnd = StaticLayoutContainers.getCatalogBookmarkEndPage();
        boolean skipCatalogPages = catalogStart >= 0 && catalogEnd >= catalogStart;
        for (int pageIndex = 0; pageIndex < contents.size(); pageIndex++) {
            if (skipCatalogPages && pageIndex >= catalogStart && pageIndex <= catalogEnd) {
                continue;
            }
            List<IObject> pageContents = contents.get(pageIndex);
            if (pageContents == null) {
                continue;
            }
            for (IObject content : pageContents) {
                List<String> allLines = extractAllLines(content);
                if (allLines.isEmpty()) {
                    continue;
                }
                String trimmed = allLines.get(0).trim();
                ConstantPattern match = matchPrefix(trimmed);
                if (match == null) {
                    continue;
                }
                // Build the full paragraph text by joining every line with the
                // smart-space rule (ASCII letter+letter or digit+digit).
                String fullText = SmartTextJoiner.joinPieces(allLines).trim();
                // SemanticTextNode and its subclasses expose font size; CustomSemanticParagraph
                // and SemanticHeading both descend from it, so this cast is safe for both.
                double fontSize = content instanceof org.verapdf.wcag.algorithms.entities.SemanticTextNode
                    ? ((org.verapdf.wcag.algorithms.entities.SemanticTextNode) content).getFontSize()
                    : 0.0;
                candidates.add(new Candidate(
                    pageIndex,
                    trimmed,
                    fullText,
                    allLines.size() == 1,
                    new TemplateKey(match.template, match.numberSystem),
                    match.value,
                    fontSize,
                    content.getLeftX(),
                    content.getTopY()
                ));
            }
        }
        LOGGER.log(java.util.logging.Level.INFO,
            "[PageBookmark] collected {0} candidates (catalog pages skipped: {1}-{2})",
            new Object[]{candidates.size(),
                skipCatalogPages ? catalogStart : -1,
                skipCatalogPages ? catalogEnd : -1});
        return candidates;
    }

    /**
     * Collects page bookmark candidates from JSON items. Only paragraph and heading
     * items are considered, and catalog pages are skipped.
     */
    private static List<Candidate> collectJsonCandidates(List<Map<String, Object>> data,
                                                         int catalogStartPage,
                                                         int catalogEndPage) {
        List<Candidate> candidates = new ArrayList<>();
        boolean skipCatalogPages = catalogStartPage >= 0 && catalogEndPage >= catalogStartPage;
        for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
            if (skipCatalogPages && pageIndex >= catalogStartPage && pageIndex <= catalogEndPage) {
                continue;
            }
            Map<String, Object> page = data.get(pageIndex);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
            if (items == null) {
                continue;
            }
            int pageLastId = 0;
            boolean pageIdOneIsText = false;
            for (Map<String, Object> item : items) {
                Object idObj = item.get(JsonName.ID);
                if (!(idObj instanceof Number)) {
                    continue;
                }
                int itemId = ((Number) idObj).intValue();
                if (itemId > pageLastId) {
                    pageLastId = itemId;
                }
                if (itemId == 1) {
                    String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
                    pageIdOneIsText = JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)
                        || JsonName.SOURCE_TYPE_HEADING.equals(sourceType);
                }
            }
            for (Map<String, Object> item : items) {
                String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
                if (!JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)
                        && !JsonName.SOURCE_TYPE_HEADING.equals(sourceType)) {
                    continue;
                }
                List<String> allLines = collectJsonItemAllLines(item);
                if (allLines.isEmpty()) {
                    continue;
                }
                String trimmed = allLines.get(0).trim();
                ConstantPattern match = matchPrefix(trimmed);
                if (match == null) {
                    continue;
                }
                String fullText = SmartTextJoiner.joinPieces(allLines).trim();
                Object idObj = item.get(JsonName.ID);
                int relatedId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
                double fontSize = getJsonItemFontSize(item);
                double leftX = getJsonItemDouble(item, JsonName.X0);
                // JSON y0 increases downward; negate so the existing
                // descending-topY sort produces top-to-bottom reading order.
                double topY = -getJsonItemDouble(item, JsonName.Y0);
                candidates.add(new Candidate(
                    pageIndex,
                    trimmed,
                    fullText,
                    allLines.size() == 1,
                    new TemplateKey(match.template, match.numberSystem),
                    match.value,
                    fontSize,
                    leftX,
                    topY,
                    relatedId,
                    pageLastId,
                    pageIdOneIsText
                ));
            }
        }
        LOGGER.log(java.util.logging.Level.INFO,
            "[PageBookmark] collected {0} JSON candidates (catalog pages skipped: {1}-{2})",
            new Object[]{candidates.size(),
                skipCatalogPages ? catalogStartPage : -1,
                skipCatalogPages ? catalogEndPage : -1});
        return candidates;
    }

    private static String getJsonItemFirstLine(Map<String, Object> item) {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List) || ((List<?>) contentObj).isEmpty()) {
            return null;
        }
        List<?> contentList = (List<?>) contentObj;
        Object first = contentList.get(0);
        if (first instanceof Map) {
            Object textListObj = ((Map<?, ?>) first).get(JsonName.CONTENT);
            if (textListObj instanceof List && !((List<?>) textListObj).isEmpty()) {
                Object textObj = ((List<?>) textListObj).get(0);
                return textObj != null ? textObj.toString() : null;
            }
        }
        return first != null ? first.toString() : null;
    }

    /**
     * Returns all lines of a paragraph/heading JSON item in reading order.
     * Each entry in {@code item.content} represents one line; if that line is
     * itself a map with its own {@code content} list, the list elements are
     * treated as text pieces of a single line and joined with the smart-space
     * rule. Returns an empty list when the item has no usable text.
     */
    private static List<String> collectJsonItemAllLines(Map<String, Object> item) {
        List<String> lines = new ArrayList<>();
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return lines;
        }
        for (Object lineObj : (List<?>) contentObj) {
            if (lineObj instanceof Map) {
                Object textListObj = ((Map<?, ?>) lineObj).get(JsonName.CONTENT);
                if (textListObj instanceof List) {
                    StringBuilder sb = new StringBuilder();
                    for (Object t : (List<?>) textListObj) {
                        if (t == null) {
                            continue;
                        }
                        String s = t.toString();
                        if (s.isEmpty()) {
                            continue;
                        }
                        SmartTextJoiner.appendSmart(sb, s);
                    }
                    String joined = sb.toString();
                    if (!joined.isEmpty()) {
                        lines.add(joined);
                    }
                }
            } else if (lineObj != null) {
                String s = lineObj.toString();
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        return lines;
    }

    private static double getJsonItemFontSize(Map<String, Object> item) {
        Object value = item.get(JsonName.FONT_UNDERLINE_SIZE);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private static double getJsonItemDouble(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * Extracts the first text line from a paragraph or heading element.
     * <p>
     * After {@link HeadingProcessor#processHeadings} runs, what started life as a
     * {@link CustomSemanticParagraph} may have been re-wrapped as a
     * {@link SemanticHeading}. Both share the {@link org.verapdf.wcag.algorithms.entities.SemanticTextNode}
     * base class and expose the heading text through its first line; this helper
     * abstracts the difference between {@code getTextLines().get(0)} on a paragraph
     * and {@code getFirstLine()} on a heading so the bookmark candidate scan sees
     * both.
     *
     * @return the first line text, trimmed; {@code null} when the element is not a
     *         paragraph/heading or has no text
     */
    private static String extractFirstText(IObject content) {
        if (content instanceof CustomSemanticParagraph) {
            List<TextLine> textLines = ((CustomSemanticParagraph) content).getTextLines();
            if (textLines.isEmpty()) {
                return null;
            }
            TextLine firstLine = textLines.get(0);
            return firstLine != null ? firstLine.getValue() : null;
        }
        if (content instanceof SemanticHeading) {
            TextLine firstLine = ((SemanticHeading) content).getFirstLine();
            return firstLine != null ? firstLine.getValue() : null;
        }
        return null;
    }

    /**
     * Returns all non-null text-line values of an element, in reading order.
     * Paragraphs contribute every {@link TextLine}; headings contribute only
     * their first line because the verapdf {@link SemanticHeading} API does
     * not expose a multi-line accessor and headings are typically single-line
     * anyway. Returns an empty list when the element has no usable text.
     */
    private static List<String> extractAllLines(IObject content) {
        List<String> lines = new ArrayList<>();
        if (content instanceof CustomSemanticParagraph) {
            List<TextLine> textLines = ((CustomSemanticParagraph) content).getTextLines();
            if (textLines != null) {
                for (TextLine line : textLines) {
                    if (line != null && line.getValue() != null) {
                        lines.add(line.getValue());
                    }
                }
            }
            return lines;
        }
        if (content instanceof SemanticHeading) {
            TextLine firstLine = ((SemanticHeading) content).getFirstLine();
            if (firstLine != null && firstLine.getValue() != null) {
                lines.add(firstLine.getValue());
            }
        }
        return lines;
    }

    public static boolean isBookmarkCandidate(String text) {
        return text != null && matchPrefix(text.trim()) != null;
    }

    private static ConstantPattern matchPrefix(String text) {
        for (ConstantPattern pattern : PATTERNS) {
            if (text.startsWith(pattern.constant)) {
                if (!hasValidBookmarkSuffix(text, pattern)) {
                    continue;
                }
                return pattern;
            }
        }
        return null;
    }

    private static boolean hasValidBookmarkSuffix(String text, ConstantPattern pattern) {
        char last = pattern.constant.charAt(pattern.constant.length() - 1);
        if (last == ')' || last == '）'
            || last == CHINESE_COMMA_FULL_WIDTH) {
            return true;
        }
        int suffixIndex = pattern.constant.length();
        if (suffixIndex >= text.length()) {
            return false;
        }
        char suffix = text.charAt(suffixIndex);
        // Full-width and half-width dots are treated as the same type.
        boolean isDot = suffix == '.' || suffix == FULL_WIDTH_DOT;
        if (!(Character.isWhitespace(suffix) || isDot
            || suffix == CHINESE_COMMA_FULL_WIDTH)) {
            return false;
        }
        // A dot right after the number cannot be followed by another digit
        // ("1.5" is a decimal, not a bookmark).
        return !isDot || suffixIndex + 1 >= text.length()
            || !Character.isDigit(text.charAt(suffixIndex + 1));
    }

    private static List<List<Candidate>> splitByValueOne(List<Candidate> sorted) {
        List<List<Candidate>> sections = new ArrayList<>();
        List<Candidate> current = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.value == 1 && !current.isEmpty()) {
                sections.add(current);
                current = new ArrayList<>();
            }
            current.add(c);
        }
        if (!current.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    /**
     * Trims a single chapter section to a contiguous "from-1" sequence.
     * Duplicate values are deduplicated by keeping the latest-occurring
     * occurrence; stray values outside the contiguous range are dropped.
     *
     * @param section candidates of one chapter, ordered in reading order
     * @return trimmed candidates, or null if the section has no valid range
     */
    private static List<Candidate> trimContiguousSection(List<Candidate> section) {
        int length = contiguousFromOneLength(section);
        if (length == 0) {
            return null;
        }
        List<Candidate> byLatestPage = new ArrayList<>(section);
        byLatestPage.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .reversed()
            .thenComparing((Candidate c) -> c.topY));
        Set<Integer> used = new HashSet<>();
        Map<Integer, Candidate> latestByValue = new LinkedHashMap<>();
        for (Candidate c : byLatestPage) {
            if (c.value >= 1 && c.value <= length && used.add(c.value)) {
                latestByValue.put(c.value, c);
            }
        }
        List<Candidate> trimmed = new ArrayList<>(latestByValue.values());
        trimmed.sort(Comparator
            .comparingInt((Candidate c) -> c.value));
        return trimmed;
    }

    private static int contiguousFromOneLength(List<Candidate> section) {
        Set<Integer> present = new HashSet<>();
        for (Candidate c : section) {
            present.add(c.value);
        }
        int length = 0;
        for (int v = 1; v <= 100; v++) {
            if (present.contains(v)) {
                length = v;
            } else {
                break;
            }
        }
        return length;
    }

    private static boolean isValidGroup(Group group) {
        if (group.candidates.isEmpty()) {
            return false;
        }
        List<Integer> values = new ArrayList<>();
        for (Candidate candidate : group.candidates) {
            values.add(candidate.value);
        }
        values.sort(Integer::compareTo);

        if (TEMPLATE_SECTION.equals(group.templateKey.template) && values.get(0) == 1) {
            return true;
        }
        if (values.size() == 1) {
            return values.get(0) == 1;
        }

        int previous = 0;
        int distinct = 0;
        for (Integer value : values) {
            if (value < 1) {
                return false;
            }
            if (value != previous) {
                if (previous != 0 && value > previous + 1) {
                    return false;
                }
                distinct++;
                previous = value;
            }
        }
        return distinct >= 2;
    }

    private static Bookmark createBookmark(Candidate candidate) {
        Bookmark bookmark = new Bookmark();
        // Use the full paragraph text (joined with smart-space) so multi-line
        // bookmarks expose every line in {@code text}. {@code Candidate.text}
        // is kept as the first line for prefix matching upstream; it is no
        // longer used as the output here.
        bookmark.setText(candidate.fullText != null ? candidate.fullText : candidate.text);
        bookmark.setPageNum(candidate.pageIndex + 1);
        bookmark.setFontSize((float) candidate.fontSize);
        // Reflect whether the source paragraph/heading actually had a single
        // line, so downstream consumers can tell apart one-line titles from
        // wrapped multi-line ones.
        bookmark.setSingleLine(candidate.singleLine);
        bookmark.setOpen(false);
        bookmark.setRelatedId(candidate.relatedId);
        bookmark.setChildren(new ArrayList<>());
        return bookmark;
    }
}
