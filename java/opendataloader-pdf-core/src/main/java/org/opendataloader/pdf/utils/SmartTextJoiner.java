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
package org.opendataloader.pdf.utils;

import java.util.List;

/**
 * Joins multiple text pieces (lines, runs, segments) using a "smart space" rule.
 *
 * <p>A single space is inserted between two adjacent pieces only when the last
 * character of the left piece and the first character of the right piece are
 * both ASCII letters ({@code a-z}, {@code A-Z}) or both ASCII digits
 * ({@code 0-9}). In every other case the two pieces are concatenated directly,
 * which avoids stray spaces at category boundaries such as
 * Chinese-anything, letter+digit, digit+letter, or any pair involving
 * whitespace/punctuation.</p>
 */
public final class SmartTextJoiner {

    private SmartTextJoiner() {
    }

    /**
     * Joins the given non-null, non-empty pieces using the smart-space rule.
     * Empty or null entries are skipped. Returns an empty string when no piece
     * is appended.
     */
    public static String joinNonEmptyPieces(List<String> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null) {
                continue;
            }
            appendSmart(sb, piece);
        }
        return sb.toString();
    }

    /**
     * Joins the given pieces using the smart-space rule. Null entries are
     * treated as empty. Empty pieces are still appended as no-ops (the loop
     * skips them).
     */
    public static String joinPieces(List<String> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : pieces) {
            appendSmart(sb, piece == null ? "" : piece);
        }
        return sb.toString();
    }

    /**
     * Appends {@code addition} to {@code sb} using the smart-space rule.
     * Empty additions are skipped.
     */
    public static void appendSmart(StringBuilder sb, String addition) {
        if (addition == null || addition.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            char lastChar = sb.charAt(sb.length() - 1);
            char firstChar = addition.charAt(0);
            boolean lastIsLetter = isAsciiLetter(lastChar);
            boolean firstIsLetter = isAsciiLetter(firstChar);
            boolean lastIsDigit = isAsciiDigit(lastChar);
            boolean firstIsDigit = isAsciiDigit(firstChar);
            if ((lastIsLetter && firstIsLetter) || (lastIsDigit && firstIsDigit)) {
                sb.append(' ');
            }
        }
        sb.append(addition);
    }

    public static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}