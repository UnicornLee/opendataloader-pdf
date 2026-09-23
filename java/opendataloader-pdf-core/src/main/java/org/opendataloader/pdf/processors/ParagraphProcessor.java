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

import org.opendataloader.pdf.custom.constants.GlobalConstant;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.utils.BulletedParagraphUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.enums.TextAlignment;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.Table;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.CaptionUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ChunksMergeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.util.*;
import java.util.stream.Collectors;

public class ParagraphProcessor {

    public static final double DIFFERENT_LINES_PROBABILITY = 0.75;

    public static final double MAX_PARAGRAPH_BEGINNING_INDENT = 50;

    /**
     * Maximum vertical gap between two consecutive lines of one paragraph, expressed in
     * multiples of the font size. Normal line spacing is about 1.2-1.5 times the font size
     * and a paragraph break about 2 times; anything beyond this ratio (for example the
     * "单位：万元" captions of two different tables, roughly 15 times apart) belongs to a
     * different paragraph even when the spacing happens to be uniform.
     */
    private static final double MAX_LINE_SPACING_RATIO = 3.0;

    /**
     * Tolerance (pt) for judging that a line fills the whole text column, i.e. that its right
     * edge reaches the column right edge (see {@link #isHangingIndentContinuation}).
     */
    private static final double HANGING_INDENT_RIGHT_TOLERANCE = 5.0;

    /**
     * Chinese numerals that can complete the word "之X" (之一, 之二, …) carried over from
     * the end of the previous line.
     */
    private static final String CHINESE_NUMERAL_CHARACTERS = "一二三四五六七八九十";

    /**
     * Minimum vertical (y) bounding-box overlap ratio required to merge two TextBlocks
     * into one. The ratio is the overlap length divided by the smaller block's height.
     * Two blocks whose y-ranges overlap by at least this fraction are treated as
     * belonging to the same visual line and are merged.
     *
     * <p>This recovers fragments that the upstream text extractor emitted as separate
     * text runs (e.g. a term set in a narrow left margin column and its definition in
     * the right column of a definition list). Such fragments often share the same
     * baseline / y-range but sit in different columns, so they stay separate TextLines
     * and are never merged by the paragraph-alignment logic below.</p>
     */
    private static final double MIN_Y_OVERLAP_MERGE_RATIO = 0.7;

    public static List<IObject> processParagraphs(List<IObject> contents, double width) {
        DocumentProcessor.setIndexesForContentsList(contents);
        List<TextBlock> blocks = new ArrayList<>();
        List<IObject> separators = new ArrayList<>();
        for (IObject content : contents) {
            if (content instanceof TextLine) {
                blocks.add(new TextBlock((TextLine) content));
            }
            // Anything that is not text separates two lines visually and therefore ends the
            // paragraph run: decoration lines, but also tables and images.
            if (content instanceof LineArtChunk || content instanceof TableBorder
                    || content instanceof Table || content instanceof ImageChunk) {
                separators.add(content);
            }
        }
        Set<TextLine> compositeRowLines = new HashSet<>();
        blocks = mergeVerticallyOverlappingBlocks(blocks, compositeRowLines);
        List<Double> leftXList = blocks.stream().map(block -> block.getBoundingBox().getLeftX()).collect(Collectors.toList());
        // 从 leftXList 中取出中位数，或者非常靠近中位数的集合的平均数
        double leftX = 0;
        if (leftXList.size() > 0) {
            if (leftXList.size() < 6) {
                leftX = leftXList.stream().sorted().collect(Collectors.toList()).get(0);
            } else {
                leftX = leftXList.stream().sorted().collect(Collectors.toList()).get(leftXList.size() / 4);
            }
        }
        List<Double> rightXList = blocks.stream().map(block -> block.getBoundingBox().getRightX()).collect(Collectors.toList());
        // 从 rightXList 中取出中位数，或者非常靠近中位数的集合的平均数
        double rightX = 0;
        if (rightXList.size() > 0) {
            rightX = rightXList.stream().sorted().collect(Collectors.toList()).get(3*rightXList.size() / 4);
            if (rightXList.size() < 6) {
                rightX = rightXList.stream().sorted().collect(Collectors.toList()).get(rightXList.size() - 1);
            }
        }

        blocks = detectParagraphsWithJustifyAlignments(blocks, leftX, rightX, width, separators);
        blocks = detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(blocks, leftX, rightX, width, separators);
        blocks = detectParagraphsWithLeftAlignments(blocks, true, leftX, rightX, width, separators);
        blocks = detectFirstLinesOfParagraphWithLeftAlignments(blocks, leftX, rightX, width, separators);
        blocks = detectParagraphsWithCenterAlignments(blocks, leftX, rightX, width, separators);
        blocks = detectParagraphsWithRightAlignments(blocks, leftX, rightX, width, separators);
        blocks = detectTwoLinesParagraphs(blocks, leftX, rightX, width, separators);
        blocks = processOtherLines(blocks, leftX, rightX, width, separators);
        blocks = mergeCompositeRowContinuations(blocks, compositeRowLines);
        return getContentsWithDetectedParagraphs(contents, blocks);
    }

    public static List<IObject> processParagraphs(List<IObject> contents) {
        DocumentProcessor.setIndexesForContentsList(contents);
        List<TextBlock> blocks = new ArrayList<>();
        for (IObject content : contents) {
            if (content instanceof TextLine) {
                blocks.add(new TextBlock((TextLine) content));
            }
        }
        blocks = mergeVerticallyOverlappingBlocks(blocks, new HashSet<>());
        blocks = detectParagraphsWithJustifyAlignments(blocks);
        blocks = detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(blocks);
        blocks = detectParagraphsWithLeftAlignments(blocks, true);
        blocks = detectParagraphsWithLeftAlignments(blocks, false);
        blocks = detectFirstLinesOfParagraphWithLeftAlignments(blocks);
        blocks = detectParagraphsWithCenterAlignments(blocks);
        blocks = detectParagraphsWithRightAlignments(blocks);
        blocks = detectTwoLinesParagraphs(blocks);
        blocks = processOtherLines(blocks);
        return getContentsWithDetectedParagraphs(contents, blocks);
    }

    private static List<IObject> getContentsWithDetectedParagraphs(List<IObject> contents, List<TextBlock> blocks) {
        List<IObject> newContents = new ArrayList<>();
        Iterator<TextBlock> iterator = blocks.iterator();
        TextBlock currentBlock = iterator.hasNext() ? iterator.next() : null;
        Integer currentIndex = currentBlock != null ? currentBlock.getFirstLine().getIndex() : null;
        for (int index = 0; index < contents.size(); index++) {
            IObject content = contents.get(index);
            if (!(content instanceof TextLine)) {
                newContents.add(content);
            } else if (Objects.equals(currentIndex, index)) {
                newContents.add(createParagraphFromTextBlock(currentBlock));
                currentBlock = iterator.hasNext() ? iterator.next() : null;
                currentIndex = currentBlock != null ? currentBlock.getFirstLine().getIndex() : null;
            }
        }
        return newContents;
    }

    /**
     * Merge TextBlocks whose vertical (y) bounding-box ranges overlap by at least
     * {@link #MIN_Y_OVERLAP_MERGE_RATIO} into a single TextBlock, replacing the
     * originals. Overlapping blocks are grouped transitively (union-find) so that a
     * chain of mutually-overlapping blocks collapses into one block.
     *
     * <p>Within a merged block, lines whose y ranges overlap significantly belong to the
     * same visual row (e.g. a definition term in a left margin column and its definition
     * in the right column). Such lines are merged into a single {@link TextLine}: their
     * text chunks are concatenated left-to-right (a space chunk is inserted when the gap
     * is large) and their bounding boxes unioned. The merged line reuses the left-most
     * original line object, so its {@code getIndex()} still points at an original
     * TextLine index and {@code getContentsWithDetectedParagraphs} keeps mapping the
     * block back to the right position in {@code contents}.</p>
     */
    private static List<TextBlock> mergeVerticallyOverlappingBlocks(List<TextBlock> blocks, Set<TextLine> compositeRowLines) {
        if (blocks.size() <= 1) {
            return blocks;
        }
        int n = blocks.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (haveSignificantYOverlap(blocks.get(i), blocks.get(j))) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<TextBlock> result = new ArrayList<>();
        for (List<Integer> group : groups.values()) {
            if (group.size() == 1) {
                result.add(blocks.get(group.get(0)));
                continue;
            }
            List<TextLine> lines = new ArrayList<>();
            for (int idx : group) {
                lines.addAll(blocks.get(idx).getLines());
            }
            result.add(buildBlockWithMergedRows(lines, compositeRowLines));
        }
        return result;
    }

    /**
     * Builds a TextBlock from the given lines, merging lines that belong to the same
     * visual row (significant y overlap) into one {@link TextLine} each. Rows are
     * ordered top-to-bottom; within a row, lines are merged left-to-right. Row-merged
     * (composite) lines are recorded in {@code compositeRowLines} so that later
     * continuation merging can recognise them.
     */
    private static TextBlock buildBlockWithMergedRows(List<TextLine> lines, Set<TextLine> compositeRowLines) {
        lines.sort(Comparator.comparingDouble((TextLine l) -> -l.getBoundingBox().getTopY())
                .thenComparingDouble(l -> l.getBoundingBox().getLeftX()));
        List<List<TextLine>> rows = new ArrayList<>();
        List<TextLine> currentRow = null;
        double rowTop = Double.NaN;
        double rowBottom = Double.NaN;
        for (TextLine line : lines) {
            if (currentRow != null && haveSignificantYOverlap(rowTop, rowBottom, line)) {
                currentRow.add(line);
                rowTop = Math.max(rowTop, line.getBoundingBox().getTopY());
                rowBottom = Math.min(rowBottom, line.getBoundingBox().getBottomY());
            } else {
                currentRow = new ArrayList<>();
                currentRow.add(line);
                rows.add(currentRow);
                rowTop = line.getBoundingBox().getTopY();
                rowBottom = line.getBoundingBox().getBottomY();
            }
        }
        List<TextLine> mergedRows = new ArrayList<>();
        for (List<TextLine> row : rows) {
            row.sort(Comparator.comparingDouble(TextLine::getLeftX));
            TextLine mergedRow = row.get(0);
            for (int i = 1; i < row.size(); i++) {
                TextLine next = row.get(i);
                addSpaceChunkIfRequired(mergedRow, next);
                mergedRow.add(next);
            }
            if (row.size() > 1) {
                compositeRowLines.add(mergedRow);
            }
            mergedRows.add(mergedRow);
        }
        TextBlock block = new TextBlock(mergedRows.get(0));
        if (mergedRows.size() > 1) {
            block.add(mergedRows.subList(1, mergedRows.size()));
        }
        return block;
    }

    /**
     * Merges single-line blocks that continue a multi-column composite row into the
     * preceding paragraph. When a visual row combines a left-column fragment (e.g. a
     * definition term) with right-column text, the composite line's leftX is the left
     * column edge, so a continuation line that starts at the right-column x (where the
     * composite row's later chunks begin) fails the ordinary left-alignment paragraph
     * tests and stays separate. Such a continuation is recognised here by:
     * <ul>
     *   <li>the previous block's last line being a composite row line,</li>
     *   <li>the next line NOT being a composite row itself (a new definition entry
     *       would carry its own left-column term),</li>
     *   <li>the next line's leftX matching the start x of one of the composite row's
     *       non-leading text chunks,</li>
     *   <li>normal line spacing and matching font sizes.</li>
     * </ul>
     */
    private static List<TextBlock> mergeCompositeRowContinuations(List<TextBlock> blocks, Set<TextLine> compositeRowLines) {
        if (blocks.isEmpty() || compositeRowLines.isEmpty()) {
            return blocks;
        }
        List<TextBlock> newBlocks = new ArrayList<>();
        newBlocks.add(blocks.get(0));
        for (int i = 1; i < blocks.size(); i++) {
            TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
            TextBlock nextBlock = blocks.get(i);
            if (isCompositeRowContinuation(previousBlock, nextBlock, compositeRowLines)) {
                previousBlock.add(nextBlock.getLines());
            } else {
                newBlocks.add(nextBlock);
            }
        }
        return newBlocks;
    }

    private static boolean isCompositeRowContinuation(TextBlock previousBlock, TextBlock nextBlock, Set<TextLine> compositeRowLines) {
        if (nextBlock.getLinesNumber() != 1) {
            return false;
        }
        TextLine lastLine = previousBlock.getLastLine();
        if (!compositeRowLines.contains(lastLine)) {
            return false;
        }
        TextLine nextLine = nextBlock.getFirstLine();
        // A new entry of the definition list would be a composite row itself.
        if (compositeRowLines.contains(nextLine)) {
            return false;
        }
        if (Math.abs(lastLine.getFontSize() - nextLine.getFontSize()) > 0.5) {
            return false;
        }
        double gap = nextLine.getBottomY() - lastLine.getTopY();
        if (gap > lastLine.getFontSize()) {
            return false;
        }
        // The continuation must start where one of the composite row's non-leading
        // chunks (i.e. a later column) starts.
        double tolerance = Math.max(1.0, lastLine.getFontSize() * 0.5);
        List<TextChunk> chunks = lastLine.getTextChunks();
        for (int i = 1; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (chunk.getValue().trim().isEmpty()) {
                continue;
            }
            if (Math.abs(chunk.getLeftX() - nextLine.getLeftX()) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    private static boolean haveSignificantYOverlap(double rowTop, double rowBottom, TextLine line) {
        double lineTop = line.getBoundingBox().getTopY();
        double lineBottom = line.getBoundingBox().getBottomY();
        double overlap = Math.min(rowTop, lineTop) - Math.max(rowBottom, lineBottom);
        if (overlap <= 0) {
            return false;
        }
        double minHeight = Math.min(rowTop - rowBottom, lineTop - lineBottom);
        return minHeight > 0 && overlap / minHeight >= MIN_Y_OVERLAP_MERGE_RATIO;
    }

    /**
     * Inserts a single-space chunk between two same-row lines when the horizontal gap
     * between them is at least the line-space ratio times the font size, mirroring the
     * behaviour of {@code TextLine.addSpaceIfRequired} (which is skipped in DataLoader
     * mode).
     */
    private static void addSpaceChunkIfRequired(TextLine row, TextLine line) {
        // getTextLineSpaceRatio() is a ThreadLocal that is only initialized on the main
        // thread; page processing runs on worker threads where it is null.
        Double spaceRatio = StaticContainers.getTextLineSpaceRatio();
        double ratio = spaceRatio != null ? spaceRatio : TextChunkUtils.TEXT_LINE_SPACE_RATIO;
        if (line.getLeftX() - row.getRightX() < row.getFontSize() * ratio) {
            return;
        }
        BoundingBox boundingBox = new BoundingBox();
        boundingBox.init(row.getRightX(), row.getBottomY(), line.getLeftX(), row.getTopY());
        row.add(new TextChunk(boundingBox, " ", row.getFontSize(), row.getBaseLine()));
    }

    private static boolean haveSignificantYOverlap(TextBlock a, TextBlock b) {
        // Normalize to [low, high] in case a box is inverted. In veraPDF's coordinate
        // system getTopY() is the LARGER y value and getBottomY() the smaller one.
        double aLow = Math.min(a.getBoundingBox().getTopY(), a.getBoundingBox().getBottomY());
        double aHigh = Math.max(a.getBoundingBox().getTopY(), a.getBoundingBox().getBottomY());
        double bLow = Math.min(b.getBoundingBox().getTopY(), b.getBoundingBox().getBottomY());
        double bHigh = Math.max(b.getBoundingBox().getTopY(), b.getBoundingBox().getBottomY());
        double overlap = Math.min(aHigh, bHigh) - Math.max(aLow, bLow);
        if (overlap <= 0) {
            return false;
        }
        double minHeight = Math.min(aHigh - aLow, bHigh - bLow);
        return minHeight > 0 && overlap / minHeight >= MIN_Y_OVERLAP_MERGE_RATIO;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    private static List<TextBlock> detectParagraphsWithJustifyAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
                double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (textAlignment == TextAlignment.JUSTIFY && probability > DIFFERENT_LINES_PROBABILITY &&
                    areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.JUSTIFY);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectParagraphsWithJustifyAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
                double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
                if (textAlignment == TextAlignment.JUSTIFY && probability > DIFFERENT_LINES_PROBABILITY &&
                    areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.JUSTIFY);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectParagraphsWithCenterAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (areLinesOfParagraphsWithCenterAlignments(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.CENTER);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectParagraphsWithCenterAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (areLinesOfParagraphsWithCenterAlignments(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.CENTER);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean areLinesOfParagraphsWithCenterAlignments(TextBlock previousBlock, TextBlock nextBlock) {
        TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
        if (textAlignment != TextAlignment.CENTER) {
            return false;
        }
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, true, false);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        return true;
    }

    private static List<TextBlock> detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
                double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (isFirstLineOfBlock(previousBlock, nextBlock, textAlignment, probability)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.JUSTIFY);
                    previousBlock.setHasStartLine(true);
                    previousBlock.setHasEndLine(nextBlock.isHasEndLine());
                } else if (isLastLineOfBlock(previousBlock, nextBlock, textAlignment, probability)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setHasEndLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
                double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
                if (isFirstLineOfBlock(previousBlock, nextBlock, textAlignment, probability)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.JUSTIFY);
                    previousBlock.setHasStartLine(true);
                    previousBlock.setHasEndLine(nextBlock.isHasEndLine());
                } else if (isLastLineOfBlock(previousBlock, nextBlock, textAlignment, probability)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setHasEndLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean prejudgeParagraphs(List<TextBlock> textBlocks, List<TextBlock> newBlocks, int index, double leftX, double rightX, double width, List<IObject> separators) {
        boolean hasJudge = false;
        if (textBlocks.size() > 1) {
            TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
            TextBlock nextBlock = textBlocks.get(index);
            if (previousBlock == null || previousBlock.getLastLine() == null
                    || nextBlock == null || nextBlock.getFirstLine() == null) {
                return hasJudge;
            }
            String prevLastLineText = previousBlock.getLastLine().getValue().trim();
            String prevLastLineTextLastCh = prevLastLineText.isEmpty() ? "" : prevLastLineText.substring(prevLastLineText.length() - 1);
            double prevFontSize = previousBlock.getLastLine().getFontSize();
            double prevLastLineLeftX = previousBlock.getLastLine().getBoundingBox().getLeftX();
            double prevLastLineRightX = previousBlock.getLastLine().getBoundingBox().getRightX();
            double prevTopY = previousBlock.getLastLine().getTopY();
            double prevBottomY = previousBlock.getLastLine().getBottomY();
            String nextFirstLineText = nextBlock.getFirstLine().getValue().trim();
            String nextFirstLineTextLastCh = nextFirstLineText.isEmpty() ? "" : nextFirstLineText.substring(nextFirstLineText.length() - 1);
            double nextFontSize = nextBlock.getFirstLine().getFontSize();
            double nextFirstLineLeftX = nextBlock.getFirstLine().getBoundingBox().getLeftX();
            double nextFirstLineRightX = nextBlock.getFirstLine().getBoundingBox().getRightX();
            double nextTopY = nextBlock.getFirstLine().getTopY();
            double nextBottomY = nextBlock.getFirstLine().getBottomY();
            double margin = prevBottomY - nextTopY;
            Double prevMargin = null;
            if (newBlocks.size() > 2) {
                prevMargin = newBlocks.get(newBlocks.size() - 2).getLastLine().getBottomY() - previousBlock.getFirstLine().getTopY();
            }
            Double nextTwoFontSize = null;
            Double nextTwoLeftX = null;
            Double nextTwoTopY = null;
            Double nextMargin = null;
            if (nextBlock.getLines().size() > 1) {
                nextBlock.getLines().sort(Comparator.comparingDouble(item -> item.getBoundingBox().getTopY()));
                Collections.reverse(nextBlock.getLines());
                nextTwoFontSize = nextBlock.getLines().get(1).getFontSize();
                nextTwoLeftX = nextBlock.getLines().get(1).getLeftX();
                nextTwoTopY = nextBlock.getLines().get(1).getTopY();
            }
            if ((nextTwoFontSize == null || nextTwoTopY == null) && index < textBlocks.size() - 1) {
                nextTwoFontSize = textBlocks.get(index + 1).getFirstLine().getFontSize();
                nextTwoLeftX = textBlocks.get(index + 1).getFirstLine().getLeftX();
                nextTwoTopY = textBlocks.get(index + 1).getFirstLine().getTopY();
            }
            if (nextTwoTopY != null) {
                nextMargin = nextBottomY - nextTwoTopY;
            }

            // 夹在 previousBlock 和 nextBlock 之间的 separators
            // 视觉上横跨两行的水平线（如下划线、装饰横线）应作为段落分隔符，
            // 命中条件：垂直方向位于两行之间，且水平方向横跨两段文本范围。
            // A gap far beyond a normal line height means the two lines belong to different
            // blocks (e.g. the "单位：万元" captions of two tables, ~15 line heights apart),
            // even when the gap is uniform and the alignment matches.
            if (prevFontSize > 0 && margin > MAX_LINE_SPACING_RATIO * prevFontSize) {
                newBlocks.add(nextBlock);
                hasJudge = true;
                return hasJudge;
            } else if (hasSeparatorBetween(previousBlock, nextBlock, separators)) {
                newBlocks.add(nextBlock);
                hasJudge = true;
                return hasJudge;
            } else if (isHangingIndentContinuation(previousBlock, nextBlock, rightX, margin)) {
                previousBlock.add(nextBlock.getLines());
                previousBlock.setTextAlignment(TextAlignment.LEFT);
                previousBlock.setHasEndLine(false);
                hasJudge = true;
                return hasJudge;
            } else if (Math.abs(prevFontSize - nextFontSize) >= 2) {
                newBlocks.add(nextBlock);
                hasJudge = true;
            } else if (prevMargin != null && margin - prevMargin >= 5) {
                newBlocks.add(nextBlock);
                hasJudge = true;
            } else if (nextMargin != null && margin - nextMargin >= 5) {
                newBlocks.add(nextBlock);
                hasJudge = true;
            } else if (rightX - prevLastLineRightX > 20 ||
                (prevLastLineLeftX + prevLastLineRightX) / 2 < width / 2 - 20) {
                newBlocks.add(nextBlock);
                hasJudge = true;
            } else if ((Math.abs(prevLastLineRightX - rightX) < 5 || width / 2 - (prevLastLineLeftX + prevLastLineRightX) / 2 < 10) &&
                prevLastLineLeftX - nextFirstLineLeftX > -5 && prevLastLineLeftX - nextFirstLineLeftX < MAX_PARAGRAPH_BEGINNING_INDENT) {
                if (rightX - nextFirstLineRightX > 20 &&
                    GlobalConstant.SENTENCE_ENDING_PUNCTUATION.contains(prevLastLineTextLastCh) &&
                    !GlobalConstant.SENTENCE_ENDING_PUNCTUATION.contains(nextFirstLineTextLastCh)) {
                    newBlocks.add(nextBlock);
                } else if (nextTwoFontSize != null && nextMargin != null && nextTwoLeftX != null &&
                    Math.abs(nextFontSize - nextTwoFontSize) < 2 && Math.abs(margin - nextMargin) < 5 && nextFirstLineLeftX - nextTwoLeftX > 10) {
                    newBlocks.add(nextBlock);
                } else if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
                    // Next line is a list label (e.g. "1.", "1、", "一、"). Even when
                    // geometry alone wouldn't force a break (same leftX, similar margin,
                    // no indentation in following line), a labeled first line should
                    // start its own paragraph rather than be appended to the previous one.
                    newBlocks.add(nextBlock);
                } else {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    if (rightX - nextFirstLineRightX > 20) {
                        previousBlock.setHasEndLine(true);
                    } else {
                        previousBlock.setHasEndLine(false);
                    }
                }
                hasJudge = true;
            }
        }
        return hasJudge;
    }

    /**
     * 检测是否存在 lineArt 视觉上夹在 previousBlock 与 nextBlock 之间。
     * 命中条件：lineArt 垂直方向位于 prevBottomY 与 nextTopY 之间，
     * 且水平方向横跨 previousBlock 左边界与 nextBlock 右边界。
     * 这种情况通常意味着两行之间存在装饰线/分隔线，应作为段落分隔符。
     */
    private static boolean hasSeparatorBetween(TextBlock previousBlock, TextBlock nextBlock, List<IObject> separators) {
        if (separators == null || separators.isEmpty()) {
            return false;
        }
        double prevBottomY = previousBlock.getLastLine().getBottomY();
        double nextTopY = nextBlock.getFirstLine().getTopY();
        for (IObject separator : separators) {
            double separatorBottomY = separator.getBottomY();
            double separatorTopY = separator.getTopY();
            if (separatorBottomY >= nextTopY && separatorTopY <= prevBottomY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when {@code nextBlock} is the continuation line of a numbered heading laid
     * out with a <em>hanging indent</em>: the previous line fills the whole text column and the
     * next line is indented by roughly the width of the leading label (e.g. {@code "9.  "}).
     *
     * <p>The existing opening-indent rule only covers the mirrored case — a first line indented
     * relative to the lines that follow it. A hanging-indent pair matches none of the other
     * rules either: its two lines are neither left-, right- nor center-aligned (the indentation
     * exceeds the alignment tolerance), so without this test the continuation is emitted as a
     * separate paragraph.</p>
     *
     * <p>The "previous line fills the column" requirement is what keeps real paragraph breaks
     * out: a block that starts a new paragraph is normally preceded by a short last line, whose
     * right edge sits well inside the column.</p>
     *
     * @param previousBlock block whose last line is the filled line
     * @param nextBlock     candidate continuation block
     * @param rightX        right edge of the text column
     * @param margin        vertical gap between the two lines
     * @return true when the next block continues the previous one
     */
    private static boolean isHangingIndentContinuation(TextBlock previousBlock, TextBlock nextBlock,
                                                       double rightX, double margin) {
        TextLine previousLine = previousBlock.getLastLine();
        TextLine nextLine = nextBlock.getFirstLine();
        if (previousLine == null || nextLine == null) {
            return false;
        }
        // A filled line ends on the column right edge; otherwise it is the last line of a
        // paragraph (e.g. a short heading) and the next block opens a new one.
        if (Math.abs(previousLine.getRightX() - rightX) >= HANGING_INDENT_RIGHT_TOLERANCE) {
            return false;
        }
        double indent = nextLine.getLeftX() - previousLine.getLeftX();
        if (indent <= 0 || indent > MAX_PARAGRAPH_BEGINNING_INDENT) {
            return false;
        }
        // A labeled line ("10. ...", "一、...") opens its own entry, never a continuation.
        if (isListLabeledNextLine(previousBlock, nextLine)) {
            return false;
        }
        double fontSize = previousLine.getFontSize();
        return fontSize > 0 && margin <= MAX_LINE_SPACING_RATIO * fontSize;
    }

    /**
     * Returns true when the next line starts with a list label ("1.", "一、" …) and that
     * label is NOT the tail of a word continued from the previous line. Example: a
     * definition ending with "…控股股東之" that continues with "一、曲女士的配偶" — here
     * "一、" completes "之一" ("one of") and must not be treated as a numbered list marker
     * opening a new paragraph.
     */
    private static boolean isListLabeledNextLine(TextBlock previousBlock, TextLine nextLine) {
        if (!BulletedParagraphUtils.isLabeledLine(nextLine)) {
            return false;
        }
        TextLine previousLine = previousBlock != null ? previousBlock.getLastLine() : null;
        if (previousLine != null) {
            String previousText = previousLine.getValue().trim();
            String nextText = nextLine.getValue().trim();
            // "之一、" / "之二、" … — the numeral directly after "之" belongs to the
            // previous word rather than to a list label.
            if (previousText.endsWith("之") && !nextText.isEmpty()
                    && CHINESE_NUMERAL_CHARACTERS.indexOf(nextText.charAt(0)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static List<TextBlock> detectParagraphsWithLeftAlignments(List<TextBlock> textBlocks, boolean checkStyle, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (areLinesOfParagraphsWithLeftAlignments(previousBlock, nextBlock, checkStyle)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasEndLine(false);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectParagraphsWithLeftAlignments(List<TextBlock> textBlocks, boolean checkStyle) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (areLinesOfParagraphsWithLeftAlignments(previousBlock, nextBlock, checkStyle)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasEndLine(false);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean areLinesOfParagraphsWithRightAlignments(TextBlock previousBlock, TextBlock nextBlock) {
        TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
        if (textAlignment != TextAlignment.RIGHT) {
            return false;
        }
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (previousBlock.getLinesNumber() != 1 && previousBlock.getTextAlignment() != TextAlignment.RIGHT) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (nextBlock.getLinesNumber() != 1 && nextBlock.getTextAlignment() != TextAlignment.RIGHT) {
            return false;
        }
        return true;
    }

    private static boolean areLinesOfParagraphsWithLeftAlignments(TextBlock previousBlock, TextBlock nextBlock, boolean checkStyle) {
        TextAlignment textAlignment = ChunksMergeUtils.getAlignment(previousBlock.getLastLine(), nextBlock.getFirstLine());
        if (textAlignment != TextAlignment.LEFT) {
            return false;
        }
        boolean haveSameStyle = TextChunkUtils.areTextChunksHaveSameStyle(previousBlock.getLastLine().getFirstTextChunk(),
            nextBlock.getFirstLine().getFirstTextChunk());
        if (checkStyle && !haveSameStyle) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
            return false;
        }
        boolean areShouldBeCloseLines = false;
        if (previousBlock.getLinesNumber() != 1) {
            if (previousBlock.getTextAlignment() == TextAlignment.JUSTIFY) {
                if (!haveSameStyle) {
                    return false;
                }
                areShouldBeCloseLines = true;
            } else if (previousBlock.getTextAlignment() != TextAlignment.LEFT) {
                return false;
            }
        }
        if (nextBlock.getLinesNumber() != 1) {
            if (nextBlock.getTextAlignment() == TextAlignment.JUSTIFY) {
                if (!haveSameStyle) {
                    return false;
                }
                areShouldBeCloseLines = true;
            } else if (nextBlock.getTextAlignment() != TextAlignment.LEFT) {
                return false;
            }
        }
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, true, areShouldBeCloseLines);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        return true;
    }

    private static List<TextBlock> detectFirstLinesOfParagraphWithLeftAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (isFirstLineOfParagraphWithLeftAlignment(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasStartLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectFirstLinesOfParagraphWithLeftAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (isFirstLineOfParagraphWithLeftAlignment(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasStartLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean isFirstLineOfParagraphWithLeftAlignment(TextBlock previousBlock, TextBlock nextBlock) {
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
        if (previousBlock.getLinesNumber() != 1) {
            return false;
        }
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
            return false;
        }
        if (nextBlock.isHasStartLine()) {
            return false;
        }
        if (nextBlock.getTextAlignment() != TextAlignment.LEFT) {
            return false;
        }
        if (!CaptionUtils.areOverlapping(previousBlock.getLastLine(), nextBlock.getFirstLine().getBoundingBox())) {
            return false;
        }
        return true;
    }

    private static List<TextBlock> detectTwoLinesParagraphs(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (isTwoLinesParagraph(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasStartLine(true);
                    previousBlock.setHasEndLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectTwoLinesParagraphs(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (isTwoLinesParagraph(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasStartLine(true);
                    previousBlock.setHasEndLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean isTwoLinesParagraph(TextBlock previousBlock, TextBlock nextBlock) {
        if (previousBlock.getLinesNumber() != 1 || nextBlock.getLinesNumber() != 1) {
            return false;
        }
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
            return false;
        }
        if (previousBlock.getLastLine().getLeftX() < nextBlock.getFirstLine().getLeftX() ||
            previousBlock.getLastLine().getRightX() < nextBlock.getFirstLine().getRightX()) {
            return false;
        }
        return true;
    }

    private static boolean isFirstLineOfBulletedParagraphWithLeftAlignment(TextBlock previousBlock, TextBlock nextBlock) {
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (previousBlock.getLinesNumber() != 1) {
            return false;
        }
        if (nextBlock.isHasStartLine()) {
            return false;
        }
        if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
            return false;
        }
        if (!BulletedParagraphUtils.isLabeledLine(previousBlock.getFirstLine())) {
            return false;
        }
        if (previousBlock.getLastLine().getLeftX() > nextBlock.getFirstLine().getLeftX()) {
            return false;
        }
        if (nextBlock.getTextAlignment() != TextAlignment.LEFT && nextBlock.getLinesNumber() != 1) {
            return false;
        }
        if (!CaptionUtils.areOverlapping(previousBlock.getLastLine(), nextBlock.getFirstLine().getBoundingBox())) {
            return false;
        }
        return true;
    }

    private static List<TextBlock> detectParagraphsWithRightAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (areLinesOfParagraphsWithRightAlignments(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.RIGHT);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectParagraphsWithRightAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (areLinesOfParagraphsWithRightAlignments(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.RIGHT);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> detectBulletedParagraphsWithLeftAlignments(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (isFirstLineOfBulletedParagraphWithLeftAlignment(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                    previousBlock.setTextAlignment(TextAlignment.LEFT);
                    previousBlock.setHasStartLine(true);
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> processOtherLines(List<TextBlock> textBlocks, double leftX, double rightX, double width, List<IObject> separators) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, separators)) {
                } else if (isOneParagraph(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static List<TextBlock> processOtherLines(List<TextBlock> textBlocks) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (isOneParagraph(previousBlock, nextBlock)) {
                    previousBlock.add(nextBlock.getLines());
                } else {
                    newBlocks.add(nextBlock);
                }
            }
        }
        return newBlocks;
    }

    private static boolean isOneParagraph(TextBlock previousBlock, TextBlock nextBlock) {
        if (!areCloseStyle(previousBlock, nextBlock)) {
            return false;
        }
        double probability = getDifferentLinesProbability(previousBlock, nextBlock, false, false);
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())) {
            return false;
        }
        if (!CaptionUtils.areOverlapping(previousBlock.getLastLine(), nextBlock.getFirstLine().getBoundingBox())) {
            return false;
        }
        if (previousBlock.getLinesNumber() != 1 && previousBlock.getTextAlignment() != null) {
            return false;
        }
        if (nextBlock.getLinesNumber() != 1 && nextBlock.getTextAlignment() != null) {
            return false;
        }
        return true;
    }

    private static boolean isFirstLineOfBlock(TextBlock previousBlock, TextBlock nextBlock, TextAlignment textAlignment,
                                              double probability) {
        if (previousBlock.getLinesNumber() != 1) {
            return false;
        }
        if (textAlignment != TextAlignment.RIGHT) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (nextBlock.getTextAlignment() != TextAlignment.JUSTIFY) {
            return false;
        }
        if (nextBlock.isHasStartLine()) {
            return false;
        }
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        return true;
    }

    private static boolean isLastLineOfBlock(TextBlock previousBlock, TextBlock nextBlock, TextAlignment textAlignment,
                                             double probability) {
        if (nextBlock.getLinesNumber() != 1) {
            return false;
        }
        if (textAlignment != TextAlignment.LEFT) {
            return false;
        }
        if (!areTextBlocksHaveSameTextSize(previousBlock, nextBlock)) {
            return false;
        }
        if (previousBlock.getTextAlignment() != TextAlignment.JUSTIFY) {
            return false;
        }
        if (previousBlock.isHasEndLine()) {
            return false;
        }
        if (probability < DIFFERENT_LINES_PROBABILITY) {
            return false;
        }
        return true;
    }

    public static CustomSemanticParagraph createParagraphFromTextBlock(TextBlock textBlock) {
        CustomSemanticParagraph textParagraph = new CustomSemanticParagraph();
        textParagraph.addTextLines(textBlock.getLines());
        textParagraph.getColumns().add(new TextColumn());
        textParagraph.getLastColumn().getBlocks().add(textBlock);
        textParagraph.setBoundingBox(textBlock.getBoundingBox());
        textParagraph.setCorrectSemanticScore(1.0);
        textParagraph.setHiddenText(textBlock.isHiddenText());
        return textParagraph;
    }

    private static double getDifferentLinesProbability(TextBlock previousBlock, TextBlock nextBlock,
                                                       boolean areSupportNotSingleLines, boolean areShouldBeCloseLines) {
        if (previousBlock.isHiddenText() != nextBlock.isHiddenText()) {
            return 0;
        }
        if (previousBlock.getLinesNumber() == 1 && nextBlock.getLinesNumber() == 1) {
            return ChunksMergeUtils.mergeLeadingProbability(previousBlock.getLastLine(), nextBlock.getFirstLine());
        }
        if (previousBlock.getLinesNumber() == 1) {
            return ChunksMergeUtils.mergeLeadingProbability(previousBlock.getLastLine(), nextBlock, areShouldBeCloseLines);
        }
        if (nextBlock.getLinesNumber() == 1) {
            return ChunksMergeUtils.mergeLeadingProbability(previousBlock, nextBlock.getFirstLine(), areShouldBeCloseLines);
        }
        if (areSupportNotSingleLines) {
            return ChunksMergeUtils.mergeLeadingProbability(previousBlock, nextBlock);
        }
        return 0;
    }

    private static boolean areCloseStyle(TextBlock previousBlock, TextBlock nextBlock) {
        return NodeUtils.areCloseNumbers(previousBlock.getFontSize(), nextBlock.getFontSize(), 1e-1) &&
            NodeUtils.areCloseNumbers(previousBlock.getFirstLine().getFirstTextChunk().getFontWeight(),
                nextBlock.getFirstLine().getFirstTextChunk().getFontWeight(), 1e-1);
    }

    private static boolean areTextBlocksHaveSameTextSize(TextBlock firstBlock, TextBlock secondBlock) {
        for (Double textSize1 : firstBlock.getTextSizes()) {
            for (Double textSize2 : secondBlock.getTextSizes()) {
                if (NodeUtils.areCloseNumbers(textSize1, textSize2)) {
                    return true;
                }
            }
        }
        return false;
    }
}
