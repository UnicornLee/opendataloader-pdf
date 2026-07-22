package org.opendataloader.pdf.processors;

import org.opendataloader.pdf.custom.utils.StrUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

public class StreamTableProcessor {

    public static List<IObject> processStreamTables(List<IObject> contents, int pageNumber) {
        // 1. 先将文本信息按行进行聚合
        List<List<IObject>> rows = groupByRows(contents);
        // 2. 收集行内文本间的空隙范围
        // 3. 根据空隙范围是否存在无线表格
        boolean isExisted = existStreamTable(rows);
        // 4. 对整页内容进行截图发给大模型进行内容的识别
        if (isExisted) {}
        // 5. 根据识别回来的内容替换无线表格的部分
        return new ArrayList<>(contents);
    }

    private static List<List<IObject>> groupByRows(List<IObject> contents) {
        List<List<IObject>> rows = new ArrayList<>();
        List<IObject> currentRow = new ArrayList<>();
        for (IObject content : contents) {
            if (content instanceof TableBorder || content instanceof ImageChunk) {
                if (currentRow.size() > 0) {
                    currentRow.sort(Comparator.comparingDouble(item -> item.getBoundingBox().getLeftX()));
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                rows.add(List.of(content));
                continue;
            }

            if (content instanceof TextLine) {
                // 1. 先把当前 TextLine 内所有"有效（非空白）"的 TextChunk 提取出来
                List<TextChunk> validChunks = new ArrayList<>();
                for (TextChunk textChunk : ((TextLine) content).getTextChunks()) {
                    if (!"".equals(textChunk.getValue().trim())) {
                        validChunks.add(textChunk);
                    }
                }

                // 2. 没有有效内容的 TextLine 直接跳过，避免污染 currentRow
                if (validChunks.isEmpty()) {
                    continue;
                }

                // 3. 后续统一以 TextChunk 粒度进行行聚合，保证 currentRow 的元素类型一致
                if (currentRow.size() == 0) {
                    currentRow.addAll(validChunks);
                } else {
                    Double maxTopY = currentRow.stream()
                        .mapToDouble(obj -> obj.getBoundingBox().getTopY())
                        .max()
                        .orElse(0.0);
                    Double minBottomY = currentRow.stream()
                        .mapToDouble(obj -> obj.getBoundingBox().getBottomY())
                        .min()
                        .orElse(0.0);
                    double middleY = (content.getTopY() + content.getBottomY()) / 2;
                    if ((middleY > minBottomY && middleY < maxTopY) ||
                        (((TextLine) content).getBaseLine() > minBottomY
                            && ((TextLine) content).getBaseLine() < maxTopY)) {
                        currentRow.addAll(validChunks);
                    } else {
                        currentRow.sort(Comparator.comparingDouble(item -> item.getBoundingBox().getLeftX()));
                        rows.add(currentRow);
                        currentRow = new ArrayList<>();
                        currentRow.addAll(validChunks);
                    }
                }
            }
        }
        if (currentRow.size() > 0) {
            currentRow.sort(Comparator.comparingDouble(item -> item.getBoundingBox().getLeftX()));
            rows.add(currentRow);
        }

        return rows;
    }

    private static boolean existStreamTable(List<List<IObject>> rows) {
        if (rows == null || rows.size() < 2) {
            return false;
        }

        // 收集行内文本间的空隙范围
        Map<Integer, List<double[]>> gapMap = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            List<IObject> groups = rows.get(i);
            if (groups.size() > 1) {
                List<double[]> gaps = new ArrayList<>();
                double startX = groups.get(0).getRightX();
                for (int j = 1; j < groups.size(); j++) {
                    TextChunk textChunk = (TextChunk) groups.get(j);
                    if (textChunk.getLeftX() - startX > 10) {
                        double[] gap = new double[2];
                        gap[0] = startX;
                        gap[1] = textChunk.getLeftX();
                        gaps.add(gap);
                    }
                    startX = textChunk.getRightX();
                }

                if (gaps.size() > 0) {
                    gapMap.put(i, gaps);
                }
            }
        }

        // 根据空隙范围确定无线表格的范围
        List<List<Integer>> tableIndexGroups = new ArrayList<>();
        if (gapMap.size() > 1) {
            Integer[] rowNums = gapMap.keySet().toArray(new Integer[0]);
            Arrays.sort(rowNums);
            List<Integer> groups = new ArrayList<>();
            Integer maxRowNum = 0;
            boolean toClose;
            for (int i = 0; i < rowNums.length; i++) {
                toClose = false;
                if (groups.size() == 0) {
                    groups.add(rowNums[i]);
                    maxRowNum = rowNums[i];
                    continue;
                }

                // 确认中间没有 table 或 image
                if (rowNums[i] - groups.get(groups.size() - 1) > 1) {
                    for (int m = groups.get(groups.size() - 1) + 1; m < rowNums[i]; m++) {
                        if (rows.get(m).get(0) instanceof TableBorder || rows.get(m).get(0) instanceof ImageChunk) {
                            toClose = true;
                            break;
                        }
                    }
                }
                if (toClose) {
                    if (groups.size() > 1) {
                        tableIndexGroups.add(groups);
                    }
                    groups = new ArrayList<>();
                    groups.add(rowNums[i]);
                    maxRowNum = rowNums[i];
                    continue;
                }

                // 对现在的间隔跟之前的间隔做匹配：是否存在重叠的间隔
                List<double[]> longGaps;
                List<double[]> shortGaps;
                if (gapMap.get(rowNums[i]).size() > gapMap.get(maxRowNum).size()) {
                    longGaps = gapMap.get(rowNums[i]);
                    shortGaps = gapMap.get(maxRowNum);
                } else {
                    longGaps = gapMap.get(maxRowNum);
                    shortGaps = gapMap.get(rowNums[i]);
                }
                Integer matchCount = 0;
                for (double[] longGap : longGaps) {
                    for (double[] shortGap : shortGaps) {
                        double leftX = longGap[0] > shortGap[0] ? longGap[0] : shortGap[0];
                        double rightX = longGap[1] < shortGap[1] ? longGap[1] : shortGap[1];
                        if (rightX - leftX >= 2) {
                            matchCount++;
                            break;
                        }
                    }
                }

                // 根据间隔的行数和间隔重叠的情况决定是否关闭现在的无线表格，开启下一个无线表格的识别
                if (rowNums[i] - groups.get(groups.size() - 1) > 2) {
                    if (matchCount < 2) {
                        toClose = true;
                    }
                } else {
                    if (matchCount == 0) {
                        toClose = true;
                    }
                }
                if (toClose) {
                    if (groups.size() > 1) {
                        tableIndexGroups.add(groups);
                    }
                    groups = new ArrayList<>();
                    groups.add(rowNums[i]);
                    maxRowNum = rowNums[i];
                    continue;
                }

                if (rowNums[i] - groups.get(groups.size() - 1) > 1) {
                    // 如果中间没有间隔的行不被限定在某个单元格或几个单元格中则关闭当前无线表格，开启下一个无线表格识别
                    for (int m = groups.get(groups.size() - 1) + 1; m < rowNums[i]; m++) {
                        List<IObject> currentRow = rows.get(m);
                        OptionalDouble leftX = currentRow.stream().mapToDouble(IObject::getLeftX).min();
                        OptionalDouble rightX = currentRow.stream().mapToDouble(IObject::getRightX).max();
                        if (leftX.isPresent() && rightX.isPresent()) {
                            for (int n = 0; n < longGaps.size(); n++) {
                                double[] currentGap = longGaps.get(n);
                                if (leftX.getAsDouble() >= currentGap[1] || rightX.getAsDouble() <= currentGap[0]) {
                                    break;
                                }
                                if (n == longGaps.size() - 1) {
                                    toClose = true;
                                    break;
                                }
                            }
                        } else {
                            toClose = true;
                        }
                        if (toClose) {
                            break;
                        }
                    }
                    if (toClose) {
                        if (groups.size() > 1) {
                            tableIndexGroups.add(groups);
                        }
                        groups = new ArrayList<>();
                        groups.add(rowNums[i]);
                        maxRowNum = rowNums[i];
                        continue;
                    }

                    // 如果中间没有间隔的行之间的间距过大则关闭当前无线表格，开启下一个无线表格识别
                    List<Double> marginList = new ArrayList<>();
                    for (int m = groups.get(groups.size() - 1) + 1; m < rowNums[i]; m++) {
                        if (m == 0) {
                            continue;
                        }
                        List<IObject> prevRow = rows.get(m - 1);
                        List<IObject> currentRow = rows.get(m);
                        OptionalDouble prevBottomY = prevRow.stream().mapToDouble(IObject::getBottomY).min();
                        OptionalDouble currentTopY = currentRow.stream().mapToDouble(IObject::getTopY).max();
                        if (prevBottomY.isPresent() && currentTopY.isPresent()) {
                            marginList.add(currentTopY.getAsDouble() - prevBottomY.getAsDouble());
                        }
                    }
                    if (marginList.size() > 0) {
                        Double minMargin = Collections.min(marginList);
                        Double maxMargin = Collections.max(marginList);
                        if (maxMargin >= 20 || maxMargin - minMargin >= 15) {
                            toClose = true;
                        }
                    }
                    if (toClose) {
                        if (groups.size() > 1) {
                            tableIndexGroups.add(groups);
                        }
                        groups = new ArrayList<>();
                        groups.add(rowNums[i]);
                        maxRowNum = rowNums[i];
                        continue;
                    }
                }

                groups.add(rowNums[i]);
                if (gapMap.get(rowNums[i]).size() > gapMap.get(maxRowNum).size()) {
                    maxRowNum = rowNums[i];
                }
            }
            if (groups.size() > 1) {
                tableIndexGroups.add(groups);
            }
        }

        boolean existStreamTable = false;
        if (tableIndexGroups.size() > 0) {
            for (List<Integer> tableIndexGroup : tableIndexGroups) {
                if (tableIndexGroup.size() > 2) {
                    existStreamTable = true;
                    break;
                }

                Integer colNum = 0;
                for (Integer index : tableIndexGroup) {
                    if (gapMap.get(index).size() + 1 > colNum) {
                        colNum = gapMap.get(index).size() + 1;
                    }
                }
                if (colNum > 2) {
                    existStreamTable = true;
                    break;
                }

                // 如果只有2行2列，确认每个单元格的内容是否是正常文字，表格宽度是否过短。
                List<Double> widthList = new ArrayList<>();
                Integer abnormalContentCount = 0;
                for (Integer index : tableIndexGroup) {
                    for (IObject rowItem : rows.get(index)) {
                        TextChunk textChunk = (TextChunk) rowItem;
                        widthList.add(textChunk.getRightX() - textChunk.getLeftX());
                        if (!StrUtils.containsAny(textChunk.getValue().trim())) {
                            abnormalContentCount++;
                        }
                    }
                }
                if (abnormalContentCount < 2 && Collections.min(widthList) >= 50 && Collections.max(widthList) >= 100) {
                    existStreamTable = true;
                    break;
                }
            }
        }

        // 甄别无线表格，判断存在无线表格的可能性
        return existStreamTable;
    }
}
