package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.processors.ProcessingResult;

import java.util.HashMap;
import java.util.Map;

public class DebugSample1 {
    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.setOutputFolder("D:\\Code\\JavaCode\\opendataloader-pdf\\tmp_output");
        config.setGenerateMarkdown(true);
        config.getFilterConfig().setHalfWidthToFullWidth(true);
        Map<String, Object> customOptions = new HashMap<>();
        customOptions.put("paddleUrl", "http://192.168.1.97:8088/layout-parsing");
        customOptions.put("basicParseStreamTable", true);
        customOptions.put("basicFormulaRecognize", false);
        customOptions.put("businessId", 123456789);
        customOptions.put("extend", new HashMap<String, Object>(){});
        /*customOptions.put("basicEnv", "test");
        customOptions.put("pulsarReceiveTopicName", "pdf_parse_increment");
        customOptions.put("ossTempBucketName", "stock-temp-bucket");
        customOptions.put("ossPermanentBucketName", "common-pdf-bucket");
        customOptions.put("ossEndpoint", "https://obs.cn-north-1.myhuaweicloud.com");
        customOptions.put("ossAccessKey", "EUD3T68PPED8VI2ZHX2K");
        customOptions.put("ossSecretKey", "qOe8kcPGpJk0ZTIo1baWqQubShQoiyfOZThbp5dE");
        customOptions.put("ossDomainName", "https://common-pdf-bucket.obs.cn-north-1.myhuaweicloud.com/");*/
        config.setCustomOptions(customOptions);

        try {
//            ProcessingResult result = OpenDataLoaderPDF.processFile("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\202304271682510470028924.pdf", config);
            ProcessingResult result = OpenDataLoaderPDF.processFile("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\20260507AN202606291826520711.pdf", config);
            System.out.println("JSON URL / local path: " + result.getJsonUrlOrPath());
            System.out.println("OCR JSON local path: " + result.getOcrJsonLocalPath());
        } catch (Throwable e) {
            e.printStackTrace();
        }
        OpenDataLoaderPDF.shutdown();
    }
}
