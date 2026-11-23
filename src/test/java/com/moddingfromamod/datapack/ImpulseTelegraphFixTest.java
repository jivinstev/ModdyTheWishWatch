package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * a pack compiled from the OLD impulse template, rewritten on install, must say exactly what
 * the FIXED template renders for the same parameters — so a creature granted before the fix and one
 * granted after it behave identically, and the rewrite cannot drift from the template it imitates.
 *
 * <p>The old template is kept verbatim as a fixture ({@code impulse-1725/impulse-before-1725.cbs}),
 * and so is a tick function a real server compiled from it ({@code tick-before-1725.mcfunction},
 * from the before-film's world), because "the compiler passes these lines through untouched" is
 * the assumption the rewrite stands on and it is cheaper to pin than to trust.
 */
class ImpulseTelegraphFixTest {

    private static final String TAG = "wishmob_film_impulse";
    private static final String SEL = "@e[distance=..4,tag=!" + TAG + ",type=!minecraft:player,"
            + "type=!minecraft:item,type=!minecraft:arrow,type=!minecraft:armor_stand,"
            + "type=!minecraft:block_display,type=!minecraft:item_display,type=!minecraft:text_display,"
            + "type=!minecraft:interaction,type=!minecraft:marker,tag=!mfamrigpx]";
    private static final String HIT_SEL = "@e[nbt={HurtTime:10s},distance=..4,tag=!" + TAG + "]";

    private static String resource(String path) throws IOException {
        try (InputStream in = ImpulseTelegraphFixTest.class.getClassLoader().getResourceAsStream(path)) {
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

    /** The commands of the clock tick, as the compiler emits them: no comments, no leading slash. */
    private static List<String> tickCommands(String text) {
        List<String> out = new ArrayList<>();
        boolean inTick = !text.contains("clock tick");
        for (String raw : text.split("\n")) {
            String l = raw.strip();
            if (l.equals("clock tick")) {
                inTick = true;
                continue;
            }
            if (inTick && l.equals("end")) {
                break;
            }
            if (!inTick || l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            out.add(l.startsWith("/") ? l.substring(1) : l);
        }
        return out;
    }

    private static Map<String, String> params(String selector, String period) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("TAG", TAG);
        p.put("VICTIM_SELECTOR", selector);
        p.put("MOVE", "execute facing entity @e[tag=" + TAG + ",limit=1] feet run tp @s ^ ^ ^-0.40");
        p.put("RAMP_TICKS", "6");
        p.put("PARTICLE", "minecraft:cloud");
        p.put("SOUND", "minecraft:entity.player.attack.knockback");
        p.put("RADIUS", "4");
        p.put("LANDING", "scoreboard players reset @s mfam_noop");
        p.put("PERIOD_TICKS", period);
        return p;
    }

    private static List<String> before(String selector, String period) throws IOException {
        return tickCommands(fill(resource("impulse-1725/impulse-before-1725.cbs"), params(selector, period)));
    }

    private static List<String> after(String selector, String period, String fireIf) throws IOException {
        Map<String, String> p = params(selector, period);
        p.put("FIRE_IF", fireIf);
        return tickCommands(fill(resource("effects/impulse.cbs"), p));
    }

    /**
     * What a world is given: the WHOLE install chain (then an earlier issue's {@link VictimTagFix}) — so
     * an old pack is compared with today's template, not with the template as left it.
     */
    private static List<String> fixed(List<String> commands, List<String> notes) {
        return tickCommands(chain(String.join("\n", commands), notes));
    }

    private static String chain(String text, List<String> notes) {
        return VictimTagFix.fix(ImpulseTelegraphFix.fix(text, notes), notes);
    }

    @Test
    void anOldProximityPackBecomesWhatTheFixedTemplateRenders() throws IOException {
        List<String> notes = new ArrayList<>();
        assertEquals(after(SEL, "20", "if entity " + SEL + " "), fixed(before(SEL, "1"), notes),
                "the rewrite of an old proximity pack must be the fixed template, re-armed at 20 ticks");
        assertEquals(2, notes.size(), "the install must say it changed the pack, once per fix "
                + "(#1725's telegraph, #1871's victim tag): " + notes);
    }

    @Test
    void anOldOnHitPackKeepsItsClockAndGainsTheGate() throws IOException {
        assertEquals(after(HIT_SEL, "1", "if entity " + HIT_SEL + " "), fixed(before(HIT_SEL, "1"),
                new ArrayList<>()), "on_hit: every hit is its own event, so no re-arm — only the gate");
    }

    @Test
    void aPeriodicPackIsLeftAlone() throws IOException {
        // The clock IS the trigger: a stomp every five seconds still stomps with nobody near, and the
        // fixed template renders a periodic impulse to the same commands the old one did.
        List<String> notes = new ArrayList<>();
        List<String> old = before(SEL, "100");
        assertEquals(old, tickCommands(ImpulseTelegraphFix.fix(String.join("\n", old), notes)),
                "#1725 must not touch a periodic impulse (#1871 tags its victims: VictimTagFixTest)");
        assertTrue(notes.isEmpty());
        // ...and the new template, with FIRE_IF empty, behaves the same: it fires on the clock alone.
        List<String> fresh = after(SEL, "100", "");
        assertTrue(fresh.stream().anyMatch(l -> l.equals("execute as @e[tag=" + TAG
                        + ",scores={mfam_impt=100..}] at @s run tag @s add mfam_impf")),
                "a periodic source must fire on its clock with no condition: " + fresh);
    }

    @Test
    void theRealCompiledFunctionIsRewrittenToo() throws IOException {
        // What a server actually stored for the before-film's creature: comments and all.
        String real = resource("impulse-1725/tick-before-1725.mcfunction");
        List<String> notes = new ArrayList<>();
        String out = chain(real, notes);
        assertEquals(after(SEL, "20", "if entity " + SEL + " "), tickCommands(out));
        assertFalse(notes.isEmpty());
        // Idempotent: a pack installed twice is not rewritten twice.
        List<String> again = new ArrayList<>();
        assertEquals(out, chain(out, again));
        assertTrue(again.isEmpty(), "an already-fixed pack was rewritten again: " + again);
    }

    @Test
    void anythingElseComesBackByteForByte() {
        String other = "execute as @e[tag=x] at @s run particle minecraft:flame ~ ~ ~ 0 0 0 0 1\n";
        List<String> notes = new ArrayList<>();
        assertEquals(other, ImpulseTelegraphFix.fix(other, notes));
        assertTrue(notes.isEmpty());
    }

    @Test
    void noTelegraphInTheFixedTemplateRunsOnTheClockAlone() throws IOException {
        for (String l : after(SEL, "20", "if entity " + SEL + " ")) {
            if (l.contains(" run particle ") || l.contains(" run playsound ")) {
                assertTrue(l.contains("tag=mfam_impf"), "a telegraph not gated on a catch: " + l);
            }
        }
    }
}
