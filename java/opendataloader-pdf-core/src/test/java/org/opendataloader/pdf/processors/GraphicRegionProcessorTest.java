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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Covers the gates and the two capture paths of {@link GraphicRegionProcessor}:
 * <ul>
 *   <li>fragment clustering — a tagged PDF gives every part of a logo lockup its own
 *       marked group, so the lockup arrives as several {@link LineArtChunk}s that are
 *       each vetoed on their own while their envelope passes every gate;</li>
 *   <li>what counts as a rule — a pictorial mark is enclosed by one outline whose box
 *       is the mark itself and must not be read as a table rule;</li>
 *   <li>what a crop may remove — tables that only restate the captured strokes go with
 *       it, tables holding text do not;</li>
 *   <li>inline marks — a small signature set in front of a company name is joined to
 *       that text line rather than emitted as a standalone image.</li>
 * </ul>
 */
class GraphicRegionProcessorTest {

    private static final double[] GRAY = new double[]{0.5, 0.5, 0.5};
    private static final double PAGE_WIDTH = 595.276;
    private static final double PAGE_HEIGHT = 841.89;

    @BeforeEach
    void setUpGlobalState() {
        // Text emptiness checks read this flag; outside a document run it is unset.
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
    }

    @Test
    void capturesIndividuallyVetoedFragmentsAsOneLockup() {
        // Measured on the "前標誌" lockup of a three-page announcement: a pictorial
        // mark, a glyph outline below MIN_REGION_WIDTH and a wordmark, each rejected
        // by itself and all three passing as the 170.6 x 50.2 pt envelope.
        LineArtChunk mark = lineArt(92.1, 496.4, 136.0, 545.6);
        LineArtChunk glyph = lineArt(139.1, 517.3, 162.6, 541.2);
        LineArtChunk wordmark = lineArt(138.4, 495.4, 262.7, 540.2);
        ShapeChunk underline = new ShapeChunk(new BoundingBox(0, 92.5, 496.8, 135.6, 502.1),
                ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
        List<IObject> pageContents = new ArrayList<>(List.of(mark, glyph, wordmark, underline));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 1, imagesUtils,
                shortLines(10, 100, 500), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "The three fragments should be captured as one region");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(92.1, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(495.4, bbox.getBottomY(), 0.0001);
        Assertions.assertEquals(262.7, bbox.getRightX(), 0.0001);
        Assertions.assertEquals(545.6, bbox.getTopY(), 0.0001);
        Assertions.assertFalse(pageContents.contains(mark));
        Assertions.assertFalse(pageContents.contains(glyph));
        Assertions.assertFalse(pageContents.contains(wordmark));
        Assertions.assertFalse(pageContents.contains(underline),
                "A shape fully covered by the crop should not survive it");
        Assertions.assertTrue(pageContents.contains(imagesUtils.saved.get(0)),
                "The screenshot should replace the fragments");
    }

    @Test
    void judgesDistantFragmentsSeparately() {
        // A 60 pt gutter is beyond the fragment tolerance, so the sparse second box
        // must not borrow the density of the first one.
        LineArtChunk dense = lineArt(50, 500, 90, 540);
        LineArtChunk sparse = lineArt(150, 500, 190, 540);
        List<IObject> pageContents = new ArrayList<>(List.of(dense, sparse));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 55, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(50.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(90.0, bbox.getRightX(), 0.0001);
        Assertions.assertTrue(pageContents.contains(sparse),
                "The vetoed fragment should stay untouched");
    }

    @Test
    void fallsBackToIndividualJudgementWhenTheEnvelopeReachesAnImage() {
        // Growing an envelope is the point of clustering, so it has an extra gate: it
        // may not swallow a neighbouring raster figure, which would then be rendered
        // twice. The fragments are still worth judging on their own.
        LineArtChunk left = lineArt(100, 500, 160, 540);
        LineArtChunk right = lineArt(165, 500, 205, 540);
        ImageChunk raster = new ImageChunk(new BoundingBox(0, 170, 495, 210, 545));
        List<IObject> pageContents = new ArrayList<>(List.of(left, right, raster));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 105, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "Only the fragment that passes on its own should be captured");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(100.0, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(160.0, bbox.getRightX(), 0.0001);
        Assertions.assertTrue(pageContents.contains(raster), "The raster image must survive");
    }

    @Test
    void capturesASingleDenseFragment() {
        // Regression guard: the pre-clustering behaviour, one self-sufficient region.
        LineArtChunk single = lineArt(100, 500, 300, 600);
        List<IObject> pageContents = new ArrayList<>(List.of(single));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 110, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertEquals(1, pageContents.size());
        Assertions.assertTrue(pageContents.get(0) instanceof ImageChunk);
    }

    @Test
    void capturesAMarkEnclosedByItsOwnOutline() {
        // Measured on the 屋 house logo of a 2007 announcement: the mark is a
        // 51.2 x 48.3 pt region whose enclosing outline polyline is 51.03 x 47.67 pt,
        // so it spans 99.6 % of the region. Judged by length alone that is a table
        // rule and the logo produced no output at all; an outline is square, a rule is
        // thin, and the thickness test is what tells them apart.
        LineArtChunk house = lineArt(413.5, 667.1, 464.8, 715.4);
        ShapeChunk outline = polyline(413.6, 667.6, 464.7, 715.3);
        List<IObject> pageContents = new ArrayList<>(List.of(house, outline));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 420, 675), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "A square outline is the mark itself, not a rule spanning the region");
        BoundingBox bbox = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(413.5, bbox.getLeftX(), 0.0001);
        Assertions.assertEquals(715.4, bbox.getTopY(), 0.0001);
        Assertions.assertFalse(pageContents.contains(house));
        Assertions.assertFalse(pageContents.contains(outline));
    }

    @Test
    void stillRejectsABandSpannedByOneWideRule() {
        // The counterpart of the test above. A wireless table row is drawn as a
        // polyline as wide as the band and one third as tall (measured 506 x 141 pt),
        // which is a rule and must keep vetoing the capture.
        LineArtChunk band = lineArt(42, 185, 549, 326);
        ShapeChunk rule = polyline(42.5, 185.5, 548.5, 326.5);
        List<IObject> pageContents = new ArrayList<>(List.of(band, rule));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 50, 200), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertTrue(imagesUtils.saved.isEmpty(),
                "A thin stroke spanning the band still marks ruled structure");
        Assertions.assertTrue(pageContents.contains(band));
        Assertions.assertTrue(pageContents.contains(rule));
    }

    @Test
    void dropsOnlyTheContentFreeTableCoveredByTheCrop() {
        // The line preprocessing reads the strokes of a pictorial mark as rules, so the
        // 屋 character inside the house logo also surfaced as a 2 x 2 table whose four
        // cells held nothing but those strokes. The crop now shows the graphic, so the
        // blank table has to go with it — while a table that does hold words stays.
        LineArtChunk mark = lineArt(100, 500, 300, 600);
        TableBorder blank = table(110, 510, 190, 560, null);
        TableBorder written = table(200, 510, 290, 590, "9.75%");
        List<IObject> pageContents = new ArrayList<>(List.of(mark, blank, written));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                shortLines(10, 110, 510), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size());
        Assertions.assertFalse(pageContents.contains(blank),
                "A table with nothing in any cell only restates the captured strokes");
        Assertions.assertTrue(pageContents.contains(written),
                "The crop must never swallow a table that carries text");
    }

    @Test
    void inlinesASmallVectorMarkIntoTheAdjacentTextLine() {
        // Measured on the 新百利有限公司 lockup: a 23.21 x 23.77 pt signature set 4.38 pt
        // in front of the company name. At that size it is under MIN_REGION_WIDTH and,
        // with seven strokes, under MIN_RAW_LINE_COUNT, so as a region it produced no
        // output at all — yet it is not a region, it is the initial of that line.
        StaticLayoutContainers.setImagesDirectory("images");
        LineArtChunk signature = lineArt(249.2, 427.7, 272.4, 451.5);
        TextLine name = textLine(276.8, 433.75, 345.83, 443.49, "新百利有限公司");
        SemanticParagraph heading = new SemanticParagraph(new BoundingBox(0, 276.8, 433.75, 345.83, 443.49),
                Collections.singletonList(new TextColumn(name)));
        List<IObject> pageContents = new ArrayList<>(List.of(signature, heading));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                strokes(5, 250, 429), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertEquals(1, imagesUtils.saved.size(),
                "The mark should be screenshotted once, as part of the line");
        BoundingBox crop = imagesUtils.saved.get(0).getBoundingBox();
        Assertions.assertEquals(249.2, crop.getLeftX(), 0.0001);
        Assertions.assertEquals(451.5, crop.getTopY(), 0.0001);
        Assertions.assertFalse(pageContents.contains(signature));

        String value = name.getValue();
        Assertions.assertTrue(value.contains("<img"), "The line should carry the image inline: " + value);
        Assertions.assertTrue(value.contains("新百利有限公司"), "The line must keep its text: " + value);
        Assertions.assertTrue(value.indexOf("<img") < value.indexOf("新百利有限公司"),
                "The mark precedes the name in reading order: " + value);
        Assertions.assertEquals(249.2, name.getBoundingBox().getLeftX(), 0.0001,
                "The line has to widen to cover what it now contains");
        Assertions.assertEquals(249.2, heading.getBoundingBox().getLeftX(), 0.0001,
                "and so does the paragraph enclosing it");
    }

    @Test
    void leavesAMarkThatIsNotSetIntoAnyTextLine() {
        // Distance is the whole test: 153 pt of gutter means the box is a separate
        // object on the page, and at 23 pt it is too small to be a region either.
        StaticLayoutContainers.setImagesDirectory("images");
        LineArtChunk signature = lineArt(100, 427.7, 123.2, 451.5);
        TextLine name = textLine(276.8, 433.75, 345.83, 443.49, "新百利有限公司");
        SemanticParagraph heading = new SemanticParagraph(new BoundingBox(0, 276.8, 433.75, 345.83, 443.49),
                Collections.singletonList(new TextColumn(name)));
        List<IObject> pageContents = new ArrayList<>(List.of(signature, heading));

        CapturingImagesUtils imagesUtils = new CapturingImagesUtils();
        GraphicRegionProcessor.processGraphicRegions(pageContents, 0, imagesUtils,
                strokes(5, 101, 429), PAGE_WIDTH, PAGE_HEIGHT);

        Assertions.assertTrue(imagesUtils.saved.isEmpty());
        Assertions.assertTrue(pageContents.contains(signature));
        Assertions.assertFalse(name.getValue().contains("<img"));
    }

    private static LineArtChunk lineArt(double leftX, double bottomY, double rightX, double topY) {
        return new LineArtChunk(new BoundingBox(0, leftX, bottomY, rightX, topY));
    }

    private static ShapeChunk polyline(double leftX, double bottomY, double rightX, double topY) {
        return new ShapeChunk(new BoundingBox(0, leftX, bottomY, rightX, topY),
                ShapeChunk.TYPE_POLYLINE, GRAY, 1);
    }

    private static TextLine textLine(double leftX, double bottomY, double rightX, double topY, String text) {
        return new TextLine(new TextChunk(new BoundingBox(0, leftX, bottomY, rightX, topY), text, 9.0, bottomY + 2));
    }

    /** A one-cell table whose cell holds {@code text} (or nothing when null). */
    private static TableBorder table(double leftX, double bottomY, double rightX, double topY, String text) {
        BoundingBox box = new BoundingBox(0, leftX, bottomY, rightX, topY);
        TableBorderCell cell = new TableBorderCell(0, 0, 1, 1, 0L);
        cell.setBoundingBox(box);
        if (text != null) {
            cell.addContentObject(new TextChunk(box, text, 9.0, bottomY + 2));
        }
        ContentRow row = new ContentRow(0);
        row.put(0, cell);
        return new OneRowTable(box, row);
    }

    /**
     * {@link TableBorder}'s row-carrying constructor asks the document for an ID, which
     * a unit test has none of, so the rows are supplied through {@code getRows()}.
     */
    private static final class OneRowTable extends TableBorder {

        private final TableBorderRow[] rows;

        private OneRowTable(BoundingBox box, TableBorderRow row) {
            super(1, 1);
            rows = new TableBorderRow[]{row};
            setBoundingBox(box);
        }

        @Override
        public TableBorderRow[] getRows() {
            return rows;
        }
    }

    /** {@link TableBorderRow} leaves its cell array unpopulated; tests need to fill it. */
    private static final class ContentRow extends TableBorderRow {

        private ContentRow(int rowNumber) {
            super(rowNumber, 1, 0L);
        }

        private void put(int index, TableBorderCell cell) {
            cells[index] = cell;
        }
    }

    /** {@code count} 10 x 3 pt strokes starting at {@code (x, y)} and stepping right/down. */
    private static List<IObject> shortLines(int count, double x, double y) {
        List<IObject> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(new LineChunk(0, x + i, y + (i % 5) * 8, x + 10 + i, y + (i % 5) * 8 + 3));
        }
        return lines;
    }

    /** Like {@link #shortLines} but stepping by 4 pt, so the strokes fit a 24 pt mark. */
    private static List<IObject> strokes(int count, double x, double y) {
        List<IObject> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(new LineChunk(0, x + i, y + i * 4, x + 8 + i, y + i * 4 + 3));
        }
        return lines;
    }
}
