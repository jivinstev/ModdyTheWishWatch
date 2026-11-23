package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * <b>Every wish history is newest first</b> (2026-10-04) — the one order rule, for
 * the watch's creations, its buttons and the panel that lays them out.
 *
 * <ol>
 *   <li>his own things with a known time, the most recently made or changed first — one sort
 *       across every kind, so a creature made this afternoon is never behind an item from June
 *       because creatures happened to be listed after items;</li>
 *   <li>his own things whose time nobody knows (made and not in any record), in the
 *       order they came — an unknown age is not "the oldest", it is off the timeline;</li>
 *   <li>the library's starters, in their curated order. A starter has no creation time and is
 *       not his, so it never leads his history.</li>
 * </ol>
 *
 * <p>Stable throughout, so two things with the same time keep the order the caller gave them.
 * Minecraft-free: the rule is {@code NewestFirstTest}.
 */
public final class NewestFirst {

    private NewestFirst() {
    }

    /**
     * Sort {@code items} into history order.
     *
     * @param recency when each was made or last changed, epoch ms; {@code <= 0} is unknown
     * @param starter whether it is a library starter rather than his own thing
     */
    public static <T> List<T> sort(List<T> items, ToLongFunction<T> recency, Predicate<T> starter) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        // Each item's bucket and recency are read ONCE (2026-10-31): a recency that looks itself up
        // (LearnedTemplates' did a linear search of the store) was paid on every comparison, and the
        // watch's half-second refresh spent ~130 ms a time in it on a live world. A stable sort over
        // the precomputed keys orders exactly as the comparator over the items did.
        List<Keyed<T>> keyed = new ArrayList<>(items.size());
        for (T t : items) {
            long r = recency.applyAsLong(t);
            int b = starter.test(t) ? 2 : r > 0 ? 0 : 1;
            keyed.add(new Keyed<>(t, b, b == 0 ? r : 0L));
        }
        keyed.sort(Comparator.comparingInt((Keyed<T> k) -> k.bucket)
                .thenComparing(Comparator.comparingLong((Keyed<T> k) -> k.key).reversed()));
        List<T> out = new ArrayList<>(keyed.size());
        for (Keyed<T> k : keyed) {
            out.add(k.item);
        }
        return out;
    }

    /** Only his own things, all of them non-starters. */
    public static <T> List<T> sort(List<T> items, ToLongFunction<T> recency) {
        return sort(items, recency, t -> false);
    }

    private record Keyed<T>(T item, int bucket, long key) {
    }

    private static <T> int bucket(T t, ToLongFunction<T> recency, Predicate<T> starter) {
        if (starter.test(t)) {
            return 2;
        }
        return recency.applyAsLong(t) > 0 ? 0 : 1;
    }
}
