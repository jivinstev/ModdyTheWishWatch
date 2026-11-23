package com.moddingfromamod.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.plugin.Plugins;

/**
 * The hook for a proxy's player identity (epic). On our servers the private {@code identity}
 * plug-in answers it: a player moved to a new key keeps his old ids (step 8), and a login the proxy did not
 * vouch for is told to try again. The public single-player mod has no proxy in front, so with no hook a player
 * has exactly the one id he plays as, and every login is checked.
 *
 * <p>Register it with {@code Plugins.add(PlayerIdentity.class, impl)}; the core asks the static methods here.
 *
 * <p><b>An internal seam, not an extension point</b>: it exists for our own servers' proxy and may change
 * without notice. Do not build on it.
 */
public interface PlayerIdentity {

    /** The refusal a login hears when its identity could not be checked: a forwarding failure, so "try again". */
    String TRY_AGAIN = "We couldn't check who you are. Try joining again!";

    /** His id first, then every id he moved from. */
    List<String> aliasesOf(String playerId);

    /** True, once, when this profile's login was let through unforwarded in the last minute. */
    boolean takeUnforwarded(UUID id, long nowMs);

    private static Optional<PlayerIdentity> hook() {
        return Plugins.shared().first(PlayerIdentity.class);
    }

    /**
     * His id first, then every id he moved from; lower-case. A blank id has none. Without the plug-in, just his own
     * id: nobody has ever moved.
     */
    static List<String> idsOf(String playerId) {
        Optional<PlayerIdentity> h = hook();
        if (h.isPresent()) {
            return h.get().aliasesOf(playerId);
        }
        List<String> out = new ArrayList<>();
        if (playerId != null && !playerId.isBlank()) {
            out.add(playerId.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Was this login let through unforwarded? Never without the plug-in. */
    static boolean unforwarded(UUID id, long nowMs) {
        return hook().map(h -> h.takeUnforwarded(id, nowMs)).orElse(false);
    }
}
