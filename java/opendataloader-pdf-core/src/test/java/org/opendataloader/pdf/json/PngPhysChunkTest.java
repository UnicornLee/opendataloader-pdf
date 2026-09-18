package org.opendataloader.pdf.json;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;

public class PngPhysChunkTest {

    @Test
    public void streamTableScreenshotHasPhysDpiChunk(@TempDir File tempDir) throws Exception {
        File pdf = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf");
        Assertions.assertTrue(pdf.exists(), "Test PDF not found: " + pdf);

        // Render page 7 (index 6) at 300 DPI using PDFBox, exactly like JsonWriter.renderPage does
        BufferedImage rendered;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            rendered = renderer.renderImageWithDPI(6, 300.0f);
        }

        // Call the private writePngWithDpi helper via reflection.
        Method writeMethod = JsonWriter.class.getDeclaredMethod(
            "writePngWithDpi", BufferedImage.class, File.class, int.class);
        writeMethod.setAccessible(true);
        File out = new File(tempDir, "test_with_dpi.png");
        writeMethod.invoke(null, rendered, out, 300);

        // Verify the PNG is still valid
        BufferedImage roundTrip = ImageIO.read(out);
        Assertions.assertNotNull(roundTrip, "Resulting PNG should be readable");
        Assertions.assertEquals(rendered.getWidth(), roundTrip.getWidth());
        Assertions.assertEquals(rendered.getHeight(), roundTrip.getHeight());

        // Walk PNG chunks and validate the pHYs payload (length=9, type="pHYs").
        try (FileInputStream fis = new FileInputStream(out)) {
            byte[] sig = new byte[8];
            Assertions.assertEquals(8, fis.read(sig), "Read PNG signature");
            byte[] expectedSig = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            Assertions.assertArrayEquals(expectedSig, sig, "PNG signature");

            // Chunk 1: IHDR
            Chunk ihdr = readChunk(fis);
            Assertions.assertEquals("IHDR", ihdr.type, "First chunk must be IHDR");

            // Chunk 2: pHYs (this is the one we injected)
            Chunk phys = readChunk(fis);
            Assertions.assertEquals("pHYs", phys.type, "Second chunk must be pHYs");
            Assertions.assertEquals(9, phys.data.length, "pHYs data length must be 9");
            int pixelsPerMeterX = readBE(phys.data, 0);
            int pixelsPerMeterY = readBE(phys.data, 4);
            int unit = phys.data[8] & 0xFF;
            int expected = (int) Math.round(300 * 1000.0 / 25.4); // 300 DPI = 11811 px/m
            Assertions.assertEquals(expected, pixelsPerMeterX, "pHYs pixelsPerMeterX for 300 DPI");
            Assertions.assertEquals(expected, pixelsPerMeterY, "pHYs pixelsPerMeterY for 300 DPI");
            Assertions.assertEquals(1, unit, "pHYs unit specifier must be 1 (meter)");

            // Verify CRC matches (the chunks would not be considered valid PNG otherwise)
            Assertions.assertEquals(phys.expectedCrc, phys.actualCrc, "pHYs CRC must match");
        }

        Assertions.assertTrue(out.length() > 1000, "PNG should not be empty");
        System.out.println("OK: produced " + out + " with pHYs (300 DPI = "
            + (int) Math.round(300 * 1000.0 / 25.4) + " pixels/meter)");
    }

    private static int readBE(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
                | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8)
                | (b[offset + 3] & 0xFF);
    }

    private static class Chunk {
        final String type;
        final byte[] data;
        final long expectedCrc;
        final long actualCrc;
        Chunk(String t, byte[] d, long exp, long act) {
            this.type = t; this.data = d; this.expectedCrc = exp; this.actualCrc = act;
        }
    }

    private static Chunk readChunk(FileInputStream fis) throws IOException {
        byte[] lenBytes = new byte[4];
        if (fis.read(lenBytes) != 4) throw new IOException("EOF reading chunk length");
        int len = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16)
                | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);
        byte[] typeBytes = new byte[4];
        if (fis.read(typeBytes) != 4) throw new IOException("EOF reading chunk type");
        String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
        byte[] data = new byte[len];
        int totalRead = 0;
        while (totalRead < len) {
            int n = fis.read(data, totalRead, len - totalRead);
            if (n < 0) throw new IOException("EOF reading chunk data");
            totalRead += n;
        }
        byte[] crcBytes = new byte[4];
        if (fis.read(crcBytes) != 4) throw new IOException("EOF reading chunk CRC");
        long expectedCrc = ((crcBytes[0] & 0xFFL) << 24)
                | ((crcBytes[1] & 0xFFL) << 16)
                | ((crcBytes[2] & 0xFFL) << 8)
                | (crcBytes[3] & 0xFFL);
        long actualCrc = crc32(typeBytes, data);
        return new Chunk(type, data, expectedCrc, actualCrc);
    }

    private static long crc32(byte[] type, byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(data);
        return crc.getValue();
    }
}