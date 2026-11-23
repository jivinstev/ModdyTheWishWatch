package com.moddingfromamod.datapack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A datapack that makes noise on EVERY tick, forever, is refused at compile time</b>.
 *
 * <p>The owner, of an old T-Rex whose knockback telegraphed 20 times a second with nobody near it:
 * <i>"It's creating a terrible animation."</i> A sound — or a big particle burst — on every tick is
 * never what a child wished for: "it roars" is a roar now and then, "it stomps" is a stomp when it
 * lands. So this refuses exactly that shape, and nothing else:
 * <ul>
 *   <li>a {@code playsound} that</li>
 *   <li>runs from a {@code clock tick} (directly or through a function it calls), with</li>
 *   <li><b>no gate</b> on the way: no {@code if}/{@code unless}/{@code while}/{@code for} block, no
 *   {@code execute if|unless}, and no selector that asks anything but WHICH always-present entity
 *   it is — a player, or the creature itself ({@code tag=wishmob_…}). A {@code distance=}, a
 *   {@code scores=}, an {@code nbt=}, a predicate, a named selector ({@code @NewMachine}) or any
 *   other tag (a projectile, a fresh marker) is an event, and passes.</li>
 * </ul>
 *
 * <p>⚠ <b>Deliberately narrow: sounds only.</b> Particles every tick are a LOOK a child asks for — a
 * fire ghost, a blizzard, a trail — and are never refused here; a burst that drowns the screen is
 * {@link NoiseMeter}'s to throttle at runtime, and so is a counter that is true on every tick
 * anyway (the {@code impulse} template at {@code PERIOD_TICKS=1}), which a reading of the source
 * cannot see. Measured before shipping against every production, starter and template pack and
 * the 141-source real corpus (the scan): it refuses none of them.
 *
 * <p>Pure: no Minecraft. {@link CbScriptCompiler#compile} asks it on every compile, after the
 * safety sweep, so author_datapack, make_item, apply_effect and the GameTests share one hook.
 */
public final class NoiseLint {

    private static final Set<String> OPENERS = Set.of("clock", "function", "reset", "if", "unless",
            "as", "at", "while", "for");
    private static final Set<String> GATES = Set.of("if", "unless", "while", "for");
    private static final Pattern CALL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\(\\)$");
    private static final Pattern SELECTOR = Pattern.compile("@[aeprsn]\\[([^\\]]*)\\]");
    private static final Set<String> IDENTITY_KEYS = Set.of("tag", "type", "limit", "sort");
    /** A selector by name ({@code @NewMachine}): what it picks is unknown here, so it is an event. */
    private static final Pattern NAMED = Pattern.compile("@[A-Z][A-Za-z0-9_]*");

    private NoiseLint() {
    }

    /** One offending line, as the refusal names it. */
    public record Finding(int line, String text) {
    }

    /** Null when the source passes; else the message the agent gets as a compile error. */
    public static String check(String source) {
        List<Finding> f = findings(source);
        if (f.isEmpty()) {
            return null;
        }
        Finding first = f.get(0);
        return "This datapack would play a sound on EVERY tick, forever — 20 times a second with nobody "
                + "doing anything (line " + first.line() + ": `" + first.text() + "`). A child hears a "
                + "constant drone, not a roar or a stomp. Play it on an EVENT (a hit, a landing, someone "
                + "coming near: a `distance=`/`scores=`/`nbt=` in the selector, or `execute if ...`) or "
                + "on a TIMER (a counter: `t = t + 1` then `if t > 40`, reset it inside). Particles "
                + "every tick are fine.";
    }

    /** Every ungated playsound reachable from a {@code clock tick}. */
    public static List<Finding> findings(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] lines = source.split("\n", -1);
        // Pass 1: block structure. For each line, its enclosing headers (outermost first).
        List<List<String>> enclosing = new ArrayList<>();
        Map<String, int[]> functions = new HashMap<>();        // name -> [first body line, end line]
        Deque<int[]> open = new ArrayDeque<>();                 // [header line index]
        for (int i = 0; i < lines.length; i++) {
            String s = strip(lines[i]);
            List<String> heads = new ArrayList<>();
            for (int[] o : open) {
                heads.add(0, strip(lines[o[0]]));
            }
            enclosing.add(heads);
            if (s.isEmpty() || s.startsWith("/")) {
                continue;
            }
            String w = firstWord(s);
            if ("end".equals(w)) {
                int[] o = open.poll();
                if (o != null && "function".equals(firstWord(strip(lines[o[0]])))) {
                    String name = strip(lines[o[0]]).substring("function".length()).strip()
                            .replaceAll("\\(.*$", "").strip();
                    functions.put(name, new int[]{o[0] + 1, i});
                }
            } else if (OPENERS.contains(w)) {
                open.push(new int[]{i});
            }
        }
        // Pass 2: walk each clock tick's body; follow ungated calls one function at a time.
        List<Finding> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String s = strip(lines[i]);
            if (!"clock".equals(firstWord(s))) {
                continue;
            }
            int end = endOf(lines, i);
            walk(lines, enclosing, functions, i + 1, end, enclosing.get(i).size() + 1, out, seen,
                    new HashSet<>());
        }
        return out;
    }

    private static void walk(String[] lines, List<List<String>> enclosing, Map<String, int[]> functions,
                             int from, int to, int depth, List<Finding> out, Set<Integer> seen,
                             Set<String> visiting) {
        for (int i = from; i < to; i++) {
            List<String> heads = enclosing.get(i);
            boolean gated = false;
            for (int h = depth; h < heads.size(); h++) {
                gated |= gates(heads.get(h));
            }
            if (gated) {
                continue;
            }
            String s = strip(lines[i]);
            Matcher call = CALL.matcher(s);
            if (call.matches()) {
                int[] body = functions.get(call.group(1));
                if (body != null && visiting.add(call.group(1))) {
                    walk(lines, enclosing, functions, body[0], body[1], enclosing.get(body[0]).size(),
                            out, seen, visiting);
                    visiting.remove(call.group(1));
                }
                continue;
            }
            if (s.startsWith("/") && loud(s) && !commandGated(s) && seen.add(i)) {
                out.add(new Finding(i + 1, s.length() > 120 ? s.substring(0, 120) + "…" : s));
            }
        }
    }

    /** The line index of the {@code end} closing the block opened at {@code at}. */
    private static int endOf(String[] lines, int at) {
        int depth = 0;
        for (int i = at; i < lines.length; i++) {
            String s = strip(lines[i]);
            if (s.isEmpty() || s.startsWith("/")) {
                continue;
            }
            String w = firstWord(s);
            if (OPENERS.contains(w)) {
                depth++;
            } else if ("end".equals(w) && --depth == 0) {
                return i;
            }
        }
        return lines.length;
    }

    /** Does this block header gate what is inside it? */
    static boolean gates(String header) {
        String w = firstWord(header);
        if (GATES.contains(w)) {
            return true;
        }
        if ("as".equals(w) || "at".equals(w)) {
            return selectorsAsk(header);
        }
        return false;                       // clock / function / reset: no condition
    }

    /** {@code /execute if|unless …}, or a selector that asks more than which entity. */
    static boolean commandGated(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1).strip() : cmd;
        if (!c.startsWith("execute ")) {
            return false;
        }
        int run = c.indexOf(" run ");
        String chain = run < 0 ? c : c.substring(0, run);
        if (chain.matches(".*\\s(if|unless)\\s.*")) {
            return true;
        }
        return selectorsAsk(chain);
    }

    /**
     * True when a selector in {@code text} picks something that is not always there: it filters on
     * anything but identity, names a tag that is not the creature's own, or is a named selector.
     */
    static boolean selectorsAsk(String text) {
        if (NAMED.matcher(text).find()) {
            return true;
        }
        Matcher m = SELECTOR.matcher(text);
        while (m.find()) {
            for (String kv : m.group(1).split(",")) {
                String[] p = kv.split("=", 2);
                String k = p[0].strip();
                if (k.isEmpty()) {
                    continue;
                }
                if (!IDENTITY_KEYS.contains(k)) {
                    return true;
                }
                if ("tag".equals(k) && p.length > 1) {
                    String v = p[1].strip();
                    if (!v.startsWith("!") && !v.startsWith("wishmob_")) {
                        return true;                // a marker, a projectile, a "_new": transient
                    }
                }
            }
        }
        return false;
    }

    /** A playsound, run directly or at the end of an execute chain. */
    static boolean loud(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1).strip() : cmd;
        int run = c.lastIndexOf(" run ");
        String tail = c.startsWith("execute ") && run >= 0 ? c.substring(run + 5).strip() : c;
        return tail.startsWith("playsound ");
    }

    private static String strip(String line) {
        String s = line.strip();
        if (s.startsWith("#")) {
            return "";
        }
        return s;
    }

    private static String firstWord(String s) {
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }
}
