package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The locomotion layer is chosen from the CARRIER's real state</b>: the
 * shipping bridge makes a real carrier, the shipping animator drives it, and the only thing the
 * test changes is what is true of that carrier — it is under water, it is off the ground, it was
 * told to stay. Each test has its control in the same plot, the same species, the state not true:
 * an airborne flier beside a grounded one, a swimmer in water beside one on the floor, a stayer
 * beside a free one. A rule that fired on the species alone would light both.
 *
 * <p>Every subject is {@code NoAI}, so nothing wanders, and a {@code NoAI} mob never runs
 * {@code travel()} ({@code isControlledByLocalInstance} is false), so it neither falls nor sets its
 * own {@code onGround}. The ones standing on the floor have it set each tick: that is the fact
 * under test, stated, rather than left at whatever the spawn happened to leave it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigLocomotionGameTest {

    private static final String BATCH = "rig_locomotion";

    /** A small creature with legs, wings and a tail, all named the way the agent names them. */
    private static List<CreatureSculpt.Part> critter(boolean legs) {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>();
        int y = legs ? 4 : 0;
        out.add(new CreatureSculpt.Part("body", null, new float[]{0, y, 0},
                new float[]{-3, y, -4}, new float[]{3, y + 4, 4}, 0xff8040));
        out.add(new CreatureSculpt.Part("head", null, new float[]{0, y + 3, -4},
                new float[]{-2, y + 3, -8}, new float[]{2, y + 7, -4}, 0xff8040));
        out.add(new CreatureSculpt.Part("wingL", "body", new float[]{-3, y + 3, 0},
                new float[]{-9, y + 3, -2}, new float[]{-3, y + 4, 2}, 0xffffff));
        out.add(new CreatureSculpt.Part("wingR", "body", new float[]{3, y + 3, 0},
                new float[]{3, y + 3, -2}, new float[]{9, y + 4, 2}, 0xffffff));
        out.add(new CreatureSculpt.Part("tail", "body", new float[]{0, y + 2, 4},
                new float[]{-1, y + 1, 4}, new float[]{1, y + 3, 9}, 0xff8040));
        if (legs) {
            for (int i = 0; i < 4; i++) {
                float x = i % 2 == 0 ? -2 : 1;
                float z = i < 2 ? -3 : 2;
                out.add(new CreatureSculpt.Part("leg" + i, null, new float[]{x + 0.5f, 4, z + 0.5f},
                        new float[]{x, 0, z}, new float[]{x + 1, 4, z + 1}, 0x804020));
            }
        } else {
            out.add(new CreatureSculpt.Part("finL", "body", new float[]{-3, 1, 0},
                    new float[]{-6, 1, -1}, new float[]{-3, 2, 1}, 0xffffff));
        }
        return out;
    }

    private static String plant(GameTestHelper helper, String id, boolean legs, String powers) {
        List<CreatureSculpt.Part> parts = critter(legs);
        try {
            CreatureFiles.write(id, parts, "{}");
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(
                com.google.gson.JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"modelKey\":\"" + id + "\",\"health\":40,\"speed\":0.2,\"scale\":0.6,"
                        + "\"base\":\"wild\",\"powers\":[\"friendly\"" + powers + "]}")
                        .getAsJsonObject()));
        return id;
    }

    private static Mob spawn(GameTestHelper helper, String id, BlockPos rel) {
        var level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(rel);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(id);
        LivingEntity carrier = VanillaRigBridge.manifestNow(level, mob);
        helper.assertTrue(carrier instanceof Mob, Component.literal("the bridge made no rig for " + id));
        Mob m = (Mob) carrier;
        m.setPermanentlyInvulnerable(true);
        return m;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aFlierFlapsInTheAirAndNotOnTheGround(GameTestHelper helper) {
        String id = plant(helper, "loco_flier", true, ",\"flies\"");
        Mob air = spawn(helper, id, new BlockPos(1, 4, 2));
        Mob ground = spawn(helper, id, new BlockPos(4, 1, 2));
        air.setNoAi(true);
        air.setNoGravity(true);
        ground.setNoAi(true);
        helper.onEachTick(() -> ground.setOnGround(true));
        helper.succeedWhen(() -> {
            var a = VanillaRigAnimator.weightsOf(air);
            var g = VanillaRigAnimator.weightsOf(ground);
            helper.assertTrue(a != null && g != null, Component.literal("not driven yet"));
            helper.assertTrue(!air.onGround() && ground.onGround(), Component.literal("the stage is not set: air "
                    + air.onGround() + " ground " + ground.onGround()));
            helper.assertTrue(a.fly() >= 1f, Component.literal("the airborne flier is not flying: " + a.fly()));
            helper.assertTrue(g.fly() == 0f, Component.literal("the GROUNDED flier flies: " + g.fly()));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aSwimmerSwimsInWaterAndNotOnLand(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
                }
            }
        }
        String id = plant(helper, "loco_swimmer", false, "");
        Mob wet = spawn(helper, id, new BlockPos(1, 2, 2));
        Mob dry = spawn(helper, id, new BlockPos(5, 1, 2));
        wet.setNoAi(true);
        wet.setNoGravity(true);
        dry.setNoAi(true);
        helper.onEachTick(() -> dry.setOnGround(true));
        helper.succeedWhen(() -> {
            var w = VanillaRigAnimator.weightsOf(wet);
            var d = VanillaRigAnimator.weightsOf(dry);
            helper.assertTrue(w != null && d != null, Component.literal("not driven yet"));
            helper.assertTrue(wet.isInWater() && !dry.isInWater(), Component.literal("the stage is not set"));
            helper.assertTrue(w.swim() >= 1f, Component.literal("the creature in the water is not swimming: "
                    + w.swim()));
            helper.assertTrue(d.swim() == 0f, Component.literal("the creature on LAND swims: " + d.swim()));
        });
    }

    // ------------------------------------------------------------------ the walk

    /** The Builder's horse, as its sculpt.json: front legs {@code legL/legR}, hind {@code legBL/legBR}. */
    private static String horse() {
        try (var in = RigLocomotionGameTest.class.getResourceAsStream(
                "/builder-bodies/species/horse/sculpt.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("the builder horse is missing", e);
        }
    }

    /**
     * A body and a {@code leg_row} of {@code pairs}, the way the agent is told to make a bug — as the
     * sculpt.json the agent's call would store: the kit is expanded when the model is read, so a spec
     * whose parts were expanded by hand would carry the legs twice.
     */
    private static String bug(int pairs) {
        return "{\"parts\":[{\"name\":\"body\",\"from\":[-4,5,-2],\"to\":[4,11,8],\"colour\":\"#3a2a1e\"},"
                + "{\"name\":\"head\",\"parent\":\"body\",\"from\":[-3,5,-7],\"to\":[3,10,-1],"
                + "\"colour\":\"#5a4030\"}],\"kits\":[{\"kit\":\"leg_row\",\"on\":\"body\",\"count\":" + pairs + "}]}";
    }

    /**
     * <b>Every leg STEPS while the creature walks, whatever it is called </b> — counted the
     * way the issue counts it: the leg displays whose pose changes during a real walk. The carrier
     * is moved by vanilla's own {@code travel()} (AI on, its own speed zeroed, a velocity set each
     * tick: a {@code NoAI} mob never animates its walk, VANILLA_RIG_PARITY §11), so the stride
     * comes off the same limb speed and distance the renderer reads.
     *
     * <p>Before: 2 of 4 on the horse (the clip knows {@code legL/legR}, not {@code legBL/legBR}),
     * 0 of 8 on the spider, 0 of 6 on the ant. The control is the BODY, measured the same way: a
     * display that is not a leg must not move, or "every leg moved" is true of a rig that shakes.
     */
    private static void walksOnAllItsLegs(GameTestHelper helper, String id, String sculpt, int legs) {
        // The model is read back from the SPEC (ModelRepository.sculptedParts), so it is written
        // as one: written as "{}" the creature came up as the humanoid stand-in, two legs named
        // leg_left/leg_right, and the counts measured a creature nobody asked for.
        try {
            var spec = com.google.gson.JsonParser.parseString(sculpt).getAsJsonObject();
            List<CreatureSculpt.Part> parts = new java.util.ArrayList<>(
                    CreatureSculpt.parseParts(spec.getAsJsonArray("parts")));
            if (spec.has("kits")) {
                parts.addAll(com.moddingfromamod.runtime.SculptKits.expand(spec.getAsJsonArray("kits"), parts));
            }
            CreatureFiles.write(id, parts, sculpt);
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(
                com.google.gson.JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"modelKey\":\"" + id + "\",\"health\":40,\"speed\":0.2,\"scale\":0.5,"
                        + "\"base\":\"wild\",\"powers\":[\"friendly\"]}").getAsJsonObject()));
        Mob m = spawn(helper, id, new BlockPos(2, 1, 4));
        var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        java.util.Map<Integer, java.util.Set<String>> seen = new java.util.HashMap<>();
        float[] peakSpeed = {0f};
        int[] sampled = {0};
        helper.onEachTick(() -> {
            // Across the plot and back, facing the way it goes.
            double x = m.getX() - helper.absolutePos(BlockPos.ZERO).getX();
            double dir = (helper.getTick() / 40) % 2 == 0 ? 1 : -1;
            if (x > 6.5) {
                dir = -1;
            } else if (x < 1.5) {
                dir = 1;
            }
            m.setDeltaMovement(0.12 * dir, m.getDeltaMovement().y, 0);
            float yaw = dir > 0 ? -90f : 90f;
            m.setYRot(yaw);
            m.setYBodyRot(yaw);
            m.setYHeadRot(yaw);
            peakSpeed[0] = Math.max(peakSpeed[0], m.walkAnimation.speed());
            DisplayRig rig = VanillaRigAnimator.rigOf(m);
            if (rig == null || helper.getTick() < 20) {
                return;
            }
            sampled[0]++;
            for (int i = 0; i < rig.displays().size(); i++) {
                var t = com.moddingfromamod.compat.EntityNbt.save(rig.displays().get(i)).get("transformation");
                seen.computeIfAbsent(i, k -> new java.util.HashSet<>()).add(String.valueOf(t));
            }
        });
        helper.runAfterDelay(140, () -> {
            DisplayRig rig = VanillaRigAnimator.rigOf(m);
            RigLocomotion.Plan plan = VanillaRigAnimator.planOf(m);
            helper.assertTrue(rig != null && plan != null, Component.literal("the creature is not being driven"));
            helper.assertTrue(peakSpeed[0] > 0.1f, Component.literal("it never walked: limb speed "
                    + peakSpeed[0] + " -- nothing below is about a walk"));
            List<RigAnatomy.Leg> found = plan.anatomy().legs();
            helper.assertTrue(found.size() == legs, Component.literal("the rig found " + found.size()
                    + " legs, not " + legs + ": " + found.stream().map(RigAnatomy.Leg::bone).toList()));
            List<RigPlan.Group> groups = rig.groups();
            int stepping = 0;
            StringBuilder still = new StringBuilder();
            for (RigAnatomy.Leg l : found) {
                java.util.Set<String> chain = RigLocomotion.subtree(plan.anatomy().parts(), l.bone());
                boolean moved = false;
                for (int i = 0; i < groups.size(); i++) {
                    if (chain.contains(groups.get(i).boneName()) && seen.getOrDefault(i, java.util.Set.of()).size() > 2) {
                        moved = true;
                    }
                }
                if (moved) {
                    stepping++;
                } else {
                    still.append(' ').append(l.bone());
                }
            }
            int bodyPoses = 0;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).boneName().equals(plan.anatomy().body())) {
                    bodyPoses = Math.max(bodyPoses, seen.getOrDefault(i, java.util.Set.of()).size());
                }
            }
            helper.assertTrue(bodyPoses == 1, Component.literal("the control moved: the body display took "
                    + bodyPoses + " poses while walking, so a leg that 'moved' proves nothing"));
            helper.assertTrue(stepping == legs, Component.literal(stepping + " of " + legs
                    + " legs stepped over " + sampled[0] + " ticks; still:" + still));
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aHorseWhoseHindLegsAreLegBLAndLegBRWalksOnAllFour(GameTestHelper helper) {
        walksOnAllItsLegs(helper, "loco_walk_horse", horse(), 4);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aSpiderWalksOnAllEightLegs(GameTestHelper helper) {
        walksOnAllItsLegs(helper, "loco_walk_spider", bug(4), 8);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void anInsectWalksOnAllSixLegs(GameTestHelper helper) {
        walksOnAllItsLegs(helper, "loco_walk_ant", bug(3), 6);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void toldToStayItSitsAndAFreeOneKeepsStanding(GameTestHelper helper) {
        String id = plant(helper, "loco_sitter", true, "");
        Mob stays = spawn(helper, id, new BlockPos(1, 1, 2));
        Mob free = spawn(helper, id, new BlockPos(4, 1, 2));
        stays.setNoAi(true);
        free.setNoAi(true);
        // A HELD sit is steady (it was seen to twitch): once it is all the way down, every tick
        // after must stay all the way down. A sit that stands up a little for one tick and sits
        // again tips the whole body and swings the tail, eight ticks in every few.
        int[] held = {0};
        float[] lowest = {1f};
        StringBuilder trace = new StringBuilder();
        helper.onEachTick(() -> {
            stays.setOnGround(true);
            free.setOnGround(true);
            var s = VanillaRigAnimator.weightsOf(stays);
            if (s != null && (held[0] > 0 || s.sit() >= 1f)) {
                held[0]++;
                lowest[0] = Math.min(lowest[0], s.sit());
                if (trace.length() < 400) {
                    trace.append(String.format("%.2f ", s.sit()));
                }
            }
        });
        PetBehaviours.apply(stays, new PetBehaviour(null, null, null, null, 0, null), null);
        helper.succeedWhen(() -> {
            var s = VanillaRigAnimator.weightsOf(stays);
            var f = VanillaRigAnimator.weightsOf(free);
            helper.assertTrue(s != null && f != null, Component.literal("not driven yet"));
            helper.assertTrue(held[0] >= 100, Component.literal("told to stay, it has not sat (or not for long): "
                    + s.sit() + " (" + VanillaRigAnimator.layerOf(stays) + ")"));
            helper.assertTrue(lowest[0] >= 1f, Component.literal("a held sit dipped to " + lowest[0]
                    + " -- it twitches: " + trace));
            helper.assertTrue(f.sit() == 0f, Component.literal("nobody told it to stay and it sits: " + f.sit()));
        });
    }
}
