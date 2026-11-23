package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>Gate B for R1, R2 and R3 on buildings</b> — the decisions that live inside
 * {@code BuildStructureTool.call} and that no unit test can reach.
 *
 * <p>They were found the way this repo usually finds an untested decision: a mutation sweep that
 * PASSED when it should have failed. Reverting the tool to persist the SENT shapes rather than the
 * merged building, and to grade the clamp notes against the merged building rather than against
 * what was sent, broke nothing at all. The rules had tests; their <i>use</i> did not — the same
 * shape as the creature shape gate, whose own tests stayed 6/6 green while it was unwired.
 *
 * <h2>Never `return` from inside succeedWhen</h2>
 * A {@code succeedWhen} runnable that completes without throwing has SUCCEEDED. The first cut of
 * these tests kicked the edit off and then returned, so three of them passed at the instant the
 * edit was dispatched — before asserting one thing about its result. They were green against a
 * deliberately broken tool that persisted the diff. So the edit is dispatched and then FALLEN
 * THROUGH to the assertions, which keep throwing (and therefore keep retrying) until it lands.
 *
 * <h2>Why every call is made from another thread</h2>
 * A GameTest body runs ON the server thread, and {@code AgentBridge.onServer} refuses that outright
 * ("that would deadlock") — so calling the tool directly here fails with an infrastructure error
 * that has nothing to do with the thing under test. The real agent reaches this tool from an HTTP
 * thread, so a background thread plus {@code succeedWhen} polling is not a workaround: it is the
 * production path. Driving the tool's internals instead would test everything except whether the
 * tool uses them, which is exactly the gap these tests exist to close.
 */
@GameTestHolder(GateShards.CI)
public class BuildEditGameTest {

    /**
     * Its OWN BATCH, and this is not tidiness.
     *
     * <p>These tests drive the real tool from a background thread, and the tool sets
     * {@code ProvenanceContext.note}, which is a {@code static volatile} — process-global, not
     * per-thread. Batches run sequentially while the tests INSIDE one run together, so sharing a
     * batch with the rest of the suite let these threads clear the design note out from under a
     * datapack test mid-assertion. It failed as "datapack manifest intent must be the wish", in a
     * test that has nothing to do with buildings.
     */
    private static final String BATCH = "buildEdit";

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Run one real tool call off the server thread; the result lands in the reference. */
    private static AtomicReference<ToolResult> callAsync(String json) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(new BuildStructureTool().call(args(json)));
            } catch (RuntimeException e) {
                out.set(ToolResult.error("threw: " + e));
            }
        }, "build-edit-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /**
     * A castle, under an id THIS TEST OWNS.
     *
     * <p>Per-test ids, and no {@code BuildRegistry.clear()} anywhere: the registry is global and
     * GameTest batches run CONCURRENTLY, so three tests sharing `gt_edit_castle` and clearing the
     * registry would each delete the others' building mid-run. That failure moves between tests
     * from run to run and reads as flakiness rather than as a fixture bug — a trap this repo has
     * already paid for once.
     */
    private static String castle(String id) {
        return CASTLE_TEMPLATE.replace("$ID", id);
    }

    private static String addADome(String id) {
        return DOME_TEMPLATE.replace("$ID", id);
    }

    private static final String CASTLE_TEMPLATE = """
        {"id":"$ID","name":"The Castle","build":false,"shapes":[
          {"kind":"box","name":"keep","dx":0,"dy":0,"dz":0,
           "width":12,"height":8,"depth":12,"block":"minecraft:purple_concrete","hollow":true},
          {"kind":"box","name":"doorway","dx":5,"dy":0,"dz":0,
           "width":2,"height":3,"depth":1,"block":"minecraft:air"},
          {"kind":"box","name":"roof","dx":0,"dy":8,"dz":0,
           "width":12,"height":2,"depth":12,"block":"minecraft:gold_block"}]}
        """;

    private static final String DOME_TEMPLATE = """
        {"id":"$ID","build":false,"start_from":"$ID","shapes":[
          {"kind":"box","name":"dome","dx":2,"dy":10,"dz":2,
           "width":8,"height":6,"depth":8,"block":"minecraft:glass"}]}
        """;

    /**
     * R3: adding a dome persists the CASTLE PLUS THE DOME, not the dome.
     *
     * <p>Persisting the diff leaves a dome and no castle, and the next edit then inherits that. The
     * creature version of this bug shipped twice in one day.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void anEditPersistsTheMergedBuildingNotTheDiff(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync(castle("gt_edit_merge"));
        // LOCAL, never a static field. GameTest batches run concurrently, so a shared reference is
        // trampled by whichever sibling test happens to be running — a documented trap in this
        // repo, and one that shows up as a different test failing on every run.
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        // CAPTURED ONCE, because the runnable re-runs every tick: re-reading "before" after the
        // edit lands reads the edited building and the pre-condition flips from true to false
        // under its own test.
        java.util.concurrent.atomic.AtomicInteger shapesBefore =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        helper.succeedWhen(() -> {
            if (edit.get() == null) {
                helper.assertTrue(made.get() != null, Component.literal("waiting for the castle to be saved"));
                BuildPlan before = BuildRegistry.get("gt_edit_merge");
                helper.assertTrue(before != null, Component.literal("the castle should have been saved"));
                shapesBefore.set(authored(before));
                edit.set(callAsync(addADome("gt_edit_merge")));
            }
            helper.assertTrue(shapesBefore.get() == 3,
                    Component.literal("the castle should have started with three authored shapes, had "
                            + shapesBefore.get()));
            helper.assertTrue(edit.get().get() != null, Component.literal("waiting for the edit"));
            BuildPlan after = BuildRegistry.get("gt_edit_merge");
            helper.assertTrue(after != null, Component.literal("the castle vanished entirely"));
            helper.assertTrue(authored(after) == 4,
                    Component.literal("an edit must persist the MERGED building (3 + 1), got "
                            + authored(after) + " authored shapes: " + after.shapes()));
            helper.assertTrue(after.shapes().stream().anyMatch(s -> "keep".equals(s.name())),
                    Component.literal("the keep is gone, so the child's castle was replaced by its own dome"));
            // AFTER THE ROOF, not at a fixed index. The index was 3 until the lighting backstop
            // started appending lanterns of its own, at which point this test failed for a reason
            // that has nothing to do with merging. What it is actually about is DRAW ORDER: later
            // shapes overwrite earlier ones, so a dome authored before the roof is under it.
            helper.assertTrue(indexOf(after, "dome") > indexOf(after, "roof"),
                    Component.literal("…and the dome is drawn AFTER the roof, or it sits under it: " + after.shapes()));
        });
    }

    /**
     * Shapes the AGENT wrote, ignoring any the build tool hung there itself.
     *
     * <p>Counting every shape made this suite depend on whether {@code BuildLighting} felt like
     * adding a lantern, which is a different feature entirely.
     */
    private static int authored(BuildPlan plan) {
        return (int) plan.shapes().stream()
                .filter(s -> s.name() == null || !s.name().startsWith("light_")).count();
    }

    private static int indexOf(BuildPlan plan, String name) {
        for (int i = 0; i < plan.shapes().size(); i++) {
            if (name.equals(plan.shapes().get(i).name())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * R2: a correct one-shape edit is told nothing was clamped, and is not told it replaced
     * anything.
     *
     * <p>Grading the merged building against the one-shape request instead produces a wall of
     * authoritative "shape N was made smaller / moved closer … redesign within the limits and build
     * again". That is the creature {@code MISSED} bug, whose only satisfying answer is a rebuild —
     * i.e. the R1 fix reintroducing the R2 defect it was meant to be free of.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void aCorrectEditIsNotToldItWasClamped(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync(castle("gt_edit_clamp"));
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null && BuildRegistry.get("gt_edit_clamp") != null,
                    Component.literal("waiting for the castle"));
            if (edit.get() == null) {
                edit.set(callAsync(addADome("gt_edit_clamp")));
            }
            ToolResult r = edit.get().get();
            helper.assertTrue(r != null, Component.literal("waiting for the edit"));
            String said = r.text();
            helper.assertFalse(said.contains("did not come out exactly as you asked"),
                    Component.literal("a correct edit must not be told it was clamped: " + said));
            helper.assertFalse(said.contains("was made smaller") || said.contains("was moved closer"),
                    Component.literal("…and certainly not about shapes it never sent: " + said));
            helper.assertFalse(said.contains("REPLACED THE BUILDING"),
                    Component.literal("nor that adding a dome replaced the castle: " + said));
        });
    }

    /** R4, wired: an edit that throws the castle away says so, from the REAL tool. */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void replacingTheBuildingIsReportedToTheAgent(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync(castle("gt_edit_drift"));
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null && BuildRegistry.get("gt_edit_drift") != null,
                    Component.literal("waiting for the castle"));
            if (edit.get() == null) {
                edit.set(callAsync("""
                    {"id":"gt_edit_drift","build":false,"shapes":[
                      {"kind":"box","name":"tower","dx":60,"dy":0,"dz":60,
                       "width":4,"height":20,"depth":4,"block":"minecraft:glass"}]}
                    """));
            }
            ToolResult r = edit.get().get();
            helper.assertTrue(r != null, Component.literal("waiting for the replacement"));
            helper.assertTrue(r.text().contains("REPLACED THE BUILDING"),
                    Component.literal("the drift gate must reach the agent from the REAL tool, not only from its "
                            + "own unit tests: " + r.text()));
        });
    }

    /** A plan saved before names existed survives its first edit. */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void anOldNamelessBuildingSurvivesItsFirstEdit(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync("""
            {"id":"gt_old","build":false,"shapes":[
              {"kind":"box","dx":0,"dy":0,"dz":0,"width":10,"height":6,"depth":10,
               "block":"minecraft:stone"},
              {"kind":"box","dx":2,"dy":6,"dz":2,"width":6,"height":4,"depth":6,
               "block":"minecraft:stone"}]}
            """);
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null && BuildRegistry.get("gt_old") != null,
                    Component.literal("waiting for the old building"));
            if (edit.get() == null) {
                edit.set(callAsync("""
                    {"id":"gt_old","build":false,"start_from":"gt_old","shapes":[
                      {"kind":"box","name":"spire","dx":4,"dy":10,"dz":4,
                       "width":2,"height":8,"depth":2,"block":"minecraft:diamond_block"}]}
                    """));
            }
            helper.assertTrue(edit.get().get() != null, Component.literal("waiting for the edit"));
            BuildPlan after = BuildRegistry.get("gt_old");
            helper.assertTrue(after != null && after.shapes().size() == 3,
                    Component.literal("both nameless shapes must survive, or the first refinement of every building "
                            + "saved before today deletes it: "
                            + (after == null ? "null" : after.shapes())));
        });
    }

    /** The budget is a property of the finished building, so a tiny edit can still be refused. */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void theBudgetIsAskedOfTheWholeBuilding(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync("""
            {"id":"gt_big","build":false,"shapes":[
              {"kind":"box","name":"a","dx":0,"dy":0,"dz":0,
               "width":90,"height":26,"depth":90,"block":"minecraft:stone"}]}
            """);
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null && BuildRegistry.get("gt_big") != null,
                    Component.literal("waiting for the big building"));
            if (edit.get() == null) {
                edit.set(callAsync("""
                    {"id":"gt_big","build":false,"start_from":"gt_big","shapes":[
                      {"kind":"box","name":"b","dx":0,"dy":30,"dz":0,
                       "width":90,"height":26,"depth":90,"block":"minecraft:stone"}]}
                    """));
            }
            ToolResult r = edit.get().get();
            helper.assertTrue(r != null, Component.literal("waiting for the refusal"));
            helper.assertTrue(r.isError(),
                    Component.literal("the merged building is over MAX_BLOCKS and must be refused: " + r.text()));
            helper.assertTrue(BuildRegistry.get("gt_big").shapes().size() == 1,
                    Component.literal("…and the refusal must leave the child's building as it was"));
        });
    }

    /**
     * An edit that would overflow the shape cap is REFUSED, never silently dropped.
     *
     * <p>Found by a smoke run on a real castle, and it was a cap this branch introduced. A
     * Spikor castle reached the 64-shape limit at rung 4; the next wish — <i>"put a huge lava moat
     * all the way around the outside of it"</i> — merged to 65+, and {@link BuildPlan}'s compact
     * constructor <b>truncates</b>, so the moat was dropped on the floor. The overflow check was
     * being made against the constructed plan, which is already 64 long and can never be too long,
     * so it never fired. The tool returned SUCCESS, said the building had gone up, and nothing had
     * changed at all.
     *
     * <p>That is precisely the failure {@code clampNotes} exists for — <i>three of this class's
     * four caps were silent</i> — and this branch had quietly added a fifth.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void anEditThatOverflowsTheShapeCapIsRefusedNotDropped(GameTestHelper helper) {
        StringBuilder full = new StringBuilder(
                "{\"id\":\"gt_full\",\"build\":false,\"shapes\":[");
        for (int i = 0; i < BuildPlan.MAX_SHAPES; i++) {
            full.append(i > 0 ? "," : "")
                .append("{\"kind\":\"box\",\"name\":\"p").append(i).append("\",\"dx\":")
                .append(i).append(",\"dy\":0,\"dz\":0,\"width\":2,\"height\":2,\"depth\":2,")
                .append("\"block\":\"minecraft:stone\"}");
        }
        full.append("]}");
        AtomicReference<ToolResult> made = callAsync(full.toString());
        AtomicReference<AtomicReference<ToolResult>> edit = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null && BuildRegistry.get("gt_full") != null,
                    Component.literal("waiting for the full building"));
            helper.assertTrue(BuildRegistry.get("gt_full").shapes().size() == BuildPlan.MAX_SHAPES,
                    Component.literal("it should be saved at the cap"));
            if (edit.get() == null) {
                edit.set(callAsync("""
                    {"id":"gt_full","build":false,"start_from":"gt_full","shapes":[
                      {"kind":"box","name":"moat","dx":0,"dy":40,"dz":0,
                       "width":8,"height":2,"depth":8,"block":"minecraft:lava"}]}
                    """));
            }
            ToolResult r = edit.get().get();
            helper.assertTrue(r != null, Component.literal("waiting for the overflowing edit"));
            helper.assertTrue(r.isError(),
                    Component.literal("an edit whose parts would be dropped must be REFUSED, not reported as done: "
                            + r.text()));
            helper.assertTrue(r.text().contains("Nothing has been changed"),
                    Component.literal("…and it must say so plainly: " + r.text()));
            helper.assertTrue(BuildRegistry.get("gt_full").shapes().stream()
                            .noneMatch(s -> "moat".equals(s.name())),
                    Component.literal("the refused change must not be half-applied"));
        });
    }

    /** A name that does not survive being saved cannot be edited tomorrow. */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = BATCH)
    public static void aNameSurvivesTheJsonRoundTrip(GameTestHelper helper) {
        BuildPlan plan = BuildPlan.fromJson(args("""
            {"id":"rt","shapes":[{"kind":"box","name":"keep","width":4,"height":4,"depth":4,
                                  "block":"minecraft:stone"}]}"""));
        BuildPlan again = BuildPlan.fromJson(plan.toJson());
        helper.assertTrue(again != null && "keep".equals(again.shapes().get(0).name()),
                Component.literal("a name must survive the round trip"));
        BuildShape bare = new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 4, 4, 4,
                "minecraft:stone", false);
        helper.assertFalse(bare.toJson().has("name"),
                Component.literal("an unnamed shape must not gain a key, or every saved plan churns"));
        helper.succeed();
    }
}
