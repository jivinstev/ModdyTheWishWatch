package com.moddingfromamod.item.delivery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * <b>What Moddy says about a hand-over: each thing, by name, and where it landed</b>
 *. Pure: no Minecraft import.
 *
 * <p>This example is the contract, word for word:
 *
 * <blockquote>Pineapple Paradise is in slot 8, Mango Splash in slot 9, and the Tropical Oasis Bar
 * and 32 Melon Slices are in your Wish Bag!</blockquote>
 *
 * <p>and when the list is long it is grouped rather than read out (<i>"6 things from your oasis:
 * 2 in slots 8–9, 4 in your Wish Bag!"</i>). Slots are numbered as he sees them, 1–9. The agent is
 * told the same facts in its own words ({@link #forAgent}), in place of the {@code (no output)} a
 * suppressed {@code /give} used to leave it — which is how a lost item read as a granted one.
 */
public final class DeliveryWords {

    /** More distinct things than this and the line is grouped, not read out. */
    public static final int READ_OUT = 4;

    /**
     * Where one part of a give went. {@code BAG} and {@code FEET} (the bag's backstop) are a
     * hand-over's; {@code INVENTORY} and {@code FLOOR} are a behaviour give's, which Moddy
     * never announces — they have words only so the agent, and a caller that asks, can be told.
     */
    public enum Where { HOTBAR, INVENTORY, BAG, FEET, FLOOR }

    /**
     * One landing: {@code count} of {@code name}, at {@code where}; {@code slot} is the hotbar slot
     * as HE numbers it (1–9), and 0 anywhere else.
     */
    public record Landed(String name, int count, Where where, int slot) {
    }

    /** Several landings of one thing in one place, merged. */
    private record Thing(String name, int count, Where where, TreeSet<Integer> slots) {
    }

    private DeliveryWords() {
    }

    /**
     * The line he reads, or "" when nothing landed.
     *
     * @param from what the hand-over came from, for the grouped form ("your oasis"), or null
     */
    public static String forChild(List<Landed> landed, String from) {
        List<Thing> things = merge(landed);
        if (things.isEmpty()) {
            return "";
        }
        if (things.size() > READ_OUT) {
            return grouped(things, from);
        }
        List<String> clauses = new ArrayList<>();
        List<Thing> inv = new ArrayList<>();
        List<Thing> bag = new ArrayList<>();
        List<Thing> feet = new ArrayList<>();
        List<Thing> floor = new ArrayList<>();
        for (Thing t : things) {
            switch (t.where()) {
                case HOTBAR -> clauses.add(label(t, false) + (clauses.isEmpty() ? (plural(t) ? " are" : " is") : "")
                        + " in " + slots(t.slots()));
                case INVENTORY -> inv.add(t);
                case BAG -> bag.add(t);
                case FEET -> feet.add(t);
                case FLOOR -> floor.add(t);
            }
        }
        if (!inv.isEmpty()) {
            clauses.add(list(inv, true) + (inv.size() > 1 || plural(inv.get(0)) ? " are" : " is")
                    + " in your inventory");
        }
        if (!bag.isEmpty()) {
            clauses.add(list(bag, true) + (bag.size() > 1 || plural(bag.get(0)) ? " are" : " is")
                    + " in your Wish Bag");
        }
        if (!feet.isEmpty()) {
            clauses.add(list(feet, true) + (feet.size() > 1 || plural(feet.get(0)) ? " are" : " is")
                    + " at your feet, because your Wish Bag is full");
        }
        if (!floor.isEmpty()) {
            clauses.add(list(floor, true) + (floor.size() > 1 || plural(floor.get(0)) ? " are" : " is")
                    + " at your feet, because your inventory is full");
        }
        return join(clauses) + "!";
    }

    /**
     * The same facts for the agent: what went where, in full, never grouped — it reads every word,
     * and a grouped count is exactly the kind of summary that hid the lost items.
     */
    public static String forAgent(List<Landed> landed) {
        List<Thing> things = merge(landed);
        if (things.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        boolean behaviour = false;
        for (Thing t : things) {
            String what = (t.count() == 1 ? "" : t.count() + " × ") + t.name();
            behaviour |= t.where() == Where.INVENTORY || t.where() == Where.FLOOR;
            parts.add(switch (t.where()) {
                case HOTBAR -> what + " → hotbar " + slots(t.slots());
                case INVENTORY -> what + " → his inventory";
                case BAG -> what + " → his Wish Bag";
                case FEET -> what + " → on the ground at his feet (his Wish Bag is full)";
                case FLOOR -> what + " → on the ground at his feet (his inventory is full)";
            });
        }
        if (behaviour) {
            return "Given by the thing itself, not handed over: " + String.join("; ", parts)
                    + ". Moddy said nothing — an item's, a rule's or a creature's own give goes to "
                    + "his hotbar, then his inventory, then the floor, and never his Wish Bag.";
        }
        return "Handed over: " + String.join("; ", parts) + ". Moddy told him where each one is "
                + "(hotbar first, then his Wish Bag in slot 2 — never his main inventory).";
    }

    /** For a caller with its own greeting ("Here's your Hot Coffee!"): just where it is. */
    public static String where(List<Landed> landed) {
        List<Thing> things = merge(landed);
        if (things.isEmpty()) {
            return "";
        }
        if (things.size() == 1) {
            Thing t = things.get(0);
            return switch (t.where()) {
                case HOTBAR -> "It's in " + slots(t.slots()) + "!";
                case INVENTORY -> "It's in your inventory!";
                case BAG -> "It's in your Wish Bag!";
                case FEET -> "It's at your feet, because your Wish Bag is full!";
                case FLOOR -> "It's at your feet, because your inventory is full!";
            };
        }
        return forChild(landed, null);
    }

    // ── the pieces ──────────────────────────────────────────────────────────────────────────

    private static List<Thing> merge(List<Landed> landed) {
        Map<String, Thing> out = new LinkedHashMap<>();
        if (landed == null) {
            return List.of();
        }
        for (Landed l : landed) {
            if (l == null || l.count() <= 0) {
                continue;
            }
            String name = l.name() == null || l.name().isBlank() ? "something" : l.name().strip();
            String key = l.where() + "\u0000" + name;
            Thing t = out.get(key);
            TreeSet<Integer> slots = t == null ? new TreeSet<>() : t.slots();
            if (l.where() == Where.HOTBAR && l.slot() > 0) {
                slots.add(l.slot());
            }
            out.put(key, new Thing(name, (t == null ? 0 : t.count()) + l.count(), l.where(), slots));
        }
        return new ArrayList<>(out.values());
    }

    private static String grouped(List<Thing> things, String from) {
        int hotbar = 0;
        int inv = 0;
        int bag = 0;
        int feet = 0;
        int floor = 0;
        TreeSet<Integer> slots = new TreeSet<>();
        for (Thing t : things) {
            switch (t.where()) {
                case HOTBAR -> {
                    hotbar++;
                    slots.addAll(t.slots());
                }
                case INVENTORY -> inv++;
                case BAG -> bag++;
                case FEET -> feet++;
                case FLOOR -> floor++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (hotbar > 0) {
            parts.add(hotbar + " in " + slots(slots));
        }
        if (inv > 0) {
            parts.add(inv + " in your inventory");
        }
        if (bag > 0) {
            parts.add(bag + " in your Wish Bag");
        }
        if (feet > 0) {
            parts.add(feet + " at your feet, because your Wish Bag is full");
        }
        if (floor > 0) {
            parts.add(floor + " at your feet, because your inventory is full");
        }
        String head = things.size() + " things " + (from == null || from.isBlank() ? "for you"
                : "from " + from.strip());
        return head + ": " + String.join(", ", parts) + "!";
    }

    /** "slot 8", "slots 8–9", "slots 3, 5 and 8". */
    static String slots(java.util.SortedSet<Integer> slots) {
        List<Integer> s = new ArrayList<>(slots);
        if (s.isEmpty()) {
            return "your hotbar";
        }
        if (s.size() == 1) {
            return "slot " + s.get(0);
        }
        boolean run = true;
        for (int i = 1; i < s.size(); i++) {
            run &= s.get(i) == s.get(i - 1) + 1;
        }
        if (run) {
            return "slots " + s.get(0) + "–" + s.get(s.size() - 1);
        }
        List<String> words = new ArrayList<>();
        for (int n : s) {
            words.add(String.valueOf(n));
        }
        return "slots " + andList(words);
    }

    private static String list(List<Thing> things, boolean article) {
        List<String> words = new ArrayList<>();
        for (Thing t : things) {
            words.add(label(t, article));
        }
        return andList(words);
    }

    private static String andList(List<String> words) {
        if (words.size() == 1) {
            return words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    /** Clauses: "a", "a and b", "a, b, and c" — the example has the comma before the last. */
    private static String join(List<String> clauses) {
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        if (clauses.size() == 2) {
            return clauses.get(0) + ", and " + clauses.get(1);
        }
        return String.join(", ", clauses.subList(0, clauses.size() - 1)) + ", and "
                + clauses.get(clauses.size() - 1);
    }

    private static boolean plural(Thing t) {
        return t.count() != 1;
    }

    /** "Pineapple Paradise", "the Tropical Oasis Bar" (in a list with an article), "32 Melon Slices". */
    private static String label(Thing t, boolean article) {
        if (t.count() == 1) {
            return article ? "the " + t.name() : t.name();
        }
        return t.count() + " " + pluralOf(t.name());
    }

    /** Plural of a display name: only its last word changes ("Melon Slice" → "Melon Slices"). */
    static String pluralOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("s")) {
            return name;                           // "Cocoa Beans" is already plural
        }
        if (lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh")) {
            return name + "es";
        }
        if (lower.length() > 1 && lower.endsWith("y") && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }
}
