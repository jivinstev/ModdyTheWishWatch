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

/** <b>Every event his world keeps, what comes back on its own, and what is happening now</b> (E5). */
public final class ListEventsTool implements McpTool {

    @Override
    public String name() {
        return "list_events";
    }

    @Override
    public String description() {
        return "List the world events kept in his world (each design by name, with what it does), the "
                + "ones that come back every few days, and every event happening or left standing now "
                + "(a crater waiting for remove_event), with the id remove_event takes. Look here before "
                + "a re-wish ('make the wave bigger'): the same name changes that design.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        try {
            return AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                return ToolResult.ok(listing(server) + com.moddingfromamod.wish.WishLessons.appendTo("event"));
            });
        } catch (Exception e) {
            return ToolResult.error("list_events failed: " + e.getMessage());
        }
    }

    static String listing(MinecraftServer server) {
        StringBuilder b = new StringBuilder();
        for (EventDesign d : EventDesigns.all(server)) {
            b.append("\n- design ").append(d.name()).append(": ").append(d.describe());
        }
        for (EventDesigns.Schedule s : EventDesigns.schedules(server)) {
            b.append("\n- every ").append(s.days()).append(s.days() == 1 ? " day: " : " days: ").append(s.design());
        }
        for (StagedEvent e : EventDirector.all(server)) {
            b.append("\n- ").append(e.phase().name().toLowerCase(java.util.Locale.ROOT)).append(": ")
                    .append(e.id()).append(" (").append(e.changed()).append(" blocks changed)");
        }
        return b.length() == 0 ? "No events in this world." : "Events:" + b;
    }
}
