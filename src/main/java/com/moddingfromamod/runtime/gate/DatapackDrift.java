package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>What a re-authored datapack stopped doing.</b>
 *
 * <p>The datapacks class's {@link ItemDrift}, and the class that needed it most, because a datapack
 * has <b>no appearance at all</b>. A castle that loses a tower looks different; a wand that stops
 * doing lightning is at least in his hand to try. A rule that stopped happening is indistinguishable
 * from a rule he never asked for, until the night it does not rain.
 *
 * <h2>Measured, not supposed</h2>
 * On the {@code raincod} ladder a worker that had not written the script re-authored it and the
 * program SHRANK — 1979 characters to 1387, losing both the night condition and the explosion; 2461
 * to 1378; 2433 to 974. It is not editing, it is re-deriving the whole program from the wish in
 * front of it and writing the minimum that satisfies that wish. Clauses come back as well as go: one
 * cell lost the night condition at rung 4 and recovered it at rung 5 while losing the explosion.
 *
 * <p><b>Two information fixes did not touch it.</b> A read-back tool ({@code list_datapacks} with a
 * feature id) — called, and a clause dropped anyway. Then the rules listed unasked in the handover
 * with their size — no movement, identical continuity median. The agent knows what is there. This is
 * a generation problem, and the reason this gate refuses rather than only warns.
 *
 * <h2>What counts as "stopped doing"</h2>
 * Three markers, chosen by looking at the real before/after pairs rather than guessing:
 *
 * <ul>
 *   <li><b>resource ids</b> — {@code minecraft:cod}, {@code minecraft:explosion_emitter}. Losing one
 *       is losing the thing itself.</li>
 *   <li><b>command verbs</b> — {@code /scoreboard}, {@code /particle}. This is what catches a
 *       condition: the night check lived in a scoreboard and has no id of its own.</li>
 *   <li><b>declared functions</b> — {@code function explodecheck()}. A whole named behaviour.</li>
 * </ul>
 *
 * <p>Deliberately NOT a diff of the text. A rewrite that says the same thing differently is fine and
 * common; what is not fine is the program no longer mentioning a mob, a command or a routine it used
 * to. Every one of the measured losses shows up in all three markers at once, and none of the
 * legitimate edits did.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class DatapackDrift {

    private static final Pattern RESOURCE_ID =
            Pattern.compile("\\b[a-z][a-z0-9_]*:[a-z0-9_./]+");
    private static final Pattern COMMAND = Pattern.compile("/([a-z_]+)");
    private static final Pattern FUNCTION = Pattern.compile("function\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Words that mean he asked for something to come OFF. Same list ItemDrift settled on. */
    private static final String[] REMOVAL = {
        "remove", "take away", "get rid", "stop", "no longer", "without",
        "delete", "undo", "instead of", "not any more", "no more", "cancel", "only",
    };

    private DatapackDrift() {
    }

    /**
     * What this re-author dropped, or {@code ""} when nothing worth refusing did.
     *
     * @param before     the script the feature is running now; null for a brand-new feature
     * @param after      the script about to replace it
     * @param childWords the wish being granted — something he asked to remove is never reported
     */
    public static String between(String before, String after, String childWords) {
        if (before == null || after == null || before.isBlank()) {
            return "";                       // nothing to lose yet
        }
        if (removalRequested(childWords)) {
            return "";
        }
        List<String> lostIds = missing(RESOURCE_ID, before, after, 0);
        List<String> lostCommands = missing(COMMAND, before, after, 1);
        List<String> lostFunctions = missing(FUNCTION, before, after, 1);
        if (lostIds.isEmpty() && lostCommands.isEmpty() && lostFunctions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("⚠ THIS SCRIPT STOPPED DOING THINGS THE OLD ONE DID.\n");
        if (!lostIds.isEmpty()) {
            sb.append("  gone: ").append(String.join(", ", lostIds)).append('\n');
        }
        if (!lostCommands.isEmpty()) {
            sb.append("  no longer uses: ").append(prefixed(lostCommands)).append('\n');
        }
        if (!lostFunctions.isEmpty()) {
            sb.append("  functions removed: ").append(String.join(", ", lostFunctions)).append('\n');
        }
        sb.append("He did not ask for any of that to stop. `cbscript` is the WHOLE program, not a "
                + "patch — send everything it already did, PLUS the new part. Read it back first "
                + "with list_datapacks if you need it.\n"
                + "He cannot see this by looking: a rule that quietly stopped happening is "
                + "identical, from where he is standing, to a rule he never asked for.");
        return sb.toString();
    }

    /** Everything the old script had under this pattern and the new one does not. */
    private static List<String> missing(Pattern p, String before, String after, int group) {
        Set<String> now = matches(p, after, group);
        List<String> lost = new ArrayList<>();
        for (String s : matches(p, before, group)) {
            if (!now.contains(s)) {
                lost.add(s);
            }
        }
        return lost;
    }

    private static Set<String> matches(Pattern p, String text, int group) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group(group));
        }
        return out;
    }

    private static String prefixed(List<String> commands) {
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            out.add("/" + c);
        }
        return String.join(", ", out);
    }

    /**
     * Did he ask for something to come off?
     *
     * <p>Narrow, for the R5 reason this repo has paid for twice: a silencer keyed on the child's
     * vocabulary can mute the exact case it exists for. {@code "only"} is on the list because
     * <i>"can it only do it at night"</i> genuinely is a request to stop it happening the rest of
     * the time, and refusing that would fire on a correct edit.
     */
    static boolean removalRequested(String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String text = childWords.toLowerCase(Locale.ROOT);
        for (String word : REMOVAL) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
