package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** the archetype table and each archetype's life, decided purely. */
class ArchetypeTest {

    private static ArchetypeLife.World world(double x, double y, double z, double ground, boolean water,
                                             double riderDist) {
        return new ArchetypeLife.World(x, y, z, 0, ground, 0, ground, (a, b) -> ground,
                10, ground + 6, 0, Double.NaN, Double.NaN, water,
                riderDist > 0 ? riderDist : Double.NaN, ground, 0, riderDist > 0 ? riderDist : Double.NaN, false, 0);
    }

    @Test
    void theVanillaMobsTheAgentNamesReadEveryWay() {
        assertEquals(Archetype.PACK_HUNTER, Archetype.resolve("wolf"));
        assertEquals(Archetype.PACK_HUNTER, Archetype.resolve("minecraft:wolf"));
        assertEquals(Archetype.DRAGON, Archetype.resolve("dragon"));
        assertEquals(Archetype.DRAGON, Archetype.resolve("ender_dragon"));
        assertEquals(Archetype.ARCHER, Archetype.resolve("Skeleton"));
        assertEquals(Archetype.CLIMBER, Archetype.resolve("cave spider"));
        assertEquals(Archetype.VILLAGER, Archetype.resolve("villager_like"));
        assertNull(Archetype.resolve("unicorn"), "not a mob, not invented: the caller asks the registry or refuses");
        assertNull(Archetype.resolve(""));
    }

    @Test
    void everyArchetypeHasAVanillaMobAndAWordAndALine() {
        for (Archetype a : Archetype.values()) {
            assertTrue(Archetype.VANILLA.containsValue(a) || a == Archetype.DRAGON || a == Archetype.VEHICLE,
                    a + " has no vanilla mob");
            assertSame(a, Archetype.byKey(a.key()));
            assertFalse(a.says().isBlank());
        }
        for (String stand : Archetype.NOT_YET) {
            assertNotNull(Archetype.VANILLA.get(stand), stand + " is listed as a stand-in but maps to nothing");
        }
    }

    @Test
    void aModdedMobFindsTheNearestBuiltArchetypeByWhatItIs() {
        assertEquals(Archetype.BUZZER, Archetype.byTraits(true, false, true));
        assertEquals(Archetype.SWIMMER, Archetype.byTraits(false, true, false));
        assertEquals(Archetype.BRUISER, Archetype.byTraits(false, false, true));
        assertEquals(Archetype.GRAZER, Archetype.byTraits(false, false, false));
    }

    @Test
    void aFlyingLifeMakesItsSpeciesFly() {
        Species s = new Species("d", "D", null, null, 0, 1, 20, 0.25, 3, 0, 0, Set.of(), "",
                Species.Base.WILD, Set.of(), Set.of());
        Species d = s.withBehaves(Archetype.DRAGON);
        assertEquals("dragon", d.behaves());
        assertTrue(d.has(Species.Power.FLIES), "a dragon that cannot fly is not living a dragon's life");
        assertFalse(s.withBehaves(Archetype.GRAZER).has(Species.Power.FLIES));
        assertEquals(Archetype.DRAGON, Species.fromJson(JsonParser.parseString(d.toJson().toString())
                .getAsJsonObject()).archetype(), "the archetype must survive the species file");
        assertFalse(s.toJson().has("behaves"), "a creature with no archetype writes the file it always did");
        assertEquals("", s.withBehaves(Archetype.DRAGON).withBehaves(null).behaves());
    }

    @Test
    void aDragonCirclesHighOverHomeAndSometimesPerches() {
        Random r = new Random(7);
        int high = 0, perch = 0;
        for (int i = 0; i < 200; i++) {
            var in = ArchetypeLife.next(Archetype.DRAGON, world(12, 70, 0, 60, false, -1), r);
            if (in.act() == ArchetypeLife.Act.PERCH) {
                perch++;
                assertEquals(67, in.y(), 0.01, "it perches on the highest spot near home");
                continue;
            }
            assertEquals(ArchetypeLife.Act.FLY, in.act());
            double above = in.y() - 60;
            assertTrue(above >= 9 && above <= 15, "a dragon circles 9-15 above the ground, not " + above);
            assertEquals(12, Math.hypot(in.x(), in.z()), 0.01, "round its home at its circle's radius");
            high++;
        }
        assertTrue(perch > 0 && perch < high, "it should perch now and then, not always: " + perch + "/" + high);
    }

    @Test
    void aBuzzerFlitsLowNearHomeAndLandsNowAndThen() {
        Random r = new Random(3);
        int landed = 0;
        for (int i = 0; i < 200; i++) {
            var in = ArchetypeLife.next(Archetype.BUZZER, world(0, 62, 0, 60, false, -1), r);
            assertTrue(Math.hypot(in.x(), in.z()) <= Archetype.BUZZER.life().wanderRadius() + 0.01);
            if (in.act() == ArchetypeLife.Act.PERCH) {
                landed++;
                continue;
            }
            double above = in.y() - 60;
            assertTrue(above >= 1 && above <= 4, "a buzzer flits 1-4 above the ground, not " + above);
        }
        assertTrue(landed > 10 && landed < 100, "it should land now and then: " + landed);
    }

    @Test
    void aGrazerMostlyStandsAndKeepsNearItsHerd() {
        Random r = new Random(5);
        int stood = 0;
        double sumX = 0;
        int walks = 0;
        for (int i = 0; i < 400; i++) {
            var w = new ArchetypeLife.World(0, 60, 0, 0, 60, 0, 60, (a, b) -> 60, Double.NaN, Double.NaN, Double.NaN,
                    20, 0, false, Double.NaN, 0, 0, Double.NaN, false, 0);
            var in = ArchetypeLife.next(Archetype.GRAZER, w, r);
            if (in.act() == ArchetypeLife.Act.STAND) {
                stood++;
            } else {
                assertEquals(ArchetypeLife.Act.WALK, in.act());
                sumX += in.x();
                walks++;
            }
        }
        assertTrue(stood > 160, "a grazer spends most of its time standing: " + stood + "/400");
        assertTrue(sumX / walks > 8, "it wanders towards its herd (at x=20) from home (x=0): mean " + sumX / walks);
    }

    @Test
    void aSwimmerNeverWandersOntoLand() {
        Random r = new Random(1);
        assertEquals(ArchetypeLife.Act.STAND, ArchetypeLife.next(Archetype.SWIMMER, world(0, 60, 0, 60, false, -1), r).act());
        for (int i = 0; i < 50; i++) {
            assertEquals(ArchetypeLife.Act.SWIM, ArchetypeLife.next(Archetype.SWIMMER, world(0, 58, 0, 55, true, -1), r).act());
        }
    }

    @Test
    void aMountComesOverToItsRiderWhenHeIsAbout() {
        Random r = new Random(2);
        int came = 0;
        for (int i = 0; i < 100; i++) {
            if (ArchetypeLife.next(Archetype.MOUNT, world(0, 60, 0, 60, false, 10), r).act() == ArchetypeLife.Act.TO_RIDER) {
                came++;
            }
        }
        assertTrue(came > 15 && came < 70, "a mount drifts over to him sometimes: " + came);
        for (int i = 0; i < 50; i++) {
            assertFalse(ArchetypeLife.next(Archetype.GRAZER, world(0, 60, 0, 60, false, 10), r).act() == ArchetypeLife.Act.TO_RIDER,
                    "a grazer does not come to anyone");
        }
    }

    @Test
    void anArcherKeepsItsDistanceAndStrafes() {
        double[] back = ArchetypeLife.keepDistance(2, 0, 0, 0, true);
        assertTrue(Math.hypot(back[0], back[1]) >= ArchetypeLife.ARCHER_NEAR, "too close: it backs off");
        double[] in = ArchetypeLife.keepDistance(30, 0, 0, 0, true);
        assertTrue(Math.hypot(in[0], in[1]) <= ArchetypeLife.ARCHER_FAR, "too far: it closes in");
        double[] side = ArchetypeLife.keepDistance(9, 0, 0, 0, true);
        assertEquals(9, side[0], 0.01, "in range: it strafes sideways round the target");
        assertTrue(Math.abs(side[1]) > 2);
    }

    @Test
    void aClimberGoesUpAWallAndASwooperComesFromAbove() {
        assertEquals(ArchetypeLife.CLIMB_SPEED, ArchetypeLife.climb(true, true));
        assertTrue(Double.isNaN(ArchetypeLife.climb(true, false)), "against a wall but not going anywhere");
        assertTrue(Double.isNaN(ArchetypeLife.climb(false, true)));
        assertEquals(4, ArchetypeLife.swoopHeight(12));
        assertEquals(0, ArchetypeLife.swoopHeight(3), "close: it dives");
    }

    @Test
    void movesComeFromTheFightNotTheLife() {
        assertEquals(Set.of(com.moddingfromamod.runtime.Abilities.Kind.RANGED), Archetype.ARCHER.impliedAbilities());
        assertEquals(Set.of(com.moddingfromamod.runtime.Abilities.Kind.RANGED), Archetype.DRAGON.impliedAbilities());
        assertTrue(Archetype.GRAZER.impliedAbilities().isEmpty());
        // A peaceful dragon gains no fire (checked before any carrier is touched).
        Species friendly = new Species("p", "P", null, null, 0, 1, 20, 0.25, 3, 0, 0, Set.of(Species.Power.FRIENDLY), "",
                Species.Base.COMPANION, Set.of(), Set.of()).withBehaves(Archetype.DRAGON);
        assertSame(friendly, com.moddingfromamod.vanilla.CreatureLife.withImpliedMoves(null, friendly));
    }

    @Test
    void anArchetypeCreatureNeverRidesABrain() {
        var pool = java.util.List.of("cow", "camel", "goat", "horse", "frog", "armadillo", "panda");
        assertEquals(java.util.List.of("cow", "horse"), com.moddingfromamod.vanilla.CarrierPool.forArchetype(pool, true));
        assertEquals(pool, com.moddingfromamod.vanilla.CarrierPool.forArchetype(pool, false));
    }

    @Test
    void aVehicleStaysParkedIsRigidAndIsDriven() {
        Random r = new Random(9);
        for (int i = 0; i < 50; i++) {
            var in = ArchetypeLife.next(Archetype.VEHICLE, world(3, 70, 4, 60, false, 8), r);
            assertEquals(ArchetypeLife.Act.PARK, in.act(), "a vehicle never wanders, flits or comes over by itself");
            assertEquals(3, in.x(), 0.01);
            assertEquals(60, in.y(), 0.01, "parked on the ground under it: a plane comes down");
        }
        assertTrue(Archetype.VEHICLE.rigid());
        assertFalse(Archetype.DRAGON.rigid());
        assertEquals(Set.of(Species.Power.RIDEABLE), Archetype.VEHICLE.impliedPowers(), "a vehicle he cannot drive is a statue");
        assertTrue(Archetype.VEHICLE.impliedAbilities().isEmpty());
        assertEquals(Archetype.VEHICLE, Archetype.resolve("minecart"));
        assertEquals(Archetype.VEHICLE, Archetype.resolve("Boat"));
        assertEquals(Archetype.VEHICLE, Archetype.resolve("vehicle"));
        Species car = new Species("c", "Car", null, null, 0, 1, 20, 0.25, 3, 0, 0, Set.of(), "",
                Species.Base.WILD, Set.of(), Set.of()).withBehaves(Archetype.VEHICLE);
        assertSame(com.moddingfromamod.vanilla.RigInstinct.NONE, com.moddingfromamod.vanilla.RigInstinct.of(car),
                "a car has no instincts, whatever its temperament says");
        assertTrue(car.has(Species.Power.RIDEABLE));
    }
}
