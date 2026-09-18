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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tries to install Poppler's command-line tools when {@link PopplerRenderer} cannot
 * find them on the system. The strategy depends on the OS:
 * <ul>
 *   <li><b>Linux</b> &mdash; tries {@code apt-get}, {@code dnf}, {@code yum},
 *       {@code apk}, {@code pacman}, {@code zypper} in that order. The package is
 *       in every mainstream distro's default repo, so this succeeds in ~95% of
 *       Linux environments (server containers and developer machines alike).</li>
 *   <li><b>macOS</b> &mdash; tries {@code brew install poppler}. Requires Homebrew
 *       on {@code PATH} (~40% of macOS dev machines have it). Users without brew
 *       are directed to install it via the install hint message.</li>
 *   <li><b>Windows</b> &mdash; first tries {@code winget install Poppler.Poppler}
 *       (built into Windows 10 1709+ and Windows 11), then falls back to downloading
 *       a portable ZIP from
 *       {@code https://github.com/oschwartz10612/poppler-windows/releases} and
 *       extracting it under {@code ~/.opendataloader-pdf/poppler/}.</li>
 * </ul>
 *
 * <p>Every step is best-effort: any failure logs at {@code WARNING} and returns
 * {@code false}, allowing the caller to gracefully degrade to PDFBox. Nothing here
 * throws to the caller.</p>
 */
public final class PopplerAutoInstaller {

    private static final Logger LOGGER = Logger.getLogger(PopplerAutoInstaller.class.getCanonicalName());

    /** Version of the oschwartz10612 portable ZIP we download as a Windows fallback. */
    private static final String WINDOWS_PORTABLE_VERSION = "v24.08.0-0";
    private static final String WINDOWS_PORTABLE_URL =
        "https://github.com/oschwartz10612/poppler-windows/releases/download/"
        + WINDOWS_PORTABLE_VERSION + "/Release-24.08.0-0.zip";

    /** Per-attempt install timeout. Poppler apt install can take ~60s on slow links. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /** Where the portable ZIP gets extracted on Windows (or any OS as a last resort). */
    private static final Path PORTABLE_BASE_DIR =
        Paths.get(System.getProperty("user.home"), ".opendataloader-pdf", "poppler");

    private PopplerAutoInstaller() {}

    /**
     * Top-level entry point: try to install Poppler using the OS-appropriate strategy.
     * Returns {@code true} if a {@code pdftocairo} executable is on {@code PATH}
     * (or in the portable install dir) after this method returns; {@code false}
     * if the attempt failed.
     */
    public static boolean install() {
        if (PopplerRenderer.isWindows()) {
            return installWindows();
        }
        if (PopplerRenderer.isMac()) {
            return installMac();
        }
        if (PopplerRenderer.isLinux()) {
            return installLinux();
        }
        LOGGER.log(Level.WARNING, "Unknown OS; skipping Poppler auto-install");
        return false;
    }

    private static boolean installLinux() {
        // Each entry: argv to run. We try them in order; first one that exits 0 wins.
        String[][] commands = {
            {"apt-get", "install", "-y", "poppler-utils"},
            {"dnf",     "install", "-y", "poppler-utils"},
            {"yum",     "install", "-y", "poppler-utils"},
            {"apk",     "add",     "--no-cache", "poppler-utils"},
            {"pacman",  "-S",      "--noconfirm", "poppler"},
            {"zypper",  "install", "-y", "poppler-tools"},
        };
        for (String[] cmd : commands) {
            if (tryRun(cmd, DEFAULT_TIMEOUT_SECONDS)) {
                LOGGER.log(Level.INFO, "Installed poppler via {0}", cmd[0]);
                return true;
            }
        }
        return false;
    }

    private static boolean installMac() {
        if (tryRun(new String[]{"brew", "install", "poppler"}, DEFAULT_TIMEOUT_SECONDS)) {
            LOGGER.log(Level.INFO, "Installed poppler via brew");
            return true;
        }
        return false;
    }

    private static boolean installWindows() {
        // 1) Try winget first (Windows 10 1709+ and Windows 11 ship it built-in).
        if (tryRun(new String[]{
                "winget", "install", "--id", "Poppler.Poppler",
                "--accept-source-agreements", "--accept-package-agreements",
                "--silent"}, DEFAULT_TIMEOUT_SECONDS)) {
            LOGGER.log(Level.INFO, "Installed poppler via winget");
            return true;
        }
        // 2) Fall back to downloading the portable ZIP.
        return installWindowsPortableZip();
    }

    private static boolean installWindowsPortableZip() {
        Path target = getPortableInstallDir();
        Path bin = target.resolve("Library").resolve("bin").resolve("pdftocairo.exe");
        if (Files.isExecutable(bin)) {
            LOGGER.log(Level.INFO, "Poppler portable already extracted at {0}", target);
            return true;
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not create portable install dir {0}: {1}",
                new Object[]{target.getParent(), e.getMessage()});
            return false;
        }

        Path zipFile = null;
        try {
            zipFile = Files.createTempFile("poppler-portable-", ".zip");
            LOGGER.log(Level.INFO, "Downloading Poppler portable from {0}", WINDOWS_PORTABLE_URL);
            downloadFile(WINDOWS_PORTABLE_URL, zipFile, DEFAULT_TIMEOUT_SECONDS);
            LOGGER.log(Level.INFO, "Extracting {0} -> {1}", new Object[]{zipFile, target});
            unzipToDirectory(zipFile, target);
            boolean ok = Files.isExecutable(bin);
            if (ok) {
                LOGGER.log(Level.INFO, "Poppler portable installed at {0}", target);
            } else {
                LOGGER.log(Level.WARNING,
                    "Extracted portable ZIP but pdftocairo.exe not found at {0}", bin);
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Poppler portable install failed: {0}", t.getMessage());
            return false;
        } finally {
            if (zipFile != null) {
                try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Returns the directory the portable install gets extracted to. Public for
     * {@link PopplerRenderer}, whose binary probe looks for pdftocairo here as
     * well (see {@link PopplerRenderer#retryProbe()}).
     */
    public static Path getPortableInstallDir() {
        return PORTABLE_BASE_DIR.resolve("poppler-" + WINDOWS_PORTABLE_VERSION);
    }

    private static void downloadFile(String url, Path target, long timeoutSeconds)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout((int) (timeoutSeconds * 1000L));
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IOException("Download failed: HTTP " + code + " for " + url);
        }
        long expected = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(target,
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            if (expected > 0 && total != expected) {
                throw new IOException("Download truncated: got " + total + " of " + expected);
            }
        }
    }

    /**
     * Unzips {@code zipFile} into {@code targetDir}, refusing any entry that would
     * escape the target directory (zip-slip). Uses pure JDK so we don't need
     * commons-compress or similar.
     */
    static void unzipToDirectory(Path zipFile, Path targetDir) throws IOException {
        Path absoluteTarget = targetDir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = targetDir.resolve(e.getName()).normalize();
                if (!out.startsWith(absoluteTarget)) {
                    throw new IOException("Refusing zip-slip entry: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream os = Files.newOutputStream(out,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                    // Preserve executable bit on POSIX; on Windows we just rely on
                    // .exe extension being recognized by CreateProcess.
                    if (out.getFileName().toString().toLowerCase().endsWith(".exe")) {
                        try {
                            out.toFile().setExecutable(true);
                        } catch (UnsupportedOperationException ignored) {
                            // Some Windows filesystems don't support setExecutable
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs a command and returns {@code true} only if the process exited 0.
     * Always waits up to {@code timeoutSeconds} and reads all output (logged at
     * FINE on failure, INFO on success).
     */
    private static boolean tryRun(String[] cmd, long timeoutSeconds) {
        try {
            // On non-Windows, run through /bin/sh -c so PATH and shell builtins
            // work the same way the user expects.
            ProcessBuilder pb;
            if (PopplerRenderer.isWindows()) {
                pb = new ProcessBuilder(cmd);
            } else {
                List<String> sh = new ArrayList<>();
                sh.add("/bin/sh");
                sh.add("-c");
                StringBuilder joined = new StringBuilder();
                for (String s : cmd) {
                    joined.append(shellQuote(s)).append(' ');
                }
                joined.append("</dev/null >/dev/null 2>&1");
                sh.add(joined.toString());
                pb = new ProcessBuilder(sh);
            }
            pb.redirectErrorStream(true);
            LOGGER.log(Level.FINE, "PopplerAutoInstaller: running {0}",
                String.join(" ", cmd));
            Process proc = pb.start();
            // Drain stdout/stderr so the subprocess doesn't block on a full pipe.
            String output;
            try (InputStream in = proc.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                LOGGER.log(Level.WARNING,
                    "PopplerAutoInstaller: command timed out after {0}s: {1}",
                    new Object[]{timeoutSeconds, String.join(" ", cmd)});
                return false;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                LOGGER.log(Level.FINE,
                    "PopplerAutoInstaller: command succeeded: {0}", String.join(" ", cmd));
                return true;
            }
            LOGGER.log(Level.FINE,
                "PopplerAutoInstaller: command failed (exit {0}): {1}\n{2}",
                new Object[]{exit, String.join(" ", cmd), output});
            return false;
        } catch (Throwable t) {
            LOGGER.log(Level.FINE,
                "PopplerAutoInstaller: command error: {0}", t.getMessage());
            return false;
        }
    }

    /** Minimal POSIX shell quoting; only used for /bin/sh -c. */
    private static String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        if (s.matches("[a-zA-Z0-9_./+@:\\-]+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}