package org.opendataloader.pdf.custom.entities;

import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.content.TextLine;

import java.util.ArrayList;
import java.util.List;

public class CustomSemanticParagraph extends SemanticParagraph {
    private final List<TextLine> textLines = new ArrayList<>();

    public List<TextLine> getTextLines() {
        return this.textLines;
    }

    public void addTextLine(TextLine textLine) {
        this.textLines.add(textLine);
    }

    public void addTextLines(List<TextLine> textLines) {
        this.textLines.addAll(textLines);
    }
}
