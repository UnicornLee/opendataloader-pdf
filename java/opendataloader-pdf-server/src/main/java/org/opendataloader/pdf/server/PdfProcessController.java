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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PdfProcessController {

    private final PdfProcessService pdfProcessService;

    public PdfProcessController(PdfProcessService pdfProcessService) {
        this.pdfProcessService = pdfProcessService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * Processes a PDF referenced by the request body:
     * <ul>
     *   <li>{@code url} (required): absolute local path (e.g. {@code D:\a.pdf})
     *       or an http(s) URL of a PDF</li>
     *   <li>{@code businessId} (optional): Long value echoed back in the
     *       {@code X-Business-Id} response header</li>
     *   <li>{@code extend} (optional): arbitrary map, logged for traceability</li>
     *   <li>{@code format} (optional): markdown (default) | json | html | text</li>
     *   <li>{@code pages} / {@code password} (optional)</li>
     * </ul>
     */
    @PostMapping("/pdf/process")
    public ResponseEntity<byte[]> process(@RequestBody Map<String, Object> request) {
        String url = extractUrl(request);
        Long businessId = extractBusinessId(request);
        Map<String, Object> extend = extractExtend(request);
        String format = request.get("format") instanceof String s ? s : "markdown";
        String pages = request.get("pages") instanceof String s ? s : null;
        String password = request.get("password") instanceof String s ? s : null;

        PdfProcessService.ProcessedResult result;
        try {
            result = pdfProcessService.process(url, businessId, extend, format, pages, password);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to process pdf: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "interrupted while processing pdf", e);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(result.mediaType())
                .contentLength(result.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodeFileName(result.fileName()));
        if (businessId != null) {
            builder.header("X-Business-Id", String.valueOf(businessId));
        }
        return builder.body(result.content());
    }

    private static String extractUrl(Map<String, Object> request) {
        Object url = request.get("url");
        if (!(url instanceof String s) || !StringUtils.hasText(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "url is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static Long extractBusinessId(Map<String, Object> request) {
        Object businessId = request.get("businessId");
        if (businessId == null) {
            return null;
        }
        if (businessId instanceof Number number) {
            return number.longValue();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "businessId must be a number");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractExtend(Map<String, Object> request) {
        Object extend = request.get("extend");
        if (extend == null) {
            return null;
        }
        if (extend instanceof Map) {
            return (Map<String, Object>) extend;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "extend must be an object");
    }

    private static String encodeFileName(String fileName) {
        return java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
