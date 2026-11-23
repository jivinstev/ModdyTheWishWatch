package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the vanilla-creature spike's arithmetic.</b>
 *
 * <p>Everything the spike claims about placement — a transform that puts a tilted bone back on its
 * pivot, a mirror that is a coordinate conversion rather than a content fix, a base chosen on its
 * width error — is arithmetic, so it is checked here in milliseconds rather than eyeballed one
 * screenshot at a time.
 *
 * <p>The grouped planner's own tests — 62 boxes collapsing to 6 groups, the {@code [-16, 32]}
 * model window, the shrink that fitted an oversized group into it — went with the pack rig
 *. What survives is re-pinned on {@link RigPlan#atoms}, the only plan that ships; the atom
 * plan's own counts and geometry live in {@code RigPlanAtomTest}.
 *
 * <p>Measured against the REAL fixture, {@link SpikeFixture#SPIKOR} (a real 62-part sculpt where the private corpus is,
 * its public stand-in elsewhere), because the spike's constraint is that existing sculpts are the input. A hand-written fixture
 * would let the plan be wrong in exactly the way a real creature exposes.
 */
class RigPlanTest {

    private static List<CreatureSculpt.Part> spikor() {
        return SpikeFixture.parts(SpikeFixture.SPIKOR);
    }

    // ── the parent graph, which the atom plan walks for every box's ancestor chain ──────────

    /**
     * Re-pinned on the atom plan. The grouped planner answered this by folding the part
     * into {@code root}; the atom plan answers it in {@code ancestorChain}, which must stop at the
     * missing name and hand back the identity rather than inventing a bone or throwing.
     */
    @Test
    @DisplayName("a parent naming a part that does not exist is ignored, as the mod renderer does")
    void adanglingParentDoesNotInventABone() {
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("a", "ghost", new float[]{0, 0, 0},
                        new float[]{0, 0, 0}, new float[]{1, 1, 1}, 0xFFFFFF),
                new CreatureSculpt.Part("b", null, new float[]{0, 0, 0},
                        new float[]{0, 0, 0}, new float[]{1, 1, 1}, 0xFFFFFF));
        List<RigPlan.Group> atoms = RigPlan.atoms(parts, false);
        assertEquals(List.of("a", "b"), atoms.stream().map(RigPlan.Group::name).toList(),
                "a dangling parent must not create a display of its own");
        float[] q = atoms.get(0).chainRotationXyzw();
        float[] t = atoms.get(0).chainTranslation();
        assertEquals(1f, q[3], 1e-6f, "a ghost ancestor contributes no rotation");
        assertEquals(0f, Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]), 1e-6f);
        assertEquals(0f, Math.abs(t[0]) + Math.abs(t[1]) + Math.abs(t[2]), 1e-6f,
                "…and no translation");
    }

    @Test
    @DisplayName("a parent cycle terminates instead of hanging the server")
    void acycleDoesNotHang() {
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("a", "b", new float[]{0, 0, 0},
                        new float[]{0, 0, 0}, new float[]{1, 1, 1}, 0xFFFFFF),
                new CreatureSculpt.Part("b", "a", new float[]{0, 0, 0},
                        new float[]{0, 0, 0}, new float[]{1, 1, 1}, 0xFFFFFF));
        // Re-pinned on the atom plan: its ancestor walk is the loop a cycle could spin.
        List<RigPlan.Group> atoms = RigPlan.atoms(parts, false);
        assertNotNull(atoms, "a cycle must not spin forever");
        assertEquals(2, atoms.size(), "and both boxes still get a display");
    }

    // ── the transform ────────────────────────────────────────────────────────────────────────

    /**
     * The derivation is {@code T = (P + R·(C − P))·s/16}. With no rotation that collapses to
     * {@code C·s/16}, which is the case every untilted group takes, so it is worth pinning
     * separately from the rotated one.
     */
    @Test
    @DisplayName("an untilted group sits at its own centre")
    void anUntiltedGroupSitsAtItsCentre() {
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("box", null, new float[]{0, 0, 0},
                        new float[]{0, 0, 0}, new float[]{16, 16, 16}, 0xFFFFFF));
        // Re-pinned on the atom plan; a one-box group is the same group under either plan.
        RigPlan.Group g = RigPlan.atoms(parts, false).get(0);
        RigTransform.Placed p = RigTransform.place(g, 1.0);
        assertEquals(8.0 / 16.0, p.translation().y(), 1e-5,
                "a 16-unit box centred at y=8 sits half a block up");
        assertEquals(1.0f, p.scale().x(), 1e-6);
    }

    /**
     * <b>The rotation must move the translation with it.</b> A rig that placed the group at its
     * centre and then rotated would swing every tilted group away from its pivot — and it would
     * look exactly like an animation bug, which is the hardest kind of wrong to attribute.
     */
    @Test
    @DisplayName("a tilted group rotates about its pivot, not about its own centre")
    void atiltedGroupTurnsOnItsPivot() {
        // A bar reaching along +X from a pivot at the origin, SYMMETRIC in Y and Z about that
        // pivot so its centre is exactly (16,0,0) and the expected answer is obvious by
        // inspection rather than re-derived with the formula under test: turn it 90 degrees about
        // Y and the bar that pointed along +X must now point along -Z.
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("bar", null, new float[]{0, 0, 0},
                        new float[]{0, -2, -2}, new float[]{32, 2, 2}, 0xFFFFFF,
                        new float[]{0, 90, 0}, 0f, "", 0),
                new CreatureSculpt.Part("tip", "bar", new float[]{0, 0, 0},
                        new float[]{30, -2, -2}, new float[]{32, 2, 2}, 0xFFFFFF));
        // Re-pinned on the atom plan: the bar is its own box, so its atom's centre and pivot
        // are exactly what the grouped plan gave the "bar" group, and the answer is unchanged.
        RigPlan.Group g = RigPlan.atoms(parts, false).stream()
                .filter(x -> x.name().equals("bar")).findFirst().orElseThrow();
        assertTrue(g.tilted(), "the bar should carry its rest tilt");
        assertEquals(16f, g.originUnits()[0], 1e-5, "the group's centre is 16 units along +X");
        assertEquals(0f, g.pivotUnits()[0], 1e-5, "…and it is pivoted at the origin");

        RigTransform.Placed p = RigTransform.place(g, 1.0);
        assertEquals(0.0, p.translation().x(), 1e-4,
                "after a 90-degree Y turn nothing is left on X; got " + p.translation());
        assertEquals(-1.0, p.translation().z(), 1e-4,
                "the bar now reaches along -Z, one block out; got " + p.translation());

        // AND THE CONTRAST THAT MAKES IT A REAL CHECK: the same group with no tilt stays on +X.
        RigPlan.Group upright = new RigPlan.Group(g.name(), g.parts(), g.originUnits(),
                g.pivotUnits(), new float[]{0, 0, 0}, g.shrink());
        RigTransform.Placed q = RigTransform.place(upright, 1.0);
        assertEquals(1.0, q.translation().x(), 1e-4,
                "untilted, the same group sits one block along +X — so the rotation is what moved it");
        assertEquals(0.0, q.translation().z(), 1e-4);
    }

    /**
     * The X mirror is a coordinate-space conversion, not a content fix. Skipping it renders every
     * asymmetric creature as its own mirror image — invisible on symmetric Spikor and a Q1 delta
     * that is not about fidelity on anything else.
     */
    @Test
    @DisplayName("mirroring into world space flips X and leaves the tilts ALONE")
    void mirroringMatchesTheModRenderersConvention() {
        CreatureSculpt.Part p = new CreatureSculpt.Part("a", null, new float[]{2, 3, 4},
                new float[]{1, 0, 0}, new float[]{5, 2, 2}, 0xFFFFFF,
                new float[]{10, 20, 30}, 0f, "", 0);
        CreatureSculpt.Part m = RigTransform.mirrorX(p);
        assertEquals(-5f, m.from()[0], 1e-6, "the low X corner is the negated high one");
        assertEquals(-1f, m.to()[0], 1e-6);
        assertEquals(-2f, m.pivot()[0], 1e-6);
        assertEquals(p.from()[1], m.from()[1], 1e-6, "Y is untouched");
        assertEquals(p.from()[2], m.from()[2], 1e-6, "Z is untouched");

        // 🔴 THE ROTATIONS DO NOT MIRROR, AND THIS TEST USED TO ASSERT THAT THEY DID.
        //
        // The old version pinned `-20` and `-30` here, citing RuntimeGeoModel, which visibly
        // negates the Y and Z bone rotations. That reasoning is backwards: the mod's
        // `scale(-1/16, …)` sits OUTSIDE the bone hierarchy, so its bones are built in RAW sculpt
        // space and the negation IS that mirror's conjugation, already spent. The rig mirrors the
        // geometry first, so the equivalent rotation is `M R M⁻¹` — and conjugating by
        // diag(-1,1,1) sends Ry(θ)→Ry(-θ) and Rz(θ)→Rz(-θ), which cancels the mod's negation
        // exactly. Negating here as well applied it twice.
        //
        // Measured, on one box tilted 31° with the tilt carried cleanly on the display: X read
        // 0 px while Y and Z read 49.0% and 36.5%. One axis right and two wrong is the signature,
        // because X is the one axis a reflection in X leaves alone. Dropping the negation took all
        // three to 0 px.
        assertEquals(10f, m.rotation()[0], 1e-6, "an X tilt survives a reflection in X");
        assertEquals(20f, m.rotation()[1], 1e-6,
                "a Y tilt is NOT negated — the mod's own negation is this mirror's conjugation");
        assertEquals(30f, m.rotation()[2], 1e-6, "nor is a Z tilt");
    }

    @Test
    @DisplayName("mirroring twice is the identity, so the conversion cannot drift")
    void mirroringIsAnInvolution() {
        List<CreatureSculpt.Part> once = RigTransform.mirrorIntoWorldSpace(spikor());
        List<CreatureSculpt.Part> twice = RigTransform.mirrorIntoWorldSpace(once);
        List<CreatureSculpt.Part> original = spikor();
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).from()[0], twice.get(i).from()[0], 1e-5);
            assertEquals(original.get(i).rotation()[1], twice.get(i).rotation()[1], 1e-5);
        }
    }

    // ── Q4's arithmetic ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the base is chosen on width error after height is matched")
    void thebaseIsTheLeastWrongOne() {
        List<RigBase.Candidate> cands = List.of(
                new RigBase.Candidate("thin", 0.6f, 1.95f),
                new RigBase.Candidate("square", 1.0f, 1.0f),
                new RigBase.Candidate("wide", 2.0f, 1.0f));
        // want 4 wide x 4 tall = ratio 1.0 -> 'square' is exact
        RigBase.Choice c = RigBase.choose(cands, 4.0, 4.0);
        assertNotNull(c);
        assertEquals("square", c.id());
        assertEquals(4.0, c.scale(), 1e-6, "scale matches HEIGHT");
        assertEquals(0.0, c.widthErrorFraction(), 1e-6);
        assertFalse(c.tooNarrow());
    }

    @Test
    @DisplayName("a creature no vanilla base can reach is reported, not silently clamped")
    void anUnreachableHeightIsRefused() {
        List<RigBase.Candidate> cands = List.of(new RigBase.Candidate("tiny", 1f, 1f));
        assertNotNull(RigBase.choose(cands, 1, 16), "16x is exactly the limit and is allowed");
        assertEquals(null, RigBase.choose(cands, 1, 20),
                "beyond vanilla's scale range there is no honest answer, so say so");
    }

    /**
     * The real question Q4 asks, at Gate A speed: how wrong is the box for the creature the spike is
     * written against? This pins the sign — too WIDE is tolerable, too NARROW is the bug the mod
     * path already fixed once.
     */
    @Test
    @DisplayName("Spikor's own hitbox residual is measured, and its direction recorded")
    void spikorsHitboxResidualIsKnown() {
        List<CreatureSculpt.Part> parts = spikor();
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var e = CreatureAnatomy.extents(RigGeometry.of(parts));
        double w = CreatureAnatomy.widthBlocks(e) * scale;
        double h = CreatureAnatomy.heightBlocks(e) * scale;
        assertEquals(3.60, w, 0.01, "Spikor is 3.6 blocks wide at scale 1.2");
        assertEquals(4.50, h, 0.01, "…and 4.5 tall");
    }

    // ── the creature that needed mirroring ───────────────────────────────────────────────────

    /**
     * <b>The spike asks for "one that needed mirroring", and the corpus cannot supply one.</b>
     *
     * <p>Measured: all nine committed sculpts report {@code frontSign == -1}, i.e. every one of them
     * already faces -Z. That is not luck — {@code SculptOrientation.faceForward} runs at SCULPT
     * time, as the last step of {@code SculptCreatureTool.withKits}, so a persisted sculpt has
     * already been normalised and a backwards one cannot reach disk. The mirroring case is
     * therefore <i>structurally unreachable</i> from a stored artifact, and the rig inherits the fix
     * for free.
     *
     * <p>So the case is constructed instead: Spikor reflected front-to-back, which is exactly what
     * the agent produces before normalisation, and which
     * {@code SculptOrientation} reports 23 times in 113 real sculpts.
     */
    @Test
    @DisplayName("a creature built facing the wrong way is detected, and normalising it is enough")
    void theMirroringCaseIsConstructedBecauseTheCorpusCannotSupplyOne() {
        List<CreatureSculpt.Part> forward = spikor();
        assertFalse(com.moddingfromamod.runtime.SculptOrientation.facesBackwards(forward),
                "control: every persisted sculpt is already normalised");

        // Build the case the corpus cannot: reflect it front-to-back.
        // Reflected here rather than through SculptOrientation.mirrorZ, which is package-private
        // and belongs to the frozen sculpt path. Same reflection: z negates and swaps, and an X or
        // Y tilt reverses while a Z tilt does not, because reflecting z flips handedness.
        List<CreatureSculpt.Part> backwards = new ArrayList<>();
        for (CreatureSculpt.Part p : forward) {
            backwards.add(new CreatureSculpt.Part(p.name(), p.parent(),
                    new float[]{p.pivot()[0], p.pivot()[1], -p.pivot()[2]},
                    new float[]{p.from()[0], p.from()[1], -p.to()[2]},
                    new float[]{p.to()[0], p.to()[1], -p.from()[2]},
                    p.colour(),
                    new float[]{-p.rotation()[0], -p.rotation()[1], p.rotation()[2]},
                    p.inflate(), p.pattern(), p.patternColour()));
        }
        assertTrue(com.moddingfromamod.runtime.SculptOrientation.facesBackwards(backwards),
                "a reflected Spikor must be recognised as built backwards");

        // The rig does not mirror, and must not: the shipped renderer does not either, because the
        // fix is baked upstream. Both arms therefore agree, which is the property that matters.
        // (Was the grouped planner's 6-group count; re-pinned on the atom plan,.)
        assertEquals(RigPlan.atoms(forward).stream().map(RigPlan.Group::name).toList(),
                RigPlan.atoms(backwards).stream().map(RigPlan.Group::name).toList(),
                "the plan is orientation-independent — it reads the parts, not anatomy");

        // Normalising it puts it back exactly where the corpus creature already is.
        List<CreatureSculpt.Part> fixed =
                com.moddingfromamod.runtime.SculptOrientation.faceForward(backwards);
        assertFalse(com.moddingfromamod.runtime.SculptOrientation.facesBackwards(fixed));
        for (int i = 0; i < forward.size(); i++) {
            assertEquals(forward.get(i).from()[2], fixed.get(i).from()[2], 1e-4,
                    "normalising the mirrored creature must recover the original Z");
        }

        // And the landmark an ability aims at comes back to the same place, which is the thing a
        // wrong orientation actually breaks: a fire breath out of the back of the head.
        double s = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var a = CreatureAnatomy.landmarks(RigGeometry.of(forward), s);
        var b = CreatureAnatomy.landmarks(RigGeometry.of(fixed), s);
        assertEquals(a, b, "a normalised mirrored creature must aim exactly where the original does");
    }
}
