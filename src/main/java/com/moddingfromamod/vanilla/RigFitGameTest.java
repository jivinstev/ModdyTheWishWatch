package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> in the world</b>: the pure instrument ({@code RigFitCorpusTest}) says where the carrier
 * should stand and which one it should be; these ask the live server whether that is what spawn
 * and a reload actually did.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RigFitGameTest {

    private static final BlockPos SPOT = new BlockPos(1, 1, 1);

    /** an earlier issue's video, as a sculpt: a long whale whose origin is at its tail. */
    public static List<CreatureSculpt.Part> whale() {
        return List.of(
                part("body", -8, 2, -56, 8, 18, 0),
                part("head", -7, 3, -72, 7, 16, -56),
                part("tail", -5, 6, 0, 5, 14, 12),
                part("fluke", -14, 8, 12, 14, 10, 20),
                part("finL", 8, 4, -44, 16, 6, -36),
                part("finR", -16, 4, -44, -8, 6, -36));
    }

    private static CreatureSculpt.Part part(String n, float x0, float y0, float z0,
                                            float x1, float y1, float z1) {
        return new CreatureSculpt.Part(n, null, new float[]{0, y0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, 0x3050E0);
    }

    /** Every table row is the size the registry says, and hostile exactly when the table says. */
    @GameTest(template = "empty_test")
    public static void theCarrierPoolTableMatchesTheLiveRegistry(GameTestHelper helper) {
        for (CarrierPool.Skeleton row : CarrierPool.ALL) {
            var t = BuiltInRegistries.ENTITY_TYPE.getValue(
                    ResourceLocation.withDefaultNamespace(row.id()));
            helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals(row.id()),
                    Component.literal(row.id() + " is not a registered entity type"));
            var d = t.getDimensions();
            helper.assertTrue(Math.abs(d.width() - row.width()) < 1e-3
                            && Math.abs(d.height() - row.height()) < 1e-3,
                    Component.literal(row.id() + ": table says " + row.width() + "x" + row.height()
                            + ", registry says " + d.width() + "x" + d.height()));
            Entity probe = t.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            boolean hostile = !(probe instanceof Mob)
                    || probe instanceof net.minecraft.world.entity.monster.Enemy;
            if (probe != null) {
                probe.discard();
            }
            helper.assertTrue(hostile == row.hostile(),
                    Component.literal(row.id() + ": table says hostile=" + row.hostile() + ", the live mob says "
                            + hostile));
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aWhaleAuthoredFromItsTailStandsOnItsMiddleOnLand(GameTestHelper helper) {
        whaleCase(helper, false);
    }

    @GameTest(template = "empty_test")
    public static void aWhaleAuthoredFromItsTailStandsOnItsMiddleInWater(GameTestHelper helper) {
        whaleCase(helper, true);
    }

    private static void whaleCase(GameTestHelper helper, boolean water) {
        if (water) {
            for (int x = 0; x <= 2; x++) {
                for (int y = 1; y <= 3; y++) {
                    for (int z = 0; z <= 2; z++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                    }
                }
            }
        }
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), at, 0f, whale(), 1.5,
                "gt_whale", CarrierPool.Move.SWIM);
        try {
            helper.assertTrue(rig.choice() != null, Component.literal("no carrier chosen"));
            List<String> allowed = CarrierPool.allowed(CarrierPool.Move.SWIM, water);
            helper.assertTrue(allowed.contains(rig.choice().id()),
                    Component.literal("a swimmer " + (water ? "in" : "out of") + " water got '" + rig.choice().id()
                            + "', allowed " + allowed));
            // The displays' OWN saved transforms, volume-weighted: their middle is the carrier.
            double w = 0;
            double x = 0;
            double z = 0;
            for (Entity d : rig.displays()) {
                CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
                var tf = com.mojang.math.Transformation.EXTENDED_CODEC
                        .parse(NbtOps.INSTANCE, tag.get("transformation")).result().orElseThrow();
                var s = tf.getScale();
                double v = Math.abs((double) s.x() * s.y() * s.z());
                w += v;
                x += v * tf.getTranslation().x();
                z += v * tf.getTranslation().z();
            }
            double off = Math.hypot(x / w, z / w);
            helper.assertTrue(off < 1.0 / 16, Component.literal("the whale's middle is " + off
                    + " blocks from its carrier; before #774 it was about 3"));
            double width = rig.living().getBbWidth();
            helper.assertTrue(Math.abs(width - rig.choice().actualWidth()) < 0.05,
                    Component.literal("the live hitbox is not the one chosen: " + width + " vs "
                            + rig.choice().actualWidth()));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>A rig saved is re-seated on load.</b> Its displays carry the OLD, uncentred
     * translations; the drivers only rewrite bones an animation moves, so without the re-seat an
     * idle body stays at the old offset while a swinging limb jumps to the new one.
     */
    @GameTest(template = "empty_test")
    public static void adoptReseatsADisplaySavedBeforeCentring(GameTestHelper helper) {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), at, 0f, whale(), 1.5,
                "gt_whale_adopt", CarrierPool.Move.SWIM);
        try {
            // What a pre-#774 save looks like: the plan at the sculpt's ORIGIN.
            List<RigPlan.Group> old = RigPlan.atoms(RigTransform.mirrorIntoWorldSpace(whale()));
            for (int i = 0; i < old.size(); i++) {
                DisplayRig.apply(rig.displays().get(i), RigTransform.placeAtom(old.get(i), 1.5), 0);
            }
            DisplayRig adopted = DisplayRig.adopt(helper.getLevel(), (Mob) rig.living(), whale(),
                    1.5);
            helper.assertTrue(adopted != null, Component.literal("the rig was not adopted"));
            for (int i = 0; i < adopted.groups().size(); i++) {
                CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(adopted.displays().get(i));
                var tf = com.mojang.math.Transformation.EXTENDED_CODEC
                        .parse(NbtOps.INSTANCE, tag.get("transformation")).result().orElseThrow();
                var want = RigTransform.placeAtom(adopted.groups().get(i), 1.5).translation();
                helper.assertTrue(tf.getTranslation().distance(want) < 1e-3,
                        Component.literal("display " + i + " kept its pre-#774 seat " + tf.getTranslation()
                                + " instead of " + want));
            }
            helper.succeed();
        } finally {
            rig.discard();
        }
    }
}
