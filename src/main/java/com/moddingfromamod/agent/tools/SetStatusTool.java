package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.server.level.ServerPlayer;

/**
 * Narrate what you're doing RIGHT NOW on the child's status bar.
 *
 * <p>Exists because the wait used to say the same thing to everyone forever — "Making something
 * amazing for you... (42s)" — a generic line counting seconds, which for a big build meant a whole
 * minute of a bar that never changed what it said. A seven-year-old reads that as nothing happening.
 * This lets the agent replace it with the truth, in his own words: <i>what</i> is being wished into
 * the world and <i>which step</i> it's on.
 *
 * <p>Cheap and non-blocking — call it liberally: once when you start (say what you're making), and
 * again each time you move to a new step (building → checking → finishing). It only changes a line of
 * text; it never slows the wish down.
 */
public final class SetStatusTool implements McpTool {

    @Override
    public String name() {
        return "set_status";
    }

    @Override
    public String description() {
        return "Show the child, on their status bar, what you're doing RIGHT NOW — call this a LOT "
                + "while you work, not just once. Say it in a few pithy, magical, kid-friendly words "
                + "naming the actual thing you're wishing into the world: 'Dreaming up your purple "
                + "dragon', then 'Building your dragon's wings', then 'Making sure it really breathes "
                + "fire', then 'Adding the last sparkles'. Update it every time you START A NEW STEP "
                + "so the bar keeps changing and never looks stuck. Pass 'phase' so it gets the right "
                + "bit of magic (dreaming/building/checking/finishing). This is just the status "
                + "line — use notify_player for a full chat message.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "doing", "string",
                "A SHORT kid-facing line naming what you're doing now, e.g. 'Building your purple "
                        + "crystal lighthouse' or 'Making sure the dragon breathes fire'.");
        Schemas.prop(s, "phase", "string",
                "Which step you're on, for the right magic icon: 'dreaming' (planning), 'building' "
                        + "(making it), 'checking' (verifying it works), or 'finishing' (final "
                        + "touches).");
        Schemas.prop(s, "step", "integer",
                "If you called plan_wish: which numbered step you are STARTING now (1, 2, 3...). "
                        + "Moves the child's progress bar along. Leave it out to just advance by one.");
        return Schemas.required(s, "doing");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String doing = args != null && args.has("doing") && args.get("doing").isJsonPrimitive()
                ? args.get("doing").getAsString() : "";
        String phase = args != null && args.has("phase") && args.get("phase").isJsonPrimitive()
                ? args.get("phase").getAsString() : "";
        // 0 means "the agent didn't say" — WishProgress.startStep then advances by one, which is
        // the right behaviour for an agent that is working its plan in order but not counting.
        int step = 0;
        if (args != null && args.has("step") && args.get("step").isJsonPrimitive()) {
            try {
                step = args.get("step").getAsInt();
            } catch (RuntimeException ignored) {
                step = 0;   // a non-number must never stop the status line updating
            }
        }
        final int wantStep = step;
        if (doing.isBlank()) {
            return ToolResult.error("Say what you're doing in 'doing', e.g. 'Building your castle'.");
        }
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                // THE CHILD WHO ASKED, not the room. This loop used to run over every player, so
                // A's "Building your castle" was written into B's status slot and B's action bar
                // rendered it as B's own — while the boss bar two files away already addressed the
                // owner alone. See docs/MULTIPLAYER_DECISIONS.md D1/D2.
                //
                // The run was ALREADY in scope here (it is fetched for wishId below); the loop
                // simply never asked it who the wish belonged to. AgentBridge.onServer re-binds
                // the worker across the hop to the server thread, so currentRun() answers with the
                // calling agent's own run rather than "whichever single run exists".
                var statusRun = com.moddingfromamod.agent.AgentContext.currentRun();
                java.util.UUID owner = statusRun == null ? null : statusRun.playerId();
                for (java.util.UUID id : com.moddingfromamod.marshal.WishAudience.forNarration(
                        owner, onlineIds(server))) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p == null) {
                        continue;
                    }
                    WishProgress.setStatus(p, doing, phase);
                    if (statusRun != null && WishProgress.planFor(statusRun.wishId()) != null) {
                        WishProgress.startStep(p, statusRun.wishId(), wantStep);
                    }
                }
            });
            // The mod's own narration holds off while this line is fresh: the agent's words are
            // wish-specific and always better than a generic one.
            com.moddingfromamod.wish.ToolNarrator.agentSpoke();
            return ToolResult.ok("Status bar now shows: " + doing);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** Everyone in the world, as ids — the audience rule is about keys, not about players. */
    static java.util.List<java.util.UUID> onlineIds(net.minecraft.server.MinecraftServer server) {
        return server == null ? java.util.List.of()
                : server.getPlayerList().getPlayers().stream()
                        .map(ServerPlayer::getUUID).toList();
    }
}
