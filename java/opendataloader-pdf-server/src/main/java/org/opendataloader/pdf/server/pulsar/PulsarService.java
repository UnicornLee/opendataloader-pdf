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
package org.opendataloader.pdf.server.pulsar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.opendataloader.pdf.server.PdfProcessService;
import org.opendataloader.pdf.server.PdfProcessService.PulsarProcessResult;
import org.opendataloader.pdf.server.config.BasicProperties;
import org.opendataloader.pdf.server.config.OssProperties;
import org.opendataloader.pdf.server.config.PdfProperties;
import org.opendataloader.pdf.server.config.PulsarProperties;
import org.opendataloader.pdf.server.constant.Global;
import org.opendataloader.pdf.utils.ProcessingDeadline;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Bridges the Pulsar message queue with {@link PdfProcessService}.
 *
 * <p>Lifecycle: a single {@link PulsarClient} plus one producer for the regular
 * result topic, one optional producer for the OCR image-analysis topic (when
 * {@code basic.is_ocr=true}), one consumer for the inbound parse topic, and
 * one optional consumer for the OCR result topic are created in
 * {@link #start()} and torn down in {@link #stop()}. Each consumer runs on its
 * own daemon thread and processes messages synchronously - one
 * {@code consumer.receive()} at a time, blocking per
 * <a href="#">test.py</a>'s reference pattern.</p>
 *
 * <p>Failure semantics (per task clarification): on any processing failure
 * the handler still sends the result message with {@code jsonUrl=""} and
 * acknowledges the inbound message. There is no negative-acknowledge path -
 * downstream observes the empty {@code jsonUrl} and reacts accordingly.</p>
 *
 * <p>Wall-clock watchdog ({@code pulsar.consum_timeout_min}, default 0 =
 * disabled, production 40): each inbound message is executed on its own worker
 * thread and the consumer loop waits at most that long for it. On expiry the
 * item is treated as failed - a result message with {@code jsonUrl=""} is
 * published (same contract as any other failure) and the inbound message is
 * <strong>acknowledged without redelivery</strong>, so the consumer slot is
 * released and the next message can be consumed. The abandoned worker is
 * logged with its full stack, counted, and escalated (error log at
 * {@code count + ocrCount}, process exit at twice that) so a permanent pile-up
 * of runaway documents cannot silently degrade the instance.</p>
 *
 * <p>Why both a watchdog <em>and</em> a deadline: the watchdog is a hard,
 * mechanism-independent bound, but it cannot stop the abandoned computation -
 * Java has no safe way to kill a thread, and {@code shutdownNow()} only works
 * for the parts of the pipeline that respond to interrupts. The cooperative
 * {@link ProcessingDeadline} checkpoints in the core pipeline (armed by the
 * same worker, see below) normally fire <em>before</em> the watchdog and abort
 * the document cleanly through the existing failure path, which means no worker
 * is left behind. The watchdog only catches the residual cases where even the
 * checkpoints cannot fire.</p>
 *
 * <p>{@code ackTimeout} vs {@code consum_timeout_min}: {@code ackTimeout} makes
 * the <em>broker</em> redeliver an unacked message, which for a slow item means
 * a second consumer starts the very same document from scratch. That is exactly
 * the 7x-duplicated-work failure mode observed in production (one oversized
 * announcement was consumed 21 times over 24h and froze the whole subscription).
 * The watchdog replaces it, so {@code pulsar.ack_timeout_seconds} must be
 * {@code 0} (or at least larger than {@code consum_timeout_min}); the two
 * settings are cross-checked in {@link #start()} and a mismatch is reported as
 * an error.</p>
 *
 * <p>Thread-survival safeguard: both consumer loops and both message handlers
 * catch {@link Throwable} rather than {@code Exception}. {@code Error}s (e.g.
 * {@link OutOfMemoryError}, {@link StackOverflowError}) are <em>not</em>
 * {@code Exception} subclasses, so catching {@code Exception} alone let an
 * {@code Error} escape the loop and silently terminate the daemon thread. That
 * is unrecoverable on a Shared subscription: the dead thread's
 * {@code Consumer} stays registered with the broker (so {@code consumers_count}
 * still looks healthy) but nothing ever calls {@code receive()} or
 * {@code acknowledge()} on it again - its in-flight message stays
 * unacknowledged forever and one consumer slot is permanently lost (observed in
 * production as "{@code N} messages never consumed" where {@code N} grows over
 * time, one per killed thread; {@code ackTimeout} cannot recover it because no
 * code path ever returns to that consumer). Catching {@code Throwable} keeps
 * the thread alive and still lets the handler acknowledge the message.</p>
 */
@Slf4j
@Component
public class PulsarService {

    private static final int DOWNLOAD_MAX_ATTEMPTS = 5;

    /** Name prefix of the per-message worker thread that the watchdog supervises. */
    static final String WORKER_THREAD_PREFIX = "pulsar-work-";

    private final PulsarProperties pulsarProperties;
    private final BasicProperties basicProperties;
    private final OssProperties ossProperties;
    private final PdfProperties pdfProperties;
    private final PdfProcessService pdfProcessService;
    private final ObjectMapper objectMapper;

    private PulsarClient client;
    private Producer<byte[]> sendProducer;
    private Producer<byte[]> ocrProducer;
    private final List<Consumer<byte[]>> receiveConsumers = new ArrayList<>();
    private final List<Consumer<byte[]>> ocrReceiveConsumers = new ArrayList<>();

    private volatile boolean running = true;
    private final List<Thread> receiveThreads = new ArrayList<>();
    private final List<Thread> ocrReceiveThreads = new ArrayList<>();

    private final Environment env;

    /**
     * Worker executors that are still alive: one per message currently being
     * processed, plus one per abandoned (timed-out) message whose worker refused
     * to stop. Pruned on normal completion; torn down in {@link #stop()}.
     */
    private final List<ExecutorService> workerExecutors =
            Collections.synchronizedList(new ArrayList<>());

    /** Number of messages that blew the watchdog and were abandoned mid-flight. */
    private final AtomicInteger abandonedWorkers = new AtomicInteger();

    public PulsarService(PulsarProperties pulsarProperties,
                         BasicProperties basicProperties,
                         OssProperties ossProperties,
                         PdfProperties pdfProperties,
                         PdfProcessService pdfProcessService,
                         ObjectMapper objectMapper,
                         Environment env) {
        this.pulsarProperties = pulsarProperties;
        this.basicProperties = basicProperties;
        this.ossProperties = ossProperties;
        this.pdfProperties = pdfProperties;
        this.pdfProcessService = pdfProcessService;
        this.objectMapper = objectMapper;
        this.env = env;
    }

    @PostConstruct
    public void start() {
        try {
            validateTimeoutConfiguration();
            client = PulsarClient.builder()
                    .serviceUrl(pulsarProperties.servers())
                    .authentication(AuthenticationFactory.token(pulsarProperties.token()))
                    .build();

            sendProducer = client.newProducer(Schema.BYTES)
                    .topic(pulsarProperties.sendTopicName())
                    .create();
            log.info("pulsar send producer ready, topic={}", pulsarProperties.sendTopicName());

            if (basicProperties.isOcr() && hasText(pulsarProperties.ocrSendTopicName())) {
                ocrProducer = client.newProducer(Schema.BYTES)
                        .topic(pulsarProperties.ocrSendTopicName())
                        .create();
                log.info("pulsar ocr send producer ready, topic={}", pulsarProperties.ocrSendTopicName());
            }

            if (hasText(pulsarProperties.receiveTopicName())) {
                int consumerCount = Math.max(1, pulsarProperties.count());
                for (int i = 0; i < consumerCount; i++) {
                    Consumer<byte[]> consumer = client.newConsumer(Schema.BYTES)
                            .topic(pulsarProperties.receiveTopicName())
                            .subscriptionName(subscriptionName(pulsarProperties.receiveTopicName()))
                            .subscriptionType(SubscriptionType.Shared)
                            .receiverQueueSize(1)
                            .ackTimeout(pulsarProperties.ackTimeoutSeconds(), TimeUnit.SECONDS)
                            .subscribe();
                    receiveConsumers.add(consumer);
                    receiveThreads.add(startThread("pulsar-receive-" + i,
                            () -> consumeReceiveLoop(consumer)));
                }
                log.info("pulsar receive consumers started, count={}, topic={}, subscription={}",
                        consumerCount,
                        pulsarProperties.receiveTopicName(),
                        subscriptionName(pulsarProperties.receiveTopicName()));
            } else {
                log.warn("pulsar.receive_topic_name is empty, consumer not started");
            }

            if (basicProperties.isOcr() && hasText(pulsarProperties.ocrReceiveTopicName())) {
                int consumerCount = Math.max(1, pulsarProperties.ocrCount());
                for (int i = 0; i < consumerCount; i++) {
                    Consumer<byte[]> consumer = client.newConsumer(Schema.BYTES)
                            .topic(pulsarProperties.ocrReceiveTopicName())
                            .subscriptionName(subscriptionName(pulsarProperties.ocrReceiveTopicName()))
                            .subscriptionType(SubscriptionType.Shared)
                            .receiverQueueSize(1)
                            .ackTimeout(pulsarProperties.ackTimeoutSeconds(), TimeUnit.SECONDS)
                            .subscribe();
                    ocrReceiveConsumers.add(consumer);
                    ocrReceiveThreads.add(startThread("pulsar-ocr-receive-" + i,
                            () -> consumeOcrReceiveLoop(consumer)));
                }
                log.info("pulsar ocr receive consumers started, count={}, topic={}, subscription={}",
                        consumerCount,
                        pulsarProperties.ocrReceiveTopicName(),
                        subscriptionName(pulsarProperties.ocrReceiveTopicName()));
            } else if (basicProperties.isOcr()) {
                log.warn("pulsar.ocr_receive_topic_name is empty, ocr consumer not started");
            }
        } catch (Throwable e) {
            log.error("PulsarService failed to start, consumers/producers are disabled: {}",
                    e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        for (int i = 0; i < receiveConsumers.size(); i++) {
            closeQuietly(receiveConsumers.get(i), "receiveConsumer-" + i);
        }
        for (int i = 0; i < ocrReceiveConsumers.size(); i++) {
            closeQuietly(ocrReceiveConsumers.get(i), "ocrReceiveConsumer-" + i);
        }
        closeQuietly(sendProducer, "sendProducer");
        closeQuietly(ocrProducer, "ocrProducer");
        closeQuietly(client, "pulsarClient");
        for (ExecutorService worker : new ArrayList<>(workerExecutors)) {
            shutdownWorker(worker);
        }
        for (int i = 0; i < receiveThreads.size(); i++) {
            joinQuietly(receiveThreads.get(i), "pulsar-receive-" + i);
        }
        for (int i = 0; i < ocrReceiveThreads.size(); i++) {
            joinQuietly(ocrReceiveThreads.get(i), "pulsar-ocr-receive-" + i);
        }
        log.info("PulsarService stopped");
    }

    // ---------------------------------------------------------------------
    // Receive topic (parse requests)
    // ---------------------------------------------------------------------

    private void consumeReceiveLoop(Consumer<byte[]> consumer) {
        while (running) {
            Message<byte[]> msg;
            try {
                msg = consumer.receive();
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
                log.error("pulsar receive loop error: {}", e.getMessage(), e);
                sleepBriefly();
                continue;
            }
            try {
                runWithWatchdog(consumer, msg, this::handleReceiveMessage);
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
                // Never let anything escape the loop: a dead receive thread leaves its
                // Consumer registered with the broker while nothing consumes from it.
                log.error("pulsar receive watchdog error: {}", e.getMessage(), e);
                sleepBriefly();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Per-message wall-clock watchdog (pulsar.consum_timeout_min)
    // ---------------------------------------------------------------------

    /**
     * Work item executed on the watchdog worker thread. Implementations must fill
     * the two references as early as they can: the watchdog uses them to report
     * and identify an item that is still stuck inside the worker.
     */
    @FunctionalInterface
    private interface MessageHandler {
        void handle(Consumer<byte[]> consumer, Message<byte[]> msg,
                    AtomicReference<Object> businessIdRef,
                    AtomicReference<Map<String, Object>> extendRef,
                    AtomicBoolean abandoned);
    }

    /**
     * Runs one inbound message on a dedicated worker thread under a wall-clock
     * watchdog.
     *
     * <p>When {@code pulsar.consum_timeout_min} is {@code 0} (default) the
     * handler runs inline on the consumer thread, exactly as it did before the
     * watchdog existed. Otherwise the consumer thread waits at most that long:
     * on expiry the item is reported downstream as a failure
     * ({@code jsonUrl=""}), acknowledged <strong>without redelivery</strong>,
     * and the worker is abandoned (see {@link #onMessageTimeout}).</p>
     *
     * <p>The worker also arms {@link ProcessingDeadline} with the same budget so
     * the core pipeline's cooperative checkpoints normally abort the document
     * first - cleanly, leaving no orphan thread behind.</p>
     */
    private void runWithWatchdog(Consumer<byte[]> consumer, Message<byte[]> msg, MessageHandler handler) {
        long timeoutMillis = messageTimeoutMillis();
        AtomicReference<Object> businessIdRef = new AtomicReference<>();
        AtomicReference<Map<String, Object>> extendRef = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean(false);

        if (timeoutMillis <= 0) {
            // Watchdog disabled: keep the historical inline behaviour (and its thread
            // count) so operators can turn the feature off without surprises.
            handler.handle(consumer, msg, businessIdRef, extendRef, abandoned);
            return;
        }

        ExecutorService worker = newWorkerExecutor(consumer.getConsumerName());
        try {
            Future<?> future = worker.submit(() -> {
                ProcessingDeadline.start(timeoutMillis);
                try {
                    handler.handle(consumer, msg, businessIdRef, extendRef, abandoned);
                } catch (Throwable t) {
                    log.error("message handler threw on worker thread: {}", t.getMessage(), t);
                } finally {
                    // Worker threads are per-message, but the budget is a ThreadLocal and
                    // the thread may be reused by the pool, so always clear it.
                    ProcessingDeadline.clear();
                    if (abandoned.get()) {
                        // The watchdog gave up on this item earlier; the worker has now
                        // stopped by itself, so it no longer holds heap. Release its slot
                        // so the escalation counter reflects *live* abandoned workers
                        // rather than a lifetime total.
                        int live = abandonedWorkers.decrementAndGet();
                        log.warn("abandoned worker finished after the watchdog gave up on it, "
                                + "liveAbandonedWorkers={}", live);
                        workerExecutors.remove(worker);
                        worker.shutdown();
                    }
                }
            });
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            onMessageTimeout(consumer, msg, businessIdRef, extendRef, worker);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for message processing, businessId={}", businessIdRef.get());
            acknowledgeQuietly(consumer, msg, businessIdRef.get());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("message worker failed, businessId={}: {}", businessIdRef.get(), cause.getMessage(), cause);
            acknowledgeQuietly(consumer, msg, businessIdRef.get());
        } finally {
            if (!abandoned.get()) {
                worker.shutdown();
                workerExecutors.remove(worker);
            }
        }
    }

    /**
     * Handles an item whose worker exceeded {@code pulsar.consum_timeout_min}:
     * publish the failure result, acknowledge the inbound message so the broker
     * never redelivers it, abandon the worker, and escalate if such workers pile up.
     */
    private void onMessageTimeout(Consumer<byte[]> consumer, Message<byte[]> msg,
                                  AtomicReference<Object> businessIdRef,
                                  AtomicReference<Map<String, Object>> extendRef,
                                  ExecutorService worker) {
        int abandoned = abandonedWorkers.incrementAndGet();
        Object businessId = businessIdRef.get();
        Map<String, Object> extend = extendRef.get();
        if (businessId == null) {
            // The worker never reached the payload parsing (e.g. it is stuck in the
            // download). Recover the identity here so the failure report is usable.
            Map<String, Object> inbound = parseInbound(msg);
            businessId = inbound == null ? null : inbound.get("businessId");
            extend = inbound == null ? null : uncheckedMap(inbound.get("extend"));
        }
        log.error("message processing TIMEOUT after {} min, force-ack and skip (no redelivery): "
                + "businessId={}, consumer={}, abandonedWorkers={}. Stuck worker stack:{}",
                pulsarProperties.consumTimeoutMin(), businessId, consumer.getConsumerName(),
                abandoned, describeWorkerThreads());
        // Same failure contract as every other failure: downstream sees jsonUrl="" and
        // decides what to do with it (e.g. retry_pdf_parse). Per the agreed semantics
        // there is deliberately NO redelivery on the Pulsar side.
        sendResultMessage("", businessId, extend);
        acknowledgeQuietly(consumer, msg, businessId);
        // Best effort: only helps for code paths that honour interrupts. The core
        // pipeline's ProcessingDeadline checkpoints are the mechanism that actually
        // stops a runaway document; a worker that ignores interrupts stays until it
        // finishes, hence the escalation below.
        worker.shutdownNow();
        escalateIfTooManyAbandoned(abandoned);
    }

    /**
     * @return configured watchdog budget in milliseconds; {@code 0} disables it
     */
    private long messageTimeoutMillis() {
        return Math.max(0L, TimeUnit.MINUTES.toMillis(pulsarProperties.consumTimeoutMin()));
    }

    /**
     * One abandoned worker per consumer slot is expected when a poison oversized
     * document shows up; beyond that the instance is degrading and operators must
     * hear about it.
     */
    private int abandonedSoftLimit() {
        return Math.max(1, pulsarProperties.count() + pulsarProperties.ocrCount());
    }

    /**
     * Twice the soft limit: abandoned workers keep their heap (page contents,
     * images) alive, so at this point restarting is strictly better than sliding
     * into an OOM. Restarting is recoverable; a slow-motion OOM is not.
     */
    private int abandonedHardLimit() {
        return 2 * abandonedSoftLimit();
    }

    private void escalateIfTooManyAbandoned(int abandoned) {
        int hardLimit = abandonedHardLimit();
        if (abandoned >= hardLimit) {
            log.error("abandoned message workers reached the hard limit ({} >= {}); exiting so the "
                    + "orchestrator restarts this instance with a clean heap. "
                    + "Investigate the timed-out businessIds above.", abandoned, hardLimit);
            System.exit(1);
        } else if (abandoned >= abandonedSoftLimit()) {
            log.error("abandoned message workers reached the soft limit ({} >= {}); consumption "
                    + "continues but this instance is degrading.", abandoned, abandonedSoftLimit());
        }
    }

    private ExecutorService newWorkerExecutor(String consumerName) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, WORKER_THREAD_PREFIX + consumerName);
            t.setDaemon(true);
            return t;
        });
        workerExecutors.add(worker);
        return worker;
    }

    private static void shutdownWorker(ExecutorService worker) {
        try {
            worker.shutdownNow();
        } catch (Throwable e) {
            // best effort
        }
    }

    /**
     * Dumps the stack of every live worker thread. Attached to the timeout error
     * log so the next occurrence can be triaged from Kibana alone - the previous
     * production incident required a manual {@code jcmd Thread.print} on the pod.
     */
    private static String describeWorkerThreads() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.getThreadInfo(bean.getAllThreadIds(), Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : infos) {
            if (info == null || info.getThreadName() == null
                    || !info.getThreadName().startsWith(WORKER_THREAD_PREFIX)) {
                continue;
            }
            sb.append(System.lineSeparator()).append(info);
        }
        return sb.length() == 0 ? " <none>" : sb.toString();
    }

    private Map<String, Object> parseInbound(Message<byte[]> msg) {
        try {
            return objectMapper.readValue(
                    new String(msg.getData(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Throwable e) {
            log.warn("cannot parse inbound payload for timeout reporting: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /**
     * Cross-checks {@code pulsar.ack_timeout_seconds} against
     * {@code pulsar.consum_timeout_min}. An ack timeout shorter than the watchdog
     * makes the broker redeliver an item that is still being processed, which is
     * precisely the duplicate-work storm this watchdog replaces.
     */
    private void validateTimeoutConfiguration() {
        int ackTimeoutSeconds = pulsarProperties.ackTimeoutSeconds();
        long watchdogMillis = messageTimeoutMillis();
        if (watchdogMillis <= 0 || ackTimeoutSeconds <= 0) {
            return;
        }
        if (TimeUnit.SECONDS.toMillis(ackTimeoutSeconds) < watchdogMillis) {
            log.error("pulsar.ack_timeout_seconds={}s is SHORTER than pulsar.consum_timeout_min={}min. "
                    + "The broker will redeliver every message that outlives the ack timeout while the "
                    + "watchdog is still waiting, so one slow document gets processed by several "
                    + "consumers at once (observed in production: 21 duplicate deliveries of a single "
                    + "message). Set ack_timeout_seconds=0 to disable broker-side redelivery and let "
                    + "the watchdog own the timeout.",
                    ackTimeoutSeconds, pulsarProperties.consumTimeoutMin());
        }
    }

    private void handleReceiveMessage(Consumer<byte[]> consumer, Message<byte[]> pulsarMsg,
                                      AtomicReference<Object> businessIdRef,
                                      AtomicReference<Map<String, Object>> extendRef,
                                      AtomicBoolean abandoned) {
        String jsonUrl = "";
        byte[] ocrJsonBytes = new byte[0];
        Object businessId = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> extend = null;
        Path inputDir = null;
        Path outputDir = null;

        try {
            String payload = new String(pulsarMsg.getData(), StandardCharsets.UTF_8);
            Map<String, Object> inbound = objectMapper.readValue(
                    payload, new TypeReference<Map<String, Object>>() {});
            businessId = inbound.get("businessId");
            extend = (Map<String, Object>) inbound.get("extend");
            // Published to the watchdog as early as possible: if this worker is
            // abandoned later, these references are all it has to identify the item.
            businessIdRef.set(businessId);
            extendRef.set(extend);
            String fileUrl = resolveFileUrl(asString(inbound.get("fileUrl")));
            /*if (shouldSkipAnnualReport(extend) && env.acceptsProfiles(Profiles.of("prod"))) {
                log.info("Skip annual report, businessId={}, fileUrl={}", businessId, fileUrl);
                acknowledgeQuietly(consumer, pulsarMsg, businessId);
                return;
            }*/

            if (fileUrl == null || fileUrl.isBlank()) {
                log.warn("inbound fileUrl is empty, businessId={}", businessId);
            } else {
                String baseName = extractPdfBaseName(fileUrl);
                if (baseName == null) {
                    log.warn("file is not a PDF, fileUrl={}, businessId={}", fileUrl, businessId);
                } else {
                    inputDir = Files.createTempDirectory(resolveTempBase(), "in-");
                    Path inputPdf = inputDir.resolve(baseName + ".pdf");
                    download(fileUrl, inputPdf);
                    log.info("download pdf success, businessId={}, file={}", businessId, inputPdf);

                    PulsarProcessResult result = pdfProcessService.processForPulsar(
                            inputPdf.toString(), businessId, fileUrl, extend);
                    jsonUrl = result.jsonUrlOrPath();
                    ocrJsonBytes = result.ocrJsonBytes() == null ? new byte[0] : result.ocrJsonBytes();
                    outputDir = result.outputDir();
                    log.info("process pdf success, businessId={}, jsonUrl={}, ocrJsonBytesLen={}",
                            businessId, jsonUrl, ocrJsonBytes.length);

                    if (basicProperties.isOcr() && ocrProducer != null
                            && !jsonUrl.isEmpty() && ocrJsonBytes.length > 0) {
                        sendOcrPayload(ocrJsonBytes, jsonUrl, extend);
                    }
                }
            }
        } catch (Throwable e) {
            log.error("handleReceiveMessage failed, businessId={}: {}", businessId, e.getMessage(), e);
        }

        if (abandoned.get()) {
            // The watchdog already published the failure result and acknowledged the
            // message; publishing/acking again for an abandoned item would double-report
            // it downstream. Only the local temp cleanup below is still worth doing.
            log.warn("message was abandoned by the watchdog, skip result publish and ack, businessId={}",
                    businessId);
        } else {
            boolean sendResult = !basicProperties.completeDisplay() || ocrJsonBytes.length == 0;
            if (sendResult) {
                sendResultMessage(jsonUrl, businessId, extend);
            } else {
                log.info("complete_display=true and ocr json present, skip send_topic_name, businessId={}",
                        businessId);
            }
        }

        // Local cleanup runs AFTER downstream messages have been published
        if (outputDir != null) {
            deleteRecursively(outputDir);
        }
        if (inputDir != null) {
            deleteRecursively(inputDir);
        }

        if (!abandoned.get()) {
            acknowledgeQuietly(consumer, pulsarMsg, businessId);
        }
    }

    private String resolveFileUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return fileUrl;
        }
        String lower = fileUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return fileUrl;
        }
        String prefix = basicProperties.pdfUrlPrefix();
        if (prefix == null || prefix.isBlank()) {
            return fileUrl;
        }
        return prefix.endsWith("/") ? prefix + fileUrl : prefix + "/" + fileUrl;
    }

    // ---------------------------------------------------------------------
    // OCR receive topic (rebuild bookmarks)
    // ---------------------------------------------------------------------

    private void consumeOcrReceiveLoop(Consumer<byte[]> consumer) {
        while (running) {
            Message<byte[]> msg;
            try {
                msg = consumer.receive();
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
                log.error("pulsar ocr receive loop error: {}", e.getMessage(), e);
                sleepBriefly();
                continue;
            }
            try {
                runWithWatchdog(consumer, msg, this::handleOcrReceiveMessage);
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
                log.error("pulsar ocr receive watchdog error: {}", e.getMessage(), e);
                sleepBriefly();
            }
        }
    }

    private void handleOcrReceiveMessage(Consumer<byte[]> consumer, Message<byte[]> pulsarMsg,
                                         AtomicReference<Object> businessIdRef,
                                         AtomicReference<Map<String, Object>> extendRef,
                                         AtomicBoolean abandoned) {
        Object businessId = null;
        String jsonUrl = "";
        Map<String, Object> extend = null;
        Path inputDir = null;
        try {
            Map<String, Object> inbound = objectMapper.readValue(
                    pulsarMsg.getValue(), new TypeReference<Map<String, Object>>() {});
            businessId = inbound.get("businessId");
            extend = (Map<String, Object>) inbound.get("extend");
            businessIdRef.set(businessId);
            extendRef.set(extend);
            Boolean hasError = asBoolean(inbound.get("hasError"));
            String receivedJsonUrl = resolveObsJsonUrl(asString(inbound.get("jsonUrl")));
            String errorMsg = asString(inbound.get("errorMsg"));
            jsonUrl = receivedJsonUrl == null ? "" : receivedJsonUrl;

            if (Boolean.TRUE.equals(hasError)) {
                log.error("OCR upstream reported error, businessId={}, errorMsg={}", businessId, errorMsg);
            } else if (jsonUrl.isEmpty()) {
                log.warn("OCR message jsonUrl is empty, businessId={}", businessId);
            } else {
                String safeName = sanitizeFileName(businessId);
                inputDir = Files.createTempDirectory(resolveTempBase(), "ocr-");
                Path jsonPath = inputDir.resolve(safeName + ".json");
                download(jsonUrl, jsonPath);
                log.info("download ocr json success, businessId={}, path={}", businessId, jsonPath);
                String rebuiltUrl = pdfProcessService.rebuildBookmarksForPulsar(
                        jsonPath.toString(), businessId, extend, jsonUrl);
                jsonUrl = rebuiltUrl == null ? "" : rebuiltUrl;
                log.info("rebuild bookmarks success, businessId={}, jsonUrl={}", businessId, jsonUrl);
            }
        } catch (Throwable e) {
            log.error("handleOcrReceiveMessage failed, businessId={}: {}", businessId, e.getMessage(), e);
        }

        // Per task step 3 the OCR consumer always publishes to send_topic_name
        // (no complete_display branch). On any failure jsonUrl stays "".
        if (abandoned.get()) {
            log.warn("ocr message was abandoned by the watchdog, skip result publish and ack, businessId={}",
                    businessId);
        } else {
            sendResultMessage(jsonUrl, businessId, extend);
        }

        // Local cleanup runs AFTER downstream messages have been published
        if (inputDir != null) {
            deleteRecursively(inputDir);
        }

        if (!abandoned.get()) {
            acknowledgeQuietly(consumer, pulsarMsg, businessId);
        }
    }

    /**
     * Resolves a relative OBS object key (e.g.
     * {@code announcement-pdf-bucket/public/prod/.../xxx.json}) into a full
     * download URL by swapping the bucket name in {@code oss.domain_name}.
     * Mirrors {@code test_ocr.py:205-212}.
     */
    private String resolveObsJsonUrl(String jsonUrl) {
        if (jsonUrl == null || jsonUrl.isBlank()) {
            return jsonUrl;
        }
        String lower = jsonUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return jsonUrl;
        }
        int slash = jsonUrl.indexOf('/');
        if (slash < 0) {
            return jsonUrl;
        }
        String bucketName = jsonUrl.substring(0, slash);
        String otherUrl = jsonUrl.substring(slash + 1);
        String domainName = ossProperties.domainName();
        String permanentBucketName = ossProperties.permanentBucketName();
        if (domainName == null || domainName.isBlank()
                || permanentBucketName == null || permanentBucketName.isBlank()) {
            return jsonUrl;
        }
        String baseUrl = domainName.replace(permanentBucketName, bucketName);
        return baseUrl.endsWith("/") ? baseUrl + otherUrl : baseUrl + "/" + otherUrl;
    }

    // ---------------------------------------------------------------------
    // Outbound message helpers
    // ---------------------------------------------------------------------

    private void sendResultMessage(String jsonUrl, Object businessId, Map<String, Object> extend) {
        if (sendProducer == null) {
            log.error("sendProducer is null, cannot publish result, businessId={}", businessId);
            return;
        }
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("is_ocr", basicProperties.isOcr());
            msg.put("complete_display", basicProperties.completeDisplay());
            msg.put("businessId", businessId);
            msg.put("extend", extend);
            msg.put("jsonUrl", jsonUrl == null ? "" : toRelativeObsUrl(jsonUrl));
            String payload = objectMapper.writeValueAsString(msg);
            log.info("send result message payload: {}", payload);
            sendProducer.send(payload.getBytes(StandardCharsets.UTF_8));
            log.info("send result message success, businessId={}, jsonUrl={}", businessId, jsonUrl);
        } catch (Throwable e) {
            log.error("send result message failed, businessId={}: {}", businessId, e.getMessage(), e);
        }
    }

    private void sendOcrPayload(byte[] ocrJsonBytes, String jsonUrl, Map<String, Object> extend) {
        if (ocrProducer == null) {
            log.error("ocrProducer is null, cannot publish OCR payload, jsonUrl={}", jsonUrl);
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ocrPayload = objectMapper.readValue(
                    ocrJsonBytes, new TypeReference<Map<String, Object>>() {});
            ocrPayload.put("json_url", toRelativeObsUrl(jsonUrl));
            ocrPayload.put("is_ocr", basicProperties.isOcr());
            ocrPayload.put("complete_display", basicProperties.completeDisplay());
            ocrPayload.put("extend", extend);
            String payload = objectMapper.writeValueAsString(ocrPayload);
            log.info("send ocr message payload: {}", payload);
            ocrProducer.send(payload.getBytes(StandardCharsets.UTF_8));
            log.info("send ocr message success, jsonUrl={}", jsonUrl);
        } catch (Throwable e) {
            log.error("send ocr message failed, jsonUrl={}: {}", jsonUrl, e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    /**
     * Converts an absolute OBS URL into a relative bucket/object-key form.
     * For example:
     * {@code https://stock-temp-bucket.obs.cn-north-1.myhuaweicloud.com/public/test/file.json}
     * becomes {@code stock-temp-bucket/public/test/file.json}.
     * Non-HTTP values (e.g. local paths) are returned unchanged.
     */
    private static String toRelativeObsUrl(String jsonUrl) {
        if (jsonUrl == null || jsonUrl.isBlank()) {
            return jsonUrl;
        }
        String lower = jsonUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return jsonUrl;
        }
        try {
            URI uri = URI.create(jsonUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || host.isBlank() || path == null || path.isBlank()) {
                return jsonUrl;
            }
            int dot = host.indexOf('.');
            String bucketName = dot >= 0 ? host.substring(0, dot) : host;
            String objectKey = path.startsWith("/") ? path.substring(1) : path;
            return bucketName + "/" + objectKey;
        } catch (Throwable e) {
            return jsonUrl;
        }
    }

    private static String subscriptionName(String topic) {
        int slash = topic.lastIndexOf('/');
        return slash >= 0 ? topic.substring(slash + 1) : topic;
    }

    private static String extractPdfBaseName(String fileUrl) {
        String fullName;
        try {
            String path = URI.create(fileUrl).getPath();
            if (path == null || path.isEmpty()) {
                fullName = Path.of(fileUrl).getFileName().toString();
            } else {
                fullName = Path.of(path).getFileName().toString();
            }
        } catch (Throwable e) {
            fullName = Path.of(fileUrl).getFileName().toString();
        }
        String lower = fullName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return fullName.substring(0, fullName.length() - 4);
        }
        return null;
    }

    private static String sanitizeFileName(Object businessId) {
        String name = businessId == null ? "unknown" : String.valueOf(businessId);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        String safe = sb.toString();
        return safe.isEmpty() ? "unknown" : safe;
    }

    private Path resolveTempBase() {
        String configured = pdfProperties.temp() == null ? "" : pdfProperties.temp().path();
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
        try {
            return Files.createDirectories(Path.of(configured));
        } catch (IOException e) {
            log.warn("pdf.temp.path '{}' cannot be created, fallback to system temp", configured, e);
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
    }

    private void download(String url, Path target) throws IOException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2))
                        .GET()
                        .build();
                HttpResponse<Path> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofFile(target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE));
                if (response.statusCode() / 100 == 2) {
                    return;
                }
                throw new IOException("download failed, http " + response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("download interrupted", e);
            } catch (IOException e) {
                last = e;
                log.warn("download attempt {}/{} failed for {}: {}",
                        attempt, DOWNLOAD_MAX_ATTEMPTS, url, e.getMessage());
            }
        }
        throw new IOException("download failed after " + DOWNLOAD_MAX_ATTEMPTS + " attempts: " + url, last);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private Thread startThread(String name, Runnable runnable) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void acknowledgeQuietly(Consumer<byte[]> consumer, Message<byte[]> msg, Object businessId) {
        if (consumer == null || msg == null) {
            return;
        }
        try {
            consumer.acknowledge(msg);
        } catch (Throwable e) {
            log.error("acknowledge failed, businessId={}: {}", businessId, e.getMessage(), e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable, String name) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable e) {
            log.warn("close {} failed: {}", name, e.getMessage());
        }
    }

    private static void joinQuietly(Thread t, String name) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.warn("thread {} did not stop within timeout", name);
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Boolean asBoolean(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static boolean shouldSkipAnnualReport(Map<String, Object> extend) {
        if (extend == null || !extend.containsKey("newTypes")) {
            return false;
        }
        if (!(extend.get("newTypes") instanceof List<?> newTypes)) {
            return false;
        }
        for (Object type : newTypes) {
            if (Global.ANNUAL_REPORT_TYPE_CODE.equals(type)) {
                return true;
            }
        }
        return false;
    }
}
