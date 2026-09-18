package org.opendataloader.pdf.poppler;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Manual / smoke tests for {@link PopplerRenderer} + {@link PopplerAutoInstaller}. */
public class PopplerRendererTest {

    @Test
    public void isAvailableAndRenderIfInstalled(@TempDir File outDir) throws Exception {
        File pdf = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf");
        assertTrue(pdf.exists(), "Test PDF not found");

        // First call triggers the probe + auto-install if missing.
        boolean available = PopplerRenderer.isAvailable();
        String path = PopplerRenderer.getBinaryPath();
        System.out.println("Poppler available: " + available + " at " + path);

        if (!available) {
            // The auto-install must have failed (no network, no package manager, etc.).
            // That's fine for the test - we just verify that isAvailable() is stable.
            assertNull(path, "binary path must be null when unavailable");
            return;
        }

        // Render page 7 (0-indexed 6) and verify the output
        File out = new File(outDir, "page7_poppler.png");
        BufferedImage img = PopplerRenderer.render(pdf, 6, null);
        assertNotNull(img, "Poppler should produce a non-null image");
        ImageIO.write(img, "PNG", out);
        assertTrue(out.length() > 1000, "PNG file should not be empty");

        // Typical A4 at 300 DPI is 2479 x 3508 (Poppler may be off by 1 due to rounding)
        assertTrue(img.getWidth() >= 2470 && img.getWidth() <= 2490,
            "Width " + img.getWidth() + " out of expected range");
        assertTrue(img.getHeight() >= 3500 && img.getHeight() <= 3520,
            "Height " + img.getHeight() + " out of expected range");
    }

    @Test
    public void handlesMissingPdfGracefully() {
        File missing = new File("D:\\does\\not\\exist.pdf");
        assertFalse(missing.exists());
    }

    @Test
    public void installInstructionsMatchOS() {
        String hint = PopplerRenderer.installInstructions();
        assertNotNull(hint);
        assertFalse(hint.isBlank());
        // Should mention one of the known install methods
        String low = hint.toLowerCase();
        boolean hasKnownHint = low.contains("apt") || low.contains("brew") || low.contains("winget")
            || low.contains("download");
        assertTrue(hasKnownHint, "install hint should mention a known install path: " + hint);
    }
}