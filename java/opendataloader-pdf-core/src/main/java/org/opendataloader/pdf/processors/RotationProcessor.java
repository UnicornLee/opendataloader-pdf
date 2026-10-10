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

import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flattens text that a producer laid out rotated by a right angle inside an otherwise
 * upright page (the body of a table printed sideways while the header/footer stay upright).
 *
 * <p>The extractor already detects such runs: their glyphs carry a {@code slantDegree} of
 * about &plusmn;90 and their per-chunk reading axis is the vertical {@code Y} (baseLine is the
 * horizontal {@code X}). Downstream line/paragraph grouping, however, assumes horizontal text
 * with a vertical stacking axis, so the sideways rows — thin vertical strips whose bounding
 * boxes all overlap in {@code Y} — collapse into a single crammed block during paragraph
 * merging. This processor removes that mismatch by rewriting the rotated runs back into a
 * normal horizontal coordinate frame (an exact 90&deg; rotation about the region bounds, so
 * every corner and the reading axis map cleanly onto an axis-aligned box).</p>
 *
 * <p>Only pages where the rotated run is the dominant body are touched; isolated rotated
 * glyphs on an otherwise horizontal page (below {@link #MIN_ROTATED_CHUNKS}) are left exactly
 * as they are, so ordinary documents see no change. Header/footer runs that were extracted with
 * {@code slantDegree == 0} are deliberately not moved — the two orientations are physically at
 * right angles on the source page and cannot both be upright in one frame; the body is what the
 * reader rotates the page to read, so it is the body that is flattened here.</p>
 */
public class RotationProcessor {

    /** Tolerance (degrees) for treating a chunk as orthogonally rotated. */
    private static final double ROTATED_SLANT_TOLERANCE = 1.0;

    /** Minimum number of rotated chunks on a page before the run is treated as rotated body. */
    private static final int MIN_ROTATED_CHUNKS = 8;

    /**
     * Upper bound on how far the body frame may be widened past the PDF's own reading advance to
     * absorb browser reflow of leader dots / inter-column gaps. The per-character estimate is
     * deliberately crude (it uses the line ink thickness as the font size, which overstates the
     * true glyph advance), so it is clamped to this multiple of the reading length; beyond it the
     * extra width is rendering slack the wrapped item box never occupies, which would just show up
     * as dead space on the right.
     */
    private static final double MAX_RENDER_WIDTH_RATIO = 1.2;

    private RotationProcessor() {
    }

    /**
     * De-rotates the dominant rotated text run of a page in place.
     *
     * @param contents  the page's extracted objects (TextChunks mutated directly)
     * @param pageWidth the page's true crop-box width, used to size the widened frame that
     *                  must host the flattened body (its reading axis is the source page's
     *                  vertical extent, which can exceed a portrait width)
     * @return {@code {width, height}} of the de-rotated region in the new horizontal frame, or
     *         {@code null} when the page was left untouched (no dominant rotated run)
     */
    public static double[] processRotation(List<IObject> contents, double pageWidth) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        List<TextChunk> rotated = new ArrayList<>();
        for (IObject content : contents) {
            if (content instanceof TextChunk && isRotated((TextChunk) content)) {
                rotated.add((TextChunk) content);
            }
        }
        if (rotated.size() < MIN_ROTATED_CHUNKS) {
            return null;
        }

        // Region of the rotated run, in the source (portrait) page space.
        double rx0 = Double.MAX_VALUE;
        double rx1 = -Double.MAX_VALUE;
        double ry0 = Double.MAX_VALUE;
        double ry1 = -Double.MAX_VALUE;
        int bottomUp = 0;
        for (TextChunk chunk : rotated) {
            BoundingBox box = chunk.getBoundingBox();
            if (box == null) {
                continue;
            }
            rx0 = Math.min(rx0, box.getLeftX());
            rx1 = Math.max(rx1, box.getRightX());
            ry0 = Math.min(ry0, box.getBottomY());
            ry1 = Math.max(ry1, box.getTopY());
            if (chunk.getSlantDegree() >= 0) {
                bottomUp++;
            }
        }
        if (rx0 > rx1 || ry0 > ry1) {
            return null;
        }
        // Majority orientation decides the whole region (rows of one run share it).
        boolean counterClockwise = bottomUp * 2 >= rotated.size();

        // Flattening turns the source vertical extent (ry1 - ry0) into the body's horizontal
        // length. That is only the PDF's compressed advance: a browser reflows the sideways rows
        // (leader dots, inter-column spacing) with a proportional web font and they come out
        // wider than the advance the producer squeezed into [ry0, ry1]. Left-aligned items then
        // grow rightward past the frame, so the right margin is eaten and text runs off the page.
        // Size the frame to the widest row's ESTIMATED rendered width instead of the raw advance,
        // and keep the run's own left/right margins (rx0 from the page edge, pageWidth - rx1 on
        // the right) on top of it. The frame is only widened, never shrunk.
        double readingLength = ry1 - ry0;
        double estimatedWidth = estimateMaxRenderedWidth(rotated);
        double contentWidth = Math.min(Math.max(readingLength, estimatedWidth), readingLength * MAX_RENDER_WIDTH_RATIO);
        double leftMargin = rx0;
        double rightMargin = pageWidth - rx1;
        double newWidth = Math.max(pageWidth, contentWidth + leftMargin + rightMargin);
        // Stretch the reading axis of every flattened row by the same factor the frame was widened,
        // so the body's own boxes grow to fill the widened page. Without this the line boxes stay
        // at the compressed PDF advance while only the page is widened, and the extra frame shows
        // up as dead whitespace to the right of the (unchanged) text. Reading order and the vertical
        // stack are untouched: the scale only spreads glyphs along X.
        double readingScale = readingLength > 0.0 ? contentWidth / readingLength : 1.0;

        for (TextChunk chunk : rotated) {
            if (isRotated(chunk)) {
                derotate(chunk, rx0, rx1, ry0, ry1, counterClockwise, leftMargin, readingScale);
            }
        }
        // Reading axis (source Y) becomes the new width; stacking axis (source X) the new height.
        return new double[]{newWidth, rx1 - rx0};
    }

    private static boolean isRotated(TextChunk chunk) {
        double slant = Math.abs(chunk.getSlantDegree());
        return Math.abs(slant - 90.0) <= ROTATED_SLANT_TOLERANCE;
    }

    /**
     * Estimates the horizontal space the flattened body needs once a browser lays it out, as
     * opposed to the PDF's compressed reading advance. Cells are grouped into source rows (they
     * share the stacking coordinate, i.e. the {@code X} band, since the reading axis is vertical);
     * each cell's rendered width is estimated from its glyph height (the line thickness, which is
     * the font size) times a per-character advance (full-width for CJK, half for Latin/digits/
     * spaces). The widest row wins. This is a deliberate over-estimate so the frame is roomy
     * rather than leaving the text to overflow the right margin.
     */
    private static double estimateMaxRenderedWidth(List<TextChunk> rotated) {
        List<double[]> cells = new ArrayList<>();
        double thicknessSum = 0.0;
        int counted = 0;
        for (TextChunk chunk : rotated) {
            BoundingBox box = chunk.getBoundingBox();
            String text = chunk.getValue();
            if (box == null || text == null || text.isEmpty()) {
                continue;
            }
            double fontSize = Math.abs(box.getRightX() - box.getLeftX());
            if (fontSize <= 0.0) {
                continue;
            }
            thicknessSum += fontSize;
            counted++;
            double estimated = 0.0;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                estimated += fontSize * (isFullWidth(codePoint) ? 1.0 : 0.5);
            }
            double rowCoord = (box.getLeftX() + box.getRightX()) / 2.0;
            cells.add(new double[]{rowCoord, estimated});
        }
        if (cells.isEmpty()) {
            return 0.0;
        }
        // Cells of one row sit at (nearly) the same stacking coordinate; rows are separated by
        // roughly one line thickness, so a fraction of the average font size distinguishes them.
        double gapTolerance = (thicknessSum / counted) * 0.6;
        cells.sort(Comparator.comparingDouble(cell -> cell[0]));
        double widestRow = 0.0;
        double rowWidth = 0.0;
        double previousCoord = Double.NEGATIVE_INFINITY;
        for (double[] cell : cells) {
            if (cell[0] - previousCoord > gapTolerance) {
                widestRow = Math.max(widestRow, rowWidth);
                rowWidth = 0.0;
            }
            rowWidth += cell[1];
            previousCoord = cell[0];
        }
        return Math.max(widestRow, rowWidth);
    }

    /**
     * Whether a code point occupies a full em in a proportional rendering (CJK ideographs, their
     * punctuation and full-width forms) as opposed to half an em (Latin, digits, spaces, leaders).
     */
    private static boolean isFullWidth(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }

    /**
     * Rewrites one rotated chunk into the horizontal frame. The reading axis ({@code Y} in the
     * source, holding textStart/textEnd/symbolEnds) becomes {@code X}; the stacking axis
     * ({@code X}, holding baseLine) becomes {@code Y}, ordered so rows read top-to-bottom.
     * {@code xOffset} shifts every reading-axis ({@code X}) value right so the flattened body keeps
     * a left margin instead of sitting flush against the page edge, and {@code readingScale} spreads
     * the reading axis so the row boxes fill the widened frame instead of leaving the extra width as
     * dead whitespace on the right.
     */
    private static void derotate(TextChunk chunk, double rx0, double rx1, double ry0, double ry1,
                                 boolean counterClockwise, double xOffset, double readingScale) {
        BoundingBox box = chunk.getBoundingBox();
        // Capture every source value up front: getTextStart/getTextEnd read through the box and
        // interpret the axis from the current slant, so they must be sampled while slant is 90.
        double leftX = box.getLeftX();
        double bottomY = box.getBottomY();
        double rightX = box.getRightX();
        double topY = box.getTopY();
        double oldBaseLine = chunk.getBaseLine();
        double oldTextStart = chunk.getTextStart();
        double oldTextEnd = chunk.getTextEnd();
        List<Double> symbolEnds = chunk.getSymbolEnds();

        double newLeftX;
        double newRightX;
        double newBottomY;
        double newTopY;
        double newBaseLine;
        double newTextStart;
        double newTextEnd;
        // Anchor the flattened block in the page's global vertical layout instead of a
        // region-local origin. The rotated run physically occupied [ry0, ry1] on the portrait
        // page (its top row sitting just under the upright heading, its footer/page-number
        // below [ry0, ry1]). Flattening turns the source width (rx1 - rx0) into the block's
        // height, so translating the new stacking axis so that the block TOP lands at ry1 keeps
        // the body adjacent to the content above it and entirely above the footer — otherwise
        // the body drifts to [0, rx1 - rx0] (a detached band), opening a gap under the heading
        // and letting the upright footer (whose global Y falls inside that band) sort into the
        // middle of the rows.
        double stackOffset = ry1 - (rx1 - rx0);
        List<Double> newSymbolEnds = null;
        if (counterClockwise) {
            // +90 (bottom-up): new_x = (y - ry0) * scale + xOffset ; new_y = rx1 - x (+ stackOffset)
            newLeftX = (bottomY - ry0) * readingScale + xOffset;
            newRightX = (topY - ry0) * readingScale + xOffset;
            newBottomY = rx1 - rightX + stackOffset;
            newTopY = rx1 - leftX + stackOffset;
            newBaseLine = rx1 - oldBaseLine + stackOffset;
            newTextStart = (oldTextStart - ry0) * readingScale + xOffset;
            newTextEnd = (oldTextEnd - ry0) * readingScale + xOffset;
            if (symbolEnds != null) {
                newSymbolEnds = new ArrayList<>(symbolEnds.size());
                for (Double symbolEnd : symbolEnds) {
                    newSymbolEnds.add(symbolEnd == null ? null : (symbolEnd - ry0) * readingScale + xOffset);
                }
            }
        } else {
            // -90 (top-down): new_x = (ry1 - y) * scale + xOffset ; new_y = x - rx0 (+ stackOffset)
            newLeftX = (ry1 - topY) * readingScale + xOffset;
            newRightX = (ry1 - bottomY) * readingScale + xOffset;
            newBottomY = leftX - rx0 + stackOffset;
            newTopY = rightX - rx0 + stackOffset;
            newBaseLine = oldBaseLine - rx0 + stackOffset;
            newTextStart = (ry1 - oldTextStart) * readingScale + xOffset;
            newTextEnd = (ry1 - oldTextEnd) * readingScale + xOffset;
            if (symbolEnds != null) {
                newSymbolEnds = new ArrayList<>(symbolEnds.size());
                for (Double symbolEnd : symbolEnds) {
                    newSymbolEnds.add(symbolEnd == null ? null : (ry1 - symbolEnd) * readingScale + xOffset);
                }
            }
        }

        // Switch to the horizontal model FIRST. setTextStart/setTextEnd write through to the
        // bounding box and choose the axis from the current slant: while the chunk is still
        // slant=90 they would overwrite the vertical (Y) edges with the reading-axis values.
        chunk.setSlantDegree(0.0);

        box.setLeftX(Math.min(newLeftX, newRightX));
        box.setRightX(Math.max(newLeftX, newRightX));
        box.setBottomY(Math.min(newBottomY, newTopY));
        box.setTopY(Math.max(newBottomY, newTopY));

        chunk.setBoundingBox(box);
        chunk.setBaseLine(newBaseLine);
        chunk.setTextStart(newTextStart);
        chunk.setTextEnd(newTextEnd);
        if (newSymbolEnds != null && !newSymbolEnds.isEmpty()) {
            chunk.setSymbolEnds(newSymbolEnds);
        }
    }
}
