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
        @DefaultValue("1") int ocrCount) {
}
