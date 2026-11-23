package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>No boxes in a child's chat</b> — every expectation here was read off a PHOTOGRAPH of a
 * stock 1.21.1 client drawing these exact characters (the PR carries it), not assumed.
 */
class ChildGlyphsTest {

    @Test
    void theTableShipsAndIsNotEmpty() {
        assertTrue(ChildGlyphs.rangeCount() > 500,
                "glyphs/stock-client.ranges is missing or truncated — regenerate it with "
                        + "tools/stock-glyph-coverage.py. (An empty table fails OPEN, so without "
                        + "this test a packaging fault would silently let every box through.)");
    }

    @Test
    void theLiveLineLosesItsBoxesAndKeepsItsSun() {
        // The owner's screenshot, 26.3: "☀️🆕" drew a sun, a dashed "VS16" box and a boxed NEW.
        String live = "✨ Your sky is locked to daytime forever! ☀️🆕";
        assertEquals("✨ Your sky is locked to daytime forever! ☀", ChildGlyphs.clean(live));
    }

    @Test
    void whatThePhotographShowedAsABoxIsDropped() {
        for (int cp : new int[] {
                0xFE0F,             // VS16 — the "☀️" box
                0xFE0E,             // VS15
                0x200D,             // zero-width joiner — "ZWJ" box
                0x1F1EC, 0x1F1E7,   // regional indicators — 🇬🇧 drew as [G][B]
                0x1F3FB,            // a skin tone after 👍 drew as a dotted swatch
                0x20E3,             // the keycap, an empty rounded box on its own
                0xE0067,            // a tag character, the tail of a subdivision flag
                0x1F195, 0x1F197,   // 🆕 🆗 — real glyphs, but a 3-pixel word in a box
                0x0007}) {          // a control code
            assertFalse(ChildGlyphs.drawable(cp), String.format("U+%04X draws as a box", cp));
        }
    }

    @Test
    void whatThePhotographShowedAsAGlyphIsKept() {
        // The code's own ✨ 🪄 👉 🏗 🤔 😴 👂 👍 👎 💡, and the plain symbols the agent likes.
        String kept = "✨🪄👉🏗🤔😴👂👍👎💡☀→—⭐🔥❤éÆ";
        assertSame(kept, ChildGlyphs.clean(kept),
                "a string with nothing to remove must come back as the same instance");
    }

    @Test
    void plainWordsCostNothing() {
        String s = "Want to change it? Just wish again!";
        assertSame(s, ChildGlyphs.clean(s));
    }

    @Test
    void aRemovedGlyphLeavesNoDoubleSpaceAndNoTrailingOne() {
        assertEquals("sun ☀ end", ChildGlyphs.clean("sun ☀️ ‍ end"));
        assertEquals("done", ChildGlyphs.clean("done ️"));
        assertEquals("line one\nline two", ChildGlyphs.clean("line one\nline two"));
        assertEquals("", ChildGlyphs.clean("️"));
    }

    @Test
    void everyChildVisibleArgumentIsCleanedAndNothingElseIsTouched() {
        var args = com.google.gson.JsonParser.parseString("{"
                + "\"say\":\"It's daytime FOREVER! ☀️🆕\","
                + "\"label\":\"sun☀️\","          // a photo FILE name — nobody reads it
                + "\"claims\":[{\"claim\":\"sun ☀️\",\"works\":true}]}").getAsJsonObject();
        assertEquals(1, ChildGlyphs.cleanArgs("finish_building", args));
        assertEquals("It's daytime FOREVER! ☀", args.get("say").getAsString());
        assertEquals("sun☀\uFE0F", args.get("label").getAsString(),
                "only the fields SafetyTextSweep.FIELDS names are a child's to read");

        var plan = com.google.gson.JsonParser.parseString(
                "{\"steps\":[\"Lock the sun ☀️\",\"Done\"]}").getAsJsonObject();
        assertEquals(1, ChildGlyphs.cleanArgs("plan_wish", plan));
        assertEquals("Lock the sun ☀", plan.getAsJsonArray("steps").get(0).getAsString());

        var cmd = com.google.gson.JsonParser.parseString(
                "{\"command\":[\"tellraw @a \\\"hi ☀️\\\"\",\"time set noon\"]}")
                .getAsJsonObject();
        assertEquals(1, ChildGlyphs.cleanArgs("run_command", cmd));
        assertEquals("tellraw @a \"hi ☀\"", cmd.getAsJsonArray("command").get(0).getAsString());

        var item = com.google.gson.JsonParser.parseString(
                "{\"variants\":[{\"name\":\"Red ❤️\"}]}").getAsJsonObject();
        assertEquals(1, ChildGlyphs.cleanArgs("make_item", item));
        assertEquals("Red ❤", item.getAsJsonArray("variants").get(0).getAsJsonObject()
                .get("name").getAsString());

        var untouched = com.google.gson.JsonParser.parseString("{\"message\":\"hi\"}")
                .getAsJsonObject();
        assertEquals(0, ChildGlyphs.cleanArgs("notify_player", untouched));
    }

    /**
     * <b>Our own words, too.</b> Eleven shipped literals carried U+FE0F — "🏗️" on the progress
     * bar, "[↩️ undo]" under every wish, "❤️"/"⚔️" on the wish card — and each drew the same
     * dashed box. The runtime filter covers what the AGENT writes; this covers what WE write, by
     * file and line, so a new literal cannot bring the box back.
     */
    @Test
    void noShippedStringLiteralCarriesAGlyphAStockClientCannotDraw() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (int[] lit : literals(src)) {
                    String text = src.substring(lit[0], lit[1]);
                    text.codePoints()
                            .filter(cp -> cp >= 0x80 && !ChildGlyphs.drawable(cp))
                            .findFirst()
                            .ifPresent(cp -> offenders.add(String.format("%s:%d U+%04X in \"%s\"",
                                    p, 1 + src.substring(0, lit[0]).chars()
                                            .filter(c -> c == '\n').count(),
                                    cp, text.length() > 60 ? text.substring(0, 60) : text)));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "string literals a stock client draws as a box — drop "
                + "the U+FE0F (the base character draws on its own):\n  "
                + String.join("\n  ", offenders));
    }

    /** [start, end) of every string literal body, skipping comments and char literals. */
    private static List<int[]> literals(String s) {
        List<int[]> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (s.startsWith("//", i)) {
                int j = s.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int j = s.indexOf("\"\"\"", i + 3);
                j = j < 0 ? n : j;
                out.add(new int[] {i + 3, j});
                i = Math.min(n, j + 3);
            } else if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                char q = s.charAt(i);
                int j = i + 1;
                while (j < n && s.charAt(j) != q && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                if (q == '"') {
                    out.add(new int[] {i + 1, j});
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }
}
