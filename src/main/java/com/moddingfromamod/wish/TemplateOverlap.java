package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which templates would this new one compete with?</b>
 *
 * <p>Two learned templates that share a matching word both answer a wish containing it, and which
 * one wins is not something the child can see or the agent intended. Measured over five real
 * grants of ONE wish — "build me a machine that crushes rocks and puts them in a chest" — the world
 * ended with <b>three</b> templates all matching {@code crush} and {@code crusher}:
 *
 * <pre>
 *   rock_crusher_chest_wish   [crusher, crush, rock crusher, grind rocks, crushes rocks]
 *   rock_crusher_chest_build  [crusher, crush, grind, millstone]
 *   rock_crusher_chest        [crusher, crush, rock crusher, millstone]
 * </pre>
 *
 * <p><b>The lesson was already right, and already followed.</b> {@code ListTemplatesTool} says to
 * widen an existing template with the SAME id rather than teach a rival, and the runs that called
 * it did exactly that — 2 of 2 reused the id. The two that minted rivals were the two that never
 * called it. Calling it is optional; calling {@code add_template} is not. So the check belongs
 * here, on the path that always runs.
 *
 * <p><b>It reports, and deliberately does not refuse.</b> Neither real rival was a subset of what
 * already existed — each added one genuinely new word — so no crisp rule separates "a rival" from
 * "a legitimate neighbour", and the only rules that would have caught them are tuned thresholds.
 * This project keeps unpicking those. A named overlap the agent can act on is worth more than a
 * threshold that is wrong in both directions.
 *
 * <p>Pure and Minecraft-free, so the rule is unit-tested without booting a game.
 */
public final class TemplateOverlap {

    private TemplateOverlap() {
    }

    /** One existing template this new one would compete with, and the words they both claim. */
    public record Rival(String id, List<String> sharedTerms) {
    }

    /**
     * Templates whose terms overlap {@code newTerms}, most-overlapping first.
     *
     * <p>{@code newId} is excluded: re-teaching the same id is the WANTED behaviour — it is what
     * the lesson asks for — and reporting a template's overlap with itself would turn the one
     * correct move into a warning.
     */
    public static List<Rival> rivals(String newId, Iterable<String> newTerms,
                                     Map<String, ? extends Iterable<String>> existing) {
        Set<String> mine = normalise(newTerms);
        List<Rival> out = new ArrayList<>();
        if (mine.isEmpty() || existing == null) {
            return out;
        }
        String self = newId == null ? "" : newId.strip().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ? extends Iterable<String>> e : existing.entrySet()) {
            String id = e.getKey();
            if (id == null || id.strip().toLowerCase(Locale.ROOT).equals(self)) {
                continue;
            }
            List<String> shared = new ArrayList<>();
            for (String term : normalise(e.getValue())) {
                if (mine.contains(term)) {
                    shared.add(term);
                }
            }
            if (!shared.isEmpty()) {
                out.add(new Rival(id, List.copyOf(shared)));
            }
        }
        out.sort((a, b) -> {
            int bySize = Integer.compare(b.sharedTerms().size(), a.sharedTerms().size());
            return bySize != 0 ? bySize : a.id().compareTo(b.id());
        });
        return List.copyOf(out);
    }

    /**
     * The sentence the agent reads, or empty when nothing competes.
     *
     * <p>Names the id to reuse rather than only stating the problem: the fix for a rival is one
     * more {@code add_template} with that id, and a message that does not say which id is a
     * message that needs a second tool call to act on.
     */
    public static String describe(List<Rival> rivals) {
        if (rivals == null || rivals.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n⚠ This competes with ");
        sb.append(rivals.size() == 1 ? "an existing template" : rivals.size() + " existing templates");
        sb.append(": ");
        for (int i = 0; i < rivals.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append('\'').append(rivals.get(i).id()).append("' also matches ")
              .append(String.join(", ", rivals.get(i).sharedTerms()));
        }
        sb.append(". A wish containing those words now matches more than one template, and which "
                + "wins is not something you chose. If you meant to WIDEN one, call add_template "
                + "again with id '").append(rivals.get(0).id())
          .append("' and the full set of words — same id replaces, it does not duplicate.");
        return sb.toString();
    }

    private static Set<String> normalise(Iterable<String> terms) {
        Set<String> out = new LinkedHashSet<>();
        if (terms == null) {
            return out;
        }
        for (String t : terms) {
            if (t == null) {
                continue;
            }
            String n = t.strip().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
