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
package org.opendataloader.pdf.json;

/**
 * {@link JsonWriter#writeToCustomJson} 的输出结果，用于向上层调用方返回
 * 主 JSON 的存储位置以及 OCR JSON 的本地路径。
 */
public class CustomOutputResult {

    /**
     * 主 JSON 文件上传后的可访问 URL，或本地绝对路径（未启用 OSS 时）。
     */
    private final String jsonUrlOrPath;

    /**
     * {@code _ocr.json} 的本地绝对路径；若未生成则为空字符串。
     */
    private final String ocrJsonLocalPath;

    /**
     * 是否成功上传到对象存储（true 表示可以安全删除本地源文件）。
     */
    private final boolean ossUploadSuccess;

    public CustomOutputResult(String jsonUrlOrPath, String ocrJsonLocalPath, boolean ossUploadSuccess) {
        this.jsonUrlOrPath = jsonUrlOrPath;
        this.ocrJsonLocalPath = ocrJsonLocalPath;
        this.ossUploadSuccess = ossUploadSuccess;
    }

    public String getJsonUrlOrPath() {
        return jsonUrlOrPath;
    }

    public String getOcrJsonLocalPath() {
        return ocrJsonLocalPath;
    }

    public boolean isOssUploadSuccess() {
        return ossUploadSuccess;
    }
}
