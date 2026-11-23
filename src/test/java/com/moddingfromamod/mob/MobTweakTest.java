package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for changes to real mobs. As with {@code SpeciesTest}, the property that matters isn't
 * "parses good input" but "<b>survives anything</b>" — these numbers come from an AI acting on a
 * 7-year-old's excitement.
 */
class MobTweakTest {

    private static MobTweak parse(String json) {
        return MobTweak.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesAFullRequest() {
        MobTweak t = parse("""
            {"target":"wither_storm","radius":30,"count":2,"scale":4,"health":900,
             "damage":25,"speed":0.4,"glow":"pink","name":"Steve's Storm",
             "invulnerable":true,"persistent":true}
            """);
        assertEquals("wither_storm", t.targetQuery());
        assertEquals(30, t.radius());
        assertEquals(2, t.count());
        assertEquals(4.0, t.scale());
        assertEquals(900.0, t.health());
        assertEquals("light_purple", t.glow());
        assertEquals("Steve's Storm", t.name());
        assertTrue(t.invulnerable());
        assertTrue(t.glowsInColour());
    }

    @Test
    void unmentionedKnobsStayNull() {
        // The single most important property here. If "leave it alone" were indistinguishable from
        // "set it to a default", then {"scale":4} would silently reset a boss's health and damage.
        MobTweak t = parse("{\"scale\":4}");
        assertEquals(4.0, t.scale());
        assertNull(t.health());
        assertNull(t.damage());
        assertNull(t.speed());
        assertNull(t.glow());
        assertNull(t.name());
        assertNull(t.invulnerable());
        assertNull(t.persistent());
    }

    @Test
    void absurdNumbersAreClamped() {
        MobTweak t = parse("""
            {"scale":9999,"health":999999,"damage":-50,"speed":50,"radius":5000,"count":900}
            """);
        assertEquals(MobTweak.MAX_SCALE, t.scale());
        assertEquals(MobTweak.MAX_HEALTH, t.health());
        assertEquals(0.0, t.damage());
        assertEquals(MobTweak.MAX_SPEED, t.speed());
        assertEquals(MobTweak.MAX_RADIUS, t.radius());
        assertEquals(MobTweak.MAX_COUNT, t.count());
    }

    @Test
    void healthCeilingIsHighEnoughNotToNerfAModdedBoss() {
        // A real boss can natively exceed the invented-creature cap of 500. If we clamped to that,
        // "make it tougher" would quietly make it weaker — the opposite of the wish.
        assertTrue(MobTweak.MAX_HEALTH > 500,
                "clamping to the wish_mob ceiling would nerf the very boss the child wanted");
        assertEquals(1500.0, parse("{\"health\":1500}").health());
    }

    @Test
    void emptyMeansNothingToDo() {
        assertTrue(parse("{}").isEmpty());
        assertTrue(parse("{\"target\":\"zombie\",\"radius\":10}").isEmpty(),
                "naming a target but no change is still nothing to do");
        assertFalse(parse("{\"scale\":2}").isEmpty());
        assertFalse(parse("{\"name\":\"Bob\"}").isEmpty());
    }

    @Test
    void glowOffIsDistinctFromGlowColour() {
        MobTweak off = parse("{\"glow\":\"none\"}");
        assertEquals(GlowColour.NONE, off.glow());
        assertFalse(off.glowsInColour());
        assertFalse(off.isEmpty(), "switching the glow off is a real change");

        assertTrue(parse("{\"glow\":\"purple\"}").glowsInColour());
    }

    @Test
    void namesAreTrimmedAndBounded() {
        assertEquals("Bob", parse("{\"name\":\"   Bob   \"}").name());
        assertNull(parse("{\"name\":\"   \"}").name(), "a blank name means leave it alone");
        assertEquals(MobTweak.MAX_NAME_LENGTH,
                parse("{\"name\":\"" + "x".repeat(500) + "\"}").name().length());
    }

    @Test
    void survivesRubbishInput() {
        assertNotNull(MobTweak.fromJson(null));
        assertTrue(MobTweak.fromJson(null).isEmpty());
        // Wrong types everywhere: must be ignored, never thrown.
        MobTweak t = parse("""
            {"scale":"huge","health":{},"damage":[],"glow":"","count":"lots","name":123}
            """);
        assertNull(t.scale());
        assertNull(t.health());
        assertNull(t.glow());
        assertEquals(1, t.count());
    }

    @Test
    void summaryReadsLikeSomethingYouCouldTellAChild() {
        String s = parse("{\"scale\":4,\"glow\":\"pink\",\"name\":\"Storm\"}").summary();
        assertTrue(s.contains("size ×4"), s);
        assertTrue(s.contains("glowing light purple"), s);
        assertTrue(s.contains("Storm"), s);
        assertEquals("nothing", parse("{}").summary());
    }
}
