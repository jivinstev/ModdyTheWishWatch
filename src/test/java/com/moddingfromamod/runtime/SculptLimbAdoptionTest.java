package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a creature stays in one piece while it MOVES, not only standing still. */
class SculptLimbAdoptionTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static Set<String> bonesMoved(JsonObject anims) {
        Set<String> out = new HashSet<>();
        for (var a : anims.getAsJsonObject("animations").entrySet()) {
            out.addAll(a.getValue().getAsJsonObject().getAsJsonObject("bones").keySet());
        }
        return out;
    }

    @Test
    void noStandardClipMovesTheBody() throws Exception {
        assertFalse(bonesMoved(CreatureFiles.animations()).contains("body"),
                "legs and arms are ROOT bones beside the body: a body that bobs lifts off them");
        try (var dirs = Files.list(Path.of("src/main/resources/starter-library/species"))) {
            for (Path d : dirs.toList()) {
                Path f = d.resolve("animations.json");
                if (Files.exists(f)) {
                    assertFalse(bonesMoved(JsonParser.parseString(Files.readString(f)).getAsJsonObject())
                            .contains("body"), f + " still moves the body");
                }
            }
        }
    }

    @Test
    void theAdoptedLimbsAreTheOnesTheClipsSwing() {
        Set<String> swung = bonesMoved(CreatureFiles.animations());
        swung.remove("head");   // the head has its own adoption
        assertEquals(SculptLimbAdoption.SWUNG, swung);
    }

    @Test
    void aClawOnASwingingArmHangsFromIt() {
        var parts = parse("""
                [{"name":"body","from":[-6,8,-4],"to":[6,20,4],"colour":"green"},
                 {"name":"armR","from":[-9,10,-2],"to":[-6,20,2],"colour":"green"},
                 {"name":"clawR1","from":[-9,8,-2],"to":[-7,10,0],"colour":"white"}]""");
        assertEquals(Map.of("clawR1", "armR"), SculptLimbAdoption.adoptions(parts));
        var adopted = SculptLimbAdoption.adopt(parts);
        assertEquals("armR", adopted.get(2).parent());
        assertEquals(parts.get(2).from()[1], adopted.get(2).from()[1], "nothing moves at rest");
    }

    @Test
    void aSecondClawRestingOnTheFirstRidesTheArmToo() {
        var parts = parse("""
                [{"name":"body","from":[-6,8,-4],"to":[6,20,4],"colour":"green"},
                 {"name":"armR","from":[-9,10,-2],"to":[-6,20,2],"colour":"green"},
                 {"name":"clawR1","from":[-9,7,-2],"to":[-6,10,1],"colour":"white"},
                 {"name":"clawR2","from":[-9,5,-2],"to":[-7,7,0],"colour":"white"}]""");
        assertEquals(Map.of("clawR1", "armR", "clawR2", "clawR1"), SculptLimbAdoption.adoptions(parts));
    }

    @Test
    void aShoulderPadThatRestsOnTheBodyMoreStaysWithTheBody() {
        var parts = parse("""
                [{"name":"body","from":[-6,8,-4],"to":[6,20,4],"colour":"green"},
                 {"name":"armR","from":[-9,10,-2],"to":[-6,20,2],"colour":"green"},
                 {"name":"pad","from":[-8,18,-3],"to":[-2,21,3],"colour":"red"}]""");
        assertEquals(Map.of(), SculptLimbAdoption.adoptions(parts));
    }

    @Test
    void aTiltedLimbAdoptsNothingBecauseThatWouldMoveThePart() {
        var parts = parse("""
                [{"name":"body","from":[-6,8,-4],"to":[6,20,4],"colour":"green"},
                 {"name":"armR","from":[-9,10,-2],"to":[-6,20,2],"pivot":[-7.5,20,0],
                  "rotation":[0,0,20],"colour":"green"},
                 {"name":"clawR1","from":[-9,8,-2],"to":[-7,10,0],"colour":"white"}]""");
        assertSame(parts, SculptLimbAdoption.adopt(parts));
    }

    @Test
    void theStarterCatsBackPawsSwingWithItsBackLegs() throws Exception {
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/cat/sculpt.json"))).getAsJsonObject();
        var moves = SculptLimbAdoption.adoptions(CreatureSculpt.parseParts(o.getAsJsonArray("parts")));
        assertEquals("armR", moves.get("legR2"));
        assertEquals("armL", moves.get("legL2"));
        assertTrue(moves.keySet().stream().noneMatch(SculptLimbAdoption.SWUNG::contains));
    }

    @Test
    void aCreatureSavedBeforeTheRuleIsAdoptedWhereItIsDrawn() throws Exception {
        // ModelRepository.drawnParts adopts at READ, so a sculpt saved (the starter cat's file is
        // one) is drawn whole; and reading it again changes nothing (an adopted part is no longer a root).
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/cat/sculpt.json"))).getAsJsonObject();
        var drawn = SculptLimbAdoption.adopt(SculptOrientation.faceForward(CreatureSculpt.parseParts(o.getAsJsonArray("parts"))));
        assertEquals("armR", drawn.stream().filter(p -> p.name().equals("legR2")).findFirst().orElseThrow().parent());
        assertEquals("armL", drawn.stream().filter(p -> p.name().equals("legL2")).findFirst().orElseThrow().parent());
        assertTrue(SculptLimbAdoption.adopt(drawn) == drawn, "a second read adopts nothing");
    }
}
