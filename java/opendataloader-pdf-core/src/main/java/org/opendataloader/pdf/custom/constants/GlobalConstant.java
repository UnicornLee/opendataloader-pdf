package org.opendataloader.pdf.custom.constants;

import java.util.Arrays;
import java.util.List;

public class GlobalConstant {

    public static final List<String> SENTENCE_ENDING_PUNCTUATION = Arrays.asList(".", "。", "！", "！", "？", "？");
    public static final List<String> SPECIAL_CHARACTER_ORIGIN = Arrays.asList("\uF0A3", "\uF052", "\uF0FE", "£", "R", "\uF06C", "\uF0B7");
    public static final List<String> SPECIAL_CHARACTER_TARGET = Arrays.asList("☐", "☑", "☑", "☐", "☑", "•", "•");
}
