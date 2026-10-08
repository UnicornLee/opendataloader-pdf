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
package org.opendataloader.pdf.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Shared horizontal reading-order ordering used wherever a visual line of PDF content has
 * to be turned into text: {@link org.opendataloader.pdf.processors.TextLineProcessor} orders
 * a {@link org.verapdf.wcag.algorithms.entities.content.TextLine}'s chunks with it, and
 * {@link org.opendataloader.pdf.json.JsonWriter} re-orders the chunks it flattens out of a
 * table cell before assembling the cell text. Both consumers must produce the same order,
 * otherwise the JSON cell text can diverge from the paragraph text the processors built.
 */
public final class ReadingOrderSortUtils {

    /**
     * Smallest horizontal distance two element left edges may differ by before the clustering
     * puts them in different clusters. Absolute floor, used for small fonts.
     */
    public static final double X_TIE_MIN = 0.5;

    /**
     * Font-size multiplier applied to {@link #X_TIE_MIN}: the tie tolerance grows with the line's
     * font size so that wide CJK glyphs, whose pen origin sits to the left of their ink, stay tied
     * with the narrow glyph drawn beside them.
     */
    public static final double X_TIE_FACTOR = 0.08;

    private ReadingOrderSortUtils() {
    }

    /**
     * Orders a line's elements by their horizontal reading position, in place.
     *
     * <p>Left edges within one tie tolerance of a cluster's leading edge form one cluster, and the
     * elements inside a cluster keep their original (PDF stream) order. This matters whenever a
     * narrow glyph is drawn beside a wide CJK glyph: the em box of a full-width glyph starts
     * slightly to the left of the narrow bracket drawn just before it, so a strict left-edge sort
     * moves that bracket behind the whole CJK run. Stream order matches the visual order here, and
     * once the order is correct the measured gap between the former neighbours turns negative, so
     * downstream space synthesis stops producing the phantom spaces a strict sort would.</p>
     *
     * <p>Clustering is done on the left edges and never by a dead-zone comparator: a comparator that
     * reports close values as equal is not transitive and can make {@link List#sort} throw. Instead
     * every element gets the single monotone key {@code cluster * count + streamIndex}, so the
     * comparison stays a plain total order while clusters still run left to right.</p>
     *
     * @param objects      the line's elements to order in place; on entry they are in stream order
     *                     and the list must be mutable
     * @param leftXGetter  extracts an element's left edge; kept as a function so each caller can
     *                     use the same accessor it already relies on
     * @param fontSize     the line's representative font size, used to size the tie tolerance
     */
    public static <T> void sortByReadingOrder(List<T> objects, ToDoubleFunction<T> leftXGetter, double fontSize) {
        int count = objects.size();
        if (count < 2) {
            return;
        }
        double tolerance = Math.max(X_TIE_MIN, X_TIE_FACTOR * fontSize);
        double[] leftX = new double[count];
        for (int i = 0; i < count; i++) {
            leftX[i] = leftXGetter.applyAsDouble(objects.get(i));
        }
        // Walking the left edges in ascending order splits them into clusters: an element joins the
        // current cluster while it stays within tolerance of the edge that opened the cluster.
        Integer[] byLeft = new Integer[count];
        for (int i = 0; i < count; i++) {
            byLeft[i] = i;
        }
        Arrays.sort(byLeft, Comparator.comparingDouble((Integer index) -> leftX[index]).thenComparingInt(index -> index));
        int[] cluster = new int[count];
        int clusterCount = 0;
        double clusterLead = leftX[byLeft[0]];
        cluster[byLeft[0]] = 0;
        for (int k = 1; k < count; k++) {
            int index = byLeft[k];
            if (leftX[index] - clusterLead > tolerance) {
                clusterCount++;
                clusterLead = leftX[index];
            }
            cluster[index] = clusterCount;
        }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        final int keyModulus = count;
        Arrays.sort(order, Comparator.comparingLong(index -> (long) cluster[index] * keyModulus + index));
        List<T> sorted = new ArrayList<>(count);
        for (Integer index : order) {
            sorted.add(objects.get(index));
        }
        objects.clear();
        objects.addAll(sorted);
    }
}
