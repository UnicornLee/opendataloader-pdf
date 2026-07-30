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
package org.opendataloader.pdf.processors;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;
import org.opendataloader.pdf.custom.utils.BookmarkUtils;
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.hybrid.ElementMetadata;
import org.opendataloader.pdf.processors.readingorder.XYCutPlusPlusSorter;
import org.opendataloader.pdf.json.JsonWriter;
import org.opendataloader.pdf.markdown.MarkdownGenerator;
import org.opendataloader.pdf.markdown.MarkdownGeneratorFactory;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.html.HtmlGenerator;
import org.opendataloader.pdf.html.HtmlGeneratorFactory;
import org.opendataloader.pdf.pdf.PDFWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.text.TextGenerator;
import org.opendataloader.pdf.utils.ContentSanitizer;
import org.opendataloader.pdf.utils.FileUtils;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.opendataloader.pdf.utils.TextNodeUtils;
import org.verapdf.as.ASAtom;
import org.verapdf.containers.StaticCoreContainers;
import org.verapdf.cos.COSDictionary;
import org.verapdf.cos.COSObjType;
import org.verapdf.cos.COSObject;
import org.verapdf.cos.COSTrailer;
import org.verapdf.exceptions.InvalidPasswordException;
import org.verapdf.gf.model.impl.containers.StaticStorages;
import org.verapdf.gf.model.impl.cos.GFCosInfo;
import org.verapdf.gf.model.impl.sa.GFSAPDFDocument;
import org.verapdf.parser.PDFFlavour;
import org.verapdf.pd.PDDocument;
import org.verapdf.tools.StaticResources;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.TableBordersCollection;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.semanticalgorithms.consumers.LinesPreprocessingConsumer;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.xmp.containers.StaticXmpCoreContainers;

import org.opendataloader.pdf.exceptions.InvalidPdfFileException;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main processor for PDF document analysis and output generation.
 * Coordinates the extraction, processing, and generation of various output formats.
 */
public class DocumentProcessor {
    private static final Logger LOGGER = Logger.getLogger(DocumentProcessor.class.getCanonicalName());

    /**
     * Releases PDF resources to prevent file locks and memory leaks.
     * - Closes PDDocument to free OS file handles (required for file deletion)
     * - Clears static containers to remove lingering references
     * Should always be called in a finally block.
     */
    private static void closePdfResources() {
        clearCleanupStep("PDDocument", () -> {
            PDDocument document = StaticResources.getDocument();
            if (document != null) {
                document.close();
            }
        });
        clearCleanupStep("ImagesUtils", StaticContainers::closeImagesUtils);

        clearCleanupStep("StaticResources", StaticResources::clear);
        clearCleanupStep("StaticContainers", () -> StaticContainers.updateContainers(null));
        clearCleanupStep(
            "GFStaticContainers",
            org.verapdf.gf.model.impl.containers.StaticContainers::clearAllContainers
        );
        clearCleanupStep("StaticLayoutContainers", StaticLayoutContainers::clearContainers);
        clearCleanupStep("StaticStorages", StaticStorages::clearAllContainers);
        clearCleanupStep("StaticCoreContainers", StaticCoreContainers::clearAllContainers);
        clearCleanupStep("StaticXmpCoreContainers", StaticXmpCoreContainers::clearAllContainers);
    }

    /**
     * Executes a cleanup step safely without interrupting subsequent steps.
     *
     * Each cleanup action is isolated so that a failure in one step
     * does not prevent the remaining cleanup operations from running.
     * Errors are logged for debugging purposes.
     */
    private static void clearCleanupStep(String name, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error clearing " + name, e);
        }
    }

    /**
     * Processes a PDF file and generates the configured outputs.
     *
     * @param inputPdfName the path to the input PDF file
     * @param config the configuration settings
     * @throws IOException if unable to process the file
     */
    public static void processFile(String inputPdfName, Config config) throws IOException {
        processFileWithResult(inputPdfName, config);
    }

    /**
     * Processes a PDF file and returns a {@link ProcessingResult} containing
     * metadata collected during processing (e.g., hybrid server timings).
     *
     * @param inputPdfName the path to the input PDF file
     * @param config the configuration settings
     * @return processing result with optional metadata
     * @throws IOException if unable to process the file
     */
    public static ProcessingResult processFileWithResult(String inputPdfName, Config config) throws IOException {
        try {
            // Phase 1: Extract
            ExtractionResult extraction = extractContents(inputPdfName, config);

            // Phase 2: Output (JSON/MD/HTML/PDF/Text)
            long t0 = System.nanoTime();
            generateCustomOutputs(inputPdfName, extraction.getContents(), config, extraction.getElementMetadata());
            long outputNs = System.nanoTime() - t0;

            return new ProcessingResult(extraction.getHybridTimings(), extraction.getExtractionNs(), outputNs);
        } finally {
            // Always release resources, even if processing threw. closePdfResources
            // logs and swallows per-step failures so cleanup cannot mask the original
            // processing exception.
            closePdfResources();
        }
    }

    /**
     * Run the extraction pipeline only (preprocessing + content extraction + sanitization).
     * Does not generate any output files. The returned {@link ExtractionResult} can be
     * passed to {@link org.opendataloader.pdf.api.AutoTagger} or used to generate
     * specific output formats.
     *
     * <p>Structured processing (headings, lists, tables, captions) is always enabled
     * because auto-tagging and all structured output formats depend on it.
     *
     * @param inputPdfName path to the input PDF file
     * @param config       configuration
     * @return extraction result with contents and timing metadata
     */
    public static ExtractionResult extractContents(String inputPdfName, Config config) throws IOException {
        long t0 = System.nanoTime();
        preprocessing(inputPdfName, config);
        calculateDocumentInfo();
        Set<Integer> pagesToProcess = getValidPageNumbers(config);
        List<List<IObject>> contents;
        if (StaticLayoutContainers.isUseStructTree()) {
            if (config.isHybridEnabled()) {
                // Counterpart to the "no structure tree" warning emitted in preprocessing():
                // here the struct-tree path wins, so the hybrid backend is never called.
                // Warn at this branch (where precedence is resolved) instead of silently
                // dropping the hybrid request (#633).
                LOGGER.log(Level.WARNING, "Both --use-struct-tree and --hybrid were set on a tagged PDF. "
                    + "The structure tree takes precedence, so the hybrid backend was NOT called. "
                    + "A well-tagged PDF already carries reading order and structure; "
                    + "drop --use-struct-tree if you want the hybrid backend instead.");
            }
            contents = TaggedDocumentProcessor.processDocument(inputPdfName, config, pagesToProcess);
        } else if (config.isHybridEnabled()) {
            contents = HybridDocumentProcessor.processDocument(inputPdfName, config, pagesToProcess);
        } else {
            contents = processDocument(inputPdfName, config, pagesToProcess);
        }
        sortContents(contents, config);
        ContentSanitizer contentSanitizer = new ContentSanitizer(config.getFilterConfig().getFilterRules(),
            config.getFilterConfig().isFilterSensitiveData());
        contentSanitizer.sanitizeContents(contents);
        long extractionNs = System.nanoTime() - t0;

        // Re-key metadata by actual IObject IDs in contents.
        // After enrichment, IObject recognizedStructureIds may differ from transformer-assigned IDs.
        // Match metadata to IObjects by bbox proximity.
        Map<Long, ElementMetadata> rawMetadata = HybridDocumentProcessor.getLastElementMetadata();
        Map<Long, ElementMetadata> remappedMetadata = remapMetadataToContents(rawMetadata, contents);

        return new ExtractionResult(contents, extractionNs, HybridDocumentProcessor.getLastHybridTimings(),
            remappedMetadata);
    }

    /**
     * Validates and filters page numbers from config against actual document pages.
     * Logs warnings for pages that don't exist in the document.
     *
     * @param config the configuration containing page selection
     * @return Set of valid 0-indexed page numbers to process, or null for all pages
     */
    private static Set<Integer> getValidPageNumbers(Config config) {
        List<Integer> requestedPages = config.getPageNumbers();
        if (requestedPages.isEmpty()) {
            return null; // null means process all pages
        }

        int totalPages = StaticContainers.getDocument().getNumberOfPages();
        Set<Integer> validPages = new LinkedHashSet<>();
        List<Integer> invalidPages = new ArrayList<>();

        for (Integer page : requestedPages) {
            int zeroIndexed = page - 1; // Convert 1-based to 0-based
            if (zeroIndexed >= 0 && zeroIndexed < totalPages) {
                validPages.add(zeroIndexed);
            } else {
                invalidPages.add(page);
            }
        }

        if (!invalidPages.isEmpty()) {
            LOGGER.log(Level.WARNING,
                "Requested pages {0} do not exist in document (total pages: {1}). Processing only existing pages: {2}",
                new Object[]{invalidPages, totalPages,
                    validPages.stream().map(p -> p + 1).collect(Collectors.toList())});
        }

        if (validPages.isEmpty()) {
            LOGGER.log(Level.WARNING,
                "No valid pages to process. Document has {0} pages but requested: {1}",
                new Object[]{totalPages, requestedPages});
        }

        return validPages;
    }

    @SuppressWarnings("unchecked")
    private static List<List<IObject>> processDocument(String inputPdfName, Config config, Set<Integer> pagesToProcess) throws IOException {
        String absoluteImagesDirectory = StaticLayoutContainers.getImagesDirectory();
        int totalPages = StaticContainers.getDocument().getNumberOfPages();
        List<List<IObject>> contents = new ArrayList<>(Collections.nCopies(totalPages, null));

        // Capture ALL ThreadLocal state from main thread for propagation to workers
        final var document = StaticContainers.getDocument();
        BoundingBox pageBoundingBox = getPageBoundingBox(0);
        final double height = pageBoundingBox.getHeight();
        final double width = pageBoundingBox.getWidth();
        final var pdDocument = StaticResources.getDocument();
        final var tableBordersCollection = StaticContainers.getTableBordersCollection();
        final var accumulatedNodeMapper = StaticContainers.getAccumulatedNodeMapper();
        final var objectKeyMapper = StaticContainers.getObjectKeyMapper();
        final var linesCollection = StaticContainers.getLinesCollection();
        final boolean keepLineBreaks = StaticContainers.isKeepLineBreaks();
        final boolean isDataLoader = StaticContainers.isDataLoader();
        final var isIgnoreCharsWithoutUnicode = StaticContainers.getIsIgnoreCharactersWithoutUnicode();

        // Capture StaticLayoutContainers state (shared mutable — synchronized list for headings)
        final var headings = StaticLayoutContainers.getHeadings();
        final long contentId = StaticLayoutContainers.getCurrentContentId();
        final boolean useStructTree = StaticLayoutContainers.isUseStructTree();
        final var embeddedImageBytesMap = StaticLayoutContainers.getEmbeddedImageBytesMap();
        final var replacementCharRatiosMap = StaticLayoutContainers.getReplacementCharRatiosMap();

        // Runnable that propagates ThreadLocal state to the current (worker) thread
        final Runnable propagateState = () -> {
            StaticResources.setDocument(pdDocument);
            // veraPDF StaticContainers
            StaticContainers.setDocument(document);
            StaticContainers.setTableBordersCollection(tableBordersCollection);
            StaticContainers.setAccumulatedNodeMapper(accumulatedNodeMapper);
            StaticContainers.setObjectKeyMapper(objectKeyMapper);
            StaticContainers.setLinesCollection(linesCollection);
            StaticContainers.setKeepLineBreaks(keepLineBreaks);
            StaticContainers.setIsDataLoader(isDataLoader);
            StaticContainers.setIsIgnoreCharactersWithoutUnicode(isIgnoreCharsWithoutUnicode);
            StaticContainers.setFileName(inputPdfName);
            StaticContainers.setPassword(config.getPassword());
            // Project StaticLayoutContainers — share the same headings list across workers
            StaticLayoutContainers.setHeadings(headings);
            StaticLayoutContainers.setCurrentContentId(contentId);
            StaticLayoutContainers.setIsUseStructTree(useStructTree);
            StaticLayoutContainers.setEmbeddedImageBytesMap(embeddedImageBytesMap);
            StaticLayoutContainers.setReplacementCharRatiosMap(replacementCharRatiosMap);
        };

        // Pre-fetch all page artifacts on main thread (document access is ThreadLocal)
        List<?>[] pageArtifacts = new List<?>[totalPages];
        for (int i = 0; i < totalPages; i++) {
            pageArtifacts[i] = document.getArtifacts(i);
        }

        int parallelism = config.getThreads();
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        int pagesToProcessCount = (pagesToProcess != null) ? pagesToProcess.size() : totalPages;
        LOGGER.log(Level.INFO, "Processing {0} pages with {1} threads", new Object[]{pagesToProcessCount, parallelism});

        String paddleUrl;
        if (config.getCustomOptions() != null && config.getCustomOptions().containsKey("paddleUrl")) {
            paddleUrl = config.getCustomOptions().get("paddleUrl").toString();
        } else {
            paddleUrl = null;
        }
        boolean isImmediateOcr = false;
        if (config.getCustomOptions() != null && config.getCustomOptions().containsKey("is_immediate_ocr")) {
            isImmediateOcr = Boolean.valueOf(config.getCustomOptions().get("is_immediate_ocr").toString());
        }

        try {
            // Loop 1: ContentFilter per-page (largest bottleneck)
            pool.submit(() ->
                IntStream.range(0, totalPages).parallel().forEach(pageNumber -> {
                    try {
                        propagateState.run();
                        if (shouldProcessPage(pageNumber, pagesToProcess)) {
                            List<IObject> pageContents = ContentFilterProcessor.getFilteredContents(inputPdfName,
                                (List) pageArtifacts[pageNumber], pageNumber, config);
                            contents.set(pageNumber, pageContents);
                        } else {
                            contents.set(pageNumber, new ArrayList<>());
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
            ).get();

            // Fallback OCR: if initial text extraction is mostly garbage (e.g., broken ToUnicode / FakeFont),
            // render the page and replace contents with Paddle OCR results.
            Set<Integer> ocrFallbackPages = new HashSet<>();
            if (paddleUrl != null && !"".equals(paddleUrl)) {
                for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                    if (!shouldProcessPage(pageNumber, pagesToProcess)) {
                        continue;
                    }
                    List<IObject> pageContents = contents.get(pageNumber);
                    double replacementRatio = StaticLayoutContainers.getReplacementCharRatio(pageNumber);
                    if (shouldUseOcrFallback(pageContents, width, height) || replacementRatio >= 0.1) {
                        LOGGER.log(Level.WARNING, "Page {0} text extraction is mostly garbled or image-dominant; falling back to full-page OCR.", pageNumber);
                        if (isImmediateOcr) {
                            List<IObject> ocrContents = fallbackOcrPage(inputPdfName, pageNumber, width, height, paddleUrl);
                            if (!ocrContents.isEmpty()) {
                                contents.set(pageNumber, ocrContents);
                                ocrFallbackPages.add(pageNumber);
                            }
                        } else {
                            ImageChunk imageChunk = new ImageChunk(new BoundingBox(pageNumber, 0, 0, width, height));
                            contents.set(pageNumber, new ArrayList<>(Collections.singletonList(imageChunk)));
                            ocrFallbackPages.add(pageNumber);
                        }
                    }
                }
            }

            // Hidden text detection: sequential post-processing (requires ContrastRatioConsumer
            // which renders PDF pages — not safe to parallelize due to per-thread PDF file I/O)
            if (config.getFilterConfig().isFilterHiddenText()) {
                for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                    if (shouldProcessPage(pageNumber, pagesToProcess)) {
                        List<IObject> pageContents = HiddenTextProcessor.findHiddenText(contents.get(pageNumber), true);
                        contents.set(pageNumber, pageContents);
                    }
                }
            }

            // Structured processing is always enabled — auto-tagging needs headings,
            // lists, tables, and captions regardless of output format flags.
            boolean structured = true;

            // ClusterTableProcessor: whole-document (must be sequential)
            if (structured && config.isClusterTableMethod()) {
                new ClusterTableProcessor().processTables(contents);
            }

            new File(config.getOutputFolder()).mkdirs();
            if (!config.isImageOutputOff() && (config.isGenerateHtml() || config.isGenerateMarkdown() || config.isGenerateJSON())) {
                String imagesDirectory;
                if (config.getImageDir() != null && !config.getImageDir().isEmpty()) {
                    imagesDirectory = config.getImageDir();
                } else {
                    String fileName = Paths.get(inputPdfName).getFileName().toString();
                    imagesDirectory = config.getOutputFolder() + File.separator + FileUtils.getBaseName(fileName) + MarkdownSyntax.IMAGES_DIRECTORY_SUFFIX;
                }
                absoluteImagesDirectory = imagesDirectory;
                StaticLayoutContainers.setImagesDirectory(imagesDirectory);
                ImagesUtils imagesUtils = new ImagesUtils();
                imagesUtils.write(contents);
            }

            // Loop 2: TableBorder + TextLine per-page
            final String finalImagesDirectory = absoluteImagesDirectory;
            pool.submit(() ->
                IntStream.range(0, totalPages).parallel().forEach(pageNumber -> {
                    if (!shouldProcessPage(pageNumber, pagesToProcess)) {
                        return;
                    }
                    propagateState.run();
                    List<IObject> pageContents = contents.get(pageNumber);
                    if (structured) {
                        TextDecorationProcessor.processStrikethroughAndUnderlinedText(pageContents, pageNumber, config.isDetectStrikethrough());
                        pageContents = TableBorderProcessor.processTableBorders(pageContents, pageNumber, finalImagesDirectory);
                        pageContents = pageContents.stream().filter(x -> !(x instanceof LineChunk)).collect(Collectors.toList());
                        pageContents = SpecialTableProcessor.detectSpecialTables(pageContents);
                    }
                    pageContents = TextLineProcessor.processTextLines(pageContents, finalImagesDirectory);
                    // 1. 连续
                    // 对 pageContents 按照每个元素的 bounding box 的 topY 坐标从大到小进行排序，确保从上到下的顺序
                    pageContents.sort(Comparator.comparingDouble(item -> item.getTopY()));
                    Collections.reverse(pageContents);
                    // 无线表格识别 (skip for pages already replaced by fallback OCR to avoid double OCR)
                    if (paddleUrl != null && !"".equals(paddleUrl) && !ocrFallbackPages.contains(pageNumber)) {
                        try {
                            pageContents = StreamTableProcessor.processStreamTables(inputPdfName, pageContents, pageNumber, width, height, paddleUrl);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    contents.set(pageNumber, pageContents);
                })
            ).get();

            if (structured) {
                // Cross-page operations (must be sequential)
                HeaderFooterProcessor.processHeadersAndFooters(contents, false);
                // Extract TOC pages as catalog bookmarks without mutating the
                // original contents. The extracted bookmarks are stored in a
                // thread-local container and emitted later by JsonWriter.
                StaticLayoutContainers.setCatalogBookmarks(BookmarkUtils.getCatalogBookmarks(contents, config));
                // TOC detection is temporarily disabled. It is not yet complete:
                //  - the heuristic has heavy false positives (any line ending in a
                //    bare number is treated as a TOC item), so it can restructure
                //    ordinary body content;
                //  - it never resolves a TOCI target (destination), so the tagged
                //    struct tree cannot emit a valid /Ref and the output is not a
                //    conformant table of contents (PDF/UA-2 clause 8.2.5.8.1).
                // Re-enable once detection precision and target resolution are in
                // place. (Remaining hardening is tracked in the internal tasks tracker.)
                // new TableOfContentsProcessor().processTableOfContents(contents);
                // ListProcessor.processLists(contents, false);
            }

            // Loop 3: Paragraph + Heading per-page (always need ParagraphProcessor for text output)
            pool.submit(() ->
                IntStream.range(0, totalPages).parallel().forEach(pageNumber -> {
                    if (!shouldProcessPage(pageNumber, pagesToProcess)) {
                        return;
                    }
                    propagateState.run();
                    List<IObject> pageContents = contents.get(pageNumber);
                    pageContents = ParagraphProcessor.processParagraphs(pageContents, width);
                    if (structured) {
//                        pageContents = ListProcessor.processListsFromTextNodes(pageContents);
                        HeadingProcessor.processHeadings(pageContents, false);
                    }
                    contents.set(pageNumber, pageContents);
                })
            ).get();

            // 循环每一页，先做图表截图，再做公式截图。
            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                List<IObject> pageContents = contents.get(pageNumber);
                ImagesUtils imagesUtils = new ImagesUtils();
                List<IObject> shapeChunks = pageContents.stream()
                        .filter(ShapeChunk.class::isInstance)
                        .collect(Collectors.toList());
                // 对 pageContents 中的 ShapeChunk 做分组，将有交集的分成一组
                List<List<IObject>> groupedShapeChunks = ShapeRecognizer.groupShapes(shapeChunks);
                if (groupedShapeChunks != null && !groupedShapeChunks.isEmpty()) {
                    processBarChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber);
                }
                processLineArtGroups(pageContents, pageNumber, imagesUtils, paddleUrl);
            }

            // Sequential ID assignment (must be in page order, before CaptionProcessor)
            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                if (shouldProcessPage(pageNumber, pagesToProcess)) {
                    setIDs(contents.get(pageNumber));
                }
            }

            // Extract page bookmarks from CustomSemanticParagraph contents after
            // paragraphs and headings have been detected. The extracted bookmarks
            // are stored in a thread-local container and emitted later by JsonWriter.
            if (structured) {
                StaticLayoutContainers.setPageBookmarks(BookmarkUtils.getPageBookmarks(contents));
            }

            // Caption detection runs after setIDs so that recognizedStructureId is available
            // for linking captions to figures/tables
            /*if (structured) {
                for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                    if (shouldProcessPage(pageNumber, pagesToProcess)) {
                        CaptionProcessor.processCaptions(contents.get(pageNumber));
                    }
                }
            }*/

            if (structured) {
                // Cross-page post-processing (must be sequential)
                ListProcessor.checkNeighborLists(contents);
                TableBorderProcessor.checkNeighborTables(contents);
                HeadingProcessor.detectHeadingsLevels();
                LevelProcessor.detectLevels(contents);
            }
        } catch (Exception e) {
            throw new IOException("Parallel page processing failed", e);
        } finally {
            pool.shutdown();
        }
        return contents;
    }

    /**
     * Checks if a page should be processed based on the filter.
     *
     * @param pageNumber 0-indexed page number
     * @param pagesToProcess set of valid page numbers to process, or null for all pages
     * @return true if the page should be processed
     */
    /**
     * Filters ElementMetadata down to entries whose transformer-assigned ID still
     * matches an IObject in the post-enrichment contents. This is deliberately
     * ID-based (not positional): sorting, filtering, and enrichment can reorder
     * or drop IObjects, so positional matching would attach the wrong
     * confidence/source label to an element. IObjects whose ID was rewritten
     * during enrichment simply lose their metadata — preferable to a wrong one.
     */
    private static Map<Long, ElementMetadata> remapMetadataToContents(
            Map<Long, ElementMetadata> rawMetadata, List<List<IObject>> contents) {
        if (rawMetadata == null || rawMetadata.isEmpty()) return Collections.emptyMap();

        Map<Long, ElementMetadata> remapped = new LinkedHashMap<>();
        for (List<IObject> pageContents : contents) {
            for (IObject obj : pageContents) {
                collectMetadata(obj, rawMetadata, remapped);
            }
        }
        return remapped;
    }

    /**
     * Walks an IObject tree and copies any metadata entry keyed by its
     * recognized structure id into {@code remapped}. Containers like
     * {@code ListItem} hold their own children via {@code getContents()}, so
     * a shallow iteration over the top-level page list would miss nested
     * images / pictures — their metadata (ai_score, source label, caption)
     * would silently disappear from the JSON output. We recurse through the
     * containers we actually emit at this level (lists, tables, headers,
     * footers); leaf nodes terminate naturally.
     */
    private static void collectMetadata(IObject obj,
            Map<Long, ElementMetadata> rawMetadata,
            Map<Long, ElementMetadata> remapped) {
        if (obj == null) return;
        Long id = obj.getRecognizedStructureId();
        if (id != null && id != 0L) {
            ElementMetadata meta = rawMetadata.get(id);
            if (meta != null) {
                remapped.put(id, meta);
            }
        }
        // Recurse into every container the JSON serializers walk. This keeps
        // the metadata visibility surface aligned with the serialized tree —
        // any image / picture / heading that ends up in the JSON output also
        // gets its ElementMetadata copied through. Add new container types
        // here when their serializer descends into child IObjects.
        if (obj instanceof org.verapdf.wcag.algorithms.entities.lists.ListItem) {
            for (IObject child : ((org.verapdf.wcag.algorithms.entities.lists.ListItem) obj).getContents()) {
                collectMetadata(child, rawMetadata, remapped);
            }
        } else if (obj instanceof org.verapdf.wcag.algorithms.entities.lists.PDFList) {
            for (org.verapdf.wcag.algorithms.entities.lists.ListItem item :
                    ((org.verapdf.wcag.algorithms.entities.lists.PDFList) obj).getListItems()) {
                collectMetadata(item, rawMetadata, remapped);
            }
        } else if (obj instanceof org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder) {
            org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder table =
                (org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder) obj;
            if (table.isTextBlock()) {
                // Text-block tables serialize as a single anonymous cell. Recurse
                // through the cell IObject itself so its own structureId metadata
                // is captured alongside the children — going straight to
                // getContents() would skip the cell-level entry.
                org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell cell = table.getCell(0, 0);
                if (cell != null) {
                    collectMetadata(cell, rawMetadata, remapped);
                }
            } else {
                for (org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow row : table.getRows()) {
                    for (org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell cell : row.getCells()) {
                        collectMetadata(cell, rawMetadata, remapped);
                    }
                }
            }
        } else if (obj instanceof org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell) {
            for (IObject child : ((org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell) obj).getContents()) {
                collectMetadata(child, rawMetadata, remapped);
            }
        } else if (obj instanceof org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter) {
            for (IObject child : ((org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter) obj).getContents()) {
                collectMetadata(child, rawMetadata, remapped);
            }
        }
    }

    private static boolean shouldProcessPage(int pageNumber, Set<Integer> pagesToProcess) {
        return pagesToProcess == null || pagesToProcess.contains(pageNumber);
    }

    public static void generateCustomOutputs(String inputPdfName, List<List<IObject>> contents, Config config,
                                       Map<Long, ElementMetadata> elementMetadata) throws IOException {
        JsonWriter.writeToJCustomJson(inputPdfName, config.getOutputFolder(), contents, elementMetadata,
            null, config.isIncludeHeaderFooter());
    }

    /**
     * Writes the configured output files (JSON/MD/HTML/PDF/Text/images/tagged PDF)
     * from already-extracted contents.
     *
     * <p><strong>Internal API. Do not call directly.</strong> This method is
     * {@code public} only so the {@link org.opendataloader.pdf.api.OutputWriter}
     * facade in the {@code api} package can delegate to it. The signature
     * (notably the {@code List<List<IObject>>} and
     * {@code Map<Long, ElementMetadata>} parameters) is an implementation
     * detail and may change in any release. External callers must use
     * {@link org.opendataloader.pdf.api.OutputWriter#writeOutputs}, which is
     * the stable public API.
     */
    public static void generateOutputs(String inputPdfName, List<List<IObject>> contents, Config config,
                                           Map<Long, ElementMetadata> elementMetadata) throws IOException {
        // Stdout mode: write primary format to stdout, skip file I/O
        if (config.isOutputStdout()) {
            java.io.Writer stdoutWriter = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8));
            if (config.isGenerateText()) {
                TextGenerator textGenerator = new TextGenerator(stdoutWriter, config);
                textGenerator.writeToText(contents);
                stdoutWriter.flush();
            } else if (config.isGenerateMarkdown()) {
                MarkdownGenerator markdownGenerator = new MarkdownGenerator(stdoutWriter, config);
                markdownGenerator.writeToMarkdown(contents);
                stdoutWriter.flush();
            }
            // JSON and HTML stdout not yet supported
            return;
        }

        File inputPDF = new File(inputPdfName);
        if (config.isGenerateTaggedPDF()) {
            AutoTaggingProcessor.createTaggedPDF(inputPDF, config.getOutputFolder(),
                StaticResources.getDocument(), contents);
        }
        if (config.isGeneratePDF()) {
            PDFWriter pdfWriter = new PDFWriter();
            pdfWriter.updatePDF(inputPDF, config.getPassword(), config.getOutputFolder(), contents);
        }
        if (config.isGenerateJSON()) {
            JsonWriter.writeToJson(inputPDF, config.getOutputFolder(), contents, elementMetadata,
                    null, config.isIncludeHeaderFooter());
        }
        if (config.isGenerateMarkdown()) {
            try (MarkdownGenerator markdownGenerator = MarkdownGeneratorFactory.getMarkdownGenerator(inputPDF,
                config)) {
                markdownGenerator.writeToMarkdown(contents);
            }
        }
        if (config.isGenerateHtml()) {
            try (HtmlGenerator htmlGenerator = HtmlGeneratorFactory.getHtmlGenerator(inputPDF, config)) {
                htmlGenerator.writeToHtml(contents);
            }
        }
        if (config.isGenerateText()) {
            try (TextGenerator textGenerator = new TextGenerator(inputPDF, config)) {
                textGenerator.writeToText(contents);
            }
        }
    }

    /**
     * Performs preprocessing on a PDF document.
     * Initializes static containers and parses the document structure.
     *
     * @param pdfName the path to the PDF file
     * @param config the configuration settings
     * @throws IOException if unable to read the PDF file
     */
    public static void preprocessing(String pdfName, Config config) throws IOException {
        LOGGER.log(Level.INFO, () -> "File name: " + pdfName);
        validatePdfMagicNumber(pdfName);
        updateStaticContainers(config);
        PDDocument pdDocument;
        try {
            pdDocument = new PDDocument(pdfName);
        } catch (InvalidPasswordException pw) {
            // Encrypted PDFs are not a content-validity failure — let the
            // password-handling branch in callers (e.g. CLIMain) take over.
            throw pw;
        } catch (IOException cause) {
            // Magic number was present, so the user expected a real PDF, but
            // veraPDF could not parse the document (truncated download, body
            // corruption, missing xref). Surface a friendly message instead
            // of letting the raw veraPDF IOException leak as a stack trace.
            throw new InvalidPdfFileException(
                "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
                cause);
        }
        StaticResources.setDocument(pdDocument);
        GFSAPDFDocument document = new GFSAPDFDocument(pdDocument);
//        org.verapdf.gf.model.impl.containers.StaticContainers.setFlavour(Collections.singletonList(PDFAFlavour.WCAG_2_2));
        StaticResources.setFlavour(Collections.singletonList(Objects.equals(pdDocument.getVersion(), 2.0F) ?
            PDFFlavour.WCAG_2_2_PDF_2_0_HUMAN : PDFFlavour.WCAG_2_2_HUMAN));
        StaticStorages.setIsFilterInvisibleLayers(config.getFilterConfig().isFilterHiddenOCG());
        StaticContainers.setDocument(document);
        if (config.isUseStructTree()) {
            document.parseStructureTreeRoot();
            if (document.getTree() != null) {
                StaticLayoutContainers.setIsUseStructTree(true);
            } else {
                StaticLayoutContainers.setIsUseStructTree(false);
                LOGGER.log(Level.WARNING, "The document has no structure tree. The 'use-struct-tree' option will be ignored.");
            }
        }
        StaticContainers.setFileName(pdfName);
        StaticContainers.setPassword(config.getPassword());
        StaticContainers.setIsDataLoader(true);
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticResources.setIsFontProgramsParsing(true);
        StaticStorages.setIsIgnoreMCIDs(!StaticLayoutContainers.isUseStructTree());
        StaticStorages.setIsAddSpacesBetweenTextPieces(true);
        document.parseChunks();
        ShapeRecognizer.recognize(document);
        LinesPreprocessingConsumer linesPreprocessingConsumer = new LinesPreprocessingConsumer();
        linesPreprocessingConsumer.findTableBorders();
        /*linesPreprocessingConsumer.getTableBorders().forEach(builders -> builders.forEach(builder -> {
            TableBorder border = new TableBorder(builder);
            boolean badTable = border.isBadTable();
        }));*/
        StaticContainers.setTableBordersCollection(new TableBordersCollection(linesPreprocessingConsumer.getTableBorders()));
    }

    /**
     * Verifies the input file contains the PDF magic number ({@code %PDF-})
     * within its first 1024 bytes.
     *
     * <p>ISO 32000-1 §7.5.2 allows the {@code %PDF-} header to appear "near
     * the beginning" of the file rather than strictly at byte 0; real-world
     * PDFs sometimes have a leading UTF-8 BOM or whitespace. A 1024-byte
     * search window matches that tolerance while still rejecting any
     * JPG/PNG/HTML/empty file.
     *
     * @throws InvalidPdfFileException if the magic number is not present
     * @throws IOException if the file cannot be opened or read
     */
    private static void validatePdfMagicNumber(String pdfName) throws IOException {
        Path path = Path.of(pdfName);
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(1024);
        }
        byte[] marker = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (indexOfBytes(head, marker) < 0) {
            throw new InvalidPdfFileException(
                "'" + displayName(pdfName) + "' is not a valid PDF file (missing %PDF- header).");
        }
    }

    /**
     * Path.getFileName() returns null for filesystem roots (e.g. {@code C:\}).
     * Fall back to the original input string in that case so the user-facing
     * error message is never empty.
     */
    private static String displayName(String pdfName) {
        Path fileName = Path.of(pdfName).getFileName();
        return fileName != null ? fileName.toString() : pdfName;
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        int last = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void updateStaticContainers(Config config) {
        StaticResources.clear();
        StaticContainers.updateContainers(null);
        StaticLayoutContainers.clearContainers();
        org.verapdf.gf.model.impl.containers.StaticContainers.clearAllContainers();
        StaticCoreContainers.clearAllContainers();
        StaticXmpCoreContainers.clearAllContainers();
        StaticContainers.setKeepLineBreaks(config.isKeepLineBreaks());
        StaticLayoutContainers.setCurrentContentId(1);
        StaticLayoutContainers.setEmbedImages(config.isEmbedImages());
        StaticLayoutContainers.setImageFormat(config.getImageFormat());
        StaticResources.setPassword(config.getPassword());
    }

    /**
     * Assigns unique IDs to each content object.
     *
     * @param contents the list of content objects
     */
    public static void setIDs(List<IObject> contents) {
        for (IObject object : contents) {
            object.setRecognizedStructureId(StaticLayoutContainers.incrementContentId());
        }
    }

    /**
     * Sets index values for all content objects across all pages.
     *
     * @param contents the document contents organized by page
     */
    public static void setIndexesForDocumentContents(List<List<IObject>> contents) {
        for (List<IObject> pageContents : contents) {
            setIndexesForContentsList(pageContents);
        }
    }

    /**
     * Sets index values for content objects in a list.
     *
     * @param contents the list of content objects
     */
    public static void setIndexesForContentsList(List<IObject> contents) {
        for (int index = 0; index < contents.size(); index++) {
            IObject content = contents.get(index);
            if (!(content instanceof ImageChunk)) {
                content.setIndex(index);
            }
        }
    }

    /**
     * Creates a new list with null objects removed.
     *
     * @param contents the list that may contain null objects
     * @return a new list without null objects
     */
    public static List<IObject> removeNullObjectsFromList(List<IObject> contents) {
        List<IObject> newContents = new ArrayList<>();
        for (IObject content : contents) {
            if (content != null) {
                newContents.add(content);
            }
        }
        return newContents;
    }

    private static void calculateDocumentInfo() {
        PDDocument document = StaticResources.getDocument();
        LOGGER.log(Level.INFO, () -> "Number of pages: " + document.getNumberOfPages());
        COSTrailer trailer = document.getDocument().getTrailer();
        GFCosInfo info = getInfo(trailer);
        LOGGER.log(Level.INFO, () -> "Author: " + (info.getAuthor() != null ? info.getAuthor() : info.getXMPCreator()));
        LOGGER.log(Level.INFO, () -> "Title: " + (info.getTitle() != null ? info.getTitle() : info.getXMPTitle()));
        LOGGER.log(Level.INFO, () -> "Creation date: " + (info.getCreationDate() != null ? info.getCreationDate() : info.getXMPCreateDate()));
        LOGGER.log(Level.INFO, () -> "Modification date: " + (info.getModDate() != null ? info.getModDate() : info.getXMPModifyDate()));
    }

    private static GFCosInfo getInfo(COSTrailer trailer) {
        COSObject object = trailer.getKey(ASAtom.INFO);
        return new GFCosInfo((COSDictionary) (object != null && object.getType() == COSObjType.COS_DICT ? object.getDirectBase() : COSDictionary.construct().get()));
    }

    /**
     * Gets a debug string representation of a text node.
     *
     * @param textNode the text node to describe
     * @return a string with font, size, color, and content information
     */
    public static String getContentsValueForTextNode(SemanticTextNode textNode) {
        return String.format("%s: font %s, text size %.2f, text color %s, text content \"%s\"",
                textNode.getSemanticType().getValue(), textNode.getFontName(),
                textNode.getFontSize(), Arrays.toString(TextNodeUtils.getTextColorOrDefault(textNode)),
                textNode.getValue().length() > 15 ? textNode.getValue().substring(0, 15) + "..." : textNode.getValue());
    }

    /**
     * Processes shape groups that contain bar charts: renders the group's
     * bounding box as a screenshot, removes all page contents inside that area,
     * and adds the screenshot as an {@link ImageChunk}.
     */
    private static void processBarChartGroups(List<IObject> pageContents, List<List<IObject>> groupedShapeChunks,
                                              ImagesUtils imagesUtils, int pageNumber) {
        if (pageContents == null || imagesUtils == null || groupedShapeChunks == null) {
            return;
        }
        for (List<IObject> group : groupedShapeChunks) {
            if (group == null || group.isEmpty() || !containsBarChart(group)) {
                continue;
            }
            BoundingBox groupBBox = unionBoundingBoxes(group, pageNumber);
            if (groupBBox == null || groupBBox.isEmpty()) {
                continue;
            }
            pageContents.removeIf(content -> isVerticallyMostlyInside(groupBBox, content.getBoundingBox()));
            ImageChunk imageChunk = new ImageChunk(groupBBox);
            imagesUtils.saveImageChunk(imageChunk);
            pageContents.add(imageChunk);
        }
    }

    private static boolean containsBarChart(List<IObject> group) {
        for (IObject obj : group) {
            if (obj instanceof ShapeChunk && ShapeChunk.TYPE_BAR_CHART.equals(((ShapeChunk) obj).getShapeType())) {
                return true;
            }
        }
        return false;
    }

    private static BoundingBox unionBoundingBoxes(List<IObject> group, int pageNumber) {
        BoundingBox union = new BoundingBox(pageNumber);
        boolean hasValid = false;
        for (IObject obj : group) {
            BoundingBox bbox = obj.getBoundingBox();
            if (bbox != null && !bbox.isEmpty()) {
                union.union(bbox);
                hasValid = true;
            }
        }
        return hasValid ? union : null;
    }

    private static boolean isVerticallyMostlyInside(BoundingBox outer, BoundingBox inner) {
        if (inner == null || inner.isEmpty()) {
            return false;
        }
        double overlapPercent = inner.getVerticalIntersectionPercent(outer);
        return overlapPercent > 0.5;
    }

    /** Minimum overlap ratio to treat a neighboring element as intersecting a LineArtChunk. */
    private static final double MIN_LINE_ART_OVERLAP_PERCENT = 0.05;

    /** Garbage text ratio that triggers full-page OCR fallback for a page. */
    private static final double OCR_FALLBACK_GARBAGE_RATIO_THRESHOLD = 0.5;
    /** DPI used when rendering a page image for fallback OCR. */
    private static final float OCR_FALLBACK_RENDER_DPI = 300.0f;

    /**
     * Merges LineArtChunks with their significantly overlapping neighbors,
     * renders the merged area as an image, and replaces the merged elements
     * with a single {@link ImageChunk}.
     */
    private static void processLineArtGroups(List<IObject> pageContents, int pageNumber,
                                             ImagesUtils imagesUtils, String paddleUrl) {
        if (pageContents == null || pageContents.isEmpty() || imagesUtils == null) {
            return;
        }
        List<IObject> result = new ArrayList<>(pageContents.size());
        for (int i = 0; i < pageContents.size(); i++) {
            IObject current = pageContents.get(i);
            if (!(current instanceof LineArtChunk)) {
                result.add(current);
                continue;
            }

            BoundingBox lineArtBox = new BoundingBox(current.getBoundingBox());
            List<IObject> group = new ArrayList<>();
            group.add(current);

            // Pull overlapping elements already added to result (the "before" neighbors).
            for (int j = result.size() - 1; j >= 0; j--) {
                IObject candidate = result.get(j);
                if (hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    group.add(0, candidate);
                    result.remove(j);
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }

            // Collect overlapping "after" neighbors from the original list.
            int forwardCount = 0;
            for (int j = i + 1; j < pageContents.size(); j++) {
                IObject candidate = pageContents.get(j);
                if (hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    group.add(candidate);
                    forwardCount++;
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }
            i += forwardCount;

            if (group.size() > 1) {
                BoundingBox union = unionBoundingBoxes(group, pageNumber);
                if (union != null && !union.isEmpty()) {
                    ImageChunk imageChunk = new ImageChunk(union);
                    imagesUtils.saveImageChunk(imageChunk);
                    IObject replacement = imageChunk;
                    if (paddleUrl != null && !"".equals(paddleUrl)) {
                        String imageFileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT,
                            StaticLayoutContainers.getImagesDirectory(), File.separator,
                            imageChunk.getIndex(), StaticLayoutContainers.getImageFormat());
                        try {
                            TextInOcrAnalysisResultDto textInOcrAnalysisResultDto = PaddleOcrProcessor.getPaddleResponse(
                                new File(imageFileName), 1, paddleUrl);
                            LOGGER.log(Level.INFO, "Text in ocr analysis result: {}", textInOcrAnalysisResultDto);
                            TextChunk formulaChunk = tryCreateFormulaTextChunk(textInOcrAnalysisResultDto, union);
                            if (formulaChunk != null) {
                                Double fontSize = 12.0;
                                List<Double> fontSizes = new ArrayList<>();
                                for (IObject item : group) {
                                    if (item instanceof CustomSemanticParagraph) {
                                        CustomSemanticParagraph customSemanticParagraph = (CustomSemanticParagraph) item;
                                        fontSizes.add(customSemanticParagraph.getFontSize());
                                    }
                                }
                                if (fontSizes.size() > 0) {
                                    fontSize = Collections.max(fontSizes);
                                }
                                formulaChunk.setFontSize(fontSize);
                                replacement = formulaChunk;

                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to call Paddle OCR for image chunk: " + imageFileName, e);
                        }
                    }
                    result.add(replacement);
                    continue;
                }
            }
            result.add(current);
        }
        pageContents.clear();
        pageContents.addAll(result);
    }

    private static boolean hasSignificantOverlap(BoundingBox candidateBox, BoundingBox lineArtBox) {
        if (candidateBox == null || candidateBox.isEmpty() || lineArtBox == null || lineArtBox.isEmpty()) {
            return false;
        }
        double candidateOverlap = candidateBox.getVerticalIntersectionPercent(lineArtBox);
        double lineArtOverlap = lineArtBox.getVerticalIntersectionPercent(candidateBox);
        return Math.max(candidateOverlap, lineArtOverlap) > MIN_LINE_ART_OVERLAP_PERCENT;
    }

    /**
     * Checks whether a page needs fallback OCR because either:
     * <ul>
     *   <li>the extracted text is mostly garbage (e.g., missing ToUnicode mappings
     *       yielding {@code (cid:*)} placeholders or Unicode replacement characters);</li>
     *   <li>or images dominate the page (cover more than 80% of the page area), e.g.,
     *       a scanned page with a full-page image and little or no extractable text.</li>
     * </ul>
     */
    private static boolean shouldUseOcrFallback(List<IObject> pageContents, double pageWidth, double pageHeight) {
        if (pageContents == null || pageContents.isEmpty()) {
            return false;
        }
        if (isImageDominantPage(pageContents, pageWidth, pageHeight)) {
            return true;
        }
        int totalChars = 0;
        int garbageChars = 0;
        for (IObject content : pageContents) {
            int[] counts = countTextAndGarbage(content);
            totalChars += counts[0];
            garbageChars += counts[1];
        }
        if (totalChars == 0) {
            return false;
        }
        return (double) garbageChars / totalChars >= OCR_FALLBACK_GARBAGE_RATIO_THRESHOLD;
    }

    private static boolean isImageDominantPage(List<IObject> pageContents, double pageWidth, double pageHeight) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            return false;
        }
        double pageArea = pageWidth * pageHeight;
        double imageArea = 0.0;
        for (IObject content : pageContents) {
            if (content instanceof ImageChunk) {
                BoundingBox bbox = content.getBoundingBox();
                if (bbox != null && !bbox.isEmpty()) {
                    imageArea += bbox.getWidth() * bbox.getHeight();
                }
            }
        }
        return imageArea / pageArea > 0.8;
    }

    private static int[] countTextAndGarbage(IObject content) {
        int total = 0;
        int garbage = 0;
        if (content instanceof TextChunk) {
            TextChunk textChunk = (TextChunk) content;
            String value = textChunk.getValue().trim();
            if (value != null) {
                total += value.length();
                if (isGarbageText(value)) {
                    garbage += value.length();
                }
            }
        } else if (content instanceof TextLine) {
            for (TextChunk textChunk : ((TextLine) content).getTextChunks()) {
                int[] counts = countTextAndGarbage(textChunk);
                total += counts[0];
                garbage += counts[1];
            }
        }
        return new int[]{total, garbage};
    }

    private static boolean isGarbageText(String value) {
        return value.contains("(cid:") || value.contains("\uFFFD");
    }

    /**
     * Renders a single PDF page to a temporary PNG and OCRs it via Paddle.
     * Returns OCR-generated {@link TextChunk} objects that replace the page's
     * original extracted contents. If OCR fails, returns an empty list so the
     * caller can keep the original contents.
     */
    private static List<IObject> fallbackOcrPage(String pdfPath, int pageNumber, double sourceWidth,
                                                 double sourceHeight, String paddleUrl) {
        RenderedPage rendered;
        try {
            rendered = renderPageToImage(pdfPath, pageNumber);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to render page " + pageNumber + " for fallback OCR: " + pdfPath, e);
            return Collections.emptyList();
        }
        try {
            TextInOcrAnalysisResultDto resultDto = PaddleOcrProcessor.getPaddleResponse(rendered.imageFile, 1, paddleUrl);
            return convertOcrResultToPageContents(resultDto, pageNumber, sourceWidth, sourceHeight,
                rendered.imageWidth, rendered.imageHeight);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Fallback OCR failed for page " + pageNumber + " of " + pdfPath, e);
            return Collections.emptyList();
        } finally {
            rendered.imageFile.delete();
        }
    }

    private static RenderedPage renderPageToImage(String pdfPath, int pageNumber) throws IOException {
        File source = new File(pdfPath);
        File outputFile = new File(System.getProperty("java.io.tmpdir"),
            source.getName().replaceAll("\\.pdf$", "") + "-page-" + pageNumber + "-fallback.png");
        try (org.apache.pdfbox.pdmodel.PDDocument sourceDoc = Loader.loadPDF(source)) {
            PDFRenderer renderer = new PDFRenderer(sourceDoc);
            BufferedImage pageImage = renderer.renderImageWithDPI(pageNumber, OCR_FALLBACK_RENDER_DPI);
            ImageIO.write(pageImage, "PNG", outputFile);
            return new RenderedPage(outputFile, pageImage.getWidth(), pageImage.getHeight());
        }
    }

    private static List<IObject> convertOcrResultToPageContents(TextInOcrAnalysisResultDto resultDto,
                                                                int pageNumber, double sourceWidth,
                                                                double sourceHeight, int imageWidth,
                                                                int imageHeight) {
        List<IObject> contents = new ArrayList<>();
        if (resultDto == null || resultDto.getDetail() == null || resultDto.getDetail().isEmpty()) {
            return contents;
        }
        for (TextInOcrDetailDto detail : resultDto.getDetail()) {
            if (!"paragraph".equals(detail.getType())) {
                continue;
            }
            String text = detail.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<Double> position = detail.getPosition();
            if (position == null || position.size() < 8) {
                continue;
            }
            BoundingBox bbox = ocrPositionToBoundingBox(position, pageNumber, sourceWidth, sourceHeight,
                imageWidth, imageHeight);
            TextChunk textChunk = new TextChunk(text);
            textChunk.setBoundingBox(new BoundingBox(bbox));
            textChunk.setFontSize(resolveOcrFontSize(detail.getOutlineLevel()));
            textChunk.setBaseLine(bbox.getBottomY());
            contents.add(textChunk);
        }
        contents.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
        return contents;
    }

    private static BoundingBox ocrPositionToBoundingBox(List<Double> position, int pageNumber, double sourceWidth,
                                                      double sourceHeight, int imageWidth, int imageHeight) {
        double widthRatio = sourceWidth / imageWidth;
        double heightRatio = sourceHeight / imageHeight;

        double leftX0 = position.get(0) * widthRatio;
        double leftY0 = position.get(1) * heightRatio;
        double leftX1 = position.get(2) * widthRatio;
        double rightY1 = position.get(7) * heightRatio;

        return new BoundingBox(pageNumber, leftX0, sourceHeight - rightY1, leftX1, sourceHeight - leftY0);
    }

    private static double resolveOcrFontSize(Integer outlineLevel) {
        if (outlineLevel == null) {
            return 10D;
        }
        switch (outlineLevel) {
            case 0: return 13D;
            case 1: return 12D;
            case 2: return 11D;
            default: return 10D;
        }
    }

    private static class RenderedPage {
        final File imageFile;
        final int imageWidth;
        final int imageHeight;

        RenderedPage(File imageFile, int imageWidth, int imageHeight) {
            this.imageFile = imageFile;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    /**
     * Tries to create a {@link TextChunk} from the first OCR detail entry if it
     * looks like a LaTeX formula. Returns {@code null} if the OCR result is
     * missing, not a paragraph, or does not contain LaTeX markers.
     */
    private static TextChunk tryCreateFormulaTextChunk(TextInOcrAnalysisResultDto resultDto, BoundingBox bbox) {
        if (resultDto == null || resultDto.getDetail() == null || resultDto.getDetail().isEmpty()) {
            return null;
        }
        TextInOcrDetailDto detail = resultDto.getDetail().get(0);
        if (!"paragraph".equals(detail.getType())) {
            return null;
        }
        String text = detail.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!isLatexExpression(text)) {
            return null;
        }
        // Normalize LaTeX delimiters: strip any stray $ and wrap with $$...$$
        text = text.replace("$", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        text = "$$" + text + "$$";
        return createTextChunk(text, bbox);
    }

    private static TextChunk createTextChunk(String text, BoundingBox bbox) {
        TextChunk textChunk = new TextChunk(text);
        textChunk.setBoundingBox(new BoundingBox(bbox));
        textChunk.setFontSize(bbox.getHeight());
        textChunk.setBaseLine(bbox.getCenterY());
        return textChunk;
    }

    /**
     * Heuristic LaTeX formula detector. Recognizes:
     * <ul>
     *   <li>Inline/display math delimiters ({@code $...$}, {@code $$...$$})</li>
     *   <li>Backslash commands (e.g., {@code \frac}, {@code \sum})</li>
     *   <li>Subscript/superscript with braces (e.g., {@code x_{i}}, {@code y^{2}})</li>
     *   <li>Simple subscript/superscript without braces (e.g., {@code x^2}, {@code a_i})</li>
     * </ul>
     * Adjust the patterns if your OCR output uses different conventions.
     */
    private static boolean isLatexExpression(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("$") || trimmed.endsWith("$") || trimmed.contains("$$")) {
            return true;
        }
        if (LATEX_COMMAND_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (LATEX_SUBSUP_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (LATEX_SIMPLE_SUBSUP_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        return false;
    }

    private static final Pattern LATEX_COMMAND_PATTERN = Pattern.compile("\\\\[a-zA-Z]+");
    private static final Pattern LATEX_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*\\{");
    private static final Pattern LATEX_SIMPLE_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*[a-zA-Z0-9]");

    /**
     * Gets the bounding box for a page.
     *
     * @param pageNumber the page number (0-indexed)
     * @return the page bounding box, or null if not available
     */
    public static BoundingBox getPageBoundingBox(int pageNumber) {
        PDDocument document = StaticResources.getDocument();
        if (document == null) {
            return null;
        }
        double[] cropBox = document.getPage(pageNumber).getCropBox();
        if (cropBox == null) {
            return null;
        }
        return new BoundingBox(pageNumber, cropBox);
    }

    /**
     * Sorts page contents by their bounding box positions.
     *
     * @param contents the list of content objects to sort
     * @return a new sorted list of content objects
     */
    public static List<IObject> sortPageContents(List<IObject> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }
        List<IObject> sortedContents = new ArrayList<>(contents);
        sortedContents.sort((o1, o2) -> {
            BoundingBox b1 = o1.getBoundingBox();
            BoundingBox b2 = o2.getBoundingBox();
            if (b1 == null && b2 == null) {
                return 0;
            }
            if (b1 == null) {
                return 1;
            }
            if (b2 == null) {
                return -1;
            }
            if (!Objects.equals(b1.getPageNumber(), b2.getPageNumber())) {
                return b1.getPageNumber() - b2.getPageNumber();
            }
            if (!Objects.equals(b1.getLastPageNumber(), b2.getLastPageNumber())) {
                return b1.getLastPageNumber() - b2.getLastPageNumber();
            }
            if (!Objects.equals(b1.getTopY(), b2.getTopY())) {
                return b2.getTopY() - b1.getTopY() > 0 ? 1 : -1;
            }
            if (!Objects.equals(b1.getLeftX(), b2.getLeftX())) {
                return b1.getLeftX() - b2.getLeftX() > 0 ? 1 : -1;
            }
            if (!Objects.equals(b1.getBottomY(), b2.getBottomY())) {
                return b1.getBottomY() - b2.getBottomY() > 0 ? 1 : -1;
            }
            if (!Objects.equals(b1.getRightX(), b2.getRightX())) {
                return b1.getRightX() - b2.getRightX() > 0 ? 1 : -1;
            }
            return 0;
        });
        return sortedContents;
    }

    /**
     * Sorts document contents according to the configured reading order.
     *
     * @param contents the document contents organized by page
     * @param config the configuration containing reading order settings
     */
    public static void sortContents(List<List<IObject>> contents, Config config) {
        String readingOrder = config.getReadingOrder();

        // xycut: XY-Cut++ sorting (per-page, stateless — safe to parallelize)
        if (Config.READING_ORDER_XYCUT.equals(readingOrder)) {
            int totalPages = StaticContainers.getDocument().getNumberOfPages();
            IntStream pages = IntStream.range(0, totalPages);
            if (config.getThreads() > 1) {
                pages.parallel().forEach(pageNumber ->
                    contents.set(pageNumber, XYCutPlusPlusSorter.sort(contents.get(pageNumber))));
            } else {
                pages.forEach(pageNumber ->
                    contents.set(pageNumber, XYCutPlusPlusSorter.sort(contents.get(pageNumber))));
            }
            return;
        }

        // Log warning for unknown reading order values
        if (!Config.READING_ORDER_OFF.equals(readingOrder)) {
            LOGGER.log(Level.WARNING, "Unknown reading order value ''{0}'', using default ''off''", readingOrder);
        }

        // off: skip sorting (keep PDF COS object order)
    }
}
