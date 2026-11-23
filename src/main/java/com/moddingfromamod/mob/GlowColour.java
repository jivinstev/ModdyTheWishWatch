package com.moddingfromamod.mob;

import java.util.List;
import java.util.Locale;

/**
 * Turns any colour a child might name into one Minecraft can actually paint a <b>real</b> mob with.
 *
 * <p>This is what makes "make the wither storm pink" answerable without abandoning the real mob.
 * A modded entity's texture is shipped inside its own jar, so we can't tint it the way
 * {@code wish_mob} tints a vanilla texture. But every entity can be made to <b>glow</b>, and the
 * glow outline is drawn in the colour of the entity's <b>scoreboard team</b> — which works on any
 * mob from any mod, with no art and no restart.
 *
 * <p>The catch, and the reason this class exists: a team colour is not free RGB. It must be one of
 * the 16 {@code ChatFormatting} colours. So an arbitrary {@code #FF69B4} has to be snapped to the
 * nearest one, and "nearest" in plain RGB distance picks visibly wrong colours (it will happily
 * call a saturated pink "grey", because grey sits near the middle of the cube and is close to
 * everything). We use the <b>redmean</b> approximation instead — a cheap, well-known perceptual
 * distance that keeps hues where a human puts them.
 *
 * <p>Pure — no Minecraft imports — so the snapping is unit-tested in milliseconds. The Minecraft
 * side only resolves the returned name via {@code ChatFormatting.getByName}.
 */
public final class GlowColour {

    /** Sentinel meaning "don't glow" / "stop glowing". */
    public static final String NONE = "none";

    /**
     * The 16 team colours, with their real vanilla RGB values.
     *
     * <p>Order matters only for tie-breaking, which is why it's a list and not a map: an exact tie
     * should resolve to the same colour every run, or a test that snaps a midpoint becomes flaky.
     */
    private static final List<Entry> COLOURS = List.of(
            new Entry("black", 0x000000),
            new Entry("dark_blue", 0x0000AA),
            new Entry("dark_green", 0x00AA00),
            new Entry("dark_aqua", 0x00AAAA),
            new Entry("dark_red", 0xAA0000),
            new Entry("dark_purple", 0xAA00AA),
            new Entry("gold", 0xFFAA00),
            new Entry("gray", 0xAAAAAA),
            new Entry("dark_gray", 0x555555),
            new Entry("blue", 0x5555FF),
            new Entry("green", 0x55FF55),
            new Entry("aqua", 0x55FFFF),
            new Entry("red", 0xFF5555),
            new Entry("light_purple", 0xFF55FF),
            new Entry("yellow", 0xFFFF55),
            new Entry("white", 0xFFFFFF));

    private record Entry(String name, int rgb) {
    }

    private GlowColour() {
    }

    /** Every team-colour name, for the tool schema and for error messages. */
    public static List<String> names() {
        return COLOURS.stream().map(Entry::name).toList();
    }

    /**
     * Resolve what the agent wrote — a colour name ("pink"), a hex value ("#FF69B4"), or
     * {@link #NONE} — into a vanilla team-colour name.
     *
     * @return the team colour name, or {@link #NONE} for "no glow". Never null.
     */
    public static String resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (v.equals(NONE) || v.equals("off") || v.equals("no")) {
            return NONE;
        }
        // An exact team-colour name wins outright: if the agent wrote "light_purple" it means that
        // colour, and it would be silly to round-trip it through RGB and risk snapping it elsewhere.
        for (Entry e : COLOURS) {
            if (e.name.equals(v)) {
                return e.name;
            }
        }
        // Otherwise reuse the species colour vocabulary ("pink", "hot_pink", "#FF69B4"), so the two
        // creature paths accept exactly the same words. Then snap to the nearest team colour.
        int rgb = com.moddingfromamod.species.Species.parseColour(v);
        return nearest(rgb);
    }

    /** The team colour closest to an RGB value, by perceptual (redmean) distance. */
    public static String nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        String best = "white";
        double bestDistance = Double.MAX_VALUE;
        for (Entry e : COLOURS) {
            double d = redmean(r, g, b, (e.rgb >> 16) & 0xFF, (e.rgb >> 8) & 0xFF, e.rgb & 0xFF);
            if (d < bestDistance) {
                bestDistance = d;
                best = e.name;
            }
        }
        return best;
    }

    /**
     * Redmean colour distance (squared — we only ever compare, so the square root is wasted work).
     *
     * <p>Weights the channels by where in the red axis the two colours sit, which is what stops a
     * saturated hue collapsing to grey the way plain Euclidean distance does.
     */
    private static double redmean(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rMean = (r1 + r2) / 2.0;
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return (2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db;
    }
}
