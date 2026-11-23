package com.moddingfromamod.datapack;

import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>: an on_step or on_land pack compiled before its reach counted from the creature's side, put
 * right on its way into a world</b> — the move {@link ImpulseTelegraphFix} and {@link VictimTagFix} made.
 *
 * <p>Since, {@code apply_effect} adds the creature's drawn half width to a step's or a landing's
 * RADIUS, so a big stomper reaches what is beside it rather than only what is under its middle. A pack
 * is compiled once, when it is wished, so that fix reaches no creature that already has one. Its INSTALL
 * copy is rewritten here instead: the victim selector's {@code distance=..R} becomes R plus the half
 * width of the species its tag names, and gains {@code tag=!mfam_flank}, the no-op marker every new
 * render carries, so a pack compiled after the fix is never widened twice. The stored zip is untouched,
 * so every install (one at every boot) rewrites from the original: idempotent. A species nobody knows
 * leaves the line as it was. Pure but for the width it is handed.
 */
public final class ReachFromBodyFix {

    /** What a render carries in its victim selector; a pack without it is pre-fix. */
    public static final String MARKER = "tag=!mfam_flank";

    /** The victim line of on_step (mfam_stepw) and on_land (mfam_landair), pre-marker. */
    private static final Pattern VICTIMS = Pattern.compile(
            "^(\\s*execute as @e\\[tag=([A-Za-z0-9_.+-]+),[^\\]]*scores=\\{mfam_(?:stepw|landair)=[^}]*}] at @s as @e\\[distance=\\.\\.)"
                    + "([0-9]+(?:\\.[0-9]+)?)(,tag=!\\2)(.*)$");

    private ReachFromBodyFix() {
    }

    /** {@code text} with a pre-#2505 step or landing reach counted from the creature's side, or unchanged. */
    public static String fix(String text, List<String> notes, ToDoubleFunction<String> halfWidthOfSpecies) {
        if (!text.contains("mfam_stepw") && !text.contains("mfam_landair")) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(MARKER)) {
                continue;
            }
            Matcher m = VICTIMS.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            String tag = m.group(2);
            String species = tag.startsWith("wishmob_") ? tag.substring("wishmob_".length()) : tag;
            double half;
            try {
                half = halfWidthOfSpecies.applyAsDouble(species);
            } catch (RuntimeException e) {
                half = 0.0;
            }
            if (!(half > 0.0)) {
                continue;
            }
            double r = Double.parseDouble(m.group(3)) + half;
            lines[i] = m.group(1) + String.format(java.util.Locale.ROOT, "%.1f", r) + m.group(4) + "," + MARKER + m.group(5);
            changed = true;
        }
        if (!changed) {
            return text;
        }
        notes.add("#2505: a step or landing's reach counted from the creature's side");
        return String.join("\n", lines);
    }
}
