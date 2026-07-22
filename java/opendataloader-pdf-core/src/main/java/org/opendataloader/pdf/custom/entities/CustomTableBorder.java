package org.opendataloader.pdf.custom.entities;

import org.verapdf.wcag.algorithms.entities.INode;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.TableBorderBuilder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;

import java.util.List;

public class CustomTableBorder extends TableBorder {
    public CustomTableBorder(TableBorderBuilder builder) {
        super(builder);
    }

    public CustomTableBorder(int numberOfRows, int numberOfColumns) {
        super(numberOfRows, numberOfColumns);
    }

    public CustomTableBorder(BoundingBox boundingBox, TableBorderRow[] rows, int numberOfRows, int numberOfColumns) {
        super(boundingBox, rows, numberOfRows, numberOfColumns);
    }

    public CustomTableBorder(INode tableNode) {
        super(tableNode);
    }
}
