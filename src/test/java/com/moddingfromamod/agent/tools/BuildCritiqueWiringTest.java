package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Is the buildings drift gate actually WIRED, and does the edit path merge?</b>
 *
 * <p>Separate from {@code BuildDriftTest} and {@code BuildBaseTest} on purpose, because those test
 * the <i>rules</i> and nothing in them tests that a rule reaches the agent. Unwiring the creature
 * shape gate from its critique left {@code ShapeDriftTest} <b>6/6 green</b>, and the obvious live
 * check — grepping a real client's {@code run.log} for the warning — returns zero whether the gate
 * is silent, unwired or absent, because {@code run.log} carries no tool results at all.
 *
 * <p>So this asserts on the string the tool really returns.
 */
class BuildCritiqueWiringTest {

    private static BuildShape named(String name, int dx, int dy, int dz,
                                    int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false,
                BuildShape.Anchor.CORNER, name);
    }

    private static BuildShape hollow(String name, int dx, int dy, int dz,
                                     int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, true,
                BuildShape.Anchor.CORNER, name);
    }

    private static final BuildPlan CASTLE = new BuildPlan("glimmerhold", "The Glimmerhold",
            List.of(named("keep", 0, 0, 0, 24, 14, 24, "minecraft:purple_concrete"),
                    named("doorway", 10, 0, 0, 4, 6, 1, "minecraft:air"),
                    named("roof", 0, 14, 0, 24, 6, 24, "minecraft:gold_block")));

    private static JsonObject args(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    // ───────────────────────── the gate is wired ─────────────────────────

    @Test
    @DisplayName("a replacement reaches the string the agent reads")
    void aReplacementReachesTheAgent() {
        BuildPlan somethingElse = new BuildPlan("glimmerhold", "Tower",
                List.of(named("tower", 60, 0, 60, 8, 40, 8, "minecraft:glass")));
        String note = BuildStructureTool.critiqueFor(CASTLE, somethingElse);
        assertTrue(note.contains("REPLACED THE BUILDING"),
                "the drift gate must be in the tool's answer, not merely in its own tests: " + note);
        assertTrue(note.contains("start_from"), note);
    }

    @Test
    @DisplayName("a healthy edit says nothing alarming")
    void aHealthyEditSaysNothingAlarming() {
        BuildPlan withDome = new BuildPlan("glimmerhold", "The Glimmerhold",
                List.of(CASTLE.shapes().get(0), CASTLE.shapes().get(1), CASTLE.shapes().get(2),
                        named("dome", 4, 20, 4, 16, 10, 16, "minecraft:glass")));
        assertFalse(BuildStructureTool.critiqueFor(CASTLE, withDome).contains("REPLACED"),
                "a gate that fires on a correct edit is one the agent learns to skim");
    }


    @Test
    @DisplayName("a creation is not judged against a building that never existed")
    void aCreationIsNotJudged() {
        assertFalse(BuildStructureTool.critiqueFor(null, CASTLE).contains("REPLACED"));
    }

    // ───────────────────────── the edit path merges ─────────────────────────

    @Test
    @DisplayName("start_from folds the base under the sent shapes")
    void startFromFoldsTheBaseUnder() {
        BuildPlan sent = BuildPlan.fromJson(args("""
                {"id":"glimmerhold","name":"The Glimmerhold","start_from":"glimmerhold",
                 "shapes":[{"kind":"box","name":"dome","dx":4,"dy":20,"dz":4,
                            "width":16,"height":10,"depth":16,"block":"minecraft:glass"}]}"""));
        BuildPlan merged = BuildStructureTool.withBase(sent,
                args("{\"start_from\":\"glimmerhold\"}"), id -> CASTLE);
        assertEquals(4, merged.shapes().size(), "three from the castle, one new: " + merged.shapes());
        assertEquals("keep", merged.shapes().get(0).name(), "and the castle is still first");
        assertEquals("dome", merged.shapes().get(3).name(), "…with the dome placed last, on top");
    }

    @Test
    @DisplayName("an unknown base costs a plainer building, never a failed wish")
    void anUnknownBaseIsNotAFailure() {
        BuildPlan sent = BuildPlan.fromJson(args("""
                {"id":"x","shapes":[{"kind":"box","width":4,"height":4,"depth":4,
                                     "block":"minecraft:stone"}]}"""));
        assertEquals(sent, BuildStructureTool.withBase(sent,
                args("{\"start_from\":\"nothing_like_this\"}"), id -> null));
    }

    @Test
    @DisplayName("the clamp notes grade WHAT WAS SENT, not the merged building")
    void clampNotesGradeWhatWasSent() {
        // R2, and the regression the R1 fix would otherwise have introduced. clampNotes compares
        // requested[i] with shapes[i] BY POSITION. Graded against a merged 24-shape castle, a
        // correct two-shape edit produces a wall of authoritative "shape N was made smaller /
        // moved closer … redesign within the limits and build again" — the creature MISSED bug,
        // whose only satisfying answer is a rebuild.
        JsonObject sentArgs = args("""
                {"id":"glimmerhold","start_from":"glimmerhold",
                 "shapes":[{"kind":"box","name":"dome","dx":4,"dy":20,"dz":4,
                            "width":16,"height":10,"depth":16,"block":"minecraft:glass"}]}""");
        BuildPlan sent = BuildPlan.fromJson(sentArgs);
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(sentArgs);

        assertEquals(List.of(), sent.clampNotes(asked),
                "nothing was clamped, so the agent is told nothing");

        BuildPlan merged = BuildStructureTool.withBase(sent, sentArgs, id -> CASTLE);
        assertFalse(merged.clampNotes(asked).isEmpty(),
                "…whereas grading the MERGED building against the same one-shape request invents "
                        + "complaints out of nothing, which is what must never be shipped to the "
                        + "agent: " + merged.clampNotes(asked));
    }

    // ─────────────────────────: can a child get in ─────────────────────────

    @Test
    @DisplayName("a sealed room reaches the agent, with the cheapest doorway named")
    void aSealedRoomReachesTheAgent() {
        // A shell with no opening. This is the case that passes every OTHER check on the list:
        // four walls, a solid lid, and BuildLighting fires precisely because it "encloses a
        // space", so it gets lit and reads as improved. BuildGeometry could always answer this --
        // sealedInterior() and its unit tests predate this wiring -- but nothing asked it.
        BuildPlan box = new BuildPlan("vault", "The Vault",
                List.of(hollow("shell", 0, 0, 0, 9, 7, 9, "minecraft:stone")));
        String note = BuildStructureTool.critiqueFor(null, box);
        assertTrue(note.contains("SEALED"),
                "the sealed-interior check must be in the tool's answer, not merely in its own "
                        + "tests: " + note);
        // P2: name the remedy, not just the problem.
        assertTrue(note.contains("1x2 hole"), "it must say how to open it: " + note);
    }

    @Test
    @DisplayName("a doorway is enough — the check does not fire on a room you can walk into")
    void aDoorwayClearsIt() {
        // Same shell, one air shape punched through a wall. If this still said SEALED the note
        // would be noise, and a note nobody can act on gets ignored along with the true ones.
        BuildPlan withDoor = new BuildPlan("vault", "The Vault",
                List.of(hollow("shell", 0, 0, 0, 9, 7, 9, "minecraft:stone"),
                        named("doorway", 4, 1, 0, 1, 2, 1, "minecraft:air")));
        String note = BuildStructureTool.critiqueFor(null, withDoor);
        assertFalse(note.contains("SEALED"),
                "a building with a doorway must not be reported as sealed: " + note);
    }

    @Test
    @DisplayName("a solid block of stone is not a sealed room — there is no room in it")
    void aSolidLumpIsNotSealed() {
        String note = BuildStructureTool.critiqueFor(null, CASTLE);
        assertFalse(note.contains("SEALED"),
                "a solid build encloses nothing, so there is nothing to be shut out of: " + note);
    }

    /**
     * <b>: the informative half of the lighting note reached nobody.</b>
     *
     * <p>{@code BuildLighting.note} had two overloads. The one-argument form said what was hung; the
     * two-argument form also said how big the room was per lantern — and, now, whether anyone can
     * get INTO the room it just lit. Both production call sites used the one-argument form, so both
     * facts were written, unit-tested, and dead: this repo's own trap, <i>a rule tested where it is
     * DEFINED is not tested where it RUNS</i>, and the same trap {@code sealedInterior()} was in
     * wired it up.
     *
     * <p>The overload is deleted, so dropping the plan is a compile error. This closes the other
     * half — passing a literal {@code null} compiles fine and restores the old behaviour exactly,
     * silently, and would look like a tidy-up in a diff.
     */
    @Test
    @DisplayName("the lighting note is always given the plan, never null")
    void theLightingNoteIsGivenSomethingToMeasure() {
        String src = read("src/main/java/com/moddingfromamod/agent/tools/BuildStructureTool.java");
        int at = src.indexOf("BuildLighting.note(");
        assertTrue(at > 0, "BuildStructureTool no longer lights buildings; update this deliberately");
        int calls = 0;
        while (at > 0) {
            String call = src.substring(at, src.indexOf(')', at) + 1);
            assertTrue(call.contains(","),
                    "the note must be given the plan or it cannot say how big the room is, nor "
                            + "whether it is sealed: " + call);
            assertFalse(call.contains("null"),
                    "a null plan compiles and silently restores the old, less useful note: " + call);
            calls++;
            at = src.indexOf("BuildLighting.note(", at + 1);
        }
        assertEquals(2, calls,
                "there are two build paths — save-only and build-now — and both have to tell the "
                        + "truth about the room; found " + calls);
    }

    private static String read(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    @Test
    @DisplayName("#1378: a dark roofed room reaches the agent from critiqueFor, and a lit one is silent")
    void aDarkRoomIsReported() {
        BuildPlan dark = new BuildPlan("cell", "Cell", List.of(
                hollow("walls", 0, 0, 0, 11, 6, 11, "minecraft:stone_bricks"),
                named("door", 5, 1, 0, 1, 2, 1, "minecraft:air")));
        String note = BuildStructureTool.critiqueFor(null, dark);
        assertTrue(note.contains("DARK INSIDE"), "the dark-interior check must be wired: " + note);
        BuildPlan lit = dark.withShapes(new java.util.ArrayList<>(dark.shapes()) {{
            add(named("lamp", 5, 1, 5, 1, 1, 1, "minecraft:lantern"));
        }});
        assertFalse(BuildStructureTool.critiqueFor(null, lit).contains("DARK INSIDE"),
                "and silent once the room has a light of its own");
    }

    @Test
    @DisplayName("#1374: build_structure's result carries the support warning and the auto-facing note")
    void theSupportNoteReachesTheResult() {
        BuildShape tower = new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 5, 10, 5,
                "minecraft:stone", true, BuildShape.Anchor.CORNER, "tower");
        BuildShape hanging = new BuildShape(BuildShape.Kind.BOX, 2, 1, 2, 1, 9, 1,
                "minecraft:ladder[facing=east]", false, BuildShape.Anchor.CORNER, "ladder");
        BuildPlan bad = new BuildPlan("t", "T", List.of(tower, hanging));
        var faced = com.moddingfromamod.builder.BuildSupport.autoFace(bad);
        String note = BuildStructureTool.supportSuffix(faced, faced.plan());
        assertTrue(note.contains("CANNOT STAND") && note.contains("(ladder)"), note);
        BuildShape bare = new BuildShape(BuildShape.Kind.BOX, 1, 1, 2, 1, 9, 1,
                "minecraft:ladder", false, BuildShape.Anchor.CORNER, "ladder");
        var turned = com.moddingfromamod.builder.BuildSupport.autoFace(
                new BuildPlan("t", "T", List.of(tower, bare)));
        String ok = BuildStructureTool.supportSuffix(turned, turned.plan());
        assertTrue(ok.contains("I turned 1 ladder") && !ok.contains("CANNOT STAND"), ok);
    }

    @Test
    @DisplayName("#2097: a part standing on nothing reaches the string the agent reads")
    void aFloatingPartReachesTheAgent() {
        BuildPlan lit = new BuildPlan("glimmerhold", "The Glimmerhold",
                List.of(named("keep", 0, 0, 0, 24, 14, 24, "minecraft:purple_concrete"),
                        named("doorway", 10, 0, 0, 4, 6, 1, "minecraft:air"),
                        named("roof", 0, 14, 0, 24, 6, 24, "minecraft:gold_block"),
                        named("beacon", 12, 30, 12, 1, 1, 1, "minecraft:glowstone")));
        String note = BuildStructureTool.critiqueFor(CASTLE, lit);
        assertTrue(note.contains("FLOATING —") && note.contains("glowstone"), note);
        assertFalse(BuildStructureTool.critiqueFor(CASTLE, CASTLE).contains("FLOATING"));
    }
}
