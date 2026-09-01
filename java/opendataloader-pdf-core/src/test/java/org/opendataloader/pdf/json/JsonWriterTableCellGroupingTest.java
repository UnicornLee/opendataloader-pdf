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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonWriterTableCellGroupingTest {

    @Test
    void textChunkCanBeAppendedToANewGroupAfterAVerticalGap() throws Exception {
        TableBorderCell cell = new TableBorderCell(0, 0, 1, 1, null);
        cell.setBoundingBox(new BoundingBox(0, 0, 0, 100, 100));
        cell.addContentObject(textChunk("A", 10, 60, 20, 80));
        cell.addContentObject(textChunk("B", 30, 20, 40, 40));
        cell.addContentObject(textChunk("C", 45, 22, 55, 38));

        TableBorderRow row = new TableBorderRow(0, 1, null);
        row.setBoundingBox(new BoundingBox(0, 0, 0, 100, 100));
        row.getCells()[0] = cell;

        TableBorder table = new TableBorder(1, 1);
        table.setRecognizedStructureId(1L);
        table.setBoundingBox(new BoundingBox(0, 0, 0, 100, 100));
        table.getRows()[0] = row;
        table.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();

        List<IObject> pageContents = new ArrayList<>();
        pageContents.add(table);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator generator = new ObjectMapper().getFactory().createGenerator(output)) {
            Method method = JsonWriter.class.getDeclaredMethod(
                "generateJsonPageContentData", String.class, int.class, boolean.class,
                double.class, List.class, JsonGenerator.class);
            method.setAccessible(true);
            method.invoke(null, "test-url", 0, true, 100.0, pageContents, generator);
        }

        JsonNode tableJson = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
        JsonNode text = tableJson.get("content").get(0).get(0).get("text");
        assertNotNull(text, tableJson.toString());
        assertEquals("A", text.get(0).asText());
        assertEquals("B C", text.get(1).asText());
    }

    private static TextChunk textChunk(String value, double left, double bottom, double right, double top) {
        return new TextChunk(new BoundingBox(0, left, bottom, right, top), value, 10,
            (bottom + top) / 2);
    }
}
