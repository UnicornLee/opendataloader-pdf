package org.opendataloader.pdf.custom.utils;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;

import java.io.File;

public class CustomChunksMergeUtils {
    public static boolean judgeIfOneLine(ImageChunk imageChunk, TextLine textLine) {
        return (imageChunk.getCenterY() >= textLine.getBottomY() && imageChunk.getCenterY() <= textLine.getTopY()) ||
            (textLine.getCenterY() >= imageChunk.getBottomY() && textLine.getCenterY() <= imageChunk.getTopY());
    }

    public static TextChunk convertImageChunkToTextChunk(ImageChunk imageChunk, Double fontSize, Double baseLine, String imagesDirectory) {
        String imageFormat = StaticLayoutContainers.getImageFormat();
        String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, imagesDirectory, File.separator, imageChunk.getIndex(), imageFormat);
        String text = "<img src='" + absolutePath + "' style='height: " + imageChunk.getHeight() + "pt !important; width: " + imageChunk.getWidth() + "pt !important;' />";
        return new TextChunk(imageChunk.getBoundingBox(), text, fontSize == null ? imageChunk.getHeight() : fontSize, baseLine == null ? imageChunk.getCenterY() : baseLine);
    }
}
