package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A creature a GameTest makes is taken back when the test ends</b> (2026-11-15,).
 *
 * <p>{@code SpeciesRegistry.put} refuses a NEW creature once the registry holds 500, and one Gate B server runs every
 * test. 38 GameTest classes put their own creatures and never took them back, so a full run filled the registry and
 * the tests late in it found their creatures refused ("species registry full"), reading downstream as a creature with
 * no species. A GameTest makes a creature through {@link SpeciesForTest} — {@code put} (memory only) or
 * {@code putSaved} (the real write path, for a test ABOUT saving, shelves or owners) — and both are undone on pass,
 * fail or timeout. A file that must call the registry itself does its own cleanup with
 * {@code SpeciesRegistry.restoreForTest}, and says why in {@link #ALLOWED}.
 */
class SpeciesCleanupRuleTest {

    /** A direct write to the registry: put or putShared, qualified or not. */
    static final Pattern DIRECT = Pattern.compile("\\bSpeciesRegistry\\.put(Shared)?\\(");

    /** File name → why it may call the registry directly. Kept empty unless a test truly cannot go through the kit. */
    static final Map<String, String> ALLOWED = Map.of();

    static boolean breaks(String src) {
        return DIRECT.matcher(src).find() && !src.contains("SpeciesRegistry.restoreForTest(");
    }

    @Test
    @DisplayName("no GameTest puts a creature it never takes back")
    void everyGameTestCreatureIsUndone() throws Exception {
        List<String> bad = new ArrayList<>();
        int kit = 0;
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith("GameTest.java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (src.contains("SpeciesForTest.")) {
                    kit++;
                }
                if (breaks(src) && !ALLOWED.containsKey(f.getFileName().toString())) {
                    bad.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(bad.isEmpty(), "these GameTests call SpeciesRegistry.put( and never take the creature back, so a full "
                + "Gate B fills the 500-creature registry -- use SpeciesForTest.put (memory only) or .putSaved (the real "
                + "path, undone at the end): " + bad);
        assertTrue(kit >= 30, "only " + kit + " GameTests make creatures through SpeciesForTest: the sweep has stopped "
                + "reaching them");
    }

    @Test
    @DisplayName("the rule catches what it forbids and lets the cleaned-up forms through")
    void theRuleBites() {
        assertTrue(breaks("SpeciesRegistry.put(server, s);"));
        assertTrue(breaks("com.moddingfromamod.species.SpeciesRegistry.putShared(server, s);"));
        assertTrue(!breaks("SpeciesForTest.put(helper, s);"));
        assertTrue(!breaks("SpeciesRegistry.putForTest(List.of(s));"));
        assertTrue(!breaks("SpeciesRegistry.put(server, s); ... SpeciesRegistry.restoreForTest(server, id, null);"));
        assertEquals(0, ALLOWED.size(), "an allowlisted file must say why here, and then this count moves with it");
    }
}
