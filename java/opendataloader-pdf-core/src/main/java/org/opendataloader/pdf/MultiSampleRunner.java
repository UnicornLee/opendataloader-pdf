package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.processors.ProcessingResult;

import java.util.HashMap;
import java.util.Map;

/**
 * One-off runner that processes every PDF passed on the command line with the
 * same config as {@link DebugSample}. Used to verify bookmark-related changes
 * against multiple real documents.
 */
public class MultiSampleRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MultiSampleRunner <outputFolder> <pdf1> [pdf2] ...");
            System.exit(1);
        }
        String outputFolder = args[0];
        Config config = new Config();
        config.setOutputFolder(outputFolder);
        config.setGenerateMarkdown(true);
        config.getFilterConfig().setHalfWidthToFullWidth(true);
        Map<String, Object> customOptions = new HashMap<>();
        customOptions.put("businessId", 123456789);
        customOptions.put("extend", new HashMap<String, Object>(){});
        config.setCustomOptions(customOptions);

        for (int i = 1; i < args.length; i++) {
            String pdf = args[i];
            System.out.println("\n========== Processing: " + pdf + " ==========");
            try {
                ProcessingResult result = OpenDataLoaderPDF.processFile(pdf, config);
                System.out.println("  JSON : " + result.getJsonUrlOrPath());
                System.out.println("  OCR  : " + result.getOcrJsonLocalPath());
            } catch (Exception e) {
                System.err.println("  FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
        OpenDataLoaderPDF.shutdown();
    }
}
