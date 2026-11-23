package com.moddingfromamod.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>"What do I do now?" — the next goal on a goal tree</b>. Pure: the tree arrives as
 * parent links, the player's progress as the set of ids he has done, and out comes ONE goal.
 *
 * <p><b>The rule.</b> A goal is OPEN when it is not done and its parent is done (the root is open
 * when it is not done). Of the open goals, the one offered is the first in the world's {@code order}
 * list; goals the list does not name come after every named one, nearest the root first, then by id
 * — so the answer is the same every time he asks, and a world's author decides which early goal
 * leads (the cobblestone generator before a hoe). Ids in {@code skip} are never offered: SkyBlock
 * Advancements' "Oops! Turn your lava into obsidian" is a warning, not a goal.
 *
 * <p>Empty when nothing is open: every goal under the root is done — or skipped — and the caller
 * says so ("you have done every goal!").
 *
 * <p>In the game, {@code IsleGoals} builds the links from the server's advancement tree (every node
 * with a display, under the manifest's {@code guide.goals.root}) and the done set from the player's
 * own {@code PlayerAdvancements}; it never grants or changes one.
 */
public final class NextGoal {

    private NextGoal() {
    }

    /**
     * The next goal.
     *
     * @param root     the tree's root id
     * @param parentOf every node's parent, by id (the root's entry, if any, is ignored)
     * @param done     the ids he has done
     * @param order    ids offered first, in this order
     * @param skip     ids never offered (hidden advancements, warnings)
     */
    public static Optional<String> next(String root, Map<String, String> parentOf, Set<String> done,
                                        List<String> order, Collection<String> skip) {
        if (root == null || root.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> links = parentOf == null ? Map.of() : parentOf;
        Map<String, List<String>> children = new HashMap<>();
        for (Map.Entry<String, String> e : links.entrySet()) {
            if (e.getValue() != null && !e.getKey().equals(root)) {
                children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        // Siblings in a fixed order, whatever order the map gave them in.
        children.values().forEach(java.util.Collections::sort);
        Set<String> d = done == null ? Set.of() : done;
        List<String> o = order == null ? List.of() : order;
        Collection<String> s = skip == null ? List.of() : skip;
        // Walk the tree from the root, breadth first: depth is how far from the root a goal is.
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        depth.put(root, 0);
        queue.add(root);
        String best = null;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            boolean isDone = d.contains(id);
            boolean open = !isDone && (id.equals(root) || d.contains(links.get(id)));
            if (open && !s.contains(id) && (best == null || before(id, best, o, depth))) {
                best = id;
            }
            for (String c : children.getOrDefault(id, List.of())) {
                if (!depth.containsKey(c)) {
                    depth.put(c, depth.get(id) + 1);
                    queue.add(c);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean before(String a, String b, List<String> order, Map<String, Integer> depth) {
        int ia = rank(a, order);
        int ib = rank(b, order);
        if (ia != ib) {
            return ia < ib;
        }
        int da = depth.get(a);
        int db = depth.get(b);
        if (da != db) {
            return da < db;
        }
        return a.compareTo(b) < 0;
    }

    private static int rank(String id, List<String> order) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** The wish "Help me with this" pre-fills when the world names none for this goal. Pure. */
    public static String fallbackWish(String goalTitle) {
        String t = goalTitle == null ? "" : goalTitle.strip();
        return t.isEmpty() ? "help me with my next goal" : "help me with " + t;
    }

    /**
     * The wish the help button pre-fills: the world's own for this goal, else {@link #fallbackWish}.
     * Never one that names an advancement command — a help wish asks Moddy to help, build or explain,
     * and the goal stays his to earn. Pure.
     */
    public static String helpWish(WorldGuide.Help help, String goalTitle) {
        String w = help == null ? "" : help.wish();
        return w.isBlank() || w.toLowerCase(java.util.Locale.ROOT).contains("advancement")
                ? fallbackWish(goalTitle) : w;
    }
}
