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
package org.opendataloader.pdf.server.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Suppresses known-benign PDFBox 3.0.4 / veraPDF 1.31.x ERROR and SEVERE
 * messages that fire on every page of malformed PDFs (broken ExtGState
 * references, missing glyph widths, under-fed stream operators). These are
 * PDFBox's deliberate "keep parsing, recover gracefully" log events, not
 * genuine failures — see opendataloader-pdf docs/memory notes for the
 * rationale.
 *
 * <p>Filtering is keyed on the message format string rather than the logger
 * name. The same loggers ({@code org.apache.pdfbox.rendering.PageDrawer},
 * {@code org.apache.pdfbox.contentstream.PDFStreamEngine},
 * {@code org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters},
 * {@code org.verapdf.gf.model.factory.chunks.ChunkParser}) also emit real
 * errors (NPEs, IOExceptions, JPEG2000 SPI failures) that must still reach
 * the appenders. Matching the full pattern keeps those visible.
 *
 * <p>Performance: this filter runs in the earliest Logback event-handling
 * phase (TurboFilter.decide), so a short-circuit on the first matching
 * pattern keeps the overhead O(1) per event. {@code format == null} is
 * the fast path for parameterless Logback events and is checked first.
 *
 * <p>Scope: this class is intentionally only on the
 * {@code opendataloader-pdf-server} runtime classpath, so the CLI in
 * {@code opendataloader-pdf-cli} is unaffected.
 */
public class PdfBoxNoiseFilter extends TurboFilter {

    private static final String[] NOISE_PATTERNS = {
        // PageDrawer (PDFBox 3.0.4) — color space missing on rasterize path.
        "colorSpace is null, will be rendered as transparency",
        // SetGraphicsStateParameters — broken ExtGState reference. PDFBox
        // logs this at ERROR; PDFStreamEngine catches the resulting
        // MissingResourceException silently when ExtGState is null.
        "name for 'gs' operator not found in resources: /",
        // PDFStreamEngine.operatorException — stream operators under-fed.
        // MissingOperandException is caught and logged at ERROR with the
        // operator name followed by " has too few operands: [...]".
        " has too few operands:",
        // veraPDF ChunkParser — font glyph width missing. ChunkParser uses
        // java.util.logging at SEVERE; the spring-jcl bridge routes it
        // through Logback so this filter sees it.
        "Missing width of glyph with code"
    };

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format == null || format.isEmpty()) {
            return FilterReply.NEUTRAL;
        }
        for (String pattern : NOISE_PATTERNS) {
            if (format.contains(pattern)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}