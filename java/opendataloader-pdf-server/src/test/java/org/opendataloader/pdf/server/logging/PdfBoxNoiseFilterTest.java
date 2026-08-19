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
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PdfBoxNoiseFilter}.
 *
 * The filter must DENY the exact format strings PDFBox 3.0.4 / veraPDF 1.31.x
 * emit when they hit a malformed PDF on every page (recover-and-continue
 * pattern) and must remain NEUTRAL for everything else so unrelated real
 * errors from the same loggers still surface.
 */
class PdfBoxNoiseFilterTest {

    private TurboFilter filter;
    private Logger logger;

    @BeforeEach
    void setUp() {
        filter = new PdfBoxNoiseFilter();
        filter.start();
        LoggerContext context = new LoggerContext();
        logger = context.getLogger("org.apache.pdfbox.rendering.PageDrawer");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // PageDrawer (PDFBox 3.0.4) — color space missing on rasterize path
        "colorSpace is null, will be rendered as transparency",
        // SetGraphicsStateParameters — broken ExtGState reference
        "name for 'gs' operator not found in resources: /GS1",
        "name for 'gs' operator not found in resources: /GS51",
        // PDFStreamEngine.operatorException — stream operators under-fed
        "Operator m has too few operands: [COSInt{9223372036854775807}]",
        "Operator l has too few operands: [COSFloat{70.737}]",
        "Operator c has too few operands: [COSInt{307}, COSInt{4}, COSInt{7}, COSInt{65952296}, COSInt{205}]",
        "Operator scn has too few operands: [COSFloat{0.48}, COSInt{1}]",
        // veraPDF ChunkParser — font glyph width missing
        "Missing width of glyph with code 31 in font WOQWMF+HelveticaNeue-Bold",
        "Missing width of glyph with code 117 in font UEJDJF+HelveticaNeueLTStd-Bd",
        "Missing width of glyph with code 173 in font RTBROP+HelveticaNeueLTPro-Lt"
    })
    void deniesKnownNoiseMessages(String format) {
        FilterReply reply = filter.decide(null, logger, Level.ERROR, format, null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Real PDFBox errors that must still surface — partial-keyword overlap
        // does NOT count, the filter only matches the full noise format string.
        "java.io.IOException: stream closed unexpectedly",
        "Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O Tools are not installed",
        // Format strings that contain a noise keyword but describe a different
        // condition — must NOT be denied (the full pattern is longer than
        // "colorSpace is null" alone).
        "colorSpace is null, but I'm not really the noise pattern",
        // Distinct real warnings from neighbouring PDFBox code paths
        "Nested arrays are not allowed in an array for TJ operation: [foo, bar]",
        "No current font, will use default",
        "Cannot find ancestor of transparency group; using current group instead"
    })
    void neutralForUnrelatedMessages(String format) {
        FilterReply reply = filter.decide(null, logger, Level.ERROR, format, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void neutralWhenFormatIsNull() {
        // Some Logback events arrive with a null format (e.g. parameterized
        // logging where the caller formatted the message itself). The filter
        // must not blow up and must not deny.
        FilterReply reply = filter.decide(null, logger, Level.ERROR, null, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void neutralWhenFormatIsEmpty() {
        FilterReply reply = filter.decide(null, logger, Level.WARN, "", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void denyRegardlessOfLevel() {
        // The filter matches on the message pattern, not the level. Even
        // though PDFBox currently emits these at ERROR / SEVERE, the filter
        // should DENY if some future version drops them to WARN/INFO — the
        // pattern itself is the noise signature.
        FilterReply reply = filter.decide(null, logger, Level.WARN,
                "colorSpace is null, will be rendered as transparency", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }
}