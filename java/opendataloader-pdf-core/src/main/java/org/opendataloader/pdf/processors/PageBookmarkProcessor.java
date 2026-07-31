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
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>Level assignment is dynamic: up to three validated groups are ranked by average
 * font size (larger = higher level) and average left indentation (smaller = higher
 * level), then arranged into a parent-child tree in document reading order.</p>
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
        // Longest first so that "10" is preferred over "1" and "第100章" over "第1章".
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

        Candidate(int pageIndex, String text, TemplateKey templateKey, int value,
                  double fontSize, double leftX, double topY) {
            this.pageIndex = pageIndex;
            this.text = text;
            this.templateKey = templateKey;
            this.value = value;
            this.fontSize = fontSize;
            this.leftX = leftX;
            this.topY = topY;
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

        List<Candidate> candidates = collectCandidates(contents);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Map<TemplateKey, Group> groups = groupByTemplate(candidates);
        List<Group> validGroups = new ArrayList<>();
        for (Group group : groups.values()) {
            if (isValidGroup(group)) {
                group.computeStatistics();
                validGroups.add(group);
            }
        }

        if (validGroups.isEmpty()) {
            return Collections.emptyList();
        }

        // Rank groups by visual hierarchy: larger font and smaller left indentation
        // indicate higher level. Earlier appearance is used as a stable tie-breaker.
        validGroups.sort((a, b) -> {
            int fontCmp = Double.compare(b.averageFontSize, a.averageFontSize);
            if (fontCmp != 0) {
                return fontCmp;
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

        int maxLevels = Math.min(3, validGroups.size());
        Map<TemplateKey, Integer> levelByTemplate = new HashMap<>();
        for (int i = 0; i < maxLevels; i++) {
            levelByTemplate.put(validGroups.get(i).templateKey, i + 1);
        }

        // Sort candidates in reading order: page by page, top to bottom.
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.pageIndex)
                .thenComparing((Candidate c) -> -c.topY));

        List<Bookmark> rootBookmarks = new ArrayList<>();
        Bookmark currentLevel1 = null;
        Bookmark currentLevel2 = null;

        for (Candidate candidate : candidates) {
            Integer level = levelByTemplate.get(candidate.templateKey);
            if (level == null) {
                continue;
            }
            Bookmark bookmark = createBookmark(candidate);
            if (level == 1) {
                rootBookmarks.add(bookmark);
                currentLevel1 = bookmark;
                currentLevel2 = null;
            } else if (level == 2) {
                if (currentLevel1 == null) {
                    rootBookmarks.add(bookmark);
                } else {
                    currentLevel1.getChildren().add(bookmark);
                }
                currentLevel2 = bookmark;
            } else if (level == 3) {
                if (currentLevel2 != null) {
                    currentLevel2.getChildren().add(bookmark);
                } else if (currentLevel1 != null) {
                    currentLevel1.getChildren().add(bookmark);
                } else {
                    rootBookmarks.add(bookmark);
                }
            }
        }

        return rootBookmarks;
    }

    private static List<Candidate> collectCandidates(List<List<IObject>> contents) {
        List<Candidate> candidates = new ArrayList<>();
        // When a catalog (table-of-contents) page range was detected by
        // CatalogBookmarkProcessor, skip those pages here: the same heading
        // text ("第一节 释义", "(一) ..." etc.) appears on both catalog and body
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

    private static ConstantPattern matchPrefix(String text) {
        for (ConstantPattern pattern : PATTERNS) {
            if (text.startsWith(pattern.constant)) {
                if (TEMPLATE_NUMBER.equals(pattern.template)) {
                    int nextIndex = pattern.constant.length();
                    if (nextIndex < text.length() && isDigitLike(text.charAt(nextIndex))) {
                        continue;
                    }
                }
                return pattern;
            }
        }
        return null;
    }

    private static boolean isDigitLike(char c) {
        return (c >= '0' && c <= '9')
                || c == '一' || c == '二' || c == '三' || c == '四' || c == '五'
                || c == '六' || c == '七' || c == '八' || c == '九' || c == '十' || c == '百';
    }

    private static Map<TemplateKey, Group> groupByTemplate(List<Candidate> candidates) {
        Map<TemplateKey, Group> groups = new HashMap<>();
        for (Candidate candidate : candidates) {
            groups.computeIfAbsent(candidate.templateKey, Group::new).add(candidate);
        }
        return groups;
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

        if (values.size() == 1) {
            return values.get(0) == 1;
        }

        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != i + 1) {
                return false;
            }
        }
        return true;
    }

    private static Bookmark createBookmark(Candidate candidate) {
        Bookmark bookmark = new Bookmark();
        bookmark.setText(candidate.text);
        bookmark.setPageNum(candidate.pageIndex + 1);
        bookmark.setFontSize((float) candidate.fontSize);
        bookmark.setSingleLine(true);
        bookmark.setOpen(false);
        bookmark.setChildren(new ArrayList<>());
        return bookmark;
    }
}
