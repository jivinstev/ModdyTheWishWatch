package com.moddingfromamod.seam;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Is this player a GameTest's mock?</b> The question shipped code asks (the watch pin, the vanilla watch item); the
 * mocks themselves are made by the GameTest harness ({@code testing/MockPlayerNetwork}), which never ships.
 *
 * <p>A mock is one named {@link #MOCK_NAME}, or one the harness marked when it made it. Nothing that ships marks a
 * player, and no real player can take the name (it is not a valid Minecraft account name), so on every real server
 * this answers false.
 */
public final class MockPlayers {

    /** The name every unnamed mock shares. */
    public static final String MOCK_NAME = "mfam-mock";

    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();

    private MockPlayers() {
    }

    /** The harness made this player. */
    public static void mark(UUID id) {
        MARKED.add(id);
    }

    /** The harness took this player away. */
    public static void unmark(UUID id) {
        MARKED.remove(id);
    }

    /** True for a mock the harness made, whatever it is called. */
    public static boolean is(ServerPlayer p) {
        return p != null && (MOCK_NAME.equals(p.getGameProfile().name()) || MARKED.contains(p.getUUID()));
    }
}
