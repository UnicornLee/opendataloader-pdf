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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.opendataloader.pdf.server.PdfProcessService;
import org.opendataloader.pdf.server.config.PulsarProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Last line of defence for the {@code in-*}/{@code out-*}/{@code ocr-*}
 * working directories: the per-message cleanups in
 * {@link PdfProcessService} and {@code PulsarService} cannot run when the JVM
 * is killed (OOMKill, rolling deploy, {@code System.exit} at the watchdog hard
 * limit) or when the worker that owns the directory was abandoned and never
 * returns. Those are exactly the cases that left directories behind in
 * production, so an external sweeper is required - there is no in-process
 * code path left to rely on.
 *
 * <p>Runs one sweep at startup (clears what the previous instance left in the
 * same {@code /tmp} mount) and then every {@link #SWEEP_INTERVAL_MINUTES}
 * minutes:</p>
 * <ul>
 *   <li>directories this JVM created but the watchdog abandoned
 *       ({@link TempWorkspaces#sweepOrphans});</li>
 *   <li>directories under {@code pdf.temp.path} / {@code pdf.output.path}
 *       whose prefix matches and that have been idle longer than the grace
 *       period (i.e. from a previous JVM instance).</li>
 * </ul>
 *
 * <p>The grace period is derived from {@code pulsar.consum_timeout_min}: a
 * directory whose owner is still processing a message must never be swept, so
 * the floor is {@code 2 x} the watchdog budget with a 60 minute minimum.</p>
 */
@Slf4j
@Component
public class TempWorkspaceSweeper {

    /** Sweep cadence. Cheap (one directory listing per base) and independent of traffic. */
    private static final long SWEEP_INTERVAL_MINUTES = 10;

    /** Wait before deleting a directory whose owner worker was abandoned. */
    private static final Duration ORPHAN_GRACE = Duration.ofMinutes(5);

    /** Never sweep a directory that is younger than this, even if it looks idle. */
    private static final Duration MIN_STALE_GRACE = Duration.ofMinutes(60);

    private final PdfProcessService pdfProcessService;
    private final PulsarProperties pulsarProperties;

    private ScheduledExecutorService scheduler;
    private Thread shutdownHook;

    public TempWorkspaceSweeper(PdfProcessService pdfProcessService, PulsarProperties pulsarProperties) {
        this.pdfProcessService = pdfProcessService;
        this.pulsarProperties = pulsarProperties;
    }

    @PostConstruct
    void start() {
        shutdownHook = new Thread(() -> {
            int released = TempWorkspaces.releaseAll();
            log.info("shutdown hook released {} temp directories", released);
        }, "temp-workspace-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            log.warn("JVM already shutting down; temp directories will be swept on next start");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "temp-workspace-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        // Initial delay 0: clear whatever the previous instance left behind before
        // the first messages start piling up their own directories.
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, 0, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("temp workspace sweeper started, interval={}min, staleGrace={}",
                SWEEP_INTERVAL_MINUTES, staleGrace());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // ignore: the hook is running or the JVM is on its way out
            }
        }
        TempWorkspaces.releaseAll();
        log.info("temp workspace sweeper stopped");
    }

    private void sweepQuietly() {
        try {
            Path inputBase = pdfProcessService.inputBaseDir();
            Path outputBase = pdfProcessService.outputBaseDir();
            Duration staleGrace = staleGrace();
            int removed = TempWorkspaces.sweepOrphans(ORPHAN_GRACE)
                    + TempWorkspaces.sweep(inputBase, TempWorkspaces.INPUT_PREFIX, staleGrace)
                    + TempWorkspaces.sweep(inputBase, TempWorkspaces.OCR_PREFIX, staleGrace)
                    + TempWorkspaces.sweep(outputBase, TempWorkspaces.OUTPUT_PREFIX, staleGrace);
            if (removed > 0) {
                log.warn("temp workspace sweep removed {} leftover directories (active={})",
                        removed, TempWorkspaces.activeCount());
            } else if (log.isDebugEnabled()) {
                log.debug("temp workspace sweep: nothing to do (active={})", TempWorkspaces.activeCount());
            }
        } catch (Throwable t) {
            // The scheduled executor cancels all future runs if a task throws.
            log.error("temp workspace sweep failed: {}", t.getMessage(), t);
        }
    }

    private Duration staleGrace() {
        long timeoutMinutes = Math.max(0L, pulsarProperties.consumTimeoutMin());
        long graceMinutes = Math.max(MIN_STALE_GRACE.toMinutes(), timeoutMinutes * 2);
        return Duration.ofMinutes(graceMinutes);
    }
}
