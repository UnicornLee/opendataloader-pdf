package org.opendataloader.pdf.utils;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs malformed {@code /ToUnicode} CMaps that map a printable ASCII source
 * code (0x20–0x7E) to a C0 control character (e.g. {@code <31> <0018>}).
 *
 * <p>Such a mapping is always a producer defect: a text code in the printable
 * range is never meant to decode to a control character, and the intended
 * character for a printable code is the character with that same code point
 * (this holds for the WinAnsi / PDFDoc / Standard encodings across 0x20–0x7E,
 * which are ASCII-compatible). Readers that trust {@code /ToUnicode}
 * (veraPDF, PDFBox) then hand back a control character that no renderer can
 * draw, so the glyph shows up as a "tofu" box (a rectangle with a diagonal
 * line). A concrete example is a subset {@code Times-Roman} whose ToUnicode
 * turns the digits {@code 1} and {@code 2} into {@code U+0018}.</p>
 *
 * <p>The repair rewrites only the offending destination values in place,
 * leaving every other mapping and the block structure untouched, so fonts that
 * legitimately rely on {@code /ToUnicode} for non-ASCII glyphs are unaffected.
 * It runs on a PDFBox document before veraPDF parses it; the caller persists
 * the result to a temporary file when anything changed.</p>
 */
public final class ToUnicodeRepair {

    private static final Logger LOGGER = Logger.getLogger(ToUnicodeRepair.class.getName());

    /** A single {@code <hex>} string token inside a CMap. */
    private static final Pattern HEX_TOKEN = Pattern.compile("<([0-9A-Fa-f]*)>");
    /** bfrange array form: {@code <start> <end> [ <d1> <d2> ... ]}. */
    private static final Pattern BFRANGE_ARRAY =
        Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[([^\\]]*)\\]");
    /** bfrange dstStart form: {@code <start> <end> <dstStart>}. */
    private static final Pattern BFRANGE_DSTSTART =
        Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");

    private ToUnicodeRepair() {
    }

    /**
     * Scans every font reachable from the document's pages (including fonts
     * inside form XObjects) and repairs broken ToUnicode CMaps.
     *
     * @param document the PDFBox document to repair in place
     * @return the number of font CMaps that were modified (0 when nothing was broken)
     */
    public static int repairBrokenToUnicode(PDDocument document) {
        Map<COSDictionary, Boolean> visitedFonts = new IdentityHashMap<>();
        Map<PDResources, Boolean> visitedResources = new IdentityHashMap<>();
        int repaired = 0;
        for (PDPage page : document.getPages()) {
            repaired += repairResources(page.getResources(), visitedFonts, visitedResources);
        }
        return repaired;
    }

    private static int repairResources(PDResources resources, Map<COSDictionary, Boolean> visitedFonts,
                                       Map<PDResources, Boolean> visitedResources) {
        if (resources == null || visitedResources.put(resources, Boolean.TRUE) != null) {
            return 0;
        }
        int repaired = 0;
        for (COSName fontName : resources.getFontNames()) {
            PDFont font;
            try {
                font = resources.getFont(fontName);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable font " + fontName, e);
                continue;
            }
            if (font == null) {
                continue;
            }
            COSDictionary fontDict = font.getCOSObject();
            if (visitedFonts.put(fontDict, Boolean.TRUE) != null) {
                continue;
            }
            repaired += repairFontDictionary(fontDict);
        }
        for (COSName xObjectName : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(xObjectName);
                if (xObject instanceof PDFormXObject) {
                    repaired += repairResources(((PDFormXObject) xObject).getResources(), visitedFonts, visitedResources);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable XObject " + xObjectName, e);
            }
        }
        return repaired;
    }

    private static int repairFontDictionary(COSDictionary fontDict) {
        COSBase toUnicode = fontDict.getDictionaryObject(COSName.TO_UNICODE);
        if (!(toUnicode instanceof COSStream)) {
            return 0;
        }
        return repairCMapStream((COSStream) toUnicode) ? 1 : 0;
    }

    private static boolean repairCMapStream(COSStream stream) {
        String original = readStream(stream);
        if (original == null) {
            return false;
        }
        String repaired = repairCMapText(original);
        if (repaired == null) {
            return false;
        }
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(repaired.getBytes(StandardCharsets.ISO_8859_1));
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to rewrite a ToUnicode CMap stream", e);
            return false;
        }
    }

    private static String readStream(COSStream stream) {
        try (InputStream in = stream.createInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            return bos.toString(StandardCharsets.ISO_8859_1.name());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to read a ToUnicode CMap stream", e);
            return null;
        }
    }

    /**
     * Rewrites broken mappings in a CMap program.
     *
     * @return the corrected CMap text, or {@code null} when nothing needed fixing
     */
    static String repairCMapText(String cmap) {
        String result = repairBfcharBlocks(cmap);
        result = repairBfrangeBlocks(result);
        return result.equals(cmap) ? null : result;
    }

    private static String repairBfcharBlocks(String cmap) {
        return forEachBlock(cmap, "beginbfchar", "endbfchar", content -> {
            List<int[]> spans = new ArrayList<>();
            List<String> values = new ArrayList<>();
            Matcher m = HEX_TOKEN.matcher(content);
            while (m.find()) {
                spans.add(new int[]{m.start(), m.end()});
                values.add(m.group(1));
            }
            StringBuilder sb = new StringBuilder(content);
            boolean changed = false;
            // Entries come in (src, dst) pairs; rewrite the odd (destination) tokens.
            for (int i = 1; i < values.size(); i += 2) {
                String replacement = correctedDestination(values.get(i - 1), values.get(i));
                if (replacement != null) {
                    int[] span = spans.get(i);
                    sb.replace(span[0], span[1], "<" + replacement + ">");
                    changed = true;
                }
            }
            return changed ? sb.toString() : null;
        });
    }

    private static String repairBfrangeBlocks(String cmap) {
        return forEachBlock(cmap, "beginbfrange", "endbfrange", content -> {
            StringBuilder sb = new StringBuilder(content);
            boolean changed = repairBfrangeArrays(sb);
            if (repairBfrangeDstStarts(sb)) {
                changed = true;
            }
            return changed ? sb.toString() : null;
        });
    }

    /** Rewrite the per-code destination arrays of bfrange entries. */
    private static boolean repairBfrangeArrays(StringBuilder block) {
        boolean changed = false;
        Matcher m = BFRANGE_ARRAY.matcher(block);
        while (m.find()) {
            int start = parseHexCode(m.group(1));
            int end = parseHexCode(m.group(2));
            if (start < 0 || end < 0 || end < start) {
                continue;
            }
            String arrayBody = m.group(3);
            List<int[]> tokenSpans = new ArrayList<>();
            List<String> tokenValues = new ArrayList<>();
            Matcher tm = HEX_TOKEN.matcher(arrayBody);
            while (tm.find()) {
                tokenSpans.add(new int[]{tm.start(), tm.end()});
                tokenValues.add(tm.group(1));
            }
            StringBuilder newArray = new StringBuilder(arrayBody);
            boolean arrayChanged = false;
            for (int i = 0; i < tokenValues.size() && start + i <= end; i++) {
                int src = start + i;
                String replacement = correctedDestination(hex(src), tokenValues.get(i));
                if (replacement != null) {
                    int[] span = tokenSpans.get(i);
                    newArray.replace(span[0], span[1], "<" + replacement + ">");
                    arrayChanged = true;
                }
            }
            if (arrayChanged) {
                // Replace the array body inside the whole-block buffer.
                int bodyStart = m.start(3);
                int bodyEnd = m.end(3);
                block.replace(bodyStart, bodyEnd, newArray.toString());
                changed = true;
                // Indices after this match shift; restart scanning from the top.
                return repairBfrangeArrays(block);
            }
        }
        return changed;
    }

    /**
     * Rewrite {@code <start> <end> <dstStart>} bfrange entries whose base maps a
     * printable code to a control character. Only applied when the whole source
     * range stays inside printable ASCII, where identity is provably correct.
     */
    private static boolean repairBfrangeDstStarts(StringBuilder block) {
        Matcher m = BFRANGE_DSTSTART.matcher(block);
        while (m.find()) {
            int start = parseHexCode(m.group(1));
            int end = parseHexCode(m.group(2));
            String dstStart = m.group(3);
            if (start < 0x20 || start > 0x7E || end > 0x7E || end < start) {
                continue;
            }
            if (correctedDestination(hex(start), dstStart) != null) {
                block.replace(m.start(3), m.end(3), "<" + hex(start) + ">");
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the corrected destination hex (4 digits) when {@code srcHex} is a
     * printable ASCII code but {@code dstHex} decodes to a non-whitespace C0
     * control character; {@code null} when the mapping is fine as-is.
     */
    private static String correctedDestination(String srcHex, String dstHex) {
        int src = parseHexCode(srcHex);
        if (src < 0x20 || src > 0x7E) {
            return null;
        }
        // Only a single UCS-2 code unit (4 hex digits) can be reasoned about here;
        // multi-unit destinations are left untouched.
        if (dstHex == null || dstHex.length() != 4) {
            return null;
        }
        int dst;
        try {
            dst = Integer.parseInt(dstHex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if (dst >= 0x20 || dst == 0x09 || dst == 0x0A || dst == 0x0D) {
            return null;
        }
        return hex(src);
    }

    private static int parseHexCode(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() > 4) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String hex(int code) {
        return String.format("%04X", code);
    }

    /**
     * Applies {@code fixer} to the body of every {@code begin.../end...} block,
     * substituting the returned text when the fixer reports a change.
     */
    private static String forEachBlock(String cmap, String begin, String end,
                                       java.util.function.Function<String, String> fixer) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (true) {
            int beginIdx = cmap.indexOf(begin, cursor);
            if (beginIdx < 0) {
                break;
            }
            int contentStart = cmap.indexOf('\n', beginIdx);
            if (contentStart < 0) {
                break;
            }
            contentStart++;
            int endIdx = cmap.indexOf(end, contentStart);
            if (endIdx < 0) {
                break;
            }
            int contentEnd = cmap.lastIndexOf('\n', endIdx);
            if (contentEnd < contentStart) {
                contentEnd = endIdx;
            }
            String body = cmap.substring(contentStart, contentEnd);
            String fixed = fixer.apply(body);
            out.append(cmap, cursor, contentStart);
            if (fixed != null) {
                out.append(fixed);
            } else {
                out.append(body);
            }
            cursor = contentEnd;
        }
        out.append(cmap, cursor, cmap.length());
        return out.toString();
    }
}
