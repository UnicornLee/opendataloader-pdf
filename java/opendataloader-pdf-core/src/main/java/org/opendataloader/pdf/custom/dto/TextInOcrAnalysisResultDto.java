package org.opendataloader.pdf.custom.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * @author Lawrence
 * 合合ocr识别结果
 */
public class TextInOcrAnalysisResultDto {

    private String markdown;

    @JsonSetter("success_count")
    private Integer successCount;

    @JsonSetter("total_count")
    private Integer totalCount;

    private List<TextInOcrDetailDto> detail;


    public String getMarkdown() {
        return markdown;
    }

    public void setMarkdown(String markdown) {
        this.markdown = markdown;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Integer successCount) {
        this.successCount = successCount;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public List<TextInOcrDetailDto> getDetail() {
        return detail;
    }

    public void setDetail(List<TextInOcrDetailDto> detail) {
        this.detail = detail;
    }

}
