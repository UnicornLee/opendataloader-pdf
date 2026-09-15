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
package org.opendataloader.pdf.exceptions;

import java.io.IOException;

/**
 * Thrown when a processing deadline set via
 * {@link org.opendataloader.pdf.utils.ProcessingDeadline} expires.
 *
 * <p>Motivation: some pathological inputs (very large scanned documents with
 * heavy per-page line-art / formula-candidate work) can push a single
 * extraction pass into the hours, which - in a Pulsar consumer - keeps one
 * consumer slot busy far beyond any recovery timeout and eventually starves the
 * whole subscription. Instead of leaving an unbounded computation running, the
 * pipeline checks its remaining budget at well-defined checkpoints (phase
 * boundaries and once per processed page) and aborts as soon as the budget is
 * exhausted.</p>
 *
 * <p>This is a checked subtype of {@link IOException} on purpose: existing
 * callers that already handle {@code IOException} keep working and treat it as
 * a normal "this document failed" outcome, while callers that care about the
 * distinction can catch this type specifically. Note that when the abort
 * happens inside one of the page-parallel phases, {@code DocumentProcessor}
 * re-wraps it as {@code IOException("Parallel page processing failed (...)")}
 * with this exception preserved as the cause.</p>
 *
 * <p>Absent any deadline (the default, {@code ProcessingDeadline} value
 * {@code NEVER}), no instance of this exception is ever produced.</p>
 */
public class ProcessingTimeoutException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description of the checkpoint that observed the expiry,
     *                e.g. {@code "processing deadline exceeded at page 12"}
     */
    public ProcessingTimeoutException(String message) {
        super(message);
    }
}
