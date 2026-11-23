package com.moddingfromamod.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** an earlier issue's linter: which @e selectors check every entity even with the tag index. */
class SelectorCostTest {

    @Test
    @DisplayName("tagged or positioned is cheap; type=, scores= and bare @e are not")
    void classifies() {
        assertEquals(List.of(), SelectorCost.costly("execute as @e[tag=wishmob_trex] at @s run say hi"));
        assertEquals(List.of(), SelectorCost.costly("@e[tag=${TAG},scores={mfam_imp=1..}]"));
        assertEquals(List.of(), SelectorCost.costly("@e[distance=..4,tag=!wishmob_trex,type=!minecraft:item]"));
        assertEquals(List.of(), SelectorCost.costly("@e[x=0,y=0,z=0,dx=5,dy=5,dz=5]"));
        assertEquals(List.of("@e[scores={mfam_grow=0}]"), SelectorCost.costly("execute as @e[scores={mfam_grow=0}] run x"));
        assertEquals(List.of("@e[type=minecraft:cow]"), SelectorCost.costly("kill @e[type=minecraft:cow]"));
        assertEquals(List.of("@e[tag=!wishmob]"), SelectorCost.costly("@e[tag=!wishmob]"));
        assertEquals(List.of("@e"), SelectorCost.costly("kill @e"));
        assertEquals(List.of(), SelectorCost.costly("@a[tag=x] @p @s @eggs"));
        assertEquals(List.of(), SelectorCost.costly("# a bare @e only sees one dimension\n  # @e[type=cow] too\nsay hi"));
        assertEquals(List.of("@e[nbt={Tags:[\"tag=x\"]}]"), SelectorCost.costly("@e[nbt={Tags:[\"tag=x\"]}]"));
        assertTrue(SelectorCost.advice("say nothing").isEmpty());
        assertTrue(SelectorCost.advice("@e[scores={a=1}]").contains("tag @s add"));
    }

    /**
     * The shipped templates, as a RATCHET: each template's count of costly selectors may not grow.
     * Lower a number when a template is fixed; a new template starts at its own count, and a
     * template that needs one says why in a comment beside it.
     */
    @Test
    @DisplayName("no shipped template gains a selector that checks every entity")
    void templatesDoNotGrow() throws Exception {
        Map<String, Integer> now = new TreeMap<>();
        for (String dir : List.of("src/main/resources/effects", "src/main/resources/items")) {
            try (var files = Files.list(Path.of(dir))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".cbs")).sorted().toList()) {
                    int n = SelectorCost.costly(Files.readString(f)).size();
                    if (n > 0) {
                        now.put(f.getFileName().toString(), n);
                    }
                }
            }
        }
        assertEquals(ALLOWED, now, "a template's count of @e selectors with no tag and no position changed: "
                + "a new one checks every entity in the world each time it runs (#1871, SelectorCost). "
                + "Select by a tag instead; if a template got CHEAPER, lower its number here.");
    }

    /**
     * The STARTERS, as a world installs them: each frozen zip in the jar, put through the install
     * rewrite ({@code VictimTagFix}), must run no selector that checks every entity — but for the
     * cookie's upgrade sweep. A starter is compiled once and shipped as a zip with no source, so the
     * template ratchet above never sees it; this does, and a new starter is held to it from day one.
     */
    @Test
    @DisplayName("no starter pack, as installed, checks every entity")
    void startersAsInstalledDoNotScanEverything() throws Exception {
        Map<String, List<String>> found = new TreeMap<>();
        try (var dirs = Files.list(Path.of("src/main/resources/starter-library/datapacks"))) {
            for (Path d : dirs.sorted().toList()) {
                Path zip = d.resolve(d.getFileName() + ".zip");
                if (!Files.isRegularFile(zip)) {
                    continue;
                }
                try (var z = new java.util.zip.ZipFile(zip.toFile())) {
                    for (var e : java.util.Collections.list(z.entries())) {
                        if (!e.getName().endsWith(".mcfunction")) {
                            continue;
                        }
                        String text = new String(z.getInputStream(e).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        String installed = com.moddingfromamod.datapack.VictimTagFix.fix(text, new java.util.ArrayList<>());
                        List<String> c = SelectorCost.costly(installed);
                        if (!c.isEmpty()) {
                            found.put(d.getFileName() + "/" + e.getName().replaceAll(".*/", ""), c);
                        }
                    }
                }
            }
        }
        assertEquals(Map.of("tiny_cookie_grow/tick.mcfunction", List.of("@e[scores={mfam_grow=0..},tag=!mfam_grow_on]"),
                        "tiny_cookie_grow/reset.mcfunction", List.of("@e[scores={mfam_grow=0..},tag=!mfam_grow_on]")),
                found, "a starter pack, as installed, runs a selector that checks every entity in the world "
                        + "(#1871): select by a tag. The cookie's two are its upgrade sweep (load, and every 200 ticks).");
    }

    /**
     * The ONE left, and why: grow.cbs's upgrade sweep (`@e[scores={mfam_grow=0..},tag=!mfam_grow_on]`)
     * adopts an entity part-way through a timer written by the pre-#1871 template, so it does not
     * stay tiny for ever. It runs at load and every 200 ticks, not every tick. impulse.cbs went 2 → 0
     * and grow.cbs 2 → 1 when both started tagging their victims (2026-11-09).
     */
    private static final Map<String, Integer> ALLOWED = new TreeMap<>(Map.of("grow.cbs", 1));
}
