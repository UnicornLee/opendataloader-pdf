package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.processors.ProcessingResult;

import java.util.HashMap;
import java.util.Map;

public class DebugAll {
    public static void main(String[] args) throws Exception {
        String[] pdfs = new String[] {
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202306221687344038470064294.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202303251679660111823147.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304271682523609840984.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304271682505621075149.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304211681997115596529.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304271682510470028924.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304281682603453761936.pdf",
            "D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304211681992320803737.pdf",
        };

        for (String pdfPath : pdfs) {
            Config config = new Config();
            config.setOutputFolder("D:\\Code\\JavaCode\\opendataloader-pdf\\tmp_output");
            config.setGenerateMarkdown(true);
            config.getFilterConfig().setHalfWidthToFullWidth(true);
            Map<String, Object> customOptions = new HashMap<>();
            customOptions.put("paddleUrl", "http://192.168.1.97:8088/layout-parsing");
            customOptions.put("basicParseStreamTable", true);
            customOptions.put("businessId", 123456789);
            customOptions.put("extend", new HashMap<String, Object>());
            config.setCustomOptions(customOptions);

            try {
                System.out.println("=== Processing: " + pdfPath + " ===");
                ProcessingResult result = OpenDataLoaderPDF.processFile(pdfPath, config);
                System.out.println("JSON: " + result.getJsonUrlOrPath());
            } catch (Throwable e) {
                System.out.println("ERROR processing " + pdfPath + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        OpenDataLoaderPDF.shutdown();
    }
}
