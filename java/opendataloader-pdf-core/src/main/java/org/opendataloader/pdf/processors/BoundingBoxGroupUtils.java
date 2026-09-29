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
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.Table;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
        return containsType(group, ShapeChunk.TYPE_BAR_CHART);
    }

    /**
     * Returns true when at least one entry in {@code group} is a
     * {@link ShapeChunk} whose type is {@link ShapeChunk#TYPE_PIE_CHART}.
     */
    static boolean containsPieChart(List<IObject> group) {
        return containsType(group, ShapeChunk.TYPE_PIE_CHART);
    }

    /**
     * Returns true when at least one entry in {@code group} is a
     * {@link ShapeChunk} whose type equals {@code type}.
     */
    static boolean containsType(List<IObject> group, String type) {
        for (IObject obj : group) {
            if (obj instanceof ShapeChunk
                    && type.equals(((ShapeChunk) obj).getShapeType())) {
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

    /**
     * Safety cap on the number of cuts applied while shrinking a screenshot box.
     */
    private static final int MAX_SHRINK_STEPS = 30;
    /**
     * Distance (pt) by which a cut is placed outside the element that must not be
     * cropped, so the element ends up clearly outside the screenshot box.
     */
    private static final double BOUNDARY_EPSILON = 0.5;
    /** Tolerance (pt) when deciding whether an element touches the screenshot box. */
    private static final double BLOCKER_EPSILON = 0.1;

    /**
     * A screenshot box together with the page contents it replaces.
     */
    static final class ScreenshotFit {
        /** Box to render; {@code null} when the region must not be cropped at all. */
        final BoundingBox box;
        /** Contents that leave the text layer because the screenshot shows them. */
        final List<IObject> removableContents;

        ScreenshotFit(BoundingBox box, List<IObject> removableContents) {
            this.box = box;
            this.removableContents = removableContents;
        }
    }

    /**
     * Fits a screenshot box to the contents it replaces, so that nothing is emitted
     * twice: whatever the box covers is either removed from the text layer or not
     * covered at all.
     *
     * <p>A screenshot replaces everything it covers, but the collection steps around it
     * deliberately skip body text (and text that is only partially inside the region).
     * Such an element would stay in the text layer while still being rendered inside the
     * image — the reader sees it twice. The box is therefore cut back on the side of
     * every element that would be covered without being removed ({@code remaining}
     * elements of the page), until the box covers nothing but the diagram. When an
     * element cannot be cut away (it overlaps the diagram's own shapes, or the cut would
     * leave those shapes outside the box), the whole crop is abandoned.</p>
     *
     * @param box        the screenshot box the caller computed
     * @param shapeBox   union box of the shapes that make up the diagram; the box must
     *                   keep covering them, and they are the anchor used to decide which
     *                   side of a blocker to cut
     * @param replaceable contents the caller intends to remove together with the crop
     * @param pageContents the current page contents (used to find elements that stay)
     * @param shapeSet   identity set of the diagram's shapes (skipped as blockers)
     * @return the fitted box and the subset of {@code replaceable} that may be removed;
     *         {@link ScreenshotFit#box} is {@code null} when no valid crop exists
     */
    static ScreenshotFit fitScreenshotToContents(BoundingBox box, BoundingBox shapeBox,
                                                 List<IObject> replaceable, List<IObject> pageContents,
                                                 Set<IObject> shapeSet) {
        if (box == null || box.isEmpty() || shapeBox == null || shapeBox.isEmpty()) {
            return new ScreenshotFit(null, Collections.emptyList());
        }
        BoundingBox fitted = new BoundingBox(box);
        for (int step = 0; step <= MAX_SHRINK_STEPS; step++) {
            IObject blocker = findCoveredElement(pageContents, shapeSet, fitted);
            if (blocker == null) {
                if (fitted.getWidth() <= 0 || fitted.getHeight() <= 0
                        || !covers(fitted, shapeBox)) {
                    return new ScreenshotFit(null, Collections.emptyList());
                }
                return new ScreenshotFit(fitted, keepFullyInside(fitted, replaceable));
            }
            if (!cutAway(fitted, shapeBox, blocker.getBoundingBox())) {
                return new ScreenshotFit(null, Collections.emptyList());
            }
        }
        return new ScreenshotFit(null, Collections.emptyList());
    }

    /**
     * Returns the first output element that {@code box} covers only partially, or
     * {@code null} when the box is consistent.
     *
     * <p>Elements that are fully covered are removed together with the crop (see
     * {@link #keepFullyInside}), so they never block it. Everything else would stay in
     * the text layer while being rendered inside the image — the box is cut back on its
     * side instead.</p>
     *
     * <p>Only element types that are written to the output are considered: shapes and
     * line art never reach the text flow, so covering them cannot duplicate anything a
     * reader would see.</p>
     */
    private static IObject findCoveredElement(List<IObject> pageContents, Set<IObject> shapeSet,
                                              BoundingBox box) {
        for (IObject content : pageContents) {
            if (!isOutputContent(content) || shapeSet.contains(content)) {
                continue;
            }
            BoundingBox contentBox = content.getBoundingBox();
            if (contentBox == null || contentBox.isEmpty() || !intersects(box, contentBox)) {
                continue;
            }
            if (covers(box, contentBox)) {
                // Fully covered: removed with the crop, so no duplication.
                continue;
            }
            return content;
        }
        return null;
    }

    /**
     * Cuts one side off {@code box} so that {@code blockerBox} ends up outside it,
     * based on where the blocker sits relative to the diagram's own shapes. Returns
     * {@code false} when no side can be cut (the blocker overlaps the shapes on both
     * axes).
     *
     * <p>The axis on which the blocker and the shapes do not overlap at all decides
     * the cut: a chart header sitting next to the plot is cut away horizontally even
     * when its lower edge happens to start just below the bars' baseline.</p>
     */
    private static boolean cutAway(BoundingBox box, BoundingBox shapeBox, BoundingBox blockerBox) {
        boolean horizontallySeparated = blockerBox.getRightX() <= shapeBox.getLeftX()
                || blockerBox.getLeftX() >= shapeBox.getRightX();
        if (horizontallySeparated) {
            if (blockerBox.getRightX() <= shapeBox.getLeftX()) {
                box.setLeftX(blockerBox.getRightX() + BOUNDARY_EPSILON);
            } else {
                box.setRightX(blockerBox.getLeftX() - BOUNDARY_EPSILON);
            }
            return true;
        }
        boolean verticallySeparated = blockerBox.getTopY() <= shapeBox.getBottomY()
                || blockerBox.getBottomY() >= shapeBox.getTopY();
        if (verticallySeparated) {
            if (blockerBox.getTopY() <= shapeBox.getBottomY()) {
                box.setBottomY(blockerBox.getTopY() + BOUNDARY_EPSILON);
            } else {
                box.setTopY(blockerBox.getBottomY() - BOUNDARY_EPSILON);
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the subset of {@code items} that is fully covered by {@code box} — the
     * contents that may leave the text layer because the screenshot shows them.
     */
    static List<IObject> keepFullyInside(BoundingBox box, List<IObject> items) {
        List<IObject> inside = new ArrayList<>();
        for (IObject item : items) {
            BoundingBox itemBox = item.getBoundingBox();
            if (itemBox != null && !itemBox.isEmpty() && covers(box, itemBox)) {
                inside.add(item);
            }
        }
        return inside;
    }

    /**
     * True when {@code inner} lies inside {@code outer} (within {@link #BLOCKER_EPSILON}).
     *
     * <p>Deliberately compares coordinates only: {@link BoundingBox#contains} returns
     * {@code false} whenever one of the two boxes carries no page number, and the text
     * blocks of a page do not always carry one.</p>
     */
    private static boolean covers(BoundingBox outer, BoundingBox inner) {
        return outer.getLeftX() <= inner.getLeftX() + BLOCKER_EPSILON
                && outer.getRightX() + BLOCKER_EPSILON >= inner.getRightX()
                && outer.getBottomY() <= inner.getBottomY() + BLOCKER_EPSILON
                && outer.getTopY() + BLOCKER_EPSILON >= inner.getTopY();
    }

    /**
     * True when {@code a} and {@code b} share more than a boundary — coordinates only,
     * see {@link #covers}.
     */
    private static boolean intersects(BoundingBox a, BoundingBox b) {
        return a.getLeftX() < b.getRightX() + BLOCKER_EPSILON
                && b.getLeftX() < a.getRightX() + BLOCKER_EPSILON
                && a.getBottomY() < b.getTopY() + BLOCKER_EPSILON
                && b.getBottomY() < a.getTopY() + BLOCKER_EPSILON;
    }

    /** Identity set of {@code items}, for "is one of these" tests. */
    static Set<IObject> identitySet(List<IObject> items) {
        Set<IObject> set = Collections.newSetFromMap(new IdentityHashMap<IObject, Boolean>());
        set.addAll(items);
        return set;
    }

    /** True for element types that are written to the output and could be duplicated. */
    static boolean isOutputContent(IObject content) {
        return content instanceof TextChunk || content instanceof TextLine
                || content instanceof org.verapdf.wcag.algorithms.entities.SemanticTextNode
                || content instanceof org.opendataloader.pdf.custom.entities.CustomSemanticParagraph
                || content instanceof org.verapdf.wcag.algorithms.entities.SemanticHeading
                || content instanceof org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter
                || content instanceof ImageChunk
                || content instanceof Table
                || content instanceof TableBorder;
    }
}
