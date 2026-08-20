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
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.hybrid.ElementMetadata;
import org.opendataloader.pdf.json.CustomOutputResult;
import org.opendataloader.pdf.processors.readingorder.XYCutPlusPlusSorter;
import org.opendataloader.pdf.json.JsonWriter;
import org.opendataloader.pdf.markdown.MarkdownGenerator;
import org.opendataloader.pdf.markdown.MarkdownGeneratorFactory;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.html.HtmlGenerator;
import org.opendataloader.pdf.html.HtmlGeneratorFactory;
import org.opendataloader.pdf.pdf.PDFWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdfbox.GetDrawings;
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
import org.verapdf.wcag.algorithms.entities.content.*;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.TableBordersCollection;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
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

    /** Garbage text ratio that triggers full-page OCR fallback for a page. */
    private static final double OCR_FALLBACK_GARBAGE_RATIO_THRESHOLD = 0.5;
    /** DPI used when rendering a page image for fallback OCR. */
    private static final float OCR_FALLBACK_RENDER_DPI = 300.0f;

    /**
     * Tracks the temp file produced by {@link #tryRepairPdfWithPdfBox} so
     * {@link #closePdfResources} can delete it promptly. The file is also
     * registered with {@code File#deleteOnExit} as a safety net for
     * JVMs that crash before reaching the cleanup step.
     */
    private static File repairedPdfTempFile;

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
        // The repaired-PDF temp file may still be held by veraPDF's
        // SeekableInputStream, so close the PDDocument first (step above)
        // before deleting the file. Always null-out the static field so a
        // future invocation does not try to delete a file the current
        // shutdown already removed (or that belongs to a different run).
        clearCleanupStep("RepairedPdfTempFile", DocumentProcessor::deleteRepairedPdfTempFile);
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
        CustomOutputResult customOutput = null;
        try {
            long startTime = System.nanoTime();
            // Phase 1: Extract
            ExtractionResult extraction = extractContents(inputPdfName, config);

            // Phase 2: Output (JSON/MD/HTML/PDF/Text)
            long t0 = System.nanoTime();
            long extractionNs = t0 - startTime;
            customOutput = generateCustomOutputs(inputPdfName, extraction.getContents(), config, extraction.getElementMetadata());
            long outputNs = System.nanoTime() - t0;
            String fileName = inputPdfName;
            Map<String, Object> docCustomOptions = config.getCustomOptions();
            if (docCustomOptions != null && docCustomOptions.containsKey("url")
                    && !"".equals(docCustomOptions.get("url"))) {
                fileName = (String) docCustomOptions.get("url");
            }
            LOGGER.log(Level.INFO, "{0} - extraction cost {1}, generating outputs cost {2}, total cost {3}.",
                new Object[]{fileName, formatElapsed(extractionNs), formatElapsed(outputNs),
                    formatElapsed(extractionNs + outputNs)});

            return new ProcessingResult(extraction.getHybridTimings(), extraction.getExtractionNs(), outputNs,
                customOutput.getJsonUrlOrPath(), customOutput.getOcrJsonLocalPath());
        } finally {
            // Always release resources, even if processing threw. closePdfResources
            // logs and swallows per-step failures so cleanup cannot mask the original
            // processing exception.
            closePdfResources();

            // Delete the source PDF after resource release only when OSS upload succeeded
            // and the original file still exists.
            if (customOutput != null && customOutput.isOssUploadSuccess()) {
                try {
                    File inputPdf = new File(inputPdfName);
                    if (inputPdf.exists()) {
                        Files.delete(inputPdf.toPath());
                        LOGGER.log(Level.INFO, "Deleted input PDF after OSS upload: {0}", inputPdfName);
                    }
                } catch (IOException deleteEx) {
                    LOGGER.log(Level.WARNING, "Failed to delete input PDF after OSS upload: " + inputPdfName, deleteEx);
                }
            }
        }
    }

    /**
     * Formats an elapsed time in nanoseconds as readable text: under 60 seconds
     * it prints seconds (1 decimal); at 60 seconds or more it prints
     * "X minutes Y seconds". Used for the processing timing log.
     */
    private static String formatElapsed(long nanos) {
        double totalSeconds = nanos / 1_000_000_000.0;
        if (totalSeconds < 60) {
            return String.format(Locale.ROOT, "%.1f s", totalSeconds);
        }
        long minutes = (long) totalSeconds / 60;
        long seconds = (long) totalSeconds % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            long restMinutes = minutes % 60;
            return String.format(Locale.ROOT, "%d hours %d minutes %d seconds", hours, restMinutes, seconds);
        }
        return String.format(Locale.ROOT, "%d minutes %d seconds", minutes, seconds);
    }

    /**
     * Returns the mode (most frequent value) among the non-zero entries of
     * {@code values}. Zeroes are treated as "missing" and ignored. Returns
     * {@code 0.0} when every entry is zero (nothing to infer from).
     */
    private static double modeOfValues(double[] values) {
        Map<Double, Integer> frequency = new HashMap<>();
        for (double value : values) {
            if (value != 0.0) {
                frequency.merge(value, 1, Integer::sum);
            }
        }
        double mode = 0.0;
        int bestCount = 0;
        for (Map.Entry<Double, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                mode = entry.getKey();
            }
        }
        return mode;
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
            config.getFilterConfig().isFilterSensitiveData(),
            config.isHalfWidthToFullWidth());
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
        // Per-page dimensions: non-uniform documents (mixed page sizes / rotation)
        // must not use page 0's width/height for other pages, otherwise per-page
        // coordinate mapping (OCR fallback, stream tables, paragraphs, line-art OCR)
        // is silently offset.
        final double[] pageWidths = new double[totalPages];
        final double[] pageHeights = new double[totalPages];
        for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
            BoundingBox pageBoundingBox = getPageBoundingBox(pageNumber);
            pageWidths[pageNumber] = pageBoundingBox != null ? pageBoundingBox.getWidth() : 0.0;
            pageHeights[pageNumber] = pageBoundingBox != null ? pageBoundingBox.getHeight() : 0.0;
        }
        // Zero dimensions mean the page bbox is unavailable. Replace each zero
        // dimension with the mode (most frequent non-zero value) of that
        // dimension across all pages; a page whose width AND height are both
        // zero is additionally logged as a warning since its geometry is
        // completely missing and downstream coordinates will be estimates.
        double modeWidth = modeOfValues(pageWidths);
        double modeHeight = modeOfValues(pageHeights);
        for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
            boolean widthMissing = pageWidths[pageNumber] == 0.0;
            boolean heightMissing = pageHeights[pageNumber] == 0.0;
            if (widthMissing && heightMissing) {
                LOGGER.log(Level.WARNING,
                    "Page {0} has zero width and height (no bounding box); using mode values {1} x {2} as fallback.",
                    new Object[]{pageNumber + 1, modeWidth, modeHeight});
            } else if (widthMissing) {
                LOGGER.log(Level.WARNING,
                    "Page {0} has zero width (no bounding box); using mode values |{1}| x {2} as fallback.",
                    new Object[]{pageNumber + 1, modeWidth, pageHeights[pageNumber]});
            } else if (heightMissing) {
                LOGGER.log(Level.WARNING,
                    "Page {0} has zero height (no bounding box); using mode values {1} x |{2}| as fallback.",
                    new Object[]{pageNumber + 1, pageWidths[pageNumber], modeHeight});
            }
            if (widthMissing) {
                pageWidths[pageNumber] = modeWidth;
            }
            if (heightMissing) {
                pageHeights[pageNumber] = modeHeight;
            }
        }
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
        // Bookmark ThreadLocals added by catalog/page-bookmark feature (commits 9fe705b / fceeb31).
        // Without propagation, worker threads would see null/0 and either NPE or behave as if no
        // bookmark range was set — see CLAUDE.md "StaticLayoutContainers ThreadLocal" gotcha.
        final List<Bookmark> catalogBookmarks = StaticLayoutContainers.getCatalogBookmarks();
        final List<Bookmark> pageBookmarks = StaticLayoutContainers.getPageBookmarks();
        final int catalogBookmarkStartPage = StaticLayoutContainers.getCatalogBookmarkStartPage();
        final int catalogBookmarkEndPage = StaticLayoutContainers.getCatalogBookmarkEndPage();

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
            // *Map variants share the main-thread list reference (see CLAUDE.md gotcha); the
            // plain setCatalogBookmarks / setPageBookmarks APIs do clear+addAll copy semantics
            // and would leave each worker seeing its own ThreadLocal-initial LinkedList instead
            // of the main thread's data, defeating the point of propagation.
            StaticLayoutContainers.setCatalogBookmarksMap(catalogBookmarks);
            StaticLayoutContainers.setPageBookmarksMap(pageBookmarks);
            StaticLayoutContainers.setCatalogBookmarkPageRange(catalogBookmarkStartPage, catalogBookmarkEndPage);
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
        boolean basicParseStreamTable;
        if (config.getCustomOptions() != null && config.getCustomOptions().containsKey("basicParseStreamTable")) {
            basicParseStreamTable = Boolean.valueOf(config.getCustomOptions().get("basicParseStreamTable").toString());
        } else {
            basicParseStreamTable = false;
        }
        boolean basicFormulaRecognize;
        if (config.getCustomOptions() != null && config.getCustomOptions().containsKey("basicFormulaRecognize")) {
            basicFormulaRecognize = Boolean.valueOf(config.getCustomOptions().get("basicFormulaRecognize").toString());
        } else {
            basicFormulaRecognize = false;
        }
        boolean isImmediateOcr;
        if (config.getCustomOptions() != null && config.getCustomOptions().containsKey("basicIsImmediateOcr")) {
            isImmediateOcr = Boolean.valueOf(config.getCustomOptions().get("basicIsImmediateOcr").toString());
        } else {
            isImmediateOcr = false;
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
                    if (shouldUseOcrFallback(pageContents, pageWidths[pageNumber], pageHeights[pageNumber]) || replacementRatio >= 0.1) {
                        LOGGER.log(Level.WARNING, "Page {0} text extraction is mostly garbled or image-dominant; falling back to full-page OCR.", pageNumber);
                        if (isImmediateOcr) {
                            List<IObject> ocrContents = fallbackOcrPage(inputPdfName, pageNumber, pageWidths[pageNumber], pageHeights[pageNumber], paddleUrl);
                            if (!ocrContents.isEmpty()) {
                                contents.set(pageNumber, ocrContents);
                                ocrFallbackPages.add(pageNumber);
                            }
                        } else {
                            ImageChunk imageChunk = new ImageChunk(new BoundingBox(pageNumber, 0, 0, pageWidths[pageNumber], pageHeights[pageNumber]));
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

            if (config.isHalfWidthToFullWidth()) {
                ContentSanitizer halfWidthConverter = new ContentSanitizer(new ArrayList<>(), false, true);
                halfWidthConverter.sanitizeContents(contents);
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
                    // Sort pageContents by each element's bounding box topY coordinate
                    // in descending order so the final order runs from top to bottom.
                    pageContents.sort(Comparator.comparingDouble(item -> item.getTopY()));
                    Collections.reverse(pageContents);
                    // Stream-table recognition (skip pages already replaced by fallback OCR to avoid double OCR).
                    if (basicParseStreamTable && paddleUrl != null && !"".equals(paddleUrl) && !ocrFallbackPages.contains(pageNumber)) {
                        try {
                            pageContents = StreamTableProcessor.processStreamTables(inputPdfName, pageContents, pageNumber, pageWidths[pageNumber], pageHeights[pageNumber], paddleUrl);
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
                    pageContents = ParagraphProcessor.processParagraphs(pageContents, pageWidths[pageNumber]);
                    contents.set(pageNumber, pageContents);
                })
            ).get();

            if (structured) {
                pool.submit(() ->
                    IntStream.range(0, totalPages).parallel().forEach(pageNumber -> {
                        if (!shouldProcessPage(pageNumber, pagesToProcess)) return;
                        propagateState.run();
                        HeadingProcessor.processHeadings(contents.get(pageNumber), false);
                    })
                ).get();
            }

            boolean paddleEnabled = paddleUrl != null && !"".equals(paddleUrl);
            if (!paddleEnabled) {
                LOGGER.log(Level.INFO, "No OCR service configured; skipping formula recognition to avoid false positives.");
            }
            // Process each page: first chart/flowchart screenshots, then formula screenshots.
            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                List<IObject> pageContents = contents.get(pageNumber);
                ImagesUtils imagesUtils = new ImagesUtils();
                List<IObject> shapeChunks = pageContents.stream()
                        .filter(ShapeChunk.class::isInstance)
                        .collect(Collectors.toList());
                // Group ShapeChunks in pageContents by intersection.
                List<List<IObject>> groupedShapeChunks = ShapeRecognizer.groupShapes(shapeChunks);
                if (groupedShapeChunks != null && !groupedShapeChunks.isEmpty()) {
                    BarChartProcessor.processBarChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber);
                    FlowchartProcessor.processFlowchartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber);
                }
                if (paddleEnabled) {
                    long count = pageContents.stream()
                        .filter(c -> c instanceof LineArtChunk && c.getHeight() <= 3 && c.getWidth() <= 300)
                        .count();
                    LOGGER.log(Level.INFO, "Page {0} - LineArtChunk count with height <= 3 and width <= 300 in pageContents: {1}.",
                        new Object[]{pageNumber + 1, count});
                    // Page-level OCR converts pixel-space boxes back to PDF user units
                    // using the page's real width/height (from per-page arrays).
                    LineArtProcessor.processLineArtGroups(pageContents, pageNumber, imagesUtils, paddleUrl,
                        inputPdfName, pageWidths[pageNumber], pageHeights[pageNumber], basicFormulaRecognize);
                }
                ConsecutiveImageProcessor.processConsecutiveImages(pageContents, pageNumber, imagesUtils);
            }

            // Sequential ID assignment (must be in page order, before CaptionProcessor)
            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                if (shouldProcessPage(pageNumber, pagesToProcess)) {
                    setIDs(contents.get(pageNumber));
                }
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
            // Unwrap ForkJoinPool's ExecutionException so the IO message names the real cause
            // (NPE from a missed ThreadLocal propagation, UncheckedIOException from
            // ContentFilterProcessor, RuntimeException from StreamTableProcessor, etc.).
            // Without this, PulsarService only sees "Parallel page processing failed" with no
            // hint at which sub-step failed — making the ELK error.stack_trace field the only
            // way to triage. surfacing the cause class+message here lets Kibana message-only
            // queries narrow it down.
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            throw new IOException("Parallel page processing failed ("
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")", e);
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

    public static CustomOutputResult generateCustomOutputs(String inputPdfName, List<List<IObject>> contents, Config config,
                                       Map<Long, ElementMetadata> elementMetadata) throws IOException {
        return JsonWriter.writeToCustomJson(inputPdfName, config.getOutputFolder(), contents, elementMetadata,
            null, config.isIncludeHeaderFooter(), config);
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
            // veraPDF is strict about cross-reference validation and refuses
            // some real-world PDFs that PDFBox still reads successfully — the
            // most common symptom is an empty page tree ("Pages not found")
            // caused by a partially corrupt xref table. If PDFBox can load
            // the file, re-saving it rebuilds the xref on serialization and
            // lets veraPDF parse the result. Any other IOException (truncated
            // body, malformed header, encryption wrong, ...) cannot be fixed
            // this way, so we surface a friendly message instead of letting
            // the raw veraPDF IOException leak as a stack trace.
            File repaired = tryRepairPdfWithPdfBox(pdfName, cause);
            if (repaired != null) {
                try {
                    pdDocument = new PDDocument(repaired.getAbsolutePath());
                    LOGGER.log(Level.WARNING,
                        "veraPDF could not parse '" + displayName(pdfName)
                            + "' because its xref table was rejected ("
                            + cause.getMessage() + "). PDFBox rebuilt the xref on the fly; "
                            + "processing continues from the repaired copy at '"
                            + repaired.getAbsolutePath() + "'.");
                } catch (IOException retryCause) {
                    // Repair did not help; drop the temp file and surface
                    // the original cause rather than the after-repair error.
                    deleteRepairedPdfTempFile();
                    throw new InvalidPdfFileException(
                        "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
                        cause);
                }
            } else {
                throw new InvalidPdfFileException(
                    "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
                    cause);
            }
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
        ShapeRecognizer.recognize(document, extractPageFillBoxes(pdfName, pdDocument.getNumberOfPages()));
        LinesPreprocessingConsumer linesPreprocessingConsumer = new LinesPreprocessingConsumer();
        linesPreprocessingConsumer.findTableBorders();
        /*linesPreprocessingConsumer.getTableBorders().forEach(builders -> builders.forEach(builder -> {
            TableBorder border = new TableBorder(builder);
            boolean badTable = border.isBadTable();
        }));*/
        StaticContainers.setTableBordersCollection(new TableBordersCollection(linesPreprocessingConsumer.getTableBorders()));
    }

    /**
     * Attempts to rescue a PDF that veraPDF rejected but PDFBox can still read.
     *
     * <p>veraPDF's xref validator is strict about cross-reference tables and throws
     * an empty page tree ("Pages not found") for some real-world PDFs — typically
     * downloads with a partially corrupt xref section — even though the body is
     * intact. PDFBox's lenient parser recovers the page tree, and re-saving
     * through PDFBox serializes a fresh xref table on disk. Reloading that copy
     * with veraPDF then succeeds.</p>
     *
     * <p>Only the specific "Pages not found" symptom is known to be xref-related;
     * other IOExceptions (truncated body, malformed header, wrong password) are
     * unaffected by a re-save, so we ignore them and let the caller surface the
     * original error.</p>
     *
     * <p>The returned file is registered with the JVM {@code deleteOnExit} hook
     * for the long-running case; {@link #preprocessing} additionally deletes it
     * immediately after veraPDF has finished parsing so the temp file does not
     * linger for the lifetime of a server process.</p>
     *
     * @param pdfName the original PDF path that veraPDF rejected
     * @param cause   the IOException thrown by veraPDF; used to gate the recovery
     *                on the recoverable symptom only
     * @return the repaired temp file, or {@code null} if recovery was not
     *         applicable or failed
     */
    private static File tryRepairPdfWithPdfBox(String pdfName, IOException cause) {
        if (!"Pages not found".equals(cause.getMessage())) {
            // A re-save only fixes xref-related failures. Anything else
            // (truncated body, encrypted, etc.) wastes effort and risks
            // masking the real error.
            return null;
        }
        Path repairedPath = null;
        org.apache.pdfbox.pdmodel.PDDocument boxDoc = null;
        try {
            boxDoc = Loader.loadPDF(new File(pdfName));
            repairedPath = Files.createTempFile("opendataloader-pdf-repaired-", ".pdf");
            File repaired = repairedPath.toFile();
            repaired.deleteOnExit();
            boxDoc.save(repaired);
            // Register for prompt cleanup in closePdfResources(); the
            // deleteOnExit hook above is a safety net for abnormal shutdown.
            repairedPdfTempFile = repaired;
            return repaired;
        } catch (IOException repairFailure) {
            if (repairedPath != null) {
                try {
                    Files.deleteIfExists(repairedPath);
                } catch (IOException deleteFailure) {
                    LOGGER.log(Level.WARNING,
                        "Failed to delete unused repair tmp file: " + repairedPath, deleteFailure);
                }
            }
            LOGGER.log(Level.WARNING,
                "PDFBox-based repair attempt failed for '" + displayName(pdfName) + "': "
                    + repairFailure.getMessage());
            return null;
        } finally {
            if (boxDoc != null) {
                try {
                    boxDoc.close();
                } catch (IOException ignored) {
                    // best-effort close; ignore
                }
            }
        }
    }

    /**
     * Deletes the repaired-PDF temp file produced by
     * {@link #tryRepairPdfWithPdfBox} on a previous call to
     * {@link #preprocessing}, if any, and clears the static reference so
     * the next run starts clean.
     *
     * <p>Idempotent: a second call after the temp file has already been
     * removed is a no-op. Errors during deletion are logged but never
     * propagate — temp-file cleanup must not mask the original processing
     * exception that triggered {@link #closePdfResources}.</p>
     */
    private static void deleteRepairedPdfTempFile() {
        File toDelete = repairedPdfTempFile;
        repairedPdfTempFile = null;
        if (toDelete == null) {
            return;
        }
        try {
            Files.deleteIfExists(toDelete.toPath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete repaired-PDF temp file: " + toDelete, e);
        }
    }

    /**
     * Extracts the bounding boxes of filled paths from the raw PDF content stream
     * using PDFBox, per page, in y-up (bottom-left origin) coordinates.
     *
     * <p>These boxes are a fallback source for arrowheads: veraPDF's chunk layer can
     * merge an arrowhead triangle into a larger marked-content container, which loses
     * the bbox-only line-art chunk the shape recognizer normally relies on. Only
     * closed fill paths are kept to limit noise. The boxes are empty for pages with
     * no fills.</p>
     *
     * @param pdfName   the PDF file path
     * @param pageCount expected page count (from the already parsed veraPDF document)
     * @return map of page number (0-based) to fill boxes; never null
     */
    private static Map<Integer, List<BoundingBox>> extractPageFillBoxes(String pdfName, int pageCount) {
        Map<Integer, List<BoundingBox>> pageFillBoxes = new HashMap<>();
        try (org.apache.pdfbox.pdmodel.PDDocument boxDocument = Loader.loadPDF(new File(pdfName))) {
            int pages = Math.min(pageCount, boxDocument.getNumberOfPages());
            for (int pageNumber = 0; pageNumber < pages; pageNumber++) {
                List<BoundingBox> pageBoxes = new ArrayList<>();
                try {
                    PDPage page = boxDocument.getPage(pageNumber);
                    for (GetDrawings.Drawing drawing : GetDrawings.getDrawings(page, pageNumber)) {
                        if (drawing.type != GetDrawings.PaintType.FILL
                                && drawing.type != GetDrawings.PaintType.FILL_STROKE) {
                            continue;
                        }
                        if (!drawing.closePath || drawing.rect == null) {
                            continue;
                        }
                        // PDFBox returns coordinates in PDF user space: origin at the
                        // bottom-left, y increasing upward.  rect.y0 is therefore the
                        // bottom edge and rect.y1 the top edge; no pageHeight flip is
                        // needed.  Previously the values were mirrored, which moved
                        // fallback arrowhead fills to the wrong side of the page and
                        // made the PDFBox fallback fail for merged arrowheads.
                        pageBoxes.add(new BoundingBox(pageNumber,
                                drawing.rect.x0, drawing.rect.y0,
                                drawing.rect.x1, drawing.rect.y1));
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to extract fill drawings for page " + (pageNumber + 1), e);
                }
                if (!pageBoxes.isEmpty()) {
                    pageFillBoxes.put(pageNumber, pageBoxes);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load " + displayName(pdfName) + " for fill extraction; "
                    + "arrowheads will rely on artifacts only", e);
        }
        return pageFillBoxes;
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
