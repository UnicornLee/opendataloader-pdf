package org.opendataloader.pdf.custom.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.verapdf.wcag.algorithms.entities.BaseObject;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.List;

/**
 *  {
 *           "item_type": "image",
 *           "content": [
 *             ".\\202302111676015430614413\\202302111676015430614413_2_1.png"
 *           ],
 *           "height": 842.4,
 *           "width": 595.7,
 *           "x0": 0.0,
 *           "y0": 0.0,
 *           "x1": 595.7,
 *           "y1": 842.4,
 *           "margin_top": 0.0
 *         }
 */
public class PageItem extends BaseObject {

//    @JsonProperty("section_num")
//    private Integer sectionNum;

    private String itemType;

    private Object content;

    private Double height;

    private Double width;

    private Double x0;

    private Double x1;

    private Double y0;

    private Double y1;

    private Double fontSize;


    private Double marginTop;

    private Boolean paragraph = false;

    private Integer id;

    @JsonProperty("is_third_party")
    private Boolean thirdParty;

    public PageItem() {
        super(null, null, null);
    }

    public PageItem(BoundingBox boundingBox) {
        super(boundingBox);
    }

    public PageItem(BaseObject baseObject) {
        super(baseObject);
    }

    public PageItem(BoundingBox boundingBox, List<Integer> errorCodes, List<List<Object>> errorArguments) {
        super(boundingBox, errorCodes, errorArguments);
    }

    @JsonGetter("item_type")
    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public double getWidth() {
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

    public Double getY0() {
        return y0;
    }

    public void setY0(Double y0) {
        this.y0 = y0;
    }

    public Double getY1() {
        return y1;
    }

    public void setY1(Double y1) {
        this.y1 = y1;
    }

    @JsonGetter("margin_top")
    public Double getMarginTop() {
        return marginTop;
    }

    public void setMarginTop(Double marginTop) {
        this.marginTop = marginTop;
    }

    @JsonGetter("font_size")
    public Double getFontSize() {
        return fontSize;
    }

    public void setFontSize(Double fontSize) {
        this.fontSize = fontSize;
    }

    //    public Integer getSectionNum() {
//        return sectionNum;
//    }
//
//    public void setSectionNum(Integer sectionNum) {
//        this.sectionNum = sectionNum;
//    }


    public Boolean getParagraph() {
        return paragraph;
    }

    public void setParagraph(Boolean paragraph) {
        this.paragraph = paragraph;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Boolean getThirdParty() {
        return thirdParty;
    }

    public void setThirdParty(Boolean thirdParty) {
        this.thirdParty = thirdParty;
    }
}
