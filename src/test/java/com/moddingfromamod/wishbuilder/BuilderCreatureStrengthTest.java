package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/** {@link BuilderCreatureStrength} and its lowering (#103): size and How strong move health and damage. */
class BuilderCreatureStrengthTest {

    private static BuilderMobSizes.Level size(String id) {
        return BuilderMobSizes.of(id);
    }

    @Test
    void normalAtItsOwnStrengthIsMadeAsBefore() {
        assertEquals(30, BuilderCreatureStrength.health(30, size("4"), 2));
        assertEquals(6, BuilderCreatureStrength.damage(6, size("4"), 2, false, 8, false));
    }

    @Test
    void biggerIsTougherAndHitsHarderAndEverySizeStepMovesThem() {
        double lastH = 0;
        double lastD = 0;
        for (BuilderMobSizes.Level l : BuilderMobSizes.ALL) {
            double h = BuilderCreatureStrength.health(20, l, 2);
            double d = BuilderCreatureStrength.damage(4, l, 2, false, 0, false);
            assertTrue(h > lastH && d > lastD, l.name() + ": " + h + "/" + d);
            lastH = h;
            lastD = d;
        }
        // a colossal hamster is no longer a hamster's 6: Teeny x0.4 to Colossal x5 health, x0.6 to x2.5 damage
        assertEquals(30, BuilderCreatureStrength.health(6, size("8"), 2));
        assertEquals(2.5, BuilderCreatureStrength.damage(1, size("8"), 2, false, 0, false));
        assertEquals(2.5, BuilderCreatureStrength.health(6, size("1"), 2));
    }

    @Test
    void howStrongMovesBoth() {
        assertEquals(30, BuilderCreatureStrength.health(40, size("4"), 1));
        assertEquals(60, BuilderCreatureStrength.health(40, size("4"), 3));
        assertEquals(3, BuilderCreatureStrength.damage(6, size("4"), 1, false, 0, false));
        assertEquals(9, BuilderCreatureStrength.damage(6, size("4"), 3, false, 0, false));
    }

    @Test
    void itStaysInsideWhatTheGameAllows() {
        assertEquals(500, BuilderCreatureStrength.health(200, size("8"), 3));
        assertEquals(40, BuilderCreatureStrength.damage(15, size("8"), 3, false, 0, false));
        assertEquals(1, BuilderCreatureStrength.health(1, size("1"), 1));
    }

    @Test
    void aGuardAlwaysHitsForItsBandAndAVehicleNever() {
        assertEquals(8, BuilderCreatureStrength.damage(0, size("4"), 2, true, 8, false), "a guard on a 0-damage body");
        assertEquals(12, BuilderCreatureStrength.damage(12, size("4"), 2, true, 8, false), "its own when it is more");
        assertEquals(0, BuilderCreatureStrength.damage(0, size("4"), 3, true, 8, true), "a vehicle never hits");
    }

    @Test
    void anUnstatedStrengthIsItsOwnAndAnOldSizeIsItsNamedOne() {
        BuilderSpec plain = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/bear", "", 2,
                List.of(), "friendly", 1, "", "", Map.of());
        assertEquals(2, BuilderCreatureStrength.strengthOf(plain));
        assertEquals("4", BuilderCreatureStrength.sizeOf(plain).id());
        BuilderSpec big = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/bear", "", 4,
                List.of(), "friendly", 1, "", "", Map.of("mobsize", "8", "strength", "3"));
        assertEquals(3, BuilderCreatureStrength.strengthOf(big));
        assertEquals("8", BuilderCreatureStrength.sizeOf(big).id());
    }

    @Test
    void theLoweringSendsTheScaledNumbersAndAGuardThatHits() {
        // a colossal strong bear: 30 x5 x1.5 health (capped at 500 is not reached: 225), 6 x2.5 x1.5 damage
        BuilderSpec bear = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/bear", "", 4,
                List.of(), "friendly", 1, "", "", Map.of("mobsize", "8", "strength", "3"));
        JsonObject sculpt = sculptOf(bear);
        assertEquals(225, sculpt.get("health").getAsDouble(), 1e-9);
        assertEquals(22.5, sculpt.get("damage").getAsDouble(), 1e-9);
        // a guard car hits nothing; a guard hamster hits for its band
        BuilderSpec guardHamster = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/hamster", "", 2,
                List.of(), "guard", 1, "", "", Map.of());
        assertTrue(sculptOf(guardHamster).get("damage").getAsDouble() >= 1, sculptOf(guardHamster).toString());
        BuilderSpec guardCar = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.RIDE, "preset:bodies/car", "", 2,
                List.of(), "guard", 1, "", "", Map.of());
        assertEquals(0, sculptOf(guardCar).get("damage").getAsDouble(), 1e-9);
        // and the facts follow: a world sees the scaled numbers and the strength it can lower
        MakeFacts f = MakeFacts.of(bear);
        assertEquals(225, f.number(MakeFacts.HEALTH_HALF_HEARTS).orElse(-1), 1e-9);
        assertEquals(3, f.number(MakeFacts.BUILDER_STRENGTH_STEP).orElse(-1), 1e-9);
        BuilderSpec plain = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/bear", "", 2,
                List.of(), "friendly", 1, "", "", Map.of());
        assertEquals(2, MakeFacts.of(plain).number(MakeFacts.BUILDER_STRENGTH_STEP).orElse(-1), 1e-9);
    }

    @Test
    void aWorldCanFitACreatureByItsStrength() {
        BuilderSpec bear = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/bear", "", 2,
                List.of(), "friendly", 1, "", "", Map.of());
        MakeFit.Fitted gentle = MakeFit.fit(bear, Map.of(MakeFacts.BUILDER_STRENGTH_STEP, 1.0));
        assertTrue(gentle.fits() && gentle.changed() && gentle.spec().opt("strength").equals("1"), gentle.toString());
        // a limit on its damage is fitted on the live creature, the make unchanged, so a later raise gives it all back
        double was = MakeFacts.of(bear).number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1);
        MakeFit.Fitted held = MakeFit.fit(bear, Map.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, was - 1));
        assertTrue(held.fits() && held.spec().equals(bear) && held.lowered().equals(List.of(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS)),
                "fitted live: " + held);
    }

    private static JsonObject sculptOf(BuilderSpec spec) {
        for (BuilderLowering.Call c : BuilderLowering.lower(spec, "t_1", LibraryPresets.SHIPPED)) {
            if (c.tool().equals("sculpt_creature")) {
                return c.args();
            }
        }
        throw new AssertionError("no sculpt for " + spec);
    }
}
