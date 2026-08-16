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
package org.opendataloader.pdf.api;

import org.opendataloader.pdf.hybrid.HybridClientFactory;
import org.opendataloader.pdf.json.JsonWriter;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.processors.PaddleOcrClient;
import org.opendataloader.pdf.processors.PaddleOcrProcessor;
import org.opendataloader.pdf.processors.ProcessingResult;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main entry point for the opendataloader-pdf library.
 * Use the static method {@link #processFile(String, Config)} to process a PDF.
 */
public final class OpenDataLoaderPDF {

    private static final Logger LOGGER = Logger.getLogger(OpenDataLoaderPDF.class.getCanonicalName());

    private OpenDataLoaderPDF() {
    }

    /**
     * Processes a PDF file to extract its content and structure based on the provided configuration.
     *
     * @param inputPdfName The path to the input PDF file.
     * @param config       The configuration object specifying output formats and other options.
     * @return A {@link ProcessingResult} containing timing metadata and output file paths/URLs.
     * @throws IOException If an error occurs during file reading or processing.
     */
    public static ProcessingResult processFile(String inputPdfName, Config config) throws IOException {
        return DocumentProcessor.processFileWithResult(inputPdfName, config);
    }

    /**
     * Rebuilds the bookmark sections (self_bookmarks, catalog_bookmarks, page_bookmarks,
     * and the quality-selected bookmarks) of an existing JSON file produced by
     * {@link #processFile(String, Config)}.
     *
     * <p>The processing pipeline (catalog detection, page bookmark extraction,
     * catalog-page complement, and three-source quality selection) is the same as
     * the one used during full PDF parsing. Other fields in the JSON file
     * (e.g., url, data, extend) are left untouched.</p>
     *
     * <p>If {@link Config#getCustomOptions()} supplies the OBS upload configuration
     * (businessId, basicEnv, pulsarReceiveTopicName, ossTempBucketName, ossEndpoint,
     * ossAccessKey, ossSecretKey, ossDomainName), the rebuilt JSON is uploaded to the
     * OBS temp bucket and the local file is deleted on success. Otherwise the JSON
     * remains on local disk.</p>
     *
     * @param inputJsonName Absolute path of the JSON file to rebuild.
     * @param config        The configuration object. Custom options drive OSS upload.
     * @return A {@link RebuildBookmarksResult} containing the OBS URL or local
     *         absolute path and whether OBS upload succeeded.
     * @throws IOException If the JSON cannot be read, written, or uploaded.
     */
    public static RebuildBookmarksResult rebuildBookmarks(String inputJsonName, Config config) throws IOException {
        return JsonWriter.rebuildBookmarksFromJson(inputJsonName, config);
    }

    /**
     * Shuts down any cached resources used by the library.
     *
     * <p>This method should be called when processing is complete, typically at CLI exit.
     * It releases resources such as HTTP client thread pools used for hybrid mode backends
     * and Paddle OCR.
     */
    public static void shutdown() {
        shutdownQuietly("HybridClientFactory", HybridClientFactory::shutdown);
        shutdownQuietly("PaddleOcrClient", PaddleOcrClient::shutdown);
        shutdownQuietly("PaddleOcrProcessor", PaddleOcrProcessor::shutdown);
    }

    private static void shutdownQuietly(String name, Runnable shutdown) {
        try {
            shutdown.run();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Shutdown of " + name + " failed; continuing with remaining cleanup", e);
        }
    }
}
