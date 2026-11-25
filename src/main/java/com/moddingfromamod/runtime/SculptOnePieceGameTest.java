package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.marshal.Marshal;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A sculpted creature ships in one piece</b>, driven through the REAL
 * {@code sculpt_creature} — a rule tested only where it is defined is not tested where it runs.
 *
 * <p>The unit tests ({@code SculptConnectivityTest}) prove the geometry. These prove the tool
 * refuses a creature in pieces once, lets the same one through the second time with the warning,
 * and that a {@code start_from} edit replacing the body and head brings the ears and legs along —
 * the archived cat (syn-1-007) that a child would have seen with its ears in the air.
 *
 * <p>Each call runs on its own thread bound to its own wish, because the tool hops to the server
 * thread and refuses to do that FROM the server thread. Its own batch: it binds runs in the global
 * Marshal.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SculptOnePieceGameTest {

    private static final String BATCH = "sculptOnePiece";

    private SculptOnePieceGameTest() {
    }

    /** Run {@code body} as an agent granting one wish, off the server thread. */
    private static void asAgent(String label, Runnable body) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-piece-" + label + "-" + wish;
        Marshal.global().submit(wish, UUID.randomUUID(), "gt one piece " + label);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-piece-" + label);
        t.setDaemon(true);
        t.start();
    }

    private static String said(ToolResult r) {
        return (r.isError() ? "ERROR " : "") + r.text();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aCreatureInPiecesIsSentBackOnceThenAllowed(GameTestHelper helper) {
        String id = "gt_piece_crown_" + helper.getLevel().getGameTime();
        JsonObject crowned = JsonParser.parseString("""
            {"id":"%s","spawn":false,"parts":[
              {"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"blue"},
              {"name":"head","from":[-3,12,-3],"to":[3,18,3],"colour":"blue"},
              {"name":"crown","from":[-2,22,-2],"to":[2,24,2],"colour":"yellow"}]}
            """.formatted(id)).getAsJsonObject();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<List<CreatureSculpt.Part>> afterFirst = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        asAgent("crown", () -> {
            first.set(said(new SculptCreatureTool().call(crowned.deepCopy())));
            ModelRepository.clearCache();
            afterFirst.set(ModelRepository.sculptedParts(id));
            second.set(said(new SculptCreatureTool().call(crowned.deepCopy())));
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(second.get() != null, Component.literal("waiting for both sculpts"));
            helper.assertTrue(first.get().startsWith("ERROR ") && first.get().contains("NOT SCULPTED YET")
                            && first.get().contains("crown") && first.get().contains("[0, -5, 0]"),
                    Component.literal("the first sculpt must come back naming the loose piece and the move: " + first.get()));
            helper.assertTrue(afterFirst.get().isEmpty(),
                    Component.literal("a refused sculpt must leave nothing behind: " + afterFirst.get()));
            helper.assertFalse(second.get().startsWith("ERROR "),
                    Component.literal("the same sculpt again is a deliberate gap and must go through: " + second.get()));
            helper.assertTrue(second.get().contains("STILL IN PIECES"),
                    Component.literal("...and still say so: " + second.get()));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aMuchSmallerBodyIsSentBackOnceThenCarriesItsEarsAndLegs(GameTestHelper helper) {
        String base = "gt_piece_cat_" + helper.getLevel().getGameTime();
        String edit = base + "_diamond";
        // The starter cat's layout, cut down: ears hang from the head, legs stand under the body.
        JsonObject cat = JsonParser.parseString("""
            {"id":"%s","spawn":false,"parts":[
              {"name":"body","from":[-4,6,-8],"to":[4,13,8],"colour":"orange"},
              {"name":"head","from":[-4,9,-14],"to":[4,16,-8],"colour":"orange"},
              {"name":"earL","parent":"head","from":[-4,16,-12],"to":[-2,18,-10],"colour":"orange"},
              {"name":"earR","parent":"head","from":[2,16,-12],"to":[4,18,-10],"colour":"orange"},
              {"name":"legL","from":[-3,0,-7],"to":[-1,6,-5],"colour":"orange"},
              {"name":"legR","from":[1,0,5],"to":[3,6,7],"colour":"orange"}]}
            """.formatted(base)).getAsJsonObject();
        // What Muse sent for syn-1-007: a body and head in a frame of its own.
        JsonObject diamond = JsonParser.parseString("""
            {"id":"%s","start_from":"%s","spawn":false,"parts":[
              {"name":"body","from":[0,0,0],"to":[8,8,8],"colour":"orange"},
              {"name":"head","from":[0,8,0],"to":[8,12,8],"colour":"white"}]}
            """.formatted(edit, base)).getAsJsonObject();
        AtomicReference<String> made = new AtomicReference<>();
        AtomicReference<String> firstEdit = new AtomicReference<>();
        AtomicReference<String> edited = new AtomicReference<>();
        asAgent("cat", () -> {
            made.set(said(new SculptCreatureTool().call(cat)));
            ModelRepository.clearCache();
            firstEdit.set(said(new SculptCreatureTool().call(diamond.deepCopy())));
            edited.set(said(new SculptCreatureTool().call(diamond.deepCopy())));
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(edited.get() != null, Component.literal("waiting for the sculpts"));
            helper.assertFalse(made.get().startsWith("ERROR "), Component.literal("the base cat: " + made.get()));
            // The owner graded the carried result "better, but still bad": a body half the cat's
            // size is a new SHAPE, so the agent is told first, with both boxes, rather than having
            // the ears quietly moved onto it.
            helper.assertTrue(firstEdit.get().startsWith("ERROR ") && firstEdit.get().contains("new SHAPE")
                            && firstEdit.get().contains("8×7×16"),
                    Component.literal("a much smaller body must be sent back once, naming the base's box: " + firstEdit.get()));
            helper.assertFalse(edited.get().startsWith("ERROR "),
                    Component.literal("sent again unchanged, it goes through and carries its ears and legs: " + edited.get()));
            helper.assertTrue(edited.get().contains("AUTO-FIXED") && edited.get().contains("earL"),
                    Component.literal("the carried parts are said out loud: " + edited.get()));
            ModelRepository.clearCache();
            var parts = ModelRepository.sculptedParts(edit);
            helper.assertTrue(parts.size() == 6, Component.literal("all six parts saved: " + parts.size()));
            helper.assertTrue(SculptConnectivity.islands(parts).isEmpty(),
                    Component.literal("the SAVED creature is one piece: " + SculptConnectivity.islands(parts)));
        });
    }

    /**
     * #132: a sculpt is stored with its own shape, not {@code humanoid} whatever it is, so its stand-in (if its model is
     * ever not to hand) is a four-legged animal, not a zombie two blocks tall. Through the real {@code sculpt_creature}.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aFourLeggedSculptIsStoredAsAFourLeggedBody(GameTestHelper helper) {
        String id = "gt_shape_dog_" + helper.getLevel().getGameTime();
        JsonObject dog = JsonParser.parseString("""
            {"id":"%s","spawn":false,"parts":[
              {"name":"body","from":[-4,6,-8],"to":[4,13,8],"colour":"brown"},
              {"name":"head","from":[-4,9,-14],"to":[4,16,-8],"colour":"brown"},
              {"name":"legFL","from":[-4,0,-7],"to":[-2,6,-5],"colour":"brown"},
              {"name":"legFR","from":[2,0,-7],"to":[4,6,-5],"colour":"brown"},
              {"name":"legBL","from":[-4,0,5],"to":[-2,6,7],"colour":"brown"},
              {"name":"legBR","from":[2,0,5],"to":[4,6,7],"colour":"brown"}]}
            """.formatted(id)).getAsJsonObject();
        AtomicReference<String> made = new AtomicReference<>();
        asAgent("dog", () -> made.set(said(new SculptCreatureTool().call(dog))));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(made.get() != null, Component.literal("still sculpting"));
            helper.assertFalse(made.get().startsWith("ERROR"), Component.literal("the dog: " + made.get()));
            var species = com.moddingfromamod.species.SpeciesRegistry.get(id);
            helper.assertTrue(species != null && species.body() == com.moddingfromamod.species.Species.Body.QUADRUPED,
                    Component.literal("a four-legged sculpt is stored as one: " + (species == null ? "none" : species.body())));
        });
    }
}
