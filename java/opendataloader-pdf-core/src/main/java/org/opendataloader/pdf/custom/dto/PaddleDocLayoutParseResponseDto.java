package org.opendataloader.pdf.custom.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
import java.util.Map;

/**
 * 文档布局解析响应Dto
 * 对应JSON结构的完整数据模型，使用JsonSetter/JsonGetter实现下划线与驼峰命名转换
 */
public class PaddleDocLayoutParseResponseDto {

    private String logId;
    private ResultDto result;
    private Integer errorCode;
    private String errorMsg;

    // 无参构造
    public PaddleDocLayoutParseResponseDto() {
    }

    @JsonGetter("logId")
    public String getLogId() {
        return logId;
    }

    @JsonSetter("logId")
    public void setLogId(String logId) {
        this.logId = logId;
    }

    @JsonGetter("result")
    public ResultDto getResult() {
        return result;
    }

    @JsonSetter("result")
    public void setResult(ResultDto result) {
        this.result = result;
    }

    @JsonGetter("errorCode")
    public Integer getErrorCode() {
        return errorCode;
    }

    @JsonSetter("errorCode")
    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    @JsonGetter("errorMsg")
    public String getErrorMsg() {
        return errorMsg;
    }

    @JsonSetter("errorMsg")
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    /**
     * 结果核心数据Dto
     */
    public static class ResultDto {
        private List<LayoutParsingResultDto> layoutParsingResults;
        private DataInfoDto dataInfo;

        public ResultDto() {
        }

        @JsonGetter("layoutParsingResults")
        public List<LayoutParsingResultDto> getLayoutParsingResults() {
            return layoutParsingResults;
        }

        @JsonSetter("layoutParsingResults")
        public void setLayoutParsingResults(List<LayoutParsingResultDto> layoutParsingResults) {
            this.layoutParsingResults = layoutParsingResults;
        }

        @JsonGetter("dataInfo")
        public DataInfoDto getDataInfo() {
            return dataInfo;
        }

        @JsonSetter("dataInfo")
        public void setDataInfo(DataInfoDto dataInfo) {
            this.dataInfo = dataInfo;
        }
    }

    /**
     * 布局解析结果Dto
     */
    public static class LayoutParsingResultDto {
        private PrunedResultDto prunedResult;
        private MarkdownDto markdown;

        public LayoutParsingResultDto() {
        }

        @JsonGetter("prunedResult")
        public PrunedResultDto getPrunedResult() {
            return prunedResult;
        }

        @JsonSetter("prunedResult")
        public void setPrunedResult(PrunedResultDto prunedResult) {
            this.prunedResult = prunedResult;
        }

        @JsonGetter("markdown")
        public MarkdownDto getMarkdown() {
            return markdown;
        }

        @JsonSetter("markdown")
        public void setMarkdown(MarkdownDto markdown) {
            this.markdown = markdown;
        }
    }

    /**
     * 裁剪后的解析结果Dto
     */
    public static class PrunedResultDto {
        private Integer pageCount;
        private Integer width;
        private Integer height;
        private ModelSettingsDto modelSettings;
        private List<ParsingResDto> parsingResList;
        private DocPreprocessorResDto docPreprocessorRes;
        private LayoutDetResDto layoutDetRes;

        public PrunedResultDto() {
        }

        @JsonGetter("page_count")
        public Integer getPageCount() {
            return pageCount;
        }

        @JsonSetter("page_count")
        public void setPageCount(Integer pageCount) {
            this.pageCount = pageCount;
        }

        @JsonGetter("width")
        public Integer getWidth() {
            return width;
        }

        @JsonSetter("width")
        public void setWidth(Integer width) {
            this.width = width;
        }

        @JsonGetter("height")
        public Integer getHeight() {
            return height;
        }

        @JsonSetter("height")
        public void setHeight(Integer height) {
            this.height = height;
        }

        @JsonGetter("model_settings")
        public ModelSettingsDto getModelSettings() {
            return modelSettings;
        }

        @JsonSetter("model_settings")
        public void setModelSettings(ModelSettingsDto modelSettings) {
            this.modelSettings = modelSettings;
        }

        @JsonGetter("parsing_res_list")
        public List<ParsingResDto> getParsingResList() {
            return parsingResList;
        }

        @JsonSetter("parsing_res_list")
        public void setParsingResList(List<ParsingResDto> parsingResList) {
            this.parsingResList = parsingResList;
        }

        @JsonGetter("doc_preprocessor_res")
        public DocPreprocessorResDto getDocPreprocessorRes() {
            return docPreprocessorRes;
        }

        @JsonSetter("doc_preprocessor_res")
        public void setDocPreprocessorRes(DocPreprocessorResDto docPreprocessorRes) {
            this.docPreprocessorRes = docPreprocessorRes;
        }

        @JsonGetter("layout_det_res")
        public LayoutDetResDto getLayoutDetRes() {
            return layoutDetRes;
        }

        @JsonSetter("layout_det_res")
        public void setLayoutDetRes(LayoutDetResDto layoutDetRes) {
            this.layoutDetRes = layoutDetRes;
        }
    }

    /**
     * 模型配置Dto
     */
    public static class ModelSettingsDto {
        private Boolean useDocPreprocessor;
        private Boolean useLayoutDetection;
        private Boolean useChartRecognition;
        private Boolean useSealRecognition;
        private Boolean useOcrForImageBlock;
        private Boolean formatBlockContent;
        private Boolean mergeLayoutBlocks;
        private List<String> markdownIgnoreLabels;
        private Boolean returnLayoutPolygonPoints;
        private Boolean useDocOrientationClassify;

        public ModelSettingsDto() {
        }

        @JsonGetter("use_doc_preprocessor")
        public Boolean getUseDocPreprocessor() {
            return useDocPreprocessor;
        }

        @JsonSetter("use_doc_preprocessor")
        public void setUseDocPreprocessor(Boolean useDocPreprocessor) {
            this.useDocPreprocessor = useDocPreprocessor;
        }

        @JsonGetter("use_layout_detection")
        public Boolean getUseLayoutDetection() {
            return useLayoutDetection;
        }

        @JsonSetter("use_layout_detection")
        public void setUseLayoutDetection(Boolean useLayoutDetection) {
            this.useLayoutDetection = useLayoutDetection;
        }

        @JsonGetter("use_chart_recognition")
        public Boolean getUseChartRecognition() {
            return useChartRecognition;
        }

        @JsonSetter("use_chart_recognition")
        public void setUseChartRecognition(Boolean useChartRecognition) {
            this.useChartRecognition = useChartRecognition;
        }

        @JsonGetter("use_seal_recognition")
        public Boolean getUseSealRecognition() {
            return useSealRecognition;
        }

        @JsonSetter("use_seal_recognition")
        public void setUseSealRecognition(Boolean useSealRecognition) {
            this.useSealRecognition = useSealRecognition;
        }

        @JsonGetter("use_ocr_for_image_block")
        public Boolean getUseOcrForImageBlock() {
            return useOcrForImageBlock;
        }

        @JsonSetter("use_ocr_for_image_block")
        public void setUseOcrForImageBlock(Boolean useOcrForImageBlock) {
            this.useOcrForImageBlock = useOcrForImageBlock;
        }

        @JsonGetter("format_block_content")
        public Boolean getFormatBlockContent() {
            return formatBlockContent;
        }

        @JsonSetter("format_block_content")
        public void setFormatBlockContent(Boolean formatBlockContent) {
            this.formatBlockContent = formatBlockContent;
        }

        @JsonGetter("merge_layout_blocks")
        public Boolean getMergeLayoutBlocks() {
            return mergeLayoutBlocks;
        }

        @JsonSetter("merge_layout_blocks")
        public void setMergeLayoutBlocks(Boolean mergeLayoutBlocks) {
            this.mergeLayoutBlocks = mergeLayoutBlocks;
        }

        @JsonGetter("markdown_ignore_labels")
        public List<String> getMarkdownIgnoreLabels() {
            return markdownIgnoreLabels;
        }

        @JsonSetter("markdown_ignore_labels")
        public void setMarkdownIgnoreLabels(List<String> markdownIgnoreLabels) {
            this.markdownIgnoreLabels = markdownIgnoreLabels;
        }

        @JsonGetter("return_layout_polygon_points")
        public Boolean getReturnLayoutPolygonPoints() {
            return returnLayoutPolygonPoints;
        }

        @JsonSetter("return_layout_polygon_points")
        public void setReturnLayoutPolygonPoints(Boolean returnLayoutPolygonPoints) {
            this.returnLayoutPolygonPoints = returnLayoutPolygonPoints;
        }

        @JsonGetter("use_doc_orientation_classify")
        public Boolean getUseDocOrientationClassify() {
            return useDocOrientationClassify;
        }

        @JsonSetter("use_doc_orientation_classify")
        public void setUseDocOrientationClassify(Boolean useDocOrientationClassify) {
            this.useDocOrientationClassify = useDocOrientationClassify;
        }
    }

    /**
     * 解析结果项Dto
     */
    public static class ParsingResDto {
        private String blockLabel;
        private String blockContent;
        private List<Double> blockBbox;
        private Integer blockId;
        private Integer blockOrder;
        private Integer groupId;
        private List<List<Double>> blockPolygonPoints;

        public ParsingResDto() {
        }

        @JsonGetter("block_label")
        public String getBlockLabel() {
            return blockLabel;
        }

        @JsonSetter("block_label")
        public void setBlockLabel(String blockLabel) {
            this.blockLabel = blockLabel;
        }

        @JsonGetter("block_content")
        public String getBlockContent() {
            return blockContent;
        }

        @JsonSetter("block_content")
        public void setBlockContent(String blockContent) {
            this.blockContent = blockContent;
        }

        @JsonGetter("block_bbox")
        public List<Double> getBlockBbox() {
            return blockBbox;
        }

        @JsonSetter("block_bbox")
        public void setBlockBbox(List<Double> blockBbox) {
            this.blockBbox = blockBbox;
        }

        @JsonGetter("block_id")
        public Integer getBlockId() {
            return blockId;
        }

        @JsonSetter("block_id")
        public void setBlockId(Integer blockId) {
            this.blockId = blockId;
        }

        @JsonGetter("block_order")
        public Integer getBlockOrder() {
            return blockOrder;
        }

        @JsonSetter("block_order")
        public void setBlockOrder(Integer blockOrder) {
            this.blockOrder = blockOrder;
        }

        @JsonGetter("group_id")
        public Integer getGroupId() {
            return groupId;
        }

        @JsonSetter("group_id")
        public void setGroupId(Integer groupId) {
            this.groupId = groupId;
        }

        @JsonGetter("block_polygon_points")
        public List<List<Double>> getBlockPolygonPoints() {
            return blockPolygonPoints;
        }

        @JsonSetter("block_polygon_points")
        public void setBlockPolygonPoints(List<List<Double>> blockPolygonPoints) {
            this.blockPolygonPoints = blockPolygonPoints;
        }
    }

    /**
     * 文档预处理结果Dto
     */
    public static class DocPreprocessorResDto {
        private ModelSettingsDto modelSettings;
        private Integer angle;

        public DocPreprocessorResDto() {
        }

        @JsonGetter("model_settings")
        public ModelSettingsDto getModelSettings() {
            return modelSettings;
        }

        @JsonSetter("model_settings")
        public void setModelSettings(ModelSettingsDto modelSettings) {
            this.modelSettings = modelSettings;
        }

        @JsonGetter("angle")
        public Integer getAngle() {
            return angle;
        }

        @JsonSetter("angle")
        public void setAngle(Integer angle) {
            this.angle = angle;
        }
    }

    /**
     * 布局检测结果Dto
     */
    public static class LayoutDetResDto {
        private List<BoxDto> boxes;

        public LayoutDetResDto() {
        }

        @JsonGetter("boxes")
        public List<BoxDto> getBoxes() {
            return boxes;
        }

        @JsonSetter("boxes")
        public void setBoxes(List<BoxDto> boxes) {
            this.boxes = boxes;
        }
    }

    /**
     * 布局检测框Dto
     */
    public static class BoxDto {
        private Integer clsId;
        private String label;
        private Double score;
        private List<Double> coordinate;
        private Integer order;
        private List<List<Double>> polygonPoints;

        public BoxDto() {
        }

        @JsonGetter("cls_id")
        public Integer getClsId() {
            return clsId;
        }

        @JsonSetter("cls_id")
        public void setClsId(Integer clsId) {
            this.clsId = clsId;
        }

        @JsonGetter("label")
        public String getLabel() {
            return label;
        }

        @JsonSetter("label")
        public void setLabel(String label) {
            this.label = label;
        }

        @JsonGetter("score")
        public Double getScore() {
            return score;
        }

        @JsonSetter("score")
        public void setScore(Double score) {
            this.score = score;
        }

        @JsonGetter("coordinate")
        public List<Double> getCoordinate() {
            return coordinate;
        }

        @JsonSetter("coordinate")
        public void setCoordinate(List<Double> coordinate) {
            this.coordinate = coordinate;
        }

        @JsonGetter("order")
        public Integer getOrder() {
            return order;
        }

        @JsonSetter("order")
        public void setOrder(Integer order) {
            this.order = order;
        }

        @JsonGetter("polygon_points")
        public List<List<Double>> getPolygonPoints() {
            return polygonPoints;
        }

        @JsonSetter("polygon_points")
        public void setPolygonPoints(List<List<Double>> polygonPoints) {
            this.polygonPoints = polygonPoints;
        }
    }

    /**
     * Markdown结果Dto
     */
    public static class MarkdownDto {
        private String text;
        private Map<String, String> images;

        public MarkdownDto() {
        }

        @JsonGetter("text")
        public String getText() {
            return text;
        }

        @JsonSetter("text")
        public void setText(String text) {
            this.text = text;
        }

        @JsonGetter("images")
        public Map<String, String> getImages() {
            return images;
        }

        @JsonSetter("images")
        public void setImages(Map<String, String> images) {
            this.images = images;
        }
    }

    /**
     * 数据基本信息Dto
     */
    public static class DataInfoDto {
        private Integer width;
        private Integer height;
        private String type;

        public DataInfoDto() {
        }

        @JsonGetter("width")
        public Integer getWidth() {
            return width;
        }

        @JsonSetter("width")
        public void setWidth(Integer width) {
            this.width = width;
        }

        @JsonGetter("height")
        public Integer getHeight() {
            return height;
        }

        @JsonSetter("height")
        public void setHeight(Integer height) {
            this.height = height;
        }

        @JsonGetter("type")
        public String getType() {
            return type;
        }

        @JsonSetter("type")
        public void setType(String type) {
            this.type = type;
        }
    }
}
