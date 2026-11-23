package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;

/** Lets the agent see what the child asked for that the watch couldn't grant on its own. */
public final class ListWishesTool implements McpTool {

    @Override
    public String name() {
        return "list_wishes";
    }

    @Override
    public String description() {
        return "List the child's wishes that the game could not grant by itself. "
                + "Each has an id, the exact words the child used, and where they were standing. "
                + "Start here: these are the jobs waiting for you.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.prop(Schemas.object(), "includeFinished", "boolean",
                "Also include wishes already granted or failed (default false).");
    }

    @Override
    public ToolResult call(JsonObject args) {
        boolean includeFinished = args.has("includeFinished")
                && args.get("includeFinished").getAsBoolean();
        try {
            return ToolResult.ok(AgentBridge.onServer(() -> {
                WishBook book = WishBook.get(AgentBridge.serverOrNull());
                List<Wish> wishes = includeFinished ? book.recent(25) : book.pending();
                if (wishes.isEmpty()) {
                    return "No wishes are waiting right now.";
                }
                StringBuilder sb = new StringBuilder();
                for (Wish w : wishes) {
                    sb.append("- id=").append(w.shortId())
                      .append(" status=").append(w.status())
                      .append(" from=").append(w.playerName())
                      .append(" at=(").append(Math.round(w.x())).append(",")
                      .append(Math.round(w.y())).append(",").append(Math.round(w.z())).append(")")
                      .append(" dim=").append(w.dimension())
                      .append("\n  wish: \"").append(w.text()).append("\"\n");
                    // A re-opened wish carries WHY it came back. Without this the agent sees an
                    // identical-looking wish and confidently does the identical wrong thing.
                    if (w.result() != null && !w.result().isBlank()) {
                        sb.append("  ⚠ ").append(w.result()).append('\n');
                    }
                }
                return sb.toString();
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
