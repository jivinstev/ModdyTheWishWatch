package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * <b>Turn-into's suggestions</b> (§1): the From page opens with the blocks he is LOOKING AT and
 * the things he is CARRYING, and the To page with what he turned things into LAST, above the pages' own picks, so the
 * stone in front of him is one tap and never a search. Pure: {@code BuilderView} (the game) counts what a fan of rays
 * across his view hits and what his inventory holds; this ranks them. Deterministic: the same counts give the same tiles.
 */
public final class BuilderSuggest {

    private BuilderSuggest() {
    }

    /** At most this many of each From section. */
    public static final int MOST = 6;
    /** At most this many recent To targets. */
    public static final int MOST_RECENT = 4;
    public static final String LOOKING_LORE = "You're looking at this";
    public static final String CARRYING_LORE = "In your inventory";
    public static final String RECENT_LORE = "You picked this last time";

    /**
     * What his view held when the page opened: the block at his crosshair (or null: sky) and how many of the fan's
     * rays hit each block id first. {@code carrying}: each item he holds (hotbar, backpack, off hand, never armour)
     * and how many.
     */
    public record Seen(String crosshair, Map<String, Integer> inView, Map<String, Integer> carrying) {
        public Seen {
            inView = Map.copyOf(inView);
            carrying = Map.copyOf(carrying);
        }

        public static final Seen NOTHING = new Seen(null, Map.of(), Map.of());
    }

    /** The blocks in view: his crosshair first (when {@code ok}), then the most rays, ties by id; at most {@link #MOST}. */
    public static List<String> lookingAt(Seen seen, Predicate<String> ok) {
        List<String> out = new ArrayList<>();
        if (seen.crosshair() != null && ok.test(seen.crosshair())) {
            out.add(seen.crosshair());
        }
        for (String id : ranked(seen.inView())) {
            if (out.size() == MOST) {
                break;
            }
            if (!out.contains(id) && ok.test(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** What he carries: the most first, ties by id; at most {@link #MOST}. */
    public static List<String> carrying(Seen seen, Predicate<String> ok) {
        List<String> out = new ArrayList<>();
        for (String id : ranked(seen.carrying())) {
            if (out.size() == MOST) {
                break;
            }
            if (ok.test(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** His last To targets, newest first, each once, those {@code ok} allows; at most {@link #MOST_RECENT}. */
    public static List<String> recent(List<String> newestFirst, Predicate<String> ok) {
        List<String> out = new ArrayList<>();
        for (String id : newestFirst) {
            if (out.size() == MOST_RECENT) {
                break;
            }
            if (id != null && !id.isEmpty() && !out.contains(id) && ok.test(id)) {
                out.add(id);
            }
        }
        return out;
    }

    static List<String> ranked(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> e = new ArrayList<>(new LinkedHashMap<>(counts).entrySet());
        e.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(x -> -x.getValue()).thenComparing(Map.Entry::getKey));
        return e.stream().map(Map.Entry::getKey).toList();
    }
}
