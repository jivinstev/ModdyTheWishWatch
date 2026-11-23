package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pet behaviours on a real mob: the pure half (the words, the merge, the stored copy). */
class PetBehaviourTest {

    private static PetBehaviour of(String json) {
        return PetBehaviour.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    @DisplayName("what he says it finds becomes a block or a tag the game can look for")
    void findsIsResolvedFromTheChildsWords() {
        assertEquals("#minecraft:diamond_ores", PetBehaviour.resolveFinds("diamonds"));
        assertEquals("#minecraft:diamond_ores", PetBehaviour.resolveFinds("Diamond"));
        assertEquals("minecraft:carrots", PetBehaviour.resolveFinds("carrots"));
        assertEquals("minecraft:potatoes", PetBehaviour.resolveFinds("potatoes"));
        assertEquals("minecraft:chest", PetBehaviour.resolveFinds("treasure"));
        assertEquals("minecraft:chest", PetBehaviour.resolveFinds("loot"));
        assertEquals("#minecraft:logs", PetBehaviour.resolveFinds("#logs"));
        assertEquals("create:zinc_ore", PetBehaviour.resolveFinds("create:zinc_ore"));
        assertEquals("minecraft:glowstone", PetBehaviour.resolveFinds("glowstone"),
                "an unknown word is tried as a vanilla block id; the tool checks it exists");
        assertEquals(PetBehaviour.FINDS_OFF, PetBehaviour.resolveFinds("none"));
    }

    @Test
    @DisplayName("null means leave it alone: asking for follow keeps what it already finds")
    void aRequestIsLaidOverWhatItAlreadyDoes() {
        PetBehaviour finder = of("{finds:'diamonds'}");
        PetBehaviour now = of("{follow:true}").over(finder);
        assertEquals(Boolean.TRUE, now.follow());
        assertEquals("#minecraft:diamond_ores", now.finds(), "follow must not stop it finding");
        PetBehaviour stopped = of("{finds:'none'}").over(now);
        assertEquals(PetBehaviour.FINDS_OFF, stopped.finds());
        assertTrue(stopped.isActive(), "it still follows");
        assertFalse(of("{follow:false}").over(of("{follow:true}")).isActive());
    }

    @Test
    @DisplayName("the copy kept on the mob reads back exactly, and junk reads back as nothing")
    void theStoredCopyRoundTrips() {
        PetBehaviour p = of("{follow:true, friendly:true, rideable:false, finds:'gold'}");
        assertEquals(p, PetBehaviour.fromStored(p.toStored()));
        assertEquals(PetBehaviour.NONE, PetBehaviour.fromStored(""));
        assertEquals(PetBehaviour.NONE, PetBehaviour.fromStored("{not json"));
    }

    @Test
    @DisplayName("modify_mob with only a pet field is not an empty request")
    void aPetFieldAloneIsAChange() {
        MobTweak t = MobTweak.fromJson(JsonParser.parseString("{scope:'one', friendly:true}")
                .getAsJsonObject());
        assertFalse(t.isEmpty());
        assertTrue(t.summary().contains("friendly"), t.summary());
        MobTweak none = MobTweak.fromJson(JsonParser.parseString("{scope:'one'}").getAsJsonObject());
        assertTrue(none.isEmpty());
        assertTrue(none.pet().isEmpty());
    }

    @Test
    @DisplayName("the undo record keeps the pet behaviour it had, including none at all")
    void theUndoRecordCarriesThePetState() {
        MobState before = new MobState(1.0, null, null, null, null, null, null, "");
        MobState back = MobState.fromJson(before.toJson());
        assertEquals("", back.pet(), "\"did nothing\" is a real value: undoing 'follow me' puts it back");
        assertNull(MobState.fromJson(new MobState(1.0, null, null, null, null, null, null).toJson()).pet(),
                "an old record knows nothing about pets, and says so with null");
    }

    /** how a child says "stay", what is stored, and that a tool call never carries a home. */
    @Test
    void stayReadsEveryWayItIsSaidAndOnlyTheMobKeepsAHome() {
        var j = (java.util.function.Function<String, PetBehaviour>) t ->
                PetBehaviour.fromJson(JsonParser.parseString(t).getAsJsonObject());
        assertEquals(0, j.apply("{stay:true}").stay());
        assertEquals(3, j.apply("{stay:3}").stay());
        assertEquals(PetBehaviour.MAX_STAY, j.apply("{stay:500}").stay(), "bounded");
        assertEquals(PetBehaviour.STAY_OFF, j.apply("{stay:false}").stay());
        assertNull(j.apply("{follow:true}").stay(), "not said = left alone");
        assertNull(j.apply("{stay:0,home:12345}").home(), "a home comes from where it stands, not from a call");
        PetBehaviour homed = j.apply("{stay:2}").homedAt(98765L);
        assertTrue(homed.stays() && homed.isActive());
        PetBehaviour back = PetBehaviour.fromStored(homed.toStored());
        assertEquals(98765L, back.home(), "the stored copy keeps its home through a reload");
        assertEquals(2, back.stay());
        assertFalse(j.apply("{stay:false}").over(back).stays(), "let go, it wanders again");
        assertTrue(j.apply("{friendly:true}").over(back).stays(), "another change keeps it home");
        assertTrue(j.apply("{stay:0}").summary().get(0).contains("walks back"), "he is told what it does");
    }

    /** within its radius a stayer is free; past it, it walks home; far away it is put back. */
    @Test
    void aStayerWalksHomeAndIsPutBackWhenFar() {
        assertEquals(PetMoves.Follow.STAY, PetMoves.home(0.5, 0));
        assertEquals(PetMoves.Follow.STAY, PetMoves.home(3.5, 3));
        assertEquals(PetMoves.Follow.WALK, PetMoves.home(6, 3));
        assertEquals(PetMoves.Follow.HOP, PetMoves.home(PetMoves.HOME_TELEPORT + 1, 3));
    }
}
