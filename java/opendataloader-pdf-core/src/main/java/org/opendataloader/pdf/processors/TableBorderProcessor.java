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
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.TextChunkUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TableBorderProcessor {

    private static final double LINE_ART_PERCENT = 0.9;
    private static final double NEIGHBOUR_TABLE_EPSILON = 0.2;
    /** Minimum overlap (relative to the cell area) for a rectangle ShapeChunk to be
     *  treated as the background color of a table cell. */
    private static final double CELL_BACKGROUND_OVERLAP_THRESHOLD = 0.5;

    /**
     * Maximum depth for nested table processing.
     * Real-world PDFs rarely have tables nested more than 2-3 levels.
     * This limit prevents stack overflow from malicious or malformed PDFs.
     */
    private static final int MAX_NESTED_TABLE_DEPTH = 10;

    /**
     * Thread-local counter for tracking current nesting depth.
     */
    private static final ThreadLocal<Integer> currentDepth = ThreadLocal.withInitial(() -> 0);

    public static List<IObject> processTableBorders(List<IObject> contents, int pageNumber) {
        return processTableBorders(contents, pageNumber, null);
    }

    public static List<IObject> processTableBorders(List<IObject> contents, int pageNumber, String imagesDirectory) {
        // Check if TableBordersCollection exists (may be null if no borders detected during preprocessing)
        if (StaticContainers.getTableBordersCollection() == null) {
            return new ArrayList<>(contents);
        }

        // Check depth limit to prevent stack overflow from deeply nested tables
        int depth = currentDepth.get();
        if (depth >= MAX_NESTED_TABLE_DEPTH) {
            // Exceeded maximum nesting depth - return contents without further table processing
            return new ArrayList<>(contents);
        }

        try {
            currentDepth.set(depth + 1);

            List<IObject> newContents = new ArrayList<>();
            Set<TableBorder> processedTableBorders = new LinkedHashSet<>();
            for (IObject content : contents) {
                TableBorder tableBorder = addContentToTableBorder(content);
                if (tableBorder != null) {
                    if (content instanceof LineChunk && tableBorder.isOneCellTable()) {
                        continue;
                    }
                    if (!processedTableBorders.contains(tableBorder)) {
                        processedTableBorders.add(tableBorder);
                        newContents.add(tableBorder);
                    }
                    if (content instanceof TextChunk) {
                        TextChunk textChunk = (TextChunk) content;
                        TextChunk textChunkPart = getTextChunkPartBeforeTable(textChunk, tableBorder);
                        if (textChunkPart != null && !textChunkPart.isEmpty() && !textChunkPart.isWhiteSpaceChunk()) {
                            newContents.add(textChunkPart);
                        }
                        textChunkPart = getTextChunkPartAfterTable(textChunk, tableBorder);
                        if (textChunkPart != null && !textChunkPart.isEmpty() && !textChunkPart.isWhiteSpaceChunk()) {
                            newContents.add(textChunkPart);
                        }
                    }
                } else {
                    newContents.add(content);
                }
            }
            Map<TableBorder, TableBorder> normalizedTables = new HashMap<>();
            for (TableBorder border : processedTableBorders) {
                StaticContainers.getTableBordersCollection().removeTableBorder(border, pageNumber);
                TableBorder normalizedTable = normalizeAndProcessTableBorder(contents, border, pageNumber, imagesDirectory);
                normalizedTables.put(border, normalizedTable);
                // Remove the outer table while processing its contents, then restore the page index
                // with the final instance so later lookups still see the normalized table.
//                StaticContainers.getTableBordersCollection().getTableBorders(pageNumber).add(normalizedTable);
            }
            for (int index = 0; index < newContents.size(); index++) {
                IObject content = newContents.get(index);
                if (content instanceof TableBorder && normalizedTables.containsKey(content)) {
                    newContents.set(index, normalizedTables.get(content));
                }
            }
            return newContents;
        } finally {
            // Reset depth when exiting this level (clean up ThreadLocal)
            if (depth == 0) {
                currentDepth.remove();
            } else {
                currentDepth.set(depth);
            }
        }
    }

    private static TableBorder addContentToTableBorder(IObject content) {
        if (StaticContainers.getTableBordersCollection() == null) {
            return null;
        }
        // Keep extracted vector shapes as top-level items so they are emitted
        // separately in JSON instead of being swallowed by table cells.
        if (content instanceof ShapeChunk) {
            return null;
        }
        TableBorder tableBorder = StaticContainers.getTableBordersCollection().getTableBorder(content.getBoundingBox());
        if (tableBorder != null) {
            if (content instanceof LineChunk) {
                return tableBorder;
            }
            if (content instanceof LineArtChunk && BoundingBox.areSameBoundingBoxes(tableBorder.getBoundingBox(), content.getBoundingBox())) {
                return tableBorder;
            }
            Set<TableBorderCell> tableBorderCells = tableBorder.getTableBorderCells(content);
            if (!tableBorderCells.isEmpty()) {
                if (tableBorderCells.size() > 1 && content instanceof TextChunk) {
                    TextChunk textChunk = (TextChunk) content;
                    for (TableBorderCell tableBorderCell : tableBorderCells) {
                        TextChunk currentTextChunk = getTextChunkPartForTableCell(textChunk, tableBorderCell);
                        if (currentTextChunk != null && !currentTextChunk.isEmpty()) {
                            tableBorderCell.addContentObject(currentTextChunk);
                        }
                    }
                } else {
                    for (TableBorderCell tableBorderCell : tableBorderCells) {
                        if (content instanceof LineArtChunk &&
                                tableBorderCell.getBoundingBox().getIntersectionPercent(content.getBoundingBox()) > LINE_ART_PERCENT) {
                            return tableBorder;
                        }
                        tableBorderCell.addContentObject(content);
                        break;
                    }
                }
                return tableBorder;
            }
            if (content instanceof LineArtChunk) {
                return tableBorder;
            }
        }
        return null;
    }

    public static void processTableBorder(TableBorder tableBorder, int pageNumber) {
        processTableBorderContents(tableBorder, pageNumber, null);
    }

    static TableBorder normalizeAndProcessTableBorder(List<IObject> rawPageContents, TableBorder tableBorder, int pageNumber, String imagesDirectory) {
        TableBorder normalizedTable = TableStructureNormalizer.normalize(rawPageContents, tableBorder);
        assignBackgroundColorsFromRectangleShapes(rawPageContents, normalizedTable);
        processTableBorderContents(normalizedTable, pageNumber, imagesDirectory);
        return normalizedTable;
    }

    /**
     * Scans the raw page contents for rectangle {@link ShapeChunk}s and, when one
     * overlaps a table cell by more than {@link #CELL_BACKGROUND_OVERLAP_THRESHOLD},
     * records the shape color as that cell's background color.
     *
     * <p>The original ShapeChunks remain top-level page contents; this method only
     * annotates the affected cells.</p>
     */
    private static void assignBackgroundColorsFromRectangleShapes(List<IObject> rawPageContents, TableBorder tableBorder) {
        if (tableBorder == null || tableBorder.isTextBlock() || rawPageContents == null || rawPageContents.isEmpty()) {
            return;
        }
        // Track the best matching rectangle shape for each cell to avoid relying on
        // arbitrary iteration order when multiple shapes overlap the same cell.
        Map<TableBorderCell, BestShapeMatch> bestMatches = new HashMap<>();
        for (IObject content : rawPageContents) {
            if (!(content instanceof ShapeChunk)) {
                continue;
            }
            ShapeChunk shapeChunk = (ShapeChunk) content;
            if (!ShapeChunk.TYPE_RECTANGLE.equals(shapeChunk.getShapeType())) {
                continue;
            }
            double[] color = shapeChunk.getColor();
            if (color == null || color.length != 3) {
                continue;
            }
            Set<TableBorderCell> cells = tableBorder.getTableBorderCells(shapeChunk);
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            for (TableBorderCell cell : cells) {
                double overlap = cell.getBoundingBox().getIntersectionPercent(shapeChunk.getBoundingBox());
                if (overlap > CELL_BACKGROUND_OVERLAP_THRESHOLD) {
                    BestShapeMatch current = bestMatches.get(cell);
                    if (current == null || overlap > current.overlap) {
                        bestMatches.put(cell, new BestShapeMatch(color, overlap));
                    }
                }
            }
        }
        for (Map.Entry<TableBorderCell, BestShapeMatch> entry : bestMatches.entrySet()) {
            entry.getKey().setBackgroundColor(entry.getValue().color);
        }
    }

    /**
     * Simple holder for the best rectangle shape match when assigning cell background colors.
     */
    private static class BestShapeMatch {
        final double[] color;
        final double overlap;

        BestShapeMatch(double[] color, double overlap) {
            this.color = color;
            this.overlap = overlap;
        }
    }

    private static void processTableBorderContents(TableBorder tableBorder, int pageNumber, String imagesDirectory) {
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderRow row = tableBorder.getRow(rowNumber);
            for (int colNumber = 0; colNumber < tableBorder.getNumberOfColumns(); colNumber++) {
                TableBorderCell tableBorderCell = row.getCell(colNumber);
                if (tableBorderCell.getRowNumber() == rowNumber && tableBorderCell.getColNumber() == colNumber) {
                    tableBorderCell.setContents(processTableCellContent(tableBorderCell.getContents(), pageNumber, imagesDirectory));
                }
            }
        }
    }

    private static List<IObject> processTableCellContent(List<IObject> contents, int pageNumber, String imagesDirectory) {
        List<IObject> newContents = TableBorderProcessor.processTableBorders(contents, pageNumber, imagesDirectory);
        newContents = TextLineProcessor.processTextLines(newContents, imagesDirectory);
        List<List<IObject>> contentsList = new ArrayList<>(1);
        contentsList.add(newContents);
//        ListProcessor.processLists(contentsList, true);
        newContents = contentsList.get(0);
        newContents = ParagraphProcessor.processParagraphs(newContents);
//        newContents = ListProcessor.processListsFromTextNodes(newContents);
        HeadingProcessor.processHeadings(newContents, true);
        DocumentProcessor.setIDs(newContents);
        CaptionProcessor.processCaptions(newContents);
        contentsList.set(0, newContents);
        ListProcessor.checkNeighborLists(contentsList);
        newContents = contentsList.get(0);
        return newContents;
    }

    public static void checkNeighborTables(List<List<IObject>> contents) {
        TableBorder previousTable = null;
        for (List<IObject> iObjects : contents) {
            for (IObject content : iObjects) {
                if (content instanceof TableBorder && !((TableBorder) content).isTextBlock()) {
                    TableBorder currentTable = (TableBorder) content;
                    if (previousTable != null) {
                        checkNeighborTables(previousTable, currentTable);
                    }
                    previousTable = currentTable;
                } else {
                    if (!HeaderFooterProcessor.isHeaderOrFooter(content) &&
                            !(content instanceof LineChunk) && !(content instanceof LineArtChunk)) {
                        previousTable = null;
                    }
                }
            }
        }
    }

    private static void checkNeighborTables(TableBorder previousTable, TableBorder currentTable) {
        if (currentTable.getNumberOfColumns() != previousTable.getNumberOfColumns()) {
            return;
        }
        if (!NodeUtils.areCloseNumbers(currentTable.getWidth(), previousTable.getWidth(), NEIGHBOUR_TABLE_EPSILON)) {
            return;
        }
        for (int columnNumber = 0; columnNumber < previousTable.getNumberOfColumns(); columnNumber++) {
            TableBorderCell cell1 = previousTable.getCell(0, columnNumber);
            TableBorderCell cell2 = currentTable.getCell(0, columnNumber);
            if (!NodeUtils.areCloseNumbers(cell1.getWidth(), cell2.getWidth(), NEIGHBOUR_TABLE_EPSILON)) {
                return;
            }
        }
        previousTable.setNextTable(currentTable);
        currentTable.setPreviousTable(previousTable);
    }

    private static TextChunk getTextChunkPartForTableCell(TextChunk textChunk, TableBorderCell cell) {
        return TextChunkUtils.getTextChunkPartForRange(textChunk, cell.getLeftX(), cell.getRightX(), true);
    }

    public static TextChunk getTextChunkPartBeforeTable(TextChunk textChunk, TableBorder table) {
        return TextChunkUtils.getTextChunkPartBeforeBoundingBox(textChunk, table.getBoundingBox());
    }

    public static TextChunk getTextChunkPartAfterTable(TextChunk textChunk, TableBorder table) {
        return TextChunkUtils.getTextChunkPartAfterBoundingBox(textChunk, table.getBoundingBox());
    }
}
