package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the ATOM rig's arithmetic — {@code docs/ATOM_RIG_PLAN.md} Phase 0.</b>
 *
 * <p>The atom rig's claim is that a box's SIZE and TILT can leave the model file entirely and ride
 * on the display's transform, so that the model becomes a constant and a creature costs a stock
 * client no resource reload at all. Every part of that claim is arithmetic, so it is measured here
 * in milliseconds rather than looked at one photograph at a time.
 *
 * <p>Phase 0's own kill criterion is "none — this phase cannot fail, it can only measure". That is
 * true of the entity COUNT and it is not true of the geometry: a plan that puts a box in the wrong
 * place is a plan, and the point of doing it at Gate A is that it goes red here rather than in a
 * 200-second client run.
 *
 * <h2>The oracle, and why it is not {@code CreatureAnatomy} alone</h2>
 *
 * <p>{@link CreatureAnatomy#extents} is the axis-aligned union of the raw boxes and <b>ignores
 * rotation entirely</b> — deliberately, because it sizes a hitbox. So it is the right oracle for
 * translation, size and inflate (on an untilted creature) and cannot say anything about tilt.
 *
 * <p>For tilt the oracle is the mod renderer's own pipeline, written out independently in
 * {@link #expectedCorners}: {@code m -> chain(pivot + R(m - pivot)) * scale / 16}. That is the
 * formula in {@link RigTransform}'s class javadoc, computed from the PART rather than through the
 * plan, so a plan that agrees with it agrees with something other than itself.
 */
class RigPlanAtomTest {

    private static final float EPS = 1e-3f;

    private static List<CreatureSculpt.Part> spikor() {
        return SpikeFixture.parts(SpikeFixture.SPIKOR);
    }

    /** The synthetic fixtures the plan names, plus the two that carry inflate/asymmetry. */
    private static final List<String> SYNTHETIC = List.of(
            SpikeFixture.UNIT_BOX, SpikeFixture.TILT_X, SpikeFixture.TILT_Y, SpikeFixture.TILT_Z,
            SpikeFixture.TWO_BOX, SpikeFixture.TWO_SIZE, SpikeFixture.TWO_Y, SpikeFixture.ASYM,
            SpikeFixture.LEGS3, SpikeFixture.MULTI_TILT, SpikeFixture.CHAIN,
            SpikeFixture.TWO_GROUP, SpikeFixture.BIG_BOX, SpikeFixture.PLAN_BAR);

    // ── the number the whole plan rests on ───────────────────────────────────────────────────

    /**
     * <b>The Phase 0 ledger row.</b> Prints boxes / atoms / overlays / displays for every fixture
     * the spike corpus holds, and asserts the one relationship the atom plan is defined by.
     *
     * <p>The {@code groups} and {@code exact} columns went with the pack rig: they were the
     * other two arms of the A/B, and a column for a planner that no longer exists is a number with
     * nothing behind it.
     *
     * <p>It PRINTS as well as asserting because the plan's Phase 4 is "how many creatures before it
     * lags", and that question needs the per-creature entity count in the ledger beside the arm
     * that produced it — a number quoted from memory is how "62 entities is a lag machine" became
     * an assertion nobody had measured.
     */
    @Test
    @DisplayName("every box is its own display — and here is what that costs, per creature")
    void theEntityCountIsTheBoxCount() {
        StringBuilder table = new StringBuilder(
                "\n  fixture                      boxes  atoms  patt  displays\n");
        int boxesAll = 0;
        int atomsAll = 0;
        int pattAll = 0;
        int measured = 0;

        List<String> cases = new ArrayList<>(SpikeFixture.CASES);
        cases.addAll(SYNTHETIC);
        for (String name : cases) {
            if (!name.startsWith("__") && !SpikeFixture.available(name)) {
                // A cloud container may not carry the corpus. Saying so beats a green run over
                // nothing — the same reason SpikeFixture.CASES says "nine" out loud.
                table.append(String.format("  %-28s  (not available)%n", name));
                continue;
            }
            List<CreatureSculpt.Part> parts = SpikeFixture.parts(name);
            List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(parts);
            int boxes = parts.size();
            List<RigPlan.Group> plan = RigPlan.atoms(mirrored, true);
            int atoms = (int) plan.stream().filter(g -> !g.patternOverlay()).count();
            int patt = (int) plan.stream().filter(RigPlan.Group::patternOverlay).count();
            int wantPatt = (int) mirrored.stream()
                    .filter(x -> x.patterned() && AtomLibrary.patternModelData(x.pattern()) > 0)
                    .count();

            assertEquals(boxes, atoms, name + ": the atom plan is one BASE display per BOX, by "
                    + "definition — anything else means a box was dropped or duplicated");
            assertEquals(wantPatt, patt, name + ": one overlay per PATTERNED box (§2 B) — a "
                    + "patterned part with no overlay renders flat and nothing says so");
            assertEquals(boxes, RigPlan.atoms(mirrored, false).size(),
                    name + ": with the overlays off the plan is exactly Phase 1a's, which is what "
                            + "makes the A/B seam a seam rather than a second planner");

            table.append(String.format("  %-28s  %5d  %5d  %4d  %8d%n",
                    name, boxes, atoms, patt, atoms + patt));
            boxesAll += boxes;
            atomsAll += atoms;
            pattAll += patt;
            measured++;
        }
        table.append(String.format("  %-28s  %5d  %5d  %4d  %8d   (%d fixtures)%n",
                "TOTAL", boxesAll, atomsAll, pattAll, atomsAll + pattAll, measured));
        System.out.println(table);

        assertEquals(boxesAll, atomsAll, "the total must be the box total too");
    }

    @Test
    @DisplayName("Spikor is 62 boxes, 62 atoms — and 64 displays with his scales on")
    void spikorsOwnNumbers() {
        // The grouped count (6) that used to sit beside these was the pack arm's, and went with it
        //. What is left is the atom rig's own cost, which is the number that ships.
        List<CreatureSculpt.Part> parts = RigTransform.mirrorIntoWorldSpace(spikor());
        assertEquals(62, parts.size(), "the fixture should be the measured 62-part Spikor");
        assertEquals(62, RigPlan.atoms(parts, false).size(),
                "one display per box and no per-creature pack: 62 entities for 62 boxes");

        // §2 B's cost, stated as the number rather than as "a couple more". Spikor's `body` and
        // `head` are both `scales`, so the pattern arm is 62 + 2.
        List<RigPlan.Group> withPatterns = RigPlan.atoms(parts, true);
        assertEquals(64, withPatterns.size(), "62 boxes + one overlay per patterned box");
        assertEquals(2, withPatterns.stream().filter(RigPlan.Group::patternOverlay).count());
        assertEquals(List.of("body#pattern", "head#pattern"),
                withPatterns.stream().filter(RigPlan.Group::patternOverlay)
                        .map(RigPlan.Group::name).sorted().toList());
    }

    /**
     * <b>§2 B: a patterned box is TWO displays, and everything but the model, the tint and a hair
     * of scale is shared.</b>
     *
     * <p>The pair is planned by copying the base group rather than re-deriving it from the part, so
     * this asserts the copy: same box, same origin, same pivot, same rest tilt, same chain, and the
     * overlay immediately after its base so {@code DisplayRig.adopt}'s index run stays contiguous.
     * A re-derivation that drifted would put a creature's stripes somewhere near its skin, which is
     * the {@code TrainFraming} shape {@code docs/CAMERA.md} records.
     */
    @Test
    @DisplayName("a patterned part plans as two atoms; an unpatterned one as one")
    void aPatternedPartIsTwoAtoms() {
        CreatureSculpt.Part flat = new CreatureSculpt.Part("plain", null, new float[]{0, 4, 0},
                new float[]{-3, 0, -3}, new float[]{3, 8, 3}, 0x112233,
                CreatureSculpt.NO_ROTATION, 0f, "", 0);
        CreatureSculpt.Part patterned = new CreatureSculpt.Part("shell", null, new float[]{0, 4, 0},
                new float[]{-6, 0, -2}, new float[]{6, 10, 2}, 0x00AA00,
                new float[]{0, 0, 17f}, 1.5f, "scales", 0xFFCC00);

        assertEquals(1, RigPlan.atoms(List.of(flat), true).size(),
                "an unpatterned box costs exactly one display");

        List<RigPlan.Group> plan = RigPlan.atoms(List.of(flat, patterned), true);
        assertEquals(List.of("plain", "shell", "shell#pattern"),
                plan.stream().map(RigPlan.Group::name).toList(),
                "the overlay sits immediately after its own base — `adopt` recovers order from a "
                        + "0..n-1 run and would otherwise pair a creature's stripes with its leg");

        RigPlan.Group base = plan.get(1);
        RigPlan.Group over = plan.get(2);
        assertTrue(!base.patternOverlay() && over.patternOverlay());
        assertEquals("shell", over.boneName(),
                "the overlay animates as the bone it is drawn on, or the pattern stands still while "
                        + "the box walks");
        assertEquals(base.boneName(), over.boneName());
        assertEquals(base.parts(), over.parts(), "the same box, not a copy of it");
        assertArrayEquals(base.originUnits(), over.originUnits(), 0f);
        assertArrayEquals(base.pivotUnits(), over.pivotUnits(), 0f);
        assertArrayEquals(base.restRotation(), over.restRotation(), 0f);
        assertArrayEquals(base.chainRotationXyzw(), over.chainRotationXyzw(), 0f);
        assertArrayEquals(base.chainTranslation(), over.chainTranslation(), 0f);
        assertEquals(base.shrink(), over.shrink());
        assertArrayEquals(RigPlan.atomSizeUnits(base), RigPlan.atomSizeUnits(over), 0f,
                "atomSizeUnits stays HONEST — it answers how big the BOX is, and the overlay's "
                        + "hair is added in blocks by RigTransform.atomise, after the scale");
    }

    /**
     * <b>The hair, measured</b> — {@link RigPlan#PATTERN_LIFT_BLOCKS} per FACE, so twice that per
     * axis of scale, and not one bit of it in the translation.
     *
     * <p>Two things this is really asking. That the two cubes stay CONCENTRIC, because a lift that
     * moved the translation would slide the pattern off one end of the box. And that the lift does
     * not ride the creature's scale: Z-fighting is a property of the depth buffer, so a 4× creature
     * needs the same absolute separation a 1× one does, and a lift folded into the size would give
     * it four times as much and float its pattern visibly off its own skin.
     */
    @Test
    @DisplayName("the pattern overlay is lifted a hair, symmetrically, and not by the creature scale")
    void theOverlayIsLiftedByAConstantInBlocks() {
        CreatureSculpt.Part patterned = new CreatureSculpt.Part("shell", null, new float[]{0, 4, 0},
                new float[]{-6, 0, -2}, new float[]{6, 10, 2}, 0x00AA00,
                new float[]{0, 0, 17f}, 0f, "scales", 0xFFCC00);
        List<RigPlan.Group> plan = RigPlan.atoms(List.of(patterned), true);

        for (double scale : new double[]{0.5, 1.0, 4.0}) {
            RigTransform.Placed base = RigTransform.placeAtom(plan.get(0), scale);
            RigTransform.Placed over = RigTransform.placeAtom(plan.get(1), scale);

            assertVec(base.translation(), over.translation(),
                    "scale " + scale + ": the two cubes must stay concentric");
            assertEquals(base.leftRotation(), over.leftRotation(),
                    "the overlay carries the same tilt, or the pattern shears off the box");

            float want = 2f * RigPlan.PATTERN_LIFT_BLOCKS;
            assertEquals(want, over.scale().x() - base.scale().x(), 1e-6f, "x at scale " + scale);
            assertEquals(want, over.scale().y() - base.scale().y(), 1e-6f, "y at scale " + scale);
            assertEquals(want, over.scale().z() - base.scale().z(), 1e-6f, "z at scale " + scale);
        }

        assertTrue(RigPlan.PATTERN_LIFT_BLOCKS > 0f && RigPlan.PATTERN_LIFT_BLOCKS < 0.1f,
                "zero lifts nothing and Z-fights; a big one is a pattern floating off its own box");
    }

    /**
     * The atom plan takes no {@code alsoSplit} set because there is nothing left to split. Asserted
     * rather than merely written down, because an overload that quietly ignored its argument would
     * be a far worse thing to leave lying about than one that does not exist.
     */
    @Test
    @DisplayName("the animated-bones set cannot change an atom plan — every box is already split")
    void animatedBonesAreASatisfiedConstraint() {
        List<CreatureSculpt.Part> parts = RigTransform.mirrorIntoWorldSpace(spikor());
        List<RigPlan.Group> plain = RigPlan.atoms(parts);
        List<RigPlan.Group> withSet = RigPlan.atoms(parts, java.util.Set.of("head", "legL", "legR"));
        assertEquals(plain.size(), withSet.size());
        for (int i = 0; i < plain.size(); i++) {
            assertEquals(plain.get(i).name(), withSet.get(i).name());
        }
        // The control that used to follow — the same set DOES change the exact plan — went with
        // that planner. Every box being its own display is asserted directly instead.
        assertEquals(parts.size(), RigPlan.atoms(parts, false).size(),
                "one display per box is why there is nothing left for the set to split");
    }

    // ── geometry: the box lands where the creature says it does ──────────────────────────────

    /**
     * <b>The corner test — size, tilt, inflate, pivot and the ancestor chain in one assertion.</b>
     *
     * <p>Runs the eight corners of every box of every fixture through BOTH the atom plan and an
     * independent transcription of the mod renderer's own bone pipeline, and requires them to land
     * in the same place. It is the strongest oracle available without a client, and it is the one
     * that would go red if the display's scale carried the wrong axis, if the tilt were applied
     * before the scale, or if a nested bone's parent rotation were dropped.
     */
    @Test
    @DisplayName("every corner of every box lands where the bone chain says it should")
    void thePlacedAtomsMatchTheBoneChain() {
        for (String name : allAvailable()) {
            double scale = SpikeFixture.scale(name);
            List<CreatureSculpt.Part> mirrored =
                    RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(name));
            Map<String, CreatureSculpt.Part> byName = index(mirrored);
            // BASES only: an overlay is the same box at the same transform, so it has nothing new
            // to say to a corner oracle, and it breaks the 1:1 with the sculpt this loop rests on.
            // Its own geometry is asserted against its base in theOverlayIsLiftedByAConstantInBlocks.
            List<RigPlan.Group> atoms = bases(RigPlan.atoms(mirrored));

            for (int i = 0; i < atoms.size(); i++) {
                RigPlan.Group g = atoms.get(i);
                CreatureSculpt.Part p = mirrored.get(i);
                assertEquals(p.name(), g.name(),
                        name + ": the atom plan must keep the sculpt's own order");

                Vector3f[] want = expectedCorners(p, byName, scale);
                Vector3f[] got = placedCorners(RigSeparationTest.unseparated(
                        RigTransform.placeAtom(g, scale), g, scale));
                for (int c = 0; c < 8; c++) {
                    assertVec(want[c], got[c],
                            name + " / " + p.name() + " corner " + c);
                }
            }
        }
    }

    @Test
    @DisplayName("the display's scale IS the box's size in blocks, inflate included")
    void inflateGrowsTheScaleOnEveryAxis() {
        // A 8x16x4 box, inflated by 1.5 on every face -> 11 x 19 x 7 units -> /16 blocks.
        CreatureSculpt.Part fat = new CreatureSculpt.Part(
                "fat", null, new float[]{0, 0, 0},
                new float[]{2, 4, 6}, new float[]{10, 20, 10}, 0xFF0000,
                CreatureSculpt.NO_ROTATION, 1.5f, "", 0);
        CreatureSculpt.Part thin = new CreatureSculpt.Part(
                "thin", null, new float[]{0, 0, 0},
                new float[]{2, 4, 6}, new float[]{10, 20, 10}, 0xFF0000);

        RigPlan.Group gf = RigPlan.atoms(List.of(fat)).get(0);
        RigPlan.Group gt = RigPlan.atoms(List.of(thin)).get(0);
        Vector3f sf = RigTransform.placeAtom(gf, 1.0).scale();
        Vector3f st = RigTransform.placeAtom(gt, 1.0).scale();

        assertEquals(8f / 16f, st.x(), EPS);
        assertEquals(16f / 16f, st.y(), EPS);
        assertEquals(4f / 16f, st.z(), EPS);
        assertEquals((8f + 3f) / 16f, sf.x(), EPS, "inflate adds 2*1.5 on X");
        assertEquals((16f + 3f) / 16f, sf.y(), EPS, "…and on Y");
        assertEquals((4f + 3f) / 16f, sf.z(), EPS, "…and on Z");

        // And the creature's scale multiplies all three, because the model cannot absorb it here.
        Vector3f big = RigTransform.placeAtom(gt, 2.5).scale();
        assertEquals(st.x() * 2.5f, big.x(), EPS);
        assertEquals(st.y() * 2.5f, big.y(), EPS);
        assertEquals(st.z() * 2.5f, big.z(), EPS);
    }

    /**
     * <b>The kill risk, stated as a fact rather than as a worry.</b>
     *
     * <p>The deleted pack rig kept its display transform orthonormal by folding the
     * creature's scale into a per-creature MODEL, because a non-orthonormal transform takes vanilla
     * down the inverse-transpose normal path and side faces measured 0.800 against 0.737. The atom
     * rig cannot do that — one shared model — so its scale is non-uniform for any box that is not a cube. This
     * pins that so nobody later "fixes" the atom scale into a uniform one and wonders why every
     * creature is a stack of cubes.
     */
    @Test
    @DisplayName("an atom's display scale is genuinely non-uniform — that is the design, and the risk")
    void theAtomScaleIsNonUniformByConstruction() {
        RigPlan.Group g = RigPlan.atoms(SpikeFixture.twoSize()).get(0);   // 8 x 24 x 8
        Vector3f s = RigTransform.placeAtom(g, 1.0).scale();
        assertTrue(Math.abs(s.x() - s.y()) > 0.1f,
                "the tall box must be drawn taller than it is wide, which means a scale vanilla "
                        + "will not treat as orthonormal — docs/ATOM_RIG_PLAN.md §3 risk 1");
        assertEquals(s.x(), s.z(), EPS, "…and square in plan, because the box is");
    }

    /**
     * <b>Extents, against {@code CreatureAnatomy} — the hitbox oracle.</b>
     *
     * <p>Untilted parts only, and that is not a dodge: {@code CreatureAnatomy.extents} is the
     * axis-aligned union of the raw boxes and ignores rotation by design, because it sizes a hitbox
     * rather than a silhouette. Comparing a rotated creature against it would be measuring the
     * approximation, not the plan. The tilted case is {@link #thePlacedAtomsMatchTheBoneChain}.
     *
     * <h2>The tolerance is the pattern lift, and nothing else</h2>
     *
     * <p>Until it also carried {@code FightingFaces.MAX_GROWTH}: {@code BedrockGeometry.fromJson}
     * grew coplanar boxes, so {@code CreatureAnatomy} measured a creature very slightly larger than
     * the sculpt, while <b>no display rig applied that separation at all</b> — the flicker the owner
     * filmed. The separation now lives in the rig ({@link RigSeparation}), the parser returns the
     * sculpt as authored, and this compares the rig with its separation taken out, so the two
     * agree exactly.
     */
    @Test
    @DisplayName("an untilted creature's placed atoms span exactly what CreatureAnatomy measures")
    void theExtentsMatchTheAnatomy() {
        for (String name : allAvailable()) {
            List<CreatureSculpt.Part> flat = SpikeFixture.unrotated(SpikeFixture.parts(name));
            double scale = SpikeFixture.scale(name);
            CreatureAnatomy.Extents want = CreatureAnatomy.extents(RigGeometry.of(flat));

            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (RigPlan.Group g : RigPlan.atoms(RigTransform.mirrorIntoWorldSpace(flat))) {
                for (Vector3f c : placedCorners(RigSeparationTest.unseparated(
                        RigTransform.placeAtom(g, scale), g, scale))) {
                    float[] v = {c.x(), c.y(), c.z()};
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], v[i]);
                        hi[i] = Math.max(hi[i], v[i]);
                    }
                }
            }
            // Back into sculpt units, and un-mirror X: the rig works in reflected space, so its
            // world minX is the sculpt's -maxX. Reading that the other way round would report a
            // symmetric creature as perfect and every asymmetric one as broken.
            float k = (float) (RigPlan.UNITS_PER_BLOCK / scale);
            // ONE tolerance, §2 B's: the pattern overlay is lifted clear of its own base, so the
            // RIG is marginally bigger than the anatomy wherever a patterned box is outermost.
            // There used to be a second — FightingFaces.MAX_GROWTH — because the parser grew
            // coplanar boxes and no rig did. Now the parser returns the sculpt as authored,
            // the rig's separation is taken back out above (RigSeparationTest.unseparated, and
            // bounded in RigSeparationTest.sizesOnly), and the two agree exactly again.
            //
            // ⚠ In SCULPT UNITS, so the lift is converted: it is 1/32 of a BLOCK after the scale,
            // i.e. 0.227 units on a 2.2x Spikor. This line used to add the block figure to a unit
            // tolerance, which was wrong by 7x and hidden by the MAX_GROWTH slack beside it
            // (the patterned Spikor arm's patterned shell is outermost on maxZ: 14.227 against 14.0).
            float tol = RigPlan.PATTERN_LIFT_BLOCKS * k + 1e-2f;
            float out = RigPlan.PATTERN_LIFT_BLOCKS * k + 1e-2f;
            assertEquals(want.minX(), -hi[0] * k, tol, name + " minX");
            assertEquals(want.maxX(), -lo[0] * k, tol, name + " maxX");
            assertEquals(want.minY(), lo[1] * k, tol, name + " minY");
            assertEquals(want.maxY(), hi[1] * k, tol, name + " maxY");
            assertEquals(want.minZ(), lo[2] * k, tol, name + " minZ");
            assertEquals(want.maxZ(), hi[2] * k, tol, name + " maxZ");
            // …and the gap is only ever the separation nudge, never the other way: a rig box may
            // not stick OUT of what the anatomy measured, because that is the hitbox a child's
            // sword has to reach. Direction, not just magnitude — a symmetric tolerance would let
            // a genuinely oversized rig hide inside it.
            assertTrue(-hi[0] * k >= want.minX() - out && -lo[0] * k <= want.maxX() + out,
                    name + ": the rig reaches outside its own hitbox on X");
            assertTrue(lo[1] * k >= want.minY() - out && hi[1] * k <= want.maxY() + out,
                    name + ": the rig reaches outside its own hitbox on Y");
            assertTrue(lo[2] * k >= want.minZ() - out && hi[2] * k <= want.maxZ() + out,
                    name + ": the rig reaches outside its own hitbox on Z");
        }
    }

    /**
     * <b>Landmarks — where an ability aims — reduced to the quantity they are made of.</b>
     *
     * <p>{@code CreatureAnatomy.landmarks} is an AVERAGE of cube centres under a keyword table with
     * a claim rule ({@code claimedBy}), so re-deriving which bones feed which label here would be a
     * second copy of that table — and a test whose own copy of the rule drifts reports a creature as
     * broken when the test is. What every landmark is built from, before any of that, is one number
     * per bone: {@code up = cy/16*s}, {@code forward = -cz/16*s}.
     *
     * <p>So this asserts THAT, for every part rather than for the ten that get reported: an atom's
     * display translation is exactly its box's centre in blocks. A landmark cannot move unless this
     * does, and this cannot be true for the wrong reason.
     */
    @Test
    @DisplayName("an atom sits exactly on its box's centre — the number every landmark averages")
    void everyAtomSitsOnTheCentreALandmarkWouldReport() {
        for (String name : allAvailable()) {
            List<CreatureSculpt.Part> flat = SpikeFixture.unrotated(SpikeFixture.parts(name));
            double scale = SpikeFixture.scale(name);
            List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(flat);
            List<RigPlan.Group> atoms = bases(RigPlan.atoms(mirrored));
            for (int i = 0; i < atoms.size(); i++) {
                CreatureSculpt.Part p = flat.get(i);
                Vector3f t = RigTransform.placeAtom(atoms.get(i), scale).translation();
                double k = scale / RigPlan.UNITS_PER_BLOCK;
                // Inflate is symmetric, so it moves the extent and never the centre — which is also
                // why CreatureAnatomy.landmarks can ignore it and CreatureAnatomy.extents cannot.
                assertEquals((p.from()[1] + p.to()[1]) / 2.0 * k, t.y(), 1e-3,
                        name + " / " + p.name() + ": the 'up' a landmark would report");
                assertEquals((p.from()[2] + p.to()[2]) / 2.0 * k, t.z(), 1e-3,
                        name + " / " + p.name() + ": the 'forward' a landmark would report");
                assertEquals(-(p.from()[0] + p.to()[0]) / 2.0 * k, t.x(), 1e-3,
                        name + " / " + p.name() + ": X, mirrored into rig space");
            }

            // …and the real thing still resolves, so the reduction above is not standing in for a
            // landmark list that turns out to be empty.
            assertTrue(!CreatureAnatomy.landmarks(RigGeometry.of(flat), scale).isEmpty()
                            || name.startsWith("__"),
                    name + " should report at least one landmark");
        }
    }

    // ── the ancestor chain, which is where the plan diverges on purpose ──────────────────────

    /**
     * A deeper parent graph than Spikor's — the case the deleted grouped planner got wrong
     * for an articulation point and {@link RigPlan#atoms} gets right. The divergence note that used
     * to close this test compared against that planner and went with it; the corner oracle is the
     * claim that survives.
     */
    @Test
    @DisplayName("a box under two tilted ancestors carries BOTH of their rotations")
    void theWholeChainRidesOnEveryAtom() {
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("torso", null, new float[]{0, 8, 0},
                        new float[]{-4, 0, -2}, new float[]{4, 16, 2}, 0x333333,
                        new float[]{0, 0, 20f}, 0f, "", 0),
                new CreatureSculpt.Part("neck", "torso", new float[]{0, 16, 0},
                        new float[]{-2, 16, -2}, new float[]{2, 22, 2}, 0x666666,
                        new float[]{13f, 0, 0}, 0f, "", 0),
                new CreatureSculpt.Part("head", "neck", new float[]{0, 22, 0},
                        new float[]{-5, 22, -5}, new float[]{5, 30, 5}, 0x999999,
                        new float[]{0, 7f, 0}, 0f, "", 0));

        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(parts);
        Map<String, CreatureSculpt.Part> byName = index(mirrored);
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored);
        assertEquals(3, atoms.size(), "three unpatterned boxes, so three displays");

        // The corner oracle is the real assertion; this one names WHY it holds, so a regression
        // reads as "the chain was dropped" rather than as "some corner moved".
        assertTrue(isIdentity(atoms.get(0).chainRotationXyzw()), "the torso has no ancestors");
        assertTrue(!isIdentity(atoms.get(1).chainRotationXyzw()), "the neck inherits the torso");
        assertTrue(!isIdentity(atoms.get(2).chainRotationXyzw()),
                "the head inherits the neck AND the torso — the case a flat graph cannot show");

        for (int i = 0; i < atoms.size(); i++) {
            Vector3f[] want = expectedCorners(mirrored.get(i), byName, 1.0);
            Vector3f[] got = placedCorners(RigTransform.placeAtom(atoms.get(i), 1.0));
            for (int c = 0; c < 8; c++) {
                assertVec(want[c], got[c], "chain / " + mirrored.get(i).name() + " corner " + c);
            }
        }

    }

    @Test
    @DisplayName("a grouped plan handed to the atom sizing is refused, not silently mis-sized")
    void aGroupedPlanOnTheAtomPathIsALoudFailure() {
        // The grouped planner that used to build this fixture is gone, but the guard is
        // not: RigPlan.Group is still a list of boxes, so a two-box group is still expressible
        // and still has no single size. Built by hand from the two-size fixture's own parts.
        List<CreatureSculpt.Part> two = RigTransform.mirrorIntoWorldSpace(SpikeFixture.twoSize());
        assertEquals(2, two.size(), "the fixture should be two boxes");
        RigPlan.Group grouped = new RigPlan.Group("twoSize", two, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1.0f);
        assertEquals(2, grouped.boxCount());
        assertThrows(IllegalArgumentException.class,
                () -> RigPlan.atomSizeUnits(grouped),
                "two boxes in one group have no single size, and drawing them both at the first "
                        + "one's is the sort of wrong that photographs plausibly");
    }

    @Test
    @DisplayName("an atom needs no shrink and no model window, whatever the box's size")
    void thereIsNoVanillaWindowToFit() {
        // 60 units on a side — well past MAX_SPAN, which forced the deleted grouped plan to
        // shrink (its control half went with it). The atom half is the claim that ships.
        CreatureSculpt.Part huge = new CreatureSculpt.Part(
                "huge", null, new float[]{0, 0, 0},
                new float[]{-30, 0, -30}, new float[]{30, 60, 30}, 0x00FF00);
        assertTrue(60f > RigPlan.MAX_SPAN, "the fixture must exceed the old model window");

        RigPlan.Group atom = RigPlan.atoms(List.of(huge)).get(0);
        assertEquals(1.0f, atom.shrink(), 1e-6f, "an atom's model is a unit cube and always fits");
        assertEquals(60f / 16f, RigTransform.placeAtom(atom, 1.0).scale().x(), EPS,
                "…and its size is carried whole on the display rather than shrunk and multiplied");
    }

    @Test
    @DisplayName("an empty or null sculpt plans to nothing rather than throwing")
    void nothingPlansToNothing() {
        assertEquals(List.of(), RigPlan.atoms(null));
        assertEquals(List.of(), RigPlan.atoms(List.of()));
    }

    @Test
    @DisplayName("a part with no pivot turns about its own centre")
    void amissingPivotFallsBackToTheCentre() {
        CreatureSculpt.Part p = new CreatureSculpt.Part("b", null, null,
                new float[]{4, 4, 4}, new float[]{12, 20, 8}, 0xFF00FF,
                new float[]{0, 45f, 0}, 0f, "", 0);
        RigPlan.Group g = RigPlan.atoms(List.of(p)).get(0);
        assertNotNull(g.pivotUnits());
        assertEquals(8f, g.originUnits()[0], EPS);
        assertEquals(12f, g.originUnits()[1], EPS);
        assertEquals(6f, g.originUnits()[2], EPS);
        for (int i = 0; i < 3; i++) {
            assertEquals(g.originUnits()[i], g.pivotUnits()[i], EPS,
                    "with no pivot the box turns about itself, so the display does not move");
        }
        // …and that is observable: a 45-degree turn about its own centre leaves the translation
        // exactly where an untilted box would sit.
        Vector3f t = RigTransform.placeAtom(g, 1.0).translation();
        assertEquals(8f / 16f, t.x(), EPS);
        assertEquals(12f / 16f, t.y(), EPS);
        assertEquals(6f / 16f, t.z(), EPS);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static List<String> allAvailable() {
        List<String> out = new ArrayList<>();
        for (String c : SpikeFixture.CASES) {
            if (SpikeFixture.available(c)) {
                out.add(c);
            }
        }
        out.addAll(SYNTHETIC);
        return out;
    }

    private static Map<String, CreatureSculpt.Part> index(List<CreatureSculpt.Part> parts) {
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        return byName;
    }

    private static boolean isIdentity(float[] q) {
        return Math.abs(q[0]) < 1e-6 && Math.abs(q[1]) < 1e-6 && Math.abs(q[2]) < 1e-6
                && Math.abs(Math.abs(q[3]) - 1) < 1e-6;
    }

    private static boolean matches(String bone, String label) {
        String b = bone.toLowerCase(java.util.Locale.ROOT);
        return b.contains(label) || (label.endsWith("s") && b.contains(label.substring(0,
                label.length() - 1)));
    }

    /**
     * The eight corners of one box in world space, from the PART, through the mod renderer's own
     * bone pipeline — {@code m -> chain(pivot + R(m - pivot)) * scale / 16}.
     *
     * <p>Independent of {@link RigPlan} and {@link RigTransform#placeAtom} on purpose: an oracle
     * derived from the thing it is testing agrees with it by construction.
     */
    private static Vector3f[] expectedCorners(CreatureSculpt.Part p,
                                              Map<String, CreatureSculpt.Part> byName,
                                              double scale) {
        float inf = p.inflate();
        float[] lo = new float[3];
        float[] hi = new float[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(p.from()[i], p.to()[i]) - inf;
            hi[i] = Math.max(p.from()[i], p.to()[i]) + inf;
        }
        Vector3f pivot = new Vector3f(p.pivot()[0], p.pivot()[1], p.pivot()[2]);
        Quaternionf own = RigTransform.rotation(p.rotation());

        // The ancestors, composed root-first — the same product RigPlan.ancestorChain builds, but
        // written out here so the two can disagree.
        List<CreatureSculpt.Part> chain = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String at = p.parent();
        while (at != null && !at.isEmpty() && seen.add(at)) {
            CreatureSculpt.Part a = byName.get(at);
            if (a == null) {
                break;
            }
            chain.add(0, a);
            at = a.parent();
        }

        Vector3f[] out = new Vector3f[8];
        int n = 0;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    Vector3f m = new Vector3f(xi == 0 ? lo[0] : hi[0],
                            yi == 0 ? lo[1] : hi[1], zi == 0 ? lo[2] : hi[2]);
                    // this bone: pivot + own*(m - pivot)
                    Vector3f v = new Vector3f(m).sub(pivot).rotate(own).add(pivot);
                    // then each ancestor, innermost first
                    for (int k = chain.size() - 1; k >= 0; k--) {
                        CreatureSculpt.Part a = chain.get(k);
                        Vector3f ap = new Vector3f(a.pivot()[0], a.pivot()[1], a.pivot()[2]);
                        v.sub(ap).rotate(RigTransform.rotation(a.rotation())).add(ap);
                    }
                    out[n++] = v.mul((float) (scale / RigPlan.UNITS_PER_BLOCK));
                }
            }
        }
        return out;
    }

    /**
     * The eight corners the DISPLAY will actually draw: {@code world = T + L·(S ⊙ c)} over the unit
     * cube's own corners, which after the item renderer's {@code -0.5} sit at ±0.5.
     *
     * <p>Enumerated in the same order as {@link #expectedCorners}, so a mismatch names a corner
     * rather than reporting a permuted set as broken.
     */
    private static Vector3f[] placedCorners(RigTransform.Placed p) {
        Vector3f[] out = new Vector3f[8];
        int n = 0;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    Vector3f c = new Vector3f(
                            (xi == 0 ? -0.5f : 0.5f) * p.scale().x(),
                            (yi == 0 ? -0.5f : 0.5f) * p.scale().y(),
                            (zi == 0 ? -0.5f : 0.5f) * p.scale().z());
                    out[n++] = c.rotate(p.leftRotation()).add(p.translation());
                }
            }
        }
        return out;
    }

    /**
     * The plan's BASE atoms — one per box, in the sculpt's own order.
     *
     * <p>Every geometry oracle here pairs {@code atoms.get(i)} with {@code parts.get(i)}, and §2 B's
     * pattern overlay is a SECOND display of a box that already has one. Filtering rather than
     * planning with {@code atoms(parts, false)} on purpose: this way the oracle still runs over the
     * plan the rig actually ships, and an overlay landing in the wrong place would show up as an
     * ordering failure rather than never being planned at all.
     */
    private static List<RigPlan.Group> bases(List<RigPlan.Group> plan) {
        return plan.stream().filter(g -> !g.patternOverlay()).toList();
    }

    private static void assertVec(Vector3f want, Vector3f got, String what) {
        assertEquals(want.x(), got.x(), EPS, what + " x");
        assertEquals(want.y(), got.y(), EPS, what + " y");
        assertEquals(want.z(), got.z(), EPS, what + " z");
    }

    /**
     * <b>The {@code !nopattern} seam strips the pattern and NOTHING else.</b>
     *
     * <p>Phase 2 uses it to separate the atom arm's one recorded fidelity loss (a patterned box
     * renders flat — §5.3 deviation 2) from whatever else the arm gets wrong, by photographing the
     * same creature twice and subtracting. That subtraction is only worth anything if the seam
     * moves exactly one thing, so this asserts it field by field rather than trusting the diff:
     * every other component of every part is {@code equals} to the original, the pattern really is
     * gone on the parts that had one, and the creature still has the boxes it started with.
     *
     * <p>The {@code patternColour} is deliberately LEFT ALONE — it is inert once the pattern name
     * is blank, and zeroing it too would make the seam two changes instead of one.
     */
    @Test
    void theNoPatternSeamStripsThePatternAndNothingElse() {
        int stripped = 0;
        for (String name : SpikeFixture.CASES) {
            if (!SpikeFixture.available(name)) {
                continue;
            }
            List<CreatureSculpt.Part> base = SpikeFixture.parts(name);
            List<CreatureSculpt.Part> flat = SpikeFixture.parts(name + "!nopattern");
            assertEquals(base.size(), flat.size(), name + ": the strip changed the box count");
            for (int i = 0; i < base.size(); i++) {
                CreatureSculpt.Part a = base.get(i);
                CreatureSculpt.Part b = flat.get(i);
                assertEquals("", b.pattern(), name + "/" + a.name() + ": pattern survived");
                if (!a.pattern().isEmpty()) {
                    stripped++;
                }
                assertEquals(a.name(), b.name(), name + ": name moved");
                assertEquals(a.parent(), b.parent(), name + ": parent moved");
                assertArrayEquals(a.pivot(), b.pivot(), 0f, name + ": pivot moved");
                assertArrayEquals(a.from(), b.from(), 0f, name + ": from moved");
                assertArrayEquals(a.to(), b.to(), 0f, name + ": to moved");
                assertEquals(a.colour(), b.colour(), name + ": colour moved");
                assertArrayEquals(a.rotation(), b.rotation(), 0f, name + ": rotation moved");
                assertEquals(a.inflate(), b.inflate(), 0.0, name + ": inflate moved");
                assertEquals(a.patternColour(), b.patternColour(),
                        name + ": patternColour moved — it is inert once the name is blank, and "
                                + "changing it too would make the seam two changes");
            }
            assertEquals(SpikeFixture.scale(name), SpikeFixture.scale(name + "!nopattern"), 0.0,
                    name + ": the strip moved the creature's scale");
        }
        // A seam that strips nothing is a seam that cannot separate anything, and the whole of
        // Phase 2's pattern arithmetic rests on this being non-zero on the corpus it runs over.
        assertTrue(stripped >= 3, "the corpus should carry at least the three patterned parts "
                + "Phase 2 names (spikor body + head, the hoodie body); found " + stripped);
    }
}
