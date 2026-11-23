package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>an earlier issue's hit boxes: a creature reshaped IN PLACE is as hittable as one spawned fresh.</b>
 *
 * <p>{@link DisplayRig#reshapeInPlace} keeps the carrier and re-plans everything a player hits:
 * the carrier's size, and the invisible hit proxies, whose COUNT may change (a wolf that grows
 * horns can need fewer or more). This holds it to an earlier issue's own instrument,
 * {@link HitProxyCensusGameTest#measure} — live display transforms against the live boxes of the
 * carrier and every proxy, turned through 0°, 22.5° and 45° — for three edits of every creature in
 * the census corpus: a SHAPE change (it becomes the next creature), and a resize up and down.
 *
 * <p>The oracle is a FRESH spawn of the same target where the reshaped one stands, so the claim is
 * exact rather than a threshold: same carrier type, same proxy count, the same four numbers. An
 * edit whose plan picks a different carrier is not reshaped in place at all (it respawns, which IS
 * a fresh spawn, and the census already covers that); those are counted, not compared. Writes
 * {@code hit-proxy-reshape.md} in the run directory: before, after and fresh, per edit.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HitProxyReshapeGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/HitProxyReshape");

    private static final BlockPos SPOT = new BlockPos(1, 2, 1);

    /** Float noise between two rigs built from the same plan; anything above it is a real difference. */
    static final double EPS = 0.005;

    static double[] turned(DisplayRig rig, Vec3 at) {
        double[] sum = new double[4];
        for (float yaw : new float[]{0f, 22.5f, 45f}) {
            RigDriver.follow(rig, at, yaw);
            double[] m = HitProxyCensusGameTest.measure(rig);
            for (int i = 0; i < 4; i++) {
                sum[i] += m[i] / 3;
            }
        }
        return sum;
    }

    record Edit(String kind, HitProxyCensusGameTest.Creature from, HitProxyCensusGameTest.Creature to,
                double toScale) {
    }

    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aCreatureReshapedInPlaceTakesHitsLikeOneSpawnedFresh(GameTestHelper helper)
            throws Exception {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        var level = helper.getLevel();
        List<HitProxyCensusGameTest.Creature> corpus = HitProxyCensusGameTest.corpus();
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            var a = corpus.get(i);
            edits.add(new Edit("shape", a, corpus.get((i + 1) % corpus.size()),
                    corpus.get((i + 1) % corpus.size()).scale()));
            edits.add(new Edit("grow 1.6x", a, a, a.scale() * 1.6));
            edits.add(new Edit("shrink 0.6x", a, a, a.scale() * 0.6));
        }
        StringBuilder md = new StringBuilder("| edit | from → to | carrier | proxies before → after "
                + "(fresh) | click hit before/after/fresh | click air | arrow hit | arrow air |\n"
                + "|---|---|---|---|---|---|---|---|\n");
        List<String> mismatches = new ArrayList<>();
        List<double[]> before = new ArrayList<>();
        List<double[]> after = new ArrayList<>();
        int inPlace = 0;
        int respawn = 0;
        int countChanged = 0;
        for (Edit e : edits) {
            String species = "gt_reshape_" + e.from().id();
            CarrierPool.Move moveA = CarrierPool.movementOf(e.from().powers(), e.from().parts(),
                    e.from().name());
            CarrierPool.Move moveB = CarrierPool.movementOf(e.to().powers(), e.to().parts(),
                    e.to().name());
            DisplayRig a = DisplayRig.spawn(level, at, 0f, e.from().parts(), e.from().scale(),
                    species, moveA);
            DisplayRig b = null;
            DisplayRig fresh = null;
            try {
                double[] mA = turned(a, at);
                int pA = a.proxies().size();
                String carrier = a.base().getType().toShortString();
                var uuid = a.base().getUUID();
                b = DisplayRig.reshapeInPlace(level, a, e.to().parts(), e.toScale(), species, moveB);
                if (b == null) {
                    respawn++;
                    md.append(String.format(Locale.ROOT, "| %s | %s → %s | %s → (respawn) | | | | | |\n",
                            e.kind(), e.from().id(), e.to().id(), carrier));
                    continue;
                }
                inPlace++;
                helper.assertTrue(b.base().getUUID().equals(uuid),
                        Component.literal("an in-place reshape changed the carrier: " + e));
                double[] mB = turned(b, at);
                int pB = b.proxies().size();
                // the oracle: the same target, spawned fresh where it stands
                b.discard();
                b = null;
                fresh = DisplayRig.spawn(level, at, 0f, e.to().parts(), e.toScale(), species, moveB);
                double[] mF = turned(fresh, at);
                int pF = fresh.proxies().size();
                before.add(mA);
                after.add(mB);
                if (pA != pB) {
                    countChanged++;
                }
                boolean same = pB == pF && fresh.base().getType().toShortString().equals(carrier);
                for (int i = 0; i < 4; i++) {
                    same &= Math.abs(mB[i] - mF[i]) <= EPS;
                }
                if (!same) {
                    mismatches.add(String.format(Locale.ROOT,
                            "%s %s→%s: proxies %d (fresh %d), carrier %s (fresh %s), "
                                    + "after %.3f/%.3f/%.3f/%.3f fresh %.3f/%.3f/%.3f/%.3f",
                            e.kind(), e.from().id(), e.to().id(), pB, pF, carrier,
                            fresh.base().getType().toShortString(), mB[0], mB[1], mB[2], mB[3],
                            mF[0], mF[1], mF[2], mF[3]));
                }
                md.append(String.format(Locale.ROOT,
                        "| %s | %s → %s | %s | %d → %d (%d) | %.2f / %.2f / %.2f | %.2f / %.2f / %.2f "
                                + "| %.2f / %.2f / %.2f | %.2f / %.2f / %.2f |\n",
                        e.kind(), e.from().id(), e.to().id(), carrier, pA, pB, pF,
                        mA[0], mB[0], mF[0], mA[1], mB[1], mF[1], mA[2], mB[2], mF[2],
                        mA[3], mB[3], mF[3]));
            } finally {
                a.discard();
                if (b != null) {
                    b.discard();
                }
                if (fresh != null) {
                    fresh.discard();
                }
            }
        }
        double[] medB = medians(before);
        double[] medA = medians(after);
        md.append(String.format(Locale.ROOT, "\n%d edits: %d in place, %d respawned (the plan picked "
                        + "another carrier); %d in-place edits changed the proxy count; %d differ from a "
                        + "fresh spawn\n\n| median | click hit | click air | arrow hit | arrow air |\n"
                        + "|---|---|---|---|---|\n| before | %.2f | %.2f | %.2f | %.2f |\n"
                        + "| after (in place) | %.2f | %.2f | %.2f | %.2f |\n"
                        + "| #838 target | ≥ 0.95 | ≤ 0.50 | ≥ 0.99 | ≤ 0.80 |\n",
                edits.size(), inPlace, respawn, countChanged, mismatches.size(),
                medB[0], medB[1], medB[2], medB[3], medA[0], medA[1], medA[2], medA[3]));
        for (String m : mismatches) {
            md.append("\n- ").append(m);
        }
        Files.writeString(Path.of("hit-proxy-reshape.md"), md.toString());
        LOG.info("hit proxy reshape:\n{}", md);
        helper.assertTrue(inPlace > edits.size() / 2,
                Component.literal("most edits should keep their carrier, but only " + inPlace + " of " + edits.size()
                        + " did -- the in-place path is not being exercised"));
        helper.assertTrue(countChanged > 0,
                Component.literal("no in-place edit changed its proxy count -- the re-plan of the proxies is not being "
                        + "exercised by this corpus"));
        helper.assertTrue(mismatches.isEmpty(),
                Component.literal(mismatches.size() + " in-place reshapes take hits differently from a fresh spawn of "
                        + "the same creature: " + mismatches.subList(0, Math.min(3, mismatches.size()))));
        helper.assertTrue(medA[0] >= 0.95, Component.literal("click hit median after reshape " + medA[0]));
        helper.assertTrue(medA[1] <= 0.50, Component.literal("click on air median after reshape " + medA[1]));
        helper.assertTrue(medA[2] >= 0.99, Component.literal("arrow hit median after reshape " + medA[2]));
        helper.assertTrue(medA[3] <= 0.80, Component.literal("arrow on air median after reshape " + medA[3]));
        helper.succeed();
    }

    static double[] medians(List<double[]> rows) {
        double[] med = new double[4];
        if (rows.isEmpty()) {
            return med;
        }
        for (int i = 0; i < 4; i++) {
            final int k = i;
            List<Double> col = new ArrayList<>(rows.stream().map(r -> r[k]).toList());
            col.sort(Double::compare);
            int n = col.size();
            med[i] = n % 2 == 1 ? col.get(n / 2) : (col.get(n / 2 - 1) + col.get(n / 2)) / 2;
        }
        return med;
    }
}
