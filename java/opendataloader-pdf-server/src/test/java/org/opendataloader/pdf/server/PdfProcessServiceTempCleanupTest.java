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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendataloader.pdf.server.config.BasicProperties;
import org.opendataloader.pdf.server.config.OssProperties;
import org.opendataloader.pdf.server.config.PaddleProperties;
import org.opendataloader.pdf.server.config.PdfProperties;
import org.opendataloader.pdf.server.config.PulsarProperties;
import org.opendataloader.pdf.server.workspace.TempWorkspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the production issue where {@code in-*} / {@code out-*}
 * directories survived a parse: the HTTP path must leave the staging and output
 * bases empty both on success and on failure.
 */
class PdfProcessServiceTempCleanupTest {

    private static final Path SAMPLE = Path.of("../../samples/pdf/lorem.pdf").toAbsolutePath().normalize();
    private static final Path INVALID_PDF = Path.of("../../samples/pdf/fake-jpg.pdf").toAbsolutePath().normalize();

    @TempDir
    Path base;

    @Test
    void successfulParseLeavesNoWorkingDirectoriesBehind() throws Exception {
        Path inputBase = base.resolve("in");
        Path outputBase = base.resolve("out");
        PdfProcessService service = newService(inputBase, outputBase);

        // JSON output also writes an <name>_images/ sub-directory, which is the tree
        // shape that used to survive a partially failed delete.
        PdfProcessService.ProcessedResult result =
                service.process(SAMPLE.toString(), 1L, null, "json", null, null);

        assertTrue(result.content().length > 0, "the parse itself must still work");
        assertEmpty(inputBase, TempWorkspaces.INPUT_PREFIX);
        assertEmpty(outputBase, TempWorkspaces.OUTPUT_PREFIX);
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void invalidPdfLeavesNoWorkingDirectoriesBehind() {
        Path inputBase = base.resolve("in");
        Path outputBase = base.resolve("out");
        PdfProcessService service = newService(inputBase, outputBase);

        assertThrows(Exception.class, () -> service.process(INVALID_PDF.toString(), 1L, null, "json", null, null));

        assertEmpty(inputBase, TempWorkspaces.INPUT_PREFIX);
        assertEmpty(outputBase, TempWorkspaces.OUTPUT_PREFIX);
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void missingLocalFileLeavesNoWorkingDirectoriesBehind() {
        Path inputBase = base.resolve("in");
        Path outputBase = base.resolve("out");
        PdfProcessService service = newService(inputBase, outputBase);

        assertThrows(IllegalArgumentException.class,
                () -> service.process(base.resolve("nope.pdf").toString(), 1L, null, "json", null, null));

        assertEmpty(inputBase, TempWorkspaces.INPUT_PREFIX);
        assertEmpty(outputBase, TempWorkspaces.OUTPUT_PREFIX);
    }

    private static PdfProcessService newService(Path inputBase, Path outputBase) {
        return new PdfProcessService(
                new BasicProperties(2.0, "test", "", false, false, false, false, false),
                new OssProperties("", "", "", "", "", ""),
                new PaddleProperties(""),
                new PdfProperties(new PdfProperties.PdfTemp(inputBase.toString()),
                        new PdfProperties.PdfOutput(outputBase.toString()), 2),
                new PulsarProperties("", "", "", "", "", "", 1, 1, 0, 0));
    }

    private static void assertEmpty(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(0, children.filter(Files::isDirectory)
                            .filter(child -> child.getFileName().toString().startsWith(prefix))
                            .count(),
                    "leftover '" + prefix + "*' directories under " + dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
