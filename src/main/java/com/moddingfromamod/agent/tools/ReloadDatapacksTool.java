package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.datapack.DatapackStore;

/**
 * Re-install every saved CBScript datapack into the open world and reload — a manual recovery if a
 * feature seems to have stopped, or to pull persisted packs into a freshly opened world. Normally
 * unnecessary: author_datapack loads on write, and packs auto-install when a world opens.
 *
 * <p><b>This tool is a known foot-gun, hence the two guards below.</b> A full reload on a
 * heavily-modded instance occupies the server thread for 10-30s, so a call made during one used to
 * time out on the 5s default budget and report failure even though the reload had SUCCEEDED. The
 * agent read that as "it didn't work" and retried; each retry queued another full reload. Measured
 * live on 2026-08-03: 35 calls produced 34 full data reloads and 48 JEI restarts in 15 minutes, the
 * wish never finished, and the game became unplayable. So: coalesce first, then wait generously.
 */
public final class ReloadDatapacksTool implements McpTool {

    /**
     * How long to wait for the game thread. Far above {@link AgentBridge#DEFAULT_TIMEOUT_MS} because
     * a reload on a big modpack really does take tens of seconds — the work was fine, the impatience
     * was the bug.
     */
    static final long RELOAD_TIMEOUT_MS = 60_000L;

    @Override
    public String name() {
        return "reload_datapacks";
    }

    @Override
    public String description() {
        return "Re-load all saved custom datapacks into the open world. ONLY use this if a "
                + "previously-working custom feature seems to have STOPPED. Do NOT call it after "
                + "author_datapack — that already loaded the pack — and never call it twice in a "
                + "row: one reload takes tens of seconds and stacking them freezes the game.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        // Coalesce BEFORE hopping to the server thread. Checking on the server thread would be
        // useless here: during a reload that thread is exactly what is unavailable, so the check
        // itself would time out and look like the failure it is meant to prevent.
        if (DatapackReloader.reloadInProgress()) {
            return ToolResult.ok("A reload is ALREADY running — your packs are being loaded right "
                    + "now. This is success, not an error. Do not call reload_datapacks again; "
                    + "carry on with the wish.");
        }
        try {
            int n = AgentBridge.onServer(
                    () -> DatapackStore.installAllIntoWorld(AgentBridge.serverOrNull()),
                    RELOAD_TIMEOUT_MS);
            return ToolResult.ok("Reloaded " + n + " custom datapack(s) into the world.");
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
