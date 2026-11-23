package com.moddingfromamod.datapack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Which tags does this program SELECT, and which does it ever put on anything?</b>
 *
 * <p>A datapack that filters on a tag nothing wears is not broken in any way a compiler, a loader
 * or a test runner can see. It compiles, it loads, its functions are real, and running them
 * succeeds — {@code execute as @e[tag=spikor] run …} with no matches is a perfectly successful
 * no-op. It is simply, permanently, inert.
 *
 * <p>That is not hypothetical. Every ready-made ability in the catalogue substitutes a tag into its
 * selectors, and for a long while {@code apply_effect} substituted the bare species id — so an
 * ability given to a creature tagged {@code wishmob_spikor} installed a clock selecting
 * {@code @e[tag=spikor]}. The CBScript compiled, the pack loaded, {@code verify_datapack} passed,
 * and the agent told the child it had worked. A 195-second screen recording of that world contains
 * zero frames with a flame particle in them.
 *
 * <p>The rule this class exists to make checkable is general, and is worth stating in the form that
 * would apply to any datapack: <b>a selector tag that nothing in the program ever introduces, and
 * that the program's host does not promise to provide, is dead.</b> The host's promise has to be
 * declared, because a pack legitimately selects things it did not tag itself — a wish creature
 * wears {@code wishmob_<species>} put there by the mod, not by the pack.
 *
 * <p>Pure and Minecraft-free, so the sweep over the whole catalogue is a unit test rather than a
 * game.
 */
public final class SelectorTags {

    private SelectorTags() {
    }

    /** {@code tag=foo} and {@code tag=!foo} inside a selector. */
    private static final Pattern SELECTED = Pattern.compile("tag=!?([A-Za-z0-9_.+-]+)");

    /** {@code tag @e[…] add foo} — the ordinary way a pack marks something. */
    private static final Pattern ADDED =
            Pattern.compile("\\btag\\s+@\\S+\\s+add\\s+([A-Za-z0-9_.+-]+)");

    /**
     * {@code Tags:["foo","bar"]} — the other way, and the one a naive rule misses.
     *
     * <p>{@code lingering_zone} summons its own marker armour stand carrying
     * {@code Tags:["mfam_zone_<tag>"]} and then selects it on the next tick. Without this the
     * sweep would report that tag as dead, which is both wrong and exactly the kind of false
     * alarm that gets a check switched off.
     */
    private static final Pattern NBT_TAGS = Pattern.compile("Tags:\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** Every tag this program filters on. */
    public static Set<String> selected(String source) {
        return all(SELECTED, source);
    }

    /** On a veiled creature's carrier while a camera photographs it ({@code RigVeilService}). */
    public static final String VEILED_TAG = "mfam_veiled";

    /** On each player that veil hides from: a veiled creature's particles and sounds skip him. */
    public static final String BLIND_TAG = "mfam_veil_blind";

    /**
     * Tags the MOD puts on entities at runtime, which the effect templates select on. Never dead:
     * nothing wears them between wishes by design, and reporting them would tell the agent
     * "SELECTS NOBODY" about every ability it installs — for machinery it must not need to know
     * about.
     */
    public static final Set<String> HOST_TAGS = Set.of(VEILED_TAG, BLIND_TAG,
            // A creature's hit boxes, which every victim-picking effect leaves out (chunk 2).
            EffectVictims.PROXY_TAG);

    /** Every tag this program puts on something, however it does it. */
    public static Set<String> introduced(String source) {
        Set<String> out = all(ADDED, source);
        if (source != null) {
            Matcher lists = NBT_TAGS.matcher(withoutComments(source));
            while (lists.find()) {
                Matcher each = QUOTED.matcher(lists.group(1));
                while (each.find()) {
                    out.add(each.group(1));
                }
            }
        }
        return out;
    }

    /**
     * Tags this program selects that nothing will ever wear.
     *
     * @param provided tags the HOST promises to put on entities — for a wish ability, the tags
     *                 {@code WishMobEntity} really applies. Anything outside this and the
     *                 program's own tagging is a selector that cannot match.
     */
    public static Set<String> dead(String source, Set<String> provided) {
        Set<String> out = new LinkedHashSet<>(selected(source));
        out.removeAll(introduced(source));
        out.removeAll(provided);
        out.removeAll(HOST_TAGS);
        return out;
    }

    /**
     * Drop whole-line comments before reading anything.
     *
     * <p>These templates are heavily commented, and the comments discuss selectors — {@code
     * on_death} explains a trap using {@code @e[tag=X,nbt={DeathTime:1s}]} as its example. A rule
     * that cannot tell an instruction from an explanation of one reports {@code X} as a dead tag
     * and is switched off within the week.
     *
     * <p>Only whole-line comments, deliberately: a {@code #} mid-line is not reliably a comment in
     * a command, and guessing there would start silently dropping real selectors, which is the
     * failure direction that matters.
     */
    private static String withoutComments(String source) {
        return source.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static Set<String> all(Pattern p, String source) {
        Set<String> out = new LinkedHashSet<>();
        if (source == null) {
            return out;
        }
        Matcher m = p.matcher(withoutComments(source));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
