package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Can a child FLY on a rideable creature?</b> A flying creature rides a bat carrier (the only FLY carrier a creature is given), so the
 * question is about the bat. He climbs on with an empty hand, then four phases of {@value #PHASE}
 * ticks: look UP 45° holding W, look DOWN 45° holding W, look LEVEL holding W, and LET GO. Each
 * phase prints how far it rose and went and whether he is still on, then how high he was when he
 * got off — a flier that drops him from a height is worse than one that never left the ground. (His
 * fall is simulated by his client, which a mock player has not got, so the damage is not measured.)
 *
 * <p>Two arms: the bat as a rig gives it today, and the same bat with gravity off — which is what
 * makes {@code PetBehaviours.steer} treat a mount as 3-D (a bat is neither a {@code FlyingMob} nor
 * on a flying path navigation). Printed, never asserted: it is the evidence for whether riding a
 * flier is a small change or vehicles territory.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CarrierFlyRideProbeGameTest {

    static final int PHASE = 40;
    static final double SPEED = 0.3;

    private record Phase(String name, float yaw, float pitch, float forward) {}

    private static final List<Phase> PHASES = List.of(
            new Phase("up", 0f, -45f, 1f), new Phase("down", 180f, 45f, 1f),
            new Phase("up again", 0f, -45f, 1f), new Phase("level", 180f, 0f, 1f),
            new Phase("let go", 180f, 0f, 0f));

    @GameTest(template = "empty_test", batch = "carrier_fly_ride_probe", timeoutTicks = 20 * 120)
    public static void howABatCarrierFliesWithARider(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-fly-probe");
        List<String> rows = new ArrayList<>();
        GameTestSequence seq = helper.startSequence();
        Mob[] cur = new Mob[1];
        double[] at = new double[3];
        for (boolean noGravity : new boolean[] {false, true}) {
            String arm = noGravity ? "bat, gravity off" : "bat, as rigged";
            seq.thenExecute(() -> {
                Mob m = EntityTypes.BAT.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                var p = helper.absolutePos(new BlockPos(4, 2, 1));
                m.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0f, 0f);
                var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(SPEED);
                }
                m.setNoGravity(noGravity);
                helper.getLevel().addFreshEntity(m);
                PetBehaviours.apply(m, new PetBehaviour(null, true, true, null), kid.getUUID());
                kid.stopRiding();
                kid.setHealth(kid.getMaxHealth());
                kid.fallDistance = 0;
                kid.getInventory().clearContent();
                kid.teleportTo(m.getX(), m.getY(), m.getZ() - 1);
                NeoForge.EVENT_BUS.post(com.moddingfromamod.mv.Interact.event(kid, InteractionHand.MAIN_HAND, m));
                cur[0] = m;
                rows.add(arm + ": mounted=" + (kid.getVehicle() == m) + " start y=" + String.format("%.1f", m.getY()));
            });
            for (Phase ph : PHASES) {
                seq.thenExecute(() -> {
                    at[0] = cur[0].getX();
                    at[1] = cur[0].getY();
                    at[2] = cur[0].getZ();
                });
                for (int t = 0; t < PHASE; t += 5) {
                    seq.thenExecuteAfter(5, () -> {
                        kid.setYRot(ph.yaw());
                        kid.setXRot(ph.pitch());
                        RiderInput.hold(kid, ph.forward(), 0f);
                    });
                }
                seq.thenExecute(() -> {
                    Mob m = cur[0];
                    rows.add(String.format("  %-7s on=%-5s rose %5.1f  went %5.1f  y=%5.1f  rider hp %.0f",
                            ph.name(), kid.getVehicle() == m, m.getY() - at[1],
                            Math.hypot(m.getX() - at[0], m.getZ() - at[2]), m.getY(), kid.getHealth()));
                });
            }
            // Off at the end, wherever it left him: does he fall, and does it hurt?
            seq.thenExecute(() -> {
                double h = kid.getY() - helper.absolutePos(new BlockPos(0, 1, 0)).getY();
                kid.stopRiding();
                rows.add(String.format("  dismount %.1f blocks above the floor", h));
            });
            // A player's own fall is simulated by his CLIENT, which a mock player has not got, so
            // whether that height hurts him is not measurable here; the height is the finding.
            seq.thenExecute(() -> {
                cur[0].discard();
            });
        }
        seq.thenExecute(() -> System.out.println("CARRIER-FLY-RIDE-PROBE (" + PHASE
                + " ticks a phase, speed " + SPEED + "):\n  " + String.join("\n  ", rows))).thenSucceed();
    }
}
