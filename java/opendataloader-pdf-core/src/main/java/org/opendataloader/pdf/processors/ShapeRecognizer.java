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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
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
    /**
     * Maximum number of line segments of a single color that will be fed into
     * chain building. Pages with extremely dense vector art (e.g. patterns or
     * highly fragmented strokes) can produce tens of thousands of segments;
     * trying to connect all of them is prohibitively expensive and rarely
     * produces useful chart shapes. Colors exceeding this limit are skipped
     * for polyline/connector recognition only.
     */
    private static final int MAX_LINES_PER_COLOR_FOR_CHAIN_RECOGNITION = 5000;
    /** Margin used when deciding a single line segment connects two existing shapes. */
    private static final double CONNECTOR_MARGIN = 8.0;
    /**
     * Maximum thickness (pt) of a chain's union box for the chain to count as a
     * single straight line (see {@link #isStraightChain}). Thin strokes are around
     * 0.5-2 pt wide, and a vertical/horizontal run of them stays within this box.
     */
    private static final double STRAIGHT_CHAIN_TOLERANCE = 3.0;
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
    /**
     * Minimum area (pt²) of a filled region for it to be reported as an arrowhead
     * ({@link ShapeChunk#TYPE_ARROW_HEADER}). Small fills at the end of a line are also
     * produced by table corners and rounded cell joints (measured 1.44 x 1.44 pt = 2.1 pt²),
     * and treating those as heads makes the diagram region grow from a table corner and
     * swallow the whole table.
     */
    private static final double MIN_ARROWHEAD_AREA = 4.0;
    /** Minimum side (pt) of a filled region for it to be reported as an arrowhead. */
    private static final double MIN_ARROWHEAD_DIMENSION = 2.0;
    /**
     * Share of a head candidate's area that may be covered by an already recognized solid
     * shape (rectangle / bar chart) before the candidate is discarded as a piece of that
     * shape. Table headers are the typical case: a filled header band at the end of a row
     * separator line satisfies every arrowhead size guard (measured 120 x 12 pt and
     * 49 x 14 pt) and would otherwise make the diagram region grow across the whole table.
     */
    private static final double MAX_ARROWHEAD_SHAPE_COVERAGE = 0.9;
    /** Bar chart: width variation tolerance between bars. */
    private static final double BAR_WIDTH_VARIATION = 0.35;
    /**
     * Stacked bar charts: maximum difference (pt) between the stack bottoms of two
     * columns that still counts as a shared baseline. Bars sit on the x-axis, but the
     * very short first columns of a chart can be rendered a couple of points off it
     * (observed 3.3 pt), so the tolerance has to stay above that.
     */
    private static final double STACK_BASELINE_TOLERANCE = 5.0;
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

    /**
     * Rounded-rectangle node boxes (see {@link #recognizeCurvedNodeBoxes}).
     *
     * <p>Such a box survives in the content stream only as a closed path with curve
     * segments; the chunk layer has no line geometry for it. A single curved path can
     * also be a decorative blob, so at least {@link #MIN_CURVED_NODE_COUNT} of them have
     * to be present before any of them is treated as a diagram node.</p>
     */
    private static final double CURVED_NODE_MIN_SIZE = 15.0;
    private static final int MIN_CURVED_NODE_COUNT = 2;

    /**
     * Line-chart recognition (see {@link #applyLineChartTypes}).
     *
     * <p>A line chart carries no marker of its own: the straight axis lines are filtered
     * out of polyline building (see {@link #isStraightChain}), so the only chart-specific
     * geometry that survives is the bent data path plus the small markers drawn on its
     * data points. What makes such a path a chart rather than a decorative line is the
     * axis frame around it, and the absence of the node boxes a flow diagram would have
     * inside that frame.</p>
     */
    /** Minimum width (pt) of the frame enclosing a line chart's data path. */
    private static final double LINE_CHART_MIN_FRAME_WIDTH = 120.0;
    /** Minimum height (pt) of the frame enclosing a line chart's data path. */
    private static final double LINE_CHART_MIN_FRAME_HEIGHT = 40.0;
    /** Maximum height/width ratio of that frame; taller regions are not plot areas. */
    private static final double LINE_CHART_MAX_FRAME_ASPECT = 0.6;
    /** Tolerance (pt) when deciding a shape lies inside the frame. */
    private static final double LINE_CHART_FRAME_TOLERANCE = 3.0;
    /** Minimum share of the frame's width that the data path has to span. */
    private static final double LINE_CHART_MIN_DATA_SPAN_RATIO = 0.5;
    /** Minimum width (pt) of a polyline that counts as the plot's data path. */
    private static final double LINE_CHART_MIN_DATA_LENGTH = 40.0;
    /** Maximum thickness (pt) of a frame piece that can still be one of the plot's axes. */
    private static final double LINE_CHART_AXIS_MAX_THICKNESS = 8.0;
    /** Minimum share of the frame's extent an axis piece has to run along. */
    private static final double LINE_CHART_AXIS_MIN_COVER = 0.7;
    /** Maximum side (pt) of a shape that counts as a data-point marker. */
    private static final double LINE_CHART_MARKER_MAX_SIZE = 8.0;
    /**
     * Minimum margin (pt) between a data-point marker and the frame's edges. Markers
     * sitting on the frame boundary itself are a table's corner stubs, not data points.
     */
    private static final double LINE_CHART_MARKER_INSET = 1.0;
    /**
     * Maximum share of a frame edge that a data-path piece may run along before that
     * edge counts as "hugged". A data path following two or more frame edges is a piece
     * of the frame itself (measured: the bottom row of a lattice table misread as a plot
     * because its right and bottom borders look like a bent data path).
     */
    private static final double LINE_CHART_MAX_EDGE_HUG = 0.5;
    /** Minimum width (pt) of a polyline piece that can join a frameless path family. */
    private static final double LINE_CHART_FAMILY_MIN_PIECE_WIDTH = 15.0;
    /** Maximum gap (pt) in x between two chained pieces of one path family. */
    private static final double LINE_CHART_FAMILY_MAX_GAP = 25.0;
    /** Pieces of one path family may overlap by at most this much (pt) in x. */
    private static final double LINE_CHART_FAMILY_MAX_OVERLAP = 2.0;
    /** Maximum vertical discontinuity (pt) allowed where two pieces of a family meet. */
    private static final double LINE_CHART_FAMILY_MAX_Y_JUMP = 8.0;
    /** Minimum width (pt) of a path family's overall span. */
    private static final double LINE_CHART_FAMILY_MIN_WIDTH = 120.0;
    /**
     * Minimum height (pt) of a path family's overall span. A real series travels a
     * visible vertical range; a degenerate flat band is a ruled line or a table row's
     * fragments (measured: a waterfall chart's baseline band chained into a bogus
     * family).
     */
    private static final double LINE_CHART_FAMILY_MIN_HEIGHT = 20.0;
    /** Maximum height/width ratio of a path family's overall span; taller groups are diagrams. */
    private static final double LINE_CHART_FAMILY_MAX_ASPECT = 0.5;
    /** Maximum width (pt) of a dash rectangle that may belong to a path family. */
    private static final double LINE_CHART_FAMILY_DASH_MAX_WIDTH = 20.0;
    /** Maximum height (pt) of a dash rectangle that may belong to a path family. */
    private static final double LINE_CHART_FAMILY_DASH_MAX_HEIGHT = 12.0;
    /** A node-sized shape this close (pt) to a path family's span disqualifies it. */
    private static final double LINE_CHART_FAMILY_NODE_MARGIN = 3.0;
    /** Minimum width (pt) of a shape that looks like a diagram node. */
    private static final double LINE_CHART_NODE_MIN_WIDTH = 15.0;
    /** Minimum height (pt) of a shape that looks like a diagram node. */
    private static final double LINE_CHART_NODE_MIN_HEIGHT = 8.0;
    /**
     * How many node-like shapes may sit inside the frame before the region is read as a flow
     * diagram or a table instead of a plot. The data path itself is excluded from the count;
     * a plot's frame encloses nothing but the path and its markers, so any node-sized shape
     * inside it means the frame is something else (measured: a chart grid's cell outlines, a
     * table's frame, a diagram's box).
     */
    private static final int LINE_CHART_MAX_NODE_LIKE_SHAPES = 0;

    /**
     * Pie-chart recognition from the PDFBox fill-box fallback source.
     *
     * <p>A pie is rendered as a set of filled sectors that all share the pie
     * centre as one of their corners (the apex of every wedge). We therefore
     * group the candidate fills by shared corner points and treat the largest
     * such cluster as the wedge set of a single pie. The union of the wedges is
     * required to be roughly square (a disc), with the shared corner near its
     * centre, which rejects bar charts (no common corner), tables, and lone
     * full-disc fills (a single box would dominate the union area).</p>
     */
    /** Tolerance (pt) when deciding that two corners coincide (shared apex). */
    private static final double PIE_CORNER_TOLERANCE = 2.0;
    /** Minimum number of wedges that must share a corner to call it a pie. */
    private static final int MIN_PIE_WEDGE_COUNT = 3;
    /** Minimum number of wedges required when no separate full-disc fill box is present. */
    private static final int MIN_PIE_WEDGE_COUNT_WITHOUT_DISC = 5;
    /** Minimum side length (pt) of the pie's union bbox. */
    private static final double PIE_MIN_SIZE = 30.0;
    /** Absolute (pt) tolerance for the "union must be square" check. */
    private static final double PIE_SQUARE_TOLERANCE = 4.0;
    /** Relative tolerance for the "union must be square" check (fraction of the
     *  larger side). Keeps large pies from being rejected for a few points of
     *  rounding on the bounding box of the sectors. */
    private static final double PIE_SQUARE_RATIO = 0.08;
    /** Tolerance (pt) between the shared corner and the centre of the union bbox. */
    private static final double PIE_CENTER_TOLERANCE = 2.0;
    /** Tolerance (pt) when matching the centre of a separate full-disc fill box to the
     *  shared wedge corner (the disc centre coincides with the pie centre). */
    private static final double PIE_DISC_CENTER_TOLERANCE = 8.0;
    /** A single fill may cover at most this share of the union area, otherwise it
     *  is treated as a full-disc fill rather than one wedge of a pie. */
    private static final double PIE_MAX_WEDGE_AREA_RATIO = 0.6;

    /**
     * Fallback color assigned to filled rectangles that only surface through the
     * PDFBox fallback source (e.g. rectangles filled with a {@code /Pattern}
     * shading/gradient color space whose RGB cannot be resolved by the veraPDF
     * chunk layer). All such rectangles share the same fallback color so they
     * are grouped together by {@link #groupByColor} and can form bar-chart
     * groups; the color itself is only informational metadata.
     */
    private static final double[] FALLBACK_FILL_COLOR = new double[]{0.5, 0.7, 0.95};

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
        return recognize(document, pageFillBoxes, null);
    }

    /**
     * Same as {@link #recognize(IDocument, Map)}, with one additional fallback source
     * extracted from the raw PDF content stream: {@code pageCurvedClosedPathBoxes} —
     * closed paths that contain curve segments. These are the rounded-rectangle node
     * boxes of diagrams, which the chunk layer does not expose as line geometry at all.
     *
     * @param document                  the already-parsed document
     * @param pageFillBoxes             per-page filled-path boxes, or null
     * @param pageCurvedClosedPathBoxes per-page closed curve-path boxes, or null
     * @return the list of shapes found per page
     */
    public static List<List<ShapeChunk>> recognize(IDocument document,
                                                   Map<Integer, List<BoundingBox>> pageFillBoxes,
                                                   Map<Integer, List<BoundingBox>> pageCurvedClosedPathBoxes) {
        if (document == null) {
            return Collections.emptyList();
        }
        int pages = document.getNumberOfPages();
        List<List<ShapeChunk>> result = new ArrayList<>(pages);
        for (int pageNumber = 0; pageNumber < pages; pageNumber++) {
            List<IChunk> artifacts = document.getArtifacts(pageNumber);
            List<BoundingBox> fillBoxes = pageFillBoxes == null ? null : pageFillBoxes.get(pageNumber);
            List<BoundingBox> curvedClosedPathBoxes = pageCurvedClosedPathBoxes == null
                    ? null : pageCurvedClosedPathBoxes.get(pageNumber);
            List<ShapeChunk> shapes = recognizePage(pageNumber, artifacts, fillBoxes, curvedClosedPathBoxes);
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
        return recognizePage(pageNumber, artifacts, fillBoxes, null);
    }

    /**
     * Recognizes shapes on a single page, see {@link #recognize(IDocument, Map, Map)}
     * for the extra path-box fallback source.
     *
     * @param pageNumber              the 0-based page number
     * @param artifacts               the raw page artifacts (may be null)
     * @param fillBoxes               raw filled-path boxes (y-up), or null
     * @param curvedClosedPathBoxes   raw closed curve-path boxes (y-up), or null
     * @return a list of new shape chunks; never null
     */
    public static List<ShapeChunk> recognizePage(int pageNumber, List<IChunk> artifacts, List<BoundingBox> fillBoxes,
                                                 List<BoundingBox> curvedClosedPathBoxes) {
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
            // A diagram may be drawn with rounded rectangles only: such a page has no line
            // geometry at all, and the closed curve paths are its only shape source.
            List<ShapeChunk> nodeShapes = new ArrayList<>();
            nodeShapes.addAll(recognizeCurvedNodeBoxes(pageNumber, curvedClosedPathBoxes, nodeShapes));
            return nodeShapes;
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
        // Pattern/shading-filled rectangles (e.g. WIND-style gradient bars) are
        // invisible to the veraPDF artifact layer: SCN_FILL resolves no color for
        // /Pattern color spaces and, on tagged pages, MCID-nested geometry never
        // reaches getArtifacts(). The PDFBox fill boxes act as a fallback source
        // (the same mechanism already used for merged arrowheads).
        shapes.addAll(recognizeBarChartsFromFillBoxes(pageNumber, fillBoxes, shapes));
        // Pie charts are recognised from the same fill-box fallback source. Runs after
        // the bar-chart pass so already recognised bars are excluded from pie candidates.
        shapes.addAll(recognizePieChartsFromFillBoxes(pageNumber, fillBoxes, shapes));
        shapes.addAll(recognizePolylines(pageNumber, thinLines));
        // Rounded-rectangle node boxes survive only as closed curve paths in the content
        // stream; the chunk layer has no line geometry for them. Turn the box-sized ones
        // into rectangles so diagram regions can form around them.
        shapes.addAll(recognizeCurvedNodeBoxes(pageNumber, curvedClosedPathBoxes, shapes));
        // Single-segment lines that bridge two existing shapes are likely arrows/connectors.
        // They are too short to form a polyline on their own but are important for
        // reconstructing flowcharts and diagrams.
        shapes.addAll(recognizeConnectorLines(pageNumber, thinLines, shapes, filledArtBoxes, fillBoxes));
        applyLineChartTypes(shapes);
        applyPathFamilyChartTypes(shapes);

        return shapes;
    }

    /**
     * Retypes the data path of every line chart as {@link ShapeChunk#TYPE_LINE_CHART}.
     *
     * <p>A line chart is not recognizable from one shape alone, so the decision needs the
     * whole page's shape list: a wide, flat polyline acts as the plot's axis frame, and a
     * bent polyline that spans most of that frame is its data path. Two further guards keep
     * flow diagrams and tables out:</p>
     * <ul>
     *   <li>the frame must contain a small marker (a data-point symbol);</li>
     *   <li>the frame must not enclose node-sized shapes — a diagram's boxes, or a table's
     *       cell outlines, would show up there (see
     *       {@link #LINE_CHART_MAX_NODE_LIKE_SHAPES}).</li>
     * </ul>
     *
     * <p>The data paths keep their geometry and only change type, so downstream code that
     * counts shapes per type sees one polyline less and one chart more.</p>
     */
    private static void applyLineChartTypes(List<ShapeChunk> shapes) {
        List<ShapeChunk> frames = new ArrayList<>();
        for (ShapeChunk shape : shapes) {
            if (isLineChartFrame(shape)) {
                frames.add(shape);
            }
        }
        if (frames.isEmpty()) {
            return;
        }
        for (ShapeChunk frame : frames) {
            if (isFrameInsideAnotherFrame(frame, frames)) {
                // Nested frames (a chart grid, a table with merged cells): the outer frame is the
                // region, and judging the inner one lets a small piece of it pass as a "plot".
                continue;
            }
            if (!hasPlotAxes(frame)) {
                continue;
            }
            ShapeChunk dataPath = soleDataPath(shapes, frame);
            if (dataPath == null) {
                continue;
            }
            for (int i = 0; i < shapes.size(); i++) {
                // Identity comparison: ShapeChunk.equals() compares the geometry, and a plot can
                // contain several identical pieces.
                if (shapes.get(i) == dataPath) {
                    shapes.set(i, new ShapeChunk(dataPath.getBoundingBox(), ShapeChunk.TYPE_LINE_CHART,
                            dataPath.getColor(), dataPath.getComponentCount(), dataPath.getComponentBBoxes()));
                    break;
                }
            }
        }
    }

    /**
     * Retypes frameless path families as {@link ShapeChunk#TYPE_LINE_CHART}.
     *
     * <p>Some plots draw their series as several end-to-end polylines (the renderer splits
     * the path at dash boundaries), interleaved with the small filled rectangles those dashes
     * are made of, and draw no axis frame at all. Such a family is recognized when:</p>
     * <ul>
     *   <li>at least two substantial polylines chain in x — mostly disjoint, advancing, with
     *   a small gap and no vertical jump at the joints — into a wide, flat span;</li>
     *   <li>at least one dash-sized rectangle sits on the family's span (the dashes a real
     *   series is stroked with; plain diagram connectors do not have them);</li>
     *   <li>no node-sized shape comes near the span — a diagram's boxes would.</li>
     * </ul>
     *
     * <p>The family's pieces and its dashes are replaced by a single composite chunk (union
     * box, all piece boxes as components), so downstream processors see one chart covering
     * the whole series, not scattered fragments.</p>
     */
    private static void applyPathFamilyChartTypes(List<ShapeChunk> shapes) {
        List<ShapeChunk> pieces = new ArrayList<>();
        for (ShapeChunk shape : shapes) {
            if (ShapeChunk.TYPE_POLYLINE.equals(shape.getShapeType())
                    && shape.getBoundingBox() != null && !shape.getBoundingBox().isEmpty()
                    && shape.getBoundingBox().getWidth() >= LINE_CHART_FAMILY_MIN_PIECE_WIDTH) {
                pieces.add(shape);
            }
        }
        if (pieces.size() < 2) {
            return;
        }
        pieces.sort(Comparator.comparingDouble(shape -> shape.getBoundingBox().getLeftX()));

        List<ShapeChunk> consumed = new ArrayList<>();
        List<ShapeChunk> family = new ArrayList<>();
        for (ShapeChunk piece : pieces) {
            if (!consumed.contains(piece) && !family.isEmpty()) {
                ShapeChunk tail = family.get(family.size() - 1);
                if (!chainLink(tail.getBoundingBox(), piece.getBoundingBox())) {
                    finishFamily(shapes, family, consumed);
                    family = new ArrayList<>();
                }
            }
            if (!consumed.contains(piece)) {
                family.add(piece);
            }
        }
        finishFamily(shapes, family, consumed);
    }

    /** True when {@code next} continues the chain started by {@code prev} in x and y. */
    private static boolean chainLink(BoundingBox prev, BoundingBox next) {
        double gap = next.getLeftX() - prev.getRightX();
        if (gap > LINE_CHART_FAMILY_MAX_GAP || gap < -LINE_CHART_FAMILY_MAX_OVERLAP) {
            return false;
        }
        double yOverlap = intervalOverlap(prev.getBottomY(), prev.getTopY(),
                next.getBottomY(), next.getTopY());
        if (yOverlap >= 2.0) {
            return true;
        }
        double yJump = Math.max(next.getBottomY() - prev.getTopY(), prev.getBottomY() - next.getTopY());
        return yJump <= LINE_CHART_FAMILY_MAX_Y_JUMP;
    }

    /** Validates one chained family and, if it is a frameless plot, consumes its pieces. */
    private static void finishFamily(List<ShapeChunk> shapes, List<ShapeChunk> family,
                                     List<ShapeChunk> consumed) {
        if (family.size() < 2) {
            family.clear();
            return;
        }
        BoundingBox union = new BoundingBox(family.get(0).getBoundingBox().getPageNumber());
        for (ShapeChunk piece : family) {
            union.union(piece.getBoundingBox());
        }
        if (union.getWidth() < LINE_CHART_FAMILY_MIN_WIDTH
                || union.getHeight() < LINE_CHART_FAMILY_MIN_HEIGHT
                || union.getHeight() > LINE_CHART_FAMILY_MAX_ASPECT * union.getWidth()) {
            family.clear();
            return;
        }
        List<ShapeChunk> members = new ArrayList<>(family);
        // The dashes stroked along the series: small rectangles sitting on the span.
        List<ShapeChunk> dashes = new ArrayList<>();
        for (ShapeChunk shape : shapes) {
            if (members.contains(shape) || consumed.contains(shape)
                    || !ShapeChunk.TYPE_RECTANGLE.equals(shape.getShapeType())) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty()
                    || box.getWidth() > LINE_CHART_FAMILY_DASH_MAX_WIDTH
                    || box.getHeight() > LINE_CHART_FAMILY_DASH_MAX_HEIGHT) {
                continue;
            }
            if (box.getLeftX() >= union.getLeftX() - LINE_CHART_FAMILY_NODE_MARGIN
                    && box.getRightX() <= union.getRightX() + LINE_CHART_FAMILY_NODE_MARGIN
                    && intervalOverlap(box.getBottomY(), box.getTopY(),
                            union.getBottomY(), union.getTopY()) >= 2.0) {
                dashes.add(shape);
            }
        }
        if (dashes.isEmpty()) {
            family.clear();
            return;
        }
        // Node-sized shapes near the span mean a diagram, not a plot.
        for (ShapeChunk shape : shapes) {
            if (members.contains(shape) || dashes.contains(shape) || consumed.contains(shape)) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty()
                    || box.getWidth() < LINE_CHART_NODE_MIN_WIDTH
                    || box.getHeight() < LINE_CHART_NODE_MIN_HEIGHT) {
                continue;
            }
            if (intervalOverlap(box.getLeftX(), box.getRightX(),
                    union.getLeftX() - LINE_CHART_FAMILY_NODE_MARGIN,
                    union.getRightX() + LINE_CHART_FAMILY_NODE_MARGIN) > 0
                    && intervalOverlap(box.getBottomY(), box.getTopY(),
                            union.getBottomY() - LINE_CHART_FAMILY_NODE_MARGIN,
                            union.getTopY() + LINE_CHART_FAMILY_NODE_MARGIN) > 0) {
                family.clear();
                return;
            }
        }
        members.addAll(dashes);
        int componentCount = 0;
        List<BoundingBox> components = new ArrayList<>();
        for (ShapeChunk member : members) {
            componentCount += member.getComponentCount();
            components.add(member.getBoundingBox());
        }
        ShapeChunk composite = new ShapeChunk(union, ShapeChunk.TYPE_LINE_CHART,
                family.get(0).getColor(), componentCount, components);
        shapes.removeAll(members);
        shapes.add(composite);
        consumed.addAll(members);
        family.clear();
    }

    /**
     * The single bent path spanning most of {@code frame}'s width, or {@code null} when the
     * frame does not have exactly one — a plot draws one path per series, so several wide flat
     * polylines inside the same frame mean a table grid or a diagram — or when the chart
     * context is missing (no data-point marker, or a node-sized shape inside the frame).
     */
    private static ShapeChunk soleDataPath(List<ShapeChunk> shapes, ShapeChunk frame) {
        BoundingBox frameBox = frame.getBoundingBox();
        if (frameBox == null || frameBox.isEmpty()) {
            return null;
        }
        // A plot carries exactly one substantial polyline: its data path. Everything else inside
        // the frame is a small data-point marker. Several wide polylines mean a chart grid or a
        // table drawn with lines, not a plot (measured: pages whose frames enclose two to seven
        // further wide polylines).
        ShapeChunk found = null;
        int substantial = 0;
        for (ShapeChunk shape : shapes) {
            if (shape == frame || !ShapeChunk.TYPE_POLYLINE.equals(shape.getShapeType())) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty() || !isInside(box, frameBox, LINE_CHART_FRAME_TOLERANCE)) {
                continue;
            }
            if (box.getWidth() < LINE_CHART_MIN_DATA_LENGTH) {
                continue;
            }
            substantial++;
            found = shape;
        }
        if (substantial != 1 || found == null) {
            return null;
        }
        if (found.getBoundingBox().getWidth() < LINE_CHART_MIN_DATA_SPAN_RATIO * frameBox.getWidth()) {
            return null;
        }
        if (hugsFrameEdges(found, frameBox)) {
            // The "data path" is a piece of the frame itself (a table row's right and
            // bottom borders read as a bent path); a plotted series stays clear of the
            // frame's edges.
            return null;
        }
        if (!hasDataMarker(shapes, frame, frameBox)) {
            return null;
        }
        return countNodeLikeShapes(shapes, frame, found, frameBox) == LINE_CHART_MAX_NODE_LIKE_SHAPES ? found : null;
    }

    /**
     * True when the frame's pieces include a vertical run along (nearly) its full height and a
     * horizontal run along (nearly) its full width — the two axes of a plot. Frames assembled
     * from short pieces (a table grid, a diagram drawn box by box) fail this test.
     */
    private static boolean hasPlotAxes(ShapeChunk frame) {
        BoundingBox box = frame.getBoundingBox();
        if (box == null || box.isEmpty()) {
            return false;
        }
        boolean vertical = false;
        boolean horizontal = false;
        for (BoundingBox part : frame.getComponentBBoxes()) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (part.getWidth() <= LINE_CHART_AXIS_MAX_THICKNESS
                    && part.getHeight() >= LINE_CHART_AXIS_MIN_COVER * box.getHeight()) {
                vertical = true;
            }
            if (part.getHeight() <= LINE_CHART_AXIS_MAX_THICKNESS
                    && part.getWidth() >= LINE_CHART_AXIS_MIN_COVER * box.getWidth()) {
                horizontal = true;
            }
        }
        return vertical && horizontal;
    }

    /**
     * True when {@code frame} lies inside another frame candidate: only the outer one is then
     * judged, otherwise a small piece of a chart grid or a table passes as a plot.
     */    private static boolean isFrameInsideAnotherFrame(ShapeChunk frame, List<ShapeChunk> frames) {
        for (ShapeChunk other : frames) {
            if (other == frame) {
                continue;
            }
            if (isInside(frame.getBoundingBox(), other.getBoundingBox(), LINE_CHART_FRAME_TOLERANCE)) {
                return true;
            }
        }
        return false;
    }

    /** A wide, flat polyline large enough to be the frame of a plot area. */
    private static boolean isLineChartFrame(ShapeChunk shape) {
        if (!ShapeChunk.TYPE_POLYLINE.equals(shape.getShapeType())) {
            return false;
        }
        BoundingBox box = shape.getBoundingBox();
        if (box == null || box.isEmpty()) {
            return false;
        }
        double width = box.getWidth();
        double height = box.getHeight();
        return width >= LINE_CHART_MIN_FRAME_WIDTH && height >= LINE_CHART_MIN_FRAME_HEIGHT
                && height <= LINE_CHART_MAX_FRAME_ASPECT * width;
    }

    /** True when the frame encloses a data-point marker (a small polygon on the path). */
    private static boolean hasDataMarker(List<ShapeChunk> shapes, ShapeChunk frame, BoundingBox frameBox) {
        for (ShapeChunk shape : shapes) {
            if (shape == frame) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty()
                    || box.getWidth() > LINE_CHART_MARKER_MAX_SIZE
                    || box.getHeight() > LINE_CHART_MARKER_MAX_SIZE) {
                continue;
            }
            if (isStrictlyInside(box, frameBox, LINE_CHART_MARKER_INSET)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code inner} sits at least {@code margin} points clear of every edge
     * of {@code outer} — unlike {@link #isInside}, shapes touching the boundary fail.
     */
    private static boolean isStrictlyInside(BoundingBox inner, BoundingBox outer, double margin) {
        return inner.getLeftX() >= outer.getLeftX() + margin
                && inner.getRightX() <= outer.getRightX() - margin
                && inner.getBottomY() >= outer.getBottomY() + margin
                && inner.getTopY() <= outer.getTopY() - margin;
    }

    /**
     * True when {@code dataPath} has pieces running along two or more edges of
     * {@code frameBox}. A plotted series lives strictly inside the plot area, so a path
     * that hugs the frame's edges is a fragment of the frame itself — measured on a
     * lattice table whose bottom row's borders (right edge + bottom edge + corner
     * stubs) were misread as the bent data path of a plot.
     */
    private static boolean hugsFrameEdges(ShapeChunk dataPath, BoundingBox frameBox) {
        List<BoundingBox> parts = dataPath.getComponentBBoxes();
        if (parts == null || parts.isEmpty()) {
            return false;
        }
        double leftX = frameBox.getLeftX();
        double rightX = frameBox.getRightX();
        double bottomY = frameBox.getBottomY();
        double topY = frameBox.getTopY();
        boolean left = false;
        boolean right = false;
        boolean bottom = false;
        boolean top = false;
        for (BoundingBox part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            double centerX = 0.5 * (part.getLeftX() + part.getRightX());
            double centerY = 0.5 * (part.getBottomY() + part.getTopY());
            if (part.getWidth() <= 2.0 * LINE_CHART_FRAME_TOLERANCE
                    && Math.abs(centerX - leftX) <= LINE_CHART_FRAME_TOLERANCE
                    && intervalOverlap(part.getBottomY(), part.getTopY(), bottomY, topY)
                            >= LINE_CHART_MAX_EDGE_HUG * frameBox.getHeight()) {
                left = true;
            }
            if (part.getWidth() <= 2.0 * LINE_CHART_FRAME_TOLERANCE
                    && Math.abs(centerX - rightX) <= LINE_CHART_FRAME_TOLERANCE
                    && intervalOverlap(part.getBottomY(), part.getTopY(), bottomY, topY)
                            >= LINE_CHART_MAX_EDGE_HUG * frameBox.getHeight()) {
                right = true;
            }
            if (part.getHeight() <= 2.0 * LINE_CHART_FRAME_TOLERANCE
                    && Math.abs(centerY - bottomY) <= LINE_CHART_FRAME_TOLERANCE
                    && intervalOverlap(part.getLeftX(), part.getRightX(), leftX, rightX)
                            >= LINE_CHART_MAX_EDGE_HUG * frameBox.getWidth()) {
                bottom = true;
            }
            if (part.getHeight() <= 2.0 * LINE_CHART_FRAME_TOLERANCE
                    && Math.abs(centerY - topY) <= LINE_CHART_FRAME_TOLERANCE
                    && intervalOverlap(part.getLeftX(), part.getRightX(), leftX, rightX)
                            >= LINE_CHART_MAX_EDGE_HUG * frameBox.getWidth()) {
                top = true;
            }
        }
        int hugged = (left ? 1 : 0) + (right ? 1 : 0) + (bottom ? 1 : 0) + (top ? 1 : 0);
        return hugged >= 2;
    }

    /** Length of the intersection of the intervals [a0, a1] and [b0, b1]. */
    private static double intervalOverlap(double a0, double a1, double b0, double b1) {
        return Math.max(0.0, Math.min(a1, b1) - Math.max(a0, b0));
    }

    /**
     * Counts the node-sized shapes inside the frame, ignoring the frame itself and the data
     * path under test: two or more of them mean the region is a diagram or a table, not a plot.
     */
    private static int countNodeLikeShapes(List<ShapeChunk> shapes, ShapeChunk frame, ShapeChunk dataPath,
                                           BoundingBox frameBox) {
        int count = 0;
        for (ShapeChunk shape : shapes) {
            if (shape == frame || shape == dataPath) {
                continue;
            }
            BoundingBox box = shape.getBoundingBox();
            if (box == null || box.isEmpty()) {
                continue;
            }
            if (box.getWidth() >= LINE_CHART_NODE_MIN_WIDTH && box.getHeight() >= LINE_CHART_NODE_MIN_HEIGHT
                    && isInside(box, frameBox, LINE_CHART_FRAME_TOLERANCE)) {
                count++;
            }
        }
        return count;
    }

    /** True when {@code inner} lies inside {@code outer} within {@code tolerance} points. */
    private static boolean isInside(BoundingBox inner, BoundingBox outer, double tolerance) {
        return inner.getLeftX() >= outer.getLeftX() - tolerance
                && inner.getRightX() <= outer.getRightX() + tolerance
                && inner.getBottomY() >= outer.getBottomY() - tolerance
                && inner.getTopY() <= outer.getTopY() + tolerance;
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

        // Stacked bar charts are assembled across colors first: their segments belong to
        // different series (colors), so the per-color passes below never see a column as a
        // whole. The rectangles claimed here are removed from the per-color input.
        Set<LineChunk> usedInStacks = new HashSet<>();
        for (List<LineChunk> chart : detectStackedBarColumns(filledRects)) {
            shapes.add(createShape(pageNumber, chart, ShapeChunk.TYPE_BAR_CHART));
            usedInStacks.addAll(chart);
        }
        if (!usedInStacks.isEmpty()) {
            List<LineChunk> remainingRects = new ArrayList<>(filledRects.size());
            for (LineChunk rect : filledRects) {
                if (!usedInStacks.contains(rect)) {
                    remainingRects.add(rect);
                }
            }
            byColor = groupByColor(remainingRects);
        }

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
     * Detects bar-chart groups from the raw PDFBox fill-box fallback source.
     *
     * <p>Pattern- or shading-filled rectangles are invisible to the veraPDF
     * artifact layer: {@code SCN_FILL} cannot resolve a color for a
     * {@code /Pattern} color space, and on tagged pages the MCID-nested
     * geometry never reaches {@code getArtifacts()} at all. PDFBox's
     * {@code GetDrawings} still reports those closed fills, so this method
     * turns the fallback boxes into synthetic filled rectangles (the same
     * "thick line through the rectangle" representation the veraPDF chunk
     * layer produces) and runs them through the same bar-group validation as
     * the artifact-layer rectangles.</p>
     *
     * <p>Boxes that substantially coincide with an already recognized
     * rectangle/bar chart shape are dropped first, so geometry veraPDF did
     * manage to surface (e.g. axis bands) is not duplicated.</p>
     */
    private static List<ShapeChunk> recognizeBarChartsFromFillBoxes(int pageNumber,
                                                                    List<BoundingBox> fillBoxes,
                                                                    List<ShapeChunk> existingShapes) {
        if (fillBoxes == null || fillBoxes.isEmpty()) {
            return Collections.emptyList();
        }
        List<BoundingBox> candidates = filterShapeCoincidentFills(fillBoxes, existingShapes);
        if (candidates.size() < MIN_BAR_COUNT) {
            return Collections.emptyList();
        }
        List<LineChunk> rects = new ArrayList<>(candidates.size());
        for (BoundingBox box : candidates) {
            if (box == null || box.isEmpty()) {
                continue;
            }
            double w = box.getWidth();
            double h = box.getHeight();
            if (w <= 0 || h <= 0) {
                continue;
            }
            if (h >= w) {
                // Vertical bar: a center line along the bar height, thickness = width.
                rects.add(LineChunk.createLineChunk(pageNumber,
                        box.getLeftX() + 0.5 * w, box.getBottomY() + 0.5 * w,
                        box.getLeftX() + 0.5 * w, box.getTopY() - 0.5 * w,
                        w, LineChunk.PROJECTING_SQUARE_CAP_STYLE, FALLBACK_FILL_COLOR));
            } else {
                // Horizontal bar: a center line along the bar length, thickness = height.
                rects.add(LineChunk.createLineChunk(pageNumber,
                        box.getLeftX() + 0.5 * h, box.getBottomY() + 0.5 * h,
                        box.getRightX() - 0.5 * h, box.getBottomY() + 0.5 * h,
                        h, LineChunk.PROJECTING_SQUARE_CAP_STYLE, FALLBACK_FILL_COLOR));
            }
        }
        if (rects.size() < MIN_BAR_COUNT) {
            return Collections.emptyList();
        }
        List<ShapeChunk> shapes = new ArrayList<>();
        // Stacked bar charts are recognized across colors first (see
        // detectStackedBarColumns); the per-color alignment pass below cannot see a
        // stack as a whole.
        Set<LineChunk> usedInStacks = new HashSet<>();
        for (List<LineChunk> chart : detectStackedBarColumns(rects)) {
            shapes.add(createShape(pageNumber, chart, ShapeChunk.TYPE_BAR_CHART));
            usedInStacks.addAll(chart);
        }
        List<LineChunk> remaining = rects;
        if (!usedInStacks.isEmpty()) {
            remaining = new ArrayList<>(rects.size());
            for (LineChunk rect : rects) {
                if (!usedInStacks.contains(rect)) {
                    remaining.add(rect);
                }
            }
        }
        for (List<LineChunk> group : detectBarGroups(remaining)) {
            if (group.size() >= MIN_BAR_COUNT && isValidBarGroup(group)) {
                shapes.add(createShape(pageNumber, group, ShapeChunk.TYPE_BAR_CHART));
            }
        }
        return shapes;
    }

    /**
     * Recognises pie charts from the raw PDFBox fill-box fallback source.
     *
     * <p>Pie sectors are filled polygons whose bounding boxes all share the pie
     * centre as a corner. We cluster the candidate fills by corner coincidence
     * and accept a cluster as the wedge set of one pie when:</p>
     * <ul>
     *   <li>it has at least {@link #MIN_PIE_WEDGE_COUNT} wedges;</li>
     *   <li>the shared corner is the centre of a roughly square fill box (the
     *       whole disc), which becomes the pie bbox — or, when no such disc box
     *       exists, the cluster has at least
     *       {@link #MIN_PIE_WEDGE_COUNT_WITHOUT_DISC} wedges and a bbox is
     *       derived around the shared corner;</li>
     *   <li>no single wedge covers more than {@link #PIE_MAX_WEDGE_AREA_RATIO}
     *       of the disc (rejects a lone full-disc fill sharing a corner).</li>
     * </ul>
     * <p>The union of the wedge boxes is deliberately NOT used as the pie bbox:
     * every wedge has the centre at one corner, so their union only spans the
     * quadrant the wedges fan into, never the whole disc.</p>
     */
    private static List<ShapeChunk> recognizePieChartsFromFillBoxes(int pageNumber,
                                                                    List<BoundingBox> fillBoxes,
                                                                    List<ShapeChunk> existingShapes) {
        if (fillBoxes == null || fillBoxes.isEmpty()) {
            return Collections.emptyList();
        }
        List<BoundingBox> candidates = filterShapeCoincidentFills(fillBoxes, existingShapes);
        if (candidates.size() < MIN_PIE_WEDGE_COUNT) {
            return Collections.emptyList();
        }

        // Group candidate fills by shared corner point. Pie wedges all meet at the pie
        // centre, so they share that corner even though each wedge's bbox only spans part
        // of the disc (the centre sits at one corner of every wedge's bounding box). Their
        // union is therefore NOT a square; the full disc is usually emitted separately as a
        // full-circle fill whose centre equals that corner, which we use as the pie bbox.
        Map<String, CornerCluster> byCorner = new LinkedHashMap<>();
        for (BoundingBox box : candidates) {
            for (double[] corner : cornerPoints(box)) {
                String key = cornerKey(corner[0], corner[1]);
                CornerCluster cluster = byCorner.computeIfAbsent(key, k -> new CornerCluster(corner[0], corner[1]));
                cluster.boxes.add(box);
            }
        }

        List<ShapeChunk> shapes = new ArrayList<>();
        for (CornerCluster cluster : byCorner.values()) {
            // A single fill can contribute several corners; de-duplicate.
            List<BoundingBox> wedges = cluster.boxes.stream().distinct().collect(Collectors.toList());
            if (wedges.size() < MIN_PIE_WEDGE_COUNT) {
                continue;
            }
            // Prefer the separate full-disc fill (centre == shared corner) as the pie bbox.
            // Fall back to a box centred on the shared corner with the largest wedge reach as
            // its half-side (which equals the disc radius for a full pie). A pie that has no
            // disc fill is only accepted with a higher wedge count, to avoid spurious small
            // clusters of 3-4 fills that happen to share a corner and form a square.
            BoundingBox disc = findDiscBox(candidates, cluster.x, cluster.y);
            if (disc == null && wedges.size() < MIN_PIE_WEDGE_COUNT_WITHOUT_DISC) {
                continue;
            }
            BoundingBox pieBox = disc != null ? disc : boxAroundCorner(cluster.x, cluster.y, wedges, pageNumber);
            double discArea = pieBox.getWidth() * pieBox.getHeight();
            boolean dominated = false;
            for (BoundingBox w : wedges) {
                if (w.getWidth() * w.getHeight() > PIE_MAX_WEDGE_AREA_RATIO * discArea) {
                    dominated = true;
                    break;
                }
            }
            if (dominated || !isValidPieDisc(pieBox, cluster.x, cluster.y)) {
                continue;
            }
            LOGGER.log(Level.INFO, "Page " + (pageNumber + 1) + ": recognized pie chart with " + wedges.size()
                    + " wedges at bbox=[" + pieBox.getLeftX() + "," + pieBox.getBottomY() + "," + pieBox.getRightX() + "," + pieBox.getTopY() + "]");
            shapes.add(new ShapeChunk(pieBox, ShapeChunk.TYPE_PIE_CHART, null, wedges.size(), wedges));
        }
        return shapes;
    }

    /**
     * Returns the full-disc fill box whose centre is near ({@code cx},{@code cy}) and
     * that is roughly square, or {@code null} if none qualifies. The disc is the
     * natural bbox for the pie; its centre coincides with the shared wedge corner.
     */
    private static BoundingBox findDiscBox(List<BoundingBox> candidates, double cx, double cy) {
        BoundingBox best = null;
        double bestDist = Double.MAX_VALUE;
        for (BoundingBox box : candidates) {
            if (box == null || box.isEmpty()) {
                continue;
            }
            double centerX = (box.getLeftX() + box.getRightX()) / 2.0;
            double centerY = (box.getBottomY() + box.getTopY()) / 2.0;
            if (Math.abs(centerX - cx) > PIE_DISC_CENTER_TOLERANCE
                    || Math.abs(centerY - cy) > PIE_DISC_CENTER_TOLERANCE) {
                continue;
            }
            if (!isSquare(box)) {
                continue;
            }
            double dist = Math.abs(centerX - cx) + Math.abs(centerY - cy);
            if (dist < bestDist) {
                bestDist = dist;
                best = box;
            }
        }
        return best;
    }

    /**
     * Builds a square box centred on ({@code cx},{@code cy}) whose half-side is the
     * largest distance from that corner to any wedge corner (the disc radius for a
     * full pie). Used only when no separate full-disc fill box is available.
     */
    private static BoundingBox boxAroundCorner(double cx, double cy, List<BoundingBox> wedges, int pageNumber) {
        double reach = 0.0;
        for (BoundingBox w : wedges) {
            reach = Math.max(reach, Math.abs(w.getLeftX() - cx));
            reach = Math.max(reach, Math.abs(w.getRightX() - cx));
            reach = Math.max(reach, Math.abs(w.getBottomY() - cy));
            reach = Math.max(reach, Math.abs(w.getTopY() - cy));
        }
        BoundingBox box = new BoundingBox(pageNumber);
        box.setLeftX(cx - reach);
        box.setRightX(cx + reach);
        box.setBottomY(cy - reach);
        box.setTopY(cy + reach);
        return box;
    }

    /** Returns true when {@code box} is roughly square. */
    private static boolean isSquare(BoundingBox box) {
        double w = box.getWidth();
        double h = box.getHeight();
        return Math.abs(w - h) <= Math.max(PIE_SQUARE_TOLERANCE, PIE_SQUARE_RATIO * Math.max(w, h));
    }

    /**
     * Returns true when {@code box} looks like a pie disc: roughly square and
     * centred on the shared {@code hubX}/{@code hubY} corner.
     */
    private static boolean isValidPieDisc(BoundingBox box, double hubX, double hubY) {
        if (box == null || box.isEmpty()) {
            return false;
        }
        double width = box.getWidth();
        double height = box.getHeight();
        if (width < PIE_MIN_SIZE || height < PIE_MIN_SIZE) {
            return false;
        }
        if (!isSquare(box)) {
            return false;
        }
        double centerX = (box.getLeftX() + box.getRightX()) / 2.0;
        double centerY = (box.getBottomY() + box.getTopY()) / 2.0;
        return Math.abs(hubX - centerX) <= PIE_CENTER_TOLERANCE
                && Math.abs(hubY - centerY) <= PIE_CENTER_TOLERANCE;
    }

    /** Returns the four corners of a fill box as {@code {x, y}} pairs. */
    private static List<double[]> cornerPoints(BoundingBox box) {
        List<double[]> points = new ArrayList<>(4);
        points.add(new double[]{box.getLeftX(), box.getBottomY()});
        points.add(new double[]{box.getRightX(), box.getBottomY()});
        points.add(new double[]{box.getLeftX(), box.getTopY()});
        points.add(new double[]{box.getRightX(), box.getTopY()});
        return points;
    }

    /** Quantises a corner coordinate into a tolerance bucket for coincidence grouping. */
    private static String cornerKey(double x, double y) {
        return Math.round(x / PIE_CORNER_TOLERANCE) + "_" + Math.round(y / PIE_CORNER_TOLERANCE);
    }

    /** Accumulates the fills that share one corner point. */
    private static final class CornerCluster {
        final double x;
        final double y;
        final List<BoundingBox> boxes = new ArrayList<>();

        CornerCluster(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Detects <b>stacked</b> bar charts: several equally wide columns standing
     * side by side with positive gaps between them, where at least one column
     * consists of two or more vertically contiguous segments that belong to
     * different series (different fill colors) and all columns share a baseline.
     *
     * <p>{@link #detectBarGroups} cannot see those charts: it buckets rectangles by
     * their baseline, so the segments stacked on top of each other end up in
     * different buckets and the group never reaches {@link #MIN_BAR_COUNT}. The
     * columns are therefore assembled first and treated as one bar chart; the
     * rectangles used here are excluded from the per-color passes.</p>
     *
     * <p>Only columns that touch each other vertically inside one stack qualify, and
     * the recognition is deliberately narrow so that ordinary tables are not
     * mistaken for charts: the columns have to be separated horizontally, have
     * similar widths, share a baseline, vary in total height as if encoding values,
     * and at least one column has to mix two series colors. A table's cell
     * backgrounds are aligned to the same row boundaries, so their column heights
     * do not vary and the table is rejected.</p>
     *
     * @param rects all filled rectangles of the page (any color)
     * @return one rectangle list per recognized chart; never null
     */
    private static List<List<LineChunk>> detectStackedBarColumns(List<LineChunk> rects) {
        if (rects == null || rects.size() < 2 * MIN_BAR_COUNT) {
            return Collections.emptyList();
        }
        List<LineChunk> sorted = new ArrayList<>(rects);
        sorted.sort(Comparator.comparingDouble(LineChunk::getLeftX));

        // 1. Assemble columns: rectangles sharing the same horizontal extent.
        List<List<LineChunk>> columns = new ArrayList<>();
        for (LineChunk rect : sorted) {
            BoundingBox box = rect.getBoundingBox();
            if (box == null || box.isEmpty()) {
                continue;
            }
            List<LineChunk> target = null;
            for (List<LineChunk> column : columns) {
                BoundingBox columnBox = columnBoundingBox(column);
                if (isSameColumn(columnBox, box)) {
                    target = column;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                columns.add(target);
            }
            target.add(rect);
        }

        // 2. Keep only columns that form a contiguous stack of similar widths.
        List<List<LineChunk>> stacks = new ArrayList<>();
        for (List<LineChunk> column : columns) {
            if (isColumnStack(column)) {
                stacks.add(column);
            }
        }
        if (stacks.size() < MIN_BAR_COUNT) {
            return Collections.emptyList();
        }
        stacks.sort(Comparator.comparingDouble(column -> columnBoundingBox(column).getCenterX()));

        // 3. Split into runs of horizontally separated, similarly wide columns so two
        //    charts on one page (or a chart next to unrelated blocks) stay separate.
        //    The series-color test is only meaningful when the source layer reports
        //    colors at all (the PDFBox fill fallback uses a single synthetic color).
        boolean colorInformationAvailable = countDistinctColors(rects) >= 2;
        List<List<LineChunk>> charts = new ArrayList<>();
        List<List<LineChunk>> run = new ArrayList<>();
        for (List<LineChunk> column : stacks) {
            if (!run.isEmpty() && !isNextColumnOfRun(columnBoundingBox(run.get(run.size() - 1)),
                    columnBoundingBox(column))) {
                addStackedChart(charts, run, colorInformationAvailable);
                run = new ArrayList<>();
            }
            run.add(column);
        }
        addStackedChart(charts, run, colorInformationAvailable);
        return charts;
    }

    /** Adds {@code run} to {@code charts} when it is a chart (else drops it). */
    private static void addStackedChart(List<List<LineChunk>> charts, List<List<LineChunk>> run,
                                        boolean colorInformationAvailable) {
        if (run.size() < MIN_BAR_COUNT) {
            return;
        }
        // A table whose cells carry fills shares one row grid: every column carries the
        // same set of segment boundaries, so the columns all have the same signature. A
        // stacked chart has a different height in (nearly) every column, so the
        // signatures differ. Reject the shared-grid case as a table, not a chart.
        if (isSharedGrid(run)) {
            return;
        }
        double minBaseline = Double.MAX_VALUE;
        double maxBaseline = -Double.MAX_VALUE;
        double minHeight = Double.MAX_VALUE;
        double maxHeight = -Double.MAX_VALUE;
        boolean mixedSeriesColumn = false;
        for (List<LineChunk> column : run) {
            BoundingBox columnBox = columnBoundingBox(column);
            minBaseline = Math.min(minBaseline, columnBox.getBottomY());
            maxBaseline = Math.max(maxBaseline, columnBox.getBottomY());
            double height = columnBox.getHeight();
            minHeight = Math.min(minHeight, height);
            maxHeight = Math.max(maxHeight, height);
            if (countDistinctColors(column) >= 2) {
                mixedSeriesColumn = true;
            }
        }
        // A stacked chart has at least one column that mixes two series colors; a set
        // of single-colored columns with a shared baseline is an ordinary bar chart
        // (handled by detectBarGroups) and is left alone here. When the source layer
        // carries no color information the test is skipped (nothing to compare).
        if (colorInformationAvailable && !mixedSeriesColumn) {
            return;
        }
        if (maxBaseline - minBaseline > STACK_BASELINE_TOLERANCE) {
            return;
        }
        double avgHeight = (minHeight + maxHeight) / 2.0;
        if (avgHeight <= 0 || (maxHeight - minHeight) / avgHeight <= BAR_VALUE_VARIATION) {
            return;
        }
        List<LineChunk> chart = new ArrayList<>();
        for (List<LineChunk> column : run) {
            chart.addAll(column);
        }
        charts.add(chart);
    }

    /** True when both boxes have (nearly) the same horizontal extent. */
    private static boolean isSameColumn(BoundingBox a, BoundingBox b) {
        return Math.abs(a.getLeftX() - b.getLeftX()) <= ADJACENCY_GAP
                && Math.abs(a.getRightX() - b.getRightX()) <= ADJACENCY_GAP;
    }

    /**
     * True when {@code column} is a stack: its segments are vertically contiguous
     * (no gap larger than {@link #ADJACENCY_GAP}) and have similar widths.
     */
    private static boolean isColumnStack(List<LineChunk> column) {
        if (column.isEmpty()) {
            return false;
        }
        double width = column.get(0).getBoundingBox().getWidth();
        for (LineChunk segment : column) {
            BoundingBox box = segment.getBoundingBox();
            if (box == null || box.isEmpty()) {
                return false;
            }
            if (width > 0 && Math.abs(box.getWidth() - width) / width > BAR_WIDTH_VARIATION) {
                return false;
            }
        }
        List<LineChunk> byY = new ArrayList<>(column);
        byY.sort(Comparator.comparingDouble(LineChunk::getBottomY));
        for (int i = 1; i < byY.size(); i++) {
            double gap = byY.get(i).getBoundingBox().getBottomY()
                    - byY.get(i - 1).getBoundingBox().getTopY();
            if (gap > ADJACENCY_GAP || gap < -ADJACENCY_GAP) {
                // A real gap (or a full overlap) means these are not stacked segments.
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code next} is the following column of the same chart: it lies to
     * the right, separated by a positive gap, with a similar width.
     */
    private static boolean isNextColumnOfRun(BoundingBox previous, BoundingBox next) {
        double gap = next.getLeftX() - previous.getRightX();
        if (gap <= 0) {
            return false;
        }
        double width = previous.getWidth();
        return width <= 0 || Math.abs(next.getWidth() - width) / width <= BAR_WIDTH_VARIATION;
    }

    /** Number of distinct fill colors among the segments of one column. */
    private static int countDistinctColors(List<LineChunk> column) {
        Set<String> keys = new HashSet<>();
        for (LineChunk segment : column) {
            keys.add(colorKey(segment.getStrokeColor()));
        }
        return keys.size();
    }

    /**
     * True when {@code columns} all carry (almost) the same set of segment y
     * boundaries - the signature of a table whose filled cells share one row grid.
     *
     * <p>A stacked bar chart has a different height in nearly every column, so the
     * column signatures differ; a table's cells align to the same rows, so every
     * column carries the same boundaries. Rejecting this case stops a zebra-striped
     * or otherwise filled table from being recognised as a stacked bar chart and then
     * cropped into an image.</p>
     */
    private static boolean isSharedGrid(List<List<LineChunk>> columns) {
        if (columns.size() < MIN_BAR_COUNT) {
            return false;
        }
        Map<String, Integer> signatureCounts = new HashMap<>();
        for (List<LineChunk> column : columns) {
            List<Double> bounds = new ArrayList<>();
            for (LineChunk segment : column) {
                BoundingBox box = segment.getBoundingBox();
                if (box == null || box.isEmpty()) {
                    continue;
                }
                bounds.add(roundBound(box.getBottomY()));
                bounds.add(roundBound(box.getTopY()));
            }
            bounds.sort(Double::compareTo);
            StringBuilder signature = new StringBuilder();
            for (Double value : bounds) {
                signature.append(value).append(';');
            }
            signatureCounts.merge(signature.toString(), 1, Integer::sum);
        }
        int maxFrequency = 0;
        for (int count : signatureCounts.values()) {
            maxFrequency = Math.max(maxFrequency, count);
        }
        // Nearly every column sharing one boundary set => a table, not a chart.
        return maxFrequency >= columns.size() - 1;
    }

    /** Rounds a coordinate to 0.5 pt so grid-aligned boundaries coincide. */
    private static double roundBound(double value) {
        return Math.round(value * 2.0) / 2.0;
    }

    private static BoundingBox columnBoundingBox(List<LineChunk> column) {
        return clusterBoundingBox(column);
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

    /**
     * Turns box-sized closed curve paths into {@link ShapeChunk#TYPE_RECTANGLE} nodes.
     *
     * <p>Rounded rectangles are the standard node shape of diagrams (AI infrastructure
     * charts, card grids), but unlike straight rectangles they produce no line chunks:
     * the chunk layer only exposes their text, so the whole diagram would otherwise
     * stay in the text layer. The candidates come from the PDFBox path fallback and are
     * therefore only trusted when several of them are present and none of them
     * coincides with an already recognized solid shape (a pie wedge or a curve-shaped
     * legend blob is not a node).</p>
     *
     * @param pageNumber             the 0-based page number
     * @param curvedClosedPathBoxes  closed curve paths of the page (one box per
     *                               subpath), or null
     * @param existingShapes         shapes recognized so far, used to discard
     *                               candidates that are pieces of them
     * @return the node rectangles plus one compound group shape; never null
     */
    private static List<ShapeChunk> recognizeCurvedNodeBoxes(int pageNumber,
                                                             List<BoundingBox> curvedClosedPathBoxes,
                                                             List<ShapeChunk> existingShapes) {
        if (curvedClosedPathBoxes == null || curvedClosedPathBoxes.size() < MIN_CURVED_NODE_COUNT) {
            return Collections.emptyList();
        }
        List<BoundingBox> candidates = filterShapeCoincidentFills(curvedClosedPathBoxes, existingShapes);
        if (candidates.size() < MIN_CURVED_NODE_COUNT) {
            return Collections.emptyList();
        }
        List<ShapeChunk> shapes = new ArrayList<>();
        List<BoundingBox> nodeBoxes = new ArrayList<>();
        for (BoundingBox candidate : candidates) {
            if (candidate == null || candidate.isEmpty()
                    || candidate.getWidth() < CURVED_NODE_MIN_SIZE
                    || candidate.getHeight() < CURVED_NODE_MIN_SIZE) {
                continue;
            }
            nodeBoxes.add(new BoundingBox(candidate));
            shapes.add(new ShapeChunk(new BoundingBox(candidate), ShapeChunk.TYPE_RECTANGLE,
                    null, 1, Collections.singletonList(new BoundingBox(candidate))));
        }
        if (nodeBoxes.size() >= MIN_CURVED_NODE_COUNT) {
            // A card grid draws every card as a separate subpath of one PDF path, so the
            // individual nodes never overlap and the bbox-overlap grouping cannot see the
            // grid. One extra compound shape spanning all nodes lets the grouping collect
            // them into a single diagram region.
            BoundingBox union = new BoundingBox(nodeBoxes.get(0));
            for (BoundingBox box : nodeBoxes) {
                union.union(box);
            }
            shapes.add(new ShapeChunk(union, ShapeChunk.TYPE_GROUP,
                    null, nodeBoxes.size(), new ArrayList<>(nodeBoxes)));
        }
        return shapes;
    }

    private static List<ShapeChunk> recognizePolylines(int pageNumber, List<LineChunk> thinLines) {
        if (thinLines.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<LineChunk>> byColor = groupByColor(thinLines);
        List<ShapeChunk> shapes = new ArrayList<>();

        for (List<LineChunk> sameColorLines : byColor.values()) {
            if (!shouldBuildChains(sameColorLines, pageNumber, "polyline")) {
                continue;
            }
            List<List<LineChunk>> chains = buildChains(sameColorLines);
            for (List<LineChunk> chain : chains) {
                if (chain.size() < MIN_POLYLINE_SEGMENTS) {
                    continue;
                }
                if (isStraightChain(chain)) {
                    // Collinear segments form a straight connector line that the content
                    // stream (or marked-content splitting) broke into pieces - not a
                    // polyline. Connector recognition below turns it into an arrow so the
                    // arrowhead and the bridge between the two node boxes are preserved.
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
     *
     * <p>Every straight chain that ends in a filled arrowhead also yields an
     * {@link ShapeChunk#TYPE_ARROW_HEADER} shape, i.e. the head on its own. That
     * happens independently of {@link #isConnectorLine}: a flow diagram whose
     * connectors start or end on plain lines (a bracket around a row of boxes,
     * for instance) has no valid shaft end points but still has heads, and the
     * heads are what the flowchart detection grows its region from.</p>
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

        // Both candidate sources are filtered once for all chains: the head is now resolved
        // for every chain (not only for the ones that end up accepted as a connector), so
        // repeating the O(candidates x shapes) filtering per chain would be wasteful.
        List<BoundingBox> arrowFills = fillBoxes == null ? null
                : filterShapeCoincidentFills(fillBoxes, existingShapes);
        List<BoundingBox> headerArtBoxes = filterCandidatesInsideShapes(filledArtBoxes, existingShapes);
        List<BoundingBox> headerFills = filterCandidatesInsideShapes(arrowFills, existingShapes);

        for (List<LineChunk> sameColorLines : byColor.values()) {
            if (!shouldBuildChains(sameColorLines, pageNumber, "connector")) {
                continue;
            }
            List<List<LineChunk>> chains = buildChains(sameColorLines);
            for (List<LineChunk> chain : chains) {
                // A single segment is always a connector candidate (it may be slanted).
                // Long chains only qualify when they are collinear, i.e. a straight line
                // that was split into several pieces; anything else is a real polyline.
                if (chain.size() != 1 && !isStraightChain(chain)) {
                    continue;
                }
                BoundingBox shaft = chainBoundingBox(chain);
                if (shaft == null || shaft.isEmpty()) {
                    continue;
                }
                BoundingBox head = findArrowhead(shaft, filledArtBoxes, arrowFills);
                BoundingBox headOnly = arrowheadOnly(findArrowhead(shaft, headerArtBoxes, headerFills), shaft);
                if (isUsableArrowhead(headOnly)) {
                    double[] headColor = chain.get(0).getStrokeColor();
                    connectors.add(new ShapeChunk(new BoundingBox(headOnly), ShapeChunk.TYPE_ARROW_HEADER,
                            headColor, 1, Collections.singletonList(new BoundingBox(headOnly))));
                }
                if (!isConnectorLine(shaft, existingShapes)) {
                    continue;
                }
                BoundingBox bbox = head == null ? new BoundingBox(shaft) : unionOf(shaft, head);
                List<BoundingBox> parts;
                if (chain.size() == 1) {
                    parts = Collections.singletonList(bbox);
                } else {
                    parts = new ArrayList<>(chain.size());
                    for (LineChunk line : chain) {
                        parts.add(line.getBoundingBox());
                    }
                }
                connectors.add(new ShapeChunk(new BoundingBox(bbox), ShapeChunk.TYPE_ARROW,
                        chain.get(0).getStrokeColor(), chain.size(), parts));
            }
        }
        return connectors;
    }

    private static BoundingBox unionOf(BoundingBox first, BoundingBox second) {
        BoundingBox union = new BoundingBox(first);
        union.union(second);
        return union;
    }

    /**
     * Reduces an arrowhead candidate to the actual head, i.e. the part of the candidate
     * that lies beyond the end of the shaft.
     *
     * <p>Most diagrams draw the whole arrow as a single filled polygon (shaft plus head,
     * e.g. a 3 x 100 pt "block arrow"), so the candidate found by {@link #pickArrowhead}
     * covers the shaft as well. Only the slice beyond the shaft's end is the head; the
     * shaft itself is a line chunk on the page and stays where it is.</p>
     */
    private static BoundingBox arrowheadOnly(BoundingBox head, BoundingBox shaft) {
        if (head == null || shaft == null || shaft.isEmpty()) {
            return head;
        }
        BoundingBox slice = new BoundingBox(head);
        if (shaft.getHeight() >= shaft.getWidth()) {
            if (head.getTopY() > shaft.getTopY() + ARROWHEAD_EXTENSION_EPSILON) {
                slice.setBottomY(Math.max(head.getBottomY(), shaft.getTopY()));
            } else if (head.getBottomY() < shaft.getBottomY() - ARROWHEAD_EXTENSION_EPSILON) {
                slice.setTopY(Math.min(head.getTopY(), shaft.getBottomY()));
            }
        } else {
            if (head.getRightX() > shaft.getRightX() + ARROWHEAD_EXTENSION_EPSILON) {
                slice.setLeftX(Math.max(head.getLeftX(), shaft.getRightX()));
            } else if (head.getLeftX() < shaft.getLeftX() - ARROWHEAD_EXTENSION_EPSILON) {
                slice.setRightX(Math.min(head.getRightX(), shaft.getLeftX()));
            }
        }
        return slice;
    }

    /**
     * Returns true when the slice qualifies as an arrowhead: big enough not to be a
     * table corner or a cell joint, see {@link #MIN_ARROWHEAD_AREA}.
     */
    private static boolean isUsableArrowhead(BoundingBox head) {
        if (head == null || head.isEmpty()) {
            return false;
        }
        double width = head.getWidth();
        double height = head.getHeight();
        return width * height >= MIN_ARROWHEAD_AREA
                && Math.min(width, height) >= MIN_ARROWHEAD_DIMENSION;
    }

    /**
     * Returns the arrowhead of the given shaft (a thin connector line), or null when
     * the shaft has no head. The arrowhead triangle in a PDF is usually rendered as a
     * filled polygon that produces a {@link LineArtChunk} with no line segments. When
     * a small such region overlaps the shaft and extends past exactly one of its ends,
     * it is the head.
     *
     * <p>If no arrowhead is found in the artifact layer (e.g. it was merged into a
     * larger marked-content container and is lost from the artifacts), the raw
     * content-stream fill boxes are used as a fallback candidate source.</p>
     */
    private static BoundingBox findArrowhead(BoundingBox shaft, List<BoundingBox> filledArtBoxes,
                                             List<BoundingBox> fillBoxes) {
        if (shaft == null || shaft.isEmpty()) {
            return null;
        }
        BoundingBox head = pickArrowhead(shaft, filledArtBoxes);
        if (head == null && fillBoxes != null && !fillBoxes.isEmpty()) {
            head = pickArrowhead(shaft, fillBoxes);
        }
        return head;
    }

    /**
     * Drops the candidate fills that lie (almost) entirely inside an already recognized
     * solid shape (rectangle / bar chart).
     *
     * <p>Such a candidate is a piece of that shape — the filled header band of a table is
     * the measured case — and can never be an arrowhead. {@link #filterShapeCoincidentFills}
     * does not cover it: that filter compares the areas of the two boxes, which keeps
     * small pieces of large shapes on purpose (an arrowhead poking into a big node box
     * must survive).</p>
     */
    private static List<BoundingBox> filterCandidatesInsideShapes(List<BoundingBox> candidates,
                                                                  List<ShapeChunk> existingShapes) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<BoundingBox> filtered = new ArrayList<>(candidates.size());
        for (BoundingBox candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            double candidateArea = candidate.getWidth() * candidate.getHeight();
            boolean inside = false;
            for (ShapeChunk shape : existingShapes) {
                String type = shape.getShapeType();
                if (!ShapeChunk.TYPE_RECTANGLE.equals(type) && !ShapeChunk.TYPE_BAR_CHART.equals(type)) {
                    continue;
                }
                BoundingBox shapeBox = shape.getBoundingBox();
                if (shapeBox == null || shapeBox.isEmpty()) {
                    continue;
                }
                if (overlapArea(candidate, shapeBox) >= MAX_ARROWHEAD_SHAPE_COVERAGE * candidateArea) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                filtered.add(candidate);
            }
        }
        return filtered;
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

    private static boolean isConnectorLine(BoundingBox shaft, List<ShapeChunk> existingShapes) {
        if (shaft == null || shaft.isEmpty()) {
            return false;
        }
        // The shaft is a straight run of segments, so its two ends are the extreme
        // points along its dominant axis.
        double startX;
        double startY;
        double endX;
        double endY;
        if (shaft.getHeight() >= shaft.getWidth()) {
            startX = 0.5 * (shaft.getLeftX() + shaft.getRightX());
            startY = shaft.getBottomY();
            endX = startX;
            endY = shaft.getTopY();
        } else {
            startX = shaft.getLeftX();
            startY = 0.5 * (shaft.getBottomY() + shaft.getTopY());
            endX = shaft.getRightX();
            endY = startY;
        }
        ShapeChunk startShape = findShapeNearPoint(startX, startY, existingShapes);
        ShapeChunk endShape = findShapeNearPoint(endX, endY, existingShapes);
        if (startShape == null || endShape == null || startShape == endShape) {
            return false;
        }
        // A line whose bbox lies fully inside another shape's bbox is an internal
        // structural line of that shape (e.g. a table row separator running between two
        // opposite table borders), not a connector between two distinct shapes. The
        // two endpoints merely happen to land within CONNECTOR_MARGIN of *different*
        // shapes (the left table edge vs the right outline polyline), but the line
        // itself does not bridge anything — it is contained by a single spanning shape.
        return !isContainedInAnyShape(shaft, existingShapes);
    }

    /**
     * Returns the union bounding box of a chain, or {@code null} when it is empty.
     */
    private static BoundingBox chainBoundingBox(List<LineChunk> chain) {
        BoundingBox union = null;
        for (LineChunk line : chain) {
            BoundingBox box = line.getBoundingBox();
            if (box == null || box.isEmpty()) {
                continue;
            }
            if (union == null) {
                union = new BoundingBox(box);
            } else {
                union.union(box);
            }
        }
        return union;
    }

    /**
     * Returns true when the chain is a set of collinear segments: a single straight
     * line that the content stream (or marked-content splitting) broke into pieces.
     *
     * <p>The test looks at the union box of the chain — a straight horizontal run is
     * thin vertically and vice versa. A slanted run (e.g. a real polyline or a
     * diagonal connector drawn as several segments) has a union box that is wide and
     * tall at the same time and is therefore not classified as straight here.</p>
     */
    private static boolean isStraightChain(List<LineChunk> chain) {
        BoundingBox union = chainBoundingBox(chain);
        if (union == null || union.isEmpty()) {
            return false;
        }
        boolean thinVertically = union.getWidth() <= STRAIGHT_CHAIN_TOLERANCE;
        boolean thinHorizontally = union.getHeight() <= STRAIGHT_CHAIN_TOLERANCE;
        return thinVertically != thinHorizontally;
    }

    /**
     * Returns true when the given bbox is fully contained (including coincident
     * edges) by any shape already on the page.
     */
    private static boolean isContainedInAnyShape(BoundingBox shaft, List<ShapeChunk> shapes) {
        for (ShapeChunk shape : shapes) {
            BoundingBox box = shape.getBoundingBox();
            if (box != null && !box.isEmpty()
                    && box.getLeftX() <= shaft.getLeftX()
                    && box.getRightX() >= shaft.getRightX()
                    && box.getBottomY() <= shaft.getBottomY()
                    && box.getTopY() >= shaft.getTopY()) {
                return true;
            }
        }
        return false;
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
     *
     * <p>Uses a {@link LinkedList} walked with a {@link ListIterator} so that
     * scanning and removing candidates are both O(1). The previous implementation
     * used indexed access on a {@code LinkedList}, which made the scan O(n²) per
     * chain and caused pathological runtimes for pages with many segments.</p>
     */
    private static List<List<LineChunk>> buildChains(List<LineChunk> lines) {
        LinkedList<LineChunk> remaining = new LinkedList<>(lines);
        List<List<LineChunk>> chains = new ArrayList<>();

        while (!remaining.isEmpty()) {
            LineChunk seed = remaining.removeFirst();
            List<LineChunk> chain = new ArrayList<>();
            chain.add(seed);
            Vertex start = seed.getStart();
            Vertex end = seed.getEnd();

            boolean extended = true;
            while (extended && !remaining.isEmpty()) {
                extended = false;
                ListIterator<LineChunk> it = remaining.listIterator();
                while (it.hasNext()) {
                    LineChunk candidate = it.next();
                    Vertex cStart = candidate.getStart();
                    Vertex cEnd = candidate.getEnd();
                    if (Vertex.areCloseVertexes(end, cStart, ADJACENCY_GAP)) {
                        it.remove();
                        chain.add(candidate);
                        end = cEnd;
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(end, cEnd, ADJACENCY_GAP)) {
                        it.remove();
                        chain.add(candidate);
                        end = cStart;
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(start, cEnd, ADJACENCY_GAP)) {
                        it.remove();
                        chain.add(0, candidate);
                        start = cStart;
                        extended = true;
                        break;
                    } else if (Vertex.areCloseVertexes(start, cStart, ADJACENCY_GAP)) {
                        it.remove();
                        chain.add(0, candidate);
                        start = cEnd;
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
     * Guards the expensive chain-building step against pathological inputs.
     *
     * <p>When a single color produces thousands of line segments (typically
     * dense vector patterns or heavily fragmented strokes), connecting them all
     * is prohibitively expensive and almost never yields meaningful polylines
     * or connectors. This check skips that color and logs a warning so the
     * behavior is visible.</p>
     *
     * @param sameColorLines lines of one color group
     * @param pageNumber     0-based page number for the log message
     * @param kind           "polyline" or "connector", used in the warning
     * @return true if chain building should proceed, false if it should be skipped
     */
    private static boolean shouldBuildChains(List<LineChunk> sameColorLines, int pageNumber, String kind) {
        if (sameColorLines.size() <= MAX_LINES_PER_COLOR_FOR_CHAIN_RECOGNITION) {
            return true;
        }
        LOGGER.log(Level.WARNING,
                "Page {0}: skipping {1} recognition for color with {2} line segments (threshold {3})",
                new Object[]{pageNumber + 1, kind, sameColorLines.size(), MAX_LINES_PER_COLOR_FOR_CHAIN_RECOGNITION});
        return false;
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
     * Returns true when at least one of the given items is a
     * {@link ShapeChunk#TYPE_ARROW_HEADER} shape.
     */
    public static boolean containsArrowHeader(List<IObject> items) {
        if (items == null) {
            return false;
        }
        for (IObject obj : items) {
            if (obj instanceof ShapeChunk
                    && ShapeChunk.TYPE_ARROW_HEADER.equals(((ShapeChunk) obj).getShapeType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Groups the page items into diagram regions grown from the recognized arrowheads.
     *
     * <p>Every {@link ShapeChunk#TYPE_ARROW_HEADER} seeds one region. The region then
     * repeatedly absorbs every item — shapes and raw lines alike — whose bounding box
     * meets the region's bounding box ({@link #ADJACENCY_GAP} tolerance), so it follows
     * the connector to the next node and from there on through the rest of the diagram.
     * Regions of heads that end up meeting each other are merged, so a diagram whose
     * arrows point at the same group of nodes becomes a single region.</p>
     *
     * <p>Unlike {@link #groupShapes(List)}, the growth is not limited to shapes: the
     * connectors of a flow diagram are routinely plain lines that never become a shape
     * of their own, and they are exactly what bridges a head to the node it points at.</p>
     *
     * @param shapes     the recognized shapes of the page; must contain the arrowheads
     * @param extraItems the page's raw lines (line chunks / bbox-only line art); may be null
     * @return one group per merged diagram region, or an empty list when the page has no
     *         arrowhead at all
     */
    public static List<List<IObject>> groupShapesByArrowHeaders(List<IObject> shapes, List<IObject> extraItems) {
        if (shapes == null || !containsArrowHeader(shapes)) {
            return Collections.emptyList();
        }
        List<IObject> candidates = new ArrayList<>(shapes);
        if (extraItems != null) {
            candidates.addAll(extraItems);
        }

        List<Set<IObject>> regions = new ArrayList<>();
        List<BoundingBox> regionBoxes = new ArrayList<>();
        for (IObject seed : shapes) {
            if (!(seed instanceof ShapeChunk)
                    || !ShapeChunk.TYPE_ARROW_HEADER.equals(((ShapeChunk) seed).getShapeType())
                    || seed.getBoundingBox() == null || seed.getBoundingBox().isEmpty()) {
                continue;
            }
            Set<IObject> region = Collections.newSetFromMap(new IdentityHashMap<IObject, Boolean>());
            region.add(seed);
            BoundingBox reach = new BoundingBox(seed.getBoundingBox());
            boolean grew = true;
            while (grew) {
                grew = false;
                for (IObject candidate : candidates) {
                    if (region.contains(candidate)) {
                        continue;
                    }
                    BoundingBox box = candidate.getBoundingBox();
                    if (box == null || box.isEmpty() || !meets(reach, box, ADJACENCY_GAP)) {
                        continue;
                    }
                    region.add(candidate);
                    reach.union(box);
                    grew = true;
                }
            }
            regions.add(region);
            regionBoxes.add(reach);
        }
        mergeOverlappingRegions(regions, regionBoxes);

        List<List<IObject>> groups = new ArrayList<>(regions.size());
        for (Set<IObject> region : regions) {
            groups.add(new ArrayList<>(region));
        }
        return groups;
    }

    /**
     * Merges the regions whose bounding boxes meet each other, so overlapping diagram
     * regions end up as one group. Both lists are modified in place and stay in sync.
     */
    private static void mergeOverlappingRegions(List<Set<IObject>> regions, List<BoundingBox> boxes) {
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < regions.size() && !merged; i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    if (!meets(boxes.get(i), boxes.get(j), ADJACENCY_GAP)) {
                        continue;
                    }
                    regions.get(i).addAll(regions.get(j));
                    boxes.get(i).union(boxes.get(j));
                    regions.remove(j);
                    boxes.remove(j);
                    merged = true;
                    break;
                }
            }
        }
    }

    /**
     * Returns true when the two boxes overlap, or are separated by no more than
     * {@code tolerance} on both axes.
     */
    private static boolean meets(BoundingBox a, BoundingBox b, double tolerance) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.getLeftX() <= b.getRightX() + tolerance && b.getLeftX() <= a.getRightX() + tolerance
                && a.getBottomY() <= b.getTopY() + tolerance && b.getBottomY() <= a.getTopY() + tolerance;
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
