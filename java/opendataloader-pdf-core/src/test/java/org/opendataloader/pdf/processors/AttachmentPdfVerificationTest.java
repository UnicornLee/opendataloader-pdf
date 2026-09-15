package org.opendataloader.pdf.processors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.custom.entities.Bookmark;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-off verification that the attachment L1 detection works against the real
 * 202609081788820439424027803.pdf OCR'd JSON output stored in
 * <code>tmp_output/</code>.
 */
public class AttachmentPdfVerificationTest {

    @Test
    public void verifyAttachmentBookmarksForRealPdf() throws Exception {
        File jsonFile = Paths.get("..", "..", "tmp_output", "202609081788820439424027803.json").toFile();
        assertTrue(jsonFile.exists(), "Expected OCR JSON at " + jsonFile.getAbsolutePath());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> root = mapper.readValue(jsonFile,
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) root.get("data");
        assertNotNull(pages, "JSON should contain a 'data' field with page entries");

        // Use the catalog range stored in self_bookmarks to skip TOC if available,
        // but for simplicity we pass (-1, -1) here; the PDF has no catalog.
        List<Bookmark> bookmarks = PageBookmarkProcessor.extractPageBookmarksFromJson(pages, -1, -1);

        System.out.println("=== Real PDF verification: 202609081788820439424027803.pdf ===");
        System.out.println("Total top-level bookmarks: " + bookmarks.size());
        boolean sawFujiaYi = false;
        boolean sawFujiaEr = false;
        for (Bookmark b : bookmarks) {
            String t = b.getText() == null ? "" : b.getText();
            // Avoid console encoding issues: print only ASCII-safe prefix.
            String asciiPreview = t.length() > 4 ? t.substring(0, 4) : t;
            System.out.println("  page=" + b.getPageNum()
                    + " relatedId=" + b.getRelatedId()
                    + " asciiPrefix=" + asciiPreview
                    + " textLen=" + t.length()
                    + " isSingleLine=" + b.getSingleLine());
            // The two attachments we expect start with the same two codepoints
            // (E9 99 84 E4 BB B6 = "附件" = "附件") so check that.
            if (t.startsWith("附件")) {
                if (t.contains("一")) {
                    sawFujiaYi = true;
                } else if (t.contains("二")) {
                    sawFujiaEr = true;
                }
            }
        }
        assertTrue(sawFujiaYi, "Should detect 附件一： as an L1 attachment bookmark");
        assertTrue(sawFujiaEr, "Should detect 附件二： as an L1 attachment bookmark");
    }
}