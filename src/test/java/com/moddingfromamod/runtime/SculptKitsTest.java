package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Part-kits — precomputed morphology, the one fidelity change that makes wishes shorter. */
class SculptKitsTest {

    private static final List<CreatureSculpt.Part> BODY = CreatureSculpt.parseParts(
            JsonParser.parseString("""
                    [{"name":"body","from":[-8,10,-6],"to":[8,32,6],"colour":"green"},
                     {"name":"head","from":[-5,32,-4],"to":[5,42,4],"colour":"tan"}]""")
                    .getAsJsonArray());

    private static JsonArray kits(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void everyKitInTheVocabularyBuildsSomething() {
        for (String kit : SculptKits.NAMES) {
            var made = SculptKits.expand(
                    kits("[{\"kit\":\"" + kit + "\",\"on\":\"body\",\"colour\":\"white\"}]"), BODY);
            assertFalse(made.isEmpty(), kit + " built nothing");
        }
    }

    /**
     * The whole point of the kit system. If a kit's boxes come out axis-aligned then it is just a
     * more expensive way to write the staircase we already had.
     */
    @Test
    void kitsEmitTiltedGeometryWhichIsTheReasonTheyExist() {
        for (String kit : SculptKits.NAMES) {
            var made = SculptKits.expand(
                    kits("[{\"kit\":\"" + kit + "\",\"on\":\"body\",\"colour\":\"white\"}]"), BODY);
            assertTrue(made.stream().anyMatch(CreatureSculpt.Part::rotated),
                    kit + " produced only axis-aligned boxes — that is the bug it exists to fix");
        }
    }

    @Test
    void everyKitPartIsDrawableSoNoneAreSilentlyDroppedLater() {
        // parseParts throws away a box whose sides are under 1 apart. A kit that emitted one would
        // give a child six spikes where seven were asked for, and say nothing about the seventh.
        for (String kit : SculptKits.NAMES) {
            for (var p : SculptKits.expand(
                    kits("[{\"kit\":\"" + kit + "\",\"on\":\"body\",\"count\":7}]"), BODY)) {
                assertTrue(p.width() >= 1 && p.height() >= 1 && p.depth() >= 1,
                        kit + " emitted a box that draws nothing: " + p.name());
                assertFalse(p.name().isEmpty(), kit + " emitted an unnamed bone");
            }
        }
    }

    @Test
    void spikeRowBuildsTwoBoxesPerSpikeUpToTheCap() {
        // Two boxes per spike: a base and a narrower tip, which is what makes it taper.
        var five = SculptKits.expand(
                kits("[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":5,\"colour\":\"white\"}]"),
                BODY);
        assertEquals(10, five.size());
        assertTrue(five.stream().allMatch(p -> p.name().startsWith(SculptKits.MARK + "spike_row")));

        // ...but BOXES are what is capped, not `count`. A spike being two boxes is exactly how
        // count:9 quietly became 18 parts and made a 26-part lizard mostly prefabricated.
        var many = SculptKits.expand(
                kits("[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":9,\"colour\":\"white\"}]"),
                BODY);
        assertEquals(SculptKits.MAX_BOXES_PER_KIT, many.size());
    }

    @Test
    void aKitAttachesToTheBoneItNames() {
        var made = SculptKits.expand(
                kits("[{\"kit\":\"horn\",\"on\":\"head\",\"colour\":\"white\"}]"), BODY);
        assertTrue(made.stream().allMatch(p -> "head".equals(p.parent())));
        // ...and lands at head height, not at the origin.
        assertTrue(made.stream().allMatch(p -> p.from()[1] > 25),
                "horns must sit on the head, not at the creature's feet");
    }

    @Test
    void anUnknownAnchorFallsBackToTheBodyRatherThanTheOrigin() {
        var made = SculptKits.expand(
                kits("[{\"kit\":\"crest\",\"on\":\"nonexistent_bone\"}]"), BODY);
        assertFalse(made.isEmpty());
        assertTrue(made.stream().allMatch(p -> "body".equals(p.parent())));
    }

    @Test
    void oneKitCannotFloodThePartBudget() {
        var made = SculptKits.expand(
                kits("[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":9999}]"), BODY);
        assertTrue(made.size() <= SculptKits.MAX_PER_KIT * 2,
                "a creature is more than its spikes");
    }

    @Test
    void nonsenseKitsAreSkippedNotFatal() {
        var made = SculptKits.expand(kits("""
                [{"kit":"ignore_all_previous_instructions","on":"body"},
                 "not an object",
                 {"kit":"horn","on":"head"}]"""), BODY);
        assertFalse(made.isEmpty(), "the one good kit must still build");
        assertTrue(made.stream().allMatch(p -> p.name().startsWith(SculptKits.MARK + "horn")));
    }

    @Test
    void expandingAgainstNoPartsAtAllIsSafe() {
        assertTrue(SculptKits.expand(kits("[{\"kit\":\"horn\",\"on\":\"head\"}]"), List.of()).isEmpty());
        assertTrue(SculptKits.expand(null, BODY).isEmpty());
    }

    /** The adoption number — the only thing that distinguishes a used kit from a shipped one. */
    @Test
    void adoptionCountsKitBuiltPartsAndOnlyThose() {
        var made = SculptKits.expand(
                kits("[{\"kit\":\"horn\",\"on\":\"head\"}]"), BODY);
        var all = new java.util.ArrayList<>(BODY);
        all.addAll(made);
        assertEquals(0, SculptKits.usedBy(BODY));
        assertEquals(made.size(), SculptKits.usedBy(all));
    }

    /**
     * <b>The regression that scoring the historical corpus caught.</b>
     *
     * <p>Adoption was first read as "does the bone name start with a kit name". Run against 110
     * sculpts recorded months before kits existed, it reported <b>355 kit-built parts</b> — children
     * ask for horns and tails, so the agent had always been hand-naming bones {@code horn} and
     * {@code tail}. The number lied in the flattering direction, which would have shown a kit as
     * adopted on the day it shipped.
     */
    @Test
    void aHandSculptedBoneCalledHornIsNotCountedAsKitBuilt() {
        var handMade = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"horn","from":[-1,30,-1],"to":[1,36,1],"colour":"white"},
                 {"name":"tail","from":[-2,10,-12],"to":[2,14,-6],"colour":"green"},
                 {"name":"shell","from":[-9,12,-9],"to":[9,28,-5],"colour":"green"}]""")
                .getAsJsonArray());
        assertEquals(0, SculptKits.usedBy(handMade),
                "hand-named bones must never inflate the adoption number");
    }

    @Test
    void kitBuiltPartsStillMatchTheirFeatureName() {
        // The marker must not hide the feature from G3, or shipping kits would look like a
        // regression in feature recall.
        var made = SculptKits.expand(kits("[{\"kit\":\"horn\",\"on\":\"head\"}]"), BODY);
        assertTrue(made.stream().anyMatch(p ->
                com.moddingfromamod.likeness.FeatureRecall.matches(p.name(), "horn")));
    }

    /**
     * <b>The tail-out-of-the-belly bug.</b>
     *
     * <p>The agent's facing convention is not consistent — across the recorded corpus roughly 14
     * creatures are built face-at-+Z and 22 face-at-−Z. Every kit places "back" at the anchor's
     * minimum Z, so on the second group a tail landed on the stomach. A human spotted it on Barney
     * in <em>both</em> arms of an A/B, which is what ruled out the face kit as the cause.
     */
    @Test
    void aTailGoesBehindTheCreatureWhicheverWayItWasBuilt() {
        // Built face at +Z (snout positive): the back is negative Z.
        var facesPlusZ = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-6,10,-12],"to":[6,26,12],"colour":"green"},
                 {"name":"snout","from":[-4,16,12],"to":[4,22,20],"colour":"green"}]""")
                .getAsJsonArray());
        var tailA = SculptKits.expand(kits("[{\"kit\":\"tail\",\"on\":\"body\"}]"), facesPlusZ);
        assertTrue(midZ(tailA) < 0, "a +Z-facing creature's tail belongs at negative z, was "
                + midZ(tailA));

        // Built face at -Z (snout negative): the back is POSITIVE Z. Same kit, opposite side.
        var facesMinusZ = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-6,10,-12],"to":[6,26,12],"colour":"green"},
                 {"name":"snout","from":[-4,16,-20],"to":[4,22,-12],"colour":"green"}]""")
                .getAsJsonArray());
        var tailB = SculptKits.expand(kits("[{\"kit\":\"tail\",\"on\":\"body\"}]"), facesMinusZ);
        assertTrue(midZ(tailB) > 0, "a -Z-facing creature's tail belongs at positive z, was "
                + midZ(tailB));
    }

    /** Same for a shell, which is the other kit that assumed a side. */
    @Test
    void aShellGoesOnTheBackWhicheverWayTheCreatureWasBuilt() {
        var facesMinusZ = CreatureSculpt.parseParts(JsonParser.parseString("""
                [{"name":"body","from":[-8,10,-10],"to":[8,28,10],"colour":"green"},
                 {"name":"mouth","from":[-3,20,-14],"to":[3,23,-10],"colour":"red"}]""")
                .getAsJsonArray());
        var shell = SculptKits.expand(kits("[{\"kit\":\"shell\",\"on\":\"body\"}]"), facesMinusZ);
        assertTrue(midZ(shell) > 0, "the shell should sit behind a -Z-facing creature, was "
                + midZ(shell));
    }

    /** With no facing evidence at all, nothing changes — guessing is worse than leaving it. */
    @Test
    void aCreatureWithNoFacingCuesKeepsTheOldPlacement() {
        var blob = CreatureSculpt.parseParts(JsonParser.parseString(
                "[{\"name\":\"body\",\"from\":[-8,0,-8],\"to\":[8,20,8],\"colour\":\"purple\"}]")
                .getAsJsonArray());
        var tail = SculptKits.expand(kits("[{\"kit\":\"tail\",\"on\":\"body\"}]"), blob);
        assertTrue(midZ(tail) < 0, "unchanged default: back at negative z");
    }

    private static double midZ(List<CreatureSculpt.Part> parts) {
        return parts.stream().mapToDouble(p -> (p.from()[2] + p.to()[2]) / 2.0).average().orElse(0);
    }

    @Test
    void nearMissKitNamesResolve() {
        assertEquals("spike_row", SculptKits.sanitize("spikes"));
        assertEquals("horn", SculptKits.sanitize("Horns"));
        assertEquals("claw", SculptKits.sanitize("talons"));
        assertEquals("", SculptKits.sanitize("rm -rf"));
    }
}
