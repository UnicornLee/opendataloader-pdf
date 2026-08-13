package org.opendataloader.pdf.custom.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

public class Bookmark implements Serializable {

    private String text;

    @JsonProperty("page_num")
    private Integer pageNum = 0;

    @JsonProperty("is_single_line")
    private Boolean isSingleLine = true;

    @JsonProperty("is_super_long")
    private Boolean isSuperLong = false;

    @JsonProperty("related_id")
    private Integer relatedId = 0;

    @JsonProperty("is_open")
    private Boolean isOpen = false;

    @JsonProperty("original_page_num")
    private Integer originalPageNum;

    @JsonProperty("is_match")
    private Boolean isMatch = false;

    @JsonProperty("font_size")
    private Float fontSize;

    private List<Bookmark> children;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Boolean getSingleLine() {
        return isSingleLine;
    }

    public void setSingleLine(Boolean singleLine) {
        isSingleLine = singleLine;
    }

    public Boolean getSuperLong() {
        return isSuperLong;
    }

    public void setSuperLong(Boolean superLong) {
        isSuperLong = superLong;
    }

    public Integer getRelatedId() {
        return relatedId;
    }

    public void setRelatedId(Integer relatedId) {
        this.relatedId = relatedId;
    }

    public Boolean getOpen() {
        return isOpen;
    }

    public void setOpen(Boolean open) {
        isOpen = open;
    }

    public Integer getOriginalPageNum() {
        return originalPageNum;
    }

    public void setOriginalPageNum(Integer originalPageNum) {
        this.originalPageNum = originalPageNum;
    }

    public Boolean getMatch() {
        return isMatch;
    }

    public void setMatch(Boolean match) {
        isMatch = match;
    }

    public Float getFontSize() {
        return fontSize;
    }

    public void setFontSize(Float fontSize) {
        this.fontSize = fontSize;
    }

    public List<Bookmark> getChildren() {
        return children;
    }

    public void setChildren(List<Bookmark> children) {
        this.children = children;
    }
}
