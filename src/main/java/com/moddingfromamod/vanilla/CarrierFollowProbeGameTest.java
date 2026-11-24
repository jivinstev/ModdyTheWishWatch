package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.PetMoves;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Which carriers can FOLLOW?</b>. The first real grant after 4a put the starter
 * pet dragon on a turtle, which closed 12 blocks to 7.4 in ten seconds: following, at a crawl. So
 * each walking carrier is given a companion's follow at the pet dragon's speed, from one corner of
 * the plot to the child in the other, for {@value #TICKS} ticks, and the table is printed.
 *
 * <p>It is also the evidence for {@link CarrierPool#CANNOT_FOLLOW}. <b>The question is whether a
 * carrier WALKS to him</b>: comes within {@link PetMoves#FOLLOW_CLOSE} — where a follower stops —
 * at some tick, without a single hop. A carrier on the list must not do that in more than half its
 * trials; every carrier off it must do it in at least one.
 *
 * <p><b>Why not the distance it ENDED at.</b> Until 2026-11-11 the test asked that every
 * listed carrier END farther away than every follower. Since a follower that stops getting
 * nearer HOPS to him, so the frog — whose own brain still does not follow — arrived anyway, by a
 * hop, 2.2–3.6 blocks off: beside the followers, which stop at 3 and drift to 1–3.6. The two
 * ranges touched, and the comparison failed on about one full-tier run in thirty (and the same way
 * twice on the builder, 2.4 against 2.2). Measured 2026-11-11 over 12 trials each: the ten
 * followers walked to him 144/144 (nearest 1.0–2.2, never a hop); the turtle 0/12 (nearest 3.9:
 * it crawls); the frog 0/12 (11 hops in 12, and one trial it never moved). Two trials keep the
 * capability question robust — a follower that idles one trial still walks the other.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CarrierFollowProbeGameTest {

    static final int TICKS = 200;
    static final int TRIALS = 2;
    static final double SPEED = 0.35;   // the starter pet dragon's species speed
    /** A move further than this in ONE tick is a hop, not a step (a walker covers ~0.3 a tick at this speed). */
    static final double HOP_STEP = 1.5;

    @GameTest(template = "empty_test", batch = "carrier_follow_probe", timeoutTicks = 20 * 1200)
    public static void onlyTheCarriersThatCannotFollowAreKeptFromAPet(GameTestHelper helper) {
        // Height 2, as every rig test here (SpeciesOnRigGameTest.SPOT): at height 1 the 1.21.1 plot
        // left only the two short carriers (pig, armadillo) room to path, and the other ten stood
        // still -- which read as ten carriers that cannot follow.
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-follow-probe");
        var corner = helper.absolutePos(new BlockPos(7, 2, 7));
        kid.teleportTo(corner.getX() + 0.5, corner.getY(), corner.getZ() + 0.5);
        List<String> ids = CarrierPool.allowed(CarrierPool.Move.WALK, false);
        List<String> rows = new ArrayList<>();
        java.util.Map<String, Integer> walkedTo = new java.util.HashMap<>();
        GameTestSequence seq = helper.startSequence();
        Mob[] cur = new Mob[1];
        double[] start = new double[1];
        double[] last = new double[2];
        int[] hops = new int[1];
        double[] nearest = new double[1];
        // every hop the follow makes (PetBehaviours.follow: a stuck walker hops to him)
        helper.onEachTick(() -> {
            Mob m = cur[0];
            if (m == null || m.isRemoved()) {
                return;
            }
            if (Math.hypot(m.getX() - last[0], m.getZ() - last[1]) > HOP_STEP) {
                hops[0]++;
            }
            last[0] = m.getX();
            last[1] = m.getZ();
            nearest[0] = Math.min(nearest[0], m.distanceTo(kid));
        });
        java.util.Map<String, List<String>> trials = new java.util.LinkedHashMap<>();
        for (String id : ids) for (int trial = 0; trial < TRIALS; trial++) {
            seq.thenExecute(() -> {
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace(id));
                Mob m = (Mob) type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                var at = helper.absolutePos(new BlockPos(1, 2, 1));
                m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
                var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(SPEED);
                }
                helper.getLevel().addFreshEntity(m);
                PetBehaviours.apply(m, new PetBehaviour(true, true, null, null), kid.getUUID());
                last[0] = m.getX();
                last[1] = m.getZ();
                hops[0] = 0;
                nearest[0] = Double.MAX_VALUE;
                cur[0] = m;
                start[0] = m.distanceTo(kid);
            }).thenExecuteAfter(TICKS, () -> {
                Mob m = cur[0];
                double end = m.distanceTo(kid);
                boolean walked = hops[0] == 0 && nearest[0] < PetMoves.FOLLOW_CLOSE;
                trials.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(String.format("%4.1f->%4.1f (nearest %4.1f)%s", start[0], end, nearest[0],
                                hops[0] > 0 ? " hop" + hops[0] : (walked ? " ok  " : "     ")));
                walkedTo.merge(id, walked ? 1 : 0, Integer::sum);
                cur[0] = null;
                m.discard();
            });
        }
        seq.thenExecute(() -> trials.forEach((id, t) ->
                rows.add(String.format("%-11s %s   walked to him %d/%d", id, String.join("  ", t), walkedTo.get(id), TRIALS))));
        seq.thenExecute(() -> {
            System.out.println("CARRIER-FOLLOW-PROBE (" + TICKS + " ticks x " + TRIALS + " trials, speed " + SPEED
                    + "; walked to him = came within " + PetMoves.FOLLOW_CLOSE + " blocks with no hop):\n  "
                    + String.join("\n  ", rows));
            helper.assertTrue(rows.size() == ids.size(), Component.literal("not every carrier was measured"));
            List<String> wrong = new ArrayList<>();
            for (var e : walkedTo.entrySet()) {
                boolean listed = CarrierPool.CANNOT_FOLLOW.contains(e.getKey());
                if (listed ? e.getValue() * 2 > TRIALS : e.getValue() == 0) {
                    wrong.add(e.getKey() + (listed ? " (listed, but walked to him " : " (not listed, but never walked to him: ")
                            + e.getValue() + "/" + TRIALS + ")");
                }
            }
            helper.assertTrue(wrong.isEmpty(), Component.literal(
                    "CarrierPool.CANNOT_FOLLOW no longer matches what the carriers do: " + String.join(", ", wrong)));
        }).thenSucceed();
    }
}
