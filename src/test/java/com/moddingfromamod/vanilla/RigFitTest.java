package com.moddingfromamod.vanilla;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/** Gate A: the fit instrument's arithmetic, the centring, and the classified pool. */
class RigFitTest {

    static CreatureSculpt.Part box(String name, float[] from, float[] to) {
        return new CreatureSculpt.Part(name, null, new float[]{0, 0, 0}, from, to, 0xFFFFFF);
    }

    static List<CreatureSculpt.Part> fixture(String set, String id) throws Exception {
        try (Reader r = Files.newBufferedReader(
                Path.of("src/test/resources/rig-fit", set, id + ".json"))) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            return CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
        }
    }

    @Test
    void anUprightBoxIsMeasuredExactlyAsDrawn() {
        var parts = List.of(box("body", new float[]{-4, 0, -8}, new float[]{4, 16, 8}));
        RigFit.Box b = RigFit.restBounds(RigTransform.mirrorIntoWorldSpace(parts), 2.0);
        assertEquals(-0.5, b.minX(), 1e-4);
        assertEquals(0.5, b.maxX(), 1e-4);
        assertEquals(0.0, b.minY(), 1e-4);
        assertEquals(2.0, b.maxY(), 1e-4);
        assertEquals(-1.0, b.minZ(), 1e-4);
        assertEquals(1.0, b.maxZ(), 1e-4);
    }

    @Test
    void aTiltedBoxIsMeasuredOnItsRotatedCornersNotItsFromAndTo() {
        var tilted = new CreatureSculpt.Part("fin", null, new float[]{0, 0, 0},
                new float[]{-8, 0, -1}, new float[]{8, 2, 1}, 0xFFFFFF,
                new float[]{0, 45, 0}, 0f, "", 0);
        RigFit.Box b = RigFit.restBounds(List.of(tilted), 1.0);
        // a 16-long bar turned 45° about Y reaches 8·cos45 + 1·sin45 = 6.36 units each way
        assertEquals(6.364 / 16, b.maxX(), 2e-3);
        assertEquals(6.364 / 16, b.maxZ(), 2e-3);
    }

    @Test
    void theWhaleAuthoredFromItsTailIsCentredOnItsMass() throws Exception {
        var whale = fixture("synthetic", "whale_tail_origin");
        float[] before = RigFit.massCentreUnits(RigTransform.mirrorIntoWorldSpace(whale));
        assertTrue(Math.abs(before[1]) > 20, "the fixture must reproduce the video: its mass is "
                + "far from its origin, got " + before[1]);
        var centred = RigFit.intoRigSpace(whale);
        float[] after = RigFit.massCentreUnits(centred);
        assertEquals(0f, after[0], 1f / 16);
        assertEquals(0f, after[1], 1f / 16);
        // Y is never moved: the feet stay on the ground.
        RigFit.Box a = RigFit.restBounds(RigTransform.mirrorIntoWorldSpace(whale), 1.0);
        RigFit.Box c = RigFit.restBounds(centred, 1.0);
        assertEquals(a.minY(), c.minY(), 1e-6);
        assertEquals(a.maxY(), c.maxY(), 1e-6);
        // and a centred creature is left exactly as it was.
        assertEquals(centred, RigFit.centreFootprint(centred));
    }

    @Test
    void aLandmarkIsReportedFromWhereTheCarrierNowStands() throws Exception {
        var whale = fixture("synthetic", "whale_tail_origin");
        double scale = 1.5;
        var marks = CreatureAnatomy.landmarks(RigGeometry.of(whale), scale);
        var head = marks.stream().filter(m -> m.name().contains("head")).findFirst()
                .or(() -> marks.stream().findFirst());
        assertTrue(head.isPresent(), "the whale has named parts: " + marks);
        double shifted = head.get().forward() + RigFit.forwardShiftBlocks(whale, scale);
        var fromCarrier = CreatureAnatomy.landmarks(
                RigGeometry.of(RigFit.shift(whale, 0,
                        -RigFit.massCentreUnits(RigTransform.mirrorIntoWorldSpace(whale))[1])),
                scale).stream().filter(m -> m.name().equals(head.get().name())).findFirst();
        assertEquals(fromCarrier.orElseThrow().forward(), shifted, 0.02);
        assertTrue(shifted < head.get().forward(), "the head is nearer the carrier than the tail was");
    }

    @Test
    void theRaysSeeAWholeHitAndNoPhantomWhenTheBoxIsTheCreature() {
        var parts = List.of(box("body", new float[]{-8, 0, -8}, new float[]{8, 16, 8}));
        var atoms = RigFit.atoms(RigPlan.atoms(RigFit.intoRigSpace(parts)), 1.0);
        double[] same = RigFit.rays(atoms, RigFit.Box.carrier(1.0, 1.0));
        assertEquals(1.0, same[0], 1e-9);
        assertEquals(0.0, same[1], 0.02);
        double[] half = RigFit.rays(atoms, RigFit.Box.carrier(0.5, 1.0));
        assertTrue(half[0] < 0.9, "a box half as wide misses some aimed clicks: " + half[0]);
        double[] big = RigFit.rays(atoms, RigFit.Box.carrier(2.0, 2.0));
        assertTrue(big[1] > 0.3, "a box twice as big catches clicks on air: " + big[1]);
    }

    @Test
    void movementClassComesFromPowersThenAnatomy() throws Exception {
        assertEquals(CarrierPool.Move.SWIM, CarrierPool.movementOf(Set.of("friendly"),
                fixture("synthetic", "whale_tail_origin"), "Blue Whale"));
        assertEquals(CarrierPool.Move.FLY, CarrierPool.movementOf(Set.of("FLIES"),
                fixture("synthetic", "whale_tail_origin"), "whale"));
        // a shark in trainers walks: legs win over a fin and a sea creature's name
        assertEquals(CarrierPool.Move.WALK, CarrierPool.movementOf(Set.of(),
                fixture("real", "tralalelo-0f38c5f5"), "Tralalero Tralala shark"));
        var hand = List.of(box("fingerL", new float[]{0, 0, 0}, new float[]{1, 1, 1}));
        assertEquals(CarrierPool.Move.WALK, CarrierPool.movementOf(Set.of(), hand, "gray blob"),
                "'finger' is not a fin and 'gray' is not a ray");
        assertEquals(CarrierPool.Move.SWIM, CarrierPool.movementOf(Set.of(),
                List.of(box("body", new float[]{0, 0, 0}, new float[]{1, 1, 1})), "an eel"));
    }

    @Test
    void theWaterOnlySwimmersAreOfferedOnlyInWater() {
        var land = CarrierPool.allowed(CarrierPool.Move.SWIM, false);
        var sea = CarrierPool.allowed(CarrierPool.Move.SWIM, true);
        assertFalse(land.contains("dolphin") || land.contains("squid"), land.toString());
        assertTrue(land.contains("turtle") && land.contains("frog"), land.toString());
        assertTrue(sea.containsAll(List.of("dolphin", "squid", "turtle", "frog")), sea.toString());
        assertFalse(CarrierPool.allowed(CarrierPool.Move.WALK, false).contains("bat"));
        assertEquals(List.of("bat"), CarrierPool.allowed(CarrierPool.Move.FLY, false));
    }

    @Test
    void noCarrierThatCanBeTakenAwayFromTheChildIsInThePool() {
        for (String gone : List.of("bee", "mooshroom", "sniffer", "parrot", "allay", "axolotl",
                "cod", "salmon", "tropical_fish", "pufferfish", "tadpole", "glow_squid", "fox",
                "chicken", "sheep", "cat", "rabbit", "snow_golem", "villager", "strider")) {
            assertEquals(null, CarrierPool.byId(gone), gone + " must not be a carrier");
        }
        for (CarrierPool.Skeleton s : CarrierPool.ALL) {
            assertFalse(s.note().isBlank(), s.id() + " must say what it does besides carry");
        }
    }

    @Test
    void theFitNeverPicksAHostileCarrier() throws Exception {
        var whale = RigFit.intoRigSpace(fixture("synthetic", "whale_tail_origin"));
        RigFit.Box rig = RigFit.restBounds(whale, 1.5);
        RigBase.Choice c = RigBase.chooseByFit(CarrierPool.candidates(), rig, null);
        assertNotNull(c);
        assertFalse(CarrierPool.byId(c.id()).hostile(), c.id());
        RigBase.Choice onLand = RigBase.chooseByFit(CarrierPool.candidates(), rig,
                CarrierPool.allowed(CarrierPool.Move.SWIM, false));
        assertTrue(Set.of("turtle", "frog").contains(onLand.id()), onLand.id());
    }

    /**
     * <b>Every driver is handed RIG space, never merely-mirrored parts.</b> A rig's displays
     * are placed from {@link RigFit#intoRigSpace}; a driver handed {@code mirrorIntoWorldSpace}
     * poses it from an uncentred plan, and a creature authored off its origin tears apart on its
     * first animated frame. Three merges in a row each brought new call sites
     * written the old way — ten in all — so this is a sweep rather than a memory.
     */
    @Test
    void noDriverIsHandedMerelyMirroredParts() throws Exception {
        java.util.regex.Pattern bad = java.util.regex.Pattern.compile(
                "\\b(drive|animate|tick|compose|articulate)\\s*\\([^;]*mirrorIntoWorldSpace\\s*\\(");
        List<String> hits = new java.util.ArrayList<>();
        try (var files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f).replaceAll("//[^\\n]*", "")
                        .replaceAll("\\s+", " ");
                var m = bad.matcher(src);
                while (m.find()) {
                    hits.add(f + ": ..." + m.group().substring(0, Math.min(120, m.group().length())));
                }
            }
        }
        assertTrue(hits.isEmpty(), "use RigFit.intoRigSpace(parts), not mirrorIntoWorldSpace, "
                + "wherever parts feed a driver or a pose:\n" + String.join("\n", hits));
    }

    /**
     * the name plate floats over the creature's HEAD, and a carrier chosen by fit
     * can be shorter than the creature. Found by the first rig-films run: Spikor's plate inside his
     * crown, 4.12 blocks up a creature drawn ~4.7 tall.
     */
    @Test
    void theHeadIsTheHigherOfTheCarrierAndTheDrawnRig() {
        assertEquals(4.7, RigFit.headTop(4.12, 4.7), 1e-9, "a creature taller than its carrier");
        assertEquals(2.7, RigFit.headTop(2.7, 1.9), 1e-9, "a carrier taller than its creature");
        assertEquals(1.4, RigFit.headTop(1.4, 0), 1e-9, "a rig with no atoms keeps vanilla's rule");
    }

    @Test
    void theNamePlateIsPlacedOverTheHeadNotOverTheCarriersBox() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/vanilla/DisplayRig.java"));
        int at = src.indexOf("private double plateY()");
        String body = src.substring(at, src.indexOf("}", at));
        assertTrue(body.contains("RigFit.headTop("), "plateY must float the plate over RigFit.headTop, "
                + "not the carrier's box alone (#992): " + body);
    }
}
