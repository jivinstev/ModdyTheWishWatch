package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>No test enumerates a product that can keep growing</b>. Gate A had grown to
 * 10-12 minutes, most of it five tests that each walked a full product: every SUBSET of a building's outside options
 * (doubling with each new option), every house type x floors x roof x door x windows x subset. A nightly that grows the
 * same way gets switched off, so the rule holds everywhere.
 *
 * <p>Two shapes are refused in {@code src/test/java}, by file and line:
 * <ul>
 *   <li><b>a subset-mask loop</b>: a {@code for} whose condition shifts 1 by a non-literal ({@code mask < 1 << ids.size()}),
 *       i.e. every subset of something;</li>
 *   <li><b>three or more nested loops over registries</b>: {@code for (x : SOME_REGISTRY)} (an UPPER_CASE static field, its
 *       {@code keySet()}/{@code values()}, or an enum's {@code values()}) inside two others.</li>
 * </ul>
 * What to do instead: independence by design (each option alone plus a pure check that the options cannot interfere:
 * {@code wishbuilder/OutsideZones}), or an all-pairs sample ({@link AllPairs}), or a budget the nightly owns
 * ({@link GateTier}). A loop that IS bounded (a fixed table that cannot grow) says so on its line or the line above:
 * {@code // gate-a: bounded - <why>}. Every rule carries the code it forbids, and the sweep is proven to fire on it.
 */
class GrowingProductSweepTest {

    static final Path ROOT = Path.of("src/test/java");

    static final String ALLOW = "gate-a: bounded";

    /** {@code for (...; i < 1 << n; ...)}: every subset of n things. A literal shift ({@code 1 << 22}) is a size, not a set. */
    static final Pattern SUBSET_MASK = Pattern.compile("\\bfor\\s*\\([^;]*;[^;]*<\\s*\\(?\\s*1L?\\s*<<\\s*\\(?\\s*[A-Za-z_]");

    /** {@code for (T x : REGISTRY)} / {@code REGISTRY.keySet()} / {@code Enum.values()} / {@code Owner.REGISTRY}. */
    static final Pattern REGISTRY_LOOP = Pattern.compile(
            "\\bfor\\s*\\([^:;()]+:\\s*(?:[A-Za-z_][\\w.]*\\.)?(?:[A-Z][A-Z0-9_]{2,}(?:\\.(?:keySet|values|entrySet)\\(\\))?|[A-Z]\\w*\\.values\\(\\))\\s*\\)");

    static final String PLANTED_SUBSET = """
            for (int mask = 0; mask < 1 << ids.size(); mask++) {
                build(mask);
            }
            """;

    static final String PLANTED_PRODUCT = """
            for (BuilderHouses.Simple s : BuilderHouses.SIMPLE) {
                for (String roof : BuilderHouses.ROOFS.keySet()) {
                    for (String door : BuilderHouses.DOORS.keySet()) {
                        walk(s, roof, door);
                    }
                }
            }
            """;

    static final String ALLOWED_PRODUCT = """
            for (Corner c : Corner.values()) {
                for (Axis a : Axis.values()) {
                    // gate-a: bounded - a cube has 8 corners, 3 axes and 2 signs, forever
                    for (Sign s : Sign.values()) {
                        check(c, a, s);
                    }
                }
            }
            """;

    /** Each violation in {@code text}, as "line N: what". */
    static List<String> violations(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        Deque<Integer> open = new ArrayDeque<>();   // brace depth inside each enclosing registry loop
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean allowed = line.contains(ALLOW) || (i > 0 && lines[i - 1].contains(ALLOW));
            String code = line.replaceAll("//.*", "");
            if (SUBSET_MASK.matcher(code).find() && !allowed) {
                out.add("line " + (i + 1) + ": a loop over every subset (" + line.strip() + ")");
            }
            boolean registry = REGISTRY_LOOP.matcher(code).find();
            if (registry && open.size() >= 2 && !allowed) {
                out.add("line " + (i + 1) + ": a third nested loop over a registry (" + line.strip() + ")");
            }
            for (char c : code.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    while (!open.isEmpty() && depth < open.peek()) {
                        open.pop();
                    }
                }
            }
            if (registry && code.contains("{")) {
                open.push(depth);
            }
        }
        return out;
    }

    @Test
    void theSweepCatchesWhatItForbids() {
        assertEquals(1, violations(PLANTED_SUBSET).size(), "a subset-mask loop");
        assertEquals(1, violations(PLANTED_PRODUCT).size(), "three nested registry loops");
        assertEquals(List.of(), violations(ALLOWED_PRODUCT), "a loop marked bounded is allowed");
        assertEquals(List.of(), violations("StringBuilder sb = new StringBuilder(1 << 22);\nfor (int i = 0; i < 1 << 4; i++) {}"),
                "a literal shift is a size, not every subset");
        assertEquals(List.of(), violations("""
                for (Type t : TYPES) {
                    use(t);
                }
                for (String r : ROOFS.keySet()) {
                    for (String d : DOORS.keySet()) {
                        use(r, d);
                    }
                }
                """), "sibling loops are not nested");
    }

    @Test
    void noTestEnumeratesAGrowingProduct() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (p.getFileName().toString().equals("GrowingProductSweepTest.java")) {
                    continue;   // its planted samples
                }
                violations(Files.readString(p, StandardCharsets.UTF_8)).forEach(v -> bad.add(SourceRoots.relativize(ROOT, p) + " " + v));
            }
        }
        assertTrue(bad.isEmpty(), "a test enumerates a product that can keep growing (#2222). Prove the options independent, "
                + "sample all-pairs (testing/AllPairs), or give it to the nightly (testing/GateTier); a fixed table says '"
                + ALLOW + " - <why>':\n" + String.join("\n", bad));
    }
}
