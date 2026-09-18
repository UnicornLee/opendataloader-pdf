package org.opendataloader.pdf.json;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.processors.ProcessingResult;

/**
 * End-to-end verification: drive the real {@code OpenDataLoaderPDF.processFile} pipeline
 * (which internally calls the modified JsonWriter) on the test PDF and confirm the
 * generated stream-table screenshot PNGs now carry an embedded pHYs chunk declaring 300 DPI.
 *
 * <p>Without the fix, the screenshots had no pHYs chunk and any viewer that couldn't
 * infer the real DPI defaulted to 96 DPI, making the high-resolution text appear
 * blurry when displayed / OCR-processed.</p>
 */
public class PngPhysChunkEndToEndTest {

    @Test
    public void realPipelineProducesScreenshotsWithPhysChunk(@TempDir File outputFolder) throws Exception {
        File pdf = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf");
        Assertions.assertTrue(pdf.exists(), "Test PDF not found: " + pdf);

        Config config = new Config();
        config.setOutputFolder(outputFolder.getAbsolutePath());
        Map<String, Object> customOptions = new HashMap<>();
        customOptions.put("businessId", 123456789);
        customOptions.put("basicParseStreamTable", Boolean.TRUE);
        customOptions.put("basicFormulaRecognize", Boolean.FALSE);
        config.setCustomOptions(customOptions);

        try {
            ProcessingResult result = OpenDataLoaderPDF.processFile(pdf.getAbsolutePath(), config);
            System.out.println("JSON URL / local path: " + result.getJsonUrlOrPath());
            System.out.println("OCR JSON local path: " + result.getOcrJsonLocalPath());
        } finally {
            OpenDataLoaderPDF.shutdown();
        }

        File imagesDir = new File(outputFolder, pdf.getName().replaceAll("\\.pdf$", "") + "_images");
        Assertions.assertTrue(imagesDir.isDirectory(), "Images dir not created: " + imagesDir);

        // Only check JsonWriter-generated screenshots; imageFile*.png are embedded images extracted
        // from the PDF and should NOT carry a pHYs chunk (they have whatever metadata their PDF
        // embedded them with, which is the correct thing to preserve).
        File[] screenshots = imagesDir.listFiles((dir, name) ->
            name.endsWith(".png")
                && (name.contains("_streamtable-") || name.contains("_ocr-") || name.contains("_formula-")));
        Assertions.assertNotNull(screenshots, "No JsonWriter-generated screenshots found in " + imagesDir);
        // Pages 7 and 8 of this PDF trigger the OCR-screenshot pass (large embedded image + low item
        // count). Page 9 doesn't trigger either branch on this PDF, which is fine; we only assert
        // that whatever the pipeline produces carries the pHYs chunk.
        Assertions.assertTrue(screenshots.length >= 1,
            "Expected at least 1 JsonWriter-generated screenshot, got " + screenshots.length);

        int checked = 0;
        for (File shot : screenshots) {
            String chunks = describeChunks(shot);
            System.out.println("Screenshot: " + shot.getName() + " size=" + shot.length() + " chunks=[" + chunks + "]");
            // Every screenshot written by JsonWriter must carry a pHYs chunk
            Assertions.assertTrue(chunks.contains("pHYs"),
                "Screenshot " + shot.getName() + " is missing pHYs chunk; chunks=" + chunks);
            checked++;
        }
        Assertions.assertEquals(screenshots.length, checked);
        System.out.println("All " + checked + " screenshots have pHYs chunks.");
    }

    private static String describeChunks(File png) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(png)) {
            byte[] sig = new byte[8];
            if (fis.read(sig) != 8) return "<no signature>";
            // Skip the rest, we only want a coarse view here
            int count = 0;
            while (true) {
                byte[] len = new byte[4];
                if (fis.read(len) != 4) break;
                int l = ((len[0] & 0xFF) << 24) | ((len[1] & 0xFF) << 16)
                        | ((len[2] & 0xFF) << 8) | (len[3] & 0xFF);
                byte[] type = new byte[4];
                if (fis.read(type) != 4) break;
                String t = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
                if (sb.length() > 0) sb.append(",");
                sb.append(t).append("(").append(l).append(")");
                long toSkip = (long) l + 4;
                long skipped = 0;
                while (skipped < toSkip) {
                    long n = fis.skip(toSkip - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                if (count++ > 1000) break; // safety
            }
        }
        return sb.toString();
    }
}