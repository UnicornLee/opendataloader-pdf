package org.opendataloader.pdf.custom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class PageContentDto {

    @JsonProperty("page_index")
    private Integer pageIndex;

    private Double height;

    private Double width;

    @JsonProperty("margin_left")
    private Double marginLeft;

    @JsonProperty("margin_right")
    private Double marginRight;

    @JsonProperty("margin_top")
    private Double marginTop;

    @JsonProperty("margin_bottom")
    private Double marginBottom;


    private List<PageItem> items;

    @JsonProperty("is_ocr")
    private Boolean ocr;

    @JsonProperty("is_third_party")
    private Boolean thirdParty;

    public Integer getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(Integer pageIndex) {
        this.pageIndex = pageIndex;
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

    public List<PageItem> getItems() {
        return items;
    }

    public void setItems(List<PageItem> items) {
        this.items = items;
    }

    public Boolean getOcr() {
        return ocr;
    }

    public void setOcr(Boolean ocr) {
        this.ocr = ocr;
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

    public Boolean getThirdParty() {
        return thirdParty;
    }

    public void setThirdParty(Boolean thirdParty) {
        this.thirdParty = thirdParty;
    }
}
