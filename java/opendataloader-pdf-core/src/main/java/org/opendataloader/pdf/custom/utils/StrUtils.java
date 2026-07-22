package org.opendataloader.pdf.custom.utils;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

public class StrUtils {
    /** 是否包含繁体独有字 */
    public static boolean containsTraditional(String text) {
        return !ZhConverterUtil.toTraditional(text).equals(text);
    }

    /** 是否包含简体独有字 */
    public static boolean containsSimplified(String text) {
        return !ZhConverterUtil.toSimple(text).equals(text);
    }

    /** 是否包含英文字母 */
    public static boolean containsEnglishLetter(String text) {
        return text.codePoints().anyMatch(cp ->
            (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'));
    }

    /** 是否包含阿拉伯数字 */
    public static boolean containsArabicDigit(String text) {
        return text.codePoints().anyMatch(cp -> cp >= '0' && cp <= '9');
    }

    /** 是否包含四类中的任意一种 */
    public static boolean containsAny(String text) {
        return containsSimplified(text)
            || containsTraditional(text)
            || containsEnglishLetter(text)
            || containsArabicDigit(text);
    }
}
