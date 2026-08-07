package org.opendataloader.pdf.custom.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.custom.entities.Bookmark;
import org.opendataloader.pdf.processors.CatalogBookmarkProcessor;
import org.opendataloader.pdf.processors.PageBookmarkProcessor;
import org.verapdf.wcag.algorithms.entities.IObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookmarkUtils {

    public static List<Bookmark> getSelfBookmarks(String inputPdfName) {
        List<Bookmark> selfBookmarks = new ArrayList<>();

        File inputPDF = new File(inputPdfName);
        try (PDDocument doc = Loader.loadPDF(inputPDF)) {
            PDOutlineNode outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                selfBookmarks.addAll(getSelfBookmarks(outline, doc));
            }
        } catch (Exception e) {
        }
        return selfBookmarks;
    }

    public static List<Bookmark> getSelfBookmarks(PDOutlineNode node) {
        return getSelfBookmarks(node, null);
    }

    private static List<Bookmark> getSelfBookmarks(PDOutlineNode node, PDDocument doc) {
        List<Bookmark> selfBookmarks = new ArrayList<>();

        PDOutlineItem item = node.getFirstChild();
        while (item != null) {
            Bookmark bookmark = new Bookmark();
            bookmark.setText(item.getTitle());
            if (doc != null) {
                try {
                    PDPage page = item.findDestinationPage(doc);
                    if (page != null) {
                        bookmark.setPageNum(doc.getPages().indexOf(page) + 1);
                    }
                } catch (Exception e) {
                }
            }
            if (item.hasChildren()) {
                bookmark.setChildren(getSelfBookmarks(item, doc));
            }
            selfBookmarks.add(bookmark);
            item = item.getNextSibling();
        }
        return selfBookmarks;
    }

    /**
     * Extracts catalog bookmarks from detected table-of-contents pages.
     *
     * <p>The detection is fully automatic: pages are scored by their density of
     * "title ... page-number" lines, consecutive TOC pages are grouped into
     * ranges, and the best range is selected. The selected range is logged before
     * bookmarks are extracted.</p>
     *
     * @param contents per-page document contents produced by the pipeline
     * @return list of top-level catalog bookmarks, possibly empty
     */
    public static List<Bookmark> getCatalogBookmarks(List<List<IObject>> contents, Config config) {
        return CatalogBookmarkProcessor.extractCatalogBookmarks(contents, config);
    }

    /**
     * Extracts page bookmarks from {@link org.opendataloader.pdf.custom.entities.CustomSemanticParagraph}
     * paragraphs whose first line starts with one of the public constants in
     * {@link org.opendataloader.pdf.custom.constants.BookmarkConstant}.
     *
     * @param contents per-page document contents
     * @return list of top-level page bookmarks, possibly empty
     */
    public static List<Bookmark> getPageBookmarks(List<List<IObject>> contents) {
        return PageBookmarkProcessor.extractPageBookmarks(contents);
    }
}
