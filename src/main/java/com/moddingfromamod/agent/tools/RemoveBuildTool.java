package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.Builder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Take a building back down and clear it away — the structure analogue of remove_train / remove_creature.
 *
 * <p>Buildings are plain blocks, so the agent could always fill them with air by hand; this makes it
 * one honest call and keeps it non-destructive — it clears only the blocks the saved plan placed at
 * its remembered anchor, never terrain or anything the child added on top. The design is kept, so the
 * building can be rebuilt instantly.
 */
public final class RemoveBuildTool implements McpTool {

    @Override
    public String name() {
        return "remove_build";
    }

    @Override
    public String description() {
        return "Take a building back down and clear it away — use this to clean up a build the child "
                + "rejected, or a wrong one, BEFORE building a replacement. Pass the building's id. "
                + "Only the building's own blocks are cleared (not terrain); the design is kept so you "
                + "can build it again.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.prop(Schemas.object(), "id", "string",
                "The id of the building to remove.");
        return Schemas.required(s, "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("id") ? args.get("id").getAsString() : null;
        if (id == null || id.isBlank()) {
            return ToolResult.error("Which building? Pass the id to remove.");
        }
        BuildPlan plan = BuildRegistry.get(id);
        if (plan == null) {
            return ToolResult.error("There is no building called '" + id + "'.");
        }
        Long anchor = BuildRegistry.anchorOf(id);
        if (anchor == null || !BuildRegistry.standing(id)) {
            // honest about WHY — taken down, in another world, or built before positions
            // were saved. Never a guessed footprint: clearing one deletes his terrain.
            return ToolResult.error(BuildRegistry.whyNotHere(id));
        }
        var dimension = BuildRegistry.dimensionOf(id);
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // The BUILDING'S own world. Clearing a footprint in whichever world a player
                // happens to be standing in does not fail quietly — it deletes that world's
                // terrain instead. See ArtifactLevel.
                ServerLevel level = com.moddingfromamod.agent.ArtifactLevel.resolve(server, dimension);
                int cleared = Builder.clearBuild(level, BlockPos.of(anchor), plan);
                BuildRegistry.markRemoved(server, id);
                return ToolResult.ok("Removed '" + id + "' — cleared " + cleared + " block(s). The "
                        + "design is still saved, so you can build it again any time.");
            }, 15_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
