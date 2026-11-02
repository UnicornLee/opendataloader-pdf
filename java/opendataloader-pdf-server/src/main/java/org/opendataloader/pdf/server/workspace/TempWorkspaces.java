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

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Owns the lifecycle of the per-message working directories ({@code in-*},
 * {@code out-*}, {@code ocr-*}) created under {@code pdf.temp.path} and
 * {@code pdf.output.path}.
 *
 * <p>Before this class existed, every call site did
 * {@code Files.createTempDirectory(...)} and a hand-rolled
 * {@code Files.walk(...).sorted(reverseOrder()).forEach(deleteIfExists)} that
 * swallowed {@link IOException}. That combination leaks directories in
 * production in three independent ways:</p>
 * <ol>
 *   <li><b>进程消失</b> - JVM 被 OOMKill / {@code System.exit} / 滚动发布杀死时，
 *       {@code finally} 根本不执行，目录必然残留。只能靠下一次启动或定时清扫。</li>
 *   <li><b>看门狗放弃了 worker</b> - 消息已被判定失败并 ack，但 worker 线程还卡在
 *       不可中断的调用里，它才是唯一会执行清理的人。这里把它创建的目录标记为
 *       <em>orphaned</em>，交给清扫线程重试删除。</li>
 *   <li><b>删除静默失败</b> - 输出目录里还有 {@code xx_images/} 子目录和上百张 png，
 *       只要有一个文件和目录删不掉，父目录就会因为非空而留下，而旧实现连日志都不打。</li>
 * </ol>
 *
 * <p>清理分四层落地：</p>
 * <ol>
 *   <li>{@link #create} / {@link #release} - 正常路径，异常安全（调用方写在 finally 里）；</li>
 *   <li>{@link #orphanOwnedBy} + {@link #sweepOrphans} - 被看门狗放弃的 worker；</li>
 *   <li>{@link #sweep} - 上一个 JVM 实例留下的目录（按目录名前缀 + 空闲时长）；</li>
 *   <li>{@link #releaseAll} - 进程正常退出 / {@code System.exit} 时的 shutdown hook。</li>
 * </ol>
 *
 * <p>{@link #release} 与 {@link #releaseAll} 都不会抛出异常：清理失败只记录告警，
 * 由 {@link #sweep} 在下一轮继续重试，直到目录真正消失。</p>
 */
@Slf4j
public final class TempWorkspaces {

    /** Staged input PDF (and the OCR JSON) live here. */
    public static final String INPUT_PREFIX = "in-";
    /** Generated markdown/json/html/text plus the images sub-directory live here. */
    public static final String OUTPUT_PREFIX = "out-";
    /** Downloaded OCR result JSON for the rebuild-bookmarks consumer. */
    public static final String OCR_PREFIX = "ocr-";

    /**
     * A directory that cannot be deleted in one pass is usually held for a
     * moment by another thread (image writer flush, antivirus/indexer on
     * Windows, NFS attribute cache), so a couple of retries clear the bulk of
     * the transient cases. Anything still failing is reported and retried by
     * the sweeper on its next cycle.
     */
    private static final int DELETE_ATTEMPTS = 3;
    private static final long DELETE_RETRY_PAUSE_MILLIS = 100L;

    /** Directories created by this JVM that have not been released yet. */
    private static final Map<Path, Workspace> ACTIVE = new ConcurrentHashMap<>();

    private TempWorkspaces() {
    }

    private static final class Workspace {
        private final String prefix;
        private final Thread owner;
        /** Set when the watchdog abandoned the owning worker thread. */
        private volatile Instant orphanedAt;

        private Workspace(String prefix, Thread owner) {
            this.prefix = prefix;
            this.owner = owner;
        }
    }

    /**
     * Creates a working directory under {@code base} and registers it for the
     * cleanup layers above. Callers must release it, normally from a
     * {@code finally} block.
     *
     * @param base   parent directory (created if missing)
     * @param prefix one of {@link #INPUT_PREFIX}, {@link #OUTPUT_PREFIX},
     *               {@link #OCR_PREFIX}
     * @return the created directory, never {@code null}
     * @throws IOException if the directory cannot be created - in that case
     *                     nothing is registered, so nothing can leak
     */
    public static Path create(Path base, String prefix) throws IOException {
        Files.createDirectories(base);
        Path dir = Files.createTempDirectory(base, prefix);
        ACTIVE.put(dir, new Workspace(prefix, Thread.currentThread()));
        return dir;
    }

    /**
     * Deletes {@code dir} and forgets it. Null-safe, idempotent and never
     * throws: a failure is logged at WARN and retried by the sweeper.
     */
    public static void release(Path dir) {
        if (dir == null) {
            return;
        }
        if (!deleteRecursively(dir)) {
            // Keep the entry so the sweeper retries. Stamping orphanedAt is what
            // makes sweepOrphans() pick it up: sweep() skips every registered
            // directory, so a registered-but-undeletable one would otherwise only
            // be retried on the next shutdown.
            Workspace workspace = ACTIVE.get(dir);
            if (workspace != null) {
                workspace.orphanedAt = Instant.now();
            }
            log.warn("temp directory {} could not be deleted on release; left to the sweeper", dir);
            return;
        }
        ACTIVE.remove(dir);
    }

    /**
     * Best-effort cleanup of everything this JVM still owns. Wired to the JVM
     * shutdown hook and to Spring's {@code @PreDestroy}, so both a graceful
     * shutdown and {@code System.exit(1)} (watchdog hard limit) clean up.
     */
    public static int releaseAll() {
        int released = 0;
        for (Path dir : new ArrayList<>(ACTIVE.keySet())) {
            if (deleteRecursively(dir)) {
                ACTIVE.remove(dir);
                released++;
            }
        }
        if (released > 0) {
            log.info("released {} temp directories on shutdown", released);
        }
        return released;
    }

    /**
     * Marks every working directory created by {@code owner} as orphaned after
     * the watchdog gave up on that worker. The worker thread stays alive (Java
     * cannot kill it) and is the only code path that would call
     * {@link #release}, so someone else has to take over. The directories are
     * not deleted here: the zombie worker may still be writing, and the sweeper
     * retries until the deletion finally succeeds.
     *
     * @return how many directories were marked
     */
    public static int orphanOwnedBy(Thread owner) {
        if (owner == null) {
            return 0;
        }
        int marked = 0;
        for (Workspace workspace : ACTIVE.values()) {
            if (workspace.owner == owner && workspace.orphanedAt == null) {
                workspace.orphanedAt = Instant.now();
                marked++;
            }
        }
        if (marked > 0) {
            log.warn("{} temp directories marked orphaned after the watchdog abandoned thread {}",
                    marked, owner.getName());
        }
        return marked;
    }

    /**
     * Deletes orphaned working directories whose grace period has expired.
     *
     * @param grace how long to wait after orphaning before touching the
     *              directory, giving a still-running zombie worker a chance to
     *              release it itself (and to stop writing into it)
     * @return how many directories were deleted
     */
    public static int sweepOrphans(Duration grace) {
        Instant cutoff = Instant.now().minus(grace);
        int deleted = 0;
        for (Map.Entry<Path, Workspace> entry : ACTIVE.entrySet()) {
            Instant orphanedAt = entry.getValue().orphanedAt;
            if (orphanedAt == null || orphanedAt.isAfter(cutoff)) {
                continue;
            }
            if (deleteRecursively(entry.getKey())) {
                ACTIVE.remove(entry.getKey());
                log.warn("deleted orphaned temp directory {} (prefix '{}', abandoned at {})",
                        entry.getKey(), entry.getValue().prefix, orphanedAt);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Deletes directories left behind by a previous JVM instance (crash,
     * OOMKill, rolling deploy): every direct child of {@code base} whose name
     * starts with {@code prefix} and that has not been touched for
     * {@code staleGrace}.
     *
     * <p>The idle check is what keeps this safe when several instances share a
     * volume: a directory that is still being written to is recent, and a
     * directory owned by this JVM is in {@link #ACTIVE} and skipped
     * unconditionally.</p>
     *
     * @param base       directory to scan (may not exist; then nothing happens)
     * @param prefix     {@code in-} / {@code out-} / {@code ocr-}
     * @param staleGrace minimum idle time before a directory is considered garbage
     * @return how many directories were deleted
     */
    public static int sweep(Path base, String prefix, Duration staleGrace) {
        if (base == null || !Files.isDirectory(base)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(staleGrace);
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> children = Files.list(base)) {
            children.filter(Files::isDirectory)
                    .filter(child -> child.getFileName().toString().startsWith(prefix))
                    .forEach(candidates::add);
        } catch (IOException e) {
            log.warn("cannot scan {} for stale '{}' directories: {}", base, prefix, e.getMessage());
            return 0;
        }

        int deleted = 0;
        for (Path dir : candidates) {
            if (ACTIVE.containsKey(dir)) {
                continue;
            }
            Instant lastTouched = lastModified(dir);
            if (lastTouched == null || lastTouched.isAfter(cutoff)) {
                continue;
            }
            if (deleteRecursively(dir)) {
                log.warn("swept stale temp directory {} (idle since {}, prefix '{}')", dir, lastTouched, prefix);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * @return number of working directories this JVM still owns (for logging
     *         and diagnostics)
     */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * Deletes a directory tree, retrying a few times walking it.
     *
     * <p>Unlike the previous implementation this walks the whole tree even
     * after an individual delete fails, so one locked file does not keep
     * hundreds of siblings alive, and it records the failure instead of
     * silently dropping it. Read-only files are flipped writable before the
     * retry, which is what makes the same code work on Windows dev machines.</p>
     *
     * @return {@code true} when the directory no longer exists afterwards
     */
    public static boolean deleteRecursively(Path dir) {
        if (dir == null) {
            return true;
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            last = deleteTreeOnce(dir);
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (attempt < DELETE_ATTEMPTS) {
                sleepQuietly();
            }
        }
        log.warn("failed to delete temp directory {} after {} attempts: {}",
                dir, DELETE_ATTEMPTS, last == null ? "unknown" : last.toString());
        return false;
    }

    private static IOException deleteTreeOnce(Path dir) {
        List<IOException> failures = new ArrayList<>(2);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteEntry(file, failures);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Cannot even stat it (permissions, vanished entry): still try
                    // to delete, and record the reason if that fails too.
                    deleteEntry(file, failures);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                    if (exc != null) {
                        failures.add(exc);
                    }
                    deleteEntry(directory, failures);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failures.add(e);
        }
        return failures.isEmpty() ? null : failures.get(0);
    }

    private static void deleteEntry(Path path, List<IOException> failures) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException first) {
            // Windows keeps read-only files undeletable; other filesystems
            // return a transient EBUSY/ENOTEMPTY. Flip the flag and retry once.
            try {
                if (!path.toFile().setWritable(true)) {
                    failures.add(first);
                    return;
                }
                Files.deleteIfExists(path);
            } catch (IOException second) {
                failures.add(second);
            }
        }
    }

    private static Instant lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(DELETE_RETRY_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
