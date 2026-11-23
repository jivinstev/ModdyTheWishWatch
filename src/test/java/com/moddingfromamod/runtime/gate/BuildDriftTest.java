package com.moddingfromamod.runtime.gate;

import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buildings R4/R5 gate.
 *
 * <p>The two that carry the design are {@link #aReplacementIsCalledOut()} and
 * {@link #makingItTallerIsNotDrift()}. A gate that fails either one is useless in a different
 * direction: silent on the failure, or noisy on the fix.
 */
class BuildDriftTest {

    private static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false);
    }

    private static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("glimmerhold", "The Glimmerhold", List.of(shapes));
    }

    private static final BuildPlan CASTLE =
            plan(box(0, 0, 0, 24, 14, 24, "minecraft:purple_concrete"));

    @Test
    @DisplayName("a creation has nothing to lose")
    void aCreationHasNothingToLose() {
        assertEquals("", BuildDrift.between(null, CASTLE, "build me a Glimmerhold"));
        assertEquals("", BuildDrift.between(new BuildPlan("x", "x", List.of()), CASTLE, "build it"));
    }

    @Test
    @DisplayName("a replacement is called out")
    void aReplacementIsCalledOut() {
        // The measured arm-B failure: the castle is gone and a different building stands there.
        BuildPlan somethingElse = plan(box(60, 0, 60, 8, 40, 8, "minecraft:glass"));
        String note = BuildDrift.between(CASTLE, somethingElse, "put a big glass dome on top of it");
        assertTrue(note.startsWith("⚠ THIS REPLACED THE BUILDING"), note);
        assertTrue(note.contains("start_from"), "it names the thing that would have kept it: " + note);
    }

    @Test
    @DisplayName("making it taller is NOT drift, and that is the whole calibration")
    void makingItTallerIsNotDrift() {
        // Measured on a real arm-A ladder: "make it much taller" kept 0.43 of the previous
        // building's BLOCKS and 1.00 of its footprint. A block-keyed gate fires here — on a
        // correct refinement — and a warning that fires on every change is one the agent skims.
        BuildPlan taller = plan(box(0, 0, 0, 24, 40, 24, "minecraft:purple_concrete"));
        assertEquals("", BuildDrift.between(CASTLE, taller, "make it much taller"));
    }

    @Test
    @DisplayName("adding a dome to the castle is silent")
    void addingADomeIsSilent() {
        BuildPlan withDome = plan(box(0, 0, 0, 24, 14, 24, "minecraft:purple_concrete"),
                                  box(4, 14, 4, 16, 10, 16, "minecraft:glass"));
        assertEquals("", BuildDrift.between(CASTLE, withDome, "put a big glass dome on top of it"));
    }

    @Test
    @DisplayName("a repaint needs no special case")
    void aRepaintNeedsNoSpecialCase() {
        // Every position still occupied, so the footprint is untouched and this is silent — where
        // PaletteDrift needed a whole `repaintRequested` discriminator to reach the same answer.
        BuildPlan gold = plan(box(0, 0, 0, 24, 14, 24, "minecraft:gold_block"));
        assertEquals("", BuildDrift.between(CASTLE, gold, "make it out of shiny gold instead"));
    }

    @Test
    @DisplayName("a demolition he asked for is never reported")
    void aDemolitionHeAskedForIsNeverReported() {
        BuildPlan tiny = plan(box(0, 0, 0, 4, 4, 4, "minecraft:purple_concrete"));
        assertEquals("", BuildDrift.between(CASTLE, tiny, "make it much smaller"));
        assertEquals("", BuildDrift.between(CASTLE, tiny, "knock it down and start again"));
    }

    @Test
    @DisplayName("'taller' must not silence the gate")
    void tallerMustNotSilenceTheGate() {
        // The creature version of this list learned the same lesson expensively: a blanket rule
        // silenced "give him tiny black wings", the exact wish that destroyed the creature. Here
        // the equivalent hole would be treating "taller" as a licence to lose the building.
        assertTrue(BuildDrift.demolitionRequested("make it much smaller"));
        assertTrue(BuildDrift.demolitionRequested("knock it down"));
        assertTrue(!BuildDrift.demolitionRequested("make it much taller"),
                "the wish that does the most damage is not a request to lose anything");
        assertTrue(!BuildDrift.demolitionRequested("put a giant diamond spire on the very top"));
    }

    @Test
    @DisplayName("keeping the ground but losing most of the blocks is a remark, not a finding")
    void keepingTheGroundButLosingTheBlocksIsARemark() {
        // Hollowing a solid keep out so the child can walk inside: same ground, most blocks gone.
        BuildPlan hollow = plan(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 24, 14, 24,
                "minecraft:purple_concrete", true));
        String note = BuildDrift.between(CASTLE, hollow, "can I go inside it");
        assertTrue(note.contains("footprint"), note);
        assertTrue(!note.startsWith("⚠"), "not an alarm — the building is still there: " + note);
    }
}
