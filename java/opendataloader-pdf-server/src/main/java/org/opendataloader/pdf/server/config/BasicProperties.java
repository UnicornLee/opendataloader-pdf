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
package org.opendataloader.pdf.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level {@code basic} block from application-{profile}.yml.
 * {@code is_ocr}, {@code complete_display}, {@code parse_stream_table} and
 * {@code pdf_url_prefix} are bound through Spring Boot's relaxed binding
 * (snake_case to camelCase).
 */
@ConfigurationProperties("basic")
public record BasicProperties(
        @DefaultValue("1.0") double version,
        @DefaultValue("dev") String env,
        @DefaultValue String pdfUrlPrefix,
        @DefaultValue("false") boolean isOcr,
        @DefaultValue("false") boolean isImmediateOcr,
        @DefaultValue("false") boolean completeDisplay,
        @DefaultValue("false") boolean parseStreamTable,
        @DefaultValue("false") boolean formulaRecognize) {
}
