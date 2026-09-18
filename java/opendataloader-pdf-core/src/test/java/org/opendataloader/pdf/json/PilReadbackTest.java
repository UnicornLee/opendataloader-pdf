package org.opendataloader.pdf.json;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity test: produce a screenshot via the private writePngWithDpi helper and then
 * re-read it with javax.imageio (which honours pHYs for PNG). Confirms the pHYs chunk
 * is structurally valid PNG: a real PNG decoder accepts the file without complaint.
 */
public class PilReadbackTest {

    @Test
    public void screenshotIsValidPngWithPhysChunk(@TempDir File tmp) throws Exception {
        File pdf = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf");
        assertTrue(pdf.exists());

        java.awt.image.BufferedImage rendered;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            rendered = new PDFRenderer(doc).renderImageWithDPI(6, 300.0f);
        }

        Method m = JsonWriter.class.getDeclaredMethod(
            "writePngWithDpi", java.awt.image.BufferedImage.class, File.class, int.class);
        m.setAccessible(true);
        File out = new File(tmp, "rt.png");
        m.invoke(null, rendered, out, 300);

        // javax.imageio must accept the PNG (any failure here means the pHYs insertion
        // broke the PNG structure)
        java.awt.image.BufferedImage roundTrip = ImageIO.read(out);
        assertNotNull(roundTrip, "ImageIO refused the PNG; pHYs injection likely corrupted it");
        assertEquals(rendered.getWidth(), roundTrip.getWidth());
        assertEquals(rendered.getHeight(), roundTrip.getHeight());

        // Verify the embedded pHYs payload directly
        try (FileInputStream fis = new FileInputStream(out)) {
            byte[] sig = new byte[8];
            assertEquals(8, fis.read(sig));
            // Skip IHDR (4+4+13+4 = 25)
            byte[] ihdrBytes = new byte[25];
            assertEquals(25, fis.read(ihdrBytes));
            // pHYs (4 length + 4 type + 9 data + 4 crc = 21)
            byte[] physHeader = new byte[8];
            assertEquals(8, fis.read(physHeader));
            // length = 9
            assertEquals(9, ((physHeader[0] & 0xFF) << 24 | (physHeader[1] & 0xFF) << 16
                    | (physHeader[2] & 0xFF) << 8 | (physHeader[3] & 0xFF)));
            // type = "pHYs"
            assertEquals("pHYs", new String(physHeader, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            byte[] physData = new byte[9];
            assertEquals(9, fis.read(physData));
            int pxm = ((physData[0] & 0xFF) << 24 | (physData[1] & 0xFF) << 16
                    | (physData[2] & 0xFF) << 8 | (physData[3] & 0xFF));
            assertEquals((int) Math.round(300 * 1000.0 / 25.4), pxm);
            assertEquals(1, physData[8]);
        }
    }
}