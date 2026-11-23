package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/** How the agent talks to the child. Keep it short, warm, and readable by a 7-year-old. */
public final class NotifyPlayerTool implements McpTool {

    @Override
    public String name() {
        return "notify_player";
    }

    @Override
    public String description() {
        return "Say something to the child in the game chat. Use short, friendly, excited words a "
                + "7-year-old can read — for example 'Making your dragon now!' or "
                + "'I can't make that one, but I made you something even cooler!'. "
                + "Tell them what you are doing before a slow job, and again when it's done.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.prop(Schemas.object(), "message", "string",
                "What to say, in kid-friendly words.");
        return Schemas.required(s, "message");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String message = args.has("message") && args.get("message").isJsonPrimitive()
                ? args.get("message").getAsString() : "";
        if (message.isBlank()) {
            return ToolResult.error("No message given.");
        }
        //, the backstop: McpProtocol already blanks such a message before this runs.
        String internal = com.moddingfromamod.safety.AgentInternals.found(message);
        if (internal != null) {
            return ToolResult.error("Not sent." + com.moddingfromamod.safety.AgentInternals.notice(
                    name(), java.util.List.of(new com.moddingfromamod.safety.AgentInternals.Held(
                            "message", internal))));
        }
        // Bound it: the chat line is read aloud by a child, not a log.
        String text = message.length() > 200 ? message.substring(0, 200) : message;
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                // "THE CHILD", as this tool's own description says — not the room. It said
                // "Say something to the child in the game chat" while sending to every player, so
                // with two children each one read the other's agent talking to him by name. Same
                // D1/D2 answer as the status line; see docs/MULTIPLAYER_DECISIONS.md.
                var notifyRun = com.moddingfromamod.agent.AgentContext.currentRun();
                java.util.UUID owner = notifyRun == null ? null : notifyRun.playerId();
                for (java.util.UUID id : com.moddingfromamod.marshal.WishAudience.forNarration(
                        owner, onlineIds(server))) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null) {
                        p.sendSystemMessage(Component.literal("✨ " + text)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
                    }
                }
            });
            return ToolResult.ok("Told the player: " + text);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** Everyone in the world, as ids — the audience rule is about keys, not about players. */
    private static java.util.List<java.util.UUID> onlineIds(
            net.minecraft.server.MinecraftServer server) {
        return server == null ? java.util.List.of()
                : server.getPlayerList().getPlayers().stream()
                        .map(ServerPlayer::getUUID).toList();
    }
}
