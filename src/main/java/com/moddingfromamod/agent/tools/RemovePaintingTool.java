package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.paint.PaintPanelBlockEntity;
import com.moddingfromamod.paint.PaintRecord;
import com.moddingfromamod.paint.PaintRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Take a painted picture away — "get rid of that Spikor", "the picture's wrong, start again".
 *
 * <p>Exists for the same reason {@code remove_item} does: a kind with no reversal makes the
 * {@code [undo]} affordance lie, and every other kind a wish can make already has one.
 * A child who can be TOLD about his pictures — {@code Surroundings} lists them — will ask for one
 * to go.
 */
public final class RemovePaintingTool implements McpTool {

    @Override
    public String name() {
        return "remove_painting";
    }

    @Override
    public String description() {
        return "Take away a picture painted with paint_surface, by its id. Use it when he wants a "
                + "picture gone, or when you painted the wrong thing and want to start again. "
                + "Removing the picture takes the panel with it if paint_surface put one there, "
                + "and leaves the surface as it was if it was already painted on. "
                + "Call look_around to see the ids of pictures already painted.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line, shown the instant this call arrives, e.g. 'Taking the "
                        + "picture down'. Pass it EVERY time — it is free here.");
        Schemas.prop(s, "id", "string", "The picture's id, as given to paint_surface.");
        return Schemas.required(s, "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args != null && args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim() : "";
        if (id.isEmpty()) {
            return ToolResult.error("remove_painting needs the picture's 'id'.");
        }
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("No world is open.");
                }
                PaintRecord rec = PaintRegistry.get(id);
                if (rec == null) {
                    return ToolResult.error("There is no picture called '" + id
                            + "'. Call look_around to see which pictures exist.");
                }
                net.minecraft.resources.ResourceLocation dim =
                        net.minecraft.resources.ResourceLocation.tryParse(rec.dimension());
                ServerLevel level = com.moddingfromamod.agent.ArtifactLevel.resolve(server,
                        dim == null ? null : net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, dim));
                BlockPos at = new BlockPos(rec.x(), rec.y(), rec.z());
                boolean hadPanel =
                        level.getBlockEntity(at) instanceof PaintPanelBlockEntity;
                // TAKE THE VANILLA COPY DOWN FROM THE RECORD. It used to be read off the panel's
                // own block state, which worked only while a panel was always there; after the
                // path-2 flip a picture on a static wall has none, so the facing and the span live
                // in PaintRecord. An undo that stopped one view and left the other hanging is the
                // half-reversed state PaintRecord exists to make impossible, so the two
                // views are removed by the same act.
                int tilesDown = 0;
                if (rec.hasVanillaCopy()) {
                    net.minecraft.core.Direction f =
                            net.minecraft.core.Direction.byName(rec.facing());
                    if (f != null) {
                        tilesDown = com.moddingfromamod.paint.MapArtPanels.takeDown(
                                level, at, f, rec.wide(), rec.tall());
                    }
                }
                // …and the board a hung picture stood on, put back block by block.
                int boardBack = com.moddingfromamod.paint.PictureCommand.takeDownBoard(level, rec);
                boolean wasThere = hadPanel || tilesDown > 0 || boardBack > 0;
                if (hadPanel) {
                    if (rec.placedPanel()) {
                        level.removeBlock(at, false);
                    } else if (level.getBlockEntity(at) instanceof PaintPanelBlockEntity panel) {
                        // The surface was already painted on before this picture, so leaving it
                        // blank is the honest inverse — taking the panel away would remove
                        // somebody else's.
                        panel.setArt("", com.moddingfromamod.paint.PaintRecipe.blank(),
                                panel.widthBlocks(), panel.heightBlocks());
                    }
                }
                PaintRegistry.remove(server, id);
                return ToolResult.ok(wasThere
                        ? "Took '" + id + "' down from " + at.toShortString() + "."
                        : "'" + id + "' was already gone from the world — forgotten it too.");
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
