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
 * Working-directory settings bound from the {@code pdf} block. The nested
 * {@code pdf.temp} group exposes the directory used to stage input PDFs and
 * {@code pdf.output} group exposes the directory used for generated outputs.
 */
@ConfigurationProperties("pdf")
public record PdfProperties(@DefaultValue PdfTemp temp, @DefaultValue PdfOutput output,
                            /**
                             * Per-message cap on the worker-thread parallelism used by the
                             * core pipeline (per-page extraction loops, chart/formula loop,
                             * OCR screenshot passes). The core clamps this further by the
                             * document's page count, so small documents never spawn more
                             * threads than pages. Sized against the pod's CPU/heap: every
                             * consumer thread can process one message at a time, so the
                             * effective worst-case thread count is roughly
                             * {@code pulsar.count * pdf.threads}, and 300-DPI screenshot
                             * rendering holds ~33MB of raster per worker.
                             */
                            @DefaultValue("4") int threads) {

    /**
     * {@code pdf.temp} sub-group; currently only the path is used.
     */
    public record PdfTemp(@DefaultValue("") String path) {
    }

    /**
     * {@code pdf.output} sub-group; currently only the path is used.
     */
    public record PdfOutput(@DefaultValue("") String path) {
    }
}
