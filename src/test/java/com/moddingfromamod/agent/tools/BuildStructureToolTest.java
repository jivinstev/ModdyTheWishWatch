package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildPlan;
import org.junit.jupiter.api.Test;

/**
 * What {@code build_structure} tells the agent — the half that does not depend on the agent
 * reading a schema carefully.
 *
 * <p>The agent cannot see its own building. So a wall eaten by a later shape is invisible to it, and
 * it reports "done" over a castle you can walk through the back of; that is how two real builds
 * shipped. The tool now runs the same oracle that gates the corpus and says so.
 */
class BuildStructureToolTest {

    private static BuildPlan plan(String shapes) {
        return BuildPlan.fromJson(JsonParser.parseString(
                "{\"id\":\"t\",\"name\":\"t\",\"shapes\":" + shapes + "}").getAsJsonObject());
    }

    @Test
    void theSchemaSaysWhichEndTheOffsetsAre() {
        // The always-on delivery. Everything else here depends on the agent making the mistake
        // first; this is the only part that can stop it happening at all, so it must not drift.
        String shapes = new BuildStructureTool().inputSchema()
                .getAsJsonObject("properties").getAsJsonObject("shapes")
                .get("description").getAsString().toLowerCase(Locale.ROOT);
        assertTrue(shapes.contains("low corner"), "the schema no longer says dx/dz are the corner");
        assertTrue(shapes.contains("anchor"), "the schema no longer offers a way to say 'centre'");
        assertTrue(shapes.contains("concentric") || shapes.contains("not concentric"),
                "the worked example is gone — stating the rule without it is what failed before");
    }

    @Test
    void aBuildThatLosesAWallIsReportedBackToTheAgent() {
        String note = BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":44,"height":12,"depth":44,
                  "block":"minecraft:deepslate_bricks","hollow":true},
                 {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":40,"height":14,"depth":40,
                  "block":"minecraft:air","hollow":false}]"""));
        assertTrue(note.contains("north"), note);
        assertTrue(note.contains("west"), note);
        assertTrue(note.toLowerCase(Locale.ROOT).contains("anchor"),
                "the note names the defect but not the fix, which leaves the agent stuck: " + note);
    }

    @Test
    void theSameBuildAnchoredFromItsMiddleIsReportedAsFine() {
        assertEquals("", BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":44,"height":12,"depth":44,
                  "block":"minecraft:deepslate_bricks","hollow":true,"anchor":"centre"},
                 {"kind":"cylinder","dx":0,"dy":0,"dz":0,"width":40,"height":14,"depth":40,
                  "block":"minecraft:air","hollow":false,"anchor":"centre"}]""")));
    }

    @Test
    void aLiddedTowerIsReportedAndAnArenaFloorIsNot() {
        assertTrue(BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":8,"height":40,"depth":8,
                  "block":"minecraft:stone","hollow":true}]""")).contains("unfinished box"));
        // The anti-correlation guard again, at the tool boundary: nagging about a flat arena floor
        // would train the agent to ignore the note entirely.
        assertEquals("", BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":60,"height":2,"depth":60,
                  "block":"minecraft:sandstone","hollow":false}]""")));
    }

    @Test
    void anOrdinaryBuildingGetsNoNoteAtAll() {
        // A note on every build is a note nobody reads.
        assertEquals("", BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":12,"height":6,"depth":12,
                  "block":"minecraft:stone_bricks","hollow":true},
                 {"kind":"box","dx":5,"dy":1,"dz":0,"width":2,"height":3,"depth":1,
                  "block":"minecraft:air","hollow":false},
                 {"kind":"pyramid","dx":0,"dy":6,"dz":0,"width":12,"height":7,"depth":12,
                  "block":"minecraft:deepslate_tiles","hollow":false}]""")));
    }

    // ── the SEALED note, rewritten because the old one told the agent something untrue ──

    /** A tower with a floor across the middle and no stairs: two shut rooms, one shell. */
    private static final String TWO_STOREY_TOWER = """
            [{"kind":"box","dx":0,"dy":0,"dz":0,"width":10,"height":12,"depth":10,
              "block":"minecraft:stone","hollow":true},
             {"kind":"box","dx":1,"dy":6,"dz":1,"width":8,"height":1,"depth":8,
              "block":"minecraft:stone","hollow":false}]""";

    @Test
    void aMultiRoomBuildIsToldItIsSHUTINSEVERALPLACES() {
        // The measured failure: a real lighthouse reported 742 sealed blocks and ONE thinnest
        // spot, the agent cut a 3x4x8 doorway through exactly that wall six seconds later, and
        // was answered with 619 still sealed at a different spot. The note had promised "a 1x2
        // hole there would open it". It could not have; it was never one room.
        String note = BuildStructureTool.geometrySuffix(plan(TWO_STOREY_TOWER));
        assertTrue(note.contains("SEALED"), note);
        assertTrue(note.contains("SEPARATE rooms"),
                "a two-room building must say so, or one doorway reads as the whole fix: " + note);
        assertFalse(note.contains("a 1x2 hole there would open it"),
                "the singular claim is the defect this replaces: " + note);
    }

    @Test
    void everyShutRoomGetsItsOwnDoorway() {
        // P2, and the reason the whole building can now be opened in ONE call: a coordinate per
        // room. The old note gave one, so a 17-room lighthouse would have needed 17 round trips.
        String note = BuildStructureTool.geometrySuffix(plan(TWO_STOREY_TOWER));
        int coords = note.split("thinnest at the single block", -1).length - 1;
        assertEquals(2, coords, "one doorway per shut room, not one for the building: " + note);
    }

    @Test
    void aSingleRoomBuildIsNotToldAboutRooms() {
        // P6 from the other side. If every ordinary sealed shell gains a multi-room warning, the
        // sentence that means something gets skipped along with the ones that do not.
        String note = BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":10,"height":8,"depth":10,
                  "block":"minecraft:stone","hollow":true}]"""));
        assertTrue(note.contains("SEALED"), note);
        assertFalse(note.contains("SEPARATE"),
                "one room is not several, and saying so is noise: " + note);
        assertTrue(note.contains("thinnest at the single block"), note);
    }

    @Test
    void aBuildingWithAWayInSaysNothingAtAll() {
        assertEquals("", BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":10,"height":8,"depth":10,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":4,"dy":1,"dz":0,"width":2,"height":3,"depth":1,
                  "block":"minecraft:air","hollow":false}]""")),
                "a building a child can walk into must produce no complaint");
    }

    @Test
    void theDoorwayListIsBounded() {
        // Past a handful of shut rooms the building is full of pockets — a differently shaped
        // problem — and forty coordinates would bury the sentence that says so.
        StringBuilder shapes = new StringBuilder(
                "[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":10,\"height\":42,"
                + "\"depth\":10,\"block\":\"minecraft:stone\",\"hollow\":true}");
        for (int y = 5; y < 40; y += 5) {          // seven floors, so eight shut rooms
            shapes.append(",{\"kind\":\"box\",\"dx\":1,\"dy\":").append(y)
                  .append(",\"dz\":1,\"width\":8,\"height\":1,\"depth\":8,"
                          + "\"block\":\"minecraft:stone\",\"hollow\":false}");
        }
        String note = BuildStructureTool.geometrySuffix(plan(shapes.append("]").toString()));
        int coords = note.split("thinnest at the single block", -1).length - 1;
        assertTrue(coords <= BuildStructureTool.MAX_BREACHES_SHOWN,
                "listed " + coords + " doorways: " + note);
        assertTrue(note.contains("More rooms than that are shut"),
                "and it must say the list was cut, or the agent reads it as the whole job: " + note);
    }

    @Test
    void aStartFromRefusalNamesTheWayForward() {
        // A real round trip, from the castle transcript: this tool's own description says
        // to "send ONLY the shapes that change", so an agent wanting the saved plan back unaltered
        // sent start_from and an empty shapes list. The refusal named neither start_from nor a way
        // forward, and it spent two more calls working it out.
        //
        // NB the building LESSON already explains start_from, and it was stapled to that very
        // refusal in the transcript — the agent read it and still spent the calls. So this asserts
        // the SENTENCE, not the token: testing for "start_from" in the result passes on the lesson
        // text alone, which is how the first version of this test passed while measuring nothing.
        String editing = "you are editing '";
        var res = new BuildStructureTool().call(JsonParser.parseString(
                "{\"id\":\"castle\",\"start_from\":\"castle\",\"shapes\":[]}")
                .getAsJsonObject());
        assertTrue(res.text().contains(editing), res.text());
        assertTrue(res.text().contains("already built"),
                "it must say that sending nothing is not a request: " + res.text());
        // And a from-scratch call keeps the plain sentence — the extra half is for the case that
        // earned it, not for every refusal.
        var scratch = new BuildStructureTool().call(JsonParser.parseString(
                "{\"id\":\"castle\",\"shapes\":[]}").getAsJsonObject());
        assertFalse(scratch.text().contains(editing), scratch.text());
    }

    @Test
    void aTowerWithNoStairsIsToldSo() {
        // The remedy differs from every other sealed case, so the note must too: a doorway in the
        // outside wall of storey three leaves the other storeys exactly as they were.
        StringBuilder shapes = new StringBuilder(
                "[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":9,\"height\":42,"
                + "\"depth\":9,\"block\":\"minecraft:stone\",\"hollow\":true}");
        for (int y = 8; y <= 32; y += 8) {
            shapes.append(",{\"kind\":\"box\",\"dx\":1,\"dy\":").append(y)
                  .append(",\"dz\":1,\"width\":7,\"height\":1,\"depth\":7,"
                          + "\"block\":\"minecraft:glass\",\"hollow\":false}");
        }
        String note = BuildStructureTool.geometrySuffix(plan(shapes.append("]").toString()));
        assertTrue(note.contains("STACKED ONE ABOVE ANOTHER"), note);
        assertTrue(note.contains("ladder"), "P2 — it must name the remedy, not the symptom: " + note);
        assertFalse(note.contains("a doorway into one does not open the others"),
                "that is the OTHER shape's advice, and it is wrong for a tower: " + note);
    }

    @Test
    void sideBySideRoomsKeepTheDoorwayAdvice() {
        String note = BuildStructureTool.geometrySuffix(plan("""
                [{"kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":20,"depth":9,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":20,"dy":0,"dz":0,"width":9,"height":20,"depth":9,
                  "block":"minecraft:stone","hollow":true},
                 {"kind":"box","dx":40,"dy":0,"dz":0,"width":9,"height":20,"depth":9,
                  "block":"minecraft:stone","hollow":true}]"""));
        assertTrue(note.contains("SEPARATE rooms"), note);
        assertFalse(note.contains("STACKED"), "three towers side by side are not three storeys: " + note);
    }
}
