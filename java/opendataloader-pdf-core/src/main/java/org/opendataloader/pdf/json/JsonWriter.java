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
package org.opendataloader.pdf.json;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.RebuildBookmarksResult;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.constants.GlobalConstant;
import org.opendataloader.pdf.custom.dto.PageItem;
import org.opendataloader.pdf.custom.dto.TableSingleItem;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.custom.utils.BookmarkQualitySelector;
import org.opendataloader.pdf.custom.utils.BookmarkUtils;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.custom.utils.FileUtils;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.processors.CatalogBookmarkProcessor;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.processors.PageBookmarkProcessor;
import org.opendataloader.pdf.utils.HuaweiObsClient;
import org.opendataloader.pdf.utils.SmartTextJoiner;
import org.verapdf.as.ASAtom;
import org.verapdf.cos.COSDictionary;
import org.verapdf.cos.COSObjType;
import org.verapdf.cos.COSObject;
import org.verapdf.cos.COSTrailer;
import org.verapdf.gf.model.impl.cos.GFCosInfo;
import org.verapdf.pd.PDDocument;
import org.verapdf.tools.StaticResources;
import org.opendataloader.pdf.hybrid.ElementMetadata;
import org.opendataloader.pdf.json.serializers.SerializerUtil;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticCaption;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.SemanticTOC;
import org.verapdf.wcag.algorithms.entities.SemanticTOCI;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.lists.PDFList;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JsonWriter {
    private static final Logger LOGGER = Logger.getLogger(JsonWriter.class.getCanonicalName());
    private static JsonGenerator getJsonGenerator(String fileName) throws IOException {
        JsonFactory jsonFactory = new JsonFactory();
        return jsonFactory.createGenerator(new File(fileName), JsonEncoding.UTF8)
                .setPrettyPrinter(new DefaultPrettyPrinter())
                .setCodec(ObjectMapperHolder.getObjectMapper());
    }

    public static void writeToJson(File inputPDF, String outputFolder, List<List<IObject>> contents) throws IOException {
        writeToJson(inputPDF, outputFolder, contents, Collections.emptyMap(), null);
    }

    public static void writeToJson(File inputPDF, String outputFolder, List<List<IObject>> contents,
                                   Map<Long, ElementMetadata> elementMetadata) throws IOException {
        writeToJson(inputPDF, outputFolder, contents, elementMetadata, null);
    }

    public static void writeToJson(File inputPDF, String outputFolder, List<List<IObject>> contents,
                                   Map<Long, ElementMetadata> elementMetadata,
                                   Map<String, Object> hybridInfo) throws IOException {
        writeToJson(inputPDF, outputFolder, contents, elementMetadata, hybridInfo, false);
    }

    public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                          Map<Long, ElementMetadata> elementMetadata,
                                          Map<String, Object> hybridInfo,
                                          boolean includeHeaderFooter) throws IOException {
        return writeToCustomJson(inputPdfName, outputFolder, contents, elementMetadata, hybridInfo, includeHeaderFooter, null);
    }

    /**
     * 从已有的 JSON 文件中读取 {@code self_bookmarks}（缺失则回退到 {@code bookmarks}），
     * 复用 {@link #writeToCustomJson(String, String, List, Map, Map, boolean, Config)} 中
     * 的目录识别 / 页面书签抽取 / 质量选型三条流水线，重新生成 {@code catalog_bookmarks}、
     * {@code page_bookmarks} 与 {@code bookmarks}，写回 JSON；按 customOptions 是否
     * 包含 OSS 8 项配置（不含 {@code ossPermanentBucketName}），决定本地保留还是上传到
     * OBS 临时桶。
     *
     * <p>仅刷新书签相关字段（{@code self_bookmarks}、{@code catalog_bookmarks}、
     * {@code page_bookmarks}、{@code bookmarks}、{@code catalog_page_range_start/end}）；
     * 其余字段（{@code url}、{@code data}、{@code extend}、{@code is_ocr} 等）一律保留不动。
     * 不做图片上传、不做 OCR 检测、不重命名 / 移动 JSON。</p>
     *
     * @param inputJsonName 待重建的 JSON 文件绝对路径
     * @param config        配置对象；customOptions 驱动是否启用 OSS 上传
     * @return {@link RebuildBookmarksResult}，包含 OBS URL 或本地绝对路径以及是否上传成功
     * @throws IOException 读写或上传 JSON 失败时抛出
     */
    public static RebuildBookmarksResult rebuildBookmarksFromJson(String inputJsonName, Config config) throws IOException {
        File jsonFile = new File(inputJsonName);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(jsonFile, new TypeReference<Map<String, Object>>() {});

        // self_bookmarks from JSON, fallback to bookmarks, fallback to empty list
        List<Bookmark> selfBookmarks;
        Object selfObj = map.get("self_bookmarks");
        if (selfObj instanceof List) {
            selfBookmarks = mapper.convertValue(selfObj, new TypeReference<List<Bookmark>>() {});
        } else {
            Object bookmarksObj = map.get("bookmarks");
            if (bookmarksObj instanceof List) {
                selfBookmarks = mapper.convertValue(bookmarksObj, new TypeReference<List<Bookmark>>() {});
            } else {
                selfBookmarks = new ArrayList<>();
            }
        }
        map.put("self_bookmarks", selfBookmarks);

        // OSS config & client
        OssUploadConfig ossConfig = OssUploadConfig.fromCustomOptionsForJsonUpload(config);
        boolean ossEnabled = ossConfig.isEnabled();
        HuaweiObsClient obsClient = null;
        try {
            // Resolve self_bookmarks related_ids
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) map.get(JsonName.DATA);

            if (data != null) {
                resolveSelfBookmarkRelatedIds(mapper, map, data);
                CatalogBookmarkProcessor.CatalogResult catalogResult =
                    CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);
                List<Bookmark> catalogBookmarks = catalogResult.getBookmarks();
                int catalogStartPage = catalogResult.getStartPage();
                int catalogEndPage = catalogResult.getEndPage();

                List<Bookmark> pageBookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(
                    data, catalogStartPage, catalogEndPage);

                CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(
                    data, catalogStartPage, catalogEndPage, catalogBookmarks, pageBookmarks);

                if (catalogStartPage >= 0 && catalogEndPage >= catalogStartPage) {
                    map.put("catalog_page_range_start", catalogStartPage + 1);
                    map.put("catalog_page_range_end", catalogEndPage + 1);
                }
                map.put("catalog_bookmarks", catalogBookmarks);
                map.put("page_bookmarks", pageBookmarks);

                Map<Integer, Set<Integer>> pageItemIds = BookmarkQualitySelector.buildPageItemIds(data);
                BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
                    catalogBookmarks, pageBookmarks, selfBookmarks, pageItemIds);
                map.put("bookmarks", selection.getBookmarks());
                String selectedSource = selection.getSource();
                if (selectedSource != null) {
                    map.remove(selectedSource);
                }
            } else {
                map.put("bookmarks", new ArrayList<>());
                // data is null: keep all three sources as-is (catalog_bookmarks/page_bookmarks/self_bookmarks)
            }

            // Write back to JSON
            mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, map);

            String jsonUrlOrPath;
            boolean ossUploadSuccess = false;
            if (ossEnabled) {
                obsClient = new HuaweiObsClient(ossConfig.getEndpoint(), ossConfig.getAccessKey(), ossConfig.getSecretKey());
                String jsonObjectKey = buildJsonObjectKeyForRebuild(ossConfig);
                jsonUrlOrPath = obsClient.uploadFile(ossConfig.getTempBucketName(), jsonObjectKey, jsonFile, ossConfig.getTempDomainName());
                LOGGER.log(Level.INFO, "Uploaded rebuilt JSON to OBS: {0}", jsonUrlOrPath);
                ossUploadSuccess = true;
                Files.delete(jsonFile.toPath());
                LOGGER.log(Level.INFO, "Deleted local JSON after OSS upload: {0}", inputJsonName);
            } else {
                jsonUrlOrPath = jsonFile.getAbsolutePath();
            }

            return new RebuildBookmarksResult(jsonUrlOrPath, ossUploadSuccess);
        } finally {
            if (obsClient != null) {
                try {
                    obsClient.close();
                } catch (IOException closeEx) {
                    LOGGER.log(Level.WARNING, "Failed to close OBS client: " + closeEx.getMessage());
                }
            }
        }
    }

    public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                          Map<Long, ElementMetadata> elementMetadata,
                                          Map<String, Object> hybridInfo,
                                          boolean includeHeaderFooter,
                                          Config config) throws IOException {
        StaticLayoutContainers.resetImageIndex();
        File inputPDF = new File(inputPdfName);
        String jsonFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "json";
        try (JsonGenerator jsonGenerator = getJsonGenerator(jsonFileName)) {
            jsonGenerator.writeStartObject();
            if (config.getCustomOptions().containsKey("url") && !"".equals(config.getCustomOptions().get("url"))) {
                jsonGenerator.writeStringField("url", (String) config.getCustomOptions().get("url"));
            } else {
                jsonGenerator.writeStringField("url", inputPdfName);
            }
            jsonGenerator.writeArrayFieldStart("self_bookmarks");
            for (Bookmark bookmark : BookmarkUtils.getSelfBookmarks(inputPdfName)) {
                jsonGenerator.writePOJO(bookmark);
            }
            jsonGenerator.writeEndArray();

            if (hybridInfo != null && !hybridInfo.isEmpty()) {
                writeHybridBlock(jsonGenerator, hybridInfo);
            }

            SerializerUtil.setElementMetadata(elementMetadata);
            try {
                jsonGenerator.writeArrayFieldStart(JsonName.DATA);
                for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                    jsonGenerator.writeStartObject();
                    jsonGenerator.writeNumberField(JsonName.PAGE_INDEX, pageNumber + 1);
                    BoundingBox pageBoundingBox = DocumentProcessor.getPageBoundingBox(pageNumber);
                    double width = pageBoundingBox.getWidth();
                    double height = pageBoundingBox.getHeight();
                    jsonGenerator.writeNumberField(JsonName.WIDTH, width);
                    jsonGenerator.writeNumberField(JsonName.HEIGHT, height);
                    jsonGenerator.writeBooleanField(JsonName.IS_OCR, false);
                    List<IObject> pageContents = contents.get(pageNumber);
                    List<IObject> layoutObjects = pageContents.stream()
                            .filter(o -> !(o instanceof SemanticHeaderOrFooter))
                            .collect(Collectors.toList());
                    List<Double> leftXList = layoutObjects.stream().map(IObject::getLeftX).collect(Collectors.toList());
                    List<Double> rightXList = layoutObjects.stream().map(IObject::getRightX).collect(Collectors.toList());
                    List<Double> topYList = layoutObjects.stream().map(IObject::getTopY).collect(Collectors.toList());
                    List<Double> bottomYList = layoutObjects.stream().map(IObject::getBottomY).collect(Collectors.toList());
                    double minX = leftXList.stream().min(Double::compare).orElse(0.0);
                    double maxX = rightXList.stream().max(Double::compare).orElse(0.0);
                    double maxY = topYList.stream().max(Double::compare).orElse(0.0);
                    double minY = bottomYList.stream().min(Double::compare).orElse(0.0);
                    double marginLeft = minX;
                    double marginRight = width - maxX;
                    double marginTop = height - maxY;
                    double marginBottom = minY;
                    jsonGenerator.writeNumberField(JsonName.MARGIN_LEFT, marginLeft);
                    jsonGenerator.writeNumberField(JsonName.MARGIN_RIGHT, marginRight);
                    jsonGenerator.writeNumberField(JsonName.MARGIN_TOP, marginTop);
                    jsonGenerator.writeNumberField(JsonName.MARGIN_BOTTOM, marginBottom);
                    jsonGenerator.writeArrayFieldStart(JsonName.ITEMS);
                    final double[] prevBottomY = {height};
                    int textId = 1;
                    for (IObject content : pageContents) {
                        final int finalTextId = textId;
                        if (content instanceof LineArtChunk || content instanceof ShapeChunk) {
                            continue;
                        }
                        if (!includeHeaderFooter && content instanceof SemanticHeaderOrFooter) {
                            continue;
                        }
                        if (content instanceof SemanticHeading) {
                            SemanticHeading heading = (SemanticHeading) content;
                            List<Map<String, Object>> headingList = new ArrayList<>();
                            final double[] lineBottomY = {prevBottomY[0]};
                            heading.getColumns().forEach(column -> {
                                column.getBlocks().forEach(block -> {
                                    Map<String, Object> paragraphMap = new HashMap<>();
                                    paragraphMap.put(JsonName.ITEM_TYPE, "text");
                                    paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_HEADING);
                                    paragraphMap.put(JsonName.ID, finalTextId);
                                    paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, block.getFontSize());
                                    paragraphMap.put(JsonName.X0, block.getLeftX());
                                    paragraphMap.put(JsonName.X1, block.getRightX());
                                    paragraphMap.put(JsonName.Y0, height - block.getTopY());
                                    paragraphMap.put(JsonName.Y1, height - block.getBottomY());
                                    paragraphMap.put(JsonName.WIDTH, block.getWidth());
                                    paragraphMap.put(JsonName.HEIGHT, block.getHeight());
                                    paragraphMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - block.getTopY());
                                    List<Map<String, Object>> lineList = new ArrayList<>();
                                    block.getLines().forEach(line -> {
                                        Map<String, Object> textMap = new HashMap<>();
                                        textMap.put(JsonName.ITEM_TYPE, "text");
                                        textMap.put(JsonName.FONT_UNDERLINE_SIZE, line.getFontSize());
                                        textMap.put(JsonName.X0, line.getLeftX());
                                        textMap.put(JsonName.X1, line.getRightX());
                                        textMap.put(JsonName.Y0, height - line.getTopY());
                                        textMap.put(JsonName.Y1, height - line.getBottomY());
                                        textMap.put(JsonName.WIDTH, line.getWidth());
                                        textMap.put(JsonName.HEIGHT, line.getHeight());
                                        textMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - line.getTopY());
                                        String lineText = line.getTextChunks().stream().map(chunk -> {
                                            String val = chunk.getValue();
                                            if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                                return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                            } else {
                                                return val;
                                            }
                                        }).collect(Collectors.joining(""));
                                        textMap.put(JsonName.CONTENT, Arrays.asList(lineText));
                                        lineList.add(textMap);
                                        lineBottomY[0] = line.getBottomY();
                                    });
                                    paragraphMap.put(JsonName.CONTENT, lineList);
                                    headingList.add(paragraphMap);
                                    lineBottomY[0] = block.getBottomY();
                                });
                            });
                            for (Map<String, Object> headingItem : headingList) {
                                jsonGenerator.writeObject(headingItem);
                            }
                        }
                        if (content instanceof PDFList) {
                            PDFList pdfList = (PDFList) content;
                            List<Map<String, Object>> list = new ArrayList<>();
                            final double[] lineBottomY = {prevBottomY[0]};
                            pdfList.getListItems().forEach(listItem -> {
                                Map<String, Object> paragraphMap = new HashMap<>();
                                paragraphMap.put(JsonName.ITEM_TYPE, "text");
                                paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_LIST);
                                paragraphMap.put(JsonName.ID, finalTextId);
                                paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, listItem.getFontSize());
                                paragraphMap.put(JsonName.X0, listItem.getLeftX());
                                paragraphMap.put(JsonName.X1, listItem.getRightX());
                                paragraphMap.put(JsonName.Y0, height - listItem.getTopY());
                                paragraphMap.put(JsonName.Y1, height - listItem.getBottomY());
                                paragraphMap.put(JsonName.WIDTH, listItem.getWidth());
                                paragraphMap.put(JsonName.HEIGHT, listItem.getHeight());
                                paragraphMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - listItem.getTopY());
                                List<Map<String, Object>> lineList = new ArrayList<>();
                                listItem.getLines().forEach(line -> {
                                    Map<String, Object> textMap = new HashMap<>();
                                    textMap.put(JsonName.ITEM_TYPE, "text");
                                    textMap.put(JsonName.FONT_UNDERLINE_SIZE, line.getFontSize());
                                    textMap.put(JsonName.X0, line.getLeftX());
                                    textMap.put(JsonName.X1, line.getRightX());
                                    textMap.put(JsonName.Y0, height - line.getTopY());
                                    textMap.put(JsonName.Y1, height - line.getBottomY());
                                    textMap.put(JsonName.WIDTH, line.getWidth());
                                    textMap.put(JsonName.HEIGHT, line.getHeight());
                                    textMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - line.getTopY());
                                    String lineText = line.getTextChunks().stream().map(chunk -> {
                                        String val = chunk.getValue();
                                        if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                            return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                        } else {
                                            return val;
                                        }
                                    }).collect(Collectors.joining(""));
                                    textMap.put(JsonName.CONTENT, Arrays.asList(lineText));
                                    lineList.add(textMap);
                                    lineBottomY[0] = line.getBottomY();
                                });
                                paragraphMap.put(JsonName.CONTENT, lineList);
                                list.add(paragraphMap);
                                lineBottomY[0] = listItem.getBottomY();
                            });
                            for (Map<String, Object> pdfItem : list) {
                                jsonGenerator.writeObject(pdfItem);
                            }
                        }
                        if (content instanceof SemanticCaption) {
                            SemanticCaption semanticCaption = (SemanticCaption) content;
                            List<Map<String, Object>> captionList = new ArrayList<>();
                            final double[] lineBottomY = {prevBottomY[0]};
                            semanticCaption.getColumns().forEach(column -> {
                                column.getBlocks().forEach(block -> {
                                    Map<String, Object> paragraphMap = new HashMap<>();
                                    paragraphMap.put(JsonName.ITEM_TYPE, "text");
                                    paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_CAPTION);
                                    paragraphMap.put(JsonName.ID, finalTextId);
                                    paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, block.getFontSize());
                                    paragraphMap.put(JsonName.X0, block.getLeftX());
                                    paragraphMap.put(JsonName.X1, block.getRightX());
                                    paragraphMap.put(JsonName.Y0, height - block.getTopY());
                                    paragraphMap.put(JsonName.Y1, height - block.getBottomY());
                                    paragraphMap.put(JsonName.WIDTH, block.getWidth());
                                    paragraphMap.put(JsonName.HEIGHT, block.getHeight());
                                    paragraphMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - block.getTopY());
                                    List<Map<String, Object>> lineList = new ArrayList<>();
                                    block.getLines().forEach(line -> {
                                        Map<String, Object> textMap = new HashMap<>();
                                        textMap.put(JsonName.ITEM_TYPE, "text");
                                        textMap.put(JsonName.FONT_UNDERLINE_SIZE, line.getFontSize());
                                        textMap.put(JsonName.X0, line.getLeftX());
                                        textMap.put(JsonName.X1, line.getRightX());
                                        textMap.put(JsonName.Y0, height - line.getTopY());
                                        textMap.put(JsonName.Y1, height - line.getBottomY());
                                        textMap.put(JsonName.WIDTH, line.getWidth());
                                        textMap.put(JsonName.HEIGHT, line.getHeight());
                                        textMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - line.getTopY());
                                        String lineText = line.getTextChunks().stream().map(chunk -> {
                                            String val = chunk.getValue();
                                            if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                                return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                            } else {
                                                return val;
                                            }
                                        }).collect(Collectors.joining(""));
                                        textMap.put(JsonName.CONTENT, Arrays.asList(lineText));
                                        lineList.add(textMap);
                                        lineBottomY[0] = line.getBottomY();
                                    });
                                    paragraphMap.put(JsonName.CONTENT, lineList);
                                    captionList.add(paragraphMap);
                                    lineBottomY[0] = block.getBottomY();
                                });
                            });
                            for (Map<String, Object> captionItem : captionList) {
                                jsonGenerator.writeObject(captionItem);
                            }
                        }
                        if (content instanceof SemanticTOC) {
                            SemanticTOC semanticTOC = (SemanticTOC) content;
                            List<Map<String, Object>> tocList = new ArrayList<>();
                            final double[] lineBottomY = {prevBottomY[0]};
                            semanticTOC.getTOCItems().forEach(tocItem -> {
                                if (tocItem instanceof SemanticTOCI) {
                                    SemanticTOCI semanticTOCI = (SemanticTOCI) tocItem;
                                    semanticTOCI.getLines().forEach(line -> {
                                        Map<String, Object> paragraphMap = new HashMap<>();
                                        paragraphMap.put(JsonName.ITEM_TYPE, "text");
                                        paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_TOC);
                                        paragraphMap.put(JsonName.ID, finalTextId);
                                        paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, line.getFontSize());
                                        paragraphMap.put(JsonName.X0, line.getLeftX());
                                        paragraphMap.put(JsonName.X1, line.getRightX());
                                        paragraphMap.put(JsonName.Y0, height - line.getTopY());
                                        paragraphMap.put(JsonName.Y1, height - line.getBottomY());
                                        paragraphMap.put(JsonName.WIDTH, line.getWidth());
                                        paragraphMap.put(JsonName.HEIGHT, line.getHeight());
                                        paragraphMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - line.getTopY());
                                        Map<String, Object> tocItemMap = new HashMap<>();
                                        tocItemMap.put(JsonName.ITEM_TYPE, "text");
                                        tocItemMap.put(JsonName.FONT_UNDERLINE_SIZE, line.getFontSize());
                                        tocItemMap.put(JsonName.X0, line.getLeftX());
                                        tocItemMap.put(JsonName.X1, line.getRightX());
                                        tocItemMap.put(JsonName.Y0, height - line.getTopY());
                                        tocItemMap.put(JsonName.Y1, height - line.getBottomY());
                                        tocItemMap.put(JsonName.WIDTH, line.getWidth());
                                        tocItemMap.put(JsonName.HEIGHT, line.getHeight());
                                        tocItemMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - line.getTopY());
                                        String lineText = line.getTextChunks().stream().map(chunk -> {
                                            String val = chunk.getValue();
                                            if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                                return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                            } else {
                                                return val;
                                            }
                                        }).collect(Collectors.joining(""));
                                        tocItemMap.put(JsonName.CONTENT, Arrays.asList(lineText));
                                        paragraphMap.put(JsonName.CONTENT, Arrays.asList(tocItemMap));
                                        tocList.add(paragraphMap);
                                        lineBottomY[0] = line.getBottomY();
                                    });
                                }
                            });
                            for (Map<String, Object> tocItem : tocList) {
                                jsonGenerator.writeObject(tocItem);
                            }
                        }
                        if (content instanceof TextChunk) {
                            Map<String, Object> paragraphMap = new HashMap<>();
                            List<Map<String, Object>> paragraphContentList = new ArrayList<>();
                            TextChunk textChunk = (TextChunk) content;
                            Map<String, Object> textLineMap = new HashMap<>();
                            textLineMap.put(JsonName.ITEM_TYPE, "text");
                            textLineMap.put(JsonName.IS_THIRD_PARTY, true);
                            textLineMap.put(JsonName.HEIGHT, textChunk.getHeight());
                            textLineMap.put(JsonName.WIDTH, textChunk.getWidth());
                            textLineMap.put(JsonName.FONT_UNDERLINE_SIZE, textChunk.getFontSize());
                            textLineMap.put(JsonName.X0, textChunk.getLeftX());
                            textLineMap.put(JsonName.X1, textChunk.getRightX());
                            textLineMap.put(JsonName.Y0, height - textChunk.getTopY());
                            textLineMap.put(JsonName.Y1, height - textChunk.getBottomY());
                            textLineMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - textChunk.getTopY());
                            textLineMap.put(JsonName.CONTENT, Arrays.asList(textChunk.getValue()));
                            paragraphContentList.add(textLineMap);
                            paragraphMap.put(JsonName.CONTENT, paragraphContentList);
                            paragraphMap.put(JsonName.ITEM_TYPE, "text");
                            paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_TEXT_CHUNK);
                            paragraphMap.put(JsonName.ID, finalTextId);
                            paragraphMap.put(JsonName.IS_BOOKMARK, false);
                            paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, textChunk.getFontSize());
                            paragraphMap.put(JsonName.X0, textChunk.getLeftX());
                            paragraphMap.put(JsonName.X1, textChunk.getRightX());
                            paragraphMap.put(JsonName.Y0, height - textChunk.getTopY());
                            paragraphMap.put(JsonName.Y1, height - textChunk.getBottomY());
                            paragraphMap.put(JsonName.WIDTH, textChunk.getWidth());
                            paragraphMap.put(JsonName.HEIGHT, textChunk.getHeight());
                            paragraphMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - textChunk.getTopY());
                            jsonGenerator.writeObject(paragraphMap);
                        }
                        if (content instanceof CustomSemanticParagraph) {
                            Map<String, Object> paragraphMap = new HashMap<>();
                            List<Map<String, Object>> paragraphContentList = new ArrayList<>();
                            CustomSemanticParagraph customSemanticParagraph = (CustomSemanticParagraph) content;
                            final double[] lineBottomY = {prevBottomY[0]};
                            customSemanticParagraph.getTextLines().forEach(textLine -> {
                                Map<String, Object> textLineMap = new HashMap<>();
                                textLineMap.put(JsonName.ITEM_TYPE, "text");
                                textLineMap.put(JsonName.IS_THIRD_PARTY, false);
                                textLineMap.put(JsonName.HEIGHT, textLine.getHeight());
                                textLineMap.put(JsonName.WIDTH, textLine.getWidth());
                                textLineMap.put(JsonName.FONT_UNDERLINE_SIZE, textLine.getFontSize());
                                textLineMap.put(JsonName.X0, textLine.getLeftX());
                                textLineMap.put(JsonName.X1, textLine.getRightX());
                                textLineMap.put(JsonName.Y0, height - textLine.getTopY());
                                textLineMap.put(JsonName.Y1, height - textLine.getBottomY());
                                textLineMap.put(JsonName.MARGIN_TOP, lineBottomY[0] - textLine.getTopY());
                                String lineText = textLine.getTextChunks().stream().map(chunk -> {
                                    String val = chunk.getValue();
                                    if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                        return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                    } else {
                                        return val;
                                    }
                                }).collect(Collectors.joining(""));
                                textLineMap.put(JsonName.CONTENT, Arrays.asList(lineText));
                                paragraphContentList.add(textLineMap);
                                lineBottomY[0] = textLine.getBottomY();
                            });
                            paragraphMap.put(JsonName.CONTENT, paragraphContentList);
                            paragraphMap.put(JsonName.ITEM_TYPE, "text");
                            paragraphMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_PARAGRAPH);
                            paragraphMap.put(JsonName.ID, finalTextId);
                            paragraphMap.put(JsonName.IS_BOOKMARK, false);
                            paragraphMap.put(JsonName.FONT_UNDERLINE_SIZE, customSemanticParagraph.getFontSize());
                            paragraphMap.put(JsonName.X0, customSemanticParagraph.getLeftX());
                            paragraphMap.put(JsonName.X1, customSemanticParagraph.getRightX());
                            paragraphMap.put(JsonName.Y0, height - customSemanticParagraph.getTopY());
                            paragraphMap.put(JsonName.Y1, height - customSemanticParagraph.getBottomY());
                            paragraphMap.put(JsonName.WIDTH, customSemanticParagraph.getWidth());
                            paragraphMap.put(JsonName.HEIGHT, customSemanticParagraph.getHeight());
                            paragraphMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - customSemanticParagraph.getTopY());
                            jsonGenerator.writeObject(paragraphMap);
                        }
                        if (content instanceof ImageChunk) {
                            ImageChunk imageChunk = (ImageChunk) content;
                            Map<String, Object> imageMap = new HashMap<>();
                            imageMap.put(JsonName.ITEM_TYPE, "image");
                            imageMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_IMAGE);
                            imageMap.put(JsonName.ID, finalTextId);
                            imageMap.put(JsonName.WIDTH, imageChunk.getWidth());
                            imageMap.put(JsonName.HEIGHT, imageChunk.getHeight());
                            imageMap.put(JsonName.FONT_UNDERLINE_SIZE, imageChunk.getHeight());
                            imageMap.put(JsonName.X0, imageChunk.getLeftX());
                            imageMap.put(JsonName.X1, imageChunk.getRightX());
                            imageMap.put(JsonName.Y0, height - imageChunk.getTopY());
                            imageMap.put(JsonName.Y1, height - imageChunk.getBottomY());
                            String absoluteImagesDirectory = StaticLayoutContainers.getImagesDirectory();
                            String imageFormat = StaticLayoutContainers.getImageFormat();
                            String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, absoluteImagesDirectory,
                                File.separator, imageChunk.getIndex(), imageFormat);
                            imageMap.put(JsonName.CONTENT, Arrays.asList(absolutePath));
                            imageMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - imageChunk.getTopY());
                            jsonGenerator.writeObject(imageMap);
                        }
                        if (content instanceof PageItem) {
                            PageItem pageItem = (PageItem) content;
                            if ("stream_table".equals(pageItem.getItemType())) {
                                Map<String, Object> tableMap = new HashMap<>();
                                tableMap.put(JsonName.ITEM_TYPE, "stream_table");
                                tableMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_STREAM_TABLE);
                                tableMap.put(JsonName.ID, finalTextId);
                                tableMap.put(JsonName.WIDTH, pageItem.getWidth());
                                tableMap.put(JsonName.HEIGHT, pageItem.getHeight());
                                tableMap.put(JsonName.X0, pageItem.getX0());
                                tableMap.put(JsonName.X1, pageItem.getX1());
                                tableMap.put(JsonName.Y0, pageItem.getY0());
                                tableMap.put(JsonName.Y1, pageItem.getY1());
                                tableMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - pageItem.getTopY());
                                tableMap.put(JsonName.IS_THIRD_PARTY, true);
                                List<List<Map<String, Object>>> rowList = new ArrayList<>();
                                List<List<TableSingleItem>> tableContent = (List<List<TableSingleItem>>) pageItem.getContent();
                                for (List<TableSingleItem> row : tableContent) {
                                    List<Map<String, Object>> rowMapList = new ArrayList<>();
                                    for (TableSingleItem cell : row) {
                                        Map<String, Object> cellMap = new HashMap<>();
                                        cellMap.put(JsonName.CELL_RADIO, cell.getCellRadio());
                                        cellMap.put(JsonName.ROW_LENGTH, cell.getRowLen());
                                        cellMap.put(JsonName.COLUMN_LENGTH, cell.getColumnLen());
                                        cellMap.put(JsonName.HEIGHT, cell.getHeight());
                                        cellMap.put(JsonName.WIDTH, cell.getWidth());
                                        cellMap.put(JsonName.X0, cell.getX0());
                                        cellMap.put(JsonName.X1, cell.getX1());
                                        cellMap.put(JsonName.TEXT, cell.getText());
                                        cellMap.put(JsonName.CELL_TYPE, "text");
                                        rowMapList.add(cellMap);
                                    }
                                    rowList.add(rowMapList);
                                }
                                tableMap.put(JsonName.CONTENT, rowList);
                                jsonGenerator.writeObject(tableMap);
                            }
                        }
                        if (content instanceof TableBorder) {
                            TableBorder tableBorder = (TableBorder) content;
                            Map<String, Object> tableMap = new HashMap<>();
                            tableMap.put(JsonName.ITEM_TYPE, "lattice_table");
                            tableMap.put(JsonName.SOURCE_TYPE, JsonName.SOURCE_TYPE_LATTICE_TABLE);
                            tableMap.put(JsonName.ID, finalTextId);
                            tableMap.put(JsonName.WIDTH, tableBorder.getWidth());
                            tableMap.put(JsonName.HEIGHT, tableBorder.getHeight());
                            tableMap.put(JsonName.X0, tableBorder.getLeftX());
                            tableMap.put(JsonName.X1, tableBorder.getRightX());
                            tableMap.put(JsonName.Y0, height - tableBorder.getTopY());
                            tableMap.put(JsonName.Y1, height - tableBorder.getBottomY());
                            tableMap.put(JsonName.MARGIN_TOP, prevBottomY[0] - tableBorder.getTopY());
                            tableMap.put(JsonName.IS_THIRD_PARTY, false);
                            List<List<Map<String, Object>>> rowList = new ArrayList<>();
                            int numberOfColumns = ((TableBorder) content).getNumberOfColumns();
                            float[] cellRadios = new float[numberOfColumns];
                            double tableWidth = tableBorder.getLeftX(numberOfColumns) - tableBorder.getLeftX(0);
                            float cumulativeProportion = 0;
                            for (int i = 1; i <= numberOfColumns; i++) {
                                if (i == numberOfColumns) {
                                    cellRadios[i - 1] = 1 - cumulativeProportion;
                                } else {
                                    float cellRadio = BigDecimal.valueOf((float) (tableBorder.getLeftX(i) - tableBorder.getLeftX(i - 1)) / tableWidth)
                                        .setScale(4, RoundingMode.HALF_UP).floatValue();
                                    cellRadios[i - 1] = cellRadio;
                                    cumulativeProportion += cellRadio;
                                }
                            }
                            int[] cumulativeRowSpans = new int[numberOfColumns];
                            for (int m = 0; m < tableBorder.getRows().length; m++) {
                                TableBorderRow row = tableBorder.getRows()[m];
                                List<Map<String, Object>> oneRowList = new ArrayList<>();
                                int cumulativeColSpans = 0;
                                for (int i = 0; i < row.getCells().length; i++) {
                                    if (cumulativeColSpans >= numberOfColumns) {
                                        break;
                                    }

                                    TableBorderCell cell = row.getCells()[i];
                                    if (m < cumulativeRowSpans[i]) {
                                        if (i >= cumulativeColSpans) {
                                            cumulativeColSpans += cell.getColSpan();
                                        }
                                        continue;
                                    }
                                    Map<String, Object> cellMap = new HashMap<>();
                                    float cellRadio = 0;
                                    for (int j = cumulativeColSpans; j < cumulativeColSpans + cell.getColSpan(); j++) {
                                        cellRadio += cellRadios[j];
                                        cumulativeRowSpans[j] += cell.getRowSpan();
                                    }
                                    cellMap.put(JsonName.CELL_RADIO, cellRadio);
                                    cumulativeColSpans += cell.getColSpan();
                                    cellMap.put(JsonName.ROW_LENGTH, cell.getRowSpan());
                                    cellMap.put(JsonName.COLUMN_LENGTH, cell.getColSpan());
                                    cellMap.put(JsonName.HEIGHT, cell.getHeight());
                                    cellMap.put(JsonName.WIDTH, cell.getWidth());
                                    cellMap.put(JsonName.X0, cell.getLeftX());
                                    cellMap.put(JsonName.X1, cell.getRightX());
                                    cellMap.put(JsonName.Y0, height - cell.getTopY());
                                    cellMap.put(JsonName.Y1, height - cell.getBottomY());
                                    double[] backgroundColor = cell.getBackgroundColor();
                                    if (backgroundColor != null && backgroundColor.length == 3) {
                                        // convert normalized RGB [0,1] to 0-255 integers, then to hex
                                        int r = Math.max(0, Math.min(255, (int) Math.round(backgroundColor[0] * 255)));
                                        int g = Math.max(0, Math.min(255, (int) Math.round(backgroundColor[1] * 255)));
                                        int b = Math.max(0, Math.min(255, (int) Math.round(backgroundColor[2] * 255)));
                                        cellMap.put(JsonName.BACKGROUND_COLOR, String.format("#%02x%02x%02x", r, g, b));
                                    }
                                    String text = "";
                                    List<String> textList = new ArrayList<>();
                                    for (int n = cell.getRowNumber(); n < cell.getRowNumber() + cell.getRowSpan(); n++) {
                                        for (int k = cell.getColNumber(); k < cell.getColNumber() + cell.getColSpan(); k++) {
                                            if (n < tableBorder.getRows().length && k < tableBorder.getRows()[n].getCells().length) {
                                                TableBorderCell cellItem = tableBorder.getRows()[n].getCells()[k];
                                                String currentText = "";
                                                for (IObject cellContent : cellItem.getContents()) {
                                                    if (cellContent instanceof CustomSemanticParagraph) {
                                                        currentText += ((CustomSemanticParagraph) cellContent).getTextLines().stream()
                                                            .map(line ->
                                                                line.getTextChunks().stream().map(chunk -> {
                                                                    String val = chunk.getValue();
                                                                    if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                                                                        return GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
                                                                    } else {
                                                                        return val;
                                                                    }
                                                                }).collect(Collectors.joining("")))
                                                            .collect(Collectors.joining(""));
                                                    }
                                                    if (cellContent instanceof TextChunk) {
                                                        String val = ((TextChunk) cellContent).getValue();
                                                        currentText += GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val) ?
                                                            GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val)) :
                                                            val;
                                                    }
                                                    if (cellContent instanceof ImageChunk) {
                                                        ImageChunk imageChunk = (ImageChunk) cellContent;
                                                        String absoluteImagesDirectory = StaticLayoutContainers.getImagesDirectory();
                                                        String imageFormat = StaticLayoutContainers.getImageFormat();
                                                        String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, absoluteImagesDirectory,
                                                            File.separator, imageChunk.getIndex(), imageFormat);
                                                        currentText += "<img src='" + absolutePath + "' style='height: " + imageChunk.getHeight() + "pt !important; width: " + imageChunk.getWidth() + "pt !important;' />";
                                                    }
                                                }
                                                if (!textList.contains(currentText) && currentText.length() > 0) {
                                                    textList.add(currentText);
                                                }
                                            }
                                        }
                                    }
                                    if (textList.size() > 0) {
                                        text = String.join("", textList);
                                    }
                                    cellMap.put(JsonName.TEXT, Arrays.asList(text));
                                    cellMap.put(JsonName.CELL_TYPE, "text");
                                    oneRowList.add(cellMap);
                                }
                                rowList.add(oneRowList);
                            }
                            tableMap.put(JsonName.CONTENT, rowList);
                            jsonGenerator.writeObject(tableMap);
                        }
                        if (!(content instanceof ShapeChunk)) {
                            textId++;
                        }
                        prevBottomY[0] = content.getBottomY();
                    }

                    jsonGenerator.writeEndArray();
                    jsonGenerator.writeEndObject();
                }
                jsonGenerator.writeEndArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SerializerUtil.clearElementMetadata();
            }

            jsonGenerator.writeEndObject();
            LOGGER.log(Level.INFO, "Created {0}", jsonFileName);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Unable to create JSON output: " + ex.getMessage());
        }

        // 重新读取json文件内容
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(
            new File(jsonFileName),
            new TypeReference<Map<String, Object>>() {}
        );

        // 解析配置中的 OSS 参数，判断是否启用对象存储
        OssUploadConfig ossConfig = OssUploadConfig.fromCustomOptions(config);
        boolean ossEnabled = ossConfig.isEnabled();
        HuaweiObsClient obsClient = null;
        if (ossEnabled) {
            obsClient = new HuaweiObsClient(ossConfig.getEndpoint(), ossConfig.getAccessKey(), ossConfig.getSecretKey());
        }

        // 遍历每页，把图片上传到永久桶并用 OSS URL 替换本地路径
        try {
            if (ossEnabled) {
                uploadImagesToOssAndUpdateMap(map, obsClient, ossConfig);
            }

            // 扫描每一页，识别符合 OCR 条件的页面，写入 <pdfname>_ocr.json，
            // 并在命中页上将 is_ocr 置为 true（由后续 bookmarks 写回主 JSON 时一起持久化）。
            try {
                writeOcrDetectionJson(mapper, map, outputFolder, inputPDF.getName(), config);
            } catch (Exception ocrEx) {
                LOGGER.log(Level.WARNING, "Unable to create OCR detection JSON: " + ocrEx.getMessage());
            }

            // 在已生成的 JSON 数据上识别 catalog_bookmarks 与 page_bookmarks，
            // page_bookmarks 的 relatedId 直接复用 JSON item 的 id。
            if (config != null) {
                // 记录 BookmarkQualitySelector 选中的来源键名；未选中(null)时三个原始键全部保留。
                String selectedSource = null;
                List<Map<String, Object>> data = (List<Map<String, Object>>) map.get(JsonName.DATA);
                if (data != null) {
                    resolveSelfBookmarkRelatedIds(mapper, map, data);
                    CatalogBookmarkProcessor.CatalogResult catalogResult =
                        CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);
                    List<Bookmark> catalogBookmarks = catalogResult.getBookmarks();
                    int catalogStartPage = catalogResult.getStartPage();
                    int catalogEndPage = catalogResult.getEndPage();

                    List<Bookmark> pageBookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(
                        data, catalogStartPage, catalogEndPage);

                    // Complement missing L2/L3 sub-bookmarks in the catalog tree from
                    // the page bookmark candidates (sliced by anchor ranges).
                    CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(
                        data, catalogStartPage, catalogEndPage, catalogBookmarks, pageBookmarks);

                    if (catalogStartPage >= 0 && catalogEndPage >= catalogStartPage) {
                        map.put("catalog_page_range_start", catalogStartPage + 1);
                        map.put("catalog_page_range_end", catalogEndPage + 1);
                    }
                    map.put("catalog_bookmarks", catalogBookmarks);
                    map.put("page_bookmarks", pageBookmarks);

                    writeCollectedPageBookmarkMarkdown(outputFolder, inputPdfName, data,
                        catalogStartPage, catalogEndPage);

                    // 从 catalog/page/self 三种来源中选出质量最高的目录写入 bookmarks
                    List<Bookmark> selfBookmarks = mapper.convertValue(
                        map.get("self_bookmarks"), new TypeReference<List<Bookmark>>() {});
                    Map<Integer, Set<Integer>> pageItemIds = BookmarkQualitySelector.buildPageItemIds(data);
                    BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
                        catalogBookmarks, pageBookmarks, selfBookmarks, pageItemIds);
                    map.put("bookmarks", selection.getBookmarks());
                    // 只移除被选中的来源；未选中的(或全部被淘汰时 selectedSource 为 null)
                    // 仍保留在 map 中，以便写入 json。
                    selectedSource = selection.getSource();
                } else {
                    map.put("bookmarks", new ArrayList<>());
                    // data 为空时无法做来源选择，三个原始键保留在输出中。
                }
                if (selectedSource != null) {
                    map.remove(selectedSource);
                }

                // 把更新后的内容重新写回 json 文件
                mapper.writerWithDefaultPrettyPrinter().writeValue(new File(jsonFileName), map);
            }

            // 上传主 JSON 到临时桶，并构造返回结果
            String jsonUrlOrPath;
            String ocrJsonLocalPath = resolveOcrJsonLocalPath(outputFolder, inputPDF.getName());
            boolean ossUploadSuccess = false;
            if (ossEnabled) {
                String jsonObjectKey = buildJsonObjectKey(ossConfig, inputPDF.getName());
                jsonUrlOrPath = obsClient.uploadFile(ossConfig.getTempBucketName(), jsonObjectKey, new File(jsonFileName), ossConfig.getTempDomainName());
                LOGGER.log(Level.INFO, "Uploaded main JSON to OBS: {0}", jsonUrlOrPath);
                ossUploadSuccess = true;

                // 上传成功后清理本地文件：保留 _ocr.json，删除 outputFolder 下与当前 PDF 相关的其余生成文件
                cleanupLocalFiles(outputFolder, inputPDF.getName(), ocrJsonLocalPath);
            } else {
                // 生成 html/js/css 等辅助文件
                FileUtils.copyResourceToDir("templates/index.css", outputFolder);
                String jsFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "js";
                String jsFileContent = "var url = " + mapper.writeValueAsString(inputPdfName) + ";";
                jsFileContent += "\n\n";
                Object selectedBookmarks = map.get("bookmarks");
                jsFileContent += "var bookmarks = " + mapper.writeValueAsString(
                    selectedBookmarks != null ? selectedBookmarks : new ArrayList<>()) + ";";
                jsFileContent += "\n\n";
                jsFileContent += "var data = " + mapper.writeValueAsString(map.get(JsonName.DATA)) + ";";
                FileUtils.writeToFile(jsFileName, jsFileContent);

                String htmlFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "html";
                // 按行读取 templates/announcementAnalysis.html 文件，并按行写入 htmlFileName
                List<String> htmlLines = FileUtils.readResourceLines("templates/announcementAnalysis.html");
                String pdfFileName = inputPDF.getName().substring(0, inputPDF.getName().length() - 4);
                htmlLines.set(6, "  <title>" + pdfFileName + "</title>");
                htmlLines.set(11, "  <script type=\"text/javascript\" src= \"" + pdfFileName + ".js\"></script>");
                FileUtils.writeToFile(htmlFileName, String.join("\n", htmlLines));
                jsonUrlOrPath = new File(jsonFileName).getAbsolutePath();
            }

            return new CustomOutputResult(jsonUrlOrPath, ocrJsonLocalPath, ossUploadSuccess);
        } finally {
            if (obsClient != null) {
                try {
                    obsClient.close();
                } catch (IOException closeEx) {
                    LOGGER.log(Level.WARNING, "Failed to close OBS client: " + closeEx.getMessage());
                }
            }
        }
    }

    /**
     * 从 JSON data 中收集 page bookmark 候选并写出调试 markdown。
     */
    private static void writeCollectedPageBookmarkMarkdown(String outputFolder, String inputPdfName,
                                                           List<Map<String, Object>> data,
                                                           int catalogStartPage, int catalogEndPage) {
        Path outputFolderPath = Path.of(outputFolder);
        String inputName = Path.of(inputPdfName).getFileName().toString();
        String stem = inputName.toLowerCase(Locale.ROOT).endsWith(".pdf")
            ? inputName.substring(0, inputName.length() - 4) : inputName;
        Path output = outputFolderPath.resolve(stem + "_page_bookmarks_collected.md");
        StringBuilder markdown = new StringBuilder("# 收集到的页面目录候选（JSON items）\n\n");
        markdown.append("| 所在页码 | 目录内容 |\n|---:|---|\n");
        int count = 0;
        for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
            if (catalogStartPage >= 0 && catalogEndPage >= catalogStartPage
                    && pageIndex >= catalogStartPage && pageIndex <= catalogEndPage) {
                continue;
            }
            Map<String, Object> page = data.get(pageIndex);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get(JsonName.ITEMS);
            if (items == null) {
                continue;
            }
            for (Map<String, Object> item : items) {
                if (!isParagraphOrHeadingItem(item)) {
                    continue;
                }
                String firstLine = getItemFirstLineText(item);
                if (firstLine == null || !PageBookmarkProcessor.isBookmarkCandidate(firstLine)) {
                    continue;
                }
                String fullText = getItemFullText(item);
                if (fullText.isEmpty()) {
                    continue;
                }
                markdown.append('|').append(pageIndex + 1).append('|')
                    .append(fullText.replace("|", "\\|")).append('|').append('\n');
                count++;
            }
        }
        markdown.insert(0, "<!-- entries: " + count + " -->\n");
        try {
            Files.createDirectories(outputFolderPath);
            Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[JsonWriter] unable to write collected page bookmark markdown: " + output, e);
        }
    }

    private static boolean isParagraphOrHeadingItem(Map<String, Object> item) {
        String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
        return JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)
            || JsonName.SOURCE_TYPE_HEADING.equals(sourceType);
    }

    /**
     * 扫描主 JSON 的每一页，对符合 OCR 条件的页面：
     * <ul>
     *     <li>将页面对象上的 {@code is_ocr} 置为 {@code true}（由后续 bookmarks 写回时一起持久化到主 JSON）</li>
     *     <li>把命中页汇总后写入 {@code <pdfname>_ocr.json}（紧凑 JSON，与样例格式一致）</li>
     * </ul>
     *
     * <p>OCR 命中条件：
     * <ol>
     *     <li>页面 items 数量不超过 4</li>
     *     <li>页面内不含 lattice_table / stream_table</li>
     *     <li>页面内至少包含一张图片</li>
     *     <li>存在某张图片，其 {@code height / page.height > 0.8}（同一页内有多张满足条件时取比例最大的那张）</li>
     * </ol>
     *
     * @param mapper       已用于读写主 JSON 的 ObjectMapper
     * @param map          主 JSON 反序列化得到的内存 Map（其内 {@code data} 列表会被原地更新 is_ocr）
     * @param outputFolder 输出目录（与主 JSON 同目录）
     * @param pdfFileName  原始 PDF 文件名（含扩展名），用于推导 {@code <pdfname>_ocr.json}
     */
    private static void writeOcrDetectionJson(ObjectMapper mapper,
                                              Map<String, Object> map,
                                              String outputFolder,
                                              String pdfFileName,
                                              Config config) throws IOException {
        Object dataObj = map.get(JsonName.DATA);
        if (!(dataObj instanceof List)) {
            return;
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) dataObj;
        List<Map<String, Object>> ocrEntries = new ArrayList<>();

        for (Map<String, Object> page : data) {
            Object itemsObj = page.get(JsonName.ITEMS);
            if (!(itemsObj instanceof List)) {
                continue;
            }
            List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;

            // 条件1：items 数量不超过 4
            if (items.size() > 4) {
                continue;
            }

            // 条件2：无表格
            boolean hasTable = false;
            for (Map<String, Object> item : items) {
                String itemType = (String) item.get(JsonName.ITEM_TYPE);
                if ("lattice_table".equals(itemType) || "stream_table".equals(itemType)) {
                    hasTable = true;
                    break;
                }
            }
            if (hasTable) {
                continue;
            }

            // 条件3 + 4：包含图片，且有图片 height / page.height > 0.8
            Object pageHeightObj = page.get(JsonName.HEIGHT);
            if (!(pageHeightObj instanceof Number)) {
                continue;
            }
            double pageHeight = ((Number) pageHeightObj).doubleValue();
            if (pageHeight <= 0) {
                continue;
            }

            Map<String, Object> bestImage = null;
            double bestRatio = 0.0;
            for (Map<String, Object> item : items) {
                String itemType = (String) item.get(JsonName.ITEM_TYPE);
                if (!"image".equals(itemType)) {
                    continue;
                }
                Object imageHeightObj = item.get(JsonName.HEIGHT);
                if (!(imageHeightObj instanceof Number)) {
                    continue;
                }
                double imageHeight = ((Number) imageHeightObj).doubleValue();
                double ratio = imageHeight / pageHeight;
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    bestImage = item;
                }
            }
            if (bestImage == null || bestRatio <= 0.8) {
                continue;
            }

            // 命中：标记 is_ocr=true，并收集到 ocrEntries
            page.put(JsonName.IS_OCR, true);

            String imageUrl = "";
            Object contentObj = bestImage.get(JsonName.CONTENT);
            if (contentObj instanceof List && !((List<?>) contentObj).isEmpty()) {
                Object first = ((List<?>) contentObj).get(0);
                if (first != null) {
                    imageUrl = first.toString();
                }
            }

            Object pageWidthObj = page.get(JsonName.WIDTH);
            double pageWidth = pageWidthObj instanceof Number ? ((Number) pageWidthObj).doubleValue() : 0.0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(JsonName.PAGE_INDEX, page.get(JsonName.PAGE_INDEX));
            entry.put("image_url", imageUrl);
            entry.put("image_height", pageHeight);
            entry.put("image_width", pageWidth);
            ocrEntries.add(entry);
        }

        // 没有命中页时跳过写出，避免产生空 _ocr.json
        if (ocrEntries.isEmpty()) {
            LOGGER.log(Level.INFO, "No OCR pages detected, skip creating _ocr.json for {0}", pdfFileName);
            return;
        }

        // business_id / extend 取自 config.customOptions（key 分别为 businessId / extend），
        // 缺省时回落到原样（"None" / 空对象），保持向后兼容。
        Object businessId = null;
        Object extendValue = null;
        Map<String, Object> customOptions = config != null ? config.getCustomOptions() : null;
        if (customOptions != null) {
            if (customOptions.containsKey("businessId")) {
                businessId = customOptions.get("businessId");
            }
            if (customOptions.containsKey("extend")) {
                extendValue = customOptions.get("extend");
            }
        }

        Map<String, Object> ocrResult = new LinkedHashMap<>();
        if (businessId != null) {
            ocrResult.put("business_id", businessId);
        }
        if (extendValue != null) {
            ocrResult.put("extend", extendValue);
        }
        ocrResult.put("url", map.get("url"));
        ocrResult.put("data", ocrEntries);

        // 文件名：与主 JSON 同前缀（去掉尾部因 length()-3 残留的 '.'），后缀改为 "_ocr.json"
        String ocrBaseName = pdfFileName.substring(0, pdfFileName.length() - 3);
        if (ocrBaseName.endsWith(".")) {
            ocrBaseName = ocrBaseName.substring(0, ocrBaseName.length() - 1);
        }
        String ocrFileName = outputFolder + File.separator + ocrBaseName + "_ocr.json";

        // 紧凑 JSON（与样例 202604231785283947722051256_ocr.json 单行格式一致）
        mapper.writeValue(new File(ocrFileName), ocrResult);
        LOGGER.log(Level.INFO, "Created {0}", ocrFileName);
    }

    /**
     * 解析 self_bookmarks 的 related_id：对每个书签按 page_num 定位 JSON 页，
     * 用书签标题在该页 items 中匹配文本项，命中则复用其 id，未命中保持 0。
     */
    private static void resolveSelfBookmarkRelatedIds(ObjectMapper mapper, Map<String, Object> map,
                                                     List<Map<String, Object>> data) {
        Object selfObj = map.get("self_bookmarks");
        if (!(selfObj instanceof List)) {
            return;
        }
        List<Bookmark> selfBookmarks = mapper.convertValue(selfObj, new TypeReference<List<Bookmark>>() {});
        CatalogBookmarkProcessor.resolveSelfBookmarkRelatedIds(selfBookmarks, data);
        map.put("self_bookmarks", selfBookmarks);
    }

    /**
     * 取 JSON item 的第一行文本（用于模式匹配）。
     */
    private static String getItemFirstLineText(Map<String, Object> item) {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List) || ((List<?>) contentObj).isEmpty()) {
            return null;
        }
        List<?> contentList = (List<?>) contentObj;
        Object first = contentList.get(0);
        if (first instanceof Map) {
            Object textListObj = ((Map<?, ?>) first).get(JsonName.CONTENT);
            if (textListObj instanceof List && !((List<?>) textListObj).isEmpty()) {
                return ((List<?>) textListObj).get(0).toString();
            }
        }
        return first.toString();
    }

    /**
     * 取 JSON item 的完整文本。
     *
     * <p>遍历 item 所有行所有片段，调 {@link SmartTextJoiner} 按"两边都是 ASCII
     * 字母或两边都是 ASCII 数字才插空格"的规则拼接成单行文本。空字符串片段
     * 会被跳过，避免引入多余空白。</p>
     */
    private static String getItemFullText(Map<String, Object> item) {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return "";
        }
        List<String> pieces = new ArrayList<>();
        for (Object lineObj : (List<?>) contentObj) {
            if (lineObj instanceof Map) {
                Object textListObj = ((Map<?, ?>) lineObj).get(JsonName.CONTENT);
                if (textListObj instanceof List) {
                    for (Object t : (List<?>) textListObj) {
                        if (t != null) {
                            String s = t.toString();
                            if (!s.isEmpty()) {
                                pieces.add(s);
                            }
                        }
                    }
                }
            } else if (lineObj != null) {
                String s = lineObj.toString();
                if (!s.isEmpty()) {
                    pieces.add(s);
                }
            }
        }
        return SmartTextJoiner.joinNonEmptyPieces(pieces).trim();
    }

    /**
     * 封装 customOptions 中的 OSS 配置项。
     */
    private static final class OssUploadConfig {
        private final String businessId;
        private final String basicEnv;
        private final String pulsarReceiveTopicName;
        private final String tempBucketName;
        private final String permanentBucketName;
        private final String endpoint;
        private final String accessKey;
        private final String secretKey;
        private final String domainName;

        private OssUploadConfig(String businessId, String basicEnv, String pulsarReceiveTopicName,
                                String tempBucketName, String permanentBucketName, String endpoint,
                                String accessKey, String secretKey, String domainName) {
            this.businessId = businessId;
            this.basicEnv = basicEnv;
            this.pulsarReceiveTopicName = pulsarReceiveTopicName;
            this.tempBucketName = tempBucketName;
            this.permanentBucketName = permanentBucketName;
            this.endpoint = endpoint;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.domainName = domainName;
        }

        static OssUploadConfig fromCustomOptions(Config config) {
            if (config == null) {
                return disabled();
            }
            Map<String, Object> options = config.getCustomOptions();
            if (options == null) {
                return disabled();
            }
            String[] requiredKeys = {
                "businessId", "basicEnv", "pulsarReceiveTopicName", "ossTempBucketName",
                "ossPermanentBucketName", "ossEndpoint", "ossAccessKey", "ossSecretKey", "ossDomainName"
            };
            for (String key : requiredKeys) {
                if (!options.containsKey(key) || options.get(key) == null) {
                    return disabled();
                }
            }
            String topicName = String.valueOf(options.get("pulsarReceiveTopicName"));
            int lastSlash = topicName.lastIndexOf('/');
            String topicLastPart = lastSlash >= 0 ? topicName.substring(lastSlash + 1) : topicName;
            return new OssUploadConfig(
                String.valueOf(options.get("businessId")),
                String.valueOf(options.get("basicEnv")),
                topicLastPart,
                String.valueOf(options.get("ossTempBucketName")),
                String.valueOf(options.get("ossPermanentBucketName")),
                String.valueOf(options.get("ossEndpoint")),
                String.valueOf(options.get("ossAccessKey")),
                String.valueOf(options.get("ossSecretKey")),
                String.valueOf(options.get("ossDomainName"))
            );
        }

        /**
         * 仅校验重建书签场景所需的 8 项配置（不含 {@code ossPermanentBucketName}），
         * 与 {@link #fromCustomOptions(Config)} 共用同一字段集，便于在仅重建 JSON
         * 时不强制要求永久桶配置。其它语义完全一致。
         */
        static OssUploadConfig fromCustomOptionsForJsonUpload(Config config) {
            if (config == null) {
                return disabled();
            }
            Map<String, Object> options = config.getCustomOptions();
            if (options == null) {
                return disabled();
            }
            String[] requiredKeys = {
                "businessId", "basicEnv", "pulsarReceiveTopicName", "ossTempBucketName", "ossPermanentBucketName",
                "ossEndpoint", "ossAccessKey", "ossSecretKey", "ossDomainName"
            };
            for (String key : requiredKeys) {
                if (!options.containsKey(key) || options.get(key) == null) {
                    return disabled();
                }
            }
            String topicName = String.valueOf(options.get("pulsarReceiveTopicName"));
            int lastSlash = topicName.lastIndexOf('/');
            String topicLastPart = lastSlash >= 0 ? topicName.substring(lastSlash + 1) : topicName;
            return new OssUploadConfig(
                String.valueOf(options.get("businessId")),
                String.valueOf(options.get("basicEnv")),
                topicLastPart,
                String.valueOf(options.get("ossTempBucketName")),
                String.valueOf(options.get("ossPermanentBucketName")),
                String.valueOf(options.get("ossEndpoint")),
                String.valueOf(options.get("ossAccessKey")),
                String.valueOf(options.get("ossSecretKey")),
                String.valueOf(options.get("ossDomainName"))
            );
        }

        private static OssUploadConfig disabled() {
            return new OssUploadConfig(null, null, null, null, null, null, null, null, null);
        }

        boolean isEnabled() {
            return businessId != null;
        }

        String getBusinessId() { return businessId; }
        String getBasicEnv() { return basicEnv; }
        String getPulsarReceiveTopicName() { return pulsarReceiveTopicName; }
        String getTempBucketName() { return tempBucketName; }
        String getPermanentBucketName() { return permanentBucketName; }
        String getEndpoint() { return endpoint; }
        String getAccessKey() { return accessKey; }
        String getSecretKey() { return secretKey; }
        String getDomainName() { return domainName; }

        /**
         * Returns a domain name suitable for the temporary bucket by replacing
         * the permanent bucket name with the temporary bucket name.
         */
        String getTempDomainName() {
            if (domainName == null || domainName.isBlank()
                    || tempBucketName == null || tempBucketName.isBlank()
                    || permanentBucketName == null || permanentBucketName.isBlank()) {
                return domainName;
            }
            return domainName.replace(permanentBucketName, tempBucketName);
        }
    }

    /**
     * 遍历主 JSON 的每一页，将图片上传到 OSS 永久桶，并用 OSS URL 替换 content 中的本地路径。
     */
    private static void uploadImagesToOssAndUpdateMap(Map<String, Object> map,
                                                       HuaweiObsClient obsClient,
                                                       OssUploadConfig ossConfig) throws IOException {
        Object dataObj = map.get(JsonName.DATA);
        if (!(dataObj instanceof List)) {
            return;
        }
        List<?> pages = (List<?>) dataObj;
        for (Object pageObj : pages) {
            if (!(pageObj instanceof Map)) {
                continue;
            }
            Map<String, Object> page = (Map<String, Object>) pageObj;
            Object itemsObj = page.get(JsonName.ITEMS);
            if (!(itemsObj instanceof List)) {
                continue;
            }
            for (Object itemObj : (List<?>) itemsObj) {
                if (!(itemObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) itemObj;
                if (!"image".equals(item.get(JsonName.ITEM_TYPE))) {
                    continue;
                }
                Object contentObj = item.get(JsonName.CONTENT);
                if (!(contentObj instanceof List) || ((List<?>) contentObj).isEmpty()) {
                    continue;
                }
                Object first = ((List<?>) contentObj).get(0);
                if (first == null) {
                    continue;
                }
                String localPath = first.toString();
                File imageFile = new File(localPath);
                if (!imageFile.exists()) {
                    LOGGER.log(Level.WARNING, "Image file not found, skip uploading: {0}", localPath);
                    continue;
                }
                String imageName = imageFile.getName();
                String objectKey = String.format("public/%s/%s_%s/%s",
                    ossConfig.getBasicEnv(),
                    ossConfig.getPulsarReceiveTopicName(),
                    ossConfig.getBusinessId(),
                    imageName);
                String imageUrl = obsClient.uploadFile(
                    ossConfig.getPermanentBucketName(), objectKey, imageFile, ossConfig.getDomainName());
                ((List<Object>) contentObj).set(0, imageUrl);
                LOGGER.log(Level.INFO, "Replaced image path with OBS URL: {0}", imageUrl);
            }
        }
    }

    /**
     * 构造主 JSON 文件上传到临时桶时使用的 object key。
     */
    private static String buildJsonObjectKey(OssUploadConfig ossConfig, String pdfFileName) {
        String baseName = pdfFileName;
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        // 去除可能残留的点号
        if (baseName.endsWith(".")) {
            baseName = baseName.substring(0, baseName.length() - 1);
        }
        return String.format("public/%s/%s_%s.json",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId());
    }

    /**
     * 构造重建书签后上传到临时桶时使用的 object key（与 {@link #buildJsonObjectKey} 格式
     * 一致，但不需要 PDF 文件名参数）。
     */
    private static String buildJsonObjectKeyForRebuild(OssUploadConfig ossConfig) {
        return String.format("public/%s/%s_%s.json",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId());
    }

    /**
     * 解析 _ocr.json 的本地绝对路径；若文件不存在则返回空字符串。
     */
    private static String resolveOcrJsonLocalPath(String outputFolder, String pdfFileName) {
        String baseName = pdfFileName;
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        if (baseName.endsWith(".")) {
            baseName = baseName.substring(0, baseName.length() - 1);
        }
        String ocrFileName = outputFolder + File.separator + baseName + "_ocr.json";
        File ocrFile = new File(ocrFileName);
        return ocrFile.exists() ? ocrFile.getAbsolutePath() : "";
    }

    /**
     * OSS 上传成功后清理本地生成文件。
     *
     * <p>保留 {@code ocrJsonLocalPath}（如果存在），只删除输出目录下与当前 PDF
     * 文件名相关的生成文件，不影响其他 PDF 产生的文件。{@code ocrJsonLocalPath}
     * 为空字符串时表示没有生成 _ocr.json。
     * 原始输入 PDF 不在这里删除，由上层在关闭 PDF 资源后处理。</p>
     */
    private static void cleanupLocalFiles(String outputFolder, String pdfFileName, String ocrJsonLocalPath)
            throws IOException {
        Path outputPath = Paths.get(outputFolder);
        if (!Files.exists(outputPath)) {
            return;
        }

        String pdfBaseName = pdfFileName;
        if (pdfBaseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
        }
        if (pdfBaseName.endsWith(".")) {
            pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 1);
        }
        final String baseName = pdfBaseName;
        final String imageDirName = baseName + MarkdownSyntax.IMAGES_DIRECTORY_SUFFIX;
        final Path ocrPath = ocrJsonLocalPath.isEmpty() ? null : Paths.get(ocrJsonLocalPath).toAbsolutePath().normalize();
        final String ocrFileName = ocrPath != null ? ocrPath.getFileName().toString() : null;

        Files.walkFileTree(outputPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                if (ocrPath != null && normalized.equals(ocrPath)) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = normalized.getFileName().toString();
                if (fileName.equals(ocrFileName)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isRelatedToCurrentPdf(fileName, baseName)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path normalized = dir.toAbsolutePath().normalize();
                if (normalized.equals(outputPath.toAbsolutePath().normalize())) {
                    return FileVisitResult.CONTINUE;
                }
                String dirName = normalized.getFileName().toString();
                if (dirName.equals(imageDirName)) {
                    // 直接删除当前 PDF 的图片目录及其内容，不再继续遍历该目录
                    deleteDirectoryTree(normalized);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!isRelatedToCurrentPdf(dirName, baseName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Path normalized = dir.toAbsolutePath().normalize();
                if (normalized.equals(outputPath.toAbsolutePath().normalize())) {
                    return FileVisitResult.CONTINUE;
                }
                String dirName = normalized.getFileName().toString();
                if (isRelatedToCurrentPdf(dirName, baseName)) {
                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectoryTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
            .sorted(Collections.reverseOrder())
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    private static boolean isRelatedToCurrentPdf(String name, String pdfBaseName) {
        return name.equals(pdfBaseName + ".json")
            || name.equals(pdfBaseName + ".js")
            || name.equals(pdfBaseName + ".html")
            || name.equals(pdfBaseName + "_page_bookmarks_collected.md")
            || name.startsWith(pdfBaseName + "_");
    }

    public static void writeToJson(File inputPDF, String outputFolder, List<List<IObject>> contents,
                                   Map<Long, ElementMetadata> elementMetadata,
                                   Map<String, Object> hybridInfo,
                                   boolean includeHeaderFooter) throws IOException {
        StaticLayoutContainers.resetImageIndex();
        String jsonFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "json";
        try (JsonGenerator jsonGenerator = getJsonGenerator(jsonFileName)) {
            jsonGenerator.writeStartObject();
            writeDocumentInfo(jsonGenerator, inputPDF.getName());

            if (hybridInfo != null && !hybridInfo.isEmpty()) {
                writeHybridBlock(jsonGenerator, hybridInfo);
            }

            SerializerUtil.setElementMetadata(elementMetadata);
            try {
                jsonGenerator.writeArrayFieldStart(JsonName.KIDS);
                for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                    for (IObject content : contents.get(pageNumber)) {
                        if (content instanceof LineArtChunk) {
                            continue;
                        }
                        if (!includeHeaderFooter && content instanceof SemanticHeaderOrFooter) {
                            continue;
                        }
                        jsonGenerator.writePOJO(content);
                    }
                }
                jsonGenerator.writeEndArray();
            } finally {
                SerializerUtil.clearElementMetadata();
            }

            jsonGenerator.writeEndObject();
            LOGGER.log(Level.INFO, "Created {0}", jsonFileName);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Unable to create JSON output: " + ex.getMessage());
        }
    }

    private static void writeHybridBlock(JsonGenerator generator, Map<String, Object> hybridInfo) throws IOException {
        generator.writeObjectFieldStart(JsonName.HYBRID);
        for (Map.Entry<String, Object> entry : hybridInfo.entrySet()) {
            generator.writePOJOField(entry.getKey(), entry.getValue());
        }
        generator.writeEndObject();
    }

    private static void writeDocumentInfo(JsonGenerator generator, String pdfName) throws IOException {
        PDDocument document = StaticResources.getDocument();
        generator.writeStringField(JsonName.FILE_NAME, pdfName);
        generator.writeNumberField(JsonName.NUMBER_OF_PAGES, document.getNumberOfPages());
        COSTrailer trailer = document.getDocument().getTrailer();
        COSObject object = trailer.getKey(ASAtom.INFO);
        GFCosInfo info = new GFCosInfo((COSDictionary)
                (object != null && object.getType() == COSObjType.COS_DICT ?
                        object.getDirectBase() : COSDictionary.construct().get()));
        generator.writeStringField(JsonName.AUTHOR, info.getAuthor() != null ? info.getAuthor() : info.getXMPCreator());
        generator.writeStringField(JsonName.TITLE, info.getTitle() != null ? info.getTitle() : info.getXMPTitle());
        generator.writeStringField(JsonName.CREATION_DATE, info.getCreationDate() != null ?
                info.getCreationDate() : info.getXMPCreateDate());
        generator.writeStringField(JsonName.MODIFICATION_DATE, info.getModDate() != null ?
                info.getModDate() : info.getXMPModifyDate());
    }
}
