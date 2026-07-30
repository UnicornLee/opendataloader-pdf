package org.opendataloader.pdf.custom.constants;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BookmarkConstant {

    private static final String[] CHINESE_DIGITS = {
            "", "一", "二", "三", "四", "五", "六", "七", "八", "九"
    };

    public static final List<Integer> NUMBERS_1_TO_100 = IntStream.rangeClosed(1, 100)
            .boxed()
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBERS_1_TO_100 = IntStream.rangeClosed(1, 100)
            .mapToObj(BookmarkConstant::toChineseNumber)
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBER_CHAPTERS_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> "第" + n + "章")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBER_CHAPTERS_1_TO_100 = CHINESE_NUMBERS_1_TO_100.stream()
            .map(s -> "第" + s + "章")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBER_SECTIONS_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> "第" + n + "节")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBER_SECTIONS_1_TO_100 = CHINESE_NUMBERS_1_TO_100.stream()
            .map(s -> "第" + s + "节")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBER_ARTICLES_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> "第" + n + "条")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBER_ARTICLES_1_TO_100 = CHINESE_NUMBERS_1_TO_100.stream()
            .map(s -> "第" + s + "条")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBERS_IN_PARENS_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> "（" + n + "）")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBERS_IN_PARENS_1_TO_100 = CHINESE_NUMBERS_1_TO_100.stream()
            .map(s -> "（" + s + "）")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBERS_IN_ASCII_PARENS_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> "(" + n + ")")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> CHINESE_NUMBERS_IN_ASCII_PARENS_1_TO_100 = CHINESE_NUMBERS_1_TO_100.stream()
            .map(s -> "(" + s + ")")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBERS_WITH_CLOSE_PAREN_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> n + "）")
            .collect(Collectors.toUnmodifiableList());

    public static final List<String> NUMBERS_WITH_CLOSE_ASCII_PAREN_1_TO_100 = NUMBERS_1_TO_100.stream()
            .map(n -> n + ")")
            .collect(Collectors.toUnmodifiableList());

    private static String toChineseNumber(int n) {
        if (n < 1 || n > 100) {
            throw new IllegalArgumentException("Unsupported number: " + n);
        }
        if (n == 100) {
            return "一百";
        }
        if (n < 10) {
            return CHINESE_DIGITS[n];
        }
        int ten = n / 10;
        int unit = n % 10;
        if (ten == 1) {
            return "十" + CHINESE_DIGITS[unit];
        }
        return CHINESE_DIGITS[ten] + "十" + CHINESE_DIGITS[unit];
    }
}
