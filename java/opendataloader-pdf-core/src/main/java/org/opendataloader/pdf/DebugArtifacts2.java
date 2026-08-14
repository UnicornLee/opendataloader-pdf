package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.gf.model.impl.containers.StaticStorages;
import org.verapdf.parser.PDFFlavour;
import org.verapdf.tools.StaticResources;
import org.verapdf.pd.PDDocument;
import org.verapdf.gf.model.impl.sa.GFSAPDFDocument;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.entities.IDocument;
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.Arrays;
import java.util.List;

/**
 * Experiment B: parse with isIgnoreMCIDs forced to true (skip struct-tree
 * nesting) to check whether the pattern-filled bars surface as LineChunks
 * in the artifact layer, and with what color.
 */
public class DebugArtifacts2 {

    public static void main(String[] args) throws Exception {
        String pdf = args.length > 0 ? args[0]
                : "D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\202304271682510470028924-15.pdf";
        int pageNum = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        PDDocument pdDocument = new PDDocument(pdf);
        GFSAPDFDocument document = new GFSAPDFDocument(pdDocument);
        StaticResources.setFlavour(java.util.Collections.singletonList(PDFFlavour.WCAG_2_2_HUMAN));
        StaticResources.setIsFontProgramsParsing(true);
        StaticStorages.setIsIgnoreMCIDs(true);          // <-- force: no struct-tree nesting
        StaticStorages.setIsAddSpacesBetweenTextPieces(true);
        document.parseChunks();

        List<IChunk> artifacts = document.getArtifacts(pageNum);
        System.out.println("page " + (pageNum + 1) + " artifacts total=" + (artifacts == null ? "null" : artifacts.size()));
        if (artifacts == null) return;

        int lineCount = 0, lineArtCount = 0, nullColor = 0;
        for (IChunk c : artifacts) {
            if (c instanceof LineChunk) {
                lineCount++;
                LineChunk l = (LineChunk) c;
                BoundingBox bb = l.getBoundingBox();
                double[] col = l.getStrokeColor();
                if (col == null) nullColor++;
                // focus on the bar region (x 160..480, y 630..750)
                if (bb.getLeftX() > 155 && bb.getLeftX() < 480 && bb.getBottomY() > 600 && bb.getBottomY() < 760) {
                    System.out.printf("BAR? bbox=(%.2f,%.2f)-(%.2f,%.2f) w=%.2f h=%.2f width=%.2f color=%s%n",
                            bb.getLeftX(), bb.getTopY(), bb.getRightX(), bb.getBottomY(),
                            bb.getWidth(), bb.getHeight(), l.getWidth(), Arrays.toString(col));
                }
            } else if (c instanceof LineArtChunk) {
                lineArtCount++;
            }
        }
        System.out.printf("summary: LineChunk=%d LineArtChunk=%d nullColor=%d%n", lineCount, lineArtCount, nullColor);
    }
}