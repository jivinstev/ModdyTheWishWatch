package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import org.junit.jupiter.api.Test;

/**
 * <b>an earlier issue's instrument run over every committed creature</b>, and the comparison that decided the
 * fix. Each creature is measured under several ways of placing and choosing its carrier, and
 * judged by {@link RigFit#rays}: of the rays a child aims at what they see, how many land on the
 * hitbox ({@code hit}), and of the clicks the hitbox catches, how many were on thin air
 * ({@code phantom}).
 *
 * <p>The table is written to {@code build/rig-fit/table.md}; the committed copy, with what each
 * row taught, is {@code docs/VANILLA_RIG_PARITY.md} §13. Deterministic: there is no noise floor,
 * and any change to a number here is a change to the code.
 */
class RigFitCorpusTest {

    /** The pool {@code DisplayRig.candidates} offered, with the live registry's sizes. */
    static final List<RigBase.Candidate> OLD_POOL = List.of(
            new RigBase.Candidate("iron_golem", 1.4f, 2.7f),
            new RigBase.Candidate("polar_bear", 1.4f, 1.4f),
            new RigBase.Candidate("cow", 0.9f, 1.4f),
            new RigBase.Candidate("panda", 1.3f, 1.25f),
            new RigBase.Candidate("goat", 0.9f, 1.3f),
            new RigBase.Candidate("camel", 1.7f, 2.375f),
            new RigBase.Candidate("sniffer", 1.9f, 1.75f),
            new RigBase.Candidate("turtle", 1.2f, 0.4f),
            new RigBase.Candidate("frog", 0.5f, 0.5f),
            new RigBase.Candidate("bee", 0.7f, 0.6f),
            new RigBase.Candidate("armadillo", 0.7f, 0.65f),
            new RigBase.Candidate("mooshroom", 0.9f, 1.4f),
            new RigBase.Candidate("horse", 1.3964844f, 1.6f),
            new RigBase.Candidate("hoglin", 1.3964844f, 1.4f, false));

    record Creature(String set, String id, double scale, List<String> powers,
                    List<CreatureSculpt.Part> parts, String name) {
    }

    enum Centre { ORIGIN, BOX, MASS }

    enum Objective { MAIN, BOX_IOU, BOX_F2, MASS_F1, MASS_F2 }

    record Variant(String label, Centre centre, Objective objective, boolean newPool) {
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("V0 main (before #774)", Centre.ORIGIN, Objective.MAIN, false),
            new Variant("V1 box-centred, main choice", Centre.BOX, Objective.MAIN, false),
            new Variant("V2 mass-centred, main choice", Centre.MASS, Objective.MAIN, false),
            new Variant("V3 mass, box IoU, old pool", Centre.MASS, Objective.BOX_IOU, false),
            new Variant("V4 mass, box F2, old pool", Centre.MASS, Objective.BOX_F2, false),
            new Variant("V5 mass, mass F1, old pool", Centre.MASS, Objective.MASS_F1, false),
            new Variant("V6 mass, mass F2, old pool", Centre.MASS, Objective.MASS_F2, false),
            new Variant("V7 box, box F2, new pool", Centre.BOX, Objective.BOX_F2, true),
            new Variant("V8 mass, box F2, new pool", Centre.MASS, Objective.BOX_F2, true),
            new Variant("V9 mass, mass F2, new pool", Centre.MASS, Objective.MASS_F2, true),
            new Variant("SHIPS (the production path)", Centre.MASS, Objective.MAIN, true));

    /** Which variant ships — {@code DisplayRig.spawn} must do exactly this. */
    static final int SHIPS = 10;

    /** The measured variant the production path must reproduce, number for number. */
    static final int MEASURED = 8;

    record Row(Creature c, Variant v, String carrier, CarrierPool.Move move, RigFit.Report r,
               double rayHit, double rayPhantom) {
    }

    static List<Creature> corpus() throws IOException {
        List<Creature> out = new ArrayList<>();
        for (String set : List.of("real", "synthetic")) {
            Path dir = Path.of("src/test/resources/rig-fit", set);
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
                        double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;
                        String id = f.getFileName().toString().replace(".json", "");
                        out.add(new Creature(set, id, scale, powers,
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

    static List<CreatureSculpt.Part> space(Creature c, Centre centre) {
        List<CreatureSculpt.Part> m = RigTransform.mirrorIntoWorldSpace(c.parts());
        return switch (centre) {
            case ORIGIN -> m;
            case BOX -> {
                float[] k = RigFit.footprintCentreUnits(m);
                yield RigFit.shift(m, -k[0], -k[1]);
            }
            case MASS -> {
                float[] k = RigFit.massCentreUnits(m);
                yield RigFit.shift(m, -k[0], -k[1]);
            }
        };
    }

    static double fBeta(double recall, double precision, double beta) {
        double b2 = beta * beta;
        double d = b2 * precision + recall;
        return d <= 0 ? 0 : (1 + b2) * precision * recall / d;
    }

    static ToDoubleFunction<RigFit.Box> objective(Objective o, RigFit.Box rig,
                                                   List<double[]> pts) {
        double cell = pts.get(pts.size() - 1)[0];
        int n = pts.size() - 1;
        return switch (o) {
            case BOX_IOU -> box -> box.iou(rig);
            case BOX_F2 -> box -> {
                double i = box.intersect(rig).volume();
                return fBeta(i / rig.volume(), box.volume() <= 0 ? 0 : i / box.volume(), 2);
            };
            default -> box -> {
                int in = 0;
                for (int k = 0; k < n; k++) {
                    double[] p = pts.get(k);
                    if (box.contains(p[0], p[1], p[2])) {
                        in++;
                    }
                }
                double recall = n == 0 ? 0 : (double) in / n;
                double precision = box.volume() <= 0 ? 0 : Math.min(1, in * cell / box.volume());
                return fBeta(recall, precision, o == Objective.MASS_F1 ? 1 : 2);
            };
        };
    }

    static Row measure(Creature c, Variant v) {
        List<RigFit.Atom> atoms = RigFit.atoms(RigPlan.atoms(space(c, v.centre())), c.scale());
        RigFit.Box rig = RigFit.bounds(atoms);
        CarrierPool.Move move = CarrierPool.movementOf(c.powers(), c.parts(), c.name());
        List<RigBase.Candidate> pool = v.newPool() ? CarrierPool.candidates() : OLD_POOL;
        List<String> allowed = v.newPool() ? CarrierPool.allowed(move, true) : null;
        RigBase.Choice pick;
        if (v.label().startsWith("SHIPS")) {
            // Exactly what DisplayRig.spawn does, through the same three calls.
            atoms = RigFit.atoms(RigPlan.atoms(RigFit.intoRigSpace(c.parts())), c.scale());
            rig = RigFit.bounds(atoms);
            pick = RigBase.chooseByFit(CarrierPool.candidates(), rig,
                    CarrierPool.allowed(move, true));
        } else if (v.objective() == Objective.MAIN) {
            var ext = CreatureAnatomy.extents(RigGeometry.of(c.parts()));
            pick = RigBase.choose(pool, CreatureAnatomy.widthBlocks(ext) * c.scale(),
                    CreatureAnatomy.heightBlocks(ext) * c.scale());
        } else {
            List<double[]> pts = v.objective().name().startsWith("MASS")
                    ? RigFit.samples(atoms, 1500) : List.of(new double[]{0});
            pick = RigBase.chooseBy(pool, allowed, rig, objective(v.objective(), rig, pts));
        }
        double w = pick == null ? 0 : pick.actualWidth();
        double h = pick == null ? 0 : pick.height();
        double[] rays = RigFit.rays(atoms, RigFit.Box.carrier(w, h));
        return new Row(c, v, pick == null ? "-" : pick.id(), move, RigFit.measure(atoms, w, h),
                rays[0], rays[1]);
    }

    static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n == 0 ? 0 : n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static double mean(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static double min(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    @Test
    void theCarrierStandsUnderTheCreatureAndFitsItBetterThanMainDid() throws IOException {
        List<Creature> corpus = corpus();
        List<List<Row>> byVariant = new ArrayList<>();
        for (Variant v : VARIANTS) {
            List<Row> rows = new ArrayList<>();
            for (Creature c : corpus) {
                rows.add(measure(c, v));
            }
            byVariant.add(rows);
        }

        StringBuilder md = new StringBuilder();
        md.append("| variant | set | n | ray hit (median) | ray hit (mean) | ray hit (min) | "
                + "phantom (median) | bbox IoU (median) | volume IoU (median) | "
                + "mass-centre offset (median / max, blocks) |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < VARIANTS.size(); i++) {
            for (String set : List.of("real", "synthetic", "standin", "ALL")) {
                List<Row> rs = byVariant.get(i).stream()
                        .filter(r -> set.equals("ALL") || r.c().set().equals(set)).toList();
                md.append(String.format(Locale.ROOT,
                        "| %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.2f / %.2f |\n",
                        VARIANTS.get(i).label(), set, rs.size(),
                        median(rs.stream().map(Row::rayHit).toList()),
                        mean(rs.stream().map(Row::rayHit).toList()),
                        min(rs.stream().map(Row::rayHit).toList()),
                        median(rs.stream().map(Row::rayPhantom).toList()),
                        median(rs.stream().map(r -> r.r().iou()).toList()),
                        median(rs.stream().map(r -> r.r().volumeIou()).toList()),
                        median(rs.stream().map(r -> r.r().massOffset()).toList()),
                        rs.stream().mapToDouble(r -> r.r().massOffset()).max().orElse(0)));
            }
        }
        md.append("\n| creature | set | move | before: carrier | hit | phantom | bbox IoU | "
                + "offset | after: carrier | hit | phantom | bbox IoU | w/l/h err |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<String> worse = new ArrayList<>();
        for (int k = 0; k < corpus.size(); k++) {
            Row a = byVariant.get(0).get(k);
            Row b = byVariant.get(SHIPS).get(k);
            md.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %s | %.2f | %.2f | %.2f | "
                            + "%+.0f%%/%+.0f%%/%+.0f%% |\n",
                    a.c().id(), a.c().set(), b.move(), a.carrier(), a.rayHit(), a.rayPhantom(),
                    a.r().iou(), a.r().massOffset(), b.carrier(), b.rayHit(), b.rayPhantom(),
                    b.r().iou(), 100 * b.r().widthErr(), 100 * b.r().lengthErr(),
                    100 * b.r().heightErr()));
            if (b.rayHit() < a.rayHit() - 0.02) {
                worse.add(String.format(Locale.ROOT, "%s hit %.2f -> %.2f (phantom %.2f -> %.2f)",
                        a.c().id(), a.rayHit(), b.rayHit(), a.rayPhantom(), b.rayPhantom()));
            }
        }
        md.append("\nRay hit worse by more than 0.02 after than before: ")
                .append(worse.isEmpty() ? "none" : String.join("; ", worse)).append('\n');
        Path out = Path.of("build/rig-fit/table.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);

        // --- the pins ---
        List<Row> before = byVariant.get(0);
        List<Row> after = byVariant.get(SHIPS);
        for (int k = 0; k < corpus.size(); k++) {
            Row m = byVariant.get(MEASURED).get(k);
            Row p = after.get(k);
            assertTrue(m.carrier().equals(p.carrier())
                            && Math.abs(m.rayHit() - p.rayHit()) < 1e-9,
                    p.c().id() + ": the production path must be the variant that was measured ("
                            + m.carrier() + " vs " + p.carrier() + ")");
            float[] mass = RigFit.massCentreUnits(RigFit.intoRigSpace(p.c().parts()));
            assertTrue(Math.hypot(mass[0], mass[1]) / RigPlan.UNITS_PER_BLOCK < 1.0 / 16,
                    p.c().id() + ": the carrier must stand under the creature's middle");
        }
        double iou0 = median(before.stream().map(r -> r.r().iou()).toList());
        double iou1 = median(after.stream().map(r -> r.r().iou()).toList());
        double ph0 = median(before.stream().map(Row::rayPhantom).toList());
        double ph1 = median(after.stream().map(Row::rayPhantom).toList());
        double hit0 = mean(before.stream().map(Row::rayHit).toList());
        double hit1 = mean(after.stream().map(Row::rayHit).toList());
        assertTrue(iou1 >= iou0 + 0.10, "median bbox IoU must rise by 0.10: " + iou0 + " -> " + iou1);
        assertTrue(ph1 < ph0, "fewer clicks on thin air: " + ph0 + " -> " + ph1);
        assertTrue(hit1 >= hit0, "aimed hits must not fall on average: " + hit0 + " -> " + hit1);
        Row whale = after.stream().filter(r -> r.c().id().equals("whale_tail_origin"))
                .findFirst().orElseThrow();
        assertTrue(whale.rayHit() >= 0.9, "#774's whale: aim at it and hit it, got "
                + whale.rayHit());
    }
}
