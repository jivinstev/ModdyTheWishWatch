package com.moddingfromamod.vanilla;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Real sculpts, read off disk, for the vanilla-creature spike to measure against.</b>
 *
 * <p>The spike's hard constraint is that {@code sculpt_creature} is frozen and existing sculpts are
 * the input, so every number it reports has to come from a creature the agent really built. These
 * live in {@code corpus/tier-experiment/&lt;case&gt;/sculpt.json}.
 *
 * <h2>Why the path is walked and not written down</h2>
 *
 * <p>A dev client runs in {@code run/client-&lt;slot&gt;} and a Gate B server in
 * {@code run/gameTestServer} — neither is the repo root, so a plain
 * {@code Path.of("corpus/...")} resolves to nothing. {@code WishLadderGateD} already records what
 * that costs: not a compile error, but a FAIL minutes into a long run. Same walk-up here, and a
 * <b>loud</b> failure when the file genuinely is not there, because a fixture loader that quietly
 * returns an empty list turns every assertion built on it into a vacuous pass.
 */
public final class SpikeFixture {

    private SpikeFixture() {
    }

    /** How far up to look for the repo root before giving up. */
    private static final int MAX_UP = 6;

    /** The repo-relative home of the tier-experiment sculpts. */
    public static final String CORPUS_DIR = "corpus/tier-experiment";

    /**
     * The public stand-ins. The tier-experiment sculpts are a private corpus; the public repo carries one
     * made-up creature for each case a GameTest names, with the same boxes, parents, sizes and turns and its own names
     * and colours ({@code corpus/tier-experiment/<stand-in>/sculpt.json}), so the rig tests measure the same geometry.
     */
    public static final java.util.Map<String, String> STAND_INS = java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(java.util.Map.of(
                    "bowser-armB", "thornshell",
                    "sigmaboy-armB", "hoodie",
                    "tralalelo-armA", "legfish-a",
                    "tralalelo-armB", "legfish-b")));

    /** Is the private corpus here (this repo), or only the stand-ins (the public repo)? */
    public static final boolean PRIVATE_CORPUS = find(CORPUS_DIR + "/bowser-armB/sculpt.json") != null;

    /** The case to use for {@code caseName}: itself where the private corpus is here, else its public stand-in. */
    public static String real(String caseName) {
        return PRIVATE_CORPUS ? caseName : STAND_INS.getOrDefault(caseName, caseName);
    }

    /**
     * The measured fixture the spike is written against: a real 62-part, 6-group Spikor where the private corpus is
     * present, and its stand-in (the same geometry) where it is not, as in the public repo.
     */
    public static final String SPIKOR = real("bowser-armB");

    /**
     * Every committed tier-experiment sculpt — three creatures across three arms.
     *
     * <p>Nine real sculpts, not a hand-written fixture. Small, and honestly so: the wider archive
     * ({@code ~/mfam-eval-corpus}) is machine-local and simply absent in a cloud container, so a
     * spike run here measures these nine and must say nine rather than imply a corpus it cannot see.
     */
    public static final List<String> CASES = PRIVATE_CORPUS ? List.of(
            "bowser-arm0", "bowser-armA", "bowser-armB",
            "sigmaboy-arm0", "sigmaboy-armA", "sigmaboy-armB",
            "tralalelo-arm0", "tralalelo-armA", "tralalelo-armB")
            : List.of("thornshell", "hoodie", "legfish-a", "legfish-b");


    /** Locate a repo-relative path from wherever this JVM happens to be running. */
    public static Path find(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < MAX_UP && dir != null; up++, dir = dir.getParent()) {
            Path f = dir.resolve(repoRelative);
            if (Files.isReadable(f)) {
                return f;
            }
        }
        return null;
    }

    /** Is the corpus reachable at all from here? */
    public static boolean available(String caseName) {
        int bang = caseName.indexOf('!');
        if (bang > 0) {
            return available(caseName.substring(0, bang));
        }
        int hash = caseName.indexOf('#');
        if (hash > 0) {
            return available(caseName.substring(0, hash));
        }
        return caseName.startsWith("__")
                || find(CORPUS_DIR + "/" + caseName + "/sculpt.json") != null;
    }

    /**
     * <b>The bisect fixture: one box, nothing else.</b>
     *
     * <p>A whole creature cannot tell a per-box transform error from a grouping error from a
     * rotation error — they all present as "the parts are in the wrong places", which is how four
     * plausible causes were each refuted in turn without the number moving. One axis-aligned box at
     * a known place, rendered by both arms, isolates the per-box mapping from every other term: if
     * THIS matches, the transform is right and the fault is in composition; if it does not, nothing
     * downstream can be trusted.
     *
     * <p>Deliberately offset from the origin and asymmetric in all three axes, so a sign error
     * cannot hide behind symmetry — a cube centred on the model origin looks identical mirrored,
     * rotated 180, or placed by the wrong convention.
     */
    public static final String UNIT_BOX = "__unitbox";

    /** The one-box bisect creature, built in code rather than read off disk. */
    public static List<CreatureSculpt.Part> unitBox() {
        return List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "block", null,
                new float[]{0, 0, 0},
                new float[]{2, 4, 6},
                new float[]{10, 20, 14},
                0xFF3030));
    }

    /**
     * The same bisect box, TILTED — the next term after {@link #UNIT_BOX} matched pixel-for-pixel.
     *
     * <p>A tilt about each axis in turn, at an angle no vanilla element rotation allows, so the
     * per-axis sign and the Z-Y-X order are both exercised. If the unrotated box matches and this
     * one does not, the fault is the rotation convention and nothing else.
     */
    public static final String TILT_X = "__tiltx";
    public static final String TILT_Y = "__tilty";
    public static final String TILT_Z = "__tiltz";

    private static List<CreatureSculpt.Part> tilted(float rx, float ry, float rz) {
        return List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "block", null,
                new float[]{6, 12, 10},
                new float[]{2, 4, 6},
                new float[]{10, 20, 14},
                0xFF3030,
                new float[]{rx, ry, rz}, 0f, "", 0));
    }

    /**
     * <b>Two boxes in two different bone groups, separated along X</b> — the next bisect step after
     * a single box matched pixel-for-pixel.
     *
     * <p>A single box exercises one group's placement and says nothing about the placement of one
     * group RELATIVE to another, which is exactly what the whole-Spikor residual looks like in a
     * photograph: the parts are the right size and the right colours and are spread apart
     * horizontally.
     *
     * <p>Two boxes, no rotation, no shrink, different parents so {@link RigPlan} must emit two
     * groups. Different colours so the diff can say which one moved. If this matches, inter-group
     * placement is sound and the residual is in rotation composition; if it does not, this is the
     * smallest creature that reproduces the bug.
     */
    public static final String TWO_BOX = "__twobox";

    /**
     * <b>Two boxes of DIFFERENT SIZE and colour in one group</b> — the fixture that can tell a
     * geometry swap from a texture swap.
     *
     * <p>{@code __twobox}'s two boxes are the same size, so "the boxes are in each other's places"
     * and "the boxes have each other's skins" produce a byte-identical photograph. That ambiguity
     * is why an X flip looked like the fix for it and then did nothing for a real creature: the
     * picture was consistent with both, and I picked the one that moved the geometry.
     *
     * <p>Here the left box is twice the height of the right one. A geometry swap moves the tall box;
     * a texture swap leaves it where it is and recolours it. One photograph, unambiguous.
     */
    public static final String TWO_SIZE = "__twosize";

    /** Two differently-SIZED boxes in one group. */
    public static List<CreatureSculpt.Part> twoSize() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "tall", null, new float[]{0, 0, 0},
                        new float[]{-14, 4, 6}, new float[]{-6, 28, 14}, 0xFF3030),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "short", null, new float[]{0, 0, 0},
                        new float[]{6, 4, 6}, new float[]{14, 14, 14}, 0x3030FF));
    }

    /**
     * <b>The same two boxes, offset in Y instead of X</b> — the axis discriminator.
     *
     * <p>{@code __twosize} fails and every one-box fixture passes, so the fault is in how MULTIPLE
     * elements sit inside one model. This asks whether it is X-specific (the mirror/flip family,
     * where every earlier bug in this rig has lived) or true of any axis. Same two boxes, same
     * sizes, same colours, stacked vertically with no X offset at all.
     */
    public static final String TWO_Y = "__twoy";

    /** Two differently-sized boxes stacked in Y. */
    public static List<CreatureSculpt.Part> twoY() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "tall", null, new float[]{0, 0, 0},
                        new float[]{-4, 4, 6}, new float[]{4, 28, 14}, 0xFF3030),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "short", null, new float[]{0, 0, 0},
                        new float[]{-4, 30, 6}, new float[]{4, 40, 14}, 0x3030FF));
    }

    /**
     * <b>Two boxes at ASYMMETRIC X positions</b> — the fixture that finally tests the X flip.
     *
     * <p>Every earlier multi-box fixture is symmetric in POSITION: {@code __twobox} and
     * {@code __twosize} both sit at ±6..14, so their group origin is x=0 and reflecting the model
     * about it maps the pair onto itself. Spikor's first two parts are a leg PAIR and do the same.
     * A reflection cannot be measured on anything symmetric, and every fixture so far was.
     *
     * <p>Here the boxes are at −12..−4 and 0..4 — different sizes AND different distances from the
     * group origin, so a reflection about it is unmistakable.
     */
    public static final String ASYM = "__asym";

    /** Two boxes, asymmetric in both size and X position. */
    public static List<CreatureSculpt.Part> asym() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "big", null, new float[]{0, 0, 0},
                        new float[]{-12, 4, 6}, new float[]{-4, 28, 14}, 0xFF3030),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "small", null, new float[]{0, 0, 0},
                        new float[]{0, 4, 6}, new float[]{4, 14, 14}, 0x3030FF));
    }

    /**
     * <b>Spikor's first three parts, rebuilt synthetically</b> — separates "this geometry" from
     * "the real sculpt pipeline".
     *
     * <p>{@code SPIKOR + "!first3"} (legL, legR, footL) fails while every synthetic fixture passes,
     * including a genuinely asymmetric one. Those three parts are unrotated, uninflated and
     * unpatterned, so if the same numbers built by hand ALSO fail the fault is in the geometry
     * shape; if they pass, it is in something the real parser, packer or atlas does.
     */
    public static final String LEGS3 = "__legs3";

    /** legL, legR and footL copied verbatim, with the pivots kept. */
    public static List<CreatureSculpt.Part> legs3() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "legL", null, new float[]{-7, 16, 1},
                        new float[]{-11, 0, -4}, new float[]{-3, 16, 6}, 0xC8A064),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "legR", null, new float[]{7, 16, 1},
                        new float[]{3, 0, -4}, new float[]{11, 16, 6}, 0x64C8A0),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "footL", null, new float[]{-7.5f, 0, -7},
                        new float[]{-12, 0, -10}, new float[]{-3, 5, -4}, 0x6464C8));
    }

    /**
     * Perturbations of {@link #LEGS3}, one variable each, to name what breaks it.
     *
     * <ul>
     *   <li>{@code a} — pivots zeroed. They are non-zero on the real parts and zero on every
     *       passing fixture, and with no rotation they should not matter at all.</li>
     *   <li>{@code b} — Z shifted wholly positive. Every passing fixture lives at z 6..14; these
     *       parts straddle zero.</li>
     *   <li>{@code c} — {@code footL} widened by one to an EVEN width. It is 9 wide, so its centre
     *       and the group's origin both land on a half unit; every passing fixture is integer.</li>
     * </ul>
     */
    public static final String LEGS3A = "__legs3a";
    public static final String LEGS3B = "__legs3b";
    public static final String LEGS3C = "__legs3c";

    private static List<CreatureSculpt.Part> legs3Variant(String v) {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>();
        for (CreatureSculpt.Part p : legs3()) {
            float[] piv = "a".equals(v) ? new float[]{0, 0, 0} : p.pivot();
            float dz = "b".equals(v) ? 16f : 0f;
            float toX = "c".equals(v) && "footL".equals(p.name()) ? -4f : p.to()[0];
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), piv,
                    new float[]{p.from()[0], p.from()[1], p.from()[2] + dz},
                    new float[]{toX, p.to()[1], p.to()[2] + dz},
                    p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        }
        return out;
    }

    /** Two boxes, two groups, offset along X — the inter-group bisect. */
    public static List<CreatureSculpt.Part> twoBox() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "left", null, new float[]{0, 0, 0},
                        new float[]{-14, 4, 6}, new float[]{-6, 20, 14}, 0xFF3030),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "right", null, new float[]{0, 0, 0},
                        new float[]{6, 4, 6}, new float[]{14, 20, 14}, 0x3030FF));
    }

    /**
     * <b>The same two boxes, in two SEPARATE bone groups</b> — the inter-group bisect.
     *
     * <p>{@link #TWO_BOX} looked like an inter-group test and was not: both parts have no parent, so
     * {@link RigPlan} folds them into one {@code root} group and the fixture measures intra-MODEL
     * layout. It found a real bug there (an X reversal inside the item model) and, once that was
     * fixed, reads 0 px — while a whole creature did not move at all, which is the tell that the
     * two fixtures are asking different questions.
     *
     * <p>Distinct parents force two displays, so this is the first fixture that measures where one
     * group is placed RELATIVE to another — the only remaining term between a pixel-exact primitive
     * and a 51.7% creature.
     */
    public static final String TWO_GROUP = "__twogroup";

    /** Two boxes, genuinely two groups, offset along X. */
    public static List<CreatureSculpt.Part> twoGroup() {
        // A group forms at an ARTICULATION POINT -- a part that something else names as its parent.
        // Two boxes with distinct rotations and parents that do not EXIST both fall to `root` and
        // become one display, which is how the first cut of this fixture silently measured the same
        // thing TWO_BOX does. So each side is a real bone with a real child hanging off it.
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "boneL", null, new float[]{-10, 12, 10},
                        new float[]{-14, 4, 6}, new float[]{-6, 20, 14}, 0xFF3030,
                        new float[]{0, 0, 20}, 0f, "", 0),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "tipL", "boneL", new float[]{-10, 22, 10},
                        new float[]{-12, 20, 8}, new float[]{-8, 24, 12}, 0xFFFFFF,
                        new float[]{0, 0, 0}, 0f, "", 0),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "boneR", null, new float[]{10, 12, 10},
                        new float[]{6, 4, 6}, new float[]{14, 20, 14}, 0x3030FF,
                        new float[]{0, 0, -20}, 0f, "", 0),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "tipR", "boneR", new float[]{10, 22, 10},
                        new float[]{8, 20, 8}, new float[]{12, 24, 12}, 0xFFD030,
                        new float[]{0, 0, 0}, 0f, "", 0));
    }

    /**
     * <b>A tilted child of a tilted bone</b> — the last placement path nothing else reaches.
     *
     * <p>Every other fixture leaves {@link RigPlan#ancestorChain} at identity: a bone's own tilt
     * rides on its display, and a child with no tilt of its own is never split out. Only a child
     * that is ITSELF tilted becomes its own display and has to be turned by its parent's angle as
     * well as its own — and it is the one thing between four pixel-exact primitives and a creature
     * that is still 46% wrong.
     *
     * <p>The child is offset asymmetrically in X on purpose, so an intra-model reversal cannot hide
     * behind symmetry the way it did in {@code __twogroup}, whose boxes both sit centred on model 8.
     */
    public static final String CHAIN = "__chain";

    /** A tilted bone with a tilted child hanging off it, offset in X. */
    public static List<CreatureSculpt.Part> chain() {
        return List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "arm", null, new float[]{0, 6, 10},
                        new float[]{-4, 4, 6}, new float[]{4, 20, 14}, 0xFF3030,
                        new float[]{0, 0, 25}, 0f, "", 0),
                new com.moddingfromamod.runtime.CreatureSculpt.Part(
                        "hand", "arm", new float[]{6, 20, 10},
                        new float[]{2, 18, 7}, new float[]{12, 24, 13}, 0x3030FF,
                        new float[]{17, 0, 0}, 0f, "", 0));
    }

    /**
     * <b>One box tilted about all THREE axes</b> — the only fixture where compose ORDER can matter.
     *
     * <p>Every single-axis tilt reads the same under any ordering, which is exactly why the `xyz`
     * and `zyx` sweeps came back byte-identical and proved nothing about it. 14 of Spikor's 62
     * parts carry a multi-axis tilt, so if bedrock's intrinsic Z-Y-X is not reproduced this is
     * where a real creature diverges and no synthetic fixture so far could see it.
     *
     * <p>Three deliberately unequal angles, none of them a multiple of the others, so a swapped
     * pair cannot coincide with the right answer.
     */
    public static final String MULTI_TILT = "__multi";

    /** One box tilted on all three axes at once. */
    public static List<CreatureSculpt.Part> multiTilt() {
        return List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "block", null, new float[]{6, 12, 10},
                new float[]{2, 4, 6}, new float[]{10, 20, 14}, 0xFF3030,
                new float[]{23, 41, 17}, 0f, "", 0));
    }

    /**
     * <b>A box that is LONG IN Z and narrow in X</b> — the fixture every earlier one failed to be.
     *
     * <p>{@code __unitbox} is 8x8 in plan, {@code __twobox} and {@code __twogroup} are symmetric
     * arrangements, {@code __chain} and {@code __multi} are small and roughly square. A creature
     * turned by the wrong angle about Y keeps its HEIGHT and changes its WIDTH — which is exactly
     * what Spikor does (185 px vs 217 px at an identical 299 px tall) — and a plan-square fixture
     * cannot show that at all.
     *
     * <p>4:1 in plan, so a few degrees of yaw error is tens of pixels. If this reads 0 px the
     * orientation is right and the residual is elsewhere; if it does not, the yaw offset that was
     * fitted on a square box was fitted on the one shape that could not measure it.
     */
    public static final String PLAN_BAR = "__planbar";

    /** A 4:1-in-plan bar, offset off the origin so its placement is exercised too. */
    public static List<CreatureSculpt.Part> planBar() {
        return List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "bar", null, new float[]{0, 0, 0},
                new float[]{-4, 4, -16}, new float[]{4, 12, 16}, 0xFF3030));
    }

    /**
     * <b>One box too big for the vanilla element window</b>, so {@link RigPlan} must shrink the
     * model and hand the size back through the display's scale.
     *
     * <p>A vanilla item-model element is bounded to [-16, 32]; a group wider than that is emitted
     * small and scaled up. That round trip is the one part of the placement arithmetic the
     * single-box fixture never touched — its span is 16 and it fits — so it has never been
     * measured against a photograph.
     */
    public static final String BIG_BOX = "__bigbox";

    /** One box wide enough to force a shrink. */
    public static List<CreatureSculpt.Part> bigBox() {
        return List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "block", null, new float[]{0, 0, 0},
                new float[]{-40, 4, 6}, new float[]{40, 20, 14}, 0x30FF30));
    }

    /** The raw sculpt document for a tier-experiment case. */
    public static JsonObject document(String caseName) {
        String rel = CORPUS_DIR + "/" + caseName + "/sculpt.json";
        Path f = find(rel);
        if (f == null) {
            throw new IllegalStateException("fixture not found: " + rel
                    + " (looked up to " + MAX_UP + " levels above " + Path.of("").toAbsolutePath()
                    + ") — refusing to measure a spike against an empty creature");
        }
        try {
            return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + f, e);
        }
    }

    /**
     * A case's parts, through the REAL parser.
     *
     * <p>Deliberately {@code CreatureSculpt.parseParts} rather than a hand-rolled reader: it applies
     * the same clamps, the same {@code MAX_PARTS} truncation and the same rotation handling the
     * shipped path does, so the rig is measured on the creature the mod would actually draw.
     */

    /**
     * <b>A real creature with every rotation zeroed</b> — {@code SPIKOR + "!norot"}.
     *
     * <p>Splits a real sculpt along the one axis that matters without the contamination the
     * {@code #group} filter turned out to have: both arms are handed the SAME list, the creature
     * keeps its full extent, and nothing about arm A's own sizing changes. If a creature with no
     * tilts is exact then the fault is rotation-related; if it is not, rotation is innocent.
     */
    public static List<CreatureSculpt.Part> unrotated(List<CreatureSculpt.Part> all) {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>(all.size());
        for (CreatureSculpt.Part p : all) {
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    p.colour(), CreatureSculpt.NO_ROTATION, p.inflate(), p.pattern(),
                    p.patternColour()));
        }
        return out;
    }

    /**
     * <b>A real creature with every PATTERN stripped</b> — {@code SPIKOR + "!nopattern"}.
     *
     * <p>The ATOM arm's one deliberate, recorded fidelity loss: {@code docs/ATOM_RIG_PLAN.md} §5.3
     * deviation 2 leaves the eight pattern cubes unbuilt, so a patterned box renders FLAT on that
     * arm while the mod renderer and the pack rig both paint it into their texture. Spikor's
     * {@code body} and {@code head} carry a {@code scales} pattern and omegalad's {@code body} a
     * {@code gradient} one, so on those two creatures a straight atoms-vs-A delta is one number
     * covering two different things.
     *
     * <p><b>This is the seam that separates them, and it is a seam rather than a mask on purpose.</b>
     * Both arms are built from the SAME stripped parts, so the pattern is not estimated from part
     * extents, not inferred from which colour ray a pixel lands on, and not subtracted from
     * anything — it is simply absent, from both arms, by construction. The pattern's contribution
     * is then the difference between two measurements of the same creature, which is the only form
     * of that number nothing has to be trusted about.
     *
     * <p>Everything else — geometry, tilt, colour, scale, the parent graph — is untouched, so a
     * residual that survives the strip is the atom rig's own.
     */
    public static List<CreatureSculpt.Part> unpatterned(List<CreatureSculpt.Part> all) {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>(all.size());
        for (CreatureSculpt.Part p : all) {
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    p.colour(), p.rotation(), p.inflate(), "", p.patternColour()));
        }
        return out;
    }

    /**
     * <b>Phase 4's WORST CASE: 64 boxes, every one of them under a bone the walk clip moves.</b>
     *
     * <p>{@code MAX_PARTS} is 64, so this is the largest creature the agent can author, and its
     * parent graph is the shape that costs the most: one articulation point ({@code body}) with 63
     * children, so the stock walk clip's {@code body} position channel moves the WHOLE rig every
     * frame. Spikor's worst walk frame writes 38 of 62 (§5.7.4) because its five animated bones
     * carry only part of the creature between them; this writes 64 of 64.
     *
     * <p>It is deliberately not a creature anybody would sculpt. Phase 4's question is where the
     * atom rig stops being affordable, and a ceiling measured on a typical creature answers a
     * different question from a ceiling measured on the worst one the format permits.
     */
    public static final String WORST64 = "__worst64";

    /** 64 boxes in a lattice, 63 of them children of {@code body}. */
    public static List<CreatureSculpt.Part> worst64() {
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>();
        // The parent, and an articulation point precisely because the others name it.
        out.add(new CreatureSculpt.Part("body", null, new float[]{0, 8, 0},
                new float[]{-3, 8, -3}, new float[]{3, 14, 3}, 0xC0C0C0));
        // A 4x4x4 lattice minus the cell the body occupies -- 63 boxes, each its own display, all
        // of them descendants of `body` and therefore all of them written when `body` moves.
        int i = 0;
        for (int gx = 0; gx < 4 && out.size() < 64; gx++) {
            for (int gy = 0; gy < 4 && out.size() < 64; gy++) {
                for (int gz = 0; gz < 4 && out.size() < 64; gz++) {
                    float x = -9f + gx * 6f;
                    float y = 1f + gy * 6f;
                    float z = -9f + gz * 6f;
                    // Colours spread over the cube so the palette is not degenerate: a mask built
                    // from one colour would measure a fraction of the creature.
                    int colour = ((40 + gx * 60) << 16) | ((40 + gy * 60) << 8) | (40 + gz * 60);
                    out.add(new CreatureSculpt.Part("bx" + (i++), "body",
                            new float[]{x + 2f, y, z + 2f},
                            new float[]{x, y, z}, new float[]{x + 4f, y + 4f, z + 4f}, colour));
                }
            }
        }
        return List.copyOf(out);
    }

    public static List<CreatureSculpt.Part> parts(String caseName) {
        int bang = caseName.indexOf('!');
        if (bang > 0) {
            List<CreatureSculpt.Part> base = parts(caseName.substring(0, bang));
            String op = caseName.substring(bang + 1);
            if ("norot".equals(op)) {
                return unrotated(base);
            }
            if ("nopattern".equals(op)) {
                return unpatterned(base);
            }
            if (op.startsWith("first")) {
                // Both arms get the same truncated list, so this cannot advantage either -- unlike
                // the `#group` filter, which changes what arm A's own entity computes for itself.
                int n = Integer.parseInt(op.substring(5));
                return base.subList(0, java.lang.Math.min(n, base.size()));
            }
            return base;
        }
        if (UNIT_BOX.equals(caseName)) {
            return unitBox();
        }
        if (WORST64.equals(caseName)) {
            return worst64();
        }
        if (TWO_BOX.equals(caseName)) {
            return twoBox();
        }
        if (TWO_GROUP.equals(caseName)) {
            return twoGroup();
        }
        if (CHAIN.equals(caseName)) {
            return chain();
        }
        if (MULTI_TILT.equals(caseName)) {
            return multiTilt();
        }
        if (PLAN_BAR.equals(caseName)) {
            return planBar();
        }
        if (TWO_SIZE.equals(caseName)) {
            return twoSize();
        }
        if (TWO_Y.equals(caseName)) {
            return twoY();
        }
        if (ASYM.equals(caseName)) {
            return asym();
        }
        if (LEGS3.equals(caseName)) {
            return legs3();
        }
        if (LEGS3A.equals(caseName)) {
            return legs3Variant("a");
        }
        if (LEGS3B.equals(caseName)) {
            return legs3Variant("b");
        }
        if (LEGS3C.equals(caseName)) {
            return legs3Variant("c");
        }
        if (BIG_BOX.equals(caseName)) {
            return bigBox();
        }
        if (TILT_X.equals(caseName)) {
            return tilted(31f, 0f, 0f);
        }
        if (TILT_Y.equals(caseName)) {
            return tilted(0f, 31f, 0f);
        }
        if (TILT_Z.equals(caseName)) {
            return tilted(0f, 0f, 31f);
        }
        JsonObject doc = document(caseName);
        List<CreatureSculpt.Part> parts =
                CreatureSculpt.parseParts(doc.getAsJsonArray("parts"));
        if (parts.isEmpty()) {
            throw new IllegalStateException(caseName + " parsed to zero parts");
        }
        return parts;
    }

    /** The creature's authored scale, defaulting to 1 when the document does not say. */
    public static double scale(String caseName) {
        int bang = caseName.indexOf('!');
        if (bang > 0) {
            // "!scale12" means 1.2, "!scale1" means 1.0. Scale is the ONLY variable left once the
            // geometry is exact: `__legs3` is 0 px at scale 1, and `SPIKOR + "!first3"` -- the same
            // three boxes from the real document, with a byte-identical silhouette -- differs by 34%
            // of its pixels at scale 1.2, every one of them a uniform brightness ratio.
            String op = caseName.substring(bang + 1);
            if (op.startsWith("scale")) {
                double v = Double.parseDouble(op.substring(5));
                while (v >= 10.0) {
                    v /= 10.0;
                }
                return v;
            }
            return scale(caseName.substring(0, bang));
        }
        int hash = caseName.indexOf('#');
        if (hash > 0) {
            return scale(caseName.substring(0, hash));
        }
        if (caseName.startsWith("__")) {
            return 1.0;
        }
        JsonObject doc = document(caseName);
        return doc.has("scale") ? doc.get("scale").getAsDouble() : 1.0;
    }
}
