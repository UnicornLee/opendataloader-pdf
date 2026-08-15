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

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.opendataloader.pdf.custom.entities.CustomSemanticParagraph;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Merges {@link LineArtChunk}s with their significantly overlapping
 * neighbours, renders the merged area as an image, and replaces the merged
 * elements with a single {@link ImageChunk}. When a PaddleOCR endpoint is
 * configured the screenshot is OCR'd and, if the result looks like a LaTeX
 * formula, replaced with a {@link TextChunk} carrying the formula.
 */
public final class LineArtProcessor {

    private static final Logger LOGGER = Logger.getLogger(LineArtProcessor.class.getCanonicalName());

    /** Maximum number of recognize fornula per page. */
    private static final int FORMULA_RECOGNIZE_MAX_COUNT = 5;

    /** Maximum number of attempts (initial + retries) when calling Paddle OCR. */
    private static final int PADDLE_MAX_RETRIES = 3;

    /** Sleep duration between failed Paddle OCR attempts, in seconds. */
    private static final long PADDLE_RETRY_SLEEP_SECONDS = 3L;

    /** Minimum overlap ratio to treat a neighbouring element as intersecting a LineArtChunk. */
    private static final double MIN_LINE_ART_OVERLAP_PERCENT = 0.05;

    private static final Pattern LATEX_COMMAND_PATTERN = Pattern.compile("\\\\[a-zA-Z]+");
    private static final Pattern LATEX_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*\\{");
    private static final Pattern LATEX_SIMPLE_SUBSUP_PATTERN = Pattern.compile("[a-zA-Z0-9]\\s*[_^]\\s*[a-zA-Z0-9]");

    private LineArtProcessor() {
    }

    /**
     * Walks {@code pageContents} once, merging any
     * {@link LineArtChunk}/{@link ShapeChunk} cluster with overlapping
     * neighbours into one screenshot. When {@code paddleUrl} is set, the
     * screenshot is OCR'd and may be replaced by a LaTeX formula
     * {@link TextChunk}.
     *
     * @param pageContents the current page contents (will be replaced)
     * @param pageNumber   0-based page number (unused for OCR; kept for symmetry)
     * @param imagesUtils  image renderer / saver
     * @param paddleUrl    PaddleOCR endpoint URL, or {@code null}/empty to skip OCR
     */
    public static void processLineArtGroups(List<IObject> pageContents, int pageNumber,
                                             ImagesUtils imagesUtils, String paddleUrl) {
        if (pageContents == null || pageContents.isEmpty() || imagesUtils == null) {
            return;
        }
        // Sort page contents from top to bottom (topY descending) so the forward/backward
        // overlap scan finds neighbours in the correct vertical order, regardless of
        // how earlier processors may have rearranged the list.
        pageContents.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
        List<IObject> result = new ArrayList<>(pageContents.size());
        int recognizeCount = 0;
        for (int i = 0; i < pageContents.size(); i++) {
            if (recognizeCount > FORMULA_RECOGNIZE_MAX_COUNT) {
                LOGGER.log(Level.INFO, "Page {0} - 每页最多只做{1}个公式识别，已达上限，跳过后续识别！",
                    new Object[]{pageNumber + 1, FORMULA_RECOGNIZE_MAX_COUNT});
                break;
            }
            IObject current = pageContents.get(i);
            if (!(current instanceof LineArtChunk && current.getHeight() <= 3 && current.getWidth() <= 300)) {
                result.add(current);
                continue;
            }

            BoundingBox lineArtBox = new BoundingBox(current.getBoundingBox());
            List<IObject> group = new ArrayList<>();
            group.add(current);

            // Pull overlapping elements already added to result (the "before" neighbours).
            for (int j = result.size() - 1; j >= 0; j--) {
                IObject candidate = result.get(j);
                if (candidate instanceof ShapeChunk || HeaderFooterProcessor.isHeaderOrFooter(candidate)) {
                    continue;
                }
                if (BoundingBoxGroupUtils.hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    group.add(0, candidate);
                    result.remove(j);
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }

            // Collect overlapping "after" neighbours from the original list.
            int forwardCount = 0;
            for (int j = i + 1; j < pageContents.size(); j++) {
                IObject candidate = pageContents.get(j);
                if (candidate instanceof ShapeChunk || HeaderFooterProcessor.isHeaderOrFooter(candidate)) {
                    continue;
                }
                if (BoundingBoxGroupUtils.hasSignificantOverlap(candidate.getBoundingBox(), lineArtBox)) {
                    group.add(candidate);
                    forwardCount++;
                    lineArtBox = lineArtBox.union(candidate.getBoundingBox());
                } else {
                    break;
                }
            }
            i += forwardCount;

            if (group.size() > 1) {
                BoundingBox union = BoundingBoxGroupUtils.unionBoundingBoxes(group, pageNumber);
                if (union != null && !union.isEmpty()) {
                    ImageChunk imageChunk = new ImageChunk(union);
                    imagesUtils.saveImageChunk(imageChunk);
                    IObject replacement = imageChunk;
                    if (paddleUrl != null && !"".equals(paddleUrl)) {
                        String imageFileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT,
                                StaticLayoutContainers.getImagesDirectory(), File.separator,
                                imageChunk.getIndex(), StaticLayoutContainers.getImageFormat());
                        TextInOcrAnalysisResultDto textInOcrAnalysisResultDto = callPaddleWithRetry(
                                new File(imageFileName), 1, paddleUrl);
                        if (textInOcrAnalysisResultDto != null) {
                            LOGGER.log(Level.INFO, "Text in ocr analysis result: {}", textInOcrAnalysisResultDto);
                            TextChunk formulaChunk = tryCreateFormulaTextChunk(textInOcrAnalysisResultDto, union);
                            if (formulaChunk != null) {
                                Double fontSize = 12.0;
                                List<Double> fontSizes = new ArrayList<>();
                                for (IObject item : group) {
                                    if (item instanceof CustomSemanticParagraph) {
                                        CustomSemanticParagraph customSemanticParagraph = (CustomSemanticParagraph) item;
                                        fontSizes.add(customSemanticParagraph.getFontSize());
                                    }
                                }
                                if (fontSizes.size() > 0) {
                                    fontSize = Collections.max(fontSizes);
                                }
                                formulaChunk.setFontSize(fontSize);
                                replacement = formulaChunk;
                            } else {
                                // OCR 成功但未识别到公式：撤销合并，把 group 里的原始元素全部放回 result
                                // 按 topY 从大到小排序，与 processLineArtGroups 入口处 pageContents 的排序方向一致
                                group.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
                                result.addAll(group);
                                continue;
                            }
                            recognizeCount++;
                        }
                        // null = 重试全部失败：ERROR 日志已在 callPaddleWithRetry 中打印，replacement 保持为 imageChunk
                    }
                    result.add(replacement);
                    continue;
                }
            }
            result.add(current);
        }
        pageContents.clear();
        pageContents.addAll(result);
    }

    /**
     * Wraps {@link PaddleOcrProcessor#getPaddleResponse} with retry logic: on
     * failure, sleeps {@value #PADDLE_RETRY_SLEEP_SECONDS} seconds and retries up
     * to {@value #PADDLE_MAX_RETRIES} times in total. If every attempt fails,
     * logs an {@code ERROR}-level message and returns {@code null} without
     * propagating the exception.
     */
    private static TextInOcrAnalysisResultDto callPaddleWithRetry(File imageFile, int fileType, String paddleUrl) {
        IOException lastException = null;
        for (int attempt = 1; attempt <= PADDLE_MAX_RETRIES; attempt++) {
            try {
                return PaddleOcrProcessor.getPaddleResponse(imageFile, fileType, paddleUrl);
            } catch (IOException e) {
                lastException = e;
                LOGGER.log(Level.WARNING,
                    "Paddle OCR call failed (attempt " + attempt + "/" + PADDLE_MAX_RETRIES
                        + ") for image chunk: " + imageFile, e);
                if (attempt < PADDLE_MAX_RETRIES) {
                    try {
                        Thread.sleep(PADDLE_RETRY_SLEEP_SECONDS * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(Level.WARNING, "Paddle OCR retry sleep interrupted", ie);
                        break;
                    }
                }
            }
        }
        LOGGER.log(Level.SEVERE,
            "Paddle OCR call failed after " + PADDLE_MAX_RETRIES + " attempts for image chunk: "
                + imageFile + "; falling back to ImageChunk", lastException);
        return null;
    }

    private static TextChunk tryCreateFormulaTextChunk(TextInOcrAnalysisResultDto resultDto, BoundingBox bbox) {
        if (resultDto == null || resultDto.getDetail() == null || resultDto.getDetail().isEmpty()) {
            return null;
        }
        TextInOcrDetailDto detail = resultDto.getDetail().get(0);
        if (!"paragraph".equals(detail.getType())) {
            return null;
        }
        String text = detail.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!isLatexExpression(text)) {
            return null;
        }
        // Normalise LaTeX delimiters: strip any stray $ and wrap with $$...$$
        text = text.replace("$", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        text = "$$" + text + "$$";
        return createTextChunk(text, bbox);
    }

    private static TextChunk createTextChunk(String text, BoundingBox bbox) {
        TextChunk textChunk = new TextChunk(text);
        textChunk.setBoundingBox(new BoundingBox(bbox));
        textChunk.setFontSize(bbox.getHeight());
        textChunk.setBaseLine(bbox.getCenterY());
        return textChunk;
    }

    private static boolean isLatexExpression(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("$") || trimmed.endsWith("$") || trimmed.contains("$$")) {
            return true;
        }
        if (LATEX_COMMAND_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (LATEX_SUBSUP_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        return LATEX_SIMPLE_SUBSUP_PATTERN.matcher(trimmed).find();
    }
}
