package com.moddingfromamod.vanilla;

/**
 * <b>What colour to multiply a creature by, for a state vanilla would have drawn on its model.</b>
 *
 * <p>Minecraft-free on purpose: every number here is derived from vanilla's own shader and overlay
 * texture, so it can be checked in a unit test rather than eyeballed in a client.
 *
 * <h2>Why a multiply at all</h2>
 *
 * <p>Vanilla's hurt flash is an <b>overlay</b> applied to the entity's MODEL, and the rig's entity
 * is invisible, so it cannot be reached. What a vanilla client will do for us is multiply a model
 * by a colour, through {@code minecraft:dyed_color} on the carrier item — see
 * {@code RigPackWriter#TINTABLE} (deleted) for the two halves that have to be true at once.
 *
 * <h2>The exact arithmetic, both sides</h2>
 *
 * <p>Read out of {@code rendertype_entity_cutout_no_cull.fsh} and {@code OverlayTexture}'s
 * constructor rather than remembered:
 *
 * <pre>
 *   overlay pixel (v &lt; 8)  = 0xB20000FF as ABGR  -&gt;  pure RED at alpha 178/255 = 0.6980
 *   the fragment shader     : color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a)
 *                           = overlay*(1-a) + color*a
 *   so vanilla's hurt flash : r' = 0.302 + 0.698*r ,  g' = 0.698*g ,  b' = 0.698*b
 *   and our tint            : color *= vertexColor            (the same term, one line earlier)
 *                           : r' = tr*r , g' = tg*g , b' = tb*b
 * </pre>
 *
 * <p>So the multiply that best emulates the flash is {@code (1.0, 0.698, 0.698)} =
 * {@link #HURT_FLASH}. <b>Green and blue come out EXACTLY right</b>; only red differs, and it
 * differs by {@code 0.302 × (1 − r)} — nothing, on an already-red creature, and the full 30% on a
 * white one. That is the whole of the emulation gap, and it is a number rather than a hand-wave:
 * see {@link #redShortfall}.
 *
 * <h2>⚠ The default is LEATHER BROWN, and it is not a colour anyone chose</h2>
 *
 * <p>{@code ItemColors.createDefault} registers the dyeable handler as
 * {@code tintIndex > 0 ? -1 : DyedItemColor.getOrDefault(stack, -6265536)} — and that fallback is
 * {@code 0xA06540}, the colour of undyed leather. The rig's models declare {@code tintindex: 0} on
 * every face, so a display wearing the tintable carrier <b>with no {@code dyed_color} component at
 * all</b> is not untinted: it is multiplied by brown. {@link #NONE} exists so that never happens,
 * and {@code DisplayRig.wear} writes it unconditionally under {@code TINTABLE}.
 *
 * <p>This is the shape that would have shipped as "the tint channel makes creatures look muddy":
 * the bug is in the ABSENCE of a component, so nothing in the pack, the model or the write path
 * looks wrong.
 */
public final class RigTint {

    private RigTint() {
    }

    /** White. A multiply by white is the identity — "no tint", spelt as a colour. */
    public static final int NONE = 0xFFFFFF;

    /**
     * What vanilla multiplies by when a dyeable item carries no {@code dyed_color} — undyed
     * leather. Never a colour to ship; here so a test can assert we never leave it in place.
     */
    public static final int LEATHER_DEFAULT = 0xA06540;

    /** The alpha of vanilla's red overlay row, {@code 178/255}. */
    public static final float HURT_ALPHA = 178f / 255f;

    /** {@code (1.0, 0.698, 0.698)} — derived above, not chosen. */
    public static final int HURT_FLASH = pack(1.0f, HURT_ALPHA, HURT_ALPHA);

    /**
     * ⚠ <b>INVENTIONS, not parity.</b> Vanilla tints a poisoned, withered or freezing mob's model
     * <b>not at all</b> — poison and wither are swirl particles only (which already reach the rig
     * for free), and freezing is a shake in {@code setupRotations} plus a first-person vignette.
     * Verified in {@code LivingEntityRenderer.getOverlayCoords}, whose entire body is
     * {@code u = getWhiteOverlayProgress}, {@code v = hurtTime > 0 || deathTime > 0}.
     *
     * <p>They are kept because "make him green" is a wish a child will type, and this is the
     * channel that would grant it. But a row that draws something vanilla does not is a
     * DIVERGENCE, and calling it parity would be the same mistake as scoring an instrument against
     * itself. Nothing switches them on by itself.
     */
    public static final int POISON = 0x88CC88;

    /** See {@link #POISON} — an invention. */
    public static final int WITHER = 0x555555;

    /** See {@link #POISON} — an invention. */
    public static final int FROST = 0xBFE6FF;

    /**
     * The tint for a creature that is being hurt or is dying.
     *
     * <p>⚠ The {@code deathTime} half is not decoration and is easy to drop: vanilla's own
     * condition is {@code hurtTime > 0 || deathTime > 0}, so <b>a dying mob is red for the whole
     * death animation</b>, not just for the ten ticks after the blow that killed it. A flop that
     * plays in the creature's ordinary colours is visibly not what Minecraft does.
     */
    public static int hurtFlash(int hurtTime, int deathTime) {
        return hurtTime > 0 || deathTime > 0 ? HURT_FLASH : NONE;
    }

    /**
     * How much red this emulation fails to add, for a channel value {@code r} in 0..1 — the whole
     * of the difference from vanilla, and zero on a creature that is already fully red.
     */
    public static float redShortfall(float r) {
        return (1f - HURT_ALPHA) * (1f - r);
    }

    /** Apply the multiply to one 0xRRGGBB colour — what a client will actually draw. */
    public static int multiply(int rgb, int tint) {
        int r = ((rgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * <b>A colour part of the way to white</b> (the transformation): {@code t = 0} is
     * {@code rgb}, {@code t = 1} is pure white. The tint channel is a MULTIPLY, so no tint can make
     * a box brighter than its own colour; whitening is done on the colour itself, before the
     * multiply, which is why it is a separate step from {@link #multiply}.
     */
    public static int towardWhite(int rgb, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((rgb >> 16) & 0xFF) + (255 - ((rgb >> 16) & 0xFF)) * k);
        int g = Math.round(((rgb >> 8) & 0xFF) + (255 - ((rgb >> 8) & 0xFF)) * k);
        int b = Math.round((rgb & 0xFF) + (255 - (rgb & 0xFF)) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** What VANILLA would draw for the same pixel, so a test can measure the gap rather than argue. */
    public static int vanillaHurt(int rgb) {
        float a = HURT_ALPHA;
        int r = Math.round(255 * (1 - a) + ((rgb >> 16) & 0xFF) * a);
        int g = Math.round(((rgb >> 8) & 0xFF) * a);
        int b = Math.round((rgb & 0xFF) * a);
        return (Math.min(255, r) << 16) | (g << 8) | b;
    }

    private static int pack(float r, float g, float b) {
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
