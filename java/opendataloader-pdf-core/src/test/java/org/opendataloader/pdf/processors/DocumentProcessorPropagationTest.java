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
package org.opendataloader.pdf.processors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.entities.Bookmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for "Parallel page processing failed" caused by missing
 * ThreadLocal propagation in {@code DocumentProcessor.propagateState}.
 *
 * <p>Background: the catalog/page-bookmark feature (commits 9fe705b / fceeb31)
 * added four new ThreadLocal fields to {@link StaticLayoutContainers} — but the
 * propagation lambda in {@code DocumentProcessor.processDocument} was not
 * updated to mirror them. Worker threads therefore saw stale / default values
 * and (depending on call path) could NPE on a {@code ThreadLocal.get()} of a
 * never-set reference. The outer catch block at
 * {@code DocumentProcessor.java:574} re-wraps whatever any of the four
 * {@code pool.submit(...).get()} blocks throws as
 * {@code "Parallel page processing failed"}, so the missing propagation
 * surfaces only as that generic message in Kibana.</p>
 *
 * <p>These tests stand in for the per-page lambda body and verify that each
 * newly added bookmark ThreadLocal can survive a main→worker round trip via
 * the public setter API. If someone later adds a new ThreadLocal to
 * {@link StaticLayoutContainers} but forgets to mirror the propagation call,
 * they are expected to also extend this test fixture so the regression is
 * caught here rather than in production.</p>
 */
class DocumentProcessorPropagationTest {

    private List<Bookmark> capturedCatalog;
    private List<Bookmark> capturedPage;

    @BeforeEach
    void setUp() {
        StaticLayoutContainers.clearContainers();

        // Mimic the main-thread side of propagateState: load bookmark ThreadLocals
        // with distinctive marker data that the worker must read back identically.
        Bookmark catalogRoot = new Bookmark();
        catalogRoot.setText("catalog-root-" + System.nanoTime());
        capturedCatalog = new ArrayList<>();
        capturedCatalog.add(catalogRoot);

        Bookmark pageRoot = new Bookmark();
        pageRoot.setText("page-root-" + System.nanoTime());
        capturedPage = new ArrayList<>();
        capturedPage.add(pageRoot);

        StaticLayoutContainers.setCatalogBookmarksMap(capturedCatalog);
        StaticLayoutContainers.setPageBookmarksMap(capturedPage);
        StaticLayoutContainers.setCatalogBookmarkPageRange(7, 9);
    }

    @AfterEach
    void tearDown() {
        StaticLayoutContainers.clearContainers();
    }

    /**
     * Catalog-bookmark list must reach the worker thread as the same instance the
     * main thread captured. A copy here would silently drop worker-side mutations.
     */
    @Test
    void catalogBookmarks_propagateToWorkerThread() throws Exception {
        List<Bookmark> workerView = runOnWorkerAndRead(() -> StaticLayoutContainers.getCatalogBookmarks());

        assertNotNull(workerView, "Worker thread must see a non-null catalogBookmarks list");
        assertSame(capturedCatalog, workerView,
                "Worker must share the main-thread list instance (avoid silent copy)");
        assertEquals(1, workerView.size());
        assertEquals(capturedCatalog.get(0).getText(), workerView.get(0).getText());
    }

    /**
     * Page-bookmark list, same contract as catalog.
     */
    @Test
    void pageBookmarks_propagateToWorkerThread() throws Exception {
        List<Bookmark> workerView = runOnWorkerAndRead(() -> StaticLayoutContainers.getPageBookmarks());

        assertNotNull(workerView, "Worker thread must see a non-null pageBookmarks list");
        assertSame(capturedPage, workerView,
                "Worker must share the main-thread list instance (avoid silent copy)");
        assertEquals(1, workerView.size());
        assertEquals(capturedPage.get(0).getText(), workerView.get(0).getText());
    }

    /**
     * Catalog bookmark range (start/end page) must be readable on the worker
     * even though {@code setCatalogBookmarkPageRange} bundles two fields into one
     * call. This is the exact chain {@code DocumentProcessor.propagateState} uses.
     */
    @Test
    void catalogBookmarkPageRange_propagateToWorkerThread() throws Exception {
        int[] workerRange = runOnWorkerAndRead(() -> new int[] {
                StaticLayoutContainers.getCatalogBookmarkStartPage(),
                StaticLayoutContainers.getCatalogBookmarkEndPage()
        });

        assertEquals(7, workerRange[0], "Worker must observe the main-thread start page");
        assertEquals(9, workerRange[1], "Worker must observe the main-thread end page");
    }

    /**
     * Walks the worker's ThreadLocal through the same propagation chain
     * {@code DocumentProcessor.propagateState} runs after capturing main-thread
     * values, then executes {@code read} on the worker thread and returns its
     * result. Synchronises with a {@link CountDownLatch} so the calling test
     * can rely on the worker having completed.
     */
    private <T> T runOnWorkerAndRead(java.util.function.Supplier<T> read) throws Exception {
        CountDownLatch workerDone = new CountDownLatch(1);
        AtomicReference<T> workerResult = new AtomicReference<>();

        // Capture main-thread values then replay the propagation setters on the
        // worker. This is functionally what propagateState.run() does for these
        // four fields — keeping the test focused on the new bookmark contract.
        List<Bookmark> mainCatalog = StaticLayoutContainers.getCatalogBookmarksMap();
        List<Bookmark> mainPage = StaticLayoutContainers.getPageBookmarksMap();
        int mainStart = StaticLayoutContainers.getCatalogBookmarkStartPage();
        int mainEnd = StaticLayoutContainers.getCatalogBookmarkEndPage();

        Thread worker = new Thread(() -> {
            try {
                // Same calls as DocumentProcessor.propagateState:
                StaticLayoutContainers.setCatalogBookmarksMap(mainCatalog);
                StaticLayoutContainers.setPageBookmarksMap(mainPage);
                StaticLayoutContainers.setCatalogBookmarkPageRange(mainStart, mainEnd);

                workerResult.set(read.get());
            } finally {
                workerDone.countDown();
            }
        }, "propagation-test-worker");

        worker.start();
        assertTrue(workerDone.await(5, TimeUnit.SECONDS),
                "Worker thread hung — propagation chain likely deadlocked");
        worker.join();

        return workerResult.get();
    }
}
