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

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects table-of-contents (TOC) pages in a document and extracts catalog
 * bookmarks from the detected page range.
 *
 * <p>The algorithm works in two phases:</p>
 * <ol>
 *   <li><b>Page-range detection</b>: each page is scored by the density of
 *       lines ending with a page number. Lines are matched against Arabic
 *       numerals, Roman numerals, and the PDF's own page labels. Consecutive
 *       pages that pass the threshold are merged into candidate ranges, and
 *       the best range is selected by a combined score of total TOC lines and
 *       page span.</li>
 *   <li><b>Bookmark extraction</b>: lines inside the selected range are
 *       parsed into title/page-number pairs and assembled into a hierarchical
 *       tree using left indentation and font size.</li>
 * </ol>
 */
public class CatalogBookmarkProcessor {

    private static final Logger LOGGER = Logger.getLogger(CatalogBookmarkProcessor.class.getCanonicalName());

    private static final Pattern ARABIC_TOC_PATTERN = Pattern.compile("^(.*?)[\\s\\.]+(\\d{1,5})$");
    private static final Pattern ROMAN_TOC_PATTERN = Pattern.compile("^(.*?)[\\s\\.]+([IVXLCDMivxlcdm]+)$");
    private static final Pattern ROMAN_NUMERAL = Pattern.compile("^(?i)M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$");
    private static final String TITLE_CLEANUP = "[\\s\\.]+$";
    private static final double CONTINUATION_LEFT_X_DELTA = 2.0;
    private static final double CONTINUATION_VERTICAL_GAP = 18.0;

    /**
     * Detects the catalog/TOC page range and extracts bookmarks from it.
     *
     * @param contents per-page document contents produced by the pipeline
     * @param config   processing configuration; thresholds are read from it
     * @return list of top-level catalog bookmarks, possibly empty
     */
    public static List<Bookmark> extractCatalogBookmarks(List<List<IObject>> contents, Config config) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> pageLabels = collectPageLabels();

        int minTocLines = config != null ? config.getCatalogBookmarkMinTocLines() : 3;
        double minTocRatio = config != null ? config.getCatalogBookmarkMinTocRatio() : 0.4;

        List<PageTocInfo> pageInfos = analyzePages(contents, pageLabels, minTocLines, minTocRatio);
        List<PageRange> ranges = detectRanges(pageInfos);
        PageRange bestRange = selectBestRange(ranges);

        if (bestRange == null) {
            LOGGER.log(Level.INFO, "[CatalogBookmark] no catalog page range detected");
            return Collections.emptyList();
        }

        LOGGER.log(Level.INFO,
                "[CatalogBookmark] detected catalog page range: {0}-{1} ({2} pages, {3} toc lines)",
                new Object[]{bestRange.startPage + 1, bestRange.endPage + 1,
                        bestRange.pageCount(), bestRange.totalTocLines});

        List<Bookmark> roots = extractBookmarks(contents, bestRange, pageLabels);
        int total = countAllBookmarks(roots);
        LOGGER.log(Level.INFO,
                "[CatalogBookmark] extracted {0} bookmarks ({1} top-level) from range {2}-{3}",
                new Object[]{total, roots.size(), bestRange.startPage + 1, bestRange.endPage + 1});

        return roots;
    }

    /**
     * Collects all page labels defined in the PDF document.
     */
    private static Set<String> collectPageLabels() {
        Set<String> labels = new HashSet<>();
        int totalPages = StaticContainers.getDocument().getNumberOfPages();
        for (int i = 0; i < totalPages; i++) {
            String label = StaticContainers.getDocument().getPage(i).getPageLabel();
            if (label != null) {
                labels.add(label);
            }
        }
        return labels;
    }

    /**
     * Scans every page and counts how many lines look like TOC entries.
     */
    private static List<PageTocInfo> analyzePages(List<List<IObject>> contents, Set<String> pageLabels,
                                                   int minTocLines, double minTocRatio) {
        List<PageTocInfo> infos = new ArrayList<>(contents.size());
        for (int pageIndex = 0; pageIndex < contents.size(); pageIndex++) {
            List<IObject> pageContents = contents.get(pageIndex);
            PageTocInfo info = new PageTocInfo(pageIndex, minTocLines, minTocRatio);
            if (pageContents == null) {
                infos.add(info);
                continue;
            }
            for (IObject content : pageContents) {
                if (!(content instanceof TextLine)) {
                    continue;
                }
                TextLine line = (TextLine) content;
                String value = line.getValue();
                if (value == null || value.trim().isEmpty() || line.isHiddenText()) {
                    continue;
                }
                info.totalLines++;
                TocMatch match = matchTocLine(value.trim(), pageLabels);
                if (match != null) {
                    info.tocLines.add(new TocLine(match.title, match.rawPage, line));
                }
            }
            infos.add(info);
        }
        return infos;
    }

    /**
     * Tries to match a TOC line ending with a page number or page label.
     *
     * @param text       the line text
     * @param pageLabels set of page labels defined in the document
     * @return a {@link TocMatch} with title and raw page, or null if no match
     */
    private static TocMatch matchTocLine(String text, Set<String> pageLabels) {
        // Arabic numerals: "Title ........ 12"
        Matcher arabicMatcher = ARABIC_TOC_PATTERN.matcher(text);
        if (arabicMatcher.matches()) {
            String title = arabicMatcher.group(1).trim().replaceAll(TITLE_CLEANUP, "");
            if (!title.isEmpty()) {
                return new TocMatch(title, arabicMatcher.group(2));
            }
        }

        // Roman numerals: "Title .... iv"
        Matcher romanMatcher = ROMAN_TOC_PATTERN.matcher(text);
        if (romanMatcher.matches()) {
            String rawPage = romanMatcher.group(2);
            if (ROMAN_NUMERAL.matcher(rawPage).matches()) {
                String title = romanMatcher.group(1).trim().replaceAll(TITLE_CLEANUP, "");
                if (!title.isEmpty()) {
                    return new TocMatch(title, rawPage);
                }
            }
        }

        // Document page labels: "Title .... A" or "Title .... iii"
        if (pageLabels != null) {
            for (String label : pageLabels) {
                if (label.isEmpty()) {
                    continue;
                }
                int labelPos = findLabelSuffix(text, label);
                if (labelPos > 0) {
                    String title = text.substring(0, labelPos).trim().replaceAll(TITLE_CLEANUP, "");
                    if (!title.isEmpty()) {
                        return new TocMatch(title, label);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds the start position of a page-label suffix preceded by a separator.
     *
     * @param text  original line text
     * @param label page label to look for
     * @return position of the separator before the label, or -1 if not found
     */
    private static int findLabelSuffix(String text, String label) {
        if (label.isEmpty() || text.length() < label.length() + 1) {
            return -1;
        }
        int labelStart = text.length() - label.length();
        if (!text.substring(labelStart).equalsIgnoreCase(label)) {
            return -1;
        }
        // Skip whitespace immediately before the label.
        int separatorPos = labelStart;
        while (separatorPos > 0 && Character.isWhitespace(text.charAt(separatorPos - 1))) {
            separatorPos--;
        }
        if (separatorPos == 0) {
            return -1;
        }
        char separator = text.charAt(separatorPos - 1);
        if (separator == '.' || Character.isWhitespace(separator)) {
            return separatorPos;
        }
        return -1;
    }

    /**
     * Groups consecutive TOC pages into candidate ranges.
     */
    private static List<PageRange> detectRanges(List<PageTocInfo> pageInfos) {
        List<PageRange> ranges = new ArrayList<>();
        PageRange current = null;
        for (PageTocInfo info : pageInfos) {
            if (info.isTocPage()) {
                if (current == null) {
                    current = new PageRange(info.pageIndex, info.tocLines.size());
                } else if (info.pageIndex == current.endPage + 1) {
                    current.endPage = info.pageIndex;
                    current.totalTocLines += info.tocLines.size();
                } else {
                    ranges.add(current);
                    current = new PageRange(info.pageIndex, info.tocLines.size());
                }
            }
        }
        if (current != null) {
            ranges.add(current);
        }
        return ranges;
    }

    /**
     * Selects the best candidate range using a combined score.
     *
     * <p>The score balances total TOC lines and page span, so a multi-page
     * catalog with many entries beats a single dense page of page-numbered
     * lines.</p>
     */
    private static PageRange selectBestRange(List<PageRange> ranges) {
        if (ranges.isEmpty()) {
            return null;
        }
        PageRange best = ranges.get(0);
        double bestScore = best.score();
        for (int i = 1; i < ranges.size(); i++) {
            PageRange candidate = ranges.get(i);
            double candidateScore = candidate.score();
            if (candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        return best;
    }

    /**
     * Parses TOC lines inside the selected range into hierarchical bookmarks,
     * merging continuation lines for multi-line titles.
     */
    private static List<Bookmark> extractBookmarks(List<List<IObject>> contents, PageRange range,
                                                   Set<String> pageLabels) {
        List<LineInfo> allLines = collectAllLines(contents, range, pageLabels);
        if (allLines.isEmpty()) {
            return Collections.emptyList();
        }

        // Reading order: page by page, top to bottom (PDF y decreases downward).
        allLines.sort(Comparator
                .comparingInt((LineInfo l) -> l.pageIndex)
                .thenComparing((LineInfo l) -> -l.line.getTopY()));

        List<Candidate> candidates = new ArrayList<>();
        StringBuilder pendingTitle = new StringBuilder();
        LineInfo pendingLine = null;

        for (LineInfo info : allLines) {
            if (info.isToc) {
                String title = info.title;
                if (pendingTitle.length() > 0 && pendingLine != null
                        && isContinuation(pendingLine, info)) {
                    title = pendingTitle.toString() + title;
                }
                pendingTitle.setLength(0);
                pendingLine = null;

                if (isTocHeading(title)) {
                    continue;
                }

                Bookmark bookmark = new Bookmark();
                bookmark.setText(title);
                bookmark.setOriginalPageNum(parseOriginalPageNum(info.rawPage));
                bookmark.setPageNum(resolvePageIndex(info.rawPage, pageLabels) + 1);
                bookmark.setFontSize((float) info.line.getFontSize());
                bookmark.setSingleLine(true);
                bookmark.setChildren(new ArrayList<>());
                candidates.add(new Candidate(bookmark, info.line.getLeftX(), info.line.getTopY(), info.pageIndex));
            } else {
                if (pendingTitle.length() == 0) {
                    pendingTitle.append(info.title);
                    pendingLine = info;
                } else {
                    pendingTitle.append(" ").append(info.title);
                }
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        assignLevels(candidates);
        return buildHierarchy(candidates);
    }

    /**
     * Collects all text lines inside the selected range, marking TOC lines.
     */
    private static List<LineInfo> collectAllLines(List<List<IObject>> contents, PageRange range,
                                                   Set<String> pageLabels) {
        List<LineInfo> allLines = new ArrayList<>();
        for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
            List<IObject> pageContents = contents.get(pageIndex);
            if (pageContents == null) {
                continue;
            }
            for (IObject content : pageContents) {
                if (!(content instanceof TextLine)) {
                    continue;
                }
                TextLine line = (TextLine) content;
                String value = line.getValue();
                if (value == null || value.trim().isEmpty() || line.isHiddenText()) {
                    continue;
                }
                String trimmed = value.trim();
                TocMatch match = matchTocLine(trimmed, pageLabels);
                if (match != null) {
                    allLines.add(new LineInfo(line, match.title, match.rawPage, pageIndex, true));
                } else {
                    allLines.add(new LineInfo(line, trimmed, null, pageIndex, false));
                }
            }
        }
        return allLines;
    }

    /**
     * Checks whether a non-TOC line is a continuation of the next TOC line's title.
     */
    private static boolean isContinuation(LineInfo pending, LineInfo toc) {
        if (pending.pageIndex != toc.pageIndex) {
            return false;
        }
        double fontSize = toc.line.getFontSize();
        double leftDelta = Math.abs(pending.line.getLeftX() - toc.line.getLeftX());
        double fontDelta = Math.abs(pending.line.getFontSize() - fontSize);
        double verticalGap = pending.line.getTopY() - toc.line.getTopY();
        return leftDelta <= CONTINUATION_LEFT_X_DELTA + fontSize * 0.5
                && fontDelta <= 0.5
                && verticalGap >= 0
                && verticalGap <= CONTINUATION_VERTICAL_GAP + fontSize;
    }

    /**
     * Returns true for TOC headings that should not become bookmarks.
     */
    private static boolean isTocHeading(String title) {
        String compact = title.replaceAll("\\s+", "");
        return "目录".equals(compact) || "目錄".equals(compact) || "Contents".equalsIgnoreCase(compact)
                || "TableofContents".equalsIgnoreCase(compact);
    }

    /**
     * Parses a raw page string into an integer for original_page_num. Roman numerals
     * and labels that are not pure Arabic integers are returned as 0.
     */
    private static int parseOriginalPageNum(String rawPage) {
        try {
            return Integer.parseInt(rawPage);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Assigns a hierarchy level to each candidate based on left indentation.
     */
    private static void assignLevels(List<Candidate> candidates) {
        double minLeftX = candidates.stream().mapToDouble(c -> c.leftX).min().orElse(0.0);
        double avgFontSize = candidates.stream().mapToDouble(c -> c.bookmark.getFontSize()).average().orElse(12.0);
        double levelStep = avgFontSize * 1.5;

        for (Candidate candidate : candidates) {
            int level = 1 + (int) Math.floor((candidate.leftX - minLeftX) / levelStep);
            candidate.level = Math.max(1, Math.min(level, 3));
        }
    }

    /**
     * Builds a parent-child tree from leveled candidates.
     */
    private static List<Bookmark> buildHierarchy(List<Candidate> candidates) {
        List<Bookmark> roots = new ArrayList<>();
        Candidate lastL1 = null;
        Candidate lastL2 = null;

        for (Candidate candidate : candidates) {
            Bookmark bookmark = candidate.bookmark;
            int level = candidate.level;
            if (level == 1) {
                roots.add(bookmark);
                lastL1 = candidate;
                lastL2 = null;
            } else if (level == 2) {
                if (lastL1 != null) {
                    lastL1.bookmark.getChildren().add(bookmark);
                } else {
                    roots.add(bookmark);
                }
                lastL2 = candidate;
            } else {
                if (lastL2 != null) {
                    lastL2.bookmark.getChildren().add(bookmark);
                } else if (lastL1 != null) {
                    lastL1.bookmark.getChildren().add(bookmark);
                } else {
                    roots.add(bookmark);
                }
            }
        }
        return roots;
    }

    /**
     * Resolves a TOC page number/label to a physical page index.
     */
    private static int resolvePageIndex(String rawPage, Set<String> pageLabels) {
        int totalPages = StaticContainers.getDocument().getNumberOfPages();
        Map<String, Integer> labelToIndex = buildPageLabelMap(pageLabels);
        Integer pageIndex = labelToIndex.get(rawPage.toUpperCase(Locale.ROOT));
        if (pageIndex != null) {
            return pageIndex;
        }
        try {
            int parsed = Integer.parseInt(rawPage) - 1;
            return Math.max(0, Math.min(parsed, totalPages - 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Builds a map from page labels (and 1-based numbers) to physical page indices.
     */
    private static Map<String, Integer> buildPageLabelMap(Set<String> pageLabels) {
        Map<String, Integer> map = new HashMap<>();
        int totalPages = StaticContainers.getDocument().getNumberOfPages();
        for (int i = 0; i < totalPages; i++) {
            if (pageLabels != null) {
                for (String label : pageLabels) {
                    map.put(label.toUpperCase(Locale.ROOT), i);
                }
            }
            map.put(String.valueOf(i + 1), i);
        }
        return map;
    }

    /**
     * Recursively counts all bookmarks including children.
     */
    private static int countAllBookmarks(List<Bookmark> bookmarks) {
        int count = 0;
        for (Bookmark bookmark : bookmarks) {
            count++;
            List<Bookmark> children = bookmark.getChildren();
            if (children != null) {
                count += countAllBookmarks(children);
            }
        }
        return count;
    }

    private static class TocMatch {
        final String title;
        final String rawPage;

        TocMatch(String title, String rawPage) {
            this.title = title;
            this.rawPage = rawPage;
        }
    }

    private static class PageTocInfo {
        final int pageIndex;
        final int minTocLines;
        final double minTocRatio;
        int totalLines = 0;
        final List<TocLine> tocLines = new ArrayList<>();

        PageTocInfo(int pageIndex, int minTocLines, double minTocRatio) {
            this.pageIndex = pageIndex;
            this.minTocLines = minTocLines;
            this.minTocRatio = minTocRatio;
        }

        boolean isTocPage() {
            if (tocLines.size() < minTocLines) {
                return false;
            }
            return totalLines == 0 || (double) tocLines.size() / totalLines >= minTocRatio;
        }
    }

    private static class TocLine {
        final String title;
        final String rawPage;
        final TextLine textLine;

        TocLine(String title, String rawPage, TextLine textLine) {
            this.title = title;
            this.rawPage = rawPage;
            this.textLine = textLine;
        }
    }

    private static class PageRange {
        int startPage;
        int endPage;
        int totalTocLines;

        PageRange(int startPage, int tocLines) {
            this.startPage = startPage;
            this.endPage = startPage;
            this.totalTocLines = tocLines;
        }

        int pageCount() {
            return endPage - startPage + 1;
        }

        double score() {
            return totalTocLines * (1.0 + Math.log(1.0 + pageCount()));
        }
    }

    private static class LineInfo {
        final TextLine line;
        final String title;
        final String rawPage;
        final int pageIndex;
        final boolean isToc;

        LineInfo(TextLine line, String title, String rawPage, int pageIndex, boolean isToc) {
            this.line = line;
            this.title = title;
            this.rawPage = rawPage;
            this.pageIndex = pageIndex;
            this.isToc = isToc;
        }
    }

    private static class Candidate {
        final Bookmark bookmark;
        final double leftX;
        final double topY;
        final int pageIndex;
        int level = 1;

        Candidate(Bookmark bookmark, double leftX, double topY, int pageIndex) {
            this.bookmark = bookmark;
            this.leftX = leftX;
            this.topY = topY;
            this.pageIndex = pageIndex;
        }
    }
}
