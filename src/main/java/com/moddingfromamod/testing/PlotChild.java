package com.moddingfromamod.testing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * <b>"The child" a tool measures from, standing inside THIS test's own plot, never at world spawn.</b>
 *
 * <p>A GameTest keeps loaded only its own structure's chunks (forced while its batch runs) and the ring of chunks
 * those keep loaded. World spawn is not one of them: it is the corner of the run's FIRST plot, and from 1.21.9 there
 * are no spawn chunks to hold it, so a mob a test stood there was removed {@code UNLOADED_TO_CHUNK} as soon as the
 * grid moved on (MobIdentityGameTest, CreatureResolverGameTest and ModifyMobGameTest, intermittently, on 1.21.10).
 * The tests were there because {@code modify_mob} and {@code look_around} measure from the child, and with nobody
 * online that meant world spawn. A stand-in child in the plot moves the whole scene into ground the test owns.
 *
 * <p>The stand-in is the shipped shape for a world with nobody in it (the headless probe's, the Magician check's):
 * a {@code FakePlayer}, never on the player list, so a test that asserts "nobody is online" still can. It is ONE
 * shared slot ({@code ActingChild.standIn}), so a test that sets it must run in a named batch of its own, as
 * {@code ActingChildBatchTest} already requires of {@code MockPlayerNetwork.actingChildFor}; {@link #standHere}
 * refuses the default batch rather than trusting that to be remembered.
 */
public final class PlotChild {

    /** Where he stands, relative to the plot: its west edge, half way along, on the floor (y 0 is the floor). */
    private static final Vec3 SPOT = new Vec3(0.5, 1, 4.5);

    private PlotChild() {
    }

    /** Where a child stands in this test's plot. A mob up to eight blocks along +x from here is inside the plot. */
    public static Vec3 spot(GameTestHelper helper) {
        return helper.absoluteVec(SPOT);
    }

    /**
     * <b>Stand a stand-in child at {@link #spot}, looking along +x into the plot, for this test only</b>, and
     * return where he stands. Put back when the test ends, passed or failed. Fails the test when somebody is online
     * (that player, not the stand-in, would be "the child") or when the test is in the default batch, where every
     * concurrent test would act round this one's child.
     */
    public static Vec3 standHere(GameTestHelper helper) {
        return standHere(helper, SPOT);
    }

    /** {@link #standHere(GameTestHelper)} at a spot of the test's choosing, relative to its plot. */
    public static Vec3 standHere(GameTestHelper helper, Vec3 relative) {
        String batch = helper.testInfo.getTest().batch().unwrapKey()
                .map(k -> k.identifier().getPath()).orElse("");
        if (batch.endsWith("/defaultbatch")) {
            helper.fail(Component.literal("PlotChild.standHere sets the ONE shared stand-in child, so "
                    + helper.testInfo.id() + " must name a batch of its own: in the default batch every test "
                    + "running beside it would act round its child"));
        }
        MockPlayerNetwork.requireNobodyIsOnline(helper);
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(relative);
        UUID id = childId(helper);
        ServerPlayer child = FakePlayerFactory.get(level, new GameProfile(id, "mfam-plot-child"));
        child.setServerLevel(level);
        child.snapTo(at.x, at.y, at.z, -90f, 0f);
        ServerPlayer before = com.moddingfromamod.agent.ActingChild.currentStandIn();
        com.moddingfromamod.agent.ActingChild.standIn(child);
        MockPlayerNetwork.afterTest(helper, () -> {
            if (com.moddingfromamod.agent.ActingChild.currentStandIn() == child) {
                com.moddingfromamod.agent.ActingChild.standIn(before != null && !before.isRemoved() ? before : null);
            }
        });
        return at;
    }

    /**
     * The stand-in's id, for a test that submits a wish in his name: a wish OWNED by anybody else is acted on for
     * that owner or nobody ({@code ActingChild.person}), so its tools would not measure from the stand-in at all.
     */
    public static UUID childId(GameTestHelper helper) {
        return UUID.nameUUIDFromBytes(("moddingfromamod:plot-child:" + helper.testInfo.id())
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * <b>Hold the chunks under positions OUTSIDE the plot while this test's batch runs</b>, with a forced ticket, so
     * a mob a test deliberately stands far from the child ("he walks twenty blocks") is not unloaded under it.
     *
     * <p>Released by the runner, not here: {@code GameTestRunner} unforces every forced chunk when a batch finishes,
     * before it lays out the next one. A release from this test's own end listener ran AFTER that, by which time the
     * next batch had forced its plot, sometimes in the very chunk released: measured, the next test's plot was
     * unloaded under it and it never finished, nor timed out. So only a test in a batch of its own may hold chunks
     * (every caller also stands a {@link #standHere} child, which requires that).
     */
    public static void holdChunksAt(GameTestHelper helper, Vec3... at) {
        ServerLevel level = helper.getLevel();
        for (Vec3 v : at) {
            ChunkPos c = new ChunkPos(BlockPos.containing(v));
            level.setChunkForced(c.x, c.z, true);
            level.getChunk(c.x, c.z);
        }
    }
}
