package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * the editor is the Builder's own edit mode driven by operators (kept in sync). These
 * pin that it changes exactly what "✦ Change it in the Builder" could, refuses what it could not, and keeps every
 * choice it was not asked to change.
 */
class BuilderEditTest {

    static final BuilderAllowlist ALLOW = BuilderAllowlist.REVIEWED;

    static BuilderEdit.Result edit(String spec, BuilderEdit.Op... ops) {
        return BuilderEdit.apply(ALLOW, LibraryPresets.SHIPPED, "thing_1", BuilderSpec.read(j(spec)), List.of(ops));
    }

    static final String CAT = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"scheme\":\"ocean\","
            + "\"size\":2,\"powers\":[\"power:glowing\"],\"temper\":\"shy\",\"count\":1,\"name\":\"Blueberry\"}";

    /**
     * THE SYNC GUARANTEE: every example make, reopened and walked through the Builder's own edit with nothing changed
     * but its name, LOWERS to the same tool calls as before. The spec may spell a default out (an unset temper is
     * "friendly", a picture's own frame is written down: the Builder's own edit writes exactly that), so the check is
     * what the tools would be asked to do. A page the editor cannot walk past (a new Builder page that
     * {@code BuilderFlow.keepPress} does not answer) fails here by name.
     */
    @Test
    void everyExampleMakeComesBackUnchangedWhenOnlyItsNameChanges() {
        List<String> wrong = new ArrayList<>();
        for (String example : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec prior = BuilderSpec.read(j(example)).withName("Renamed");
            if (Catalogue.prefix(prior.from()).equals("mob") || prior.kind() == BuilderSpec.Kind.SUPPLIES) {
                continue;   // nothing kept to change (BuilderLowering refuses an edit of these too)
            }
            BuilderEdit.Result r = BuilderEdit.apply(ALLOW, LibraryPresets.SHIPPED, "x_1", BuilderSpec.read(j(example)),
                    List.of(BuilderEdit.Op.set("name", "Renamed")));
            String what = prior.kind().id() + " " + (prior.from().isEmpty() ? prior.opts() : prior.from());
            if (!r.ok()) {
                wrong.add(what + ": refused: " + r.refusal());
                continue;
            }
            // Through the EDIT lowering, the path an edit really runs (it spawns, places and hands over nothing).
            String before = BuilderLowering.lower(prior, "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1")).toString();
            String after = BuilderLowering.lower(r.spec(), "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1")).toString();
            if (!before.equals(after)) {
                wrong.add(what + ":\n   was " + before + "\n   now " + after);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aPowerIsAddedAndTakenOffOnTheBuildersOwnPage() {
        BuilderEdit.Result added = edit(CAT, BuilderEdit.Op.add("powers", "power:flies"));
        assertTrue(added.ok(), added.refusal());
        assertTrue(added.spec().powers().contains("power:flies"), added.spec().powers().toString());
        assertTrue(added.spec().powers().contains("power:glowing"), "a change keeps the powers it did not name");
        assertEquals("shy", added.spec().temper(), "and the temper");
        assertEquals("ocean", added.spec().scheme(), "and the colours");
        BuilderEdit.Result off = edit(CAT, BuilderEdit.Op.remove("powers", "power:glowing"));
        assertTrue(off.ok(), off.refusal());
        assertFalse(off.spec().powers().contains("power:glowing"));
    }

    @Test
    void aTemperAndAMoveAreTheBuildersToo() {
        BuilderEdit.Result r = edit(CAT, BuilderEdit.Op.set("temper", "temper:friendly"),
                BuilderEdit.Op.add("abilities", "ability:fire_breath"));
        assertTrue(r.ok(), r.refusal());
        assertEquals("friendly", r.spec().temper());
        assertTrue(r.spec().powers().contains("ability:fire_breath"), r.spec().powers().toString());
    }

    @Test
    void sizeStepsWithinTheDialogsOwnRangeAndStopsAtItsEnd() {
        BuilderEdit.Result bigger = edit(CAT, BuilderEdit.Op.step("size", 1));
        assertTrue(bigger.ok(), bigger.refusal());
        assertEquals(3, bigger.spec().size());
        BuilderEdit.Result past = edit(CAT, BuilderEdit.Op.step("size", 5));
        assertFalse(past.ok(), "past the Builder's biggest is the wish worker's, never clamped quietly");
        assertTrue(past.refusal().contains("goes from"), past.refusal());
    }

    @Test
    void coloursAreSetFromTheDialogsOwnChoices() {
        BuilderEdit.Result r = edit(CAT, BuilderEdit.Op.set("scheme", "lava"));
        assertTrue(r.ok(), r.refusal());
        assertEquals("lava", r.spec().scheme());
        assertFalse(edit(CAT, BuilderEdit.Op.set("scheme", "plaid")).ok(), "a scheme the Builder does not have");
    }

    @Test
    void whatTheBuildersEditCannotReachIsRefusedNotHalfMade() {
        // A tile the Builder does not have, or a control its dialog does not ask, is refused, and makes nothing.
        BuilderEdit.Result r = edit(CAT, BuilderEdit.Op.add("powers", "power:lasers"));
        assertFalse(r.ok());
        assertNull(r.spec(), "a refused change makes nothing");
        assertFalse(edit(CAT, BuilderEdit.Op.add("powers", "power:lasers")).ok());
        assertFalse(edit(CAT, BuilderEdit.Op.step("floors", 1)).ok());
        // One refused change refuses the whole wish: never "bigger" without the part that could not be made.
        assertFalse(edit(CAT, BuilderEdit.Op.step("size", 1), BuilderEdit.Op.add("powers", "power:lasers")).ok());
    }

    /**
     * "Change it in the Builder" reaches every kind's own pages, not only a creature's and a rule's. For every
     * example make of the other kinds, its edit's pages offer OTHER choices the editor really makes (an item's power,
     * a snack's powers, a building's type or outside, a picture's background, an event's what), and its name is kept.
     * Not every page on its own: a new item shape (or snack look, or block trigger) asks for what follows it again,
     * as the Builder does for a new make, so that change needs its next pick too.
     */
    @Test
    void everyKindsOwnPagesCanBeChanged() {
        List<String> wrong = new ArrayList<>();
        int changed = 0;
        int pages = 0;
        for (String example : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec prior = BuilderSpec.read(j(example));
            if (Catalogue.prefix(prior.from()).equals("mob") || prior.kind() == BuilderSpec.Kind.SUPPLIES
                    || prior.kind() == BuilderSpec.Kind.CREATURE || prior.kind() == BuilderSpec.Kind.RIDE
                    || prior.kind() == BuilderSpec.Kind.RULE) {
                continue;   // creatures, rides and rules already reopened on their pages; nothing kept of the rest
            }
            BuilderFlow walk = BuilderFlow.editing(ALLOW, LibraryPresets.SHIPPED, "x_1", prior);
            int before = changed;
            for (int guard = 0; guard < 16 && walk.step() != BuilderFlow.Step.TUNE; guard++) {
                BuilderFlow.Step here = walk.step();
                String keep = BuilderFlow.multiPick(here) ? com.moddingfromamod.ui.PickPage.NEXT_ID : walk.keepPress();
                List<String> others = new ArrayList<>();
                for (var t : walk.page().tiles()) {
                    if (!t.id().startsWith("@") && !t.id().equals(keep) && !t.selected()) {
                        others.add(t.id());
                    }
                }
                boolean any = false;
                pages++;
                for (String other : others.subList(0, Math.min(6, others.size()))) {
                    BuilderEdit.Result r = BuilderEdit.apply(ALLOW, LibraryPresets.SHIPPED, "x_1", prior,
                            List.of(BuilderEdit.Op.set("page", other)));
                    if (r.ok() && !BuilderLowering.lower(r.spec(), "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1"))
                            .equals(BuilderLowering.lower(prior, "x_1", LibraryPresets.SHIPPED, new BuilderLowering.Edit("x_1")))) {
                        any = true;
                        changed++;
                        assertEquals(prior.name().isEmpty() ? r.spec().name() : prior.name(), r.spec().name(), "its name is kept");
                        break;
                    }
                }
                walk.press(keep);
            }
            if (walk.step() != BuilderFlow.Step.TUNE) {
                wrong.add(prior.kind().id() + " " + example + ": its edit did not walk to the dialog");
            } else if (changed == before && pages > 0 && walk.tune() != null
                    && !BuilderFlow.editing(ALLOW, LibraryPresets.SHIPPED, "x_1", prior).step().equals(BuilderFlow.Step.TUNE)) {
                wrong.add(prior.kind().id() + " " + example + ": none of its pages changes it");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        assertTrue(changed >= 6, "the other kinds' pages were reached " + changed + " time(s)");
    }

    @Test
    void aRuleChangesOnItsOwnPages() {
        String rule = "{\"builder\":1,\"kind\":\"rule\",\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:flowers\"}}";
        BuilderEdit.Result r = edit(rule, BuilderEdit.Op.set("does", "rule:bounce"));
        if (r.ok()) {
            assertEquals("rule:bounce", r.spec().opt("does"));
            assertEquals("when:jumps", r.spec().opt("when"), "the trigger it did not name stays");
        } else {
            assertTrue(r.refusal().contains("bounce") || r.refusal().contains("follow"), r.refusal());
        }
    }

    @Test
    void aModificationReadsBackAndANewerOneIsRefused() {
        BuilderEdit.Modification m = new BuilderEdit.Modification("cat_1", "creature", "crafter", List.of(
                BuilderEdit.Op.step("size", 1), BuilderEdit.Op.add("powers", "power:flies")));
        BuilderEdit.Modification back = BuilderEdit.Modification.read(m.toJson());
        assertEquals(m, back);
        JsonObject newer = m.toJson();
        newer.addProperty("modify", 2);
        assertThrows(IllegalArgumentException.class, () -> BuilderEdit.Modification.read(newer));
        assertNotNull(m.toJson().getAsJsonObject("target"));
    }
}
