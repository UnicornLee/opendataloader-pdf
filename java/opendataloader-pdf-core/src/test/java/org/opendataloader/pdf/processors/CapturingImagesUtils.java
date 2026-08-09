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
package org.opendataloader.pdf.processors;

import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture {@link ImagesUtils} that records every {@link ImageChunk} it
 * would have saved instead of writing it to disk. Tests inspect the recorded
 * list to assert on the screenshots produced by the processors.
 */
public class CapturingImagesUtils extends ImagesUtils {

    public final List<ImageChunk> saved = new ArrayList<>();

    @Override
    public void saveImageChunk(ImageChunk chunk) {
        saved.add(chunk);
    }
}
