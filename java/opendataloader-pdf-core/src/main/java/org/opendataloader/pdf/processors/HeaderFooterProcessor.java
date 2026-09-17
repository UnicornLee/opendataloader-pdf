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
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.INode;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
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
import java.util.stream.Collectors;

/**
 * Processor for detecting and extracting headers and footers from PDF documents.
 * Identifies repeating content at the top and bottom of pages.
 */
public class HeaderFooterProcessor {

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
        List<SemanticHeaderOrFooter> footers = getHeadersOrFooters(filteredSortedContents, false);
        List<SemanticHeaderOrFooter> headers = getHeadersOrFooters(filteredSortedContents, true);
        for (int pageNumber = 0; pageNumber < contents.size(); pageNumber++) {
            contents.set(pageNumber, updatePageContents(contents.get(pageNumber), headers.get(pageNumber), footers.get(pageNumber)));
        }
        if (!isTagged) {
            processHeadersOrFootersContents(footers);
            processHeadersOrFootersContents(headers);
        }
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

    private static List<SemanticHeaderOrFooter> getHeadersOrFooters(List<List<IObject>> sortedContents, boolean isHeaderDetection) {
        List<SemanticHeaderOrFooter> headersOrFooters = new ArrayList<>(sortedContents.size());
        List<Integer> numberOfHeaderOrFooterContentsForEachPage = getNumberOfHeaderOrFooterContentsForEachPage(sortedContents, isHeaderDetection);
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

    private static List<Integer> getNumberOfHeaderOrFooterContentsForEachPage(List<List<IObject>> sortedContents, boolean isHeaderDetection) {
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
            Set<Integer> newIndexes = getIndexesOfHeaderOrFootersContents(contents, isHeaderDetection);
            if (newIndexes.isEmpty()) {
                break;
            }
            for (Integer newIndex : newIndexes) {
                numberOfHeaderOrFooterContentsForEachPage.set(newIndex, currentIndex + 1);
            }
            currentIndex++;
        }
        return numberOfHeaderOrFooterContentsForEachPage;
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

    private static Set<Integer> getIndexesOfHeaderOrFootersContents(List<IObject> contents, boolean isHeaderDetection) {
        Set<Integer> result = new HashSet<>(contents.size());
        for (int pageNumber = 0; pageNumber < contents.size() - 1; pageNumber++) {
            IObject currentObject = contents.get(pageNumber);
            IObject nextObject = contents.get(pageNumber + 1);
            if (currentObject != null && nextObject != null) {
                if (arePossibleHeadersOrFooters(currentObject, nextObject, 1, isHeaderDetection)) {
                    result.add(pageNumber);
                    result.add(pageNumber + 1);
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
        for (int pageNumber = 0; pageNumber < contents.size() - 2; pageNumber++) {
            IObject currentObject = contents.get(pageNumber);
            IObject nextObject = contents.get(pageNumber + 2);
            if (currentObject != null && nextObject != null) {
                if (arePossibleHeadersOrFooters(currentObject, nextObject, 2, isHeaderDetection)) {
                    pairStarts.add(pageNumber);
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
        }
        return result;
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
