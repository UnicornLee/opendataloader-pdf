/*
 * Regression: runs the full preprocessing pipeline on the -84 flowchart and asserts
 * that arrow 1's bounding box extends onto its filled arrowhead (whose triangle the
 * veraPDF chunk layer merged into a larger MCID container and which is recovered
 * from PDFBox fill drawings via DocumentProcessor.extractPageFillBoxes). The -83
 * variant guards the bbox-only artifact path against regression.
 */
package org.opendataloader.pdf.processors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.entities.IDocument;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.List;

class ArrowE2ETest {

    @Test
    void arrowOneHeadRecoveredFromPdfBoxFill() throws Exception {
        String pdf = "D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\202302281677505819604328-84(流程图).pdf";
        DocumentProcessor.preprocessing(pdf, new Config());
        StaticLayoutContainers.clearContainers();
        IDocument doc = StaticContainers.getDocument();
        List<IChunk> artifacts = doc.getArtifacts(0);

        List<ShapeChunk> arrows = new ArrayList<>();
        for (IChunk c : artifacts) {
            if (c instanceof ShapeChunk
                    && ShapeChunk.TYPE_ARROW.equals(((ShapeChunk) c).getShapeType())) {
                arrows.add((ShapeChunk) c);
            }
        }
        System.out.println("== arrows on -84 page 0 ==");
        for (ShapeChunk a : arrows) {
            System.out.println("ARROW " + a.getBoundingBox());
        }

        // Arrow 1: shaft at y-up [298.5,249.87,299.5,267.97] plus its filled
        // arrowhead triangle merged into the MCID container. The PDFBox fallback
        // fill box [296,244.87,302,267.97] must extend the bbox to the tip.
        ShapeChunk arrow1 = arrows.stream()
                .filter(a -> a.getBoundingBox().getTopY() > 260 && a.getBoundingBox().getBottomY() < 250)
                .findFirst()
                .orElseThrow(() -> new AssertionError("arrow 1 expected"));
        Assertions.assertEquals(244.87, arrow1.getBoundingBox().getBottomY(), 0.01,
                "Arrow 1 bbox must extend onto its filled arrowhead tip");
        Assertions.assertEquals(296.0, arrow1.getBoundingBox().getLeftX(), 0.01);
        Assertions.assertEquals(302.0, arrow1.getBoundingBox().getRightX(), 0.01);
        Assertions.assertEquals(267.97, arrow1.getBoundingBox().getTopY(), 0.01);

        // Arrow 2 already had its head in the artifact layer; must be unchanged.
        ShapeChunk arrow2 = arrows.stream()
                .filter(a -> a.getBoundingBox().getTopY() > 200 && a.getBoundingBox().getTopY() < 215)
                .findFirst()
                .orElseThrow(() -> new AssertionError("arrow 2 expected"));
        Assertions.assertEquals(184.62, arrow2.getBoundingBox().getBottomY(), 0.01);
        Assertions.assertEquals(208.22, arrow2.getBoundingBox().getTopY(), 0.01);

        // No extra arrows may appear from other page fills.
        Assertions.assertEquals(2, arrows.size(), "No other fills may be misread as arrowheads");
    }

    @Test
    void flowDiagramIsGrownFromItsArrowheadOnPage106() throws Exception {
        // The organisation chart of page 106 is drawn as rows of boxes with a bracket of
        // percentages. Its only head is a 5 x 4 pt filled triangle that the connector
        // recognition never turns into an arrow (the shaft does not bridge two shapes),
        // yet the whole diagram region must be discovered from that head.
        String pdf = "D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\20260507AN202606291826520711-106.pdf";
        DocumentProcessor.preprocessing(pdf, new Config());
        StaticLayoutContainers.clearContainers();
        IDocument doc = StaticContainers.getDocument();
        List<IChunk> artifacts = doc.getArtifacts(0);

        List<IObject> shapes = new ArrayList<>();
        List<IObject> rawLines = new ArrayList<>();
        for (IChunk c : artifacts) {
            if (c instanceof ShapeChunk) {
                shapes.add(c);
            } else if (c instanceof LineChunk) {
                rawLines.add(c);
            } else if (c instanceof LineArtChunk) {
                List<LineChunk> children = ((LineArtChunk) c).getLineChunks();
                if (children == null || children.isEmpty()) {
                    rawLines.add(c);
                } else {
                    rawLines.addAll(children);
                }
            }
        }

        List<ShapeChunk> heads = new ArrayList<>();
        for (IObject shape : shapes) {
            if (ShapeChunk.TYPE_ARROW_HEADER.equals(((ShapeChunk) shape).getShapeType())) {
                heads.add((ShapeChunk) shape);
            }
        }
        Assertions.assertEquals(1, heads.size(), "The page's arrowhead must be recognized");
        // Only the slice beyond the shaft end is the head, not the whole arrow.
        BoundingBox head = heads.get(0).getBoundingBox();
        Assertions.assertEquals(265.53, head.getLeftX(), 0.01);
        Assertions.assertEquals(475.49, head.getBottomY(), 0.01);
        Assertions.assertEquals(270.52, head.getRightX(), 0.01);
        Assertions.assertEquals(479.08, head.getTopY(), 0.01);

        List<List<IObject>> groups = ShapeRecognizer.groupShapesByArrowHeaders(shapes, rawLines);
        Assertions.assertEquals(1, groups.size(), "The chart is one region grown from its head");
        BoundingBox region = new BoundingBox(0);
        for (IObject item : groups.get(0)) {
            region.union(item.getBoundingBox());
        }
        Assertions.assertEquals(86.88, region.getLeftX(), 0.01);
        Assertions.assertEquals(375.83, region.getBottomY(), 0.01);
        Assertions.assertEquals(508.39, region.getRightX(), 0.01);
        Assertions.assertEquals(531.89, region.getTopY(), 0.01);
    }

    @Test
    void arrowOn83StillRecognized() throws Exception {
        // -83 has its arrowhead as a bbox-only artifact chunk (not merged), so the
        // artifact path runs first and must remain unchanged by the fill fallback.
        String pdf = "D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\202302281677505819604328-83(流程图).pdf";
        DocumentProcessor.preprocessing(pdf, new Config());
        StaticLayoutContainers.clearContainers();
        IDocument doc = StaticContainers.getDocument();
        List<IChunk> artifacts = doc.getArtifacts(0);

        List<ShapeChunk> arrows = new ArrayList<>();
        for (IChunk c : artifacts) {
            if (c instanceof ShapeChunk
                    && ShapeChunk.TYPE_ARROW.equals(((ShapeChunk) c).getShapeType())) {
                arrows.add((ShapeChunk) c);
            }
        }
        System.out.println("== arrows on -83 page 0 ==");
        for (ShapeChunk a : arrows) {
            System.out.println("ARROW " + a.getBoundingBox());
        }
        // The page carries two vertical arrows. The second one is drawn as a chain of
        // collinear segments, which connector recognition now accepts as well (it used to
        // be dropped because only single-segment chains were considered).
        Assertions.assertEquals(2, arrows.size(), "Both arrows on -83 must be recognized");
        ShapeChunk lowerArrow = arrows.stream()
                .filter(a -> a.getBoundingBox().getBottomY() < 180)
                .findFirst()
                .orElseThrow(() -> new AssertionError("lower arrow expected"));
        BoundingBox b = lowerArrow.getBoundingBox();
        Assertions.assertEquals(158.52, b.getBottomY(), 0.01);
        Assertions.assertEquals(175.32, b.getTopY(), 0.01);
        Assertions.assertEquals(296.48, b.getLeftX(), 0.01);
        Assertions.assertEquals(303.48, b.getRightX(), 0.01);
    }
}
