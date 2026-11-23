package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * Gate A: the wish-time proxy planner — its ray test agrees with the reference one, it
 * gives a compact creature nothing and a long one enough, and over every committed creature it
 * lands inside the vanilla bounds. Writes {@code build/rig-fit/proxy-plan.md}, the census.
 */
class HitProxyPlanTest {

    static List<RigFit.Atom> atoms(List<CreatureSculpt.Part> parts, double scale) {
        return RigFit.atoms(RigPlan.atoms(RigFit.intoRigSpace(parts)), scale);
    }

    static HitProxyPlan.Plan plan(List<CreatureSculpt.Part> parts, double scale,
                                  CarrierPool.Move move) {
        return HitProxyPlan.plan(atoms(parts, scale), CarrierPool.candidates(),
                CarrierPool.allowed(move, false), HitProxyPlan.proxyPool(),
                HitProxyPlan.Bounds.VANILLA);
    }

    /** The fast ray test is an optimisation of {@link RigFit.Atom#enter}; it must agree with it. */
    @Test
    void theFastRayTestAgreesWithTheReferenceOnEveryRay() throws Exception {
        var atoms = atoms(RigFitTest.fixture("real", "spikor-2be2fa60"), 2.2);
        Random rnd = new Random(838);
        int agree = 0;
        int hits = 0;
        for (int n = 0; n < 20000; n++) {
            double[] o = {rnd.nextGaussian() * 4, rnd.nextGaussian() * 4 + 2, rnd.nextGaussian() * 4};
            double[] d = {rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()};
            double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            d = new double[]{d[0] / len, d[1] / len, d[2] / len};
            for (RigFit.Atom a : atoms) {
                boolean ref = a.enter(o, d) >= 0;
                boolean fast = new HitProxyPlan.Fast(a).hits(o, d);
                if (ref == fast) {
                    agree++;
                }
                if (ref) {
                    hits++;
                }
            }
        }
        int total = 20000 * atoms.size();
        assertTrue(hits > 1000, "the rays must actually meet the creature: " + hits);
        // float rounding on a ray that grazes an edge may differ; nothing else may
        assertTrue(total - agree <= total / 10000, (total - agree) + " of " + total + " disagree");
    }

    @Test
    void aCreatureThatIsItsOwnBoxNeedsNoProxy() {
        var cube = List.of(RigFitTest.box("body", new float[]{-8, 0, -8}, new float[]{8, 16, 8}));
        HitProxyPlan.Plan p = plan(cube, 1.0, CarrierPool.Move.WALK);
        assertEquals(0, p.extraEntities(), p.toString());
        assertTrue(p.met(), p.toString());
    }

    @Test
    void theWhaleIsGivenProxiesAndThenBehavesLikeAVanillaMob() throws Exception {
        var whale = RigFitTest.fixture("synthetic", "whale_tail_origin");
        HitProxyPlan.Plan p = plan(whale, 1.5, CarrierPool.Move.SWIM);
        assertTrue(p.extraEntities() > 0, p.toString());
        assertTrue(p.met(), p.toString());
        for (HitProxyPlan.Proxy x : p.proxies()) {
            assertTrue(HitProxyPlan.proxyPool().contains(x.id()), x.toString());
        }
    }

    /** A turned body is scored too: a long creature measured only at 0° would flatter the plan. */
    @Test
    void aTurnedBodyIsScoredWithItsBoxesStillSquareToTheWorld() throws Exception {
        var atoms = atoms(RigFitTest.fixture("synthetic", "snake"), 1.0);
        RigFit.Box rig = RigFit.bounds(atoms);
        var rays = new HitProxyPlan.Rays(atoms, HitProxyPlan.grow(rig, 1));
        HitProxyPlan.Score[] straight = rays.scoreAt(0, List.of(rig));
        HitProxyPlan.Score[] turned = rays.scoreAt(2, List.of(rig));
        // its own bounds catch every aimed click at 0°; turned 45° the body leaves its box
        assertTrue(straight[0].hit() > 0.99, straight[0].toString());
        assertTrue(turned[0].hit() < 0.9, "a snake turned 45° leaves its box: " + turned[0]);
        assertTrue(turned[0].air() > straight[0].air(), straight[0] + " vs " + turned[0]);
    }

    record Census(String set, String id, CarrierPool.Move move, HitProxyPlan.Plan plan,
                  double ms) {
    }

    /** Vanilla's own mobs in their own boxes, scored by the planner's instrument. */
    static String vanilla() {
        Map<String, Object[]> v = new java.util.LinkedHashMap<>();
        v.put("zombie", new Object[]{StandInSculpt.forBody(
                com.moddingfromamod.species.Species.Body.HUMANOID, 0), 0.6, 1.95});
        v.put("pig", new Object[]{StandInSculpt.forBody(
                com.moddingfromamod.species.Species.Body.QUADRUPED, 0), 0.9, 0.9});
        v.put("cow", new Object[]{cow(), 0.9, 1.4});
        v.put("spider", new Object[]{StandInSculpt.forBody(
                com.moddingfromamod.species.Species.Body.SPIDER, 0), 1.4, 0.9});
        v.put("chicken", new Object[]{StandInSculpt.forBody(
                com.moddingfromamod.species.Species.Body.BIRD, 0), 0.4, 0.7});
        StringBuilder md = new StringBuilder("| vanilla mob | click hit | click air | arrow hit "
                + "| arrow air |\n|---|---|---|---|---|\n");
        for (var e : v.entrySet()) {
            @SuppressWarnings("unchecked")
            var parts = (List<CreatureSculpt.Part>) e.getValue()[0];
            var s = HitProxyPlan.score(atoms(parts, 1.0), List.of(RigFit.Box.carrier(
                    (Double) e.getValue()[1], (Double) e.getValue()[2])));
            md.append(String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.2f | %.2f |\n",
                    e.getKey(), s[0].hit(), s[0].air(), s[1].hit(), s[1].air()));
        }
        return md.toString();
    }

    /** Vanilla's cow, from CowModel's cuboids. */
    static List<CreatureSculpt.Part> cow() {
        return List.of(
                p("body", -6, 12, -9, 6, 22, 9), p("head", -4, 16, -15, 4, 24, -9),
                p("hornL", -5, 22, -13, -4, 25, -12), p("hornR", 4, 22, -13, 5, 25, -12),
                p("legFL", -6, 0, -7, -2, 12, -3), p("legFR", 2, 0, -7, 6, 12, -3),
                p("legBL", -6, 0, 5, -2, 12, 9), p("legBR", 2, 0, 5, 6, 12, 9));
    }

    static CreatureSculpt.Part p(String n, float x0, float y0, float z0, float x1, float y1,
                                 float z1) {
        return new CreatureSculpt.Part(n, null, new float[]{0, y0, 0}, new float[]{x0, y0, z0},
                new float[]{x1, y1, z1}, 0xFFFFFF);
    }

    @Test
    void theCorpusCensus() throws Exception {
        List<Census> rows = new ArrayList<>();
        // warm the JIT on one creature, so the first row's time is not the class loader's
        var c0 = RigFitCorpusTest.corpus().get(0);
        for (int i = 0; i < 3; i++) {
            plan(c0.parts(), c0.scale(), CarrierPool.Move.WALK);
        }
        for (var c : RigFitCorpusTest.corpus()) {
            var move = CarrierPool.movementOf(c.powers(), c.parts(), c.name());
            long t0 = System.nanoTime();
            HitProxyPlan.Plan p = plan(c.parts(), c.scale(), move);
            double ms = (System.nanoTime() - t0) / 1e6;
            rows.add(new Census(c.set(), c.id(), move, p, ms));
        }
        StringBuilder md = new StringBuilder(vanilla()).append("\n");
        md.append("| set | creature | move | carrier | proxies | click hit | click air | "
                + "arrow hit | arrow air | in bounds | plan ms |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Census r : rows) {
            var p = r.plan();
            md.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s x%.2f | %s | %.2f | %.2f | %.2f | %.2f | %s | %.1f |\n",
                    r.set(), r.id(), r.move(), p.core().id(), p.core().scale(),
                    p.proxies().isEmpty() ? "0" : p.proxies().size() + " ("
                            + String.join(", ", p.proxies().stream().map(HitProxyPlan.Proxy::id)
                            .toList()) + ")",
                    p.click().hit(), p.click().air(), p.arrow().hit(), p.arrow().air(),
                    p.met() ? "yes" : "**NO**", r.ms()));
        }
        Map<Integer, Integer> byK = new TreeMap<>();
        rows.forEach(r -> byK.merge(r.plan().extraEntities(), 1, Integer::sum));
        List<Double> ch = new ArrayList<>();
        List<Double> ca = new ArrayList<>();
        List<Double> ah = new ArrayList<>();
        List<Double> aa = new ArrayList<>();
        List<Double> ms = new ArrayList<>();
        for (Census r : rows) {
            ch.add(r.plan().click().hit());
            ca.add(r.plan().click().air());
            ah.add(r.plan().arrow().hit());
            aa.add(r.plan().arrow().air());
            ms.add(r.ms());
        }
        long met = rows.stream().filter(r -> r.plan().met()).count();
        md.append(String.format(Locale.ROOT, "\n**%d of %d in bounds.** proxies per creature: %s; "
                        + "mean %.2f\n\n"
                        + "| | click hit | click air | arrow hit | arrow air | plan ms |\n"
                        + "|---|---|---|---|---|---|\n"
                        + "| median | %.2f | %.2f | %.2f | %.2f | %.1f |\n"
                        + "| worst | %.2f | %.2f | %.2f | %.2f | %.1f |\n",
                met, rows.size(), byK,
                rows.stream().mapToInt(r -> r.plan().extraEntities()).average().orElse(0),
                median(ch), median(ca), median(ah), median(aa), median(ms),
                ch.stream().mapToDouble(x -> x).min().orElse(0),
                ca.stream().mapToDouble(x -> x).max().orElse(0),
                ah.stream().mapToDouble(x -> x).min().orElse(0),
                aa.stream().mapToDouble(x -> x).max().orElse(0),
                ms.stream().mapToDouble(x -> x).max().orElse(0)));
        Files.createDirectories(Path.of("build/rig-fit"));
        Files.writeString(Path.of("build/rig-fit/proxy-plan.md"), md.toString());
        System.out.println(md);
        // an earlier issue's targets, over the whole corpus
        assertTrue(median(ch) >= 0.95, "click hit median " + median(ch));
        assertTrue(median(ca) <= 0.50, "click air median " + median(ca));
        assertTrue(median(ah) >= 0.99, "arrow hit median " + median(ah));
        assertTrue(median(aa) <= 0.80, "arrow air median " + median(aa));
        assertTrue(rows.stream().allMatch(r -> r.plan().extraEntities() <= 6));
    }

    static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
