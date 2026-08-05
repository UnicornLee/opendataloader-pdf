package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.util.HashMap;
import java.util.Map;

public class DebugSample {
    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.setOutputFolder("D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\tmp_output");
        config.setGenerateMarkdown(true);
        config.getFilterConfig().setHalfWidthToFullWidth(true);
        Map<String, Object> customOptions = new HashMap<>();
//        customOptions.put("paddleUrl", "http://192.168.1.97:8088/layout-parsing");
        config.setCustomOptions(customOptions);

//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202302281677505819604328-272.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-452.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-252(公式).pdf", config);
        OpenDataLoaderPDF.processFile("D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\202302281677505819604328.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\带中文的公式.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-314(公式).pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202304191681815910199312-95(图表).pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480-31.pdf", config);
//        OpenDataLoaderPDF.processFile("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\202303181679059838994480_1-13.pdf", config);
        OpenDataLoaderPDF.shutdown();
    }
}
