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
package org.opendataloader.pdf.json;

import org.junit.jupiter.api.Test;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the embedded nested-table HTML produced by
 * {@code JsonWriter#renderTableBorderToHtml}.
 *
 * <p>A spanning cell is stored with the SAME reference in every slot it covers
 * (see TableBorderRow#getNumberOfCells, which only counts slots whose
 * rowNumber/colNumber match the cell origin). The row renderer must therefore
 * emit a cell only at its origin position; iterating the raw array without that
 * guard duplicated every colspan/rowspan cell and made the first row of the
 * sub-tables on page 3 of 202609071788789239330011123.pdf render an extra
 * "公司資料" column on the right.</p>
 */
class JsonWriterNestedTableHtmlTest {

    @Test
    void colspanHeaderCellIsRenderedOnceNotOncePerCoveredSlot() throws Exception {
        // 2-column sub-table: header row is one colspan=2 cell whose reference
        // occupies both slots, followed by a normal 2-cell row.
        TableBorder table = new TableBorder(2, 2);
        TableBorderRow headerRow = new TableBorderRow(0, 2, null);
        headerRow.setBoundingBox(new BoundingBox(0, 0, 280, 200, 300));
        TableBorderCell header = new TableBorderCell(0, 0, 1, 2, null);
        header.setBoundingBox(new BoundingBox(0, 0, 280, 200, 300));
        header.addContentObject(textChunk("\u516C\u53F8\u8CC7\u6599", 0, 280, 200, 300)); // 公司資料
        headerRow.getCells()[0] = header;
        headerRow.getCells()[1] = header; // spanned slot: same reference
        table.getRows()[0] = headerRow;

        TableBorderRow bodyRow = new TableBorderRow(1, 2, null);
        bodyRow.setBoundingBox(new BoundingBox(0, 0, 180, 200, 280));
        TableBorderCell label = new TableBorderCell(1, 0, 1, 1, null);
        label.setBoundingBox(new BoundingBox(0, 0, 180, 100, 280));
        label.addContentObject(textChunk("\u80A1\u4EFD\u4EE3\u865F", 0, 180, 100, 280)); // 股份代號
        TableBorderCell value = new TableBorderCell(1, 1, 1, 1, null);
        value.setBoundingBox(new BoundingBox(0, 100, 180, 200, 280));
        value.addContentObject(textChunk("09976", 100, 180, 200, 280));
        bodyRow.getCells()[0] = label;
        bodyRow.getCells()[1] = value;
        table.getRows()[1] = bodyRow;

        assertEquals("<table><tr><td colspan=\"2\">\u516C\u53F8\u8CC7\u6599</td></tr>"
                + "<tr><td>\u80A1\u4EFD\u4EE3\u865F</td><td>09976</td></tr></table>",
            render(table));
    }

    @Test
    void rowspanCellIsRenderedOnlyInItsOriginRow() throws Exception {
        // 2x2 table: (0,0) has rowspan=2 and its reference also fills slot (1,0).
        TableBorder table = new TableBorder(2, 2);
        TableBorderCell side = new TableBorderCell(0, 0, 2, 1, null);
        side.setBoundingBox(new BoundingBox(0, 0, 100, 100, 300));
        side.addContentObject(textChunk("A", 0, 100, 100, 300));
        TableBorderCell top = new TableBorderCell(0, 1, 1, 1, null);
        top.setBoundingBox(new BoundingBox(0, 100, 200, 200, 300));
        top.addContentObject(textChunk("B", 100, 200, 200, 300));
        TableBorderCell bottom = new TableBorderCell(1, 1, 1, 1, null);
        bottom.setBoundingBox(new BoundingBox(0, 100, 100, 200, 200));
        bottom.addContentObject(textChunk("C", 100, 100, 200, 200));

        TableBorderRow row0 = new TableBorderRow(0, 2, null);
        row0.getCells()[0] = side;
        row0.getCells()[1] = top;
        TableBorderRow row1 = new TableBorderRow(1, 2, null);
        row1.getCells()[0] = side; // spanned slot from row 0
        row1.getCells()[1] = bottom;
        table.getRows()[0] = row0;
        table.getRows()[1] = row1;

        assertEquals("<table><tr><td rowspan=\"2\">A</td><td>B</td></tr>"
                + "<tr><td>C</td></tr></table>", render(table));
    }

    /**
     * A cell background assigned from a page rectangle shape must be emitted as
     * an inline style, using the same #rrggbb representation as the bg_color
     * field of the outer table grid. The dominant chunk font color rides along
     * (white header text on the dark cards); black and missing colors are
     * skipped because browsers already render text black.
     */
    @Test
    void cellBackgroundColorIsEmittedAsInlineStyle() throws Exception {
        TableBorder table = new TableBorder(1, 1);
        TableBorderRow row = new TableBorderRow(0, 1, null);
        TableBorderCell cell = new TableBorderCell(0, 0, 1, 1, null);
        cell.setBoundingBox(new BoundingBox(0, 0, 280, 200, 300));
        TextChunk white = textChunk("X", 0, 280, 200, 300);
        white.setFontColor(new double[] {1, 1, 1});
        cell.addContentObject(white);
        cell.setBackgroundColor(new double[] {0, 0, 0.35});
        row.getCells()[0] = cell;
        table.getRows()[0] = row;

        assertEquals("<table><tr><td style=\"background-color:#000059; color:#ffffff\">X</td></tr></table>",
            render(table));
    }

    @Test
    void blackOrMissingFontColorIsNotEmittedOnColoredBackground() throws Exception {
        TableBorder table = new TableBorder(1, 2);
        TableBorderRow row = new TableBorderRow(0, 2, null);

        TableBorderCell blackCell = new TableBorderCell(0, 0, 1, 1, null);
        blackCell.setBoundingBox(new BoundingBox(0, 0, 280, 100, 300));
        TextChunk black = textChunk("A", 0, 280, 100, 300);
        black.setFontColor(new double[] {0, 0, 0});
        blackCell.addContentObject(black);
        blackCell.setBackgroundColor(new double[] {0, 0, 0.35});

        TableBorderCell noColorCell = new TableBorderCell(0, 1, 1, 1, null);
        noColorCell.setBoundingBox(new BoundingBox(0, 0, 280, 200, 300));
        noColorCell.addContentObject(textChunk("B", 100, 280, 200, 300));
        noColorCell.setBackgroundColor(new double[] {0, 0, 0.35});

        row.getCells()[0] = blackCell;
        row.getCells()[1] = noColorCell;
        table.getRows()[0] = row;

        assertEquals("<table><tr><td style=\"background-color:#000059\">A</td>"
                + "<td style=\"background-color:#000059\">B</td></tr></table>", render(table));
    }

    /**
     * Near-white text without a background would vanish into the rendered page
     * (for example when the rectangle-shape background match missed), so it is
     * dropped; other clearly visible colors are emitted even without a
     * background.
     */
    @Test
    void nearWhiteFontColorIsOnlyEmittedOnTopOfABackground() throws Exception {
        TableBorder table = new TableBorder(1, 2);
        TableBorderRow row = new TableBorderRow(0, 2, null);

        TableBorderCell whiteNoBg = new TableBorderCell(0, 0, 1, 1, null);
        whiteNoBg.setBoundingBox(new BoundingBox(0, 0, 280, 100, 300));
        TextChunk white = textChunk("X", 0, 280, 100, 300);
        white.setFontColor(new double[] {1, 1, 1});
        whiteNoBg.addContentObject(white);

        TableBorderCell redNoBg = new TableBorderCell(0, 1, 1, 1, null);
        redNoBg.setBoundingBox(new BoundingBox(0, 100, 280, 200, 300));
        TextChunk red = textChunk("Z", 100, 280, 200, 300);
        red.setFontColor(new double[] {1, 0, 0});
        redNoBg.addContentObject(red);

        row.getCells()[0] = whiteNoBg;
        row.getCells()[1] = redNoBg;
        table.getRows()[0] = row;

        assertEquals("<table><tr><td>X</td><td style=\"color:#ff0000\">Z</td></tr></table>",
            render(table));
    }

    private static String render(TableBorder table) throws Exception {
        Method method = JsonWriter.class.getDeclaredMethod(
            "renderTableBorderToHtml", TableBorder.class, int.class, String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, table, 0, "test-url", 1);
    }

    private static TextChunk textChunk(String value, double left, double bottom, double right, double top) {
        return new TextChunk(new BoundingBox(0, left, bottom, right, top), value, 10, (bottom + top) / 2);
    }
}
