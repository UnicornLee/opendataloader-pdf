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
package org.opendataloader.pdf.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code header_and_footer} size gate: the serialized value (key names, quotes and
 * commas included) must stay shorter than 1000 characters, and lines are dropped whole from the
 * tail of the footer first, then from the tail of the header.
 */
class JsonWriterHeaderAndFooterLimitTest {

    private static final int MAX_VALUE_LENGTH = 1000;

    @Test
    void footerLinesAreDroppedFromTheTailFirst() throws Exception {
        List<String> header = lines("H", 20);
        List<String> footer = lines("F", 300);

        trim(header, footer);

        assertEquals(lines("H", 20), header, "header must survive while the footer alone overflows");
        assertTrue(footer.size() < 300, "footer must lose lines, got " + footer.size());
        assertEquals(lines("F", footer.size()), footer, "kept footer lines must be an in-order prefix");
        assertTrue(serializedValueLength(header, footer) < MAX_VALUE_LENGTH);
    }

    @Test
    void headerIsTrimmedOnceTheFooterIsGone() throws Exception {
        List<String> header = lines("H", 300);
        List<String> footer = new ArrayList<>();
        footer.add("F");

        trim(header, footer);

        assertTrue(footer.isEmpty(), "footer must be emptied before the header is touched");
        assertTrue(header.size() < 300, "header must lose lines, got " + header.size());
        assertEquals(lines("H", header.size()), header, "kept header lines must be an in-order prefix");
        assertTrue(serializedValueLength(header, footer) < MAX_VALUE_LENGTH);
    }

    @Test
    void shortListsAreLeftUntouched() throws Exception {
        List<String> header = new ArrayList<>();
        header.add("Company Annual Report 2026");
        List<String> footer = new ArrayList<>();
        footer.add("Confidential");

        trim(header, footer);

        assertEquals(List.of("Company Annual Report 2026"), header);
        assertEquals(List.of("Confidential"), footer);
    }

    private static List<String> lines(String prefix, int count) {
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(prefix + i);
        }
        return result;
    }

    private static void trim(List<String> header, List<String> footer) throws Exception {
        Method method = JsonWriter.class.getDeclaredMethod(
            "trimToMaxValueLength", List.class, List.class);
        method.setAccessible(true);
        method.invoke(null, header, footer);
    }

    /**
     * Independent measurement of the written value: compact JSON of
     * {@code {"header":[...],"footer":[...]}} with empty lists omitted.
     */
    private static int serializedValueLength(List<String> header, List<String> footer) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        if (!header.isEmpty()) {
            value.put(JsonName.HEADER_AND_FOOTER_HEADER, header);
        }
        if (!footer.isEmpty()) {
            value.put(JsonName.HEADER_AND_FOOTER_FOOTER, footer);
        }
        return new ObjectMapper().writeValueAsString(value).length();
    }
}
