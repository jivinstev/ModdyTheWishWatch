package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.RemoveCreatureTool;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a rig creature can be taken out of the world — by {@code remove_creature}, by a real
 * {@code /kill}, and by a {@code /kill} after its chunk has unloaded and loaded again — and nothing
 * of it is left standing.</b>
 *
 * <p>Every assertion counts THIS carrier's own displays and hit proxies (by its UUID), after the
 * whole removal has happened (a killed mob leaves the world ~20 ticks after the blow). The tests
 * before this one called {@code DisplayRig.reap} directly, or asserted proxies only — so the path a
 * child's "get rid of it" actually takes had no test. It found one real bug: {@code remove_creature}
 * swept only the retired {@code WishMobEntity}, so it answered "no such creature" to every rig.
 * (a live server's "a {@code /kill} left 101 display parts behind" was NOT a rig bug: the kills went in
 * over RCON while vanilla's pause-when-empty had stopped the server ticking, so no killed mob ever
 * finished dying. These tests are the proof that a ticking server reaps them.)
 *
 * <p>The rigs stand far from every test plot, on chunks forced loaded, because a neighbouring
 * plot's {@code succeed} discards every non-player entity in it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigRemovalGameTest {

    private static final String BATCH = "rigRemoval";

    private RigRemovalGameTest() {
    }

    /** What the level holds of this carrier, for a failure message: found? where? tags? */
    private static String census(ServerLevel level, UUID carrier) {
        Entity e = level.getEntity(carrier);
        long rigs = 0;
        for (Entity x : level.getEntities().getAll()) {
            if (x.getTags().contains(DisplayRig.RIG_TAG)) {
                rigs++;
            }
        }
        return "carrier " + (e == null ? "NOT IN LEVEL" : e.getType().toShortString()
                + " removed=" + e.isRemoved() + " tags=" + e.getTags()
                + " speciesOn=" + (DisplayRig.speciesOn(e) == null ? null : DisplayRig.speciesOn(e).id()))
                + "; " + rigs + " entities carry the rig tag";
    }

    /** A creature with a real sculpt, and a rig of it at {@code base}, driven like a live one. */
    private static DisplayRig rigAt(GameTestHelper helper, String id, BlockPos base) {
        ServerLevel level = helper.getLevel();
        List<CreatureSculpt.Part> parts = RigFitGameTest.whale();
        try {
            CreatureFiles.write(id, parts, RigReloadGameTest.spec(parts));
        } catch (Exception e) {
            throw new IllegalStateException("could not write the sculpt: " + e, e);
        }
        Species sp = com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\",\"health\":40,"
                        + "\"speed\":0.0,\"scale\":1.0}").getAsJsonObject()));
        List<CreatureSculpt.Part> saved = StandInSculpt.manifest(sp,
                com.moddingfromamod.runtime.ModelRepository.sculptedParts(id));
        DisplayRig rig = DisplayRig.spawn(level, Vec3.atBottomCenterOf(base), 0f, saved, sp.scale(), id,
                CarrierPool.movementOf(sp, saved));
        rig.base().setNoAi(true);
        VanillaRigAnimator.drive(rig, RigFit.intoRigSpace(saved), java.util.Map.of());
        return rig;
    }

    private static int kill(ServerLevel level, String id) {
        try {
            return level.getServer().getCommands().getDispatcher().execute(
                    "kill @e[tag=" + WishMobTags.forSpecies(id) + "]",
                    level.getServer().createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return -1;
        }
    }

    private static void assertNothingLeft(GameTestHelper helper, UUID carrier, String how) {
        ServerLevel level = helper.getLevel();
        Entity c = level.getEntity(carrier);
        helper.assertTrue(c == null || c.isRemoved(), Component.literal(how + ": the carrier is still in the world"));
        int d = RigReloadGameTest.owned(level, carrier, false).size();
        int p = RigReloadGameTest.owned(level, carrier, true).size();
        helper.assertTrue(d == 0 && p == 0, Component.literal(how + ": " + d + " display part(s) and " + p
                + " hit prox(ies) of the creature are still standing"));
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aRealKillTakesTheWholeRigWithIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4)).offset(4608, 0, 0);
        ChunkPos chunk = new ChunkPos(base);
        level.setChunkForced(chunk.x, chunk.z, true);
        String id = "gt_889_kill";
        UUID[] carrier = new UUID[1];
        helper.startSequence()
                // WAIT FOR THE FORCED CHUNK TO TICK ENTITIES. Forcing it is a ticket, and the
                // ticket is processed on a later tick; a rig spawned before then is in the level
                // but invisible to every selector and to getAll() until it is — measured on 1.21.1,
                // where /kill and remove_creature both answered "nothing there" for a carrier the
                // UUID lookup found moments later.
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(base),
                        Component.literal("the forced chunk is not ticking entities yet")))
                .thenExecuteAfter(5, () -> {
                    DisplayRig rig = rigAt(helper, id, base);
                    carrier[0] = rig.base().getUUID();
                    helper.assertTrue(!rig.displays().isEmpty(), Component.literal("the rig has no displays to lose"));
                })
                .thenExecuteAfter(5, () -> helper.assertTrue(kill(level, id) > 0,
                        Component.literal("/kill found no creature wearing the species tag")))
                .thenWaitUntil(() -> assertNothingLeft(helper, carrier[0], "after /kill"))
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false))
                .thenSucceed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void removeCreatureRemovesARigCreatureAndAllOfIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4)).offset(4864, 0, 0);
        ChunkPos chunk = new ChunkPos(base);
        level.setChunkForced(chunk.x, chunk.z, true);
        String id = "gt_889_remove";
        UUID[] carrier = new UUID[1];
        AtomicReference<String> said = new AtomicReference<>();
        helper.startSequence()
                // WAIT FOR THE FORCED CHUNK TO TICK ENTITIES. Forcing it is a ticket, and the
                // ticket is processed on a later tick; a rig spawned before then is in the level
                // but invisible to every selector and to getAll() until it is — measured on 1.21.1,
                // where /kill and remove_creature both answered "nothing there" for a carrier the
                // UUID lookup found moments later.
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(base),
                        Component.literal("the forced chunk is not ticking entities yet")))
                .thenExecuteAfter(5, () -> carrier[0] = rigAt(helper, id, base).base().getUUID())
                .thenExecuteAfter(5, () -> {
                    JsonObject args = new JsonObject();
                    args.addProperty("id", id);
                    // Off the server thread: the tool hops onto it, and refuses a caller already there.
                    Thread t = new Thread(() -> said.set(new RemoveCreatureTool().call(args).text()),
                            "gt-889-remove");
                    t.setDaemon(true);
                    t.start();
                })
                .thenWaitUntil(() -> helper.assertTrue(said.get() != null, Component.literal("remove_creature has not answered")))
                .thenExecute(() -> helper.assertTrue(said.get().startsWith("Removed 1 "),
                        Component.literal("remove_creature did not find the rig creature standing there: " + said.get()
                                + " | " + census(level, carrier[0]))))
                .thenWaitUntil(() -> assertNothingLeft(helper, carrier[0], "after remove_creature"))
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false))
                .thenSucceed();
    }

    /**
     * The shape a live server was in: the creature's chunk had unloaded and loaded again, so the rig was
     * ADOPTED from disk rather than the one spawned — then {@code /kill}.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1600)
    public static void aKillAfterTheChunkReloadsStillTakesTheWholeRig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4)).offset(5120, 0, 0);
        ChunkPos chunk = new ChunkPos(base);
        level.setChunkForced(chunk.x, chunk.z, true);
        String id = "gt_889_reload";
        UUID[] carrier = new UUID[1];
        Mob[] first = new Mob[1];
        helper.startSequence()
                // WAIT FOR THE FORCED CHUNK TO TICK ENTITIES. Forcing it is a ticket, and the
                // ticket is processed on a later tick; a rig spawned before then is in the level
                // but invisible to every selector and to getAll() until it is — measured on 1.21.1,
                // where /kill and remove_creature both answered "nothing there" for a carrier the
                // UUID lookup found moments later.
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(base),
                        Component.literal("the forced chunk is not ticking entities yet")))
                .thenExecuteAfter(5, () -> {
                    DisplayRig rig = rigAt(helper, id, base);
                    carrier[0] = rig.base().getUUID();
                    first[0] = rig.base();
                })
                .thenExecuteAfter(5, () -> level.setChunkForced(chunk.x, chunk.z, false))
                .thenWaitUntil(() -> {
                    level.getChunkSource().tick(() -> true, false);
                    helper.assertTrue(first[0].isRemoved() && first[0].getRemovalReason()
                            == Entity.RemovalReason.UNLOADED_TO_CHUNK, Component.literal("the chunk has not unloaded yet"));
                })
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, true))
                .thenWaitUntil(() -> helper.assertTrue(
                        VanillaRigAnimator.isDriving(level.getEntity(carrier[0])),
                        Component.literal("the creature came back from disk and was not adopted")))
                .thenExecute(() -> helper.assertTrue(kill(level, id) > 0,
                        Component.literal("/kill found no creature wearing the species tag after the reload")))
                .thenWaitUntil(() -> assertNothingLeft(helper, carrier[0], "after a reload and /kill"))
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false))
                .thenSucceed();
    }
}
