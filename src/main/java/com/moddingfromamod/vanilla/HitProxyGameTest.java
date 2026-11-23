package com.moddingfromamod.vanilla;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> in the world: a shot at a creature's head lands on the creature.</b>
 *
 * <p>Every test aims at a point on the DRAWN whale that its carrier's box does not reach — its
 * head, a long way from the carrier standing on its middle — and every test that could pass
 * without the proxies carries its own control: the same whale with its proxies taken away, the
 * same shot, which must MISS. A control that also hit would mean the shot was landing on the
 * carrier anyway and the test proved nothing about proxies.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HitProxyGameTest {

    /**
     * The middle of the 9x9 template, on its floor. ⚠ A rig must stay INSIDE its own test's area:
     * a GameTest that SUCCEEDS discards every entity within its bounds + 1
     * ({@code GameTestInfo.succeed}), so a whale reaching into a neighbour's area loses its carrier
     * the moment the neighbour passes — measured, an arrow that then flew through a proxy with no
     * carrier behind it, one run in three.
     */
    private static final BlockPos SPOT = new BlockPos(4, 0, 4);

    /**
     * The first air above the template's floor at {@link #SPOT}. NOT a fixed height: the
     * floor sits at relative y 0 on 1.21.4 and y 1 on 1.21.1, so a hard-coded {@code y = 1} stood
     * the 1.21.1 whale INSIDE the floor, where it suffocated — and a creature just hurt by a
     * wall shrugs off the next hit for a moment, so the arrow the test fired bounced off it.
     * (raised it to a fixed height 2, which clears 1.21.1's floor but spawns the 1.21.4
     * whale a block up, falling out from under the aim computed at spawn; the floor is found.)
     */
    private static Vec3 floor(GameTestHelper helper) {
        BlockPos p = helper.absolutePos(SPOT);
        for (int i = 0; i < 4 && !helper.getLevel().getBlockState(p).isAir(); i++) {
            p = p.above();
        }
        return Vec3.atBottomCenterOf(p);
    }

    /** 5.75 blocks nose to fluke: inside the template, and still far longer than any one box. */
    private static final double SCALE = 1.0;

    /** The whale: long enough that its head is well outside any one box. */
    private static List<com.moddingfromamod.runtime.CreatureSculpt.Part> whale() {
        return RigFitGameTest.whale();
    }

    private static DisplayRig spawn(GameTestHelper helper, Vec3 at, String key) {
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), at, 0f, whale(), SCALE, key,
                CarrierPool.Move.WALK);
        rig.base().setNoAi(true);   // a wandering carrier would walk its head out of the aim
        return rig;
    }

    /**
     * The point to aim at: the middle of the drawn box farthest from the carrier's axis, in the
     * world. Asserted to be outside the carrier's box grown by an arrow's margin, or the test
     * would measure the carrier.
     */
    private static Vec3 head(GameTestHelper helper, DisplayRig rig) {
        RigFit.Atom far = null;
        for (RigFit.Atom a : RigFit.atoms(rig.groups(), rig.scale())) {
            if (far == null || Math.hypot(a.t().x, a.t().z) > Math.hypot(far.t().x, far.t().z)) {
                far = a;
            }
        }
        Vec3 p = HitProxies.toWorld(far.t().x, far.t().y, far.t().z, rig.base().position(),
                rig.base().yBodyRot);
        helper.assertFalse(rig.base().getBoundingBox().inflate(HitProxyPlan.ARROW_MARGIN)
                .contains(p), Component.literal("the aim point " + p + " is inside the carrier's own box "
                + rig.base().getBoundingBox() + " -- the test would not be testing proxies"));
        return p;
    }

    private static Arrow arrowAt(ServerLevel level, Vec3 target, Vec3 from, Mob owner) {
        Arrow arrow = EntityType.ARROW.create(level, EntitySpawnReason.COMMAND);
        arrow.setPos(from.x, from.y, from.z);
        arrow.setNoGravity(true);   // straight at the point, so a miss is the box and not the aim
        if (owner != null) {
            arrow.setOwner(owner);
        }
        Vec3 d = target.subtract(from);
        arrow.shoot(d.x, d.y, d.z, 2.0f, 0f);
        level.addFreshEntity(arrow);
        return arrow;
    }

    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void anArrowAtTheWhalesHeadHurtsTheWhale(GameTestHelper helper) {
        // side by side in the one template: each shot goes OUTWARD, away from the other whale
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a.add(-2, 0, 0), "gt_px_arrow");
        DisplayRig control = spawn(helper, a.add(2, 0, 0), "gt_px_arrow_ctl");
        control.discardProxies();
        helper.assertTrue(!rig.proxies().isEmpty(), Component.literal("the whale was given no proxies: "
                + rig.hitPlan()));
        Vec3 aim = head(helper, rig);
        Vec3 aimCtl = head(helper, control);
        float hp = rig.base().getHealth();
        float hpCtl = control.base().getHealth();
        // Across the head from just beside the proxy that covers it, so the carrier's box is
        // nowhere on the line and the shot is short enough that nothing else is either.
        Mob over = null;
        for (Mob m : rig.proxies()) {
            if (m.getBoundingBox().inflate(1e-3).contains(aim)) {
                over = m;
            }
        }
        helper.assertTrue(over != null, Component.literal("no proxy covers the whale's head at " + aim + ": "
                + rig.proxies().stream().map(Mob::getBoundingBox).toList()));
        double run = over.getBoundingBox().maxX - aim.x + HitProxyPlan.ARROW_MARGIN + 0.5;
        // the whale on the -x side is shot flying -x, the control on the +x side flying +x
        Arrow shot = arrowAt(helper.getLevel(), aim, aim.add(run, 0, 0), null);
        Arrow shotCtl = arrowAt(helper.getLevel(), aimCtl, aimCtl.add(-run, 0, 0), null);
        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(byArrow(rig.base()), Component.literal("an arrow through the whale's head did not "
                        + "hurt the whale (" + hp + " -> " + rig.base().getHealth() + ", last hurt by "
                        + what(rig.base()) + "; the arrow " + (shot.isRemoved() ? "is gone"
                        : "is at " + shot.position())
                        + ")"));
                helper.assertFalse(byArrow(control.base()), Component.literal("CONTROL: without proxies the same "
                        + "arrow hurt the whale too (" + hpCtl + " -> " + control.base().getHealth()
                        + "), so the head was inside the carrier's box and this test proves "
                        + "nothing about proxies"));
                for (Mob m : rig.proxies()) {
                    helper.assertTrue(m.getHealth() == m.getMaxHealth() && m.isAlive(),
                            Component.literal("a proxy took the arrow itself: " + m));
                }
                helper.succeed();
            } finally {
                rig.discard();
                control.discard();
                shot.discard();
                shotCtl.discard();
            }
        });
    }

    /** Was this mob's last damage an arrow? Health alone would count a wall or a fall. */
    private static boolean byArrow(Mob m) {
        var src = m.getLastDamageSource();
        return src != null && src.is(net.minecraft.world.damagesource.DamageTypes.ARROW);
    }

    private static String what(Mob m) {
        var src = m.getLastDamageSource();
        return src == null ? "nothing" : src.getMsgId();
    }

    @GameTest(template = "empty_test")
    public static void aPunchOnAProxyIsAPunchOnTheWhale(GameTestHelper helper) {
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a, "gt_px_punch");
        try {
            helper.assertTrue(!rig.proxies().isEmpty(), Component.literal("no proxies: " + rig.hitPlan()));
            Player child = helper.makeMockPlayer(GameType.SURVIVAL);
            Mob proxy = rig.proxies().get(0);
            float hp = rig.base().getHealth();
            child.attack(proxy);
            helper.assertTrue(rig.base().getHealth() < hp
                            && rig.base().getLastDamageSource() != null
                            && rig.base().getLastDamageSource().getEntity() == child,
                    Component.literal("punching a proxy did not hurt the whale (" + hp + " -> "
                            + rig.base().getHealth() + ", last hurt by " + what(rig.base()) + ")"));
            helper.assertTrue(proxy.getHealth() == proxy.getMaxHealth(), Component.literal("the proxy was hurt"));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    @GameTest(template = "empty_test")
    public static void aNameTagOnAProxyNamesTheWhale(GameTestHelper helper) {
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a, "gt_px_tag");
        try {
            helper.assertTrue(!rig.proxies().isEmpty(), Component.literal("no proxies: " + rig.hitPlan()));
            Player child = helper.makeMockPlayer(GameType.SURVIVAL);
            ItemStack tag = new ItemStack(Items.NAME_TAG);
            tag.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal("Wally"));
            child.setItemInHand(InteractionHand.MAIN_HAND, tag);
            Mob proxy = rig.proxies().get(0);
            child.interactOn(proxy, InteractionHand.MAIN_HAND);
            helper.assertTrue(rig.base().hasCustomName()
                            && "Wally".equals(rig.base().getCustomName().getString()),
                    Component.literal("a name tag used on a proxy did not name the whale: " + rig.base().getCustomName()));
            helper.assertFalse(proxy.hasCustomName(), Component.literal("the proxy took the name itself"));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * The creature's OWN projectile, born inside its own head, must fly out through the proxy there
     * — a fire breath that struck the dragon breathing it is the bug a naive redirect would ship.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void theWhalesOwnArrowPassesThroughItsOwnHead(GameTestHelper helper) {
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a, "gt_px_own");
        Vec3 aim = head(helper, rig);
        float hp = rig.base().getHealth();
        // fired FROM the head outwards, owned by the whale
        Arrow arrow = arrowAt(helper.getLevel(), aim.add(6, 0, 0), aim, rig.base());
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(rig.base().getHealth() == hp, Component.literal("the whale's own arrow hurt it"));
                helper.assertTrue(arrow.position().x > aim.x + 2 || arrow.isRemoved(),
                        Component.literal("the whale's own arrow stuck in its own head at " + arrow.position()));
                helper.succeed();
            } finally {
                rig.discard();
                arrow.discard();
            }
        });
    }

    /**
     * A proxy is a hitbox and nothing else: after a second of the whale being driven, every proxy
     * is still where the plan puts it (nothing pushed it), unhurt, invisible, silent and inert.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void proxiesStayOverTheirPartsAndTouchNothing(GameTestHelper helper) {
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a, "gt_px_inert");
        VanillaRigAnimator.drive(rig, RigFit.intoRigSpace(whale()), java.util.Map.of());
        Vec3 feet = rig.base().position();
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(rig.base().position().distanceTo(feet) < 1e-3,
                        Component.literal("the carrier was pushed " + rig.base().position().distanceTo(feet)
                                + " blocks by its own proxies"));
                var plan = rig.hitPlan().proxies();
                for (int i = 0; i < plan.size(); i++) {
                    Mob m = rig.proxies().get(i);
                    Vec3 want = HitProxies.at(plan.get(i), rig.base().position(),
                            rig.base().yBodyRot);
                    helper.assertTrue(m.position().distanceTo(want) < 1e-3, Component.literal("proxy " + i + " is "
                            + m.position().distanceTo(want) + " blocks from its part"));
                    helper.assertTrue(m.isInvisible() && m.isSilent() && m.isNoAi()
                            && m.isInvulnerable() && m.noPhysics && !m.isCurrentlyGlowing()
                            && m.isPersistenceRequired(), Component.literal("proxy " + i + " is not inert: " + m));
                    helper.assertTrue(Math.abs(m.getBbWidth() - plan.get(i).width()) < 0.02,
                            Component.literal("proxy " + i + " is " + m.getBbWidth() + " wide, planned "
                                    + plan.get(i).width()));
                }
                helper.succeed();
            } finally {
                VanillaRigAnimator.forget(rig.base().getUUID());
                rig.discard();
            }
        });
    }

    /** A dead creature leaves no hitbox behind: the reaper takes its proxies with its displays. */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void killingTheWhaleTakesItsProxies(GameTestHelper helper) {
        Vec3 a = floor(helper);
        DisplayRig rig = spawn(helper, a, "gt_px_reap");
        List<Mob> proxies = rig.proxies();
        helper.assertTrue(!proxies.isEmpty(), Component.literal("no proxies: " + rig.hitPlan()));
        rig.base().kill(helper.getLevel());
        // the carrier leaves the world after its death animation (LivingEntity.tickDeath, 20
        // ticks); the reaper runs on that leave, so ask once the carrier is gone
        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(rig.base().isRemoved(), Component.literal("the whale never left the world"));
                for (Mob m : proxies) {
                    helper.assertTrue(m.isRemoved(), Component.literal("a proxy outlived its whale: " + m));
                }
                helper.succeed();
            } finally {
                rig.discard();
            }
        });
    }

    /** A compact creature is its own box, so it is given nothing (the planner's k = 0). */
    @GameTest(template = "empty_test")
    public static void aCubeIsGivenNoProxies(GameTestHelper helper) {
        Vec3 a = floor(helper);
        var cube = List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part("body", null,
                new float[]{0, 0, 0}, new float[]{-8, 0, -8}, new float[]{8, 14, 8}, 0xFFAA00));
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), a, 0f, cube, 1.0, "gt_px_cube",
                CarrierPool.Move.WALK);
        try {
            helper.assertTrue(rig.proxies().isEmpty(), Component.literal("a cube was given proxies: " + rig.hitPlan()));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }
}
