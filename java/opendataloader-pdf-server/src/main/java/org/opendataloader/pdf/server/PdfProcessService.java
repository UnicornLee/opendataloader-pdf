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
package org.opendataloader.pdf.server;

import lombok.extern.slf4j.Slf4j;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.api.RebuildBookmarksResult;
import org.opendataloader.pdf.processors.ProcessingResult;
import org.opendataloader.pdf.server.config.BasicProperties;
import org.opendataloader.pdf.server.config.OssProperties;
import org.opendataloader.pdf.server.config.PaddleProperties;
import org.opendataloader.pdf.server.config.PdfProperties;
import org.opendataloader.pdf.server.config.PulsarProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Service
public class PdfProcessService {

    private static final String INPUT_NAME = "input";

    private final BasicProperties basicProperties;
    private final OssProperties ossProperties;
    private final PaddleProperties paddleProperties;
    private final PdfProperties pdfProperties;
    private final PulsarProperties pulsarProperties;

    public PdfProcessService(BasicProperties basicProperties,
                             OssProperties ossProperties,
                             PaddleProperties paddleProperties,
                             PdfProperties pdfProperties,
                             PulsarProperties pulsarProperties) {
        this.basicProperties = basicProperties;
        this.ossProperties = ossProperties;
        this.paddleProperties = paddleProperties;
        this.pdfProperties = pdfProperties;
        this.pulsarProperties = pulsarProperties;
    }

    public record ProcessedResult(byte[] content, String fileName, MediaType mediaType) {
    }

    /**
     * Minimal projection of {@link ProcessingResult} for Pulsar consumer paths.
     * {@code ocrJsonLocalPath} is an empty string when the OCR detection step
     * produced no file (matches {@code ProcessingResult.getOcrJsonLocalPath()}
     * semantics; we normalize null to empty here so callers don't need to).
     */
    public record PulsarProcessResult(String jsonUrlOrPath, byte[] ocrJsonBytes, Path outputDir) {
    }

    public ProcessedResult process(String url, Long businessId, Map<String, Object> extend,
                                   String format, String pages, String password)
            throws IOException, InterruptedException {
        OutputSpec outputSpec = OutputSpec.of(format);
        log.info("processing pdf, url={}, businessId={}, extend={}, env={}, outputPath={}",
                url, businessId, extend, basicProperties.env(), pdfProperties.output().path());

        Path inputBase = resolveBaseDir(pdfProperties.temp() == null ? "" : pdfProperties.temp().path(),
                "pdf.temp.path (input staging)");
        Path outputBase = resolveBaseDir(pdfProperties.output() == null ? "" : pdfProperties.output().path(), "output.path");
        Path inputDir = Files.createTempDirectory(inputBase, "in-");
        Path outputDir = Files.createTempDirectory(outputBase, "out-");
        try {
            Path inputPdf = inputDir.resolve(INPUT_NAME + ".pdf");
            if (url.toLowerCase(Locale.ROOT).startsWith("http://")
                    || url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                download(url, inputPdf);
            } else {
                Path local = Path.of(url);
                if (!Files.exists(local)) {
                    throw new IllegalArgumentException("local file does not exist: " + url);
                }
                if (!Files.isRegularFile(local)) {
                    throw new IllegalArgumentException("url is not a regular file: " + url);
                }
                Files.copy(local, inputPdf, StandardCopyOption.REPLACE_EXISTING);
            }

            Config config = new Config();
            config.setOutputFolder(outputDir.toString());
            config.setGenerateJSON(false);
            outputSpec.apply(config);
            if (StringUtils.hasText(pages)) {
                config.setPages(pages);
            }
            if (StringUtils.hasText(password)) {
                config.setPassword(password);
            }

            OpenDataLoaderPDF.processFile(inputPdf.toString(), config);

            Path output = outputDir.resolve(INPUT_NAME + outputSpec.extension);
            if (!Files.exists(output)) {
                if (log.isDebugEnabled()) {
                    try (Stream<Path> files = Files.list(outputDir)) {
                        log.debug("expected output {} missing; generated files: {}", output,
                                files.map(Path::getFileName).map(Path::toString).toList());
                    }
                }
                throw new IOException("expected output was not generated: " + output.getFileName());
            }
            return new ProcessedResult(Files.readAllBytes(output), output.getFileName().toString(),
                    outputSpec.mediaType);
        } finally {
            deleteRecursively(inputDir);
            deleteRecursively(outputDir);
        }
    }

    /**
     * Runs {@link OpenDataLoaderPDF#processFile(String, Config)} against a
     * previously downloaded PDF and returns the URLs/paths the Pulsar consumer
     * layer needs. Compared to {@link #process}, this method:
     * <ul>
     *   <li>always enables JSON output (so the OCR-detection step in
     *       {@code JsonWriter.writeToCustomJson} can produce a {@code _ocr.json});</li>
     *   <li>populates {@code Config.customOptions} with the OSS upload keys so
     *       the generated main JSON is uploaded to the OBS temp bucket and the
     *       local files are cleaned up by {@code DocumentProcessor};</li>
     *   <li>does not download the input (the Pulsar consumer handles that with
     *       its own retry loop) and does not return any HTTP bytes.</li>
     * </ul>
     *
     * <p>The {@code outputDir} is <strong>not</strong> deleted on success - the
     * Pulsar consumer needs to read the {@code _ocr.json} out of it for the OCR
     * step, and the OCR JSON bytes are returned in the result so the consumer
     * can safely delete the directory afterwards. On failure the directory is
     * cleaned up here because the consumer has nothing to clean.</p>
     *
     * @param inputPdf   absolute path of the already-downloaded PDF
     * @param businessId echoed back into the OSS upload keys; coerced to string
     *                   (may be {@link Long} / {@link Integer} / {@link String} from
     *                   Pulsar map deserialization)
     * @param fileUrl    optional original URL of the PDF; currently logged for
     *                   traceability but not forwarded to OBS
     * @param extend     optional {@code extend} map from the inbound Pulsar message;
     *                   currently logged for traceability but not forwarded to OBS
     * @return {@link PulsarProcessResult} carrying the OSS/local URLs, the OCR
     *         JSON bytes (empty array when no OCR JSON was produced), and the
     *         {@code outputDir} the caller must delete after publishing
     * @throws IOException if {@code processFile} fails
     */
    public PulsarProcessResult processForPulsar(String inputPdf, Object businessId, String fileUrl,
                                                Map<String, Object> extend) throws IOException {
        Path outputBase = resolveBaseDir(pdfProperties.output() == null ? "" : pdfProperties.output().path(), "output.path");
        Path outputDir = Files.createTempDirectory(outputBase, "out-");
        boolean succeeded = false;
        try {
            Config config = new Config();
            config.setOutputFolder(outputDir.toString());
            config.setGenerateJSON(true);
            config.setCustomOptions(buildOssCustomOptions(businessId, extend));
            config.getCustomOptions().put("url", fileUrl);

            log.info("processing pdf for pulsar, input={}, businessId={}, fileUrl={}, extend={}, env={}",
                    inputPdf, businessId, fileUrl, extend, basicProperties.env());
            ProcessingResult result = OpenDataLoaderPDF.processFile(inputPdf, config);
            String jsonUrlOrPath = result.getJsonUrlOrPath() == null ? "" : result.getJsonUrlOrPath();
            String ocrJsonLocalPath = result.getOcrJsonLocalPath();
            byte[] ocrJsonBytes = (ocrJsonLocalPath == null || ocrJsonLocalPath.isEmpty())
                    ? new byte[0]
                    : Files.readAllBytes(Path.of(ocrJsonLocalPath));
            succeeded = true;
            return new PulsarProcessResult(jsonUrlOrPath, ocrJsonBytes, outputDir);
        } finally {
            if (!succeeded) {
                deleteRecursively(outputDir);
            }
        }
    }

    /**
     * Runs {@link OpenDataLoaderPDF#rebuildBookmarks(String, Config)} on a
     * previously downloaded OCR JSON and returns its final URL/path. The
     * {@code Config.customOptions} carries the same OSS upload keys as
     * {@link #processForPulsar}, so the rebuilt JSON is uploaded to the OBS
     * temp bucket and the local file is deleted by {@code JsonWriter} on success.
     *
     * @param inputJson        absolute path of the OCR-result JSON
     * @param businessId       used as the OSS object-key suffix
     * @param extend           currently unused; reserved for parity with the
     *                         downstream contract
     * @param originalJsonUrl  reserved for parity with the downstream contract
     *                         (not currently consumed by {@code rebuildBookmarks})
     * @return the JSON's OSS URL, or its local absolute path when OSS upload
     *         is not configured; never {@code null}
     * @throws IOException if rebuild or upload fails
     */
    public String rebuildBookmarksForPulsar(String inputJson, Object businessId,
                                             Map<String, Object> extend, String originalJsonUrl)
            throws IOException {
        Config config = new Config();
        config.setCustomOptions(buildOssCustomOptions(businessId, extend));
        log.info("rebuilding bookmarks for pulsar, input={}, businessId={}, extend={}, originalJsonUrl={}",
                inputJson, businessId, extend, originalJsonUrl);
        RebuildBookmarksResult result = OpenDataLoaderPDF.rebuildBookmarks(inputJson, config);
        return result.getJsonUrlOrPath() == null ? "" : result.getJsonUrlOrPath();
    }

    /**
     * Builds the {@code customOptions} map that drives OSS upload inside the
     * core module. All keys are required (see {@code OssUploadConfig} in
     * opendataloader-pdf-core); missing or blank values cause the core to fall
     * back to local-output mode, which is fine - {@code jsonUrlOrPath} then
     * becomes a local absolute path.
     */
    private Map<String, Object> buildOssCustomOptions(Object businessId, Map<String, Object> extend) {
        Map<String, Object> customOptions = new HashMap<>();
        if (businessId != null) {
            customOptions.put("businessId", businessId);
        }
        if (extend != null) {
            customOptions.put("extend", extend);
        }
        customOptions.put("basicEnv", basicProperties.env());
        customOptions.put("basicIsImmediateOcr", basicProperties.isImmediateOcr());
        customOptions.put("basicParseStreamTable", basicProperties.parseStreamTable());
        customOptions.put("basicFormulaRecognize", basicProperties.formulaRecognize());
        customOptions.put("pulsarReceiveTopicName", pulsarProperties.receiveTopicName());
        customOptions.put("ossTempBucketName", ossProperties.tempBucketName());
        customOptions.put("ossPermanentBucketName", ossProperties.permanentBucketName());
        customOptions.put("ossEndpoint", ossProperties.endpoint());
        customOptions.put("ossAccessKey", ossProperties.accessKey());
        customOptions.put("ossSecretKey", ossProperties.secretKey());
        customOptions.put("ossDomainName", ossProperties.domainName());
        customOptions.put("paddleUrl", paddleProperties.url());
        return customOptions;
    }

    private static Path resolveBaseDir(String configured, String label) {
        if (configured == null || configured.isBlank()) {
            log.debug("{} not configured; falling back to system temp directory", label);
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
        Path dir = Path.of(configured);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            log.warn("{}='{}' cannot be created; falling back to system temp directory",
                    label, configured, e);
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("download failed, http status " + response.statusCode() + " for " + url);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp working directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of the temp working directory
        }
    }

    private enum OutputSpec {
        MARKDOWN(".md", MediaType.parseMediaType("text/markdown; charset=UTF-8")) {
            @Override
            void apply(Config config) {
                config.setGenerateMarkdown(true);
            }
        },
        JSON(".json", MediaType.APPLICATION_JSON) {
            @Override
            void apply(Config config) {
                config.setGenerateJSON(true);
            }
        },
        HTML(".html", MediaType.TEXT_HTML) {
            @Override
            void apply(Config config) {
                config.setGenerateHtml(true);
            }
        },
        TEXT(".txt", MediaType.TEXT_PLAIN) {
            @Override
            void apply(Config config) {
                config.setGenerateText(true);
            }
        };

        final String extension;
        final MediaType mediaType;

        OutputSpec(String extension, MediaType mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        abstract void apply(Config config);

        static OutputSpec of(String format) {
            String normalized = StringUtils.hasText(format)
                    ? format.trim().toLowerCase(Locale.ROOT) : "markdown";
            return switch (normalized) {
                case "markdown", "md" -> MARKDOWN;
                case "json" -> JSON;
                case "html" -> HTML;
                case "text", "txt" -> TEXT;
                default -> throw new IllegalArgumentException(
                        "unsupported format: " + format + " (expected markdown|json|html|text)");
            };
        }
    }
}
