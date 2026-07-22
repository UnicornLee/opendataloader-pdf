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
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.enums.TextAlignment;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.CaptionUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ChunksMergeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.util.*;
import java.util.stream.Collectors;

public class ParagraphProcessor {

    public static final double DIFFERENT_LINES_PROBABILITY = 0.75;

    public static final double MAX_PARAGRAPH_BEGINNING_INDENT = 50;

    public static List<IObject> processParagraphs(List<IObject> contents, double width) {
        DocumentProcessor.setIndexesForContentsList(contents);
        List<TextBlock> blocks = new ArrayList<>();
        for (IObject content : contents) {
            if (content instanceof TextLine) {
                blocks.add(new TextBlock((TextLine) content));
            }
        }
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

        blocks = detectParagraphsWithJustifyAlignments(blocks, leftX, rightX, width);
        blocks = detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(blocks, leftX, rightX, width);
        blocks = detectParagraphsWithLeftAlignments(blocks, true, leftX, rightX, width);
        blocks = detectFirstLinesOfParagraphWithLeftAlignments(blocks, leftX, rightX, width);
        blocks = detectParagraphsWithCenterAlignments(blocks, leftX, rightX, width);
        blocks = detectParagraphsWithRightAlignments(blocks, leftX, rightX, width);
        blocks = detectTwoLinesParagraphs(blocks, leftX, rightX, width);
        blocks = processOtherLines(blocks, leftX, rightX, width);
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

    private static List<TextBlock> detectParagraphsWithJustifyAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
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
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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

    private static List<TextBlock> detectParagraphsWithCenterAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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

    private static List<TextBlock> detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
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
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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

    private static boolean prejudgeParagraphs(List<TextBlock> textBlocks, List<TextBlock> newBlocks, int index, double leftX, double rightX, double width) {
        boolean hasJudge = false;
        if (textBlocks.size() > 1) {
            TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
            TextBlock nextBlock = textBlocks.get(index);
            String prevLastLineText = previousBlock.getLastLine().getValue().trim();
            String prevLastLineTextLastCh = prevLastLineText.substring(prevLastLineText.length() - 1);
            double prevFontSize = previousBlock.getLastLine().getFontSize();
            double prevLastLineLeftX = previousBlock.getLastLine().getBoundingBox().getLeftX();
            double prevLastLineRightX = previousBlock.getLastLine().getBoundingBox().getRightX();
            double prevTopY = previousBlock.getLastLine().getTopY();
            double prevBottomY = previousBlock.getLastLine().getBottomY();
            String nextFirstLineText = nextBlock.getFirstLine().getValue().trim();
            String nextFirstLineTextLastCh = nextFirstLineText.substring(nextFirstLineText.length() - 1);
            double nextFontSize = nextBlock.getFirstLine().getFontSize();
            double nextFirstLineLeftX = nextBlock.getFirstLine().getBoundingBox().getLeftX();
            double nextFirstLineRightX = nextBlock.getFirstLine().getBoundingBox().getRightX();
            double nextTopY = nextBlock.getFirstLine().getTopY();
            double nextBottomY = nextBlock.getFirstLine().getBottomY();
            double margin = prevBottomY - nextTopY;
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

            if (Math.abs(prevFontSize - nextFontSize) >= 2) {
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

    private static List<TextBlock> detectParagraphsWithLeftAlignments(List<TextBlock> textBlocks, boolean checkStyle, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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
        if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {
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

    private static List<TextBlock> detectFirstLinesOfParagraphWithLeftAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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
        if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {
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

    private static List<TextBlock> detectTwoLinesParagraphs(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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
        if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {
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
        if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {
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

    private static List<TextBlock> detectParagraphsWithRightAlignments(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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

    private static List<TextBlock> processOtherLines(List<TextBlock> textBlocks, double leftX, double rightX, double width) {
        List<TextBlock> newBlocks = new ArrayList<>();
        if (!textBlocks.isEmpty()) {
            newBlocks.add(textBlocks.get(0));
        }
        if (textBlocks.size() > 1) {
            for (int i = 1; i < textBlocks.size(); i++) {
                TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
                TextBlock nextBlock = textBlocks.get(i);
                if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width)) {
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
        if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {
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
