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
 * Merges runs of consecutive {@link ImageChunk}s that are stacked closely
 * together on the page. The combined bounding box of each run is rendered as
 * a single screenshot and replaces the original chunks, so content that the
 * PDF engine split into several adjacent image fragments (for example a
 * figure stored as a sequence of tiles) is preserved as one image instead of
 * a column of broken images.
 *
 * <p>Runs are collected in {@code pageContents} order (the caller sorts the
 * list top-to-bottom beforehand) and are broken by any non-image element and
 * by any vertical gap larger than a fixed ratio of the smaller neighbour's
 * height.
 */
public final class ConsecutiveImageProcessor {

    /**
     * Maximum vertical gap, as a ratio of the smaller of the two neighbours'
     * heights, for two consecutive images to be considered part of the same
     * run. Images overlapping vertically (e.g. side by side) always merge.
     */
    private static final double MAX_VERTICAL_GAP_RATIO = 0.5;

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
            int j = i + 1;
            while (j < pageContents.size() && pageContents.get(j) instanceof ImageChunk) {
                ImageChunk previous = (ImageChunk) run.get(run.size() - 1);
                ImageChunk next = (ImageChunk) pageContents.get(j);
                if (!areClose(previous.getBoundingBox(), next.getBoundingBox())) {
                    break;
                }
                run.add(next);
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
     * Returns true when {@code upper} and {@code lower} are close enough to
     * be merged. {@code upper} is expected to appear before {@code lower} in
     * reading order (pageContents is sorted top-to-bottom), so a negative gap
     * means the two images overlap vertically (side-by-side fragments).
     */
    private static boolean areClose(BoundingBox upper, BoundingBox lower) {
        if (upper == null || lower == null || upper.isEmpty() || lower.isEmpty()) {
            return false;
        }
        double gap = upper.getBottomY() - lower.getTopY();
        double maxGap = Math.min(upper.getHeight(), lower.getHeight()) * MAX_VERTICAL_GAP_RATIO;
        return gap <= maxGap;
    }
}
