package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import java.util.UUID;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wish's test scaffolding must come down, and the wish itself must not —.</b>
 *
 * <p>{@link PropSweep} is pure and has its own tests; what this adds is the JOIN — a real entity, a
 * real level, a real run ledger — which is the half that was wrong in every case this repo keeps
 * relearning: <i>a rule tested only where it is DEFINED is not tested where it RUNS.</i>
 *
 * <p><b>Both directions, always.</b> A sweep that removes everything passes the first test here and
 * is a catastrophe; a sweep that removes nothing passes the second and is the bug. Neither test is
 * worth anything without the other, which is why they are in one file with this note between them.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishPropsGameTest {

    private static final String BATCH = "wishprops";

    /**
     * A run of our own, from a marshal of our own.
     *
     * <p>NOT {@code Marshal.global()}, and the reason is a rule this repo already records: GameTests
     * in one batch run concurrently and share every static. A submit to the global marshal leaves
     * the wish in its waiting QUEUE, so a concurrent test calling {@code claim} would be handed
     * this test's fixture and fail against another test rather than against the code. Nothing in
     * the sweep path needs a marshal anyway — {@link WishProps} takes the run itself.
     */
    private static WishRun freshRun() {
        return Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1)
                .submit(UUID.randomUUID(), UUID.randomUUID(), "a test wish for #517");
    }

    /**
     * <b>The reported case, end to end.</b> An armour stand summoned to aim a fire breath at, still
     * standing when the wish ends.
     *
     * <p><b>Verified to fail</b> by returning {@code Kept.REGISTERED} unconditionally from
     * {@code PropSweep.verdict}: the stand survives and the assertion names it.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aTestPropIsTakenDownWhenTheWishEnds(GameTestHelper helper) {
        var level = helper.getLevel();
        var where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        WishRun run = freshRun();

        var before = WishProps.snapshot(level, where);
        Entity stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(stand != null, Component.literal("the fixture must be able to make an armour stand"));
        stand.setPos(where.x, where.y, where.z);
        level.addFreshEntity(stand);
        UUID standId = stand.getUUID();

        int recorded = WishProps.recordNew(level, where, before, run);
        helper.assertTrue(recorded >= 1,
                Component.literal("the bracket saw nothing appear — with the diff blind, the sweep below can only "
                        + "ever pass by doing nothing. Recorded " + recorded));
        helper.assertTrue(run.commandSpawned().size() >= 1,
                Component.literal("the prop must be on the RUN's ledger, not a global one: a second wish's tidy-up "
                        + "must never be able to reach this one's props"));

        var swept = WishProps.sweep(helper.getLevel().getServer(), run);
        helper.assertTrue(swept.removed() >= 1,
                Component.literal("the armour stand was left standing — this is #517 verbatim, and nothing about it "
                        + "is wrong enough to trip any other assertion. Swept " + swept));
        helper.assertTrue(level.getEntity(standId) == null
                        || level.getEntity(standId).isRemoved(),
                Component.literal("the sweep reported a removal and the entity is still in the world"));
        helper.succeed();
    }

    /**
     * <b>The other half, and it is the one that matters.</b> says it plainly: getting this
     * wrong deletes the present. A creature the wish REGISTERED must survive its own tidy-up.
     *
     * <p><b>Verified to fail</b> by returning {@code Kept.NOT_KEPT} unconditionally: the bee is
     * discarded and the assertion names it.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void whatTheWishKEPTSurvivesItsOwnTidyUp(GameTestHelper helper) {
        var level = helper.getLevel();
        var where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        WishRun run = freshRun();

        // BOTH snapshots, in production's order — EntityCapture's LivingEntity diff mints the
        // handle and WishProps' wider one only reads it back, so a fixture that takes the second
        // without the first records a prop with no handle and the keep rule has nothing to match
        // on. Caught by the guard below on this test's first real run, which is what it is for.
        var beforeLiving = com.moddingfromamod.mob.EntityCapture.snapshot(level, where);
        var before = WishProps.snapshot(level, where);
        Entity carrier = EntityType.BEE.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(carrier != null, Component.literal("the fixture must be able to make a carrier"));
        carrier.setPos(where.x, where.y, where.z);
        level.addFreshEntity(carrier);
        UUID carrierId = carrier.getUUID();

        com.moddingfromamod.mob.EntityCapture.rememberNew(level, where, beforeLiving);
        WishProps.recordNew(level, where, before, run);
        // The handle the diff minted for it is what a wish REGISTERS. Recording it in made() is
        // exactly what sculpt_creature's McpProtocol hook does at the end of a real sculpt.
        String handle = com.moddingfromamod.mob.EntityHandles.global().handleFor(carrierId);
        helper.assertTrue(handle != null && !handle.isBlank(),
                Component.literal("the entity diff must have minted a handle, or the keep rule has nothing to match "
                        + "on and this test would pass for the wrong reason"));
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, handle);

        var swept = WishProps.sweep(helper.getLevel().getServer(), run);
        helper.assertTrue(swept.kept() >= 1 && swept.removed() == 0,
                Component.literal("the wish's own creature was swept away as scaffolding — this is the direction "
                        + "#517 warns about, and it deletes what the child asked for. Swept "
                        + swept));
        Entity still = level.getEntity(carrierId);
        helper.assertTrue(still != null && !still.isRemoved(),
                Component.literal("the registered creature must still be in the world after its own wish tidies up"));
        still.discard();
        helper.succeed();
    }

    /**
     * A prop the agent already killed is not a failure to report — one of the two real fire tests
     * in the action log opens with {@code kill @e[tag=firetest]}, so this is the common case and
     * must read as "already gone" rather than as a sweep that did nothing.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aPropTheAgentAlreadyClearedIsCountedNotMourned(GameTestHelper helper) {
        var level = helper.getLevel();
        var where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        WishRun run = freshRun();

        var before = WishProps.snapshot(level, where);
        Entity stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        stand.setPos(where.x, where.y, where.z);
        level.addFreshEntity(stand);
        WishProps.recordNew(level, where, before, run);
        stand.discard();      // "kill @e[tag=firetest]" — the agent tidied up after itself

        var swept = WishProps.sweep(helper.getLevel().getServer(), run);
        helper.assertTrue(swept.alreadyGone() >= 1 && swept.removed() == 0,
                Component.literal("a prop the agent cleared itself must be counted as already gone, not removed "
                        + "again and not silently dropped. Swept " + swept));
        helper.assertTrue(!PropSweep.report(swept.removed(), swept.alreadyGone(), swept.kept())
                        .isBlank(),
                Component.literal("and the agent should still be told, so 'the sweep worked' and 'the sweep never "
                        + "ran' cannot print the same word"));
        helper.succeed();
    }
}
