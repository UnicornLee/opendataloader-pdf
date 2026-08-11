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
import java.util.Iterator;
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

    /**
     * Maximum allowed length (in Java chars) of any bookmark entry's text.
     * A real heading is short; an entry longer than this is treated as body
     * text that happened to land in the outline (e.g. a numbered paragraph
     * absorbed by the catalog extractor) and is dropped.
     *
     * <p>Mirrors {@code PageBookmarkProcessor#MAX_ENTRY_TEXT_LENGTH = 200}
     * so all three sources use the same definition of "overlong heading".</p>
     */
    public static final int MAX_TITLE_LENGTH = 200;

    /**
     * Filters overlong bookmark nodes out of an outline tree.
     *
     * <p>Rules (applied level by level):</p>
     * <ul>
     *   <li><strong>L1:</strong> if {@code root.text.length() > MAX_TITLE_LENGTH},
     *       drop this L1; other L1 entries are kept.</li>
     *   <li><strong>L2:</strong> if any L2 child of an L1 has
     *       {@code text.length() > MAX_TITLE_LENGTH}, drop ALL L2 children of
     *       that L1 (the L1 itself is kept but ends up with no children).</li>
     *   <li><strong>L3:</strong> if any L3 child of an L2 has
     *       {@code text.length() > MAX_TITLE_LENGTH}, drop ALL L3 children of
     *       that L2 (the L2 itself is kept but ends up with no children).</li>
     * </ul>
     *
     * <p>The filter mutates the input {@code roots} in place and returns it.
     * A {@code null} or empty input is returned as-is.</p>
     *
     * @param roots top-level bookmark nodes
     * @return the same list with overlong nodes pruned
     */
    public static List<Bookmark> trimOverlongNodes(List<Bookmark> roots) {
        if (roots == null || roots.isEmpty()) {
            return roots;
        }
        Iterator<Bookmark> iterator = roots.iterator();
        while (iterator.hasNext()) {
            Bookmark root = iterator.next();
            if (root == null) {
                iterator.remove();
                continue;
            }
            String rootText = root.getText();
            if (rootText != null && rootText.length() > MAX_TITLE_LENGTH) {
                iterator.remove();
                continue;
            }
            trimLevel2(root);
        }
        return roots;
    }

    /**
     * Applies the L2/L3 filter to a single bookmark node's subtree.
     * If any L2 child is overlong, all L2 children are removed; otherwise each
     * L2 has its L3 children filtered similarly.
     */
    private static void trimLevel2(Bookmark parent) {
        List<Bookmark> l2Children = parent.getChildren();
        if (l2Children == null || l2Children.isEmpty()) {
            return;
        }
        boolean anyL2Overlong = false;
        List<Bookmark> keptL2 = new ArrayList<>(l2Children.size());
        for (Bookmark l2 : l2Children) {
            if (l2 == null) {
                continue;
            }
            String l2Text = l2.getText();
            if (l2Text != null && l2Text.length() > MAX_TITLE_LENGTH) {
                anyL2Overlong = true;
                continue;
            }
            trimLevel3(l2);
            keptL2.add(l2);
        }
        if (anyL2Overlong) {
            parent.setChildren(new ArrayList<>());
        } else {
            parent.setChildren(keptL2);
        }
    }

    /**
     * Applies the L3 filter to a single L2 node. If any L3 child is overlong,
     * all L3 children of this L2 are removed; otherwise each L3 is kept.
     */
    private static void trimLevel3(Bookmark l2) {
        List<Bookmark> l3Children = l2.getChildren();
        if (l3Children == null || l3Children.isEmpty()) {
            return;
        }
        boolean anyL3Overlong = false;
        List<Bookmark> keptL3 = new ArrayList<>(l3Children.size());
        for (Bookmark l3 : l3Children) {
            if (l3 == null) {
                continue;
            }
            String l3Text = l3.getText();
            if (l3Text != null && l3Text.length() > MAX_TITLE_LENGTH) {
                anyL3Overlong = true;
                continue;
            }
            keptL3.add(l3);
        }
        if (anyL3Overlong) {
            l2.setChildren(new ArrayList<>());
        } else {
            l2.setChildren(keptL3);
        }
    }
}
