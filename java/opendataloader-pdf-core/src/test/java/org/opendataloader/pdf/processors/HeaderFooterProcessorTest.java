/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.processors;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.tools.StaticResources;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.enums.SemanticType;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.util.ArrayList;
import java.util.List;

public class HeaderFooterProcessorTest {

    private void initContainers() {
        StaticContainers.setIsDataLoader(true);
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticResources.setDocument(null);
        StaticLayoutContainers.clearContainers();
    }

    @Test
    public void testProcessHeadersAndFooters() {
        initContainers();
        List<List<IObject>> contents = new ArrayList<>();
        List<IObject> page1Contents = new ArrayList<>();
        page1Contents.add(new TextLine(new TextChunk(new BoundingBox(0, 10.0, 30.0, 20.0, 40.0),
            "Header", 10, 30.0)));
        page1Contents.add(new TextLine(new TextChunk(new BoundingBox(0, 10.0, 20.0, 20.0, 30.0),
            "Text", 10, 20.0)));
        page1Contents.add(new TextLine(new TextChunk(new BoundingBox(0, 10.0, 10.0, 20.0, 20.0),
            "Footer1", 10, 10.0)));
        List<IObject> page2Contents = new ArrayList<>();
        page2Contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 30.0, 20.0, 40.0),
            "Header", 10, 30.0)));
        page2Contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 20.0, 20.0, 30.0),
            "Different Text", 10, 20.0)));
        page2Contents.add(new TextLine(new TextChunk(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0),
            "Footer2", 10, 10.0)));
        contents.add(page1Contents);
        contents.add(page2Contents);
        HeaderFooterProcessor.processHeadersAndFooters(contents, false);

        Assertions.assertEquals(3, contents.get(0).size());
        Assertions.assertEquals(3, contents.get(1).size());

        Assertions.assertTrue(contents.get(0).get(0) instanceof SemanticHeaderOrFooter);
        Assertions.assertEquals(SemanticType.HEADER, ((SemanticHeaderOrFooter) contents.get(0).get(0)).getSemanticType());
        Assertions.assertTrue(contents.get(1).get(0) instanceof SemanticHeaderOrFooter);
        Assertions.assertEquals(SemanticType.HEADER, ((SemanticHeaderOrFooter) contents.get(1).get(0)).getSemanticType());
        Assertions.assertTrue(contents.get(0).get(2) instanceof SemanticHeaderOrFooter);
        Assertions.assertEquals(SemanticType.FOOTER, ((SemanticHeaderOrFooter) contents.get(0).get(2)).getSemanticType());
        Assertions.assertTrue(contents.get(1).get(2) instanceof SemanticHeaderOrFooter);
        Assertions.assertEquals(SemanticType.FOOTER, ((SemanticHeaderOrFooter) contents.get(1).get(2)).getSemanticType());
    }

    /**
     * Tests that body text repeated on adjacent pages is not absorbed into the footer.
     * Reproduces #385: pages 19-20 of CERAGEM PDF have identical note text
     * "※ 출수 중 출수 버튼을 터치하면 출수가 정지됩니다." at y=116 above the actual
     * footer at y=34. The note was incorrectly classified as footer because it matched
     * across pages. Page height is 595 (A4-like).
     */
    @Test
    public void testRepeatedBodyTextNotAbsorbedIntoFooter() {
        initContainers();
        // Simulate 4 pages (17-20) with A4-like height (595pt)
        // Page bounding box: [0, 0, 420, 595]
        // Footer line at y=35 (bottom), body note at y=117 (well above footer)
        double pageHeight = 595.0;
        double footerY = 35.0;
        double bodyNoteY = 117.0;

        List<List<IObject>> contents = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            List<IObject> pageContents = new ArrayList<>();
            // Body heading at top
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, pageHeight - 60, 300.0, pageHeight - 30),
                "Section " + (page + 1), 12, pageHeight - 30)));
            // Body paragraph in middle
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, pageHeight / 2, 300.0, pageHeight / 2 + 30),
                "Body content page " + (page + 1), 10, pageHeight / 2 + 30)));

            // Repeated body note — same text on pages 2 and 3 (simulating pages 19-20)
            if (page == 2 || page == 3) {
                pageContents.add(new TextLine(new TextChunk(
                    new BoundingBox(page, 223.0, bodyNoteY, 360.0, bodyNoteY + 18),
                    "※ Repeated note text", 6.5, bodyNoteY + 18)));
            }

            // Actual footer line (repeating pattern across all pages)
            String footerText = (page % 2 == 0)
                ? "CGM BALANCE " + (page + 17)
                : (page + 17) + " CERAGEM BALANCE USER MANUAL";
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, footerY, 280.0, footerY + 9),
                footerText, 7.5, footerY + 9)));

            contents.add(pageContents);
        }

        HeaderFooterProcessor.processHeadersAndFooters(contents, false);

        // Verify: each page should have footer detected
        for (int page = 0; page < 4; page++) {
            List<IObject> pageContent = contents.get(page);
            IObject lastElement = pageContent.get(pageContent.size() - 1);
            Assertions.assertTrue(lastElement instanceof SemanticHeaderOrFooter,
                "Page " + page + ": last element should be footer");
            SemanticHeaderOrFooter footer = (SemanticHeaderOrFooter) lastElement;
            Assertions.assertEquals(SemanticType.FOOTER, footer.getSemanticType());

            // Critical: footer should contain only 1 element (the actual footer line),
            // NOT the repeated body note
            Assertions.assertEquals(1, footer.getContents().size(),
                "Page " + page + ": footer should contain only the footer line, " +
                "not absorb the repeated body note. Got " + footer.getContents().size() + " elements.");
        }

        // Verify: the repeated note text on pages 2-3 should still be in body content
        for (int page = 2; page <= 3; page++) {
            List<IObject> pageContent = contents.get(page);
            boolean foundNote = false;
            for (IObject obj : pageContent) {
                if (!(obj instanceof SemanticHeaderOrFooter) && obj instanceof TextLine) {
                    TextLine line = (TextLine) obj;
                    if (line.getValue().contains("Repeated note")) {
                        foundNote = true;
                        break;
                    }
                }
            }
            Assertions.assertTrue(foundNote,
                "Page " + page + ": repeated note text should remain in body, not be absorbed into footer");
        }
    }

    /**
     * Positive control: two closely spaced footer lines (gap < 30pt) should be
     * grouped into a single footer. Ensures the proximity check does not reject
     * legitimate multi-line footers.
     */
    @Test
    public void testCloseFooterLinesAreGrouped() {
        initContainers();
        List<List<IObject>> contents = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            List<IObject> pageContents = new ArrayList<>();
            // Body text at top
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, 500.0, 300.0, 530.0),
                "Body text page " + (page + 1), 10, 530.0)));

            // Two footer lines close together (11pt gap between nearest edges)
            // Line 1: y=[55, 67]  Line 2: y=[35, 44]  gap = 55-44 = 11pt
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, 55.0, 280.0, 67.0),
                "Copyright 2026", 7.5, 67.0)));
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 37.0, 35.0, 280.0, 44.0),
                "Company Footer", 7.5, 44.0)));

            contents.add(pageContents);
        }

        HeaderFooterProcessor.processHeadersAndFooters(contents, false);

        for (int page = 0; page < 3; page++) {
            List<IObject> pageContent = contents.get(page);
            IObject lastElement = pageContent.get(pageContent.size() - 1);
            Assertions.assertTrue(lastElement instanceof SemanticHeaderOrFooter,
                "Page " + page + ": last element should be footer");
            SemanticHeaderOrFooter footer = (SemanticHeaderOrFooter) lastElement;
            Assertions.assertEquals(SemanticType.FOOTER, footer.getSemanticType());
            Assertions.assertEquals(2, footer.getContents().size(),
                "Page " + page + ": footer should contain both close footer lines (gap=11pt < 30pt)");
        }
    }

    /**
     * Reproduces the issue where a 2-page style match on two isolated pages was treated
     * as a repeating header. In 招股意向书 the text "单位：万元/吨" appears at the same
     * y=73.49 position on PDF pages 196 and 198, but page 197 has a different layout
     * (table at top). Before the fix the 2-page style branch in
     * {@link HeaderFooterProcessor#getIndexesOfHeaderOrFootersContents} would mark the
     * text as a header on both 196 and 198 and silently drop it from the output JSON.
     *
     * <p>The tightened 2-page style rule requires at least 3 distinct pages to
     * participate in 2-page matches AND >= 50% of pages to participate, so two
     * isolated matches are no longer enough to classify something as a header.</p>
     */
    @Test
    public void testTwoPageStyleRequiresMajority() {
        initContainers();
        List<List<IObject>> contents = new ArrayList<>();

        // Mimic the 招股意向书 layout: 10 pages, two of which (PDF pages 196 and 198)
        // share an identical body text "单位：万元/吨" at the same y=73.49pt from the top.
        // The other pages have different body content at the same y position.
        //
        // BoundingBox uses PDF bottom-up coordinates, so a top-y of 73.49pt corresponds
        // to bottomY=841.92-84.84=757.08 and topY=841.92-73.49=768.44.
        double headerBottomY = 841.92 - 54.77;   // 787.15 — title bottom
        double headerTopY = 841.92 - 44.21;      // 797.71 — title top
        double unitBottomY = 841.92 - 84.84;     // 757.08 — "单位" bottom
        double unitTopY = 841.92 - 73.49;        // 768.44 — "单位" top
        // Other pages put their body text well below the header candidate region
        // (PDF bottomY < height*2/3 = 561.28 → filterHeaderOrFooterContents drops it).
        double altBottomY = 841.92 - 500.0;      // 341.92 — below header region
        double altTopY = 841.92 - 480.0;         // 361.92
        double bodyBottomY = 841.92 - 220.0;     // 621.92
        double bodyTopY = 841.92 - 200.0;        // 641.92

        int totalPages = 10;
        int[] pagesWithRepeated = {3, 5}; // 0-based; matches pageNumber+2 in the 2-page loop

        for (int page = 0; page < totalPages; page++) {
            List<IObject> pageContents = new ArrayList<>();

            // Real repeating header line (appears on every page at the same position)
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 89.9, headerBottomY, 505.66, headerTopY),
                "威海市泓淋电力技术股份有限公司 招股意向书", 10.56, headerTopY)));

            if (page == pagesWithRepeated[0] || page == pagesWithRepeated[1]) {
                // The candidate that should NOT be classified as a header.
                pageContents.add(new TextLine(new TextChunk(
                    new BoundingBox(page, 439.66, unitBottomY, 505.68, unitTopY),
                    "单位：万元/吨", 10.56, unitTopY)));
            } else {
                // Body text well below the header candidate region so it never enters
                // the 2-page style matching (filterHeaderOrFooterContents drops it).
                pageContents.add(new TextLine(new TextChunk(
                    new BoundingBox(page, 89.9, altBottomY, 505.0, altTopY),
                    "Body text page " + (page + 1), 10.56, altTopY)));
            }

            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 89.9, bodyBottomY, 505.0, bodyTopY),
                "Body paragraph page " + (page + 1), 12.0, bodyTopY)));

            contents.add(pageContents);
        }

        HeaderFooterProcessor.processHeadersAndFooters(contents, false);

        // The genuine header (line 0) should be classified as a header on every page.
        for (int page = 0; page < totalPages; page++) {
            List<IObject> pageContent = contents.get(page);
            IObject first = pageContent.get(0);
            Assertions.assertTrue(first instanceof SemanticHeaderOrFooter,
                "Page " + page + ": first element should be header (the real repeating title)");
        }

        // The two pages that share "单位：万元/吨" should NOT have it absorbed into a header.
        for (int page : pagesWithRepeated) {
            List<IObject> pageContent = contents.get(page);
            boolean foundUnit = false;
            for (IObject obj : pageContent) {
                if (obj instanceof SemanticHeaderOrFooter) {
                    SemanticHeaderOrFooter hf = (SemanticHeaderOrFooter) obj;
                    for (IObject c : hf.getContents()) {
                        if (c instanceof TextLine && ((TextLine) c).getValue().contains("单位")) {
                            Assertions.fail("Page " + page + ": \"单位：万元/吨\" should remain in body, "
                                + "but it was absorbed into a header/footer");
                        }
                    }
                } else if (obj instanceof TextLine && ((TextLine) obj).getValue().contains("单位")) {
                    foundUnit = true;
                }
            }
            Assertions.assertTrue(foundUnit,
                "Page " + page + ": \"单位：万元/吨\" should remain in body content");
        }
    }

    /**
     * Positive control: a genuine two-sided header pattern (odd pages share one
     * header, even pages share another) must still be detected. We model 6 pages
     * where every odd page (1, 3, 5) has "奇数页眉" at the same y, and every even
     * page (2, 4, 6) has "偶数页眉" at the same y. After processing, both should be
     * classified as headers — even though adjacent pages don't match (1-page style
     * misses), only the tightened 2-page style catches them.
     */
    @Test
    public void testTwoPageStyleGenuineOddEvenStillDetected() {
        initContainers();
        List<List<IObject>> contents = new ArrayList<>();
        double oddBottomY = 841.92 - 65.0;
        double oddTopY = 841.92 - 55.0;
        double evenBottomY = 841.92 - 65.0;
        double evenTopY = 841.92 - 55.0;

        for (int page = 0; page < 6; page++) {
            List<IObject> pageContents = new ArrayList<>();
            String headerText = (page % 2 == 0) ? "偶数页眉" : "奇数页眉";
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 50.0, oddBottomY, 300.0, oddTopY),
                headerText, 12.0, oddTopY)));
            pageContents.add(new TextLine(new TextChunk(
                new BoundingBox(page, 50.0, 200.0, 400.0, 220.0),
                "Body page " + (page + 1), 12.0, 220.0)));
            contents.add(pageContents);
        }

        HeaderFooterProcessor.processHeadersAndFooters(contents, false);

        for (int page = 0; page < 6; page++) {
            List<IObject> pageContent = contents.get(page);
            IObject first = pageContent.get(0);
            Assertions.assertTrue(first instanceof SemanticHeaderOrFooter,
                "Page " + page + ": first element should be header (two-sided pattern must still be detected)");
        }
    }
}
