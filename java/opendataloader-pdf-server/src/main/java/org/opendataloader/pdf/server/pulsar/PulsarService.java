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
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
@Slf4j
@Component
public class PulsarService {

    private static final int DOWNLOAD_MAX_ATTEMPTS = 5;

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
        } catch (Exception e) {
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
            try {
                Message<byte[]> msg = consumer.receive();
                handleReceiveMessage(consumer, msg);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("pulsar receive loop error: {}", e.getMessage(), e);
                sleepBriefly();
            }
        }
    }

    private void handleReceiveMessage(Consumer<byte[]> consumer, Message<byte[]> pulsarMsg) {
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
            String fileUrl = resolveFileUrl(asString(inbound.get("fileUrl")));
            if (shouldSkipAnnualReport(extend) && env.acceptsProfiles(Profiles.of("prod"))) {
                log.info("Skip annual report, businessId={}, fileUrl={}", businessId, fileUrl);
                acknowledgeQuietly(consumer, pulsarMsg, businessId);
                return;
            }

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
        } catch (Exception e) {
            log.error("handleReceiveMessage failed, businessId={}: {}", businessId, e.getMessage(), e);
        }

        boolean sendResult = !basicProperties.completeDisplay() || ocrJsonBytes.length == 0;
        if (sendResult) {
            sendResultMessage(jsonUrl, businessId, extend);
        } else {
            log.info("complete_display=true and ocr json present, skip send_topic_name, businessId={}",
                    businessId);
        }

        // Local cleanup runs AFTER downstream messages have been published
        if (outputDir != null) {
            deleteRecursively(outputDir);
        }
        if (inputDir != null) {
            deleteRecursively(inputDir);
        }

        acknowledgeQuietly(consumer, pulsarMsg, businessId);
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
            try {
                Message<byte[]> msg = consumer.receive();
                handleOcrReceiveMessage(consumer, msg);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("pulsar ocr receive loop error: {}", e.getMessage(), e);
                sleepBriefly();
            }
        }
    }

    private void handleOcrReceiveMessage(Consumer<byte[]> consumer, Message<byte[]> pulsarMsg) {
        Object businessId = null;
        String jsonUrl = "";
        Map<String, Object> extend = null;
        Path inputDir = null;
        try {
            Map<String, Object> inbound = objectMapper.readValue(
                    pulsarMsg.getValue(), new TypeReference<Map<String, Object>>() {});
            businessId = inbound.get("businessId");
            extend = (Map<String, Object>) inbound.get("extend");
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
        } catch (Exception e) {
            log.error("handleOcrReceiveMessage failed, businessId={}: {}", businessId, e.getMessage(), e);
        }

        // Per task step 3 the OCR consumer always publishes to send_topic_name
        // (no complete_display branch). On any failure jsonUrl stays "".
        sendResultMessage(jsonUrl, businessId, extend);

        // Local cleanup runs AFTER downstream messages have been published
        if (inputDir != null) {
            deleteRecursively(inputDir);
        }

        acknowledgeQuietly(consumer, pulsarMsg, businessId);
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
        } catch (Exception e) {
            log.error("acknowledge failed, businessId={}: {}", businessId, e.getMessage(), e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable, String name) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
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
