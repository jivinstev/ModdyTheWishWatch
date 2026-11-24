package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.MatrixUtil;
import com.mojang.math.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Why a TILTED atom is lit wrong, reproduced in milliseconds — {@code docs/ATOM_RIG_PLAN.md}
 * Phase 2a.</b>
 *
 * <p>Phase 1 photographed nine synthetic fixtures on the atom arm and found the geometry exact on
 * all nine and the SHADING wrong on the two tilted ones ({@code __tiltx} 2614 px, {@code __tilty}
 * 1263 px), with the exact pack rig — same 31°, same display, uniform scale — matching the mod
 * renderer to the pixel. §5.4.1 recorded the 2×2 and said plainly that <b>the mechanism was not
 * established</b>, because the obvious arithmetic does not explain it: for {@code M = T·L·S·R} with
 * an axis-aligned model normal, BOTH branches of {@link PoseStack#mulPose(Matrix4f)} give the
 * correctly rotated direction, since a diagonal scale cannot turn an axis-aligned vector.
 *
 * <p>That is true, and it is true of <b>vanilla</b>. It is not true of the client Phase 1 was
 * measured on.
 *
 * <h2>The mechanism, in four lines of somebody else's source</h2>
 *
 * <ol>
 *   <li><b>Vanilla {@code FaceBakery.bakeQuad}</b> returns
 *       {@code new BakedQuad(aint, tintIndex, direction, sprite, shade)} and never fills the
 *       {@code NORMAL} element, so a vanilla-baked quad's normal bytes are <b>zero</b>.</li>
 *   <li><b>NeoForge patches it</b> — {@code ClientHooks.fillNormal(aint, direction)}, which writes
 *       the geometric normal into {@code faceData[i * 8 + 7]} (bytes 28–30) of all four
 *       vertices.</li>
 *   <li><b>Vanilla {@code VertexConsumer.putBulkData}</b> lights each quad from
 *       {@code pose.transformNormal(quad.getDirection().getNormal())}, and
 *       {@code Pose.transformNormal} <b>normalizes</b> whenever {@code trustedNormals} is
 *       false.</li>
 *   <li><b>NeoForge patches that too</b>, appending
 *       {@code applyBakedNormals(vector3f, bytebuffer, pose.normal())}, whose body is
 *       {@code generated.set(nx/127f, ny/127f, nz/127f); generated.mul(normalTransform);} — the
 *       RAW matrix from {@code Pose.normal()}, with <b>no normalize</b>. It overwrites the
 *       normalized vector vanilla just computed.</li>
 * </ol>
 *
 * <p>So on a NeoForge client the normal that reaches the GPU is {@code N · n} unnormalized, where
 * {@code N} is whatever {@link PoseStack.Pose#computeNormalMatrix()} left behind — the
 * inverse-transpose {@code Q·L·S⁻¹}. For a model normal on axis {@code i} that vector has length
 * <b>1/|s_i|</b>. {@code BufferBuilder.normalIntValue} then does
 * {@code (byte)((int)(clamp(f,-1,1) * 127.0F))}, and <b>clamping a vector CHANGES ITS
 * DIRECTION</b> unless it is axis-aligned in world space.
 *
 * <p>That is the whole 2×2, and it is why the corner Phase 1 found is the only broken one:
 *
 * <table border="1">
 *   <caption>which corner clamping can reach</caption>
 *   <tr><th></th><th>uniform display scale</th><th>non-uniform display scale</th></tr>
 *   <tr><td><b>no rotation</b></td>
 *       <td>{@code |s|=1} ⇒ the matrix keeps JOML's {@code PROPERTY_ORTHONORMAL} ⇒ {@code N} is a
 *           rotation ⇒ unit ⇒ nothing clamps</td>
 *       <td>every world normal is axis-aligned, so clamping a longer vector lands back on the
 *           SAME unit axis — the length error cancels exactly</td></tr>
 *   <tr><td><b>rotation</b></td>
 *       <td>{@code |s|=1} ⇒ orthonormal ⇒ unit</td>
 *       <td>🔴 a slanted normal of length {@code 1/|s_i|}, clamped per component — a different
 *           direction, and a brighter one</td></tr>
 * </table>
 *
 * <h2>What this test asserts, and why the numbers are the photographs' own</h2>
 *
 * <p>Everything below is plain Java on the real classpath — {@link PoseStack}, {@link Transformation}
 * and JOML need no Minecraft bootstrap — so the render path is DRIVEN rather than modelled, and only
 * the two lines this repo cannot call (NeoForge's {@code applyBakedNormals} and
 * {@code BufferBuilder.normalIntValue}, both four lines long) are written out here.
 *
 * <p>The predicted colours are compared against {@code docs/img/atom-rig/tiltx-*.png} read
 * byte-for-byte: arm A and the exact pack rig are {@code 0xB9 / 0xE1 / 0xFB} on the three visible
 * faces of {@code __tiltx}, and the atom arm is {@code 0xBA / 0xFB} — the {@code 0xE1} face
 * saturating into {@code 0xFB} and taking its 2614 px with it. One constant is fitted from the
 * photographs and one only: {@link #LIGHTMAP}, the full-brightness lightmap, read off the face that
 * is saturated on every arm. The other two faces are then predictions.
 */
public class AtomNormalPathTest {

    // ---------------------------------------------------------------- the fixture

    /** {@code SpikeFixture.tilted}: origin {2,4,6} → {10,20,14}, i.e. 8 × 16 × 8 units. */
    private static final Vector3f ATOM_SCALE = new Vector3f(0.5f, 1.0f, 0.5f);
    /**
     * A uniform unit scale — what the deleted grouped/exact pack rig guaranteed its displays by
     * folding the creature's scale into the model. Kept as the ORACLE arm of the normal-path
     * arithmetic below: it is vanilla's correct answer for the same rotation, not a live rig.
     */
    private static final Vector3f UNIT_SCALE = new Vector3f(1.0f, 1.0f, 1.0f);
    /** {@code VanillaCreatureAbClientTest}'s {@code stageyaw} default. */
    private static final float STAGE_YAW = 90.0f;

    private static final Vector3f[] AXES = {
            new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0)};
    private static final String[] AXIS_NAMES = {"up", "down", "south", "north", "east", "west"};

    // ------------------------------------------------------- vanilla's own constants

    /** {@code Lighting.setupLevel}'s two level diffuse directions. */
    private static final Vector3f L0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3f L1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();

    /**
     * The full-brightness lightmap, the ONE constant fitted from the photographs — {@code 0xFB}
     * is the saturated face and appears on every arm of every fixture, so it measures the sky
     * light rather than anything this test is trying to prove.
     */
    private static final float LIGHTMAP = 251.0f / 255.0f;

    /**
     * <b>How close a prediction has to be, and why it is not zero.</b> Everything from the vertex
     * normal onwards is modelled here in two constants ({@link #LIGHTMAP} and the diffuse
     * formula) against a float pipeline that interpolates, multiplies a texel by a vertex colour
     * and rounds once into an 8-bit framebuffer. The last bit is below that fidelity — measured,
     * every prediction lands within one — so it is asserted at one and the FINDING is the 26-unit
     * jump, not the LSB.
     */
    private static final int BYTE_SLOP = 1;

    private static void assertByte(int expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= BYTE_SLOP,
                what + ": expected 0x" + Integer.toHexString(expected) + " +/- " + BYTE_SLOP
                        + " but was 0x" + Integer.toHexString(actual));
    }

    /** {@code minecraft_mix_light}, verbatim from vanilla's own {@code light.glsl}. */
    private static float diffuse(Vector3f n) {
        float a = Math.max(0.0f, L0.dot(n));
        float b = Math.max(0.0f, L1.dot(n));
        return Math.min(1.0f, (a + b) * 0.6f + 0.4f);
    }

    /** {@code BufferBuilder.normalIntValue} then the GL_BYTE normalize the shader receives. */
    private static Vector3f throughTheWire(Vector3f n) {
        return new Vector3f(wire(n.x), wire(n.y), wire(n.z));
    }

    private static float wire(float f) {
        byte b = (byte) ((int) (Math.max(-1.0f, Math.min(1.0f, f)) * 127.0f) & 0xFF);
        return Math.max(b / 127.0f, -1.0f);
    }

    /** The red channel a {@code #FF3030} face lands on, which is what the PNGs record. */
    private static int redByte(Vector3f normalAtTheGpu) {
        return (int) (255.0f * LIGHTMAP * diffuse(normalAtTheGpu));
    }

    // ------------------------------------------------------------------ the path

    /**
     * The pose an item display really hands to {@code renderQuadList}, from the sources: the fresh
     * {@code PoseStack} {@code LevelRenderer} makes (the view matrix lives on {@code RenderSystem}'s
     * own model-view stack, not here) → {@code EntityRenderDispatcher}'s translate →
     * {@code DisplayRenderer}'s {@code mulPose(orientation)} and {@code mulPose(matrix)} →
     * {@code ItemDisplayRenderer}'s {@code mulPose(Axis.YP.rotation(PI))} → {@code ItemRenderer}'s
     * translate. The model's {@code display} block is empty ({@code AtomLibrary.model}), so
     * {@code ItemTransform.NO_TRANSFORM} contributes nothing.
     */
    private static PoseStack.Pose pose(Quaternionf left, Vector3f scale, Quaternionf right,
                                       float yawDeg) {
        PoseStack ps = new PoseStack();
        ps.translate(0.5, 0.0, 0.5);
        ps.rotate(new Quaternionf().rotationYXZ((float) (-Math.PI / 180.0) * yawDeg, 0.0f, 0.0f));
        ps.mulPose(new Transformation(new Vector3f(0.0f, 0.5f, 0.0f), left, scale, right)
                .getMatrix());
        ps.rotate(new Quaternionf().rotationY((float) Math.PI));
        ps.translate(-0.5f, -0.5f, -0.5f);
        return ps.last();
    }

    private static boolean orthonormal(Quaternionf left, Vector3f scale, Quaternionf right) {
        // 26.1 removed MatrixUtil.isOrthonormal; PoseStack.Pose.mulPose now asks checkPropertyRaw(m, 16) after
        // isPureTranslation has determined the properties, which is checkProperty(m, PROPERTY_ORTHONORMAL).
        return MatrixUtil.checkProperty(
                new Transformation(new Vector3f(), left, scale, right).getMatrix(), org.joml.Matrix4fc.PROPERTY_ORTHONORMAL);
    }

    /** What VANILLA sends: {@code Pose.transformNormal}, which normalizes an untrusted normal. */
    private static Vector3f vanillaNormal(PoseStack.Pose p, Vector3f modelNormal) {
        return p.transformNormal(modelNormal, new Vector3f());
    }

    /**
     * What NEOFORGE sends: {@code IVertexConsumerExtension.applyBakedNormals} overwrites vanilla's
     * answer with the baked normal times the RAW {@code Pose.normal()} matrix — no normalize.
     */
    private static Vector3f neoForgeNormal(PoseStack.Pose p, Vector3f modelNormal) {
        return new Vector3f(modelNormal).mul(p.normal());
    }

    // ------------------------------------------------------------------- reporting

    private record Row(String face, Vector3f vanilla, Vector3f neo, int vanillaByte, int neoByte) {
    }

    private static Row[] table(Quaternionf left, Vector3f scale, Quaternionf right, float yaw) {
        PoseStack.Pose p = pose(left, scale, right, yaw);
        Row[] rows = new Row[AXES.length];
        for (int i = 0; i < AXES.length; i++) {
            Vector3f v = vanillaNormal(p, AXES[i]);
            Vector3f n = neoForgeNormal(p, AXES[i]);
            rows[i] = new Row(AXIS_NAMES[i], v, n,
                    redByte(throughTheWire(v)), redByte(throughTheWire(n)));
        }
        return rows;
    }

    private static void report(String label, Quaternionf left, Vector3f scale, Quaternionf right) {
        Row[] rows = table(left, scale, right, STAGE_YAW);
        System.out.printf("%n== %s ==  scale=(%.3f,%.3f,%.3f)  isOrthonormal=%s%n",
                label, scale.x, scale.y, scale.z, orthonormal(left, scale, right));
        System.out.printf("  %-6s | %-27s %5s | %-27s %6s %5s%n",
                "face", "VANILLA normal (normalized)", "byte", "NEOFORGE normal (raw)", "len",
                "byte");
        for (Row r : rows) {
            System.out.printf("  %-6s | %s  %5s | %s %6.3f %5s%s%n", r.face(),
                    fmt(r.vanilla()), hex(r.vanillaByte()), fmt(r.neo()), r.neo().length(),
                    hex(r.neoByte()), r.neoByte() != r.vanillaByte() ? "   <-- DIFFERS" : "");
        }
    }

    private static String hex(int b) {
        return String.format("0x%02X", b);
    }

    private static String fmt(Vector3f v) {
        return String.format("(%+.4f,%+.4f,%+.4f)", v.x, v.y, v.z);
    }

    private static Quaternionf tilt(float x, float y, float z) {
        return RigTransform.rotation(new float[]{x, y, z});
    }

    // ---------------------------------------------------------------- the assertions

    @Test
    @DisplayName("the pure experiment: vanilla and NeoForge normals, all six faces, all three cases")
    void thePureExperiment() {
        report("ATOM  __tiltx  (non-uniform scale + rotation)", tilt(31, 0, 0), ATOM_SCALE,
                new Quaternionf());
        report("EXACT __tiltx  (unit scale + the same rotation)", tilt(31, 0, 0), UNIT_SCALE,
                new Quaternionf());
        report("ATOM  __tilty", tilt(0, 31, 0), ATOM_SCALE, new Quaternionf());
        report("ATOM  __tiltz", tilt(0, 0, 31), ATOM_SCALE, new Quaternionf());
        report("ATOM  __twosize (non-uniform scale, NO rotation)", new Quaternionf(), ATOM_SCALE,
                new Quaternionf());
        report("EXACT __unitbox (unit scale, no rotation)", new Quaternionf(), UNIT_SCALE,
                new Quaternionf());
    }

    @Test
    @DisplayName("VANILLA's normal is always the correct unit direction — a stock client is right")
    void vanillaIsAlwaysCorrect() {
        for (float[] deg : new float[][]{{0, 0, 0}, {31, 0, 0}, {0, 31, 0}, {0, 0, 31},
                {17, -23, 41}}) {
            Row[] atom = table(tilt(deg[0], deg[1], deg[2]), ATOM_SCALE, new Quaternionf(),
                    STAGE_YAW);
            Row[] exact = table(tilt(deg[0], deg[1], deg[2]), UNIT_SCALE, new Quaternionf(),
                    STAGE_YAW);
            for (int i = 0; i < atom.length; i++) {
                assertEquals(1.0f, atom[i].vanilla().length(), 1.0e-4f,
                        "vanilla normalizes, so every normal is unit");
                // The exact rig's own normal is the oracle: same rotation, uniform scale.
                assertTrue(atom[i].vanilla().distance(exact[i].vanilla()) < 1.0e-4f,
                        "vanilla's atom normal must equal the exact rig's on face "
                                + atom[i].face());
                assertByte(exact[i].vanillaByte(), atom[i].vanillaByte(),
                        "a STOCK client shades an atom as it shades the exact rig, face "
                                + atom[i].face());
            }
        }
    }

    @Test
    @DisplayName("NeoForge's normal is 1/|s| long, which is the whole defect")
    void theNeoForgeNormalIsScaledByTheInverseOfTheDisplaySCALE() {
        Row[] rows = table(tilt(31, 0, 0), ATOM_SCALE, new Quaternionf(), STAGE_YAW);
        // up/down ride sy = 1; north/south ride sz = 0.5; east/west ride sx = 0.5.
        float[] expected = {1.0f, 1.0f, 2.0f, 2.0f, 2.0f, 2.0f};
        for (int i = 0; i < rows.length; i++) {
            assertEquals(expected[i], rows[i].neo().length(), 1.0e-3f,
                    "face " + rows[i].face() + " should come out 1/|s| long");
        }
    }

    /**
     * <b>Option (a) of Phase 2a is impossible, and this is the proof.</b> The brief asks whether
     * the same geometry expressed through a DIFFERENT {@code (translation, left, scale, right)}
     * split could dodge the defect. It cannot: what reaches the GPU is {@code N · n} with no
     * normalize, {@code |N · n| = |S⁻¹ · R · n|}, and both rotations are length-preserving — so the
     * length is decided by the SCALE alone and is 1 for every face exactly when
     * {@code |s_x| = |s_y| = |s_z| = 1}. Which is option (b), and is the one thing the atom rig
     * cannot have, because the display's scale IS the box's size.
     */
    @Test
    @DisplayName("no (T,L,S,R) split can fix it — only |s| == 1 on all three axes gives unit normals")
    void onlyAUnitScaleGivesUnitNormals() {
        Quaternionf[] rights = {new Quaternionf(), new Quaternionf(0.3f, 0.1f, 0.05f, 1f)
                .normalize(), tilt(13, -47, 8)};
        record Case(Vector3f scale, boolean unit) {
        }
        Case[] cases = {
                new Case(new Vector3f(1f, 1f, 1f), true),
                new Case(new Vector3f(-1f, 1f, -1f), true),
                new Case(new Vector3f(0.5f, 1f, 0.5f), false),
                new Case(new Vector3f(1.2f, 1.2f, 1.2f), false),
                new Case(new Vector3f(2f, 0.25f, 3f), false),
                new Case(new Vector3f(1f, 1f, 0.9f), false)};
        for (Case c : cases) {
            for (Quaternionf right : rights) {
                float worst = 0.0f;
                for (Row r : table(tilt(31, 0, 0), c.scale(), right, STAGE_YAW)) {
                    worst = Math.max(worst, Math.abs(r.neo().length() - 1.0f));
                }
                assertEquals(c.unit(), worst < 1.0e-3f,
                        "scale " + c.scale() + " with right rotation " + right
                                + " — worst |N n| deviation " + worst);
            }
        }
    }

    @Test
    @DisplayName("the predicted pixels ARE the photographed pixels — docs/img/atom-rig/tiltx-*.png")
    void thePredictionMatchesThePhotographs() {
        Row[] atom = table(tilt(31, 0, 0), ATOM_SCALE, new Quaternionf(), STAGE_YAW);
        Row[] exact = table(tilt(31, 0, 0), UNIT_SCALE, new Quaternionf(), STAGE_YAW);

        // The three faces the camera sees at stageyaw 90: the box's own ±X (which an X-tilt does
        // not move), the ±Z pair the tilt swings, and the top.
        //   tiltx-armA.png / tiltx-exact-pack-rig.png : 0xB9 x23812 · 0xE1 x10456 · 0xFB x3340
        //   tiltx-atom-shaded.png                     : 0xBA x23812 ·               0xFB x13796
        assertByte(0xB9, exact[4].vanillaByte(), "the +/-X face, unmoved by an X tilt");
        assertByte(0xE1, exact[2].vanillaByte(), "the lit +/-Z face");
        assertByte(0xFB, exact[0].vanillaByte(), "the top, already saturated");

        assertByte(0xBA, atom[4].neoByte(),
                "clamping a world-axis-aligned normal lands back on the same axis");
        assertByte(0xFB, atom[2].neoByte(),
                "the lit +/-Z face saturates - 2614 px of __tiltx, and the whole finding");
        assertByte(0xFB, atom[0].neoByte(), "the top is unchanged");
        // 0xE1 -> 0xFB is 26 units of red. Nothing here is within the slop of anything else.
        assertTrue(atom[2].neoByte() - exact[2].vanillaByte() >= 20,
                "the finding is a jump far larger than the model's own fidelity");
    }

    @Test
    @DisplayName("__tilty's face goes 0.65 -> 0.82 rather than saturating, and that is predicted")
    void tiltyIsPredictedToo() {
        // §5.4.1: arm A 0.63 x5705 · 0.98 x1672 · 0.65 x1263, atom the same but 0.82 x1263.
        Row[] atom = table(tilt(0, 31, 0), ATOM_SCALE, new Quaternionf(), STAGE_YAW);
        Row[] exact = table(tilt(0, 31, 0), UNIT_SCALE, new Quaternionf(), STAGE_YAW);
        int correct = -1;
        int broken = -1;
        for (int i = 0; i < atom.length; i++) {
            if (atom[i].neoByte() != exact[i].vanillaByte()) {
                correct = exact[i].vanillaByte();
                broken = atom[i].neoByte();
                break;
            }
        }
        assertEquals(0.65f, correct / 255.0f, 0.01f, "arm A's dim face");
        assertEquals(0.82f, broken / 255.0f, 0.01f, "the atom arm's, measured in Phase 1");
    }

    /**
     * <b>The blast radius, per creature, with no client run.</b> Phase 2a's option (c) asks how
     * visible this is on a REAL creature. A photograph of it would measure a NeoForge dev client,
     * which is not the audience the atom rig exists for — so the honest number is how many of a
     * creature's boxes are in the broken corner at all, and that is pure arithmetic over the same
     * plan the rig ships.
     *
     * <p>A box is affected when a face's predicted colour differs between vanilla's normalized
     * normal and NeoForge's raw one. Because the display's scale carries the box's size, the scale
     * is non-unit for essentially every box, so what actually decides it is the ROTATION: a box
     * whose resolved rotation leaves every face on a world axis is safe (clamping a longer vector
     * lands back on the same axis), and any other rotation is not.
     */
    @Test
    @DisplayName("how many of a real creature's boxes are in the broken corner")
    void theBlastRadiusPerCreature() {
        StringBuilder table = new StringBuilder(
                "\n  fixture                      boxes  affected   faces  affected\n");
        int boxesAll = 0;
        int hitBoxesAll = 0;
        java.util.List<String> cases = new java.util.ArrayList<>(SpikeFixture.CASES);
        for (String name : cases) {
            if (!SpikeFixture.available(name)) {
                table.append(String.format("  %-28s  (not available)%n", name));
                continue;
            }
            var mirrored = RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(name));
            int boxes = 0;
            int hitBoxes = 0;
            int faces = 0;
            int hitFaces = 0;
            for (RigPlan.Group g : RigPlan.atoms(mirrored)) {
                RigTransform.Placed placed = RigTransform.placeAtom(g, 1.0);
                Row[] rows = table(placed.leftRotation(), placed.scale(), placed.rightRotation(),
                        STAGE_YAW);
                boolean hit = false;
                for (Row r : rows) {
                    faces++;
                    if (r.neoByte() != r.vanillaByte()) {
                        hitFaces++;
                        hit = true;
                    }
                }
                boxes++;
                if (hit) {
                    hitBoxes++;
                }
            }
            table.append(String.format("  %-28s  %5d  %8s  %6d  %8s%n", name, boxes,
                    hitBoxes + " (" + pct(hitBoxes, boxes) + ")", faces,
                    hitFaces + " (" + pct(hitFaces, faces) + ")"));
            boxesAll += boxes;
            hitBoxesAll += hitBoxes;
        }
        table.append(String.format("  %-28s  %5d  %8s%n", "TOTAL", boxesAll,
                hitBoxesAll + " (" + pct(hitBoxesAll, boxesAll) + ")"));
        System.out.println(table);
        assertTrue(boxesAll == 0 || hitBoxesAll >= 0, "printed, not gated");
    }

    /**
     * <b>Option (c) costed: how many models a shape library would actually need.</b> With
     * {@code |s| = 1} forced (see {@link #onlyAUnitScaleGivesUnitNormals}), the box's size has to
     * live in the MODEL, so the library needs one entry per distinct size. The reserved band
     * ({@code VanillaCarrier.ATOM_MODEL_DATA_FIRST..LAST}) is sixteen numbers.
     *
     * <p>⚠ And the count below is the OPTIMISTIC half, because it counts sizes in sculpt UNITS. The
     * display scale is {@code creatureScale × size / 16}, and the creature's scale is a continuous
     * float derived from the wished height — so a library at unit display scale needs one model per
     * (size × scale) pair, i.e. per CREATURE. Which is a per-creature pack, which is the one thing
     * the atom rig exists to remove.
     */
    @Test
    @DisplayName("option (c) costed: distinct box sizes per creature, against a 16-model band")
    void theShapeLibraryWouldNeedThisManyModels() {
        StringBuilder table = new StringBuilder(
                "\n  fixture                      boxes  exact   q=1   q=2   q=4   q=8\n");
        int[] quanta = {1, 2, 4, 8};
        java.util.Set<String> allExact = new java.util.LinkedHashSet<>();
        java.util.List<java.util.Set<String>> allQ = new java.util.ArrayList<>();
        for (int q : quanta) {
            allQ.add(new java.util.LinkedHashSet<>());
        }
        for (String name : SpikeFixture.CASES) {
            if (!SpikeFixture.available(name)) {
                table.append(String.format("  %-28s  (not available)%n", name));
                continue;
            }
            var mirrored = RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(name));
            java.util.Set<String> exact = new java.util.LinkedHashSet<>();
            java.util.List<java.util.Set<String>> q = new java.util.ArrayList<>();
            for (int ignored : quanta) {
                q.add(new java.util.LinkedHashSet<>());
            }
            int boxes = 0;
            for (RigPlan.Group g : RigPlan.atoms(mirrored)) {
                float[] size = RigPlan.atomSizeUnits(g);
                exact.add(String.format("%.4f/%.4f/%.4f", size[0], size[1], size[2]));
                for (int i = 0; i < quanta.length; i++) {
                    q.get(i).add(bucket(size, quanta[i]));
                }
                boxes++;
            }
            allExact.addAll(exact);
            for (int i = 0; i < quanta.length; i++) {
                allQ.get(i).addAll(q.get(i));
            }
            table.append(String.format("  %-28s  %5d  %5d %5d %5d %5d %5d%n", name, boxes,
                    exact.size(), q.get(0).size(), q.get(1).size(), q.get(2).size(),
                    q.get(3).size()));
        }
        table.append(String.format("  %-28s  %5s  %5d %5d %5d %5d %5d%n", "UNION over the corpus",
                "-", allExact.size(), allQ.get(0).size(), allQ.get(1).size(), allQ.get(2).size(),
                allQ.get(3).size()));
        table.append("  (q = quantisation in sculpt units; worst geometry error is q/2 per axis,\n"
                + "   i.e. q/32 of a block, and the design's own constraint is PIXEL-EXACT)\n");
        System.out.println(table);
        assertTrue(allExact.size() > 16,
                "if this ever drops to 16 the shape library becomes possible and §5.5.3 is wrong");
    }

    private static String bucket(float[] size, int q) {
        return Math.max(1, Math.round(size[0] / q)) + "/" + Math.max(1, Math.round(size[1] / q))
                + "/" + Math.max(1, Math.round(size[2] / q));
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : String.format("%.0f%%", 100.0 * a / b);
    }

    @Test
    @DisplayName("the 2x2: only rotation AND non-uniform scale together is wrong")
    void onlyTheOneCornerIsBroken() {
        record Corner(String name, Quaternionf rot, Vector3f scale, boolean broken) {
        }
        Corner[] corners = {
                new Corner("no rotation, unit scale", new Quaternionf(), UNIT_SCALE, false),
                new Corner("no rotation, non-uniform", new Quaternionf(), ATOM_SCALE, false),
                new Corner("rotation, unit scale", tilt(31, 0, 0), UNIT_SCALE, false),
                new Corner("rotation, non-uniform", tilt(31, 0, 0), ATOM_SCALE, true)};
        for (Corner c : corners) {
            Row[] rows = table(c.rot(), c.scale(), new Quaternionf(), STAGE_YAW);
            boolean differs = false;
            for (Row r : rows) {
                differs |= r.neoByte() != r.vanillaByte();
            }
            assertEquals(c.broken(), differs, c.name());
        }
    }
}
