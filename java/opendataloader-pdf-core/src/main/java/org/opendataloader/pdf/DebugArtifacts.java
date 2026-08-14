package org.opendataloader.pdf;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.entities.content.ShapeChunk;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.entities.IDocument;
import org.verapdf.wcag.algorithms.entities.content.IChunk;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

import java.util.Arrays;
import java.util.List;

/**
 * Debug artifact dump: parses the PDF via the same preprocessing path as the
 * real pipeline (`GFSAPDFDocument.parseChunks` + `ShapeRecognizer.recognize`),
 * then prints every LineChunk / LineArtChunk / ShapeChunk seen in the artifact
 * layer of a given page.
 */
public class DebugArtifacts {

    public static void main(String[] args) throws Exception {
        String pdf = args.length > 0 ? args[0]
                : "D:\\Code\\JavaCode\\opendataloader-pdf-parse\\opendataloader-pdf\\docs\\pdf\\202304271682510470028924-15.pdf";
        int pageNum = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        boolean showAll = args.length > 2 && args[2].equals("all");

        Config config = new Config();
        config.setGenerateMarkdown(false);
        DocumentProcessor.preprocessing(pdf, config);

        IDocument doc = StaticContainers.getDocument();
        int pages = doc.getNumberOfPages();
        System.out.println("pages=" + pages);
        List<Integer> pageIdx = new java.util.ArrayList<>();
        if (pageNum < 0) { for (int i = 0; i < pages; i++) pageIdx.add(i); } else { pageIdx.add(pageNum); }
        for (int idx : pageIdx) {
        List<IChunk> artifacts = doc.getArtifacts(idx);
        System.out.println("page " + (idx + 1) + " artifacts total=" + (artifacts == null ? "null" : artifacts.size()));
        if (artifacts == null) {
            continue;
        }
        java.util.Map<String,Integer> typeCnt = new java.util.TreeMap<>();
        int lineCount = 0, lineArtCount = 0, shapeCount = 0, other = 0;
        for (IChunk c : artifacts) {
            if (c instanceof LineChunk) {
                lineCount++;
                LineChunk l = (LineChunk) c;
                BoundingBox bb = l.getBoundingBox();
                double[] col = l.getStrokeColor();
                System.out.printf("LINE  bbox=(%.2f,%.2f)-(%.2f,%.2f) w=%.2f h=%.2f width=%.2f color=%s%n",
                        bb.getLeftX(), bb.getTopY(), bb.getRightX(), bb.getBottomY(),
                        bb.getWidth(), bb.getHeight(), l.getWidth(), Arrays.toString(col));
            } else if (c instanceof LineArtChunk) {
                lineArtCount++;
                LineArtChunk art = (LineArtChunk) c;
                BoundingBox bb = art.getBoundingBox();
                List<LineChunk> lines = art.getLineChunks();
                System.out.printf("LINEART bbox=(%.2f,%.2f)-(%.2f,%.2f) childLines=%s%n",
                        bb.getLeftX(), bb.getTopY(), bb.getRightX(), bb.getBottomY(),
                        lines == null ? "null" : lines.size());
            } else if (c instanceof ShapeChunk) {
                shapeCount++;
                typeCnt.merge(((ShapeChunk) c).getShapeType(), 1, Integer::sum);
                ShapeChunk s = (ShapeChunk) c;
                BoundingBox bb = s.getBoundingBox();
                System.out.printf("SHAPE  type=%s bbox=(%.2f,%.2f)-(%.2f,%.2f) components=%d color=%s%n",
                        s.getShapeType(), bb.getLeftX(), bb.getTopY(), bb.getRightX(), bb.getBottomY(),
                        s.getComponentCount(), Arrays.toString(s.getColor()));
            } else if (showAll) {
                other++;
                System.out.println("OTHER  " + c.getClass().getSimpleName());
            }
        }
        System.out.printf("summary: LineChunk=%d LineArtChunk=%d ShapeChunk=%d typeCount=%s%n", lineCount, lineArtCount, shapeCount, typeCnt);
        }
    }
}