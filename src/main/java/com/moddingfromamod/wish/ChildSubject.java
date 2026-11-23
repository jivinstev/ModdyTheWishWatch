package com.moddingfromamod.wish;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * <b>Who a child-facing sentence is ABOUT, in the words the child knows</b>.
 *
 * <p>The effect catalogue's {@code child_when} sentences carry a {@code ${TAG}} placeholder, and
 * it used to be filled with the creature's selector tag. For a Builder or Apprentice make that tag
 * is an id ({@code ember_the_flying_dragon_24a470}), so the 👉 line read
 * <i>"ember_the_flying_dragon_24a470 breathes it out of its MOUTH"</i>. The TAG belongs in the
 * selectors a command runs; the SENTENCE gets the creature's name.
 *
 * <p>Pure: the species lookup is handed in, so the rule is unit-tested without a world. The name
 * comes from, in order: the species' display name; else the tag's own words with any id suffix
 * dropped and underscores spaced; else {@code "it"} — never a guessed creature (see
 * {@link BuiltAbility} for why a wrong subject is worse than a vague one).
 */
public final class ChildSubject {

    /** The placeholder the catalogue's child sentences use. */
    public static final String PLACEHOLDER = "${TAG}";

    /** The tag every wish creature wears, and the prefix of its per-species tag. */
    private static final String WISHMOB = "wishmob";

    /** An id suffix: {@code _} and six hex digits, at least one of them a digit (so "facade" is a word). */
    private static final Pattern ID_SUFFIX = Pattern.compile("_(?=[0-9a-f]{0,5}[0-9])[0-9a-f]{6}$");

    private ChildSubject() {
    }

    /**
     * The creature a tag stands for, as a child would say it, or {@code null} when the tag names
     * no one creature (blank, the shared {@code wishmob} tag, our own {@code mfam…} machinery).
     *
     * @param speciesName a species id → its display name, or null when unknown
     */
    public static String name(String tag, Function<String, String> speciesName) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String id = tag.strip();
        if (id.equalsIgnoreCase(WISHMOB) || id.toLowerCase(Locale.ROOT).startsWith("mfam")) {
            return null;
        }
        if (id.toLowerCase(Locale.ROOT).startsWith(WISHMOB + "_")) {
            id = id.substring(WISHMOB.length() + 1);
        }
        String known = speciesName == null ? null : speciesName.apply(id);
        if (known == null && speciesName != null && !id.equals(id.toLowerCase(Locale.ROOT))) {
            known = speciesName.apply(id.toLowerCase(Locale.ROOT));
        }
        if (known != null && !known.isBlank()) {
            return known.strip();
        }
        String words = words(id);
        return words.isEmpty() ? null : words;
    }

    /**
     * An id said as words: its id suffix dropped and underscores spaced —
     * {@code cozy_house_3fa2b1} → {@code cozy house}, {@code sky_castle} → {@code sky castle}.
     * For any thing with no display name of its own (a build, an event), never just for creatures.
     */
    public static String words(String id) {
        return id == null ? "" : ID_SUFFIX.matcher(id.strip()).replaceFirst("").replace('_', ' ').strip();
    }

    /**
     * A catalogue sentence with its {@code ${TAG}} filled by the creature's name ({@code "it"}
     * when there is none), and its first letter a capital — several sentences open with the subject.
     */
    public static String say(String childWhen, String tag, Function<String, String> speciesName) {
        if (childWhen == null) {
            return "";
        }
        String who = name(tag, speciesName);
        String out = childWhen.replace(PLACEHOLDER, who == null ? "it" : who).strip();
        if (!out.isEmpty() && Character.isLowerCase(out.charAt(0))) {
            out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        }
        return out;
    }
}
