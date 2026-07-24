package org.opendataloader.pdf.custom.dto;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.util.List;

/**
 * @author Lawrence
 * 表格单个item类目
 * {
 *                 "cell_type": "text",
 *                 "text": [
 *                   "\u79d1\u76ee"
 *                 ],
 *                 "bg_color": null,
 *                 "row_len": 1,
 *                 "column_len": 1,
 *                 "height": 10.599999999999994,
 *                 "width": 32.400000000000006,
 *                 "x0": 72.8,
 *                 "x1": 105.2
 *               },
 */
public class TableSingleItem {

    private String cellType;

    private List<String> text;

    private String bgColor;

    private Integer rowLen;

    private Integer columnLen;

    private Double height;

    private Double width;

    private Double x0;

    private Double x1;

    private Double cellRadio;

    private Double fontSize;

    @JsonGetter("cell_type")
    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }

    public List<String> getText() {
        return text;
    }

    public void setText(List<String> text) {
        this.text = text;
    }

    @JsonGetter("bg_color")
    public String getBgColor() {
        return bgColor;
    }

    public void setBgColor(String bgColor) {
        this.bgColor = bgColor;
    }

    @JsonGetter("row_len")
    public Integer getRowLen() {
        return rowLen;
    }

    public void setRowLen(Integer rowLen) {
        this.rowLen = rowLen;
    }

    @JsonGetter("column_len")
    public Integer getColumnLen() {
        return columnLen;
    }

    public void setColumnLen(Integer columnLen) {
        this.columnLen = columnLen;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getX0() {
        return x0;
    }

    public void setX0(Double x0) {
        this.x0 = x0;
    }

    public Double getX1() {
        return x1;
    }

    public void setX1(Double x1) {
        this.x1 = x1;
    }

    @JsonGetter("cell_radio")
    public Double getCellRadio() {
        return cellRadio;
    }

    public void setCellRadio(Double cellRadio) {
        this.cellRadio = cellRadio;
    }

    @JsonGetter("font_size")
    public Double getFontSize() {
        return fontSize;
    }

    public void setFontSize(Double fontSize) {
        this.fontSize = fontSize;
    }
}
