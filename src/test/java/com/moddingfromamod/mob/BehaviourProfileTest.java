package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.Abilities;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.Species.Base;
import com.moddingfromamod.species.Species.Power;
import com.moddingfromamod.vanilla.RigInstinct;
import org.junit.jupiter.api.Test;

/**
 * one vocabulary — base, powers, abilities — for a sculpted creature and a real mob, parsed by
 * the same parsers and laid over what the mob already has.
 */
class BehaviourProfileTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void theArmyWishParsesToAGuardianProfile() {
        BehaviourProfile p = BehaviourProfile.fromJson(json(
                "{\"base\":\"guardian\",\"powers\":[\"leaps\",\"fireproof\"],\"abilities\":[\"slam\"]}"));
        assertEquals(Base.GUARDIAN, p.base());
        assertEquals(Set.of(Power.LEAPS, Power.FIREPROOF), p.powers());
        assertEquals(Set.of(Abilities.Kind.SLAM), p.abilities());
        assertEquals(List.of("guardian", "leaps", "fireproof", "slam"), p.summary());
    }

    @Test
    void noBaseIsNullNeverWild() {
        // On a real mob "wild" and "nothing said" differ: nothing said leaves its base alone.
        BehaviourProfile p = BehaviourProfile.fromJson(json("{\"powers\":[\"fireproof\"]}"));
        assertNull(p.base());
        assertTrue(BehaviourProfile.fromJson(json("{}")).isEmpty());
        assertTrue(BehaviourProfile.fromJson(null).isEmpty());
    }

    @Test
    void sameWordsAsASculptedCreature() {
        // The same parsers sculpt_creature's species uses: case and spacing do not matter.
        BehaviourProfile p = BehaviourProfile.fromJson(json(
                "{\"base\":\" Hunter \",\"powers\":[\"SKITTISH\"],\"abilities\":[\"Ranged\"]}"));
        assertEquals(Base.HUNTER, p.base());
        assertEquals(Set.of(Power.SKITTISH), p.powers());
        assertEquals(Set.of(Abilities.Kind.RANGED), p.abilities());
    }

    @Test
    void unknownWordsAreNamedNotDropped() {
        List<String> bad = BehaviourProfile.unknownWords(json(
                "{\"base\":\"fights\",\"powers\":[\"fireproof\",\"flying\"],\"abilities\":[\"breath\"]}"));
        assertEquals(List.of("base 'fights'", "power 'flying'", "ability 'breath'"), bad);
        assertTrue(BehaviourProfile.unknownWords(json("{\"base\":\"guardian\"}")).isEmpty());
    }

    @Test
    void layingOverAddsPowersAndKeepsTheOldBase() {
        BehaviourProfile was = new BehaviourProfile(Base.GUARDIAN, Set.of(Power.LEAPS), Set.of(Abilities.Kind.SLAM));
        BehaviourProfile asked = new BehaviourProfile(null, Set.of(Power.FIREPROOF), Set.of(Abilities.Kind.HEAL));
        BehaviourProfile now = asked.over(was);
        assertEquals(Base.GUARDIAN, now.base(), "no base asked: the old one stays");
        assertEquals(Set.of(Power.LEAPS, Power.FIREPROOF), now.powers(), "fireproof does not take its leap away");
        assertEquals(Set.of(Abilities.Kind.SLAM, Abilities.Kind.HEAL), now.abilities());
        assertEquals(Base.COWARD, new BehaviourProfile(Base.COWARD, Set.of(), Set.of()).over(was).base());
    }

    @Test
    void aRealMobKeepsItsOwnBodyAndSaysSo() {
        BehaviourProfile.ForRealMob split = new BehaviourProfile(Base.GUARDIAN,
                Set.of(Power.FLIES, Power.LEAPS, Power.BOSS, Power.FIREPROOF), Set.of()).forRealMob();
        assertEquals(Set.of(Power.LEAPS, Power.FIREPROOF), split.kept().powers());
        assertEquals(Set.of("flies", "boss"), Set.copyOf(split.refused()));
        assertEquals(Base.GUARDIAN, split.kept().base());
    }

    @Test
    void storedRoundTrips() {
        BehaviourProfile p = new BehaviourProfile(Base.COMPANION, Set.of(Power.RIDEABLE), Set.of(Abilities.Kind.TELEPORT));
        assertEquals(p, BehaviourProfile.fromStored(p.toStored()));
        assertEquals("", BehaviourProfile.NONE.toStored());
        assertTrue(BehaviourProfile.fromStored("not json").isEmpty());
    }

    @Test
    void mobTweakCarriesTheProfileAndItCountsAsAChange() {
        MobTweak t = MobTweak.fromJson(json("{\"target\":\"enderman\",\"base\":\"guardian\"}"));
        assertEquals(Base.GUARDIAN, t.behaviour().base());
        assertFalse(t.isEmpty(), "a temperament alone is something to change");
        assertTrue(t.summary().contains("guardian"));
        assertTrue(MobTweak.fromJson(json("{\"target\":\"enderman\"}")).behaviour().isEmpty());
    }

    // ── the one rule that differs for a real mob ──────────────────────────────────────────────

    @Test
    void aRealMobsOwnNatureStandsInForWild() {
        // A rig carrier's "wild" makes a hostile species fight; a real mob's nature already does.
        assertTrue(RigInstinct.forRealMob(null, Set.of()).isEmpty());
        assertTrue(RigInstinct.forRealMob(Base.WILD, Set.of(Power.FIREPROOF, Power.LEAPS)).isEmpty(),
                "made fireproof, a cow is not suddenly a hunter");
        assertTrue(RigInstinct.forRealMob(Base.WILD, Set.of(Power.SKITTISH)).flees());
        assertFalse(RigInstinct.forRealMob(Base.WILD, Set.of(Power.SKITTISH)).fights());
        // …while the WILD rig stand-in still fights (unchanged):
        assertTrue(RigInstinct.of(Base.WILD, Set.of()).fights());
    }

    @Test
    void aNamedTemperamentIsTheSameOnBoth() {
        for (Base b : List.of(Base.GUARDIAN, Base.HUNTER, Base.COWARD, Base.COMPANION)) {
            for (Set<Power> powers : List.of(Set.<Power>of(), Set.of(Power.FRIENDLY), Set.of(Power.LEAPS))) {
                assertEquals(RigInstinct.of(b, powers), RigInstinct.forRealMob(b, powers), b + " " + powers);
            }
        }
        RigInstinct guard = RigInstinct.forRealMob(Base.GUARDIAN, Set.of());
        assertTrue(guard.fights() && guard.huntsMonsters() && !guard.huntsPlayers(),
                "a guardian endermen army hunts monsters and never a player");
    }

    @Test
    void speciesWithBehaviourAddsAndKeeps() {
        Species s = new Species("x", "X", null, null, 0, 1, 20, 0.25, 3, 0, 0, Set.of(Power.LEAPS), "",
                Base.WILD, Set.of(), Set.of());
        Species g = s.withBehaviour(Base.GUARDIAN, Set.of(Power.FIREPROOF), Set.of(Abilities.Kind.SLAM));
        assertEquals(Base.GUARDIAN, g.base());
        assertEquals(Set.of(Power.LEAPS, Power.FIREPROOF), g.powers());
        assertEquals(Set.of(Abilities.Kind.SLAM), g.abilities());
        assertEquals(s, s.withBehaviour(null, Set.of(), Set.of()), "nothing asked is the same species");
    }
}
