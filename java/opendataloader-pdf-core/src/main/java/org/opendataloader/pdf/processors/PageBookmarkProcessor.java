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
        final TemplateKey templateKey;
        final int value;
        final double fontSize;
        final double leftX;
        final double topY;
        final int relatedId;

        Candidate(int pageIndex, String text, TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY) {
            this(pageIndex, text, templateKey, value, fontSize, leftX, topY, 0);
        }

        Candidate(int pageIndex, String text, TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY, int relatedId) {
            this.pageIndex = pageIndex;
            this.text = text;
            this.templateKey = templateKey;
            this.value = value;
            this.fontSize = fontSize;
            this.leftX = leftX;
            this.topY = topY;
            this.relatedId = relatedId;
        }
    }

    private static final class Group {
        final TemplateKey templateKey;
        final List<Candidate> candidates = new ArrayList<>();
        double averageFontSize;
        double averageLeftX;
        int firstPageIndex;
        double firstTopY;

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
            : cleanCandidatesLocal(selectedCandidates);
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
        Set<TemplateKey> newUsed = new HashSet<>(usedTemplates);
        newUsed.add(selectedTemplate);

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
                cleaned = cleanCandidatesLocal(group.candidates);
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
            int indentCmp = Double.compare(a.averageLeftX, b.averageLeftX);
            if (indentCmp != 0) {
                return indentCmp;
            }
            int countCmp = Integer.compare(b.candidates.size(), a.candidates.size());
            if (countCmp != 0) {
                return countCmp;
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
        List<Candidate> trimmed = new ArrayList<>();
        for (List<Candidate> section : sections) {
            List<Candidate> trimmedSection = trimContiguousSection(section);
            if (trimmedSection != null && !trimmedSection.isEmpty()) {
                trimmed.addAll(trimmedSection);
            }
        }
        return trimmed;
    }

    /**
     * Cleans a list of candidates of the same template within a parent range.
     * Duplicates are resolved by keeping the latest occurrence, and the longest
     * consecutive run of values is retained. Unlike {@link #cleanCandidates},
     * the run may start at any value, allowing headings to continue their
     * numbering across parent sections.
     */
    private static List<Candidate> cleanCandidatesLocal(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Resolve duplicates by keeping the latest occurrence per value.
        List<Candidate> byLatestPage = new ArrayList<>(candidates);
        byLatestPage.sort(Comparator
            .comparingInt((Candidate c) -> c.pageIndex)
            .reversed()
            .thenComparing((Candidate c) -> c.topY));
        Set<Integer> used = new HashSet<>();
        Map<Integer, Candidate> latestByValue = new LinkedHashMap<>();
        for (Candidate c : byLatestPage) {
            if (used.add(c.value)) {
                latestByValue.put(c.value, c);
            }
        }

        if (latestByValue.isEmpty()) {
            return Collections.emptyList();
        }

        // Find the longest run of consecutive integer values.
        List<Integer> sortedValues = new ArrayList<>(latestByValue.keySet());
        Collections.sort(sortedValues);
        int bestStart = 0;
        int bestLength = 1;
        int currentStart = 0;
        int currentLength = 1;
        for (int i = 1; i < sortedValues.size(); i++) {
            if (sortedValues.get(i) == sortedValues.get(i - 1) + 1) {
                currentLength++;
            } else {
                if (currentLength > bestLength) {
                    bestLength = currentLength;
                    bestStart = currentStart;
                }
                currentStart = i;
                currentLength = 1;
            }
        }
        if (currentLength > bestLength) {
            bestLength = currentLength;
            bestStart = currentStart;
        }

        List<Candidate> trimmed = new ArrayList<>();
        for (int i = bestStart; i < bestStart + bestLength; i++) {
            trimmed.add(latestByValue.get(sortedValues.get(i)));
        }
        trimmed.sort(Comparator.comparingInt((Candidate c) -> c.value));
        return trimmed;
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
                String firstLineText = extractFirstText(content);
                if (firstLineText == null) {
                    continue;
                }
                String trimmed = firstLineText.trim();
                ConstantPattern match = matchPrefix(trimmed);
                if (match == null) {
                    continue;
                }
                // SemanticTextNode and its subclasses expose font size; CustomSemanticParagraph
                // and SemanticHeading both descend from it, so this cast is safe for both.
                double fontSize = content instanceof org.verapdf.wcag.algorithms.entities.SemanticTextNode
                    ? ((org.verapdf.wcag.algorithms.entities.SemanticTextNode) content).getFontSize()
                    : 0.0;
                candidates.add(new Candidate(
                    pageIndex,
                    trimmed,
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
            for (Map<String, Object> item : items) {
                String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
                if (!JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)
                        && !JsonName.SOURCE_TYPE_HEADING.equals(sourceType)) {
                    continue;
                }
                String firstLine = getJsonItemFirstLine(item);
                if (firstLine == null) {
                    continue;
                }
                String trimmed = firstLine.trim();
                ConstantPattern match = matchPrefix(trimmed);
                if (match == null) {
                    continue;
                }
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
                    new TemplateKey(match.template, match.numberSystem),
                    match.value,
                    fontSize,
                    leftX,
                    topY,
                    relatedId
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
        bookmark.setText(candidate.text);
        bookmark.setPageNum(candidate.pageIndex + 1);
        bookmark.setFontSize((float) candidate.fontSize);
        bookmark.setSingleLine(true);
        bookmark.setOpen(false);
        bookmark.setRelatedId(candidate.relatedId);
        bookmark.setChildren(new ArrayList<>());
        return bookmark;
    }
}
