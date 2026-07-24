package org.opendataloader.pdf.custom.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.List;

public class TextInOcrDetailDto {

    private String type;

    @JsonSetter("paragraph_id")
    private Integer paragraphId;

    @JsonSetter("page_id")
    private Integer pageId;

    private Integer content;

    @JsonSetter("outline_level")
    private Integer outlineLevel;

    private String text;

    private List<Double> position;

    @JsonSetter("image_url")
    private String imageUrl;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getParagraphId() {
        return paragraphId;
    }

    public void setParagraphId(Integer paragraphId) {
        this.paragraphId = paragraphId;
    }

    public Integer getPageId() {
        return pageId;
    }

    public void setPageId(Integer pageId) {
        this.pageId = pageId;
    }

    public Integer getContent() {
        return content;
    }

    public void setContent(Integer content) {
        this.content = content;
    }

    public Integer getOutlineLevel() {
        return outlineLevel;
    }

    public void setOutlineLevel(Integer outlineLevel) {
        this.outlineLevel = outlineLevel;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<Double> getPosition() {
        return position;
    }

    public void setPosition(List<Double> position) {
        this.position = position;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}

