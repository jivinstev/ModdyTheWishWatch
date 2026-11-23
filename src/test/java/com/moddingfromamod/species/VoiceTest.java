package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;

/**
 * A creature's voice (chunk 4): what the agent may say, what it means in sound ids, and that
 * every creature written before it stays exactly as it was.
 */
class VoiceTest {

    /** A slice of 1.21.4's real sounds.json, the irregular names included. */
    private static final Set<String> KNOWN = Set.of(
            "minecraft:entity.wolf.ambient", "minecraft:entity.wolf.hurt", "minecraft:entity.wolf.death",
            "minecraft:entity.wolf.step", "minecraft:entity.wolf.howl", "minecraft:entity.wolf.growl",
            "minecraft:entity.ender_dragon.growl", "minecraft:entity.ender_dragon.hurt",
            "minecraft:entity.ender_dragon.death", "minecraft:entity.ender_dragon.ambient",
            "minecraft:entity.sniffer.idle", "minecraft:entity.sniffer.hurt", "minecraft:entity.sniffer.death",
            "minecraft:entity.sniffer.step",
            "minecraft:entity.cat.ambient", "minecraft:entity.cat.hurt", "minecraft:entity.cat.death",
            "minecraft:entity.ravager.ambient", "minecraft:entity.ravager.roar", "minecraft:entity.ravager.hurt",
            "minecraft:entity.ravager.death", "minecraft:entity.ravager.step",
            "minecraft:entity.polar_bear.ambient", "minecraft:entity.polar_bear.hurt");

    private static Voice parse(String json) {
        return Voice.parse(JsonParser.parseString(json));
    }

    @Test
    void aMobNameIsThatMobsVoice() {
        assertEquals("ravager", Voice.parse(new JsonPrimitive("Ravager")).from());
        assertEquals("polar_bear", Voice.parse(new JsonPrimitive("polar bear")).from());
        assertEquals("polar_bear", Voice.parse(new JsonPrimitive("minecraft:polar_bear")).from());
    }

    @Test
    void silenceIsAskedForAndIsNotNobodyAsking() {
        for (String w : new String[]{"silent", "none", "quiet", "mute"}) {
            assertTrue(Voice.parse(new JsonPrimitive(w)).silent(), w);
        }
        assertTrue(Voice.NONE.isNone());
        assertFalse(Voice.SILENT.isNone(), "asked-for silence is a choice, kept on disk");
        assertTrue(Voice.parse(null).isNone());
    }

    @Test
    void aMomentCanSoundDifferent() {
        Voice v = parse("{\"from\":\"wolf\",\"ambient\":\"entity.wolf.howl\"}");
        assertEquals("minecraft:entity.wolf.howl", v.sounds().get(Voice.Slot.AMBIENT));
        var r = VoiceSounds.resolve(v, KNOWN);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("minecraft:entity.wolf.howl", r.get(Voice.Slot.AMBIENT));
        assertEquals("minecraft:entity.wolf.hurt", r.get(Voice.Slot.HURT));
    }

    @Test
    void anUnknownMomentIsRefusedNotDropped() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("{\"from\":\"wolf\",\"attack\":\"x\"}"));
        assertTrue(e.getMessage().contains("ambient, hurt, death and step"), e.getMessage());
    }

    @Test
    void itRoundTripsAndNothingIsWrittenForNoVoice() {
        for (String j : new String[]{"\"ravager\"", "\"silent\"", "{\"from\":\"wolf\",\"hurt\":\"minecraft:entity.wolf.growl\"}"}) {
            Voice v = parse(j);
            assertEquals(v, Voice.parse(v.toJson()), j);
        }
        assertNull(Voice.NONE.toJson());
    }

    @Test
    void theIrregularNamesAreFound() {
        var dragon = VoiceSounds.resolve(new Voice("ender_dragon", null, false), KNOWN);
        assertEquals("minecraft:entity.ender_dragon.ambient", dragon.get(Voice.Slot.AMBIENT));
        var sniffer = VoiceSounds.resolve(new Voice("sniffer", null, false), KNOWN);
        assertEquals("minecraft:entity.sniffer.idle", sniffer.get(Voice.Slot.AMBIENT),
                "a sniffer's idle sound is 'idle', not 'ambient'");
    }

    @Test
    void aMobWithNoStepOfItsOwnWalksOnTheGround() {
        var cat = VoiceSounds.resolve(new Voice("cat", null, false), KNOWN);
        assertNull(cat.get(Voice.Slot.STEP));
        assertTrue(cat.blockSteps(), "a cat's footsteps are the block's, as vanilla's are");
        assertFalse(VoiceSounds.resolve(new Voice("wolf", null, false), KNOWN).blockSteps());
        assertFalse(VoiceSounds.resolve(Voice.SILENT, KNOWN).blockSteps(), "silent is not even footsteps");
        assertFalse(VoiceSounds.resolve(Voice.NONE, KNOWN).blockSteps(), "no voice is as it always was");
    }

    @Test
    void aVoiceTheGameDoesNotHaveIsSaidWithTheNearestThatIt() {
        var r = VoiceSounds.resolve(new Voice("wolfy", null, false), KNOWN);
        assertFalse(r.ok());
        assertTrue(r.problems().get(0).contains("wolf"), r.problems().toString());
        // Spark's baseline: 1.21.4 spells it entity.wolf.howl, and the old spelling is silence.
        var howl = VoiceSounds.resolve(parse("{\"ambient\":\"minecraft:entity.wolf_howl\"}"), KNOWN);
        assertFalse(howl.ok());
        assertTrue(howl.problems().get(0).contains("minecraft:entity.wolf.howl"), howl.problems().toString());
    }

    @Test
    void biggerIsDeeperAndHeardFurther() {
        assertTrue(VoiceSounds.pitch(3, 0) < VoiceSounds.pitch(1, 0));
        assertTrue(VoiceSounds.pitch(0.5, 0) > VoiceSounds.pitch(1, 0));
        assertEquals(1.0f, VoiceSounds.pitch(1, 0), 1e-6);
        assertTrue(VoiceSounds.pitch(40, -1) >= 0.5f && VoiceSounds.pitch(0.01, 1) <= 2.0f);
        assertEquals(1.0f, VoiceSounds.volume(0.5), 1e-6, "never quieter than the mob's own");
        assertTrue(VoiceSounds.volume(3) > 1.0f);
        assertTrue(VoiceSounds.stepVolume(3) > VoiceSounds.stepVolume(1));
        assertTrue(VoiceSounds.stride(4) > VoiceSounds.stride(1));
    }

    @Test
    void theAmbientClockIsVanillas() {
        // Quiet for its interval, then likelier every tick.
        assertFalse(VoiceSounds.ambientFires(-VoiceSounds.AMBIENT_INTERVAL, 0));
        assertFalse(VoiceSounds.ambientFires(0, 0));
        assertTrue(VoiceSounds.ambientFires(50, 10));
        assertFalse(VoiceSounds.ambientFires(50, 900));
    }

    @Test
    void aSpeciesKeepsItsVoiceAndOneWithoutIsUnchanged() {
        var o = JsonParser.parseString("{\"id\":\"vt_a\",\"name\":\"A\",\"voice\":\"ravager\"}").getAsJsonObject();
        Species s = Species.fromJson(o);
        assertEquals("ravager", s.voice().from());
        assertEquals(s.voice(), Species.fromJson(s.toJson()).voice());
        assertEquals("ravager", s.withPowersAdded(Set.of(Species.Power.FLIES)).voice().from(),
                "an edit to its powers keeps its voice");
        assertEquals("ravager", s.withAbilityPack("gt_pack").voice().from(),
                "binding an ability keeps its voice");
        var legacy = Species.fromJson(JsonParser.parseString("{\"id\":\"vt_b\",\"name\":\"B\"}").getAsJsonObject());
        assertTrue(legacy.voice().isNone());
        assertFalse(legacy.toJson().has("voice"), "a creature from before stays byte-identical");
        var garbled = Species.fromJson(JsonParser.parseString(
                "{\"id\":\"vt_c\",\"name\":\"C\",\"voice\":{\"from\":\"wolf\",\"bark\":\"x\"}}").getAsJsonObject());
        assertTrue(garbled != null && garbled.voice().isNone(), "a voice it cannot read never loses the creature");
    }
}
