package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import com.moddingfromamod.selector.SelectorCost;
import org.junit.jupiter.api.Test;

/**
 * a grow or impulse pack compiled before its victims were tagged, rewritten on install, must
 * say exactly what the FIXED template renders — the same contract {@code ImpulseTelegraphFixTest}
 * holds to. The pre-#1871 templates are kept verbatim ({@code victim-tag-1871/}); the shipped
 * starter cookie is run through the rewrite as it really is.
 */
class VictimTagFixTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = VictimTagFixTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("no resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    private static String fill(String template, Map<String, String> p) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = p.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException("unfilled " + m.group(1));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        return m.appendTail(sb).toString();
    }

    /** One block's commands as the compiler emits them: no comments, no leading slash. */
    private static List<String> block(String text, String header) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String raw : text.split("\n")) {
            String l = raw.strip();
            if (l.equals(header)) {
                in = true;
                continue;
            }
            if (in && l.equals("end")) {
                break;
            }
            if (!in || l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            out.add(l.startsWith("/") ? l.substring(1) : l);
        }
        return out;
    }

    private static List<String> fixed(List<String> commands, List<String> notes) {
        String out = VictimTagFix.fix(String.join("\n", commands), notes);
        return List.of(out.split("\n"));
    }

    // ---- grow (the starter cookie, and every grow item) ----

    private static Map<String, String> grow(String target) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("TARGET", target);
        p.put("SCALE", "0.3");
        p.put("GROW_TICKS", "1200");
        return p;
    }

    @Test
    void anOldGrowPackBecomesWhatTheFixedTemplateRenders() throws IOException {
        for (String target : List.of("@s", "@e[distance=..3,limit=1,sort=nearest]")) {
            String before = fill(resource("victim-tag-1871/grow-before-1871.cbs"), grow(target));
            String after = fill(resource("items/grow.cbs"), grow(target));
            for (String header : List.of("reset", "function effect()", "clock tick")) {
                List<String> notes = new ArrayList<>();
                assertEquals(block(after, header), fixed(block(before, header), notes),
                        "grow '" + header + "' (target " + target + ") must be the fixed template");
                assertFalse(notes.isEmpty(), "the install must say it changed " + header);
            }
        }
    }

    @Test
    void theShippedStarterCookieIsRewrittenToo() throws IOException {
        // The jar's own tiny_cookie_grow.zip, as both live worlds install it.
        Path zip = Path.of("src/main/resources/starter-library/datapacks/tiny_cookie_grow/tiny_cookie_grow.zip");
        Map<String, String> fn = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (String f : List.of("reset", "effect", "tick")) {
                var e = z.getEntry("data/tiny_cookie_grow/function/" + f + ".mcfunction");
                fn.put(f, new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)
                        .replace("minecraft:generic.scale", "minecraft:scale"));   // CommandDialect's part
            }
        }
        String after = fill(resource("items/grow.cbs"), grow("@s"));
        List<String> notes = new ArrayList<>();
        assertEquals(block(after, "clock tick"), fixed(List.of(fn.get("tick").strip().split("\n")), notes));
        assertEquals(block(after, "function effect()"), fixed(List.of(fn.get("effect").strip().split("\n")), notes));
        List<String> reset = fixed(List.of(fn.get("reset").strip().split("\n")), notes);
        assertTrue(reset.containsAll(block(after, "reset")), "the cookie's reset must gain the adoption: " + reset);
        // and every tick selector left is tagged, but for the upgrade sweep that runs every 200 ticks
        List<String> costly = SelectorCost.costly(String.join("\n", fixed(List.of(fn.get("tick").strip().split("\n")),
                new ArrayList<>())));
        assertEquals(List.of("@e[scores={mfam_grow=0..},tag=!mfam_grow_on]"), costly);
    }

    // ---- impulse ----

    private static final String TAG = "wishmob_film_impulse";
    private static final String SEL = "@e[distance=..4,tag=!" + TAG + ",type=!minecraft:player]";
    private static final String HIT_SEL = "@e[nbt={HurtTime:10s},distance=..4,tag=!" + TAG + "]";

    private static Map<String, String> impulse(String selector, String period, String fireIf) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("TAG", TAG);
        p.put("VICTIM_SELECTOR", selector);
        p.put("MOVE", "execute facing entity @e[tag=" + TAG + ",limit=1] feet run tp @s ^ ^ ^-0.40");
        p.put("RAMP_TICKS", "6");
        p.put("PARTICLE", "minecraft:cloud");
        p.put("SOUND", "minecraft:entity.player.attack.knockback");
        p.put("RADIUS", "4");
        p.put("LANDING", "effect give @s minecraft:slow_falling 3 0 true");
        p.put("PERIOD_TICKS", period);
        p.put("FIRE_IF", fireIf);
        return p;
    }

    @Test
    void anOldImpulsePackOfEveryTriggerBecomesWhatTheFixedTemplateRenders() throws IOException {
        Object[][] cases = {
                {"proximity", SEL, "20", "if entity " + SEL + " "},
                {"on_hit", HIT_SEL, "1", "if entity " + HIT_SEL + " "},
                {"periodic", SEL, "100", ""},
        };
        for (Object[] c : cases) {
            Map<String, String> p = impulse((String) c[1], (String) c[2], (String) c[3]);
            List<String> before = block(fill(resource("victim-tag-1871/impulse-before-1871.cbs"), p), "clock tick");
            List<String> after = block(fill(resource("effects/impulse.cbs"), p), "clock tick");
            List<String> notes = new ArrayList<>();
            assertEquals(after, fixed(before, notes), c[0] + ": the rewrite must be the fixed template");
            assertFalse(notes.isEmpty());
            assertEquals(List.of(), SelectorCost.costly(String.join("\n", fixed(before, new ArrayList<>()))),
                    c[0] + ": a selector still looks at every entity");
        }
    }

    @Test
    void aFixedPackIsNotRewrittenAgainAndAnythingElseIsUntouched() throws IOException {
        Map<String, String> p = impulse(SEL, "20", "if entity " + SEL + " ");
        String fresh = String.join("\n", block(fill(resource("effects/impulse.cbs"), p), "clock tick"));
        List<String> notes = new ArrayList<>();
        assertEquals(fresh, VictimTagFix.fix(fresh, notes));
        String freshGrow = String.join("\n", block(fill(resource("items/grow.cbs"), grow("@s")), "clock tick"));
        assertEquals(freshGrow, VictimTagFix.fix(freshGrow, notes));
        String other = "execute as @e[tag=x] at @s run particle minecraft:flame ~ ~ ~ 0 0 0 0 1\n"
                + "scoreboard players set @s mfam_impulse_like 3";
        assertEquals(other, VictimTagFix.fix(other, notes));
        assertTrue(notes.isEmpty(), "an already-fixed or unrelated function was rewritten: " + notes);
    }

    @Test
    void theInstallPathRunsIt() throws IOException {
        // A rule tested where it is DEFINED is not tested where it RUNS: every install goes through
        // DatapackDialect.correctFunction, so it must call this.
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/datapack/DatapackDialect.java"));
        assertTrue(src.contains("VictimTagFix.fix(ImpulseTelegraphFix.fix("),
                "DatapackDialect.correctFunction no longer runs VictimTagFix on install");
    }
}
