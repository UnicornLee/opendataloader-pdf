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
package org.opendataloader.pdf.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pulsar broker / topic settings bound from the {@code pulsar} block.
 * Snake_case keys in application-*.yml map to camelCase components through
 * Spring Boot's relaxed binding (e.g. {@code receive_topic_name} -> receiveTopicName).
 *
 * <p>{@code ackTimeoutSeconds} and {@code consumTimeoutMin} are two independent
 * timeouts that must not fight each other:
 * <ul>
 *   <li>{@code ack_timeout_seconds} - broker-side redelivery of a message the
 *       consumer has not acknowledged. {@code 0} disables it.</li>
 *   <li>{@code consum_timeout_min} - wall-clock watchdog around one message in
 *       {@code PulsarService}: on expiry the message is failed, acknowledged
 *       without redelivery, and the next one is consumed. {@code 0} disables it.</li>
 * </ul>
 * An ack timeout shorter than the watchdog makes the broker redeliver messages
 * that are still being processed, which produces multiple consumers working on
 * the same document simultaneously - so the watchdog deployment must set
 * {@code ack_timeout_seconds=0}. {@code PulsarService#start()} logs an error when
 * it sees a contradictory combination.</p>
 */
@ConfigurationProperties("pulsar")
public record PulsarProperties(
        @DefaultValue("") String servers,
        @DefaultValue("") String token,
        @DefaultValue("") String receiveTopicName,
        @DefaultValue("") String sendTopicName,
        @DefaultValue("") String ocrSendTopicName,
        @DefaultValue("") String ocrReceiveTopicName,
        @DefaultValue("1") int count,
        @DefaultValue("1") int ocrCount,
        @DefaultValue("60") int ackTimeoutSeconds,
        @DefaultValue("0") int consumTimeoutMin) {
}
