package com.moddingfromamod.wish;

import java.util.UUID;

/**
 * <b>A player who is always a Magician</b>, whatever the world's Crafter list says. A server's canary asks
 * "is Moddy still GRANTING wishes", so its wish must always go to the agent; the canary is a server's own, so its
 * id is answered by a plug-in. With none registered, nobody is.
 *
 * <p><b>An internal seam, not a documented extension point</b>.
 */
public interface AlwaysMagician {

    boolean is(UUID playerId);

    /** Does any registered plug-in say this player is always a Magician? */
    static boolean anyone(UUID playerId) {
        for (AlwaysMagician a : com.moddingfromamod.plugin.Plugins.shared().all(AlwaysMagician.class)) {
            if (a.is(playerId)) {
                return true;
            }
        }
        return false;
    }
}
