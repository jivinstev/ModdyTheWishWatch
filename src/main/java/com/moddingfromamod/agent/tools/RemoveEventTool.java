package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.design.EventDesigns;
import net.minecraft.server.MinecraftServer;

/**
 * <b>Give the world back</b> (E5): an event by id is stopped and every block it changed is put
 * back (only where it still holds what the event wrote — whatever he built on top since stays); a
 * design by name is forgotten along with its "every N days".
 */
public final class RemoveEventTool implements McpTool {

    @Override
    public String name() {
        return "remove_event";
    }

    @Override
    public String description() {
        return "Undo a world event. `id` (from stage_event's reply or list_events): stop it if it is still "
                + "going and put back every block it changed — a crater is filled, a flood drained, the "
                + "chickens it rained taken away; anything he built on top since is kept. `name`: forget a "
                + "kept design and stop it coming back every few days (anything it already did stays until "
                + "you remove its id).";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "An event happening or standing now.");
        Schemas.prop(s, "name", "string", "A kept design to forget.");
        return Schemas.requireOneOf(s, "id", "name");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("id") ? args.get("id").getAsString() : null;
        String name = args.has("name") ? args.get("name").getAsString() : null;
        if ((id == null || id.isBlank()) && (name == null || name.isBlank())) {
            return ToolResult.error("remove_event needs `id` (an event happening or standing now, from "
                    + "list_events) or `name` (a kept design). Call it again with one of them.");
        }
        try {
            return AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                if (id != null) {
                    if (!EventDirector.undo(server, id)) {
                        return ToolResult.error("There is nothing to undo called '" + id + "' (it may already "
                                + "have put everything back). " + ListEventsTool.listing(server));
                    }
                    return ToolResult.ok("Putting back everything " + id + " changed; it takes a moment for a "
                            + "big one.");
                }
                if (!EventDesigns.forget(server, name)) {
                    return ToolResult.error("There is no event design called '" + name + "'. "
                            + ListEventsTool.listing(server));
                }
                return ToolResult.ok("Forgot '" + name + "'; it will not happen again by itself.");
            });
        } catch (Exception e) {
            return ToolResult.error("remove_event failed: " + e.getMessage());
        }
    }
}
