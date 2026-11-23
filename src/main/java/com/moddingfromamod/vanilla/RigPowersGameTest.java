package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.SPOT;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.carrier;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.plant;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.wish;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Damage;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: boss, explodes and flies reach the atom-rig carrier</b>, through the real
 * bridge. Each was red on {@code main}: no bar on anyone's screen, a death with no bang, and a
 * flying creature that fluttered about like the bat it rides instead of coming to the child.
 *
 * <p>Every rig here stays inside its own 9-block plot: a GameTest that succeeds discards every
 * entity in its bounds, so a creature that strays into a neighbour's loses its carrier mid-test.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigPowersGameTest {

    private static final String BATCH = "rig_powers";

    private static ServerPlayer child(GameTestHelper helper, String name, BlockPos rel,
                                      boolean survival) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        var at = helper.absolutePos(rel);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (survival) {
            MockPlayerNetwork.asSurvival(p);
        }
        return p;
    }

    private static void kill(Mob c) {
        Damage.hurt(c, c.damageSources().genericKill(), 10_000f);
    }

    // ── boss: a bar on his screen that follows its health, and goes with it ────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aBossRigShowsItsBarAndTheBarGoesWithIt(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_boss", "{base:'wild', powers:['friendly','boss'], "
                + "name:'Big Boss'}");
        ServerPlayer kid = child(helper, "mfam-boss", new BlockPos(6, 1, 6), false);
        wish(helper, id);
        AtomicReference<ServerBossEvent> held = new AtomicReference<>();
        AtomicBoolean hurt = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            if (held.get() == null) {
                helper.assertTrue(c != null, Component.literal("no carrier yet"));
                ServerBossEvent bar = RigPowers.barOf(c);
                helper.assertTrue(bar != null, Component.literal("a boss creature's carrier has no boss bar"));
                helper.assertTrue(bar.getPlayers().contains(kid),
                        Component.literal("the boss bar is not on the child's screen"));
                helper.assertTrue(bar.getName().getString().equals("Big Boss"),
                        Component.literal("the bar is not named for the creature: " + bar.getName().getString()));
                if (!hurt.getAndSet(true)) {
                    Damage.hurt(c, helper.getLevel().damageSources().magic(), c.getMaxHealth() / 2);
                }
                helper.assertTrue(bar.getProgress() < 0.75f,
                        Component.literal("the bar did not follow its health down: " + bar.getProgress()));
                held.set(bar);
                kill(c);
            }
            helper.assertTrue(held.get().getPlayers().isEmpty(),
                    Component.literal("the creature died and its bar stayed on the child's screen"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void anAdoptedBossRigGetsItsBarBack(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_bossadopt", "{base:'wild', powers:['friendly','boss']}");
        ServerPlayer kid = child(helper, "mfam-bossadopt", new BlockPos(6, 1, 6), false);
        wish(helper, id);
        AtomicBoolean reloaded = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null && RigPowers.barOf(c) != null, Component.literal("no bar yet"));
            if (!reloaded.getAndSet(true)) {
                VanillaRigAnimator.forget(c.getUUID());
                helper.assertTrue(RigPowers.barOf(c) == null, Component.literal("forgetting the rig kept its bar"));
                VanillaRigAnimator.adoptAll(helper.getLevel());
            }
            ServerBossEvent bar = RigPowers.barOf(c);   // back on the tick after adoption
            helper.assertTrue(bar != null && bar.getPlayers().contains(kid),
                    Component.literal("after adoption the boss bar is not back on the child's screen"));
        });
    }

    /**
     * <b>A boss still being wished has no bar yet</b>. Shown early, shimmering,
     * its health bar sat under the wish's own: two bars for one wish. The bar comes when the wish
     * ends.
     */
    @GameTest(template = "empty_test", batch = "rig_powers_boss_wished", timeoutTicks = 400)
    public static void aBossStillBeingWishedShowsNoBarUntilTheWishEnds(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_bosswished", "{base:'wild', powers:['friendly','boss']}");
        ServerPlayer kid = child(helper, "mfam-bosswished", new BlockPos(6, 1, 6), false);
        java.util.UUID wishId = java.util.UUID.randomUUID();
        // A LIVE wish: the shimmer ends itself for any wish that is not (WishShimmer.onServerTick).
        com.moddingfromamod.marshal.Marshal.global().submit(wishId, kid.getUUID(), "make me a boss");
        com.moddingfromamod.marshal.Marshal.global().assign("gt-boss-wished-" + helper.getLevel().getGameTime(), wishId);
        WishShimmer.begin(wishId, java.util.List.of(id), helper.getLevel().getGameTime());
        wish(helper, id);
        java.util.concurrent.atomic.AtomicInteger quietTicks = new java.util.concurrent.atomic.AtomicInteger();
        AtomicBoolean ended = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (!ended.get()) {
                helper.assertTrue(RigPowers.barOf(c) == null,
                        Component.literal("a boss still being wished put its bar on screen beside the wish's"));
                if (quietTicks.incrementAndGet() < 40) {
                    helper.fail(Component.literal("watching it stay quiet"));
                }
                WishShimmer.end(helper.getLevel().getServer(), wishId);
                com.moddingfromamod.marshal.Marshal.global().finish(wishId);
                ended.set(true);
                helper.fail(Component.literal("the wish just ended"));
            }
            ServerBossEvent bar = RigPowers.barOf(c);
            helper.assertTrue(bar != null && bar.getPlayers().contains(kid),
                    Component.literal("the wish ended and the boss bar never arrived"));
        });
    }

    /** An edit of a boss he already has: no bar from the wish's plan to its end (filmed 2026-10-22). */
    @GameTest(template = "empty_test", batch = "rig_powers_boss_edited", timeoutTicks = 400)
    public static void aBossAWishIsAboutShowsNoBarUntilTheWishEnds(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_bossedit", "{base:'wild', powers:['friendly','boss']}");
        ServerPlayer kid = child(helper, "mfam-bossedit", new BlockPos(6, 1, 6), false);
        wish(helper, id);
        java.util.UUID wishId = java.util.UUID.randomUUID();
        AtomicReference<com.moddingfromamod.marshal.WishRun> run = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (run.get() == null) {
                helper.assertTrue(RigPowers.barOf(c) != null, Component.literal("his boss has no bar before the wish"));
                com.moddingfromamod.marshal.Marshal.global().submit(wishId, kid.getUUID(), "make my boss bigger");
                var r = com.moddingfromamod.marshal.Marshal.global().assign(
                        "gt-boss-edited-" + helper.getLevel().getGameTime(), wishId);
                r.about(id);   // what plan_wish's 'about' records
                run.set(r);
                helper.fail(Component.literal("the wish just planned"));
            }
            if (!ended.get()) {
                helper.assertTrue(RigPowers.barOf(c) == null,
                        Component.literal("the boss a wish is about kept its bar beside the wish's"));
                com.moddingfromamod.marshal.Marshal.global().finish(wishId);
                ended.set(true);
                helper.fail(Component.literal("the wish just ended"));
            }
            ServerBossEvent bar = RigPowers.barOf(c);
            helper.assertTrue(bar != null && bar.getPlayers().contains(kid),
                    Component.literal("the wish ended and his boss's bar never came back"));
        });
    }

    // ── explodes: a bang on death that breaks nothing ────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void anExplodingRigBurstsOnDeathAndBreaksNoBlock(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_explodes", "{base:'wild', powers:['friendly','explodes']}");
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, SPOT.offset(2, 0, 2));
        zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        BlockPos stone = SPOT.offset(-1, 0, 3);
        helper.setBlock(stone, Blocks.STONE);
        wish(helper, id);
        AtomicBoolean killed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            if (!killed.get()) {
                helper.assertTrue(c != null, Component.literal("no carrier yet"));
                killed.set(true);
                kill(c);
            }
            var last = zombie.getLastDamageSource();
            helper.assertTrue(last != null && last.is(DamageTypeTags.IS_EXPLOSION),
                    Component.literal("the creature died without a bang (the zombie beside it was hurt by "
                            + (last == null ? "nothing" : last.getMsgId()) + ")"));
            helper.assertBlockPresent(Blocks.STONE, stone);
        });
    }

    // ── flies: it comes to him through the air ───────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 800)
    public static void aFlyingCompanionFliesToTheChildAndStays(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_flies", "{base:'companion', powers:['friendly','flies'], "
                + "scale:0.5}");
        ServerPlayer kid = child(helper, "mfam-flies", new BlockPos(7, 1, 7), false);
        wish(helper, id);
        int[] near = {0};
        double[] last = {-1};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null) {
                last[0] = c.distanceTo(kid);
                near[0] = last[0] < 4.0 ? near[0] + 1 : 0;
            }
        });
        // HELD, not touched once: a bat fluttering about a 9-block box passes close now and
        // then on its own.
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c instanceof Bat, Component.literal("a flying creature should ride a bat, not "
                    + (c == null ? "nothing" : c.getType().toShortString())));
            helper.assertTrue(near[0] >= 60, Component.literal(String.format(
                    "the flying companion has not come to the child and stayed (%.1f blocks, "
                            + "near for %d ticks)", last[0], near[0])));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 800)
    public static void aFlyingHunterFliesAtTheChildAndHurtsHim(GameTestHelper helper) {
        String id = plant(helper, "t6c4c_flyhunt", "{base:'hunter', powers:['flies'], damage:2, "
                + "scale:0.5}");
        ServerPlayer kid = child(helper, "mfam-flyhunt", new BlockPos(7, 1, 7), true);
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c instanceof Bat, Component.literal("no bat carrier yet"));
            helper.assertTrue(kid.getLastHurtByMob() == c, Component.literal(String.format(
                    "the flying hunter has not reached the child (%.1f blocks, target %s)",
                    c.distanceTo(kid), RigInstincts.targetOf(c))));
        });
    }

    // ── a hovering flier's body does not point a new way every tick ───────

    /**
     * Phone films: a dragon hovering by him flipped its heading several times a second.
     * A flier rides a bat, and the rig drew the bat's velocity heading, which is noise in a hover.
     * Read off a DISPLAY (what he sees), with {@code rigturn=off} as the run-red seam.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void aHoveringFliersBodyTurnsAtACreaturesPace(GameTestHelper helper) {
        String id = plant(helper, "rigturn_hover", "{base:'companion', powers:['friendly','flies'], "
                + "scale:0.5}");
        ServerPlayer kid = child(helper, "mfam-hover", new BlockPos(7, 1, 7), false);
        wish(helper, id);
        int[] near = {0};
        int[] measured = {0};
        float[] lastYaw = {Float.NaN};
        float[] worst = {0};
        float[] total = {0};
        float[] rawLast = {Float.NaN};
        float[] raw = {0};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                return;
            }
            near[0] = c.distanceTo(kid) < 4.0 ? near[0] + 1 : 0;
            if (near[0] < 40 && measured[0] == 0) {
                return;   // still on its way to him
            }
            float yaw = SculptAbilitiesOnRigGameTest.drawnYaw(c);
            if (!Float.isNaN(lastYaw[0]) && measured[0] < 300) {
                float d = SculptAbilitiesOnRigGameTest.apart(yaw, lastYaw[0]);
                worst[0] = Math.max(worst[0], d);
                total[0] += d;
                raw[0] += SculptAbilitiesOnRigGameTest.apart(c.yBodyRot, rawLast[0]);
                measured[0]++;
            }
            lastYaw[0] = yaw;
            rawLast[0] = c.yBodyRot;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(measured[0] >= 300, Component.literal("hovering for " + measured[0] + " ticks so far"));
            String got = String.format("over %d hovering ticks its drawn body turned %.0f degrees in all, "
                    + "%.1f at most in one tick (its carrier's own body: %.0f)", measured[0], total[0], worst[0], raw[0]);
            helper.assertTrue(worst[0] <= RigTurn.MAX + 0.5f, Component.literal(got));
            // Against its own carrier in the same run: how far it really travels differs run to run
            // (a fixed 750 degrees passed alone and failed at 780 inside the full suite).
            helper.assertTrue(total[0] <= raw[0] / 3f, Component.literal(got));
            System.out.println("RIGTURN hover: " + got);
        });
    }

    /**
     *, a second film: a child straight overhead, drifting a fraction of a block. The
     * direction to him swings right round, the carrier's head chases it and its body follows; the
     * drawn body does not. Counted only on ticks it STANDS and is looking steeply up: walking, it
     * turns to its path, and looking about on its own is its own business. {@code rigturn=off} is
     * the run-red seam.
     */
    @GameTest(template = "empty_test", batch = "rig_turn_overhead", timeoutTicks = 2400)
    public static void aChildStraightOverheadDoesNotSpinTheCreature(GameTestHelper helper) {
        String id = plant(helper, "rigturn_overhead", "{base:'companion', powers:['friendly'], scale:0.5}");
        ServerPlayer kid = child(helper, "mfam-overhead", new BlockPos(4, 2, 4), false);
        wish(helper, id);
        int[] t = {0};
        int[] still = {0};
        int[] sinceSteep = {99};
        // The drawn yaw read here was decided by the animator at the END of the last tick, on the last tick's look
        // (HeldTurnMeter): a turn after a sideways glance is not the overhead child's doing.
        HeldTurnMeter meter = new HeldTurnMeter();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                return;
            }
            t[0]++;
            // Held 5 blocks over it, going round a circle 0.4 of a block across every 2 seconds.
            double a = t[0] * Math.PI / 20.0;
            kid.teleportTo(c.getX() + 0.2 * Math.cos(a), c.getY() + 5.0, c.getZ() + 0.2 * Math.sin(a));
            var look = c.getLookControl();
            if (look.isLookingAtTarget()) {
                sinceSteep[0] = RigTurn.steep(look.getWantedX() - c.getX(), look.getWantedY() - c.getEyeY(),
                        look.getWantedZ() - c.getZ()) ? 0 : 99;
            } else {
                sinceSteep[0]++;
            }
            var v = c.getDeltaMovement();
            still[0] = c.getNavigation().isDone() && v.x * v.x + v.z * v.z < 1.0e-4 ? still[0] + 1 : 0;
            meter.tick(still[0] > 25 && sinceSteep[0] <= 2, SculptAbilitiesOnRigGameTest.drawnYaw(c), c.yBodyRot);
        });
        helper.succeedWhen(() -> {
            String got = String.format("child overhead: over %d ticks standing and looking up at him, the drawn "
                    + "body turned %.0f degrees in all, %.1f at most in one tick (its carrier's own body: %.0f)",
                    meter.counted(), meter.total(), meter.worst(), meter.raw());
            // The scene must have happened: the carrier's own body has to have been dragged round.
            helper.assertTrue(meter.counted() >= 100 && meter.raw() >= 120f, Component.literal(got));
            helper.assertTrue(meter.total() <= 20f, Component.literal(got));
            System.out.println("RIGTURN overhead: " + got);
        });
    }
}
