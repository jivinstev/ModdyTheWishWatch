package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.species.Voice;

import org.junit.jupiter.api.Test;

/** How {@code sculpt_creature}'s {@code voice} and {@code sounds} combine with a base's (chunk 4). */
class SculptVoiceTest {

    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aVoiceNamedIsThatVoice() {
        Voice v = SculptCreatureTool.voiceOf(j("{\"voice\":\"fox\"}"), j("{}"));
        assertEquals("fox", v.from());
    }

    @Test
    void aVariantKeepsItsBasesVoiceUnlessGivenAnother() {
        JsonObject base = j("{\"voice\":\"ravager\"}");
        assertEquals("ravager", SculptCreatureTool.voiceOf(j("{\"scale\":0.5}"), base).from(),
                "'a baby version of my beast' still sounds like it");
        assertEquals("wolf", SculptCreatureTool.voiceOf(j("{\"voice\":\"wolf\"}"), base).from());
        assertTrue(SculptCreatureTool.voiceOf(j("{\"voice\":\"silent\"}"), base).silent());
    }

    @Test
    void aMomentReplacesOnlyThatMoment() {
        Voice v = SculptCreatureTool.voiceOf(
                j("{\"voice\":\"wolf\",\"sounds\":{\"hurt\":\"entity.ravager.roar\"}}"), j("{}"));
        assertEquals("wolf", v.from());
        assertEquals("minecraft:entity.ravager.roar", v.sounds().get(Voice.Slot.HURT));
        assertEquals(1, v.sounds().size());
        // ...and on top of a base's own moments.
        Voice w = SculptCreatureTool.voiceOf(j("{\"sounds\":{\"death\":\"entity.cat.death\"}}"),
                j("{\"voice\":{\"from\":\"wolf\",\"hurt\":\"minecraft:entity.wolf.growl\"}}"));
        assertEquals("wolf", w.from());
        assertEquals("minecraft:entity.wolf.growl", w.sounds().get(Voice.Slot.HURT));
        assertEquals("minecraft:entity.cat.death", w.sounds().get(Voice.Slot.DEATH));
    }

    @Test
    void noVoiceAnywhereIsNone() {
        assertTrue(SculptCreatureTool.voiceOf(j("{}"), j("{}")).isNone());
    }
}
