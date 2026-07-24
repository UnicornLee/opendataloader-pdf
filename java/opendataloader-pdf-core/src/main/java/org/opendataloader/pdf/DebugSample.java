package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.util.HashMap;
import java.util.Map;

public class DebugSample {
    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.setOutputFolder("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\output");
        config.setGenerateMarkdown(true);
        Map<String, Object> customOptions = new HashMap<>();
        customOptions.put("paddleUrl", "http://192.168.1.97:8088/layout-parsing");
        config.setCustomOptions(customOptions);

//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202302281677505819604328-272.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-452.pdf", config);
        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\file-5(无线表格).pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-31.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480_1-13.pdf", config);
        OpenDataLoaderPDF.shutdown();
    }
}
