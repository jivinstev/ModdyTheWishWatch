package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>: a grow or impulse pack compiled before its victims were TAGGED, put right on its way into
 * a world</b> — the same move {@link ImpulseTelegraphFix} made, and chained right after it.
 *
 * <p>{@code items/grow.cbs} (the starter cookie, and every grow item a child made) and
 * {@code effects/impulse.cbs} (every creature's push/launch/pull) tracked their victims by a SCORE
 * alone, so every tick {@code @e[scores={…}]} looked at every entity in the world — and a wished
 * creature is dozens of entities, which the {@code @e[tag=…]} index cannot help with. Both templates
 * now tag the victim while its countdown runs. But a pack is compiled once, when it is wished, so a
 * fixed template reaches none of the packs already installed — on one live world,
 * {@code impulse_giant_trex}; on every world, the starter cookie.
 *
 * <p>So, like the telegraph fix, the stored zip is left alone and its INSTALL copy is rewritten into
 * exactly what the fixed template renders, line for line ({@code VictimTagFixTest} pins it to the
 * templates' own render). Every install goes through {@link DatapackDialect#correctFunction},
 * including the one at every boot, so a server picks it up at its next restart and no stored byte
 * changes. Pure: text in, text out; a function that is neither comes back untouched, and so does one
 * already in the new form (idempotent).
 */
public final class VictimTagFix {

    private VictimTagFix() {
    }

    // ---- impulse ----

    /** Step 3, both shapes (gated on mfam_impf, or on the clock for a periodic pack). */
    private static final Pattern IMP_CATCH = Pattern.compile(
            "^(execute as @e\\[[^\\]]*] at @s run execute as )(.+) run scoreboard players set @s mfam_imp (\\d+)$");
    private static final Pattern IMP_LANDING = Pattern.compile(
            "^execute as @e\\[scores=\\{mfam_imp=(\\d+)}] at @s run (.+)$");
    private static final String IMP_MOVE_OLD = "execute as @e[scores={mfam_imp=1..}] at @s run ";
    private static final String IMP_COUNT_OLD = "execute as @e[scores={mfam_imp=1..}] run scoreboard players remove @s mfam_imp 1";
    static final String IMP_END = "execute as @e[tag=mfam_imp_on] unless score @s mfam_imp matches 1.. run tag @s remove mfam_imp_on";

    // ---- grow ----

    private static final String GROW_RESET = "scoreboard objectives add mfam_grow dummy";
    static final String GROW_ADOPT = "execute as @e[scores={mfam_grow=0..},tag=!mfam_grow_on] run tag @s add mfam_grow_on";
    private static final Pattern GROW_SET = Pattern.compile(
            "^execute as (.+) run scoreboard players set @s mfam_grow (\\d+)$");
    private static final String GROW_OLD_PREFIX_1 = "execute as @e[scores={mfam_grow=1..}] ";
    private static final String GROW_OLD_PREFIX_0 = "execute as @e[scores={mfam_grow=0}] ";
    private static final String GROW_OLD_LAST = "execute as @e[scores={mfam_grow=0}] run scoreboard players reset @s mfam_grow";
    static final List<String> GROW_AFTER_LAST = List.of(
            "execute as @e[tag=mfam_grow_on] unless score @s mfam_grow matches 0.. run tag @s remove mfam_grow_on",
            "scoreboard players add #sweep mfam_grow 1",
            "execute if score #sweep mfam_grow matches 200.. as @e[scores={mfam_grow=0..},tag=!mfam_grow_on] run tag @s add mfam_grow_on",
            "execute if score #sweep mfam_grow matches 200.. run scoreboard players set #sweep mfam_grow 0");

    /** {@code text} with a pre-#1871 grow or impulse function's victims tagged, or unchanged. */
    public static String fix(String text, List<String> notes) {
        String out = text;
        if (!out.contains("mfam_imp_on") && out.contains("mfam_imp")) {
            out = impulse(out, notes);
        }
        if (!out.contains("mfam_grow_on") && out.contains("mfam_grow")) {
            out = grow(out, notes);
        }
        return out;
    }

    private static String impulse(String text, List<String> notes) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String l : lines) {
            String s = l.strip();
            String indent = l.substring(0, l.length() - l.stripLeading().length());
            Matcher c = IMP_CATCH.matcher(s);
            Matcher land = IMP_LANDING.matcher(s);
            if (c.matches()) {
                out.add(indent + c.group(1) + c.group(2) + " run tag @s add mfam_impn");
                out.add(indent + "execute as @e[tag=mfam_impn] run scoreboard players set @s mfam_imp " + c.group(3));
                out.add(indent + "tag @e[tag=mfam_impn] add mfam_imp_on");
                out.add(indent + "tag @e[tag=mfam_impn] remove mfam_impn");
                changed = true;
            } else if (land.matches()) {
                out.add(indent + "execute as @e[tag=mfam_imp_on,scores={mfam_imp=" + land.group(1) + "}] at @s run "
                        + land.group(2));
                changed = true;
            } else if (s.startsWith(IMP_MOVE_OLD)) {
                out.add(indent + s.replace("@e[scores={mfam_imp=1..}]", "@e[tag=mfam_imp_on,scores={mfam_imp=1..}]"));
                changed = true;
            } else if (s.equals(IMP_COUNT_OLD)) {
                out.add(indent + s.replace("@e[scores={mfam_imp=1..}]", "@e[tag=mfam_imp_on,scores={mfam_imp=1..}]"));
                out.add(indent + IMP_END);
                changed = true;
            } else {
                out.add(l);
            }
        }
        if (!changed) {
            return text;
        }
        notes.add("impulse: its victims are tagged while they are pushed, so it no longer looks at every "
                + "entity each tick (#1871)");
        return String.join("\n", out);
    }

    private static String grow(String text, List<String> notes) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String l : lines) {
            String s = l.strip();
            String indent = l.substring(0, l.length() - l.stripLeading().length());
            Matcher set = GROW_SET.matcher(s);
            if (s.equals(GROW_RESET)) {
                out.add(l);
                out.add(indent + GROW_ADOPT);
                changed = true;
            } else if (set.matches()) {
                out.add(l);
                out.add(indent + "execute as " + set.group(1) + " run tag @s add mfam_grow_on");
                changed = true;
            } else if (s.equals(GROW_OLD_LAST)) {
                out.add(indent + s.replace(GROW_OLD_PREFIX_0, "execute as @e[tag=mfam_grow_on,scores={mfam_grow=0}] "));
                for (String a : GROW_AFTER_LAST) {
                    out.add(indent + a);
                }
                changed = true;
            } else if (s.startsWith(GROW_OLD_PREFIX_1)) {
                out.add(indent + s.replace(GROW_OLD_PREFIX_1, "execute as @e[tag=mfam_grow_on,scores={mfam_grow=1..}] "));
                changed = true;
            } else if (s.startsWith(GROW_OLD_PREFIX_0)) {
                out.add(indent + s.replace(GROW_OLD_PREFIX_0, "execute as @e[tag=mfam_grow_on,scores={mfam_grow=0}] "));
                changed = true;
            } else {
                out.add(l);
            }
        }
        if (!changed) {
            return text;
        }
        notes.add("grow: whoever is on the timer is tagged, so it no longer looks at every entity each tick; "
                + "anyone already on a timer is adopted (#1871)");
        return String.join("\n", out);
    }
}
