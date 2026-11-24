package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wished creature's triggers, in the world</b> (chunk 3): what makes it act — his jump
 * when he rides it, its landing, its steps.
 *
 * <p>The held-out "when I jump it jumps super high" failed on both models in every panel: the agents
 * gave the mount jump boost, which is right, and the mount never jumped at all — a ridden creature
 * is steered by {@code PetBehaviours.steer}, which read his forward and sideways keys and never his
 * jump.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RideAndTriggersGameTest {

    private static final String RIDEABLE = "\"powers\":[\"friendly\",\"rideable\",\"persistent\"]";

    private RideAndTriggersGameTest() {
    }

    /**
     * The child, standing BESIDE the creature from the moment he arrives. A mock joins at the
     * world spawn, and this creature is nobody's, so it follows the nearest player and HOPS to him
     * once he is over {@code PetMoves.FOLLOW_TELEPORT} away ({@code PetBehaviours.ownerOf}). The
     * runner lays plots out wherever its grid has got to, so when this plot landed 16-32 blocks
     * from spawn the creature was teleported to spawn before the test put him on it, and every
     * landing went off a plot away from the husk (reproduced 1 run in 6). A child who wants to
     * ride it is standing next to it, so that is where he starts.
     */
    private static ServerPlayer kidBesideIt(GameTestHelper helper, String name) {
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, name));
        net.minecraft.core.BlockPos at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        kid.teleportTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
        return kid;
    }

    /** He climbs on, then holds jump from {@code jumpFrom}; the most it rose, in blocks. */
    private static void rideAndJump(GameTestHelper helper, String id, boolean boost, double need,
                                    String what) {
        ServerPlayer kid = kidBesideIt(helper, "mfam-jump-" + id);
        boolean[] on = {false};
        double[] ground = {Double.NaN};
        double[] rose = {0};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null || c.tickCount < 5) {
                return;
            }
            if (!on[0]) {
                kid.teleportTo(c.getX() + 1, c.getY(), c.getZ());
                kid.getInventory().clearContent();
                kid.interactOn(c, InteractionHand.MAIN_HAND);
                on[0] = com.moddingfromamod.mob.RideSeat.mountOf(kid) == c;
                if (on[0] && boost) {
                    c.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 20 * 60, 4, false, false));
                }
                return;
            }
            if (Double.isNaN(ground[0]) && c.onGround()) {
                ground[0] = c.getY();
            }
            // His jump key, held, as the input packet would carry it.
            kid.setLastClientInput(new Input(false, false, false, false, true, false, false));
            if (!Double.isNaN(ground[0])) {
                rose[0] = Math.max(rose[0], c.getY() - ground[0]);
            }
        });
        helper.runAfterDelay(160, () -> {
            if (!on[0]) {
                helper.fail(Component.literal("the child never got on the rideable creature"));
            } else if (rose[0] < need) {
                helper.fail(Component.literal(what + ": it rose " + String.format("%.2f", rose[0]) + " blocks (needs " + need + ")"));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ride_jump_land", skyAccess = true, timeoutTicks = 600)
    public static void whenHeJumpsItsLandingShakesTheGround(GameTestHelper helper) {
        // The whole wish, through the tool the agent calls: a mount he rides, on_land through
        // apply_effect (compiled, loaded), a monster beside it. Riding about does nothing; HIS jump
        // makes it jump, and the landing lands on the monster.
        if (!com.moddingfromamod.datapack.CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        String id = SculptAbilitiesOnRigGameTest.creature(helper, "ride_land", "", RIDEABLE);
        com.google.gson.JsonObject call = new com.google.gson.JsonObject();
        call.addProperty("effect", "on_land");
        call.addProperty("tag", id);
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("COMMAND", "effect give @s minecraft:slowness 10 1");
        params.addProperty("RADIUS", "4");
        call.add("params", params);
        var r = new com.moddingfromamod.agent.tools.ApplyEffectTool().call(call);
        if (r.isError()) {
            helper.fail(Component.literal("apply_effect on_land failed: " + r.text()));
            return;
        }
        ServerPlayer kid = kidBesideIt(helper, "mfam-land-" + id);
        net.minecraft.world.entity.monster.zombie.Husk husk = helper.spawn(net.minecraft.world.entity.EntityType.HUSK,
                SpeciesOnRigGameTest.SPOT.east(2));
        husk.setNoAi(true);
        boolean[] on = {false};
        boolean[] slowedBeforeJump = {false};
        int[] mountedAt = {-1};
        int[] air = {0};
        StringBuilder landings = new StringBuilder();
        StringBuilder path = new StringBuilder();
        int[] tt = {0};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (tt[0]++ % 4 == 0 && tt[0] < 90) {
                path.append(" t").append(tt[0]).append('=').append(c == null ? "none"
                        : c.getType().toShortString() + "#" + c.getId() + "@" + c.blockPosition().subtract(helper.absolutePos(SpeciesOnRigGameTest.SPOT))
                        + (c.getVehicle() != null ? "^" + c.getVehicle().getType().toShortString() : "")
                        + (c.getPassengers().isEmpty() ? "" : "+" + c.getPassengers().get(0).getName().getString()));
            }
            if (c == null || c.tickCount < 5) {
                return;
            }
            if (!on[0]) {
                kid.teleportTo(c.getX() + 1, c.getY(), c.getZ());
                kid.getInventory().clearContent();
                kid.interactOn(c, InteractionHand.MAIN_HAND);
                on[0] = com.moddingfromamod.mob.RideSeat.mountOf(kid) == c;
                mountedAt[0] = c.tickCount;
                return;
            }
            boolean jumping = c.tickCount - mountedAt[0] > 60;   // ride about first, then jump
            kid.setLastClientInput(new Input(false, false, false, false, jumping, false, false));
            if (!jumping && husk.hasEffect(MobEffects.SLOWNESS)) {
                slowedBeforeJump[0] = true;
            }
            if (!c.onGround()) {
                air[0]++;
            } else if (air[0] > 0) {
                if (landings.length() < 400) {
                    landings.append(String.format(" [t%d air %d, %.1f from husk, ridden=%s, in water=%s, at %s]", c.tickCount, air[0],
                            c.distanceTo(husk), com.moddingfromamod.mob.RideSeat.mountOf(kid) == c, c.isInWater(),
                            c.blockPosition().subtract(helper.absolutePos(SpeciesOnRigGameTest.SPOT))));
                }
                air[0] = 0;
            }
        });
        helper.runAfterDelay(260, () -> {
            if (!on[0]) {
                helper.fail(Component.literal("the child never got on"));
            } else if (slowedBeforeJump[0]) {
                helper.fail(Component.literal("the landing went off while he only rode it about, before any jump"));
            } else if (!husk.hasEffect(MobEffects.SLOWNESS)) {
                var srv = helper.getLevel().getServer();
                var tickTag = srv.getFunctions().getTag(net.minecraft.resources.Identifier.withDefaultNamespace("tick"));
                helper.fail(Component.literal("he jumped it beside the husk and its landing did nothing; landings:"
                        + landings + " husk alive=" + husk.isAlive() + " effects=" + husk.getActiveEffects()
                        + " carrier=" + (SpeciesOnRigGameTest.carrier(helper, id) == null ? "gone"
                                : SpeciesOnRigGameTest.carrier(helper, id).blockPosition().subtract(helper.absolutePos(SpeciesOnRigGameTest.SPOT))
                                + " air-run-now=" + air[0]) + " tick fns=" + tickTag.size()
                        + " reloading=" + com.moddingfromamod.datapack.DatapackReloader.reloadInProgress()
                        + " deferred=" + com.moddingfromamod.datapack.DatapackReloader.reloadDeferred()
                        + " path:" + path + " apply_effect said: " + r.text().replace('\n', ' ')));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ride_jump", skyAccess = true, timeoutTicks = 300)
    public static void aRiddenCreatureJumpsWhenHeJumps(GameTestHelper helper) {
        String id = SculptAbilitiesOnRigGameTest.creature(helper, "ride_jump", "", RIDEABLE);
        rideAndJump(helper, id, false, 1.0, "he held jump on its back and it never left the ground");
    }

    @GameTest(template = "empty_test", batch = "ride_jump_boost", skyAccess = true, timeoutTicks = 300)
    public static void jumpBoostMakesHisJumpSuperHigh(GameTestHelper helper) {
        // "when i jump it jumps super high": jump boost on the mount, which is what both models reached
        // for. It only works once his jump makes it jump at all.
        String id = SculptAbilitiesOnRigGameTest.creature(helper, "ride_jump_boost", "", RIDEABLE);
        rideAndJump(helper, id, true, 2.5, "with jump boost V his jump stayed low");
    }

    /**
     * he rides a creature with jump boost VI and holds jump, so it leaps six blocks and lands,
     * again and again (the starter T-rex); its landings hurt him only when the creature itself would
     * have been hurt. Vanilla hands a vehicle's fall to its passengers, so
     * a fallproof leaping T-rex took 9 health off its rider on every jump until RigImmunities spared
     * him. The control is the same leaps on a mount that is not fallproof: if they do not hurt him,
     * the leaps prove nothing.
     */
    private static void dropWithRider(GameTestHelper helper, String id, boolean fallproof) {
        String powers = fallproof ? "\"powers\":[\"friendly\",\"rideable\",\"persistent\",\"fallproof\"]" : RIDEABLE;
        String made = SculptAbilitiesOnRigGameTest.creature(helper, id, "", powers);
        ServerPlayer kid = kidBesideIt(helper, "mfam-drop-" + made);
        boolean[] on = {false};
        int[] droppedAt = {-1};
        int[] mountedAt = {-1};
        String[] carrier = {"?"};
        double[] peak = {0, 0};   // the highest it rose above the drop, and its longest fall
        double[] groundY = {0};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, made);
            if (c == null || c.tickCount < 5) {
                return;
            }
            if (!on[0]) {
                kid.teleportTo(c.getX() + 1, c.getY(), c.getZ());
                kid.getInventory().clearContent();
                kid.interactOn(c, InteractionHand.MAIN_HAND);
                on[0] = com.moddingfromamod.mob.RideSeat.mountOf(kid) == c;
                mountedAt[0] = c.tickCount;
                return;
            }
            carrier[0] = c.getType().toShortString();
            // Not before a new player's three seconds of spawn protection are over: the first control
            // dropped him inside them, and a hurt nobody took read as a landing that hurt nobody.
            if (droppedAt[0] < 0 && c.onGround() && c.tickCount - mountedAt[0] > 80) {
                kid.setHealth(kid.getMaxHealth());
                c.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 20 * 60, 5, false, false));
                droppedAt[0] = c.tickCount;
                groundY[0] = c.getY();
            }
            if (droppedAt[0] >= 0) {
                peak[0] = Math.max(peak[0], c.getY() - groundY[0]);
                peak[1] = Math.max(peak[1], c.fallDistance);

            }
            // His jump key, held: it leaps six blocks and comes down, again and again.
            kid.setLastClientInput(new Input(false, false, false, false, droppedAt[0] >= 0, false, false));
        });
        helper.runAfterDelay(300, () -> {
            float lost = kid.getMaxHealth() - kid.getHealth();
            if (!on[0] || droppedAt[0] < 0) {
                helper.fail(Component.literal("the child never got on, or it never leapt"));
            } else if (com.moddingfromamod.mob.RideSeat.mountOf(kid) == null) {
                helper.fail(Component.literal("he was thrown off, so the landings were not his mount's"));
            } else if (fallproof && lost > 0) {
                helper.fail(Component.literal("a fallproof mount's landings (" + carrier[0] + ") took " + lost + " health off its rider"));
            } else if (!fallproof && lost <= 0) {
                helper.fail(Component.literal("the control: leaping on a mount (" + carrier[0] + ") that is not fallproof hurt nobody (it rose " + String.format("%.1f", peak[0]) + " and fell at most " + String.format("%.1f", peak[1]) + ")"));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_test", batch = "ride_drop", skyAccess = true, timeoutTicks = 500)
    public static void aFallproofMountSparesItsRiderTheLanding(GameTestHelper helper) {
        dropWithRider(helper, "ride_drop_safe", true);
    }

    @GameTest(template = "empty_test", batch = "ride_drop", skyAccess = true, timeoutTicks = 500)
    public static void aMountThatFeelsItsFallPassesItToItsRider(GameTestHelper helper) {
        dropWithRider(helper, "ride_drop_hurt", false);
    }
}
