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
package org.opendataloader.pdfbox;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects vector drawings (stroke / fill / fill+stroke paths) of a page via
 * PDFBox graphics-stream callbacks. Used as a fallback when the veraPDF chunk
 * layer merges filled shapes (e.g. arrowhead triangles) into larger
 * marked-content containers.
 */
public class GetDrawings {

    public enum PaintType {
        STROKE, FILL, FILL_STROKE
    }

    public static class Rect {
        public float x0;
        public float y0;
        public float x1;
        public float y1;
    }

    public static class Drawing {
        public PaintType type;
        public boolean closePath;
        public Rect rect;
    }

    /**
     * Returns all painted paths of the page. Never null.
     */
    public static List<Drawing> getDrawings(PDPage page, int pageNumber) throws IOException {
        Collector collector = new Collector(page);
        collector.processPage(page);
        return collector.drawings;
    }

    private static final class Collector extends PDFGraphicsStreamEngine {

        private final GeneralPath path = new GeneralPath();
        private final List<Drawing> drawings = new ArrayList<>();
        private boolean closed;

        private Collector(PDPage page) {
            super(page);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            path.moveTo((float) p0.getX(), (float) p0.getY());
            path.lineTo((float) p1.getX(), (float) p1.getY());
            path.lineTo((float) p2.getX(), (float) p2.getY());
            path.lineTo((float) p3.getX(), (float) p3.getY());
            path.closePath();
            closed = true;
        }

        @Override
        public void drawImage(PDImage pdImage) {
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
            path.moveTo(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            path.lineTo(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return path.getCurrentPoint();
        }

        @Override
        public void closePath() {
            path.closePath();
            closed = true;
        }

        @Override
        public void endPath() {
            resetPath();
        }

        @Override
        public void strokePath() {
            record(PaintType.STROKE);
        }

        @Override
        public void fillPath(int windingRule) {
            record(PaintType.FILL);
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            record(PaintType.FILL_STROKE);
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }

        private void record(PaintType type) {
            Drawing drawing = new Drawing();
            drawing.type = type;
            drawing.closePath = closed;
            Rectangle2D bounds = path.getBounds2D();
            if (!bounds.isEmpty()) {
                Rect rect = new Rect();
                rect.x0 = (float) bounds.getMinX();
                rect.y0 = (float) bounds.getMinY();
                rect.x1 = (float) bounds.getMaxX();
                rect.y1 = (float) bounds.getMaxY();
                drawing.rect = rect;
            }
            drawings.add(drawing);
            resetPath();
        }

        private void resetPath() {
            path.reset();
            closed = false;
        }
    }
}
