package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.EntityNbt;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * <b>A wished creature casts the shadow and shows the name a vanilla mob would.</b>
 * Both mechanisms were built and measured on Gate C (scoreboard S5, S3b) and switched on by nothing a child
 * meets: through the REAL birth path ({@code WishMobEntity} → the bridge → the animator's tick), a fresh
 * creature has a shadow on exactly one display and no plate (its species' own name is not one), a name he
 * gives it appears in a plate, and taking the name away takes the plate.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigLookGameTest {

    private static float shadowOf(Entity display) {
        CompoundTag t = EntityNbt.save(display);
        return t.getFloatOr("shadow_radius", 0f);
    }

    @GameTest(template = "empty_test", batch = "rig_look", timeoutTicks = 400)
    public static void aWishedCreatureCastsAShadowAndShowsTheNameHeGaveIt(GameTestHelper helper) {
        String id = SpeciesOnRigGameTest.plant(helper, "rig_look_shadow_name", "{\"name\":\"Charge\"}");
        SpeciesOnRigGameTest.wish(helper, id);
        int[] stage = {0};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            DisplayRig rig = c == null ? null : VanillaRigAnimator.rigOf(c);
            if (rig == null || rig.displays().isEmpty()) {
                return;
            }
            switch (stage[0]) {
                case 0 -> {
                    if (shadowOf(rig.displays().get(0)) <= 0f) {
                        return;   // the first tick of the animator has not run yet
                    }
                    float expect = DisplayRig.shadowRadiusFor(rig.living());
                    helper.assertTrue(Math.abs(shadowOf(rig.displays().get(0)) - expect) < 1e-4,
                            Component.literal("the shadow is " + shadowOf(rig.displays().get(0)) + ", not half its width " + expect));
                    for (int i = 1; i < rig.displays().size(); i++) {
                        helper.assertTrue(shadowOf(rig.displays().get(i)) == 0f,
                                Component.literal("display " + i + " casts a shadow too: N stacked shadows are a black disc"));
                    }
                    helper.assertTrue(rig.nameplate() == null,
                            Component.literal("a plate stands over a creature nobody named (its species' own name is not one)"));
                    c.setCustomName(Component.literal("Sparky"));   // what a name tag on it (or a proxy) does
                    stage[0] = 1;
                }
                case 1 -> {
                    Entity plate = rig.nameplate();
                    if (plate == null) {
                        return;
                    }
                    String text = EntityNbt.save(plate).toString();
                    helper.assertTrue(text.contains("Sparky"), Component.literal("the plate does not say Sparky: " + text));
                    c.setCustomName(null);
                    stage[0] = 2;
                }
                default -> {
                    if (rig.nameplate() == null) {
                        helper.succeed();
                    }
                }
            }
        });
    }
}
