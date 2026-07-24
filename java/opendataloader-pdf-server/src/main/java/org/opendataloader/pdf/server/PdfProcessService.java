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
import org.opendataloader.pdf.server.config.BasicProperties;
import org.opendataloader.pdf.server.config.OssProperties;
import org.opendataloader.pdf.server.config.OutputProperties;
import org.opendataloader.pdf.server.config.PaddleProperties;
import org.opendataloader.pdf.server.config.PdfProperties;
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
    private final OutputProperties outputProperties;

    public PdfProcessService(BasicProperties basicProperties,
                             OssProperties ossProperties,
                             PaddleProperties paddleProperties,
                             PdfProperties pdfProperties,
                             OutputProperties outputProperties) {
        this.basicProperties = basicProperties;
        this.ossProperties = ossProperties;
        this.paddleProperties = paddleProperties;
        this.pdfProperties = pdfProperties;
        this.outputProperties = outputProperties;
    }

    public record ProcessedResult(byte[] content, String fileName, MediaType mediaType) {
    }

    public ProcessedResult process(String url, Long businessId, Map<String, Object> extend,
                                   String format, String pages, String password)
            throws IOException, InterruptedException {
        OutputSpec outputSpec = OutputSpec.of(format);
        log.info("processing pdf, url={}, businessId={}, extend={}, env={}, outputPath={}",
                url, businessId, extend, basicProperties.env(), outputProperties.path());

        Path inputBase = resolveBaseDir(pdfProperties.temp() == null ? "" : pdfProperties.temp().path(),
                "pdf.temp.path (input staging)");
        Path outputBase = resolveBaseDir(outputProperties.path(), "output.path");
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
