package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The laser: a beam from his eyes to the first thing in its way.</b>
 *
 * <p>The {@code on_shot} shape runs the moment he shoots, as him. A beam is drawn one block at a time
 * along the way he is looking, and it stops at the first solid block or creature. A wish that
 * happens to something else, or to a place, happens THERE, at the last clear cell before whatever
 * stopped it. A wish that happens to him happens on him, and the beam is still drawn. His own arrow
 * still flies, because the weapon shoots it; the beam is additive.
 *
 * <p>Unrolled rather than recursive, like {@link ChangeBlock#aimLines}: a function that calls itself
 * is bounded by {@code maxCommandChainLength} and fails silently past it, and 32 fixed steps are
 * easy to read in the compiled pack. The shooter is tagged for the duration, so the beam never
 * "hits" him as it leaves his eyes and an effect's own selector can leave him out.
 *
 * <p>Minecraft-free: it writes command strings, so it is tested without a game.
 */
public final class ShotBeam {

    /** How far the beam reaches, in blocks. */
    public static final int REACH = 32;

    /** The tag the shooter carries while his beam runs. */
    public static final String SHOOTER = "mfam_shooter";

    private static final String DONE = "mfam_beam_done";
    private static final String HIT = "mfam_beam_hit";
    private static final String NOT_A_TARGET = "tag=!" + SHOOTER
            + ",type=!minecraft:item,type=!minecraft:experience_orb,type=!#minecraft:arrows"
            + ",type=!minecraft:trident";

    private ShotBeam() {
    }

    /**
     * <b>The laser is the item's own colour.</b> A dust particle, which both targets parse in this
     * form. The first cut drew the beam with the effect's trail particle, and filmed, a lightning
     * bow's electric sparks were invisible: the beam existed and could not be seen.
     */
    public static String particle(int rgb) {
        return String.format(java.util.Locale.ROOT, "minecraft:dust{color:[%.3f,%.3f,%.3f],scale:1.5}",
                ((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0);
    }

    /**
     * @param function the effect to run
     * @param atHit    true for a wish that lands on something else or a place (it runs where the
     *                 beam stops); false for a wish on HIM (it runs on him, and the beam is only seen)
     * @param particle the particle the beam is drawn with
     */
    public static List<String> lines(String function, boolean atHit, String particle) {
        List<String> out = new ArrayList<>();
        out.add("/tag @s add " + SHOOTER);
        out.add("/tag @s remove " + DONE);
        for (int i = 1; i <= REACH; i++) {
            String at = "at @s anchored eyes positioned ^ ^ ^" + i;
            String live = "/execute if entity @s[tag=!" + DONE + "] " + at;
            out.add(live + " unless block ~ ~ ~ " + ChangeBlock.SEE_THROUGH + " run tag @s add " + HIT);
            out.add(live + " if entity @e[distance=..1," + NOT_A_TARGET + "] run tag @s add " + HIT);
            if (atHit) {
                out.add("/execute if entity @s[tag=" + HIT + "] at @s anchored eyes positioned ^ ^ ^" + (i - 1)
                        + " run function " + function);
            }
            out.add("/tag @s[tag=" + HIT + "] add " + DONE);
            out.add("/tag @s remove " + HIT);
            // TWO points a block, so the beam reads as a LINE on film rather than a dotted scatter.
            out.add(live + " run particle " + particle + " ~ ~ ~ 0.01 0.01 0.01 0 3 force");
            out.add(live + " positioned ^ ^ ^-0.5 run particle " + particle + " ~ ~ ~ 0.01 0.01 0.01 0 3 force");
        }
        if (atHit) {
            // Nothing in the way: the wish happens where the beam ends, out in the open.
            out.add("/execute if entity @s[tag=!" + DONE + "] at @s anchored eyes positioned ^ ^ ^" + REACH
                    + " run function " + function);
        } else {
            out.add("/function " + function);
        }
        out.add("/tag @s remove " + DONE);
        out.add("/tag @s remove " + SHOOTER);
        return out;
    }
}
