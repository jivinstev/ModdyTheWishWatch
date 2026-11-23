package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.datapack.DatapackStore;

/**
 * Turn a wish datapack off.
 *
 * <p><b>Why this exists.</b> Guardrail P5 — "every creation type has a remove/undo, or orphans
 * accumulate forever" — and datapacks were the one class the AGENT could not undo. The machinery
 * was already there ({@link DatapackReloader#disableAndReload}), but the only caller was the
 * child's reject button, and that path only fires when the pack's file time happens to correlate
 * with the rejected wish. So an agent that authored something wrong — a rule that makes the game
 * unpleasant, an effect that fires constantly — had no way to take it back and could only author
 * another pack on top.
 *
 * <p>Found by the capability audit while surveying which wish classes could express what. It is
 * the same hole, in the same guardrail, as the missing {@code remove_contraption} — which suggests
 * P5 is worth re-auditing whenever a class is added, rather than trusted.
 *
 * <p><b>Reversible, not destructive.</b> The zip stays on disk and the design stays in the store,
 * exactly as removing a train keeps its plan: this turns the pack OFF, it does not delete the work.
 * It can be turned back on by re-authoring or by a reload.
 */
public final class RemoveDatapackTool implements McpTool {

    @Override
    public String name() {
        return "remove_datapack";
    }

    @Override
    public String description() {
        return "Turn OFF a datapack you authored — use this when a rule or effect you added turned "
                + "out wrong, too strong, or annoying, INSTEAD of authoring another pack on top of "
                + "it. Pass the feature_id you gave author_datapack. The pack is kept on disk, so "
                + "nothing is lost; it just stops running.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.prop(Schemas.object(), "feature_id", "string",
                "The id you passed to author_datapack.");
        return Schemas.required(s, "feature_id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("feature_id") ? args.get("feature_id").getAsString() : null;
        if (id == null || id.isBlank()) {
            return ToolResult.error("Which datapack? Pass the feature_id you authored it with.");
        }
        String clean = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DatapackStore.exists(clean)) {
            return ToolResult.error("I have no record of a datapack called '" + clean
                    + "'. Use list_datapacks to see what exists.");
        }
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                boolean wasOn = DatapackReloader.disableAndReload(server, clean);
                if (!wasOn) {
                    return ToolResult.ok("'" + clean + "' was already off, so nothing changed. "
                            + "Its files are still saved.");
                }
                return ToolResult.ok("Turned '" + clean + "' off. Whatever it was doing has "
                        + "stopped. The pack is still saved on disk, so nothing you wrote is lost "
                        + "— tell the child plainly that you took it back, rather than pretending "
                        + "it was never there.");
            }, 30_000L);   // a resource reload is slow; this is the same budget the installer uses
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
