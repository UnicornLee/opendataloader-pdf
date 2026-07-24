package org.opendataloader.pdf.custom.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.opendataloader.pdf.custom.dto.PageItem;
import org.opendataloader.pdf.custom.dto.PageItemResultDto;
import org.opendataloader.pdf.custom.dto.ParagraphContent;
import org.opendataloader.pdf.custom.dto.TableSingleItem;
import org.opendataloader.pdf.custom.dto.TextInOcrAnalysisResultDto;
import org.opendataloader.pdf.custom.dto.TextInOcrDetailDto;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PaddleOcrResultUtils {
    /**
     * 进行优化，一个段落以半页坐标为基准，
     * 左边，剩余宽度为 剩余长度
     * 右边，左侧坐标顶到中间，剩余宽度为剩余长度
     * @param file
     * @param textInOcrAnalysisResultDto
     * @param sourceWidth
     * @param sourceHeight
     * @return
     */
    public static PageItemResultDto generateJsonResultByTextInOcrAnalysisResultDto(File file, TextInOcrAnalysisResultDto textInOcrAnalysisResultDto, Double sourceWidth, Double sourceHeight, int pageNumber) {

        PageItemResultDto resultDto = new PageItemResultDto();
        resultDto.setHeight(sourceHeight);
        resultDto.setWidth(sourceWidth);
        List<PageItem> pageItemList = new ArrayList<>();
        resultDto.setPageItemList(pageItemList);
        List<TextInOcrDetailDto> detailDtoList = textInOcrAnalysisResultDto.getDetail();
        Double widthRatio = 1D;
        Double heightRatio = 1D;
        try {
            BufferedImage image = ImageIO.read(file);
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();

            boolean rotate = false;

            // 判断一下是否需要对调
            for(TextInOcrDetailDto perDetailDto : detailDtoList){
                if(perDetailDto.getPosition().get(2).doubleValue() > imageWidth + 20){
                    rotate = true;
                    break;
                }
            }
            if(rotate){
                resultDto.setHeight(sourceWidth);
                resultDto.setWidth(sourceHeight);
                heightRatio = new BigDecimal(sourceWidth / imageWidth).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
                widthRatio = new BigDecimal(sourceHeight / imageHeight).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            }else {
                widthRatio = new BigDecimal(sourceWidth / imageWidth).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
                heightRatio = new BigDecimal(sourceHeight / imageHeight).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        Integer idCounter = 1;
        for(TextInOcrDetailDto detailDto : detailDtoList){
            PageItem pageItem = new PageItem();
            String type = detailDto.getType();
            List<Double> positionList = detailDto.getPosition();
            Double leftX0 = positionList.get(0);
            Double leftY0 = positionList.get(1);
            Double leftX1 = positionList.get(2);
            Double leftY1 = positionList.get(3);
            Double rightY1 = positionList.get(7);

            Double calculateLeftX0 = new BigDecimal(leftX0 * widthRatio).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            Double calculateLeftY0 = new BigDecimal(leftY0 * heightRatio).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            Double calculateLeftX1 = new BigDecimal(leftX1 * widthRatio).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            Double calculateLeftY1 = new BigDecimal(leftY1 * heightRatio).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            Double calculateRightY1 = new BigDecimal(rightY1 * heightRatio).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            BoundingBox boundingBox = new BoundingBox(pageNumber, calculateLeftX0, sourceHeight - calculateRightY1, calculateLeftX1, sourceHeight - calculateLeftY0);
            pageItem.setBoundingBox(boundingBox);

            if(Objects.equals(type,"paragraph")){

                // 段落
                ParagraphContent paragraphContent = new ParagraphContent();

                boolean leftXInHalfLeft = true;

                // 检查最左侧坐标是否在中间右侧
                if(calculateLeftX0 > sourceWidth / 2){
                    leftXInHalfLeft = false;
                }

                double fixedPreWidth = 20;

                if (leftXInHalfLeft) {
                    //宽度直接设置成剩余宽度
                    paragraphContent.setX0(calculateLeftX0);
                    double wid = sourceWidth - calculateLeftX0;
                    if(wid - fixedPreWidth > 0){
                        wid -= fixedPreWidth;
                    }
                    double widX1 = sourceWidth;
                    if(widX1 - fixedPreWidth > 0){
                        widX1 -= fixedPreWidth;
                    }
                    paragraphContent.setWidth(wid);
                    paragraphContent.setX1(widX1);
                    pageItem.setX0(calculateLeftX0);
                    pageItem.setWidth(wid);
                    pageItem.setX1(widX1);
                } else {

                    double wid = sourceWidth / 2;
                    if(wid - fixedPreWidth > 0){
                        wid -= fixedPreWidth;
                    }
                    double widX1 = sourceWidth;
                    if(widX1 - fixedPreWidth > 0){
                        widX1 -= fixedPreWidth;
                    }

                    //左侧坐标直接设置成中间一般坐标
                    //宽度直接设置成中间剩余宽度
                    paragraphContent.setX0(sourceWidth / 2);
                    paragraphContent.setWidth(wid);
                    paragraphContent.setX1(widX1);
                    pageItem.setX0(sourceWidth / 2);
                    pageItem.setWidth(wid);
                    pageItem.setX1(widX1);
                }

                paragraphContent.setContent(Arrays.asList(convertInlineToDisplay(MarkDownUtils.markdownToPlainTextV2(detailDto.getText()))));
                //  paragraphContent.setX0(calculateLeftX0); 展示优化
                paragraphContent.setY0(calculateLeftY0);
                //   paragraphContent.setX1(calculateLeftX1); 展示优化
                paragraphContent.setY1(calculateRightY1);
                //   paragraphContent.setWidth(calculateLeftX1-calculateLeftX0);  展示优化
                paragraphContent.setHeight(calculateRightY1-calculateLeftY0);
                double fontSize = 10D;
                if(detailDto.getOutlineLevel() == 0){
                    fontSize = 13D;
                }else if(detailDto.getOutlineLevel() == 1){
                    fontSize = 12D;
                }else if(detailDto.getOutlineLevel() == 2){
                    fontSize = 11D;
                }
                paragraphContent.setFontSize(fontSize);
                paragraphContent.setMarginTop(10D);
                paragraphContent.setItemType("text");
                //    pageItem.setX0(calculateLeftX0);
                pageItem.setY0(calculateLeftY0);
                //    pageItem.setX1(calculateLeftX1);
                pageItem.setY1(calculateRightY1);
                //    pageItem.setWidth(calculateLeftX1-calculateLeftX0);
                pageItem.setHeight(calculateRightY1-calculateLeftY0);
                pageItem.setFontSize(fontSize);
                pageItem.setItemType("text");
                pageItem.setContent(Arrays.asList(paragraphContent));
                pageItem.setMarginTop(10D);
                pageItem.setParagraph(true);
                pageItem.setId(idCounter++);
                pageItemList.add(pageItem);

            }else if (Objects.equals(type,"table")){
                // 表格
                // <table border="1" ><tr> <td colspan="1" rowspan="1"></td> <td colspan="1" rowspan="1">从不吸烟</td> <td colspan="1" rowspan="1">偶尔吸烟</td> <td colspan="1" rowspan="1">经常吸烟</td> </tr><tr> <td colspan="1" rowspan="1">患肺癌</td> <td colspan="1" rowspan="1">10</td> <td colspan="1" rowspan="1">200</td> <td colspan="1" rowspan="1">40</td> </tr><tr> <td colspan="1" rowspan="1">不患肺癌</td> <td colspan="1" rowspan="1">233</td> <td colspan="1" rowspan="1">40</td> <td colspan="1" rowspan="1">80</td> </tr></table>
                String tableContent = detailDto.getText();
                List<PageItem> pageItemList1 = getPageItemListByTextInContent(
                    tableContent,calculateLeftX0,calculateLeftX1, calculateLeftY0,calculateRightY1,idCounter++,
                    sourceHeight, pageNumber);
                pageItemList.addAll(pageItemList1);
            }else if(Objects.equals(type,"image")){
                // 图片
                String imageUrl = detailDto.getImageUrl();
                if(imageUrl != null && !"".equals(imageUrl.trim())) {
                    pageItem.setContent(Arrays.asList(imageUrl));
                    pageItem.setX0(calculateLeftX0);
                    pageItem.setY0(calculateLeftY0);
                    pageItem.setX1(calculateLeftX1);
                    pageItem.setY1(calculateRightY1);
                    pageItem.setWidth(calculateLeftX1 - calculateLeftX0);
                    pageItem.setHeight(calculateRightY1 - calculateLeftY0);
                    pageItem.setFontSize(12D);
                    pageItem.setItemType("image");
                    pageItemList.add(pageItem);
                }

            }

        }

        resultDto.getPageItemList().sort(new Comparator<PageItem>() {
            @Override
            public int compare(PageItem o1, PageItem o2) {
                return o1.getY0() > o2.getY0() ? 1 : -1;
            }
        });

        for(int i = 1 ; i < resultDto.getPageItemList().size() ; i++){
            PageItem pageItem = resultDto.getPageItemList().get(i);
            PageItem prePageItem = resultDto.getPageItemList().get(i-1);
            Double marginTop = pageItem.getY0() - prePageItem.getY1();
            if(marginTop < 0){
                marginTop = 10D;
            }
            pageItem.setMarginTop(marginTop);
        }

        return resultDto;
    }

    public static List<PageItem> getPageItemListByTextInContent(
        String content,Double x0,Double x1,Double y0,Double y1,Integer idCounter, Double sourceHeight, int pageNumber) {
        Document document = Jsoup.parse(content);
        System.out.println(document);
        Elements tableElementList = document.getElementsByTag("table");

        List<PageItem> pageItemList = new ArrayList<>();
        for(Element tableElement : tableElementList){
            Elements trElementList = tableElement.getElementsByTag("tr");
            int maxCol = 0;
            // 获取最大的列数
            for(Element trElement : trElementList){
                int tdSize = trElement.getElementsByTag("td").size();
                int thSize = trElement.getElementsByTag("th").size();
                if(tdSize > maxCol){
                    maxCol = tdSize;
                }
                if(thSize > maxCol){
                    maxCol = thSize;
                }
            }
            double[] singleWidthArr = new double[maxCol];
            // 获取单列宽度
            double singeWidth = new BigDecimal((x1-x0)/maxCol).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            // 赋值每个单列宽度
            for(int i = 0 ; i < maxCol ; i++){
                singleWidthArr[i] = singeWidth;
            }
            double widthGap = x1-x0-singeWidth*maxCol;
            singleWidthArr[maxCol - 1] += new BigDecimal(widthGap).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            double[] widthPercentOfCols = new double[maxCol];
            for(int i = 0 ; i < maxCol ; i++){
                widthPercentOfCols[i] = new BigDecimal(1.0D/maxCol).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
            }
            double radioGap = 1-widthPercentOfCols[0] * maxCol;
            widthPercentOfCols[maxCol - 1] += radioGap;

            int[][] matrix = new int[trElementList.size()][maxCol];
            List<List<TableSingleItem>> tableRowList = new ArrayList<>();
            for(int i = 0 ; i < trElementList.size() ; i++){
                Element trElement = trElementList.get(i);
                Elements tds = trElement.getElementsByTag("td");
                Elements ths = trElement.getElementsByTag("th");
                tds.addAll(ths);
                List<TableSingleItem> tableCellList = new ArrayList<>();
                for(int j = 0 ; j < tds.size() ; j++){
                    String colLenStr = tds.get(j).attr("colspan");
                    String rowLenStr = tds.get(j).attr("rowspan");
                    if(colLenStr == null || "".equals(colLenStr.trim())){
                        colLenStr = "1";
                    }
                    if(rowLenStr == null || "".equals(rowLenStr.trim())){
                        rowLenStr = "1";
                    }
                    int colLen = Integer.parseInt(colLenStr);
                    int rowLen = Integer.parseInt(rowLenStr);
                    int startCol = 0;
                    boolean findStart = false;
                    int endCol = 0;
                    int[] matrixRow = matrix[i];
                    for(int k = 0 ; k < matrixRow.length ; k++){
                        if(!findStart){
                            if(matrixRow[k] == 0){
                                startCol = j;
                                findStart = true;
                                matrix[i][k] = 1;
                            }
                        }
                        if (findStart){
                            if (k < startCol + colLen - 1) {
                                matrix[i][k] = 1;
                            }
                            else if (k == startCol + colLen - 1) {
                                matrix[i][k] = 1;
                                endCol = j;
                                break;
                            }
                        }
                    }
                    if (rowLen > 1) {
                        for (int k = 0; k < rowLen - 2; k++) {
                            for(int l = startCol ; l < endCol + 1 ; l++){
                                matrix[i + k + 1][l] = 1;
                            }
                        }
                    }
                    String text = tds.get(j).text();
                    TableSingleItem tableCell = new TableSingleItem();
                    tableCell.setText(Arrays.asList(convertInlineToDisplay(MarkDownUtils.markdownToPlainTextV2(text))));
                    tableCell.setCellType("text");
                    tableCell.setBgColor(null);
                    tableCell.setRowLen(rowLen);
                    tableCell.setColumnLen(colLen);
                    double cellRadio = 0;
                    for(int p = startCol ; p < endCol + 1 ; p++){
                        cellRadio += widthPercentOfCols[p];
                    }
                    tableCell.setCellRadio(cellRadio);
                    tableCell.setFontSize(12D);
                    tableCell.setHeight(new BigDecimal((y1-y0)/trElementList.size()).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
                    double cellWidth = 0;
                    for(int p = startCol ; p < endCol + 1 ; p++){
                        cellWidth += singleWidthArr[p];
                    }
                    tableCell.setWidth(new BigDecimal(cellWidth).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
                    tableCellList.add(tableCell);
                }
                tableRowList.add(tableCellList);
            }

            if (tableRowList.size() == 1 && tableRowList.get(0).size() == 1){
                continue;
            }

            PageItem pageItem = new PageItem();
            BoundingBox boundingBox = new BoundingBox(pageNumber, x0, sourceHeight - y1, x1, sourceHeight - y0);
            pageItem.setBoundingBox(boundingBox);
            pageItemList.add(pageItem);
            pageItem.setContent(tableRowList);
            pageItem.setItemType("stream_table");
            pageItem.setWidth(new BigDecimal(x1-x0).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setHeight(new BigDecimal(y1-y0).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setX0(new BigDecimal(x0).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setX1(new BigDecimal(x1).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setY0(new BigDecimal(y0).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setY1(new BigDecimal(y1).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue());
            pageItem.setFontSize(0D);
            pageItem.setId(idCounter);
            pageItem.setThirdParty(true);
        }
        return pageItemList;
    }

    private static String convertInlineToDisplay(String inlineFormula) {
        // 使用正则表达式匹配内联模式的公式
        String regex = "\\$(.*?)\\$";
        // 使用 display 模式替换内联模式
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(inlineFormula);
        while (matcher.find()){
            String displayFormula = matcher.group(1);
            String displayFormulaRegex =  "\\(" + displayFormula + "\\)";
            inlineFormula = inlineFormula.replace(matcher.group(0), displayFormulaRegex);
        }
        return inlineFormula;
    }
}
