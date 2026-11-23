package com.moddingfromamod.runtime.gate;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclaredPaletteTest {

    /** Colours are packed RGB, and must land in the buckets SculptCritique reports. */
    private static final int GREEN = 0x2E8B22;
    private static final int TAN = 0xD2A15C;
    private static final int RED = 0xC0261E;

    private static CreatureSculpt.Part box(String name, int colour, int size) {
        return new CreatureSculpt.Part(name, null, new float[] {0, 0, 0},
                new float[] {0, 0, 0}, new float[] {size, size, size}, colour);
    }

    private static com.google.gson.JsonObject args(String palette) {
        return JsonParser.parseString("{\"palette\":" + palette + "}").getAsJsonObject();
    }

    @Test
    @DisplayName("a promise that was kept passes")
    void kept() {
        var r = DeclaredPalette.check(args("[{\"colour\":\"green\",\"percent\":50},"
                + "{\"colour\":\"tan\",\"percent\":50}]"),
                List.of(box("shell", GREEN, 8), box("belly", TAN, 8)));
        assertTrue(r.allMet(), r.describe());
    }

    @Test
    @DisplayName("the failure that actually happened: a colour promised and then abandoned")
    void abandoned() {
        // Declared half tan; built a trace of it. This is the six-revision Spikor drift.
        // Green is kept close to its declared share on purpose, so the assertion is about the
        // ABANDONED colour and cannot pass for the wrong reason.
        var r = DeclaredPalette.check(args("[{\"colour\":\"green\",\"percent\":45},"
                + "{\"colour\":\"tan\",\"percent\":45}]"),
                List.of(box("shell", GREEN, 8), box("skin", RED, 7), box("trim", TAN, 2)));
        assertFalse(r.allMet());
        assertEquals(1, r.missed().size(), "only the abandoned colour should miss: " + r.describe());
        assertEquals("tan/orange", r.missed().get(0).colour());
        assertTrue(r.describe().contains("MISSED"));
    }

    @Test
    @DisplayName("percentages are accepted however the agent writes them")
    void formats() {
        Map<String, Double> a = DeclaredPalette.parse(
                JsonParser.parseString("[{\"colour\":\"green\",\"percent\":45}]"));
        Map<String, Double> b = DeclaredPalette.parse(
                JsonParser.parseString("[{\"color\":\"green\",\"percent\":\"45%\"}]"));
        Map<String, Double> c = DeclaredPalette.parse(
                JsonParser.parseString("[{\"colour\":\"green\",\"pct\":0.45}]"));
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    @DisplayName("colour words map onto the buckets the critique actually reports")
    void buckets() {
        // "brown" must not score zero just because the reported bucket is called tan/orange.
        var r = DeclaredPalette.check(args("[{\"colour\":\"brown\",\"percent\":100}]"),
                List.of(box("body", TAN, 8)));
        assertTrue(r.allMet(), r.describe());
    }

    @Test
    @DisplayName("declaring nothing is silent, not a failure")
    void silent() {
        assertTrue(DeclaredPalette.check(args("[]"), List.of(box("b", GREEN, 8))).isEmpty());
        assertTrue(DeclaredPalette.check(null, List.of(box("b", GREEN, 8))).isEmpty());
        assertEquals("", DeclaredPalette.check(args("[]"), List.of(box("b", GREEN, 8)))
                .describe());
    }

    @Test
    @DisplayName("tolerance is loose enough that an honest box build is not nagged")
    void honestBuildPasses() {
        // Boxes cannot hit a percentage exactly; 45% declared arriving at 57% is fine.
        var r = DeclaredPalette.check(args("[{\"colour\":\"green\",\"percent\":45}]"),
                List.of(box("a", GREEN, 8), box("b", RED, 7)));
        assertTrue(r.allMet(), r.describe());
    }

    @Test
    @DisplayName("REAL BUG: a declared 1% is one percent, not one hundred")
    void onePercentIsNotEverything() {
        // `pct > 1.0` left a declared 1 as 1.0 = 100%, so real declarations were reported as
        // "said 100%, built 0% MISSED" on creatures that were entirely correct.
        var r = DeclaredPalette.check(args("[{\"colour\":\"green\",\"percent\":99},"
                        + "{\"colour\":\"red\",\"percent\":1}]"),
                List.of(box("body", GREEN, 10), box("dot", RED, 2)));
        assertTrue(r.allMet(), r.describe());
    }

    @Test
    @DisplayName("REAL BUG: a hex declaration is a colour")
    void hexIsAColour() {
        // The agent declares exact hexes constantly. Words-only matching scored every one at 0%
        // built -- one creature "missed" all six declared colours while looking exactly right.
        var r = DeclaredPalette.check(args("[{\"colour\":\"#2E8B22\",\"percent\":100}]"),
                List.of(box("body", GREEN, 8)));
        assertTrue(r.allMet(), r.describe());
    }

    @Test
    @DisplayName("common colour words outside the bucket names still resolve")
    void widerVocabulary() {
        for (String word : new String[] {"lime", "gold", "magenta", "navy", "teal", "silver"}) {
            var r = DeclaredPalette.parse(com.google.gson.JsonParser.parseString(
                    "[{\"colour\":\"" + word + "\",\"percent\":50}]"));
            assertFalse(r.isEmpty(), word + " should map to a bucket");
            assertFalse(r.containsKey(word), word + " should have been bucketed, not kept raw");
        }
    }

    /**
     * <b>The check must not disagree with itself about a word the engine defines</b>.
     *
     * <p>A part painted "black" is {@code #404040}, which the critique buckets as grey — so every
     * creature that declared black and painted black was told black had vanished, and re-sculpted.
     * "gold" was yellow when declared and tan/orange when built. Every paintable colour name,
     * declared at 100% on a creature painted in it, must be met.
     */
    @Test
    @DisplayName("every colour a part can be painted in, declared and painted, is met")
    void everyPaintableNameAgreesWithItself() {
        for (String name : com.moddingfromamod.species.Species.colourNames()) {
            var parts = List.of(box("body", com.moddingfromamod.species.Species.parseColour(name), 8));
            var r = DeclaredPalette.check(args("[{\"colour\":\"" + name + "\",\"percent\":100}]"),
                    parts);
            assertTrue(r.allMet(), name + ": " + r.describe());
        }
    }

    @Test
    @DisplayName("a shade or an underscore does not turn a colour into no colour")
    void shadesAndSpellings() {
        for (String word : new String[] {"dark grey", "dark_gray", "light-gray", "hot pink",
                "dark_red", "dark green", "Light Blue", "bright yellow"}) {
            var r = DeclaredPalette.parse(JsonParser.parseString(
                    "[{\"colour\":\"" + word + "\",\"percent\":50}]"));
            String bucket = r.keySet().iterator().next();
            assertTrue(List.of("black", "white", "grey", "red", "tan/orange", "yellow", "green",
                    "cyan", "blue", "purple/pink").contains(bucket), word + " -> " + bucket);
        }
    }

    @Test
    @DisplayName("an accent built under the report's 4% floor is small, not vanished")
    void aSmallAccentIsNotVanished() {
        // 3.5% red eyes on a green body, declared at 10%: within tolerance and present.
        var parts = new java.util.ArrayList<CreatureSculpt.Part>();
        parts.add(new CreatureSculpt.Part("body", null, new float[] {0, 0, 0}, new float[] {0, 0, 0},
                new float[] {1000, 1, 1}, GREEN));
        parts.add(new CreatureSculpt.Part("eyes", null, new float[] {0, 0, 0}, new float[] {0, 0, 0},
                new float[] {36, 1, 1}, RED));
        var r = DeclaredPalette.compare(Map.of("green", 0.90, "red", 0.10), parts);
        assertTrue(r.allMet(), r.describe());
    }

    /**
     * <b>: the agent was told, and carried on.</b>
     *
     * <p>The gorilla is the real case: declared black 70% / grey 12%, built grey 88% / black 11% —
     * a wholesale inversion of its own plan. It was told, immediately, and did not correct it. Part
     * of that was a checker crying wolf (with the grader bugs of the day it flagged ~47% of
     * declarations; corrected, ~9%). The other part is that "black: said 70%, built 11%" is a true
     * sentence which does not say what to DO — re-deriving which of forty boxes to repaint from a
     * percentage is work the agent has to redo from scratch, and can get wrong.
     */
    @Test
    @DisplayName("a missed colour NAMES the boxes to repaint, not just a percentage")
    void theFeedbackSaysWhichPartsToRepaint() {
        var parts = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-10,0,-6],"to":[10,24,6],"colour":"grey"},
                 {"name":"armL","from":[-14,10,-3],"to":[-10,22,3],"colour":"grey"},
                 {"name":"armR","from":[10,10,-3],"to":[14,22,3],"colour":"grey"},
                 {"name":"nose","from":[-2,18,-8],"to":[2,21,-6],"colour":"black"}]""")
                .getAsJsonArray());
        var report = DeclaredPalette.compare(
                Map.of("black", 0.70, "grey", 0.12), parts);

        String directive = report.describe(parts);
        assertTrue(directive.contains("MISSED"), directive);
        assertTrue(directive.contains("grey took black's place"), directive);
        // The BIG grey parts, largest first -- those are the edit. Naming the nose would be true
        // and useless.
        assertTrue(directive.contains("body"), directive);
        assertTrue(directive.indexOf("body") < directive.indexOf("armL"),
                "the biggest offender must come first: " + directive);
    }

    @Test
    @DisplayName("a creature that matched its plan gets no repaint advice at all")
    void nothingIsSaidWhenNothingIsWrong() {
        var parts = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-8,0,-6],"to":[8,20,6],"colour":"green"},
                 {"name":"belly","from":[-5,4,-14],"to":[5,16,-6],"colour":"white"}]""")
                .getAsJsonArray());
        // Keys are BUCKET names, as `parse` produces — comparing against a raw colour word the
        // bucketer folds elsewhere would fail for a reason that has nothing to do with the build.
        var report = DeclaredPalette.compare(Map.of("green", 0.80, "white", 0.20), parts);
        assertEquals(report.describe(), report.describe(parts),
                "a build that met its plan must read exactly as it did before");
    }

    @Test
    @DisplayName("an OVER-built colour gets no repaint list, because there is nothing to paint to")
    void overBuiltColoursAreNotGivenAdvice() {
        // "You built more white than you said" has no instruction of this shape: the fix is to
        // paint white parts something else, and which else is a design decision, not arithmetic.
        var parts = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-8,0,-6],"to":[8,20,6],"colour":"white"},
                 {"name":"foot","from":[-3,0,-3],"to":[3,2,3],"colour":"red"}]""")
                .getAsJsonArray());
        var report = DeclaredPalette.compare(Map.of("white", 0.20, "red", 0.80), parts);
        String directive = report.describe(parts);
        assertFalse(directive.contains("took white's place"), directive);
    }

    @Test
    @DisplayName("no parts, or no declaration, is never a crash")
    void degenerateInput() {
        var report = DeclaredPalette.compare(Map.of("black", 0.7), List.of());
        assertEquals("", report.describe(null));
        var parts = CreatureSculpt.parseParts(JsonParser.parseString(
                "[{\"name\":\"body\",\"from\":[0,0,0],\"to\":[4,4,4],\"colour\":\"grey\"}]")
                .getAsJsonArray());
        assertNotNull(DeclaredPalette.compare(Map.of(), parts).describe(parts));
    }
}
