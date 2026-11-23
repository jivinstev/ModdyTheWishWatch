package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>{@code run_command}'s {@code at}: every command run ON a named place</b> — "cars in my
 * garage" is {@code summon minecraft:minecart ~ ~ ~} with {@code at: "garage:inside"}, and
 * {@code ~ ~ ~} is the garage's floor, facing its way, once it is finished.
 *
 * <p>It is exactly {@code wishspot <at> run <command>} per command, written here once so the tool,
 * the template it teaches and a replay all say the same thing. Pure — {@code AtCommandsTest}.
 */
public final class AtCommands {

    private AtCommands() {
    }

    /** A command already on a spot: left alone rather than wrapped twice. */
    public static boolean isOnASpot(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        return c.toLowerCase(Locale.ROOT).startsWith("wishspot ");
    }

    /**
     * {@code commands}, each run at {@code at}; unchanged when {@code at} is blank. Null when
     * {@code at} does not parse — the caller refuses rather than running them where he stands.
     */
    public static List<String> wrap(List<String> commands, String at) {
        if (at == null || at.isBlank()) {
            return commands;
        }
        At a = At.parse(at);
        if (a == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            String t = c.trim();
            if (t.startsWith("/")) {
                t = t.substring(1).trim();
            }
            out.add(isOnASpot(t) ? t : "wishspot " + a + " run " + t);
        }
        return out;
    }
}
