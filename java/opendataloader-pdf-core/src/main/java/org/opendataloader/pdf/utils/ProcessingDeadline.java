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
package org.opendataloader.pdf.utils;

import org.opendataloader.pdf.exceptions.ProcessingTimeoutException;

import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/**
 * Cooperative processing budget for a single document.
 *
 * <p>Java cannot interrupt arbitrary CPU-bound library code, so an unbounded
 * extraction cannot be "killed" from the outside. This class implements the
 * cooperative alternative: the caller that owns the work item arms a budget on
 * its own thread (typically the thread that will run the whole item), and the
 * pipeline calls {@link #check()} at points where aborting is cheap and safe -
 * phase boundaries and once per page. When the budget is exhausted a
 * {@link ProcessingTimeoutException} (an {@code IOException}) is thrown, which
 * unwinds through the normal failure path.</p>
 *
 * <p>Granularity caveat: a checkpoint can only fire <em>between</em> steps. If a
 * single indivisible step (e.g. one page's line-art scan on a huge scanned page)
 * takes longer than the remaining budget, the abort happens after that step
 * finishes, not during it. This is a deliberate trade-off: it keeps the pipeline
 * free of thread-killing hacks and never leaves veraPDF/PDFBox objects in an
 * inconsistent state. The Pulsar consumer layer therefore also arms a hard
 * watchdog that abandons the item if even the checkpoints fail to fire.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProcessingDeadline.start(timeoutMillis);       // arm, 0 or negative = disabled
 * try {
 *     ... work, calling ProcessingDeadline.check(stage) at checkpoints ...
 * } finally {
 *     ProcessingDeadline.clear();                // always clear: threads are pooled
 * }
 * }</pre>
 *
 * <p>The budget is stored in a {@link ThreadLocal}, so concurrent documents in
 * the same JVM never see each other's deadline. For work that is handed to a
 * worker pool, capture {@link #snapshot()} on the owning thread and pass the
 * value to the {@link #check(long, String)} overload inside the worker - the
 * {@code ThreadLocal} itself is not inherited by pool threads.</p>
 */
public final class ProcessingDeadline {

    /** Sentinel meaning "no deadline"; also returned when no budget is armed. */
    public static final long NEVER = Long.MAX_VALUE;

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private ProcessingDeadline() {
    }

    /**
     * Arms a budget of {@code timeoutMillis} milliseconds on the current thread.
     * A non-positive timeout clears any existing budget (feature disabled).
     *
     * @param timeoutMillis budget in milliseconds; {@code <= 0} disables the check
     */
    public static void start(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            DEADLINE_NANOS.remove();
            return;
        }
        DEADLINE_NANOS.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    /**
     * Removes any budget from the current thread. Must be called in a
     * {@code finally} block because consumer threads are pooled and reused:
     * a stale budget would otherwise abort the next, unrelated document.
     */
    public static void clear() {
        DEADLINE_NANOS.remove();
    }

    /**
     * Returns the current thread's absolute deadline in {@link System#nanoTime()}
     * terms, or {@link #NEVER} when no budget is armed. Capture this on the
     * owning thread and hand it to worker pools via {@link #check(long, String)}.
     */
    public static long snapshot() {
        Long value = DEADLINE_NANOS.get();
        return value == null ? NEVER : value;
    }

    /**
     * Copies a previously captured deadline onto the current thread, so that
     * {@link #check()} / {@link #remainingMillis()} work inside a worker pool.
     *
     * @param deadlineNanos value obtained from {@link #snapshot()} on the owning thread
     */
    public static void inherit(long deadlineNanos) {
        if (deadlineNanos == NEVER) {
            DEADLINE_NANOS.remove();
        } else {
            DEADLINE_NANOS.set(deadlineNanos);
        }
    }

    /**
     * @return milliseconds left on the current thread's budget, or
     *         {@link Long#MAX_VALUE} when no budget is armed; never negative
     */
    public static long remainingMillis() {
        long deadline = snapshot();
        if (deadline == NEVER) {
            return Long.MAX_VALUE;
        }
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(remaining);
    }

    /**
     * @return {@code true} when a budget is armed and already exhausted
     */
    public static boolean expired() {
        long deadline = snapshot();
        return deadline != NEVER && System.nanoTime() - deadline >= 0;
    }

    /**
     * Throws if the current thread's budget is exhausted. No-op when disabled.
     *
     * @throws ProcessingTimeoutException when the budget has been used up
     */
    public static void check() throws ProcessingTimeoutException {
        check((String) null);
    }

    /**
     * Throws if the current thread's budget is exhausted.
     *
     * @param stage short description of the checkpoint, included in the message
     *              so the log pinpoints where the runaway document was abandoned
     * @throws ProcessingTimeoutException when the budget has been used up
     */
    public static void check(String stage) throws ProcessingTimeoutException {
        long deadline = snapshot();
        if (deadline == NEVER) {
            return;
        }
        if (System.nanoTime() - deadline >= 0) {
            throw new ProcessingTimeoutException(describe(stage));
        }
    }

    /**
     * Deadline-explicit variant for code running on a pool thread, where the
     * {@code ThreadLocal} of the owning thread is not visible.
     *
     * @param deadlineNanos absolute deadline from {@link #snapshot()}, or {@link #NEVER}
     * @param stage         short description of the checkpoint
     * @throws ProcessingTimeoutException when the budget has been used up
     */
    public static void check(long deadlineNanos, String stage) throws ProcessingTimeoutException {
        if (deadlineNanos == NEVER) {
            return;
        }
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new ProcessingTimeoutException(describe(stage));
        }
    }

    private static String describe(String stage) {
        return (stage == null || stage.isBlank())
            ? "processing deadline exceeded"
            : "processing deadline exceeded at " + stage;
    }

    /**
     * Unchecked variant for use inside {@code parallel()} lambdas and other
     * {@code Runnable}/{@code Consumer} bodies that cannot declare a checked
     * exception. Wraps {@link ProcessingTimeoutException} in an
     * {@link UncheckedIOException} so it unwinds through
     * {@code ForkJoinPool.submit(...).get()} as an {@link java.util.concurrent.ExecutionException},
     * which is exactly how {@code DocumentProcessor} already surfaces per-page
     * failures.
     *
     * @param stage short description of the checkpoint
     * @throws UncheckedIOException when the budget has been used up
     */
    public static void checkUnchecked(String stage) {
        if (expired()) {
            throw new UncheckedIOException(new ProcessingTimeoutException(describe(stage)));
        }
    }

    /**
     * Deadline-explicit unchecked variant, mirroring {@link #check(long, String)}.
     *
     * @param deadlineNanos absolute deadline from {@link #snapshot()}, or {@link #NEVER}
     * @param stage         short description of the checkpoint
     * @throws UncheckedIOException when the budget has been used up
     */
    public static void checkUnchecked(long deadlineNanos, String stage) {
        if (deadlineNanos != NEVER && System.nanoTime() - deadlineNanos >= 0) {
            throw new UncheckedIOException(new ProcessingTimeoutException(describe(stage)));
        }
    }
}
