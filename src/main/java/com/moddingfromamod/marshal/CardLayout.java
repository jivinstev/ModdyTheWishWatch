package com.moddingfromamod.marshal;

/**
 * How much of the wish card goes to the picture and how much to the words.
 *
 * <p><b>Why this is a pure class and not a few lines inside the screen.</b> Three versions of this
 * arithmetic shipped, each wrong in a different way, and each one cost a fifteen-minute client run
 * to discover — because the failures are invisible to everything cheaper. In order:
 *
 * <ol>
 *   <li><b>No floor.</b> The story drew underneath the action buttons.</li>
 *   <li><b>A floor, plus an assertion that nothing crosses it.</b> It passed by drawing almost
 *       nothing: the card kept a big photograph and silently dropped the row saying what the
 *       creature DOES and the row quoting the child's own wish. Every assertion was green.</li>
 *   <li><b>The picture yielding to the words.</b> Better, but a long wish still did not fit even
 *       with the photograph at its smallest, so the story was dropped again.</li>
 * </ol>
 *
 * <p>All three are arithmetic, answerable in microseconds, and none of them needed a game. So the
 * arithmetic lives here in line counts — no fonts, no {@code GuiGraphics}, no Minecraft — and the
 * screen is left with nothing but the drawing.
 *
 * <p><b>The priority order IS the argument.</b> One line of his own words is allocated before the
 * photograph gets anything above its floor, because the two rows every earlier version dropped are
 * the two that carry the meaning: what the thing does, and why he asked for it. A card that will not
 * repeat his wish back cannot tell him what it is looking at.
 */
public final class CardLayout {

    /**
     * The result: how many lines each block gets, and how tall the photograph may be.
     *
     * @param powerLines how many wrapped lines of "what it does" fit
     * @param storyLines how many wrapped lines of his own wish fit; never zero
     * @param heroHeight the photograph's height in pixels
     */
    public record Fit(int powerLines, int storyLines, int heroHeight) {

        /**
         * Total height this fit consumes, so a test can prove it lands inside the panel.
         *
         * <p>Deliberately does NOT add the gap under the photograph: {@code available} is measured
         * with that gap already taken out. Adding it here too double-counts it, which is what the
         * first run of {@code everythingItAllocatesActuallyFitsOnThePage} caught — a 12px overflow
         * on the emptiest possible card.
         */
        public int height(int fixed, int line) {
            return heroHeight + fixed + powerLines * line + storyLines * line;
        }
    }

    private CardLayout() {
    }

    /**
     * Share the space out.
     *
     * @param available     pixels between the top of the photograph and the floor above the buttons
     * @param fixed         pixels the non-negotiable rows need (name, tier, aliases, vitals, count)
     * @param wantPowerLines how many lines the powers would take if unconstrained
     * @param wantStoryLines how many lines his wish would take if unconstrained
     * @param line          one line's height
     * @param heroMin       how small the photograph may get before it stops being worth showing
     * @param heroMax       how big it may get when there is room to spare
     */
    public static Fit allocate(int available, int fixed, int wantPowerLines, int wantStoryLines,
                               int line, int heroMin, int heroMax) {
        // One line of his words and the smallest worthwhile picture come out first; whatever is left
        // is what there is to argue over.
        int spare = available - fixed - line - heroMin;

        int powerLines = clamp(wantPowerLines, 0, Math.max(0, spare / line));
        spare -= powerLines * line;

        int storyLines = 1 + clamp(wantStoryLines - 1, 0, Math.max(0, spare / line));
        spare -= (storyLines - 1) * line;

        // Anything still unclaimed makes the photograph bigger. It is last on purpose: a picture
        // that grew at the expense of the caption is the exact failure this class exists to stop.
        int hero = clamp(heroMin + Math.max(0, spare), heroMin, heroMax);
        return new Fit(powerLines, storyLines, hero);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
