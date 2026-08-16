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

import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared concurrency wrapper around {@link PaddleOcrProcessor#getPaddleResponse}.
 *
 * <p>Paddle OCR is a slow synchronous remote call (3~15 s per image). The naive
 * implementation fires one HTTP request per {@code LineArtChunk} group inside
 * {@code LineArtProcessor.processLineArtGroups} — for a 400-page scanned PDF this
 * can easily exceed 100 sequential calls per page and stall the ForkJoinPool.
 *
 * <p>This class exposes two things on top of {@link PaddleOcrProcessor}:
 * <ul>
 *   <li>a fixed-size {@link ExecutorService} isolated from the main ForkJoinPool,
 *       so blocking HTTP calls cannot starve other page-parallel stages;</li>
 *   <li>a small LRU cache keyed by {@code (imagePath, fileSize, lastModified)}
 *       so the same screenshot never gets OCR'd twice — LineArt merging can
 *       legitimately produce duplicate ImageChunk references when overlapping
 *       neighbours all collapse onto the same bbox, and re-sending identical
 *       payloads is wasted latency.</li>
 * </ul>
 *
 * <p>Callers are expected to use {@link #submitOcrTask(File, int, String)} to
 * obtain a {@link Future} and {@link Future#get} with an overall timeout. The
 * underlying retry policy still lives in the caller
 * ({@code StreamTableProcessor.callPaddleWithRetry},
 * {@code LineArtProcessor.callPaddleWithRetry}); we only handle dispatch +
 * caching here.
 */
public final class PaddleOcrClient {

    private static final Logger LOGGER = Logger.getLogger(PaddleOcrClient.class.getCanonicalName());

    /**
     * Maximum number of OCR results retained in the in-process cache. Each entry
     * stores a {@link TextInOcrAnalysisResultDto} plus a small key, so 256 is
     * enough to cover a typical 400-page document without forcing the cache to
     * grow unbounded when the same screenshot keeps being re-keyed.
     */
    private static final int CACHE_MAX_ENTRIES = 256;

    /**
     * Fixed pool sized at {@code 2 * availableProcessors}. Paddle is I/O bound
     * with long tail latency; doubling the core count gives enough headroom to
     * overlap several concurrent OCR calls without saturating the host with
     * useless connections.
     */
    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

    /** Hard per-call timeout (s) before we stop waiting on the executor. */
    private static final long OCR_TASK_TIMEOUT_SECONDS = 60L;

    /**
     * Thread pool for paddle OCR calls. Lazy-init because the JVM may not have
     * a usable processor count at class load time, and lazy-init also avoids
     * spawning threads when paddle URL is never configured.
     */
    private static volatile ExecutorService EXECUTOR;

    /**
     * LRU cache mapping {@code cacheKey} → OCR result. Synchronized because
     * LineArtProcessor runs inside a ForkJoinPool that may invoke multiple
     * cache lookups concurrently. {@link LinkedHashMap#removeEldestEntry} keeps
     * the map bounded to {@link #CACHE_MAX_ENTRIES}.
     */
    private static final Map<String, TextInOcrAnalysisResultDto> CACHE =
        java.util.Collections.synchronizedMap(new LinkedHashMap<String, TextInOcrAnalysisResultDto>(
            CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TextInOcrAnalysisResultDto> eldest) {
                return size() > CACHE_MAX_ENTRIES;
            }
        });

    private PaddleOcrClient() {
    }

    private static ExecutorService executor() {
        ExecutorService local = EXECUTOR;
        if (local == null) {
            synchronized (PaddleOcrClient.class) {
                local = EXECUTOR;
                if (local == null) {
                    local = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
                        private final AtomicInteger seq = new AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "paddle-ocr-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });
                    EXECUTOR = local;
                    LOGGER.log(Level.INFO, "PaddleOcrClient executor started with {0} threads", POOL_SIZE);
                }
            }
        }
        return local;
    }

    /**
     * Builds a stable cache key for an image file. Uses {@code (path, size,
     * lastModified)} — two scans of the same page rasterised at the same DPI
     * produce the same file content, so size+mtime collapses identical
     * screenshots even if they are written to disk under different names. The
     * {@code null}-tolerant constructor argument keeps callers from having to
     * handle {@link SecurityManager} rejections on file I/O.
     */
    private static String cacheKey(File imageFile) {
        long size = 0L;
        long mtime = 0L;
        try {
            if (imageFile != null && imageFile.exists()) {
                size = imageFile.length();
                mtime = imageFile.lastModified();
            }
        } catch (SecurityException ignored) {
            // Defensive: fall back to path-only key.
        }
        return (imageFile == null ? "<null>" : imageFile.getAbsolutePath()) + "|" + size + "|" + mtime;
    }

    /**
     * Returns a cached OCR result for {@code imageFile} if one is available,
     * otherwise {@code null}. The cache is hit most often when
     * {@code LineArtProcessor} merges overlapping neighbours into the same
     * screenshot and the union box appears under multiple cache keys.
     */
    public static TextInOcrAnalysisResultDto getCached(File imageFile) {
        return CACHE.get(cacheKey(imageFile));
    }

    /**
     * Looks up the cache, and on miss submits an asynchronous paddle call to
     * the shared executor. The returned {@link Future} resolves to the OCR
     * result or throws if the call fails. Callers should still wrap this in
     * their own retry policy; this method does no retrying on its own.
     *
     * @param imageFile  image file to OCR (PNG/JPG)
     * @param fileType    0 = pdf, 1 = image (passed through to Paddle API)
     * @param paddleUrl  PaddleOCR endpoint URL
     * @return future resolving to OCR result; never {@code null}
     */
    public static Future<TextInOcrAnalysisResultDto> submitOcrTask(File imageFile, int fileType, String paddleUrl) {
        final String key = cacheKey(imageFile);
        TextInOcrAnalysisResultDto cached = CACHE.get(key);
        if (cached != null) {
            // Return an already-resolved future so callers don't have to special-case the cache hit.
            FutureTask<TextInOcrAnalysisResultDto> done = new FutureTask<>(new Callable<TextInOcrAnalysisResultDto>() {
                @Override
                public TextInOcrAnalysisResultDto call() {
                    return cached;
                }
            });
            done.run();
            return done;
        }
        final String keyForAfter = key;
        return executor().submit(new Callable<TextInOcrAnalysisResultDto>() {
            @Override
            public TextInOcrAnalysisResultDto call() throws IOException {
                TextInOcrAnalysisResultDto result = PaddleOcrProcessor.getPaddleResponse(imageFile, fileType, paddleUrl);
                if (result != null) {
                    CACHE.put(keyForAfter, result);
                }
                return result;
            }
        });
    }

    /**
     * Convenience: submit + block with a bounded timeout. Equivalent to:
     * <pre>{@code
     *   Future<...> f = submitOcrTask(file, type, url);
     *   return f.get(OCR_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
     * }</pre>
     * Returns {@code null} if the call fails or times out. Never throws —
     * callers can treat {@code null} as "skip OCR for this chunk", matching
     * the existing behaviour of {@code callPaddleWithRetry}.
     */
    public static TextInOcrAnalysisResultDto callWithTimeout(File imageFile, int fileType, String paddleUrl) {
        Future<TextInOcrAnalysisResultDto> future = submitOcrTask(imageFile, fileType, paddleUrl);
        try {
            return future.get(OCR_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            LOGGER.log(Level.WARNING, "Paddle OCR call timed out after " + OCR_TASK_TIMEOUT_SECONDS
                + "s for image: " + imageFile, te);
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Paddle OCR call interrupted for image: " + imageFile, ie);
            return null;
        } catch (ExecutionException ee) {
            LOGGER.log(Level.WARNING, "Paddle OCR call failed for image: " + imageFile, ee.getCause());
            return null;
        }
    }

    /**
     * Shuts the executor down. Called from {@code OpenDataLoaderPDF.shutdown()}
     * (already wired through {@code DocumentProcessor.closePdfResources}) so
     * daemon threads do not keep the JVM alive between documents. Subsequent
     * calls to {@link #submitOcrTask} lazily re-create the executor.
     */
    public static synchronized void shutdown() {
        ExecutorService local = EXECUTOR;
        if (local != null && !local.isShutdown()) {
            local.shutdown();
            try {
                if (!local.awaitTermination(5, TimeUnit.SECONDS)) {
                    local.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                local.shutdownNow();
            }
            EXECUTOR = null;
            LOGGER.log(Level.INFO, "PaddleOcrClient executor shut down");
        }
        CACHE.clear();
    }

    /** Visible for tests / monitoring. */
    public static int cacheSize() {
        return CACHE.size();
    }
}
