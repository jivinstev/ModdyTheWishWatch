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

    // ===================== #115: armour and knockback, and the record's version =====================

    @Test
    void armourAndKnockbackAreCapturedAndComeBack() {
        MobState was = new MobState(1.0, 20.0, null, null, null, null, false, null, 4.0, 0.25);
        MobState back = MobState.fromJson(was.toJson());
        assertEquals(4.0, back.armor());
        assertEquals(0.25, back.knockbackResistance());
        assertEquals(MobState.RECORD_VERSION, was.toJson().get("v").getAsInt(), "written at the current version");
        assertTrue(was.describe().contains("4 armour") && was.describe().contains("knockback resistance 0.25"),
                was.describe());
        assertFalse(new MobState(null, null, null, null, null, null, null, null, 0.0, null).isEmpty(),
                "0 armour is a value to put back, not nothing");
    }

    @Test
    void anOlderRecordReadsArmourAndKnockbackAsNotCapturedAndKeepsWhatItHad() {
        // a v1 record, as a build before #115 wrote it: no "v", and even a stray armour field is not trusted
        com.google.gson.JsonObject v1 = com.google.gson.JsonParser.parseString(
                "{\"scale\":1.0,\"health\":20.0,\"armor\":9.0}").getAsJsonObject();
        MobState old = MobState.fromJson(v1);
        assertEquals(1, MobState.versionOf(v1));
        assertEquals(1.0, old.scale());
        assertEquals(20.0, old.maxHealth());
        assertNull(old.armor(), "not captured: an undo leaves it as it is");
        assertNull(old.knockbackResistance());
    }

    @Test
    void aNewerRecordIsRefusedNotHalfRead() {
        com.google.gson.JsonObject newer = com.google.gson.JsonParser.parseString(
                "{\"v\":" + (MobState.RECORD_VERSION + 1) + ",\"scale\":2.0}").getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertThrows(MobState.Newer.class, () -> MobState.fromJson(newer));
        com.google.gson.JsonObject garbled = com.google.gson.JsonParser.parseString(
                "{\"v\":\"two\",\"scale\":2.0}").getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertThrows(MobState.Newer.class, () -> MobState.fromJson(garbled),
                "an unreadable version is refused, never guessed at");
    }
}
