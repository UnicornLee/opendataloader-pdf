package org.opendataloader.pdf.custom.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.opendataloader.pdf.custom.entities.Bookmark;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookmarkUtils {

    public static List<Bookmark> getSelfBookmarks(String inputPdfName) {
        List<Bookmark> selfBookmarks = new ArrayList<>();

        File inputPDF = new File(inputPdfName);
        try (PDDocument doc = Loader.loadPDF(inputPDF)) {
            PDOutlineNode outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                return selfBookmarks;
            }
            PDOutlineItem item = outline.getFirstChild();
            while (item != null) {
                Bookmark bookmark = new Bookmark();
                bookmark.setText(item.getTitle());
                bookmark.setPageNum(item.getOpenCount());
                if (item.hasChildren()) {
                }
                selfBookmarks.add(bookmark);
                item = item.getNextSibling();
            }

        } catch (Exception e) {

        }
        return selfBookmarks;
    }

    public static List<Bookmark> getSelfBookmarks(PDOutlineNode node) {
        List<Bookmark> selfBookmarks = new ArrayList<>();

        PDOutlineItem item = node.getFirstChild();
        while (item != null) {
            Bookmark bookmark = new Bookmark();
            bookmark.setText(item.getTitle());
            bookmark.setPageNum(item.getOpenCount());
            if (item.hasChildren()) {
                bookmark.setChildren(getSelfBookmarks(item));
            }
            selfBookmarks.add(bookmark);
            item = item.getNextSibling();
        }
        return selfBookmarks;
    }
}
