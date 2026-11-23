package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sculpted creature ships in one piece. */
class SculptConnectivityTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static List<CreatureSculpt.Part> starterCat() throws Exception {
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/cat/sculpt.json"))).getAsJsonObject();
        return CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
    }

    /** What Muse really sent for syn-1-007 ("a cat that finds diamonds"), archived 2026-10-08. */
    private static final String MUSE_CAT = """
            [{"colour":"orange","from":[0,0,0],"name":"body","to":[8,8,8]},
             {"colour":"white","from":[0,8,0],"name":"head","to":[8,12,8]}]""";

    /** The merge as it was: a replacement swapped in, nothing else moved. */
    private static List<CreatureSculpt.Part> swappedOnly(List<CreatureSculpt.Part> base,
                                                         List<CreatureSculpt.Part> added) {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        for (var p : base) {
            out.add(added.stream().filter(a -> a.name().equals(p.name())).findFirst().orElse(p));
        }
        return out;
    }

    @Test
    void theInstrumentSeesTheCatInPieces() throws Exception {
        var islands = SculptConnectivity.islands(swappedOnly(starterCat(), parse(MUSE_CAT)));
        assertFalse(islands.isEmpty(), "the archived cat was in pieces; the check must say so");
        var loose = islands.stream().flatMap(i -> i.parts().stream()).toList();
        assertTrue(loose.containsAll(List.of("earL", "earR", "legL", "legR")), loose.toString());
    }

    @Test
    void replacingTheBodyAndHeadCarriesTheEarsAndLegs() throws Exception {
        var merged = SculptBase.merge(starterCat(), parse(MUSE_CAT), Set.of(), Map.of());
        assertEquals(List.of(), SculptConnectivity.islands(merged),
                "a replaced body and head carry what hangs on them");
        var moved = SculptBase.carried(starterCat(), parse(MUSE_CAT), Set.of());
        assertTrue(moved.keySet().containsAll(List.of("earL", "earR", "legL", "legR", "tail")),
                moved.keySet().toString());
    }

    @Test
    void anEarStaysOnTopOfTheHeadItWasOn() throws Exception {
        var merged = SculptBase.merge(starterCat(), parse(MUSE_CAT), Set.of(), Map.of());
        var ear = merged.stream().filter(p -> p.name().equals("earL")).findFirst().orElseThrow();
        assertEquals(12f, Math.min(ear.from()[1], ear.to()[1]), 1e-4,
                "the ear sat on the head's top face, so it sits on the new head's top face");
        assertEquals(2f, Math.abs(ear.to()[0] - ear.from()[0]), 1e-4, "its size is unchanged");
    }

    /** The owner graded the carried cat "better, but still bad": the shape was the mistake. */
    @Test
    void aMuchSmallerBodyIsANewShapeAndIsFlaggedRatherThanCarried() throws Exception {
        var flagged = SculptBase.reshaped(starterCat(), parse(MUSE_CAT), Set.of());
        assertEquals(List.of("body"), flagged.stream().map(SculptBase.Reshape::name).toList(),
                "the 8x8x8 body is 0.57x the cat's 8x7x16; the head (0.76x) is within the band");
        assertTrue(flagged.get(0).carries().containsAll(List.of("legL", "legR")), flagged.get(0).carries().toString());
        assertTrue(flagged.get(0).describe("cat").contains("8×7×16 at [-4,6,-8]"), flagged.get(0).describe("cat"));
    }

    /** The owner graded the moved wolf head "better": same size, so it is carried, not sent back. */
    @Test
    void aHeadMovedButKeptItsSizeIsAnEditAndIsCarried() throws Exception {
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/wolf/sculpt.json"))).getAsJsonObject();
        var wolf = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
        var head = parse("""
                [{"colour":"white","from":[-4,12,-8],"name":"head","to":[4,20,0]}]""");
        assertEquals(List.of(), SculptBase.reshaped(wolf, head, Set.of()));
        assertFalse(SculptBase.carried(wolf, head, Set.of()).isEmpty(), "and its snout still comes along");
    }

    @Test
    void twoIslandsThatEachHoldTogetherAreStillTwoPieces() {
        // the old check passed this: every part touches SOME other part
        var parts = parse("""
                [{"name":"body","from":[-4,0,-4],"to":[4,8,4],"colour":"red"},
                 {"name":"tail","from":[-1,4,4],"to":[1,6,10],"colour":"red"},
                 {"name":"head","from":[-3,12,-3],"to":[3,18,3],"colour":"red"},
                 {"name":"ear","from":[-1,18,-1],"to":[1,20,1],"colour":"red"}]""");
        assertEquals(List.of(), SculptCritique.floating(parts), "the old check is blind to this");
        var islands = SculptConnectivity.islands(parts);
        assertEquals(1, islands.size());
        assertEquals(List.of("head", "ear"), islands.get(0).parts());
        assertEquals(4f, islands.get(0).gapPx(), 1e-4);
        assertEquals(-5f, islands.get(0).move()[1], 1e-4, "the move that joins it, overlapping by a pixel");
    }

    /**
     * The witch cat (syn-4-012, 2026-10-10): told to move its hat down, the agent did exactly that and
     * the brim's corner came to rest on an ear's corner, the hat 8 px off to one side. Touching only at
     * an edge is not one piece.
     */
    @Test
    void aBigPieceRestingOnAnEdgeIsNotOnePiece() throws Exception {
        var parts = new java.util.ArrayList<>(starterCat());
        parts.addAll(parse("""
                [{"name":"hat_brim","from":[0,18,-10],"to":[16,20,6],"colour":"black"},
                 {"name":"hat_cone","from":[4,20,-6],"to":[12,30,2],"colour":"purple"}]"""));
        var islands = SculptConnectivity.islands(parts);
        assertEquals(1, islands.size(), islands.toString());
        assertTrue(islands.get(0).describe().contains("only touches earR at an edge or corner"),
                islands.get(0).describe());
        assertFalse(SculptConnectivity.check(parts).onePiece(), "and it is not closed for the agent");
    }

    @Test
    void aSmallDetailMayRestOnAnEdge() {
        var parts = parse("""
                [{"name":"head","from":[-4,0,-4],"to":[4,8,4],"colour":"pink"},
                 {"name":"brow","from":[-3,8,-5],"to":[-1,9,-4],"colour":"black"}]""");
        assertEquals(List.of(), SculptConnectivity.islands(parts),
                "a 2x1x1 brow on the corner of a face reads as attached");
    }

    @Test
    void aHairlineGapIsClosedAndReported() {
        var parts = parse("""
                [{"name":"body","from":[-4,0,-4],"to":[4,8,4],"colour":"red"},
                 {"name":"horn","from":[-1,8.5,-1],"to":[1,11,1],"colour":"white"}]""");
        var r = SculptConnectivity.check(parts);
        assertTrue(r.onePiece());
        assertEquals(1, r.snapped().size());
        var horn = r.parts().get(1);
        assertEquals(8f, horn.from()[1], 1e-4);
    }

    @Test
    void aRealGapIsLeftForTheAgentNotMoved() {
        var parts = parse("""
                [{"name":"body","from":[-4,0,-4],"to":[4,8,4],"colour":"red"},
                 {"name":"crown","from":[-2,12,-2],"to":[2,14,2],"colour":"yellow"}]""");
        var r = SculptConnectivity.check(parts);
        assertFalse(r.onePiece());
        assertEquals(parts, r.parts(), "a 4 px gap might be meant; it is not moved");
        assertEquals("crown", r.fingerprint());
    }

    @Test
    void aTiltedParentCarriesItsChildAwayAtRest() {
        // On paper the tip touches the tail's end. Drawn, the tail is tilted 90 degrees about its
        // root, so the tip (the tail's child, untilted itself) swings round with it.
        var parts = parse("""
                [{"name":"body","from":[-4,0,-4],"to":[4,8,4],"colour":"red"},
                 {"name":"tail","from":[-1,4,4],"to":[1,6,12],"pivot":[0,5,4],
                  "rotation":[-90,0,0],"colour":"red"},
                 {"name":"tip","parent":"tail","from":[-1,4,12],"to":[1,6,14],"pivot":[0,5,12],
                  "colour":"white"}]""");
        assertEquals(List.of(), SculptCritique.floating(parts), "on paper, everything touches");
        var restTip = SculptConnectivity.restBounds(parts.get(2),
                Map.of("tail", parts.get(1), "tip", parts.get(2), "body", parts.get(0)));
        assertTrue(restTip[1] > 10f, "the tilt lifts the tip with the tail: " + restTip[1]);
        assertTrue(SculptConnectivity.islands(parts).isEmpty(),
                "tip still meets the tail it rides on, which still meets the body");
    }
}
