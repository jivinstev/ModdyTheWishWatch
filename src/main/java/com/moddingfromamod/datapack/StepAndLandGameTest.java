package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>WHEN a creature acts: on landing, on each step</b> (chunk 3). The held-out "the ground
 * shakes when it lands" and "it stomps the monsters when it walks by them" failed on both models in
 * every panel, the same way: there was no trigger that knew it had landed or that it was walking, so
 * the agents gave it a slam or an aura, which went off while it stood still too.
 *
 * <p>Each template's own command lines run through the real dispatcher every tick (the reset once),
 * as {@code TriggerBehaviourGameTest} does, so what is asserted is what the server does with them.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StepAndLandGameTest {

    private static final String TAG = "steplandsrc";
    private static final String SLOW = "effect give @s minecraft:slowness 10 1";

    /** The reset lines and the per-tick lines of a rendered template. */
    private record Lines(List<String> reset, List<String> tick) {
    }

    private static Lines lines(String effect, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("TAG", TAG);
        String src = EffectCatalogue.load().render(effect, p);
        List<String> reset = new ArrayList<>();
        List<String> tick = new ArrayList<>();
        List<String> into = null;
        for (String raw : src.lines().map(String::strip).toList()) {
            if (raw.startsWith("reset")) {
                into = reset;
            } else if (raw.startsWith("clock tick")) {
                into = tick;
            } else if (raw.equals("end")) {
                into = null;
            } else if (into != null && raw.startsWith("/")) {
                into.add(raw.substring(1));
            }
        }
        return new Lines(reset, tick);
    }

    private static void run(GameTestHelper helper, List<String> commands) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level)
                .withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
        for (String c : commands) {
            level.getServer().getCommands().performPrefixedCommand(src, c);
        }
    }

    /**
     * The creature that carries the ability: its physics on, so it stands ON the ground the way a
     * real one does (with its AI off a mob floats, OnGround never turns true, and no step is ever
     * counted — measured), and its speed at zero so it never wanders off.
     */
    private static Cow source(GameTestHelper helper, BlockPos at) {
        Cow c = helper.spawn(EntityType.COW, at);
        c.addTag(TAG);
        var speed = c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        return c;
    }

    private static Husk husk(GameTestHelper helper, BlockPos at) {
        Husk h = helper.spawn(EntityType.HUSK, at);
        h.setNoAi(true);
        return h;
    }

    private static boolean slowed(net.minecraft.world.entity.LivingEntity e) {
        return e.hasEffect(MobEffects.SLOWNESS);
    }

    // ── on_land ────────────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "step_land", timeoutTicks = 200)
    public static void aLandingCatchesWhatIsNearWhereItComesDown(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        Lines l = lines("on_land", Map.of("COMMAND", SLOW, "RADIUS", "3"));
        Cow src = helper.spawn(EntityType.COW, new BlockPos(4, 6, 4));   // four blocks up: it falls
        src.addTag(TAG);
        Husk near = husk(helper, new BlockPos(5, 2, 4));
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-land-kid"));
        BlockPos k = helper.absolutePos(new BlockPos(3, 2, 4));
        kid.teleportTo(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        run(helper, l.reset());
        boolean[] firedInAir = {false};
        helper.onEachTick(() -> {
            run(helper, l.tick());
            if (!src.onGround() && slowed(near)) {
                firedInAir[0] = true;
            }
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(src.onGround(), Component.literal("the creature never came down"));
            helper.assertTrue(!firedInAir[0], Component.literal("the landing fired while it was still in the air"));
            helper.assertTrue(slowed(near), Component.literal("it landed beside the husk and nothing happened to it"));
            helper.assertTrue(!slowed(kid), Component.literal("the landing caught the child, with no TARGET"));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "step_land_default", timeoutTicks = 200)
    public static void leftAloneTheLandingJoltsWhatIsNearUp(GameTestHelper helper) {
        // An agent gave on_land no COMMAND for "the ground shakes when it lands" (chunk 3 panel)
        // and got only a puff and a thump. Left out, the ground jolts them up: the wish as it is.
        Lines l = lines("on_land", Map.of("RADIUS", "3"));
        Cow src = source(helper, new BlockPos(4, 6, 4));
        Cow near = source(helper, new BlockPos(5, 1, 4));
        near.removeTag(TAG);   // a bystander, not the creature
        double[] floor = {Double.NaN};
        double[] rose = {0};
        run(helper, l.reset());
        helper.onEachTick(() -> {
            run(helper, l.tick());
            if (Double.isNaN(floor[0]) && near.onGround()) {
                floor[0] = near.getY();
            }
            if (!Double.isNaN(floor[0])) {
                rose[0] = Math.max(rose[0], near.getY() - floor[0]);
            }
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(src.onGround(), Component.literal("the creature never came down"));
            helper.assertTrue(rose[0] > 0.3, Component.literal("it landed beside the cow and the ground did not jolt it (rose "
                    + String.format("%.2f", rose[0]) + ")"));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "step_land_still", timeoutTicks = 200)
    public static void standingStillIsNotALanding(GameTestHelper helper) {
        Lines l = lines("on_land", Map.of("COMMAND", SLOW, "RADIUS", "3"));
        Cow src = source(helper, new BlockPos(4, 1, 4));   // on the floor, never in the air
        Husk near = husk(helper, new BlockPos(5, 2, 4));
        run(helper, l.reset());
        helper.onEachTick(() -> run(helper, l.tick()));
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!slowed(near), Component.literal("a creature that never left the ground 'landed'"));
            helper.succeed();
        });
    }

    // ── on_step ────────────────────────────────────────────────────────────────────────────────

    /** Walk the source east along the ground, a quarter block a tick, from {@code from} for {@code ticks}. */
    private static void walk(GameTestHelper helper, Cow src, int from, int ticks, double perTick) {
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t >= from && t < from + ticks) {
                Vec3 p = src.position();
                src.setPos(p.x + perTick, p.y, p.z);
            }
        });
    }

    @GameTest(template = "empty_test", batch = "step_walk", timeoutTicks = 200)
    public static void walkingPastAMonsterStepsOnIt(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        Lines l = lines("on_step", Map.of("COMMAND", SLOW, "RADIUS", "2", "STRIDE", "1"));
        Cow src = source(helper, new BlockPos(1, 2, 4));
        Husk passed = husk(helper, new BlockPos(5, 2, 5));
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, "mfam-step-kid"));
        BlockPos k = helper.absolutePos(new BlockPos(4, 2, 3));
        kid.teleportTo(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        run(helper, l.reset());
        helper.onEachTick(() -> run(helper, l.tick()));
        walk(helper, src, 10, 28, 0.25);   // seven blocks east, past the husk
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(slowed(passed), Component.literal("it walked right past the husk and no step landed on it"));
            helper.assertTrue(!slowed(kid), Component.literal("a step caught the child, with no TARGET"));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "step_still", timeoutTicks = 200)
    public static void standingStillTakesNoSteps(GameTestHelper helper) {
        Lines l = lines("on_step", Map.of("COMMAND", SLOW, "RADIUS", "2", "STRIDE", "1"));
        Cow src = source(helper, new BlockPos(4, 2, 4));
        Husk beside = husk(helper, new BlockPos(5, 2, 4));
        run(helper, l.reset());
        helper.onEachTick(() -> run(helper, l.tick()));
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!slowed(beside), Component.literal("it stood still beside the husk and a 'step' landed on it"));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "step_teleport", timeoutTicks = 200)
    public static void aTeleportIsNotAStep(GameTestHelper helper) {
        Lines l = lines("on_step", Map.of("COMMAND", SLOW, "RADIUS", "2", "STRIDE", "1"));
        Cow src = source(helper, new BlockPos(1, 2, 4));
        Husk there = husk(helper, new BlockPos(7, 2, 4));
        run(helper, l.reset());
        helper.onEachTick(() -> run(helper, l.tick()));
        helper.runAfterDelay(20, () -> {
            Vec3 p = src.position();
            src.setPos(p.x + 6, p.y, p.z);   // a blink, six blocks in one tick
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!slowed(there), Component.literal("a teleport counted as walking six blocks"));
            helper.succeed();
        });
    }

    /**
     * a big creature's step reaches from its FLANK. Measured from its middle, the default
     * reach of 2 left a scale-1.6 trex's stomp 1.3 blocks past its side, and ridden past the panel's
     * monster line it landed on nothing (bh-n02). A husk walked past just outside 2 blocks of its
     * middle, and inside 2 of its side, is stepped on. Red without apply_effect's reach.
     */
    @GameTest(template = "empty_test", batch = "step_walk_flank", timeoutTicks = 200)
    public static void aBigCreaturesStepReachesFromItsFlank(GameTestHelper helper) {
        var big = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"gt_flank_stomper\",\"name\":\"Flank Stomper\",\"scale\":3.0}").getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.put(helper, big);
        double half = com.moddingfromamod.vanilla.CreatureSpawn.halfWidth("gt_flank_stomper");
        helper.assertTrue(half >= 0.5, Component.literal("a scale-3 creature should be over a block wide, half " + half));
        double off = 2.0 + half / 2;   // past its middle's old reach of 2, inside its flank's
        Map<String, String> p = new LinkedHashMap<>(Map.of("COMMAND", SLOW, "STRIDE", "1"));   // RADIUS left at its default, 2
        com.moddingfromamod.agent.tools.ApplyEffectTool.fillReach("on_step", p, EffectCatalogue.load().get("on_step"),
                com.moddingfromamod.vanilla.CreatureSpawn::halfWidth, "gt_flank_stomper");
        Lines l = lines("on_step", p);
        Cow src = source(helper, new BlockPos(1, 2, 2));
        Husk beside = husk(helper, new BlockPos(5, 2, 2));
        Vec3 at = beside.position();
        beside.setPos(at.x, at.y, at.z + off);
        run(helper, l.reset());
        helper.onEachTick(() -> run(helper, l.tick()));
        walk(helper, src, 10, 28, 0.25);   // seven blocks east, past the husk
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(slowed(beside), Component.literal("it walked past a husk " + off + " blocks from its middle, "
                    + (off - half) + " from its side, and no step reached it (RADIUS " + p.get("RADIUS") + ")"));
            helper.succeed();
        });
    }

    /**
     * a step pack compiled BEFORE the reach counted from the creature's side is put right on
     * its way into a world (ReachFromBodyFix, in DatapackDialect.correctFunction, the path every
     * install takes, the one at every boot included). Without it every creature that already had a
     * stomp kept the old reach. Red if the install rewrite stops widening it.
     */
    @GameTest(template = "empty_test", batch = "step_walk_old_pack", timeoutTicks = 200)
    public static void anOldStepPackReachesFromItsFlankOnceInstalled(GameTestHelper helper) {
        var big = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"" + TAG + "\",\"name\":\"Old Stomper\",\"scale\":3.0}").getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.put(helper, big);
        double half = com.moddingfromamod.vanilla.CreatureSpawn.halfWidth(TAG);
        helper.assertTrue(half >= 0.5, Component.literal("a scale-3 creature should be over a block wide, half " + half));
        Lines fresh = lines("on_step", Map.of("COMMAND", SLOW, "STRIDE", "1"));
        // As a pack compiled has it: no marker, the RADIUS counted from its middle.
        List<String> old = fresh.tick().stream().map(c -> c.replace("," + ReachFromBodyFix.MARKER, "")).toList();
        List<String> installed = new ArrayList<>();
        for (String c : old) {
            installed.add(DatapackDialect.correctFunction(c, new ArrayList<>()));
        }
        helper.assertTrue(installed.stream().anyMatch(c -> c.contains(ReachFromBodyFix.MARKER)),
                Component.literal("the install copy of an old step pack was not widened: " + installed));
        for (String c : installed) {
            helper.assertTrue(DatapackDialect.correctFunction(c, new ArrayList<>()).equals(c),
                    Component.literal("a second install widened it again: " + c));
        }
        for (String c : fresh.tick()) {
            helper.assertTrue(DatapackDialect.correctFunction(c, new ArrayList<>()).equals(c),
                    Component.literal("a pack compiled since #2505 was widened twice: " + c));
        }
        Cow src = source(helper, new BlockPos(1, 2, 2));
        Husk beside = husk(helper, new BlockPos(5, 2, 2));
        double off = 2.0 + half / 2;
        Vec3 at = beside.position();
        beside.setPos(at.x, at.y, at.z + off);
        run(helper, fresh.reset());
        helper.onEachTick(() -> run(helper, installed));
        walk(helper, src, 10, 28, 0.25);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(slowed(beside), Component.literal("an old step pack, installed, walked past a husk " + off
                    + " blocks from its middle and never reached it"));
            helper.succeed();
        });
    }
}
