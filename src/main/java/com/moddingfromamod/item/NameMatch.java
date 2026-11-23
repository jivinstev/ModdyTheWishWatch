package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>What a child means when he names one of his own things — the pure rule, so that one rule serves
 * every class that has to answer it.</b>
 *
 * <p>. "Give me that one again" has to work for a sword and for a dragon, and the two live in
 * different registries ({@link WishItemCatalogue} and {@code SpeciesRegistry}) with different key
 * shapes. The lookup is identical in both and the CLAUDE.md rule that keeps paying applies before
 * the second copy exists rather than after: <i>when you fix a cap, a guardrail or an oracle in one
 * wish class, grep the others for the same shape</i>. So the rule is here, Minecraft-free and unit
 * tested, and both registries call it.
 *
 * <h2>Why it returns EVERY match at one tier rather than a best guess</h2>
 *
 * <p>A key is not something a child can type — {@code id:<feature>} for anything made,
 * {@code name#icon#nbt} for a keepsake made before it — and two of his things may genuinely share a
 * name, which is the whole reason a key is not the name. So the caller gets the matches and asks
 * him, because handing back the wrong sword <i>silently</i> is the failure worth avoiding and the
 * one a "closest match" would produce.
 *
 * <h2>🔴 Why it stops at the tightest tier that matched</h2>
 *
 * <p>Exact key, then exact name, then a loose contains — and the first tier with anything in it
 * wins outright. Without that, a child with a <i>wand</i> and a <i>wand of fire</i> could never ask
 * for the first: "wand" matches both under contains, so the answer to a perfectly unambiguous
 * question would be "which do you mean?" forever. The tiers are not a ranking to merge, they are a
 * question about how precisely he named it.
 */
public final class NameMatch {

    private NameMatch() {
    }

    /**
     * One thing that could be meant.
     *
     * @param key  the registry key — precise, and not something a child types
     * @param name what he calls it
     */
    public record Candidate(String key, String name) {
    }

    /**
     * @return the INDEXES into {@code candidates} that matched, at the tightest tier that matched
     *         anything; empty when nothing did. Indexes rather than values so the two registries
     *         can map back to their own row type without this class knowing either.
     */
    public static List<Integer> resolve(List<Candidate> candidates, String query) {
        if (candidates == null || candidates.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String want = query.trim().toLowerCase(Locale.ROOT);
        List<Integer> exactKey = new ArrayList<>();
        List<Integer> exactName = new ArrayList<>();
        List<Integer> loose = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c == null) {
                continue;
            }
            String key = c.key() == null ? "" : c.key().toLowerCase(Locale.ROOT);
            String name = c.name() == null ? "" : c.name().toLowerCase(Locale.ROOT);
            if (key.equals(want)) {
                exactKey.add(i);
            } else if (name.equals(want)) {
                exactName.add(i);
            } else if (!want.isEmpty() && (name.contains(want) || key.contains(want))) {
                loose.add(i);
            }
        }
        if (!exactKey.isEmpty()) {
            return List.copyOf(exactKey);
        }
        return List.copyOf(exactName.isEmpty() ? loose : exactName);
    }
}
