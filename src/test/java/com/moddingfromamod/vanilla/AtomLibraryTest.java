package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.WishIcons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the atom rig's constant library — the model, the sprite and the number space.</b>
 *
 * <p>Three things here can be wrong in a way nothing else would report, and each is a shape this
 * repo has already paid for once:
 *
 * <ul>
 *   <li><b>The two copies drift.</b> The mod SHIPS these models for a client that has the mod, and
 *       {@link AtomLibrary#model} produces the same shape for the generated pack a stock client
 *       gets. A client is only ever handed one of the two, so a divergence is correct for whichever
 *       audience was photographed — exactly, one layer down.</li>
 *   <li><b>A face loses its {@code tintindex}.</b> Then vanilla never asks for a colour, the
 *       {@code dyed_color} component is inert, and every creature is white. Or worse — see
 *       {@link RigTint#LEATHER_DEFAULT}.</li>
 *   <li><b>The number space collides.</b> {@code custom_model_data} on the carrier is ONE space
 *       shared by the wish icons, Moddy's watch and now this, and a collision does not error: it
 *       renders a creature's box as somebody's wish icon.</li>
 * </ul>
 */
class AtomLibraryTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/moddingfromamod");

    private static JsonObject shipped(String model) throws Exception {
        Path p = ASSETS.resolve("models/item").resolve(model + ".json");
        assertTrue(Files.isRegularFile(p),
                "the mod ships no " + model + " model, so a client WITH the mod draws every box of "
                        + "every atom-rig creature as the magenta missing cube — and it is never "
                        + "pushed the pack that would have supplied one. "
                        + "Run python3 tools/gen-atom-library.py");
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * The drift check, both ways round: whatever the pack writer would emit for the mod's own
     * texture id must be byte-for-byte the file the mod ships. Compared as parsed JSON rather than
     * as text, because the shipped file carries a {@code __comment} the generated one does not and
     * a whitespace diff is not a defect.
     */
    @Test
    @DisplayName("the shipped cube and the generated one are the same model")
    void theTwoCopiesHaveNotDrifted() throws Exception {
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            JsonObject ship = shipped(e.modelName());
            JsonObject gen = AtomLibrary.model("moddingfromamod:item/" + e.spriteName(),
                    e.shade());
            for (String key : new String[]{"textures", "elements", "display"}) {
                assertEquals(gen.get(key), ship.get(key),
                        "'" + key + "' differs between the shipped " + e.modelName()
                                + " and what the pack writer emits — a modded client and a stock "
                                + "client would draw different creatures. "
                                + "Run python3 tools/gen-atom-library.py");
            }
        }
    }

    @Test
    @DisplayName("every face of the cube carries tintindex 0, or dyed_color does nothing at all")
    void everyFaceIsTintable() {
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            var faces = AtomLibrary.model("x:y", e.shade()).getAsJsonArray("elements")
                    .get(0).getAsJsonObject().getAsJsonObject("faces");
            assertEquals(6, faces.size(), "a cube has six faces");
            for (String name : faces.keySet()) {
                assertEquals(0, faces.get(name).getAsJsonObject().get("tintindex").getAsInt(),
                        name + " has no tintindex, so vanilla never asks its colour handler for a "
                                + "colour and every box on this arm renders white");
            }
        }
    }

    /**
     * {@link RigTransform#placeAtom} sets the display's scale to the box's size in blocks and
     * expects a model that is exactly one block. An inset cube would draw every box of every
     * creature smaller by a constant factor — uniform, plausible, and invisible to anything but a
     * measurement, which is why there is one.
     */
    @Test
    @DisplayName("the cube fills the model box exactly — the display's scale is calibrated on it")
    void theCubeIsExactlyOneBlock() {
        var el = AtomLibrary.model("x:y", true).getAsJsonArray("elements").get(0).getAsJsonObject();
        var from = el.getAsJsonArray("from");
        var to = el.getAsJsonArray("to");
        for (int i = 0; i < 3; i++) {
            assertEquals(0f, from.get(i).getAsFloat(), 1e-6f);
            assertEquals(2 * RigPlan.MODEL_CENTRE, to.get(i).getAsFloat(), 1e-6f);
        }
    }

    @Test
    @DisplayName("the shaded and unshaded cubes differ in exactly one field")
    void theTwoArmsDifferOnlyInShade() {
        JsonObject on = AtomLibrary.model("x:y", true);
        JsonObject off = AtomLibrary.model("x:y", false);
        var elOn = on.getAsJsonArray("elements").get(0).getAsJsonObject();
        var elOff = off.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertTrue(!elOn.has("shade"), "shading ON is vanilla's default, so the field is absent");
        assertTrue(elOff.has("shade") && !elOff.get("shade").getAsBoolean());
        assertEquals(on.get("textures"), off.get("textures"));
        assertEquals(elOn.get("from"), elOff.get("from"));
        assertEquals(elOn.get("to"), elOff.get("to"));
        assertEquals(elOn.get("faces"), elOff.get("faces"),
                "if the arms differ in anything but shading, Phase 1's A/B measures two changes");
    }

    /**
     * <b>Pure white, every pixel.</b> The sprite is multiplied by {@code dyed_color} and nothing
     * else is ever drawn from it, so any pixel that is not {@code 0xFFFFFFFF} is a colour nobody
     * asked for, applied to every creature on the arm.
     */
    @Test
    @DisplayName("the PLAIN atom sprite is a real PNG and is pure opaque white")
    void theSpriteIsWhite() throws Exception {
        Path png = ASSETS.resolve("textures/item").resolve(VanillaCarrier.ATOM_SPRITE + ".png");
        assertTrue(Files.isRegularFile(png),
                "no atom sprite — run python3 tools/gen-atom-library.py");
        BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
        assertNotNull(img, "the atom sprite is not a decodable PNG");
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(0xFFFFFFFF, img.getRGB(x, y),
                        "pixel " + x + "," + y + " is not opaque white, so every creature on the "
                                + "atom arm is multiplied by it");
            }
        }
    }

    /**
     * <b>What ONE planned group wears and is tinted</b> — the decision {@code DisplayRig.spawn} and
     * {@code DisplayRig.tint} both make, tested where it lives rather than where it is used.
     *
     * <p>It lives here because {@code DisplayRig} is Minecraft-facing and this is not, which is the
     * repo's own rule; and it is ONE method rather than a branch at each of the three call sites
     * (spawn, the hurt-flash tint, and Gate C's control), because a branch repeated is a branch the
     * next writer omits — and omitting it draws a pattern overlay as a second, very slightly larger
     * copy of the box's own flat colour. A box that looks subtly bloated and nothing else.
     */
    @Test
    @DisplayName("a pattern overlay wears its pattern's cube and its pattern's ink")
    void aPatternOverlayWearsItsOwnCubeAndInk() {
        var patterned = new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "shell", null, new float[]{0, 4, 0}, new float[]{-6, 0, -2}, new float[]{6, 10, 2},
                0x00AA00, com.moddingfromamod.runtime.CreatureSculpt.NO_ROTATION, 0f,
                "scales", 0xFFCC00);
        var plan = RigPlan.atoms(java.util.List.of(patterned), true);
        assertEquals(2, plan.size());
        RigPlan.Group base = plan.get(0);
        RigPlan.Group over = plan.get(1);

        assertEquals(AtomLibrary.modelData(), AtomLibrary.modelDataFor(base),
                "the box itself is the plain cube, exactly as it was before §2 B");
        assertEquals(AtomLibrary.modelName(), AtomLibrary.modelNameFor(base));
        assertEquals(0x00AA00, AtomLibrary.colourFor(base), "…tinted its own colour");

        assertEquals(AtomLibrary.patternModelData("scales"), AtomLibrary.modelDataFor(over));
        assertEquals("rig_atom_scales", AtomLibrary.modelNameFor(over));
        assertEquals(0xFFCC00, AtomLibrary.colourFor(over), "…and the overlay its pattern colour");
    }

    /**
     * <b>The ink is the painter's, fallback and all.</b>
     *
     * <p>{@code SculptPattern.overlay} substitutes a readable contrast when the pattern colour
     * matches the part's or is absent, and the mod's own skin bakes that substitution into its
     * texture. Reading {@code patternColour} raw here would tint the overlay the part's own colour
     * — a correct pattern, in the right place, invisible — on the atom arm only, so the A/B would
     * report a missing feature that the mod renderer has.
     */
    @Test
    @DisplayName("an overlay whose pattern colour is absent gets the painter's own fallback")
    void theInkGoesThroughThePaintersFallback() {
        for (int given : new int[]{0, 0x00AA00}) {
            var p = new com.moddingfromamod.runtime.CreatureSculpt.Part(
                    "shell", null, new float[]{0, 4, 0}, new float[]{-6, 0, -2},
                    new float[]{6, 10, 2}, 0x00AA00,
                    com.moddingfromamod.runtime.CreatureSculpt.NO_ROTATION, 0f, "scales", given);
            RigPlan.Group over = RigPlan.atoms(java.util.List.of(p), true).get(1);
            int ink = AtomLibrary.colourFor(over);
            assertEquals(com.moddingfromamod.runtime.SculptPattern.inkFor(p), ink,
                    "the atom arm and the skin painter must pick the same ink");
            assertTrue(ink != 0x00AA00,
                    "patternColour " + Integer.toHexString(given) + " left the overlay the part's "
                            + "own colour — the pattern would be drawn and invisible");
        }
    }

    /**
     * <b>The §2 B seam, and the default it has to keep.</b>
     *
     * <p>Default ON for {@code AtomNormals}' reason (§5.6.1): a pattern cube only exists on the atom
     * arm, which is itself default-off, so nothing on a shipped path reaches this — and an
     * off-by-default feature is one every future Gate C measurement of the arm silently omits until
     * somebody remembers a flag.
     *
     * <p>With it off the plan is EXACTLY Phase 1a's, which is what makes it an A/B seam rather than
     * a second planner: one display per box, no overlay, and — crucially — the library still ships
     * every cube, because the pack is written at server start and a flag flipped afterwards must
     * not leave a display wearing a number the client has no model for.
     */
    @Test
    @DisplayName("the pattern seam is ON by default and turns the overlays off, not the pack")
    void thePatternSeamIsAnArmRatherThanAFeatureFlag() {
        var patterned = new com.moddingfromamod.runtime.CreatureSculpt.Part(
                "shell", null, new float[]{0, 4, 0}, new float[]{-6, 0, -2}, new float[]{6, 10, 2},
                0x00AA00, com.moddingfromamod.runtime.CreatureSculpt.NO_ROTATION, 0f,
                "scales", 0xFFCC00);
        var parts = java.util.List.of(patterned);
        int libraryBefore = AtomLibrary.all().size();

        String before = System.getProperty(AtomLibrary.PATTERNS_PROPERTY);
        try {
            System.clearProperty(AtomLibrary.PATTERNS_PROPERTY);
            assertTrue(AtomLibrary.patterns(), "§2 B ships ON — see the javadoc for why");
            assertEquals(2, RigPlan.atoms(parts).size(), "the one-arg form reads the seam");

            System.setProperty(AtomLibrary.PATTERNS_PROPERTY, "off");
            assertTrue(!AtomLibrary.patterns());
            assertEquals(1, RigPlan.atoms(parts).size(),
                    "with the seam off a patterned box is ONE display — exactly Phase 1a, which is "
                            + "what makes this an A/B rather than a second planner");
            assertEquals(libraryBefore, AtomLibrary.all().size(),
                    "the seam must not shrink the LIBRARY — the pack is written at server start, "
                            + "and a display wearing a number the client has no model for is a "
                            + "magenta cube rather than a flat box");

            // An unrecognised value must not silently switch the arm: only the literal "off"
            // leaves the shipped default.
            System.setProperty(AtomLibrary.PATTERNS_PROPERTY, "no thanks");
            assertTrue(AtomLibrary.patterns());
        } finally {
            if (before == null) {
                System.clearProperty(AtomLibrary.PATTERNS_PROPERTY);
            } else {
                System.setProperty(AtomLibrary.PATTERNS_PROPERTY, before);
            }
        }
    }

    // ── the number space ─────────────────────────────────────────────────────────────────────

    /**
     * The same rule {@code CarrierModelDriftTest} applies to Moddy, extended to the third tenant.
     * A collision here does not error — it draws a creature's shoulder as a wish icon.
     */
    @Test
    @DisplayName("the icon library, Moddy and the atom library never meet")
    void theThreeNumberSpacesAreDisjoint() {
        for (WishIcons.Icon icon : WishIcons.all()) {
            assertTrue(icon.id() < VanillaCarrier.WATCH_MODEL_DATA,
                    "wish icon " + icon.name() + " is " + icon.id() + ", at or past Moddy's "
                            + VanillaCarrier.WATCH_MODEL_DATA);
            assertTrue(icon.id() < VanillaCarrier.ATOM_MODEL_DATA_FIRST,
                    "wish icon " + icon.name() + " is " + icon.id() + ", inside the atom band — a "
                            + "creature's box would render as a wished thing. Move the band, never "
                            + "the library.");
        }
        assertTrue(VanillaCarrier.WATCH_MODEL_DATA < VanillaCarrier.ATOM_MODEL_DATA_FIRST,
                "Moddy is inside the atom band, so the watch would render as a white cube");
        assertTrue(VanillaCarrier.ATOM_MODEL_DATA_FIRST < VanillaCarrier.ATOM_MODEL_DATA_LAST,
                "an empty band cannot hold Phase 1b's eight pattern cubes");
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            assertTrue(e.customModelData() >= VanillaCarrier.ATOM_MODEL_DATA_FIRST
                            && e.customModelData() <= VanillaCarrier.ATOM_MODEL_DATA_LAST,
                    e.modelName() + " is outside its own reserved band");
        }
        assertEquals(AtomLibrary.all().size(), AtomLibrary.all().stream()
                        .map(AtomLibrary.Entry::customModelData).distinct().count(),
                "two library entries sharing one number means one can never be reached");
        assertEquals(AtomLibrary.all().size(), AtomLibrary.all().stream()
                        .map(AtomLibrary.Entry::modelName).distinct().count(),
                "two entries sharing one model name means one of them is not in the pack at all");
    }

    /**
     * <b>Phase 1b: the eight pattern cubes and their numbers.</b>
     *
     * <p>The order matters and is the reason {@link AtomLibrary#PATTERNS} exists.
     * {@code SculptPattern.NAMES} is a {@code Set.of(...)}, whose iteration order is unspecified and
     * salted per JVM — so a number taken from it would differ between the run that WROTE the pack
     * and the run that spawns a display, and a creature's scales would render as fur on some boots
     * of the game and not others. Nothing would log it.
     */
    @Test
    @DisplayName("every pattern has a cube, at a stable number, inside the band")
    void everyPatternHasItsOwnCube() {
        assertEquals(com.moddingfromamod.runtime.SculptPattern.NAMES.size(),
                AtomLibrary.PATTERNS.size(), "one cube per pattern, no more and no fewer");
        assertEquals(AtomLibrary.PATTERNS.stream().sorted().toList(), AtomLibrary.PATTERNS,
                "PATTERNS must be SORTED — a Set.of iteration order is salted per JVM, so the "
                        + "number a pattern gets would change between two boots of the game");

        for (int i = 0; i < AtomLibrary.PATTERNS.size(); i++) {
            String name = AtomLibrary.PATTERNS.get(i);
            int cmd = AtomLibrary.patternModelData(name);
            assertEquals(VanillaCarrier.ATOM_PATTERN_MODEL_DATA_FIRST + i, cmd, name);
            assertTrue(cmd > VanillaCarrier.ATOM_MODEL_DATA_FLAT
                            && cmd <= VanillaCarrier.ATOM_MODEL_DATA_LAST,
                    name + " is at " + cmd + ", outside the reserved band or on top of a plain cube");
            assertEquals("rig_atom_" + name, AtomLibrary.patternModelName(name));
            assertEquals(AtomLibrary.patternModelName(name), AtomLibrary.patternSprite(name),
                    "the model and its sprite share a name, so a missing one is one grep");
        }
        // The whole library still fits what VanillaCarrier reserved, which is the check that would
        // have fired had 1b turned out to want sixteen cubes rather than eight.
        assertTrue(AtomLibrary.all().size()
                        <= VanillaCarrier.ATOM_MODEL_DATA_LAST
                        - VanillaCarrier.ATOM_MODEL_DATA_FIRST + 1,
                "the library has outgrown its reserved band — widen the band deliberately, do not "
                        + "let it spill into whatever is above it");

        // Sanitised, so the near-misses an agent types reach the same cube the skin painter does.
        assertEquals(AtomLibrary.patternModelData("scales"), AtomLibrary.patternModelData("scaly"));
        assertEquals(-1, AtomLibrary.patternModelData(""), "a flat part has no pattern cube");
        assertEquals(-1, AtomLibrary.patternModelData("houndstooth"),
                "an unknown name must not resolve to a number, or a flat part wears somebody "
                        + "else's pattern");
    }

    @Test
    @DisplayName("shading is on by default, and the property picks the number rather than the file")
    void theShadePropertySelectsAModelRatherThanEditingOne() {
        String before = System.getProperty(AtomLibrary.SHADE_PROPERTY);
        try {
            System.clearProperty(AtomLibrary.SHADE_PROPERTY);
            assertTrue(AtomLibrary.shaded(), "the default is on — the deleted grouped rig (#582) "
                    + "measured shading that way, which is the nearest evidence there is");
            assertEquals(VanillaCarrier.ATOM_MODEL_DATA, AtomLibrary.modelData());
            assertEquals(VanillaCarrier.ATOM_MODEL, AtomLibrary.modelName());

            System.setProperty(AtomLibrary.SHADE_PROPERTY, "off");
            assertTrue(!AtomLibrary.shaded());
            assertEquals(VanillaCarrier.ATOM_MODEL_DATA_FLAT, AtomLibrary.modelData());
            assertEquals(VanillaCarrier.ATOM_MODEL_FLAT, AtomLibrary.modelName());

            // An unrecognised value must not silently pick the experimental arm: only the literal
            // "off" leaves the shipped default.
            System.setProperty(AtomLibrary.SHADE_PROPERTY, "yes please");
            assertTrue(AtomLibrary.shaded());
        } finally {
            if (before == null) {
                System.clearProperty(AtomLibrary.SHADE_PROPERTY);
            } else {
                System.setProperty(AtomLibrary.SHADE_PROPERTY, before);
            }
        }
    }

    /**
     * <b>{@link AtomLibrary#SPRITE} is the sprite the shipped models actually name.</b>
     *
     * <p>It is the discriminator a client uses to tell an atom cube from some OTHER real model —
     * see {@code ClientHarness.excludePack} for the failure that needed one — so if the model
     * files ever stop naming it, every such control silently starts reporting the wrong answer
     * with no error anywhere. Both library models are checked, because a control that is right for
     * one arm and wrong for the other is worse than none.
     */
    @Test
    @DisplayName("both library cubes name the sprite the client-side control keys on")
    void theSpriteConstantIsTheOneTheModelsName() throws Exception {
        int plain = 0;
        int patterned = 0;
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            // ⚠ Phase 2d: the library is ten models, not two, and a pattern cube names its OWN
            // sprite -- that is the whole design (one texture per pattern, never shared). So the
            // constant is checked against the two PLAIN cubes and each pattern cube against its
            // own `patternSprite`, and both counts are asserted: widening this to "any library
            // sprite" would let a pattern cube that had quietly reverted to the plain white
            // texture through, which is exactly a pattern that renders as a solid second box.
            String want = e.patterned()
                    ? com.moddingfromamod.ModdingFromAModMod.MODID + ":item/" + AtomLibrary.patternSprite(e.pattern())
                    : AtomLibrary.SPRITE;
            var tex = shipped(e.modelName()).getAsJsonObject("textures");
            for (String key : new String[]{"0", "particle"}) {
                assertEquals(want, tex.get(key).getAsString(),
                        "the shipped " + e.modelName() + "'s '" + key + "' texture");
            }
            if (e.patterned()) {
                patterned++;
            } else {
                plain++;
            }
        }
        assertEquals(2, plain, "the two plain cubes");
        assertEquals(AtomLibrary.PATTERNS.size(), patterned, "one cube per pattern name");
    }

    /**
     * <b>Every sprite a shipped library model NAMES is one {@code isLibrarySprite} accepts.</b>
     *
     * <p>The client-side control asks "is this display wearing an atom cube, or the last creature
     * group of a rig pack that shadowed the carrier's override table" (§5.8.3), and it asks it of
     * the sprite the resolved model reports. So the two have to agree about SPELLING, and they did
     * not: {@code Entry.spriteName()} is the bare {@code rig_atom} the model JSON is written from
     * and a resolved sprite's path is {@code item/rig_atom}, so the first cut rejected every atom
     * cube in the library and a real Gate C run reported all 64 of Spikor's displays as wearing
     * the wrong model — while naming {@code moddingfromamod:item/rig_atom} as what it had rejected.
     *
     * <p>Driven off the shipped model FILES rather than off the constants, so it is the same oracle
     * that checks the constants themselves: if a model's texture and the library's idea of it ever
     * part company, one of these two tests fails whichever side moved.
     */
    @Test
    @DisplayName("isLibrarySprite accepts the sprite every shipped library model actually names")
    void everyShippedModelSSpriteIsAccepted() throws Exception {
        int patterned = 0;
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            String named = shipped(e.modelName()).getAsJsonObject("textures").get("0").getAsString();
            var rl = net.minecraft.resources.Identifier.parse(named);
            org.junit.jupiter.api.Assertions.assertTrue(AtomLibrary.isLibrarySprite(rl),
                    e.modelName() + " names the sprite " + named + " and isLibrarySprite rejects "
                            + "it, so the client-side control will report every one of these "
                            + "displays as wearing the wrong model");
            org.junit.jupiter.api.Assertions.assertEquals(e.patterned(),
                    AtomLibrary.patternSprites(rl),
                    e.modelName() + ": patternSprites must be true for a pattern cube and false "
                            + "for a plain one, or the overlay COUNT the control reports is wrong "
                            + "in a way that still reads as a pass");
            if (e.patterned()) {
                patterned++;
            }
        }
        assertEquals(AtomLibrary.PATTERNS.size(), patterned, "one pattern cube per name");
        // ...and it must still REFUSE a creature group's page, which is the whole point of it.
        org.junit.jupiter.api.Assertions.assertFalse(AtomLibrary.isLibrarySprite(
                        net.minecraft.resources.Identifier.parse(
                                "moddingfromamod:item/vwalk_spikor-armb")),
                "a creature group's atlas page must not be accepted, or the control cannot see "
                        + "the rig-pack shadowing it exists for");
    }

    /**
     * <b>{@link AtomLibrary}'s class initialiser names no Minecraft type.</b>
     *
     * <p>{@code tools/AtomPatternDump} loads this class off a Minecraft-free classpath so
     * {@code gen-atom-library.py} can draw the eight pattern sprites with {@link SculptPattern}'s
     * OWN painter (§5.9.1) — one source of truth for the rules instead of two. A
     * {@code static final} field of a Minecraft type puts Minecraft in {@code <clinit>} and the
     * generator dies with {@code NoClassDefFoundError} before drawing a pixel, which is not a
     * compile error and which no other gate can see: Gate A has Minecraft on the classpath, so the
     * library loads here perfectly whether or not the generator can load it.
     *
     * <p>Measured on the merge that first put Phase 2 and Phase 2d in one tree — a
     * {@code Identifier SPRITE} constant, correct on its own and fatal to the generator.
     * Read out of the SOURCE, because that is the only place the distinction is visible.
     */
    @Test
    @DisplayName("the library's class initialiser names no Minecraft type, so the sprite "
            + "generator can load it")
    void theLibraryLoadsWithoutMinecraft() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/vanilla/AtomLibrary.java"));
        var bad = new java.util.ArrayList<String>();
        boolean inBlockComment = false;
        for (String line : src.split("\n")) {
            String t = line.strip();
            if (inBlockComment) {
                if (t.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (t.startsWith("/*")) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.startsWith("*") || t.startsWith("//")) {
                continue;
            }
            // A field, not a method: `static final <type> NAME =` with no parameter list before
            // the `=`. A method's `net.minecraft` parameter or return type is fine -- it resolves
            // lazily -- and excluding it is what keeps `spriteLocation()` legal.
            if (t.startsWith("public static final ") || t.startsWith("private static final ")
                    || t.startsWith("static final ")) {
                if (t.contains("net.minecraft")) {
                    bad.add(t);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(bad.isEmpty(),
                "AtomLibrary has a static final field of a Minecraft type, so its <clinit> now "
                        + "needs Minecraft and tools/AtomPatternDump cannot load it: " + bad);
    }
}
