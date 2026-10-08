package org.opendataloader.pdf.custom.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;

/**
 * Covers the page-background guard of {@link CustomChunksMergeUtils#judgeIfOneLine(ImageChunk,
 * TextLine)}: an image covering (almost) the whole page must never be glued into a text line,
 * because the resulting page-tall text line overlaps every other line and the paragraph / heading
 * detectors then merge the whole page into a single block.
 *
 * <p>The end-to-end effect on {@code docs/pdf/202609251790240460775053085.pdf} (page 1 came out as
 * one heading mixing the cover image tag, the title and the address block) is verified by running
 * the pipeline; these tests pin the geometry rule itself.</p>
 */
class CustomChunksMergeUtilsTest {

    private static final double PAGE_WIDTH = 595.3;
    private static final double PAGE_HEIGHT = 841.9;

    @Test
    void imageCoveringWholePageIsABackground() {
        // Cover background of docs/pdf/202609251790240460775053085.pdf page 1.
        BoundingBox imageBox = new BoundingBox(0, -0.36, 1.30, 603.36, 843.70);
        Assertions.assertTrue(CustomChunksMergeUtils.isPageBackgroundImage(imageBox, pageBox()));
    }

    @Test
    void largeFigureIsStillAnInlineCandidate() {
        // 67% of the page width, 59% of its height: a figure, not a page background.
        BoundingBox imageBox = new BoundingBox(0, 90.0, 200.0, 490.0, 700.0);
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundImage(imageBox, pageBox()));
    }

    @Test
    void missingPageGeometryKeepsPreviousBehaviour() {
        BoundingBox imageBox = new BoundingBox(0, -0.36, 1.30, 603.36, 843.70);
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundImage(imageBox, null));
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundImage(null, pageBox()));
    }

    @Test
    void backgroundSizeTestUsesBothPageDimensions() {
        Assertions.assertTrue(CustomChunksMergeUtils.isPageBackgroundSize(603.36, 842.4,
            PAGE_WIDTH, PAGE_HEIGHT));
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundSize(603.36, 300.0,
            PAGE_WIDTH, PAGE_HEIGHT));
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundSize(300.0, 842.4,
            PAGE_WIDTH, PAGE_HEIGHT));
        Assertions.assertFalse(CustomChunksMergeUtils.isPageBackgroundSize(603.36, 842.4, 0.0, 0.0));
    }

    private static BoundingBox pageBox() {
        return new BoundingBox(0, 0.0, 0.0, PAGE_WIDTH, PAGE_HEIGHT);
    }
}
