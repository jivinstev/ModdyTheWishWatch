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

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a species' instincts reach its atom-rig carrier.</b> Through the real bridge,
 * as {@link SpeciesOnRigGameTest} does: a coward runs from the child, a guardian goes for a monster
 * and never for him, a hunter hurts him, a pouncer leaves the ground, a creature hurt by him turns
 * on him, and none of it happens to a child in creative or on Peaceful. Each was red on
 * {@code main}: the carrier stood where it was put, doing what a cow does.
 *
 * <p>Batch {@code rig_instincts}; the Peaceful test has a batch of its own, because difficulty is
 * the whole server's and would delete another test's zombie.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigInstinctsGameTest {

    private static final String BATCH = "rig_instincts";

    private static ServerPlayer child(GameTestHelper helper, String name, double dx, GameType mode) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        var at = helper.absolutePos(SPOT);
        p.teleportTo(at.getX() + 0.5 + dx, at.getY(), at.getZ() + 0.5);
        if (mode == GameType.SURVIVAL) {
            MockPlayerNetwork.asSurvival(p);
        } else {
            p.setGameMode(mode);
        }
        return p;
    }

    // ── coward: runs when he comes close ─────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 800)
    public static void aCowardRigRunsFromTheChild(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_coward", "{base:'coward', powers:[]}");
        // The plot is a 9-block box walled with barriers, so he stands just OUTSIDE its west
        // wall, 5 blocks from the creature: the far wall is the furthest it can run. Inside the
        // box it had nowhere to go, and a first cut of this test measured that, not the coward.
        ServerPlayer kid = child(helper, "mfam-coward", -5, GameType.SURVIVAL);
        wish(helper, id);
        int[] clear = {0};
        double[] last = {-1};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null) {
                last[0] = c.distanceTo(kid);
                clear[0] = last[0] > RigInstinct.FLEE_START + 1 ? clear[0] + 1 : 0;
            }
        });
        // HELD for 100 ticks, not touched once: a carrier wandering at random reaches the far
        // wall now and then too, and does not stay there.
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            helper.assertTrue(clear[0] >= 100, Component.literal(String.format(
                    "the coward has not kept away from the child (%.1f blocks now, clear for %d "
                            + "ticks; carrier %s)", last[0], clear[0],
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(c.getType()))));
        });
    }

    // ── guardian: goes for the monster, never for him ────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aGuardianRigHuntsAMonsterAndNeverTheChild(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_guardian", "{base:'guardian', powers:[], damage:6}");
        ServerPlayer kid = child(helper, "mfam-guardian", -2, GameType.SURVIVAL);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, SPOT.offset(5, 0, 0));
        zombie.setNoAi(true);   // it must not hurt the child and muddy the second half
        // A helmet, or it burns in the plot's daylight -- which is how this test first passed on
        // main with nothing going for the zombie at all.
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        AtomicBoolean targetedKid = new AtomicBoolean();
        AtomicBoolean targetedZombie = new AtomicBoolean();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null && kid.getUUID().equals(RigInstincts.targetOf(c))) {
                targetedKid.set(true);
            }
            if (c != null && zombie.getUUID().equals(RigInstincts.targetOf(c))) {
                targetedZombie.set(true);
            }
        });
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            helper.assertTrue(!targetedKid.get(), Component.literal("the guardian took the child as its target"));
            helper.assertTrue(targetedZombie.get(), Component.literal("the guardian never went for the zombie"));
            helper.assertTrue(zombie.getLastHurtByMob() == c,
                    Component.literal("the guardian has not hurt the zombie yet"));
            helper.assertTrue(kid.getHealth() >= kid.getMaxHealth(), Component.literal("the child was hurt"));
        });
    }

    // ── hunter: hurts him ────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aHunterRigHurtsTheChild(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_hunter", "{base:'hunter', powers:[], damage:2}");
        ServerPlayer kid = child(helper, "mfam-hunter", 5, GameType.SURVIVAL);
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            helper.assertTrue(kid.getHealth() < kid.getMaxHealth(), Component.literal(String.format(
                    "the hunter has not hurt the child (target=%s, %.1f blocks)",
                    RigInstincts.targetOf(c), c.distanceTo(kid))));
            helper.assertTrue(kid.getLastHurtByMob() == c,
                    Component.literal("something hurt the child, but not the hunter's carrier"));
        });
    }

    // ──: a hit proxy is not prey ────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aHunterRigGoesForTheCowNotAHitProxy(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_proxy", "{base:'hunter', powers:[], damage:1}");
        // A hit proxy is a real, invisible, invulnerable vanilla cow standing in for part of
        // another creature. The proxy stands NEARER than the real cow, so a scan that did not
        // skip it would choose it and punch it for ever.
        var proxy = helper.spawn(EntityType.COW, SPOT.offset(2, 0, 0));
        proxy.addTag(HitProxies.PROXY_TAG);
        proxy.setInvulnerable(true);
        proxy.setNoAi(true);
        var cow = helper.spawn(EntityType.COW, SPOT.offset(4, 0, 3));
        cow.setNoAi(true);
        AtomicBoolean wentForProxy = new AtomicBoolean();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null && proxy.getUUID().equals(RigInstincts.targetOf(c))) {
                wentForProxy.set(true);
            }
        });
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            helper.assertTrue(!wentForProxy.get(), Component.literal("the hunter took a hit proxy as its prey"));
            helper.assertTrue(cow.getLastHurtByMob() == c, Component.literal("the hunter has not gone for the cow"));
        });
    }

    // ── a child in creative is never its prey ────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aHunterRigLeavesACreativeChildAlone(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_creative", "{base:'hunter', powers:[], damage:2}");
        ServerPlayer kid = child(helper, "mfam-creative", 3, GameType.CREATIVE);
        wish(helper, id);
        AtomicBoolean targeted = new AtomicBoolean();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null && kid.getUUID().equals(RigInstincts.targetOf(c))) {
                targeted.set(true);
            }
        });
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(carrier(helper, id) != null, Component.literal("no carrier"));
            helper.assertTrue(!targeted.get(), Component.literal("the hunter went for a child in creative"));
            helper.succeed();
        });
    }

    // ── leaps: it pounces ────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aLeapingRigPounces(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_leaps", "{base:'hunter', powers:['leaps'], damage:1}");
        child(helper, "mfam-leaps", 3.5, GameType.SURVIVAL);
        wish(helper, id);
        double[] floor = {Double.NaN};
        double[] rise = {0};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                return;
            }
            if (Double.isNaN(floor[0]) && c.onGround()) {
                floor[0] = c.getY();
            }
            if (!Double.isNaN(floor[0])) {
                rise[0] = Math.max(rise[0], c.getY() - floor[0]);
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(rise[0] > 0.5, Component.literal(String.format(
                "a leaping hunter never left the ground (highest %.2f blocks)", rise[0]))));
    }

    // ── hurt by him, it turns on him (the iron golem's contract) ─────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aGuardianRigHitByTheChildTurnsOnHim(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_hitback", "{base:'guardian', powers:[], damage:1}");
        ServerPlayer kid = child(helper, "mfam-hitback", 3, GameType.SURVIVAL);
        wish(helper, id);
        AtomicBoolean hit = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (!hit.getAndSet(true)) {
                Damage.hurt(c, helper.getLevel().damageSources().playerAttack(kid), 1f);
            }
            helper.assertTrue(kid.getUUID().equals(RigInstincts.targetOf(c)),
                    Component.literal("the guardian was hit by the child and did not turn on him"));
        });
    }

    // ── the other two birth points ───────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 800)
    public static void aReWishFromCowardToHunterReachesTheNewCarrier(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_rewish", "{base:'coward', powers:[]}");
        ServerPlayer kid = child(helper, "mfam-rewish", 4, GameType.SURVIVAL);
        wish(helper, id);
        AtomicReference<java.util.UUID> first = new AtomicReference<>();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (first.get() == null) {
                first.set(c.getUUID());
                plant(helper, id, "{base:'hunter', powers:[], damage:1}");
            }
            // The re-wish has reached the creature: rebuilt on a new carrier, or reshaped on this
            // one, its rig is now built from the hunter.
            var built = VanillaRigAnimator.builtFrom(c.getUUID());
            helper.assertTrue(built != null && built.base() == com.moddingfromamod.species.Species.Base.HUNTER,
                    Component.literal("the re-wish has not reached the creature yet"));
            helper.assertTrue(kid.getUUID().equals(RigInstincts.targetOf(c)),
                    Component.literal("the re-wished hunter does not hunt: the old coward's instincts stayed"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void anAdoptedHunterStillHunts(GameTestHelper helper) {
        String id = plant(helper, "t6c4b_adopt", "{base:'hunter', powers:[], damage:1}");
        wish(helper, id);
        AtomicReference<ServerPlayer> kid = new AtomicReference<>();
        helper.runAfterDelay(40, () -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                helper.fail(Component.literal("no carrier 40 ticks after the wish"));
                return;
            }
            VanillaRigAnimator.forget(c.getUUID());
            helper.assertTrue(RigInstincts.targetOf(c) == null, Component.literal("forgetting kept the old target"));
            VanillaRigAnimator.adoptAll(helper.getLevel());
            kid.set(child(helper, "mfam-adopt", 4, GameType.SURVIVAL));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(kid.get() != null, Component.literal("not adopted yet"));
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null && VanillaRigAnimator.isDriving(c), Component.literal("not driven after adoption"));
            helper.assertTrue(kid.get().getUUID().equals(RigInstincts.targetOf(c)),
                    Component.literal("the adopted hunter does not hunt"));
        });
    }

    // ── Peaceful: a player is never its prey (its own batch: difficulty is the server's) ────

    @GameTest(template = "empty_test", batch = "rig_instincts_peaceful", timeoutTicks = 400)
    public static void onPeacefulAHunterRigLeavesTheChildAlone(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Difficulty was = helper.getLevel().getDifficulty();
        String id = plant(helper, "t6c4b_peaceful", "{base:'hunter', powers:[], damage:2}");
        server.setDifficulty(Difficulty.PEACEFUL, true);
        ServerPlayer kid = child(helper, "mfam-peaceful", 3, GameType.SURVIVAL);
        wish(helper, id);
        AtomicBoolean targeted = new AtomicBoolean();
        AtomicBoolean seen = new AtomicBoolean();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null) {
                seen.set(true);
                if (kid.getUUID().equals(RigInstincts.targetOf(c))) {
                    targeted.set(true);
                }
            }
        });
        helper.runAfterDelay(150, () -> {
            // Put the difficulty back BEFORE asserting, so a failure here cannot leave every later
            // batch on Peaceful.
            server.setDifficulty(was, true);
            helper.assertTrue(seen.get(), Component.literal("no carrier"));
            helper.assertTrue(!targeted.get(), Component.literal("on Peaceful the hunter still went for the child"));
            helper.succeed();
        });
    }
}
