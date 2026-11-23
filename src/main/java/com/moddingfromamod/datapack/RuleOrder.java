package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The order the agent is shown the rules in — <b>Minecraft-free</b>, so the rule is a unit test.
 *
 * <p>His own rules come first, newest first; the curated library's packs come after them, in the
 * order given. {@code Surroundings} shows only the first few, so this order decides which rules the
 * agent is told exist at all. It used to be the store's name order reversed, which put every library
 * pack ahead of his own — harmless at six library packs, and the day the library reached nine a
 * world with a handful of rules showed the agent one of them, chosen by the alphabet.
 */
public final class RuleOrder {

    private RuleOrder() {
    }

    /**
     * @param ids      every rule id, his and the library's, in any order (duplicates are dropped)
     * @param isHis    true for a rule made in this world, false for a library pack
     * @param changed  when a rule of his last changed (epoch ms); ignored for library packs
     */
    public static List<String> order(List<String> ids, Predicate<String> isHis,
                                     ToLongFunction<String> changed) {
        List<String> his = new ArrayList<>();
        List<String> library = new ArrayList<>();
        for (String id : ids) {
            if (his.contains(id) || library.contains(id)) {
                continue;
            }
            (isHis.test(id) ? his : library).add(id);
        }
        // Newest first; a tie (same mtime) falls back to the name, newest-named first, so the
        // order is total and a test can pin it.
        his.sort(Comparator.comparingLong(changed).reversed()
                .thenComparing(Comparator.<String>reverseOrder()));
        List<String> out = new ArrayList<>(his);
        out.addAll(library);
        return out;
    }
}
