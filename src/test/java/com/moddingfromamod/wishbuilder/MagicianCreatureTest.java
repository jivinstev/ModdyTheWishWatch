package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * slice 1: the Builder's changes on a Magician-made creature send ONLY what changed, and keep every power the
 * Builder does not offer (the Magician's own: it explodes, it is blastproof) exactly as it was.
 */
class MagicianCreatureTest {

    static final MagicianCreature.Thing DRAKE = new MagicianCreature.Thing("lava_drake", "Lava Drake", 1.7,
            List.of("hostile", "fireproof", "explodes", "BLASTPROOF"));

    static JsonObject sculpt(List<BuilderLowering.Call> calls) {
        return calls.stream().filter(c -> c.tool().equals("sculpt_creature")).findFirst().orElseThrow().args();
    }

    static List<String> powers(JsonObject a) {
        List<String> out = new ArrayList<>();
        a.getAsJsonArray("powers").forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void itReadsAsTheBuilderSeesIt() {
        assertEquals(List.of("power:fireproof"), MagicianCreature.builderPowers(DRAKE));
        assertEquals("wild", MagicianCreature.temper(DRAKE));
        assertEquals("", MagicianCreature.temper(new MagicianCreature.Thing("x", "X", 1, List.of("glowing"))),
                "no Builder temper in its powers: no temper tile is on");
    }

    @Test
    void nothingChangedSendsNothing() {
        assertTrue(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, MagicianCreature.builderPowers(DRAKE), "").isEmpty());
        assertTrue(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, MagicianCreature.builderPowers(DRAKE), "wild").isEmpty(),
                "its own temper again is no change");
    }

    @Test
    void biggerIsOneStepUpFromItsOwnSizeAndNothingElse() {
        JsonObject a = sculpt(MagicianCreature.calls(DRAKE, 3, MagicianCreature.builderPowers(DRAKE), ""));
        assertEquals("lava_drake", a.get("id").getAsString());
        assertEquals("lava_drake", a.get("start_from").getAsString(), "itself: its parts and record are kept");
        assertEquals(1.7 * 1.6, a.get("scale").getAsDouble(), 1e-9);
        assertFalse(a.has("powers"), "powers not changed, so not sent");
        assertFalse(a.get("spawn").getAsBoolean(), "a change spawns nothing");
        assertEquals(0, a.getAsJsonArray("parts").size());
    }

    @Test
    void aBuilderPowerIsAddedAndTheMagiciansOwnAreKept() {
        List<String> chosen = new ArrayList<>(MagicianCreature.builderPowers(DRAKE));
        chosen.add("power:flies");
        List<String> p = powers(sculpt(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, chosen, "")));
        assertTrue(p.containsAll(List.of("hostile", "fireproof", "explodes", "blastproof", "flies")), p.toString());
        assertFalse(sculpt(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, chosen, "")).has("scale"), "size not changed");
    }

    @Test
    void takingABuilderPowerOffKeepsTheRest() {
        List<String> p = powers(sculpt(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, List.of(), "")));
        assertFalse(p.contains("fireproof"));
        assertTrue(p.containsAll(List.of("hostile", "explodes", "blastproof")), p.toString());
    }

    @Test
    void aNewTemperReplacesOnlyTheOldTemper() {
        List<String> p = powers(sculpt(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, MagicianCreature.builderPowers(DRAKE), "shy")));
        assertFalse(p.contains("hostile"));
        assertTrue(p.containsAll(List.of("skittish", "fireproof", "explodes", "blastproof")), p.toString());
    }

    @Test
    void followsYouIsModifyMobOnItself() {
        List<BuilderLowering.Call> calls = MagicianCreature.calls(DRAKE, MagicianCreature.NOW, MagicianCreature.builderPowers(DRAKE), "pet");
        JsonObject m = calls.stream().filter(c -> c.tool().equals("modify_mob")).findFirst().orElseThrow().args();
        assertEquals("lava_drake", m.get("target").getAsString());
        assertTrue(m.get("follow").getAsBoolean());
        assertEquals("plan_wish", calls.get(0).tool());
        assertEquals("finish_building", calls.get(calls.size() - 1).tool());
    }

    /** slice 2: a colour scheme recolours its OWN parts, biggest colour first; nothing else is sent. */
    @Test
    void aColourSchemeRecoloursItsOwnPartsAndKeepsThem() {
        com.google.gson.JsonArray parts = com.google.gson.JsonParser.parseString("[" +
                "{\"name\":\"body\",\"colour\":\"#AA3300\",\"from\":[0,0,0],\"to\":[8,8,8]}," +
                "{\"name\":\"tail\",\"colour\":\"#331100\",\"from\":[0,0,0],\"to\":[1,1,4]}," +
                "{\"name\":\"eye_left\",\"colour\":\"#000000\",\"from\":[0,0,0],\"to\":[1,1,1]}]").getAsJsonArray();
        MagicianCreature.Thing t = new MagicianCreature.Thing("lava_drake", "Lava Drake", 1.7, List.of("hostile"), parts);
        JsonObject a = sculpt(MagicianCreature.calls(t, MagicianCreature.NOW, List.of(), "", "ocean"));
        JsonObject rc = a.getAsJsonObject("recolour");
        assertTrue(rc.has("body") && rc.has("tail"), "its own parts are recoloured: " + rc);
        assertFalse(rc.has("eye_left"), "the face never is");
        assertEquals("lava_drake", a.get("start_from").getAsString(), "itself: its parts are kept");
        assertFalse(a.has("scale") || a.has("powers"), "only the colours changed");
        assertTrue(MagicianCreature.calls(t, MagicianCreature.NOW, List.of(), "", "as_is").isEmpty(), "as it is: nothing sent");
        assertTrue(MagicianCreature.calls(DRAKE, MagicianCreature.NOW, List.of("power:fireproof"), "", "ocean").isEmpty(),
                "with no parts to read, colours cannot be changed (and are not offered)");
    }
}
