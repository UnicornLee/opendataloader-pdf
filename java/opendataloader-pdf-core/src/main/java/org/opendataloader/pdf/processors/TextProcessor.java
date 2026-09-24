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

import org.verapdf.gf.model.factory.chunks.ChunkParser;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ChunksMergeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class TextProcessor {

    private static final double MIN_TEXT_INTERSECTION_PERCENT = 0.5;
    private static final double MAX_TOP_DECORATION_IMAGE_EPSILON = 0.3;
    private static final double MAX_BOTTOM_DECORATION_IMAGE_EPSILON = 0.1;
    private static final double MAX_LEFT_DECORATION_IMAGE_EPSILON = 0.1;
    private static final double MAX_RIGHT_DECORATION_IMAGE_EPSILON = 1.5;
    private static final double NEIGHBORS_TEXT_CHUNKS_EPSILON = 0.1;
    private static final double TEXT_MIN_HEIGHT = 1;

    /**
     * Minimum x-overlap (as a ratio of the narrower symbol) for two equal characters to
     * be considered the same painted glyph.
     */
    private static final double OVERPRINT_COVERAGE_RATIO = 0.7;
    /**
     * A symbol narrower than this fraction of the font size is a zero-advance artifact
     * left behind by an overprint (the repeated draw only advances a fraction of a point).
     */
    private static final double OVERPRINT_DEGENERATE_FACTOR = 0.05;
    /** Absolute upper bound (pt) for a zero-advance artifact, independent of the font size. */
    private static final double OVERPRINT_DEGENERATE_ABS = 0.4;
    /**
     * A narrow symbol is only treated as an artifact when the same character is at least
     * this wide somewhere else in the same text row (so genuine small glyphs survive).
     */
    private static final double OVERPRINT_MIN_REAL_WIDTH = 2.0;
    /**
     * A symbol this much narrower than another copy of the same character is that copy split in
     * halves (or squeezed by a repeated draw) rather than a character of its own.
     */
    private static final double OVERPRINT_HALF_GLYPH_RATIO = 0.6;
    /** Two symbols belong to the same painted text row when their baselines are this close. */
    private static final double OVERPRINT_ROW_BASELINE_FACTOR = 0.05;
    private static final double OVERPRINT_ROW_BASELINE_ABS = 0.5;
    /** Relative font size tolerance used by the "same text row" test. */
    private static final double OVERPRINT_FONT_SIZE_RATIO = 0.1;
    /** Two equal characters painted at (nearly) the same x are copies of one glyph. */
    private static final double OVERPRINT_SAME_X_FACTOR = 0.05;
    private static final double OVERPRINT_SAME_X_ABS = 0.3;
    /**
     * How close (x gap between the spans) an equal character has to be to count as "painted on top
     * of / right next to" another one.
     */
    private static final double OVERPRINT_NEARBY_ABS = 0.5;

    public static void replaceUndefinedCharacters(List<IObject> contents, String replacementCharacterString) {
        if (ChunkParser.REPLACEMENT_CHARACTER_STRING.equals(replacementCharacterString)) {
            return;
        }
        for (IObject object : contents) {
            if (object instanceof TextChunk) {
                TextChunk textChunk = ((TextChunk) object);
                if (textChunk.getValue().contains(ChunkParser.REPLACEMENT_CHARACTER_STRING)) {
                    textChunk.setValue(textChunk.getValue().replace(ChunkParser.REPLACEMENT_CHARACTER_STRING, replacementCharacterString));
                }
            }
        }
    }

    public static double measureReplacementCharRatio(List<IObject> contents) {
        char replacementChar = ChunkParser.REPLACEMENT_CHARACTER_STRING.charAt(0);
        int totalChars = 0;
        int replacementChars = 0;
        for (IObject object : contents) {
            if (object instanceof TextChunk) {
                String value = ((TextChunk) object).getValue();
                totalChars += value.length();
                for (int i = 0; i < value.length(); i++) {
                    if (value.charAt(i) == replacementChar) {
                        replacementChars++;
                    }
                }
            }
        }
        if (totalChars == 0) {
            return 0.0;
        }
        return (double) replacementChars / totalChars;
    }

    public static void filterTinyText(List<IObject> contents) {
        for (int i = 0; i < contents.size(); i++) {
            IObject object = contents.get(i);
            if (object instanceof TextChunk) {
                TextChunk textChunk = ((TextChunk) object);
                if (textChunk.getBoundingBox().getHeight() <= TEXT_MIN_HEIGHT) {
                    contents.set(i, null);
                }
            }
        }
    }

    public static void trimTextChunksWhiteSpaces(List<IObject> contents) {
        for (int i = 0; i < contents.size(); i++) {
            IObject object = contents.get(i);
            if (object instanceof TextChunk) {
                contents.set(i, ChunksMergeUtils.getTrimTextChunk((TextChunk) object));
            }
        }
    }

    public static void mergeCloseTextChunks(List<IObject> contents) {
        for (int i = 0; i < contents.size() - 1; i++) {
            IObject object = contents.get(i);
            IObject nextObject = contents.get(i + 1);
            if (object instanceof TextChunk && nextObject instanceof TextChunk) {
                TextChunk textChunk = (TextChunk) object;
                TextChunk nextTextChunk = (TextChunk) nextObject;
                if (TextChunkUtils.areTextChunksHaveSameStyle(textChunk, nextTextChunk) &&
                    TextChunkUtils.areTextChunksHaveSameBaseLine(textChunk, nextTextChunk) &&
                    areNeighborsTextChunks(textChunk, nextTextChunk)) {
                    contents.set(i, null);
                    contents.set(i + 1, TextChunkUtils.unionTextChunks(textChunk, nextTextChunk));
                }
            }
        }
    }

    public static void removeSameTextChunks(List<IObject> contents) {
        DocumentProcessor.setIndexesForContentsList(contents);
        List<IObject> sortedTextChunks = contents.stream().filter(c -> c instanceof TextChunk).sorted(
                Comparator.comparing(x -> ((TextChunk) x).getValue())).collect(Collectors.toList());
        TextChunk lastTextChunk = null;
        for (IObject object : sortedTextChunks) {
            if (object instanceof TextChunk) {
                TextChunk currentTextChunk = (TextChunk) object;
                if (lastTextChunk != null && areSameTextChunks(lastTextChunk, currentTextChunk)) {
                    contents.set(lastTextChunk.getIndex(), null);
                }
                lastTextChunk = currentTextChunk;
            }
        }
    }

    public static boolean areSameTextChunks(TextChunk firstTextChunk, TextChunk secondTextChunk) {
        return Objects.equals(firstTextChunk.getValue(), secondTextChunk.getValue()) &&
                NodeUtils.areCloseNumbers(firstTextChunk.getWidth(), secondTextChunk.getWidth()) &&
                NodeUtils.areCloseNumbers(firstTextChunk.getHeight(), secondTextChunk.getHeight()) &&
                firstTextChunk.getBoundingBox().getIntersectionPercent(secondTextChunk.getBoundingBox()) > MIN_TEXT_INTERSECTION_PERCENT;
    }

    /**
     * Removes text that the PDF paints several times on top of itself.
     *
     * <p>Some producers (fake-bold emulation, "print twice" exporters) re-show the same glyph
     * run at a sub-point offset — in this project's sample
     * {@code docs/pdf/200812311782183951489043113-1.pdf} every glyph of the first three lines is
     * drawn four times at {@code (x +-0.24, baseline +-0.24)} inside a single content stream (no
     * render mode, marked-content or font difference is available to tell the copies apart).
     * Such text looks like one line but reaches the pipeline as two to four copies.</p>
     *
     * <p>{@link #removeSameTextChunks(List)} cannot clean this up: every copy is re-chunked
     * differently by the extractor (one copy absorbs the leading spaces/colons, another one
     * carries only the tail, single glyphs are split into two halves and the repeated draw leaves
     * zero-advance "phantom" symbols of ~0.24pt), so the chunk values are never equal. This pass
     * therefore works on {@link TextChunk#getSymbolEnds()} — the real per-symbol x coordinates —
     * clusters the equal characters painted at the same place and then keeps only the copy that
     * covers the most of them.</p>
     *
     * <p>A row is only touched when it actually looks overprinted (a zero-advance phantom symbol,
     * or two equal characters painted at (nearly) the same x); everything else is left untouched.
     * Partially dropped chunks are rebuilt from their surviving symbols with
     * {@link TextChunk#getTextChunk(TextChunk, int, int)}, which also shrinks their bounding box.</p>
     *
     * @param contents page contents, modified in place; dropped chunks are removed and partially
     *                 dropped ones are replaced by their remaining symbol runs
     */
    public static void removeOverprintedTextChunks(List<IObject> contents) {
        List<OverprintSymbol> symbols = collectOverprintSymbols(contents);
        if (symbols.size() < 2) {
            return;
        }
        // Global symbol order of every dropped character, grouped by chunk for the rebuild step.
        Set<Integer> droppedOrders = new TreeSet<>();
        for (List<OverprintSymbol> row : groupOverprintSymbolsIntoRows(symbols)) {
            if (isOverprintedRow(row)) {
                removeOverprintedSymbolsInRow(row, droppedOrders);
            }
        }
        if (!droppedOrders.isEmpty()) {
            rebuildChunksWithoutDroppedSymbols(contents, symbols, droppedOrders);
        }
    }

    /**
     * Flattens every eligible text chunk of a page into its individual symbols, using the real
     * per-symbol x coordinates reported by the extractor.
     */
    private static List<OverprintSymbol> collectOverprintSymbols(List<IObject> contents) {
        List<OverprintSymbol> symbols = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < contents.size(); chunkIndex++) {
            IObject object = contents.get(chunkIndex);
            if (!(object instanceof TextChunk)) {
                continue;
            }
            TextChunk chunk = (TextChunk) object;
            String value = chunk.getValue();
            List<Double> symbolEnds = chunk.getSymbolEnds();
            if (!chunk.isHorizontalText() || value == null || value.isEmpty() || symbolEnds == null
                    || symbolEnds.size() != value.length() + 1) {
                continue;
            }
            for (int symbolIndex = 0; symbolIndex < value.length(); symbolIndex++) {
                Double start = symbolEnds.get(symbolIndex);
                Double end = symbolEnds.get(symbolIndex + 1);
                if (start == null || end == null) {
                    continue;
                }
                symbols.add(new OverprintSymbol(symbols.size(), chunkIndex, symbolIndex, value.charAt(symbolIndex),
                    Math.min(start, end), Math.max(start, end), chunk.getBaseLine(), chunk.getFontSize(),
                    chunk.getFontName()));
            }
        }
        return symbols;
    }

    /**
     * Groups symbols into visual text rows (same baseline, same font and font size), top to bottom.
     */
    private static List<List<OverprintSymbol>> groupOverprintSymbolsIntoRows(List<OverprintSymbol> symbols) {
        List<OverprintSymbol> sorted = new ArrayList<>(symbols);
        sorted.sort(Comparator.comparingDouble(OverprintSymbol::getBaseLine).reversed());
        List<List<OverprintSymbol>> rows = new ArrayList<>();
        for (OverprintSymbol symbol : sorted) {
            List<OverprintSymbol> row = null;
            for (List<OverprintSymbol> candidate : rows) {
                if (isSameOverprintRow(candidate.get(0), symbol)) {
                    row = candidate;
                    break;
                }
            }
            if (row == null) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(symbol);
        }
        return rows;
    }

    private static boolean isSameOverprintRow(OverprintSymbol first, OverprintSymbol second) {
        double baseLineTolerance = Math.max(OVERPRINT_ROW_BASELINE_ABS,
            OVERPRINT_ROW_BASELINE_FACTOR * first.getFontSize());
        return Math.abs(first.getBaseLine() - second.getBaseLine()) <= baseLineTolerance &&
            Math.abs(first.getFontSize() - second.getFontSize()) <= OVERPRINT_FONT_SIZE_RATIO * first.getFontSize() &&
            Objects.equals(first.getFontName(), second.getFontName());
    }

    /**
     * True when the row carries the signature of an overprint: two equal characters painted on the
     * same spot, or a zero-advance phantom that sits on top of / right next to a real copy of the
     * same character. Rows without that signature are never modified, so ordinary text — including
     * characters that the extractor reports with a collapsed width but only once (e.g. a "「" of
     * 0.135pt inside otherwise normal text) — stays untouched.
     */
    private static boolean isOverprintedRow(List<OverprintSymbol> row) {
        Map<Character, Double> maxWidthByCharacter = maxWidthByCharacter(row);
        List<OverprintSymbol> byLeftX = sortedByLeft(row);
        for (int i = 0; i < byLeftX.size(); i++) {
            OverprintSymbol first = byLeftX.get(i);
            double maxLeftX = first.getRight() + OVERPRINT_NEARBY_ABS;
            for (int j = i + 1; j < byLeftX.size() && byLeftX.get(j).getLeft() <= maxLeftX; j++) {
                OverprintSymbol second = byLeftX.get(j);
                if (first.getValue() != second.getValue()) {
                    continue;
                }
                if (isSamePaintedPosition(first, second) || isSqueezedCopy(first, second, maxWidthByCharacter)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Two equal characters covering (nearly) the same span, i.e. one glyph painted twice. */
    private static boolean isSamePaintedPosition(OverprintSymbol first, OverprintSymbol second) {
        if (getOverlapRatio(first, second) < OVERPRINT_COVERAGE_RATIO) {
            return false;
        }
        double sameXTolerance = Math.max(OVERPRINT_SAME_X_ABS,
            OVERPRINT_SAME_X_FACTOR * first.getFontSize());
        if (Math.abs(first.getLeft() - second.getLeft()) <= sameXTolerance) {
            return true;
        }
        return Math.min(first.getWidth(), second.getWidth())
            <= OVERPRINT_HALF_GLYPH_RATIO * Math.max(first.getWidth(), second.getWidth());
    }

    /**
     * A zero-advance phantom (the repeated draw only advanced a fraction of a point) that overlaps
     * or directly abuts a real copy of the same character.
     */
    private static boolean isSqueezedCopy(OverprintSymbol first, OverprintSymbol second,
                                          Map<Character, Double> maxWidthByCharacter) {
        boolean firstIsPhantom = isDegenerateSymbol(first, maxWidthByCharacter);
        if (firstIsPhantom == isDegenerateSymbol(second, maxWidthByCharacter)) {
            return false;
        }
        OverprintSymbol phantom = firstIsPhantom ? first : second;
        OverprintSymbol real = firstIsPhantom ? second : first;
        return real.getWidth() >= OVERPRINT_MIN_REAL_WIDTH && getGap(phantom, real) <= OVERPRINT_NEARBY_ABS;
    }

    /** Gap between two x spans (0 when they overlap or touch). */
    private static double getGap(OverprintSymbol first, OverprintSymbol second) {
        return Math.max(0.0, Math.max(first.getLeft(), second.getLeft())
            - Math.min(first.getRight(), second.getRight()));
    }

    private static List<OverprintSymbol> sortedByLeft(List<OverprintSymbol> row) {
        List<OverprintSymbol> byLeftX = new ArrayList<>(row);
        byLeftX.sort(Comparator.comparingDouble(OverprintSymbol::getLeft));
        return byLeftX;
    }

    /**
     * Drops the overprint copies of one row: first the zero-advance phantom symbols, then all copies
     * but the most complete ones.
     *
     * <p>The remaining symbols are clustered into "painted positions" (connected components of
     * "equal character at the same x"), so a whole glyph and its split halves — or the four copies
     * of one character — become a single cluster. Afterwards the copy (source chunk) covering the
     * most still uncovered clusters wins, and every symbol of the covered clusters is dropped. This
     * keeps the surviving characters of a row inside as few chunks as possible, which matters
     * because downstream passes merge neighbouring chunks only.</p>
     */
    private static void removeOverprintedSymbolsInRow(List<OverprintSymbol> row, Set<Integer> droppedOrders) {
        Map<Character, Double> maxWidthByCharacter = maxWidthByCharacter(row);
        Set<Integer> phantomOrders = findSqueezedCopyOrders(row, maxWidthByCharacter);
        List<OverprintSymbol> candidates = new ArrayList<>();
        for (OverprintSymbol symbol : row) {
            if (phantomOrders.contains(symbol.getOrder())) {
                droppedOrders.add(symbol.getOrder());
            } else {
                candidates.add(symbol);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Map<OverprintSymbol, Integer> clusterBySymbol = clusterSymbols(row);
        Map<Integer, List<OverprintSymbol>> symbolsByChunk = new LinkedHashMap<>();
        for (OverprintSymbol symbol : candidates) {
            symbolsByChunk.computeIfAbsent(symbol.getChunkIndex(), key -> new ArrayList<>()).add(symbol);
        }
        Set<Integer> coveredClusters = new HashSet<>();
        Set<Integer> keptOrders = new HashSet<>();
        while (true) {
            List<OverprintSymbol> bestChunk = null;
            int bestUncovered = 0;
            for (List<OverprintSymbol> chunkSymbols : symbolsByChunk.values()) {
                Set<Integer> uncovered = new HashSet<>();
                for (OverprintSymbol symbol : chunkSymbols) {
                    Integer cluster = clusterBySymbol.get(symbol);
                    if (cluster != null && !coveredClusters.contains(cluster)) {
                        uncovered.add(cluster);
                    }
                }
                if (uncovered.size() > bestUncovered
                        || (uncovered.size() == bestUncovered && bestChunk != null && chunkSymbols.size() > bestChunk.size())) {
                    bestChunk = chunkSymbols;
                    bestUncovered = uncovered.size();
                }
            }
            if (bestChunk == null || bestUncovered == 0) {
                break;
            }
            // Keep only the positions this copy is the first to cover; the characters it shares with
            // an already kept copy are dropped from it as well.
            for (OverprintSymbol symbol : bestChunk) {
                if (coveredClusters.add(clusterBySymbol.get(symbol))) {
                    keptOrders.add(symbol.getOrder());
                }
            }
        }
        for (OverprintSymbol symbol : candidates) {
            if (!keptOrders.contains(symbol.getOrder())) {
                droppedOrders.add(symbol.getOrder());
            }
        }
    }

    /**
     * Groups the symbols of a row into painted positions: two symbols share a position when they
     * hold the same character and their x spans overlap, which also joins a whole glyph with the
     * halves it was split into (transitively, because every cluster keeps all of its members).
     */
    private static Map<OverprintSymbol, Integer> clusterSymbols(List<OverprintSymbol> row) {
        List<OverprintSymbol> byLeftX = new ArrayList<>(row);
        byLeftX.sort(Comparator.comparingDouble(OverprintSymbol::getLeft));
        Map<OverprintSymbol, Integer> clusterBySymbol = new HashMap<>();
        List<List<OverprintSymbol>> clusters = new ArrayList<>();
        for (OverprintSymbol symbol : byLeftX) {
            int cluster = -1;
            for (int i = 0; i < clusters.size() && cluster < 0; i++) {
                for (OverprintSymbol member : clusters.get(i)) {
                    if (member.getValue() == symbol.getValue()
                            && getOverlapRatio(member, symbol) >= OVERPRINT_COVERAGE_RATIO) {
                        cluster = i;
                        break;
                    }
                }
            }
            if (cluster < 0) {
                clusters.add(new ArrayList<>());
                cluster = clusters.size() - 1;
            }
            clusters.get(cluster).add(symbol);
            clusterBySymbol.put(symbol, cluster);
        }
        return clusterBySymbol;
    }

    /**
     * Orders of the zero-advance phantoms of this row, i.e. of every narrow symbol that sits on
     * top of / directly next to a real copy of the same character. A narrow symbol without such a
     * neighbour is left alone — the extractor does report genuinely narrow characters.
     */
    private static Set<Integer> findSqueezedCopyOrders(List<OverprintSymbol> row,
                                                       Map<Character, Double> maxWidthByCharacter) {
        Set<Integer> phantomOrders = new HashSet<>();
        List<OverprintSymbol> byLeftX = sortedByLeft(row);
        for (int i = 0; i < byLeftX.size(); i++) {
            OverprintSymbol first = byLeftX.get(i);
            double maxLeftX = first.getRight() + OVERPRINT_NEARBY_ABS;
            for (int j = i + 1; j < byLeftX.size() && byLeftX.get(j).getLeft() <= maxLeftX; j++) {
                OverprintSymbol second = byLeftX.get(j);
                if (first.getValue() != second.getValue() || !isSqueezedCopy(first, second, maxWidthByCharacter)) {
                    continue;
                }
                phantomOrders.add(isDegenerateSymbol(first, maxWidthByCharacter) ? first.getOrder() : second.getOrder());
            }
        }
        return phantomOrders;
    }

    private static Map<Character, Double> maxWidthByCharacter(List<OverprintSymbol> row) {
        Map<Character, Double> maxWidthByCharacter = new HashMap<>();
        for (OverprintSymbol symbol : row) {
            maxWidthByCharacter.merge(symbol.getValue(), symbol.getWidth(), Math::max);
        }
        return maxWidthByCharacter;
    }

    private static boolean isDegenerateSymbol(OverprintSymbol symbol, Map<Character, Double> maxWidthByCharacter) {
        Double maxWidth = maxWidthByCharacter.get(symbol.getValue());
        return symbol.getWidth() <= Math.max(OVERPRINT_DEGENERATE_ABS,
            OVERPRINT_DEGENERATE_FACTOR * symbol.getFontSize()) &&
            maxWidth != null && maxWidth >= OVERPRINT_MIN_REAL_WIDTH;
    }

    /** Overlap of two symbols divided by the width of the narrower one. */
    private static double getOverlapRatio(OverprintSymbol first, OverprintSymbol second) {
        double minWidth = Math.min(first.getWidth(), second.getWidth());
        if (minWidth <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(first.getRight(), second.getRight())
            - Math.max(first.getLeft(), second.getLeft())) / minWidth;
    }

    /**
     * Rebuilds the chunks whose symbols were dropped. Each chunk is replaced by the contiguous
     * runs of its surviving symbols (a chunk whose symbols are all duplicates disappears, the
     * other symbols of a partially duplicated chunk are re-emitted in x order).
     */
    private static void rebuildChunksWithoutDroppedSymbols(List<IObject> contents, List<OverprintSymbol> symbols,
                                                           Set<Integer> droppedOrders) {
        Map<Integer, List<OverprintSymbol>> symbolsByChunk = new LinkedHashMap<>();
        for (OverprintSymbol symbol : symbols) {
            symbolsByChunk.computeIfAbsent(symbol.getChunkIndex(), key -> new ArrayList<>()).add(symbol);
        }
        List<IObject> rebuilt = new ArrayList<>(contents.size());
        for (int chunkIndex = 0; chunkIndex < contents.size(); chunkIndex++) {
            List<OverprintSymbol> chunkSymbols = symbolsByChunk.get(chunkIndex);
            if (chunkSymbols == null) {   // not a text chunk / no usable symbol geometry
                rebuilt.add(contents.get(chunkIndex));
                continue;
            }
            TextChunk chunk = (TextChunk) contents.get(chunkIndex);
            int runStart = -1;
            int runEnd = -1;
            for (OverprintSymbol symbol : chunkSymbols) {
                if (droppedOrders.contains(symbol.getOrder())) {
                    if (runStart >= 0) {
                        addChunkPart(rebuilt, chunk, runStart, runEnd);
                        runStart = -1;
                    }
                } else {
                    if (runStart < 0) {
                        runStart = symbol.getSymbolIndex();
                    }
                    runEnd = symbol.getSymbolIndex() + 1;
                }
            }
            if (runStart >= 0) {
                addChunkPart(rebuilt, chunk, runStart, runEnd);
            }
        }
        contents.clear();
        contents.addAll(rebuilt);
    }

    private static void addChunkPart(List<IObject> rebuilt, TextChunk chunk, int start, int end) {
        TextChunk part = TextChunk.getTextChunk(chunk, start, end);
        if (part != null) {
            rebuilt.add(part);
        }
    }

    /** One painted character with its real x span, taken from {@link TextChunk#getSymbolEnds()}. */
    private static final class OverprintSymbol {
        private final int order;
        private final int chunkIndex;
        private final int symbolIndex;
        private final char value;
        private final double left;
        private final double right;
        private final double baseLine;
        private final double fontSize;
        private final String fontName;

        private OverprintSymbol(int order, int chunkIndex, int symbolIndex, char value, double left, double right,
                                double baseLine, double fontSize, String fontName) {
            this.order = order;
            this.chunkIndex = chunkIndex;
            this.symbolIndex = symbolIndex;
            this.value = value;
            this.left = left;
            this.right = right;
            this.baseLine = baseLine;
            this.fontSize = fontSize;
            this.fontName = fontName;
        }

        private int getOrder() {
            return order;
        }

        private int getChunkIndex() {
            return chunkIndex;
        }

        private int getSymbolIndex() {
            return symbolIndex;
        }

        private char getValue() {
            return value;
        }

        private double getLeft() {
            return left;
        }

        private double getRight() {
            return right;
        }

        private double getWidth() {
            return right - left;
        }

        private double getBaseLine() {
            return baseLine;
        }

        private double getFontSize() {
            return fontSize;
        }

        private String getFontName() {
            return fontName;
        }
    }

    public static void removeTextDecorationImages(List<IObject> contents) {
        TextChunk lastTextChunk = null;
        for (int index = 0; index < contents.size(); index++) {
            IObject object = contents.get(index);
            if (object instanceof TextChunk) {
                lastTextChunk = (TextChunk) object;
            } else if (object instanceof ImageChunk && lastTextChunk != null &&
                    isTextChunkDecorationImage((ImageChunk) object, lastTextChunk)) {
                contents.set(index, null);
            }
        }
    }

    public static boolean isTextChunkDecorationImage(ImageChunk imageChunk, TextChunk textChunk) {
        return NodeUtils.areCloseNumbers(imageChunk.getTopY(), textChunk.getTopY(), MAX_TOP_DECORATION_IMAGE_EPSILON * textChunk.getHeight()) &&
                NodeUtils.areCloseNumbers(imageChunk.getBottomY(), textChunk.getBottomY(), MAX_BOTTOM_DECORATION_IMAGE_EPSILON * textChunk.getHeight()) &&
                (NodeUtils.areCloseNumbers(imageChunk.getLeftX(), textChunk.getLeftX(), MAX_LEFT_DECORATION_IMAGE_EPSILON * textChunk.getHeight()) || imageChunk.getLeftX() > textChunk.getLeftX()) &&
                (NodeUtils.areCloseNumbers(imageChunk.getRightX(), textChunk.getRightX(), MAX_RIGHT_DECORATION_IMAGE_EPSILON * textChunk.getHeight()) || imageChunk.getRightX() < textChunk.getRightX());
    }

    private static boolean areNeighborsTextChunks(TextChunk firstTextChunk, TextChunk secondTextChunk) {
        return NodeUtils.areCloseNumbers(firstTextChunk.getTextEnd(), secondTextChunk.getTextStart(),
            NEIGHBORS_TEXT_CHUNKS_EPSILON * firstTextChunk.getBoundingBox().getHeight());
    }
}
