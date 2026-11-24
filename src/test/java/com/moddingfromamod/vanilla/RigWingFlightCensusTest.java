package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;
import com.moddingfromamod.runtime.SculptOrientation;
import com.moddingfromamod.species.Species;
import org.junit.jupiter.api.Test;

/**
 * <b>Wings push the air down; they do not slice through it</b> — a census over every winged creature
 * this repository carries.
 *
 * <p>The swept, bat, dragon and butterfly wing kits (and many hand-sculpted wings) build their panels
 * UPRIGHT: thin front to back. The beat turns a wing about the body's long axis (Z), which is right
 * for a flat wing and wrong for an upright one: it swings within its own plane, edge first. So in
 * flight an upright wing is first laid flat about its attachment edge ({@link RigAnatomy.Wing#upright},
 * {@link RigLocomotion#LAY_FLAT_DEGREES}).
 *
 * <p><b>The measure.</b> A panel's <i>slice angle</i> is the angle between its face and horizontal
 * once the beat's own roll about the body axis is taken out: {@code asin|n·Z|} for the drawn face
 * normal {@code n}. 0° is a panel that meets its stroke face on (it pushes air); 90° is one that meets
 * it edge first (it slices). The beat cannot change it — a roll about Z leaves {@code n·Z} alone — so
 * it measures the wing's shape in flight, not where it is in its beat. The plain angle from
 * horizontal ({@code acos|n·Y|}) is in the printed table too; it includes the beat's own roll, up to
 * {@link RigLocomotion#FLAP_LIFT} + {@link RigLocomotion#FLAP_DEGREES} and a little more on outer
 * panels, as any flapping wing must.
 *
 * <p><b>The census</b> is found, not listed: every sculpt under the starter library, the Builder's
 * bodies, the rig-fit fixtures, the tier-experiment corpus, and every stand-in body, run through the
 * rig's own reading ({@link RigAnatomy#of}). A new winged creature is in it the day it lands.
 *
 * <p>Every "before" is the same anatomy with no wing read as upright
 * ({@link RigAnatomy#withoutUprightWings}), which is today's flight code path exactly.
 */
class RigWingFlightCensusTest {

    /** The slice angle no upright wing's panel may exceed at any point of the beat. */
    static final double MAX_SLICE_DEGREES = 50;

    record Flier(String source, String id, RigLocomotion.Plan plan) {
        RigAnatomy anatomy() {
            return plan.anatomy();
        }

        RigLocomotion.Plan before() {
            return RigLocomotion.plan(plan.anatomy().withoutUprightWings());
        }

        @Override
        public String toString() {
            return source + "/" + id;
        }
    }

    // ------------------------------------------------------------------- the census

    static List<Flier> census() throws IOException {
        List<Flier> out = new ArrayList<>();
        for (String root : List.of("src/main/resources/starter-library/species",
                "src/main/resources/builder-bodies/species", "src/test/resources/rig-fit",
                SpikeFixture.CORPUS_DIR)) {
            Path dir = SpikeFixture.find(root);
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(f -> f.toString().endsWith(".json")).sorted().toList();
            }
            for (Path f : files) {
                JsonObject o;
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    JsonElement e = JsonParser.parseReader(r);
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    o = e.getAsJsonObject();
                }
                if (!o.has("parts") || !o.get("parts").isJsonArray()) {
                    continue;
                }
                List<CreatureSculpt.Part> parts =
                        new ArrayList<>(CreatureSculpt.parseParts(o.getAsJsonArray("parts")));
                if (o.has("kits") && o.get("kits").isJsonArray()) {
                    parts.addAll(SculptKits.expand(o.getAsJsonArray("kits"), parts));
                }
                String id = dir.relativize(f).toString().replace(".json", "")
                        .replace("/sculpt", "");
                add(out, root.substring(root.lastIndexOf('/') + 1), id, parts);
            }
        }
        // Every wing kit, every size, on one plain body: the kits are how most wings are made,
        // and not every style is on a sculpt in the repository.
        for (String style : List.of("swept", "feather", "bat", "dragon", "butterfly")) {
            for (String size : List.of("S", "M", "L", "XL")) {
                List<CreatureSculpt.Part> parts = new ArrayList<>(List.of(
                        part("body", null, -4, 6, -7, 4, 14, 7),
                        part("head", null, -3, 11, -12, 3, 17, -7),
                        part("legL", null, -3, 0, -5, -1, 6, -3),
                        part("legR", null, 1, 0, -5, 3, 6, -3),
                        part("legL2", null, -3, 0, 3, -1, 6, 5),
                        part("legR2", null, 1, 0, 3, 3, 6, 5)));
                com.google.gson.JsonArray kits = JsonParser.parseString(
                        "[{\"kit\":\"wing\",\"style\":\"" + style + "\",\"on\":\"body\","
                                + "\"size\":\"" + size + "\"}]").getAsJsonArray();
                parts.addAll(SculptKits.expand(kits, parts));
                add(out, "kit", style + "-" + size, parts);
            }
        }
        for (Species.Body b : Species.Body.values()) {
            add(out, "standin", b.name().toLowerCase(Locale.ROOT),
                    StandInSculpt.forBody(b, 0xFFFFFF));
        }
        return out;
    }

    private static void add(List<Flier> out, String source, String id,
                            List<CreatureSculpt.Part> parts) {
        if (parts.isEmpty()) {
            return;
        }
        RigAnatomy a = RigAnatomy.of(RigFit.intoRigSpace(SculptOrientation.faceForward(parts)));
        if (!a.wings().isEmpty()) {
            out.add(new Flier(source, id, RigLocomotion.plan(a)));
        }
    }

    private static RigLocomotion.Weights fly(float fly) {
        return new RigLocomotion.Weights(fly, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------- measuring

    /** Every box of a wing side (its panels and what hangs from them). */
    static Set<String> sideBoxes(RigAnatomy a, int side) {
        Set<String> out = new LinkedHashSet<>();
        for (RigAnatomy.Wing w : a.wings()) {
            if (w.side() == side) {
                out.addAll(RigLocomotion.subtree(a.parts(), w.bone()));
            }
        }
        return out;
    }

    static Set<String> allWingBoxes(RigAnatomy a) {
        Set<String> out = new LinkedHashSet<>(sideBoxes(a, -1));
        out.addAll(sideBoxes(a, 1));
        return out;
    }

    static boolean upright(RigAnatomy a, int side) {
        return a.wings().stream().anyMatch(w -> w.side() == side && w.upright());
    }

    /** The worst slice angle and the plain tilt range, over one whole beat, of a side's plates. */
    record Beat(double worstSlice, double minTilt, double maxTilt) {
    }

    static Beat beat(RigLocomotion.Plan p, int side) {
        Set<String> boxes = sideBoxes(p.anatomy(), side);
        Map<String, float[][]> rest = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null);
        // Each box is followed by its own edge that looked most along the body at rest: the edge
        // that is the panel's thickness on an upright wing, whatever the box's proportions (a
        // small kit wing's panels are stubby blocks, not plates, one by one).
        Map<String, Integer> along = new LinkedHashMap<>();
        for (String b : boxes) {
            float[][] c = rest.get(b);
            int best = 0;
            double bestZ = -1;
            for (int k = 0; k < 3; k++) {
                float[] e = edge(c, k);
                double z = Math.abs(e[2]) / Math.max(1e-6, len(e));
                if (z > bestZ) {
                    bestZ = z;
                    best = k;
                }
            }
            along.put(b, best);
        }
        double worst = 0;
        double lo = 180;
        double hi = 0;
        for (int t = 0; t < RigLocomotion.FLAP_TICKS; t++) {
            RigLocomotion.Out o = RigLocomotion.pose(p, Map.of(), fly(1f), t);
            Map<String, float[][]> c = RigLocomotion.corners(p.anatomy().parts(), o.pose(),
                    o.root());
            for (String b : boxes) {
                float[] e = edge(c.get(b), along.get(b));
                double l = Math.max(1e-6, len(e));
                worst = Math.max(worst, Math.toDegrees(Math.asin(Math.min(1, Math.abs(e[2]) / l))));
                double tilt = Math.toDegrees(Math.acos(Math.min(1, Math.abs(e[1]) / l)));
                lo = Math.min(lo, tilt);
                hi = Math.max(hi, tilt);
            }
        }
        return new Beat(worst, lo, hi);
    }

    private static float[] edge(float[][] c, int k) {
        float[] far = c[1 << k];
        return new float[]{far[0] - c[0][0], far[1] - c[0][1], far[2] - c[0][2]};
    }

    private static double len(float[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static void assertSamePose(Map<String, BedrockAnimation.BonePose> want,
                               Map<String, BedrockAnimation.BonePose> got, Set<String> bones,
                               String what) {
        for (String b : bones) {
            BedrockAnimation.BonePose x = want.get(b);
            BedrockAnimation.BonePose y = got.get(b);
            if (x == null || y == null) {
                assertTrue(x == null && y == null, what + ": " + b + " posed on one side only");
                continue;
            }
            assertTrue(Arrays.equals(x.rotation(), y.rotation())
                            && Arrays.equals(x.position(), y.position())
                            && Arrays.equals(x.scale(), y.scale()),
                    what + ": " + b + " moved " + Arrays.toString(x.rotation()) + " -> "
                            + Arrays.toString(y.rotation()));
        }
    }

    static void assertSameRoot(RigPose.Chain a, RigPose.Chain b, String what) {
        assertEquals(a == null, b == null, what);
        if (a != null) {
            assertTrue(Arrays.equals(a.rotationXyzw(), b.rotationXyzw())
                    && Arrays.equals(a.translation(), b.translation()), what);
        }
    }

    static Set<String> union(Map<String, ?> a, Map<String, ?> b) {
        Set<String> s = new LinkedHashSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    // ------------------------------------------------------------------- the census, asserted

    @Test
    void theCensusIsBigEnoughToMeanSomethingAndHasTheKnownUprightFliers() throws IOException {
        List<Flier> all = census();
        StringBuilder table = new StringBuilder(
                "| creature | side | upright | slice before | slice after | tilt before | tilt after |\n"
                        + "|---|---|---|---|---|---|---|\n");
        int uprightSides = 0;
        for (Flier f : all) {
            for (int side : new int[]{-1, 1}) {
                if (sideBoxes(f.anatomy(), side).isEmpty()) {
                    continue;
                }
                boolean up = upright(f.anatomy(), side);
                uprightSides += up ? 1 : 0;
                if (!up) {
                    table.append(String.format(Locale.ROOT, "| %s | %s | no | — | unchanged | — | — |%n",
                            f, side < 0 ? "L" : "R"));
                    continue;
                }
                Beat was = beat(f.before(), side);
                Beat now = beat(f.plan(), side);
                table.append(String.format(Locale.ROOT,
                        "| %s | %s | yes | %.0f° | %.0f° | %.0f–%.0f° | %.0f–%.0f° |%n", f,
                        side < 0 ? "L" : "R", was.worstSlice(),
                        now.worstSlice(), was.minTilt(), was.maxTilt(), now.minTilt(),
                        now.maxTilt()));
            }
        }
        try {
            Path out = Path.of("build/rig-wings/census.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, table.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // the table is a report, never the test
        }
        System.out.println(table);
        assertTrue(all.size() >= 10, "a census of " + all.size() + " winged creatures is too small: "
                + all);
        for (String flat : List.of("chicken", "duck", "eagle", "plane", "bird_flier",
                "butterfly", "feather-S", "feather-M", "feather-L", "feather-XL")) {
            Flier f = all.stream().filter(x -> x.id().endsWith(flat)).findFirst()
                    .orElseThrow(() -> new AssertionError(flat + " is not in the census: " + all));
            assertFalse(upright(f.anatomy(), -1) || upright(f.anatomy(), 1),
                    flat + "'s wings are slabs, rods or flat, never upright: "
                            + f.anatomy().wings());
        }
        assertTrue(uprightSides >= 8, "only " + uprightSides + " upright wing sides: " + all);
        for (String known : List.of("pet_dragon", "griffin", "phoenix", "owl", "swept-M",
                "bat-M", "dragon-M", "butterfly-M")) {
            Flier f = all.stream().filter(x -> x.id().equals(known)).findFirst()
                    .orElseThrow(() -> new AssertionError(known + " is not in the census: " + all));
            assertTrue(upright(f.anatomy(), -1) && upright(f.anatomy(), 1),
                    known + "'s wings stand on edge and must be read as upright: "
                            + f.anatomy().wings());
        }
    }

    @Test
    void inTheAirEveryUprightWingMeetsItsStrokeFaceOn() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Flier f : census()) {
            for (int side : new int[]{-1, 1}) {
                if (!upright(f.anatomy(), side)) {
                    continue;
                }
                Beat b = beat(f.plan(), side);
                if (b.worstSlice() > MAX_SLICE_DEGREES) {
                    bad.add(f + " " + side + ": " + Math.round(b.worstSlice()) + "°");
                }
            }
        }
        assertTrue(bad.isEmpty(), "these wings still slice the air: " + bad);
    }

    /** The control: the same measure on today's flight path fails, so the measure can fail. */
    @Test
    void controlBeforeTheFixEveryUprightWingSlices() throws IOException {
        int sides = 0;
        for (Flier f : census()) {
            for (int side : new int[]{-1, 1}) {
                if (!upright(f.anatomy(), side)) {
                    continue;
                }
                sides++;
                Beat b = beat(f.before(), side);
                assertTrue(b.worstSlice() > MAX_SLICE_DEGREES,
                        f + " side " + side + " did not slice before the fix ("
                                + Math.round(b.worstSlice()) + "°), so the census proves nothing");
            }
        }
        assertTrue(sides > 0);
    }

    @Test
    void onTheGroundNothingChangesByteForByte() throws IOException {
        float[][] grounded = {{0, 0, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0},
                {0, 0, 0, 0, 1}, {0, 1, 0, 0, 0}, {0, 0.4f, 0.5f, 0, 0}};
        for (Flier f : census()) {
            RigLocomotion.Plan now = f.plan();
            RigLocomotion.Plan was = f.before();
            for (float[] g : grounded) {
                RigLocomotion.Weights w = new RigLocomotion.Weights(g[0], g[1], g[2], g[3], g[4],
                        0, 0, 0, 0, 0.5f);
                for (int t = 0; t < RigLocomotion.REST_WING_TICKS; t += 7) {
                    RigLocomotion.Out a = RigLocomotion.pose(was, Map.of(), w, t);
                    RigLocomotion.Out b = RigLocomotion.pose(now, Map.of(), w, t);
                    assertSamePose(a.pose(), b.pose(), union(a.pose(), b.pose()),
                            f + " at fly 0, " + Arrays.toString(g) + ", t=" + t);
                    assertSameRoot(a.root(), b.root(), f + ": the body moved");
                }
            }
        }
    }

    @Test
    void inTheAirOnlyUprightWingsChange() throws IOException {
        for (Flier f : census()) {
            Set<String> changeable = new LinkedHashSet<>();
            for (int side : new int[]{-1, 1}) {
                if (upright(f.anatomy(), side)) {
                    for (RigAnatomy.Wing w : f.anatomy().wings()) {
                        if (w.side() == side) {
                            changeable.add(w.bone());
                        }
                    }
                }
            }
            for (float fly : new float[]{0.3f, 1f}) {
                for (int t = 0; t < RigLocomotion.FLAP_TICKS; t++) {
                    RigLocomotion.Out a = RigLocomotion.pose(f.before(), Map.of(), fly(fly), t);
                    RigLocomotion.Out b = RigLocomotion.pose(f.plan(), Map.of(), fly(fly), t);
                    Set<String> rest = union(a.pose(), b.pose());
                    rest.removeAll(changeable);
                    assertSamePose(a.pose(), b.pose(), rest, f + " in the air at t=" + t);
                    assertSameRoot(a.root(), b.root(), f + ": the body moved");
                }
            }
        }
    }

    @Test
    void aLaidFlatWingStaysOnItsShoulderAndItsTrailingEdgeGoesBack() throws IOException {
        for (Flier f : census()) {
            RigAnatomy a = f.anatomy();
            Map<String, float[][]> rest = RigLocomotion.corners(a.parts(), Map.of(), null);
            for (int side : new int[]{-1, 1}) {
                if (!upright(a, side)) {
                    continue;
                }
                RigAnatomy.Wing inner = a.wings().stream()
                        .filter(w -> w.side() == side && w.rank() == 0).findFirst().orElseThrow();
                float[] at = inner.attach();
                // the rest corner nearest the attachment point, followed through the beat
                int near = 0;
                double best = Double.MAX_VALUE;
                float[][] r = rest.get(inner.bone());
                for (int i = 0; i < 8; i++) {
                    double d = dist(r[i], at);
                    if (d < best) {
                        best = d;
                        near = i;
                    }
                }
                for (int t = 0; t < RigLocomotion.FLAP_TICKS; t++) {
                    RigLocomotion.Out o = RigLocomotion.pose(f.plan(), Map.of(), fly(1f), t);
                    float[][] c = RigLocomotion.corners(a.parts(), o.pose(), null).get(inner.bone());
                    assertTrue(dist(c[near], at) <= best + 0.01,
                            f + ": the wing came off its shoulder at t=" + t);
                }
                // at the top of nothing (t where the beat is level), the panel's middle is behind
                // the shoulder: the trailing edge went back, not forward
                RigLocomotion.Out o = RigLocomotion.pose(f.plan(), Map.of(), fly(1f), 0);
                float[] b = RigAnatomy.bounds(RigLocomotion.corners(a.parts(), o.pose(), null)
                        .get(inner.bone()));
                float mid = (b[2] + b[5]) / 2f;
                assertTrue((mid - at[2]) * a.front() < 0,
                        f + ": the trailing edge went forward, not back (" + mid + " vs "
                                + at[2] + ")");
            }
        }
    }

    private static double dist(float[] a, float[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }

    // ------------------------------------------------------------------- the classification

    private static float[][] box(float x0, float y0, float z0, float x1, float y1, float z1,
                                 float rx, float ry, float rz) {
        CreatureSculpt.Part p = new CreatureSculpt.Part("p", null,
                new float[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, 0xffffff,
                new float[]{rx, ry, rz}, 0f, "", 0);
        return RigLocomotion.corners(List.of(p), Map.of(), null).get("p");
    }

    @Test
    void aPlateIsUprightOnlyWhenItsFaceLooksAlongTheBody() {
        // upright membrane (thin in Z), with a kit's rest sweep and droop
        assertTrue(RigAnatomy.uprightPanels(List.<float[][]>of(box(4, 0, -0.5f, 14, 8, 0.5f, 0, 10, -10))));
        // flat wing (thin in Y), tilted up 30 degrees
        assertFalse(RigAnatomy.uprightPanels(List.<float[][]>of(box(4, 0, -4, 14, 1, 4, 0, 0, 30))));
        // a slab hanging at the side (thin in X): a chicken's
        assertFalse(RigAnatomy.uprightPanels(List.<float[][]>of(box(4, 0, -3, 5, 6, 3, 0, 0, 0))));
        // a feather rod: two thin dimensions alike, not a plate, no vote
        assertFalse(RigAnatomy.uprightPanels(List.<float[][]>of(box(4, 0, -1.2f, 16, 2.4f, 1.2f, 0, 26, -28))));
        // a cube is not a plate either
        assertFalse(RigAnatomy.uprightPanels(List.<float[][]>of(box(0, 0, 0, 4, 4, 4, 0, 0, 0))));
        // a bat wing: a thin flat arm strut along the top, upright membranes off it -> upright
        assertTrue(RigAnatomy.uprightPanels(List.<float[][]>of(
                box(4, 8, -1.3f, 16, 9.2f, 1.3f, 0, 0, -10),
                box(4, 0, -0.5f, 7, 9.2f, 0.5f, 0, 10, -10),
                box(7, 1, -0.5f, 10, 9.2f, 0.5f, 0, 10, -10),
                box(10, 2, -0.5f, 13, 9.2f, 0.5f, 0, 10, -10),
                box(13, 3, -0.5f, 16, 9.2f, 0.5f, 0, 10, -10))));
        // a feather fan: each feather stands a little on edge, but they step back one behind
        // another, so the wing is spread front to back already and is never stood on end
        assertFalse(RigAnatomy.uprightPanels(List.<float[][]>of(
                box(4, 0, -1.2f, 16, 6, 1.2f, 0, 0, 0),
                box(4, 0, -5, 19, 6, -2.6f, 0, 0, 0),
                box(4, 0, -8.8f, 22, 6, -6.4f, 0, 0, 0),
                box(4, 0, -12.6f, 25, 6, -10.2f, 0, 0, 0))));
        // a zero-thickness panel still has a face
        assertTrue(RigAnatomy.uprightPanels(List.<float[][]>of(box(4, 0, 0, 14, 8, 0, 0, 0, 0))));
    }

    /** Fins, ears, a cape and a tail are upright plates too, and none of them is a wing. */
    @Test
    void uprightFinsEarsCapesAndTailsAreNotWings() {
        List<CreatureSculpt.Part> parts = new ArrayList<>();
        parts.add(part("body", null, -4, 6, -8, 4, 14, 8));
        parts.add(part("head", null, -3, 12, -13, 3, 18, -8));
        parts.add(part("legL", null, -3, 0, -6, -1, 6, -4));
        parts.add(part("legR", null, 1, 0, -6, 3, 6, -4));
        parts.add(part("legL2", null, -3, 0, 4, -1, 6, 6));
        parts.add(part("legR2", null, 1, 0, 4, 3, 6, 6));
        parts.add(part("earL", "head", -4, 18, -11, -2, 22, -10.5f));
        parts.add(part("earR", "head", 2, 18, -11, 4, 22, -10.5f));
        parts.add(part("finL", null, -9, 9, -1, -4, 13, -0.5f));
        parts.add(part("finR", null, 4, 9, -1, 9, 13, -0.5f));
        parts.add(part("cape", null, -5, 4, 8, 5, 14, 8.5f));
        parts.add(part("tail", null, -0.5f, 8, 8, 0.5f, 14, 16));
        RigAnatomy a = RigAnatomy.of(RigFit.intoRigSpace(SculptOrientation.faceForward(parts)));
        assertTrue(a.wings().isEmpty(), "nothing here is a wing: " + a.wings());
        // and the same creature with wings has exactly those as wings
        parts.add(part("wingL", null, -16, 10, -0.5f, -4, 14, 0.5f));
        parts.add(part("wingR", null, 4, 10, -0.5f, 16, 14, 0.5f));
        a = RigAnatomy.of(RigFit.intoRigSpace(SculptOrientation.faceForward(parts)));
        assertEquals(Set.of("wingL", "wingR"), new java.util.HashSet<>(
                a.wings().stream().map(RigAnatomy.Wing::bone).toList()));
        assertTrue(a.wings().stream().allMatch(RigAnatomy.Wing::upright), a.wings().toString());
    }

    private static CreatureSculpt.Part part(String name, String parent, float x0, float y0,
                                            float z0, float x1, float y1, float z1) {
        return new CreatureSculpt.Part(name, parent,
                new float[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, 0xffffff,
                new float[]{0, 0, 0}, 0f, "", 0);
    }

    @Test
    void theCensusFindsEveryWingByName() throws IOException {
        for (Flier f : census()) {
            for (String b : allWingBoxes(f.anatomy())) {
                // a wing's own boxes carry its name or hang from a box that does
                CreatureSculpt.Part p = f.anatomy().parts().stream()
                        .filter(x -> x.name().equals(b)).findFirst().orElseThrow();
                boolean named = false;
                for (CreatureSculpt.Part q = p; q != null && !named; ) {
                    named = q.name().toLowerCase(Locale.ROOT).contains("wing");
                    String parent = q.parent();
                    q = parent == null ? null : f.anatomy().parts().stream()
                            .filter(x -> x.name().equals(parent)).findFirst().orElse(null);
                }
                assertTrue(named, f + ": " + b + " is moved as a wing without being one");
            }
        }
    }
}
