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
package org.opendataloader.pdf.api;

/**
 * {@link OpenDataLoaderPDF#rebuildBookmarks(String, Config)} 的输出结果，
 * 用于向上层调用方返回重建后 JSON 文件的存储位置以及 OSS 上传状态。
 *
 * <p>结构与 {@link org.opendataloader.pdf.json.CustomOutputResult} 对齐，
 * 但不包含 OCR JSON 路径——重建书签场景不涉及 OCR 检测。</p>
 */
public class RebuildBookmarksResult {

    /**
     * JSON 文件上传后的可访问 URL，或本地绝对路径（未启用 OSS 时）。
     */
    private final String jsonUrlOrPath;

    /**
     * 是否成功上传到对象存储（true 表示可以安全删除本地源文件）。
     */
    private final boolean ossUploadSuccess;

    public RebuildBookmarksResult(String jsonUrlOrPath, boolean ossUploadSuccess) {
        this.jsonUrlOrPath = jsonUrlOrPath;
        this.ossUploadSuccess = ossUploadSuccess;
    }

    public String getJsonUrlOrPath() {
        return jsonUrlOrPath;
    }

    public boolean isOssUploadSuccess() {
        return ossUploadSuccess;
    }
}