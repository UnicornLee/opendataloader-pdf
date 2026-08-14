package org.opendataloader.pdfbox;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Temporary debug tool: dump every text position char-by-char via
 * PDFTextStripper, to detect duplicated single-char draws for a given
 * x-range (e.g. chars at x0 around 60.24 that also appear inside table cells).
 *
 * Usage: TextDump <pdf> <pageIndex>
 */
public class TextDump {
    public static void main(String[] args) throws Exception {
        File pdf = new File(args[0]);
        int pageIndex = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Dumper stripper = new Dumper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.setSortByPosition(true);
            stripper.getText(doc);   // drives writeString for the page range
        }
    }

    static final class Dumper extends PDFTextStripper {
        Dumper() throws IOException { super(); }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            // Ignore artifacts (opening parenthesis of list) in PDFBox 3
            if (text == null) return;
            for (TextPosition tp : textPositions) {
                System.out.printf("chr [%s] at (%.2f, %.2f) x1=%.2f y1=%.2f size=%.1f%n",
                        String.valueOf(tp.getUnicode()),
                        tp.getXDirAdj(), tp.getYDirAdj(),
                        tp.getXDirAdj() + tp.getWidthDirAdj(), tp.getYDirAdj() + tp.getHeightDir(),
                        tp.getFontSizeInPt());
            }
        }
    }
}