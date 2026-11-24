package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Damage;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> chunk 1: does a sculpted creature's {@code abilities} move actually happen?</b>
 *
 * <p>{@code sculpt_creature abilities:[…]} is offered to the agent and recommended by the ready-made
 * index, and {@code runtime.Abilities} decides when a move fires — but until {@link RigAbilities}
 * its only caller was {@code WishMobEntity.tickAbilities}, and the creature that stands in
 * the world is the rig's vanilla carrier: every move was dead. The first test is the parent
 * session's, RED on main at 8b4298791 (a slam creature targeting a husk 2.1 blocks away never hurt
 * it in 200 ticks); the rest are one per move, plus the two lines a move must never cross on the
 * way: its own rider, and a player when the creature is friendly.
 *
 * <p>The oracle is the world, never the move's own report: the victim's health, a snowball in the
 * air, a helper standing there, where the creature ended up, its health going back up. Each test is
 * its own batch, so one creature's husk is never another's target.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SculptAbilitiesOnRigGameTest {

    private static final String FRIENDLY = "\"powers\":[\"friendly\",\"persistent\"]";
    /**
     * The far corner of the 9-block plot, 8.5 blocks from {@link SpeciesOnRigGameTest#SPOT}: inside
     * the ranged, charge and teleport bands, and INSIDE the plot — a GameTest plot is walled with
     * barrier, and a monster put 10 blocks off stood behind it, out of the creature's sight, which
     * is exactly what the moves must respect.
     */
    private static final BlockPos FAR = SpeciesOnRigGameTest.SPOT.offset(6, 0, 6);

    static String creature(GameTestHelper helper, String id, String abilities, String extra) {
        String made = SpeciesOnRigGameTest.plant(helper, id,
                "{\"abilities\":[" + abilities + "],\"damage\":6," + extra + "}");
        SpeciesOnRigGameTest.wish(helper, made);
        return made;
    }

    private static Husk husk(GameTestHelper helper, BlockPos at) {
        Husk husk = helper.spawn(EntityTypes.HUSK, at);
        husk.setPersistenceRequired();
        husk.setNoAi(true);   // it stands still and never fights back: what moves or hurts it is the move
        return husk;
    }

    /**
     * Keep the creature on its spot until {@code done} says the move happened. A friendly creature
     * wanders, and in the full Gate B one wandered to 7.2 blocks from the husk — inside a blink's
     * 8-block minimum — and never blinked; alone it passed. The move is the subject, not the stroll.
     */
    private static void holdUntil(GameTestHelper helper, String id, Runnable observe,
            java.util.function.BooleanSupplier done) {
        // ONE callback, observe first: the test's check must see an arrival before the hold can pull
        // the creature back. Two onEachTick registrations do NOT run in registration order (the
        // helper keys them in a hash map), so a separate hold undid a real blink 1 run in 5.
        BlockPos at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        helper.onEachTick(() -> {
            observe.run();
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            // Only a STROLL is undone: a charge is the move under test, and it moves fast.
            if (c != null && !done.getAsBoolean() && c.getDeltaMovement().horizontalDistance() < 0.3
                    && c.onGround()   // never mid-hop: a slam is a stomp, and its jump is the move
                    && Math.hypot(c.getX() - at.getX() - 0.5, c.getZ() - at.getZ() - 0.5) > 1.0) {
                c.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            }
        });
    }

    /** After {@code ticks}, the carrier must exist; then the check. */
    private static void after(GameTestHelper helper, String id, int ticks, Consumer<Mob> check) {
        helper.runAfterDelay(ticks, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                helper.fail(Component.literal("no carrier for " + id));
                return;
            }
            check.accept(c);
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_slam", timeoutTicks = 400)
    public static void aSlamCreatureHurtsWhatItTargetsOnTheRig(GameTestHelper helper) {
        String id = creature(helper, "ability_slam_rig", "\"slam\"", FRIENDLY);
        Husk husk = husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        float full = husk.getHealth();
        // The oracle can see harm at all: one point of test damage must register, and is healed back.
        husk.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1.0F);
        if (husk.getHealth() >= full) {
            helper.fail(Component.literal("the oracle cannot see damage: the husk's health did not move"));
        }
        husk.setHealth(full);
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null && c.getTarget() != husk) {
                c.setTarget(husk);
            }
        }, () -> husk.getHealth() < full);
        // SPAWN_GRACE_TICKS (40) + one SLAM cooldown (80) + slack.
        after(helper, id, 200, c -> {
            if (husk.getHealth() >= full) {
                helper.fail(Component.literal("a creature sculpted with abilities:[slam], targeting a husk "
                        + String.format("%.1f", c.distanceTo(husk)) + " blocks away for 200 ticks, "
                        + "never hurt it (husk " + husk.getHealth() + "/" + full + "; carrier "
                        + c.getType().builtInRegistryHolder().key().identifier() + ")"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_slam_stomp", timeoutTicks = 400)
    public static void aSlamGoesUpAndTheBlowLandsWhenItComesDown(GameTestHelper helper) {
        // The owner's film (2026-10-28): monsters round a creature that stood still were knocked
        // over, with nothing to show why. A slam is a stomp — it leaves the ground, and the hit
        // lands when it is back on it.
        String id = creature(helper, "ability_slam_stomp", "\"slam\"", FRIENDLY);
        Husk husk = husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        float full = husk.getHealth();
        double[] ground = {Double.NaN};
        double[] rose = {0};
        double[] roseAtHit = {-1};
        boolean[] downAtHit = {false};
        int[] lastDown = {-100};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null || roseAtHit[0] >= 0) {
                return;
            }
            if (Double.isNaN(ground[0]) && c.onGround()) {
                ground[0] = c.getY();
            }
            if (!Double.isNaN(ground[0])) {
                rose[0] = Math.max(rose[0], c.getY() - ground[0]);
            }
            if (c.onGround()) {
                lastDown[0] = c.tickCount;
            }
            if (husk.getHealth() < full) {
                roseAtHit[0] = rose[0];
                // Read the tick AFTER the blow, by which time it may be moving again: on the ground
                // at the blow means on it within a tick of it, which a mid-air blow never is.
                downAtHit[0] = c.tickCount - lastDown[0] <= 1;
            }
        }, () -> husk.getHealth() < full);
        after(helper, id, 220, c -> {
            if (roseAtHit[0] < 0) {
                helper.fail(Component.literal("the slam never hurt the husk 2 blocks away"));
            }
            if (roseAtHit[0] < 0.5) {
                helper.fail(Component.literal("the husk was hurt but the creature only rose "
                        + String.format("%.2f", roseAtHit[0]) + " blocks first: a slam with no stomp"));
            }
            if (!downAtHit[0]) {
                helper.fail(Component.literal("the blow landed while the creature was still in the air"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_slam_unaimed", timeoutTicks = 400)
    public static void aFriendlySlamCreatureFindsTheMonsterByItselfAndNeverHitsAPlayer(GameTestHelper helper) {
        String id = creature(helper, "ability_slam_find", "\"slam\"", FRIENDLY);
        Husk husk = husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-slam-kid"));
        BlockPos k = helper.absolutePos(SpeciesOnRigGameTest.SPOT.west(2));
        kid.teleportTo(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        float full = husk.getHealth();
        float kidFull = kid.getHealth();
        // No target set by hand: "a friendly creature that stomps the monsters" has no hunting
        // instinct, so the move must find the monster itself — and the child beside it is not one.
        after(helper, id, 200, c -> {
            if (husk.getHealth() >= full) {
                helper.fail(Component.literal("a friendly slam creature with no hunting instinct never found the husk "
                        + "2 blocks away (husk " + husk.getHealth() + "/" + full + ")"));
            }
            if (kid.getHealth() < kidFull) {
                helper.fail(Component.literal("a FRIENDLY creature's slam hurt the child beside it (" + kid.getHealth()
                        + "/" + kidFull + ")"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_slam_rider", timeoutTicks = 600)
    public static void aSlamNeverLandsOnItsOwnRider(GameTestHelper helper) {
        String id = creature(helper, "ability_slam_rider", "\"slam\"",
                "\"powers\":[\"rideable\",\"persistent\"]");
        Husk husk = husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-slam-rider"));
        float full = husk.getHealth();
        float kidFull = kid.getMaxHealth();
        boolean[] on = {false};
        int[] hitAt = {-1};
        // it waits for the SLAM, not for a tick count. The fixed 220-tick verdict failed once in a
        // release's full run ("never hurt the husk") and passed 3/3 alone: a ridden creature has no hold,
        // so it could stand out of the slam's reach. The husk now keeps 2 blocks off the creature until it
        // is hit, and the verdict is the tick after the first blow (a blow on the rider lands in the same
        // slamLands loop as the one on the husk, so it is visible by then) or a fail at the timeout.
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                return;
            }
            if (!on[0] && c.tickCount > 5) {
                kid.teleportTo(c.getX() + 1, c.getY(), c.getZ());
                kid.getInventory().clearContent();
                com.moddingfromamod.mv.Interact.on(kid, c, InteractionHand.MAIN_HAND);
                on[0] = com.moddingfromamod.mob.RideSeat.mountOf(kid) == c;
            }
            if (kid.getHealth() < kidFull) {
                helper.fail(Component.literal("a slam hurt the creature's own RIDER (" + kid.getHealth() + ", "
                        + (kid.getLastDamageSource() == null ? "?" : kid.getLastDamageSource().getMsgId()) + ")"));
            }
            if (hitAt[0] < 0 && husk.getHealth() < full) {
                hitAt[0] = c.tickCount;
                if (!on[0]) {
                    helper.fail(Component.literal("the slam landed before the child got on"));
                }
            } else if (hitAt[0] >= 0 && c.tickCount > hitAt[0]) {
                helper.succeed();
            } else if (on[0] && hitAt[0] < 0 && husk.distanceTo(c) > 2.5) {
                husk.teleportTo(c.getX() + 2, c.getY(), c.getZ());   // in reach, whatever the ridden creature did
            }
        });
        helper.runAfterDelay(590, () -> helper.fail(Component.literal(!on[0]
                ? "the child never got on the rideable slam creature"
                : "the ridden slam creature never hurt the husk beside it in 590 ticks")));
    }

    @GameTest(template = "empty_test", batch = "ability_rig_ranged", timeoutTicks = 400)
    public static void aRangedCreatureShootsAtADistantMonster(GameTestHelper helper) {
        String id = creature(helper, "ability_ranged_rig", "\"ranged\"", FRIENDLY);
        husk(helper, FAR);
        int[] shots = {0};
        holdUntil(helper, id, () -> shots[0] = Math.max(shots[0], helper.getLevel().getEntitiesOfClass(Snowball.class,
                new AABB(helper.absolutePos(SpeciesOnRigGameTest.SPOT)).inflate(24)).size()), () -> shots[0] > 0);
        after(helper, id, 200, c -> {
            if (shots[0] == 0) {
                helper.fail(Component.literal("a ranged creature with a husk 8.5 blocks off never shot"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_charge", timeoutTicks = 400)
    public static void aChargingCreatureRushesAtAndHitsADistantMonster(GameTestHelper helper) {
        String id = creature(helper, "ability_charge_rig", "\"charge\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        float full = husk.getHealth();
        double[] closest = {Double.MAX_VALUE};
        boolean[] charged = {false};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null) {
                closest[0] = Math.min(closest[0], c.distanceTo(husk));
                charged[0] |= c.getDeltaMovement().horizontalDistance() >= 0.5;   // the rush began
            }
        }, () -> closest[0] <= 3.0 || husk.getHealth() < full || charged[0]);
        after(helper, id, 260, c -> {
            if (closest[0] > 3.0 && husk.getHealth() >= full) {
                helper.fail(Component.literal("a charging creature never closed on the husk 8.5 blocks off (closest "
                        + String.format("%.1f", closest[0]) + ") nor hurt it"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_teleport", timeoutTicks = 400)
    public static void aTeleportingCreatureBlinksToADistantMonster(GameTestHelper helper) {
        String id = creature(helper, "ability_teleport_rig", "\"teleport\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        boolean[] blinked = {false};
        // The blink itself, not where it ended up: 4+ blocks in one tick (a stroll, a charge or the
        // hold's pull-back never moves it that far). "Within 3 blocks of the husk" missed a real
        // blink 1 run in 2, once something nudged it off the spot before the check.
        net.minecraft.world.phys.Vec3[] last = {null};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null) {
                if (last[0] != null && c.position().distanceTo(last[0]) >= 4.0) {
                    blinked[0] = true;
                }
                last[0] = c.position();
            }
        }, () -> blinked[0]);
        after(helper, id, 260, c -> {
            if (!blinked[0]) {
                helper.fail(Component.literal("a teleporting creature never blinked to the husk 8.5 blocks off (now "
                        + String.format("%.1f", c.distanceTo(husk)) + " away)"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_teleport_crowded", timeoutTicks = 400)
    public static void aTeleporterWithAMonsterAtItsFeetStillBlinksToTheFarOne(GameTestHelper helper) {
        // The re-measure's miss: monsters at its feet, so the NEAREST was too close to blink to
        // and it never blinked (0 of 2). The far one is in sight and in the band.
        String id = creature(helper, "ability_teleport_crowd", "\"teleport\"", FRIENDLY);
        husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        Husk far = husk(helper, FAR);
        boolean[] blinked = {false};
        // The blink itself, not where it ended up: 4+ blocks in one tick (a stroll, a charge or the
        // hold's pull-back never moves it that far). "Within 3 blocks of the husk" missed a real
        // blink 1 run in 2, once something nudged it off the spot before the check.
        net.minecraft.world.phys.Vec3[] last = {null};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null) {
                if (last[0] != null && c.position().distanceTo(last[0]) >= 4.0) {
                    blinked[0] = true;
                }
                last[0] = c.position();
            }
        }, () -> blinked[0]);
        after(helper, id, 260, c -> {
            if (!blinked[0]) {
                helper.fail(Component.literal("a teleporter with a husk at its feet never blinked to the one 8.5 blocks off"));
            }
        });
    }

    /** The yaw, in Minecraft's convention, that looks from {@code from} along {@code (dx, dz)}. */
    static float yawAlong(double dx, double dz) {
        return (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
    }

    /** How far apart two yaws are, 0..180. */
    static float apart(float a, float b) {
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(a - b));
    }

    /** The body yaw the rig DREW — read off a display, which is what the child sees. */
    static float drawnYaw(Mob c) {
        DisplayRig rig = VanillaRigAnimator.rigOf(c);
        return rig == null || rig.displays().isEmpty() ? c.yBodyRot
                : rig.displays().get(0).getYRot() - DisplayRig.displayYaw(0f);
    }

    /** Turned to face AWAY from {@code at}, so a body that never turns is caught. */
    private static void faceAway(Mob c, net.minecraft.world.entity.Entity at) {
        float away = yawAlong(c.getX() - at.getX(), c.getZ() - at.getZ());
        c.setYRot(away);
        c.setYBodyRot(away);
        c.setYHeadRot(away);
    }

    @GameTest(template = "empty_test", batch = "ability_rig_ranged_hurts", timeoutTicks = 400)
    public static void aRangedCreaturesThrowHurtsTheMonsterItHits(GameTestHelper helper) {
        // The owner (2026-10-28): "let's make sure we handle throw damage explicitly". A vanilla
        // snowball hurts only blazes, so the move knocked monsters back and did nothing else.
        String id = creature(helper, "ability_ranged_hurts", "\"ranged\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        float full = husk.getHealth();
        holdUntil(helper, id, () -> {
        }, () -> husk.getHealth() < full);
        after(helper, id, 300, c -> {
            if (husk.getHealth() >= full) {
                helper.fail(Component.literal("a ranged creature's throws never hurt the husk 8.5 blocks off (husk "
                        + husk.getHealth() + "/" + full + ")"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_ranged_hurts", timeoutTicks = 400)
    public static void aCreatureThatThrowsFireballsThrowsFireChargesThatBurn(GameTestHelper helper) {
        // "a creature that throws fireballs at monsters". The move threw a snowball whatever
        // the wish said; `throws` draws the ball as the item named, and a fire charge burns.
        String id = creature(helper, "ability_ranged_fire", "\"ranged\"",
                FRIENDLY + ",\"throws\":\"minecraft:fire_charge\"");
        Husk husk = husk(helper, FAR);
        boolean[] fireball = {false};
        boolean[] burned = {false};
        holdUntil(helper, id, () -> {
            for (Snowball b : helper.getLevel().getEntitiesOfClass(Snowball.class,
                    new AABB(helper.absolutePos(SpeciesOnRigGameTest.SPOT)).inflate(24))) {
                fireball[0] |= b.getItem().is(net.minecraft.world.item.Items.FIRE_CHARGE);
            }
            burned[0] |= husk.getRemainingFireTicks() > 0;
        }, () -> fireball[0] && burned[0]);
        after(helper, id, 300, c -> {
            if (!fireball[0]) {
                helper.fail(Component.literal("it was told to throw fire charges and threw something else"));
            } else if (!burned[0]) {
                helper.fail(Component.literal("its fireballs never set the husk 8.5 blocks off alight"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_ranged_spares", timeoutTicks = 200)
    public static void aFriendlyCreaturesThrowPassesThroughTheChild(GameTestHelper helper) {
        // Who a throw may hit is who the creature may hit: never the child from a friendly one. A
        // ball thrown point blank at him neither hurts him nor stops on him — it flies on.
        String id = creature(helper, "ability_ranged_spares", "\"ranged\"", FRIENDLY);
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-throw-kid"));
        float kidFull = kid.getHealth();
        Snowball[] ball = {null};
        double[] farthest = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            if (ball[0] != null && !ball[0].isRemoved()) {
                farthest[0] = Math.max(farthest[0], ball[0].getX());
            }
        });
        helper.runAfterDelay(20, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                helper.fail(Component.literal("no carrier"));
                return;
            }
            c.setNoAi(true);   // it stands still; the throw is ours, as its own would be
            kid.teleportTo(c.getX() + 3, c.getY(), c.getZ());
            ball[0] = RigAbilities.throwBall(helper.getLevel(), c);
            ball[0].setPos(c.getX() + 1.6, kid.getY() + 1.0, c.getZ());
            ball[0].shoot(1, 0, 0, 1.0F, 0F);
            helper.getLevel().addFreshEntity(ball[0]);
        });
        helper.runAfterDelay(40, () -> {
            if (kid.getHealth() < kidFull) {
                helper.fail(Component.literal("a FRIENDLY creature's throw hurt the child (" + kid.getHealth() + "/" + kidFull + ")"));
            } else if (ball[0] == null || farthest[0] < kid.getX() + 0.5) {
                helper.fail(Component.literal("the ball stopped on the child instead of flying on (got to "
                        + String.format("%.1f", farthest[0] - kid.getX()) + " blocks past him)"));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_ranged_faces", timeoutTicks = 400)
    public static void aRangedCreatureTurnsToFaceWhatItShoots(GameTestHelper helper) {
        // The owner's film (2026-10-28): snowballs flew off one way while the creature looked
        // another, so it read as throwing at nothing. The body — which is what the rig draws —
        // must face the monster when the ball leaves, and the ball must head for the monster.
        String id = creature(helper, "ability_ranged_face", "\"ranged\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        float[] worst = {-1f, -1f};   // body vs husk, ball vs husk, at each shot
        boolean[] turned = {false};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                return;
            }
            if (!turned[0] && c.tickCount > 2) {
                faceAway(c, husk);
                turned[0] = true;
            }
            for (Snowball b : helper.getLevel().getEntitiesOfClass(Snowball.class, c.getBoundingBox().inflate(4))) {
                if (seen.add(b.getUUID())) {
                    float toHusk = yawAlong(husk.getX() - c.getX(), husk.getZ() - c.getZ());
                    worst[0] = Math.max(worst[0], apart(drawnYaw(c), toHusk));
                    var v = b.getDeltaMovement();
                    worst[1] = Math.max(worst[1], apart(yawAlong(v.x, v.z), toHusk));
                }
            }
        }, () -> seen.size() >= 2);
        after(helper, id, 260, c -> {
            if (seen.isEmpty()) {
                helper.fail(Component.literal("a ranged creature with a husk 8.5 blocks off never shot"));
            }
            if (worst[0] > 30f) {
                helper.fail(Component.literal("it shot with its body " + String.format("%.0f", worst[0])
                        + " degrees off the husk it was shooting at: it threw over its shoulder"));
            }
            if (worst[1] > 20f) {
                helper.fail(Component.literal("a snowball left " + String.format("%.0f", worst[1]) + " degrees off the husk"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_charge_faces", timeoutTicks = 400)
    public static void aChargingCreatureRunsHeadFirst(GameTestHelper helper) {
        // The owner's film: it charged BACKWARDS. A charge is velocity we set, and nothing turned
        // the body, so the rig slid tail first at the monster. While it rushes it faces where it goes.
        String id = creature(helper, "ability_charge_face", "\"charge\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        float[] worst = {-1f};
        int[] rushing = {0};
        int[] run = {0};
        boolean[] turned = {false};
        holdUntil(helper, id, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                return;
            }
            if (!turned[0] && c.tickCount > 2) {
                faceAway(c, husk);
                turned[0] = true;
            }
            var v = c.getDeltaMovement();
            if (v.horizontalDistance() >= 0.5) {
                rushing[0]++;
                run[0]++;
                if (run[0] > 1) {   // the tick a rush starts, the client is tweening the last of the turn
                    worst[0] = Math.max(worst[0], apart(drawnYaw(c), yawAlong(v.x, v.z)));
                }
            } else {
                run[0] = 0;
            }
        }, () -> rushing[0] > 10);
        after(helper, id, 260, c -> {
            if (rushing[0] <= 2) {
                helper.fail(Component.literal("a charging creature never rushed at the husk 8.5 blocks off"));
            }
            if (worst[0] > 30f) {
                helper.fail(Component.literal("it charged with its body " + String.format("%.0f", worst[0])
                        + " degrees off the way it was going: it ran sideways or backwards"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_summon", timeoutTicks = 600)
    public static void aSummoningCreatureCallsInSmallHelpersThatDoNotSummon(GameTestHelper helper) {
        String id = creature(helper, "ability_summon_rig", "\"summon\"", FRIENDLY);
        husk(helper, FAR);
        // One summon: SPAWN_GRACE_TICKS (40) + well under SUMMON's 300-tick cooldown, so a third or
        // fourth helper can only be a helper summoning.
        after(helper, id, 200, c -> {
            var minions = helper.getLevel().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(24),
                    e -> e.entityTags().contains(RigAbilities.MINION_TAG) && e.isAlive());
            if (minions.isEmpty()) {
                helper.fail(Component.literal("a summoning creature called in no helpers in 200 ticks"));
            }
            if (minions.size() > 2) {
                helper.fail(Component.literal("helpers summoned helpers: " + minions.size() + " minions from one summon"));
            }
            // The RIG's scale — what the child sees. The carrier's hitbox is not: a carrier is fitted
            // to the rig where one fits and otherwise keeps its natural size, so in the full Gate B a
            // half-scale helper's llama stood exactly as tall as the summoner's llama.
            // The SUMMONER is the one without the minion tag: helpers wear its species tag too, so
            // the species lookup may hand back a helper (it did: "0.6 vs 0.6", helper against helper).
            Mob summoner = helper.getLevel().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(24),
                    e -> e.entityTags().contains(com.moddingfromamod.entity.WishMobTags.forSpecies(id))
                            && e.entityTags().contains(DisplayRig.RIG_TAG)
                            && !e.entityTags().contains(RigAbilities.MINION_TAG)).stream().findFirst().orElse(c);
            DisplayRig own = VanillaRigAnimator.rigOf(summoner);
            for (Mob m : minions) {
                DisplayRig r = VanillaRigAnimator.rigOf(m);
                if (own == null || r == null) {
                    helper.fail(Component.literal("a creature or its helper has no rig"));
                } else if (r.scale() >= own.scale()) {
                    helper.fail(Component.literal("a helper is as big as the summoner (rig scale " + r.scale() + " vs "
                            + own.scale() + ") — they are small helpers"));
                }
            }
        });
    }

    /** The summoner itself: the one wearing the species without being anybody's helper. */
    private static Mob summoner(GameTestHelper helper, String id) {
        return helper.getLevel().getEntitiesOfClass(Mob.class,
                new AABB(helper.absolutePos(SpeciesOnRigGameTest.SPOT)).inflate(32),
                e -> e.isAlive() && e.entityTags().contains(com.moddingfromamod.entity.WishMobTags.forSpecies(id))
                        && e.entityTags().contains(DisplayRig.RIG_TAG)
                        && RigAbilities.ownerOf(e) == null).stream().findFirst().orElse(null);
    }

    private static java.util.List<Mob> helpersOf(GameTestHelper helper, Mob owner) {
        return owner == null ? java.util.List.of() : helper.getLevel().getEntitiesOfClass(Mob.class,
                owner.getBoundingBox().inflate(32),
                e -> e.isAlive() && owner.getUUID().equals(RigAbilities.ownerOf(e)));
    }

    @GameTest(template = "empty_test", batch = "ability_rig_summon_defend", timeoutTicks = 600)
    public static void helpersGoForWhoeverHurtTheirSummoner(GameTestHelper helper) {
        // The owner's film (2026-10-28): the summoner was punched and its helpers stood about. The
        // move is offered as "calls in small helpers to fight BESIDE it", and a vex or a tamed wolf
        // goes for whoever hurts its owner — so a friendly species' helpers must too.
        String id = creature(helper, "ability_summon_defend", "\"summon\"", FRIENDLY);
        Husk husk = husk(helper, FAR);
        float full = husk.getHealth();
        boolean[] struck = {false};
        holdUntil(helper, id, () -> {
            Mob owner = summoner(helper, id);
            if (owner != null && !struck[0] && !helpersOf(helper, owner).isEmpty()) {
                // The husk hits the summoner (it cannot move: NoAi), so its helpers must go to it.
                Damage.hurt(owner, helper.getLevel().damageSources().mobAttack(husk), 1.0F);
                struck[0] = true;
            }
        }, () -> husk.getHealth() < full);
        helper.runAfterDelay(500, () -> {
            if (!struck[0]) {
                helper.fail(Component.literal("the summoner never called in helpers"));
            } else if (husk.getHealth() >= full) {
                helper.fail(Component.literal("the husk hurt the summoner and its helpers never went for it (helpers: "
                        + helpersOf(helper, summoner(helper, id)).size() + ")"));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_summon_family", timeoutTicks = 600)
    public static void helpersNeverHitTheirSummonerTheChildOrEachOther(GameTestHelper helper) {
        // A slam-and-summon creature, a husk at its side and the child punching it: its slam must
        // spare its own helpers, and a FRIENDLY creature's helpers never go for the child, even
        // when the child is the one hurting their summoner.
        String id = creature(helper, "ability_summon_family", "\"slam\",\"summon\"", FRIENDLY);
        husk(helper, SpeciesOnRigGameTest.SPOT.east(2));
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-summon-kid"));
        BlockPos k = helper.absolutePos(SpeciesOnRigGameTest.SPOT.west(2));
        kid.teleportTo(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        float kidFull = kid.getHealth();
        java.util.Map<java.util.UUID, Float> helperLow = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, Float> helperMax = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, String> why = new java.util.HashMap<>();
        holdUntil(helper, id, () -> {
            Mob owner = summoner(helper, id);
            if (owner == null) {
                return;
            }
            for (Mob h : helpersOf(helper, owner)) {
                helperMax.merge(h.getUUID(), h.getMaxHealth(), Math::max);
                helperLow.merge(h.getUUID(), h.getHealth(), Math::min);
                if (h.getHealth() < h.getMaxHealth() && h.getLastDamageSource() != null) {
                    why.put(h.getUUID(), h.getLastDamageSource().getMsgId() + " by "
                            + (h.getLastDamageSource().getEntity() == null ? "nothing"
                            : h.getLastDamageSource().getEntity().getType().toShortString()));
                }
            }
            if (owner.tickCount % 40 == 0 && !helperMax.isEmpty()) {
                kid.attack(owner);
                owner.setHealth(owner.getMaxHealth());   // the punches are the provocation, not a kill
            }
        }, () -> false);
        helper.runAfterDelay(400, () -> {
            if (helperMax.isEmpty()) {
                helper.fail(Component.literal("the summoner never called in helpers"));
                return;
            }
            if (kid.getHealth() < kidFull) {
                helper.fail(Component.literal("a FRIENDLY summoner's helpers (or its slam) hurt the child punching it ("
                        + kid.getHealth() + "/" + kidFull + ")"));
            }
            for (var e : helperLow.entrySet()) {
                if (e.getValue() < helperMax.get(e.getKey())) {
                    helper.fail(Component.literal("a helper was hurt by its own family (" + e.getValue() + "/"
                            + helperMax.get(e.getKey()) + ", " + why.get(e.getKey()) + ")"));
                }
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_summon_leave", timeoutTicks = 600)
    public static void helpersLeaveWhenAVexWould(GameTestHelper helper) {
        // A vex lives 30 to 120 seconds. Summoned helpers that never left would fill the world up
        // to the summon cap and stay. Their time is cut short here; they must go, rig and all.
        String id = creature(helper, "ability_summon_leave", "\"summon\"", FRIENDLY);
        husk(helper, FAR);
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        boolean[] cut = {false};
        long[] cutAt = {0};
        holdUntil(helper, id, () -> {
            Mob owner = summoner(helper, id);
            var hs = helpersOf(helper, owner);
            if (!cut[0] && !hs.isEmpty()) {
                long soon = helper.getLevel().getGameTime() + 10;
                for (Mob h : hs) {
                    seen.add(h.getUUID());
                    h.entityTags().removeIf(t -> t.startsWith(RigAbilities.MINION_UNTIL));
                    h.addTag(RigAbilities.MINION_UNTIL + soon);
                }
                cut[0] = true;
                cutAt[0] = helper.getLevel().getGameTime();
            }
        }, () -> cut[0]);
        // It waits for the SUMMON, not for tick 260 (the same shape as): that window failed 3 times in ~190
        // runs, the summon not yet made. The verdict is 100 ticks after the helpers' time was cut (they are due at
        // +10), or a fail at 580 ticks if the summoner never called any.
        boolean[] judged = {false};
        helper.onEachTick(() -> {
            if (judged[0] || !cut[0] || helper.getLevel().getGameTime() < cutAt[0] + 100) {
                return;
            }
            judged[0] = true;
            for (java.util.UUID u : seen) {
                var e = helper.getLevel().getEntity(u);
                if (e != null && e.isAlive()) {
                    helper.fail(Component.literal("a helper was still there " + (helper.getLevel().getGameTime() - cutAt[0])
                            + " ticks after its time was up"));
                    return;
                }
            }
            helper.succeed();
        });
        helper.runAfterDelay(580, () -> {
            if (!cut[0]) {
                helper.fail(Component.literal("the summoner never called in helpers in 580 ticks"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ability_rig_heal", timeoutTicks = 400)
    public static void aHealingCreatureGetsItsHealthBack(GameTestHelper helper) {
        String id = creature(helper, "ability_heal_rig", "\"heal\"", FRIENDLY);
        float[] hurtTo = {-1};
        helper.runAfterDelay(20, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                helper.fail(Component.literal("no carrier for the heal creature"));
                return;
            }
            c.setHealth(c.getMaxHealth() / 2f);
            hurtTo[0] = c.getHealth();
        });
        after(helper, id, 300, c -> {
            if (c.getHealth() <= hurtTo[0] + 1.0f) {
                helper.fail(Component.literal("a healing creature at half health got nothing back in 280 ticks ("
                        + c.getHealth() + " from " + hurtTo[0] + " of " + c.getMaxHealth() + ")"));
            }
        });
    }
}
