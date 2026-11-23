package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>: an impulse pack compiled before the fix, put right on its way into a world.</b>
 *
 * <p>{@code effects/impulse.cbs} used to telegraph — 60 particles and a knockback sound — whenever
 * the creature's own clock came up, whether or not anything was in reach, and {@code apply_effect}
 * runs that clock at {@code PERIOD_TICKS=1} for {@code proximity} and {@code on_hit}. So every
 * creature ever given "knock back whatever comes near" stomped every tick, forever, with nothing
 * near it. The template is fixed; but an ability is compiled ONCE, when it is wished, and stored
 * as a zip ({@link DatapackStore}) — a fixed template reaches none of the creatures that already
 * have one, the live T-Rex included.
 *
 * <p>So the stored zip is left alone and its INSTALL copy is rewritten into exactly what the fixed
 * template renders for the same parameters, the way {@link DatapackDialect} already rewrites a
 * stored pack's command spelling. Every install goes through that, including the one at every
 * boot ({@link DatapackStore#installAllIntoWorld}), so a server picks the fix up at its next
 * restart with no store migration: no stored byte changes. {@code ImpulseTelegraphFixTest} pins
 * the rewrite to the fixed template's own render, line for line.
 *
 * <p>Pure: text in, text out. A function that is not an old impulse — or is a PERIODIC one, whose
 * clock alone is its trigger and which the fix leaves unchanged — comes back untouched.
 */
public final class ImpulseTelegraphFix {

    /** The re-arm a proximity impulse gets: shoved once a second, not twenty times. */
    public static final int PROXIMITY_REARM_TICKS = 20;

    /** Step 2 of the old template: whoever the trigger catches starts a countdown. */
    private static final Pattern CATCH = Pattern.compile(
            "^execute as @e\\[tag=([^,\\]]+),scores=\\{mfam_impt=(\\d+)\\.\\.}] at @s run execute as "
                    + "(@e\\[.*]) run scoreboard players set @s mfam_imp (\\d+)$");

    private ImpulseTelegraphFix() {
    }

    /** {@code text} with an old impulse's telegraph gated on a catch, or {@code text} unchanged. */
    public static String fix(String text, List<String> notes) {
        String[] lines = text.split("\n", -1);
        String tag = null;
        String period = null;
        String selector = null;
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CATCH.matcher(lines[i].strip());
            if (m.matches()) {
                tag = m.group(1);
                period = m.group(2);
                selector = m.group(3);
                at = i;
                break;
            }
        }
        if (at < 0) {
            return text;
        }
        boolean onHit = selector.contains("HurtTime");
        String fresh;
        if (onHit) {
            fresh = period;
        } else if ("1".equals(period)) {
            fresh = String.valueOf(PROXIMITY_REARM_TICKS);  // proximity: a re-arm, not every tick
        } else {
            return text;                                     // periodic: the clock IS the trigger
        }
        String clockUp = "as @e[tag=" + tag + ",scores={mfam_impt=" + period + "..}]";
        String fired = "as @e[tag=" + tag + ",tag=mfam_impf]";
        String indent = lines[at].substring(0, lines[at].indexOf(lines[at].strip()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i == at) {
                out.add(indent + "execute as @e[tag=" + tag + ",scores={mfam_impt=" + fresh
                        + "..}] at @s if entity " + selector + " run tag @s add mfam_impf");
                out.add(l.replace(clockUp, fired));
            } else if (l.strip().equals("execute " + clockUp + " run scoreboard players set @s mfam_impt 0")) {
                out.add(l.replace(clockUp, fired));
                out.add(indent + "tag @e[tag=" + tag + ",tag=mfam_impf] remove mfam_impf");
            } else if (l.contains(clockUp) && (l.contains(" run particle ") || l.contains(" run playsound "))) {
                out.add(l.replace(clockUp, fired));
            } else {
                out.add(l);
            }
        }
        notes.add("impulse '" + tag + "': telegraph only when something is caught"
                + (onHit ? "" : ", re-armed every " + fresh + " ticks") + " (#1725)");
        return String.join("\n", out);
    }
}
