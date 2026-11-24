package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Which carriers can a child RIDE?</b> (2026-10-11). A rideable creature is a {@code rideable}
 * pet behaviour on its invisible carrier, so whether he can ride it is a question about the
 * carrier's own vanilla mob: a horse or a llama bucks an untamed rider off, a brain-driven mob may
 * ignore the steering. So each walking carrier is made rideable, he climbs on with an empty hand
 * through the real interaction event, holds W facing south for {@value #TICKS} ticks, and the table
 * of how far it carried him (0 if he was thrown off or never got on) is printed.
 *
 * <p>It is the evidence for {@link CarrierPool#CANNOT_RIDE}. Unlike following, riding is judged on
 * the WORST trial: a carrier that throws him off once in three is one he cannot rely on. Measured
 * 2026-10-11: every walking carrier carried him ~7 blocks in 3 s; the horse threw him off 1 trial in
 * 3 and the llama 2 in 3 (untamed, they buck). The bat is measured too and printed, never asserted:
 * riding a flier is post-v1 (vehicles), and the row is its evidence (it carried him 7.3 blocks, level).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CarrierRideProbeGameTest {

    static final int TICKS = 60;
    static final int TRIALS = 3;
    static final double SPEED = 0.3;   // a big ridden creature's species speed (the live T-Rex)

    @GameTest(template = "empty_test", batch = "carrier_ride_probe", timeoutTicks = 20 * 600)
    public static void onlyTheCarriersThatCannotBeRiddenAreKeptFromARideableCreature(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-ride-probe");
        List<String> ids = new ArrayList<>(CarrierPool.allowed(CarrierPool.Move.WALK, false));
        ids.add("bat");
        java.util.Map<String, Double> best = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> worst = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<String>> trials = new java.util.LinkedHashMap<>();
        GameTestSequence seq = helper.startSequence();
        Mob[] cur = new Mob[1];
        double[] z0 = new double[1];
        for (String id : ids) for (int trial = 0; trial < TRIALS; trial++) {
            seq.thenExecute(() -> {
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
                Mob m = (Mob) type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                var at = helper.absolutePos(new BlockPos(4, 2, 1));
                m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
                var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(SPEED);
                }
                helper.getLevel().addFreshEntity(m);
                PetBehaviours.apply(m, new PetBehaviour(null, true, true, null), kid.getUUID());
                kid.stopRiding();
                kid.getInventory().clearContent();
                kid.teleportTo(m.getX(), m.getY(), m.getZ() - 1);
                NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(kid, InteractionHand.MAIN_HAND, m));
                kid.setYRot(0f);
                kid.setXRot(0f);
                cur[0] = m;
                z0[0] = m.getZ();
            });
            for (int t = 0; t < TICKS; t += 5) {
                seq.thenExecuteAfter(5, () -> RiderInput.hold(kid, 1f, 0f));
            }
            seq.thenExecute(() -> {
                Mob m = cur[0];
                boolean on = kid.getVehicle() == m;
                double went = on ? m.getZ() - z0[0] : 0;
                trials.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(String.format("%s %5.1f", on ? "on " : "OFF", m.getZ() - z0[0]));
                best.merge(id, went, Math::max);
                worst.merge(id, went, Math::min);
                kid.stopRiding();
                m.discard();
            });
        }
        seq.thenExecute(() -> {
            List<String> rows = new ArrayList<>();
            trials.forEach((id, t) -> rows.add(String.format("%-11s %s   best %5.1f%s", id,
                    String.join("  ", t), best.get(id), CarrierPool.CANNOT_RIDE.contains(id) ? "  (CANNOT_RIDE)" : "")));
            System.out.println("CARRIER-RIDE-PROBE (" + TICKS + " ticks x " + TRIALS + " trials, speed " + SPEED
                    + ", holding W, blocks carried; OFF = thrown off or never on):\n  " + String.join("\n  ", rows));
            // The SAFE direction only: a carrier a rideable creature may be given must never throw him
            // off and must carry him. Bucking is random, so a CANNOT_RIDE carrier that kept him on
            // through three trials is luck, not a finding -- its row is printed, never asserted.
            List<String> bad = new ArrayList<>();
            trials.forEach((id, t) -> {
                if (id.equals("bat") || CarrierPool.CANNOT_RIDE.contains(id)) {
                    return;
                }
                if (t.stream().anyMatch(r -> r.startsWith("OFF")) || worst.get(id) < 1.0) {
                    bad.add(id + " " + t);
                }
            });
            helper.assertTrue(bad.isEmpty(), Component.literal("a carrier a rideable creature may be given threw him off "
                    + "or did not carry him (add it to CarrierPool.CANNOT_RIDE): " + bad));
        }).thenSucceed();
    }
}
