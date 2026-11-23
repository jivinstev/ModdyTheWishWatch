package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ranks registry ids against a child's words. Pure — no Minecraft imports — so the one rule that
 * really matters can be tested without a game, or an installed mod, in front of it.
 *
 * <p><b>The rule: modded content outranks vanilla.</b> When a child says "wither storm" they mean
 * the boss they have seen on YouTube, not {@code minecraft:wither}. If a mod supplies the real
 * thing, it must be the first thing the agent sees, because the agent will reasonably act on the
 * first plausible hit. This existed as a comment inside a registry loop; a comment cannot fail a
 * build, which is why the ranking now lives here with tests around it.
 *
 * <p>Matching is deliberately forgiving in one direction only. All the query's words must appear
 * somewhere in the id for a strong hit ("wither storm" → {@code witherstormmod:wither_storm},
 * because the id contains both "wither" and "storm"). A partial first-word match is kept as a weak
 * hit so a near miss still shows up rather than the agent being told nothing exists.
 */
public final class ContentSearch {

    private ContentSearch() {
    }

    /**
     * @param ids   registry ids as {@code namespace:path} strings
     * @param query the child's words, e.g. "wither storm"
     * @param limit maximum results
     * @return matches, modded first, each group alphabetical — stable, so tests can't flake
     */
    public static List<String> rank(Iterable<String> ids, String query, int limit) {
        List<String> modded = new ArrayList<>();
        List<String> vanillaOrWeak = new ArrayList<>();
        if (ids == null || query == null) {
            return List.of();
        }
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            String full = raw.toLowerCase(Locale.ROOT);
            int colon = full.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : full.substring(0, colon);
            String path = colon < 0 ? full : full.substring(colon + 1);

            if (containsAll(full, words)) {
                // The whole point: a mod's version of the thing goes to the front of the list.
                (namespace.equals("minecraft") ? vanillaOrWeak : modded).add(raw);
            } else if (words.length > 1 && !words[0].isEmpty() && wholeWord(path, words[0])) {
                vanillaOrWeak.add(raw);
            }
        }
        modded.sort(String::compareTo);
        vanillaOrWeak.sort(String::compareTo);
        modded.addAll(vanillaOrWeak);
        return modded.size() > limit ? List.copyOf(modded.subList(0, limit)) : List.copyOf(modded);
    }

    /**
     * Does one id answer to these words? Used by {@code modify_mob} to pick "the wither storm" out
     * of the mobs standing around the child, with the same forgiving matching {@code find_content}
     * uses — so a word that found the mob will also select it.
     *
     * <p>An empty query matches everything, which is what makes {@code target} optional: "just
     * change the nearest thing".
     */
    public static boolean matches(String id, String query) {
        if (id == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        return containsAll(id.toLowerCase(Locale.ROOT),
                query.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+"));
    }

    /**
     * <b>Does this word stand on its own in the path, rather than sit inside another one?</b>
     *
     * <p>THE MULTI-WORD FALLBACK NEEDS THIS, and it needed it badly. A query that matches
     * nothing falls back to its FIRST word, which was tested with {@code path.contains(word)} —
     * a plain substring. Measured in a live wish: a child asked for a machine that makes BUTT
     * CHEEK WATER, the mod providing it was not installed, and {@code find_content} answered
     * with THIRTEEN WOODEN BUTTONS ({@code acacia_button}, {@code bamboo_button}, …), because
     * every one of them contains "butt". The agent read that as a hit list and built a
     * lookalike, which was handed to the child as the thing he had asked for.
     *
     * <p>Deliberately NOT applied to {@link #containsAll}, and that is the judgement here. A
     * single-word query is meant to be forgiving — {@code grind} should still find
     * {@code grinder} — and requiring whole words there would break the fuzzy matching this
     * tool exists for. What is indefensible is answering a THREE-word query by matching a
     * fragment of ONE of them: that is not a near miss, it is a different subject returned with
     * the same confidence as a real result.
     */
    static boolean wholeWord(String path, String word) {
        if (path == null || word == null || word.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = path.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            int before = at - 1;
            int after = at + word.length();
            if ((before < 0 || !Character.isLetterOrDigit(path.charAt(before)))
                    && (after >= path.length() || !Character.isLetterOrDigit(path.charAt(after)))) {
                return true;
            }
            from = at + 1;
        }
    }
    private static boolean containsAll(String haystack, String[] words) {
        boolean sawOne = false;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            sawOne = true;
            if (!haystack.contains(w)) {
                return false;
            }
        }
        return sawOne;
    }
}
