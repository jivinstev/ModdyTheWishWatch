package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>What Moddy says when a world's rules shape a wish</b> — one place, like
 * {@code SafetyWords}, so the locked pattern is written once:
 *
 * <blockquote><i>heard you → I know how → the world's rule and why → where it works (a world with no rules)
 * → something you can do instead.</i></blockquote>
 *
 * The rule and its reason come from the rules FILE (a reviewed PR); the suggestion is the grader's
 * one short line, used only after it passes the text sweep, with a fixed fallback. Every line here
 * is a template plus those two, so nothing the model wrote reaches a child unswept. No line
 * mentions a price: a refusal never costs anything.
 */
public final class WorldRulesWords {

    private WorldRulesWords() {
    }

    /** When the grader offered nothing usable. */
    static final String FALLBACK_SUGGESTION = "Here, try a wish for your own island — a creature, a build or a gift!";

    /** A clearly world-wide wish, refused at pickup. */
    public static String refusal(WorldRule rule, WorldRules rules, String suggestion) {
        String instead = suggestion == null || suggestion.isBlank() ? FALLBACK_SUGGESTION : "Here, how about: " + suggestion;
        return "✨ I heard your wish, and I know just how to do it — but here, " + ruleWords(rule) + ". "
                + "It works in " + rules.workshop() + ", so wish it there! " + instead;
    }

    /** A mixed wish: the allowed part is being granted, the rest explained. */
    public static String split(String part, WorldRule rule, WorldRules rules) {
        return "✨ I'll grant the part I can here: " + part + ". The rest I can't — here, " + ruleWords(rule)
                + ". That part works in " + rules.workshop() + "!";
    }

    /** A wish whose big words are clamped to his own base. */
    public static String clamp(WorldRule rule, WorldRules rules) {
        return "✨ I'll make it on your own island! Here, " + ruleWords(rule)
                + " — the whole-world version works in " + rules.workshop() + ".";
    }

    /**
     * A make with no agent (a Builder or Apprentice make, a replayed wish) that a world rule refused, said in place of
     * "didn't work this time": the rule and its why from the rules file, and what he can do. A rule about where the
     * wish was aimed asks him to aim it at his own place; a rule about what it would change says where it works.
     *
     * @param subject what was refused, as the sentence's subject ("Your Cozy House", "That wish")
     */
    public static String madeRefused(String subject, WorldRefusal why) {
        String what = subject == null || subject.isBlank() ? "That wish" : subject.strip();
        if (why.aboutPlace()) {
            return what + " can't go there: " + ruleWords(why.rule()) + ". Aim it at your own " + why.noun()
                    + " and wish again!";
        }
        return what + " can't work on this world: " + ruleWords(why.rule()) + ". It works in " + why.workshop() + "!";
    }

    /** "time is the same for every island — so the sun rises for everyone together" */
    static String ruleWords(WorldRule rule) {
        if (rule == null) {
            return "a wish stays on your own island";
        }
        String r = lowerFirst(rule.rule());
        String w = rule.why();
        return w.isBlank() ? r : r + " — " + lowerFirst(w);
    }

    private static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.strip();
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        // Keep a leading proper noun ("Pebble Bay …", any two title-case words, or Moddy): only lower an ordinary
        // first word.
        String[] words = t.split("\\s+", 3);
        boolean namePair = words.length > 1 && words[1].length() > 1
                && Character.isUpperCase(words[1].charAt(0)) && Character.isLowerCase(words[1].charAt(1));
        // "A wish stays …" is an ordinary first word too, though only one letter long ("I" is not).
        boolean article = t.startsWith("A ") && words.length > 1 && !words[1].isEmpty()
                && Character.isLowerCase(words[1].charAt(0));
        if (article) {
            return "a" + t.substring(1);
        }
        if (t.length() > 1 && Character.isUpperCase(t.charAt(0)) && Character.isLowerCase(t.charAt(1))
                && !namePair && !t.startsWith("Moddy")) {
            return Character.toLowerCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    // ── what the AGENT reads about this one wish ─────────────────────────────────────────────────

    static String splitNote(String part, Set<WorldEffect> denied) {
        return "WORLD RULES — THIS WISH WAS SPLIT: the child has been told you are granting only this part: \""
                + part + "\". Build only that, on his own base. The rest (" + words(denied)
                + ") is not allowed on this world and he has already been told why — do not do it and do "
                + "not apologise for it again.";
    }

    static String clampNote(Set<WorldEffect> denied) {
        return "WORLD RULES — CLAMP IT TO HIS BASE: his words reach further than his own base (" + words(denied)
                + "), and he has been told it will be made on his own island. Read 'all', 'every', "
                + "'everywhere', 'forever' and 'the whole world' as 'on his island' and build it there only.";
    }

    static String unsureNote(Set<WorldEffect> denied) {
        return "WORLD RULES — UNSURE: the world-rules check could not tell whether this wish reaches past "
                + "his own base" + (denied.isEmpty() ? "" : " (" + words(denied) + ")") + ". Grant it on his "
                + "own base only. If it truly needs the whole world, do not do that part — tell him, kindly, "
                + "that it works in Moddy's Workshop, and offer something for his island instead.";
    }

    private static String words(Set<WorldEffect> effects) {
        List<String> out = new ArrayList<>();
        for (WorldEffect e : WorldEffect.values()) {
            if (effects.contains(e)) {
                out.add(e.words());
            }
        }
        return String.join(", ", out);
    }
}
