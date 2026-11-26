package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.redstone.MechanismStore;
import com.moddingfromamod.redstone.MechanismWorld;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Takes a mechanism build_mechanism made back out, putting every block it replaced back as it was. */
public final class RemoveMechanismTool implements McpTool {

    @Override
    public String name() {
        return "remove_mechanism";
    }

    @Override
    public String description() {
        return "Take a redstone mechanism made with build_mechanism back out of the world: every block it placed is "
                + "put back as it was before (his wall, his doorway). With no id, lists the mechanisms there are.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "The mechanism's id, as build_mechanism named it (e.g. 'piston_door').");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = BuildMechanismTool.str(args, "id");
        return AgentBridge.onServer(() -> {
            var server = AgentBridge.serverOrNull();
            if (server == null) {
                return ToolResult.error("No world is running.");
            }
            ServerLevel level = server.overworld();
            if (id.isEmpty()) {
                var all = MechanismStore.all(level);
                return ToolResult.ok(all.isEmpty() ? "There are no mechanisms."
                        : all.stream().map(e -> e.id() + ": " + e.summary()).collect(Collectors.joining("\n")));
            }
            MechanismStore.Entry e = MechanismStore.get(level, id);
            if (e == null) {
                return ToolResult.error("There is no mechanism '" + id + "'. Call remove_mechanism with no id to list them.");
            }
            ServerLevel in = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(e.dimension())));
            MechanismWorld.takeBack(in == null ? level : in, e.journal());
            // a mechanism driven by its datapack: its named markers and its datapack go too
            com.moddingfromamod.redstone.MechanismDrive.unmark(in == null ? level : in, id);
            MechanismStore.remove(level, id);
            return ToolResult.ok("Took '" + id + "' back out: " + e.journal().size() + " blocks put back as they were.");
        }, 30_000L);
    }
}
