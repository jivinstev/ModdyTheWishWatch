package com.moddingfromamod.content;

import com.moddingfromamod.wishbuilder.BuilderSuggest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>{@code find_content} with {@code around: true}</b> (§3): only what was in his view and his bag when he
 * WISHED, ranked by how much of it there was — "which stone does he have" answers his andesite, never a random stone.
 * A query narrows it by the same search {@code find_content} always uses ({@link ContentIndex}); no query lists it all.
 * Pure: the reading is the run's ({@link BuilderSuggest.Seen}), the names the index's.
 */
public final class AroundSearch {

    private AroundSearch() {
    }

    /** What to answer when there is no reading (a wish with no wish-time snapshot, or no wish at all). */
    public static final String NO_VIEW = "Nothing was read from his view when he wished (no wish is being granted, or it"
            + " came from somewhere with no view). Search without around: true.";

    public static String answer(BuilderSuggest.Seen seen, String query, ContentIndex index, int limit) {
        if (seen == null) {
            return NO_VIEW;
        }
        Set<String> matching = null;
        if (query != null && !query.isBlank()) {
            matching = new HashSet<>();
            for (ContentIndex.Hit h : index.search(query, Set.of(ContentIndex.Kind.BLOCK, ContentIndex.Kind.ITEM), 4000)) {
                matching.add(h.entry().id());
            }
        }
        Map<String, Integer> blocks = new LinkedHashMap<>(seen.inView());
        if (seen.crosshair() != null) {
            blocks.putIfAbsent(seen.crosshair(), 0);
        }
        List<String> b = keep(BuilderSuggest.lookingAt(new BuilderSuggest.Seen(seen.crosshair(), blocks, Map.of()), x -> true),
                matching, limit);
        List<String> c = keep(BuilderSuggest.carrying(seen, x -> true), matching, limit);
        StringBuilder sb = new StringBuilder();
        if (!b.isEmpty()) {
            sb.append("BLOCKS IN HIS VIEW WHEN HE WISHED (crosshair first, then most; n of the 45 rays across his view) — ")
              .append(b.size()).append(" found:\n");
            for (String id : b) {
                sb.append("  ").append(id).append(" — ").append(name(index, id))
                  .append(id.equals(seen.crosshair()) ? " — at his crosshair" : "")
                  .append(blocks.getOrDefault(id, 0) > 0 ? " — " + blocks.get(id) + " of 45" : "").append('\n');
            }
            sb.append('\n');
        }
        if (!c.isEmpty()) {
            sb.append("THINGS HE CARRIED WHEN HE WISHED (most first) — ").append(c.size()).append(" found:\n");
            for (String id : c) {
                sb.append("  ").append(id).append(" — ").append(name(index, id)).append(" — ×")
                  .append(seen.carrying().get(id)).append('\n');
            }
            sb.append('\n');
        }
        if (sb.length() == 0) {
            if (matching != null && (!blocks.isEmpty() || !seen.carrying().isEmpty())) {
                // "which stone does he have": andesite is not NAMED stone, so the words found nothing — he still
                // means something he had. Everything he had, so the agent picks from it rather than guessing.
                return "Nothing in his view or his bag is NAMED \"" + query + "\" — here is everything that was, pick"
                        + " the one he means:\n\n" + answer(seen, null, index, limit);
            }
            return "Nothing in his view or his bag when he wished matches \"" + (query == null ? "" : query)
                    + "\". Search again without around: true for the whole game.";
        }
        return sb.toString();
    }

    private static List<String> keep(List<String> ranked, Set<String> matching, int limit) {
        List<String> out = new ArrayList<>();
        for (String id : ranked) {
            if ((matching == null || matching.contains(id)) && out.size() < limit) {
                out.add(id);
            }
        }
        return out;
    }

    private static String name(ContentIndex index, String id) {
        for (ContentIndex.Entry e : index.entries()) {
            if (e.id().equals(id)) {
                return e.name();
            }
        }
        return id;
    }
}
