package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;

/**
 * <b> — no two displays the atom rig DRAWS may share a surface.</b>
 *
 * <p>The oracle is {@link RigSurfaceCensus}, which deliberately knows nothing about
 * {@link RigSeparation}: it takes what the rig actually hands a client — every
 * {@link RigTransform#placeAtom} of every group {@link RigPlan#atoms} plans, pattern overlays
 * included — rebuilds each display's box in the world from its translation, rotation and scale,
 * and asks whether any two share a plane (a fight), whether a detail lies under a patterned
 * neighbour's ink (buried), or a recessed pattern paints onto its neighbour (ink shows).
 *
 * <p>The August fix's gate asked the PARSER ({@code FightingFaces.find} on
 * {@code BedrockGeometry}), which nothing has drawn; this one asks the renderer's own
 * inputs, so it cannot be green while the child sees a flicker. And it is run with the separation
 * OFF as well, where it must go red on the creatures the owner filmed — a check that passes both
 * arms is a control true of the bug ({@code docs/VANILLA_RIG_ANIMATION_LADDER.md}).
 */
class RigSeparationTest {

    /**
     * Every committed sculpt a child can meet on the rig: the corpus and the starter library. The corpus is
     * {@link SpikeFixture#CASES}: the nine tier-experiment sculpts where the private corpus is, their public
     * stand-ins (the same geometry) where it is not.
     */
    static final List<String> CORPUS = SpikeFixture.CASES;
    static final List<String> STARTERS = List.of(
            "cat", "chicken", "frog", "pet_dragon", "sheep", "snail", "turtle", "wolf");

    @AfterEach
    void on() {
        RigSeparation.setEnabledForTest(true);
    }

    @Test
    @DisplayName("every committed creature: 0 fighting surfaces and 0 buried details, at every scale")
    void nothingTheRigDrawsFights() {
        StringBuilder table = new StringBuilder();
        List<String> failures = new ArrayList<>();
        for (String name : all()) {
            List<CreatureSculpt.Part> parts = load(name);
            double authored = scaleOf(name);
            for (double scale : new double[]{authored, 0.5, 0.75, 1.0, 2.0, 3.0}) {
                Census c = census(parts, scale, null);
                RigSeparation.setEnabledForTest(false);
                Census before = census(parts, scale, null);
                RigSeparation.setEnabledForTest(true);
                if (scale == authored) {
                    table.append(String.format("%-16s scale %.2f  fights %d  buried %d  "
                                    + "ink-shows %d (was %d)%n", name, scale, c.fights.size(),
                            c.buried.size(), c.inkShows.size(), before.inkShows.size()));
                }
                if (!c.fights.isEmpty() || !c.buried.isEmpty() || !c.inkShows.isEmpty()) {
                    failures.add(name + " @" + scale + ": " + c.fights + " " + c.buried + " "
                            + c.inkShows);
                }
            }
        }
        System.out.println("RigSeparation (on):\n" + table);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * <b>The same oracle, separation OFF: it must go red where the owner saw it.</b>
     *
     * <p>Counts rather than "at least one", so the table in the PR is this test's own output and
     * a change to the oracle that stopped seeing the tilted head would show as a number moving.
     */
    @Test
    @DisplayName("with the separation off, the same oracle finds the fights the owner filmed")
    void theOracleSeesTheBugWithTheSeparationOff() {
        RigSeparation.setEnabledForTest(false);
        StringBuilder table = new StringBuilder();
        Map<String, Integer> fights = new LinkedHashMap<>();
        for (String name : all()) {
            Census c = census(load(name), scaleOf(name), null);
            fights.put(name, c.fights.size() + c.buried.size());
            table.append(String.format("%-16s fights %d  buried %d%n",
                    name, c.fights.size(), c.buried.size()));
        }
        System.out.println("RigSeparation (OFF — the bug):\n" + table);
        // The filmed Spikor (every face part under a -10° head) and every starter the
        // issue names. Zero here would mean the oracle is blind, not that the rig is fine.
        for (String must : List.of(SpikeFixture.SPIKOR, "pet_dragon", "cat", "frog", "chicken",
                "wolf", "turtle", "snail")) {
            assertTrue(fights.get(must) > 0, must + ": the oracle sees no fight with the "
                    + "separation OFF, so it cannot be what says the fix works: " + fights);
        }
    }

    /**
     * <b>The tilted head, on its own</b> — the case FightingFaces skipped even when it ran.
     * Spikor's mouth on his snout: both hang off a head turned −10°.
     */
    @Test
    @DisplayName("Spikor's snout and mouth are separated under his tilted head")
    void theTiltedHeadIsSeparated() {
        List<CreatureSculpt.Part> parts = RigFit.intoRigSpace(load(SpikeFixture.SPIKOR));
        List<RigPlan.Group> plan = RigPlan.atoms(parts);
        boolean anyTiltedGrown = false;
        for (RigPlan.Group g : plan) {
            float[] s = g.separation().blocks(1.2);
            boolean grown = s[0] > 0 || s[1] > 0 || s[2] > 0;
            Quaternionf q = new Quaternionf(g.chainRotationXyzw()[0], g.chainRotationXyzw()[1],
                    g.chainRotationXyzw()[2], g.chainRotationXyzw()[3]);
            if (grown && Math.abs(q.w) < 0.99999f) {
                anyTiltedGrown = true;
            }
        }
        assertTrue(anyTiltedGrown, "no box under a turned ancestor was separated");
    }

    /**
     * <b>The walk, not just the statue.</b> The animated path re-chains every group every tick
     * ({@link RigPose#withChain}); a copy that dropped the separation would fix a creature standing
     * still and flicker it the moment it moved — the case the owner filmed. So place every group
     * with its head turned and bent, through the per-frame copy, and ask the oracle again.
     */
    @Test
    @DisplayName("the separation survives the per-frame re-chain and a turned head")
    void theSeparationSurvivesAnimation() {
        for (String name : all()) {
            List<CreatureSculpt.Part> parts = load(name);
            for (RigPlan.Group g : RigPlan.atoms(RigFit.intoRigSpace(parts))) {
                RigPlan.Group moved = g.withChain(g.chainRotationXyzw(), g.chainTranslation());
                assertTrue(g.separation() == moved.separation(),
                        name + "/" + g.name() + ": withChain dropped the separation");
            }
            Census c = census(parts, scaleOf(name), new float[]{35f, -20f});
            assertTrue(c.fights.isEmpty() && c.buried.isEmpty() && c.inkShows.isEmpty(),
                    name + " with its head turned: " + c.fights + " " + c.buried + " "
                            + c.inkShows);
        }
    }

    /**
     * <b>It changes sizes, never counts or places</b> — the issue's "display count and traffic
     * are unchanged". Same number of groups, same order, same translations and rotations, with the
     * separation on and off; only scales may differ, and by at most {@link RigSeparation#MAX_BLOCKS}
     * per face.
     */
    @Test
    @DisplayName("the separation changes display sizes only — never the count, order or position")
    void sizesOnly() {
        for (String name : all()) {
            List<CreatureSculpt.Part> rig = RigFit.intoRigSpace(load(name));
            double scale = scaleOf(name);
            List<RigPlan.Group> on = RigPlan.atoms(rig);
            RigSeparation.setEnabledForTest(false);
            List<RigPlan.Group> off = RigPlan.atoms(rig);
            RigSeparation.setEnabledForTest(true);
            assertEquals(off.size(), on.size(), name + ": display count moved");
            for (int i = 0; i < on.size(); i++) {
                assertEquals(off.get(i).name(), on.get(i).name(), name + ": order moved");
                RigTransform.Placed a = RigTransform.placeAtom(off.get(i), scale);
                RigTransform.Placed b = RigTransform.placeAtom(on.get(i), scale);
                assertTrue(a.translation().distance(b.translation()) < 1e-6f, name + ": moved");
                assertTrue(Math.abs(a.leftRotation().dot(b.leftRotation())) > 1 - 1e-6f,
                        name + ": turned");
                Vector3f d = new Vector3f(b.scale()).sub(a.scale());
                for (int ax = 0; ax < 3; ax++) {
                    assertTrue(d.get(ax) >= -1e-6f
                                    && d.get(ax) <= 2 * RigSeparation.MAX_BLOCKS + 1e-6f,
                            name + "/" + on.get(i).name() + ": scale moved by " + d);
                }
            }
        }
    }

    /**
     * <b>Grown along the fighting axis only</b>: a cat's eye flush with its face stands proud of
     * it, and is exactly as wide and tall as it was sculpted.
     */
    @Test
    @DisplayName("a flush detail grows along the axis it fights on, and no other")
    void oneAxisOnly() {
        CreatureSculpt.Part head = new CreatureSculpt.Part("head", "", new float[]{0, 8, 0},
                new float[]{-4, 4, -4}, new float[]{4, 12, 4}, 0x808080);
        CreatureSculpt.Part eye = new CreatureSculpt.Part("eye", "head", new float[]{0, 8, 0},
                new float[]{-3, 8, -4}, new float[]{-1, 10, -3}, 0x000000);
        List<RigPlan.Group> plan = RigPlan.atoms(List.of(head, eye), false);
        assertArrayEquals(new float[]{0, 0, 0}, plan.get(0).separation().blocks(1.0), 0f,
                "the bigger box never gives way");
        assertArrayEquals(new float[]{0, 0, RigSeparation.STEP_BLOCKS},
                plan.get(1).separation().blocks(1.0), 1e-7f, "only z fights (the face at z = -4)");
    }

    /**
     * <b>A detail on a patterned box clears the pattern's overlay</b>, or it is drawn under the
     * ink and gone wherever the pattern draws.
     */
    @Test
    @DisplayName("a detail flush with a patterned box clears its overlay")
    void clearsThePatternOverlay() {
        CreatureSculpt.Part head = new CreatureSculpt.Part("head", "", new float[]{0, 8, 0},
                new float[]{-4, 4, -4}, new float[]{4, 12, 4}, 0x808080,
                new float[]{0, 0, 0}, 0f, "scales", 0xFFFFFF);
        CreatureSculpt.Part eye = new CreatureSculpt.Part("eye", "head", new float[]{0, 8, 0},
                new float[]{-3, 8, -4}, new float[]{-1, 10, -3}, 0x000000);
        List<RigPlan.Group> plan = RigPlan.atoms(List.of(head, eye), true);
        RigPlan.Group eyeAtom = plan.get(plan.size() - 1);
        assertEquals("eye", eyeAtom.name());
        assertTrue(eyeAtom.separation().blocks(1.0)[2] > RigPlan.PATTERN_LIFT_BLOCKS,
                "the eye must stand in front of the head's pattern overlay");
        assertTrue(census(List.of(head, eye), 1.0, null).buried.isEmpty());
        RigSeparation.setEnabledForTest(false);
        assertFalse(census(List.of(head, eye), 1.0, null).buried.isEmpty(),
                "with the separation off the eye is under the ink — the oracle must say so");
    }

    // ── the oracle: what the rig DRAWS ───────────────────────────────────────────────────────

    /**
     * <b>What MEASURES the creature does not move</b> — carrier fit, hit proxies, rest bounds.
     *
     * <p>Found 's own performance run, not by reading: on the §7 probe, the two Tralalero
     * armA got 6 hit proxies each on {@code main} and 0 on the first cut of this branch, because
     * {@link RigFit#atoms} read the drawn (separated) atoms and that creature sits on the
     * planner's bound. A 1/128-block nudge to its teeth changed which mobs a child's arrow meets.
     * So every committed creature's fit geometry and hit-proxy plan must be identical with the
     * separation on and off.
     */
    @Test
    @DisplayName("fit geometry and hit-proxy plans are identical with the separation on and off")
    void measurementsSeeTheAuthoredCreature() {
        for (String name : all()) {
            List<CreatureSculpt.Part> parts = load(name);
            double scale = scaleOf(name);
            List<RigFit.Atom> on = HitProxyPlanTest.atoms(parts, scale);
            HitProxyPlan.Plan planOn = HitProxyPlanTest.plan(parts, scale, CarrierPool.Move.WALK);
            RigSeparation.setEnabledForTest(false);
            List<RigFit.Atom> off = HitProxyPlanTest.atoms(parts, scale);
            HitProxyPlan.Plan planOff = HitProxyPlanTest.plan(parts, scale, CarrierPool.Move.WALK);
            RigSeparation.setEnabledForTest(true);
            assertEquals(off.size(), on.size(), name + ": fit atom count");
            for (int i = 0; i < on.size(); i++) {
                assertEquals(off.get(i).t(), on.get(i).t(), name + ": a fit atom moved");
                assertEquals(off.get(i).s(), on.get(i).s(), name + ": a fit atom grew");
                assertEquals(off.get(i).l(), on.get(i).l(), name + ": a fit atom turned");
                assertEquals(off.get(i).bounds(), on.get(i).bounds(), name + ": fit bounds");
            }
            assertEquals(planOff.core(), planOn.core(), name + ": another carrier");
            assertEquals(planOff.proxies(), planOn.proxies(), name + ": other hit proxies");
        }
    }

    /**
     * <b>The solve cache returns exactly what a solve would</b> — and any change to any coordinate
     * of any part, or to the scale, is a different shape. A cache keyed on a HASH alone, or on the
     * part records (whose arrays compare by reference), would hand one creature another's answer.
     */
    @Test
    @DisplayName("a cached separation equals a fresh solve, and a moved box is a different shape")
    void theCacheIsExact() {
        List<CreatureSculpt.Part> rig = RigFit.intoRigSpace(load("pet_dragon"));
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        rig.forEach(p -> byName.putIfAbsent(p.name(), p));
        double scale = scaleOf("pet_dragon");
        float[][] fresh = RigSeparation.solve(rig, byName, true, scale);
        List<RigPlan.Group> a = RigPlan.atoms(rig);
        List<RigPlan.Group> b = RigPlan.atoms(RigFit.intoRigSpace(load("pet_dragon")));
        for (int i = 0, k = 0; i < a.size(); i++) {
            if (a.get(i).patternOverlay()) {
                continue;
            }
            assertArrayEquals(fresh[k], a.get(i).separation().blocks(scale), 0f);
            assertArrayEquals(fresh[k], b.get(i).separation().blocks(scale), 0f,
                    "a second plan of the same sculpt (a cache hit) must give the same answer");
            k++;
        }
        RigSeparation.Shape one = new RigSeparation.Shape(rig, true, scale);
        List<CreatureSculpt.Part> moved = new ArrayList<>(rig);
        CreatureSculpt.Part m = moved.get(3);
        float[] to = m.to().clone();
        to[0] += 0.5f;
        moved.set(3, new CreatureSculpt.Part(m.name(), m.parent(), m.pivot(), m.from(), to,
                m.colour(), m.rotation(), m.inflate(), m.pattern(), m.patternColour()));
        assertFalse(one.equals(new RigSeparation.Shape(moved, true, scale)), "moved a box");
        assertFalse(one.equals(new RigSeparation.Shape(rig, true, scale * 2)), "another scale");
        assertTrue(one.equals(new RigSeparation.Shape(RigFit.intoRigSpace(load("pet_dragon")),
                true, scale)), "the same sculpt parsed again IS the same shape");
        RigSeparation.Solved small = new RigSeparation.Solved(2);
        for (double s : new double[]{1, 2, 3}) {
            small.get(new RigSeparation.Shape(rig, true, s), () -> new float[0][]);
        }
        assertEquals(2, small.size(), "the cache is bounded");
    }

    record Census(List<String> fights, List<String> buried, List<String> inkShows) {
    }

    /**
     * Place every group exactly as the rig does and hand the displays to the oracle.
     *
     * <p>At rest this is {@code DisplayRig.spawn}'s placement. With a look it is
     * {@code RigDriver.animate}'s: a frame composed from the bones, every group re-chained through
     * {@link RigPose#withChain}, the look on the head bone's own display and carried to its
     * descendants inside their chains.
     *
     * @param headDeg a head-follow yaw/pitch, or null for the rest pose
     */
    static Census census(List<CreatureSculpt.Part> raw, double scale, float[] headDeg) {
        List<CreatureSculpt.Part> rig = RigFit.intoRigSpace(raw);
        List<RigPlan.Group> plan = RigPlan.atoms(rig);
        List<RigSurfaceCensus.Drawn> drawn;
        if (headDeg == null) {
            drawn = RigSurfaceCensus.placed(plan, scale);
        } else {
            RigPose.Frame frame = RigPose.compose(rig, java.util.Map.of(), headDeg, true);
            drawn = new ArrayList<>();
            for (RigPlan.Group g : plan) {
                drawn.add(RigSurfaceCensus.drawn(g, RigTransform.placeAtom(
                        RigPose.withChain(g, frame), scale, g.restRotation(), null, null, null,
                        RigPlan.isHead(g) ? headDeg : null)));
            }
        }
        RigSurfaceCensus.Result r = RigSurfaceCensus.of(drawn);
        return new Census(r.fights(), r.buried(), r.inkShows());
    }

    /**
     * <b>A placement with its separation taken back out</b> — for the geometry oracles.
     *
     * <p>{@code RigPlanAtomTest} and {@code RigAtomAnimationTest} check every corner of every atom
     * against an independent transcription of the bone chain, on the AUTHORED box. A separated atom
     * is deliberately a hair bigger along the axis it fights on, so those oracles compare the rig
     * less exactly that hair; what the hair is, where it goes and that nothing else moves is this
     * class's job ({@link #sizesOnly}). Removing it rather than widening their tolerance keeps them
     * exact: a tolerance wide enough for the separation would hide a misplaced box of that size.
     */
    static RigTransform.Placed unseparated(RigTransform.Placed p, RigPlan.Group g, double scale) {
        float[] sep = g.separation().blocks(scale);
        return new RigTransform.Placed(new Vector3f(p.translation()),
                new Quaternionf(p.leftRotation()),
                new Vector3f(p.scale()).sub(2 * sep[0], 2 * sep[1], 2 * sep[2]),
                new Quaternionf(p.rightRotation()));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    static List<String> all() {
        List<String> out = new ArrayList<>(CORPUS);
        out.addAll(STARTERS);
        return out;
    }

    static Path file(String name) {
        String rel = CORPUS.contains(name)
                ? SpikeFixture.CORPUS_DIR + "/" + name + "/sculpt.json"
                : "src/main/resources/starter-library/species/" + name + "/sculpt.json";
        Path p = SpikeFixture.find(rel);
        assertTrue(p != null, "fixture missing: " + rel);
        return p;
    }

    static JsonObject doc(String name) {
        try {
            return JsonParser.parseString(Files.readString(file(name), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The parts the rig is handed: {@code ModelRepository.sculptedParts}' rule, kits expanded. */
    static List<CreatureSculpt.Part> load(String name) {
        JsonObject d = doc(name);
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(d.getAsJsonArray("parts"));
        if (d.has("kits") && d.get("kits").isJsonArray()) {
            List<CreatureSculpt.Part> grown = new ArrayList<>(parts);
            grown.addAll(SculptKits.expand(d.getAsJsonArray("kits"), parts));
            return grown;
        }
        return parts;
    }

    static double scaleOf(String name) {
        JsonObject d = doc(name);
        return d.has("scale") ? d.get("scale").getAsDouble() : 1.0;
    }
}
