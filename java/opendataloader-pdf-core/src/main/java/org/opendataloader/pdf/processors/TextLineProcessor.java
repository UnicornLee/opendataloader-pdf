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

import org.opendataloader.pdf.custom.utils.CustomChunksMergeUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ChunksMergeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ListUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class TextLineProcessor {

    private static final double ONE_LINE_PROBABILITY = 0.75;
    private static final Comparator<TextChunk> TEXT_CHUNK_COMPARATOR =
        Comparator.comparingDouble(o -> o.getBoundingBox().getLeftX());

    public static List<IObject> processTextLines(List<IObject> contents, String imagesDirectory) {
        List<IObject> newContents = new ArrayList<>();
        // Track which TextChunk immediately follows a whitespace chunk in stream order,
        // using reference identity so lookups are immune to TextChunk.equals() semantics.
        // Stream order may differ from visual (leftX) order in rare PDFs, but whitespace
        // chunks originate from the same PDF text operator as their adjacent text chunks,
        // so stream-order adjacency is reliable for this signal.
        Set<TextChunk> chunksAfterWhitespace = Collections.newSetFromMap(new IdentityHashMap<>());
        TextLine previousLine = new TextLine(new TextChunk(""));
        boolean isSeparateLine = false;
        boolean pendingWhitespace = false;
        for (IObject content : contents) {
            if (content instanceof TextChunk) {
                TextChunk textChunk = (TextChunk) content;
                if (textChunk.isWhiteSpaceChunk() || textChunk.isEmpty()) {
                    if (textChunk.isWhiteSpaceChunk()) {
                        pendingWhitespace = true;
                    }
                    continue;
                }
                if (pendingWhitespace) {
                    chunksAfterWhitespace.add(textChunk);
                    pendingWhitespace = false;
                }
                TextLine currentLine = new TextLine(textChunk);
                double oneLineProbability = ChunksMergeUtils.countOneLineProbability(new SemanticTextNode(), previousLine, currentLine);
                isSeparateLine |= (oneLineProbability < ONE_LINE_PROBABILITY) || previousLine.isHiddenText() != currentLine.isHiddenText();
                if (isSeparateLine) {
                    previousLine.setBoundingBox(new BoundingBox(previousLine.getBoundingBox()));
                    previousLine = currentLine;
                    newContents.add(previousLine);
                } else {
                    previousLine.add(currentLine);
                }
                isSeparateLine = false;
            } else {
                if (content instanceof TableBorder) {
                    isSeparateLine = true;
                }
                newContents.add(content);
                pendingWhitespace = false;
            }
        }

        if (newContents.size() > 1) {
            // Merge image chunks into text chunks
            for (int i = newContents.size() - 1; i >= 0; i--) {
                IObject content = newContents.get(i);
                if (content instanceof ImageChunk) {
                    if (i > 0) {
                        IObject preContent = newContents.get(i - 1);
                        if (preContent instanceof TextLine && CustomChunksMergeUtils.judgeIfOneLine((ImageChunk) content, (TextLine) preContent)) {
                            TextLine preTextLine = (TextLine) preContent;
                            TextChunk textChunk = CustomChunksMergeUtils.convertImageChunkToTextChunk((ImageChunk) content, preTextLine.getFontSize(), preTextLine.getBaseLine(), imagesDirectory);
                            preTextLine.add(textChunk);
                            newContents.remove(i);
                            continue;
                        }
                    }
                    if (i < newContents.size() - 1) {
                        IObject nextContent = newContents.get(i + 1);
                        if (nextContent instanceof TextLine && CustomChunksMergeUtils.judgeIfOneLine((ImageChunk) content, (TextLine) nextContent)) {
                            TextLine nextTextLine = (TextLine) nextContent;
                            TextChunk textChunk = CustomChunksMergeUtils.convertImageChunkToTextChunk((ImageChunk) content, nextTextLine.getFontSize(), nextTextLine.getBaseLine(), imagesDirectory);
                            nextTextLine.add(textChunk);
                            newContents.remove(i);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < newContents.size(); i++) {
            IObject content = newContents.get(i);
            if (content instanceof TextLine) {
                TextLine textLine = (TextLine) content;
                textLine.getTextChunks().sort(TEXT_CHUNK_COMPARATOR);
                List<TextChunk> textChunks = textLine.getTextChunks();
                for (int j = 0; j < textChunks.size(); j++) {
                    if (j > 0) {
                        TextChunk prevChunk = textChunks.get(j - 1);
                        TextChunk currentChunk = textChunks.get(j);
                        if (prevChunk.getFontSize() - currentChunk.getFontSize() > 3) {
                            if (prevChunk.getBaseLine() <= currentChunk.getBoundingBox().getBottomY() || currentChunk.getBaseLine() - prevChunk.getBaseLine() > 3) {
                                currentChunk.setValue("<sup>" + currentChunk.getValue() + "</sup>");
                                continue;
                            } else if (prevChunk.getBaseLine() >= currentChunk.getBoundingBox().getTopY() || prevChunk.getBaseLine() - currentChunk.getBaseLine() > 3) {
                                currentChunk.setValue("<sub>" + currentChunk.getValue() + "</sub>");
                                continue;
                            }
                        }
                    }
                    if (j < textChunks.size() - 1) {
                        TextChunk nextChunk = textChunks.get(j + 1);
                        TextChunk currentChunk = textChunks.get(j);
                        if (nextChunk.getFontSize() - currentChunk.getFontSize() >= 2.5) {
                            if (nextChunk.getBaseLine() <= currentChunk.getBoundingBox().getBottomY() || currentChunk.getBaseLine() - nextChunk.getBaseLine() > 3) {
                                currentChunk.setValue("<sup>" + currentChunk.getValue() + "</sup>");
                            } else if (nextChunk.getBaseLine() >= currentChunk.getBoundingBox().getTopY() || nextChunk.getBaseLine() - currentChunk.getBaseLine() > 3) {
                                currentChunk.setValue("<sub>" + currentChunk.getValue() + "</sub>");
                            }
                        }
                    }
                }
                double threshold = textLine.getFontSize() * TextChunkUtils.TEXT_LINE_SPACE_RATIO;
                newContents.set(i, getTextLineWithSpaces(textLine, threshold, chunksAfterWhitespace));
            }
        }
        linkTextLinesWithConnectedLineArtBullet(newContents);
        return newContents;
    }

    public static List<IObject> processTextLines(List<IObject> contents) {
        List<IObject> newContents = new ArrayList<>();
        // Track which TextChunk immediately follows a whitespace chunk in stream order,
        // using reference identity so lookups are immune to TextChunk.equals() semantics.
        // Stream order may differ from visual (leftX) order in rare PDFs, but whitespace
        // chunks originate from the same PDF text operator as their adjacent text chunks,
        // so stream-order adjacency is reliable for this signal.
        Set<TextChunk> chunksAfterWhitespace = Collections.newSetFromMap(new IdentityHashMap<>());
        TextLine previousLine = new TextLine(new TextChunk(""));
        boolean isSeparateLine = false;
        boolean pendingWhitespace = false;
        for (IObject content : contents) {
            if (content instanceof TextChunk) {
                TextChunk textChunk = (TextChunk) content;
                if (textChunk.isWhiteSpaceChunk() || textChunk.isEmpty()) {
                    if (textChunk.isWhiteSpaceChunk()) {
                        pendingWhitespace = true;
                    }
                    continue;
                }
                if (pendingWhitespace) {
                    chunksAfterWhitespace.add(textChunk);
                    pendingWhitespace = false;
                }
                TextLine currentLine = new TextLine(textChunk);
                double oneLineProbability = ChunksMergeUtils.countOneLineProbability(new SemanticTextNode(), previousLine, currentLine);
                isSeparateLine |= (oneLineProbability < ONE_LINE_PROBABILITY) || previousLine.isHiddenText() != currentLine.isHiddenText();
                if (isSeparateLine) {
                    previousLine.setBoundingBox(new BoundingBox(previousLine.getBoundingBox()));
                    previousLine = currentLine;
                    newContents.add(previousLine);
                } else {
                    previousLine.add(currentLine);
                }
                isSeparateLine = false;
            } else {
                if (content instanceof TableBorder) {
                    isSeparateLine = true;
                }
                newContents.add(content);
                pendingWhitespace = false;
            }
        }
        for (int i = 0; i < newContents.size(); i++) {
            IObject content = newContents.get(i);
            if (content instanceof TextLine) {
                TextLine textLine = (TextLine) content;
                textLine.getTextChunks().sort(TEXT_CHUNK_COMPARATOR);
                double threshold = textLine.getFontSize() * TextChunkUtils.TEXT_LINE_SPACE_RATIO;
                newContents.set(i, getTextLineWithSpaces(textLine, threshold, chunksAfterWhitespace));
            }
        }
        linkTextLinesWithConnectedLineArtBullet(newContents);
        return newContents;
    }

    private static TextLine getTextLineWithSpaces(TextLine textLine, double threshold,
                                                   Set<TextChunk> chunksAfterWhitespace) {
        List<TextChunk> textChunks = textLine.getTextChunks();
        TextChunk currentTextChunk = textChunks.get(0);
        double previousEnd = currentTextChunk.getTextEnd();
        TextLine newLine = new TextLine();
        newLine.add(currentTextChunk);
        for (int i = 1; i < textChunks.size(); i++) {
            currentTextChunk = textChunks.get(i);
            double currentStart = currentTextChunk.getTextStart();
            boolean hasGap = currentStart - previousEnd > threshold;
            boolean hadWhitespace = chunksAfterWhitespace.contains(currentTextChunk);
            if (hasGap || hadWhitespace) {
                TextChunk spaceChunk = new TextChunk(new BoundingBox(currentTextChunk.getBoundingBox()), " ", currentTextChunk.getFontName(),
                    currentTextChunk.getFontSize(), currentTextChunk.getFontWeight(), currentTextChunk.getItalicAngle(), currentTextChunk.getBaseLine(),
                    currentTextChunk.getFontColor(), null, currentTextChunk.getSlantDegree());
                spaceChunk.setTextStart(previousEnd);
                spaceChunk.setTextEnd(currentStart);
                spaceChunk.adjustSymbolEndsToBoundingBox(null);
                newLine.add(spaceChunk);
            }
            previousEnd = currentTextChunk.getTextEnd();
            newLine.add(currentTextChunk);
        }

        return newLine;
    }

    private static void linkTextLinesWithConnectedLineArtBullet(List<IObject> contents) {
        LineArtChunk lineArtChunk = null;
        for (IObject content : contents) {
            if (content instanceof LineArtChunk) {
                lineArtChunk = (LineArtChunk) content;
                continue;
            }
            if (content instanceof TableBorder) {
                lineArtChunk = null;
            }
            if (content instanceof TextLine && lineArtChunk != null) {
                TextLine textLine = (TextLine) content;
                if (isLineConnectedWithLineArt(textLine, lineArtChunk)) {
                    textLine.setConnectedLineArtLabel(lineArtChunk);
                }
                lineArtChunk = null;
            }
        }
    }

    private static boolean isLineConnectedWithLineArt(TextLine textLine, LineArtChunk lineArt) {
        return lineArt.getRightX() <= textLine.getLeftX() && lineArt.getBoundingBox().getHeight() <
                ListUtils.LIST_LABEL_HEIGHT_EPSILON * textLine.getBoundingBox().getHeight();
    }
}
