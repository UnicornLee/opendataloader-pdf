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
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.constants.GlobalConstant;
import org.opendataloader.pdf.custom.dto.PageItem;
import org.opendataloader.pdf.custom.dto.TableSingleItem;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.custom.utils.BookmarkUtils;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.custom.utils.FileUtils;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.processors.DocumentProcessor;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public static void writeToJCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                   Map<Long, ElementMetadata> elementMetadata,
                                   Map<String, Object> hybridInfo,
                                   boolean includeHeaderFooter) throws IOException {
        StaticLayoutContainers.resetImageIndex();
        File inputPDF = new File(inputPdfName);
        String jsonFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "json";
        try (JsonGenerator jsonGenerator = getJsonGenerator(jsonFileName)) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("url", inputPdfName);
            jsonGenerator.writeArrayFieldStart("bookmarks");
            for (Bookmark bookmark : BookmarkUtils.getSelfBookmarks(inputPdfName)) {
                jsonGenerator.writePOJO(bookmark);
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeArrayFieldStart("catalog_bookmarks");
            for (Bookmark bookmark : StaticLayoutContainers.getCatalogBookmarks()) {
                jsonGenerator.writePOJO(bookmark);
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeArrayFieldStart("page_bookmarks");
            for (Bookmark bookmark : StaticLayoutContainers.getPageBookmarks()) {
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
                    List<Double> leftXList = pageContents.stream().map(IObject::getLeftX).collect(Collectors.toList());
                    List<Double> rightXList = pageContents.stream().map(IObject::getRightX).collect(Collectors.toList());
                    List<Double> topYList = pageContents.stream().map(IObject::getTopY).collect(Collectors.toList());
                    List<Double> bottomYList = pageContents.stream().map(IObject::getBottomY).collect(Collectors.toList());
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
                    final double[] prevBottomY = {pageContents.get(0).getTopY()};
                    for (IObject content : pageContents) {
                        if (content instanceof LineArtChunk) {
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
                        /*if (content instanceof ShapeChunk) {
                            jsonGenerator.writeObject(content);
                        }*/
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
        FileUtils.copyResourceToDir("templates/index.css", outputFolder);
        String jsFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "js";
        String jsFileContent = "var url = " + mapper.writeValueAsString(inputPdfName) + ";";
        jsFileContent += "\n\n";
        jsFileContent += "var bookmarks = " + mapper.writeValueAsString(map.get("catalog_bookmarks")) + ";";
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
