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
package org.opendataloader.pdf.server.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempWorkspacesTest {

    @TempDir
    Path base;

    @AfterEach
    void releaseEverything() {
        TempWorkspaces.releaseAll();
    }

    @Test
    void releaseDeletesNestedOutputTree() throws IOException {
        Path dir = TempWorkspaces.create(base, TempWorkspaces.OUTPUT_PREFIX);
        Path images = Files.createDirectories(dir.resolve("input_images"));
        Files.writeString(dir.resolve("input.json"), "{}");
        Files.writeString(images.resolve("image_1.png"), "png");

        TempWorkspaces.release(dir);

        assertFalse(Files.exists(dir), "release must delete the directory and its sub-directories");
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void releaseIsNullSafeAndIdempotent() throws IOException {
        TempWorkspaces.release(null);

        Path dir = TempWorkspaces.create(base, TempWorkspaces.INPUT_PREFIX);
        TempWorkspaces.release(dir);
        TempWorkspaces.release(dir);

        assertFalse(Files.exists(dir));
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void sweepDeletesOnlyStaleLeftoversWithMatchingPrefix() throws IOException {
        Path staleIn = Files.createDirectory(base.resolve("in-stale"));
        Path freshIn = Files.createDirectory(base.resolve("in-fresh"));
        Path staleOcr = Files.createDirectory(base.resolve("ocr-stale"));
        Path staleOut = Files.createDirectory(base.resolve("out-stale"));
        Path active = TempWorkspaces.create(base, TempWorkspaces.INPUT_PREFIX);

        age(staleIn);
        age(staleOcr);
        age(staleOut);

        assertEquals(1, TempWorkspaces.sweep(base, TempWorkspaces.INPUT_PREFIX, Duration.ofMinutes(60)));

        assertFalse(Files.exists(staleIn), "idle in-* directory must be swept");
        assertTrue(Files.exists(freshIn), "a recent directory may still be in use and must survive");
        assertTrue(Files.exists(active), "a directory owned by this JVM must never be swept");
        assertTrue(Files.exists(staleOcr), "the in- pass must not touch ocr-*");
        assertTrue(Files.exists(staleOut), "the in- pass must not touch out-*");

        assertEquals(1, TempWorkspaces.sweep(base, TempWorkspaces.OCR_PREFIX, Duration.ofMinutes(60)));
        assertFalse(Files.exists(staleOcr));
    }

    @Test
    void sweepIgnoresMissingBase() {
        assertEquals(0, TempWorkspaces.sweep(base.resolve("does-not-exist"),
                TempWorkspaces.OUTPUT_PREFIX, Duration.ofMinutes(1)));
    }

    @Test
    void liveWorkspaceIsNotSweptEvenWhenOld() throws IOException {
        Path dir = TempWorkspaces.create(base, TempWorkspaces.OUTPUT_PREFIX);
        Files.writeString(dir.resolve("input.md"), "x");
        age(dir);

        assertEquals(0, TempWorkspaces.sweep(base, TempWorkspaces.OUTPUT_PREFIX, Duration.ofMinutes(60)));
        assertEquals(0, TempWorkspaces.sweepOrphans(Duration.ZERO));
        assertTrue(Files.exists(dir), "a workspace that was created by this JVM is owned, not stale");
    }

    @Test
    void orphanedWorkspaceIsDeletedOnceTheGraceExpires() throws IOException, InterruptedException {
        Path[] holder = new Path[1];
        Thread worker = new Thread(() -> {
            try {
                holder[0] = TempWorkspaces.create(base, TempWorkspaces.INPUT_PREFIX);
                Files.writeString(holder[0].resolve("a.pdf"), "pdf");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, "abandoned-worker");
        worker.start();
        worker.join(5_000);

        assertEquals(0, TempWorkspaces.sweepOrphans(Duration.ofMinutes(5)),
                "a live workspace must not be swept");
        assertEquals(1, TempWorkspaces.orphanOwnedBy(worker));
        assertEquals(1, TempWorkspaces.sweepOrphans(Duration.ZERO),
                "an orphaned workspace must be deleted after its grace period");

        assertFalse(Files.exists(holder[0]));
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void releaseAllEmptiesTheRegistry() throws IOException {
        Path first = TempWorkspaces.create(base, TempWorkspaces.INPUT_PREFIX);
        Path second = TempWorkspaces.create(base, TempWorkspaces.OUTPUT_PREFIX);

        assertEquals(2, TempWorkspaces.releaseAll());

        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertEquals(0, TempWorkspaces.activeCount());
    }

    @Test
    void deleteRecursivelyHandlesMissingPath() {
        assertTrue(TempWorkspaces.deleteRecursively(null));
        assertTrue(TempWorkspaces.deleteRecursively(base.resolve("nope")));
    }

    @Test
    void deeplyNestedTreeIsRemovedCompletely() throws IOException {
        Path dir = TempWorkspaces.create(base, TempWorkspaces.OUTPUT_PREFIX);
        Path nested = Files.createDirectories(dir.resolve("input_images").resolve("deep"));
        Files.writeString(nested.resolve("a.png"), "x");
        Files.createDirectories(dir.resolve("empty"));

        assertTrue(TempWorkspaces.deleteRecursively(dir));
        assertFalse(Files.exists(dir));
        try (Stream<Path> siblings = Files.list(base)) {
            assertEquals(0, siblings.filter(Files::isDirectory).count(),
                    "no half-deleted subtree may be left behind");
        }
    }

    private static void age(Path dir) throws IOException {
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    }
}
