/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.custom.utils;

import org.opendataloader.pdf.custom.constants.BookmarkConstant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Classifies heading/bookmark text by the numbering prefix type defined in
 * {@link BookmarkConstant}.
 *
 * <p>This utility is intentionally separate from
 * {@link org.opendataloader.pdf.processors.PageBookmarkProcessor} so that
 * catalog bookmark processing can reuse the same prefix definitions without
 * pulling in the full page-bookmark candidate pipeline.</p>
 */
public class BookmarkPrefixClassifier {

    /**
     * Numbering system of a matched prefix.
     */
    public enum NumberSystem {
        ARABIC, CHINESE
    }

    /**
     * Result of prefix classification.
     */
    public static final class PrefixType {
        private final String template;
        private final NumberSystem numberSystem;

        public PrefixType(String template, NumberSystem numberSystem) {
            this.template = template;
            this.numberSystem = numberSystem;
        }

        public String getTemplate() {
            return template;
        }

        public NumberSystem getNumberSystem() {
            return numberSystem;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PrefixType)) {
                return false;
            }
            PrefixType that = (PrefixType) o;
            return Objects.equals(template, that.template) && numberSystem == that.numberSystem;
        }

        @Override
        public int hashCode() {
            return Objects.hash(template, numberSystem);
        }

        @Override
        public String toString() {
            return template + "/" + numberSystem;
        }
    }

    private static final String TEMPLATE_NUMBER = "#";
    private static final String TEMPLATE_CHAPTER = "第#章";
    private static final String TEMPLATE_SECTION = "第#节";
    private static final String TEMPLATE_ARTICLE = "第#条";
    private static final String TEMPLATE_PAREN = "（#）";
    private static final String TEMPLATE_ASCII_PAREN = "(#)";
    private static final String TEMPLATE_CLOSE_PAREN = "#）";
    private static final String TEMPLATE_CLOSE_ASCII_PAREN = "#)";
    private static final String TEMPLATE_CHINESE_COMMA = "#、";
    private static final char CHINESE_COMMA_FULL_WIDTH = '\u3001';
    private static final char FULL_WIDTH_DOT = '．';

    private static final class ConstantPattern {
        final String constant;
        final String template;
        final NumberSystem numberSystem;
        final int value;

        ConstantPattern(String constant, String template, NumberSystem numberSystem, int value) {
            this.constant = constant;
            this.template = template;
            this.numberSystem = numberSystem;
            this.value = value;
        }
    }

    private static final List<ConstantPattern> PATTERNS = buildPatterns();

    private static List<ConstantPattern> buildPatterns() {
        List<ConstantPattern> patterns = new ArrayList<>();
        for (int i = 0; i < BookmarkConstant.NUMBERS_1_TO_100.size(); i++) {
            patterns.add(new ConstantPattern(String.valueOf(BookmarkConstant.NUMBERS_1_TO_100.get(i)),
                    TEMPLATE_NUMBER, NumberSystem.ARABIC, i + 1));
        }
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_1_TO_100, TEMPLATE_NUMBER, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_WITH_COMMA_1_TO_100, TEMPLATE_CHINESE_COMMA, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_CHAPTERS_1_TO_100, TEMPLATE_CHAPTER, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_CHAPTERS_1_TO_100, TEMPLATE_CHAPTER, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_SECTIONS_1_TO_100, TEMPLATE_SECTION, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_SECTIONS_1_TO_100, TEMPLATE_SECTION, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBER_ARTICLES_1_TO_100, TEMPLATE_ARTICLE, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBER_ARTICLES_1_TO_100, TEMPLATE_ARTICLE, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_IN_PARENS_1_TO_100, TEMPLATE_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_IN_PARENS_1_TO_100, TEMPLATE_PAREN, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_IN_ASCII_PARENS_1_TO_100, TEMPLATE_ASCII_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.CHINESE_NUMBERS_IN_ASCII_PARENS_1_TO_100, TEMPLATE_ASCII_PAREN, NumberSystem.CHINESE);
        addPatternList(patterns, BookmarkConstant.NUMBERS_WITH_CLOSE_PAREN_1_TO_100, TEMPLATE_CLOSE_PAREN, NumberSystem.ARABIC);
        addPatternList(patterns, BookmarkConstant.NUMBERS_WITH_CLOSE_ASCII_PAREN_1_TO_100, TEMPLATE_CLOSE_ASCII_PAREN, NumberSystem.ARABIC);
        // Longest first so that "10" is preferred over "1" and "第10章" over "第1章".
        patterns.sort(Comparator.comparingInt((ConstantPattern p) -> p.constant.length()).reversed());
        return patterns;
    }

    private static void addPatternList(List<ConstantPattern> patterns, List<String> constants,
                                       String template, NumberSystem numberSystem) {
        for (int i = 0; i < constants.size(); i++) {
            patterns.add(new ConstantPattern(constants.get(i), template, numberSystem, i + 1));
        }
    }

    private BookmarkPrefixClassifier() {
    }

    /**
     * Returns true if the text starts with a recognized bookmark numbering prefix.
     */
    public static boolean isBookmarkCandidate(String text) {
        return text != null && matchPrefix(text.trim()) != null;
    }

    /**
     * Classifies the text by its prefix template and numbering system.
     *
     * @return the prefix type, or null if the text does not start with a
     *         recognized bookmark prefix
     */
    public static PrefixType classify(String text) {
        if (text == null) {
            return null;
        }
        ConstantPattern match = matchPrefix(text.trim());
        if (match == null) {
            return null;
        }
        return new PrefixType(match.template, match.numberSystem);
    }

    private static ConstantPattern matchPrefix(String text) {
        for (ConstantPattern pattern : PATTERNS) {
            if (text.startsWith(pattern.constant)) {
                if (!hasValidBookmarkSuffix(text, pattern)) {
                    continue;
                }
                return pattern;
            }
        }
        return null;
    }

    private static boolean hasValidBookmarkSuffix(String text, ConstantPattern pattern) {
        char last = pattern.constant.charAt(pattern.constant.length() - 1);
        if (last == ')' || last == '）'
                || last == CHINESE_COMMA_FULL_WIDTH) {
            return true;
        }
        int suffixIndex = pattern.constant.length();
        if (suffixIndex >= text.length()) {
            return false;
        }
        char suffix = text.charAt(suffixIndex);
        // Full-width and half-width dots are treated as the same type.
        boolean isDot = suffix == '.' || suffix == FULL_WIDTH_DOT;
        if (!(Character.isWhitespace(suffix) || isDot
                || suffix == CHINESE_COMMA_FULL_WIDTH)) {
            return false;
        }
        // A dot right after the number cannot be followed by another digit
        // ("1.5" is a decimal, not a bookmark).
        return !isDot || suffixIndex + 1 >= text.length()
                || !Character.isDigit(text.charAt(suffixIndex + 1));
    }
}
