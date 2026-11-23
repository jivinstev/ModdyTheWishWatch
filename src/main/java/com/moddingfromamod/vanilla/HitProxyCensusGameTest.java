package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>an earlier issue's actuals: every committed creature spawned for real, and shot at the way Minecraft
 * shoots.</b>
 *
 * <p>The planner ({@link HitProxyPlan}) predicts how a rig will take clicks and arrows from its
 * own model of the rig. This measures the rig the server actually built instead, and nothing in
 * it comes from the plan: the creature is read back off its DISPLAY ENTITIES (each one's saved
 * {@code transformation}, turned by the display's own yaw and placed at its own position — what a
 * client draws), and the hit targets are the live entities' own {@code getBoundingBox()} — the
 * carrier and every proxy, exactly what a player's pick tests, and grown by
 * {@value HitProxyPlan#ARROW_MARGIN} for an arrow ({@code ProjectileUtil}). The body is turned to
 * 0°, 22.5° and 45° through {@link RigDriver#follow}, the method that moves a live rig every tick.
 *
 * <p>Writes {@code hit-proxy-census.md} in the server's run directory and asserts an earlier issue's targets
 * over the corpus: click hit median ≥ 0.95, click on air median ≤ 0.50, arrow hit median ≥ 0.99,
 * arrow on air median ≤ 0.80, and never more than {@value HitProxyPlan#MAX_PROXIES} proxies.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HitProxyCensusGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/HitProxyCensus");

    private static final BlockPos SPOT = new BlockPos(1, 2, 1);

    record Creature(String set, String id, double scale, List<String> powers,
                    List<CreatureSculpt.Part> parts, String name) {
    }

    /** The corpus (real + synthetic sculpts), plus a stand-in for every body plan. */
    static List<Creature> corpus() throws Exception {
        List<Creature> out = new ArrayList<>();
        for (String set : List.of("real", "synthetic")) {
            Path dir = SpikeFixture.find("src/test/resources/rig-fit/" + set);
            if (dir == null) {
                throw new IllegalStateException("the rig-fit corpus is not reachable from "
                        + Path.of("").toAbsolutePath() + " -- refusing to report a census of "
                        + "nothing");
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.sorted().toList()) {
                    try (Reader r = Files.newBufferedReader(f)) {
                        JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                        List<String> powers = new ArrayList<>();
                        if (o.has("powers") && o.get("powers").isJsonArray()) {
                            for (JsonElement e : o.getAsJsonArray("powers")) {
                                powers.add(e.getAsString());
                            }
                        }
                        String id = f.getFileName().toString().replace(".json", "");
                        out.add(new Creature(set, id,
                                o.has("scale") ? o.get("scale").getAsDouble() : 1.0, powers,
                                CreatureSculpt.parseParts(o.getAsJsonArray("parts")),
                                o.has("name") ? o.get("name").getAsString() : id));
                    }
                }
            }
        }
        for (Species.Body b : Species.Body.values()) {
            out.add(new Creature("standin", "standin-" + b.name().toLowerCase(Locale.ROOT), 1.0,
                    List.of(), StandInSculpt.forBody(b, 0xFFFFFF), b.name()));
        }
        return out;
    }

    /** One display, as a client draws it, relative to the carrier's feet. */
    static RigFit.Atom atomOf(Entity display, Vec3 feet) {
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(display);
        var tf = com.mojang.math.Transformation.EXTENDED_CODEC
                .parse(NbtOps.INSTANCE, tag.get("transformation")).result().orElseThrow();
        // DisplayRenderer, FIXED billboard: rotationYXZ(-yaw, pitch, 0), and a rig's displays
        // never pitch.
        Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(-display.getYRot()));
        Vec3 rel = display.position().subtract(feet);
        Vector3f t = new Vector3f(tf.getTranslation()).rotate(turn)
                .add((float) rel.x, (float) rel.y, (float) rel.z);
        Quaternionf l = new Quaternionf(turn).mul(tf.getLeftRotation());
        Vector3f s = new Vector3f(tf.getScale());
        Quaternionf r = new Quaternionf(tf.getRightRotation());
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int c = 0; c < 8; c++) {
            Vector3f m = new Vector3f((c & 1) == 0 ? -0.5f : 0.5f, (c & 2) == 0 ? -0.5f : 0.5f,
                    (c & 4) == 0 ? -0.5f : 0.5f).rotate(r).mul(s).rotate(l).add(t);
            lo[0] = Math.min(lo[0], m.x);
            lo[1] = Math.min(lo[1], m.y);
            lo[2] = Math.min(lo[2], m.z);
            hi[0] = Math.max(hi[0], m.x);
            hi[1] = Math.max(hi[1], m.y);
            hi[2] = Math.max(hi[2], m.z);
        }
        return new RigFit.Atom(t, l, s, r, new RigFit.Box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
    }

    static RigFit.Box boxOf(AABB b, Vec3 feet) {
        return new RigFit.Box(b.minX - feet.x, b.minY - feet.y, b.minZ - feet.z,
                b.maxX - feet.x, b.maxY - feet.y, b.maxZ - feet.z);
    }

    /** {click hit, click air, arrow hit, arrow air} of a live rig as it stands now. */
    static double[] measure(DisplayRig rig) {
        Vec3 feet = rig.base().position();
        List<RigFit.Atom> atoms = new ArrayList<>();
        for (Entity d : rig.displays()) {
            atoms.add(atomOf(d, feet));
        }
        List<RigFit.Box> targets = new ArrayList<>();
        targets.add(boxOf(rig.base().getBoundingBox(), feet));
        for (Mob m : rig.proxies()) {
            targets.add(boxOf(m.getBoundingBox(), feet));
        }
        HitProxyPlan.Score[] s = HitProxyPlan.scoreAsPlaced(atoms, targets);
        return new double[]{s[0].hit(), s[0].air(), s[1].hit(), s[1].air()};
    }

    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void everyCommittedCreatureTakesClicksAndArrowsLikeAVanillaMob(
            GameTestHelper helper) throws Exception {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        StringBuilder md = new StringBuilder("| set | creature | carrier | proxies | click hit | "
                + "click air | arrow hit | arrow air | plan said (click hit/air, arrow hit/air) "
                + "| spawn ms |\n|---|---|---|---|---|---|---|---|---|---|\n");
        List<double[]> rows = new ArrayList<>();
        Map<Integer, Integer> byK = new TreeMap<>();
        int entities = 0;
        List<Double> spawnMs = new ArrayList<>();
        for (Creature c : corpus()) {
            CarrierPool.Move move = CarrierPool.movementOf(c.powers(), c.parts(), c.name());
            long t0 = System.nanoTime();
            DisplayRig rig = DisplayRig.spawn(helper.getLevel(), at, 0f, c.parts(), c.scale(),
                    "gt_census_" + c.id(), move);
            double ms = (System.nanoTime() - t0) / 1e6;
            spawnMs.add(ms);
            try {
                double[] sum = new double[4];
                for (float yaw : new float[]{0f, 22.5f, 45f}) {
                    RigDriver.follow(rig, at, yaw);
                    double[] m = measure(rig);
                    for (int i = 0; i < 4; i++) {
                        sum[i] += m[i] / 3;
                    }
                }
                rows.add(sum);
                byK.merge(rig.proxies().size(), 1, Integer::sum);
                entities += rig.proxies().size();
                var p = rig.hitPlan();
                md.append(String.format(Locale.ROOT,
                        "| %s | %s | %s | %d | %.2f | %.2f | %.2f | %.2f | %.2f/%.2f, %.2f/%.2f "
                                + "| %.1f |\n",
                        c.set(), c.id(), rig.base().getType().toShortString(),
                        rig.proxies().size(), sum[0], sum[1], sum[2], sum[3],
                        p.click().hit(), p.click().air(), p.arrow().hit(), p.arrow().air(), ms));
            } finally {
                rig.discard();
            }
        }
        double[] med = new double[4];
        double[] worst = new double[4];
        for (int i = 0; i < 4; i++) {
            final int k = i;
            List<Double> col = new ArrayList<>(rows.stream().map(r -> r[k]).toList());
            col.sort(Double::compare);
            int n = col.size();
            med[i] = n % 2 == 1 ? col.get(n / 2) : (col.get(n / 2 - 1) + col.get(n / 2)) / 2;
            worst[i] = i % 2 == 0 ? col.get(0) : col.get(n - 1);
        }
        spawnMs.sort(Double::compare);
        md.append(String.format(Locale.ROOT, "\n%d creatures; proxies per creature %s, mean %.2f; "
                        + "spawn ms median %.1f, worst %.1f (the first of each shape plans; the "
                        + "plan is cached per shape)\n\n| | click hit | click air | arrow hit | "
                        + "arrow air |\n|---|---|---|---|---|\n| median | %.2f | %.2f | %.2f | %.2f |\n"
                        + "| worst | %.2f | %.2f | %.2f | %.2f |\n| #838 target (median) | ≥ 0.95 "
                        + "| ≤ 0.50 | ≥ 0.99 | ≤ 0.80 |\n",
                rows.size(), byK, (double) entities / rows.size(), spawnMs.get(spawnMs.size() / 2),
                spawnMs.get(spawnMs.size() - 1), med[0], med[1], med[2], med[3], worst[0],
                worst[1], worst[2], worst[3]));
        Files.writeString(Path.of("hit-proxy-census.md"), md.toString());
        LOG.info("hit proxy census:\n{}", md);
        helper.assertTrue(med[0] >= 0.95, Component.literal("click hit median " + med[0]));
        helper.assertTrue(med[1] <= 0.50, Component.literal("click on air median " + med[1]));
        helper.assertTrue(med[2] >= 0.99, Component.literal("arrow hit median " + med[2]));
        helper.assertTrue(med[3] <= 0.80, Component.literal("arrow on air median " + med[3]));
        helper.assertTrue(byK.keySet().stream().allMatch(k -> k <= HitProxyPlan.MAX_PROXIES),
                Component.literal("a creature was given more than " + HitProxyPlan.MAX_PROXIES + " proxies: " + byK));
        helper.succeed();
    }
}
