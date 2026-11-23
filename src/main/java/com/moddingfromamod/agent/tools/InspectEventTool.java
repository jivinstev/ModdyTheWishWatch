package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.event.design.EventDesigns;
import net.minecraft.server.MinecraftServer;

/**
 * <b>One event design, exactly as kept, or one event as it is happening</b> (E5). A re-wish
 * starts here: the design comes back as the same JSON stage_event takes, so the agent changes the one
 * field he asked about and stages it under the same name, instead of re-authoring the whole event.
 */
public final class InspectEventTool implements McpTool {

    @Override
    public String name() {
        return "inspect_event";
    }

    @Override
    public String description() {
        return "One world event: give `name` for a kept design (it comes back as the exact stage_event "
                + "arguments — change only what he asked for and stage_event it again under the same name), "
                + "or `id` for one happening now (how far along, how many blocks it changed, how many it was "
                + "refused because they were his build or a chest).";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "name", "string", "A kept design's name.");
        Schemas.prop(s, "id", "string", "An event happening now (from list_events).");
        return Schemas.requireOneOf(s, "name", "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String name = args.has("name") ? args.get("name").getAsString() : null;
        String id = args.has("id") ? args.get("id").getAsString() : null;
        if ((id == null || id.isBlank()) && (name == null || name.isBlank())) {
            return ToolResult.error("inspect_event needs `id` (an event happening or standing now, from "
                    + "list_events) or `name` (a kept design). Call it again with one of them.");
        }
        try {
            return AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                if (id != null) {
                    StagedEvent e = EventDirector.get(server, id);
                    if (e == null) {
                        return ToolResult.error("No event '" + id + "' is happening or standing (it may have "
                                + "finished and put everything back). " + ListEventsTool.listing(server));
                    }
                    return ToolResult.ok(e.id() + ": " + e.phase().name().toLowerCase(java.util.Locale.ROOT)
                            + ", " + e.age() + " ticks in, " + e.changed() + " blocks changed, " + e.refused()
                            + " refused (his build, a chest, or the budget), " + e.spawned()
                            + " things spawned, " + e.scale().name().toLowerCase(java.util.Locale.ROOT)
                            + (e.restoreAtEnd() ? ", puts the world back when it ends." : ", stays until remove_event."));
                }
                EventDesign d = name == null ? null : EventDesigns.get(server, name);
                if (d == null) {
                    return ToolResult.error("There is no event design called '" + name + "'. "
                            + ListEventsTool.listing(server));
                }
                return ToolResult.ok(d.describe() + "\nstage_event arguments: " + d.toJson());
            });
        } catch (Exception e) {
            return ToolResult.error("inspect_event failed: " + e.getMessage());
        }
    }
}
