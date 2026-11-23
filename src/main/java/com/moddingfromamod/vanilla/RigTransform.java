package com.moddingfromamod.vanilla;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Where a rigid group's display entity has to sit</b>, derived once so no call site types a
 * literal. Pure and Minecraft-free apart from JOML, so the arithmetic is a unit test.
 *
 * <h2>The mapping, derived rather than guessed</h2>
 *
 * <p>A {@code minecraft:item_display} renders its item model with the model cube {@code [0,16]}
 * mapped onto one block centred on the entity: the item renderer translates by {@code -0.5} and
 * scales by {@code 1/16}, so <b>model coordinate 8 is the display's own origin</b> and a model unit
 * is {@code 1/16} of a block. {@link RigPlan} therefore emits each group recentred on
 * {@link RigPlan#MODEL_CENTRE}, and everything below is about putting that centre back where the
 * creature wants it.
 *
 * <p>Writing the group's bounding-box centre as {@code C}, its pivot as {@code P}, its rest
 * rotation as {@code R} and the creature's scale as {@code s}, a model point {@code m} must land at
 *
 * <pre>  world_local(m) = ( P + R·(m − P) ) · s / 16</pre>
 *
 * <p>which is what the mod renderer does: rotate about the pivot, divide by 16, scale. The display
 * gives {@code T + R·S·(m − C)/16}. Equating the two and cancelling the {@code m} terms leaves the
 * translation with no freedom left in it:
 *
 * <pre>  T = ( P + R·(C − P) ) · s / 16        S = s · shrink</pre>
 *
 * <p>Note what that says: <b>the translation depends on the rotation.</b> A rig that set
 * {@code T = C·s/16} and then rotated would swing every tilted group away from its pivot — and it
 * would look exactly like an animation bug, which is the hardest kind of wrong to attribute.
 *
 * <h2>Why X is mirrored before any of this, and mirrored HERE</h2>
 *
 * <p>{@code RuntimeGeoModel.render} opens with {@code scale(-1/16, 1/16, 1/16)} — the mod renderer
 * draws every sculpted creature <b>mirrored in X</b>, and negates its Y and Z bone rotations to
 * compensate. That is the convention a sculpt is authored against, so a rig that skipped it would
 * render every asymmetric creature as its own mirror image: invisible on Spikor, who is symmetric,
 * and a Q1 pixel delta that is not about fidelity on anything that is not.
 *
 * <p>So the parts are mirrored ONCE, up front, into Minecraft space ({@link #mirrorIntoWorldSpace}),
 * and nothing downstream carries a sign. The alternative — a negative X in the display's scale
 * vector — flips triangle winding and would have been debugged as missing faces.
 *
 * <p>This is deliberately <b>not</b> {@code SculptOrientation}. That class decides whether the
 * agent built the creature facing the wrong way and is a content fix keyed on anatomy; this is a
 * fixed coordinate-space conversion that applies to every creature whatever its anatomy. Conflating
 * them would make a rendering convention depend on whether a creature happens to have a named
 * snout.
 */
public final class RigTransform {

    private RigTransform() {
    }

    /** The display's four-part transform, ready to be written as vanilla NBT. */
    public record Placed(org.joml.Vector3f translation, org.joml.Quaternionf leftRotation,
                         org.joml.Vector3f scale, org.joml.Quaternionf rightRotation) {
    }

    /**
     * One part reflected through the YZ plane, into the space {@code RuntimeGeoModel} draws in.
     *
     * <p><b>The POSITIONS mirror and the ROTATIONS do not, which is the opposite of what it looks
     * like.</b> Reflecting x does flip handedness, and the first cut of this method therefore
     * negated the Y and Z tilts — citing {@code RuntimeGeoModel}, which visibly does the same thing.
     * That reasoning is backwards, and it cost a whole round of the spike.
     *
     * <p>Write the mod renderer's pipeline out and it is
     * {@code M ∘ [T(p) Rz(-rz) Ry(-ry) Rx(rx) T(-p)]} — the {@code scale(-1/16, …)} mirror sits
     * OUTSIDE the bone hierarchy, so the bones are built in RAW sculpt space and the negation of Y
     * and Z is that mirror's conjugation, already accounted for. The rig mirrors the geometry
     * FIRST, so the equivalent transform is {@code M R M⁻¹}; conjugating by {@code diag(-1,1,1)}
     * sends {@code Rx(θ)→Rx(θ)} and {@code Ry(θ)→Ry(-θ)}, {@code Rz(θ)→Rz(-θ)}, which cancels the
     * mod's negation exactly. So the rig wants the RAW angles, applied positively.
     *
     * <p>Negating here as well applied the conjugation twice. Measured: with the tilts carried
     * cleanly on the display, a box tilted 31° about X read <b>0 px</b> while Y and Z read 49.0%
     * and 36.5% — one axis right and two wrong is the signature of exactly this, since X is the
     * one axis conjugation leaves alone. Dropping the negation takes all three to <b>0 px</b>.
     */
    public static CreatureSculpt.Part mirrorX(CreatureSculpt.Part p) {
        float[] from = {-p.to()[0], p.from()[1], p.from()[2]};
        float[] to = {-p.from()[0], p.to()[1], p.to()[2]};
        float[] pivot = {-p.pivot()[0], p.pivot()[1], p.pivot()[2]};
        // ⚠ The rotations are NOT conjugated even though FLIP_MODEL_X reflects the elements.
        // That looks inconsistent and it was tested: negating Y and Z here took three tilt fixtures
        // from 0 px to 49.0% and 36.5%, and Spikor from 46.2% to 49.8%. The flip and the rotation
        // convention are each independently correct as they stand; see RigPlan.FLIP_MODEL_X.
        float[] rot = p.rotation() == null ? CreatureSculpt.NO_ROTATION
                : new float[]{p.rotation()[0], p.rotation()[1], p.rotation()[2]};
        return new CreatureSculpt.Part(p.name(), p.parent(), pivot, from, to, p.colour(),
                rot, p.inflate(), p.pattern(), p.patternColour());
    }

    /** The whole creature mirrored into Minecraft space. Always applied; never conditional. */
    public static java.util.List<CreatureSculpt.Part> mirrorIntoWorldSpace(
            java.util.List<CreatureSculpt.Part> parts) {
        if (parts == null) {
            return java.util.List.of();
        }
        java.util.List<CreatureSculpt.Part> out = new java.util.ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            out.add(mirrorX(p));
        }
        return out;
    }

    /**
     * Bedrock's intrinsic Z-Y-X rotation order as a quaternion, in degrees.
     *
     * <p>Order matters and is not a preference: {@code RuntimeGeoModel} multiplies Z, then Y, then
     * X, so any other order puts a doubly-tilted part somewhere else. Parts with a single-axis tilt
     * — most of them — are unaffected either way, which is precisely why getting it wrong would
     * survive a casual look at Spikor and show up on some later creature.
     */
    public static org.joml.Quaternionf rotation(float[] degXyz) {
        org.joml.Quaternionf q = new org.joml.Quaternionf();
        if (degXyz == null) {
            return q;
        }
        q.rotateZ((float) Math.toRadians(degXyz[2]));
        q.rotateY((float) Math.toRadians(degXyz[1]));
        q.rotateX((float) Math.toRadians(degXyz[0]));
        return q;
    }

    /**
     * <b>The head-follow rotation, spelt ONCE.</b>
     *
     * <p>{@code R(yaw) &middot; R(pitch)}, with the Y turn negated by the mirror's conjugation and
     * the X turn left alone &mdash; the same rule {@link #mirrorX} derives for a rest tilt.
     *
     * <p>Extracted because head-follow is now applied in TWO places that must agree: here, on the
     * head bone's own display, and inside {@link RigPose#compose(java.util.List, java.util.Map,
     * float[])}, which is where every bone UNDER the head inherits it. A second transcription of
     * this expression is a creature whose face turns the opposite way from its skull &mdash; the
     * {@code TrainFraming} shape in {@code docs/CAMERA.md}, arriving on a rig.
     *
     * @param headDeg {@code {yaw, pitch}} relative to the body, or null / all-zero for identity
     */
    public static org.joml.Quaternionf headRotation(float[] headDeg) {
        if (headDeg == null || (headDeg[0] == 0f && headDeg[1] == 0f)) {
            return new org.joml.Quaternionf();
        }
        return new org.joml.Quaternionf()
                .rotateY((float) Math.toRadians(-headDeg[0]))
                .rotateX((float) Math.toRadians(headDeg[1]));
    }

    /**
     * The transform for one rigid group at rest.
     *
     * @param g     the group, already in mirrored (Minecraft) space
     * @param scale the creature's live scale
     */
    /**
     * <b>M2 — the whole-body modifier.</b> Post-multiply a placed transform by one rotation about
     * the display's own origin, on top of whatever M1 already wrote.
     *
     * <p>A display renders model point {@code m} at {@code world = T + L·(S ⊙ m)}. Turning the
     * whole creature by {@code q} about the origin is therefore exactly two substitutions:
     *
     * <pre>
     *   world' = q·world = q·T + (q·L)·(S ⊙ m)      →   T' = q·T ,  L' = q·L
     * </pre>
     *
     * <p>Scale and the right rotation are untouched, which is what makes this composable with the
     * clip pose rather than a replacement for it.
     *
     * <p>⚠ <b>Why this is not "M1 with a fake root bone".</b> A bone's transform inherits down its
     * own chain, so a synthetic root would only reach groups that descend from one — and
     * {@code RigPose} already reports {@code root} as the group with no bone of its own name,
     * i.e. that assumption is known to be false in general. This applies to EVERY display
     * unconditionally, which is both why it works and why it costs one write per display rather
     * than one per moved bone.
     *
     * <p>The pivot is the origin because every display of a rig sits at the carrier's position and
     * carries its offset inside {@code T} — so "about the feet" needs no pivot arithmetic. A pivot
     * elsewhere would be {@code T' = P + q·(T − P)}.
     */
    public static Placed about(Placed p, org.joml.Quaternionf q) {
        return new Placed(
                new org.joml.Vector3f(p.translation()).rotate(q),
                new org.joml.Quaternionf(q).mul(p.leftRotation()),
                new org.joml.Vector3f(p.scale()),
                new org.joml.Quaternionf(p.rightRotation()));
    }

    /**
     * <b>The same transform, but for an ATOM</b> — one box on the shared unit cube
     * ({@link RigPlan#atoms}, {@link AtomLibrary}).
     *
     * <p>Everything except the scale is identical to {@link #place}, and deliberately so: the
     * translation, the pivot arithmetic, the ancestor chain, the pose channels and the head follow
     * are the lines this rig has already paid to get right, and re-deriving them for a second
     * granularity is how two plans come to disagree about where a tilted bone goes.
     *
     * <p>What differs is what the MODEL is. A grouped plan writes the boxes into the model file, so
     * a model unit is a sculpt unit and the display's scale is the creature's scale. An atom's
     * model is the unit cube: it spans {@link RigPlan#MODEL_CENTRE} ± 8 on every axis, i.e. exactly
     * one block, so the display's scale is what makes it the size of this particular box —
     *
     * <pre>  S = creatureScale · (w + 2·inflate, h + 2·inflate, d + 2·inflate) / 16</pre>
     *
     * <p>in blocks. The translation is untouched because it places the display's ORIGIN, which is
     * model point 8 under either plan and the box's own inflated centre under both — a one-box
     * group is recentred on itself, so {@code originUnits} already is that centre.
     *
     * <p>Scale is applied BEFORE the rotation ({@code world = T + L·(S ⊙ m)}), which is the order
     * this needs: the size is measured in the box's own upright frame and the quaternion then turns
     * the sized box. A rig that rotated first would stretch a tilted box along the world axes.
     */
    private static final float[] NO_SEPARATION = {0, 0, 0};

    public static Placed placeAtom(RigPlan.Group g, double scale) {
        return atomise(place(g, scale), g, scale);
    }

    /**
     * <b>The atom as the SCULPT says it is</b> — without an earlier issue's separation.
     *
     * <p>For every caller that MEASURES the creature rather than draws it: the carrier's fit, the
     * hit proxies, the rest bounds ({@link RigFit#atoms}). The separation is a nudge for the depth
     * buffer, a hair along one axis, and nothing a sword or an arrow should meet — and a measured
     * decision is discontinuous: Tralalero armA's hit-proxy plan went from six proxies to none on
     * a 1/128-block nudge to its teeth, because it sits on the planner's bound. So measuring
     * callers read the authored creature and cannot move when the rendering does.
     */
    public static Placed placeAtomAsAuthored(RigPlan.Group g, double scale) {
        // Skipped, not added and taken back out: that round trip can move the last bit of a
        // float, and the planner this feeds has just been shown to sit on a knife edge.
        return atomise(place(g, scale), g, scale, false);
    }

    /** @see #placeAtom(RigPlan.Group, double) */
    public static Placed placeAtom(RigPlan.Group g, double scale, float[] rotationDeg) {
        return atomise(place(g, scale, rotationDeg), g, scale);
    }

    /** @see #placeAtom(RigPlan.Group, double) */
    public static Placed placeAtom(RigPlan.Group g, double scale, float[] rotationDeg,
                                   float[] poseRotDeg, float[] posePosUnits, float[] poseScale) {
        return atomise(place(g, scale, rotationDeg, poseRotDeg, posePosUnits, poseScale), g, scale);
    }

    /** @see #placeAtom(RigPlan.Group, double) */
    public static Placed placeAtom(RigPlan.Group g, double scale, float[] rotationDeg,
                                   float[] poseRotDeg, float[] posePosUnits, float[] poseScale,
                                   float[] headDeg) {
        return atomise(place(g, scale, rotationDeg, poseRotDeg, posePosUnits, poseScale, headDeg), g,
                scale);
    }

    /**
     * Swap a grouped placement's uniform scale for the atom's own box size.
     *
     * <p>Written as a MULTIPLY on whatever {@code place} produced rather than as a fresh vector, so
     * an animation's {@code poseScale} channel — a squash-and-stretch keyframe — survives instead of
     * being silently dropped by the atom path. {@code place} hands back
     * {@code s · shrink · poseScale[i]}; multiplying by {@code size[i] / (16 · shrink)} leaves
     * {@code s · poseScale[i] · size[i] / 16}, which is the line in the javadoc above.
     *
     * <p><b>Dividing {@code shrink} back out is what makes {@link RigPlan#scaled} a harmless no-op
     * on an atom plan</b> rather than a corruption. {@code scaled} exists to fold the creature's
     * scale into the MODEL, and an atom's model is shared, so there is nothing per-creature to
     * enlarge — but a caller that runs it anyway only moves the number between two factors that
     * cancel here. It was pinned by {@code scalingAnAtomPlanIsANoOp} (deleted with {@code RigPlan.scaled}), because "we
     * remembered to skip it" is not a property a build can check.
     *
     * <h2>The pattern overlay's hair (§2 B)</h2>
     *
     * <p>A patterned box is two displays at ONE transform, which is two coincident surfaces, which
     * is Z-fighting. {@link RigPlan#PATTERN_LIFT_BLOCKS} is how far the overlay's faces sit outside
     * the base's, so its scale is {@code 2 ×} that larger on every axis — and it is added in BLOCKS,
     * AFTER everything the creature's scale touches, because the depth buffer does not care how big
     * the creature is. A lift folded into {@code size} instead would ride the creature scale and
     * float a 4× creature's pattern four times as far off its own skin.
     *
     * <p>The TRANSLATION is untouched, so the two cubes stay concentric and the lift is symmetric —
     * a shifted overlay would slide its stripes off one end of the box.
     *
     * @param scale the creature's scale — {@link RigSeparation} is solved at it, because the
     *              pattern lift is in blocks and the sculpt in units. A PARAMETER rather than read
     *              back off {@code p}, whose scale also carries the pose's squash-and-stretch
     */
    public static Placed atomise(Placed p, RigPlan.Group g, double scale) {
        return atomise(p, g, scale, true);
    }

    private static Placed atomise(Placed p, RigPlan.Group g, double scale, boolean separated) {
        float[] size = RigPlan.atomSizeUnits(g);
        float k = RigPlan.UNITS_PER_BLOCK * g.shrink();
        float lift = g.patternOverlay() ? 2f * RigPlan.PATTERN_LIFT_BLOCKS : 0f;
        // a coplanar detail's growth, per face along its own axes, in blocks -- added here,
        // after the creature's scale, for the same reason the lift is. An overlay inherits its
        // base's separation (RigPlan.patternAtom), so the pair stays concentric.
        float[] sep = separated ? g.separation().blocks(scale) : NO_SEPARATION;
        return new Placed(
                new org.joml.Vector3f(p.translation()),
                new org.joml.Quaternionf(p.leftRotation()),
                new org.joml.Vector3f(p.scale().x() * size[0] / k + lift + 2f * sep[0],
                        p.scale().y() * size[1] / k + lift + 2f * sep[1],
                        p.scale().z() * size[2] / k + lift + 2f * sep[2]),
                new org.joml.Quaternionf(p.rightRotation()));
    }

    public static Placed place(RigPlan.Group g, double scale) {
        return place(g, scale, g.restRotation());
    }

    /**
     * The transform for one rigid group with an animation pose replacing its rest tilt.
     *
     * <p>The pose composes ON TOP of the rest rotation, as the mod renderer does: a bone tilted at
     * rest and then animated turns from where it was, not from upright.
     */
    public static Placed place(RigPlan.Group g, double scale, float[] rotationDeg) {
        return place(g, scale, rotationDeg, null, null, null);
    }

    /**
     * <b>The transform for one rigid group with a full animation pose</b> — rotation, position and
     * scale, composed the way {@code RuntimeGeoModel} composes them rather than the way that is
     * convenient.
     *
     * <p>Writing the mod's bone block out as a matrix product:
     *
     * <pre>  m -> p + animPos + R(anim) · R(rest) · S(animScale) · (m - p)</pre>
     *
     * <p>Three things fall out of that and the first cut of this rig got all three wrong:
     *
     * <ul>
     *   <li><b>Rotations COMPOSE, they do not add.</b> The pose used to be applied as
     *       {@code rest[i] + pose[i]} per axis. Euler angles are not a vector space — summing them
     *       is only right when both turns share one axis, so a creature whose head is tilted at
     *       rest and then animated about a different axis ends up somewhere else entirely. Exactly
     *       the kind of wrong that looks fine on the one creature you tested.</li>
     *   <li><b>The pose's POSITION channel was dropped.</b> An animation that moves a bone without
     *       turning it — a bobbing head, a recoiling arm — did nothing at all.</li>
     *   <li><b>The pose's SCALE channel was dropped.</b> A squash-and-stretch keyframe did
     *       nothing.</li>
     * </ul>
     *
     * <p>The position channel is mirrored ({@code x} negated) for the same reason the geometry is:
     * it is a translation in raw sculpt space and the rig works in reflected space. The scale is
     * diagonal and commutes with the reflection, so it crosses unchanged.
     *
     * @param poseRotDeg  the pose's own rotation, in RAW sculpt degrees, or null
     * @param posePosUnits the pose's position offset in RAW sculpt units, or null
     * @param poseScale   the pose's per-axis scale, or null for 1,1,1
     */
    public static Placed place(RigPlan.Group g, double scale, float[] rotationDeg,
                               float[] poseRotDeg, float[] posePosUnits, float[] poseScale) {
        return place(g, scale, rotationDeg, poseRotDeg, posePosUnits, poseScale, null);
    }

    /**
     * <b>As above, with the creature LOOKING somewhere</b> — the head-follow the mod renderer does
     * every frame and the rig did not.
     *
     * <p>Worth being precise about what was missing, because "head-follow does not work" sounds
     * like the rig cannot tell which way the creature is facing and it never did. The server knows
     * exactly: {@code mob.getYHeadRot()} and {@code mob.getXRot()} are live and synced, and
     * {@code WishMobRenderer} builds its own angles from the same two fields
     * ({@code yHeadRot - bodyRot}, clamped). The rig simply never applied them to the head group's
     * display. It is a missing write, not missing information.
     *
     * <p>Composed the way {@code RuntimeGeoModel} composes it —
     * {@code R(headYaw) · R(headPitch) · R(anim) · R(rest)} — with the Y turn negated by the
     * mirror's conjugation and the X turn left alone, the same rule as everywhere else here.
     *
     * @param headDeg {@code {yaw, pitch}} in degrees relative to the body, or null for no follow
     */
    public static Placed place(RigPlan.Group g, double scale, float[] rotationDeg,
                               float[] poseRotDeg, float[] posePosUnits, float[] poseScale,
                               float[] headDeg) {
        float s = (float) scale;

        // The ancestors' composed transform. Identity for a group whose articulation point hangs
        // off the model root, which is every ordinary group — so this reduces exactly to the
        // two-line derivation in the class javadoc and cannot change those numbers.
        org.joml.Quaternionf chainR = new org.joml.Quaternionf(
                g.chainRotationXyzw()[0], g.chainRotationXyzw()[1],
                g.chainRotationXyzw()[2], g.chainRotationXyzw()[3]);
        org.joml.Vector3f chainT = new org.joml.Vector3f(
                g.chainTranslation()[0], g.chainTranslation()[1], g.chainTranslation()[2]);

        // R(anim) * R(rest) -- the pose turns the bone from where its rest tilt already put it,
        // which is what `mulPose(anim...)` then `mulPose(rest...)` means on a PoseStack.
        org.joml.Quaternionf own = rotation(rotationDeg);
        if (poseRotDeg != null) {
            own = rotation(poseRotDeg).mul(own);
        }
        if (headDeg != null && (headDeg[0] != 0 || headDeg[1] != 0)) {
            own = headRotation(headDeg).mul(own);
        }
        org.joml.Vector3f pivot = new org.joml.Vector3f(
                g.pivotUnits()[0], g.pivotUnits()[1], g.pivotUnits()[2]);
        org.joml.Vector3f centre = new org.joml.Vector3f(
                g.originUnits()[0], g.originUnits()[1], g.originUnits()[2]);

        // This bone alone: m -> pivot + own*(m - pivot), i.e. rotation `own`, translation
        // `pivot - own*pivot`.
        org.joml.Vector3f dOwn = new org.joml.Vector3f(pivot)
                .sub(new org.joml.Vector3f(pivot).rotate(own));

        // Compose with the ancestors: (chainR, chainT) then (own, dOwn).
        org.joml.Quaternionf total = new org.joml.Quaternionf(chainR).mul(own);
        org.joml.Vector3f tTotal = new org.joml.Vector3f(dOwn).rotate(chainR).add(chainT);

        // The model is recentred on the group's own centre, so the display's origin corresponds to
        // model point `centre`. Put it where the composed transform sends that point:
        //     T = ( total*centre + tTotal ) * s / 16
        org.joml.Vector3f t = new org.joml.Vector3f(centre).rotate(total).add(tTotal)
                .mul(s / RigPlan.UNITS_PER_BLOCK);

        // The pose's position channel, mirrored into rig space and rotated by the ancestors just as
        // the bone's own displacement is.
        if (posePosUnits != null
                && (posePosUnits[0] != 0 || posePosUnits[1] != 0 || posePosUnits[2] != 0)) {
            t.add(new org.joml.Vector3f(-posePosUnits[0], posePosUnits[1], posePosUnits[2])
                    .rotate(chainR).mul(s / RigPlan.UNITS_PER_BLOCK));
        }

        float k = s * g.shrink();
        org.joml.Vector3f sc = poseScale == null
                ? new org.joml.Vector3f(k, k, k)
                : new org.joml.Vector3f(k * poseScale[0], k * poseScale[1], k * poseScale[2]);
        return new Placed(t, total, sc, new org.joml.Quaternionf());
    }
}
