package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does a ridden creature seat him on its back, and can he climb on from anywhere on it?</b>
 * ("knowing where the rider is supposed to sit … is important", and "having
 * to target a tiny section inside the mob will not be good"). For every starter creature and every
 * committed big sculpt, made rideable: where he sits against the top of its back over the carrier's
 * middle ({@link RigFit#backTop}), whether a right-click on EACH hit proxy climbs him on, and
 * whether a punch on one of its proxies while he rides hurts his own mount.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RideFitProbeGameTest {

    static final BlockPos SPOT = new BlockPos(2, 2, 2);

    /** {name, sculpt resource or repo path, species resource or null, extra powers}. */
    static final String[][] CASES = {
            {"cat", "starter:cat", ""}, {"chicken", "starter:chicken", ""},
            {"frog", "starter:frog", ""}, {"pet_dragon", "starter:pet_dragon", ""},
            {"pet_dragon_fly", "starter:pet_dragon", "flies"}, {"sheep", "starter:sheep", ""},
            {"snail", "starter:snail", ""}, {"turtle", "starter:turtle", ""},
            {"wolf", "starter:wolf", ""}, {"spikor", "corpus:" + SpikeFixture.SPIKOR, ""},
            {"omegalad", "corpus:" + SpikeFixture.real("sigmaboy-armB"), ""},
            {"tralalelo", "corpus:" + SpikeFixture.real("tralalelo-armB"), ""}};

    @GameTest(template = "empty_test", batch = "ride_fit_probe", timeoutTicks = 20 * 600)
    public static void whereHeSitsAndWhereHeCanClimbOn(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-ride-fit");
        List<String> rows = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        GameTestSequence seq = helper.startSequence();
        for (String[] c : CASES) {
            String id = "ridefit_" + c[0];
            seq.thenExecute(() -> {
                plant(helper, id, c[1], c[2]);
                wish(helper, id);
            });
            seq.thenWaitUntil(() -> helper.assertTrue(carrier(helper, id) != null
                    && VanillaRigAnimator.rigOf(carrier(helper, id)) != null, Component.literal("no rig yet for " + id)));
            seq.thenExecute(() -> rows.add(measure(helper, kid, id, c[0], bad)));
            seq.thenExecute(() -> clear(helper, id, kid));
        }
        seq.thenExecute(() -> System.out.println("RIDE-FIT-PROBE (seat = where he sits, back = top of "
                + "the creature over the carrier's middle; gap > 0 floats, < 0 sinks in; blocks):\n  "
                + String.join("\n  ", rows)));
        seq.thenExecute(() -> helper.assertTrue(bad.isEmpty(), Component.literal(String.join("; ", bad)))).thenSucceed();
    }

    /** How far from the creature's back he may sit, in blocks: a pixel and a half. */
    static final double SEAT_TOLERANCE = 0.1;

    static String measure(GameTestHelper helper, ServerPlayer kid, String id, String name,
                          List<String> bad) {
        Mob c = carrier(helper, id);
        DisplayRig rig = VanillaRigAnimator.rigOf(c);
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(c.getType()).getPath();
        double back = RigFit.backTop(RigFit.atoms(rig.groups(), rig.scale()), 0.25);
        double drawnTop = RigFit.bounds(RigFit.atoms(rig.groups(), rig.scale())).maxY();
        kid.stopRiding();
        kid.getInventory().clearContent();
        kid.teleportTo(c.getX(), c.getY(), c.getZ() - 1);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(kid, InteractionHand.MAIN_HAND, c));
        boolean onCarrier = com.moddingfromamod.mob.RideSeat.mountOf(kid) == c;
        double seat = Double.NaN;
        if (onCarrier) {
            Entity v = kid.getVehicle();
            if (v != c) {
                c.positionRider(v);   // the seat onto the carrier, then him onto the seat
            }
            v.positionRider(kid);
            seat = kid.getY() + kid.getVehicleAttachmentPoint(v).y - c.getY();
        }
        // Punch his own mount's proxy while riding.
        float hp0 = c.getHealth();
        List<Mob> proxies = rig.proxies();
        if (onCarrier && !proxies.isEmpty()) {
            kid.resetAttackStrengthTicker();
            kid.attack(proxies.get(0));
        }
        float ownHurt = hp0 - c.getHealth();
        // Climb on from each proxy.
        int climbs = 0;
        for (Mob p : proxies) {
            kid.stopRiding();
            kid.teleportTo(p.getX(), p.getY(), p.getZ() - 1);
            com.moddingfromamod.mv.Interact.on(kid, p, InteractionHand.MAIN_HAND);
            if (com.moddingfromamod.mob.RideSeat.mountOf(kid) == c) {
                climbs++;
            }
        }
        kid.stopRiding();
        var plan = rig.hitPlan();
        if (!onCarrier) {
            bad.add(name + ": a click on the creature did not climb him on");
        } else if (Math.abs(seat - back) > SEAT_TOLERANCE) {
            bad.add(String.format("%s: he sits %.2f blocks %s its back", name, Math.abs(seat - back),
                    seat > back ? "above" : "inside"));
        }
        if (climbs != proxies.size()) {
            bad.add(name + ": only " + climbs + " of its " + proxies.size() + " proxies climbed him on");
        }
        if (ownHurt > 0) {
            bad.add(name + ": riding it, a punch past its head hurt it " + ownHurt);
        }
        if (plan != null && plan.click().hit() < HitProxyPlan.Bounds.VANILLA.clickHit()) {
            bad.add(String.format("%s: only %.2f of clicks on it land", name, plan.click().hit()));
        }
        return String.format("%-15s %-8s x%.2f h%.2f  back %5.2f  top %5.2f  seat %5.2f  gap %+5.2f  "
                        + "carrier-click %s  proxies %d climb %d/%d  click-hit %s  own-mount-hurt %.1f",
                name, type, c.getScale(), c.getBbHeight(), back, drawnTop, seat, seat - back,
                onCarrier ? "ON" : "no", proxies.size(), climbs, proxies.size(),
                plan == null ? "-" : String.format("%.2f", plan.click().hit()), ownHurt);
    }

    /**
     * <b>A flying creature flies with him, and lets him down safely</b> — through the real path: the
     * species says {@code flies} and {@code rideable}, its rig gets the bat carrier, he climbs on
     * with an empty hand, and PetBehaviours steers it from his look and keys. Up 45° climbs, down
     * dives, letting go hovers, and getting off high up gives him slow falling (his own fall is his
     * client's, which a mock player has not got, so the EFFECT is what is asserted) and gives the
     * creature its gravity back.
     */
    @GameTest(template = "empty_test", batch = "ride_fly", timeoutTicks = 20 * 120)
    public static void aFlyingCreatureFliesWithHimAndLetsHimDownSafely(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-ride-fly");
        String id = "ridefly_dragon";
        double[] y = new double[4];
        Mob[] c = new Mob[1];
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            plant(helper, id, "starter:pet_dragon", "flies");
            wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            c[0] = carrier(helper, id);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.flies(c[0]),
                    Component.literal("a flying creature should ride a flying carrier, not a " + c[0].getType()));
            kid.getInventory().clearContent();
            kid.teleportTo(c[0].getX(), c[0].getY(), c[0].getZ() - 1);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(kid, InteractionHand.MAIN_HAND, c[0]));
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(kid) == c[0], Component.literal("he did not climb on"));
            y[0] = c[0].getY();
        });
        phase(seq, kid, 0f, -45f, 1f, 40);
        seq.thenExecute(() -> {
            helper.assertTrue(c[0].isNoGravity(), Component.literal("a ridden flier keeps its gravity, so it cannot climb"));
            helper.assertTrue(c[0].getY() > y[0] + 2, Component.literal(String.format(
                    "looking up and holding W, it went from y=%.1f to y=%.1f", y[0], c[0].getY())));
            y[1] = c[0].getY();
        });
        phase(seq, kid, 180f, 30f, 1f, 20);
        seq.thenExecute(() -> {
            helper.assertTrue(c[0].getY() < y[1] - 1, Component.literal(String.format(
                    "looking down and holding W, it went from y=%.1f to y=%.1f", y[1], c[0].getY())));
        });
        phase(seq, kid, 0f, -45f, 1f, 40);
        seq.thenExecute(() -> y[2] = c[0].getY());
        phase(seq, kid, 0f, 0f, 0f, 40);
        seq.thenExecute(() -> {
            y[3] = c[0].getY();
            System.out.println(String.format("RIDE-FLY: start %.1f  up %.1f  then %.1f  let go 2 s %.1f "
                    + "(drift %+.2f)  %.1f over the ground", y[0], y[1], y[2], y[3], y[3] - y[2],
                    com.moddingfromamod.mob.RideSeat.overGround(c[0])));
            helper.assertTrue(Math.abs(y[3] - y[2]) < 0.25, Component.literal(String.format(
                    "let go, it should hover: it went from y=%.1f to y=%.1f", y[2], y[3])));
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.overGround(kid) > 3,
                    Component.literal("the test needs him high up to ask about getting off"));
            kid.stopRiding();
            helper.assertTrue(kid.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING),
                    Component.literal("getting off a flier high up must not drop him: no slow falling"));
        });
        seq.thenExecuteAfter(2, () -> {
            helper.assertFalse(c[0].isNoGravity(), Component.literal("nobody rides it, and its gravity is still off"));
        });
        seq.thenExecute(() -> clear(helper, id, kid)).thenSucceed();
    }

    /**
     * <b>Logging off while riding leaves the creature where it was</b>: vanilla would save the
     * carrier into his player file and take it out of the world, leaving its display parts behind.
     * Through the real {@code PlayerList.remove}.
     */
    @GameTest(template = "empty_test", batch = "ride_logout", timeoutTicks = 20 * 60)
    public static void loggingOffMidRideLeavesTheCreatureInTheWorld(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-ride-logout");
        String id = "ridelogout_dragon";
        Mob[] c = new Mob[1];
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            plant(helper, id, "starter:pet_dragon", "");
            wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            c[0] = carrier(helper, id);
            kid.getInventory().clearContent();
            kid.teleportTo(c[0].getX(), c[0].getY(), c[0].getZ() - 1);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(kid, InteractionHand.MAIN_HAND, c[0]));
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(kid) == c[0], Component.literal("he did not climb on"));
            helper.getLevel().getServer().getPlayerList().remove(kid);
        });
        seq.thenExecuteAfter(2, () -> {
            helper.assertFalse(c[0].isRemoved(), Component.literal("he logged off riding it and it left the world with him ("
                    + c[0].getRemovalReason() + ")"));
            helper.assertTrue(VanillaRigAnimator.rigOf(c[0]) != null, Component.literal("it stayed, but lost its rig"));
            clear(helper, id, kid);
        }).thenSucceed();
    }

    /** Hold a look and the keys for {@code ticks}. */
    static void phase(GameTestSequence seq, ServerPlayer kid, float yaw, float pitch, float forward,
                      int ticks) {
        for (int t = 0; t < ticks; t += 5) {
            seq.thenExecuteAfter(5, () -> {
                kid.setYRot(yaw);
                kid.setXRot(pitch);
                com.moddingfromamod.compat.RiderInput.hold(kid, forward, 0f);
            });
        }
    }

    static void plant(GameTestHelper helper, String id, String sculpt, String extraPowers) {
        try {
            com.moddingfromamod.testing.SpeciesForTest.put(helper, species(id, sculpt, extraPowers));
        } catch (Exception e) {
            helper.fail(Component.literal("could not plant " + id + ": " + e));
        }
    }

    /** A rideable test creature: {@link RideFitFixture#species}. */
    public static Species species(String id, String sculpt, String extraPowers) throws java.io.IOException {
        return RideFitFixture.species(id, sculpt, extraPowers);
    }

    /** Spawn one the way a wish does: {@link RideFitFixture#spawn}. */
    public static void spawn(ServerLevel level, String id, net.minecraft.world.phys.Vec3 at) {
        RideFitFixture.spawn(level, id, at);
    }


    static void wish(GameTestHelper helper, String id) {
        var at = helper.absolutePos(SPOT);
        spawn(helper.getLevel(), id, new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
    }

    static Mob carrier(GameTestHelper helper, String id) {
        String tag = WishMobTags.forSpecies(id);
        AABB near = new AABB(helper.absolutePos(SPOT)).inflate(48);
        for (Entity e : helper.getLevel().getEntities((Entity) null, near,
                e -> e.entityTags().contains(DisplayRig.RIG_TAG) && e.entityTags().contains(tag))) {
            if (e instanceof Mob m && m.isAlive()) {
                return m;
            }
        }
        return null;
    }

    static void clear(GameTestHelper helper, String id, ServerPlayer kid) {
        kid.stopRiding();
        Mob c = carrier(helper, id);
        if (c != null) {
            DisplayRig.reap(helper.getLevel(), c.getUUID());
            c.discard();
        }
    }
}
