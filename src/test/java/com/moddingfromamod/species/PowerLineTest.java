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
        assertTrue(PowerLine.likeVanilla(100, 15).startsWith("an iron golem"), PowerLine.likeVanilla(100, 15));
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
        assertTrue(line.startsWith("Power: 60 health, 6 damage, 4 armour, knockback resistance 0.5: like an elder guardian"), line);
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

    @Test
    void theAdvisorySaysFarAboveItsSizeAndNeverRefusesOrFlagsABoss() {
        PowerBand.Band small = PowerBand.forVolume(0.5);
        // at the band's most: nothing; just above: said
        assertEquals("", PowerLine.farAbove(small.maxHealth(), small.maxDamage(), 0, small, false));
        assertTrue(PowerLine.farAbove(small.maxHealth() + 1, 3, 0, small, false).contains("too tough for its size"));
        assertTrue(PowerLine.farAbove(20, small.maxDamage() + 1, 0, small, false).contains("hits too hard"));
        // below the band is never flagged; a boss never is
        assertEquals("", PowerLine.farAbove(1, 0, 0, small, false));
        assertEquals("", PowerLine.farAbove(500, 40, 30, small, true));
        // health and armour together: above twice the band's most (iron-golem territory for a small body)
        assertEquals("", PowerLine.farAbove(40, 5, 6, small, false), "the stock robot (40, armour 6) is fine");
        assertTrue(PowerLine.farAbove(40, 5, 30, small, false).contains("health and armour together"));
        // the smoke's cases: a man-sized knight at 80/8 with 12 armour is flagged; a giant dinosaur at 300 is not
        String knight = PowerLine.farAbove(80, 8, 12, small, false);
        assertTrue(knight.startsWith("⚠ That is far above its size (small") && knight.contains("'boss' power")
                && knight.contains("Nothing was refused"), knight);
        assertEquals("", PowerLine.farAbove(300, 12, 0, PowerBand.forVolume(40), false));
    }

    @Test
    void noReviewedStockCreatureIsFlagged() throws Exception {
        for (StockStatLinesTest.Stock s : StockStatLinesTest.stock()) {
            Species sp = Species.fromJson(s.species());
            if (s.vehicle()) {
                sp = sp.withBehaves(Archetype.VEHICLE);   // as every Builder vehicle is made (BuilderVehiclesTest)
            }
            assertEquals("", PowerLine.farAbove(sp, PowerBand.volume(boxes(s), 1.0)), s.id());
        }
    }

    private static java.util.List<double[]> boxes(StockStatLinesTest.Stock s) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (var p : s.sculpt().getAsJsonArray("parts")) {
            var f = p.getAsJsonObject().getAsJsonArray("from");
            var t = p.getAsJsonObject().getAsJsonArray("to");
            out.add(new double[] {f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(),
                    t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()});
        }
        return out;
    }

    @Test
    void aRealMobIsMeasuredOnItsBox() {
        // a zombie's box (0.6 x 1.95) is small, an iron golem's (1.4 x 2.7) and a ravager's (1.95 x 2.2) are large
        assertEquals("small", PowerBand.forVolume(PowerLine.boxVolume(0.6, 1.95)).name());
        assertEquals("large", PowerBand.forVolume(PowerLine.boxVolume(1.4, 2.7)).name());
        assertEquals("large", PowerBand.forVolume(PowerLine.boxVolume(1.95, 2.2)).name());
        assertEquals("tiny", PowerBand.forVolume(PowerLine.boxVolume(0.4, 0.5)).name());
    }
}
