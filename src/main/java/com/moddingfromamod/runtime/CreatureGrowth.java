package com.moddingfromamod.runtime;

/**
 * <b>What "make it bigger" is allowed to mean.</b>
 *
 * <h2>The bug</h2>
 *
 * <p>Observed in a real two-wish run. The follow-up was "make it bigger and cooler":
 *
 * <pre>
 *   tralalero   2.75 blocks  ->  12.00 blocks
 *   spikor      5.84 blocks  ->   9.10 blocks
 * </pre>
 *
 * <p>Twelve blocks is a building, not a creature. It does not fit indoors, the camera cannot frame
 * it — so the agent's own photograph comes back as a wall of colour it cannot judge — and a child
 * asking for "bigger" means <i>a bit bigger</i>, not four times taller. Notably the part count did
 * not change at all (37 → 37), so nothing was added: the agent simply multiplied the scale, which
 * is the cheapest possible reading of the word and the least satisfying one.
 *
 * <h2>Why a rule in code and not a lesson</h2>
 *
 * <p>A lesson is something the agent must remember on every revision forever, and this project has
 * measured what happens to rules: they are obeyed precisely, which is also how a wrong one does
 * damage. A ceiling that cannot be exceeded needs no remembering. The agent is still <b>told</b>
 * what happened and why, because a silently clamped size would read to it as its own instruction
 * having no effect, and the usual response to that is to try a bigger number.
 *
 * <h2>The two limits, and why both</h2>
 *
 * <p>{@link #MAX_STEP} bounds one revision: "bigger" is a step, not a leap, and a child who wants
 * enormous can ask twice. {@link #MAX_HEIGHT_BLOCKS} bounds the result however many steps it took —
 * without it, three polite 1.6× steps still reach eleven blocks. Eight blocks is deliberately
 * generous: taller than every vanilla mob but the Ender Dragon, so a GINORMOUS gorilla boss is
 * still ginormous, and short enough that it fits in a built room and inside the camera's frame.
 *
 * <p>Pure and Minecraft-free.
 */
public final class CreatureGrowth {

    private CreatureGrowth() {
    }

    /**
     * The most one revision may multiply a creature's size by.
     *
     * <p>1.6 rather than 2: doubling reads as a different creature rather than the same one bigger,
     * and two steps of 1.6 already reach 2.56×, so wanting enormous is one extra sentence rather
     * than an impossibility.
     */
    public static final double MAX_STEP = 1.6;

    /** And the most it may ever reach, however many steps it took to get there. */
    public static final double MAX_HEIGHT_BLOCKS = 8.0;

    /** Below this a creature is a speck the child cannot see, let alone fight. */
    public static final double MIN_HEIGHT_BLOCKS = 0.4;

    /**
     * @param scale    the scale that will actually be used
     * @param capped   true when it is not the scale that was asked for
     * @param because  what to tell the agent, or "" when nothing was changed
     */
    public record Decision(double scale, boolean capped, String because) {
    }

    /**
     * Decide the scale a sculpt may use.
     *
     * @param modelHeightUnits the tallest point of the geometry, in sixteenths
     * @param wanted           the scale the agent asked for
     * @param previousScale    the scale this creature already had, or 0 for a first build
     */
    public static Decision decide(float modelHeightUnits, double wanted, double previousScale) {
        double asked = Math.max(0.01, wanted);
        double scale = asked;
        String because = "";

        // ONE STEP AT A TIME -- only on a revision. A first build has nothing to be a step from,
        // and applying the step cap to it would mean a creature that is meant to be big has to be
        // wished for twice, which is a worse experience than the bug.
        if (previousScale > 0 && scale > previousScale * MAX_STEP) {
            scale = previousScale * MAX_STEP;
            because = String.format("\"Bigger\" is a step, not a leap — scale %.2f would have been "
                    + "%.1fx in one go, so it is %.2f (%.1fx). Ask again to grow it further. "
                    + "And bigger is more fun when something is ADDED, not only stretched: more "
                    + "spikes, more detail, another feature.",
                    asked, asked / previousScale, scale, scale / previousScale);
        }

        double height = Framing.heightInBlocks(modelHeightUnits, scale);
        if (height > MAX_HEIGHT_BLOCKS) {
            double fitted = scale * (MAX_HEIGHT_BLOCKS / height);
            because = String.format("%.1f blocks tall is a building, not a creature — it would not "
                    + "fit in a room and the camera cannot frame it, so a photograph of it is a "
                    + "wall of colour. Capped to %.1f blocks (scale %.2f).",
                    height, MAX_HEIGHT_BLOCKS, fitted);
            scale = fitted;
        } else if (height < MIN_HEIGHT_BLOCKS) {
            double fitted = scale * (MIN_HEIGHT_BLOCKS / height);
            because = String.format("%.2f blocks is a speck the child cannot see. Raised to %.1f "
                    + "blocks (scale %.2f).", height, MIN_HEIGHT_BLOCKS, fitted);
            scale = fitted;
        }

        // Round, so the number the agent is told back is one it could have typed itself.
        scale = Math.round(scale * 100.0) / 100.0;
        return new Decision(scale, because.isEmpty() ? false : true, because);
    }
}
