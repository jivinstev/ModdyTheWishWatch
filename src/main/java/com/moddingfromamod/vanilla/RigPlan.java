package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>A sculpted creature, collapsed into the handful of rigid pieces a display rig can afford.</b>
 *
 * <p>Pure and Minecraft-free, so the arithmetic that decides how many entities a creature costs is
 * a unit test rather than a thing you count on screen.
 *
 * <h2>Why this exists — the number the whole approach rests on</h2>
 *
 * <p>A vanilla client can be shown a sculpted creature only as {@code minecraft:item_display}
 * entities carrying generated models. The question that decides whether that is viable at all is
 * <b>how many entities per creature</b>, and the tempting answer — one per box — is the wrong one:
 * Spikor is <b>62 boxes</b>, and 62 entities per creature is not a renderer, it is a lag machine.
 *
 * <p>Boxes that never move relative to one another belong in <i>one baked model</i>. Measured on
 * the measured fixture {@link SpikeFixture#SPIKOR}, that takes 62 boxes to <b>6 groups</b>
 * (head 26, root 7, shellrim 11, body 8, armL 5, armR 5) — a <b>10.3x</b> reduction, and the
 * difference between viable and not.
 *
 * <h2>What counts as a rigid group</h2>
 *
 * <p>A part is an <b>articulation point</b> when something is parented to it. That is the honest
 * conservative rule: a bone with children exists in order to move them, and a bone with none cannot
 * move independently of whatever it hangs off. Every part then belongs to the nearest
 * articulation point at or above it, and everything left over is one {@link #ROOT} group.
 *
 * <p><b>Verified against the fixture rather than assumed:</b> {@code CreatureSculpt.geometry} emits
 * <i>one bone per part</i>, so the parsed {@code BedrockGeometry} for Spikor has <b>62 bones</b>,
 * not 6. The 6 is a property of the {@code parent} graph and has to be derived here. Spikor's graph
 * also happens to be <b>flat</b> — all five named parents are themselves root-level parts — but the
 * walk below does not assume that, because nothing enforces it.
 *
 * <h2>The vanilla constraint that shapes the output</h2>
 *
 * <p>A vanilla item-model element must lie within <b>[-16, 32]</b> model units on every axis, so a
 * group can span at most 48 units and must be positioned inside that window. Sculpt coordinates are
 * absolute and Spikor reaches x = -18, which is already outside it. So each group is emitted in its
 * <b>own local frame</b>, recentred on its bounding box and placed at the model centre (8,8,8), and
 * the offset that recentring removed is handed back as {@link Group#originUnits()} for the display
 * entity's translation to reapply.
 *
 * <p>Measured on Spikor, all six groups fit with room to spare (largest span 36 units, the root
 * group). {@link Group#shrink()} covers the general case: a group too big for the window is emitted
 * smaller and the display's scale multiplies it back, which costs nothing because a display
 * transform carries an arbitrary float scale.
 */
public final class RigPlan {

    private RigPlan() {
    }

    /** Bedrock/sculpt units per block. Same convention as {@code CreatureAnatomy}. */
    public static final float UNITS_PER_BLOCK = 16.0f;

    /** The group holding every part that hangs off no articulation point. */
    public static final String ROOT = "root";

    /**
     * A vanilla item-model element may not leave this box, on any axis.
     *
     * <p>Not a style choice — Minecraft's model loader rejects the model outright, and a creature
     * whose model failed to load renders as the magenta missing cube, which is <i>bright</i> and so
     * passes any "the frame is not dark" check. See the spike's guard 2.
     */
    public static final float ELEMENT_MIN = -16.0f;
    public static final float ELEMENT_MAX = 32.0f;

    /** Where a recentred group is placed inside that window: the middle of a 16-unit block. */
    public static final float MODEL_CENTRE = 8.0f;

    /**
     * <b>What a pattern overlay's group name ends with</b> — {@code "body"} and {@code "body#pattern"}
     * are one bone on two displays. See {@link Group#boneName()} and {@link #atoms}.
     *
     * <p>{@code '#'} because {@code CreatureSculpt.sanitize} cannot produce one, so the split is
     * unambiguous: no bone a child's creature can ever have will collide with an overlay's name.
     */
    public static final String PATTERN_SUFFIX = "#pattern";

    /**
     * <b>How far outside its box a pattern cube sits, per FACE, in BLOCKS.</b>
     *
     * <p>The two cubes are coincident surfaces, which is the definition of Z-fighting: the depth
     * buffer cannot separate them, so which one wins is decided per pixel per frame and a still
     * creature crawls. Lifting the pattern cube clear of the base one is the whole fix, and the only
     * question is by how much.
     *
     * <p>{@code 0.5/16} of a block — half a sculpt unit at creature scale 1 — so the per-axis SCALE
     * grows by {@code 2 ×} this, i.e. {@code 1/16} of a block. Small enough that the parallax is
     * invisible at any ordinary distance, large enough to be orders above the depth buffer's
     * resolution at Minecraft's near plane.
     *
     * <p>⚠ <b>Added in BLOCKS, after the creature's scale</b> ({@code RigTransform.atomise}), rather
     * than as sculpt units the scale then multiplies. Z-fighting is a property of the depth buffer
     * and not of the creature, so the separation a big creature needs is the same one a small
     * creature needs; scaling it would make a 4× creature's pattern float four times as far off its
     * own skin, which is the visible failure rather than the invisible one.
     *
     * <p>A starting value, and the number Gate C is asked about: §2 B's fallback if the stack reads
     * badly is a GREYSCALE pattern under one tint, which is lossy and is deliberately not built.
     */
    public static final float PATTERN_LIFT_BLOCKS = 0.5f / 16f;

    /** The largest span the window can hold: {@code ELEMENT_MAX - ELEMENT_MIN}. */
    public static final float MAX_SPAN = ELEMENT_MAX - ELEMENT_MIN;

    /**
     * One rigid piece of the creature: the boxes, where they sit, and how the display that carries
     * them must be transformed to put them back.
     *
     * @param name         the articulation point's part name, or {@link #ROOT}
     * @param parts        the boxes in this group, in sculpt order, with ABSOLUTE coordinates
     * @param originUnits  the group's bounding-box centre in absolute model units — what the
     *                     recentring removed, and therefore what the display translation restores
     * @param pivotUnits   the point this group rotates about, in absolute model units. The
     *                     articulation point's own pivot, so an animation channel aimed at this
     *                     bone turns it where the mod renderer turns it
     * @param restRotation the articulation point's rest tilt in degrees (X, Y, Z), or zeros
     * @param shrink       divide model coordinates by this to fit the vanilla window; the display
     *                     scale multiplies it back. 1.0 whenever the group already fits, which is
     *                     every group of the measured fixture
     * @param separation   this atom's handle into {@link RigSeparation}'s answer to a coplanar
     *                     neighbour: how far its display grows, per FACE, along each of its
     *                     own local axes, in blocks at the rig's scale. Zeros for almost every box
     */
    public record Group(String name, List<CreatureSculpt.Part> parts,
                        float[] originUnits, float[] pivotUnits,
                        float[] restRotation, float shrink,
                        float[] chainRotationXyzw, float[] chainTranslation,
                        RigSeparation.Ref separation) {

        /** A group with no coplanar neighbour to clear. */
        public Group(String name, List<CreatureSculpt.Part> parts, float[] originUnits,
                     float[] pivotUnits, float[] restRotation, float shrink,
                     float[] chainRotationXyzw, float[] chainTranslation) {
            this(name, parts, originUnits, pivotUnits, restRotation, shrink,
                    chainRotationXyzw, chainTranslation, RigSeparation.Ref.NONE);
        }

        /**
         * <b>The same group placed on another chain</b> — {@code RigPose.withChain}'s per-frame copy.
         *
         * <p>A method rather than a constructor call at the caller, because the caller is the
         * animated path: it runs every tick, and a copy that spelt out eight of nine components
         * would drop the separation the moment a creature moved — a fix that holds for a statue
         * and flickers on a walk, which is the one case that was filmed.
         */
        public Group withChain(float[] rotationXyzw, float[] translation) {
            return new Group(name, parts, originUnits, pivotUnits, restRotation, shrink,
                    rotationXyzw, translation, separation);
        }

        /** A group whose articulation point hangs off the model root — the ordinary case. */
        public Group(String name, List<CreatureSculpt.Part> parts, float[] originUnits,
                     float[] pivotUnits, float[] restRotation, float shrink) {
            this(name, parts, originUnits, pivotUnits, restRotation, shrink,
                    new float[]{0, 0, 0, 1}, new float[]{0, 0, 0});
        }

        /** How many boxes this group bakes into one model. */
        public int boxCount() {
            return parts.size();
        }

        /**
         * <b>The BONE this group answers to</b> — its own name, with {@link #PATTERN_SUFFIX} taken
         * off.
         *
         * <p>Every group but a pattern overlay ({@link #atoms}) is named for a bone and this is the
         * identity. A pattern overlay is a SECOND display of a box that already has one, so it has
         * to be a distinct group — index-matched lists, a distinct entity, a distinct tint — and it
         * has to animate as the same bone, because it is the same bone.
         *
         * <p>🔴 <b>Every animation lookup keys on this, never on {@link #name()}.</b>
         * {@code RigPose.withChain}, {@code RigPose.movesGroup}, {@code RigDriver.articulate}'s pose
         * lookup and {@link #isHead} all ask the frame about a bone, and a frame is composed from
         * the SCULPT, which has never heard of {@code "body#pattern"}. Asking it with the suffix on
         * gets a miss, and a miss is not an error: {@code withChain} keeps the plan's baked rest
         * chain and {@code movesGroup} says no, so the base cube walks and its stripes stand still.
         * A creature whose surface detaches from its body, on a frame nothing throws about.
         */
        public String boneName() {
            int i = name.indexOf(PATTERN_SUFFIX);
            return i < 0 ? name : name.substring(0, i);
        }

        /** Is this the second, stacked display of a patterned box rather than the box itself? */
        public boolean patternOverlay() {
            return name.endsWith(PATTERN_SUFFIX);
        }

        /** Does this group turn on its own, at rest? */
        public boolean tilted() {
            return restRotation[0] != 0f || restRotation[1] != 0f || restRotation[2] != 0f;
        }
    }

    /**
     * The nearest articulation point at or above this part.
     *
     * <p>Walks the chain rather than reading {@code parent} once, so a deeper tree than the
     * measured fixture's groups correctly. Bounded by the part count, because a {@code parent}
     * cycle is expressible in the file format and an unbounded walk would hang the server on one.
     */

    /**
     * <b>THE ATOM GRANULARITY: every box is its own display, and the model is a CONSTANT.</b>
     *
     * <p>{@code groups} (deleted with the pack rig) bakes rigid pieces into per-creature models (6 for Spikor);
     * {@code exactGroups} (deleted with the pack rig) splits out the tilted ones (~19). Both need a model FILE per group, so
     * both need a per-creature resource pack, and a stock client applies one by reloading — 6654 ms
     * of frozen game, measured, once per creature at the reveal ({@code docs/PACK_DELIVERY.md} §3).
     *
     * <p>This is the granularity that removes the pack. Every box becomes one display wearing the
     * SAME white unit cube ({@link AtomLibrary}), which ships once in the join-time item pack; the
     * box's <b>size</b> rides on the display's non-uniform scale, its <b>tilt</b> on the display's
     * quaternion, and its <b>colour</b> on {@code dyed_color}. Nothing per-creature is a file, so
     * nothing per-creature costs a reload. See {@code docs/ATOM_RIG_PLAN.md}.
     *
     * <h2>Three constraints this granularity simply does not have</h2>
     *
     * <ul>
     *   <li><b>No {@code [-16, 32]} window.</b> A unit cube always fits, so {@link Group#shrink()}
     *       is always 1 and {@code fitsVanillaWindow} (deleted with the pack rig) is vacuously true. A box wider than
     *       {@link #MAX_SPAN} — which the grouped plans have to shrink and repay on the display —
     *       is just a bigger scale.</li>
     *   <li><b>No rotation snapping.</b> A vanilla element turns about ONE axis by one of
     *       {0, ±22.5, ±45} and 36 of Spikor's 62 boxes violate that ({@code RigModelPack#rotationLoss} (deleted));
     *       a display quaternion has no such limit, and here EVERY box has its own.</li>
     *   <li><b>No {@code FLIP_MODEL_X}/{@code FLIP_MODEL_Z}.</b> Those reflect a model's elements
     *       about {@link #MODEL_CENTRE}, and an atom's element is a cube centred on exactly that
     *       point — so the reflection maps it onto itself and the whole flip family is structurally
     *       invisible here. Which is also why {@code __tiltx/y/z} passing at 0 px is evidence FOR
     *       this plan's rotation convention: those fixtures are single symmetric boxes, i.e. atoms
     *       in all but name, and they are already exact.</li>
     * </ul>
     *
     * <h2>⚠ What it costs, and the one thing that is NOT free</h2>
     *
     * <p>Entities scale with BOXES rather than groups — Spikor 6 → 62, plus one per PATTERNED box,
     * so 64 — and so does the animation traffic, because {@code RigAnimator} writes per moved
     * display. That is Phase 4's measurement and it is the number that decides whether this ships
     * as the default.
     *
     * <p>{@code scaled} (deleted with the pack rig) <b>cannot</b> fold the creature's scale into the model here: there is one
     * model and it is shared, so there is nothing per-creature to make bigger. The display's scale
     * is therefore ALWAYS non-uniform (any box that is not a cube), which is exactly the condition
     * that took {@code __legs3}'s side faces from 0.800 to 0.737 — see {@code scaled} (deleted with the pack rig)'s javadoc.
     * That is the atom rig's kill risk and Phase 1 photographs it with {@code shade} on and off.
     * ({@code scaled} is nevertheless a harmless no-op on an atom plan rather than a corruption:
     * {@link RigTransform#atomise} divides {@code shrink} back out, and the translation never used
     * it. {@code scalingAnAtomPlanIsANoOp} (deleted with {@code RigPlan.scaled}) pinned that.)
     *
     * <h2>The ancestor chain, on EVERY box — a deliberate divergence from {@link #build}</h2>
     *
     * <p>A bedrock bone's rotation is relative to its parent, so a box's world transform is its own
     * pivot-rotation composed under every ancestor's. {@code exactGroups} (deleted with the pack rig) already carries that for
     * a box it splits out, and {@link #build} gives an articulation point an <i>identity</i> chain —
     * which is correct only while the parent graph is FLAT, as Spikor's happens to be (this class's
     * own javadoc says so, and says nothing enforces it).
     *
     * <p>Here every box gets {@link #ancestorChain}, uniformly, including a box that is itself an
     * articulation point. On a flat graph that is identical to what the other plans produce; on a
     * deeper one it is the answer the mod renderer gives and they do not. Chosen rather than
     * matched, because "reproduce the existing plan's bug so the A/B is clean" would put a known
     * wrong number in the arm that is meant to replace it.
     *
     * <p>⚠ <b>The chain baked here is the REST chain, and that is not a limit on animation</b> —
     * the paragraph that used to sit here said hierarchical animation could not follow it, and
     * Phase 2c measured otherwise ({@code docs/ATOM_RIG_PLAN.md} §5.7). {@code RigPose.compose}
     * recomputes every bone's chain per frame and keys it by PART NAME, which an atom group's name
     * is, so a clip that turns {@code head} already moves all 26 of Spikor's head atoms.
     * {@code RigPose.animatedBones} genuinely cannot help — it decides which bones
     * {@code exactGroups} (deleted with the pack rig) must SPLIT, and there is nothing left to split — but it was never the
     * mechanism that does the work, and conflating the two is how a working feature came to be
     * recorded as missing.
     *
     * <h2>A patterned box is TWO displays (§2 B)</h2>
     *
     * <p>A part may carry one of eight {@code SculptPattern} names and a second colour. A tint is
     * one number and a pattern is pixels, so the plan emits a second group for it —
     * {@code "<bone>#pattern"}, the same box at the same transform, drawn on
     * {@link AtomLibrary}'s cube for that pattern and lifted {@link #PATTERN_LIFT_BLOCKS} clear so
     * the two surfaces do not fight. {@code DisplayRig} tints the base with the part's
     * {@code colour} and the overlay with {@link SculptPattern#inkFor}, and the pattern sprite is
     * opaque exactly where the pattern draws — so the base colour shows through the gaps.
     *
     * <p><b>The overlay is a display, not a bone.</b> It sits immediately after its base so the
     * index run {@code DisplayRig.adopt} recovers stays contiguous, and every animation lookup goes
     * through {@link Group#boneName()}, which takes the suffix off. Spikor's 62 boxes become
     * <b>64</b> displays; the corpus's other eight fixtures add 0–4 between them.
     *
     * <p>Switchable, because a stacked cube is the one thing here only an eye can judge:
     * {@link AtomLibrary#patterns()} / {@link #atoms(List, boolean)}.
     *
     * @param parts the sculpt, in whatever space the caller is working in — {@link DisplayRig}
     *              mirrors into Minecraft space before calling, exactly as it does for the others
     */
    public static List<Group> atoms(List<CreatureSculpt.Part> parts) {
        return atoms(parts, AtomLibrary.patterns());
    }

    /**
     * <b>…with the pattern overlays switched off</b> — {@code docs/ATOM_RIG_PLAN.md} §2 B's A/B.
     *
     * <p>The one-argument form reads {@link AtomLibrary#patterns()}, so {@code DisplayRig.spawn} and
     * {@code DisplayRig.adopt} cannot disagree about how many displays a creature has (they would
     * disagree about ORDER too, since the overlay sits immediately after its base). This overload is
     * for a test that wants both arms in one JVM, and for nothing else.
     *
     * @param withPatterns {@code false} plans a patterned box as ONE flat display, which is exactly
     *                     what Phase 1a shipped
     */
    public static List<Group> atoms(List<CreatureSculpt.Part> parts, boolean withPatterns) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        // two boxes drawing one plane flicker, so the smaller is grown clear of it. Planned
        // over the WHOLE sculpt, because who gives way is a question about pairs; SOLVED at the
        // rig's scale on first placement, because the pattern lift is in blocks (RigSeparation).
        RigSeparation.Plan sep = RigSeparation.plan(parts, byName, withPatterns);
        // A part's ancestor chain is a function of its PARENT alone, so siblings share one:
        // Spikor's 26 head parts composed the same chain 26 times. Memoised per plan (—
        // measured, it more than repays the separation's own per-plan cost).
        Map<String, float[][]> chains = new java.util.HashMap<>();
        List<Group> out = new ArrayList<>(parts.size());
        for (int k = 0; k < parts.size(); k++) {
            CreatureSculpt.Part p = parts.get(k);
            float[][] chain = chains.computeIfAbsent(p.parent() == null ? "" : p.parent(),
                    parent -> ancestorChain(p, byName));
            Group base = atom(p, byName, new RigSeparation.Ref(sep, k), chain);
            sep.chain(k, base.chainRotationXyzw(), base.chainTranslation());
            out.add(base);
            // THE OVERLAY GOES IMMEDIATELY AFTER ITS BASE, and that is load-bearing twice over.
            // `adopt` recovers a rig's order from a 0..n-1 index run written at spawn, so the two
            // have to agree; and a reader holding `groups` and `displays` side by side can see the
            // pair. Appending every overlay at the end would work and would put a creature's
            // stripes eleven rows from its skin in every log this rig ever prints.
            if (withPatterns && p.patterned() && AtomLibrary.patternModelData(p.pattern()) > 0) {
                out.add(patternAtom(base));
            }
        }
        return out;
    }

    /**
     * <b>The second display of a patterned box</b> — same box, same transform, one suffix.
     *
     * <p>Everything is copied from the base group rather than re-derived from the part, so the two
     * displays cannot land in different places: the origin, the pivot, the rest rotation and the
     * ancestor chain are the SAME arrays' values, and the only difference the whole rig makes
     * between them is the hair {@code RigTransform.atomise} adds to the scale
     * ({@link #PATTERN_LIFT_BLOCKS}) and the model and tint {@code DisplayRig} writes. Re-deriving
     * would be the {@code TrainFraming} shape {@code docs/CAMERA.md} records — a value computed at
     * one call site and again at another, correct until one of them changes.
     */
    static Group patternAtom(Group base) {
        return new Group(base.name() + PATTERN_SUFFIX, base.parts(), base.originUnits(),
                base.pivotUnits(), base.restRotation(), base.shrink(),
                base.chainRotationXyzw(), base.chainTranslation(), base.separation());
    }

    /**
     * <b>{@link #atoms} takes no {@code alsoSplit} set, and that is not an omission.</b>
     *
     * <p>{@code exactGroups(List, Set)} (deleted with the pack rig) exists because a bone an animation moves has to be on its
     * own display, and rest rotation is a different question from "does anything move this". At
     * atom granularity every box is already on its own display, so the set is satisfied by
     * construction and an overload taking one would be a parameter that can never change the
     * answer. Spelt out here because the absence is the sort of thing a reader assumes is a gap.
     */
    public static List<Group> atoms(List<CreatureSculpt.Part> parts, Set<String> alsoSplit) {
        return atoms(parts);
    }

    /** One box as its own group: its own centre, its own pivot and tilt, its ancestors' chain. */
    static Group atom(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        return atom(p, byName, RigSeparation.Ref.NONE);
    }

    static Group atom(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName,
                      RigSeparation.Ref separation) {
        return atom(p, byName, separation, ancestorChain(p, byName));
    }

    static Group atom(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName,
                      RigSeparation.Ref separation, float[][] chain) {
        float[] origin = new float[3];
        float inf = p.inflate();
        for (int i = 0; i < 3; i++) {
            float lo = Math.min(p.from()[i], p.to()[i]) - inf;
            float hi = Math.max(p.from()[i], p.to()[i]) + inf;
            origin[i] = (lo + hi) / 2f;
        }
        // A part with no pivot rotates about its own centre, which is what `build` does for a group
        // with no anchor -- and with no rotation the pivot is unobservable either way.
        float[] pivot = p.pivot() != null && p.pivot().length >= 3
                ? new float[]{p.pivot()[0], p.pivot()[1], p.pivot()[2]}
                : new float[]{origin[0], origin[1], origin[2]};
        float[] rot = p.rotation() != null && p.rotation().length >= 3
                ? new float[]{p.rotation()[0], p.rotation()[1], p.rotation()[2]}
                : new float[]{0f, 0f, 0f};
        // shrink is 1 and always will be: the model is a unit cube, so there is no window to fit.
        return new Group(p.name(), List.of(p), origin, pivot, rot, 1.0f, chain[0], chain[1],
                separation);
    }

    /**
     * <b>The box's own size in sculpt units, inflate included</b> — what the display's scale has to
     * carry, because the model is a unit cube and knows nothing about this box.
     *
     * <p>Measured off {@code from}/{@code to} as FLOATS rather than through
     * {@code CreatureSculpt.Part#width()}, which rounds to an int for the UV net. The geometry path
     * ({@code localBox} (deleted with the pack rig)) already uses the raw floats, and a box whose size is 8.5 units would
     * otherwise be drawn 8 units wide with its centre still in the right place — a half-unit gap
     * at each end that reads as a modelling mistake rather than as a rounding one.
     *
     * @throws IllegalArgumentException if the group is not a single box, which means somebody has
     *         handed a grouped plan to the atom path — a failure worth a message rather than a
     *         creature silently drawn at its first box's size
     */
    public static float[] atomSizeUnits(Group g) {
        if (g.boxCount() != 1) {
            throw new IllegalArgumentException("atom sizing wants exactly one box, group '"
                    + g.name() + "' has " + g.boxCount() + " -- this is a grouped plan on the atom "
                    + "path, and every box after the first would be drawn at the first one's size");
        }
        CreatureSculpt.Part p = g.parts().get(0);
        float inf = p.inflate();
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.abs(p.to()[i] - p.from()[i]) + 2f * inf;
        }
        return out;
    }

    /**
     * The composed rotation and translation of every ANCESTOR of a part, root-first.
     *
     * <p>Each bone contributes {@code m -> pivot + R(m - pivot)}, i.e. rotation {@code R} and
     * translation {@code pivot - R*pivot}. Composing {@code (R,t)} with the next bone's
     * {@code (Rx,dx)} gives {@code (R*Rx, R*dx + t)} — the standard affine product, written out
     * because getting the order backwards produces a creature that is subtly wrong only where two
     * bones are both tilted, which is the hardest kind of wrong to see.
     *
     * @return {@code {quaternion xyzw, translation xyz}}
     */
    static float[][] ancestorChain(CreatureSculpt.Part p,
                                   Map<String, CreatureSculpt.Part> byName) {
        List<CreatureSculpt.Part> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String at = p.parent();
        while (at != null && !at.isEmpty() && seen.add(at)) {
            CreatureSculpt.Part a = byName.get(at);
            if (a == null) {
                break;
            }
            chain.add(0, a);   // root first
            at = a.parent();
        }
        org.joml.Quaternionf r = new org.joml.Quaternionf();
        org.joml.Vector3f t = new org.joml.Vector3f();
        for (CreatureSculpt.Part a : chain) {
            org.joml.Quaternionf rx = RigTransform.rotation(a.rotation());
            org.joml.Vector3f pv = new org.joml.Vector3f(
                    a.pivot()[0], a.pivot()[1], a.pivot()[2]);
            org.joml.Vector3f dx = new org.joml.Vector3f(pv).sub(new org.joml.Vector3f(pv).rotate(rx));
            t.add(new org.joml.Vector3f(dx).rotate(r));
            r.mul(rx);
        }
        return new float[][]{{r.x, r.y, r.z, r.w}, {t.x, t.y, t.z}};
    }

    /**
     * A part's box in its group's LOCAL model frame — recentred on the group origin, shrunk if the
     * group needed it, and placed at {@link #MODEL_CENTRE}.
     *
     * @return {@code {x0, y0, z0, x1, y1, z1}}, normalised so the first triple is the low corner
     */
    /**
     * <b>Bisect lever: reverse each element's X inside the item model.</b>
     *
     * <p>Two solid boxes at ±x in one group came back with their positions SWAPPED while a single
     * box, a tilted box and an over-sized (shrunk) box were all pixel-exact. Every one of those
     * three is blind to an intra-model X reversal — a single box sits symmetric about its own group
     * origin, so reflecting it about model 8 moves nothing at all. Two boxes are the smallest
     * fixture that can see it.
     *
     * <p>⚠ <b>DEFAULTED BACK OFF, and the reason is an instrument fault worth keeping.</b> The
     * photograph of {@code __twobox} that motivated turning it on showed two solid-coloured boxes
     * in each other's places, and flipping the geometry made that picture correct. It was the wrong
     * fix: a corner-level probe routed through {@code localBox} — the path the model file is
     * actually written on — reports the flip MISPLACING 2 of 2 boxes by 20 units on that fixture
     * and 19 of 62 on Spikor, while OFF is exact. Two solid-coloured boxes cannot tell "the
     * geometry is swapped" from "the skins are swapped", so the picture was consistent with both
     * and I picked the one that moved the geometry.
     *
     * <p>The first version of that probe had the same blind spot in reverse: it computed from
     * {@code place()} and the part's absolute position, skipping {@code localBox} entirely, so it
     * validated half the pipeline and pronounced the other half exact. It disagreed with the
     * photograph, and the photograph was right.
     */
    public static final boolean FLIP_MODEL_X = !"off".equalsIgnoreCase(System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".rigflipmodelx", "on"));

    /**
     * <b>The same reversal on Z, and the fixture that finally asked the question.</b>
     *
     * <p>Every fixture that passed at 0 px is symmetric in Z — each one's boxes all sit at the same
     * z range, so reflecting the model about the group's own z centre maps them onto themselves and
     * cannot be measured. {@code __legs3} (two legs at z −4..6 and a foot at z −10..−4) is the first
     * that is not, and it fails: arm B comes out 6 px wider and 10 px to the right of arm A with the
     * height and the vertical position exact.
     *
     * <p>That is the signature of a reversal on the OTHER horizontal axis. If the model's X needs
     * reversing then something is turning the model half a revolution about Y, and a half turn about
     * Y reverses X <b>and Z together</b> — implementing only X leaves every Z-asymmetric group with
     * its depth inside out.
     */
    public static final boolean FLIP_MODEL_Z = !"off".equalsIgnoreCase(System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".rigflipmodelz",
            FLIP_MODEL_X ? "on" : "off"));

    /**
     * <b>Is this the group a creature turns to look at you?</b>
     *
     * <p>The same rule {@code RuntimeGeoModel} uses to decide which bone gets head-follow, kept in
     * lockstep on purpose: if the two disagree, one renderer turns a bone the other leaves still
     * and the creature looks at you out of its chest.
     */
    public static boolean isHead(Group g) {
        return isHeadBone(g.boneName());
    }

    /**
     * <b>The same question asked of a BONE NAME</b> — because head-follow is inherited and the
     * layer that composes a chain ({@link RigPose}) sees names, not groups.
     *
     * <p>Split out rather than duplicated for the reason {@link #isHead(Group)}'s javadoc already
     * gives about {@code RuntimeGeoModel}: two copies of this rule is two renderers that can
     * disagree about which bone a creature looks at you with. There are now three callers and one
     * rule.
     */
    public static boolean isHeadBone(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("head") || lower.equals("neck") || lower.endsWith("_head");
    }

}
