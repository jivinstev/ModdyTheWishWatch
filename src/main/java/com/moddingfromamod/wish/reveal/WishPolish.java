package com.moddingfromamod.wish.reveal;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.vanilla.WishShimmer;
import com.moddingfromamod.wish.WishFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A polish in place, after the wish ended, is shown and said quietly</b>.
 *
 * <p>{@code AfterFinish.polish} decides what may still run; this is what he notices when it does:
 * a creature glints for two turns of the rainbow ({@link WishShimmer#pulse}), and one grey line
 * says Moddy gave it a little polish, once per wish however many calls the polish takes. An item
 * changes in his hand and a rule changes what it does, so those get the line alone.
 */
public final class WishPolish {

    private WishPolish() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-polish");

    /** Wishes that have already said their polish line. */
    private static final Set<UUID> SAID = ConcurrentHashMap.newKeySet();

    /**
     * Called by {@code McpProtocol} after a successful call of a finished wish. Any thread.
     *
     * @param polished {@code AfterFinish.polish}'s answer: the id of what was polished, or null
     */
    public static void afterTool(WishRun run, String tool, JsonObject args, String polished) {
        if (run == null || polished == null) {
            return;
        }
        final String name = nameOf(tool, args, polished);
        final boolean creature = "modify_mob".equals(tool);
        Runnable show = () -> {
            var server = AgentBridge.serverOrNull();
            if (server == null) {
                return;
            }
            if (creature) {
                WishShimmer.pulse(polished, server.overworld().getGameTime());
            }
            var owner = run.playerId() == null ? null
                    : server.getPlayerList().getPlayer(run.playerId());
            if (owner != null && SAID.add(run.wishId())) {
                WishFeedback.polished(owner, name);
            }
            LOG.info("[polish] wish {}: {} polished '{}' in place after the finish",
                    run.shortId(), tool, polished);
        };
        try {
            var server = AgentBridge.serverOrNull();
            if (server != null && server.isSameThread()) {
                show.run();
            } else {
                AgentBridge.onServerRun(show);
            }
        } catch (AgentBridge.Unavailable e) {
            LOG.warn("[polish] wish {}: could not reach the server: {}", run.shortId(), e.getMessage());
        }
    }

    /** What he calls it: a creature's species name, an item's name, or "" for a rule ("it"). */
    static String nameOf(String tool, JsonObject args, String polished) {
        if ("modify_mob".equals(tool)) {
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(polished);
            return sp == null || sp.name() == null ? "" : sp.name();
        }
        if ("make_item".equals(tool) && args != null && args.has("name")
                && args.get("name").isJsonPrimitive()) {
            return args.get("name").getAsString();
        }
        return "";
    }

    /** For tests. */
    public static void forgetForTest() {
        SAID.clear();
    }
}
