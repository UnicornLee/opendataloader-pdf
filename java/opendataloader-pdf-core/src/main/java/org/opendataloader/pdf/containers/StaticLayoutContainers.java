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
package org.opendataloader.pdf.containers;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;


public class StaticLayoutContainers {
    protected static final Logger LOGGER = Logger.getLogger(StaticLayoutContainers.class.getCanonicalName());

    private static final ThreadLocal<Long> currentContentId = new ThreadLocal<>();
    private static final ThreadLocal<List<SemanticHeading>> headings = new ThreadLocal<>();
    /**
     * Image-file index, unique across ALL worker threads of ONE document.
     * Must not be a plain ThreadLocal holding an int: image extraction runs on
     * ForkJoinPool workers (chart/formula loop in DocumentProcessor), and
     * per-thread int counters would both NPE on first use (no initial value on
     * workers) and produce duplicate imageFileN.png names across workers,
     * silently overwriting each other's files. Sharing one mutable AtomicInteger
     * instance (by reference, propagated through DocumentProcessor.propagateState)
     * keeps names unique across a document's worker threads.
     *
     * <p>It is deliberately a {@code ThreadLocal<AtomicInteger>} rather than a
     * process-global {@code AtomicInteger}: the production server parses many
     * documents concurrently in the same JVM, and every document start
     * ({@link #clearContainers()}) / JSON output ({@code resetImageIndex}) resets
     * the counter. A process-global counter let one document's reset rewind
     * another document mid-extraction, so its later pages reused low indices and
     * overwrote earlier pages' imageFileN.png. Scoping the counter to the
     * requesting thread (with the instance shared to that run's workers) keeps the
     * reset per-document while preserving within-document cross-worker uniqueness.
     */
    private static final ThreadLocal<AtomicInteger> imageIndex =
        ThreadLocal.withInitial(() -> new AtomicInteger(1));
    private static final ThreadLocal<Boolean> isUseStructTree = new ThreadLocal<>();
    private static final ThreadLocal<String> imagesDirectory = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> embedImages = new ThreadLocal<>();
    private static final ThreadLocal<String> imageFormat = new ThreadLocal<>();
    private static final ThreadLocal<Map<Integer, Double>> replacementCharRatios = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ThreadLocal<Map<String, byte[]>> embeddedImageBytes = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ThreadLocal<List<Bookmark>> catalogBookmarks = ThreadLocal.withInitial(LinkedList::new);
    private static final ThreadLocal<List<Bookmark>> pageBookmarks = ThreadLocal.withInitial(LinkedList::new);
    private static final ThreadLocal<Integer> catalogBookmarkStartPage = new ThreadLocal<>();
    private static final ThreadLocal<Integer> catalogBookmarkEndPage = new ThreadLocal<>();

    public static void clearContainers() {
        currentContentId.set(1L);
        headings.set(Collections.synchronizedList(new LinkedList<>()));
        // Fresh counter instance per document/run so a reset here (called at the
        // start of every preprocessing) cannot rewind a concurrently-processing
        // document that shares this thread-pool's worker threads.
        imageIndex.set(new AtomicInteger(1));
        isUseStructTree.set(false);
        imagesDirectory.set("");
        embedImages.set(false);
        imageFormat.set(Config.IMAGE_FORMAT_PNG);
        replacementCharRatios.get().clear();
        embeddedImageBytes.get().clear();
        catalogBookmarks.get().clear();
        pageBookmarks.get().clear();
        catalogBookmarkStartPage.set(-1);
        catalogBookmarkEndPage.set(-1);
    }

    public static long getCurrentContentId() {
        return currentContentId.get();
    }

    public static long incrementContentId() {
        long id = getCurrentContentId();
        StaticLayoutContainers.setCurrentContentId(id + 1);
        return id;
    }

    public static void setCurrentContentId(long currentContentId) {
        StaticLayoutContainers.currentContentId.set(currentContentId);
    }

    public static String getImagesDirectory() {
        return imagesDirectory.get();
    }

    public static String getImagesDirectoryName() {
        String dir = imagesDirectory.get();
        return dir != null && !dir.isEmpty() ? new File(dir).getName() : "";
    }

    public static void setImagesDirectory(String imagesDirectory) {
        StaticLayoutContainers.imagesDirectory.set(imagesDirectory);
    }

    public static List<SemanticHeading> getHeadings() {
        return headings.get();
    }

    public static void setHeadings(List<SemanticHeading> headings) {
        StaticLayoutContainers.headings.set(headings);
    }

    public static List<Bookmark> getCatalogBookmarks() {
        return catalogBookmarks.get();
    }

    public static void setCatalogBookmarks(List<Bookmark> bookmarks) {
        List<Bookmark> current = catalogBookmarks.get();
        current.clear();
        if (bookmarks != null) {
            current.addAll(bookmarks);
        }
    }

    public static List<Bookmark> getPageBookmarks() {
        return pageBookmarks.get();
    }

    public static void setPageBookmarks(List<Bookmark> bookmarks) {
        List<Bookmark> current = pageBookmarks.get();
        current.clear();
        if (bookmarks != null) {
            current.addAll(bookmarks);
        }
    }

    /**
     * Map-level accessors used by {@code DocumentProcessor.propagateState} so worker
     * threads share the main thread's list instance. The default {@link #setCatalogBookmarks}
     * uses copy semantics (clear + addAll), which is fine for "load fresh data" but
     * breaks propagation — workers would see their own ThreadLocal-initial LinkedList,
     * not the main thread's reference. Mirrors the pattern established by
     * {@link #setReplacementCharRatiosMap} and {@link #setEmbeddedImageBytesMap}.
     */
    public static List<Bookmark> getCatalogBookmarksMap() {
        return catalogBookmarks.get();
    }

    public static void setCatalogBookmarksMap(List<Bookmark> bookmarks) {
        catalogBookmarks.set(bookmarks);
    }

    public static List<Bookmark> getPageBookmarksMap() {
        return pageBookmarks.get();
    }

    public static void setPageBookmarksMap(List<Bookmark> bookmarks) {
        pageBookmarks.set(bookmarks);
    }

    public static void setCatalogBookmarkPageRange(int startPage, int endPage) {
        catalogBookmarkStartPage.set(startPage);
        catalogBookmarkEndPage.set(endPage);
    }

    public static int getCatalogBookmarkStartPage() {
        Integer value = catalogBookmarkStartPage.get();
        return value != null ? value : -1;
    }

    public static int getCatalogBookmarkEndPage() {
        Integer value = catalogBookmarkEndPage.get();
        return value != null ? value : -1;
    }

    public static Boolean isUseStructTree() {
        return isUseStructTree.get();
    }

    public static void setIsUseStructTree(Boolean isUseStructTree) {
        StaticLayoutContainers.isUseStructTree.set(isUseStructTree);
    }

    public static int incrementImageIndex() {
        return imageIndex.get().getAndIncrement();
    }

    public static void resetImageIndex() {
        imageIndex.get().set(1);
    }

    /**
     * Returns this thread's live image-index counter instance. DocumentProcessor
     * captures it on the main request thread and re-installs it on each worker via
     * {@code propagateState} (see {@link #setImageIndex}), so every worker of one
     * document increments the same AtomicInteger while other documents keep their
     * own — the pattern already used for the embedded-image and replacement-ratio
     * maps.
     */
    public static AtomicInteger getImageIndex() {
        return imageIndex.get();
    }

    public static void setImageIndex(AtomicInteger index) {
        imageIndex.set(index);
    }

    public static boolean isEmbedImages() {
        return Boolean.TRUE.equals(embedImages.get());
    }

    public static void setEmbedImages(boolean embedImages) {
        StaticLayoutContainers.embedImages.set(embedImages);
    }

    public static String getImageFormat() {
        String format = imageFormat.get();
        return format != null ? format : Config.IMAGE_FORMAT_PNG;
    }

    public static void setImageFormat(String format) {
        StaticLayoutContainers.imageFormat.set(format);
    }

    public static void setReplacementCharRatio(int pageNumber, double ratio) {
        replacementCharRatios.get().put(pageNumber, ratio);
    }

    public static double getReplacementCharRatio(int pageNumber) {
        return replacementCharRatios.get().getOrDefault(pageNumber, 0.0);
    }

    public static void cacheEmbeddedImageBytes(String absolutePath, byte[] bytes) {
        embeddedImageBytes.get().put(normalizeImageKey(absolutePath), bytes);
    }

    public static byte[] getEmbeddedImageBytes(String absolutePath) {
        return embeddedImageBytes.get().get(normalizeImageKey(absolutePath));
    }

    public static boolean hasEmbeddedImageBytes(String absolutePath) {
        return embeddedImageBytes.get().containsKey(normalizeImageKey(absolutePath));
    }

    // Map-level accessors are used by DocumentProcessor.propagateState so worker threads
    // share the main thread's cache instance. Today the cache is only touched on the main
    // thread, but propagating it eliminates a silent-data-loss trap if generators ever
    // run on workers.
    public static Map<String, byte[]> getEmbeddedImageBytesMap() {
        return embeddedImageBytes.get();
    }

    public static void setEmbeddedImageBytesMap(Map<String, byte[]> map) {
        embeddedImageBytes.set(map);
    }

    // Map-level accessors for replacementCharRatios so worker threads and the main thread
    // share the same per-document map. Without this, ContentFilterProcessor sets ratios on
    // worker threads but the main thread (fallback OCR / TriageProcessor) reads an empty map.
    public static Map<Integer, Double> getReplacementCharRatiosMap() {
        return replacementCharRatios.get();
    }

    public static void setReplacementCharRatiosMap(Map<Integer, Double> map) {
        replacementCharRatios.set(map);
    }

    // Image paths flow through String.format with File.separator (cache write side) and
    // through File.getPath() (cache read side via Base64ImageUtils.toDataUri). The two
    // forms can differ on Windows when the user passes forward-slash output paths
    // (e.g. /tmp/x). Collapse both forms to a single canonical key so writers and readers
    // agree on cache identity.
    private static String normalizeImageKey(String path) {
        return path == null ? null : new File(path).getPath();
    }
}
