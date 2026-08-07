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
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.json.JsonName;
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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * Result of catalog bookmark extraction, including the detected page range.
     */
    public static final class CatalogResult {
        private final List<Bookmark> bookmarks;
        private final int startPage;
        private final int endPage;

        public CatalogResult(List<Bookmark> bookmarks, int startPage, int endPage) {
            this.bookmarks = bookmarks != null ? bookmarks : Collections.emptyList();
            this.startPage = startPage;
            this.endPage = endPage;
        }

        public List<Bookmark> getBookmarks() {
            return bookmarks;
        }

        public int getStartPage() {
            return startPage;
        }

        public int getEndPage() {
            return endPage;
        }
    }

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

        StaticLayoutContainers.setCatalogBookmarkPageRange(bestRange.startPage, bestRange.endPage);

        List<Bookmark> roots = extractBookmarks(contents, bestRange, pageLabels);
        int total = countAllBookmarks(roots);
        LOGGER.log(Level.INFO,
                "[CatalogBookmark] extracted {0} bookmarks ({1} top-level) from range {2}-{3}",
                new Object[]{total, roots.size(), bestRange.startPage + 1, bestRange.endPage + 1});

        return roots;
    }

    /**
     * Detects the catalog/TOC page range and extracts bookmarks from the JSON data
     * produced by {@link org.opendataloader.pdf.json.JsonWriter}.
     *
     * @param data per-page JSON data array
     * @param config processing configuration; thresholds are read from it
     * @return catalog extraction result with bookmarks and detected page range
     */
    public static CatalogResult extractCatalogBookmarksFromJson(List<Map<String, Object>> data, Config config) {
        if (data == null || data.isEmpty()) {
            return new CatalogResult(Collections.emptyList(), -1, -1);
        }

        Set<String> pageLabels = collectPageLabels();

        int minTocLines = config != null ? config.getCatalogBookmarkMinTocLines() : 3;
        double minTocRatio = config != null ? config.getCatalogBookmarkMinTocRatio() : 0.4;

        List<JsonPageTocInfo> pageInfos = analyzeJsonPages(data, pageLabels, minTocLines, minTocRatio);
        List<JsonPageRange> ranges = detectJsonRanges(pageInfos);
        JsonPageRange bestRange = selectBestJsonRange(ranges);

        if (bestRange == null) {
            LOGGER.log(Level.INFO, "[CatalogBookmark] no catalog page range detected from JSON");
            return new CatalogResult(Collections.emptyList(), -1, -1);
        }

        LOGGER.log(Level.INFO,
                "[CatalogBookmark] detected catalog page range from JSON: {0}-{1} ({2} pages, {3} toc items)",
                new Object[]{bestRange.startPage + 1, bestRange.endPage + 1,
                        bestRange.pageCount(), bestRange.totalTocLines});

        List<Bookmark> roots = extractBookmarksFromJson(data, bestRange, pageLabels);
        resolveCatalogBookmarkTargets(roots, data, bestRange.startPage, bestRange.endPage);
        int total = countAllBookmarks(roots);
        LOGGER.log(Level.INFO,
                "[CatalogBookmark] extracted {0} bookmarks ({1} top-level) from JSON range {2}-{3}",
                new Object[]{total, roots.size(), bestRange.startPage + 1, bestRange.endPage + 1});

        return new CatalogResult(roots, bestRange.startPage, bestRange.endPage);
    }

    /**
     * Complements missing sub-bookmarks in the catalog tree by re-running the
     * page bookmark candidate pipeline below each catalog anchor.
     *
     * <p>Only catalog nodes whose {@code page_num}/{@code related_id} anchor
     * exists in the page bookmark tree are considered; front-matter entries with
     * no page anchor (e.g. 本次发行概况 / 重要声明 / 目 录) are left untouched.
     * A catalog L1 without children gets its L2 children re-derived at level 2;
     * a catalog L2 without children gets its L3 children re-derived at level 3.
     * Existing catalog children are preserved, so the catalog keeps its own
     * detected L1/L2 structure and the tree never grows beyond three levels.
     * The slice source is the raw candidate set (before cleaning), so the
     * appended nodes are freshly built objects independent of the page bookmark
     * tree, yet node-for-node consistent with it.</p>
     *
     * @param data per-page JSON data array
     * @param catalogStartPage 0-based inclusive start of catalog page range, or -1
     * @param catalogEndPage 0-based inclusive end of catalog page range, or -1
     * @param catalogBookmarks catalog bookmark roots, mutated in place
     * @param pageBookmarks page bookmark roots used for anchor resolution
     */
    public static void fillCatalogChildrenFromPageData(
            List<Map<String, Object>> data,
            int catalogStartPage, int catalogEndPage,
            List<Bookmark> catalogBookmarks,
            List<Bookmark> pageBookmarks) {
        if (data == null || data.isEmpty()
                || catalogBookmarks == null || catalogBookmarks.isEmpty()
                || pageBookmarks == null || pageBookmarks.isEmpty()) {
            return;
        }

        Map<BookmarkKey, Bookmark> pageIndex = indexBookmarks(pageBookmarks);
        int complemented = 0;

        for (Bookmark catalogTop : catalogBookmarks) {
            Bookmark pageTop = pageIndex.get(new BookmarkKey(catalogTop));
            if (pageTop == null || pageTop.getChildren().isEmpty()) {
                continue;
            }

            if (catalogTop.getChildren().isEmpty()) {
                // The catalog lost the whole subtree below this anchor: rebuild
                // the L2 children from the anchor range.
                List<Bookmark> built = PageBookmarkProcessor.extractChildrenForAnchor(
                    data, catalogStartPage, catalogEndPage,
                    anchorPage(catalogTop), anchorRelatedId(catalogTop), 2);
                if (!built.isEmpty()) {
                    catalogTop.getChildren().addAll(built);
                    complemented++;
                }
                continue;
            }

            for (Bookmark catalogChild : catalogTop.getChildren()) {
                Bookmark pageChild = pageIndex.get(new BookmarkKey(catalogChild));
                if (pageChild == null || pageChild.getChildren().isEmpty()
                        || !catalogChild.getChildren().isEmpty()) {
                    continue;
                }
                List<Bookmark> built = PageBookmarkProcessor.extractChildrenForAnchor(
                    data, catalogStartPage, catalogEndPage,
                    anchorPage(catalogChild), anchorRelatedId(catalogChild), 3);
                if (!built.isEmpty()) {
                    catalogChild.getChildren().addAll(built);
                    complemented++;
                }
            }
        }

        LOGGER.log(Level.INFO,
            "[CatalogBookmark] complemented {0} catalog bookmark group(s) from page data",
            complemented);
    }

    /**
     * Builds a deep (page_num, related_id) to bookmark index of the page tree so
     * catalog anchors can be resolved against any level.
     */
    private static Map<BookmarkKey, Bookmark> indexBookmarks(List<Bookmark> roots) {
        Map<BookmarkKey, Bookmark> index = new HashMap<>();
        for (Bookmark root : roots) {
            indexBookmarkDeep(index, root);
        }
        return index;
    }

    private static void indexBookmarkDeep(Map<BookmarkKey, Bookmark> index, Bookmark bookmark) {
        if (bookmark == null) {
            return;
        }
        index.put(new BookmarkKey(bookmark), bookmark);
        List<Bookmark> children = bookmark.getChildren();
        if (children != null) {
            for (Bookmark child : children) {
                indexBookmarkDeep(index, child);
            }
        }
    }

    private static int anchorPage(Bookmark bookmark) {
        return bookmark == null || bookmark.getPageNum() == null ? 0 : bookmark.getPageNum();
    }

    private static int anchorRelatedId(Bookmark bookmark) {
        return bookmark == null || bookmark.getRelatedId() == null ? 0 : bookmark.getRelatedId();
    }

    private static final class BookmarkKey {
        final int page;
        final int relatedId;

        BookmarkKey(Bookmark bookmark) {
            this(anchorPage(bookmark), anchorRelatedId(bookmark));
        }

        BookmarkKey(int page, int relatedId) {
            this.page = page;
            this.relatedId = relatedId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BookmarkKey)) {
                return false;
            }
            BookmarkKey that = (BookmarkKey) o;
            return page == that.page && relatedId == that.relatedId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(page, relatedId);
        }
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

                if (isCatalogSelfReference(title)) {
                    Bookmark bookmark = new Bookmark();
                    bookmark.setText(title);
                    bookmark.setOriginalPageNum(parseOriginalPageNum(info.rawPage));
                    bookmark.setPageNum(range.startPage + 1);
                    bookmark.setFontSize((float) info.line.getFontSize());
                    bookmark.setSingleLine(true);
                    bookmark.setChildren(new ArrayList<>());
                    candidates.add(new Candidate(bookmark, info.line.getLeftX(), info.line.getTopY(), info.pageIndex));
                    continue;
                }

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
        return "Contents".equalsIgnoreCase(compact) || "TableofContents".equalsIgnoreCase(compact);
    }

    /**
     * Returns true for headings that refer to the catalog itself (e.g. "目录").
     * These are kept as bookmarks but point to the catalog page range start.
     */
    private static boolean isCatalogSelfReference(String title) {
        String compact = title.replaceAll("\\s+", "");
        return "目录".equals(compact) || "目錄".equals(compact);
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
    /** Writes catalog paragraphs immediately after paragraph collection and before heading processing. */
    public static void writeCollectedCatalogMarkdown(List<List<IObject>> contents, String inputPdfName,
                                                      Config config) {
        Path outputFolder = Path.of(config.getOutputFolder());
        String inputName = Path.of(inputPdfName).getFileName().toString();
        String stem = inputName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? inputName.substring(0, inputName.length() - 4) : inputName;
        Path output = outputFolder.resolve(stem + "_page_bookmarks_collected.md");
        StringBuilder markdown = new StringBuilder("# 收集到的页面目录候选（CustomSemanticParagraph）\n\n");
        markdown.append("| 所在页码 | 目录内容 |\n|---:|---|\n");
        int count = 0;
        int catalogStart = StaticLayoutContainers.getCatalogBookmarkStartPage();
        int catalogEnd = StaticLayoutContainers.getCatalogBookmarkEndPage();
        for (int page = 0; page < contents.size(); page++) {
            if (catalogStart >= 0 && catalogEnd >= catalogStart
                    && page >= catalogStart && page <= catalogEnd) {
                continue;
            }
            List<IObject> pageContents = contents.get(page);
            if (pageContents == null) continue;
            for (IObject object : pageContents) {
                if (!(object instanceof CustomSemanticParagraph)) continue;
                CustomSemanticParagraph paragraph = (CustomSemanticParagraph) object;
                List<TextLine> textLines = paragraph.getTextLines();
                if (textLines.isEmpty() || textLines.get(0) == null
                        || !PageBookmarkProcessor.isBookmarkCandidate(textLines.get(0).getValue())) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (TextLine line : textLines) {
                    if (line != null && line.getValue() != null) {
                        if (text.length() > 0) text.append(' ');
                        text.append(line.getValue().trim());
                    }
                }
                String value = text.toString().trim();
                if (value.isEmpty()) continue;
                markdown.append('|').append(page + 1).append('|')
                        .append(value.replace("|", "\\|")).append('|').append('\n');
                count++;
            }
        }
        markdown.insert(0, "<!-- entries: " + count + " -->\n");
        try {
            Files.createDirectories(outputFolder);
            Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, "[CatalogBookmark] wrote CustomSemanticParagraph catalog: {0}", output);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[CatalogBookmark] unable to write collected catalog: " + output, e);
        }
    }

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

    // ---------- JSON-based catalog bookmark extraction ----------

    private static List<JsonPageTocInfo> analyzeJsonPages(List<Map<String, Object>> data,
                                                          Set<String> pageLabels,
                                                          int minTocLines, double minTocRatio) {
        List<JsonPageTocInfo> infos = new ArrayList<>(data.size());
        for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
            Map<String, Object> page = data.get(pageIndex);
            JsonPageTocInfo info = new JsonPageTocInfo(pageIndex, minTocLines, minTocRatio);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
            if (items == null) {
                infos.add(info);
                continue;
            }
            for (Map<String, Object> item : items) {
                if (!isTextItem(item)) {
                    continue;
                }
                for (JsonTextLine line : getJsonItemLines(item)) {
                    if (line.text.isEmpty()) {
                        continue;
                    }
                    info.totalLines++;
                    if (matchTocLine(line.text, pageLabels) != null) {
                        info.tocLineCount++;
                    }
                }
            }
            infos.add(info);
        }
        return infos;
    }

    private static boolean isTextItem(Map<String, Object> item) {
        String itemType = (String) item.get(JsonName.ITEM_TYPE);
        return "text".equals(itemType);
    }

    private static List<JsonPageRange> detectJsonRanges(List<JsonPageTocInfo> pageInfos) {
        List<JsonPageRange> ranges = new ArrayList<>();
        JsonPageRange current = null;
        for (JsonPageTocInfo info : pageInfos) {
            if (info.isTocPage()) {
                if (current == null) {
                    current = new JsonPageRange(info.pageIndex, info.tocLineCount);
                } else if (info.pageIndex == current.endPage + 1) {
                    current.endPage = info.pageIndex;
                    current.totalTocLines += info.tocLineCount;
                } else {
                    ranges.add(current);
                    current = new JsonPageRange(info.pageIndex, info.tocLineCount);
                }
            }
        }
        if (current != null) {
            ranges.add(current);
        }
        return ranges;
    }

    private static JsonPageRange selectBestJsonRange(List<JsonPageRange> ranges) {
        if (ranges.isEmpty()) {
            return null;
        }
        JsonPageRange best = ranges.get(0);
        double bestScore = best.score();
        for (int i = 1; i < ranges.size(); i++) {
            JsonPageRange candidate = ranges.get(i);
            double candidateScore = candidate.score();
            if (candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        return best;
    }

    private static List<Bookmark> extractBookmarksFromJson(List<Map<String, Object>> data,
                                                           JsonPageRange range,
                                                           Set<String> pageLabels) {
        List<JsonLineInfo> allLines = collectJsonLines(data, range, pageLabels);
        if (allLines.isEmpty()) {
            return Collections.emptyList();
        }

        allLines.sort(Comparator
            .comparingInt((JsonLineInfo l) -> l.pageIndex)
            .thenComparing((JsonLineInfo l) -> -l.topY));

        List<Candidate> candidates = new ArrayList<>();
        StringBuilder pendingTitle = new StringBuilder();
        JsonLineInfo pendingLine = null;

        for (JsonLineInfo info : allLines) {
            if (info.isToc) {
                String title = info.title;
                if (pendingTitle.length() > 0 && pendingLine != null
                        && isJsonContinuation(pendingLine, info)) {
                    title = pendingTitle.toString() + title;
                }
                pendingTitle.setLength(0);
                pendingLine = null;

                if (isCatalogSelfReference(title)) {
                    Bookmark bookmark = new Bookmark();
                    bookmark.setText(title);
                    bookmark.setOriginalPageNum(parseOriginalPageNum(info.rawPage));
                    bookmark.setPageNum(range.startPage + 1);
                    bookmark.setFontSize((float) info.fontSize);
                    bookmark.setSingleLine(true);
                    bookmark.setRelatedId(info.relatedId);
                    bookmark.setChildren(new ArrayList<>());
                    candidates.add(new Candidate(bookmark, info.leftX, info.topY, info.pageIndex, info.relatedId));
                    continue;
                }

                if (isTocHeading(title)) {
                    continue;
                }

                Bookmark bookmark = new Bookmark();
                bookmark.setText(title);
                bookmark.setOriginalPageNum(parseOriginalPageNum(info.rawPage));
                bookmark.setPageNum(resolvePageIndex(info.rawPage, pageLabels) + 1);
                bookmark.setFontSize((float) info.fontSize);
                bookmark.setSingleLine(true);
                bookmark.setRelatedId(info.relatedId);
                bookmark.setChildren(new ArrayList<>());
                candidates.add(new Candidate(bookmark, info.leftX, info.topY, info.pageIndex, info.relatedId));
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

    private static List<JsonLineInfo> collectJsonLines(List<Map<String, Object>> data,
                                                       JsonPageRange range,
                                                       Set<String> pageLabels) {
        List<JsonLineInfo> allLines = new ArrayList<>();
        for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
            Map<String, Object> page = data.get(pageIndex);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
            if (items == null) {
                continue;
            }
            for (Map<String, Object> item : items) {
                if (!isTextItem(item)) {
                    continue;
                }
                for (JsonTextLine line : getJsonItemLines(item)) {
                    if (line.text.isEmpty()) {
                        continue;
                    }
                    TocMatch match = matchTocLine(line.text, pageLabels);
                    if (match != null) {
                        allLines.add(new JsonLineInfo(match.title, match.rawPage, pageIndex, true,
                            line.leftX, line.topY, line.fontSize, line.relatedId));
                    } else {
                        allLines.add(new JsonLineInfo(line.text, null, pageIndex, false,
                            line.leftX, line.topY, line.fontSize, line.relatedId));
                    }
                }
            }
        }
        return allLines;
    }

    private static boolean isJsonContinuation(JsonLineInfo pending, JsonLineInfo toc) {
        if (pending.pageIndex != toc.pageIndex) {
            return false;
        }
        double fontSize = toc.fontSize;
        double leftDelta = Math.abs(pending.leftX - toc.leftX);
        double fontDelta = Math.abs(pending.fontSize - fontSize);
        double verticalGap = pending.topY - toc.topY;
        return leftDelta <= CONTINUATION_LEFT_X_DELTA + fontSize * 0.5
            && fontDelta <= 0.5
            && verticalGap >= 0
            && verticalGap <= CONTINUATION_VERTICAL_GAP + fontSize;
    }

    private static String getJsonItemFullText(Map<String, Object> item) {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object lineObj : (List<?>) contentObj) {
            if (lineObj instanceof Map) {
                Object textListObj = ((Map<?, ?>) lineObj).get(JsonName.CONTENT);
                if (textListObj instanceof List) {
                    for (Object t : (List<?>) textListObj) {
                        if (text.length() > 0) {
                            text.append(' ');
                        }
                        text.append(t);
                    }
                }
            } else {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(lineObj);
            }
        }
        return text.toString().trim();
    }

    /**
     * Returns each text line inside a JSON item as a separate line, preserving
     * per-line coordinates. This mirrors the old TextLine-based logic so that TOC
     * entries are not concatenated into one paragraph.
     */
    private static List<JsonTextLine> getJsonItemLines(Map<String, Object> item) {
        List<JsonTextLine> lines = new ArrayList<>();
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return lines;
        }
        double itemLeftX = getJsonItemDouble(item, JsonName.X0);
        // JSON y0 increases downward; negate so the existing descending-topY sort
        // produces top-to-bottom reading order.
        double itemTopY = -getJsonItemDouble(item, JsonName.Y0);
        double itemFontSize = getJsonItemDouble(item, JsonName.FONT_UNDERLINE_SIZE);
        Object idObj = item.get(JsonName.ID);
        int relatedId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;

        for (Object lineObj : (List<?>) contentObj) {
            if (!(lineObj instanceof Map)) {
                continue;
            }
            Map<?, ?> lineMap = (Map<?, ?>) lineObj;
            Object textListObj = lineMap.get(JsonName.CONTENT);
            if (!(textListObj instanceof List)) {
                continue;
            }
            StringBuilder lineText = new StringBuilder();
            for (Object t : (List<?>) textListObj) {
                if (lineText.length() > 0) {
                    lineText.append(' ');
                }
                lineText.append(t);
            }
            String text = lineText.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            double leftX = lineMap.containsKey(JsonName.X0)
                ? getDouble(lineMap, JsonName.X0) : itemLeftX;
            double topY = lineMap.containsKey(JsonName.Y0)
                ? -getDouble(lineMap, JsonName.Y0) : itemTopY;
            double fontSize = lineMap.containsKey(JsonName.FONT_UNDERLINE_SIZE)
                ? getDouble(lineMap, JsonName.FONT_UNDERLINE_SIZE) : itemFontSize;
            lines.add(new JsonTextLine(text, leftX, topY, fontSize, relatedId));
        }
        return lines;
    }

    private static double getDouble(Map<?, ?> map, String key) {
        Object value = map.get(key);
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
     * Resolves each catalog bookmark to its actual destination page and JSON item.
     *
     * <p>Catalog entries record the printed page number from the TOC, but the
     * physical page in the PDF may differ (e.g. cover pages shift the body pages).
     * This method searches non-catalog pages for the item whose text matches the
     * bookmark title and is closest to the printed page number. When the title
     * appears multiple times, the occurrence closest to the printed page is chosen;
     * if two occurrences are equally close, the earlier page wins.</p>
     */
    private static void resolveCatalogBookmarkTargets(List<Bookmark> bookmarks,
                                                      List<Map<String, Object>> data,
                                                      int catalogStartPage,
                                                      int catalogEndPage) {
        if (bookmarks == null || bookmarks.isEmpty() || data == null || data.isEmpty()) {
            return;
        }
        for (Bookmark bookmark : bookmarks) {
            resolveCatalogBookmarkTarget(bookmark, data, catalogStartPage, catalogEndPage);
            List<Bookmark> children = bookmark.getChildren();
            if (children != null && !children.isEmpty()) {
                resolveCatalogBookmarkTargets(children, data, catalogStartPage, catalogEndPage);
            }
        }
    }

    private static void resolveCatalogBookmarkTarget(Bookmark bookmark,
                                                     List<Map<String, Object>> data,
                                                     int catalogStartPage,
                                                     int catalogEndPage) {
        String title = bookmark.getText();
        Integer catalogHint = bookmark.getOriginalPageNum();
        if (title == null || title.trim().isEmpty() || catalogHint == null || catalogHint <= 0) {
            return;
        }

        if (isCatalogSelfReference(title)) {
            return;
        }

        String normalizedTitle = normalizeBookmarkText(title);
        if (normalizedTitle.isEmpty()) {
            return;
        }

        TargetMatch bestMatch = null;
        for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
            if (catalogStartPage >= 0 && catalogEndPage >= catalogStartPage
                    && pageIndex >= catalogStartPage && pageIndex <= catalogEndPage) {
                continue;
            }
            Map<String, Object> page = data.get(pageIndex);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
            if (items == null) {
                continue;
            }
            for (Map<String, Object> item : items) {
                if (!isTextItem(item)) {
                    continue;
                }
                String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
                if (!JsonName.SOURCE_TYPE_HEADING.equals(sourceType)
                        && !JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)) {
                    continue;
                }
                String itemText = getJsonItemFullText(item);
                MatchQuality quality = matchBookmarkTitle(normalizedTitle, itemText);
                if (quality == null) {
                    continue;
                }
                int physicalPage = pageIndex + 1;
                int distance = Math.abs(physicalPage - catalogHint);
                if (bestMatch == null
                        || distance < bestMatch.distance
                        || (distance == bestMatch.distance && pageIndex < bestMatch.pageIndex)
                        || (distance == bestMatch.distance && pageIndex == bestMatch.pageIndex
                                && quality.ordinal() < bestMatch.quality.ordinal())) {
                    Object idObj = item.get(JsonName.ID);
                    int relatedId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
                    bestMatch = new TargetMatch(pageIndex, relatedId, distance, quality);
                }
            }
        }

        if (bestMatch != null) {
            bookmark.setPageNum(bestMatch.pageIndex + 1);
            bookmark.setRelatedId(bestMatch.relatedId);
        }
    }

    /**
     * Resolves the {@code related_id} of each self bookmark (and its children)
     * to the id of the JSON text item on the bookmark's page whose text matches
     * the bookmark title. Bookmarks whose page has no matching item keep the
     * default related id (0).
     *
     * @param bookmarks the self bookmark tree
     * @param data      per-page JSON data with items
     */
    public static void resolveSelfBookmarkRelatedIds(List<Bookmark> bookmarks,
                                                     List<Map<String, Object>> data) {
        if (bookmarks == null || bookmarks.isEmpty() || data == null || data.isEmpty()) {
            return;
        }
        for (Bookmark bookmark : bookmarks) {
            resolveSelfBookmarkRelatedId(bookmark, data);
            List<Bookmark> children = bookmark.getChildren();
            if (children != null && !children.isEmpty()) {
                resolveSelfBookmarkRelatedIds(children, data);
            }
        }
    }

    private static void resolveSelfBookmarkRelatedId(Bookmark bookmark,
                                                     List<Map<String, Object>> data) {
        Integer pageNum = bookmark.getPageNum();
        if (pageNum == null || pageNum <= 0 || pageNum > data.size()) {
            return;
        }
        String title = bookmark.getText();
        if (title == null || title.trim().isEmpty()) {
            return;
        }
        String normalizedTitle = normalizeBookmarkText(title);
        if (normalizedTitle.isEmpty()) {
            return;
        }
        Map<String, Object> page = data.get(pageNum - 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
        if (items == null) {
            return;
        }
        int bestRelatedId = 0;
        MatchQuality bestQuality = null;
        for (Map<String, Object> item : items) {
            if (!isTextItem(item)) {
                continue;
            }
            String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
            if (!JsonName.SOURCE_TYPE_HEADING.equals(sourceType)
                    && !JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)) {
                continue;
            }
            String itemText = getJsonItemFullText(item);
            MatchQuality quality = matchBookmarkTitle(normalizedTitle, itemText);
            if (quality == null) {
                continue;
            }
            if (bestQuality == null || quality.ordinal() < bestQuality.ordinal()) {
                Object idObj = item.get(JsonName.ID);
                int relatedId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
                bestQuality = quality;
                bestRelatedId = relatedId;
            }
        }
        if (bestRelatedId != 0) {
            bookmark.setRelatedId(bestRelatedId);
        }
    }

    private static MatchQuality matchBookmarkTitle(String normalizedTitle, String itemText) {
        if (itemText == null || itemText.isEmpty()) {
            return null;
        }
        String normalized = normalizeBookmarkText(itemText);
        if (normalized.equals(normalizedTitle)) {
            return MatchQuality.EXACT;
        }
        if (normalized.startsWith(normalizedTitle)) {
            return MatchQuality.PREFIX;
        }
        return null;
    }

    private static String normalizeBookmarkText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").trim();
    }

    private enum MatchQuality {
        EXACT, PREFIX, CONTAINS
    }

    private static final class TargetMatch {
        final int pageIndex;
        final int relatedId;
        final int distance;
        final MatchQuality quality;

        TargetMatch(int pageIndex, int relatedId, int distance, MatchQuality quality) {
            this.pageIndex = pageIndex;
            this.relatedId = relatedId;
            this.distance = distance;
            this.quality = quality;
        }
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
        final int relatedId;
        int level = 1;

        Candidate(Bookmark bookmark, double leftX, double topY, int pageIndex) {
            this(bookmark, leftX, topY, pageIndex, 0);
        }

        Candidate(Bookmark bookmark, double leftX, double topY, int pageIndex, int relatedId) {
            this.bookmark = bookmark;
            this.leftX = leftX;
            this.topY = topY;
            this.pageIndex = pageIndex;
            this.relatedId = relatedId;
        }
    }

    private static class JsonPageTocInfo {
        final int pageIndex;
        final int minTocLines;
        final double minTocRatio;
        int totalLines = 0;
        int tocLineCount = 0;

        JsonPageTocInfo(int pageIndex, int minTocLines, double minTocRatio) {
            this.pageIndex = pageIndex;
            this.minTocLines = minTocLines;
            this.minTocRatio = minTocRatio;
        }

        boolean isTocPage() {
            if (tocLineCount < minTocLines) {
                return false;
            }
            return totalLines == 0 || (double) tocLineCount / totalLines >= minTocRatio;
        }
    }

    private static class JsonPageRange {
        int startPage;
        int endPage;
        int totalTocLines;

        JsonPageRange(int startPage, int tocLines) {
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

    private static class JsonLineInfo {
        final String title;
        final String rawPage;
        final int pageIndex;
        final boolean isToc;
        final double leftX;
        final double topY;
        final double fontSize;
        final int relatedId;

        JsonLineInfo(String title, String rawPage, int pageIndex, boolean isToc,
                     double leftX, double topY, double fontSize, int relatedId) {
            this.title = title;
            this.rawPage = rawPage;
            this.pageIndex = pageIndex;
            this.isToc = isToc;
            this.leftX = leftX;
            this.topY = topY;
            this.fontSize = fontSize;
            this.relatedId = relatedId;
        }
    }

    private static class JsonTextLine {
        final String text;
        final double leftX;
        final double topY;
        final double fontSize;
        final int relatedId;

        JsonTextLine(String text, double leftX, double topY, double fontSize, int relatedId) {
            this.text = text;
            this.leftX = leftX;
            this.topY = topY;
            this.fontSize = fontSize;
            this.relatedId = relatedId;
        }
    }
}
