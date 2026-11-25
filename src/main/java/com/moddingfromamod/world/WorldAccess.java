package com.moddingfromamod.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.function.Supplier;

/**
 * <b>Could this world's access settings be read?</b> (#82) A world's manifest ({@code worlds/<id>.json}) may say, in its
 * {@code crafters} block, that players off the server's allow list are answered by the Apprentice only
 * ({@link WorldCrafters}). When those settings are missing where a world expects them, cannot be read or are malformed,
 * the core carries on with its default: the door shut, so everyone the Magician is set up for is answered by it. That
 * default is right (a typing mistake must never switch the Magician off for everyone), but it should not be silent.
 * Nor should the case where the door is open while the server's allow list is off: vanilla then counts every player
 * as on the list, so nobody is answered by the Apprentice only.
 *
 * <p>Classified once at server start and said in ONE log line when something is wrong ({@code CrafterService}).
 * A warning only: nothing is refused and nothing changes for players. Single player, and a world whose manifest has
 * no {@code crafters} block, stay quiet.
 *
 * <p><b>For a server plug-in</b>: {@link #current()} is this state, so a server can show it in its own health report
 * ({@code docs/EXTENDING.md}, "Reading a world's access state"). Pure; {@code WorldAccessTest}.
 */
public record WorldAccess(State state, String world, String line) {

    public enum State {
        /** No world here expects access settings (single player, a plain server), or the manifest has none. */
        QUIET,
        /** Read, and consistent with the server. */
        READ,
        /** A world id is set but no jar carries its manifest. */
        MISSING,
        /** Reading the manifest failed. */
        UNREADABLE,
        /** The manifest, or its {@code crafters} block, is not valid. */
        MALFORMED,
        /** The door is open but the server's allow list (white-list) is off. */
        ALLOW_LIST_OFF
    }

    public WorldAccess {
        world = world == null ? "" : world;
        line = line == null ? "" : line;
    }

    /** Should this be said at startup? */
    public boolean warns() {
        return !line.isEmpty();
    }

    /** Before the server has started, or with none: quiet. */
    public static final WorldAccess UNKNOWN = new WorldAccess(State.QUIET, "", "");

    private static volatile WorldAccess current = UNKNOWN;

    /** This server's state, as classified at its start ({@link #UNKNOWN} before then). */
    public static WorldAccess current() {
        return current;
    }

    /** Set at server start by the core. */
    public static void publish(WorldAccess a) {
        current = a == null ? UNKNOWN : a;
    }

    private static final String DEFAULT =
            "carrying on with the default: every player the Magician is set up for is answered by it";

    /**
     * Classify this world's access settings.
     *
     * @param worldId     the world's manifest id ({@code WorldFacts.manifestId}); blank where no world expects one
     * @param text        the manifest's text, null when no jar carries it; may throw
     * @param allowListOn is the server's allow list (white-list) switched on?
     */
    public static WorldAccess classify(String worldId, Supplier<String> text, boolean allowListOn) {
        String id = worldId == null ? "" : worldId.strip();
        if (id.isEmpty()) {
            return new WorldAccess(State.QUIET, "", "");
        }
        String file = "worlds/" + id + ".json";
        String body;
        try {
            body = text == null ? null : text.get();
        } catch (RuntimeException e) {
            return new WorldAccess(State.UNREADABLE, id, "world access: " + file + " (world '" + id + "') could not be read ("
                    + e.getClass().getSimpleName() + "); " + DEFAULT + ". Nothing is refused.");
        }
        if (body == null) {
            return new WorldAccess(State.MISSING, id, "world access: " + file + " (world '" + id + "') was not found; "
                    + DEFAULT + ". Nothing is refused.");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            root = null;
        }
        if (root == null || !root.isJsonObject()) {
            return malformed(id, file, "it is not a JSON object");
        }
        JsonElement crafters = root.getAsJsonObject().get("crafters");
        if (crafters == null) {
            return new WorldAccess(State.QUIET, id, "");
        }
        if (!crafters.isJsonObject()) {
            return malformed(id, file, "its \"crafters\" block is not an object");
        }
        if (WorldCrafters.parse(body).open() && !allowListOn) {
            return new WorldAccess(State.ALLOW_LIST_OFF, id, "world access: world '" + id + "' answers players off the "
                    + "allow list with the Apprentice only, but the server's allow list (white-list) is off, so every "
                    + "player counts as on it and is answered by the Magician where it is on. Turn white-list on in "
                    + "server.properties to keep Apprentice-only players. Nothing is refused.");
        }
        return new WorldAccess(State.READ, id, "");
    }

    private static WorldAccess malformed(String id, String file, String why) {
        return new WorldAccess(State.MALFORMED, id, "world access: " + file + " (world '" + id + "') is malformed ("
                + why + "); " + DEFAULT + ". Nothing is refused.");
    }
}
