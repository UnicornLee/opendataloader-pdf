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

import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges runs of consecutive {@link ImageChunk}s that belong to one figure.
 * The combined bounding box of each run is rendered as a single screenshot and
 * replaces the original chunks, so content that the PDF engine split into several
 * adjacent image fragments (for example a figure stored as a sequence of tiles) is
 * preserved as one image instead of a column of broken images.
 *
 * <p>Runs are collected in {@code pageContents} order (the caller sorts the
 * list top-to-bottom beforehand) and are broken by any non-image element, by a
 * vertical gap larger than a fixed ratio of the smaller neighbour's height and
 * by a horizontal gap larger than {@link #MAX_HORIZONTAL_GAP_RATIO} times the
 * smaller neighbour's short side. Both gaps are measured against the union of the
 * run collected so far, not against its last fragment.
 */
public final class ConsecutiveImageProcessor {

    /**
     * Maximum vertical gap, as a ratio of the smaller of the two neighbours'
     * heights, for two consecutive images to be considered part of the same
     * run. Images overlapping vertically (e.g. side by side) still have to be
     * horizontally adjacent; see {@link #MAX_HORIZONTAL_GAP_RATIO}.
     */
    private static final double MAX_VERTICAL_GAP_RATIO = 0.5;

    /**
     * Floor for the horizontal gap tolerance (pt), so that fragments of one
     * figure still merge when the boxes are too small for
     * {@link #MAX_HORIZONTAL_GAP_RATIO} to say anything useful.
     */
    private static final double MIN_HORIZONTAL_GAP = 5.0;

    /**
     * Horizontal gap tolerance as a multiple of the smaller neighbour's short side.
     * <p>
     * The vertical check alone cannot separate side-by-side figures: two boxes next
     * to each other always have a negative vertical gap, so they always look stacked.
     * Measured, that let the "前標誌 / 新標誌" pair of an announcement (a 77.6 pt
     * gutter) and, worse, the two 14.1 pt crop marks at opposite ends of a 550 pt
     * print line (a 521.6 pt gutter) merge into a single screenshot each. An absolute
     * threshold cannot tell those apart either, because both gutters are "large";
     * scaled by the neighbour's own size they are not: 77.6 pt is 1.5 short sides
     * while 521.6 pt is 37. Anything up to two short sides is read as one figure
     * drawn in parts — including a logo lockup laid out as a mark beside a wordmark.
     */
    private static final double MAX_HORIZONTAL_GAP_RATIO = 2.0;

    private ConsecutiveImageProcessor() {
    }

    /**
     * Walks {@code pageContents} once, collapsing each run of adjacent
     * {@link ImageChunk}s that are close together into a single screenshot.
     *
     * @param pageContents the current page contents (will be replaced)
     * @param pageNumber   0-based page number
     * @param imagesUtils  image renderer / saver
     */
    public static void processConsecutiveImages(List<IObject> pageContents, int pageNumber,
                                                ImagesUtils imagesUtils) {
        if (pageContents == null || pageContents.isEmpty() || imagesUtils == null) {
            return;
        }
        List<IObject> result = new ArrayList<>(pageContents.size());
        int i = 0;
        while (i < pageContents.size()) {
            IObject current = pageContents.get(i);
            if (!(current instanceof ImageChunk)) {
                result.add(current);
                i++;
                continue;
            }

            List<IObject> run = new ArrayList<>();
            run.add(current);
            BoundingBox runBox = current.getBoundingBox() == null ? null
                : new BoundingBox(current.getBoundingBox());
            int j = i + 1;
            while (j < pageContents.size() && pageContents.get(j) instanceof ImageChunk) {
                ImageChunk next = (ImageChunk) pageContents.get(j);
                if (!areClose(runBox, next.getBoundingBox())) {
                    break;
                }
                run.add(next);
                runBox.union(next.getBoundingBox());
                j++;
            }

            if (run.size() >= 2) {
                BoundingBox union = BoundingBoxGroupUtils.unionBoundingBoxes(run, pageNumber);
                if (union != null && !union.isEmpty()) {
                    ImageChunk merged = new ImageChunk(union);
                    imagesUtils.saveImageChunk(merged);
                    result.add(merged);
                    i = j;
                    continue;
                }
            }
            result.add(current);
            i++;
        }
        pageContents.clear();
        pageContents.addAll(result);
    }

    /**
     * Returns true when {@code next} belongs to the figure collected so far.
     * {@code runBox} is the union of the run up to this point and is expected to
     * appear before {@code next} in reading order (pageContents is sorted
     * top-to-bottom), so a negative vertical gap means the two overlap vertically
     * (side-by-side fragments).
     *
     * <p>Measuring against the whole run rather than its last element matters: a
     * figure stored as tiles ends in a narrow tile, and judging the next fragment
     * from that tile alone understates how far it still belongs to the figure. On the
     * measured logo comparison the second tile of the right-hand logo is only 31.3 pt
     * wide, which shrank the gap tolerance to 62.6 pt against a 201.9 pt distance and
     * split the pair.</p>
     */
    private static boolean areClose(BoundingBox runBox, BoundingBox next) {
        if (runBox == null || next == null || runBox.isEmpty() || next.isEmpty()) {
            return false;
        }
        double gap = runBox.getBottomY() - next.getTopY();
        double maxGap = Math.min(runBox.getHeight(), next.getHeight()) * MAX_VERTICAL_GAP_RATIO;
        if (gap > maxGap) {
            return false;
        }
        return areHorizontallyAdjacent(runBox, next);
    }

    /**
     * Returns true when the two boxes share horizontal extent or are separated by at
     * most the fragment tolerance below. Negative gaps are the norm for tiles of one
     * figure, which touch or slightly overlap.
     */
    private static boolean areHorizontallyAdjacent(BoundingBox a, BoundingBox b) {
        double horizontalGap = Math.max(a.getLeftX() - b.getRightX(), b.getLeftX() - a.getRightX());
        double tolerance = Math.max(MIN_HORIZONTAL_GAP, MAX_HORIZONTAL_GAP_RATIO
            * Math.min(shortSide(a), shortSide(b)));
        return horizontalGap <= tolerance;
    }

    private static double shortSide(BoundingBox box) {
        return Math.min(box.getWidth(), box.getHeight());
    }
}
