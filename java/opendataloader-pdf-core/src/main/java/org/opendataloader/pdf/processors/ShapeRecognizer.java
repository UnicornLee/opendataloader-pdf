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
import org.verapdf.wcag.algorithms.entities.IDocument;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.geometry.Vertex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Recognizes geometric shapes (colored filled rectangles and connected line
 * segments) from the raw PDF vector-artifacts produced by veraPDF's chunk
 * parser.
 *
 * <p>The recognizer is intentionally heuristic: it does not try to fully
 * understand the semantics of the page. It extracts:</p>
 * <ul>
 *   <li>filled rectangles / color blocks (table cell backgrounds, legend
 *       swatches, single bars),</li>
 *   <li>groups of aligned filled rectangles that look like bar charts,</li>
 *   <li>end-to-end connected line segments that form polylines (line charts).</li>
 * </ul>
 *
 * <p>Recognized shapes are added as {@link ShapeChunk} objects to the page
 * artifacts. The original {@link LineChunk} objects are kept intact so that
 * downstream table-border detection still works.</p>
 */
public class ShapeRecognizer {

    private static final Logger LOGGER = Logger.getLogger(ShapeRecognizer.class.getCanonicalName());

    /** Gap allowed when deciding that two pieces are adjacent/connected. */
    private static final double ADJACENCY_GAP = 2.0;
    /** Color tolerance for treating two RGB values as the same color. */
    private static final double COLOR_EPSILON = 0.02;
    /**
     * Tolerance for treating a color channel as fully white. PDFs commonly use
     * exactly 1.0 for white fills, but small floating point differences (e.g.
     * 0.9999) are still treated as white. White shapes on a white page background
     * are invisible and are almost always decorative backgrounds or table rows,
     * not meaningful chart elements.
     */
    private static final double WHITE_EPSILON = 0.005;
    /** A line is considered a filled rectangle if its thickness is at least this
     *  fraction of the smaller bounding-box dimension. */
    private static final double FILLED_RECTANGLE_RATIO = 0.5;
    /** Minimum number of bars to classify a cluster as a bar chart. */
    private static final int MIN_BAR_COUNT = 3;
    /** Minimum number of segments to classify a connected line group as a polyline. */
    private static final int MIN_POLYLINE_SEGMENTS = 2;
    /** Margin used when deciding a single line segment connects two existing shapes. */
    private static final double CONNECTOR_MARGIN = 8.0;
    /** Maximum width (pt) across the shaft direction a filled region may have to be
     *  considered an arrowhead. Boxes and other node shapes are typically wider.
     *  Also used by the caller to pre-filter PDFBox fill drawings (see
     *  {@code DocumentProcessor.extractPageFillBoxes}). */
    public static final double MAX_ARROWHEAD_WIDTH = 15.0;
    /** Maximum extent (pt) along the shaft direction a filled arrowhead may span,
     *  as a multiple of the shaft length. Prevents large filled containers from
     *  being mistaken for arrowheads. */
    private static final double MAX_ARROWHEAD_LENGTH_FACTOR = 3.0;
    /** Tolerance (pt) applied when deciding whether a candidate fill extends past an
     *  end of the shaft. The base of an arrowhead triangle usually aligns exactly
     *  with the shaft end, and PDFBox/veraPDF coordinate rounding can differ by a
     *  fraction of a point; without this, a 0.0001 rounding error on the aligned
     *  edge made the head look as if it extended both ends and got rejected. */
    private static final double ARROWHEAD_EXTENSION_EPSILON = 0.5;
    /** Bar chart: width variation tolerance between bars. */
    private static final double BAR_WIDTH_VARIATION = 0.35;
    /**
     * Minimum relative variation in bar length (height for vertical bars, width
     * for horizontal bars) required to treat a cluster as a bar chart. A set of
     * bars with nearly identical lengths is usually a table row or decorative
     * stripe, not a chart encoding different values.
     */
    private static final double BAR_VALUE_VARIATION = 0.15;
    /** Vertical tolerance (pt) when deciding that two shapes belong to the same
     *  group in {@link #groupShapes}. A shape sitting up to this distance below
     *  another shape's top edge (or above its bottom edge) still counts. */
    private static final double SHAPE_GROUP_Y_TOLERANCE = 2.0;

    private ShapeRecognizer() {
        // utility class
    }

    /**
     * Runs shape recognition on every page of the document and appends the
     * discovered {@link ShapeChunk}s to each page's raw artifact list.
     *
     * @param document the already-parsed document
     * @return the list of shapes found per page
     */
    public static List<List<ShapeChunk>> recognize(IDocument document) {
        return recognize(document, null);
    }

    /**
     * Runs shape recognition on every page of the document and appends the
     * discovered {@link ShapeChunk}s to each page's raw artifact list.
     *
     * <p>The optional {@code pageFillBoxes} map provides, per page, the bounding
     * boxes of filled paths extracted directly from the PDF content stream (e.g.
     * via PDFBox). When the veraPDF chunk layer merges an arrowhead into a larger
     * marked-content container, the merged {@link LineArtChunk} carries line
     * segments and the bbox-only arrowhead is lost from the artifact layer. The
     * raw fill boxes act as a fallback candidate source so connector arrows still
     * get their heads. Boxes that coincide with an already recognized shape are
     * ignored.</p>
     *
     * @param document       the already-parsed document
     * @param pageFillBoxes  per-page filled-path boxes (top-left origin converted
     *                       to y-up), or null to rely on artifacts only
     * @return the list of shapes found per page
     */
    public static List<List<ShapeChunk>> recognize(IDocument document, Map<Integer, List<BoundingBox>> pageFillBoxes) {
        if (document == null) {
            return Collections.emptyList();
        }
        int pages = document.getNumberOfPages();
        List<List<ShapeChunk>> result = new ArrayList<>(pages);
        for (int pageNumber = 0; pageNumber < pages; pageNumber++) {
            List<IChunk> artifacts = document.getArtifacts(pageNumber);
            List<BoundingBox> fillBoxes = pageFillBoxes == null ? null : pageFillBoxes.get(pageNumber);
            List<ShapeChunk> shapes = recognizePage(pageNumber, artifacts, fillBoxes);
            if (artifacts != null && !shapes.isEmpty()) {
                artifacts.addAll(shapes);
            }
            result.add(shapes);
            if (!shapes.isEmpty()) {
                LOGGER.log(Level.INFO, "Page {0}: recognized {1} shape(s)",
                        new Object[]{pageNumber + 1, shapes.size()});
                for (ShapeChunk shape : shapes) {
                    LOGGER.log(Level.INFO, "Page {0}: shape type={1}, components={2}, bbox={3}",
                            new Object[]{pageNumber + 1, shape.getShapeType(), shape.getComponentCount(), shape.getBoundingBox()});
                }
            }
        }
        return result;
    }

    /**
     * Recognizes shapes on a single page.
     *
     * @param pageNumber the 0-based page number
     * @param artifacts  the raw page artifacts (may be null)
     * @return a list of new shape chunks; never null
     */
    public static List<ShapeChunk> recognizePage(int pageNumber, List<IChunk> artifacts) {
        return recognizePage(pageNumber, artifacts, null);
    }

    /**
     * Recognizes shapes on a single page.
     *
     * @param pageNumber the 0-based page number
     * @param artifacts  the raw page artifacts (may be null)
     * @param fillBoxes  raw filled-path boxes (y-up) used as a fallback source for
     *                   arrowheads lost to marked-content merging, or null
     * @return a list of new shape chunks; never null
     */
    public static List<ShapeChunk> recognizePage(int pageNumber, List<IChunk> artifacts, List<BoundingBox> fillBoxes) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptyList();
        }

        List<LineChunk> allLines = new ArrayList<>();
        List<BoundingBox> filledArtBoxes = new ArrayList<>();
        for (IChunk chunk : artifacts) {
            if (chunk instanceof LineChunk) {
                LineChunk line = (LineChunk) chunk;
                // PDF has no standard page-background-color field; the default page
                // background is white. White shapes on a white background are invisible
                // and are almost always decorative backgrounds or table rows, not
                // chart elements. Ignore them to avoid false positives like bar charts
                // built from white table-row backgrounds.
                if (isWhite(line.getStrokeColor())) {
                    continue;
                }
                allLines.add(line);
            } else if (chunk instanceof LineArtChunk) {
                LineArtChunk art = (LineArtChunk) chunk;
                List<LineChunk> lineChunks = art.getLineChunks();
                if (lineChunks == null || lineChunks.isEmpty()) {
                    BoundingBox artBox = art.getBoundingBox();
                    if (artBox != null && !artBox.isEmpty()) {
                        // Bbox-only line art: a filled region with no segment geometry,
                        // typically an arrowhead triangle or a curved shape. Kept aside
                        // so connectors can be extended onto their arrowheads.
                        filledArtBoxes.add(artBox);
                    }
                } else {
                    // Same white-filter applies to line art children.
                    for (LineChunk line : lineChunks) {
                        if (!isWhite(line.getStrokeColor())) {
                            allLines.add(line);
                        }
                    }
                }
            }
        }

        if (allLines.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShapeChunk> shapes = new ArrayList<>();

        // Split lines into filled rectangles and thin strokes.
        List<LineChunk> filledRects = new ArrayList<>();
        List<LineChunk> thinLines = new ArrayList<>();
        for (LineChunk line : allLines) {
            if (isFilledRectangle(line)) {
                filledRects.add(line);
            } else {
                // Any non-filled line is a candidate for a polyline (line charts use
                // diagonal/horizontal/vertical segments, and table borders are fine
                // to expose as closed polylines as well).
                thinLines.add(line);
            }
        }

        shapes.addAll(recognizeFilledShapes(pageNumber, filledRects));
        shapes.addAll(recognizePolylines(pageNumber, thinLines));
        // Single-segment lines that bridge two existing shapes are likely arrows/connectors.
        // They are too short to form a polyline on their own but are important for
        // reconstructing flowcharts and diagrams.
        shapes.addAll(recognizeConnectorLines(pageNumber, thinLines, shapes, filledArtBoxes, fillBoxes));

        return shapes;
    }

    private static boolean isFilledRectangle(LineChunk line) {
        BoundingBox bbox = line.getBoundingBox();
        if (bbox == null || bbox.isEmpty()) {
            return false;
        }
        double thickness = line.getWidth();
        double minDim = Math.min(bbox.getWidth(), bbox.getHeight());
        if (minDim <= 2) {
            return false;
        }
        return thickness >= FILLED_RECTANGLE_RATIO * minDim;
    }

    private static List<ShapeChunk> recognizeFilledShapes(int pageNumber, List<LineChunk> filledRects) {
        if (filledRects.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<LineChunk>> byColor = groupByColor(filledRects);
        List<ShapeChunk> shapes = new ArrayList<>();

        for (List<LineChunk> sameColorRects : byColor.values()) {
            // First, detect bar-chart groups (gapped but aligned rectangles).
            List<List<LineChunk>> barGroups = detectBarGroups(sameColorRects);
            Set<LineChunk> usedInBars = new HashSet<>();
            for (List<LineChunk> group : barGroups) {
                if (group.size() >= MIN_BAR_COUNT) {
                    shapes.add(createShape(pageNumber, group, ShapeChunk.TYPE_BAR_CHART));
                    usedInBars.addAll(group);
                }
            }

            // Then cluster any remaining rectangles by spatial adjacency.
            List<LineChunk> remaining = new ArrayList<>(sameColorRects.size());
            for (LineChunk r : sameColorRects) {
                if (!usedInBars.contains(r)) {
                    remaining.add(r);
                }
            }
            List<List<LineChunk>> clusters = clusterRects(remaining);
            for (List<LineChunk> cluster : clusters) {
                if (cluster.isEmpty()) {
                    continue;
                }
                String shapeType = guessFilledShapeType(cluster);
                shapes.add(createShape(pageNumber, cluster, shapeType));
            }
        }
        return shapes;
    }

    private static ShapeChunk createShape(int pageNumber, List<LineChunk> cluster, String shapeType) {
        double[] color = cluster.get(0).getStrokeColor();
        BoundingBox union = new BoundingBox(pageNumber);
        List<BoundingBox> parts = new ArrayList<>(cluster.size());
        for (LineChunk r : cluster) {
            BoundingBox bb = r.getBoundingBox();
            union.union(bb);
            parts.add(bb);
        }
        return new ShapeChunk(union, shapeType, color, cluster.size(), parts);
    }

    /**
     * Detects groups of rectangles that look like bar charts: aligned along a
     * baseline, similar widths/heights, and regularly spaced (gaps allowed).
     *
     * <p>Rectangles are first bucketed by their baseline (rounded to
     * {@link #ADJACENCY_GAP}) so bars from different charts that interleave in
     * x do not break each other's groups.</p>
     */
    private static List<List<LineChunk>> detectBarGroups(List<LineChunk> rects) {
        if (rects.size() < MIN_BAR_COUNT) {
            return Collections.emptyList();
        }
        // Group by rounded baseline (vertical bars: bottomY; horizontal bars: leftX).
        Map<Double, List<LineChunk>> byBaseline = new TreeMap<>();
        for (LineChunk rect : rects) {
            BoundingBox bbox = rect.getBoundingBox();
            double baseline = Math.round(Math.min(bbox.getBottomY(), bbox.getTopY()) / ADJACENCY_GAP) * ADJACENCY_GAP;
            byBaseline.computeIfAbsent(baseline, k -> new ArrayList<>()).add(rect);
        }

        List<List<LineChunk>> groups = new ArrayList<>();
        for (List<LineChunk> baselineRects : byBaseline.values()) {
            baselineRects.sort(Comparator.comparingDouble(LineChunk::getCenterX));
            List<LineChunk> currentGroup = new ArrayList<>();
            for (LineChunk rect : baselineRects) {
                if (currentGroup.isEmpty()) {
                    currentGroup.add(rect);
                } else if (isSameBarGroup(currentGroup, rect)) {
                    currentGroup.add(rect);
                } else {
                    if (currentGroup.size() >= MIN_BAR_COUNT && isValidBarGroup(currentGroup)) {
                        groups.add(new ArrayList<>(currentGroup));
                    }
                    currentGroup.clear();
                    currentGroup.add(rect);
                }
            }
            if (currentGroup.size() >= MIN_BAR_COUNT && isValidBarGroup(currentGroup)) {
                groups.add(currentGroup);
            }
        }
        return groups;
    }

    private static boolean isSameBarGroup(List<LineChunk> group, LineChunk candidate) {
        LineChunk first = group.get(0);
        BoundingBox firstBox = first.getBoundingBox();
        BoundingBox candidateBox = candidate.getBoundingBox();

        // Vertical bars: same bottom edge, similar width, not too far apart,
        // and tall enough (height at least twice the width) to avoid misclassifying
        // table border segments as bars.
        boolean sameBaseline = Math.abs(firstBox.getBottomY() - candidateBox.getBottomY()) <= ADJACENCY_GAP
                || Math.abs(firstBox.getTopY() - candidateBox.getTopY()) <= ADJACENCY_GAP;
        boolean sameWidth = firstBox.getWidth() > 0
                && Math.abs(firstBox.getWidth() - candidateBox.getWidth()) / firstBox.getWidth() <= BAR_WIDTH_VARIATION;
        double gap = candidateBox.getLeftX() - group.get(group.size() - 1).getBoundingBox().getRightX();
        boolean reasonableGap = gap >= -ADJACENCY_GAP && gap <= 5 * Math.max(firstBox.getWidth(), ADJACENCY_GAP);
        boolean verticalBarShape = candidateBox.getHeight() >= 2.0 * candidateBox.getWidth()
                && firstBox.getHeight() >= 2.0 * firstBox.getWidth();
        if (sameBaseline && sameWidth && reasonableGap && verticalBarShape) {
            return true;
        }

        // Horizontal bars: same left/right edge, similar height, not too far apart,
        // and wide enough (width at least twice the height) to avoid table borders.
        boolean sameLeftEdge = Math.abs(firstBox.getLeftX() - candidateBox.getLeftX()) <= ADJACENCY_GAP
                || Math.abs(firstBox.getRightX() - candidateBox.getRightX()) <= ADJACENCY_GAP;
        boolean sameHeight = firstBox.getHeight() > 0
                && Math.abs(firstBox.getHeight() - candidateBox.getHeight()) / firstBox.getHeight() <= BAR_WIDTH_VARIATION;
        double vGap = candidateBox.getBottomY() - group.get(group.size() - 1).getBoundingBox().getTopY();
        boolean reasonableVGap = vGap >= -ADJACENCY_GAP && vGap <= 5 * Math.max(firstBox.getHeight(), ADJACENCY_GAP);
        boolean horizontalBarShape = candidateBox.getWidth() >= 2.0 * candidateBox.getHeight()
                && firstBox.getWidth() >= 2.0 * firstBox.getHeight();
        return sameLeftEdge && sameHeight && reasonableVGap && horizontalBarShape;
    }

    private static List<ShapeChunk> recognizePolylines(int pageNumber, List<LineChunk> thinLines) {
        if (thinLines.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<LineChunk>> byColor = groupByColor(thinLines);
        List<ShapeChunk> shapes = new ArrayList<>();

        for (List<LineChunk> sameColorLines : byColor.values()) {
            List<List<LineChunk>> chains = buildChains(sameColorLines);
            for (List<LineChunk> chain : chains) {
                if (chain.size() < MIN_POLYLINE_SEGMENTS) {
                    continue;
                }
                double[] color = chain.get(0).getStrokeColor();
                BoundingBox union = new BoundingBox(pageNumber);
                List<BoundingBox> parts = new ArrayList<>(chain.size());
                for (LineChunk line : chain) {
                    BoundingBox bb = line.getBoundingBox();
                    union.union(bb);
                    parts.add(bb);
                }
                shapes.add(new ShapeChunk(union, ShapeChunk.TYPE_POLYLINE, color, chain.size(), parts));
            }
        }
        return shapes;
    }

    /**
     * Recognizes single line segments that act as connectors/arrows between two
     * already recognized shapes. These are typically discarded by
     * {@link #recognizePolylines} because a chain of length 1 does not meet the
     * polyline threshold, but they are essential for diagrams and flowcharts.
     */
    private static List<ShapeChunk> recognizeConnectorLines(int pageNumber, List<LineChunk> thinLines,
                                                             List<ShapeChunk> existingShapes,
                                                             List<BoundingBox> filledArtBoxes,
                                                             List<BoundingBox> fillBoxes) {
        if (thinLines.isEmpty() || existingShapes.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<LineChunk>> byColor = groupByColor(thinLines);
        List<ShapeChunk> connectors = new ArrayList<>();

        for (List<LineChunk> sameColorLines : byColor.values()) {
            List<List<LineChunk>> chains = buildChains(sameColorLines);
            for (List<LineChunk> chain : chains) {
                if (chain.size() != 1) {
                    continue;
                }
                LineChunk line = chain.get(0);
                if (!isConnectorLine(line, existingShapes)) {
                    continue;
                }
                BoundingBox bbox = findArrowBBox(line, filledArtBoxes, fillBoxes, existingShapes);
                connectors.add(new ShapeChunk(new BoundingBox(bbox), ShapeChunk.TYPE_ARROW,
                        line.getStrokeColor(), 1, Collections.singletonList(bbox)));
            }
        }
        return connectors;
    }

    /**
     * Computes the bounding box of an arrow given its shaft (a thin connector line)
     * and the page's filled (bbox-only) regions. The arrowhead triangle in a PDF is
     * usually rendered as a filled polygon that produces a {@link LineArtChunk} with
     * no line segments. When a small such region overlaps the shaft and extends past
     * exactly one of its ends, the returned box covers the shaft plus that arrowhead;
     * otherwise the plain shaft box is returned.
     *
     * <p>If no arrowhead is found in the artifact layer (e.g. it was merged into a
     * larger marked-content container and is lost from the artifacts), the raw
     * content-stream fill boxes are used as a fallback candidate source.</p>
     */
    private static BoundingBox findArrowBBox(LineChunk line, List<BoundingBox> filledArtBoxes,
                                             List<BoundingBox> fillBoxes, List<ShapeChunk> existingShapes) {
        BoundingBox shaft = line.getBoundingBox();
        if (shaft == null || shaft.isEmpty()) {
            return new BoundingBox(shaft);
        }
        BoundingBox head = pickArrowhead(shaft, filledArtBoxes);
        if (head == null && fillBoxes != null && !fillBoxes.isEmpty()) {
            head = pickArrowhead(shaft, filterShapeCoincidentFills(fillBoxes, existingShapes));
        }
        if (head == null) {
            return new BoundingBox(shaft);
        }
        BoundingBox arrow = new BoundingBox(shaft);
        arrow.union(head);
        return arrow;
    }

    /**
     * Returns the best candidate region that looks like the arrowhead of the given
     * shaft, or null when no candidate qualifies.
     */
    private static BoundingBox pickArrowhead(BoundingBox shaft, List<BoundingBox> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        boolean vertical = shaft.getHeight() >= shaft.getWidth();
        double shaftAlong = vertical ? shaft.getHeight() : shaft.getWidth();
        if (shaftAlong <= 0) {
            return null;
        }

        BoundingBox best = null;
        double bestArea = Double.MAX_VALUE;
        for (BoundingBox art : candidates) {
            if (art == null || art.isEmpty() || !overlaps(art, shaft)) {
                continue;
            }
            // An arrowhead extends before (or after) the shaft along its axis, never both.
            boolean extendsBefore = vertical ? art.getBottomY() < shaft.getBottomY() - ARROWHEAD_EXTENSION_EPSILON
                    : art.getLeftX() < shaft.getLeftX() - ARROWHEAD_EXTENSION_EPSILON;
            boolean extendsAfter = vertical ? art.getTopY() > shaft.getTopY() + ARROWHEAD_EXTENSION_EPSILON
                    : art.getRightX() > shaft.getRightX() + ARROWHEAD_EXTENSION_EPSILON;
            if (extendsBefore == extendsAfter) {
                continue;
            }
            // Size guards keep large filled backgrounds/containers from being arrowheads.
            double perpDim = vertical ? art.getWidth() : art.getHeight();
            if (perpDim > MAX_ARROWHEAD_WIDTH) {
                continue;
            }
            double alongDim = vertical ? art.getHeight() : art.getWidth();
            if (alongDim > MAX_ARROWHEAD_LENGTH_FACTOR * shaftAlong) {
                continue;
            }
            double area = art.getWidth() * art.getHeight();
            if (best == null || area < bestArea) {
                best = art;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * Drops raw fill boxes that substantially overlap an already recognized solid
     * shape (rectangle/bar) of comparable size. Without this, a small filled node
     * box at the end of a connector would be mistaken for an arrowhead and inflate
     * the arrow's bounding box. Polylines are deliberately not consulted: an
     * arrowhead triangle is itself reconstructed as a polyline from its (possibly
     * MCID-merged) stroke segments, so requiring non-coincidence against it would
     * defeat the PDFBox fill fallback. Arrowheads that merely poke into a much
     * larger shape are kept as well, since their fill is a distinct, much smaller
     * region.
     */
    private static List<BoundingBox> filterShapeCoincidentFills(List<BoundingBox> fillBoxes,
                                                                List<ShapeChunk> existingShapes) {
        List<BoundingBox> filtered = new ArrayList<>(fillBoxes.size());
        for (BoundingBox fill : fillBoxes) {
            if (fill == null || fill.isEmpty()) {
                continue;
            }
            double fillArea = fill.getWidth() * fill.getHeight();
            boolean coincides = false;
            for (ShapeChunk shape : existingShapes) {
                String type = shape.getShapeType();
                if (!ShapeChunk.TYPE_RECTANGLE.equals(type) && !ShapeChunk.TYPE_BAR_CHART.equals(type)) {
                    continue;
                }
                BoundingBox shapeBox = shape.getBoundingBox();
                if (shapeBox == null || shapeBox.isEmpty()) {
                    continue;
                }
                double overlap = overlapArea(fill, shapeBox);
                double shapeArea = shapeBox.getWidth() * shapeBox.getHeight();
                if (overlap >= 0.5 * fillArea && shapeArea <= 10.0 * fillArea) {
                    coincides = true;
                    break;
                }
            }
            if (!coincides) {
                filtered.add(fill);
            }
        }
        return filtered;
    }

    private static double overlapArea(BoundingBox a, BoundingBox b) {
        double width = Math.min(a.getRightX(), b.getRightX()) - Math.max(a.getLeftX(), b.getLeftX());
        double height = Math.min(a.getTopY(), b.getTopY()) - Math.max(a.getBottomY(), b.getBottomY());
        if (width <= 0 || height <= 0) {
            return 0;
        }
        return width * height;
    }

    private static boolean overlaps(BoundingBox a, BoundingBox b) {
        return a.getLeftX() <= b.getRightX() && a.getRightX() >= b.getLeftX()
                && a.getBottomY() <= b.getTopY() && a.getTopY() >= b.getBottomY();
    }

    private static boolean isConnectorLine(LineChunk line, List<ShapeChunk> existingShapes) {
        ShapeChunk startShape = findShapeNearPoint(line.getStartX(), line.getStartY(), existingShapes);
        ShapeChunk endShape = findShapeNearPoint(line.getEndX(), line.getEndY(), existingShapes);
        return startShape != null && endShape != null && startShape != endShape;
    }

    private static ShapeChunk findShapeNearPoint(double x, double y, List<ShapeChunk> shapes) {
        ShapeChunk nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (ShapeChunk shape : shapes) {
            BoundingBox bbox = shape.getBoundingBox();
            if (x < bbox.getLeftX() - CONNECTOR_MARGIN || x > bbox.getRightX() + CONNECTOR_MARGIN
                    || y < bbox.getBottomY() - CONNECTOR_MARGIN || y > bbox.getTopY() + CONNECTOR_MARGIN) {
                continue;
            }
            double centerX = 0.5 * (bbox.getLeftX() + bbox.getRightX());
            double centerY = 0.5 * (bbox.getBottomY() + bbox.getTopY());
            double distance = Math.hypot(x - centerX, y - centerY);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = shape;
            }
        }
        return nearest;
    }

    /**
     * Groups rectangles by color using a stable string key. Colors are considered
     * equal if each channel differs by at most {@link #COLOR_EPSILON}.
     */
    private static Map<String, List<LineChunk>> groupByColor(List<LineChunk> lines) {
        Map<String, List<LineChunk>> map = new TreeMap<>();
        for (LineChunk line : lines) {
            double[] color = line.getStrokeColor();
            String key = colorKey(color);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        return map;
    }

    private static String colorKey(double[] color) {
        if (color == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < color.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Math.round(color[i] / COLOR_EPSILON));
        }
        return sb.toString();
    }

    /**
     * Returns true when the color is white (or near-white). Because PDF does not
     * define a standard page background color, this recognizer assumes the
     * default white background. White shapes on a white background are invisible
     * to readers and are almost always decorative backgrounds or table rows, not
     * chart elements that should be extracted as shapes.
     */
    private static boolean isWhite(double[] color) {
        if (color == null) {
            return false;
        }
        for (double channel : color) {
            if (Math.abs(channel - 1.0) > WHITE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * Clusters rectangles that overlap or are adjacent (gap <= ADJACENCY_GAP).
     */
    private static List<List<LineChunk>> clusterRects(List<LineChunk> rects) {
        List<List<LineChunk>> clusters = new ArrayList<>();
        for (LineChunk rect : rects) {
            boolean merged = false;
            BoundingBox bbox = rect.getBoundingBox();
            for (List<LineChunk> cluster : clusters) {
                BoundingBox clusterBox = clusterBoundingBox(cluster);
                if (areAdjacentOrOverlapping(bbox, clusterBox)) {
                    cluster.add(rect);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                List<LineChunk> newCluster = new ArrayList<>();
                newCluster.add(rect);
                clusters.add(newCluster);
            }
        }
        return clusters;
    }

    private static BoundingBox clusterBoundingBox(List<LineChunk> cluster) {
        BoundingBox result = new BoundingBox(cluster.get(0).getPageNumber());
        for (LineChunk line : cluster) {
            result.union(line.getBoundingBox());
        }
        return result;
    }

    private static boolean areAdjacentOrOverlapping(BoundingBox a, BoundingBox b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        boolean xOverlap = a.getLeftX() <= b.getRightX() + ADJACENCY_GAP
                && b.getLeftX() <= a.getRightX() + ADJACENCY_GAP;
        boolean yOverlap = a.getBottomY() <= b.getTopY() + ADJACENCY_GAP
                && b.getBottomY() <= a.getTopY() + ADJACENCY_GAP;
        return xOverlap && yOverlap;
    }

    /**
     * Guesses whether a cluster of filled rectangles is a bar chart or a plain
     * rectangle / color block.
     */
    private static String guessFilledShapeType(List<LineChunk> cluster) {
        if (cluster.size() < MIN_BAR_COUNT) {
            return ShapeChunk.TYPE_RECTANGLE;
        }

        // Sort by center x to inspect horizontal alignment.
        List<LineChunk> byX = new ArrayList<>(cluster);
        byX.sort(Comparator.comparingDouble(LineChunk::getCenterX));

        boolean sameBaseline = true;
        boolean similarWidths = true;
        double firstBottom = byX.get(0).getBottomY();
        double firstTop = byX.get(0).getTopY();
        double firstWidth = byX.get(0).getBoundingBox().getWidth();
        for (LineChunk r : byX) {
            if (Math.abs(r.getBottomY() - firstBottom) > ADJACENCY_GAP
                    && Math.abs(r.getTopY() - firstTop) > ADJACENCY_GAP) {
                sameBaseline = false;
                break;
            }
            if (firstWidth > 0 && Math.abs(r.getBoundingBox().getWidth() - firstWidth) / firstWidth > BAR_WIDTH_VARIATION) {
                similarWidths = false;
            }
        }
        if (sameBaseline && similarWidths && isValidBarGroup(byX)) {
            return ShapeChunk.TYPE_BAR_CHART;
        }

        // Also check vertical bar orientation (same left/right edge, similar heights).
        List<LineChunk> byY = new ArrayList<>(cluster);
        byY.sort(Comparator.comparingDouble(LineChunk::getCenterY).reversed());
        boolean sameVerticalEdge = true;
        boolean similarHeights = true;
        double firstLeft = byY.get(0).getLeftX();
        double firstRight = byY.get(0).getRightX();
        double firstHeight = byY.get(0).getBoundingBox().getHeight();
        for (LineChunk r : byY) {
            if (Math.abs(r.getLeftX() - firstLeft) > ADJACENCY_GAP
                    && Math.abs(r.getRightX() - firstRight) > ADJACENCY_GAP) {
                sameVerticalEdge = false;
                break;
            }
            if (firstHeight > 0 && Math.abs(r.getBoundingBox().getHeight() - firstHeight) / firstHeight > BAR_WIDTH_VARIATION) {
                similarHeights = false;
            }
        }
        if (sameVerticalEdge && similarHeights && isValidBarGroup(byY)) {
            return ShapeChunk.TYPE_BAR_CHART;
        }

        return ShapeChunk.TYPE_RECTANGLE;
    }

    /**
     * Returns true when the group looks like an actual bar chart: bars are
     * elongated enough to have a clear orientation, they are separated by gaps
     * (not stacked table rows), and their lengths vary as if encoding values.
     */
    private static boolean isValidBarGroup(List<LineChunk> group) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        BoundingBox firstBox = group.get(0).getBoundingBox();
        if (firstBox == null || firstBox.isEmpty()) {
            return false;
        }
        boolean vertical = firstBox.getHeight() >= 2.0 * firstBox.getWidth();
        boolean horizontal = firstBox.getWidth() >= 2.0 * firstBox.getHeight();
        if (!vertical && !horizontal) {
            return false;
        }
        return hasBarGaps(group, vertical) && hasVaryingBarLengths(group, vertical);
    }

    /**
     * Checks that at least two consecutive bars are separated by a positive gap,
     * so stacked/adjacent rectangles (e.g. table rows) are not treated as charts.
     */
    private static boolean hasBarGaps(List<LineChunk> group, boolean vertical) {
        List<LineChunk> sorted = new ArrayList<>(group);
        if (vertical) {
            sorted.sort(Comparator.comparingDouble(LineChunk::getCenterX));
        } else {
            sorted.sort(Comparator.comparingDouble(LineChunk::getCenterY));
        }
        for (int i = 1; i < sorted.size(); i++) {
            LineChunk prev = sorted.get(i - 1);
            LineChunk curr = sorted.get(i);
            BoundingBox prevBox = prev.getBoundingBox();
            BoundingBox currBox = curr.getBoundingBox();
            if (prevBox == null || currBox == null || prevBox.isEmpty() || currBox.isEmpty()) {
                continue;
            }
            double gap = vertical ? currBox.getLeftX() - prevBox.getRightX()
                    : currBox.getBottomY() - prevBox.getTopY();
            if (gap > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks that bars differ in their value dimension (height for vertical bars,
     * width for horizontal bars). Identical-length bars are usually decorative
     * stripes or table rows, not a bar chart.
     */
    private static boolean hasVaryingBarLengths(List<LineChunk> group, boolean vertical) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0;
        int count = 0;
        for (LineChunk r : group) {
            BoundingBox bb = r.getBoundingBox();
            if (bb == null || bb.isEmpty()) {
                continue;
            }
            double len = vertical ? bb.getHeight() : bb.getWidth();
            min = Math.min(min, len);
            max = Math.max(max, len);
            sum += len;
            count++;
        }
        if (count == 0) {
            return false;
        }
        double avg = sum / count;
        return avg > 0 && (max - min) / avg > BAR_VALUE_VARIATION;
    }

    /**
     * Builds end-to-end connected chains of line segments for each color.
     */
    private static List<List<LineChunk>> buildChains(List<LineChunk> lines) {
        List<LineChunk> remaining = new LinkedList<>(lines);
        List<List<LineChunk>> chains = new ArrayList<>();

        while (!remaining.isEmpty()) {
            LineChunk seed = remaining.remove(0);
            List<LineChunk> chain = new ArrayList<>();
            chain.add(seed);
            Vertex start = seed.getStart();
            Vertex end = seed.getEnd();

            boolean extended = true;
            while (extended && !remaining.isEmpty()) {
                extended = false;
                    for (int i = 0; i < remaining.size(); i++) {
                    LineChunk candidate = remaining.get(i);
                    Vertex cStart = candidate.getStart();
                    Vertex cEnd = candidate.getEnd();
                    if (Vertex.areCloseVertexes(end, cStart, ADJACENCY_GAP)) {
                        chain.add(candidate);
                        end = cEnd;
                        remaining.remove(i);
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(end, cEnd, ADJACENCY_GAP)) {
                        chain.add(candidate);
                        end = cStart;
                        remaining.remove(i);
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(start, cEnd, ADJACENCY_GAP)) {
                        chain.add(0, candidate);
                        start = cStart;
                        remaining.remove(i);
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(start, cStart, ADJACENCY_GAP)) {
                        chain.add(0, candidate);
                        start = cEnd;
                        remaining.remove(i);
                        extended = true;
                        break;
                    }
                }
            }
            chains.add(chain);
        }
        return chains;
    }

    /**
     * Groups {@link ShapeChunk}s whose bounding boxes overlap into connected
     * components.
     *
     * <p>Each inner list contains one group of mutually intersecting shapes.
     * Non-overlapping shapes each form a single-element group. The groups are
     * returned in the order of their first member in the input.</p>
     *
     * @param shapeChunks the candidate shapes (may contain non-ShapeChunk items,
     *                    which are ignored)
     * @return a two-level list of shape groups; never null
     */
    public static List<List<IObject>> groupShapes(List<IObject> shapeChunks) {
        if (shapeChunks == null || shapeChunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShapeChunk> shapes = new ArrayList<>(shapeChunks.size());
        for (IObject obj : shapeChunks) {
            if (obj instanceof ShapeChunk) {
                shapes.add((ShapeChunk) obj);
            }
        }

        if (shapes.isEmpty()) {
            return Collections.emptyList();
        }
        if (shapes.size() == 1) {
            List<List<IObject>> result = new ArrayList<>(1);
            result.add(Collections.singletonList(shapes.get(0)));
            return result;
        }

        // Union-find: build connected components of shapes whose bounding boxes
        // overlap horizontally and are vertically within the y tolerance.
        UnionFind uf = new UnionFind(shapes.size());
        for (int i = 0; i < shapes.size(); i++) {
            BoundingBox bboxI = shapes.get(i).getBoundingBox();
            if (bboxI == null || bboxI.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < shapes.size(); j++) {
                BoundingBox bboxJ = shapes.get(j).getBoundingBox();
                if (bboxJ != null && !bboxJ.isEmpty() && overlapsWithYTolerance(bboxI, bboxJ)) {
                    uf.union(i, j);
                }
            }
        }

        Map<Integer, List<IObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < shapes.size(); i++) {
            groups.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(shapes.get(i));
        }

        return new ArrayList<>(groups.values());
    }

    /**
     * Returns true when two bounding boxes overlap in x and are vertically
     * within {@link #SHAPE_GROUP_Y_TOLERANCE} of each other (or actually
     * overlapping). A shape whose top edge sits up to the tolerance below the
     * other's bottom edge — or whose bottom edge sits up to the tolerance above
     * the other's top edge — is treated as connected.
     */
    private static boolean overlapsWithYTolerance(BoundingBox a, BoundingBox b) {
        if (a.getLeftX() > b.getRightX() || b.getLeftX() > a.getRightX()) {
            return false;
        }
        return a.getBottomY() <= b.getTopY() + SHAPE_GROUP_Y_TOLERANCE
                && b.getBottomY() <= a.getTopY() + SHAPE_GROUP_Y_TOLERANCE;
    }

    private static class UnionFind {
        private final int[] parent;
        private final int[] rank;

        UnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]);
            }
            return parent[x];
        }

        void union(int x, int y) {
            int rootX = find(x);
            int rootY = find(y);
            if (rootX == rootY) {
                return;
            }
            if (rank[rootX] < rank[rootY]) {
                parent[rootX] = rootY;
            } else if (rank[rootX] > rank[rootY]) {
                parent[rootY] = rootX;
            } else {
                parent[rootY] = rootX;
                rank[rootX]++;
            }
        }
    }
}
