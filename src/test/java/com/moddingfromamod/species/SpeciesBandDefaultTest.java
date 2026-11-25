package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A creature with no stats of its own gets ones that fit its size</b> (#100). {@code Species.fromJson} used to give
 * every such creature a flat 20 health and 3 damage, a hamster and a whale alike. Now an ABSENT field takes its
 * {@link PowerBand}'s default; a number the record states, 0 included, is always kept.
 */
class SpeciesBandDefaultTest {

    private static Species read(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static Species read(String json, double volume) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject(), volume);
    }

    @Test
    void aStatedNumberIsAlwaysKeptZeroIncluded() {
        Species s = read("{\"id\":\"a\",\"health\":7,\"damage\":0}", 100);
        assertEquals(7, s.health(), 1e-9);
        assertEquals(0, s.damage(), 1e-9);
    }

    @Test
    void aPlainHumanoidWithNoNumbersIsSmall() {
        // a zombie-shaped body at scale 1 is small: 18 health, 3 damage (the old flat default was 20/3)
        PowerBand.Band small = PowerBand.forVolume(0.5);
        Species s = read("{\"id\":\"a\"}");
        assertEquals(small.health(), s.health(), 1e-9);
        assertEquals(small.damage(), s.damage(), 1e-9);
    }

    @Test
    void theSculptedBodyAndItsScaleDecide() {
        // one cubic block of sculpt: medium; the same body at scale 3 is 27: huge
        Species medium = read("{\"id\":\"a\"}", 1.0);
        assertEquals(PowerBand.forVolume(1.0).health(), medium.health(), 1e-9);
        Species huge = read("{\"id\":\"a\",\"scale\":3}", 1.0);
        assertEquals(PowerBand.forVolume(27).health(), huge.health(), 1e-9);
        assertEquals(PowerBand.forVolume(27).damage(), huge.damage(), 1e-9);
        // a tiny sculpt with no scale: tiny
        Species tiny = read("{\"id\":\"a\"}", 0.05);
        assertEquals(PowerBand.forVolume(0.05).health(), tiny.health(), 1e-9);
    }

    @Test
    void withNoSculptItsModelShapeIsMeasured() {
        assertEquals("tiny", PowerBand.forCreature(Double.NaN, Species.Body.BIRD, 1).name());
        assertEquals("tiny", PowerBand.forCreature(Double.NaN, Species.Body.SLIME, 1).name());
        assertEquals("small", PowerBand.forCreature(Double.NaN, Species.Body.HUMANOID, 1).name());
        assertEquals("small", PowerBand.forCreature(Double.NaN, Species.Body.QUADRUPED, 1).name());
        assertEquals("small", PowerBand.forCreature(Double.NaN, Species.Body.SPIDER, 1).name());
        assertEquals(PowerBand.forVolume(PowerBand.bodyVolume(Species.Body.BIRD)).health(),
                read("{\"id\":\"a\",\"body\":\"bird\"}").health(), 1e-9);
    }

    @Test
    void onlyTheAbsentFieldTakesTheDefault() {
        Species s = read("{\"id\":\"a\",\"health\":42}", 100);
        assertEquals(42, s.health(), 1e-9);
        assertEquals(PowerBand.forVolume(100).damage(), s.damage(), 1e-9);
        Species t = read("{\"id\":\"a\",\"damage\":2}", 100);
        assertEquals(PowerBand.forVolume(100).health(), t.health(), 1e-9);
        assertEquals(2, t.damage(), 1e-9);
        // a number written as a word is a number; a word that is not one is absent
        assertEquals(9, read("{\"id\":\"a\",\"damage\":\"9\"}", 100).damage(), 1e-9);
        assertEquals(PowerBand.forVolume(100).damage(), read("{\"id\":\"a\",\"damage\":\"lots\"}", 100).damage(), 1e-9);
    }

    @Test
    void aBossHasMoreHealthAndAVehicleNoHit() {
        Species boss = read("{\"id\":\"a\",\"powers\":[\"boss\"]}", 20);
        assertEquals(PowerBand.forVolume(20).health() * PowerBand.BOSS_HEALTH, boss.health(), 1e-9);
        Species car = read("{\"id\":\"a\",\"behaves\":\"vehicle\",\"powers\":[\"rideable\",\"friendly\"]}", 4);
        assertEquals(0, car.damage(), 1e-9);
        assertEquals(PowerBand.forVolume(4).health(), car.health(), 1e-9);
    }

    @Test
    void theDefaultIsInsideWhatTheGameAllows() {
        Species s = read("{\"id\":\"a\",\"scale\":8,\"powers\":[\"boss\"]}", 400);
        assertEquals(true, s.health() <= Species.MAX_HEALTH && s.damage() <= Species.MAX_DAMAGE, s.toString());
    }

    @Test
    void writtenOutAndReadBackItKeepsItsNumbers() {
        Species s = read("{\"id\":\"a\"}", 30);
        JsonObject o = s.toJson();
        Species back = read(o.toString());
        assertEquals(s.health(), back.health(), 1e-9);
        assertEquals(s.damage(), back.damage(), 1e-9);
    }
}
