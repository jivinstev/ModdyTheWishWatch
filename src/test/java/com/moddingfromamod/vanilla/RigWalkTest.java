package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;
import com.moddingfromamod.runtime.SculptOrientation;
import org.junit.jupiter.api.Test;

/**
 * <b>Every leg steps, whatever it is called, in a gait that fits how many it has</b>.
 *
 * <p>Measured the way {@link RigLocomotionTest} measures: on the boxes as the rig draws them
 * ({@link RigLocomotion#corners}), never on the angles the generator chose. A leg "steps" when its
 * FOOT moves; the pattern is read off each foot's forward position through one cycle; and the
 * direction is read off when a foot is LIFTED, because a magnitude is true of a creature
 * moon-walking (ladder §2.2): a foot that slides forward on the ground and lifts to go back
 * travels as far as one that steps.
 *
 * <p>Fixtures: the Builder's horse (front {@code legL/legR}, hind {@code legBL/legBR}, the
 * pilot's naming), a spider and an ant made the way the agent is told to make them (a body and
 * {@code leg_row}, count 4 and 3), a spider with hand-named legs {@code legL1..legR4} (the
 * pilot's spider), the person and the starter cat (hind legs {@code armL/armR}, with paw stubs).
 */
class RigWalkTest {

    static final int SAMPLES = 48;

    // ------------------------------------------------------------------- fixtures

    static final String SPIDER = """
            {"parts":[
             {"name":"body","from":[-4,5,-2],"to":[4,11,8],"colour":"#3a2a1e"},
             {"name":"head","parent":"body","from":[-3,5,-7],"to":[3,10,-1],"colour":"#5a4030"}],
             "kits":[{"kit":"leg_row","on":"body","count":4}]}""";

    static final String ANT = """
            {"parts":[
             {"name":"body","from":[-2,4,-3],"to":[2,7,3],"colour":"#a03a18"},
             {"name":"abdomen","parent":"body","from":[-3,4,3],"to":[3,9,11],"colour":"#802a10"},
             {"name":"head","parent":"body","from":[-2.5,4,-8],"to":[2.5,8,-3],"colour":"#a03a18"}],
             "kits":[{"kit":"leg_row","on":"body","count":3}]}""";

    /** Eight legs named the way the pilot's spider named them, one box each. */
    static String handSpider() {
        StringBuilder sb = new StringBuilder("""
                {"parts":[{"name":"body","from":[-4,4,-6],"to":[4,10,6],"colour":"#333333"},
                 {"name":"head","from":[-3,5,-10],"to":[3,9,-6],"colour":"#444444"}""");
        for (int i = 0; i < 4; i++) {
            float z = -5 + i * 3.2f;
            for (String side : new String[]{"L", "R"}) {
                float x = side.equals("L") ? 4 : -5;
                sb.append(String.format(java.util.Locale.ROOT, ",{\"name\":\"leg%s%d\",\"from\":[%.1f,0,%.1f],"
                        + "\"to\":[%.1f,6,%.1f],\"colour\":\"#222222\"}", side, i + 1, x, z, x + 1, z + 1));
            }
        }
        return sb.append("]}").toString();
    }

    static List<CreatureSculpt.Part> parse(String json) {
        JsonObject d = JsonParser.parseString(json).getAsJsonObject();
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(d.getAsJsonArray("parts"));
        if (d.has("kits")) {
            List<CreatureSculpt.Part> grown = new ArrayList<>(parts);
            grown.addAll(SculptKits.expand(d.getAsJsonArray("kits"), parts));
            parts = grown;
        }
        return parts;
    }

    static String resource(String path) {
        try (InputStream in = RigWalkTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static RigLocomotion.Plan plan(String json) {
        return RigLocomotion.plan(RigAnatomy.of(
                RigFit.intoRigSpace(SculptOrientation.faceForward(parse(json)))));
    }

    static RigLocomotion.Plan horse() {
        return plan(resource("/builder-bodies/species/horse/sculpt.json"));
    }

    static RigLocomotion.Plan person() {
        return plan(resource("/builder-bodies/species/person/sculpt.json"));
    }

    static RigLocomotion.Plan cat() {
        return plan(resource("/starter-library/species/cat/sculpt.json"));
    }

    static final Map<String, BedrockAnimation> CLIPS =
            BedrockAnimation.parseAll(CreatureFiles.animations());

    /** The walk clip's pose at a point of its (one second) cycle: what the creature played before. */
    static Map<String, BedrockAnimation.BonePose> clipAt(float phase) {
        return AnimationState.pose(CLIPS,
                new AnimationState.Selection(BedrockAnimation.Clip.WALK, phase));
    }

    // ------------------------------------------------------------------- measuring

    /** Per leg, per sample: the foot ({x, y, z}), the middle of the leg chain's lowest corners. */
    static Map<String, float[][]> feet(RigLocomotion.Plan p, boolean generated) {
        RigAnatomy a = p.anatomy();
        Map<String, float[][]> out = new HashMap<>();
        for (RigAnatomy.Leg l : a.legs()) {
            out.put(l.bone(), new float[SAMPLES][]);
        }
        for (int s = 0; s < SAMPLES; s++) {
            float phase = s / (float) SAMPLES;
            RigLocomotion.Out o = generated
                    ? RigLocomotion.pose(p, clipAt(phase), RigLocomotion.Weights.NONE, 0f,
                            new RigLocomotion.Stride(phase, 1f))
                    : RigLocomotion.pose(p, clipAt(phase), RigLocomotion.Weights.NONE, 0f);
            Map<String, float[][]> c = RigLocomotion.corners(a.parts(), o.pose(), o.root());
            for (RigAnatomy.Leg l : a.legs()) {
                out.get(l.bone())[s] = foot(c, RigLocomotion.subtree(a.parts(), l.bone()));
            }
        }
        return out;
    }

    static float[] foot(Map<String, float[][]> c, Set<String> chain) {
        float low = Float.MAX_VALUE;
        for (String b : chain) {
            for (float[] v : c.get(b)) {
                low = Math.min(low, v[1]);
            }
        }
        float[] s = new float[3];
        int n = 0;
        for (String b : chain) {
            for (float[] v : c.get(b)) {
                if (v[1] <= low + 0.75f) {
                    for (int k = 0; k < 3; k++) {
                        s[k] += v[k];
                    }
                    n++;
                }
            }
        }
        return new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    /** How far forward, for this creature's facing. */
    static float[] forward(RigLocomotion.Plan p, float[][] foot) {
        float[] f = new float[foot.length];
        for (int i = 0; i < foot.length; i++) {
            f[i] = p.anatomy().front() * foot[i][2];
        }
        return f;
    }

    static int stepping(Map<String, float[][]> feet) {
        int n = 0;
        for (float[][] f : feet.values()) {
            float travel = 0f;
            for (float[] a : f) {
                for (float[] b : f) {
                    travel = Math.max(travel, (float) Math.hypot(a[0] - b[0], a[2] - b[2]));
                }
            }
            if (travel > 0.75f) {
                n++;
            }
        }
        return n;
    }

    /** The phase (degrees) of a cyclic series' fundamental. */
    static double phaseOf(float[] f) {
        double c = 0;
        double s = 0;
        for (int i = 0; i < f.length; i++) {
            double t = 2 * Math.PI * i / f.length;
            c += f[i] * Math.cos(t);
            s += f[i] * Math.sin(t);
        }
        return Math.toDegrees(Math.atan2(s, c));
    }

    /** {@code b}'s phase minus {@code a}'s, wrapped to -180..180: positive = b steps LATER. */
    static double lag(float[] a, float[] b) {
        double d = phaseOf(b) - phaseOf(a);
        while (d > 180) {
            d -= 360;
        }
        while (d <= -180) {
            d += 360;
        }
        return d;
    }

    /** Legs ordered front to back on one side ({@code +1} or {@code -1} of the legs' middle X). */
    static List<RigAnatomy.Leg> side(RigLocomotion.Plan p, int sign) {
        RigAnatomy a = p.anatomy();
        float cx = 0f;
        for (RigAnatomy.Leg l : a.legs()) {
            cx += l.hip()[0];
        }
        float mid = cx / a.legs().size();
        List<RigAnatomy.Leg> out = new ArrayList<>();
        for (RigAnatomy.Leg l : a.legs()) {
            if (Math.signum(l.hip()[0] - mid) == sign) {
                out.add(l);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(l -> -a.front() * l.hip()[2]));
        return out;
    }

    static void assertLag(double expected, double actual, String what) {
        double d = Math.abs(expected - actual);
        d = Math.min(d, 360 - d);
        assertTrue(d < 20, what + ": expected a lag of " + expected + "°, measured " + actual + "°");
    }

    // ------------------------------------------------------------------- the oracle in the issue

    @Test
    void everyLegStepsWhereTheFixedClipMovedOnlyTheFourItKnowsByName() {
        // The before is the same path with no stride: the clip alone, legL/legR/armL/armR by name.
        assertEquals(2, stepping(feet(horse(), false)), "before: the horse's legBL/legBR stand still");
        assertEquals(0, stepping(feet(plan(SPIDER), false)), "before: no leg_row leg steps");
        assertEquals(0, stepping(feet(plan(handSpider()), false)), "before: legL1..legR4 stand still");
        assertEquals(0, stepping(feet(plan(ANT), false)), "before: no leg of the ant steps");

        assertEquals(4, horse().anatomy().legs().size());
        assertEquals(4, stepping(feet(horse(), true)), "a horse named legBL/legBR walks on four");
        assertEquals(8, plan(SPIDER).anatomy().legs().size());
        assertEquals(8, stepping(feet(plan(SPIDER), true)), "the leg_row spider walks on eight");
        assertEquals(8, stepping(feet(plan(handSpider()), true)), "legL1..legR4 walk");
        assertEquals(6, plan(ANT).anatomy().legs().size());
        assertEquals(6, stepping(feet(plan(ANT), true)), "the ant walks on six");
        assertEquals(2, stepping(feet(person(), true)));
    }

    // ------------------------------------------------------------------- the gaits

    @Test
    void twoLegsAlternate() {
        RigLocomotion.Plan p = person();
        Map<String, float[][]> f = feet(p, true);
        assertLag(180, lag(forward(p, f.get("legL")), forward(p, f.get("legR"))), "left and right");
    }

    @Test
    void fourLegsTrotTheDiagonalPairsTogether() {
        RigLocomotion.Plan p = horse();
        Map<String, float[][]> f = feet(p, true);
        List<RigAnatomy.Leg> l = side(p, -1);
        List<RigAnatomy.Leg> r = side(p, 1);
        float[] fl = forward(p, f.get(l.get(0).bone()));
        float[] hl = forward(p, f.get(l.get(1).bone()));
        float[] fr = forward(p, f.get(r.get(0).bone()));
        float[] hr = forward(p, f.get(r.get(1).bone()));
        assertLag(0, lag(fl, hr), "a diagonal pair");
        assertLag(0, lag(fr, hl), "the other diagonal pair");
        assertLag(180, lag(fl, fr), "the front pair");
        assertLag(180, lag(fl, hl), "one side");
    }

    @Test
    void sixLegsWalkOnAnAlternatingTripod() {
        RigLocomotion.Plan p = plan(ANT);
        Map<String, float[][]> f = feet(p, true);
        List<RigAnatomy.Leg> l = side(p, -1);
        List<RigAnatomy.Leg> r = side(p, 1);
        assertEquals(3, l.size());
        assertEquals(3, r.size());
        float[] l1 = forward(p, f.get(l.get(0).bone()));
        assertLag(0, lag(l1, forward(p, f.get(l.get(2).bone()))), "front and back of one side");
        assertLag(0, lag(l1, forward(p, f.get(r.get(1).bone()))), "…with the middle of the other");
        assertLag(180, lag(l1, forward(p, f.get(l.get(1).bone()))), "the other tripod");
        assertLag(180, lag(l1, forward(p, f.get(r.get(0).bone()))), "the other tripod");
    }

    @Test
    void eightLegsWalkInAWaveFrontToBackTheSidesInAntiphase() {
        for (RigLocomotion.Plan p : List.of(plan(SPIDER), plan(handSpider()))) {
            Map<String, float[][]> f = feet(p, true);
            List<RigAnatomy.Leg> l = side(p, -1);
            List<RigAnatomy.Leg> r = side(p, 1);
            assertEquals(4, l.size());
            assertEquals(4, r.size());
            for (int i = 0; i < 4; i++) {
                assertLag(180, lag(forward(p, f.get(l.get(i).bone())), forward(p, f.get(r.get(i).bone()))),
                        "pair " + i + " left against right");
                if (i > 0) {
                    // Each row a quarter of a stride LATER than the row in front of it.
                    assertLag(90, lag(forward(p, f.get(l.get(i - 1).bone())),
                            forward(p, f.get(l.get(i).bone()))), "row " + i + " behind row " + (i - 1));
                }
            }
        }
    }

    // ------------------------------------------------------------------- a step, not a slide

    /**
     * The control that is FALSE of a moon-walk: a foot travelling FORWARD is off the ground and a
     * foot travelling BACK is on it. Reverse the stride and the distances, the counts and the
     * phase lags above are all unchanged; this is not.
     */
    @Test
    void aFootIsLiftedToComeForwardAndPlantedToPushBack() {
        for (RigLocomotion.Plan p : List.of(horse(), person(), plan(SPIDER), plan(ANT), plan(handSpider()))) {
            Map<String, float[][]> feet = feet(p, true);
            for (RigAnatomy.Leg l : p.anatomy().legs()) {
                float[][] f = feet.get(l.bone());
                float[] fw = forward(p, f);
                double upForward = 0;
                double upBack = 0;
                int nf = 0;
                int nb = 0;
                float ground = Float.MAX_VALUE;
                for (float[] v : f) {
                    ground = Math.min(ground, v[1]);
                }
                for (int i = 0; i < SAMPLES; i++) {
                    float v = fw[(i + 1) % SAMPLES] - fw[(i + SAMPLES - 1) % SAMPLES];
                    if (v > 0) {
                        upForward += f[i][1] - ground;
                        nf++;
                    } else if (v < 0) {
                        upBack += f[i][1] - ground;
                        nb++;
                    }
                }
                double fwdLift = upForward / Math.max(1, nf);
                double backLift = upBack / Math.max(1, nb);
                assertTrue(fwdLift > backLift + 0.25, l.bone() + ": a foot coming forward is lifted "
                        + String.format("%.2f", fwdLift) + " and one pushing back " + String.format("%.2f", backLift)
                        + " -- it slides forward and lifts to go back: a moon-walk");
            }
        }
    }

    // ------------------------------------------------------------------- one leg, one writer

    @Test
    void noLegIsSwungTwiceAndABipedsArmsKeepTheClip() {
        RigLocomotion.Plan p = person();
        for (float phase : new float[]{0f, 0.2f, 0.55f}) {
            RigLocomotion.Stride s = new RigLocomotion.Stride(phase, 1f);
            var withClip = RigLocomotion.pose(p, clipAt(phase), RigLocomotion.Weights.NONE, 0f, s).pose();
            var without = RigLocomotion.pose(p, Map.of(), RigLocomotion.Weights.NONE, 0f, s).pose();
            for (String leg : List.of("legL", "legR")) {
                assertArrayEquals(without.get(leg).rotation(), withClip.get(leg).rotation(), 1e-4f,
                        leg + " carries the clip's swing as well as the walk's");
            }
            for (String arm : List.of("armL", "armR")) {
                assertArrayEquals(clipAt(phase).get(arm).rotation(), withClip.get(arm).rotation(), 1e-4f,
                        arm + " is a biped's arm: the clip keeps it");
            }
        }
        // ...and in step with the legs: the right hand comes forward as the right foot goes back.
        Map<String, float[][]> f = feet(p, true);
        float[] hand = new float[SAMPLES];
        RigAnatomy a = p.anatomy();
        for (int i = 0; i < SAMPLES; i++) {
            float phase = i / (float) SAMPLES;
            var o = RigLocomotion.pose(p, clipAt(phase), RigLocomotion.Weights.NONE, 0f,
                    new RigLocomotion.Stride(phase, 1f));
            var c = RigLocomotion.corners(a.parts(), o.pose(), o.root());
            float low = Float.MAX_VALUE;
            float z = 0;
            for (float[] v : c.get("handR")) {
                if (v[1] < low) {
                    low = v[1];
                    z = v[2];
                }
            }
            hand[i] = a.front() * z;
        }
        assertLag(180, lag(forward(p, f.get("legR")), hand), "the right arm against the right leg");
    }

    @Test
    void theCatsPawStubStepsWithTheLegItIsTheFootOf() {
        RigLocomotion.Plan p = cat();
        Map<String, Float> o = RigLocomotion.strideOffsets(p.anatomy());
        assertEquals(o.get("armL"), o.get("legL2"), "legL2 stands where armL stands");
        assertEquals(o.get("armR"), o.get("legR2"), "legR2 stands where armR stands");
        assertEquals(0.5f, Math.abs(o.get("armL") - o.get("armR")), 1e-6f, "the hind pair alternates");
        assertEquals(o.get("legR"), o.get("armL"), "a trot: front right with hind left");
    }

    @Test
    void notWalkingTheLegsAreTheClipsAndNothingElse() {
        RigLocomotion.Plan p = horse();
        var before = RigLocomotion.pose(p, clipAt(0.3f), RigLocomotion.Weights.NONE, 0f).pose();
        var none = RigLocomotion.pose(p, clipAt(0.3f), RigLocomotion.Weights.NONE, 0f,
                RigLocomotion.Stride.NONE).pose();
        assertEquals(before.keySet(), none.keySet());
        for (String k : before.keySet()) {
            assertArrayEquals(before.get(k).rotation(), none.get(k).rotation(), 0f);
        }
        assertEquals(RigLocomotion.Stride.NONE, RigLocomotion.Stride.of(0f, 12f), "standing still");
        RigLocomotion.Stride s = RigLocomotion.Stride.of(0.6f, 10f);
        assertEquals(1f, s.amount(), 1e-6f);
        assertEquals(10f * AnimationState.WALK_CYCLE_SCALE % 1f, s.phase(), 1e-5f,
                "the walk clip's own clock");
    }

    // ------------------------------------------------------------------- the leg row stays jointed

    @Test
    void aLegRowIsJointedThighToShinAndStaysJointedWhileItWalks() {
        for (String json : List.of(SPIDER, ANT)) {
            RigLocomotion.Plan p = plan(json);
            RigAnatomy a = p.anatomy();
            List<CreatureSculpt.Part> parts = a.parts();
            float[] body = RigAnatomy.bounds(RigLocomotion.corners(parts, Map.of(), null).get("body"));
            for (RigAnatomy.Leg l : a.legs()) {
                assertTrue(l.sprawl(), l.bone() + " stands out to the side: a sprawled leg");
                List<String> chain = new ArrayList<>(RigLocomotion.subtree(parts, l.bone()));
                assertEquals(2, chain.size(), l.bone() + " is a thigh and a shin: " + chain);
                String shin = chain.get(1);
                for (int s = 0; s < SAMPLES; s += 4) {
                    float phase = s / (float) SAMPLES;
                    var o = RigLocomotion.pose(p, Map.of(), RigLocomotion.Weights.NONE, 0f,
                            new RigLocomotion.Stride(phase, 1f));
                    var c = RigLocomotion.corners(parts, o.pose(), o.root());
                    float[] thigh = RigAnatomy.bounds(c.get(l.bone()));
                    float[] shinBox = RigAnatomy.bounds(c.get(shin));
                    // The knee: the shin's highest corners sit inside the thigh's box.
                    float[] top = highest(c.get(shin));
                    assertTrue(inside(top, thigh, 0.6f), l.bone() + ": the knee opened at phase " + phase);
                    // The hip: the thigh still starts in the body.
                    assertTrue(inside(l.hip(), grow(body, 1f), 0f), l.bone() + "'s hip is off the body");
                    assertTrue(shinBox[1] < a.groundY() + 2.5f, shin + " no longer reaches the ground");
                }
            }
        }
    }

    static float[] highest(float[][] cs) {
        float hi = -Float.MAX_VALUE;
        for (float[] v : cs) {
            hi = Math.max(hi, v[1]);
        }
        float[] s = new float[3];
        int n = 0;
        for (float[] v : cs) {
            if (v[1] >= hi - 0.75f) {
                for (int k = 0; k < 3; k++) {
                    s[k] += v[k];
                }
                n++;
            }
        }
        return new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    static boolean inside(float[] v, float[] b, float tol) {
        for (int k = 0; k < 3; k++) {
            if (v[k] < b[k] - tol || v[k] > b[k + 3] + tol) {
                return false;
            }
        }
        return true;
    }

    static float[] grow(float[] b, float d) {
        return new float[]{b[0] - d, b[1] - d, b[2] - d, b[3] + d, b[4] + d, b[5] + d};
    }
}
