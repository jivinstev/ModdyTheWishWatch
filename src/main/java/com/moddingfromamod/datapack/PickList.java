package com.moddingfromamod.datapack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>A parameter that is a LIST, picked from each time it is used (T6F follow-up).</b>
 *
 * <p>"A brush that paints blocks rainbow": {@code change_block} took exactly one {@code BLOCK}, so
 * every run in an earlier pass either painted one colour or handed him six brushes, one per colour —
 * the agent itself said so. A list plus a mode says the wish whole, in the SAME parameter
 * ({@code "cycle: minecraft:red_wool, minecraft:orange_wool"}), because every catalogue parameter is
 * required and a new one would have to be passed on every change_block ever made:
 * <ul>
 *   <li><b>random</b> — {@code /random value 0..N-1}, a different one each use, no state;</li>
 *   <li><b>cycle</b> — the next one each use, in order (red, orange, yellow…), counted on one score
 *       per list, so a rainbow comes out in rainbow order.</li>
 * </ul>
 *
 * <p>A template cannot loop, so the tool renders the lines here, the way {@code summon} renders its
 * COUNT copies. Minecraft-free on purpose: the rendering is asserted by a unit test, and only the
 * game can say whether the rendered commands do what they say (ChangeBlockGameTest).
 */
public final class PickList {

    private PickList() {
    }

    /** How many a list may hold — a rainbow is seven, and every wool colour is sixteen. */
    public static final int MAX = 16;

    /** The scoreboard every pick counts on. One objective; each list is its own score holder. */
    public static final String OBJECTIVE = "mfam_pick";

    /**
     * The mode a value names in front of its list — {@code "cycle: a, b"} or {@code "random: a, b"} —
     * or null when it names none. There is no default on purpose (the item catalogue has none): a
     * list with no mode in front is refused rather than guessed.
     */
    public static String modeOf(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (String m : new String[]{"cycle", "random"}) {
            if (s.startsWith(m + ":") && !s.startsWith(m + "::")) {
                return m;
            }
        }
        return null;
    }

    /** The value with its mode word taken off the front, if it had one. */
    public static String withoutMode(String raw) {
        String m = modeOf(raw);
        return m == null ? raw : raw.trim().substring(m.length() + 1);
    }

    /** The items of a comma-separated list, trimmed, empties dropped. One item is a list of one. */
    public static List<String> items(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The function lines (each indented and starting {@code /}) that run ONE of {@code commands}:
     * just that command for a list of one, else a pick by {@code mode}. The commands are already
     * rendered from validated values; nothing here reads what the agent typed.
     */
    public static String render(List<String> commands, String mode) {
        if (commands.size() == 1) {
            return "    /" + commands.get(0);
        }
        String holder = "#" + key(commands, mode);
        String score = holder + ' ' + OBJECTIVE;
        StringBuilder b = new StringBuilder();
        b.append("    /scoreboard objectives add ").append(OBJECTIVE).append(" dummy\n");
        if ("cycle".equals(mode)) {
            // `add 0` gives an unset holder a score of 0 — an unset score matches NOTHING, so the
            // first use would paint nothing. Pick by the current count, THEN step it on and wrap,
            // so the first use is the first in the list (red, then orange).
            b.append("    /scoreboard players add ").append(score).append(" 0\n");
        } else {
            b.append("    /execute store result score ").append(score)
             .append(" run random value 0..").append(commands.size() - 1).append('\n');
        }
        for (int i = 0; i < commands.size(); i++) {
            b.append("    /execute if score ").append(score).append(" matches ").append(i).append(" run ")
             .append(commands.get(i)).append('\n');
        }
        if ("cycle".equals(mode)) {
            b.append("    /scoreboard players add ").append(score).append(" 1\n");
            b.append("    /execute if score ").append(score).append(" matches ").append(commands.size())
             .append(".. run scoreboard players set ").append(score).append(" 0\n");
        }
        b.setLength(b.length() - 1);   // no trailing newline: the template supplies its own
        return b.toString();
    }

    /** A stable, legal score-holder name for this list and mode: same list, same counter. */
    static String key(List<String> commands, String mode) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(
                    (mode + "\n" + String.join("\n", commands)).getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder("pick_");
            for (int i = 0; i < 5; i++) {
                h.append(String.format("%02x", d[i]));
            }
            return h.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
