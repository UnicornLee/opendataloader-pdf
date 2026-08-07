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
package org.opendataloader.pdf.entities.content;

import org.verapdf.wcag.algorithms.entities.content.InfoChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A geometric shape extracted from PDF vector graphics.
 *
 * <p>Used to represent filled color blocks (table cells, bars) and connected
 * line segments (polylines / line charts) that are otherwise converted to
 * plain {@link org.verapdf.wcag.algorithms.entities.content.LineChunk} and
 * dropped from the JSON output. Each shape carries its color and the bounding
 * box of the assembled original pieces.</p>
 */
public class ShapeChunk extends InfoChunk {

    /** A single filled rectangle / color block. */
    public static final String TYPE_RECTANGLE = "rectangle";
    /** A group of aligned filled rectangles forming a bar chart series. */
    public static final String TYPE_BAR_CHART = "bar_chart";
    /** Connected line segments forming a polyline / line chart. */
    public static final String TYPE_POLYLINE = "polyline";
    /** A single line segment that connects two shapes, typically an arrow. */
    public static final String TYPE_ARROW = "arrow";
    /** A generic group of same-colored connected pieces. */
    public static final String TYPE_GROUP = "group";

    private final String shapeType;
    private final double[] color;
    private final int componentCount;
    private final List<BoundingBox> componentBBoxes;

    public ShapeChunk(BoundingBox boundingBox, String shapeType, double[] color, int componentCount) {
        this(boundingBox, shapeType, color, componentCount, Collections.emptyList());
    }

    public ShapeChunk(BoundingBox boundingBox, String shapeType, double[] color,
                      int componentCount, List<BoundingBox> componentBBoxes) {
        super(boundingBox);
        this.shapeType = shapeType;
        this.color = color == null ? null : color.clone();
        this.componentCount = componentCount;
        this.componentBBoxes = componentBBoxes == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(componentBBoxes));
    }

    public String getShapeType() {
        return shapeType;
    }

    public double[] getColor() {
        return color == null ? null : color.clone();
    }

    public int getComponentCount() {
        return componentCount;
    }

    public List<BoundingBox> getComponentBBoxes() {
        return componentBBoxes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ShapeChunk that = (ShapeChunk) o;
        return componentCount == that.componentCount
                && Objects.equals(shapeType, that.shapeType)
                && Arrays.equals(color, that.color)
                && Objects.equals(componentBBoxes, that.componentBBoxes);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(shapeType);
        result = 31 * result + Arrays.hashCode(color);
        result = 31 * result + componentCount;
        result = 31 * result + Objects.hashCode(componentBBoxes);
        return result;
    }

    @Override
    public String toString() {
        return "ShapeChunk{" +
                "shapeType='" + shapeType + '\'' +
                ", color=" + Arrays.toString(color) +
                ", componentCount=" + componentCount +
                ", boundingBox=" + getBoundingBox() +
                '}';
    }
}
