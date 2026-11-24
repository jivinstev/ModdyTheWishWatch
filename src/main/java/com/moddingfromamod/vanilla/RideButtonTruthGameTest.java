package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/**
 * <b>Bedrock's Ride button tells the truth</b>. Geyser draws its touch Ride button from the Java TYPE
 * of the hitbox under his finger — every horse, llama and camel ({@link HitProxyPlan#LOOKS_RIDEABLE}) — and a
 * player pressed it over a dragon nobody could ride (the plan was a bat carrier with a llama and
 * a camel among its proxies). So a creature he cannot ride never wears one of those, from a fresh plan or from
 * a plan saved before this rule. A rideable creature keeps them, untamed: Geyser offers an untamed horse-like the
 * ride to an empty hand, and a TAMED one "Saddle" to any other (measured, Gate BG ride_button_rideable_pocket).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RideButtonTruthGameTest {

    private static final double[] SCALES = {1.5, 3, 4.5, 6};

    @GameTest(template = "empty_test", batch = "ride_button_truth", timeoutTicks = 20 * 60)
    public static void onlyARideableCreatureLooksRideableOnBedrock(GameTestHelper helper) {
        List<String> ids = new ArrayList<>();
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            for (int i = 0; i < SCALES.length; i++) {
                for (boolean rides : new boolean[] {true, false}) {
                    String id = "rbt_" + (rides ? "ride_" : "pet_") + i;
                    SpeciesOnRigGameTest.plant(helper, id, "{\"powers\":[\"friendly\",\"flies\""
                            + (rides ? ",\"rideable\"" : "") + "],\"scale\":" + SCALES[i] + "}");
                    SpeciesOnRigGameTest.wishAt(helper.getLevel(),
                            helper.absolutePos(new BlockPos(1 + 6 * i, 2, rides ? 1 : 12)), id);
                    ids.add(id);
                }
            }
        });
        seq.thenWaitUntil(() -> {
            for (String id : ids) {
                Mob c = SpeciesOnRigGameTest.carrier(helper, id);
                helper.assertTrue(c != null && VanillaRigAnimator.rigOf(c) != null, Component.literal("no rig yet for " + id));
            }
        });
        seq.thenExecute(() -> {
            HitProxyPlan.Plan horsey = null;
            for (String id : ids) {
                Mob c = SpeciesOnRigGameTest.carrier(helper, id);
                DisplayRig rig = VanillaRigAnimator.rigOf(c);
                boolean rides = id.contains("_ride_");
                if (!rides) {
                    helper.assertFalse(HitProxyPlan.looksRideable(rig.hitPlan()), Component.literal(id
                            + " cannot be ridden and wears a hitbox Bedrock offers to ride: " + rig.hitPlan()));
                    for (Mob p : rig.proxies()) {
                        helper.assertFalse(p instanceof AbstractHorse, Component.literal(id + " has a " + p.getType() + " proxy"));
                    }
                } else {
                    for (Mob p : rig.proxies()) {
                        if (p instanceof AbstractHorse h) {
                            horsey = rig.hitPlan();
                            helper.assertFalse(h.isTamed(), Component.literal(id + "'s " + p.getType()
                                    + " proxy is tamed: Bedrock would offer 'Saddle', not Ride"));
                        }
                    }
                }
            }
            helper.assertTrue(horsey != null, Component.literal("no rideable size got a horse-like proxy, so this "
                    + "test proves nothing about one: pick other sizes"));
            // A plan saved on a carrier before this rule, handed to a creature he cannot ride (the adopt path).
            Mob pet = SpeciesOnRigGameTest.carrier(helper, ids.get(ids.size() - 1));
            DisplayRig rig = VanillaRigAnimator.rigOf(pet);
            // A server's marks for Bedrock's button are a Bedrock plug-in's, tested there.
            rig.takeProxies(helper.getLevel(), horsey, pet.yBodyRot);
            helper.assertFalse(HitProxyPlan.looksRideable(rig.hitPlan()),
                    Component.literal("a saved horse-like plan came back on a creature he cannot ride: " + rig.hitPlan()));
        });
        seq.thenSucceed();
    }
}
