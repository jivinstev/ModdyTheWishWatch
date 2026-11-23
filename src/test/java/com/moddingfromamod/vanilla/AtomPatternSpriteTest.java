package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for Phase 1b's eight pattern sprites</b> — that they exist, that a client can find
 * them, and that they are the pattern the mod's own renderer draws rather than a second one that
 * looks like it.
 *
 * <p>{@code docs/ATOM_RIG_PLAN.md} §2 B. A patterned box on the atom arm is TWO displays: the plain
 * cube tinted the part's colour, and one of these tinted its {@code patternColour}. Three things can
 * be wrong here and every one of them is silent:
 *
 * <ul>
 *   <li><b>The sprite is not on the CLASSPATH.</b> A file in {@code src/main/resources} that
 *       {@code processResources} never copied is a magenta missing cube in the game and a green
 *       {@code Files.isRegularFile} in a test. So the resolution asked for here is the one the
 *       client makes.</li>
 *   <li><b>The shipped PNG has drifted from the painter.</b> {@code tools/gen-atom-library.py} runs
 *       {@link AtomLibrary#patternPixels} through {@code tools/AtomPatternDump.java} and writes the
 *       bytes; if somebody hand-edits a sprite, or retunes a density and forgets to regenerate, the
 *       atom arm draws a DIFFERENT pattern from the mod renderer it is being A/B'd against — which
 *       reads as a fidelity finding rather than as a stale asset.</li>
 *   <li><b>The painter is not the painter.</b> The whole point of routing through
 *       {@code SculptPattern.overlay} is that there is one copy of the rules. This test re-derives
 *       the sprite from that method and compares it to the file, so a Python transcription
 *       reappearing in the generator would go red here rather than in a photograph.</li>
 * </ul>
 */
class AtomPatternSpriteTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/moddingfromamod");

    /**
     * The sprite as the CLIENT will get it — off the classpath, not off the source tree.
     *
     * <p>{@code processResources} is what puts an asset in the jar, and a file it did not copy is
     * present on disk and absent in the game. Same distinction the {@code catalogue names template
     * 'on_death.cbs' but it is not on the classpath} incident turned on.
     */
    private static BufferedImage fromClasspath(String sprite) throws Exception {
        String res = "/assets/moddingfromamod/textures/item/" + sprite + ".png";
        try (InputStream in = AtomPatternSpriteTest.class.getResourceAsStream(res)) {
            assertNotNull(in, res + " is not on the classpath — a patterned box would render as the "
                    + "magenta missing cube. Run python3 tools/gen-atom-library.py, then rebuild.");
            BufferedImage img = javax.imageio.ImageIO.read(in);
            assertNotNull(img, res + " is not a decodable PNG");
            return img;
        }
    }

    @Test
    @DisplayName("every pattern cube's model and sprite resolve on the classpath")
    void everyPatternCubeIsReachable() throws Exception {
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            if (!e.patterned()) {
                continue;
            }
            String model = "/assets/moddingfromamod/models/item/" + e.modelName() + ".json";
            try (InputStream in = AtomPatternSpriteTest.class.getResourceAsStream(model)) {
                assertNotNull(in, model + " is not on the classpath — the carrier's override at "
                        + e.customModelData() + " points at nothing");
            }
            BufferedImage img = fromClasspath(e.spriteName());
            assertEquals(AtomLibrary.PATTERN_SPRITE_PX, img.getWidth(), e.spriteName());
            assertEquals(AtomLibrary.PATTERN_SPRITE_PX, img.getHeight(), e.spriteName());
        }
        assertEquals(SculptPattern.NAMES.size(),
                AtomLibrary.all().stream().filter(AtomLibrary.Entry::patterned).count(),
                "one cube per pattern name — a pattern with no cube renders flat, silently");
    }

    /**
     * <b>The shipped bytes ARE the painter's output.</b> Re-derived here rather than trusted, and
     * compared pixel by pixel rather than by size, because a stale sprite is a correct-looking
     * picture of last week's pattern.
     */
    @Test
    @DisplayName("every shipped sprite is exactly what SculptPattern's own painter draws")
    void theShippedSpritesAreThePaintersOutput() throws Exception {
        int n = AtomLibrary.PATTERN_SPRITE_PX;
        for (String pattern : AtomLibrary.PATTERNS) {
            int[] want = AtomLibrary.patternPixels(pattern);
            assertNotNull(want, pattern);
            BufferedImage img = fromClasspath(AtomLibrary.patternSprite(pattern));
            int[] got = new int[n * n];
            img.getRGB(0, 0, n, n, got, 0, n);
            assertArrayEquals(want, got,
                    "the shipped " + AtomLibrary.patternSprite(pattern) + ".png is not what "
                            + "SculptPattern.overlay draws — the atom arm and the mod renderer "
                            + "would paint DIFFERENT patterns, which reads as a fidelity finding "
                            + "rather than as a stale asset. "
                            + "Run python3 tools/gen-atom-library.py");
        }
    }

    /**
     * <b>Opaque where the pattern is, transparent where it is not</b> — and both, on a real pattern.
     *
     * <p>A sprite that came out all-opaque would hide the base cube completely and repaint every
     * patterned box one flat {@code patternColour}; one that came out all-transparent would draw
     * nothing at all and cost an entity per patterned box for no picture. Both are plausible bugs of
     * the alpha derivation and neither throws.
     */
    @Test
    @DisplayName("the scales sprite is really a pattern — some opaque, some transparent")
    void theScalesSpriteIsNonTrivial() throws Exception {
        int n = AtomLibrary.PATTERN_SPRITE_PX;
        BufferedImage img = fromClasspath(AtomLibrary.patternSprite("scales"));
        int opaque = 0;
        int clear = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a == 0xFF) {
                    opaque++;
                } else if (a == 0) {
                    clear++;
                }
                // WHITE under the transparent texels too. A transparent BLACK texel reads the same
                // to the eye and differently to a mipmap, which averages RGB and alpha
                // independently — so a black-backed sprite grows a dark fringe as it recedes, on an
                // arm whose only colour channel is the tint.
                assertEquals(0xFFFFFF, img.getRGB(x, y) & 0xFFFFFF,
                        "scales texel " + x + "," + y + " is not white under its alpha");
            }
        }
        assertTrue(opaque > 0, "the scales sprite draws nothing — the overlay display is invisible "
                + "and every patterned box costs an entity for no picture");
        assertTrue(clear > 0, "the scales sprite is fully opaque — it would hide the base cube and "
                + "repaint the whole box one flat patternColour");
        assertEquals(n * n, opaque + clear, "scales is a hard-edged pattern; only `gradient` ramps");
    }

    /**
     * Deterministic, which is what {@code SculptPattern}'s own javadoc promises and what lets a
     * shipped PNG be an assertion at all. {@code speckle} and {@code fur} place their marks by a
     * hash of the coordinate; a {@code Random} there would make every regeneration a different file
     * and this whole test a coin toss.
     */
    @Test
    @DisplayName("the painter gives the same bytes twice, for every pattern")
    void thePainterIsDeterministic() {
        for (String pattern : AtomLibrary.PATTERNS) {
            assertArrayEquals(AtomLibrary.patternPixels(pattern),
                    AtomLibrary.patternPixels(pattern), pattern + " is not deterministic");
        }
    }

    /**
     * <b>The density is pinned to the SHIPPED one.</b> A pattern's features are measured in texels,
     * so {@code CreatureSculpt.setTexelsPerUnitForTest} would change the sprite — and the sprite is
     * a committed asset. A test that moved the density and left it moved would make the drift check
     * fail somewhere else entirely, which is the worst place for it to fail.
     */
    @Test
    @DisplayName("the sprite does not move when a test moves the texel density")
    void theSpriteIsPinnedToTheShippedDensity() {
        int before = CreatureSculpt.texelsPerUnit();
        try {
            int[] atDefault = AtomLibrary.patternPixels("stripes");
            CreatureSculpt.setTexelsPerUnitForTest(1);
            assertArrayEquals(atDefault, AtomLibrary.patternPixels("stripes"),
                    "the sprite followed the live texel density — a test that lowers it would "
                            + "silently redraw a committed asset");
            assertEquals(1, CreatureSculpt.texelsPerUnit(),
                    "patternPixels must restore whatever density it found");
        } finally {
            CreatureSculpt.setTexelsPerUnitForTest(before);
        }
    }

    /**
     * <b>The gradient is the one pattern with partial alpha, and that is the design.</b>
     *
     * <p>Every other pattern paints solid ink. {@code gradient} paints {@code lerp(base, ink, t)}
     * per row, and compositing an ink-tinted layer at alpha {@code t} over a base-tinted one is that
     * same lerp — so the ramp is carried in the alpha channel and the two arms draw the same fade.
     * If this ever came back hard-edged, the derivation in {@link AtomLibrary#patternPixels} has
     * lost the grey level and the fade would step.
     */
    @Test
    @DisplayName("the gradient sprite is a real ramp, top to bottom")
    void theGradientRamps() throws Exception {
        int n = AtomLibrary.PATTERN_SPRITE_PX;
        BufferedImage img = fromClasspath(AtomLibrary.patternSprite("gradient"));
        int top = (img.getRGB(n / 2, 0) >>> 24) & 0xFF;
        int mid = (img.getRGB(n / 2, n / 2) >>> 24) & 0xFF;
        int bottom = (img.getRGB(n / 2, n - 1) >>> 24) & 0xFF;
        assertEquals(0, top, "the gradient starts at the part's own colour, i.e. transparent");
        assertEquals(0xFF, bottom, "…and ends fully at the pattern colour");
        assertTrue(mid > top + 40 && mid < bottom - 40,
                "the middle is " + mid + " — a two-step gradient is a hard edge with extra steps");
    }

    /** A pattern the vocabulary does not have has no sprite, and must not silently borrow one. */
    @Test
    @DisplayName("an unknown pattern paints nothing")
    void anUnknownPatternHasNoSprite() {
        assertNotNull(AtomLibrary.patternPixels("scaly"), "a near-miss sanitises to a real pattern");
        org.junit.jupiter.api.Assertions.assertNull(AtomLibrary.patternPixels("houndstooth"));
        org.junit.jupiter.api.Assertions.assertNull(AtomLibrary.patternPixels(""));
        org.junit.jupiter.api.Assertions.assertNull(AtomLibrary.patternPixels(null));
    }

    /**
     * The source tree and the classpath agree. Cheap, and it is the check that says which of the two
     * is stale when {@link #theShippedSpritesAreThePaintersOutput} goes red: a source file that is
     * right and a build output that is not means the answer is "rebuild", not "regenerate".
     */
    @Test
    @DisplayName("the committed sprite and the one on the classpath are the same file")
    void theCommittedFileIsTheOneThatShips() throws Exception {
        for (String pattern : AtomLibrary.PATTERNS) {
            String sprite = AtomLibrary.patternSprite(pattern);
            Path src = ASSETS.resolve("textures/item").resolve(sprite + ".png");
            assertTrue(Files.isRegularFile(src), src + " — run python3 tools/gen-atom-library.py");
            BufferedImage onDisk = javax.imageio.ImageIO.read(src.toFile());
            BufferedImage onPath = fromClasspath(sprite);
            int n = AtomLibrary.PATTERN_SPRITE_PX;
            int[] a = new int[n * n];
            int[] b = new int[n * n];
            onDisk.getRGB(0, 0, n, n, a, 0, n);
            onPath.getRGB(0, 0, n, n, b, 0, n);
            assertArrayEquals(a, b, sprite + ": the committed file and the built one differ — the "
                    + "game reads the built one, so rebuild rather than regenerate");
        }
    }
}
