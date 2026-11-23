package com.moddingfromamod.wish;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A sound id has ONE colon</b> (the phoenix): {@code minecraft:entity.phoenix.ambient}, never
 * {@code minecraft:entity:phoenix:ambient}. The colon spelling is not a quiet miss: Minecraft refuses
 * the whole FUNCTION it sits in at pack load ("Invalid ID … playsound"), so a phoenix datapack's
 * rebirth never loaded and the wish reported DONE. 4 of 501 archived grant runs spelled it that way.
 *
 * <p>CORRECTED, NOT REFUSED, for the reason {@link AttributeIds} gives: the colon spelling is never
 * valid, the fix is mechanical, and a sound id that was already valid is left byte for byte, so
 * correcting cannot change what a working command does. Only the id slot of {@code playsound} and
 * {@code stopsound} is touched, the one place the token is certainly a sound. Pure: no Minecraft.
 * <ul>
 *   <li>{@code ns:a:b:c} becomes {@code ns:a.b.c} — the first colon is the namespace's;</li>
 *   <li>{@code entity:pig:ambient}, with a vanilla sound ROOT where a namespace would be and more
 *       colons after it, becomes {@code minecraft:entity.pig.ambient};</li>
 *   <li>upper case becomes lower case (a resource location is lower case only).</li>
 * </ul>
 * A macro ({@code $(…)}) is left alone: what it becomes is only known at run time.
 */
public final class SoundIds {

    private SoundIds() {
    }

    /** The first word of every vanilla sound path: what a model writes where the namespace goes. */
    static final Set<String> ROOTS = Set.of("ambient", "block", "enchant", "entity", "event", "item",
            "music", "music_disc", "particle", "ui", "weather", "intentionally_empty");

    private static final Pattern PLAYSOUND = Pattern.compile("(?i)(\\bplaysound\\s+)(\\S+)");
    private static final Pattern STOPSOUND = Pattern.compile("(?i)(\\bstopsound\\s+\\S+\\s+\\S+\\s+)(\\S+)");

    /** The text with every sound id this rule can fix fixed; each fix is added to {@code notes}. */
    public static String correct(String text, List<String> notes) {
        if (text == null || !text.toLowerCase(java.util.Locale.ROOT).contains("sound")) {
            return text;
        }
        return fix(STOPSOUND, fix(PLAYSOUND, text, notes), notes);
    }

    private static String fix(Pattern p, String text, List<String> notes) {
        Matcher m = p.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String was = m.group(2);
            String now = corrected(was);
            if (now != null && !now.equals(was)) {
                String note = "the sound '" + was + "' to '" + now + "'";
                if (!notes.contains(note)) {
                    notes.add(note);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + (now == null ? was : now)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The one valid spelling of {@code id}, or null to leave the author's own text. */
    static String corrected(String id) {
        if (id.contains("$(") || id.equals("*")) {
            return null;
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (!lower.matches("[a-z0-9_.:/-]+")) {
            return null;
        }
        String[] parts = lower.split(":", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;                       // "a::b" — not a mistake this rule understands
            }
        }
        if (parts.length <= 2) {
            return lower;                          // already one colon (or none): only the case can change
        }
        if (ROOTS.contains(parts[0])) {
            return "minecraft:" + String.join(".", parts);
        }
        return parts[0] + ":" + String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length));
    }
}
