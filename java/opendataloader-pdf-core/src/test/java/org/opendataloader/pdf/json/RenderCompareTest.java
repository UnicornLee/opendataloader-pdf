package org.opendataloader.pdf.json;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Side-by-side render of page 7 using PDFBox / PyMuPDF / Poppler (pdftocairo).
 * Outputs are written to tmp_output/ for visual comparison.
 */
@EnabledIf("toolsAvailable")
public class RenderCompareTest {

    static boolean toolsAvailable() {
        return new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf").exists();
    }

    @Test
    public void renderAllThree() throws Exception {
        File pdf = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\docs\\pdf\\200910061781738030852011943.pdf");
        File outDir = new File("D:\\Code\\JavaCode\\opendataloader-pdf\\tmp_output");
        outDir.mkdirs();
        int pageIdx = 6; // page 7 (0-indexed)
        int dpi = 300;

        // 1) PDFBox baseline (current production rendering)
        File pdfboxPng = new File(outDir, "render_compare_pdfbox.png");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer r = new PDFRenderer(doc);
            BufferedImage img = r.renderImageWithDPI(pageIdx, dpi);
            ImageIO.write(img, "PNG", pdfboxPng);
        }
        System.out.println("PDFBox: " + pdfboxPng + " size=" + pdfboxPng.length());

        // 2) PyMuPDF (Python) - invoke via subprocess
        File pymupdfPng = new File(outDir, "render_compare_pymupdf.png");
        runPythonRender(pdf, pymupdfPng, pageIdx, dpi);
        System.out.println("PyMuPDF: " + pymupdfPng + " size=" + pymupdfPng.length());

        // 3) Poppler pdftocairo
        File popplerPng = new File(outDir, "render_compare_poppler.png");
        runPdftocairo(pdf, popplerPng, pageIdx, dpi);
        System.out.println("Poppler: " + popplerPng + " size=" + popplerPng.length());

        // Numerical comparison: which is sharpest?
        double[] lapVariance = new double[3];
        String[] names = {"PDFBox", "PyMuPDF", "Poppler"};
        File[] files = {pdfboxPng, pymupdfPng, popplerPng};
        for (int i = 0; i < 3; i++) {
            BufferedImage img = ImageIO.read(files[i]);
            lapVariance[i] = laplacianVariance(img);
        }
        System.out.println();
        System.out.println("Sharpness (Laplacian variance on full image, higher = sharper):");
        for (int i = 0; i < 3; i++) {
            System.out.printf("  %-10s %.0f%n", names[i], lapVariance[i]);
        }
    }

    static void runPythonRender(File pdf, File outPng, int pageIdx, int dpi) throws Exception {
        // Write a tiny script, run with python
        File script = File.createTempFile("render_pymupdf_", ".py");
        String code = String.format(
            "import fitz, sys\n" +
            "doc = fitz.open(r'%s')\n" +
            "page = doc[%d]\n" +
            "mat = fitz.Matrix(%f, %f)\n" +
            "pix = page.get_pixmap(matrix=mat, alpha=False)\n" +
            "pix.save(r'%s')\n",
            pdf.getAbsolutePath(), pageIdx, dpi / 72.0, dpi / 72.0, outPng.getAbsolutePath());
        Files.writeString(script.toPath(), code);
        Process proc = new ProcessBuilder("python", script.getAbsolutePath())
            .redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done = proc.waitFor(120, TimeUnit.SECONDS);
        script.delete();
        if (!done || proc.exitValue() != 0) {
            throw new IOException("PyMuPDF render failed: " + output);
        }
        if (!outPng.exists()) {
            throw new IOException("PyMuPDF did not produce output. Output: " + output);
        }
    }

    static void runPdftocairo(File pdf, File outPng, int pageIdx, int dpi) throws Exception {
        File tempPrefix = File.createTempFile("poppler_render_", "");
        String prefix = tempPrefix.getAbsolutePath();
        tempPrefix.delete(); // we want a prefix path, not a file
        File tmpPdf = pdf;
        // pdftocairo wants absolute path for output
        ProcessBuilder pb = new ProcessBuilder(
            "pdftocairo",
            "-r", String.valueOf(dpi),
            "-f", String.valueOf(pageIdx + 1),
            "-l", String.valueOf(pageIdx + 1),
            "-png",
            "-singlefile",
            tmpPdf.getAbsolutePath(),
            prefix
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done = proc.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("pdftocairo timed out. Output: " + output);
        }
        if (proc.exitValue() != 0) {
            throw new IOException("pdftocairo failed (exit " + proc.exitValue() + "): " + output);
        }
        // pdftocairo with -singlefile writes to {prefix}.png
        File actualOutput = new File(prefix + ".png");
        if (!actualOutput.exists()) {
            throw new IOException("pdftocairo did not produce output at " + actualOutput
                + ". Output: " + output);
        }
        Files.move(actualOutput.toPath(), outPng.toPath());
    }

    static double laplacianVariance(BufferedImage img) {
        java.awt.image.Raster r = img.getRaster();
        int w = r.getWidth();
        int h = r.getHeight();
        // Sample 1/4 of pixels for speed
        double sum = 0, sumSq = 0;
        long count = 0;
        int[] px = new int[w];
        for (int y = 0; y < h; y += 4) {
            r.getPixels(0, y, w, 1, px);
            for (int x = 1; x < w; x += 4) {
                double diff = (px[x] & 0xff) - (px[x - 1] & 0xff);
                sum += diff;
                sumSq += diff * diff;
                count++;
            }
        }
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }
}