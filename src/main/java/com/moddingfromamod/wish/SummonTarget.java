package com.moddingfromamod.wish;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Where a {@code /summon} is going to put something —.
 *
 * <p>Pure and string-only, so the rule is a unit test rather than a thing you need a server to ask.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A summon into a chunk that is not loaded <b>succeeds</b> and produces an entity that no
 * selector can address. Measured, one command after the other on the same tick:
 *
 * <pre>
 *   near (chunk loaded)     summon ok   @e[tag=…] FOUND
 *   far  (chunk NOT loaded) summon ok   @e[tag=…] "No entity was found"
 * </pre>
 *
 * <p>That is the whole. The original diagnosis was a tick-timing one — that a batch runs
 * inside a single server-thread hop, so the follow-up executes "before the entity is queryable" —
 * and it is wrong: a nearby summon is queryable by the very next command in the same batch. What
 * decides it is whether the target chunk is loaded, and the agent gets a cheerful {@code Ran, OK}
 * either way.
 *
 * <p>The cost of not knowing was three zombies. The agent summoned, could not address what it had
 * summoned, and tried again — twice — for a child who asked for one.
 *
 * <h2>Only ABSOLUTE coordinates</h2>
 *
 * <p>Relative ({@code ~}) and local ({@code ^}) coordinates are resolved against the command's own
 * source, which is the child, who is by definition standing in a loaded chunk — or, with nobody to
 * stand beside, the world spawn, which the caller holds with {@code place.SpawnHold} since 1.21.9
 * stopped keeping it loaded. So they are returned as absent rather than parsed: there is nothing to
 * fix and no reason to touch the world.
 */
public final class SummonTarget {

    private SummonTarget() {}

    /** A summon's absolute target, or absent when there is none to be sure of. */
    public record At(int x, int y, int z) {}

    /**
     * The absolute block position a {@code summon} names, if it names one.
     *
     * <p>Absent for anything that is not a summon, for a summon with no coordinates (which lands on
     * the source's own position), and for {@code ~}/{@code ^} forms.
     */
    public static java.util.Optional<At> of(String command) {
        if (command == null) {
            return java.util.Optional.empty();
        }
        String c = command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1).trim();
        }
        if (!c.toLowerCase(Locale.ROOT).startsWith("summon ")) {
            return java.util.Optional.empty();
        }
        // summon <entity> [<x> <y> <z> [<nbt>]] — the three tokens after the id, when present.
        String[] parts = c.split("\\s+");
        if (parts.length < 5) {
            return java.util.Optional.empty();   // no coordinates given
        }
        OptionalInt x = coord(parts[2]);
        OptionalInt y = coord(parts[3]);
        OptionalInt z = coord(parts[4]);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new At(x.getAsInt(), y.getAsInt(), z.getAsInt()));
    }

    /**
     * One coordinate, floored to a block, or absent if it is relative/local/not a number.
     *
     * <p>Floored rather than truncated: {@code -0.5} is in block {@code -1}, and truncation puts it
     * in {@code 0}. Only matters at a chunk boundary, which is exactly where this is used.
     */
    private static OptionalInt coord(String token) {
        if (token == null || token.isEmpty() || token.charAt(0) == '~' || token.charAt(0) == '^') {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of((int) Math.floor(Double.parseDouble(token)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
