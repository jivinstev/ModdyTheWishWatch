package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Surface, not just shape.</b> Turns "a grass skirt", "tiger stripes", "scaly" into real pixels.
 *
 * <p>A sculpted part used to carry exactly one flat colour, and {@code paint_creature} could only
 * apply bands (belt, waist, stripe) <i>after</i> the fact. So the whole class of request that is
 * about a creature's <b>surface</b> was unreachable at sculpt time: a troll's grass skirt, a tiger's
 * stripes, a snake's scales, the rings on Spikor's shell. See {@code docs/CREATURE_FIDELITY.md}
 *.
 *
 * <h2>Why a bounded vocabulary rather than free pixels</h2>
 *
 * <p>The constraint that governs every fidelity decision here is <b>tokens</b>: a creature wish
 * already takes 119-352 seconds, a 52-part sculpt is already ~2k output tokens, and anything the
 * agent has to <i>draw</i> costs latency linearly. A pattern name plus a second colour is about six
 * tokens and buys a whole textured part. Actual pixel art would cost hundreds and the child would
 * wait for them.
 *
 * <h2>Deterministic by construction</h2>
 *
 * <p>Every pattern is a pure function of the pixel coordinate — the speckles and fur use a hash of
 * {@code (x,y)}, never a {@code Random}. The same part always paints the same way, which is what
 * lets the graders treat a texture as a fact rather than a sample, and matches the rule the block
 * builders in this project already follow (decoration is a function of position, so a sliced build
 * equals a whole one).
 *
 * <p>Pure — no Minecraft, no image library — so it is unit-tested without a game.
 */
public final class SculptPattern {

    private SculptPattern() {
    }

    /**
     * The whole vocabulary. Bounded deliberately: this string reaches the geometry pipeline from an
     * agent acting on a child's description, and an open-ended field is a channel rather than a
     * pattern. Anything unrecognised degrades to flat.
     */
    public static final Set<String> NAMES = Set.of(
            "stripes", "spots", "scales", "fur", "plaid", "gradient", "speckle", "checker");

    /** An unknown or absent name becomes "" — a flat part, which still draws. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String n = raw.trim().toLowerCase(Locale.ROOT);
        // Forgiving on the near-misses an agent actually types, the way UvRegions matches bones.
        switch (n) {
            case "striped", "stripe" -> n = "stripes";
            case "spotted", "spot", "dots", "dotted" -> n = "spots";
            case "scaly", "scale" -> n = "scales";
            case "furry", "fuzzy", "hairy" -> n = "fur";
            case "tartan", "check" -> n = "plaid";
            case "checkered", "chequered", "checkerboard" -> n = "checker";
            case "speckled", "freckled" -> n = "speckle";
            case "faded", "ombre" -> n = "gradient";
            default -> { }
        }
        return NAMES.contains(n) ? n : "";
    }

    /**
     * The pattern rectangles to paint <b>on top of</b> a part's flat fill, inside its packed UV net.
     *
     * <p>Returns an empty list for a flat part, so the caller need not branch.
     *
     * @param part the part being painted
     * @param net  the part's rectangle in the atlas, as produced by the shelf packer
     */
    public static List<CreatureSculpt.PaintRect> overlay(
            CreatureSculpt.Part part, CreatureSculpt.PaintRect net) {
        List<CreatureSculpt.PaintRect> out = new ArrayList<>();
        if (part == null || net == null || !part.patterned()) {
            return out;
        }
        int x0 = net.x();
        int y0 = net.y();
        int w = net.width();
        int h = net.height();
        if (w <= 0 || h <= 0) {
            return out;
        }
        // TWO KINDS OF PATTERN, and the density must not be applied to both.
        //
        // A MACRO pattern is bands and blocks: it looked right at one texel per model unit, so its
        // features are measured in MODEL UNITS and scale with the density to keep the same physical
        // size. Chucko's socks were correct stripes and must stay correct stripes.
        //
        // A MICRO pattern is surface texture: it never worked, because at one texel per unit its
        // finest mark was a 1/16-block slab — fur came out as chunky blocks and scales as a brick
        // wall. Its features stay measured in TEXELS, so raising the density is exactly what makes
        // them finer, which is the whole point of the change.
        int u = CreatureSculpt.texelsPerUnit();
        int base = part.colour();
        int ink = inkFor(part);

        switch (part.pattern()) {
            case "stripes" -> {
                for (int y = u; y + u < h; y += 4 * u) {
                    out.add(new CreatureSculpt.PaintRect(x0, y0 + y, w, 2 * u, ink));
                }
            }
            case "plaid" -> {
                for (int y = u; y + u < h; y += 5 * u) {
                    out.add(new CreatureSculpt.PaintRect(x0, y0 + y, w, 2 * u, ink));
                }
                for (int x = u; x + u < w; x += 5 * u) {
                    out.add(new CreatureSculpt.PaintRect(x0 + x, y0, 2 * u, h, ink));
                }
            }
            case "checker" -> {
                int cell = 4 * u;
                for (int y = 0; y < h; y += cell) {
                    for (int x = 0; x < w; x += cell) {
                        if (((x / cell) + (y / cell)) % 2 == 0) {
                            continue;
                        }
                        out.add(new CreatureSculpt.PaintRect(x0 + x, y0 + y,
                                Math.min(cell, w - x), Math.min(cell, h - y), ink));
                    }
                }
            }
            case "spots" -> {
                // Offset every other row so the lattice reads as spots, not as a grid. Features
                // stay in texels: at density 4 a spot is half a model unit rather than two.
                int step = Math.max(4, u + 2);
                int dot = Math.max(2, u / 2);
                for (int y = 1; y + dot < h; y += step) {
                    int shift = ((y / step) % 2 == 0) ? 0 : step / 2;
                    for (int x = 1 + shift; x + dot < w; x += step) {
                        out.add(new CreatureSculpt.PaintRect(x0 + x, y0 + y, dot, dot, ink));
                    }
                }
            }
            case "scales" -> {
                // Staggered arcs drawn as a lip. In TEXELS, so at density 4 a scale is roughly a
                // model unit across instead of four -- the difference between scales and brickwork.
                int row = Math.max(2, u);
                int wide = Math.max(3, u);
                for (int y = 0; y + 1 < h; y += row) {
                    int shift = ((y / row) % 2 == 0) ? 0 : wide / 2 + 1;
                    for (int x = shift; x + wide < w; x += wide + 1) {
                        out.add(new CreatureSculpt.PaintRect(x0 + x, y0 + y, wide, 1, ink));
                    }
                }
            }
            case "fur" -> {
                // Vertical ticks of varying length, placed by hash so it looks unbrushed but is
                // still the same every time.
                for (int x = 0; x < w; x++) {
                    int hh = 1 + (hash(x, 0) % 3);
                    for (int y = hash(x, 1) % 4; y + hh <= h; y += 4 + (hash(x, y) % 3)) {
                        out.add(new CreatureSculpt.PaintRect(x0 + x, y0 + y, 1, hh, ink));
                    }
                }
            }
            case "speckle" -> {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (hash(x, y) % 11 == 0) {
                            out.add(new CreatureSculpt.PaintRect(x0 + x, y0 + y, 1, 1, ink));
                        }
                    }
                }
            }
            case "gradient" -> {
                // One rect per row, base at the top easing to the pattern colour at the bottom.
                for (int y = 0; y < h; y++) {
                    float t = h == 1 ? 1f : (float) y / (h - 1);
                    out.add(new CreatureSculpt.PaintRect(x0, y0 + y, w, 1, lerp(base, ink, t)));
                }
            }
            default -> { }
        }
        return out;
    }

    /**
     * <b>The colour a pattern will ACTUALLY be drawn in</b> — {@code patternColour} after the
     * same-as-the-part fallback.
     *
     * <p>Extracted because two different renderers now have to agree on it. The mod's own skin
     * bakes the ink into a texture, so the fallback happens once, inside {@link #overlay}, and
     * nobody outside ever needs to know. The ATOM rig ({@code docs/ATOM_RIG_PLAN.md} §2 B) draws
     * the pattern on a second display tinted by {@code dyed_color}, so it needs the same answer as
     * a NUMBER — and a second transcription of "…or contrast if it matches" is a creature whose
     * stripes are the right shape in the right place in the wrong colour, on one arm only.
     *
     * <p>Public and pure, so both arms call this one and the A/B compares a rendering rather than
     * two colour policies.
     *
     * @return 0xRRGGBB, never equal to the part's own colour unless the part has no pattern
     */
    public static int inkFor(CreatureSculpt.Part part) {
        if (part == null) {
            return 0;
        }
        int base = part.colour();
        int ink = part.patternColour();
        // A pattern in the SAME colour as the part is invisible and would read as "the feature did
        // nothing". Fall back to a readable contrast rather than painting a no-op.
        return (ink == base || ink == 0) ? contrastWith(base) : ink;
    }

    /**
     * A readable second colour when none was given.
     *
     * <p>Not simply "black": on a dark part a black stripe is invisible, which reads to a child as
     * the pattern having failed rather than as a dark stripe. So it darkens a light colour and
     * lightens a dark one.
     */
    static int contrastWith(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        // Rec. 601 luma — good enough to ask "is this light or dark", and integer-cheap.
        int luma = (299 * r + 587 * g + 114 * b) / 1000;
        float f = luma > 128 ? 0.45f : 1.9f;
        return pack(scale(r, f), scale(g, f), scale(b, f));
    }

    private static int scale(int c, float f) {
        return Math.max(0, Math.min(255, Math.round(c * f)));
    }

    private static int pack(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    static int lerp(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((from >> 16) & 0xFF) * (1 - k) + ((to >> 16) & 0xFF) * k);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - k) + ((to >> 8) & 0xFF) * k);
        int b = Math.round((from & 0xFF) * (1 - k) + (to & 0xFF) * k);
        return pack(r, g, b);
    }

    /**
     * A cheap, stable scramble of a pixel coordinate.
     *
     * <p>Deliberately NOT {@code Random}: a texture that differs between two bakes of the same
     * creature would make every grader a sampling exercise, and would break the "re-sculpt updates
     * the creature already in the world" contract by repainting it differently each time.
     */
    static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }
}
