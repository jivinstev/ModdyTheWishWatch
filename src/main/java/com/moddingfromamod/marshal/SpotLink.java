package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>A wish's parts remember where its creatures sat</b>: "boss sits on castle:throne".
 *
 * <p>A learned recipe is a list of commands, so the link lives where a replay can act on it — in
 * the creature's own line: {@code wishmob spawn boss at castle:throne}. Re-wishing the WHOLE wish
 * runs {@code wishbuild castle} and then that line, which waits for the castle and seats him; the
 * boss's PART recipe is that line alone ({@link TaughtJoin#commandsFor} — the {@code at} names no
 * thing, so the castle's part never spawns the boss), which seats him on the throne while the
 * castle stands and where he aims when it does not.
 *
 * <p>The agent need not have written it: a recipe taught as plain {@code wishmob spawn boss} by a
 * wish that SEATED the boss is given the link here ({@link #carry}), so a template and its replay
 * carry what the wish did rather than what the agent remembered to type.
 *
 * <p>Pure — no Minecraft imports — so the rule is {@code SpotLinkTest}.
 */
public final class SpotLink {

    private SpotLink() {
    }

    /** {@code [creature id, count-or-null, at-or-null]} of a {@code wishmob spawn} line, or null. */
    static String[] spawnLine(String command) {
        if (command == null) {
            return null;
        }
        String c = command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        String[] t = c.split("\\s+");
        if (t.length < 3 || !t[0].equalsIgnoreCase("wishmob") || !t[1].equalsIgnoreCase("spawn")) {
            return null;
        }
        String count = null, at = null;
        int i = 3;
        if (i < t.length && t[i].matches("\\d+")) {
            count = t[i++];
        }
        if (i + 1 < t.length && t[i].equalsIgnoreCase("at")) {
            at = String.join(" ", java.util.Arrays.copyOfRange(t, i + 1, t.length)).toLowerCase(Locale.ROOT);
        }
        return new String[]{t[2].toLowerCase(Locale.ROOT), count, at};
    }

    /** Creature id → {@code "<build>:<spot>"} for every {@code wishmob spawn … at} line. */
    public static Map<String, String> linksOf(List<String> commands) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : commands == null ? List.<String>of() : commands) {
            String[] s = spawnLine(c);
            if (s != null && s[2] != null) {
                out.put(s[0], s[2]);
            }
        }
        return out;
    }

    /**
     * <b>Give a recipe the seats its wish made.</b> Every {@code wishmob spawn <id>} with no
     * {@code at} whose creature this wish seated gets {@code at <build>:<spot>}; and a seated
     * spawn that comes BEFORE its building's {@code wishbuild} moves to just after it, so a whole
     * re-wish puts the castle up first. Lines already carrying an {@code at} are the agent's and
     * are left alone. Returns the commands unchanged (the same list) when there is nothing to do.
     *
     * @param seats creature id → {@code "<build>:<spot>"} ({@link WishRun#seats()})
     * @param notes what changed, for the agent
     */
    public static List<String> carry(List<String> commands, Map<String, String> seats, List<String> notes) {
        if (commands == null || commands.isEmpty() || seats == null || seats.isEmpty()) {
            return commands;
        }
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String c : commands) {
            String[] s = spawnLine(c);
            String seat = s == null ? null : seats.get(s[0]);
            if (s != null && s[2] == null && seat != null) {
                String linked = c.trim() + " at " + seat;
                out.add(linked);
                notes.add("`" + c.trim() + "` → `" + linked + "`");
                changed = true;
            } else {
                out.add(c);
            }
        }
        // The building first: a seat whose building comes later would replay onto the OLD castle.
        for (int i = 0; i < out.size(); i++) {
            String[] s = spawnLine(out.get(i));
            if (s == null || s[2] == null) {
                continue;
            }
            String build = s[2].contains(":") ? s[2].substring(0, s[2].lastIndexOf(':')) : s[2];
            int b = buildLine(out, build);
            if (b > i) {
                String line = out.remove(i);
                out.add(b, line);     // b shifted down by one: lands just after the build
                changed = true;
                i--;
            }
        }
        return changed ? out : commands;
    }

    private static int buildLine(List<String> commands, String build) {
        for (int i = 0; i < commands.size(); i++) {
            String[] t = commands.get(i).trim().replaceFirst("^/", "").split("\\s+");
            if (t.length >= 2 && t[0].equalsIgnoreCase("wishbuild") && t[1].equalsIgnoreCase(build)) {
                return i;
            }
        }
        return -1;
    }
}
