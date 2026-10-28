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
package org.opendataloader.pdf.utils;

import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;

import java.util.List;
import java.util.Locale;

/**
 * Rewrites the glyphs of the common PDF symbol fonts (Wingdings / Wingdings 2 /
 * Wingdings 3 / Webdings / Symbol / Zapf Dingbats) into browser-safe Unicode.
 *
 * <p>Those fonts render their pictures through private-use-area (PUA) code
 * points in the {@code U+F020–U+F0FF} block (the source byte plus {@code 0xF000}).
 * No mainstream browser ships a glyph for the PUA, so the extracted text shows up
 * as the ".notdef" box (a rectangle with a diagonal) instead of the intended
 * square / arrow / checkmark.</p>
 *
 * <p>This mapper translates every PUA character found in a recognized symbol-font
 * text chunk to an equivalent character from a block that ships with the default
 * UI fonts of Chrome / Firefox / Safari / Edge on Windows, macOS, Linux, iOS and
 * Android: Arrows ({@code U+2190–U+21FF}), Enclosed Alphanumerics
 * ({@code U+2460–U+24FF}), Geometric Shapes ({@code U+25A0–U+25FF}), Miscellaneous
 * Symbols ({@code U+2600–U+26FF}) and Dingbats ({@code U+2700–U+27BF}).</p>
 *
 * <p>The mapping source of truth is the MIT-licensed {@code dingbat-to-unicode}
 * tables, filtered down to the well-supported BMP blocks above. Per the user's
 * requirement, the Wingdings solid/arrow bullets (source bytes {@code 0x6E}/{@code 0xA7},
 * which render as solid black squares in the sample document) are normalized to
 * {@code U+25A0 ■}. Any PUA character without a table entry falls back to a generic
 * bullet {@code U+2022} so that no tofu reaches the output.</p>
 *
 * <p>Wingdings 2 and Wingdings 3 are separate typefaces with their own byte to
 * glyph tables, so they must not be folded into the Wingdings table: the same
 * source byte means a different symbol in each family (Wingdings {@code 0x52} is
 * a sun, Wingdings 2 {@code 0x52} is a checked ballot box {@code U+2611}).</p>
 *
 * <p>Substitution is strictly 1:1 (one input character becomes exactly one output
 * character), which keeps {@link TextChunk#getSymbolEnds()} and every downstream
 * geometric coordinate valid.</p>
 */
public final class SymbolFontMapper {

    /** Font families with a dedicated glyph mapping table. */
    private enum SymbolFont {
        WINGDINGS,
        WINGDINGS_2,
        WINGDINGS_3,
        WEBDINGS,
        SYMBOL,
        ZAPF_DINGBATS
    }

    /** Private-use block produced by the symbol fonts: source byte + {@code 0xF000}. */
    private static final int PUA_BASE = 0xF000;
    private static final int PUA_MIN = 0xF020;
    private static final int PUA_MAX = 0xF0FF;

    /** Placeholder for PUA glyphs that have no well-supported Unicode equivalent. */
    private static final char FALLBACK_BULLET = '\u2022';

    /** Per-family table indexed by source byte ({@code 0x00–0xFF}); {@code 0} means unmapped. */
    private static final int[][] MAPS = new int[SymbolFont.values().length][0x100];

    static {
        // --- Wingdings (browser-safe subset, user overrides: 0x6E/0xA7 -> U+25A0) ---
        put(SymbolFont.WINGDINGS, 0x23, 0x2701); // # -> ✁
        put(SymbolFont.WINGDINGS, 0x29, 0x2706); // ) -> ✆
        put(SymbolFont.WINGDINGS, 0x3E, 0x2707); // > -> ✇
        put(SymbolFont.WINGDINGS, 0x3F, 0x270D); // ? -> ✍
        put(SymbolFont.WINGDINGS, 0x41, 0x270C); // A -> ✌
        put(SymbolFont.WINGDINGS, 0x45, 0x261C); // E -> ☜
        put(SymbolFont.WINGDINGS, 0x46, 0x261E); // F -> ☞
        put(SymbolFont.WINGDINGS, 0x47, 0x261D); // G -> ☝
        put(SymbolFont.WINGDINGS, 0x48, 0x261F); // H -> ☟
        put(SymbolFont.WINGDINGS, 0x4A, 0x263A); // J -> ☺
        put(SymbolFont.WINGDINGS, 0x4C, 0x2639); // L -> ☹
        put(SymbolFont.WINGDINGS, 0x51, 0x2708); // Q -> ✈
        put(SymbolFont.WINGDINGS, 0x52, 0x263C); // R -> ☼
        put(SymbolFont.WINGDINGS, 0x54, 0x2744); // T -> ❄
        put(SymbolFont.WINGDINGS, 0x56, 0x271E); // V -> ✞
        put(SymbolFont.WINGDINGS, 0x58, 0x2720); // X -> ✠
        put(SymbolFont.WINGDINGS, 0x59, 0x2721); // Y -> ✡
        put(SymbolFont.WINGDINGS, 0x5A, 0x262A); // Z -> ☪
        put(SymbolFont.WINGDINGS, 0x5B, 0x262F); // [ -> ☯
        put(SymbolFont.WINGDINGS, 0x5D, 0x2638); // ] -> ☸
        put(SymbolFont.WINGDINGS, 0x5E, 0x2648); // ^ -> ♈
        put(SymbolFont.WINGDINGS, 0x5F, 0x2649); // _ -> ♉
        put(SymbolFont.WINGDINGS, 0x60, 0x264A); // ` -> ♊
        put(SymbolFont.WINGDINGS, 0x61, 0x264B); // a -> ♋
        put(SymbolFont.WINGDINGS, 0x62, 0x264C); // b -> ♌
        put(SymbolFont.WINGDINGS, 0x63, 0x264D); // c -> ♍
        put(SymbolFont.WINGDINGS, 0x64, 0x264E); // d -> ♎
        put(SymbolFont.WINGDINGS, 0x65, 0x264F); // e -> ♏
        put(SymbolFont.WINGDINGS, 0x66, 0x2650); // f -> ♐
        put(SymbolFont.WINGDINGS, 0x67, 0x2651); // g -> ♑
        put(SymbolFont.WINGDINGS, 0x68, 0x2652); // h -> ♒
        put(SymbolFont.WINGDINGS, 0x69, 0x2653); // i -> ♓
        put(SymbolFont.WINGDINGS, 0x6C, 0x26AB); // l -> ⚫
        put(SymbolFont.WINGDINGS, 0x6E, 0x25A0); // n -> ■ (user override: solid black square)
        put(SymbolFont.WINGDINGS, 0x71, 0x25A1); // q -> □
        put(SymbolFont.WINGDINGS, 0x72, 0x25A1); // r -> □
        put(SymbolFont.WINGDINGS, 0x75, 0x25C6); // u -> ◆
        put(SymbolFont.WINGDINGS, 0x76, 0x2756); // v -> ❖
        put(SymbolFont.WINGDINGS, 0x7F, 0x25AF); //  -> ▯
        put(SymbolFont.WINGDINGS, 0x81, 0x2780); //  -> ➀
        put(SymbolFont.WINGDINGS, 0x82, 0x2781); //  -> ➁
        put(SymbolFont.WINGDINGS, 0x83, 0x2782); //  -> ➂
        put(SymbolFont.WINGDINGS, 0x84, 0x2783); //  -> ➃
        put(SymbolFont.WINGDINGS, 0x85, 0x2784); //  -> ➄
        put(SymbolFont.WINGDINGS, 0x86, 0x2785); //  -> ➅
        put(SymbolFont.WINGDINGS, 0x87, 0x2786); //  -> ➆
        put(SymbolFont.WINGDINGS, 0x88, 0x2787); //  -> ➇
        put(SymbolFont.WINGDINGS, 0x89, 0x2788); //  -> ➈
        put(SymbolFont.WINGDINGS, 0x8A, 0x2789); //  -> ➉
        put(SymbolFont.WINGDINGS, 0x8C, 0x278A); //  -> ➊
        put(SymbolFont.WINGDINGS, 0x8D, 0x278B); //  -> ➋
        put(SymbolFont.WINGDINGS, 0x8E, 0x278C); //  -> ➌
        put(SymbolFont.WINGDINGS, 0x8F, 0x278D); //  -> ➍
        put(SymbolFont.WINGDINGS, 0x90, 0x278E); //  -> ➎
        put(SymbolFont.WINGDINGS, 0x91, 0x278F); //  -> ➏
        put(SymbolFont.WINGDINGS, 0x92, 0x2790); //  -> ➐
        put(SymbolFont.WINGDINGS, 0x93, 0x2791); //  -> ➑
        put(SymbolFont.WINGDINGS, 0x94, 0x2792); //  -> ➒
        put(SymbolFont.WINGDINGS, 0x95, 0x2793); //  -> ➓
        put(SymbolFont.WINGDINGS, 0x9F, 0x2022); //  -> •
        put(SymbolFont.WINGDINGS, 0xA7, 0x25A0); // § -> ■ (user override: solid black square)
        put(SymbolFont.WINGDINGS, 0xAB, 0x2605); // « -> ★
        put(SymbolFont.WINGDINGS, 0xB5, 0x272A); // µ -> ✪
        put(SymbolFont.WINGDINGS, 0xB6, 0x2730); // ¶ -> ✰
        put(SymbolFont.WINGDINGS, 0xEF, 0x21E6); // ï -> ⇦
        put(SymbolFont.WINGDINGS, 0xF0, 0x21E8); // ð -> ⇨
        put(SymbolFont.WINGDINGS, 0xF1, 0x21E7); // ñ -> ⇧
        put(SymbolFont.WINGDINGS, 0xF2, 0x21E9); // ò -> ⇩
        put(SymbolFont.WINGDINGS, 0xF4, 0x21F3); // ô -> ⇳
        put(SymbolFont.WINGDINGS, 0xFC, 0x2713); // ü -> ✓

        // --- Wingdings 2 (browser-safe subset, 44 entries) ---
        put(SymbolFont.WINGDINGS_2, 0x25, 0x2704); // % -> ✄
        put(SymbolFont.WINGDINGS_2, 0x26, 0x2700); // & -> ✀
        put(SymbolFont.WINGDINGS_2, 0x52, 0x2611); // R -> ☑
        put(SymbolFont.WINGDINGS_2, 0x54, 0x2612); // T -> ☒
        put(SymbolFont.WINGDINGS_2, 0x69, 0x24EA); // i -> ⓪
        put(SymbolFont.WINGDINGS_2, 0x6A, 0x2460); // j -> ①
        put(SymbolFont.WINGDINGS_2, 0x6B, 0x2461); // k -> ②
        put(SymbolFont.WINGDINGS_2, 0x6C, 0x2462); // l -> ③
        put(SymbolFont.WINGDINGS_2, 0x6D, 0x2463); // m -> ④
        put(SymbolFont.WINGDINGS_2, 0x6E, 0x2464); // n -> ⑤
        put(SymbolFont.WINGDINGS_2, 0x6F, 0x2465); // o -> ⑥
        put(SymbolFont.WINGDINGS_2, 0x70, 0x2466); // p -> ⑦
        put(SymbolFont.WINGDINGS_2, 0x71, 0x2467); // q -> ⑧
        put(SymbolFont.WINGDINGS_2, 0x72, 0x2468); // r -> ⑨
        put(SymbolFont.WINGDINGS_2, 0x73, 0x2469); // s -> ⑩
        put(SymbolFont.WINGDINGS_2, 0x74, 0x24FF); // t -> ⓿
        put(SymbolFont.WINGDINGS_2, 0x75, 0x2776); // u -> ❶
        put(SymbolFont.WINGDINGS_2, 0x76, 0x2777); // v -> ❷
        put(SymbolFont.WINGDINGS_2, 0x77, 0x2778); // w -> ❸
        put(SymbolFont.WINGDINGS_2, 0x78, 0x2779); // x -> ❹
        put(SymbolFont.WINGDINGS_2, 0x79, 0x277A); // y -> ❺
        put(SymbolFont.WINGDINGS_2, 0x7A, 0x277B); // z -> ❻
        put(SymbolFont.WINGDINGS_2, 0x7B, 0x277C); // { -> ❼
        put(SymbolFont.WINGDINGS_2, 0x7C, 0x277D); // | -> ❽
        put(SymbolFont.WINGDINGS_2, 0x7D, 0x277E); // } -> ❾
        put(SymbolFont.WINGDINGS_2, 0x7E, 0x277F); // ~ -> ❿
        put(SymbolFont.WINGDINGS_2, 0x80, 0x2609); //  -> ☉
        put(SymbolFont.WINGDINGS_2, 0x82, 0x263D); //  -> ☽
        put(SymbolFont.WINGDINGS_2, 0x83, 0x263E); //  -> ☾
        put(SymbolFont.WINGDINGS_2, 0x85, 0x271D); //  -> ✝
        put(SymbolFont.WINGDINGS_2, 0x98, 0x25CF); //  -> ●
        put(SymbolFont.WINGDINGS_2, 0x99, 0x25CB); //  -> ○
        put(SymbolFont.WINGDINGS_2, 0xA1, 0x25A0); //  -> ■
        put(SymbolFont.WINGDINGS_2, 0xA2, 0x25A0); //  -> ■
        put(SymbolFont.WINGDINGS_2, 0xA3, 0x25A1); //  -> □
        put(SymbolFont.WINGDINGS_2, 0xA8, 0x25A3); //  -> ▣
        put(SymbolFont.WINGDINGS_2, 0xAF, 0x25C7); //  -> ◇
        put(SymbolFont.WINGDINGS_2, 0xB1, 0x25C8); //  -> ◈
        put(SymbolFont.WINGDINGS_2, 0xB8, 0x25CA); //  -> ◊
        put(SymbolFont.WINGDINGS_2, 0xBA, 0x25D6); //  -> ◖
        put(SymbolFont.WINGDINGS_2, 0xBB, 0x25D7); //  -> ◗
        put(SymbolFont.WINGDINGS_2, 0xEB, 0x2736); //  -> ✶
        put(SymbolFont.WINGDINGS_2, 0xF0, 0x2739); //  -> ✹
        put(SymbolFont.WINGDINGS_2, 0xF3, 0x272F); //  -> ✯

        // --- Wingdings 3 (browser-safe subset, 13 entries) ---
        put(SymbolFont.WINGDINGS_3, 0x58, 0x21EA); // X -> ⇪
        put(SymbolFont.WINGDINGS_3, 0x70, 0x25B2); // p -> ▲
        put(SymbolFont.WINGDINGS_3, 0x71, 0x25BC); // q -> ▼
        put(SymbolFont.WINGDINGS_3, 0x72, 0x25B3); // r -> △
        put(SymbolFont.WINGDINGS_3, 0x73, 0x25BD); // s -> ▽
        put(SymbolFont.WINGDINGS_3, 0x74, 0x25C0); // t -> ◀
        put(SymbolFont.WINGDINGS_3, 0x75, 0x25B6); // u -> ▶
        put(SymbolFont.WINGDINGS_3, 0x76, 0x25C1); // v -> ◁
        put(SymbolFont.WINGDINGS_3, 0x77, 0x25B7); // w -> ▷
        put(SymbolFont.WINGDINGS_3, 0x78, 0x25E3); // x -> ◣
        put(SymbolFont.WINGDINGS_3, 0x79, 0x25E2); // y -> ◢
        put(SymbolFont.WINGDINGS_3, 0x7A, 0x25E4); // z -> ◤
        put(SymbolFont.WINGDINGS_3, 0x7B, 0x25E5); // { -> ◥

        // --- Webdings (browser-safe subset) ---
        put(SymbolFont.WEBDINGS, 0x61, 0x2714); // a -> ✔
        put(SymbolFont.WEBDINGS, 0x73, 0x2753); // s -> ❓
        put(SymbolFont.WEBDINGS, 0x77, 0x26F3); // w -> ⛳
        put(SymbolFont.WEBDINGS, 0x87, 0x26F7); //  -> ⛷
        put(SymbolFont.WEBDINGS, 0x98, 0x272F); //  -> ✯
        put(SymbolFont.WEBDINGS, 0xD9, 0x2601); // Ù -> ☁
        put(SymbolFont.WEBDINGS, 0xE8, 0x24C5); // è -> Ⓟ
        put(SymbolFont.WEBDINGS, 0xE9, 0x267F); // é -> ♿

        // --- Symbol (browser-safe subset) ---
        put(SymbolFont.SYMBOL, 0xA7, 0x2663); // § -> ♣
        put(SymbolFont.SYMBOL, 0xA8, 0x2666); // ¨ -> ♦
        put(SymbolFont.SYMBOL, 0xA9, 0x2665); // © -> ♥
        put(SymbolFont.SYMBOL, 0xAA, 0x2660); // ª -> ♠
        put(SymbolFont.SYMBOL, 0xAB, 0x2194); // « -> ↔
        put(SymbolFont.SYMBOL, 0xAC, 0x2190); // ¬ -> ←
        put(SymbolFont.SYMBOL, 0xAD, 0x2191); // ­ -> ↑
        put(SymbolFont.SYMBOL, 0xAE, 0x2192); // ® -> →
        put(SymbolFont.SYMBOL, 0xAF, 0x2193); // ¯ -> ↓
        put(SymbolFont.SYMBOL, 0xB7, 0x2022); // · -> •
        put(SymbolFont.SYMBOL, 0xBF, 0x21B5); // ¿ -> ↵
        put(SymbolFont.SYMBOL, 0xDB, 0x21D4); // Û -> ⇔
        put(SymbolFont.SYMBOL, 0xDC, 0x21D0); // Ü -> ⇐
        put(SymbolFont.SYMBOL, 0xDD, 0x21D1); // Ý -> ⇑
        put(SymbolFont.SYMBOL, 0xDE, 0x21D2); // Þ -> ⇒
        put(SymbolFont.SYMBOL, 0xDF, 0x21D3); // ß -> ⇓
        put(SymbolFont.SYMBOL, 0xE0, 0x25CA); // à -> ◊
    }

    private SymbolFontMapper() {
    }

    private static void put(SymbolFont font, int sourceByte, int target) {
        MAPS[font.ordinal()][sourceByte] = target;
    }

    /**
     * Rewrites the symbol-font PUA characters of every text chunk on a page.
     *
     * @param contents the page contents, modified in place
     */
    public static void normalizeSymbolFontText(List<IObject> contents) {
        for (IObject object : contents) {
            if (object instanceof TextChunk) {
                normalizeSymbolFontText((TextChunk) object);
            }
        }
    }

    /**
     * Rewrites the symbol-font PUA characters of a single text chunk, preserving
     * the character count so that per-symbol geometry stays aligned.
     *
     * @param textChunk the chunk to normalize, modified in place
     */
    public static void normalizeSymbolFontText(TextChunk textChunk) {
        String value = textChunk.getValue();
        if (value == null || value.isEmpty()) {
            return;
        }
        SymbolFont font = detectSymbolFont(textChunk.getFontName());
        if (font == null) {
            return;
        }
        int[] table = MAPS[font.ordinal()];
        boolean changed = false;
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            if (ch < PUA_MIN || ch > PUA_MAX) {
                continue;
            }
            int target = table[ch - PUA_BASE];
            char replacement = target != 0 ? (char) target : FALLBACK_BULLET;
            if (replacement != ch) {
                chars[i] = replacement;
                changed = true;
            }
        }
        if (changed) {
            textChunk.setValue(new String(chars));
        }
    }

    /**
     * Maps a font name (already normalized by the extractor, i.e. without the
     * subset prefix such as {@code "ABCDEE+"}) to its symbol-font family.
     *
     * @param fontName the chunk font name, possibly {@code null}
     * @return the recognized family, or {@code null} for a normal text font
     */
    private static SymbolFont detectSymbolFont(String fontName) {
        if (fontName == null || fontName.isEmpty()) {
            return null;
        }
        String name = fontName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (name.contains("wingdings3")) {
            return SymbolFont.WINGDINGS_3;
        }
        if (name.contains("wingdings2")) {
            return SymbolFont.WINGDINGS_2;
        }
        if (name.contains("wingdings")) {
            return SymbolFont.WINGDINGS;
        }
        if (name.contains("webdings")) {
            return SymbolFont.WEBDINGS;
        }
        if (name.contains("zapfdingbats") || name.contains("zapf dingbats") || name.contains("dingbats")) {
            return SymbolFont.ZAPF_DINGBATS;
        }
        if (name.contains("symbol")) {
            return SymbolFont.SYMBOL;
        }
        return null;
    }
}
