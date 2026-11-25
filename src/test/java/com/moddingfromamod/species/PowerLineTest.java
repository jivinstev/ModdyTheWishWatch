package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/** {@link PowerLine}: the creature tools' power line and the guard-that-cannot-hit flag (#101). */
class PowerLineTest {

    private static Species sp(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void itComparesWithAVanillaMobOfItsOwnKind() {
        assertTrue(PowerLine.likeVanilla(20, 3).startsWith("a zombie"), PowerLine.likeVanilla(20, 3));
        assertTrue(PowerLine.likeVanilla(100, 15).startsWith("a iron golem"), PowerLine.likeVanilla(100, 15));
        assertTrue(PowerLine.likeVanilla(8, 4).startsWith("a wolf"), PowerLine.likeVanilla(8, 4));
        // a creature with no hit is compared with gentle mobs only
        assertTrue(PowerLine.likeVanilla(10, 0).matches("a (pig|cow) .*"), PowerLine.likeVanilla(10, 0));
        assertTrue(PowerLine.likeVanilla(500, 0).startsWith("a camel"), PowerLine.likeVanilla(500, 0));
    }

    @Test
    void theLineSaysItsNumbersWhatItIsLikeAndItsBand() {
        Species dragon = sp("{\"id\":\"d\",\"health\":60,\"damage\":6,\"armor\":4,\"knockbackResistance\":0.5,"
                + "\"powers\":[\"friendly\"],\"base\":\"companion\"}");
        String line = PowerLine.of(dragon, 1.6);
        assertTrue(line.startsWith("Power: 60 health, 6 damage, 4 armour, knockback resistance 0.5: like a "), line);
        PowerBand.Band band = PowerBand.forVolume(1.6);
        assertTrue(line.contains("Its size is " + band.name() + ": about " + (long) band.health() + " health, "
                + (long) band.damage() + " damage."), line);
        // none of armour or knockback when it has none
        assertTrue(!PowerLine.of(sp("{\"id\":\"a\",\"health\":20,\"damage\":3}"), 0.5).contains("armour"));
    }

    @Test
    void aVehicleIsToldItNeverHits() {
        Species car = sp("{\"id\":\"c\",\"health\":40,\"damage\":0,\"behaves\":\"vehicle\",\"powers\":[\"rideable\",\"friendly\"]}");
        assertTrue(PowerLine.of(car, 4).contains("0 damage (a vehicle never hits)."), PowerLine.of(car, 4));
        assertEquals("", PowerLine.zeroHitFlag(car, 4), "a vehicle never fights, so its 0 is no mistake");
    }

    @Test
    void aGuardAHunterOrAHostileCreatureWithNoHitIsFlaggedWithItsBandsNumber() {
        PowerBand.Band band = PowerBand.forVolume(30);
        for (String json : new String[] {
                "{\"id\":\"g\",\"damage\":0,\"powers\":[\"friendly\"],\"base\":\"guardian\"}",
                "{\"id\":\"h\",\"damage\":0,\"base\":\"hunter\"}",
                "{\"id\":\"w\",\"damage\":0,\"powers\":[]}"}) {
            String flag = PowerLine.zeroHitFlag(sp(json), 30);
            assertTrue(flag.startsWith("⚠ It fights") && flag.contains("a hit of 0 lands nothing")
                    && flag.contains("about " + (long) band.damage() + " for its size (" + band.name() + ")"), json + " → " + flag);
        }
    }

    @Test
    void aGentleCreatureOrOneThatHitsIsNotFlagged() {
        assertEquals("", PowerLine.zeroHitFlag(sp("{\"id\":\"f\",\"damage\":0,\"powers\":[\"friendly\"]}"), 1));
        assertEquals("", PowerLine.zeroHitFlag(sp("{\"id\":\"c\",\"damage\":0,\"powers\":[\"friendly\"],\"base\":\"companion\"}"), 1));
        assertEquals("", PowerLine.zeroHitFlag(sp("{\"id\":\"g\",\"damage\":1,\"powers\":[\"friendly\"],\"base\":\"guardian\"}"), 1));
    }
}
