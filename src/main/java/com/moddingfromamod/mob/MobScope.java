package com.moddingfromamod.mob;

import java.util.List;
import java.util.Locale;

/**
 * <b>How many animals does he mean?</b> — one, the ones over there, or every one of them forever.
 *
 * <p>. Today nothing decides this: a category wish and a group wish produce byte-identical
 * behaviour, and an individual wish differs only by a count. The three readings are:
 *
 * <table border="1">
 *   <caption>the three readings</caption>
 *   <tr><th>he says</th><th>he means</th></tr>
 *   <tr><td>"make him bigger", "make that zombie bigger"</td><td>{@link Scope#ONE}</td></tr>
 *   <tr><td>"make all the zombies near me glow"</td><td>{@link Scope#GROUP} — a fixed set</td></tr>
 *   <tr><td>"make zombies bigger"</td><td>{@link Scope#CATEGORY} — a standing rule, forever</td></tr>
 * </table>
 *
 * <h2>The asymmetry, which is the whole design</h2>
 *
 * Guessing wrong is <b>not symmetric</b>. Reading a category wish as an individual merely decays —
 * he got something, and it stops meaning what he asked eventually. Reading an <i>individual</i> wish
 * as a category applies <b>a permanent global rule he never asked for</b>: "make him invincible"
 * about one pet, read wide, makes every zombie in the world invincible forever, and nothing in the
 * game shows him why.
 *
 * <p>So the tie-break is <b>narrow, and ask</b>. {@link Reading#scope()} is always the narrowest
 * honest reading, and {@link Reading#ask()} says whether the child should be asked before it is
 * acted on.
 *
 * <h2>Where this sits</h2>
 *
 * It is the <b>guard</b>, not the decider. {@code modify_mob} takes a required {@code scope}
 * argument — the model decides, explicitly, and the action log records what it decided — and this
 * runs over the child's own words to catch the one mistake that is expensive: an agent claiming a
 * wider scope than anything he said supports. A required argument cannot be silently skipped; a
 * check on his actual words cannot be talked around.
 *
 * <p>Pure — no Minecraft, no game types — so every phrasing below is a unit test.
 */
public final class MobScope {

    /** How wide the change is. Ordered narrow to wide; the order is load-bearing, see {@link #widerThan}. */
    public enum Scope {
        /** One animal, for as long as it exists. */
        ONE,
        /** The ones standing there when he said it — a fixed set. */
        GROUP,
        /** Every one of that kind, including the ones that do not exist yet. */
        CATEGORY;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parse what the agent passed. Null for anything we do not recognise — never a guess. */
        public static Scope of(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "one", "single", "individual", "him", "it" -> ONE;
                case "group", "these", "nearby" -> GROUP;
                case "category", "species", "kind", "all", "type" -> CATEGORY;
                default -> null;
            };
        }
    }

    /**
     * What his words read as.
     *
     * @param scope the <b>narrowest honest</b> reading — already tie-broken
     * @param ask   true when it could genuinely have been wider, so he should be asked
     * @param why   the phrase that decided it, for the log and for telling him what was assumed
     */
    public record Reading(Scope scope, boolean ask, String why) {
    }

    /** "here", in the ways a seven-year-old says it. Any of these makes a plural a fixed SET. */
    private static final List<String> LOCATIVE = List.of(
            "near me", "near by", "nearby", "around me", "around here", "round here", "over here",
            "right here", "next to me", "beside me", "in front of me", "by me", "here",
            "on the screen", "i can see", "in front of us");

    /** Demonstratives, which point at things present and therefore also mean a fixed set. */
    private static final List<String> DEMONSTRATIVE_PLURAL = List.of("these", "those");

    /** He is pointing at exactly one. */
    private static final List<String> SINGULAR_REFERENCE = List.of(
            "him", "her", "he ", "she ", "it ", " it", "that one", "this one", "that guy",
            "that thing", "my ");

    /** He means more than one, but has not said which. */
    private static final List<String> PLURAL_REFERENCE = List.of(
            "them", "they", "all of them", "both of them");

    /**
     * Determiners that pick out ONE thing when what follows is not a plural.
     *
     * <p>"that cow", "the zombie", "my dragon" — he is pointing, and there is nothing to weigh. The
     * first cut had only pronouns here, so "make that cow huge" fell through to the narrow DEFAULT
     * with a question attached, which reads the same but is not: an unsure reading is never refused,
     * so the guard let an agent claim the whole species about the one cow he pointed at.
     */
    private static final List<String> SINGULAR_DETERMINER = List.of(
            " that ", " this ", " the ", " a ", " an ", " my ", " our ", " your ");

    /** Words that make a claim about everything of a kind. */
    private static final List<String> UNIVERSAL = List.of("all ", "every ", "any ", "each ");

    /** Words ending in "s" that are not a plural animal, so they cannot vote for a category. */
    private static final List<String> NOT_A_PLURAL = List.of(
            "is", "his", "hers", "its", "this", "was", "yes", "us", "please", "does", "goes",
            "always", "less", "across", "glass", "grass", "class", "boss", "cross", "dress",
            "bigger's", "let's", "lets", "gets", "puts", "makes", "has");

    private MobScope() {
    }

    /**
     * Read the child's own sentence.
     *
     * <p>An empty or unreadable sentence reads as {@link Scope#ONE} <b>and asks</b>. That is the
     * narrow default doing its job: knowing nothing is not a licence to change the world forever.
     */
    public static Reading of(String text) {
        String t = text == null ? "" : " " + text.toLowerCase(Locale.ROOT).strip() + " ";
        if (t.isBlank()) {
            return new Reading(Scope.ONE, true, "he did not say");
        }
        // A handle names one exact animal and there is nothing to weigh.
        if (t.contains("@")) {
            return new Reading(Scope.ONE, false, "a handle names one exact animal");
        }

        boolean locative = containsAny(t, LOCATIVE);
        boolean demonstrative = containsAny(t, DEMONSTRATIVE_PLURAL);
        boolean universal = containsAny(t, UNIVERSAL);
        boolean pluralRef = containsAny(t, PLURAL_REFERENCE);
        String plural = pluralNoun(t);
        boolean definite = t.contains(" the ");

        // ── "the ones over there" — a fixed set, because he pointed at where they are ──────────
        if ((locative || demonstrative) && (plural != null || pluralRef || universal)) {
            return new Reading(Scope.GROUP, false,
                    demonstrative ? "\"these\"/\"those\" points at the ones standing there"
                                  : "he said where they are, so he means the ones there");
        }

        // ── a bare plural is GENERIC, and that is the whole distinction ────────────────────────
        //
        // "zombies are scary" is about zombies as a kind; "the zombies are scary" is about some
        // particular zombies. English does that with the article, and a child does it too, so it is
        // the one signal here that is grammar rather than a word list. A bare plural — with or
        // without "all"/"every" — is the category. A DEFINITE plural is genuinely either, and gets
        // the narrow reading plus a question.
        if (plural != null && !definite) {
            return new Reading(Scope.CATEGORY, false,
                    "\"" + plural + "\" with no \"the\" means the kind, not some of them");
        }
        if (plural != null) {
            return new Reading(Scope.GROUP, true,
                    "\"the " + plural + "\" could be the ones here or every one of them");
        }

        // ── "make them all invincible" — more than one, and no clue which ──────────────────────
        if (pluralRef || universal) {
            return new Reading(Scope.GROUP, true,
                    "he said more than one but not which ones");
        }

        // ── one animal ────────────────────────────────────────────────────────────────────────
        if (containsAny(t, SINGULAR_REFERENCE)) {
            return new Reading(Scope.ONE, false, "he said one of him/her/it/that one");
        }
        // A determiner with no plural anywhere in the sentence: "that cow", "the zombie". Reached
        // only after the plural branches above, so "the zombies" can never land here.
        if (containsAny(t, SINGULAR_DETERMINER)) {
            return new Reading(Scope.ONE, false, "he pointed at one of them");
        }
        // The narrow default. Being wrong this way decays; being wrong the other way is permanent.
        return new Reading(Scope.ONE, true, "nothing said how many, so the narrow reading");
    }

    /**
     * <b>Is the agent claiming a wider change than his words support?</b>
     *
     * <p>The one direction worth refusing. An agent that reads "make zombies bigger" as one zombie
     * merely under-delivers; an agent that reads "make him bigger" as a category rewrites the world.
     * So this is deliberately asymmetric, and it only fires when the words are <b>unambiguous</b> —
     * a sentence that could honestly have been either is the ask rung's business, not a refusal.
     *
     * @param claimed what {@code modify_mob} was asked to do
     * @param words   what {@link #of} made of the child's sentence
     */
    public static boolean overreaches(Scope claimed, Reading words) {
        if (claimed == null || words == null || words.ask()) {
            return false;
        }
        return widerThan(claimed, words.scope());
    }

    /** Strictly wider — ONE &lt; GROUP &lt; CATEGORY. */
    public static boolean widerThan(Scope a, Scope b) {
        return a != null && b != null && a.ordinal() > b.ordinal();
    }

    // ===================== the word work =====================

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first word that looks like a plural animal, or null.
     *
     * <p>Deliberately not a list of species. New mobs arrive with every mod the child installs, and
     * a classifier that only knows vanilla would read "make wither storms bigger" as one wither
     * storm — the expensive direction is the other way, but silently missing every modded creature
     * is its own kind of wrong. So this is a shape rule with an exception list, and the exception
     * list holds only words that end in "s" and are not animals.
     */
    static String pluralNoun(String text) {
        for (String raw : text.split("[^a-z0-9_']+")) {
            if (raw.length() < 4 || !raw.endsWith("s") || raw.endsWith("ss")) {
                continue;
            }
            if (NOT_A_PLURAL.contains(raw)) {
                continue;
            }
            return raw;
        }
        return null;
    }
}
