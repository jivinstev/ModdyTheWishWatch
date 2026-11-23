package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.wish.WishUndo;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Undoing a refinement puts the creature back one sculpt — it does not kill him and keep the
 * wings.
 *
 * <p>The old behaviour applied the CREATION rule to both cases, which is the worst possible split:
 * the mobs were discarded and the edited design was kept, so after <i>"give him tiny black
 * wings"</i> → undo, the child lost his creature and re-spawning it gave him the winged one back.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CreatureUndoGameTest {

    private static Species species(String id, double scale) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"Undo Beast\",\"health\":18,\"scale\":"
                        + scale + "}").getAsJsonObject());
    }

    /** A sculpt on disk: the folder is what an undo has to put back. */
    private static void sculpt(String key, String marker) throws Exception {
        var dir = ModelRepository.dir(key);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("sculpt.json"), marker);
    }

    private static String currentSculpt(String key) throws Exception {
        var f = ModelRepository.dir(key).resolve("sculpt.json");
        return Files.isRegularFile(f) ? Files.readString(f) : "";
    }

    @GameTest(template = "empty_test", timeoutTicks = 300, batch = "creatureundo")
    public void undoingARefinementLeavesTheCreatureThereWithoutTheWings(GameTestHelper helper) {
        try {
            var server = helper.getLevel().getServer();
            String id = "gt_undo_wings";
            CreatureHistory.forget(id);

            sculpt(id, "{\"v\":\"no wings\"}");
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(id, 1.0));

            // The refinement, exactly as SculptCreatureTool does it: snapshot, then overwrite.
            CreatureHistory.push(id, SpeciesRegistry.get(id));
            sculpt(id, "{\"v\":\"WINGS\"}");
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(id, 2.5));
            helper.assertTrue(CreatureHistory.depth(id) == 1,
                    Component.literal("the sculpt being replaced was not kept, so there is nothing to go back TO"));

            helper.assertTrue(WishUndo.undoOne(server, MadeKind.CREATURE, id),
                    Component.literal("undo reported failure"));

            // 1. The DESIGN went back — this is the half that used to survive the undo.
            helper.assertTrue(currentSculpt(id).contains("no wings"),
                    Component.literal("the wings survived the undo, which is #156 itself: " + currentSculpt(id)));
            // 2. The REGISTRY ROW went back with it, or the record is ahead of the model (R3).
            Species now = SpeciesRegistry.get(id);
            helper.assertTrue(now != null && Math.abs(now.scale() - 1.0) < 1e-6,
                    Component.literal("the species row still has the refinement's scale: "
                            + (now == null ? "null" : now.scale())));
            // 3. The creature still EXISTS — undo is not a deletion here.
            helper.assertTrue(SpeciesRegistry.has(id), Component.literal("undo removed the creature entirely"));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("could not drive the sculpt files: " + e));
        }
    }

    @GameTest(template = "empty_test", timeoutTicks = 300, batch = "creatureundo")
    public void undoingACreationStillKeepsTheDesign(GameTestHelper helper) {
        try {
            var server = helper.getLevel().getServer();
            String id = "gt_undo_first";
            CreatureHistory.forget(id);
            sculpt(id, "{\"v\":\"first\"}");
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(id, 1.0));

            helper.assertTrue(CreatureHistory.depth(id) == 0, Component.literal("a creation has no previous sculpt"));
            // No mobs of it are standing, so undoOne reports false — and the DESIGN is kept either
            // way, exactly as remove_creature keeps it. He stopped the wish; he did not ask for the
            // idea to be forgotten.
            WishUndo.undoOne(server, MadeKind.CREATURE, id);
            helper.assertTrue(SpeciesRegistry.has(id),
                    Component.literal("undoing a creation must keep the design, so his spawn egg still works"));
            helper.assertTrue(currentSculpt(id).contains("first"),
                    Component.literal("the sculpt was reverted to something that never existed"));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("could not drive the sculpt files: " + e));
        }
    }

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "creatureundo")
    public void twoRefinementsWalkBackInOrder(GameTestHelper helper) {
        try {
            var server = helper.getLevel().getServer();
            String id = "gt_undo_twice";
            CreatureHistory.forget(id);

            sculpt(id, "{\"v\":\"v1\"}");
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(id, 1.0));
            for (String v : List.of("v2", "v3")) {
                CreatureHistory.push(id, SpeciesRegistry.get(id));
                sculpt(id, "{\"v\":\"" + v + "\"}");
                com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(id, v.equals("v2") ? 2.0 : 3.0));
            }
            helper.assertTrue(CreatureHistory.depth(id) == 2,
                    Component.literal("expected two older sculpts, got " + CreatureHistory.depth(id)));

            WishUndo.undoOne(server, MadeKind.CREATURE, id);
            helper.assertTrue(currentSculpt(id).contains("v2"),
                    Component.literal("one undo should land on v2, got " + currentSculpt(id)));
            WishUndo.undoOne(server, MadeKind.CREATURE, id);
            helper.assertTrue(currentSculpt(id).contains("v1"),
                    Component.literal("two undos should land on v1, got " + currentSculpt(id)));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("could not drive the sculpt files: " + e));
        }
    }
}
