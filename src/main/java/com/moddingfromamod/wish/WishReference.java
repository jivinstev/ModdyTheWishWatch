package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a wish <b>refers to something already in the world</b> — "make it bigger", "make
 * them glow purple", "do it again".
 *
 * <p>Such a wish must never be answered from the offline template cache, however well the keywords
 * match. A template is a fixed list of commands; it cannot know what "it" is. Serving one would
 * confidently do the wrong thing, instantly, and the child would watch the wrong creature change.
 * So these always go to the agent, which can look at the handles and decide.
 *
 * <h2>This class never resolves anything</h2>
 * It answers one question — <i>does this need a mind?</i> — and is deliberately biased towards
 * saying yes, because a false positive costs a few seconds of latency while a false negative gives
 * a wrong answer. Working out <i>what</i> "it" means is the agent's job, and stays that way.
 *
 * <h2>The trap: a pronoun alone means nothing</h2>
 * Three of the flagship built-in wishes contain "it" and are not contextual at all — <i>"make it
 * rain chickens"</i>, <i>"make it night and spooky"</i>, <i>"make it sunny"</i>. There "it" means
 * the world, and the wish says exactly what to do. Gating on pronouns alone would push the most-used
 * wishes in the whole mod through a slow agent round-trip and break the thing that makes the watch
 * feel instant.
 *
 * <p>So a wish is contextual only when a pronoun is paired with a <b>change word</b> — a comparative
 * or a colour, something that modifies an existing thing rather than naming a new one. "make it
 * rain chickens" names chickens; "make it bigger" names nothing at all.
 *
 * <p>Pure — no Minecraft imports — so the whole policy is unit-tested against the real built-in
 * wish list.
 */
public final class WishReference {

    /** Words that point at something already there. Matched whole, never as substrings. */
    private static final Set<String> PRONOUNS = Set.of(
            "it", "its", "them", "they", "their", "him", "his", "her", "hers",
            "that", "those", "these", "this", "one");

    /**
     * Words that <b>modify</b> rather than name. A pronoun plus one of these is a change to an
     * existing thing, which is exactly what a fixed template cannot do.
     */
    private static final Set<String> CHANGE_WORDS = Set.of(
            // comparatives
            "bigger", "smaller", "larger", "tinier", "huger", "taller", "shorter",
            "faster", "slower", "stronger", "tougher", "weaker", "harder", "easier",
            "brighter", "darker", "louder", "quieter", "better", "worse", "cooler",
            "scarier", "nicer", "angrier", "friendlier", "prettier",
            // appearance changes
            "glow", "glowing", "shiny", "invisible",
            // colours — "make it pink" is a change, not a new thing
            "red", "blue", "green", "pink", "purple", "yellow", "orange", "black",
            "white", "rainbow", "gold", "golden", "cyan", "magenta", "grey", "gray");

    /**
     * Words that make a wish contextual on their own, with or without a pronoun — they refer to a
     * previous wish rather than to a thing.
     */
    private static final Set<String> BACK_REFERENCES = Set.of(
            "again", "another", "instead", "undo", "back", "same", "more");

    /**
     * Words that <b>attach</b> something to a thing that already exists.
     *
     * <p>A gap that cost a real wish. <i>"that's a cool castle in the sky, can you add a lighthouse
     * on top of it?"</i> has a pronoun but no comparative and no colour, so it read as a brand-new
     * wish and a learned castle template rebuilt the whole castle — instead of adding a lighthouse
     * to the one he was looking at and was proud of.
     *
     * <p>Like {@link #CHANGE_WORDS} these only count alongside a pronoun. "add a lighthouse to
     * <b>it</b>" points at something; "build a castle with a lighthouse" describes one thing to
     * make from scratch and stays perfectly cacheable.
     */
    private static final Set<String> ATTACH_WORDS = Set.of(
            "add", "adds", "attach", "put", "stick", "place", "onto", "atop",
            "top", "beside", "next", "inside", "under", "above", "below", "behind",
            "front", "around", "into", "remove", "delete", "move");

    /**
     * the pronouns that point at a THING or a creature — never "that"/"this"/"one", which
     * also introduce a NEW thing ("a sword THAT glows", "a dog that sparkles"). The broader rules
     * below fire only on these.
     */
    private static final Set<String> OBJECT_PRONOUNS = Set.of("it", "him", "her", "them");

    /**
     * change words that only count beside {@link #OBJECT_PRONOUNS} — how a thing LOOKS or
     * MOVES, and comparatives a child uses for "more of the same". Kept apart from
     * {@link #CHANGE_WORDS} because "that" + "sparkle" is how a new thing is described.
     */
    private static final Set<String> OBJECT_CHANGE_WORDS = Set.of(
            "sparkle", "sparkles", "sparkly", "sparkling", "glitter", "glittery", "shine", "shiny",
            "shimmer", "flash", "flashing", "higher", "lower", "farther", "further", "longer",
            "wider", "heavier", "lighter", "smarter", "cuter", "meaner", "spikier", "fluffier");

    /**
     * verbs that, as the FIRST word of a wish and followed by an object pronoun, act on a
     * thing that is already there — "give it wings", "teach him to fly", "turn her blue". Not
     * "make" (see {@link #leadsWithAPronoun}) and not "let" ("let it snow").
     */
    private static final Set<String> LEAD_VERBS = Set.of(
            "give", "teach", "turn", "paint", "dress", "upgrade", "change", "feed", "train",
            "name", "rename", "call", "shrink", "grow", "colour", "color", "equip", "arm",
            "help", "fix", "send", "bring", "tell", "get");

    /**
     * What "make it ___" means when "it" is the WORLD, not a thing: weather, time of day, a season,
     * a mood. "make it rain chickens", "make it night and spooky", "make it sunny" must stay instant.
     */
    private static final Set<String> WORLD_STATES = Set.of(
            "rain", "raining", "rainy", "snow", "snowing", "snowy", "storm", "stormy", "thunder",
            "thundering", "lightning", "windy", "foggy", "cloudy", "sunny", "sun", "night",
            "nighttime", "midnight", "day", "daytime", "morning", "evening", "noon", "dark",
            "light", "bright", "sunset", "sunrise", "dawn", "dusk", "spooky", "halloween",
            "christmas", "winter", "summer", "spring", "autumn", "fall", "hot", "cold", "be",
            "so", "always", "forever", "peaceful", "easy", "hard", "hardcore", "creative",
            "survival", "a", "an", "rains", "snows");

    /** Weather verbs: "when IT rains" is the world, so that "it" points at nothing. */
    private static final Set<String> WEATHER_VERBS = Set.of(
            "rains", "raining", "snows", "snowing", "storms", "thunders", "pours", "rained");

    /** Words a child puts before the verb that change nothing ("now give it…", "can you give it…"). */
    private static final Set<String> LEAD_FILLER = Set.of(
            "now", "and", "also", "then", "ok", "okay", "so", "please", "pls", "plz", "hey",
            "moddy", "can", "could", "would", "will", "you", "u", "i", "want", "wish", "to", "just",
            "oh", "yes", "yay", "wow", "cool", "awesome");

    private WishReference() {
    }

    /**
     * Does this wish refer to something that already exists?
     *
     * @return true when only an agent can answer it, so the offline cache must be skipped
     */
    public static boolean isContextual(String wish) {
        if (wish == null || wish.isBlank()) {
            return false;
        }
        String[] words = wish.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        boolean pronoun = false;
        boolean objectPronoun = false;
        boolean worldIt = false;
        boolean described = false;
        for (int i = 0; i + 1 < words.length; i++) {
            if ("it".equals(words[i]) && WEATHER_VERBS.contains(words[i + 1])) {
                worldIt = true;
            }
        }
        boolean change = false;
        boolean objectChange = false;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (BACK_REFERENCES.contains(w)) {
                return true;
            }
            if (PRONOUNS.contains(w)) {
                pronoun = true;
            }
            if ("a".equals(w) || "an".equals(w)) {
                described = true;     // "a dog that sparkles when IT runs" describes a new thing
            }
            if (OBJECT_PRONOUNS.contains(w) && !(worldIt && "it".equals(w)) && !described) {
                objectPronoun = true;
            }
            if (CHANGE_WORDS.contains(w) || ATTACH_WORDS.contains(w)) {
                change = true;
            }
            if (OBJECT_CHANGE_WORDS.contains(w)) {
                objectChange = true;
            }
        }
        if (describesANewThing(words)) {
            // item 3: "a <made-up name> that makes me float up INTO the air when i touch IT"
            // read as a change to something he owns ("that"/"it" + the attach word "into"), so the
            // agent was told IT might be one of his things; with his world's item rules beside it,
            // Opus built a magic block every time (0/4 in the routing arm, 4/4 without the banner).
            // A wish that opens by naming a new thing and a relative clause is describing THAT thing,
            // and its pronouns point back at it. BACK_REFERENCES above still win.
            return false;
        }
        return (pronoun && change) || (objectPronoun && objectChange) || leadsWithAPronoun(words);
    }

    /** Words a child puts before the new thing he names: "make me a…", "i want a…", "can i have a…". */
    private static final Set<String> ASK_WORDS = Set.of(
            "make", "give", "get", "build", "create", "spawn", "summon", "have", "need", "me", "us");

    /**
     * Does the wish OPEN by naming a new thing with a relative clause — "a dog that…", "make me an
     * owl which…", "i want a robot who…"? Then the whole wish describes that new thing. Only the
     * opening counts: "add a lighthouse on top of it" opens with a verb that acts on something, and
     * "a hat on top of it" has no relative clause, so both still point back. Pure.
     */
    static boolean describesANewThing(String[] words) {
        int i = 0;
        while (i < words.length && (words[i].isEmpty() || LEAD_FILLER.contains(words[i])
                || ASK_WORDS.contains(words[i]))) {
            i++;
        }
        if (i >= words.length || !("a".equals(words[i]) || "an".equals(words[i]))) {
            return false;
        }
        // The thing's name is one to three words ("a floaty pink bunny that…").
        for (int j = i + 2; j < Math.min(words.length, i + 5); j++) {
            if ("that".equals(words[j]) || "which".equals(words[j]) || "who".equals(words[j])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does the wish OPEN by acting on "it"/"him"/"her"/"them" — "give it wings", "teach him to
     * fly", "make him huge"?
     *
     * <p>"make it …" alone is NOT enough, because "make it rain chickens", "make it night" and
     * "make it sunny" are the flagship instant wishes and their "it" is the weather. So "make" counts
     * only with an animate pronoun (him/her/them); "make it" needs a change word, which
     * {@link #isContextual} checks separately.
     */
    static boolean leadsWithAPronoun(String[] words) {
        int i = 0;
        while (i < words.length && (words[i].isEmpty() || LEAD_FILLER.contains(words[i]))) {
            i++;
        }
        if (i + 1 >= words.length) {
            return false;
        }
        String verb = words[i];
        String obj = words[i + 1];
        if (!OBJECT_PRONOUNS.contains(obj)) {
            return false;
        }
        if ("make".equals(verb)) {
            // "make it rain/night/sunny…" is the WORLD, and those are the instant flagship wishes.
            return !"it".equals(obj) || (i + 2 < words.length && !WORLD_STATES.contains(words[i + 2]));
        }
        return LEAD_VERBS.contains(verb);
    }

    /**
     * {@link #isContextual}, or a change to something he owns NAMED with "the"/"my" — "make the
     * black hole pull harder". {@code ownedNames} are what his things are called (names and
     * aliases, any case); a name counts when it appears as whole words. Pure.
     */
    public static boolean isContextual(String wish, java.util.Collection<String> ownedNames) {
        if (isContextual(wish)) {
            return true;
        }
        if (wish == null || ownedNames == null || ownedNames.isEmpty() || !mentionsAChangeOrMore(wish)) {
            return false;
        }
        String t = " " + String.join(" ", wish.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) + " ";
        for (String name : ownedNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String n = String.join(" ", name.toLowerCase(Locale.ROOT).replace('_', ' ')
                    .split("[^a-z0-9]+")).strip();
            if (n.length() < 3) {
                continue;
            }
            // The whole name, and its head noun ("bouncy cloud" → "cloud", "sky castle" → "castle"):
            // a child says "the cloud", not the name the agent gave it.
            int sp = n.lastIndexOf(' ');
            List<String> forms = sp > 0 && n.length() - sp - 1 >= 4
                    ? List.of(n, n.substring(sp + 1)) : List.of(n);
            for (String form : forms) {
                for (String det : List.of(" the ", " my ", " his ", " her ", " that ", " our ")) {
                    if (t.contains(det + form + " ")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Do these words LOOK like they point back at something — {@link #isContextual}, or a change to
     * "the/my &lt;something&gt;" whether or not he owns it ("make the black hole pull harder" when he
     * has no black hole)? Looser than {@link #isContextual} on purpose: it never routes a wish, it only
     * decides whether the agent is reminded to be honest when it finds nothing, and a
     * reminder on "make the sky darker" costs a sentence.
     */
    public static boolean pointsBack(String wish) {
        if (isContextual(wish)) {
            return true;
        }
        if (wish == null || !mentionsAChangeOrMore(wish)) {
            return false;
        }
        String t = " " + String.join(" ", wish.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) + " ";
        return t.contains(" the ") || t.contains(" my ");
    }

    /** {@link #mentionsAChange}, or one of the object change words ("bounce me HIGHER"). */
    private static boolean mentionsAChangeOrMore(String wish) {
        if (mentionsAChange(wish)) {
            return true;
        }
        for (String w : wish.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (OBJECT_CHANGE_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does this sentence <b>change</b> something rather than name something new?
     *
     * <p>Exposed because a pronoun is not the only way to point at a thing. <i>"Make the stone tower
     * bigger"</i> has no pronoun at all, and until this existed it went to the offline template
     * matcher, matched on the word "tower", and <b>built him a second tower</b> — which is precisely
     * the failure this class exists to prevent, arriving by the one door it was not watching.
     *
     * <p>Found by a real client test, not by reasoning: the ladder resolved it perfectly and never
     * ran, because the wish was granted before it got there.
     */
    public static boolean mentionsAChange(String wish) {
        if (wish == null || wish.isBlank()) {
            return false;
        }
        // DESTROYING a thing is the most literal way of acting on one he already owns, and the
        // vocabulary for it lives in WishVerb because that is what decides whether a wish is
        // irreversible. The two lists shared only "remove" and "delete", so "get rid of the
        // dragon", "destroy my castle" and "kill the big one" were never contextual at all: they
        // skipped the resolver, went to the offline matcher, and the ladder's ASK rung — the one
        // that exists to stop before doing something irreversible to the wrong thing — could not
        // be reached by any phrasing a child actually uses for it.
        //
        // Asked rather than copied, so the two can never drift apart again.
        if (com.moddingfromamod.marshal.WishVerb.of(wish)
                == com.moddingfromamod.marshal.WishVerb.Kind.DESTRUCTIVE) {
            return true;
        }
        for (String w : wish.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (CHANGE_WORDS.contains(w) || CHANGE_VERBS.contains(w)
                    || BACK_REFERENCES.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The <b>verbs</b> out of {@link #ATTACH_WORDS}, and only those.
     *
     * <p>The positional half — "around", "top", "next", "front" — only ever meant anything ALONGSIDE
     * a pronoun. On its own "a giant robot buddy that stomps <b>around</b>" is a description of a
     * brand-new thing, and treating it as a change is how the most ordinary wish in the log would
     * have been diverted off the instant path.
     */
    private static final Set<String> CHANGE_VERBS = Set.of(
            "add", "adds", "attach", "put", "stick", "place", "remove", "delete", "move",
            "paint", "colour", "color");
}
