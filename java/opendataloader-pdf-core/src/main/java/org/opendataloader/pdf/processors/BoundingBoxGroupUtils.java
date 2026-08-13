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

import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.List;

/**
 * Shared bbox/group utilities used by the shape and line-art processors
 * ({@link BarChartProcessor}, {@link FlowchartProcessor}, {@link LineArtProcessor}).
 * Centralising these keeps the processors themselves focused on recognition.
 */
final class BoundingBoxGroupUtils {

    /** Minimum vertical overlap ratio required to count two boxes as intersecting. */
    private static final double MIN_OVERLAP_PERCENT = 0.05;

    private BoundingBoxGroupUtils() {
    }

    /**
     * Builds the union bbox of every non-empty bbox in {@code group}.
     * Returns {@code null} when no element contributes a usable bbox.
     */
    static BoundingBox unionBoundingBoxes(List<IObject> group, int pageNumber) {
        BoundingBox union = new BoundingBox(pageNumber);
        boolean hasValid = false;
        for (IObject obj : group) {
            BoundingBox bbox = obj.getBoundingBox();
            if (bbox != null && !bbox.isEmpty()) {
                union.union(bbox);
                hasValid = true;
            }
        }
        return hasValid ? union : null;
    }

    /**
     * Like {@link #unionBoundingBoxes(List, int)} but counts only
     * {@link ShapeChunk} entries.
     */
    static BoundingBox unionShapeBoundingBoxes(List<IObject> shapeGroup, int pageNumber) {
        BoundingBox union = new BoundingBox(pageNumber);
        boolean hasValid = false;
        for (IObject obj : shapeGroup) {
            if (obj instanceof ShapeChunk) {
                BoundingBox bbox = obj.getBoundingBox();
                if (bbox != null && !bbox.isEmpty()) {
                    union.union(bbox);
                    hasValid = true;
                }
            }
        }
        return hasValid ? union : null;
    }

    /**
     * Returns true when at least one entry in {@code group} is a
     * {@link ShapeChunk} whose type is {@link ShapeChunk#TYPE_BAR_CHART}.
     */
    static boolean containsBarChart(List<IObject> group) {
        for (IObject obj : group) {
            if (obj instanceof ShapeChunk
                    && ShapeChunk.TYPE_BAR_CHART.equals(((ShapeChunk) obj).getShapeType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when at least one entry in {@code group} is a
     * {@link ShapeChunk} whose type is {@link ShapeChunk#TYPE_ARROW}.
     */
    static boolean containsArrow(List<IObject> group) {
        for (IObject obj : group) {
            if (obj instanceof ShapeChunk
                && ShapeChunk.TYPE_ARROW.equals(((ShapeChunk) obj).getShapeType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when {@code inner} is mostly contained in {@code outer}
     * vertically (more than 50 % of its vertical extent is covered).
     */
    static boolean isVerticallyMostlyInside(BoundingBox outer, BoundingBox inner) {
        if (inner == null || inner.isEmpty()) {
            return false;
        }
        double overlapPercent = inner.getVerticalIntersectionPercent(outer);
        return overlapPercent > 0.5;
    }

    /**
     * Returns true when {@code candidateBox} and {@code lineArtBox} have
     * significant vertical overlap (more than {@value #MIN_OVERLAP_PERCENT}
     * in either direction).
     */
    static boolean hasSignificantOverlap(BoundingBox candidateBox, BoundingBox lineArtBox) {
        if (candidateBox == null || candidateBox.isEmpty() || lineArtBox == null || lineArtBox.isEmpty()) {
            return false;
        }
        double candidateOverlap = candidateBox.getVerticalIntersectionPercent(lineArtBox);
        double lineArtOverlap = lineArtBox.getVerticalIntersectionPercent(candidateBox);
        return Math.max(candidateOverlap, lineArtOverlap) > MIN_OVERLAP_PERCENT;
    }
}
