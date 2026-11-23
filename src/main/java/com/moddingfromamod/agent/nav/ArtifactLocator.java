package com.moddingfromamod.agent.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Which thing he already made does he mean?</b> — the matching half of {@code take_me_to}.
 *
 * <h2>The wish this exists for</h2>
 * From real play, a wish to be taken to the closest McTrain. The agent called {@code list_trains},
 * got the right answer — {@code mctrain — "The McTrain": 4 carriage(s), last built at 622, 0, 654} —
 * and then built a <b>second</b> McTrain at the child's feet, 2,900 blocks from the one he meant.
 * His next wish said that was not the one he meant.
 *
 * <p>Nothing was missing. {@code tp} is on the command allowlist, the coordinates were in hand. The
 * flow is shaped entirely around <i>manifesting</i> something, so a wish whose answer is <i>move
 * the child</i> had no verb in that shape and the agent reached for the one it had.
 *
 * <p><b>2 of the 9 distinct train wishes the child has ever typed are navigation</b> — about a
 * fifth of the class's traffic — and it is not trains-specific: <i>"take me to my castle"</i>,
 * <i>"where's my dragon"</i> are the same shape.
 *
 * <h2>Why the matching is fuzzy, and bounded</h2>
 * A child names a thing the way he remembers it — "the McTrain", "mctrain", "my mcburgers train" —
 * so exact-id matching alone answers almost none of the real phrasings. The order is deliberate and
 * is the same one {@code /food} settled on for the same reason: exact id, then exact name, then a
 * contained-word match, and <b>ambiguity is reported rather than guessed</b>. Teleporting a child
 * to the wrong artifact is worse than asking which he meant, because he arrives somewhere plausible
 * and has no way to tell it is wrong.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class ArtifactLocator {

    /** One thing the child has made, flattened out of whichever registry owns it. */
    public record Artifact(String kind, String id, String name, int x, int y, int z,
                           String dimension) {

        /** How the child would hear it named. */
        public String describe() {
            return "\"" + name + "\" (" + kind + " '" + id + "') at " + x + ", " + y + ", " + z;
        }
    }

    /** What the query picked out: exactly one thing, nothing, or several equally good. */
    public record Match(Artifact best, List<Artifact> ambiguous, String miss) {

        public boolean found() {
            return best != null;
        }

        public boolean isAmbiguous() {
            return best == null && ambiguous != null && !ambiguous.isEmpty();
        }
    }

    private ArtifactLocator() {
    }

    /**
     * Pick the artifact the child means, or say honestly that it cannot be picked.
     *
     * @param query    what he called it, in his own words
     * @param known    everything he has made that has a recorded position
     */
    public static Match resolve(String query, List<Artifact> known) {
        if (known == null || known.isEmpty()) {
            return new Match(null, List.of(), "nothing has been built in this world yet");
        }
        if (query == null || query.isBlank()) {
            // ONE thing built is not ambiguous even with no query — "take me to it" is answerable.
            return known.size() == 1 ? new Match(known.get(0), List.of(), "")
                    : new Match(null, new ArrayList<>(known), "he did not say which one");
        }
        String q = normalise(query);

        List<Artifact> byId = where(known, a -> normalise(a.id()).equals(q));
        if (byId.size() == 1) {
            return new Match(byId.get(0), List.of(), "");
        }
        List<Artifact> byName = where(known, a -> normalise(a.name()).equals(q));
        if (byName.size() == 1) {
            return new Match(byName.get(0), List.of(), "");
        }
        // Contained either way: he says "the McTrain" for `mctrain`, and "mcburgers" for
        // "The McBurgers Train". Both are how a child actually refers to a thing he named.
        List<Artifact> loose = where(known,
                a -> normalise(a.id()).contains(q) || q.contains(normalise(a.id()))
                        || normalise(a.name()).contains(q) || q.contains(normalise(a.name())));
        if (loose.size() == 1) {
            return new Match(loose.get(0), List.of(), "");
        }
        if (loose.size() > 1) {
            return new Match(null, loose, "more than one thing matches that");
        }
        return new Match(null, List.of(), "nothing he has made is called that");
    }

    /**
     * Does this wish ask to be TAKEN to something, rather than to have one made?
     *
     * <p>The guard asks for: <i>"build one here too"</i> names an existing artifact and is a
     * manifesting wish, so naming a thing is not enough on its own. This looks for the movement,
     * and then refuses when the sentence also asks for something to be made.
     *
     * <p>Deliberately advisory. The tool is a teleport, so a wrong call costs one teleport and not
     * a spurious second artifact — the failure this whole issue is about is the other direction.
     */
    public static boolean asksToBeTakenThere(String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String said = normalise(childWords);
        boolean movement = false;
        for (String phrase : new String[] {
                "take me", "teleport me", "tp me", "bring me", "go to", "get me to",
                "where is", "wheres", "where's", "find it", "show me where", "back to"}) {
            if (said.contains(normalise(phrase))) {
                movement = true;
                break;
            }
        }
        if (!movement) {
            return false;
        }
        // "build one here too" / "make another one over here" — a manifesting wish that also names
        // an artifact. Movement words plus a making verb is not a request to travel.
        for (String making : new String[] {
                "build", "make", "another", "one here", "new one", "again here", "copy"}) {
            if (said.contains(normalise(making))) {
                return false;
            }
        }
        return true;
    }

    private static List<Artifact> where(List<Artifact> all,
                                        java.util.function.Predicate<Artifact> p) {
        List<Artifact> out = new ArrayList<>();
        for (Artifact a : all) {
            if (p.test(a)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Lower-case, and strip everything that is not a letter or digit.
     *
     * <p>So "The McTrain", "mctrain", "mc_train" and "MC Train" are one word. The same
     * normalisation {@code /food} needed to make "big bun", "big_bun" and "bigbun" resolve, and for
     * the same reason: it is how a child types.
     */
    static String normalise(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
