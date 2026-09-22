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

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.INode;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.enums.SemanticType;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.lists.ListInterval;
import org.verapdf.wcag.algorithms.entities.lists.ListIntervalsCollection;
import org.verapdf.wcag.algorithms.entities.lists.info.ListItemInfo;
import org.verapdf.wcag.algorithms.entities.lists.info.ListItemTextInfo;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ListLabelsUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.listLabelsDetection.*;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Processor for detecting and extracting headers and footers from PDF documents.
 * Identifies repeating content at the top and bottom of pages.
 */
public class HeaderFooterProcessor {

    private static final Logger LOGGER = Logger.getLogger(HeaderFooterProcessor.class.getCanonicalName());

    /**
     * Processes document contents to detect headers and footers.
     *
     * @param contents the document contents organized by page
     * @param isTagged whether the document is tagged
     */
    public static void processHeadersAndFooters(List<List<IObject>> contents, boolean isTagged) {
        DocumentProcessor.setIndexesForDocumentContents(contents);
        List<List<IObject>> sortedContents = new ArrayList<>();
        for (List<IObject> content : contents) {
            sortedContents.add(DocumentProcessor.sortPageContents(content));
        }
        List<List<IObject>> filteredSortedContents = new ArrayList<>();
        for (List<IObject> content : sortedContents) {
            filteredSortedContents.add(content.stream().filter(c -> !(c instanceof LineChunk) && !(c instanceof LineArtChunk) && !(c instanceof ShapeChunk)).collect(Collectors.toList()));
        }
        // Pages whose topmost candidate was matched pairwise but rejected by the repetition coverage
        // guard: they are body content that has to be kept standalone (see
        // splitRejectedPageTopCandidates). Only the header direction collects them.
        Map<Integer, IObject> rejectedPageTopCandidates = new HashMap<>();
        List<SemanticHeaderOrFooter> footers = getHeadersOrFooters(filteredSortedContents, false, rejectedPageTopCandidates);
        List<SemanticHeaderOrFooter> headers = getHeadersOrFooters(filteredSortedContents, true, rejectedPageTopCandidates);
        for (int pageNumber = 0; pageNumber < contents.size(); pageNumber++) {
            contents.set(pageNumber, updatePageContents(contents.get(pageNumber), headers.get(pageNumber), footers.get(pageNumber)));
        }
        splitRejectedPageTopCandidates(contents, rejectedPageTopCandidates);
        if (!isTagged) {
            processHeadersOrFootersContents(footers);
            processHeadersOrFootersContents(headers);
        }
    }

    /**
     * Keeps the page-top candidates that were rejected by the repetition coverage guard out of the
     * paragraph merging of {@link ParagraphProcessor}.
     *
     * <p>Such a candidate (for example the chapter title drawn between the two rules at the top of
     * every page of a prospectus) is body content, but it sits directly below the header block and
     * immediately above the first paragraph of the page. {@link ParagraphProcessor} runs after this
     * processor and merges it into that paragraph, because the separating rules are not available
     * any more — horizontal lines are dropped from the page contents long before paragraph
     * detection, so they cannot act as paragraph separators. Wrapping the line into its own
     * paragraph keeps it standalone; {@link HeadingProcessor} and the following stages then treat
     * it like any other body paragraph.</p>
     *
     * @param contents                   the document contents, one list per page (already updated
     *                                   with the detected headers and footers)
     * @param rejectedPageTopCandidates  page number to rejected page-top candidate
     */
    private static void splitRejectedPageTopCandidates(List<List<IObject>> contents,
                                                       Map<Integer, IObject> rejectedPageTopCandidates) {
        if (rejectedPageTopCandidates.isEmpty()) {
            return;
        }
        int standaloneCount = 0;
        for (Map.Entry<Integer, IObject> entry : rejectedPageTopCandidates.entrySet()) {
            IObject candidate = entry.getValue();
            if (!(candidate instanceof TextLine)) {
                // Anything that is not a text line is already ignored by the paragraph detector.
                continue;
            }
            List<IObject> pageContents = contents.get(entry.getKey());
            for (int index = 0; index < pageContents.size(); index++) {
                if (pageContents.get(index) == candidate) {
                    pageContents.set(index, createStandaloneParagraph((TextLine) candidate));
                    standaloneCount++;
                    break;
                }
            }
        }
        if (standaloneCount > 0) {
            LOGGER.log(Level.INFO, "[HeaderFooterProcessor] kept " + standaloneCount
                + " page-top candidate(s) out of paragraph merging (not repeated on enough pages "
                + "to be a header)");
        }
    }

    /**
     * Builds the paragraph the {@link ParagraphProcessor} would have built for a single-line block,
     * so a kept page-top candidate keeps the same shape as any other one-line paragraph.
     *
     * @param line the text line to keep standalone
     * @return the paragraph wrapping the given line
     */
    private static CustomSemanticParagraph createStandaloneParagraph(TextLine line) {
        return ParagraphProcessor.createParagraphFromTextBlock(new TextBlock(line));
    }

    private static void processHeadersOrFootersContents(List<SemanticHeaderOrFooter> headersOrFooters) {
        for (SemanticHeaderOrFooter headerOrFooter : headersOrFooters) {
            if (headerOrFooter != null) {
                headerOrFooter.setContents(processHeaderOrFooterContent(headerOrFooter.getContents()));
            }
        }
    }

    private static List<IObject> updatePageContents(List<IObject> pageContents, SemanticHeaderOrFooter header, SemanticHeaderOrFooter footer) {
        SortedSet<Integer> headerAndFooterIndexes = new TreeSet<>();
        headerAndFooterIndexes.addAll(getHeaderOrFooterContentsIndexes(header));
        headerAndFooterIndexes.addAll(getHeaderOrFooterContentsIndexes(footer));
        if (headerAndFooterIndexes.isEmpty()) {
            return pageContents;
        }
        List<IObject> result = new ArrayList<>();
        if (header != null) {
            result.add(header);
        }
        Iterator<Integer> iterator = headerAndFooterIndexes.iterator();
        int nextHeaderOrFooterIndex = iterator.hasNext() ? iterator.next() : pageContents.size();
        for (int index = 0; index < pageContents.size(); index++) {
            if (index < nextHeaderOrFooterIndex) {
                result.add(pageContents.get(index));
            } else {
                nextHeaderOrFooterIndex = iterator.hasNext() ? iterator.next() : pageContents.size();
            }
        }
        if (footer != null) {
            result.add(footer);
        }
        return result;
    }

    private static Set<Integer> getHeaderOrFooterContentsIndexes(SemanticHeaderOrFooter header) {
        if (header == null) {
            return Collections.emptySet();
        }
        SortedSet<Integer> set = new TreeSet<>();
        for (IObject content : header.getContents()) {
            set.add(content.getIndex());
        }
        return set;
    }

    private static List<SemanticHeaderOrFooter> getHeadersOrFooters(List<List<IObject>> sortedContents,
                                                                   boolean isHeaderDetection,
                                                                   Map<Integer, IObject> rejectedPageTopCandidates) {
        List<SemanticHeaderOrFooter> headersOrFooters = new ArrayList<>(sortedContents.size());
        List<Integer> numberOfHeaderOrFooterContentsForEachPage =
            getNumberOfHeaderOrFooterContentsForEachPage(sortedContents, isHeaderDetection, rejectedPageTopCandidates);
        for (int pageNumber = 0; pageNumber < sortedContents.size(); pageNumber++) {
            Integer currentIndex = numberOfHeaderOrFooterContentsForEachPage.get(pageNumber);
            if (currentIndex == 0) {
                headersOrFooters.add(null);
                continue;
            }
            List<IObject> pageContents = sortedContents.get(pageNumber);
            List<IObject> headerContents = filterHeaderOrFooterContents(isHeaderDetection ? pageContents.subList(0, currentIndex) :
                    pageContents.subList(pageContents.size() - currentIndex, pageContents.size()), pageNumber, isHeaderDetection);
            if (headerContents.isEmpty()) {
                headersOrFooters.add(null);
                continue;
            }
            SemanticHeaderOrFooter semanticHeaderOrFooter = new SemanticHeaderOrFooter(isHeaderDetection ? SemanticType.HEADER : SemanticType.FOOTER);
            semanticHeaderOrFooter.addContents(headerContents);
            semanticHeaderOrFooter.setRecognizedStructureId(StaticLayoutContainers.incrementContentId());
            headersOrFooters.add(semanticHeaderOrFooter);
        }
        return headersOrFooters;
    }

    private static List<IObject> processHeaderOrFooterContent(List<IObject> contents) {
        List<IObject> newContents = ParagraphProcessor.processParagraphs(contents);
        newContents = ListProcessor.processListsFromTextNodes(newContents);
        HeadingProcessor.processHeadings(newContents, false);
        DocumentProcessor.setIDs(newContents);
        CaptionProcessor.processCaptions(newContents);
        return newContents;
    }

    /**
     * Maximum vertical gap (in points) allowed between consecutive footer/header elements.
     * If a candidate element is farther than this from the previously accepted element,
     * it is not included in the header/footer region. This prevents body text that happens
     * to repeat across pages from being absorbed into the footer.
     */
    private static final double MAX_HEADER_FOOTER_GAP = 30.0;

    /**
     * Minimum share of the document's pages on which the same content has to appear before an
     * "identical value" match is accepted as a repeating header/footer.
     *
     * <p>{@link #arePossibleHeadersOrFooters} only ever compares two pages at a time, so any
     * content that happens to be identical on a few consecutive pages is accepted as a repeating
     * header. Section titles are the typical victim: in
     * {@code 201501131782598903817032205.pdf} the title drawn between the two rules at the top of
     * every page ("附錄四 長和組織文件及開曼群島公司法及稅務概要") is identical on the pages of its
     * own chapter, so 184 of the 190 pages carrying such a title lost it from the output JSON,
     * while the pages whose neighbours carry another title kept theirs.</p>
     *
     * <p>A real page header/footer repeats on most pages of the document: the "本文件為草擬本…"
     * warning of that same document covers 192/193 pages whereas its section titles cover at most
     * 28/193 = 14.5%, so requiring half of the pages separates the two cases while still accepting
     * a two-sided pattern (odd pages share one header, even pages another — exactly 50%). Values
     * that match through a list-label sequence (page numbers "1", "2", "3", …) never reach this
     * check because they are not identical-value matches.</p>
     */
    private static final double MIN_REPEATED_VALUE_PAGE_RATIO = 0.5;

    private static List<Integer> getNumberOfHeaderOrFooterContentsForEachPage(List<List<IObject>> sortedContents,
                                                                             boolean isHeaderDetection,
                                                                             Map<Integer, IObject> rejectedPageTopCandidates) {
        List<Integer> numberOfHeaderOrFooterContentsForEachPage = new ArrayList<>(sortedContents.size());
        for (int pageNumber = 0; pageNumber < sortedContents.size(); pageNumber++) {
            numberOfHeaderOrFooterContentsForEachPage.add(0);
        }
        int currentIndex = 0;
        while (true) {
            List<IObject> contents = new ArrayList<>(sortedContents.size());
            for (int pageNumber = 0; pageNumber < sortedContents.size(); pageNumber++) {
                if (numberOfHeaderOrFooterContentsForEachPage.get(pageNumber) != currentIndex) {
                    contents.add(null);
                    continue;
                }
                List<IObject> pageContents = sortedContents.get(pageNumber);
                int index = isHeaderDetection ? currentIndex : pageContents.size() - 1 - currentIndex;
                if (index >= 0 && index < pageContents.size()) {
                    IObject candidate = pageContents.get(index);
                    if (currentIndex > 0 && !isAdjacentToExistingHeaderOrFooter(
                            pageContents, currentIndex, isHeaderDetection, candidate)) {
                        contents.add(null);
                    } else {
                        contents.add(candidate);
                    }
                } else {
                    contents.add(null);
                }
            }
            LevelMatches levelMatches = getLevelMatches(contents, isHeaderDetection);
            if (isHeaderDetection) {
                // A page-top candidate that the repetition coverage guard rejected is body content
                // that must survive the paragraph merging of the following stages.
                for (Integer pageNumber : levelMatches.nonRepeatingPages) {
                    IObject candidate = contents.get(pageNumber);
                    if (candidate != null) {
                        rejectedPageTopCandidates.putIfAbsent(pageNumber, candidate);
                    }
                }
            }
            if (levelMatches.acceptedPages.isEmpty()) {
                break;
            }
            for (Integer newIndex : levelMatches.acceptedPages) {
                numberOfHeaderOrFooterContentsForEachPage.set(newIndex, currentIndex + 1);
            }
            currentIndex++;
        }
        return numberOfHeaderOrFooterContentsForEachPage;
    }

    /**
     * Matching result of one header/footer level (see {@link #getNumberOfHeaderOrFooterContentsForEachPage}).
     */
    private static final class LevelMatches {

        /** Pages whose candidate is treated as header/footer content at this level. */
        private final Set<Integer> acceptedPages = new HashSet<>();

        /**
         * Pages whose candidate matched only because it is identical to the candidate of a
         * neighbouring page, but does not repeat on enough pages of the document to be a repeating
         * header/footer (see {@link #MIN_REPEATED_VALUE_PAGE_RATIO}).
         */
        private final Set<Integer> nonRepeatingPages = new HashSet<>();
    }

    private static boolean isAdjacentToExistingHeaderOrFooter(
            List<IObject> pageContents, int currentIndex, boolean isHeaderDetection, IObject candidate) {
        int previousIndex = isHeaderDetection ? currentIndex - 1 : pageContents.size() - currentIndex;
        if (previousIndex < 0 || previousIndex >= pageContents.size()) {
            return true;
        }
        IObject previousElement = pageContents.get(previousIndex);
        double gap;
        // Use cross-page coordinates for the gap so the test stays meaningful
        // across pages with different sizes (e.g., a landscape page header
        // and a portrait page header both sit near y=44 from the top, but
        // their PDF bottom-up topY values differ by hundreds of pt).
        BoundingBox prevBox = toCrossPageCoords(previousElement.getBoundingBox(), isHeaderDetection);
        BoundingBox candBox = toCrossPageCoords(candidate.getBoundingBox(), isHeaderDetection);
        if (isHeaderDetection) {
            gap = prevBox.getBottomY() - candBox.getTopY();
        } else {
            gap = candBox.getBottomY() - prevBox.getTopY();
        }
        return gap <= MAX_HEADER_FOOTER_GAP;
    }

    /**
     * Convert a BoundingBox into coordinates that are independent of the page
     * size so cross-page-size bbox overlap checks work consistently.
     *
     * <p>Without this, a header line at y=44 from the top on a landscape page
     * (PDF topY ≈ 551 with page height 595) and on a portrait page (PDF topY ≈
     * 798 with page height 842) would not be recognized as the same header —
     * their PDF y ranges are hundreds of points apart, even though their
     * visual positions match exactly.</p>
     *
     * <p>For headers we convert PDF bottom-up y to screen top-down y (0 = page
     * top) so all pages' headers sit at the same y regardless of page height.
     * For footers we keep PDF bottom-up y because the footer distance from the
     * bottom is the same on every page (≈60 pt here) — both choices correctly
     * line up a landscape page's footer with the next portrait page's footer.
     * x is normalized to [0, 1] across the page width so a footer at x≈0.48 on a
     * wide page matches the same visual position on a narrow page.</p>
     */
    private static BoundingBox toCrossPageCoords(BoundingBox bbox, boolean isHeaderDetection) {
        if (bbox == null || bbox.getPageNumber() == null) {
            return bbox;
        }
        BoundingBox pageBox = DocumentProcessor.getPageBoundingBox(bbox.getPageNumber());
        if (pageBox == null) {
            return bbox;
        }
        double pageHeight = pageBox.getTopY() - pageBox.getBottomY();
        double pageWidth = pageBox.getRightX() - pageBox.getLeftX();
        if (pageHeight <= 0 || pageWidth <= 0) {
            return bbox;
        }
        double leftX = (bbox.getLeftX() - pageBox.getLeftX()) / pageWidth;
        double rightX = (bbox.getRightX() - pageBox.getLeftX()) / pageWidth;
        double bottomY;
        double topY;
        if (isHeaderDetection) {
            // PDF bottom-up → screen top-down (0 at page top)
            bottomY = pageHeight - bbox.getTopY();
            topY = pageHeight - bbox.getBottomY();
        } else {
            // Footer: keep PDF y as distance from page bottom; same value
            // (~60 pt) regardless of page height, so footers line up across sizes.
            bottomY = bbox.getBottomY();
            topY = bbox.getTopY();
        }
        return new BoundingBox(bbox.getPageNumber(), leftX, bottomY, rightX, topY);
    }

    /**
     * Matches the candidates of one header/footer level across the pages of the document.
     *
     * <p>Two matching styles are combined: the 1-page style (page A and page A+1) and, when the
     * tightened conditions below hold, the 2-page style (page A and page A+2) used by two-sided
     * headers/footers. The result is then filtered by the repetition coverage guard
     * ({@link #applyRepeatedValueCoverage}).</p>
     *
     * @param contents          the candidates of this level, {@code null} for the pages that did not
     *                          reach it
     * @param isHeaderDetection whether headers (true) or footers (false) are being detected
     * @return the accepted pages and the pages dropped by the coverage guard
     */
    private static LevelMatches getLevelMatches(List<IObject> contents, boolean isHeaderDetection) {
        Set<Integer> result = new HashSet<>(contents.size());
        Set<Integer> identicalValuePages = new HashSet<>(contents.size());
        for (int pageNumber = 0; pageNumber < contents.size() - 1; pageNumber++) {
            IObject currentObject = contents.get(pageNumber);
            IObject nextObject = contents.get(pageNumber + 1);
            if (currentObject != null && nextObject != null) {
                if (arePossibleHeadersOrFooters(currentObject, nextObject, 1, isHeaderDetection)) {
                    result.add(pageNumber);
                    result.add(pageNumber + 1);
                    if (isIdenticalValueMatch(currentObject, nextObject, isHeaderDetection)) {
                        identicalValuePages.add(pageNumber);
                        identicalValuePages.add(pageNumber + 1);
                    }
                }
            }
        }
        //2-page style (tightened): a single isolated 2-page pair (A, A+2) is not enough to
        //classify something as a repeating header. In 招股意向书 the text "单位：万元/吨"
        //appears at y=73.49 on PDF pages 196 and 198 — only two pages with the same body
        //layout, not a genuine odd/even pattern. We accept 2-page matches only when either:
//   (a) there are exactly 2 pair starts and their pages cover >= 50% of all pages —
//       the minimal two-sided pattern (e.g. a 4-page document with CGM / CERAGEM
//       alternating footers); or
//   (b) there are >= 3 pair starts AND every pair start participates in a chain
//       (its start+2 or start-2 is also a pair start) — i.e. a longer repeating
//       odd/even pattern with no isolated pair.
//An isolated pair like (196, 198) alone in a 765-page document fails (b) and never
//triggers (a).
        Set<Integer> pairStarts = new HashSet<>();
        Set<Integer> identicalValuePairStarts = new HashSet<>();
        for (int pageNumber = 0; pageNumber < contents.size() - 2; pageNumber++) {
            IObject currentObject = contents.get(pageNumber);
            IObject nextObject = contents.get(pageNumber + 2);
            if (currentObject != null && nextObject != null) {
                if (arePossibleHeadersOrFooters(currentObject, nextObject, 2, isHeaderDetection)) {
                    pairStarts.add(pageNumber);
                    if (isIdenticalValueMatch(currentObject, nextObject, isHeaderDetection)) {
                        identicalValuePairStarts.add(pageNumber);
                    }
                }
            }
        }
        Set<Integer> twoPageStyleMatches = new HashSet<>();
        for (Integer start : pairStarts) {
            twoPageStyleMatches.add(start);
            twoPageStyleMatches.add(start + 2);
        }
        Set<Integer> chainMembers = new HashSet<>();
        for (Integer start : pairStarts) {
            if (pairStarts.contains(start + 2) || pairStarts.contains(start - 2)) {
                chainMembers.add(start);
            }
        }
        boolean isSmallTwoSidedPattern = pairStarts.size() == 2
                && twoPageStyleMatches.size() * 2 >= contents.size();
        boolean isChainedLongPattern = pairStarts.size() >= 3
                && chainMembers.size() == pairStarts.size();
        if (isSmallTwoSidedPattern || isChainedLongPattern) {
            result.addAll(twoPageStyleMatches);
            for (Integer start : identicalValuePairStarts) {
                identicalValuePages.add(start);
                identicalValuePages.add(start + 2);
            }
        }
        LevelMatches levelMatches = new LevelMatches();
        applyRepeatedValueCoverage(contents, result, identicalValuePages, levelMatches);
        return levelMatches;
    }

    /**
     * Repetition key of a header/footer candidate: its text. Returns {@code null} for candidates
     * that carry no text (images, tables, …), which are not subject to the repetition check.
     *
     * @param content the candidate content object
     * @return the text of the candidate, or {@code null} when it does not carry text
     */
    private static String getRepetitionValue(IObject content) {
        if (content instanceof SemanticTextNode) {
            return ((SemanticTextNode) content).getValue();
        }
        if (content instanceof TextLine) {
            return ((TextLine) content).getValue();
        }
        return null;
    }

    /**
     * Checks whether a matched pair matched because both candidates carry the same payload —
     * identical text, or identical bounding boxes for candidates without text — as opposed to
     * matching through a list-label sequence such as the page numbers "1", "2", "3" of a footer.
     *
     * @param object1           the first candidate of the pair
     * @param object2           the second candidate of the pair
     * @param isHeaderDetection whether headers (true) or footers (false) are being detected
     * @return true when the pair carries the same payload
     */
    private static boolean isIdenticalValueMatch(IObject object1, IObject object2, boolean isHeaderDetection) {
        String value1 = getRepetitionValue(object1);
        String value2 = getRepetitionValue(object2);
        if (value1 != null || value2 != null) {
            return value1 != null && value1.equals(value2);
        }
        BoundingBox bbox1 = toCrossPageCoords(object1.getBoundingBox(), isHeaderDetection);
        BoundingBox bbox2 = toCrossPageCoords(object2.getBoundingBox(), isHeaderDetection);
        return BoundingBox.areSameBoundingBoxesExcludingPages(bbox1, bbox2);
    }

    /**
     * Applies the repetition coverage guard: a page whose candidate only matched because its value is
     * identical to the value on the neighbouring page is dropped when that value does not repeat on
     * enough pages of the document (see {@link #MIN_REPEATED_VALUE_PAGE_RATIO}).
     *
     * <p>Pages that matched through a list-label sequence keep the previous behaviour, so page
     * numbers and other incrementing labels are still recognised as headers/footers.</p>
     *
     * @param contents       the candidates of the current level ({@code null} for pages that did
     *                       not reach this level)
     * @param matchedPages   pages matched by the pairwise checks of this level
     * @param identicalPages pages whose match came from an identical value
     * @param levelMatches   receives the accepted pages and the pages dropped by this guard
     */
    private static void applyRepeatedValueCoverage(List<IObject> contents, Set<Integer> matchedPages,
                                                   Set<Integer> identicalPages, LevelMatches levelMatches) {
        if (identicalPages.isEmpty()) {
            levelMatches.acceptedPages.addAll(matchedPages);
            return;
        }
        int minPages = Math.max(2, (int) Math.ceil(contents.size() * MIN_REPEATED_VALUE_PAGE_RATIO));
        Map<String, Integer> pagesPerValue = new HashMap<>();
        for (IObject candidate : contents) {
            String value = candidate == null ? null : getRepetitionValue(candidate);
            if (value != null) {
                pagesPerValue.merge(value, 1, Integer::sum);
            }
        }
        for (Integer pageNumber : matchedPages) {
            if (!identicalPages.contains(pageNumber)) {
                levelMatches.acceptedPages.add(pageNumber);
                continue;
            }
            IObject candidate = contents.get(pageNumber);
            String value = candidate == null ? null : getRepetitionValue(candidate);
            if (value == null || pagesPerValue.getOrDefault(value, 0) >= minPages) {
                levelMatches.acceptedPages.add(pageNumber);
            } else {
                levelMatches.nonRepeatingPages.add(pageNumber);
            }
        }
    }

    /**
     * Checks if a content object is a header or footer.
     *
     * @param content the content object to check
     * @return true if the content is a header or footer, false otherwise
     */
    public static boolean isHeaderOrFooter(IObject content) {
        if (content instanceof INode) {
            INode node = (INode) content;
            if (node.getSemanticType() == SemanticType.HEADER || node.getSemanticType() == SemanticType.FOOTER) {
                return true;
            }
        }
        return false;
    }

    private static List<IObject> filterHeaderOrFooterContents(List<IObject> contents, int pageNumber, boolean isHeaderDetection) {
        BoundingBox boundingBox = DocumentProcessor.getPageBoundingBox(pageNumber);
        if (boundingBox == null) {
            return contents;
        }
        List<IObject> result = new ArrayList<>();
        for (IObject content : contents) {
            if (isHeaderDetection) {
                if (content.getBottomY() < boundingBox.getHeight() * 2 / 3) {
                    continue;
                }
            } else {
                if (content.getTopY() > boundingBox.getHeight() / 3) {
                    continue;
                }
            }
            result.add(content);
        }
        return result;
    }

    private static boolean arePossibleHeadersOrFooters(IObject object1, IObject object2, int increment, boolean isHeaderDetection) {
        // Compare bounding boxes in coordinates that are independent of page
        // size so a header line that visually sits near y=44 from the top can
        // match across landscape and portrait pages regardless of PDF page height.
        BoundingBox bbox1 = toCrossPageCoords(object1.getBoundingBox(), isHeaderDetection);
        BoundingBox bbox2 = toCrossPageCoords(object2.getBoundingBox(), isHeaderDetection);
        if (object1 instanceof SemanticTextNode && object2 instanceof SemanticTextNode) {
            SemanticTextNode textNode1 = (SemanticTextNode) object1;
            SemanticTextNode textNode2 = (SemanticTextNode) object2;
            if (!BoundingBox.areOverlapsBoundingBoxesExcludingPages(bbox1, bbox2)) {
                return false;
            }
            if (!NodeUtils.areCloseNumbers(textNode1.getFontSize(), textNode2.getFontSize())) {
                return false;
            }
            if (Objects.equals(textNode1.getValue(), textNode2.getValue())) {
                return true;
            }
            List<SemanticTextNode> textNodes = new ArrayList<>(2);
            textNodes.add(textNode1);
            textNodes.add(textNode2);
            if (getHeadersOrFootersIntervals(textNodes, increment).size() == 1) {
                return true;
            }
        } else if (object1 instanceof TextLine && object2 instanceof TextLine) {
            TextLine line1 = (TextLine) object1;
            TextLine line2 = (TextLine) object2;
            SemanticTextNode textNode1 = new SemanticTextNode();
            textNode1.add(line1);
            SemanticTextNode textNode2 = new SemanticTextNode();
            textNode2.add(line2);
            return arePossibleHeadersOrFooters(textNode1, textNode2, increment, isHeaderDetection);
        } else {
            if (BoundingBox.areSameBoundingBoxesExcludingPages(bbox1, bbox2)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ListInterval> getHeadersOrFootersIntervals(List<SemanticTextNode> textNodes, int increment) {
        List<ListItemTextInfo> textChildrenInfo = new ArrayList<>(textNodes.size());
        for (int i = 0; i < textNodes.size(); i++) {
            SemanticTextNode textNode = textNodes.get(i);
            TextLine line = textNode.getFirstNonSpaceLine();
            TextLine secondLine = textNode.getNonSpaceLine(1);
            textChildrenInfo.add(new ListItemTextInfo(i, textNode.getSemanticType(),
                    line, line.getValue().trim(), secondLine == null));
        }
        Set<ListInterval> intervals = getHeadersOfFooterIntervals(textChildrenInfo, increment);
        return intervals;
    }

    private static Set<ListInterval> getHeadersOfFooterIntervals(List<ListItemTextInfo> itemsInfo, int increment) {
        ListIntervalsCollection listIntervals = new ListIntervalsCollection();
        listIntervals.putAll((new AlfaLettersListLabelsDetectionAlgorithm1(increment)).getItemsIntervals(itemsInfo));
        listIntervals.putAll((new AlfaLettersListLabelsDetectionAlgorithm2(increment)).getItemsIntervals(itemsInfo));
        listIntervals.putAll((new KoreanLettersListLabelsDetectionAlgorithm(increment)).getItemsIntervals(itemsInfo));
        listIntervals.putAll((new RomanNumbersListLabelsDetectionAlgorithm(increment)).getItemsIntervals(itemsInfo));
        ArabicNumbersListLabelsDetectionAlgorithm arabicNumbersListLabelsDetectionAlgorithm = new ArabicNumbersListLabelsDetectionAlgorithm(increment);
        arabicNumbersListLabelsDetectionAlgorithm.setHeaderOrFooterDetection(true);
        listIntervals.putAll((arabicNumbersListLabelsDetectionAlgorithm).getItemsIntervals(itemsInfo));
        ListIntervalsCollection correctIntervals = new ListIntervalsCollection(getEqualsItems(itemsInfo));
        for (ListInterval listInterval : listIntervals.getSet()) {
            List<String> labels = new LinkedList<>();
            for (ListItemInfo info : listInterval.getListItemsInfos()) {
                labels.add(((ListItemTextInfo) info).getListItem());
            }
            if (ListLabelsUtils.isListLabels(labels, increment)) {
                correctIntervals.put(listInterval);
            }
        }
        return correctIntervals.getSet();
    }

    private static Set<ListInterval> getEqualsItems(List<ListItemTextInfo> itemsInfo) {
        Set<ListInterval> listIntervals = new HashSet<>();
        String value = null;
        ListInterval interval = new ListInterval();
        for (ListItemTextInfo info : itemsInfo) {
            if (!Objects.equals(info.getListItem(), value)) {
                if (interval.getNumberOfListItems() > 1) {
                    listIntervals.add(interval);
                }
                value = info.getListItem();
                interval = new ListInterval();
            }
            interval.getListItemsInfos().add(info);
        }
        if (interval.getNumberOfListItems() > 1) {
            listIntervals.add(interval);
        }
        return listIntervals;
    }
}
