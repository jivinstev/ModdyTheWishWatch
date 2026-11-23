package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Which entity types does this program select on, and would the selector survive if the thing it
 * names were rendered some other way?</b>
 *
 * <p>The sibling of {@link SelectorTags}, one column across. {@code SelectorTags} asks whether a
 * {@code tag=} filter can ever match; this asks the same question of a {@code type=} filter, and it
 * exists because the answer stopped being obvious the moment a wished thing could be shown to the
 * child as something other than the entity the mod registered.
 *
 * <p><b>The failure is the same silence, and it is NOT a missing id.</b> A server hosting a stock
 * client still runs the mod, so {@code moddingfromamod:wish_mob} is registered and
 * {@code @e[type=moddingfromamod:wish_mob]} parses, loads, and runs every tick. What changes is
 * what the thing IS. If a wished creature is shown on some other base, that selector matches
 * nothing — no error, no log line, no failed compile, and the ability is simply inert. A worker
 * looking for an unresolvable id finds none and reads that as a pass.
 *
 * <h2>Why a rewrite is NOT offered here, unlike the attribute and NBT-case repairs</h2>
 *
 * <p>{@code AttributeIds} and the compiler's {@code nbt_case} rewrite a token because the wrong form
 * <em>can never be right on this version</em> — that is the qualifying test in
 * {@code docs/DATAPACKS.md}. A mod entity type fails it: {@code type=moddingfromamod:wish_mob} is
 * correct today and selects exactly the right creatures. There are two further reasons, and the
 * second is the dangerous one:
 *
 * <ul>
 *   <li>the obvious substitute is not equivalent. Every wish creature is tagged {@code wishmob} by
 *       {@code WishMobEntity.applySpecies} — but that method returns early when the species is
 *       unknown, so a speciesless {@code wish_mob} carries no tag at all and
 *       {@code type=} is strictly the wider filter;</li>
 *   <li><b>deleting the conjunct can widen a selector to everything in the world.</b>
 *       {@code kill @e[tag=wishmob_spikor,type=moddingfromamod:wish_mob]} survives losing its type
 *       filter; {@code kill @e[type=moddingfromamod:wish_mob]} becomes {@code kill @e}. Whether the
 *       edit is safe depends on a <em>different conjunct in the same selector</em>, which is exactly
 *       the "unambiguous" criterion that rewrite is required to meet and this does not.</li>
 * </ul>
 *
 * <p>So this class classifies and reports. It never edits.
 *
 * <p>Pure and Minecraft-free, so a sweep over the whole corpus is a unit test rather than a game.
 */
public final class SelectorTypes {

    /** The namespace whose entity types are the mod's own, and therefore substitutable. */
    public static final String MOD_NS = "moddingfromamod:";

    private SelectorTypes() {
    }

    /**
     * How a selector would fare if the entity it names were shown on some other base.
     *
     * @param entityType     the id named, e.g. {@code moddingfromamod:wish_mob}
     * @param negated        {@code true} for {@code type=!…} — an EXCLUSION rather than a search
     * @param narrowedByTag  whether the same selector also filters on a {@code tag=}, which the mod
     *                       applies to the entity rather than the entity type carrying it. When
     *                       true the type filter is redundant today and removable; when false it is
     *                       the only thing narrowing the selector and cannot simply be dropped.
     * @param selector       the selector body it was found in, for a message a person can act on
     */
    public record ModTypeUse(String entityType, boolean negated, boolean narrowedByTag,
                             String selector) {

        /**
         * What the child loses, phrased as a symptom rather than as code.
         *
         * <p>The two polarities fail in opposite directions and that is the whole reason they are
         * distinguished: a positive filter that stops matching makes the ability do <em>nothing</em>,
         * while an exclusion that stops excluding makes it do something to the thing it was
         * carefully written to spare.
         */
        public String symptom() {
            if (negated) {
                return "an exclusion that stops excluding: whatever this was written to spare — "
                        + "usually the creature itself, or its friends — is caught by its own "
                        + "effect. The ability still fires, on the wrong things.";
            }
            return narrowedByTag
                    ? "nothing happens: the ability runs every tick and matches no one. The tag in "
                    + "the same selector already identifies the creature, so this filter is "
                    + "removable without changing what it means today."
                    : "nothing happens, and this filter is the ONLY thing narrowing the selector — "
                    + "removing it would match every entity in the world, so it needs a tag "
                    + "rather than a deletion.";
        }
    }

    /** {@code type=foo} / {@code type=!foo} inside one selector body. */
    private static final Pattern TYPE = Pattern.compile("type=(!?)([A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+)");

    /** A tag filter the mod, not the entity type, is responsible for putting on the entity. */
    private static final Pattern POSITIVE_TAG = Pattern.compile("(?<![!])\\btag=(?!!)([A-Za-z0-9_.+-]+)");

    /**
     * Every mod entity type this program filters on.
     *
     * <p>Comments are dropped first, for {@link SelectorTags}' reason: these templates discuss
     * selectors in prose, and a rule that cannot tell an instruction from an explanation of one
     * gets switched off within the week. The corpus contains a comment reading
     * <i>"a define selector can't parse a namespaced type (the colon in moddingfromamod:wish_mob)"</i>
     * — a sentence about the problem, counted as an instance of it by any rule that skips this.
     */
    public static List<ModTypeUse> modTypeUses(String source) {
        List<ModTypeUse> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (String body : selectorBodies(withoutComments(source))) {
            boolean tagged = POSITIVE_TAG.matcher(body).find();
            Matcher m = TYPE.matcher(body);
            while (m.find()) {
                if (m.group(2).startsWith(MOD_NS)) {
                    out.add(new ModTypeUse(m.group(2), !m.group(1).isEmpty(), tagged, body));
                }
            }
        }
        return out;
    }

    /** True if this program selects on any mod entity type at all. */
    public static boolean any(String source) {
        return !modTypeUses(source).isEmpty();
    }

    /**
     * The bodies of every {@code @a}/{@code @e}/{@code @p}/{@code @r}/{@code @s} selector.
     *
     * <p>Scanned by bracket DEPTH rather than matched with {@code \[([^\]]*)\]}, because a selector
     * may legally hold a nested list — {@code @e[nbt={Tags:["x"]}]} — and a non-greedy regex stops
     * at the inner {@code ]}, silently truncating the body. That would drop a {@code tag=} sitting
     * after the nested part and misreport a removable filter as a load-bearing one. The real corpus
     * happens to contain zero nested cases today, which is exactly why this would never have been
     * noticed had it been written the cheap way.
     */
    private static List<String> selectorBodies(String source) {
        List<String> out = new ArrayList<>();
        Matcher open = Pattern.compile("@[aeprs]\\[").matcher(source);
        while (open.find()) {
            int depth = 1;
            int i = open.end();
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i++);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                }
            }
            if (depth == 0) {
                out.add(source.substring(open.end(), i - 1));
            }
        }
        return out;
    }

    /** Whole-line comments only — see {@link SelectorTags} for why never mid-line. */
    private static String withoutComments(String source) {
        return source.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
