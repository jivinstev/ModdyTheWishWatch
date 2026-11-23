package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>Another player may be a REFERENCE, never a SUBJECT</b> (2026-10-02).
 *
 * <p>A place to go or point at — "take me to Bob" — is a reference: nothing happens to Bob. Anything
 * done TO him or AT/NEAR him — give, clear, effect, a teleport that moves HIM, TNT at his feet — is a
 * subject, and is refused. Enforced as an ALLOWLIST of shapes, because a list of what is allowed fails
 * safe: a new dangerous form is refused by default instead of slipping past a ban list. A command that
 * names another player is refused EXCEPT:
 * <ul>
 *   <li>{@code tp|teleport <self> <other>}, and the one-argument {@code tp <other>} run as self;</li>
 *   <li>{@code execute at <other> run tp|teleport <self> ~ ~ ~} (an optional {@code as <self>} and
 *       {@code if|unless} conditions that read only self or the world may come before it).</li>
 * </ul>
 * Both are a {@link Kind#VISIT}, which the world's {@code player_reach} rule then allows ({@code open})
 * or refuses kindly ({@code off}) — {@link #allows}. The third allowed shape,
 * a compass pointed from the other player's position, has no vanilla command at all (nothing in a
 * function can write a live position into a {@code lodestone_tracker}), so it is the {@code locate}
 * effect's {@code player} target, whose Java half ({@code item.Locate}) asks the same rule on every
 * click.
 *
 * <p>{@code <self>} is {@code @s} — the wisher in {@code run_command}, the presser for a button, a
 * template or a datapack — or, in {@code run_command} only, the wisher's own name (or a selector
 * positively named for her, which is what {@link PersonSelectors#narrow} writes).
 *
 * <p><b>What "names another player" means.</b> A bare word that is another online player's name; a
 * selector positively {@code name=}d for one; a player selector that EXCLUDES someone
 * ({@code name=!…}) or the executor ({@code distance=1..}) — which is how "the nearest OTHER player"
 * is written, and which {@link PersonSelectors#narrow} used to reverse into the wisher herself, a
 * silent no-op reported as a granted wish (an earlier issue's second bug); and, in a datapack
 * ({@link Who#anyNameIsAnother}), any bare word in a slot that takes a player, because a function
 * runs long after the wish, for whoever presses it, and a name written into it is never the presser.
 *
 * <p>What it deliberately does NOT judge: a generic {@code @a}/{@code @p}/{@code @r} with no name.
 * In {@code run_command} {@link PersonSelectors#narrow} already narrows those to the wisher, and in a
 * datapack they are the shared-world question ("a sound everyone near hears" is in half the effect
 * catalogue), which is a different rule from this one.
 *
 * <p>Pure, no Minecraft: {@code PlayerReachTest}.
 */
public final class PlayerReach {

    private PlayerReach() {
    }

    /** A world's {@code player_reach} rule. */
    public enum Mode {
        /** Another player is never a place to go: the default, for any world that does not say. */
        OFF,
        /** A visit and a compass are allowed. */
        OPEN;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode ofKey(String k) {
            for (Mode m : values()) {
                if (m.key().equals(k)) {
                    return m;
                }
            }
            return null;
        }
    }

    public enum Kind {
        /** Names no other player: this rule has nothing to say. */
        NONE,
        /** Self goes to another player — allowed where the world's mode is {@code open}. */
        VISIT,
        /** Another player is the subject, or the shape is not on the list. */
        REFUSED
    }

    /** Why a command was refused — the {@code reach} log's vocabulary. */
    public static final String WHY_SUBJECT = "another_player_as_subject";
    public static final String WHY_REACH_OFF = "player_reach_off";

    /** The verdict on one command, and the reference ({@code other}) it turned on. */
    public record Verdict(Kind kind, String other, String why) {
        public static final Verdict NONE = new Verdict(Kind.NONE, null, null);

        public boolean refused() {
            return kind == Kind.REFUSED;
        }

        public boolean visit() {
            return kind == Kind.VISIT;
        }
    }

    /**
     * Who is who for one command.
     *
     * @param selfNames         the names that mean self (lower case): the wisher in {@code run_command},
     *                          none for a button or a datapack, where only {@code @s} is self
     * @param others            other players' names (lower case)
     * @param anyNameIsAnother  a datapack/template: any bare word in a player slot is another player
     */
    public record Who(Set<String> selfNames, Set<String> others, boolean anyNameIsAnother) {
        public Who {
            selfNames = lower(selfNames);
            others = lower(others);
            others.removeAll(selfNames);
        }

        /** {@code run_command}: the wisher by name, every other child online. */
        public static Who wisher(String self, Collection<String> others) {
            return new Who(self == null ? Set.of() : Set.of(self), Set.copyOf(nonNull(others)), false);
        }

        /** A datapack, a template or a button: only {@code @s} is self, and any name is someone else. */
        public static Who presser(Collection<String> knownNames) {
            return new Who(Set.of(), Set.copyOf(nonNull(knownNames)), true);
        }

        private static Set<String> lower(Set<String> in) {
            Set<String> out = new HashSet<>();
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }

        private static List<String> nonNull(Collection<String> in) {
            List<String> out = new ArrayList<>();
            if (in != null) {
                for (String s : in) {
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
    }

    /** Does this world's mode let the verdict through? A refusal never; a visit only when open. */
    public static boolean allows(Verdict v, Mode mode) {
        return switch (v.kind()) {
            case NONE -> true;
            case VISIT -> mode == Mode.OPEN;
            case REFUSED -> false;
        };
    }

    // ── the check ───────────────────────────────────────────────────────────────────────────────

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SELECTOR = Pattern.compile("@[parens](\\[.*\\])?", Pattern.DOTALL);

    /** Commands whose own arguments take a player: root → the argument indexes (after the root). */
    private static final Map<String, int[]> SLOTS = Map.ofEntries(
            Map.entry("give", new int[] {0}), Map.entry("clear", new int[] {0}),
            Map.entry("kill", new int[] {0}), Map.entry("effect", new int[] {1}),
            Map.entry("damage", new int[] {0}), Map.entry("enchant", new int[] {0}),
            Map.entry("xp", new int[] {1}), Map.entry("experience", new int[] {1}),
            Map.entry("gamemode", new int[] {1}), Map.entry("attribute", new int[] {0}),
            Map.entry("tag", new int[] {0}), Map.entry("title", new int[] {0}),
            Map.entry("tellraw", new int[] {0}), Map.entry("msg", new int[] {0}),
            Map.entry("tell", new int[] {0}), Map.entry("w", new int[] {0}),
            Map.entry("spectate", new int[] {0, 1}), Map.entry("ride", new int[] {0, 2}),
            Map.entry("spawnpoint", new int[] {0}), Map.entry("playsound", new int[] {2}),
            Map.entry("loot", new int[] {1}), Map.entry("spreadplayers", new int[] {5}));

    /** The verdict on one command. */
    public static Verdict check(String command, Who who) {
        if (command == null || command.isBlank() || who == null) {
            return Verdict.NONE;
        }
        List<String> t = tokens(command.strip().startsWith("/") ? command.strip().substring(1) : command.strip());
        if (t.isEmpty()) {
            return Verdict.NONE;
        }
        String other = firstOther(t, who);
        if (other == null) {
            return Verdict.NONE;
        }
        return shape(t, who, other);
    }

    /** The first reference to another player in the command, as written, or null. */
    static String firstOther(List<String> t, Who who) {
        String root = t.get(0).toLowerCase(Locale.ROOT);
        if (root.equals("say") || root.equals("me")) {
            return null;   // a name in a sentence everybody reads is a word, not a target
        }
        Set<Integer> slots = playerSlots(t);
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            if (isOther(w, who, slots.contains(i))) {
                return w;
            }
        }
        return null;
    }

    /** Indexes of the tokens that take a player: after {@code as}/{@code at}/{@code entity}, and SLOTS. */
    private static Set<Integer> playerSlots(List<String> t) {
        Set<Integer> out = new HashSet<>();
        int start = 0;
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i).toLowerCase(Locale.ROOT);
            if (i + 1 < t.size() && (w.equals("as") || w.equals("at") || w.equals("entity"))) {
                out.add(i + 1);
            }
            if (w.equals("run")) {
                start = i + 1;
            }
        }
        // the root of the command itself, and of whatever `execute … run` runs
        for (int s : start == 0 ? new int[] {0} : new int[] {0, start}) {
            if (s >= t.size()) {
                continue;
            }
            String root = t.get(s).toLowerCase(Locale.ROOT);
            if (root.equals("tp") || root.equals("teleport")) {
                out.add(s + 1);          // the target, or the destination of a one-argument tp
                out.add(s + 2);          // the destination (a coordinate is never a name)
            }
            int[] at = SLOTS.get(root);
            if (at != null) {
                for (int k : at) {
                    out.add(s + 1 + k);
                }
            }
        }
        return out;
    }

    private static boolean isOther(String w, Who who, boolean playerSlot) {
        if (w.startsWith("@")) {
            if (!SELECTOR.matcher(w).matches()) {
                return false;
            }
            char kind = w.charAt(1);
            String args = w.length() > 2 ? w.substring(3, w.length() - 1) : "";
            if (kind == 's') {
                return false;
            }
            for (String part : splitArgs(args)) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim().replace("\"", "").replace("'", "");
                if (key.equals("name")) {
                    if (value.startsWith("!")) {
                        return playerAimed(kind, args);     // "everyone but X": aimed past someone
                    }
                    String v = value.toLowerCase(Locale.ROOT);
                    return who.others().contains(v)
                            || (who.anyNameIsAnother() && playerAimed(kind, args) && NAME.matcher(value).matches());
                }
                if (key.equals("distance") && excludesExecutor(value) && !who.anyNameIsAnother()) {
                    // "anyone but me" — only where the executor IS the wisher. In a function the
                    // executor is as often a creature (an ability hitting whoever stands near it),
                    // which is the shared-world question, not this rule.
                    return playerAimed(kind, args);
                }
            }
            return false;
        }
        if (!NAME.matcher(w).matches() || w.chars().allMatch(Character::isDigit)) {
            return false;
        }
        String lw = w.toLowerCase(Locale.ROOT);
        if (who.others().contains(lw)) {
            return true;
        }
        return playerSlot && who.anyNameIsAnother() && !w.equals("~");
    }

    /** {@code 1..}, {@code 0.5..10}: a lower bound above zero leaves the executor out. */
    private static boolean excludesExecutor(String range) {
        int dots = range.indexOf("..");
        String lo = dots < 0 ? range : range.substring(0, dots);
        try {
            return !lo.isBlank() && Double.parseDouble(lo) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Is this selector aimed at PLAYERS? {@code @p @a @r}, or an {@code @e}/{@code @n} with a positive
     * {@code type=player}. An untyped {@code @e} is usually a tagged creature in a function, and in
     * {@code run_command} {@link PersonSelectors#narrow} already refuses it.
     */
    private static boolean playerAimed(char kind, String args) {
        if (kind == 'p' || kind == 'a' || kind == 'r') {
            return true;
        }
        for (String part : splitArgs(args)) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("type")) {
                String v = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (v.equals("player") || v.equals("minecraft:player")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSelf(String w, Who who) {
        if (w.equals("@s") || (w.startsWith("@s[") && w.endsWith("]"))) {
            return true;
        }
        if (w.startsWith("@")) {
            if (!SELECTOR.matcher(w).matches() || who.selfNames().isEmpty()) {
                return false;
            }
            String args = w.length() > 2 ? w.substring(3, w.length() - 1) : "";
            for (String part : splitArgs(args)) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("name")) {
                    String v = part.substring(eq + 1).trim().replace("\"", "").replace("'", "");
                    return who.selfNames().contains(v.toLowerCase(Locale.ROOT));
                }
            }
            return false;
        }
        return who.selfNames().contains(w.toLowerCase(Locale.ROOT));
    }

    private static boolean isOtherRef(String w, Who who) {
        return isOther(w, who, true) && !isSelf(w, who);
    }

    /** Is the command one of the allowed shapes, given that it names {@code other}? */
    private static Verdict shape(List<String> t, Who who, String other) {
        Verdict refused = new Verdict(Kind.REFUSED, other, WHY_SUBJECT);
        String at = null;
        int i = 0;
        if (t.get(0).equalsIgnoreCase("execute")) {
            i = 1;
            while (true) {
                if (i >= t.size()) {
                    return refused;
                }
                String sub = t.get(i).toLowerCase(Locale.ROOT);
                if (sub.equals("run")) {
                    i++;
                    break;
                }
                if (i + 1 >= t.size()) {
                    return refused;
                }
                String arg = t.get(i + 1);
                switch (sub) {
                    case "as" -> {
                        if (!isSelf(arg, who)) {
                            return refused;          // running AS another player: he is the subject
                        }
                        i += 2;
                    }
                    case "at" -> {
                        if (isSelf(arg, who)) {
                            at = null;
                        } else if (isOtherRef(arg, who)) {
                            at = arg;
                        } else {
                            return refused;
                        }
                        i += 2;
                    }
                    case "if", "unless" -> {
                        int n = conditionLength(t, i + 1, who);
                        if (n < 0) {
                            return refused;
                        }
                        i += 1 + n;
                    }
                    default -> {
                        return refused;              // positioned as, facing, rotated, store, summon…
                    }
                }
            }
        }
        List<String> f = t.subList(i, t.size());
        if (f.isEmpty()) {
            return refused;
        }
        String root = f.get(0).toLowerCase(Locale.ROOT);
        if (!root.equals("tp") && !root.equals("teleport")) {
            return refused;
        }
        if (at != null) {
            // execute at <other> run tp <self> ~ ~ ~ [~ ~]
            if ((f.size() == 5 || f.size() == 7) && isSelf(f.get(1), who)
                    && f.subList(2, f.size()).stream().allMatch(c -> c.equals("~"))) {
                return new Verdict(Kind.VISIT, at, null);
            }
            return refused;
        }
        if (f.size() == 3 && isSelf(f.get(1), who) && isOtherRef(f.get(2), who)) {
            return new Verdict(Kind.VISIT, f.get(2), null);
        }
        if (f.size() == 2 && isOtherRef(f.get(1), who)) {
            return new Verdict(Kind.VISIT, f.get(1), null);   // `tp <other>` moves the executor: self
        }
        return refused;
    }

    /**
     * How many tokens an {@code if}/{@code unless} condition takes, or -1 when it reads another
     * player (or is a form this does not know — refused, never guessed).
     */
    private static int conditionLength(List<String> t, int i, Who who) {
        if (i >= t.size()) {
            return -1;
        }
        int n = switch (t.get(i).toLowerCase(Locale.ROOT)) {
            case "entity", "predicate", "function", "dimension" -> 2;
            case "score" -> i + 3 < t.size() && t.get(i + 3).equalsIgnoreCase("matches") ? 5 : 6;
            case "block", "biome" -> 5;
            case "loaded" -> 4;
            case "blocks" -> 11;
            case "data" -> i + 1 < t.size() && t.get(i + 1).equalsIgnoreCase("block") ? 6 : 4;
            case "items" -> i + 1 < t.size() && t.get(i + 1).equalsIgnoreCase("block") ? 7 : 5;
            default -> -1;
        };
        if (n < 0 || i + n > t.size()) {
            return -1;
        }
        // the tokens of the condition that name an entity (the rest are objectives, ids, positions)
        List<Integer> targets = new ArrayList<>();
        switch (t.get(i).toLowerCase(Locale.ROOT)) {
            case "entity" -> targets.add(i + 1);
            case "score" -> {
                targets.add(i + 1);
                if (n == 6) {
                    targets.add(i + 4);
                }
            }
            case "data", "items" -> {
                if (t.get(i + 1).equalsIgnoreCase("entity")) {
                    targets.add(i + 2);
                }
            }
            default -> {
            }
        }
        for (int k : targets) {
            if (isOther(t.get(k), who, false) || (who.anyNameIsAnother() && isOther(t.get(k), who, true)
                    && !t.get(k).startsWith("#"))) {
                if (!isSelf(t.get(k), who)) {
                    return -1;
                }
            }
        }
        return n;
    }

    // ── tokens ──────────────────────────────────────────────────────────────────────────────────

    /** Top-level words: whitespace outside quotes, {@code […]} and {@code {…}} splits. */
    static List<String> tokens(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                word.append(c);
                if (c == '\\' && i + 1 < command.length()) {
                    word.append(command.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                word.append(c);
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (word.length() > 0) {
                    out.add(word.toString());
                    word.setLength(0);
                }
                continue;
            }
            word.append(c);
        }
        if (word.length() > 0) {
            out.add(word.toString());
        }
        return out;
    }

    /** A selector's arguments split on top-level commas. */
    private static List<String> splitArgs(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ── what the agent is told ──────────────────────────────────────────────────────────────────

    /**
     * The agent's sentence for a refused command. {@code workshop} names the world where a visit works, for the
     * {@code off} case's kind redirect.
     */
    public static String refusal(Verdict v, String workshop) {
        if (v.visit()) {
            return "'" + v.other() + "' is another player, and on this world a wish can't take anyone"
                    + " to another player — every place belongs to the player who lives there. Tell them"
                    + " kindly that visiting is something they can do in " + workshop + ", and never"
                    + " build something else that gets round this.";
        }
        return "'" + v.other() + "' is another player. Another player can be a PLACE TO GO — `tp @s "
                + v.other() + "`, or `execute at " + v.other() + " run tp @s ~ ~ ~` — but never someone"
                + " a wish does something to, moves, or puts things around. A wish touches only the"
                + " child who made it; tell them it is the other child's to wish for, and never build"
                + " something else (a datapack, a button) that gets round this.";
    }
}
