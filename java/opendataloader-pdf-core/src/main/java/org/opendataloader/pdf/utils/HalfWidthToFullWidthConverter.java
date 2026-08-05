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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts half-width ideographic punctuation to their full-width counterparts.
 *
 * <p>Currently supports:
 * <ul>
 *   <li>{@code ､} (U+FF64, Halfwidth Ideographic Comma) to {@code 、} (U+3001, Ideographic Comma)</li>
 * </ul>
 *
 * The mapping is kept extensible so additional half-width/full-width pairs can be added
 * without changing the public API.
 */
public final class HalfWidthToFullWidthConverter {

    private static final Map<Character, Character> HALF_TO_FULL_MAP;

    static {
        Map<Character, Character> map = new HashMap<>();
        map.put('\uFF64', '\u3001'); // halfwidth ideographic comma -> fullwidth ideographic comma
        HALF_TO_FULL_MAP = Collections.unmodifiableMap(map);
    }

    private HalfWidthToFullWidthConverter() {
    }

    /**
     * Converts any half-width characters known to this converter into their full-width
     * counterparts. Characters with no mapping are left unchanged.
     *
     * @param value the input string, may be null
     * @return the converted string, or an empty string if the input is null
     */
    public static String convert(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }

        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            Character fullWidth = HALF_TO_FULL_MAP.get(c);
            result.append(fullWidth != null ? fullWidth : c);
        }
        return result.toString();
    }
}
