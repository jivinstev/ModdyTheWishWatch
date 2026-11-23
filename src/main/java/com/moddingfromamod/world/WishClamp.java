package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>"Short boosts, modest gifts" — in code, not only in the brief</b>. Minecraft-free, so every clamp is
 * a Gate A test; the tools and the replay only ask it.
 *
 * <p>A world's rules file may carry ONE {@code clamp} rule ({@link WorldRule.Type#CLAMP}). Where it does, what a
 * wish hands the child himself is held to it — and CLAMPED, never refused: it is shortened or shrunk, the wish
 * still ends DONE, and Moddy says so in kid words (the world's economy design). A world with no
 * clamp rule gets exactly what it got before: {@link #of} answers null and nothing below runs.
 * <ul>
 *   <li><b>a power on a player</b> lasts at most {@code powerSeconds}; "forever" (no {@code seconds},
 *       {@code infinite}) becomes that, so it is never forever and does not come back after he dies;</li>
 *   <li><b>a gift</b>: at most {@code stacksPerKind} stacks of any one item and {@code kindsPerWish} different
 *       items in ONE wish — counted across every call, so ten {@code give} commands cannot add up past it;</li>
 *   <li><b>XP</b>: at most {@code xpLevels} levels in one wish (points are counted as the levels they make from
 *       zero);</li>
 *   <li>what cannot be counted is refused with the way that can: {@code attribute … base set} on a player (a
 *       permanent change — {@code modify_player} with {@code seconds}), {@code loot give}/{@code loot … entity}
 *       to a player and {@code summon item}/{@code experience_orb} (use {@code give_items}).</li>
 * </ul>
 * Effects on his creatures and on mobs are never clamped: only a PLAYER target is ({@link #isPlayerTarget}).
 *
 * <p>Enforced where each RUNS: {@code ModifyPlayerTool}, {@code GiveItemsTool}, {@code make_item}'s hand-over,
 * {@code run_command} and a button replayed by {@code WishExecutor} ({@link #command}). The ledger is per wish
 * ({@link #ledger}); a replayed button is one press, one ledger.
 */
public final class WishClamp {

    /** The caps in force. */
    public record Limits(int powerSeconds, int stacksPerKind, int kindsPerWish, int xpLevels) {
        public int powerTicks() {
            return powerSeconds * 20;
        }
    }

    /** This world's caps, or null when its rules carry no clamp (then nothing is clamped). */
    public static Limits of(WorldRules rules) {
        WorldRule r = rules == null ? null : rules.first(WorldRule.Type.CLAMP);
        if (r == null) {
            return null;
        }
        return new Limits(r.integer("powerSeconds", 300), r.integer("stacksPerKind", 1),
                r.integer("kindsPerWish", 3), r.integer("xpLevels", 5));
    }

    /** What one wish has been given so far. Thread-safe enough: one wish runs on one worker. */
    public static final class Ledger {
        private final Map<String, Integer> items = new LinkedHashMap<>();
        private int xpLevels;

        /**
         * How many of {@code count} {@code item}s (stack size {@code maxStack}) this wish may still give: up to
         * {@code stacksPerKind} stacks of it, and none of a new kind past {@code kindsPerWish}. Records what it
         * allows.
         */
        public synchronized int give(Limits l, String item, int count, int maxStack) {
            String k = norm(item);
            int had = items.getOrDefault(k, 0);
            if (had == 0 && !items.containsKey(k) && items.size() >= l.kindsPerWish()) {
                return 0;
            }
            int cap = Math.max(1, maxStack) * l.stacksPerKind();
            int ok = Math.max(0, Math.min(count, cap - had));
            items.put(k, had + ok);
            return ok;
        }

        /** How many of {@code levels} XP levels this wish may still give. Records what it allows. */
        public synchronized int xp(Limits l, int levels) {
            int ok = Math.max(0, Math.min(levels, l.xpLevels() - xpLevels));
            xpLevels += ok;
            return ok;
        }

        public synchronized Map<String, Integer> given() {
            return Map.copyOf(items);
        }
    }

    private static final Map<String, Ledger> LEDGERS = new ConcurrentHashMap<>();

    /** The ledger for one wish (or one button press). */
    public static Ledger ledger(String wishKey) {
        if (LEDGERS.size() > 2_000) {
            LEDGERS.clear();          // a backstop, never the normal path: a finished wish's ledger is forgotten
        }
        return LEDGERS.computeIfAbsent(wishKey == null ? "" : wishKey, k -> new Ledger());
    }

    /** A wish ended: its ledger goes. */
    public static void forget(String wishKey) {
        if (wishKey != null) {
            LEDGERS.remove(wishKey);
        }
    }

    // ── words ────────────────────────────────────────────────────────────────────────────────────────────

    /** "5 minutes", "30 seconds". */
    public static String duration(int seconds) {
        if (seconds % 60 == 0) {
            int m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return seconds + " seconds";
    }

    /** What the child is told when a power was made a short boost. */
    public static String powerWords(Limits l) {
        return "On this island powers are short boosts, so this one lasts " + duration(l.powerSeconds()) + ".";
    }

    /** What the child is told when a gift was made smaller. */
    public static String giftWords() {
        return "On this island gifts are modest, so Moddy gave you a smaller pile.";
    }

    /** What the child is told when XP was trimmed. */
    public static String xpWords(Limits l) {
        return "On this island a wish gives at most " + l.xpLevels() + " levels of XP.";
    }

    // ── a command ────────────────────────────────────────────────────────────────────────────────────────

    /** A command after the clamp: run {@code command} (maybe rewritten), or refuse with {@code refusal}. */
    public record Clamped(String command, String refusal, String words) {
        public boolean refused() {
            return refusal != null;
        }

        public boolean changed(String original) {
            return refusal == null && !command.equals(original);
        }
    }

    private static final Pattern SEL = Pattern.compile("@([aeprsn])(\\[[^\\]]*\\])?");

    /**
     * Is this target a player (or could be one)? {@code @s @p @a @r}, a name, or an {@code @e}/{@code @n} that is
     * not narrowed to a non-player type or a positive tag. His creatures ({@code @e[tag=his_pet]}) and mobs
     * ({@code @e[type=zombie]}) are not.
     */
    public static boolean isPlayerTarget(String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        Matcher m = SEL.matcher(target.strip());
        if (!m.lookingAt()) {
            return true;                                   // a name (or a UUID): it can be a player
        }
        char k = m.group(1).charAt(0);
        if (k != 'e' && k != 'n') {
            return true;
        }
        String args = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        for (String part : args.replaceAll("^\\[|\\]$", "").split(",")) {
            String p = part.strip();
            if (p.startsWith("tag=") && !p.startsWith("tag=!") && p.length() > 4) {
                return false;
            }
            if (p.startsWith("type=") && !p.startsWith("type=!") && !p.equals("type=player")
                    && !p.equals("type=minecraft:player")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Clamp one command line. {@code maxStack} answers an item's stack size (64 when unknown). Through
     * {@code execute … run}: only the innermost command is read and rewritten, the head kept as written. An
     * {@code execute as <player-capable>} makes {@code @s} a player.
     */
    public static Clamped command(String line, Limits l, Ledger ledger, java.util.function.ToIntFunction<String> maxStack) {
        if (line == null || l == null) {
            return new Clamped(line, null, null);
        }
        String c = line.strip();
        String head = "";
        boolean asPlayer = true;          // run_command and a button run as the child
        while (c.toLowerCase(Locale.ROOT).startsWith("execute ")) {
            int run = c.toLowerCase(Locale.ROOT).indexOf(" run ");
            if (run < 0) {
                return new Clamped(line, null, null);
            }
            Matcher as = Pattern.compile("(?:^|\\s)as\\s+(\\S+)").matcher(c.substring(0, run));
            while (as.find()) {
                if (!as.group(1).startsWith("@s")) {
                    asPlayer = isPlayerTarget(as.group(1));
                }
            }
            head += c.substring(0, run + " run ".length());
            c = c.substring(run + " run ".length()).strip();
        }
        List<String> t = new ArrayList<>(List.of(c.split("\\s+")));
        if (t.isEmpty()) {
            return new Clamped(line, null, null);
        }
        String root = t.get(0).toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceFirst("^minecraft:", "");
        Clamped out = switch (root) {
            case "effect" -> effect(t, l, asPlayer);
            case "give" -> give(t, l, ledger, maxStack, asPlayer);
            case "xp", "experience" -> xp(t, l, ledger, asPlayer);
            case "attribute" -> t.size() >= 4 && target(t.get(1), asPlayer) && t.contains("base")
                    && (t.contains("set") || t.contains("reset"))
                    ? refuse("`attribute … base set` on a player is a permanent change, and on this island a power is "
                    + "a short boost. Use modify_player with seconds (at most " + l.powerSeconds() + ")")
                    : keep(c);
            case "loot" -> t.size() >= 3 && (t.get(1).equalsIgnoreCase("give") && target(t.get(2), asPlayer)
                    || t.get(1).equalsIgnoreCase("replace") && t.size() >= 4 && t.get(2).equalsIgnoreCase("entity")
                    && target(t.get(3), asPlayer))
                    ? refuse("a loot table hands him a pile nobody can count, and on this island gifts are modest. "
                    + "Use give_items (at most " + l.stacksPerKind() + " stack(s) of each of " + l.kindsPerWish()
                    + " kinds a wish)")
                    : keep(c);
            case "summon" -> t.size() >= 2 && t.get(1).toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "")
                    .matches("item|experience_orb")
                    ? refuse("summoning items or XP orbs is a gift nobody can count, and on this island gifts are "
                    + "modest. Use give_items for items (at most " + l.stacksPerKind() + " stack(s) of each of "
                    + l.kindsPerWish() + " kinds a wish), or xp add for XP (at most " + l.xpLevels() + " levels)")
                    : keep(c);
            case "item" -> item(t, l, ledger, maxStack, asPlayer);
            default -> keep(c);
        };
        if (out.refused() || out.command().equals(c)) {
            return out.refused() ? out : new Clamped(line, null, out.words());
        }
        return new Clamped(head + out.command(), null, out.words());
    }

    private static boolean target(String sel, boolean asPlayer) {
        return sel.startsWith("@s") ? asPlayer : isPlayerTarget(sel);
    }

    private static Clamped keep(String c) {
        return new Clamped(c, null, null);
    }

    private static Clamped refuse(String why) {
        return new Clamped(null, "REFUSED — A MODEST WISH: " + why + ". Nothing was run.", null);
    }

    /** {@code effect give <target> <effect> [seconds|infinite] [amplifier] [hide]}. */
    private static Clamped effect(List<String> t, Limits l, boolean asPlayer) {
        if (t.size() < 4 || !t.get(1).equalsIgnoreCase("give") || !target(t.get(2), asPlayer)) {
            return keep(String.join(" ", t));
        }
        if (t.size() == 4) {
            return keep(String.join(" ", t));                       // vanilla's default: 30 seconds
        }
        String d = t.get(4);
        boolean infinite = d.equalsIgnoreCase("infinite");
        Integer secs = infinite ? null : intOrNull(d);
        if (!infinite && (secs == null || secs <= l.powerSeconds())) {
            return keep(String.join(" ", t));
        }
        List<String> r = new ArrayList<>(t);
        r.set(4, String.valueOf(l.powerSeconds()));
        return new Clamped(String.join(" ", r), null, powerWords(l));
    }

    /** {@code give <target> <item> [count]}. */
    private static Clamped give(List<String> t, Limits l, Ledger ledger, java.util.function.ToIntFunction<String> maxStack,
                                boolean asPlayer) {
        if (t.size() < 3 || !target(t.get(1), asPlayer)) {
            return keep(String.join(" ", t));
        }
        String item = t.get(2);
        int count = t.size() >= 4 ? Math.max(1, intOr(t.get(3), 1)) : 1;
        int ok = ledger.give(l, item, count, maxStack.applyAsInt(item));
        if (ok == count) {
            return keep(String.join(" ", t));
        }
        if (ok == 0) {
            return refuse("this wish has already given him all it may (" + l.kindsPerWish() + " kinds, "
                    + l.stacksPerKind() + " stack(s) of each); " + giftWords());
        }
        List<String> r = new ArrayList<>(t.subList(0, 3));
        r.add(String.valueOf(ok));
        return new Clamped(String.join(" ", r), null, giftWords());
    }

    /** {@code item replace entity <target> <slot> with <item> [count]}. */
    private static Clamped item(List<String> t, Limits l, Ledger ledger, java.util.function.ToIntFunction<String> maxStack,
                                boolean asPlayer) {
        int with = t.indexOf("with");
        if (t.size() < 7 || !t.get(1).equalsIgnoreCase("replace") || !t.get(2).equalsIgnoreCase("entity")
                || !target(t.get(3), asPlayer) || with < 0 || with + 1 >= t.size()) {
            return keep(String.join(" ", t));
        }
        String item = t.get(with + 1);
        int count = with + 2 < t.size() ? Math.max(1, intOr(t.get(with + 2), 1)) : 1;
        int ok = ledger.give(l, item, count, maxStack.applyAsInt(item));
        if (ok == count) {
            return keep(String.join(" ", t));
        }
        if (ok == 0) {
            return refuse("this wish has already given him all it may; " + giftWords());
        }
        List<String> r = new ArrayList<>(t.subList(0, with + 2));
        r.add(String.valueOf(ok));
        return new Clamped(String.join(" ", r), null, giftWords());
    }

    /** {@code xp|experience add|set <target> <amount> [levels|points]}. */
    private static Clamped xp(List<String> t, Limits l, Ledger ledger, boolean asPlayer) {
        if (t.size() < 4 || !(t.get(1).equalsIgnoreCase("add") || t.get(1).equalsIgnoreCase("set"))
                || !target(t.get(2), asPlayer)) {
            return keep(String.join(" ", t));
        }
        Integer amount = intOrNull(t.get(3));
        if (amount == null || amount <= 0) {
            return keep(String.join(" ", t));
        }
        boolean levels = t.size() >= 5 && t.get(4).toLowerCase(Locale.ROOT).startsWith("level");
        int asLevels = levels ? amount : levelsFromPoints(amount);
        if (t.get(1).equalsIgnoreCase("set")) {
            asLevels = Math.max(asLevels, 1);
        }
        int ok = ledger.xp(l, asLevels);
        if (ok >= asLevels) {
            return keep(String.join(" ", t));
        }
        if (ok == 0) {
            return refuse("this wish has already given him " + l.xpLevels() + " levels of XP; " + xpWords(l));
        }
        return new Clamped(t.get(0) + " add " + t.get(2) + " " + ok + " levels", null, xpWords(l));
    }

    /** How many whole levels {@code points} make from level 0 (vanilla's curve), at least 1 for any points. */
    static int levelsFromPoints(int points) {
        int level = 0;
        long need = 0;
        while (true) {
            long next = level < 16 ? 2L * level + 7 : level < 31 ? 5L * level - 38 : 9L * level - 158;
            if (need + next > points) {
                return Math.max(1, level);
            }
            need += next;
            level++;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String s, int d) {
        Integer i = intOrNull(s);
        return i == null ? d : i;
    }

    private static String norm(String item) {
        String s = item == null ? "" : item.strip().toLowerCase(Locale.ROOT);
        int cut = s.indexOf('[');
        int brace = s.indexOf('{');
        if (brace >= 0 && (cut < 0 || brace < cut)) {
            cut = brace;
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private WishClamp() {
    }
}
