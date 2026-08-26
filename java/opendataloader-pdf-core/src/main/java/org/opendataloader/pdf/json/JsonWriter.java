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
import org.jetbrains.annotations.NotNull;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JsonWriter {
    private static final Logger LOGGER = Logger.getLogger(JsonWriter.class.getCanonicalName());

    /**
     * Matches an {@code <img>} tag and captures its {@code src} value.
     * Group 1 is the opening quote, group 2 is the path/URL.
     */
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "<img\\b[^>]*?\\bsrc\\s*=\\s*(['\"])(.*?)\\1[^>]*?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        return writeToCustomJson(inputPdfName, outputFolder, contents, elementMetadata, hybridInfo, includeHeaderFooter, null, null);
    }

    /**
     * Reads {@code self_bookmarks} from an existing JSON file (falling back to {@code bookmarks}
     * and then to an empty list), reuses the catalog detection / page-bookmark extraction /
     * quality-selection pipelines from {@link #writeToCustomJson(String, String, List, Map, Map, boolean, Config)},
     * and regenerates {@code catalog_bookmarks}, {@code page_bookmarks} and {@code bookmarks},
     * writing them back to the JSON file. Whether the result is kept locally or uploaded to the
     * OBS temp bucket depends on whether {@code customOptions} contains the eight OSS config keys
     * (excluding {@code ossPermanentBucketName}).
     *
     * <p>Only bookmark-related fields ({@code self_bookmarks}, {@code catalog_bookmarks},
     * {@code page_bookmarks}, {@code bookmarks}, {@code catalog_page_range_start/end}) are refreshed;
     * all other fields ({@code url}, {@code data}, {@code extend}, {@code is_ocr}, etc.) are preserved.
     * No image upload, OCR detection, renaming or moving of the JSON file is performed.</p>
     *
     * @param inputJsonName absolute path of the JSON file to rebuild
     * @param config        configuration object; customOptions drive whether OSS upload is enabled
     * @return {@link RebuildBookmarksResult} containing the OBS URL or local absolute path and whether upload succeeded
     * @throws IOException when reading, writing or uploading the JSON fails
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
                    LOGGER.log(Level.WARNING, "Failed to close OBS client: " + closeEx.getClass().getSimpleName() + ": " + closeEx.getMessage(), closeEx);
                }
            }
        }
    }

    public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                          Map<Long, ElementMetadata> elementMetadata,
                                          Map<String, Object> hybridInfo,
                                          boolean includeHeaderFooter,
                                          Config config) throws IOException {
        return writeToCustomJson(inputPdfName, outputFolder, contents, elementMetadata, hybridInfo, includeHeaderFooter, config, null);
    }

    public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                          Map<Long, ElementMetadata> elementMetadata,
                                          Map<String, Object> hybridInfo,
                                          boolean includeHeaderFooter,
                                          Config config,
                                          boolean[] pageHaveStreamTables) throws IOException {
        return writeToCustomJson(inputPdfName, outputFolder, contents, elementMetadata, hybridInfo,
            includeHeaderFooter, config, pageHaveStreamTables, null);
    }

    public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                          Map<Long, ElementMetadata> elementMetadata,
                                          Map<String, Object> hybridInfo,
                                          boolean includeHeaderFooter,
                                          Config config,
                                          boolean[] pageHaveStreamTables,
                                          boolean[] pageHaveFormulas) throws IOException {
        StaticLayoutContainers.resetImageIndex();
        File inputPDF = new File(inputPdfName);
        String jsonFileName = outputFolder + File.separator + inputPDF.getName().substring(0, inputPDF.getName().length() - 3) + "json";
        String url = inputPdfName;
        if (config != null && config.getCustomOptions() != null
                && config.getCustomOptions().containsKey("url")
                && !"".equals(config.getCustomOptions().get("url"))) {
            url = (String) config.getCustomOptions().get("url");
        }
        try (JsonGenerator jsonGenerator = getJsonGenerator(jsonFileName)) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("url", url);
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
                JsonFactory pageJsonFactory = new JsonFactory();
                for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                    // Serialize each page to an independent in-memory buffer first;
                    // only after the whole page succeeds (balanced and closed) is it
                    // appended to the main stream. A per-page exception affects only
                    // that page (falls back to a minimal placeholder), while the main
                    // JsonGenerator stays inside the DATA array context, so subsequent
                    // pages continue uninterrupted and the overall JSON structure stays valid.
                    String pageJson;
                    try {
                        ByteArrayOutputStream pageBuffer = new ByteArrayOutputStream();
                        try (JsonGenerator pageGenerator = pageJsonFactory.createGenerator(pageBuffer, JsonEncoding.UTF8)
                                .setCodec(ObjectMapperHolder.getObjectMapper())) {
                            writePageToGenerator(pageNumber, includeHeaderFooter, contents, pageGenerator, config, pageHaveStreamTables, pageHaveFormulas);
                        }
                        // close() (via try-with-resources) flushes the generator's
                        // internal buffer, so the bytes here are the complete page JSON.
                        pageJson = new String(pageBuffer.toByteArray(), StandardCharsets.UTF_8);
                    } catch (Exception pageEx) {
                        // Note: on JDK 11 JUL's log(Level, String, Object...) does not treat
                        // a trailing Throwable as the thrown cause (LogRecord.setParameters has
                        // no such logic). Use the log(Level, String, Throwable) overload to
                        // ensure the stack trace is printed.
                        LOGGER.log(Level.WARNING,
                            inputPdfName + " - Error when generating JSON data of page " + (pageNumber + 1)
                                + ": fallback to empty page: " + pageEx.getClass().getSimpleName() + ": " + pageEx.getMessage(),
                            pageEx);
                        // Minimal placeholder page: keeps page numbers contiguous with an empty items list.
                        pageJson = placeholderPageJson(pageNumber + 1);
                    }
                    jsonGenerator.writeRawValue(pageJson);
                }
                jsonGenerator.writeEndArray();
            } catch (Exception e2) {
                LOGGER.log(Level.WARNING,
                    inputPdfName + " - Error when generating content JSON data: "
                        + e2.getClass().getSimpleName() + ": " + e2.getMessage(),
                    e2);
            } finally {
                // Clear document-level metadata only once after serialization finishes to avoid ThreadLocal leaks;
                // never clear inside the loop (including on exception pages), or enrichment fields would be lost
                // from the second page onward.
                SerializerUtil.clearElementMetadata();
            }

            jsonGenerator.writeEndObject();
            LOGGER.log(Level.INFO, "Created {0}", jsonFileName);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                inputPdfName + " - Error when generating JSON data: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                ex);
        }

        // Re-read the generated JSON file into memory.
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(
            new File(jsonFileName),
            new TypeReference<Map<String, Object>>() {}
        );

        // Parse OSS parameters from the configuration to decide whether object storage is enabled.
        OssUploadConfig ossConfig = OssUploadConfig.fromCustomOptions(config);
        boolean ossEnabled = ossConfig.isEnabled();
        HuaweiObsClient obsClient = null;
        if (ossEnabled) {
            obsClient = new HuaweiObsClient(ossConfig.getEndpoint(), ossConfig.getAccessKey(), ossConfig.getSecretKey());
        }

        // Walk every page and upload images to the permanent bucket, replacing local paths with OSS URLs.
        try {
            if (ossEnabled) {
                uploadImagesToOssAndUpdateMap(map, outputFolder, obsClient, ossConfig);
            }

            // Scan every page for OCR-eligible pages, write them to <pdfname>_ocr.json,
            // and mark is_ocr=true on hit pages (persisted to the main JSON later when bookmarks are written back).
            try {
                writeOcrDetectionJson(mapper, map, outputFolder, config,
                    pageHaveStreamTables, pageHaveFormulas, ossEnabled, ossConfig, obsClient, inputPdfName);
            } catch (Exception ocrEx) {
                LOGGER.log(Level.WARNING, "Unable to create OCR detection JSON: " + ocrEx.getClass().getSimpleName() + ": " + ocrEx.getMessage(), ocrEx);
            }

            // Identify catalog_bookmarks and page_bookmarks from the already-generated JSON data;
            // page_bookmarks reuse JSON item ids as their relatedId.
            if (config != null) {
                // Track the source key selected by BookmarkQualitySelector; when nothing is selected (null) all three original keys are kept.
                String selectedSource = null;
                List<Map<String, Object>> data = (List<Map<String, Object>>) map.get(JsonName.DATA);
                if (data != null) {
                    resolveSelfBookmarkRelatedIds(mapper, map, data);
                    repairSelfBookmarkRelatedPageNums(mapper, map, data);
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

                    // Select the highest-quality catalog from catalog/page/self sources and write it as bookmarks.
                    List<Bookmark> selfBookmarks = mapper.convertValue(
                        map.get("self_bookmarks"), new TypeReference<List<Bookmark>>() {});
                    Map<Integer, Set<Integer>> pageItemIds = BookmarkQualitySelector.buildPageItemIds(data);
                    BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
                        catalogBookmarks, pageBookmarks, selfBookmarks, pageItemIds);
                    map.put("bookmarks", selection.getBookmarks());
                    // Remove only the source that was selected; unselected sources (or all sources when selectedSource is null) remain in the map so they are still written to json.
                    selectedSource = selection.getSource();
                } else {
                    map.put("bookmarks", new ArrayList<>());
                    // When data is empty no source selection can be performed; keep all three original keys in the output.
                }
                if (selectedSource != null) {
                    map.remove(selectedSource);
                }

                // Write the updated content back to the JSON file.
                mapper.writerWithDefaultPrettyPrinter().writeValue(new File(jsonFileName), map);
            }

            // Upload the main JSON to the temp bucket and build the return result.
            String jsonUrlOrPath;
            String ocrJsonLocalPath = resolveOcrJsonLocalPath(outputFolder, inputPDF.getName());
            boolean ossUploadSuccess = false;
            if (ossEnabled) {
                String jsonObjectKey = buildJsonObjectKey(ossConfig, inputPDF.getName());
                jsonUrlOrPath = obsClient.uploadFile(ossConfig.getTempBucketName(), jsonObjectKey, new File(jsonFileName), ossConfig.getTempDomainName());
                LOGGER.log(Level.INFO, "Uploaded main JSON to OBS: {0}", jsonUrlOrPath);
                ossUploadSuccess = true;

                // After a successful upload clean up local files: keep _ocr.json, delete the other PDF-related generated files under outputFolder.
                cleanupLocalFiles(outputFolder, inputPDF.getName(), ocrJsonLocalPath);
            } else {
                // Generate auxiliary html/js/css files for local output.
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
                // Read templates/announcementAnalysis.html line by line and write it to htmlFileName line by line.
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
                    LOGGER.log(Level.WARNING, "Failed to close OBS client: " + closeEx.getClass().getSimpleName() + ": " + closeEx.getMessage(), closeEx);
                }
            }
        }
    }

    /**
     * Serializes a single page into a complete JSON object on the caller-supplied,
     * independent-memory JsonGenerator (page_index / width / height / margins / items).
     * Any exception is propagated upward so the caller can discard this page's buffer
     * without affecting the main JSON stream structure.
     */
    private static void writePageToGenerator(int pageNumber, boolean includeHeaderFooter,
                                            List<List<IObject>> contents, JsonGenerator pageGenerator,
                                             Config config, boolean[] pageHaveStreamTables,
                                             boolean[] pageHaveFormulas) throws IOException {
        boolean isHk = false;
        if (config != null && config.getCustomOptions() != null && config.getCustomOptions().containsKey("pulsarReceiveTopicName")) {
            String pulsarReceiveTopicName = config.getCustomOptions().get("pulsarReceiveTopicName").toString();
            if (pulsarReceiveTopicName.startsWith("hk") || pulsarReceiveTopicName.endsWith("hk")) {
                isHk = true;
            }
        }
        pageGenerator.writeStartObject();
        pageGenerator.writeNumberField(JsonName.PAGE_INDEX, pageNumber + 1);
        BoundingBox pageBoundingBox = DocumentProcessor.getPageBoundingBox(pageNumber);
        double width = pageBoundingBox.getWidth();
        double height = pageBoundingBox.getHeight();
        pageGenerator.writeNumberField(JsonName.WIDTH, width);
        pageGenerator.writeNumberField(JsonName.HEIGHT, height);
        pageGenerator.writeBooleanField(JsonName.IS_OCR, false);
        pageGenerator.writeBooleanField(JsonName.HAVE_STREAM_TABLE,
            pageHaveStreamTables != null && pageHaveStreamTables[pageNumber]);
        pageGenerator.writeBooleanField(JsonName.HAVE_FORMULA,
            pageHaveFormulas != null && pageHaveFormulas[pageNumber]);
        List<IObject> pageContents = contents.get(pageNumber);
        // 对 pageContents 按照 topY 从大到小排序
        pageContents.sort(Comparator.comparingDouble(item -> item.getTopY()));
        Collections.reverse(pageContents);
        Double[] headerPos = null;
        Double[] footerPos = pageContents.size() > 1 ? headerFooterPos(pageContents.get(pageContents.size() - 1), height) : null;
        if (isHk) {
            pageContents = flattenHeaderFooterContents(pageContents);
        } else {
            headerPos = pageContents.isEmpty() ? null : headerFooterPos(pageContents.get(0), height);
        }
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
        pageGenerator.writeNumberField(JsonName.MARGIN_LEFT, minX);
        pageGenerator.writeNumberField(JsonName.MARGIN_RIGHT, width - maxX);
        pageGenerator.writeNumberField(JsonName.MARGIN_TOP, height - maxY);
        writePosArray(pageGenerator, JsonName.HEADER_POS, headerPos);
        writePosArray(pageGenerator, JsonName.FOOTER_POS, footerPos);
        pageGenerator.writeArrayFieldStart(JsonName.ITEMS);
        generateJsonPageContentData(includeHeaderFooter, height, pageContents, pageGenerator);
        pageGenerator.writeEndArray();
        pageGenerator.writeEndObject();
    }

    /**
     * Walks the page contents and expands only the first {@link SemanticHeaderOrFooter}
     * encountered — replacing it in-place with {@code getContents()} while preserving
     * relative order. Any later {@code SemanticHeaderOrFooter} on the same page is kept
     * unchanged, as are all other elements.
     *
     * <p>This lets the real header/footer contents reach their own type-specific
     * serialization branches in {@link #generateJsonPageContentData}; the header/footer
     * wrapper itself has no dedicated branch there and would otherwise be dropped entirely.</p>
     */
    private static List<IObject> flattenHeaderFooterContents(List<IObject> pageContents) {
        List<IObject> flattened = new ArrayList<>(pageContents.size());
        boolean firstHeaderOrFooterSeen = false;
        for (IObject content : pageContents) {
            if (content instanceof SemanticHeaderOrFooter && !firstHeaderOrFooterSeen) {
                // Process only the first header/footer seen on the page, even if its contents are empty;
                // subsequent headers/footers are kept unchanged.
                firstHeaderOrFooterSeen = true;
                List<IObject> contents = ((SemanticHeaderOrFooter) content).getContents();
                if (contents != null && !contents.isEmpty()) {
                    flattened.addAll(contents);
                    continue;
                }
                // Fallback when contents is empty: keep the header/footer element unchanged.
            }
            flattened.add(content);
        }
        return flattened;
    }

    /**
     * Builds the 4-element bounding-box array for a header/footer content:
     * {@code [leftX, height - topY, rightX, height - bottomY]} (PDF coords flipped to top-left origin).
     * Returns {@code null} when the content is not a {@link SemanticHeaderOrFooter}.
     */
    private static Double[] headerFooterPos(IObject content, double height) {
        if (!(content instanceof SemanticHeaderOrFooter)) {
            return null;
        }
        BoundingBox box = ((SemanticHeaderOrFooter) content).getBoundingBox();
        return new Double[]{
                box.getLeftX(),
                height - box.getTopY(),
                box.getRightX(),
                height - box.getBottomY()
        };
    }

    /**
     * Writes a header/footer position array as a JSON array field.
     * Skips the field entirely when {@code pos} is {@code null} (no header/footer detected).
     */
    private static void writePosArray(JsonGenerator gen, String fieldName, Double[] pos) throws IOException {
        if (pos == null) {
            return;
        }
        gen.writeArrayFieldStart(fieldName);
        for (Double value : pos) {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeNumber(value);
            }
        }
        gen.writeEndArray();
    }

    /**
     * Minimal placeholder page written when page serialization fails: keeps
     * the page index and an empty items array so the {@code data} array has
     * the same length as the PDF and page_index stays contiguous.
     */
    private static String placeholderPageJson(int pageIndex) throws IOException {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put(JsonName.PAGE_INDEX, pageIndex);
        placeholder.put(JsonName.IS_OCR, false);
        placeholder.put(JsonName.HAVE_STREAM_TABLE, false);
        placeholder.put(JsonName.HAVE_FORMULA, false);
        placeholder.put(JsonName.ITEMS, Collections.emptyList());
        return ObjectMapperHolder.getObjectMapper().writeValueAsString(placeholder);
    }

    private static void generateJsonPageContentData(boolean includeHeaderFooter, double height, List<IObject> pageContents, JsonGenerator jsonGenerator) throws IOException {
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
                            String lineText = getText(line.getTextChunks());
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
                        String lineText = getText(line.getTextChunks());
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
                            String lineText = getText(line.getTextChunks());
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
                            String lineText = getText(line.getTextChunks());
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
                    String lineText = getText(textLine.getTextChunks());
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
                            if (j >= cellRadios.length) {
                                break;
                            }
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
    }

    private static String getText(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return "";
        }
        String text = "";
        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk chunk = textChunks.get(i);
            String val = chunk.getValue();
            if (i > 0) {
                TextChunk prevTextChunk = textChunks.get(i - 1);
                text += getSpaceStr(chunk.getLeftX() - prevTextChunk.getRightX(),
                    chunk.getFontSize() > prevTextChunk.getFontSize() ? chunk.getFontSize() : prevTextChunk.getFontSize());
            }
            if ("".equals(val.trim())) {
                text += getSpaceStr(chunk);
            } else if (GlobalConstant.SPECIAL_CHARACTER_ORIGIN.contains(val)) {
                text += GlobalConstant.SPECIAL_CHARACTER_TARGET.get(GlobalConstant.SPECIAL_CHARACTER_ORIGIN.indexOf(val));
            } else {
                text += val;
            }
        }
        return text.replaceAll("</sup><sup>", "").replaceAll("</sub><sub>", "");
    }

    @NotNull
    private static String getSpaceStr(TextChunk chunk) {
        double ratio = (chunk.getRightX() - chunk.getLeftX()) / chunk.getFontSize();
        if (ratio < 0.4) {
            return "";
        } else if (ratio < 1) {
            return " ";
        } else {
            return " ".repeat((int) Math.ceil(ratio));
        }
    }

    private static String getSpaceStr(double width, double fontSize) {
        if (fontSize == 0 || width / fontSize < 0.4) {
            return "";
        } else if (width / fontSize < 1) {
            return " ";
        } else {
            return " ".repeat((int) Math.ceil(width / fontSize));
        }
    }

    /**
     * Collects page bookmark candidates from JSON data and writes a debug markdown file.
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
     * Computes the y-range to clip a stream-table / formula screenshot to, based on the page's
     * {@code header_pos} / {@code footer_pos} (4-element arrays: {@code [left, top, right, bottom]}
     * in top-left PDF coordinates).
     *
     * <ul>
     *   <li>Both present → {@code [header_pos[3], footer_pos[1]]} (drop both bands)</li>
     *   <li>Only header_pos → {@code [header_pos[3], pageHeight]} (drop header band only)</li>
     *   <li>Only footer_pos → {@code [0, footer_pos[1]]} (drop footer band only)</li>
     *   <li>Neither present or malformed → {@code null} (render full page)</li>
     * </ul>
     *
     * <p>The x-axis is always preserved in full; only the y-range is adjusted.</p>
     */
    private static double[] computeClipY(Map<String, Object> page) {
        Object pageHeightObj = page.get(JsonName.HEIGHT);
        if (!(pageHeightObj instanceof Number)) {
            return null;
        }
        double pageHeight = ((Number) pageHeightObj).doubleValue();
        if (pageHeight <= 0) {
            return null;
        }
        Double[] headerPos = readPosArray(page.get(JsonName.HEADER_POS));
        Double[] footerPos = readPosArray(page.get(JsonName.FOOTER_POS));
        if (headerPos != null && footerPos != null) {
            return new double[]{headerPos[3], footerPos[1]};
        }
        if (headerPos != null) {
            return new double[]{headerPos[3], pageHeight};
        }
        if (footerPos != null) {
            return new double[]{0.0, footerPos[1]};
        }
        return null;
    }

    /**
     * Reads a {@code header_pos} / {@code footer_pos} value back from the JSON page map and
     * materializes it as a 4-element {@code Double[]}. Returns {@code null} if the value is
     * missing, not a list, shorter than 4, or contains any non-numeric element — all of
     * those cases are treated as "no usable position" by {@link #computeClipY}.
     */
    private static Double[] readPosArray(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<?> list = (List<?>) value;
        if (list.size() < 4) {
            return null;
        }
        Double[] result = new Double[4];
        for (int i = 0; i < 4; i++) {
            Object element = list.get(i);
            if (!(element instanceof Number)) {
                return null;
            }
            result[i] = ((Number) element).doubleValue();
        }
        return result;
    }

    /**
     * Reads {@code width} / {@code height} off a page map and returns them as a 2-element
     * {@code double[]}: {@code [width, height]}. Missing or non-numeric values become 0.0.
     * Used by all three screenshot passes (stream-table / formula / image-hit) so they
     * share one definition of "page size" for entry size reporting.
     */
    private static double[] readPageDimensions(Map<String, Object> page) {
        double pageWidth = 0.0;
        double pageHeight = 0.0;
        Object pageHeightObj = page.get(JsonName.HEIGHT);
        if (pageHeightObj instanceof Number) {
            pageHeight = ((Number) pageHeightObj).doubleValue();
        }
        Object pageWidthObj = page.get(JsonName.WIDTH);
        if (pageWidthObj instanceof Number) {
            pageWidth = ((Number) pageWidthObj).doubleValue();
        }
        return new double[]{pageWidth, pageHeight};
    }

    /**
     * Scans every page of the main JSON and, for pages that satisfy the OCR conditions:
     * <ul>
     *     <li>sets {@code is_ocr} to {@code true} on the page object (persisted to the main JSON when bookmarks are written back)</li>
     *     <li>aggregates hit pages and writes them to {@code <pdfname>_ocr.json} (compact JSON matching the sample format)</li>
     * </ul>
     *
     * <p>OCR hit conditions:
     * <ol>
     *     <li>the page has no more than 4 items</li>
     *     <li>the page contains no lattice_table / stream_table</li>
     *     <li>the page contains at least one image</li>
     *     <li>some image on the page has {@code height / page.height > 0.8} (when several images qualify, the one with the largest ratio is used)</li>
     * </ol>
     *
     * @param mapper       ObjectMapper already used to read/write the main JSON
     * @param map          in-memory Map deserialized from the main JSON (its {@code data} list is updated in-place with is_ocr)
     * @param outputFolder output directory (same as the main JSON)
     * @param pdfFileName  original PDF file name (with extension), used to derive {@code <pdfname>_ocr.json}
     */
    private static void writeOcrDetectionJson(ObjectMapper mapper,
                                              Map<String, Object> map,
                                              String outputFolder,
                                              Config config,
                                              boolean[] pageHaveStreamTables,
                                              boolean[] pageHaveFormulas,
                                              boolean ossEnabled,
                                              OssUploadConfig ossConfig,
                                              HuaweiObsClient obsClient,
                                              String inputPdfName) throws IOException {
        Object dataObj = map.get(JsonName.DATA);
        if (!(dataObj instanceof List)) {
            return;
        }
        String pdfFileName = new File(inputPdfName).getName();
        List<Map<String, Object>> data = (List<Map<String, Object>>) dataObj;
        List<Map<String, Object>> ocrEntries = new ArrayList<>();

        // Pass 1: pages flagged with have_stream_table=true are rendered to PNG and
        // uploaded to the temp bucket (when OSS is enabled), then emitted as their own
        // entries in _ocr.json with have_stream_table=true so downstream OCR tooling
        // can distinguish stream-table-triggered entries from image-triggered ones.
        if (pageHaveStreamTables != null) {
            // inputPdfName is the absolute path of the local PDF after download; using it
            // directly avoids accidentally picking up the cloud url stored in map("url").
            File pdfFile = new File(inputPdfName);
            String pdfBaseName = pdfFileName;
            if (pdfBaseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
            }
            if (pdfBaseName.endsWith(".")) {
                pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 1);
            }
            for (int i = 0; i < pageHaveStreamTables.length && i < data.size(); i++) {
                if (!pageHaveStreamTables[i]) {
                    continue;
                }
                Map<String, Object> page = data.get(i);
                double[] dims = readPageDimensions(page);
                double pageWidth = dims[0];
                double pageHeight = dims[1];
                double[] clipY = computeClipY(page);
                String imageUrl = renderStreamTablePageScreenshot(pdfFile, outputFolder, pdfBaseName, i,
                    ossEnabled, ossConfig, obsClient, clipY);
                if (imageUrl == null) {
                    continue;
                }
                // Mark is_ocr on the page (persisted to the main JSON when bookmarks are
                // written back); also record that this page already produced an entry so the
                // image-hit pass below skips it.
                page.put(JsonName.IS_OCR, true);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(JsonName.PAGE_INDEX, page.get(JsonName.PAGE_INDEX));
                entry.put("image_url", imageUrl);
                // image_width/image_height describe the PDF region captured: x stays full page,
                // y is the clip range (header/footer excluded) — falls back to the full page when
                // neither header_pos nor footer_pos is available.
                double capturedHeight = clipY != null ? (clipY[1] - clipY[0]) : pageHeight;
                entry.put("image_height", capturedHeight);
                entry.put("image_width", pageWidth);
                ocrEntries.add(entry);
            }
        }

        // Pass 1.5: pages flagged with have_formula=true are rendered to PNG and uploaded to
        // the temp bucket (when OSS is enabled), then emitted as their own entries with
        // have_formula=true. Mutually exclusive with Pass 1 (stream-table): if a page already
        // got a stream-table entry it is skipped here so consumers see at most one image
        // entry per detected page.
        if (pageHaveFormulas != null) {
            // Reuse the same inputPdfName/pdFBasename derivation as Pass 1.
            File pdfFile = new File(inputPdfName);
            String pdfBaseName = pdfFileName;
            if (pdfBaseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
            }
            if (pdfBaseName.endsWith(".")) {
                pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 1);
            }
            for (int i = 0; i < pageHaveFormulas.length && i < data.size(); i++) {
                if (!pageHaveFormulas[i]) {
                    continue;
                }
                // Mutex: a page already served by Pass 1 (stream-table) keeps that entry.
                if (pageHaveStreamTables != null && i < pageHaveStreamTables.length && pageHaveStreamTables[i]) {
                    continue;
                }
                Map<String, Object> page = data.get(i);
                double[] dims = readPageDimensions(page);
                double pageWidth = dims[0];
                double pageHeight = dims[1];
                double[] clipY = computeClipY(page);
                String imageUrl = renderFormulaPageScreenshot(pdfFile, outputFolder, pdfBaseName, i,
                    ossEnabled, ossConfig, obsClient, clipY);
                if (imageUrl == null) {
                    continue;
                }
                page.put(JsonName.IS_OCR, true);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(JsonName.PAGE_INDEX, page.get(JsonName.PAGE_INDEX));
                entry.put("image_url", imageUrl);
                double capturedHeight = clipY != null ? (clipY[1] - clipY[0]) : pageHeight;
                entry.put("image_height", capturedHeight);
                entry.put("image_width", pageWidth);
                ocrEntries.add(entry);
            }
        }

        // Pass 2: pages dominated by a single large image (height / page.height > 0.8) with no
        // tables and few other items. Renders a fresh screenshot of the page (clipped to
        // header/footer bands when header_pos / footer_pos is available) and writes it as the
        // entry's image_url — the embedded image in the page is no longer used directly.
        // Pages already covered by Pass 1 / Pass 1.5 are skipped so consumers see at most one
        // image entry per page.
        File pdfFile = new File(inputPdfName);
        String pdfBaseName = pdfFileName;
        if (pdfBaseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
        }
        if (pdfBaseName.endsWith(".")) {
            pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 1);
        }
        for (int i = 0; i < data.size(); i++) {
            // Skip pages already served by Pass 1 / Pass 1.5 so consumers see at most one
            // image entry per page.
            if (pageHaveStreamTables != null && i < pageHaveStreamTables.length && pageHaveStreamTables[i]) {
                continue;
            }
            if (pageHaveFormulas != null && i < pageHaveFormulas.length && pageHaveFormulas[i]) {
                continue;
            }
            Map<String, Object> page = data.get(i);
            Object itemsObj = page.get(JsonName.ITEMS);
            if (!(itemsObj instanceof List)) {
                continue;
            }
            List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;

            // Condition 1: at most 4 items on the page.
            if (items.size() > 4) {
                continue;
            }

            // Condition 2: no tables.
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

            // Conditions 3 + 4: contains an image and some image has height / page.height > 0.8.
            Object pageHeightObj = page.get(JsonName.HEIGHT);
            if (!(pageHeightObj instanceof Number)) {
                continue;
            }
            double pageHeight = ((Number) pageHeightObj).doubleValue();
            if (pageHeight <= 0) {
                continue;
            }

            boolean hasLargeImage = false;
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
                if (imageHeight / pageHeight > 0.8) {
                    hasLargeImage = true;
                    break;
                }
            }
            if (!hasLargeImage) {
                continue;
            }

            // Hit: mark is_ocr on the page and render a fresh screenshot (clipped to
            // header/footer bands) so OCR gets the cleaned-up page rather than the embedded image.
            page.put(JsonName.IS_OCR, true);

            double[] dims = readPageDimensions(page);
            double pageWidth = dims[0];
            double[] clipY = computeClipY(page);
            String imageUrl = renderOcrPageScreenshot(pdfFile, outputFolder, pdfBaseName, i,
                ossEnabled, ossConfig, obsClient, clipY);
            if (imageUrl == null) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(JsonName.PAGE_INDEX, page.get(JsonName.PAGE_INDEX));
            entry.put("image_url", imageUrl);
            double capturedHeight = clipY != null ? (clipY[1] - clipY[0]) : pageHeight;
            entry.put("image_height", capturedHeight);
            entry.put("image_width", pageWidth);
            ocrEntries.add(entry);
        }

        // Skip writing when no page hit, to avoid producing an empty _ocr.json.
        if (ocrEntries.isEmpty()) {
            LOGGER.log(Level.INFO, "No OCR pages detected, skip creating _ocr.json for {0}", pdfFileName);
            return;
        }

        // business_id / extend come from config.customOptions (keys businessId / extend respectively),
        // falling back to the original defaults ("None" / empty object) when absent to keep backward compatibility.
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

        // File name: same prefix as the main JSON (drop the trailing dot left over from length()-3), suffix changed to "_ocr.json".
        String ocrBaseName = pdfFileName.substring(0, pdfFileName.length() - 3);
        if (ocrBaseName.endsWith(".")) {
            ocrBaseName = ocrBaseName.substring(0, ocrBaseName.length() - 1);
        }
        String ocrFileName = outputFolder + File.separator + ocrBaseName + "_ocr.json";

        // Compact JSON (single-line, matching the sample 202604231785283947722051256_ocr.json format).
        mapper.writeValue(new File(ocrFileName), ocrResult);
        LOGGER.log(Level.INFO, "Created {0}", ocrFileName);
    }

    /**
     * Renders the {@code pageNumber}-th page (0-based) of {@code pdfFile} to a PNG in the
     * PDF's images directory. The file name starts with {@code pdfBaseName + "_streamtable-"}
     * so that {@link #cleanupLocalFiles} (allowlist: {@code name.startsWith(pdfBaseName + "_")})
     * removes it after upload. When OSS is enabled the PNG is uploaded to the temp bucket
     * (same as the main-JSON upload) and the local file is deleted; the returned value is
     * the OSS URL. When OSS is disabled the local absolute path is returned.
     *
     * @param clipYPdf optional y-range to clip the screenshot to, in top-left PDF coordinates
     *                 ({@code [top, bottom]}); {@code null} means render the full page.
     * @return absolute local path or OSS URL on success, {@code null} on failure
     *         (with a warning logged).
     */
    private static String renderStreamTablePageScreenshot(File pdfFile, String outputFolder, String pdfBaseName,
                                                          int pageNumber, boolean ossEnabled,
                                                          OssUploadConfig ossConfig,
                                                          HuaweiObsClient obsClient,
                                                          double[] clipYPdf) throws IOException {
        if (!pdfFile.exists()) {
            LOGGER.log(Level.WARNING, "PDF not found for stream-table screenshot: {0}", pdfFile.getAbsolutePath());
            return null;
        }
        File imagesDir = new File(outputFolder, pdfBaseName + MarkdownSyntax.IMAGES_DIRECTORY_SUFFIX);
        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            LOGGER.log(Level.WARNING, "Unable to create images directory for stream-table screenshot: {0}",
                imagesDir.getAbsolutePath());
            return null;
        }
        File screenshot = new File(imagesDir, pdfBaseName + "_streamtable-" + pageNumber + ".png");
        BufferedImage rendered;
        try {
            rendered = renderPage(pdfFile, pageNumber, clipYPdf);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING,
                "Failed to render stream-table screenshot for page " + (pageNumber + 1)
                    + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                ex);
            return null;
        }
        if (rendered == null) {
            // clip range ended up empty (header and footer overlapping or fully covering the page).
            LOGGER.log(Level.WARNING, "Empty clip range for stream-table screenshot on page {0}",
                pageNumber + 1);
            return null;
        }
        ImageIO.write(rendered, "PNG", screenshot);

        if (!ossEnabled) {
            return screenshot.getAbsolutePath();
        }

        String objectKey = String.format("public/%s/%s_%s_streamtable_%d.png",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId(),
            pageNumber + 1);
        try {
            String url = obsClient.uploadFile(ossConfig.getTempBucketName(), objectKey, screenshot,
                ossConfig.getTempDomainName());
            try {
                Files.delete(screenshot.toPath());
            } catch (IOException delEx) {
                LOGGER.log(Level.WARNING, "Failed to delete local stream-table screenshot after upload: {0}: {1}",
                    new Object[]{screenshot.getAbsolutePath(), delEx.getMessage()});
            }
            return url;
        } catch (IOException uploadEx) {
            LOGGER.log(Level.WARNING,
                "Failed to upload stream-table screenshot for page " + (pageNumber + 1)
                    + " to OBS: " + uploadEx.getClass().getSimpleName() + ": " + uploadEx.getMessage(),
                uploadEx);
            // Fallback: keep the local file so downstream consumers can still access the image.
            return screenshot.getAbsolutePath();
        }
    }

    /**
     * Renders a page screenshot for a {@code have_formula}-flagged page and either uploads it
     * to the OSS temp bucket (when OSS is enabled) or returns the local absolute path.
     * <p>
     * Mirrors {@link #renderStreamTablePageScreenshot} structurally but uses the
     * {@code _formula-} file name pattern and the {@code _formula_} OSS object-key
     * suffix so the two kinds don't collide on disk or in the bucket. Both file
     * name patterns share the {@code {baseName}_} prefix so they fall under the
     * {@code cleanupLocalFiles} {@code name.startsWith(pdfBaseName + "_")}
     * allowlist.
     *
     * @param clipYPdf optional y-range to clip the screenshot to, in top-left PDF coordinates
     *                 ({@code [top, bottom]}); {@code null} means render the full page.
     * @return absolute local path or OBS URL on success, {@code null} on failure
     *         (with a warning logged).
     */
    private static String renderFormulaPageScreenshot(File pdfFile, String outputFolder, String pdfBaseName,
                                                     int pageNumber, boolean ossEnabled,
                                                     OssUploadConfig ossConfig,
                                                     HuaweiObsClient obsClient,
                                                     double[] clipYPdf) throws IOException {
        if (!pdfFile.exists()) {
            LOGGER.log(Level.WARNING, "PDF not found for formula screenshot: {0}", pdfFile.getAbsolutePath());
            return null;
        }
        File imagesDir = new File(outputFolder, pdfBaseName + MarkdownSyntax.IMAGES_DIRECTORY_SUFFIX);
        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            LOGGER.log(Level.WARNING, "Unable to create images directory for formula screenshot: {0}",
                imagesDir.getAbsolutePath());
            return null;
        }
        File screenshot = new File(imagesDir, pdfBaseName + "_formula-" + pageNumber + ".png");
        BufferedImage rendered;
        try {
            rendered = renderPage(pdfFile, pageNumber, clipYPdf);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING,
                "Failed to render formula screenshot for page " + (pageNumber + 1)
                    + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                ex);
            return null;
        }
        if (rendered == null) {
            LOGGER.log(Level.WARNING, "Empty clip range for formula screenshot on page {0}",
                pageNumber + 1);
            return null;
        }
        ImageIO.write(rendered, "PNG", screenshot);

        if (!ossEnabled) {
            return screenshot.getAbsolutePath();
        }

        String objectKey = String.format("public/%s/%s_%s_formula_%d.png",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId(),
            pageNumber + 1);
        try {
            String url = obsClient.uploadFile(ossConfig.getTempBucketName(), objectKey, screenshot,
                ossConfig.getTempDomainName());
            try {
                Files.delete(screenshot.toPath());
            } catch (IOException delEx) {
                LOGGER.log(Level.WARNING, "Failed to delete local formula screenshot after upload: {0}: {1}",
                    new Object[]{screenshot.getAbsolutePath(), delEx.getMessage()});
            }
            return url;
        } catch (IOException uploadEx) {
            LOGGER.log(Level.WARNING,
                "Failed to upload formula screenshot for page " + (pageNumber + 1)
                    + " to OBS: " + uploadEx.getClass().getSimpleName() + ": " + uploadEx.getMessage(),
                uploadEx);
            // Fallback: keep the local file so downstream consumers can still access the image.
            return screenshot.getAbsolutePath();
        }
    }

    /**
     * Renders a page screenshot for the image-hit Pass 2 (large single-image page) and
     * either uploads it to the OSS temp bucket (when OSS is enabled) or returns the local
     * absolute path. Structurally identical to {@link #renderStreamTablePageScreenshot} /
     * {@link #renderFormulaPageScreenshot}; differs only in the {@code _ocr-} file-name
     * pattern and the {@code _ocr_} OSS object-key suffix so the three kinds don't collide
     * on disk or in the bucket. All three share the {@code {baseName}_} prefix so they fall
     * under {@link #cleanupLocalFiles}'s {@code name.startsWith(pdfBaseName + "_")} allowlist.
     *
     * @param clipYPdf optional y-range to clip the screenshot to, in top-left PDF coordinates
     *                 ({@code [top, bottom]}); {@code null} means render the full page.
     * @return absolute local path or OBS URL on success, {@code null} on failure
     *         (with a warning logged).
     */
    private static String renderOcrPageScreenshot(File pdfFile, String outputFolder, String pdfBaseName,
                                                  int pageNumber, boolean ossEnabled,
                                                  OssUploadConfig ossConfig,
                                                  HuaweiObsClient obsClient,
                                                  double[] clipYPdf) throws IOException {
        if (!pdfFile.exists()) {
            LOGGER.log(Level.WARNING, "PDF not found for ocr screenshot: {0}", pdfFile.getAbsolutePath());
            return null;
        }
        File imagesDir = new File(outputFolder, pdfBaseName + MarkdownSyntax.IMAGES_DIRECTORY_SUFFIX);
        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            LOGGER.log(Level.WARNING, "Unable to create images directory for ocr screenshot: {0}",
                imagesDir.getAbsolutePath());
            return null;
        }
        File screenshot = new File(imagesDir, pdfBaseName + "_ocr-" + pageNumber + ".png");
        BufferedImage rendered;
        try {
            rendered = renderPage(pdfFile, pageNumber, clipYPdf);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING,
                "Failed to render ocr screenshot for page " + (pageNumber + 1)
                    + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                ex);
            return null;
        }
        if (rendered == null) {
            LOGGER.log(Level.WARNING, "Empty clip range for ocr screenshot on page {0}",
                pageNumber + 1);
            return null;
        }
        ImageIO.write(rendered, "PNG", screenshot);

        if (!ossEnabled) {
            return screenshot.getAbsolutePath();
        }

        String objectKey = String.format("public/%s/%s_%s_ocr_%d.png",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId(),
            pageNumber + 1);
        try {
            String url = obsClient.uploadFile(ossConfig.getTempBucketName(), objectKey, screenshot,
                ossConfig.getTempDomainName());
            try {
                Files.delete(screenshot.toPath());
            } catch (IOException delEx) {
                LOGGER.log(Level.WARNING, "Failed to delete local ocr screenshot after upload: {0}: {1}",
                    new Object[]{screenshot.getAbsolutePath(), delEx.getMessage()});
            }
            return url;
        } catch (IOException uploadEx) {
            LOGGER.log(Level.WARNING,
                "Failed to upload ocr screenshot for page " + (pageNumber + 1)
                    + " to OBS: " + uploadEx.getClass().getSimpleName() + ": " + uploadEx.getMessage(),
                uploadEx);
            // Fallback: keep the local file so downstream consumers can still access the image.
            return screenshot.getAbsolutePath();
        }
    }

    /**
     * Renders a page to a {@link BufferedImage} at 200 DPI. When {@code clipYPdf} is non-null,
     * the page's {@code cropBox} is temporarily narrowed to the requested y-band so the
     * renderer outputs exactly that PDF region — no {@code getSubimage} on top of a full-page
     * render, so the pixel grid is exactly {@code cropBox.width × DPI / 72} wide and
     * {@code bandHeight × DPI / 72} tall, with no subpixel drift from rounding the y-start.
     *
     * <p>{@code clipYPdf} is in top-left PDF coordinates ({@code [top, bottom]}); x is always
     * preserved in full (i.e. {@code x = 0}, width = current cropBox width).</p>
     *
     * @return the rendered image, or {@code null} when the clip range collapses to zero
     *         height (header and footer bands overlapping or fully covering the page).
     */
    private static BufferedImage renderPage(File pdfFile, int pageNumber, double[] clipYPdf) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument sourceDoc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(sourceDoc);
            org.apache.pdfbox.pdmodel.PDPage page = sourceDoc.getPage(pageNumber);

            if (clipYPdf == null) {
                return renderer.renderImageWithDPI(pageNumber, 300.0f);
            }

            // PDFBox uses bottom-left origin while clipYPdf is top-left, so flip y. The width
            // comes from the current cropBox (which matches what DocumentProcessor reports as
            // the page size and what writeOcrDetectionJson records as image_width).
            PDRectangle originalCropBox = page.getCropBox();
            float pdfY = (float) (originalCropBox.getHeight() - clipYPdf[1]);
            float pdfH = (float) (clipYPdf[1] - clipYPdf[0]);
            if (pdfH <= 0) {
                return null;
            }
            page.setCropBox(new PDRectangle(0.0f, pdfY, originalCropBox.getWidth(), pdfH));
            try {
                return renderer.renderImageWithDPI(pageNumber, 300.0f);
            } finally {
                // PDFBox reads cropBox fresh on every render call, but restoring keeps the
                // PDDocument consistent for any code that might look at it later.
                page.setCropBox(originalCropBox);
            }
        }
    }

    /**
     * Resolves related_id for self_bookmarks: for each bookmark, locate the JSON page by page_num,
     * match the bookmark title against text items in that page, and reuse the item id on a hit; keep 0 otherwise.
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
     * Repairs self_bookmarks nodes whose page_num is 0: if more than 30% of nodes have page_num=0 the list is cleared;
     * otherwise page_num and related_id are filled by content matching within the anchor ranges in DFS pre-order,
     * falling back to prev.pageNum+1 (or 1) when no match is found.
     */
    private static void repairSelfBookmarkRelatedPageNums(ObjectMapper mapper, Map<String, Object> map,
                                                          List<Map<String, Object>> data) {
        Object selfObj = map.get("self_bookmarks");
        if (!(selfObj instanceof List)) {
            return;
        }
        List<Bookmark> selfBookmarks = mapper.convertValue(selfObj, new TypeReference<List<Bookmark>>() {});
        CatalogBookmarkProcessor.repairSelfBookmarkPageNums(selfBookmarks, data);
        map.put("self_bookmarks", selfBookmarks);
    }

    /**
     * Returns the first line of text from a JSON item (used for pattern matching).
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
     * Returns the full text of a JSON item.
     *
     * <p>Walks every line and every fragment of the item and joins them into a single-line string via
     * {@link SmartTextJoiner} using the rule "insert a space only when both sides are ASCII letters or both sides are
     * ASCII digits". Empty fragments are skipped to avoid introducing extra whitespace.</p>
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
     * Wraps the OSS configuration items from customOptions.
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
         * Validates only the 8 configuration items required for the bookmark-rebuild scenario
         * (excluding {@code ossPermanentBucketName}). It shares the same field set as
         * {@link #fromCustomOptions(Config)} so that permanent-bucket config is not mandatory
         * when only rebuilding JSON. All other semantics are identical.
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
     * Walks every page of the main JSON, uploads images to the OSS permanent bucket, and replaces local paths in content with OSS URLs.
     * <p>Handles:
     * <ul>
     *     <li>{@code item_type == "image"}: uploads {@code content[0]} as a local image path.</li>
     *     <li>{@code item_type == "text"}: scans every text fragment for {@code <img src="...">} tags and uploads the referenced local images.</li>
     *     <li>{@code item_type == "lattice_table"} / {@code "stream_table"}: scans every cell's {@code text} list for {@code <img>} tags.</li>
     * </ul>
     * The same local path is uploaded at most once per JSON; the resulting URL is reused for all references.
     */
    private static void uploadImagesToOssAndUpdateMap(Map<String, Object> map,
                                                       String outputFolder,
                                                       HuaweiObsClient obsClient,
                                                       OssUploadConfig ossConfig) throws IOException {
        Object dataObj = map.get(JsonName.DATA);
        if (!(dataObj instanceof List)) {
            return;
        }
        List<?> pages = (List<?>) dataObj;
        ImageUploadCache cache = new ImageUploadCache();
        for (Object pageObj : pages) {
            if (!(pageObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> page = (Map<String, Object>) pageObj;
            Object itemsObj = page.get(JsonName.ITEMS);
            if (!(itemsObj instanceof List)) {
                continue;
            }
            for (Object itemObj : (List<?>) itemsObj) {
                if (!(itemObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemObj;
                String itemType = (String) item.get(JsonName.ITEM_TYPE);
                if ("image".equals(itemType)) {
                    uploadImageItemContent(item, outputFolder, cache, obsClient, ossConfig);
                } else if ("text".equals(itemType)) {
                    processTextItemContent(item, outputFolder, cache, obsClient, ossConfig);
                } else if ("lattice_table".equals(itemType) || "stream_table".equals(itemType)) {
                    processTableItemContent(item, outputFolder, cache, obsClient, ossConfig);
                }
            }
        }
    }

    /**
     * Uploads the local image referenced by an {@code item_type == "image"} item and replaces {@code content[0]} with the OSS URL.
     */
    private static void uploadImageItemContent(Map<String, Object> item,
                                                String outputFolder,
                                                ImageUploadCache cache,
                                                HuaweiObsClient obsClient,
                                                OssUploadConfig ossConfig) throws IOException {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List) || ((List<?>) contentObj).isEmpty()) {
            return;
        }
        Object first = ((List<?>) contentObj).get(0);
        if (first == null) {
            return;
        }
        String localPath = first.toString();
        String imageUrl = uploadImageIfNeeded(localPath, outputFolder, cache, obsClient, ossConfig);
        if (imageUrl != null) {
            @SuppressWarnings("unchecked")
            List<Object> contentList = (List<Object>) contentObj;
            contentList.set(0, imageUrl);
        }
    }

    /**
     * Scans every text fragment inside a {@code text} item for {@code <img src="...">} tags,
     * uploads the referenced local images, and replaces the {@code src} values with OSS URLs in-place.
     */
    private static void processTextItemContent(Map<String, Object> item,
                                                String outputFolder,
                                                ImageUploadCache cache,
                                                HuaweiObsClient obsClient,
                                                OssUploadConfig ossConfig) throws IOException {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> contentList = (List<Object>) contentObj;
        for (int i = 0; i < contentList.size(); i++) {
            Object lineObj = contentList.get(i);
            if (lineObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> lineMap = (Map<String, Object>) lineObj;
                Object lineContentObj = lineMap.get(JsonName.CONTENT);
                if (!(lineContentObj instanceof List)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Object> lineContentList = (List<Object>) lineContentObj;
                replaceImgSrcInStringList(lineContentList, outputFolder, cache, obsClient, ossConfig);
            } else if (lineObj instanceof String) {
                String line = (String) lineObj;
                String newLine = replaceImgSrcWithOssUrl(line, outputFolder, cache, obsClient, ossConfig);
                if (!newLine.equals(line)) {
                    contentList.set(i, newLine);
                }
            }
        }
    }

    /**
     * Scans every cell's {@code text} list inside a table item for {@code <img src="...">} tags,
     * uploads the referenced local images, and replaces the {@code src} values with OSS URLs in-place.
     */
    private static void processTableItemContent(Map<String, Object> item,
                                                 String outputFolder,
                                                 ImageUploadCache cache,
                                                 HuaweiObsClient obsClient,
                                                 OssUploadConfig ossConfig) throws IOException {
        Object contentObj = item.get(JsonName.CONTENT);
        if (!(contentObj instanceof List)) {
            return;
        }
        for (Object rowObj : (List<?>) contentObj) {
            if (!(rowObj instanceof List)) {
                continue;
            }
            for (Object cellObj : (List<?>) rowObj) {
                if (!(cellObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cellMap = (Map<String, Object>) cellObj;
                Object textObj = cellMap.get(JsonName.TEXT);
                if (!(textObj instanceof List)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Object> textList = (List<Object>) textObj;
                replaceImgSrcInStringList(textList, outputFolder, cache, obsClient, ossConfig);
            }
        }
    }

    /**
     * Walks a list of strings and replaces any local {@code <img src>} paths with OSS URLs in-place.
     */
    private static void replaceImgSrcInStringList(List<Object> fragments,
                                                   String outputFolder,
                                                   ImageUploadCache cache,
                                                   HuaweiObsClient obsClient,
                                                   OssUploadConfig ossConfig) throws IOException {
        for (int i = 0; i < fragments.size(); i++) {
            Object fragmentObj = fragments.get(i);
            if (fragmentObj instanceof String) {
                String fragment = (String) fragmentObj;
                String newFragment = replaceImgSrcWithOssUrl(fragment, outputFolder, cache, obsClient, ossConfig);
                if (!newFragment.equals(fragment)) {
                    fragments.set(i, newFragment);
                }
            }
        }
    }

    /**
     * Replaces every local {@code src} value inside {@code <img>} tags of {@code text} with the corresponding OSS URL.
     * Non-local paths and tags whose file cannot be found are left unchanged.
     */
    private static String replaceImgSrcWithOssUrl(String text,
                                                   String outputFolder,
                                                   ImageUploadCache cache,
                                                   HuaweiObsClient obsClient,
                                                   OssUploadConfig ossConfig) throws IOException {
        if (text == null || text.isEmpty() || text.indexOf('<') < 0) {
            return text;
        }
        Matcher matcher = IMG_SRC_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String localPath = matcher.group(2);
            String url = uploadImageIfNeeded(localPath, outputFolder, cache, obsClient, ossConfig);
            if (url == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String beforeSrcValue = text.substring(matcher.start(0), matcher.start(2));
            String afterSrcValue = text.substring(matcher.end(2), matcher.end(0));
            String replacement = beforeSrcValue + url + afterSrcValue;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Uploads a single local image file to the OSS permanent bucket if it has not already been uploaded
     * and if its path is under the configured output folder.
     *
     * @return the OSS URL, or {@code null} if the path is empty, remote, outside the output folder,
     *         missing, or already known to have failed upload
     */
    private static String uploadImageIfNeeded(String localPath,
                                               String outputFolder,
                                               ImageUploadCache cache,
                                               HuaweiObsClient obsClient,
                                               OssUploadConfig ossConfig) throws IOException {
        if (localPath == null || localPath.isEmpty()) {
            return null;
        }
        String cachedUrl = cache.getUrl(localPath);
        if (cachedUrl != null) {
            return cachedUrl;
        }
        if (cache.isMissingOrSkipped(localPath)) {
            return null;
        }
        if (isRemoteOrDataUrl(localPath)) {
            cache.markMissingOrSkipped(localPath);
            return null;
        }
        if (!isPathUnderOutputFolder(localPath, outputFolder)) {
            LOGGER.log(Level.WARNING,
                "Image path is outside the output folder, skip uploading: {0}", localPath);
            cache.markMissingOrSkipped(localPath);
            return null;
        }
        File imageFile = new File(localPath);
        if (!imageFile.exists()) {
            LOGGER.log(Level.WARNING, "Image file not found, skip uploading: {0}", localPath);
            cache.markMissingOrSkipped(localPath);
            return null;
        }
        String imageName = imageFile.getName();
        String objectKey = String.format("public/%s/%s_%s/%s",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId(),
            imageName);
        String imageUrl = obsClient.uploadFile(
            ossConfig.getPermanentBucketName(), objectKey, imageFile, ossConfig.getDomainName());
        cache.putUrl(localPath, imageUrl);
        LOGGER.log(Level.INFO, "Replaced image path with OBS URL: {0}", imageUrl);
        return imageUrl;
    }

    /**
     * Checks whether {@code localPath} is an HTTP/HTTPS or data URL (and therefore should not be uploaded).
     */
    private static boolean isRemoteOrDataUrl(String localPath) {
        String lower = localPath.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:");
    }

    /**
     * Checks whether {@code localPath} resolves to a path inside {@code outputFolder}.
     * If {@code outputFolder} is empty, the check is skipped and the path is allowed.
     */
    private static boolean isPathUnderOutputFolder(String localPath, String outputFolder) {
        if (outputFolder == null || outputFolder.isEmpty()) {
            return true;
        }
        try {
            Path base = Paths.get(outputFolder).toAbsolutePath().normalize();
            Path candidate = Paths.get(localPath).toAbsolutePath().normalize();
            return candidate.startsWith(base);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cache for image uploads within a single JSON, mapping local paths to uploaded OSS URLs
     * and remembering paths that should not be uploaded (missing, remote, or outside the output folder).
     */
    private static final class ImageUploadCache {
        private final Map<String, String> pathToUrl = new HashMap<>();
        private final Set<String> missingOrSkipped = new HashSet<>();

        String getUrl(String localPath) {
            return pathToUrl.get(localPath);
        }

        void putUrl(String localPath, String url) {
            pathToUrl.put(localPath, url);
        }

        boolean isMissingOrSkipped(String localPath) {
            return missingOrSkipped.contains(localPath);
        }

        void markMissingOrSkipped(String localPath) {
            missingOrSkipped.add(localPath);
        }
    }

    /**
     * Builds the object key used when uploading the main JSON file to the temporary bucket.
     */
    private static String buildJsonObjectKey(OssUploadConfig ossConfig, String pdfFileName) {
        String baseName = pdfFileName;
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        // Strip any trailing dot that may remain.
        if (baseName.endsWith(".")) {
            baseName = baseName.substring(0, baseName.length() - 1);
        }
        return String.format("public/%s/%s_%s.json",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId());
    }

    /**
     * Builds the object key used when uploading a rebuilt-bookmarks JSON to the temporary bucket.
     * Same format as {@link #buildJsonObjectKey} but does not need the PDF file name parameter.
     */
    private static String buildJsonObjectKeyForRebuild(OssUploadConfig ossConfig) {
        return String.format("public/%s/%s_%s.json",
            ossConfig.getBasicEnv(),
            ossConfig.getPulsarReceiveTopicName(),
            ossConfig.getBusinessId());
    }

    /**
     * Resolves the local absolute path of _ocr.json; returns an empty string if the file does not exist.
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
     * Cleans up locally generated files after a successful OSS upload.
     *
     * <p>Keeps {@code ocrJsonLocalPath} if it exists and deletes only the generated files under the output
     * directory that relate to the current PDF file name, leaving files produced for other PDFs untouched.
     * An empty {@code ocrJsonLocalPath} means no _ocr.json was generated. The original input PDF is not deleted
     * here; the caller handles that after closing PDF resources.</p>
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
                    // Delete the image directory for the current PDF outright instead of continuing to walk it.
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
