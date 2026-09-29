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
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processor for filtering and cleaning PDF content.
 * Removes hidden text, out-of-page content, backgrounds, and other artifacts.
 */
public class ContentFilterProcessor {

    private static final Logger LOGGER = Logger.getLogger(ContentFilterProcessor.class.getCanonicalName());

    /**
     * Minimum share of the page height a decoration has to cover to count as a
     * page-length strip (see {@link #isBackground}).
     */
    private static final double BACKGROUND_STRIP_MIN_HEIGHT_RATIO = 0.5;
    /**
     * Minimum share of the page width a page-length strip has to cover. Kept low: the
     * strips that matter are narrow margin rules and sidebar bands.
     */
    private static final double BACKGROUND_STRIP_MIN_WIDTH_RATIO = 0.05;

    /**
     * Filters and cleans page contents based on configuration.
     *
     * @param inputPdfName the path to the PDF file
     * @param contents the raw page contents
     * @param pageNumber the page number (0-indexed)
     * @param config the configuration settings
     * @return the filtered list of content objects
     * @throws IOException if unable to process the content
     */
    public static List<IObject> getFilteredContents(String inputPdfName, List<IChunk> contents, int pageNumber,
                                                    Config config) throws IOException {
        List<IObject> pageContents = new ArrayList<>(contents);
        // Drop text the PDF painted several times on top of itself (fake-bold overprint). Runs
        // before removeSameTextChunks because it works on the per-symbol geometry, which the
        // value-equality based passes cannot use once the copies were re-chunked differently.
        TextProcessor.removeOverprintedTextChunks(pageContents);
        pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        TextProcessor.removeSameTextChunks(pageContents);
        pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        TextProcessor.removeTextDecorationImages(pageContents);
        pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        if (config.getFilterConfig().isFilterTinyText()) {
            TextProcessor.filterTinyText(pageContents);
            pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        }
        if (config.getFilterConfig().isFilterOutOfPage()) {
            filterOutOfPageContents(pageNumber, pageContents);
            pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        }
        TextProcessor.mergeCloseTextChunks(pageContents);
        pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
        TextProcessor.trimTextChunksWhiteSpaces(pageContents);
        filterConsecutiveSpaces(pageContents);
        pageContents = splitTextChunksByWhiteSpacesInPageContents(pageContents);
        // HiddenText detection moved to DocumentProcessor (sequential post-processing)
        // to avoid ContrastRatioConsumer per-thread PDF rendering overhead
        double replacementCharRatio = TextProcessor.measureReplacementCharRatio(pageContents);
        StaticLayoutContainers.setReplacementCharRatio(pageNumber, replacementCharRatio);
        if (replacementCharRatio >= 0.3) {
            LOGGER.log(Level.WARNING,
                "Page {0}: {1,number,#.#%} of characters are replacement characters (U+FFFD). "
                + "This PDF likely contains CID-keyed fonts without ToUnicode mappings. "
                + "Text extraction may be incomplete. Consider enabling hybrid OCR fallback with --hybrid docling-fast.",
                new Object[]{pageNumber + 1, replacementCharRatio});
        }
        TextProcessor.replaceUndefinedCharacters(pageContents, config.getReplaceInvalidChars());
        if (config.getFilterConfig().isFilterBackgrounds()) {
            processBackgrounds(pageNumber, pageContents);
        }
        return pageContents;
    }

    /**
     * Detects and removes background elements from page contents.
     *
     * @param pageNumber the page number (0-indexed)
     * @param contents the page contents to process
     */
    public static void processBackgrounds(int pageNumber, List<IObject> contents) {
        BoundingBox pageBoundingBox = DocumentProcessor.getPageBoundingBox(pageNumber);
        if (pageBoundingBox == null) {
            return;
        }
        Set<LineArtChunk> backgrounds = new HashSet<>();
        for (IObject content : contents) {
            if (content instanceof LineArtChunk) {
                if (isBackground(content, pageBoundingBox)) {
                    backgrounds.add((LineArtChunk) content);
                }
            }
        }
        if (!backgrounds.isEmpty()) {
            LOGGER.log(Level.WARNING, "Detected background on page " + (pageNumber + 1));
            contents.removeAll(backgrounds);
        }
    }

    private static void filterConsecutiveSpaces(List<IObject> pageContents) {
        for (IObject object : pageContents) {
            if (object instanceof TextChunk) {
                ((TextChunk) object).compressSpaces();
            }
        }
    }

    private static boolean isBackground(IObject content, BoundingBox pageBoundingBox) {
        return (content.getBoundingBox().getWidth() > 0.5 * pageBoundingBox.getWidth() &&
            content.getBoundingBox().getHeight() > 0.1 * pageBoundingBox.getHeight()) ||
            (content.getBoundingBox().getWidth() > 0.1 * pageBoundingBox.getWidth() &&
                content.getBoundingBox().getHeight() > 0.5 * pageBoundingBox.getHeight()) ||
            // A strip that spans most of the page height is a page decoration (a margin
            // rule, a sidebar band, an invisible frame) even when it is narrower than the
            // 10 % width limit of the clause above. Leaving such a strip in the page
            // contents makes the chart / flowchart region grow across the whole page:
            // measured 56.7 x 501.5 pt strip on a 595 x 794 pt page (9.5 % wide) that
            // swallowed the entire text column of the page into one screenshot.
            isPageHeightStrip(content.getBoundingBox(), pageBoundingBox);
    }

    /** True when {@code box} is a narrow strip spanning most of the page height. */
    private static boolean isPageHeightStrip(BoundingBox box, BoundingBox pageBoundingBox) {
        if (box == null || box.isEmpty() || pageBoundingBox == null || pageBoundingBox.isEmpty()) {
            return false;
        }
        return box.getWidth() > BACKGROUND_STRIP_MIN_WIDTH_RATIO * pageBoundingBox.getWidth()
            && box.getHeight() > BACKGROUND_STRIP_MIN_HEIGHT_RATIO * pageBoundingBox.getHeight();
    }

    private static void filterOutOfPageContents(int pageNumber, List<IObject> contents) {
        BoundingBox pageBoundingBox = DocumentProcessor.getPageBoundingBox(pageNumber);
        if (pageBoundingBox == null) {
            return;
        }
        pageBoundingBox.move(-pageBoundingBox.getLeftX(), -pageBoundingBox.getBottomY());
        for (int index = 0; index < contents.size(); index++) {
            IObject object = contents.get(index);
            if (object != null && pageBoundingBox.notOverlaps(object.getBoundingBox())) {
                contents.set(index, null);
            }
        }
    }

    private static List<IObject> splitTextChunksByWhiteSpacesInPageContents(List<IObject> contents) {
        List<IObject> newContents = new ArrayList<>();
        for (IObject object : contents) {
            if (object instanceof TextChunk) {
                TextChunk textChunk = (TextChunk) object;
                List<TextChunk> splitChunks = TextChunkUtils.splitTextChunkByWhiteSpaces(textChunk);
                newContents.addAll(splitChunks);
            } else {
                newContents.add(object);
            }
        }
        return newContents;
    }
}
