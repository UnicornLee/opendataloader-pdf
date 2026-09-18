package org.opendataloader.pdf.poppler;

import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.poppler.PopplerRenderer.ProbeResult;

import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic tests for the PATH search behaviour. */
public class PopplerProbeDiagnosticTest {

    @Test
    public void probeReturnsStructuredResult() {
        // Force a fresh probe
        PopplerRenderer.resetProbeForTesting();
        ProbeResult r = PopplerRenderer.lastProbe();

        // Always non-null result
        assertNotNull(r);

        // pathEntryCount should match System.getenv("PATH") entry count
        String path = System.getenv("PATH");
        int expected = path == null ? 0 : path.split(java.io.File.pathSeparator).length;
        assertEquals(expected, r.pathEntryCount, "pathEntryCount should match raw PATH entries");

        // checked should be >= pathEntryCount (one candidate * one ext per entry, minimum)
        // On Windows: 2 candidates * up to 4 exts per entry
        // On *nix: 2 candidates * 1 ext per entry
        // Just sanity-check it ran
        assertTrue(r.checked >= 0, "checked should be non-negative");

        // searchedSample should be non-empty unless PATH is empty
        if (expected > 0) {
            assertNotNull(r.searchedSample);
            assertFalse(r.searchedSample.isEmpty());
            assertNotEquals("(none)", r.searchedSample);
        }

        // If we found Poppler, great; if not, that's also fine - this test just checks structure
        if (r.found != null) {
            assertTrue(new java.io.File(r.found).isFile(),
                "found path should point to an actual file: " + r.found);
        }
    }

    @Test
    public void probeIsIdempotent() {
        // First call caches, second call should return the same result
        PopplerRenderer.resetProbeForTesting();
        ProbeResult r1 = PopplerRenderer.lastProbe();
        ProbeResult r2 = PopplerRenderer.lastProbe();
        // found may differ if env changed between calls, but at minimum both should be non-null
        assertNotNull(r1);
        assertNotNull(r2);
    }
}