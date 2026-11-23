package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Does this command pick out a PLAYER?</b> — for a wish whose child has LEFT (decision 3).
 *
 * <p>While the child who wished is here, {@code run_command} runs as him, so {@code @s} is him and
 * {@code @p} is (nearest to him) him. Once he has left there is no right answer for any selector
 * that can land on a player: {@code @p} is whoever happens to be nearest, {@code @a} is everybody
 * else, {@code @r} is a lottery. Each of those reaches a child who did not ask — the same bug
 * {@code give_items} refuses (decision 1), arriving through a command instead of a tool. So a
 * command carrying one is refused before anything in its batch runs, and the rest of the wish (a
 * {@code setblock}, a {@code summon}, a {@code fill}) goes ahead where it was aimed.
 *
 * <p>What counts: {@code @p @a @r}, always — even inside {@code execute as @e[type=zombie]}, where
 * {@code @p} is still the nearest PLAYER. {@code @e} and {@code @n} only when nothing restricts
 * them to a non-player type: {@code @e[type=zombie]} is fine, {@code @e[distance=..5]} is not.
 * {@code @s} is deliberately NOT here — with no player attached to the command it names whatever
 * an {@code execute as} set it to, or nothing at all, and nothing is never another child.
 *
 * <h2>…and while she IS here, with another child in the world ({@link #narrow}, option 2)</h2>
 *
 * <p>The rule, 2026-09-24: <b>a wish touches only the child who made it.</b> The world is
 * shared — night, rain, a castle, a summoned zombie are for everyone — but a child's body, bag and
 * screen are his own. So a selector that could reach another child is NARROWED to the wisher
 * ({@code @a} → {@code @a[name=Alice]}, same for {@code @p @r}), and a command that NAMES another
 * child ({@code give Bob …}, {@code @a[name=Bob]}) or reaches anything at all ({@code @e} with no
 * type) is refused. {@code say} and {@code me} are exempt from the bare-name rule: a name there is
 * just a word in a sentence everybody reads.
 *
 * <p>Pure, no Minecraft: the rule unit-tests in milliseconds ({@code PersonSelectorsTest}).
 */
public final class PersonSelectors {

    private PersonSelectors() {
    }

    /** {@code @x} not followed by a word character, with an optional {@code [...]} block. */
    private static final Pattern SELECTOR =
            Pattern.compile("@([parens])(?![A-Za-z0-9_])(\\[[^\\]]*\\])?");

    /**
     * The first selector in {@code command} that can pick out a player, exactly as written, or
     * null when there is none.
     */
    public static String firstPlayerSelector(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        Matcher m = SELECTOR.matcher(command);
        while (m.find()) {
            char kind = m.group(1).charAt(0);
            String args = m.group(2);
            switch (kind) {
                case 'p', 'a', 'r' -> {
                    return m.group();
                }
                case 'e', 'n' -> {
                    if (!restrictedToNonPlayers(args)) {
                        return m.group();
                    }
                }
                default -> {
                    // @s: see the class note.
                }
            }
        }
        return null;
    }

    /**
     * True when the arguments pin the selector to something that is not a player: a positive
     * {@code type=} naming anything else. A negated type ({@code type=!zombie}) still admits
     * players, and {@code type=!player} excludes them.
     *
     * <p>Or a positive {@code tag=}: it names something a wish tagged — the rule
     * {@code CommandGuard} already scopes {@code kill} by — and a wish can tag only its own child,
     * because every player selector it writes is narrowed to him. Taught buttons depend on it
     * ({@code effect give @e[tag=wishmob_giant_trex,distance=..48] …}), and narrowing them at replay
     * would otherwise refuse them whenever another child is online. {@code tag=!x} is "everything
     * but x" and admits players.
     */
    private static boolean restrictedToNonPlayers(String args) {
        if (args == null) {
            return false;
        }
        String body = args.substring(1, args.length() - 1);
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (key.equals("tag") && !value.isEmpty() && !value.startsWith("!")) {
                return true;
            }
            if (!key.equals("type")) {
                continue;
            }
            boolean negated = value.startsWith("!");
            String type = negated ? value.substring(1).trim() : value;
            boolean player = type.equals("player") || type.equals("minecraft:player");
            if (negated ? player : !player) {
                return true;
            }
        }
        return false;
    }

    /** What {@link #narrow} decided about one command. */
    public record Decision(String command, List<String> narrowed, String refused, String why) {
        /** Nothing in it reaches another child. */
        public boolean unchanged() {
            return refused == null && narrowed.isEmpty();
        }

        public boolean isRefused() {
            return refused != null;
        }
    }

    /** Why a command was refused or narrowed — the vocabulary of the {@code reach} log record. */
    public static final String WHY_EVERYONE = "reaches_everyone";
    public static final String WHY_NAMED = "names_another_child";
    public static final String WHY_ANY_ENTITY = "reaches_any_entity";
    /** A selector that excludes someone by name: never narrowed, because narrowing reverses it. */
    public static final String WHY_EXCLUDES = "excludes_someone";

    /**
     * Option 2 for one command: {@code owner} is the wisher's name, {@code others} the names of
     * every OTHER child online. With nobody else online nothing changes, so a single-child world
     * runs every command byte for byte as before.
     */
    public static Decision narrow(String command, String owner, Collection<String> others) {
        if (command == null || owner == null || others == null || others.isEmpty()) {
            return new Decision(command, List.of(), null, null);
        }
        List<String> lowerOthers = new ArrayList<>();
        for (String o : others) {
            if (o != null && !o.equalsIgnoreCase(owner)) {
                lowerOthers.add(o.toLowerCase(Locale.ROOT));
            }
        }
        if (lowerOthers.isEmpty()) {
            return new Decision(command, List.of(), null, null);
        }
        String named = namedChild(command, lowerOthers);
        if (named != null) {
            return new Decision(command, List.of(), named, WHY_NAMED);
        }
        StringBuilder out = new StringBuilder();
        List<String> changed = new ArrayList<>();
        Matcher m = SELECTOR.matcher(command);
        int last = 0;
        String why = null;
        while (m.find()) {
            char kind = m.group(1).charAt(0);
            String args = m.group(2);
            String positiveName = positiveName(args);
            String replacement = m.group();
            if (positiveName != null && lowerOthers.contains(positiveName.toLowerCase(Locale.ROOT))) {
                return new Decision(command, List.of(), m.group(), WHY_NAMED);
            }
            if (positiveName == null && negatedName(args) && kind != 's') {
                // "Everyone but X" aims PAST someone. Rewriting it to the wisher reversed it into a
                // silent no-op reported as a granted wish — refuse it out loud instead.
                return new Decision(command, List.of(), m.group(), WHY_EXCLUDES);
            }
            switch (kind) {
                case 'p', 'a', 'r' -> {
                    if (positiveName == null) {
                        replacement = "@" + kind + "[" + withoutNames(args)
                                + (withoutNames(args).isEmpty() ? "" : ",") + "name=" + owner + "]";
                        changed.add(m.group() + " → " + replacement);
                        why = WHY_EVERYONE;
                    }
                }
                case 'e', 'n' -> {
                    if (positiveName == null && !restrictedToNonPlayers(args)) {
                        return new Decision(command, List.of(), m.group(), WHY_ANY_ENTITY);
                    }
                }
                default -> {
                    // @s: the wisher herself.
                }
            }
            out.append(command, last, m.start()).append(replacement);
            last = m.end();
        }
        out.append(command.substring(last));
        return new Decision(out.toString(), List.copyOf(changed), null, why);
    }

    /** The value of a positive {@code name=} argument, or null. */
    private static String positiveName(String args) {
        if (args == null) {
            return null;
        }
        for (String part : args.substring(1, args.length() - 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("name")) {
                String v = part.substring(eq + 1).trim();
                if (!v.startsWith("!")) {
                    return v.replace("\"", "").replace("'", "");
                }
            }
        }
        return null;
    }

    /** Is there a negated {@code name=!…} argument? */
    private static boolean negatedName(String args) {
        if (args == null) {
            return false;
        }
        for (String part : args.substring(1, args.length() - 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("name")
                    && part.substring(eq + 1).trim().startsWith("!")) {
                return true;
            }
        }
        return false;
    }

    /** The arguments inside {@code [...]} with every {@code name=} removed, or "". */
    private static String withoutNames(String args) {
        if (args == null) {
            return "";
        }
        List<String> keep = new ArrayList<>();
        for (String part : args.substring(1, args.length() - 1).split(",")) {
            int eq = part.indexOf('=');
            boolean name = eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("name");
            if (!name && !part.isBlank()) {
                keep.add(part.trim());
            }
        }
        return String.join(",", keep);
    }

    /**
     * Another child's name written as a bare word at the top level of the command (outside any
     * quotes, braces or brackets) — {@code give Bob …}, {@code tp Bob …}. Not in {@code say}/{@code
     * me}, where it is a word in a sentence.
     */
    private static String namedChild(String command, List<String> lowerOthers) {
        String trimmed = command.strip();
        String root = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) {
            root = root.substring(1);
        }
        if (root.equals("say") || root.equals("me")) {
            return null;
        }
        int depth = 0;
        char quote = 0;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= trimmed.length(); i++) {
            char c = i < trimmed.length() ? trimmed.charAt(i) : ' ';
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                word.setLength(0);
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
                word.setLength(0);
                continue;
            }
            if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
                word.setLength(0);
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (depth == 0 && word.length() > 0
                        && lowerOthers.contains(word.toString().toLowerCase(Locale.ROOT))) {
                    return word.toString();
                }
                word.setLength(0);
                continue;
            }
            word.append(c);
        }
        return null;
    }
}
