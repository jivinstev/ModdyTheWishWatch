package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.paint.PaintRecord;
import com.moddingfromamod.paint.PaintRegistry;
import com.moddingfromamod.paint.PictureStore;

/**
 * <b>One picture, in the words a change has to use</b> (item 2): its size, where it hangs,
 * and every rectangle — so "make the sun bigger" can name the sun rather than guess at it.
 */
public final class InspectPaintingTool implements McpTool {

    @Override
    public String name() {
        return "inspect_painting";
    }

    @Override
    public String description() {
        return "See one of his PICTURES shape by shape — its canvas size, how many blocks it spans, "
                + "where it hangs, and every rectangle with its colour — so a change can be aimed "
                + "at the right part. Then paint_surface with start_from and only the change.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "The picture's id, from list_paintings.");
        return Schemas.required(s, "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args != null && args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim() : "";
        PictureStore.Picture p = PictureStore.get(id);
        if (p == null) {
            return ToolResult.error("There is no picture called '" + id + "'. Call list_paintings "
                    + "to see his pictures.");
        }
        return ToolResult.ok(describe(p, PaintRegistry.get(p.id())));
    }

    /** Pure, so a test can hold it: what a refinement can aim at is what this reports. */
    static String describe(PictureStore.Picture p, PaintRecord hung) {
        StringBuilder sb = new StringBuilder("Picture '" + p.id() + "': a " + p.recipe().size()
                + "-pixel canvas (0,0 is the top-left), " + p.wide() + "x" + p.tall()
                + " blocks.");
        sb.append(hung != null && hung.hasVanillaCopy()
                ? "\n  hangs at " + hung.x() + "," + hung.y() + "," + hung.z() + " facing "
                        + hung.facing() + " in " + hung.dimension()
                : "\n  not hanging in this world — start_from with no x/y/z hangs it on a new "
                        + "board in front of him");
        sb.append("\n  shapes, in painting order (later ones are on top):");
        int i = 0;
        for (var r : p.recipe().rects()) {
            sb.append("\n    ").append(i++).append(": x=").append(r.x()).append(" y=").append(r.y())
              .append(" w=").append(r.width()).append(" h=").append(r.height())
              .append(" ").append(String.format("#%06X", r.colour() & 0xFFFFFF));
        }
        return sb.toString();
    }
}
