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

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.PutObjectResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 封装华为云 OBS 上传操作。
 *
 * <p>基于华为云 OBS Java SDK（{@code esdk-obs-java}）实现，每个实例对应一个
 * {@link ObsClient} 连接；调用方负责在使用完毕后调用 {@link #close()}。</p>
 */
public class HuaweiObsClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(HuaweiObsClient.class.getCanonicalName());

    private final ObsClient obsClient;

    /**
     * 建立 OBS 连接。
     *
     * @param endpoint  OBS 终端节点，例如 {@code https://obs.cn-north-1.myhuaweicloud.com}
     * @param accessKey 访问密钥 ID
     * @param secretKey 访问密钥 Secret
     */
    public HuaweiObsClient(String endpoint, String accessKey, String secretKey) {
        this.obsClient = new ObsClient(accessKey, secretKey, endpoint);
    }

    /**
     * 上传本地文件到指定桶，并返回可访问 URL。
     *
     * @param bucketName  目标桶名
     * @param objectKey   对象键
     * @param file        待上传文件
     * @param domainName  用于拼接返回 URL 的域名，通常以 {@code /} 结尾
     * @return 拼接后的对象访问 URL
     * @throws IOException 上传失败时抛出
     */
    public String uploadFile(String bucketName, String objectKey, File file, String domainName) throws IOException {
        try {
            PutObjectResult result = obsClient.putObject(bucketName, objectKey, file);
            if (result.getStatusCode() != 200) {
                throw new IOException(String.format(
                    "Failed to upload file to OBS: bucket=%s, objectKey=%s, status=%d",
                    bucketName, objectKey, result.getStatusCode()));
            }
            LOGGER.log(Level.INFO, "Uploaded OBS object: {0}/{1}", new Object[]{bucketName, objectKey});
            return buildUrl(domainName, objectKey);
        } catch (ObsException e) {
            throw new IOException(String.format(
                "OBS upload failed: bucket=%s, objectKey=%s, errorCode=%s, errorMessage=%s",
                bucketName, objectKey, e.getErrorCode(), e.getErrorMessage()), e);
        }
    }

    /**
     * 上传字节数组内容到指定桶，并返回可访问 URL。
     *
     * @param bucketName  目标桶名
     * @param objectKey   对象键
     * @param content     待上传内容
     * @param domainName  用于拼接返回 URL 的域名，通常以 {@code /} 结尾
     * @return 拼接后的对象访问 URL
     * @throws IOException 上传失败时抛出
     */
    public String uploadBytes(String bucketName, String objectKey, byte[] content, String domainName) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            PutObjectResult result = obsClient.putObject(bucketName, objectKey, inputStream);
            if (result.getStatusCode() != 200) {
                throw new IOException(String.format(
                    "Failed to upload bytes to OBS: bucket=%s, objectKey=%s, status=%d",
                    bucketName, objectKey, result.getStatusCode()));
            }
            LOGGER.log(Level.INFO, "Uploaded OBS object: {0}/{1}", new Object[]{bucketName, objectKey});
            return buildUrl(domainName, objectKey);
        } catch (ObsException e) {
            throw new IOException(String.format(
                "OBS upload failed: bucket=%s, objectKey=%s, errorCode=%s, errorMessage=%s",
                bucketName, objectKey, e.getErrorCode(), e.getErrorMessage()), e);
        }
    }

    /**
     * 上传字符串内容到指定桶，并返回可访问 URL。
     *
     * @param bucketName  目标桶名
     * @param objectKey   对象键
     * @param content     待上传字符串
     * @param domainName  用于拼接返回 URL 的域名，通常以 {@code /} 结尾
     * @return 拼接后的对象访问 URL
     * @throws IOException 上传失败时抛出
     */
    public String uploadString(String bucketName, String objectKey, String content, String domainName) throws IOException {
        return uploadBytes(bucketName, objectKey, content.getBytes(StandardCharsets.UTF_8), domainName);
    }

    private static String buildUrl(String domainName, String objectKey) {
        if (domainName == null) {
            domainName = "";
        }
        String base = domainName.endsWith("/") ? domainName : domainName + "/";
        return base + objectKey;
    }

    @Override
    public void close() throws IOException {
        try {
            obsClient.close();
        } catch (ObsException e) {
            throw new IOException("Failed to close OBS client", e);
        }
    }
}
