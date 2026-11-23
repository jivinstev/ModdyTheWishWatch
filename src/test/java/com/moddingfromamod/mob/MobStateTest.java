package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the undo record behind {@code MadeKind.MOB}.
 *
 * <p>A mob edit is the one kind of thing a wish makes that cannot be undone by deleting it — the
 * animal was already standing there. So the inverse is the numbers it had before, and if this record
 * is wrong the {@code [undo]} button lies, which is the one thing the design says must never happen.
 */
class MobStateTest {

    @Test
    void itDescribesItselfInTheSameWordsTheChangeDid() {
        // Read beside MobTweak.summary()'s "size ×2", so the agent can subtract rather than decode.
        MobState was = new MobState(1.0, 20.0, null, 0.25, null, null, false);
        assertEquals("size ×1, 20 health, speed 0.25", was.describe());
    }

    @Test
    void nothingRecordedSaysSoRatherThanReadingAsAMobWithNoStats() {
        assertTrue(MobState.UNKNOWN.isEmpty());
        assertEquals("nothing recorded", MobState.UNKNOWN.describe());
        assertFalse(new MobState(1.0, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        MobState before = new MobState(2.5, 180.0, 12.0, 0.3, "light_purple", "Daisy", true);
        MobState after = MobState.fromJson(before.toJson());
        assertEquals(before, after);
    }

    @Test
    void anAbsentAttributeComesBackAbsentAndNeverAsZero() {
        // A cow has no attack damage. Writing 0 back on an undo would invent a value it never had —
        // and a cow that hits for nothing is a different animal to anything reading the attribute.
        MobState cow = new MobState(1.0, 10.0, null, 0.2, null, null, false);
        MobState after = MobState.fromJson(cow.toJson());
        assertNull(after.damage(), "absent must not become 0");
        assertNull(after.glow());
        assertNull(after.name());
    }

    @Test
    void rubbishJsonDegradesToKnowingNothing() {
        // Better to refuse an undo than to restore invented numbers over a mob he likes.
        assertTrue(MobState.fromJson(null).isEmpty());
        com.google.gson.JsonObject junk = new com.google.gson.JsonObject();
        junk.add("scale", new com.google.gson.JsonArray());
        junk.addProperty("health", "not a number");
        assertTrue(MobState.fromJson(junk).isEmpty());
    }

    @Test
    void aBlankNameOrColourIsTheSameAsNotHavingOne() {
        MobState s = new MobState(null, null, null, null, "  ", "   ", null);
        assertTrue(s.isEmpty(), "empty strings are not a name and not a colour");
    }
}
