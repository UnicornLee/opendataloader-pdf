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
package org.opendataloader.pdf.poppler;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders PDF pages using Poppler's {@code pdftocairo} command-line tool. Designed as
 * a drop-in replacement for {@code PDFRenderer.renderImageWithDPI} when a CJK-clean
 * rasterizer is needed: Poppler's Cairo backend produces CJK text with proper
 * subpixel anti-aliasing and is the same engine LibreOffice / Chrome PDFium
 * derivatives trace their CJK handling to.
 *
 * <p><b>Lifecycle:</b> {@link #isAvailable()} probes for {@code pdftocairo} on
 * {@code PATH} exactly once. If missing, {@link PopplerAutoInstaller} is invoked.
 * If the install attempt also fails (no network, no permission, etc.) the
 * {@link State} is cached as {@code UNAVAILABLE} and subsequent callers should
 * fall back to PDFBox rather than repeatedly retrying the install.</p>
 *
 * <p><b>No state leaks:</b> the renderer is stateless; the only shared state is
 * the cached {@code State} and the {@code pdftocairo} path. No long-running
 * subprocess is spawned (a fresh process per {@link #render} call, terminated
 * after a 60s timeout).</p>
 */
public final class PopplerRenderer {

    private static final Logger LOGGER = Logger.getLogger(PopplerRenderer.class.getCanonicalName());

    /** Default render DPI, matches the existing PDFBox path. */
    static final int DEFAULT_DPI = 300;

    /** Hard timeout for each {@code pdftocairo} invocation. */
    static final long SUBPROCESS_TIMEOUT_SECONDS = 60L;

    private enum State { UNCHECKED, AVAILABLE, UNAVAILABLE }

    private static volatile State state = State.UNCHECKED;
    private static volatile String binaryPath;

    private PopplerRenderer() {}

    /**
     * Returns {@code true} if Poppler is installed and usable (either pre-existing
     * or successfully auto-installed during this JVM's lifetime). The result is
     * cached, so the first call may trigger the install probe; subsequent calls
     * are O(1).
     */
    public static boolean isAvailable() {
        ensureProbed();
        return state == State.AVAILABLE;
    }

    /**
     * Returns the absolute path of the {@code pdftocairo} binary, or {@code null}
     * if Poppler is not available. Useful for diagnostics and for callers that
     * want to spawn their own subprocesses.
     */
    public static String getBinaryPath() {
        ensureProbed();
        return binaryPath;
    }

    /**
     * Forcibly resets the cached availability probe; mostly useful in tests.
     */
    static synchronized void resetProbeForTesting() {
        state = State.UNCHECKED;
        binaryPath = null;
    }

    private static synchronized void ensureProbed() {
        if (state != State.UNCHECKED) {
            return;
        }
        ProbeResult probe = probe();
        if (probe.found != null) {
            binaryPath = probe.found;
            state = State.AVAILABLE;
            LOGGER.log(Level.INFO, "Poppler pdftocairo available at: {0}", probe.found);
            return;
        }
        // Only attempt auto-install if PATH search genuinely failed; avoid retrying
        // on every render when the user has explicitly chosen to skip Poppler.
        LOGGER.log(Level.FINE, "Poppler not found on PATH (checked {0} entries); trying auto-install",
            probe.checked);
        if (PopplerAutoInstaller.install()) {
            probe = probe();
        }
        if (probe.found != null) {
            binaryPath = probe.found;
            state = State.AVAILABLE;
            LOGGER.log(Level.INFO,
                "Poppler pdftocairo available after auto-install at: {0}", probe.found);
            return;
        }
        state = State.UNAVAILABLE;
        // Log the actual PATH we saw and which directories we checked, so the user
        // can immediately see why detection failed (typical causes: env var set in
        // a different terminal than the one running JVM, typo in the path, file
        // missing despite directory existing).
        LOGGER.log(Level.WARNING,
            "Poppler (pdftocairo) is not available; falling back to PDFBox for screenshots. " +
            "Install manually with: {0}\n" +
            "  Probed PATH had {1} entries; checked {2}; first 5: {3}",
            new Object[]{installInstructions(),
                probe.pathEntryCount, probe.checked, probe.searchedSample});
    }

    /** Result of searching the system + portable dirs for a Poppler binary. */
    static final class ProbeResult {
        final String found;
        final int pathEntryCount;
        final int checked;
        final String searchedSample; // first 5 dirs checked, joined
        ProbeResult(String found, int pathEntryCount, int checked, String searchedSample) {
            this.found = found;
            this.pathEntryCount = pathEntryCount;
            this.checked = checked;
            this.searchedSample = searchedSample;
        }
    }

    private static ProbeResult probe() {
        String[] candidates = isWindows()
            ? new String[]{"pdftocairo.exe", "pdftoppm.exe"}
            : new String[]{"pdftocairo", "pdftoppm"};
        String[] exts = isWindows() ? new String[]{"", ".exe", ".bat", ".cmd"} : new String[]{""};
        String pathEnv = System.getenv("PATH");
        int entryCount = 0;
        int checked = 0;
        java.util.List<String> sample = new java.util.ArrayList<>();
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String sep = File.pathSeparator;
            String[] dirs = pathEnv.split(sep);
            entryCount = dirs.length;
            for (String name : candidates) {
                for (String dir : dirs) {
                    if (dir == null || dir.isEmpty()) continue;
                    for (String ext : exts) {
                        File f = new File(dir, name + ext);
                        if (sample.size() < 5) sample.add(f.getAbsolutePath());
                        if (f.isFile() && isExecutableForOs(f)) {
                            return new ProbeResult(f.getAbsolutePath(), entryCount, ++checked, joinSample(sample));
                        }
                        checked++;
                    }
                }
            }
        }
        // Also check our portable install dir
        Path portable = PopplerAutoInstaller.getPortableInstallDir();
        if (portable != null && Files.isDirectory(portable)) {
            for (String name : candidates) {
                Path bin = portable.resolve(name);
                if (sample.size() < 5) sample.add(bin.toString());
                if (Files.isExecutable(bin)) {
                    return new ProbeResult(bin.toString(), entryCount, ++checked, joinSample(sample));
                }
                checked++;
            }
        }
        return new ProbeResult(null, entryCount, checked, joinSample(sample));
    }

    private static String joinSample(java.util.List<String> sample) {
        if (sample.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sample.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sample.get(i));
        }
        return sb.toString();
    }

    /**
     * On Windows, an .exe is executable by extension; we don't strictly need the
     * executable bit, which can be missing on network drives or in archives. Only
     * require canExecute() on non-Windows.
     */
    private static boolean isExecutableForOs(File f) {
        if (isWindows()) {
            return f.getName().toLowerCase().endsWith(".exe")
                || f.getName().toLowerCase().endsWith(".bat")
                || f.getName().toLowerCase().endsWith(".cmd")
                || f.canExecute();
        }
        return f.canExecute();
    }

    /**
     * Renders a single page of a PDF to a {@link BufferedImage} using {@code pdftocairo}.
     *
     * @param pdfFile       source PDF
     * @param pageNumber    0-based page index
     * @param clipYPdf      optional {@code [top, bottom]} y-range in top-left PDF points
     *                      ({@code null} = full page)
     * @return rendered image at {@link #DEFAULT_DPI}, in the same dimensions
     *         the equivalent PDFBox code would produce
     * @throws IOException if Poppler is unavailable or rendering fails
     */
    public static BufferedImage render(File pdfFile, int pageNumber, double[] clipYPdf)
            throws IOException {
        if (!isAvailable()) {
            throw new IOException("Poppler is not available; cannot render via PopplerRenderer");
        }
        return doRender(pdfFile, pageNumber, clipYPdf);
    }

    private static BufferedImage doRender(File pdfFile, int pageNumber, double[] clipYPdf)
            throws IOException {
        Path tempDir = Files.createTempDirectory("opendataloader-poppler-");
        File outputPrefix = tempDir.resolve("page").toFile();
        File actualOutput = tempDir.resolve("page.png").toFile();

        try {
            int[] pageSizePx = getPageSizeInPixels(pdfFile, pageNumber, DEFAULT_DPI);

            List<String> cmd = new ArrayList<>();
            cmd.add(binaryPath);
            cmd.add("-r");
            cmd.add(String.valueOf(DEFAULT_DPI));
            cmd.add("-f");
            cmd.add(String.valueOf(pageNumber + 1));
            cmd.add("-l");
            cmd.add(String.valueOf(pageNumber + 1));
            cmd.add("-png");
            cmd.add("-singlefile");
            if (clipYPdf != null) {
                int x = 0;
                int y = (int) Math.round(clipYPdf[0] * DEFAULT_DPI / 72.0);
                int w = pageSizePx[0];
                int h = (int) Math.round((clipYPdf[1] - clipYPdf[0]) * DEFAULT_DPI / 72.0);
                if (h <= 0) {
                    throw new IOException("Invalid clip range: top=" + clipYPdf[0]
                        + " bottom=" + clipYPdf[1] + " (empty band)");
                }
                cmd.add("-x");
                cmd.add(String.valueOf(x));
                cmd.add("-y");
                cmd.add(String.valueOf(y));
                cmd.add("-W");
                cmd.add(String.valueOf(w));
                cmd.add("-H");
                cmd.add(String.valueOf(h));
            }
            cmd.add(pdfFile.getAbsolutePath());
            cmd.add(outputPrefix.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Force C.UTF-8 so the subprocess can decode paths/console output that
            // might contain non-ASCII characters (e.g. Chinese PDF filenames).
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (InputStream in = proc.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("pdftocairo timed out after " + SUBPROCESS_TIMEOUT_SECONDS + "s");
            }
            if (proc.exitValue() != 0) {
                throw new IOException("pdftocairo failed (exit " + proc.exitValue() + "): " + output);
            }
            if (!actualOutput.exists()) {
                throw new IOException("pdftocairo did not produce output at " + actualOutput);
            }
            BufferedImage img = ImageIO.read(actualOutput);
            if (img == null) {
                throw new IOException("pdftocairo produced unreadable PNG at " + actualOutput);
            }
            return img;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pdftocairo interrupted", e);
        } finally {
            deleteRecursive(tempDir.toFile());
        }
    }

    /**
     * Returns {@code [widthPx, heightPx]} for the given page at the given DPI,
     * using PDFBox's lightweight metadata API (no rendering).
     */
    private static int[] getPageSizeInPixels(File pdf, int pageNumber, int dpi) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageNumber);
            org.apache.pdfbox.pdmodel.common.PDRectangle box = page.getMediaBox();
            int width = (int) Math.round(box.getWidth() / 72.0 * dpi);
            int height = (int) Math.round(box.getHeight() / 72.0 * dpi);
            return new int[]{width, height};
        }
    }

    /**
     * Re-runs the Poppler probe without touching the cached state. Useful after the
     * user fixes their PATH or installs Poppler outside the JVM.
     */
    public static synchronized boolean retryProbe() {
        ProbeResult probe = probe();
        if (probe.found != null) {
            binaryPath = probe.found;
            state = State.AVAILABLE;
            LOGGER.log(Level.INFO, "Poppler re-probe succeeded: {0}", probe.found);
            return true;
        }
        LOGGER.log(Level.WARNING, "Poppler re-probe failed; still unavailable");
        return false;
    }

    /**
     * Returns the most recent {@link ProbeResult} from {@link #probe()}, useful for
     * diagnostics (e.g. the CLI's --diagnose-poppler command).
     */
    public static synchronized ProbeResult lastProbe() {
        return probe();
    }

    static String installInstructions() {
        if (isWindows()) {
            return "winget install Poppler.Poppler  (or download portable ZIP from "
                + "https://github.com/oschwartz10612/poppler-windows/releases)";
        }
        if (isMac()) {
            return "brew install poppler";
        }
        return "apt install poppler-utils  (or dnf / apk / zypper equivalent for your distro)";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        if (!f.delete()) {
            // Best-effort; will be cleaned by the OS temp cleanup eventually
        }
    }
}