package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureHistory;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.wish.WishUndo;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Undoing a wish that made a creature takes the creature away — on the rig.</b>
 *
 * <p>{@code WishUndo.undoCreature}'s creation branch discarded every {@code WISH_MOB} entity of the
 * species. Since there are none: the bridge swaps each for a vanilla carrier, its display
 * parts and its hit proxies. So an undo removed nothing, returned false, and the creature stood
 * there — "undone", with the thing he asked to undo still in front of him.
 *
 * <p>The contract is PER WISH, as every other reversal in {@code WishUndo} is: only the creatures
 * THIS wish spawned go, found by the carriers its run recorded. A second wish's creature of
 * the same species — a brother's, or the same child's next wish — stays. The design is kept, as
 * {@code remove_creature} keeps it, so the spawn egg still works.
 *
 * <p>A long creature (the shark, which the rig gives hit proxies) so "nothing is left" covers the
 * proxies as well as the displays.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RigUndoGameTest {

    private static final String SHARK = SpikeFixture.real("tralalelo-armA");

    @GameTest(template = "empty_test", batch = "t6g3_undo", timeoutTicks = 400)
    public static void undoingACreationRemovesItsRigsAndOnlyItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = "gt_t6g3_shark";
        CreatureHistory.forget(id);
        plantSculpted(helper, id);
        tidy(level, id);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2)));

        // Wish A spawns two; wish B — another child — spawns one of the same creature.
        var a = run(UUID.nameUUIDFromBytes("gt-t6g3-a".getBytes()), "gt-t6g3-wa", "two sharks");
        var b = run(UUID.nameUUIDFromBytes("gt-t6g3-b".getBytes()), "gt-t6g3-wb", "a shark");
        List<LivingEntity> mine = new ArrayList<>();
        AgentContext.with("gt-t6g3-wa", () -> {
            for (int i = 0; i < 2; i++) {
                mine.add(CreatureSpawn.spawn(level, id, at.x + 3 * i, at.y, at.z, 0f,
                        EntitySpawnReason.COMMAND));
            }
            return null;
        });
        a.made(MadeKind.CREATURE, id);
        LivingEntity theirs = AgentContext.with("gt-t6g3-wb", () ->
                CreatureSpawn.spawn(level, id, at.x, at.y, at.z + 4, 0f, EntitySpawnReason.COMMAND));
        b.made(MadeKind.CREATURE, id);
        try {
            helper.assertTrue(mine.stream().allMatch(c -> c != null) && theirs != null,
                    Component.literal("the creatures did not stand up, so there is nothing to undo"));
            int proxies = proxiesOf(level, mine.get(0));
            helper.assertTrue(displaysOf(level, mine.get(0)) > 0 && proxies > 0,
                    Component.literal("the shark should have display parts and hit proxies (" + proxies
                            + " proxies) or this proves nothing about them"));

            WishUndo.Result r = WishUndo.undo(level.getServer(), a);

            helper.assertTrue(r.anything() && r.did().contains(id), Component.literal("#949: undo reported it "
                    + "could not put the creature back: did " + r.did() + ", couldNot "
                    + r.couldNot()));
            for (LivingEntity c : mine) {
                helper.assertFalse(c.isAlive() && !c.isRemoved(), Component.literal("#949: the creature this wish "
                        + "made is still standing after the undo"));
                helper.assertTrue(displaysOf(level, c) == 0, Component.literal("#949: its display parts are still "
                        + "standing — a creature-shaped ghost"));
                helper.assertTrue(proxiesOf(level, c) == 0, Component.literal("#949: its hit proxies are still "
                        + "standing — invisible mobs you can hit"));
            }
            helper.assertTrue(theirs.isAlive() && !theirs.isRemoved()
                            && displaysOf(level, theirs) > 0,
                    Component.literal("undoing one wish took ANOTHER wish's creature of the same kind"));
            helper.assertTrue(SpeciesRegistry.has(id), Component.literal("undoing a creation keeps the design, as "
                    + "remove_creature does, so his spawn egg still works"));
            helper.succeed();
        } finally {
            Marshal.global().finish(a.wishId());
            Marshal.global().finish(b.wishId());
            tidy(level, id);
        }
    }

    // ===================== helpers =====================

    private static com.moddingfromamod.marshal.WishRun run(UUID player, String worker,
                                                           String text) {
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, player, text);
        return Marshal.global().assign(worker, wish);
    }

    private static void plantSculpted(GameTestHelper helper, String id) {
        var parts = SpikeFixture.parts(SHARK);
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SHARK + "/sculpt.json");
            helper.assertTrue(spec != null, Component.literal("the corpus sculpt is missing"));
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Undo Shark\","
                        + "\"modelKey\":\"" + id + "\",\"health\":20,\"speed\":0.0,\"scale\":"
                        + SpikeFixture.scale(SHARK) + "}").getAsJsonObject()));
    }

    private static int displaysOf(ServerLevel level, Entity carrier) {
        String owner = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        int n = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof Display && e.getTags().contains(owner) && !e.isRemoved()) {
                n++;
            }
        }
        return n;
    }

    private static int proxiesOf(ServerLevel level, Entity carrier) {
        int n = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (HitProxies.belongsTo(e, carrier.getUUID()) && !e.isRemoved()) {
                n++;
            }
        }
        return n;
    }

    private static void tidy(ServerLevel level, String id) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e.getTags().contains(WishMobTags.forSpecies(id))
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            DisplayRig.reap(level, e.getUUID());
            e.discard();
        }
    }
}
