package com.moddingfromamod.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptPattern;

/**
 * <b>The atom rig's CONSTANT model library — a white unit cube, eight pattern cubes, and the
 * numbers that select them.</b>
 *
 * <p>Pure and Minecraft-free, like {@link RigPlan} and {@link RigTransform}, so the one thing that
 * has to be true of this library — that it does not depend on the creature — is checkable in a unit
 * test rather than in a photograph.
 *
 * <h2>Why a constant library is the whole design</h2>
 *
 * <p>A stock client can only be given a creature's models as a server resource pack, and it applies
 * one by RELOADING: <b>6654 ms</b> of frozen game, ~89% of it fixed cost
 * ({@code docs/PACK_DELIVERY.md} §3). The pack rig (deleted) baked a fresh model per rigid
 * group per creature, so that reload landed on the beat the child is told to look.
 *
 * <p>Everything a sculpt part IS turns out to be expressible as entity data instead:
 *
 * <table border="1">
 *   <caption>a box, with no file in it</caption>
 *   <tr><th>the box's…</th><th>rides on</th><th>because</th></tr>
 *   <tr><td>size</td><td>the display's non-uniform scale</td>
 *       <td>{@code RigTransform.placeAtom} — the cube is one block, so the scale IS the size</td></tr>
 *   <tr><td>tilt</td><td>the display's quaternion</td>
 *       <td>arbitrary, where a vanilla element is ±22.5/±45 on one axis</td></tr>
 *   <tr><td>colour</td><td>{@code minecraft:dyed_color}</td>
 *       <td>{@link RigTint} — verified in Gate C at 8,429 px</td></tr>
 * </table>
 *
 * <p>So nothing per-creature is a file, and the library ships ONCE in the join-time item pack
 * ({@code ItemPackService}) where a reload is expected anyway. {@code docs/ATOM_RIG_PLAN.md} is the
 * plan, its phases and its kill criteria.
 *
 * <h2>The one thing a box IS that a tint cannot carry: its pattern</h2>
 *
 * <p>A part may also name one of eight {@code SculptPattern}s and a second colour, and that is
 * pixels rather than a scalar — so §2 B spends the library's remaining budget on it: eight more
 * cubes, one per pattern, drawn opaque-over-transparent. A patterned box becomes TWO displays,
 * the plain cube tinted {@code colour} and the pattern cube tinted {@code patternColour}, and
 * the library stops there: eight names is the whole vocabulary and it does not grow with the
 * creatures. Spikor's two {@code scales} parts cost two extra entities.
 *
 * <h2>Where the JSON comes from, and why there are two copies</h2>
 *
 * <p>The mod SHIPS these models ({@code tools/gen-atom-library.py}) because a client that HAS the
 * mod is deliberately never pushed the item pack — {@code ItemPackService.offerTo} refuses one, on
 * the grounds that it reads the same overrides out of the jar. {@link #model} produces the byte
 * shape the generated pack needs for a client that has no mod, and {@code AtomLibraryDriftTest}
 * requires the two to agree.
 *
 * <p>That is exactly the shape — a writer that worked and a route that never called it, so the
 * only audience the vanilla-lite path exists for got a leather horse armour — arriving one layer
 * down. Both copies, or the atom arm is correct for whichever client nobody photographed.
 */
public final class AtomLibrary {

    private AtomLibrary() {
    }

    /** {@code -Dmoddingfromamod.rigatomshade=off} picks the unshaded cube. See {@link #shaded()}. */
    public static final String SHADE_PROPERTY = ModdingFromAModMod.MODID + ".rigatomshade";

    /**
     * <b>Is the atom cube drawn with vanilla's per-direction shading?</b> Default {@code on}.
     *
     * <p>This is the atom rig's kill risk expressed as a switch, and it exists because the answer
     * cannot be reasoned to. {@link RigPlan#scaled} exists because a display scale that is not
     * exactly 1 puts vanilla on the inverse-transpose normal path and side faces measured
     * <b>0.800 against 0.737</b> ({@code __legs3}: 0 px at scale 1, 30.8% at 1.2). The atom rig
     * cannot use that fix — there is one shared model, so there is nothing per-creature to fold a
     * scale into — and its display scale is non-uniform for any box that is not a cube.
     *
     * <p>🔴 <b>MEASURED AND REFUTED — Phase 1, {@code docs/ATOM_RIG_PLAN.md} §5.4.2. This flag
     * changes NOTHING, and it cannot.</b> Nine synthetic fixtures were photographed on both
     * settings and the two runs agree <i>to the pixel on every one</i>. The hypothesis was that
     * {@code shade: false} removes the baked block-shade constant; the item path never reads that
     * constant at all. {@code ItemRenderer.renderQuadList} hands each quad to
     * {@code VertexConsumer.putBulkData}, which lights it from
     * {@code pose.transformNormal(quad.getDirection().getNormal())}, and neither of those two
     * vanilla files contains the string {@code shade} anywhere — {@code ModelBlockRenderer}, the
     * <i>block</i> path, is the only caller of {@code BakedQuad.isShade()}. A display renders an
     * ITEM, so the flag was inert from the moment it was written.
     *
     * <p>It is KEPT rather than deleted because the measurement is the point: a reader who reaches
     * for the same idea should meet the A/B seam and §5.4.2 rather than re-derive them. The default
     * stays ON, which is now a statement that the two are indistinguishable rather than a guess.
     *
     * <p>⚠ <b>The risk this flag was aimed at is real and lands elsewhere.</b> {@code __twosize}
     * and {@code __legs3} — the fixtures the kill criterion names, and the ones with genuinely
     * non-uniform box shapes — are <b>0 px against a 0 px floor</b>. What is wrong is a box that is
     * both non-uniformly scaled AND rotated: {@code __tiltx} is <b>2614 px</b>, and the exact pack
     * rig (uniform display scale, same 31° on the same display) is 0 px on it. §5.4.1.
     *
     * <p>🔴 <b>And that one has its mechanism now — §5.5, {@code AtomNormalPathTest}.</b> It is not
     * this cube's doing and no setting of this flag reaches it: NeoForge patches
     * {@code VertexConsumer.putBulkData} with {@code applyBakedNormals}, which replaces vanilla's
     * <i>normalized</i> normal with {@code bakedNormal.mul(pose.normal())} — the raw
     * inverse-transpose, which is {@code 1/|s_i|} long — and
     * {@code BufferBuilder.normalIntValue}'s clamp then turns any normal that is not on a world
     * axis. <b>A genuinely stock client has neither that patch nor the
     * {@code ClientHooks.fillNormal} that feeds it, so it renders a tilted atom correctly</b>;
     * Phase 1's 2614 px is a fact about the NeoForge dev client Gate C runs on. Nothing in
     * {@code (translation, left, scale, right)} can fix it — the normal's length is
     * {@code |S⁻¹·R·n|} and both rotations preserve length, so only {@code |s| = 1} on all three
     * axes is unit, which is the one thing an atom cannot have.
     */
    public static boolean shaded() {
        return !"off".equalsIgnoreCase(System.getProperty(SHADE_PROPERTY, "on"));
    }

    /** Which of the two library numbers a display should wear, right now. */
    public static int modelData() {
        return shaded() ? VanillaCarrier.ATOM_MODEL_DATA : VanillaCarrier.ATOM_MODEL_DATA_FLAT;
    }

    /** The model NAME (under {@code models/item/}) that {@link #modelData()} selects. */
    public static String modelName() {
        return shaded() ? VanillaCarrier.ATOM_MODEL : VanillaCarrier.ATOM_MODEL_FLAT;
    }

    /**
     * <b>The library cube's own sprite — the only cheap way a client can tell the atom cube from
     * some OTHER real model.</b>
     *
     * <p>Both library models are drawn from one texture and it is nothing else's, so a resolved
     * {@code BakedModel} whose particle icon carries this name IS an atom cube and one that does
     * not is not — where "is it the plain carrier, or the missing model" cannot tell the two apart
     * at all, and a quad count cannot either (a one-box creature group is also six quads).
     *
     * <p>Written down here rather than in a test because it is a fact about the shipped model
     * file; {@code AtomLibraryDriftTest} holds the two together.
     */
    public static final String SPRITE = ModdingFromAModMod.MODID + ":item/rig_atom";

    /**
     * {@link #SPRITE} as a {@code Identifier}, for a client-side caller that wants to compare
     * one.
     *
     * <p>⚠ <b>A METHOD rather than a constant, and that is load-bearing.</b> This class is loaded
     * off a Minecraft-free classpath by {@code tools/AtomPatternDump}, which is how
     * {@code gen-atom-library.py} draws the eight pattern sprites with {@link SculptPattern}'s own
     * painter instead of a second copy of the rules (§5.9.1). A {@code Identifier} in a
     * {@code static final} initialiser puts Minecraft in this class's {@code <clinit>} and the
     * generator dies with {@code NoClassDefFoundError} before it draws a pixel — measured, on the
     * merge that first put the two halves in one tree. A parameter or a return type is fine; only
     * the static initialiser is not. {@code AtomLibraryTest.theLibraryLoadsWithoutMinecraft} pins
     * it by reading this source.
     */
    public static net.minecraft.resources.Identifier spriteLocation() {
        return net.minecraft.resources.Identifier.parse(SPRITE);
    }

    /**
     * <b>Is this the sprite of one of the library's OWN models?</b> — the plain cube's
     * {@link #SPRITE} or any of the eight pattern cubes'.
     *
     * <p>Derived from {@link #all()} for the same reason {@link #claims} is: §5.8.3's control asks
     * "is this display wearing an atom cube, or the last creature group of a rig pack that
     * shadowed the carrier's override table", and after Phase 2d a patterned box's SECOND display
     * legitimately wears {@code rig_atom_scales} rather than {@code rig_atom}. A control pinned to
     * the single plain sprite would fail that display — reporting the pack-shadowing failure about
     * a rig that is working, which is worse than the check it replaced. A caller that wants the
     * two counted apart asks {@link #patternSprites} as well.
     */
    public static boolean isLibrarySprite(net.minecraft.resources.Identifier sprite) {
        for (Entry e : all()) {
            if (spriteId(e).equals(sprite.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Just the eight pattern cubes' sprites, so a control can count overlays separately. */
    public static boolean patternSprites(net.minecraft.resources.Identifier sprite) {
        for (Entry e : all()) {
            if (e.patterned() && spriteId(e).equals(sprite.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * An entry's sprite as the fully-qualified id a baked model reports, {@code ns:item/name}.
     *
     * <p>⚠ {@link Entry#spriteName()} is a BARE name ({@code rig_atom}) because that is what the
     * model JSON's {@code textures} block is written from, and a resolved sprite's path is
     * {@code item/rig_atom}. Comparing the two directly answers "not an atom cube" about every atom
     * cube — measured on a real client, which reported all 64 of Spikor's displays as wearing the
     * wrong model and named {@code moddingfromamod:item/rig_atom} as the thing it had rejected. A
     * control that calls the right answer wrong is worse than no control, because the next reader
     * learns to skip the line. {@code AtomLibraryTest.everyShippedModelSSpriteIsAccepted} pins it
     * against the shipped model files, which is the same oracle the constant itself is checked by.
     */
    private static String spriteId(Entry e) {
        return ModdingFromAModMod.MODID + ":item/" + e.spriteName();
    }

    /**
     * <b>Is this {@code custom_model_data} one of the library's OWN numbers?</b>
     *
     * <p>Derived from {@link #all()} rather than from the reserved band
     * ({@code VanillaCarrier.ATOM_MODEL_DATA_FIRST..LAST}), so it answers "is this an atom cube"
     * and not "is this inside sixteen numbers we set aside". The difference matters to
     * {@code com.moddingfromamod.client.rig.AtomNormals}, whose whole promise is that every other
     * item on the carrier — Moddy's watch, every wish icon, every grouped rig group — is left
     * byte-identical: a band test would claim the six reserved numbers nothing draws, and would have
     * claimed Phase 1b's pattern cubes before anybody decided they wanted them. (They are wanted,
     * and they are in {@link #all()}, which is how they came into scope with no edit here — that is
     * the property this method is derived for rather than a coincidence.)
     */
    public static boolean claims(int customModelData) {
        for (Entry e : all()) {
            if (e.customModelData() == customModelData) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>Every library entry, in ascending number order</b> — the two plain cubes and the eight
     * pattern cubes.
     *
     * <p>This list is the library. {@link #claims} is derived from it, {@code WishPackWriter.atoms}
     * emits it, {@code AtomNormals} scopes itself by it, and three separate tests count it — so a
     * pattern cube added here is in the pack, in the normal fix and in the number-space check with
     * no second edit. That is the property {@code claims}'s javadoc is about: the library answers
     * "is this an atom cube", never "is this inside sixteen numbers we set aside".
     */
    public static java.util.List<Entry> all() {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        out.add(new Entry(VanillaCarrier.ATOM_MODEL_DATA, VanillaCarrier.ATOM_MODEL,
                VanillaCarrier.ATOM_SPRITE, true, ""));
        out.add(new Entry(VanillaCarrier.ATOM_MODEL_DATA_FLAT, VanillaCarrier.ATOM_MODEL_FLAT,
                VanillaCarrier.ATOM_SPRITE, false, ""));
        for (String pattern : PATTERNS) {
            out.add(new Entry(patternModelData(pattern), patternModelName(pattern),
                    patternSprite(pattern), true, pattern));
        }
        return java.util.List.copyOf(out);
    }

    /**
     * One library model: the number a display wears, the model's name, the sprite it draws from,
     * its shading, and — for a pattern cube — which {@link SculptPattern} name it paints.
     *
     * @param pattern {@code ""} for the two plain cubes; otherwise a {@code SculptPattern} name
     */
    public record Entry(int customModelData, String modelName, String spriteName, boolean shade,
                        String pattern) {

        /** Is this one of the eight Phase 1b pattern cubes rather than a plain one? */
        public boolean patterned() {
            return !pattern.isEmpty();
        }
    }

    // ── Phase 1b: the pattern cubes (docs/ATOM_RIG_PLAN.md §2 B) ─────────────────────────────

    /**
     * <b>The eight pattern names, in a PINNED order</b> — which is the whole reason this exists
     * rather than iterating {@code SculptPattern.NAMES} at each call site.
     *
     * <p>{@code NAMES} is a {@code Set.of(...)}, whose iteration order is deliberately unspecified
     * and, for the immutable sets, salted per JVM. A number derived from that order would differ
     * between the server that spawns a display and the pack that was written on some other run —
     * i.e. a creature whose scales render as fur, intermittently, with nothing logged. Sorted, so
     * the order is a fact about the vocabulary and not about the run.
     */
    public static final java.util.List<String> PATTERNS =
            SculptPattern.NAMES.stream().sorted().toList();

    /**
     * <b>The number a pattern cube wears</b>, or {@code -1} for a name the vocabulary does not have.
     *
     * <p>Deliberately independent of {@link #shaded()} — see
     * {@code VanillaCarrier.ATOM_PATTERN_MODEL_DATA_FIRST} for why there are no flat twins. The
     * short version is that the reserved band holds sixteen numbers and §5.4.2 measured the shade
     * flag as inert on the item path, so a flat twin would be eight files that are provably
     * byte-different and pixel-identical.
     */
    public static int patternModelData(String pattern) {
        int i = PATTERNS.indexOf(SculptPattern.sanitize(pattern));
        return i < 0 ? -1 : VanillaCarrier.ATOM_PATTERN_MODEL_DATA_FIRST + i;
    }

    /** The pattern cube's model, under {@code models/item/}. */
    public static String patternModelName(String pattern) {
        return VanillaCarrier.ATOM_PATTERN_PREFIX + SculptPattern.sanitize(pattern);
    }

    /** The pattern cube's sprite, under {@code textures/item/}. One per pattern, never shared. */
    public static String patternSprite(String pattern) {
        return patternModelName(pattern);
    }

    /** {@code -Dmoddingfromamod.rigatompatterns=off}. See {@link #patterns()}. */
    public static final String PATTERNS_PROPERTY = ModdingFromAModMod.MODID + ".rigatompatterns";

    /**
     * <b>Does a patterned box get its second, stacked display?</b> Default <b>on</b>.
     *
     * <p>The A/B seam for §2 B's one open risk, which is a thing only an eye can settle: two cubes a
     * hair apart may Z-FIGHT, and a shimmer on a moving creature is not something a pixel count
     * distinguishes from a pattern. With this off a patterned box renders flat — the Phase 1a
     * behaviour — so a Gate C run can shoot the same fixture both ways and attribute a shimmer to
     * the stack rather than to the creature.
     *
     * <p>Default ON for the same reason {@code AtomNormals} is (§5.6.1): a pattern cube only exists
     * on the atom arm, which is itself default-off, so nothing on a shipped path reaches this — and
     * an off-by-default feature is one every future measurement of this arm silently omits until
     * somebody remembers a flag.
     *
     * <p>⚠ <b>The library is emitted into the pack regardless</b>, because the pack is written at
     * server start and a flag flipped afterwards must not leave a display wearing a number the
     * client has no model for. Only the PLANNER reads this.
     *
     * <p>Read per call rather than at class-init, unlike {@code VanillaRigFlags.atoms()}: there is
     * no {@code static final} elsewhere that has to agree with it (the {@code TINTABLE} coupling is
     * the atom arm's, not this one's), and a dynamic read is what lets one unit test plan both arms.
     */
    public static boolean patterns() {
        return !"off".equalsIgnoreCase(System.getProperty(PATTERNS_PROPERTY, "on"));
    }

    /**
     * <b>Which library cube ONE planned group wears</b> — the plain one, or its pattern's.
     *
     * <p>Here rather than at each call site for the reason {@code RigDriver.placed} gives about the
     * scale: a branch repeated is a branch the next writer omits, and omitting THIS one draws a
     * pattern overlay as a second, very slightly larger copy of the box's own flat colour — a box
     * that looks subtly bloated and nothing else. Three callers already: {@code DisplayRig.spawn},
     * {@code DisplayRig.tint}, and Gate C's own atom control, which would otherwise report every
     * patterned box as wearing the wrong number.
     */
    public static int modelDataFor(RigPlan.Group g) {
        return g.patternOverlay()
                ? patternModelData(g.parts().get(0).pattern())
                : modelData();
    }

    /** The model NAME {@link #modelDataFor} selects — what a resolved baked model should be. */
    public static String modelNameFor(RigPlan.Group g) {
        return g.patternOverlay()
                ? patternModelName(g.parts().get(0).pattern())
                : modelName();
    }

    /**
     * <b>What colour ONE planned group is tinted</b> — the box's own, or the pattern's ink.
     *
     * <p>The ink comes from {@link SculptPattern#inkFor} rather than straight off
     * {@code patternColour}, because the painter applies a fallback when the two colours match or
     * the second is absent, and the mod's own skin bakes that fallback into its texture. Reading
     * the raw field here would draw the pattern in the part's own colour — a shape in exactly the
     * wrong place to be visible, on one arm only.
     */
    public static int colourFor(RigPlan.Group g) {
        CreatureSculpt.Part p = g.parts().get(0);
        return g.patternOverlay() ? SculptPattern.inkFor(p) : p.colour();
    }

    /** For a report header, so a run's arm is recorded with its results rather than remembered. */
    public static String describePatterns() {
        return patterns()
                ? "rigatompatterns=ON (a patterned box is two displays — base + pattern cube)"
                : "rigatompatterns=off (a patterned box renders flat, as in Phase 1a)";
    }

    /**
     * <b>How big a pattern sprite is, and the measurement that chose it.</b>
     *
     * <p>The mod's own renderer paints a pattern into the part's packed UV NET at
     * {@code CreatureSculpt.texelsPerUnit()} texels per model unit, so a feature's size on screen
     * depends on how big the box is. An atom's cube is SHARED — that is the whole design — and every
     * face shows the same square sprite stretched over it, so the density has to be chosen once, for
     * the parts patterns actually land on.
     *
     * <p>Measured over the nine-creature spike corpus: the ten patterned parts are the big ones — a
     * shell, a body, a plastron, hair — and the largest face edge of each, in the mod renderer's own
     * texels, runs <b>32 … 104 with a median of 72</b>. 64 is the nearest power of two at or under
     * that, and the honest match: at 64 a {@code stripes} face carries four bands where Spikor's
     * body carries five or six, and a {@code fur} face carries 64 ticks against its 104. Sixteen —
     * the size a per-face reading of a median 4-unit BOX would suggest — is 6.5x too coarse for the
     * parts that are actually patterned, which is the distinction the measurement exists to draw.
     *
     * <p>A power of two, because Minecraft mipmaps the item atlas.
     */
    public static final int PATTERN_SPRITE_PX = 64;

    /**
     * <b>The pattern cube's sprite, painted by {@link SculptPattern}'s OWN painter.</b>
     *
     * <p>ARGB, row-major, {@link #PATTERN_SPRITE_PX} square: white wherever the pattern draws and
     * fully transparent everywhere else, so a display tinted {@code patternColour} and stacked a hair
     * outside the base cube shows the pattern in that colour and the base colour through the gaps.
     *
     * <p><b>There is exactly one copy of the pattern rules and this is not it.</b> The pixels come
     * from {@link SculptPattern#overlay} — the same call {@code CreatureSculpt.bake} makes for the
     * mod's own skin — rasterised by {@link CreatureSculpt#rasterise}, the one pixel loop this repo
     * has. A transcription of "a stripe is {@code 2u} tall every {@code 4u}" into a generator would
     * be a second copy that agrees on the day it is written; instead {@code tools/gen-atom-library.py}
     * runs THIS method and {@code AtomPatternSpriteTest} re-derives the shipped PNG from it.
     *
     * <h2>Why black and white, and how the alpha comes out of a colour painter</h2>
     *
     * <p>The painter emits coloured rectangles, not an alpha mask. Painted with
     * {@code colour = 0x000000} and {@code patternColour = 0xFFFFFF} its output is a GREY LEVEL that
     * is exactly the coverage the pattern wants:
     *
     * <ul>
     *   <li>every pattern but {@code gradient} paints solid {@code ink}, i.e. white → alpha 255;</li>
     *   <li>{@code gradient} paints {@code lerp(base, ink, t)} per row, i.e. grey {@code 255·t} →
     *       alpha {@code 255·t}. Compositing an ink-tinted layer at alpha {@code t} over a
     *       base-tinted one IS {@code lerp(base, ink, t)}, so the two arms draw the same ramp.</li>
     * </ul>
     *
     * <p>So the channel value is the alpha, with no inverse to solve and no per-pattern branch. The
     * choice also dodges {@link SculptPattern#inkFor}'s same-colour fallback, since white is neither
     * black nor zero.
     *
     * <p>Pinned to {@link CreatureSculpt#DEFAULT_TEXELS_PER_UNIT} rather than the live
     * {@code texelsPerUnit()}: the feature sizes are absolute in texels, so a test that moved the
     * density would otherwise change a SHIPPED asset and the drift check would fail somewhere else
     * entirely.
     *
     * @return {@code null} if the name is not one of the eight
     */
    public static int[] patternPixels(String pattern) {
        String name = SculptPattern.sanitize(pattern);
        if (name.isEmpty()) {
            return null;
        }
        int n = PATTERN_SPRITE_PX;
        int before = CreatureSculpt.texelsPerUnit();
        try {
            CreatureSculpt.setTexelsPerUnitForTest(CreatureSculpt.DEFAULT_TEXELS_PER_UNIT);
            CreatureSculpt.Part probe = new CreatureSculpt.Part(
                    "atom", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{1, 1, 1},
                    0x000000, CreatureSculpt.NO_ROTATION, 0f, name, 0xFFFFFF);
            int[] painted = CreatureSculpt.rasterise(n,
                    SculptPattern.overlay(probe, new CreatureSculpt.PaintRect(0, 0, n, n, 0)));
            int[] out = new int[painted.length];
            for (int i = 0; i < painted.length; i++) {
                // The grey level IS the coverage, and an unpainted texel is 0 on both counts.
                out[i] = ((painted[i] & 0xFF) << 24) | 0x00FFFFFF;
            }
            return out;
        } finally {
            CreatureSculpt.setTexelsPerUnitForTest(before);
        }
    }

    /**
     * <b>The unit cube, as JSON.</b>
     *
     * <p>One element filling {@code [0,16]} exactly — not inset. {@link RigTransform}'s whole
     * derivation rests on "model coordinate 8 is the display's own origin and a model unit is 1/16
     * of a block", so {@link RigTransform#placeAtom} sets the display's scale to the box's size in
     * blocks and expects a cube that is exactly one block. An inset cube would draw every box of
     * every creature smaller by one constant factor: uniform, plausible, and invisible to anything
     * that is not a measurement.
     *
     * <p>{@code tintindex: 0} on every face is half of what makes the colour work. Without it
     * vanilla never asks its item-colour handler for a colour and the {@code dyed_color} component
     * is completely inert; the other half is the carrier being a <b>dyeable</b> item, which is
     * {@link RigCarrier#CARRIER}.
     *
     * @param textureId the sprite reference, e.g. {@code "moddingfromamod:item/rig_atom"} for the
     *                  mod's own copy or {@code "wishpack:item/rig_atom"} for the generated pack
     * @param shade     vanilla's per-direction multiplier (up 1.0, down 0.5, N/S 0.8, E/W 0.6)
     */
    public static JsonObject model(String textureId, boolean shade) {
        JsonObject textures = new JsonObject();
        textures.addProperty("0", textureId);
        textures.addProperty("particle", textureId);

        JsonObject faces = new JsonObject();
        for (String name : new String[]{"north", "south", "east", "west", "up", "down"}) {
            JsonObject f = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(0);
            uv.add(0);
            uv.add(16);
            uv.add(16);
            f.add("uv", uv);
            f.addProperty("texture", "#0");
            f.addProperty("tintindex", 0);
            faces.add(name, f);
        }
        JsonObject element = new JsonObject();
        element.add("from", vec(0, 0, 0));
        element.add("to", vec(16, 16, 16));
        element.add("faces", faces);
        if (!shade) {
            element.addProperty("shade", false);
        }
        JsonArray elements = new JsonArray();
        elements.add(element);

        JsonObject root = new JsonObject();
        root.add("textures", textures);
        root.add("elements", elements);
        // A display renders the item in the NONE context, so a "fixed"/"head" display block would
        // be ignored — and the transform arithmetic already assumes the identity. Same as
        // RigModelPack.model.
        root.add("display", new JsonObject());
        return root;
    }

    private static JsonArray vec(float a, float b, float c) {
        JsonArray j = new JsonArray();
        j.add(a);
        j.add(b);
        j.add(c);
        return j;
    }
}
