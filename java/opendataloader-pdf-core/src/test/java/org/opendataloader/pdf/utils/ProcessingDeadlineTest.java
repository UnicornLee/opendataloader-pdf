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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.exceptions.ProcessingTimeoutException;

import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the cooperative processing budget. The pipeline checkpoints are
 * no-ops unless a budget is armed, so these tests also pin the "feature off"
 * default that every existing caller relies on.
 */
class ProcessingDeadlineTest {

    @AfterEach
    void tearDown() {
        ProcessingDeadline.clear();
    }

    @Test
    void checkIsNoOpWhenNoBudgetIsArmed() {
        assertDoesNotThrow(() -> ProcessingDeadline.check());
        assertDoesNotThrow(() -> ProcessingDeadline.check("stage"));
        assertDoesNotThrow(() -> ProcessingDeadline.checkUnchecked("stage"));
        assertEquals(ProcessingDeadline.NEVER, ProcessingDeadline.snapshot());
        assertEquals(Long.MAX_VALUE, ProcessingDeadline.remainingMillis());
    }

    @Test
    void startWithNonPositiveTimeoutKeepsTheBudgetDisabled() {
        ProcessingDeadline.start(0);
        assertDoesNotThrow(() -> ProcessingDeadline.check());

        ProcessingDeadline.start(-1);
        assertDoesNotThrow(() -> ProcessingDeadline.check());
        assertEquals(ProcessingDeadline.NEVER, ProcessingDeadline.snapshot());
    }

    @Test
    void checkThrowsOnceTheBudgetIsExhausted() throws InterruptedException {
        ProcessingDeadline.start(50);
        assertDoesNotThrow(() -> ProcessingDeadline.check());

        Thread.sleep(80);

        ProcessingTimeoutException thrown =
            assertThrows(ProcessingTimeoutException.class, () -> ProcessingDeadline.check("page 7"));
        assertTrue(thrown.getMessage().contains("page 7"), thrown.getMessage());
        assertTrue(ProcessingDeadline.expired());
        assertEquals(0L, ProcessingDeadline.remainingMillis());
    }

    @Test
    void clearRemovesTheBudgetSoTheThreadCanBeReused() {
        ProcessingDeadline.start(1);
        ProcessingDeadline.clear();
        assertEquals(ProcessingDeadline.NEVER, ProcessingDeadline.snapshot());
        assertDoesNotThrow(() -> ProcessingDeadline.check());
    }

    @Test
    void explicitDeadlineVariantWorksFromAnotherThread() throws InterruptedException {
        ProcessingDeadline.start(50);
        final long deadline = ProcessingDeadline.snapshot();
        assertTrue(deadline != ProcessingDeadline.NEVER);

        Thread.sleep(80);

        // The ThreadLocal is invisible on a pool thread; the captured value must be enough.
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ProcessingDeadline.check(deadline, "loop2 page 3");
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        worker.join();

        assertNotNull(error.get(), "captured deadline must be enforceable on a worker thread");
        assertTrue(error.get() instanceof ProcessingTimeoutException, String.valueOf(error.get()));
    }

    @Test
    void explicitDeadlineNeverMeansNoCheck() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ProcessingDeadline.check(ProcessingDeadline.NEVER, "loop2 page 3");
                ProcessingDeadline.checkUnchecked(ProcessingDeadline.NEVER, "loop2 page 3");
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        assertDoesNotThrow(() -> worker.join(2000));
        assertEquals(null, error.get());
    }

    @Test
    void uncheckedVariantWrapsTheTimeout() throws InterruptedException {
        ProcessingDeadline.start(50);
        Thread.sleep(80);

        UncheckedIOException thrown =
            assertThrows(UncheckedIOException.class, () -> ProcessingDeadline.checkUnchecked("json output page 4"));
        assertTrue(thrown.getCause() instanceof ProcessingTimeoutException, String.valueOf(thrown.getCause()));
        assertTrue(thrown.getCause().getMessage().contains("json output page 4"));
    }
}
