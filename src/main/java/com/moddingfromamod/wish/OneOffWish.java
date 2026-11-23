package com.moddingfromamod.wish;

import java.util.List;
import java.util.function.Predicate;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;

/**
 * Did this wish leave anything behind, or was it only ever alive while he watched? —.
 *
 * <p>Pure, so the rule is a unit test rather than something you need a granted wish to ask.
 *
 * <h2>What this is for</h2>
 *
 * <p>Found on a Spikor-fireball wish. The agent tried to author a CBScript datapack, the compile
 * hard-failed, and it <b>fell back to building the effect by hand with live commands</b>. The
 * effect appeared in the game and persisted <b>nothing</b>: {@code DatapackForge} only writes the
 * zip on the success branch, and the hand-built path calls neither {@code addAndSave} nor anything
 * else that saves. Confirmed against the world afterwards — {@code spikor_fireball} was in neither
 * the datapacks nor {@code learned.json}. It was gone on reload, and nobody had said so.
 *
 * <h2>The rule</h2>
 *
 * <p>A wish is one-off when it reached DONE and <b>nothing it made can be made again</b>. What
 * counts is what {@code WishRun.made()} recorded, minus two cases that look like persistence and
 * are not:
 *
 * <ul>
 *   <li>a {@link MadeKind#MOB} is a LIVE ENTITY, not a recipe. It dies, despawns, or is left
 *       behind in a world he stops playing. Counting it would call the commonest hand-built grant
 *       persistent.</li>
 *   <li>a {@link MadeKind#DATAPACK} whose zip is <b>not on disk</b> — which is exactly the failed
 *       compile above. The run recorded the intent; the artifact never existed.</li>
 * </ul>
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>Auto-persist the fallback. That was the original suggestion and product rejected it: the path
 * is untested, and in the case that prompted this it did not even produce the intended result — it
 * caused severe tick lag. Persisting an untested, possibly broken implementation is worse than
 * losing it. This detects and reports; deciding which of these SHOULD become templates is a later
 * question that needs the log this produces to answer at all.
 */
public final class OneOffWish {

    private OneOffWish() {}

    /**
     * Can the child have this again tomorrow?
     *
     * @param made          what the run recorded making
     * @param datapackOnDisk whether a datapack id actually has a zip — a failed compile records the
     *                       id and writes nothing
     */
    public static boolean isOneOff(List<WishRun.Made> made, Predicate<String> datapackOnDisk) {
        if (made == null || made.isEmpty()) {
            return true;                       // it ran commands and left no artifact at all
        }
        for (WishRun.Made m : made) {
            if (rerunnable(m, datapackOnDisk)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same, also counting a TAUGHT BUTTON.
     *
     * <p>{@code add_template} records no {@code made()} entry — it is a recipe, not an artifact —
     * so a wish granted by commands and then taught was judged one-off, and the child read "Ta-da!
     * I'll remember your Daytime Forever … just tap its button" followed a second later by "I can't
     * save it for next time". A learned template is exactly the "have it again" this rule asks
     * about. Reachable let "make it daytime forever" be granted by a gamerule.
     */
    public static boolean isOneOff(List<WishRun.Made> made, List<String> toolsUsed,
                                   Predicate<String> datapackOnDisk) {
        if (toolsUsed != null && toolsUsed.contains("add_template")) {
            return false;
        }
        return isOneOff(made, datapackOnDisk);
    }

    /**
     * The same, also counting a creature whose SAVED DESIGN the wish changed.
     *
     * <p>An edit of a creature he owns — "make my snake bigger", "make it blue", "turn my troll into
     * a snake" — records a live-mob handle or nothing at all in {@code made()}, so it read as "left
     * nothing behind" and he was told "I can't save it for next time" while the edit WAS saved to
     * the creature and follows him on his shelf. One edit is enough: he can have it again tomorrow.
     */
    public static boolean isOneOff(List<WishRun.Made> made, List<String> toolsUsed,
                                   List<WishRun.CreatureEdit> edited,
                                   Predicate<String> datapackOnDisk) {
        if (edited != null && !edited.isEmpty()) {
            return false;
        }
        return isOneOff(made, toolsUsed, datapackOnDisk);
    }

    /**
     * What to tell him when a wish changed a creature he keeps — the true version of the
     * line {@link #TELL_THE_CHILD} used to say wrongly. Null when nothing was edited. Pure.
     *
     * <ul>
     *   <li>re-sculpted into something else: <i>"Your Troll is now a Snake — saved, so it'll be
     *       like this next time too!"</i></li>
     *   <li>renamed: <i>"Your Wolf is now called Sky — saved, so …"</i></li>
     *   <li>changed, same name: <i>"Your Snake is saved like this — it'll be like this next time
     *       too!"</i></li>
     *   <li>someone else's (the library's, a brother's), so the change made HIS copy: <i>"This
     *       Spikor is your very own now — saved, so …"</i></li>
     * </ul>
     *
     * Several creatures in one wish are named together; a blank name is "creature".
     */
    public static String savedEditLine(List<WishRun.CreatureEdit> edited) {
        if (edited == null || edited.isEmpty()) {
            return null;
        }
        final String saved = " — saved, so it'll be like this next time too!";
        if (edited.size() > 1) {
            List<String> names = new java.util.ArrayList<>();
            for (WishRun.CreatureEdit e : edited) {
                String n = nameOf(e.after());
                if (!names.contains(n)) {
                    names.add(n);
                }
            }
            return "Your " + joinAnd(names) + " are saved like this — they'll be like this next "
                    + "time too!";
        }
        WishRun.CreatureEdit e = edited.get(0);
        String before = nameOf(e.before());
        String after = nameOf(e.after());
        if (e.forked()) {
            return "This " + after + " is your very own now" + saved;
        }
        if (!before.equalsIgnoreCase(after)) {
            return "Your " + before + " is now " + (e.reshaped() ? article(after) : "called " + after)
                    + saved;
        }
        return "Your " + after + " is saved like this — it'll be like this next time too!";
    }

    private static String nameOf(String n) {
        return n == null || n.isBlank() ? "creature" : n.trim();
    }

    /** "a Snake", "an Owl", "The Grinch" (one rule, {@code English.withArticle}). */
    static String article(String name) {
        return com.moddingfromamod.wishbuilder.English.withArticle(name, 1, true);
    }

    private static String joinAnd(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.get(names.size() - 1);
    }

    /** Is this one thing something the child can get back? */
    public static boolean rerunnable(WishRun.Made m, Predicate<String> datapackOnDisk) {
        if (m == null || m.kind() == null) {
            return false;
        }
        if (MadeKind.MOB.equals(m.kind())) {
            return false;                      // a live entity is not a recipe
        }
        if (MadeKind.DATAPACK.equals(m.kind())) {
            // The failed-compile case. The id was recorded, the zip was never written, and the
            // difference is invisible to anything that only reads the run.
            return datapackOnDisk != null && datapackOnDisk.test(m.id());
        }
        return true;                           // build, creature, item, painting, event
    }

    /**
     * Was this wish HELP rather than a new thing — a question, a complaint or a fix about an earlier
     * wish, or a run that changed nothing in the world?
     *
     * <p>Live, about a third of the "can't be saved" grants were "I can't see the creature you
     * made", "Spikor stopped following me", "I didn't get a map". Telling him those "can't be
     * saved" reads as a failure, and nobody wants a "fix my Spikor" button — so they say nothing
     * about saving (owner, D3).
     *
     * @param question     the wish reads as a question ({@code WishIntent.isQuestion})
     * @param followup     {@code plan_wish} said it is about something already made
     * @param changedWorld it ran a command that worked, made something, spawned something, or took
     *                     him somewhere
     */
    public static boolean isHelp(boolean question, boolean followup, boolean changedWorld) {
        return question || followup || !changedWorld;
    }

    /**
     * What to tell the child, in tone.
     *
     * <p>Must not read as a failure — the wish WORKED and he is watching it work. It says the thing
     * is one of a kind and NOW, which is true, rather than that something went wrong, which is
     * not. It used to say "SUPER special and tricky" — took the difficulty claim out.
     */
    public static final String TELL_THE_CHILD = WishWaitWords.ONE_OF_A_KIND;
}
