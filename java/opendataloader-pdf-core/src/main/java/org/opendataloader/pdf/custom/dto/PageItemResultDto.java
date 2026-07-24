package org.opendataloader.pdf.custom.dto;

import java.util.List;

public class PageItemResultDto {

    private List<PageItem> pageItemList;

    private Double height;

    private Double width;

    private Double marginLeft;

    private Double marginRight;

    private Double marginTop;

    private Double marginBottom;

    public List<PageItem> getPageItemList() {
        return pageItemList;
    }

    public void setPageItemList(List<PageItem> pageItemList) {
        this.pageItemList = pageItemList;
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

    public Double getMarginLeft() {
        return marginLeft;
    }

    public void setMarginLeft(Double marginLeft) {
        this.marginLeft = marginLeft;
    }

    public Double getMarginRight() {
        return marginRight;
    }

    public void setMarginRight(Double marginRight) {
        this.marginRight = marginRight;
    }

    public Double getMarginTop() {
        return marginTop;
    }

    public void setMarginTop(Double marginTop) {
        this.marginTop = marginTop;
    }

    public Double getMarginBottom() {
        return marginBottom;
    }

    public void setMarginBottom(Double marginBottom) {
        this.marginBottom = marginBottom;
    }
}
