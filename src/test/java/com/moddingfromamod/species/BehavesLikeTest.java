package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.mob.BehaviourProfile;
import org.junit.jupiter.api.Test;

/** the one word a creature tool takes for how a creature lives. */
class BehavesLikeTest {

    private static final Map<String, BehavesLike.Traits> GAME = Map.of(
            "minecraft:wolf", new BehavesLike.Traits(false, false, false),
            "somemod:brute", new BehavesLike.Traits(false, false, true),
            "somemod:eel", new BehavesLike.Traits(false, true, false));

    @Test
    void aMobTheTableKnowsIsItsArchetype() {
        var r = BehavesLike.resolve("Wolf", GAME::get);
        assertEquals(Archetype.PACK_HUNTER, r.archetype());
        assertTrue(r.note().isEmpty() && !r.refused());
        assertEquals(Archetype.DRAGON, BehavesLike.resolve("dragon", id -> null).archetype(),
                "a dragon is not a mob this game has, and it is an archetype all the same");
    }

    @Test
    void anyOtherMobLivesTheNearestBuiltLifeAndSaysSo() {
        var modded = BehavesLike.resolve("somemod:eel", GAME::get);
        assertEquals(Archetype.SWIMMER, modded.archetype());
        assertTrue(modded.note().contains("nearest"), modded.note());
        assertEquals(Archetype.BRUISER, BehavesLike.resolve("somemod:brute", GAME::get).archetype());
    }

    @Test
    void aWordThatIsNoMobIsRefusedNeverDropped() {
        var r = BehavesLike.resolve("unicorn", GAME::get);
        assertTrue(r.refused());
        assertNull(r.archetype());
        assertTrue(r.error().contains("'unicorn' is not a mob"), r.error());
        assertFalse(BehavesLike.resolve("", GAME::get).refused(), "absent is fine: nothing changes");
        assertNull(BehavesLike.resolve(null, GAME::get).archetype());
    }

    @Test
    void modifyMobCarriesItAndARealMobDoesNotTakeIt() {
        var p = BehaviourProfile.fromJson(JsonParser.parseString("{\"behaves_like\":\"dragon\"}").getAsJsonObject());
        assertEquals(Archetype.DRAGON, p.behaves());
        assertFalse(p.isEmpty(), "a tweak that only changes how it lives is still a tweak");
        assertNull(p.forRealMob().kept().behaves(), "a real mob already lives its own mob's life");
        assertTrue(p.summary().contains("lives like a dragon"));
        var was = new BehaviourProfile(Species.Base.GUARDIAN, Set.of(), Set.of(), Archetype.GRAZER);
        assertEquals(Archetype.DRAGON, p.over(was).behaves());
        assertEquals(Archetype.GRAZER, BehaviourProfile.NONE.over(was).behaves());
    }

    @Test
    void aSpeciesFileThatSaysDragonFliesHoweverItWasWritten() {
        Species s = Species.fromJson(JsonParser.parseString(
                "{\"id\":\"d\",\"name\":\"D\",\"behaves\":\"dragon\",\"powers\":[\"friendly\"]}").getAsJsonObject());
        assertTrue(s.has(Species.Power.FLIES));
        assertEquals(Archetype.DRAGON, s.archetype());
    }
}
