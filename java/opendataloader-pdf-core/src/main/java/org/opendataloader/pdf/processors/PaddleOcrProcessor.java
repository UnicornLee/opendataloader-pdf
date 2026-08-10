package org.opendataloader.pdf.processors;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.opendataloader.pdf.custom.dto.PaddleDocLayoutParseResponseDto;
import org.opendataloader.pdf.custom.dto.PageItemResultDto;
import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.opendataloader.pdf.custom.utils.PaddleOcrResultUtils;
import org.opendataloader.pdf.json.ObjectMapperHolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PaddleOcrProcessor {

    private static final Logger LOGGER = Logger.getLogger(PaddleOcrProcessor.class.getCanonicalName());

    /**
     * Default Paddle OCR endpoint. The actual URL should eventually be
     * plumbed through {@link org.opendataloader.pdf.api.Config} so dev/prepub/
     * prod profiles can override it without touching this file
     * (see {@code paddle.url} in application-*.yml).
     */
    private static final String DEFAULT_PADDLE_URL =
        "http://localhost:8080/layout-parsing";

    /** Connect / read / write timeouts (ms) for the Paddle HTTP call. */
    private static final long PADDLE_HTTP_TIMEOUT_MS = 60_000L;

    /** Single, lazily-built client for Paddle calls (okhttp recommends reuse). */
    private static final OkHttpClient PADDLE_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(PADDLE_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PADDLE_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(PADDLE_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build();

    public static void main(String[] args) {
        File file = new File("D:\\Downloads\\opendataloader-pdf-cli-2.4.6\\file-5(无线表格).pdf");
        String paddleUrl = "http://192.168.1.97:8088/layout-parsing";
        try {
            TextInOcrAnalysisResultDto resultDto = getPaddleResponse(file, 0, paddleUrl);
            PageItemResultDto pageItemResultDto = PaddleOcrResultUtils.generateJsonResultByTextInOcrAnalysisResultDto(
                file, resultDto, 1000.0, 1000.0, 0);
            System.out.println(pageItemResultDto);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Calls the Paddle OCR service with the given image file and converts the
     * response into a {@link TextInOcrAnalysisResultDto} for downstream use.
     * Uses the existing okhttp + jackson dependencies rather than introducing
     * a Spring HTTP client.
     */
    static TextInOcrAnalysisResultDto getPaddleResponse(File file, Integer fileType, String paddleUrl)
        throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("paddle image file must not be null");
        }
        if (file.length() == 0) {
            throw new IllegalArgumentException("paddle image file is empty: " + file);
        }
        String effectiveUrl = (paddleUrl == null || paddleUrl.isBlank())
            ? DEFAULT_PADDLE_URL : paddleUrl;

        byte[] fileContent = Files.readAllBytes(file.toPath());
        String base64Image = Base64.getEncoder().encodeToString(fileContent);

        ObjectMapper mapper = ObjectMapperHolder.getObjectMapper();
        // LinkedHashMap keeps the field order predictable for debugging.
        Map<String, Object> jsonBody = new LinkedHashMap<>();
        jsonBody.put("markdownIgnoreLabels",
            new String[]{"header", "header_image", "footer", "footer_image",
                "number", "footnote", "aside_text"});
        jsonBody.put("file", base64Image);
        // 0: pdf, 1: image
        jsonBody.put("fileType", fileType);
        jsonBody.put("useDocOrientationClassify", true);
        jsonBody.put("useLayoutDetection", true);
        jsonBody.put("useDocUnwarping", false);
        jsonBody.put("temperature", 0);
        String bodyJson = mapper.writeValueAsString(jsonBody);

        Request request = new Request.Builder()
            .url(effectiveUrl)
            .post(RequestBody.create(bodyJson,
                MediaType.parse("application/json; charset=utf-8")))
            .build();

        try (Response response = PADDLE_CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String detail = (responseBody == null) ? "" : responseBody.string();
                throw new IOException("paddle service returned http " + response.code()
                    + " for " + effectiveUrl + ": " + detail);
            }
            if (responseBody == null) {
                throw new IOException("paddle service returned an empty body for " + effectiveUrl);
            }
            String responseJson = responseBody.string();
            // Log via supplier to avoid the SLF4J-style "{}" placeholder
            // being printed verbatim by java.util.logging. The response body
            // is already JSON, so we embed it directly instead of round-tripping
            // it through JsonUtil (which would re-encode it as a quoted string).
            LOGGER.log(Level.INFO, () -> "paddle response: " + responseJson);
            PaddleDocLayoutParseResponseDto paddle =
                mapper.readValue(responseJson, PaddleDocLayoutParseResponseDto.class);
            TextInOcrAnalysisResultDto resultDto = transferPropertiesFromPaddleToTextInModel(paddle);
            final TextInOcrAnalysisResultDto loggedResult = resultDto;
            /*LOGGER.log(Level.INFO, () -> {
                try {
                    return "paddle->textin conversion result: "
                        + mapper.writeValueAsString(loggedResult);
                } catch (Exception e) {
                    return "paddle->textin conversion result <serialization failed: "
                        + e.getMessage() + ">";
                }
            });*/
            return resultDto;
        }
    }

    private static TextInOcrAnalysisResultDto transferPropertiesFromPaddleToTextInModel(PaddleDocLayoutParseResponseDto dto) {
        Integer paragraphCounter = 0;
        TextInOcrAnalysisResultDto dto1 = new TextInOcrAnalysisResultDto();
        PaddleDocLayoutParseResponseDto.ResultDto result = dto.getResult();
        List<TextInOcrDetailDto> textInOcrDetailDtoList = new ArrayList<>();
        dto1.setDetail(textInOcrDetailDtoList);
        if(result != null){
            List<PaddleDocLayoutParseResponseDto.LayoutParsingResultDto> layoutParsingResultList = result.getLayoutParsingResults();
            if(layoutParsingResultList != null && !layoutParsingResultList.isEmpty()){
                for(PaddleDocLayoutParseResponseDto.LayoutParsingResultDto per : layoutParsingResultList){
                    PaddleDocLayoutParseResponseDto.MarkdownDto markdown = per.getMarkdown();
                    Map<String,String> imagesMap = null;
                    if(markdown != null){
                        imagesMap = markdown.getImages();
                    }
                    PaddleDocLayoutParseResponseDto.PrunedResultDto prunedResult = per.getPrunedResult();
                    if(prunedResult != null){
                        List<PaddleDocLayoutParseResponseDto.ParsingResDto> parsingResList = prunedResult.getParsingResList();
                        if(parsingResList != null && !parsingResList.isEmpty()){
                            for(PaddleDocLayoutParseResponseDto.ParsingResDto perPer : parsingResList){
                                TextInOcrDetailDto subTextInOcrDetailDto = new TextInOcrDetailDto();
                                textInOcrDetailDtoList.add(subTextInOcrDetailDto);
                                String content = perPer.getBlockContent();
                                if(content != null && !content.isBlank()){
                                    content = content.replaceAll("\\n","<br>");
                                }
                                subTextInOcrDetailDto.setText(content);
                                String blockLabel = perPer.getBlockLabel();
                                Integer outlineLevel = 3;
                                List<Double> blockBoxList = perPer.getBlockBbox();
                                String type = "paragraph";
                                if(Objects.equals("doc_title",blockLabel)){
                                    outlineLevel = 1;
                                }else if (Objects.equals("paragraph_title",blockLabel)){
                                    outlineLevel = 2;
                                }else if (
                                    Objects.equals("image",blockLabel) || Objects.equals("seal",blockLabel)
                                        || Objects.equals("chart",blockLabel) || Objects.equals("footer_image",blockLabel) || Objects.equals("header_image",blockLabel)
                                ){
                                    type = "image";
                                    List<String> blockBoxStrList = blockBoxList.stream().map(new Function<Double, String>() {
                                        @Override
                                        public String apply(Double aDouble) {
                                            return aDouble.intValue()+"";
                                        }
                                    }).collect(Collectors.toList());
                                    String tempImageSubStr = String.join("_", blockBoxStrList);
                                    // 通过imagesMap进行获取base64编码
                                    String allTempImageSubStr = "imgs/img_in_"+blockLabel+"_box_"+tempImageSubStr+".jpg";
                                    if(imagesMap != null && !imagesMap.isEmpty()){
                                        String tempBase64Str = imagesMap.get(allTempImageSubStr);
                                        String tempImagePath = FileUtils.getTempDirectoryPath()+File.separator+ UUID.randomUUID().toString()+".png";
                                        convertBase64ToImage(tempBase64Str,tempImagePath);
                                        subTextInOcrDetailDto.setImageUrl("${localFile}"+tempImagePath);
                                    }
                                }else if(Objects.equals("table",blockLabel)){
                                    type = "table";
                                }
                                subTextInOcrDetailDto.setPageId(0);
                                subTextInOcrDetailDto.setParagraphId(paragraphCounter++);
                                subTextInOcrDetailDto.setContent(0);
                                List<List<Double>> blockPolygonPoints = perPer.getBlockPolygonPoints();
                                List<Double> blockBboxList = perPer.getBlockBbox();

                                if(blockBboxList != null && blockBboxList.size() == 4){
                                    List<Double> tempPositionList = new ArrayList<>();
                                    tempPositionList.addAll(Arrays.asList(blockBboxList.get(0),blockBboxList.get(1)));
                                    tempPositionList.addAll(Arrays.asList(blockBboxList.get(2),blockBboxList.get(1)));
                                    tempPositionList.addAll(Arrays.asList(blockBboxList.get(0),blockBboxList.get(3)));
                                    tempPositionList.addAll(Arrays.asList(blockBboxList.get(2),blockBboxList.get(3)));
                                    subTextInOcrDetailDto.setPosition(tempPositionList);
                                }
                                subTextInOcrDetailDto.setOutlineLevel(outlineLevel);
                                subTextInOcrDetailDto.setType(type);
                            }
                        }
                    }
                }
            }
        }
        return dto1;
    }

    /**
     * 将Base64字符串转换为本地图片文件
     * @param base64Str Base64编码的图片字符串（可带/不带data:image/xxx;base64,前缀）
     * @param filePath  要保存的图片文件完整路径（如：D:/images/test.png）
     */
    public static void convertBase64ToImage(String base64Str, String filePath) {
        // 1. 处理Base64前缀（如果有）
        String pureBase64Str = base64Str;
        if (base64Str.contains("data:image/")) {
            // 截取base64前缀后的真实编码内容
            pureBase64Str = base64Str.split(",")[1];
        }

        // 2. Base64解码为字节数组（Java 8+ 推荐使用java.util.Base64，替代过时的sun.misc.BASE64Decoder）
        byte[] imageBytes = Base64.getDecoder().decode(pureBase64Str);

        // 3. 将字节数组写入本地文件（使用try-with-resources自动关闭流，避免资源泄漏）
        try (OutputStream outputStream = Files.newOutputStream(Paths.get(filePath))) {
            outputStream.write(imageBytes);
            outputStream.flush();
        } catch (Exception e) {
            throw new RuntimeException(e); // 抛出异常让调用方处理
        }
    }
}
