package com.moddingfromamod.runtime.gate;

import java.util.Locale;

import com.moddingfromamod.builder.BuildFingerprint;
import com.moddingfromamod.builder.BuildPlan;

/**
 * <b>Did this edit keep the building, or replace it?</b> — {@link ShapeDrift} for buildings.
 *
 * <h2>Why it is keyed on the FOOTPRINT and not on the block count</h2>
 * The obvious gate is "how many of the building's blocks survived", and it fires on correct
 * refinements. Measured on a real arm-A ladder: across five rungs the footprint held at
 * <b>1.00</b> while block survival fell to <b>0.71</b>, and the rung responsible was
 * <i>"make it much taller"</i> — which kept only 0.43 of the previous version because a taller
 * castle necessarily replaces its wall shapes. That is the wish being granted, not drift.
 *
 * <p>A gate that fires there is the R5 failure in advance: <i>a warning that fires on every change
 * is one the agent learns to skim.</i> The footprint is the thing that does <b>not</b> legitimately
 * move — a castle re-roofed, made taller, hollowed out or repainted stands on the same ground; a
 * castle replaced by a different building does not.
 *
 * <p>A <b>repaint</b> therefore needs no special case at all. Changing every block from purple to
 * gold leaves every position occupied, so footprint survival is 1.00 and this is silent — where
 * {@link PaletteDrift} needed a whole {@code repaintRequested} discriminator to reach the same
 * answer.
 *
 * <h2>It informs; it does not command</h2>
 * Deliberately no "fix it". Five mechanisms in this codebase have now been caught instructing the
 * agent in an authoritative voice and being obeyed into a worse outcome — including the
 * {@code Surroundings} line that tells it to change a building by calling {@code build_structure}
 * with the same id, which is the instruction that produced four castles from five wishes. Here the
 * obvious command — "put the building back" — would be satisfied by yet another from-scratch
 * rebuild, which is the thing this exists to catch.
 *
 * <h2>What used to be here, and why it is not</h2>
 * A <b>proportion</b> warning lived here — it fired when an addition dwarfed the building it was
 * added to, on the measurement that a stranger sized an added dome 6–12× the castle where the
 * worker who built it sized the same dome 2.5×. It was well aimed (3/3 on the affected fixture,
 * 0/3 on the other) and it <b>did not work</b>: growth went from median 6.0× to 4.6× with
 * overlapping spreads, i.e. no effect measured. The agent read it and carried on.
 *
 * <p>It is deleted rather than left in place because an ineffective gate is worse than none: it
 * reads as "handled" to whoever looks next. The evidence is also weak on its own terms — every
 * instance came from ONE synthetic rung ("put a big glass dome on top of it", a wish shaped to
 * enclose something), while the ladder taken from wishes a child really made showed growth 1.0× in
 * all three cells. See, closed pending a real reproduction.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class BuildDrift {

    /**
     * Losing this share of the previous building's GROUND PLAN is a replacement, not an edit.
     *
     * <p>Half, matching {@link ShapeDrift#REPLACED}. Some overlap is meaningless — two buildings on
     * one anchor share ground by coincidence — so the threshold is deliberately not near 1.0.
     */
    public static final double REPLACED = 0.5;

    /**
     * How much of the block count may vanish before it is worth a quiet mention.
     *
     * <p>Separate from, and much looser than, {@link #REPLACED}: this one is a remark, not a
     * finding, because the measured legitimate case ("make it much taller") sits at 0.43.
     */
    public static final double SHRANK = 0.35;



    private BuildDrift() {
    }

    /**
     * What this edit lost, in the child's terms, or "" if nothing worth saying did.
     *
     * @param before     the building as it stood before this call; null for a creation
     * @param after      the RESOLVED building — base and diff merged, not the shapes sent
     * @param childWords the wish being granted; a demolition he asked for is never reported
     */
    public static String between(BuildPlan before, BuildPlan after, String childWords) {
        if (before == null || after == null
                || before.shapes().isEmpty() || after.shapes().isEmpty()) {
            return "";                       // a creation has nothing to lose
        }
        if (demolitionRequested(childWords)) {
            return "";
        }
        Double ground = BuildFingerprint.footprintSurvival(before, after).fraction();
        Double blocks = BuildFingerprint.survival(before, after).fraction();
        if (ground == null) {
            return "";
        }
        if (ground < REPLACED) {
            return "⚠ THIS REPLACED THE BUILDING INSTEAD OF EDITING IT — only "
                    + pct(ground) + " of the ground it stood on is still built on. He never asked "
                    + "for the old one to go; it is simply everything he did not describe again "
                    + "this time. build_structure with start_from keeps it. Say in your note "
                    + "whether the building he had is still there.";
        }
        if (blocks != null && blocks < SHRANK) {
            return "This kept the building's footprint but only " + pct(blocks) + " of its blocks. "
                    + "If it was meant to grow or change shape that is fine; if not, start_from "
                    + "would have kept the parts he did not mention. Mention it in your note.";
        }
        return "";
    }


    /**
     * Did he ask for the building to go?
     *
     * <p>Deliberately generous — a false "he asked" costs one unreported drift, while a false "he
     * did not" trains the agent to skim a warning that matters.
     *
     * <p><b>"taller" is deliberately absent</b>, and that is the whole calibration. It is the wish
     * that destroys the most of a building (0.43 of the previous version, measured) and it is not a
     * request to lose anything. The creature version of this list learned the same lesson the
     * expensive way: a blanket rule silenced <i>"give him tiny black wings"</i>, the exact wish
     * that destroyed the creature.
     */
    static boolean demolitionRequested(String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String said = childWords.toLowerCase(Locale.ROOT);
        for (String phrase : new String[] {
                "remove", "delete", "knock it down", "knock down", "tear down", "take down",
                "get rid", "start again", "start over", "smaller", "shorter", "tiny",
                "replace it", "different building", "demolish"}) {
            if (said.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
