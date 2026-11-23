package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.paint.PaintRecord;
import com.moddingfromamod.paint.PaintRegistry;
import com.moddingfromamod.paint.PictureStore;

/**
 * <b>The pictures he already has, newest first</b> (item 2).
 *
 * <p>Pictures were the one class with a store and no way to read it back, so *"make the sun on my
 * picture bigger"* had exactly one correct-looking move, which was to redraw the picture from
 * nothing and lose everything the agent did not describe again. The same failure items had before
 * {@code list_items}, and the same fix: a list, by recency, that says which one he is
 * working on.
 *
 * <p>It lists his SHELF recipes as well as this world's hung pictures, because a picture he painted
 * in another world has to be findable here too — hanging it again is {@code paint_surface} with
 * {@code start_from} and no coordinates (item 5).
 */
public final class ListPaintingsTool implements McpTool {

    @Override
    public String name() {
        return "list_paintings";
    }

    @Override
    public String description() {
        return "List the PICTURES he already has, newest first — the one he is working on is at "
                + "the top. Call this BEFORE changing a picture (\"make the sun bigger\", \"add a "
                + "hat\"), then pass its id to paint_surface as start_from with only the change. "
                + "Includes pictures from his other worlds (hang them here with start_from and no "
                + "x/y/z) and the built-in starter pictures.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        String me = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        var all = PictureStore.recent(me);
        if (all.isEmpty()) {
            return ToolResult.ok("He has no pictures yet, so this wish is a NEW picture — paint it "
                    + "without start_from." + com.moddingfromamod.wish.WishLessons.appendTo("painting"));
        }
        StringBuilder sb = new StringBuilder("His pictures (newest first):");
        boolean first = true;
        for (PictureStore.Picture p : all) {
            sb.append("\n  • ").append(p.id())
              .append("  ").append(p.wide()).append('x').append(p.tall()).append(" blocks, ")
              .append(p.recipe().rects().size()).append(" shapes");
            PaintRecord hung = PaintRegistry.get(p.id());
            sb.append(hung != null && hung.hasVanillaCopy()
                    ? ", hanging in this world" : ", not hanging here yet");
            if (PictureStore.inLibrary(p.id())) {
                sb.append(", a built-in picture (changing it makes him his own copy)");
            } else if (!com.moddingfromamod.marshal.ArtifactOwner.normalise(p.ownerId()).isEmpty()
                    && !com.moddingfromamod.marshal.ArtifactOwner.normalise(p.ownerId())
                            .equals(com.moddingfromamod.marshal.ArtifactOwner.normalise(me))) {
                sb.append(", another child's (changing it makes him his own copy)");
            }
            if (first) {
                sb.append("   ← the one he was last working on");
                first = false;
            }
        }
        sb.append("\n\nTo CHANGE one: paint_surface with start_from=<id> and only the shapes that "
                + "change. To see its shapes first: inspect_painting.");
        // THE LESSON RIDES ON THE LIST, as the item one does: this is the call made before any
        // change, so it is where "start_from, never a redraw" is certain to be read in time.
        return ToolResult.ok(sb + com.moddingfromamod.wish.WishLessons.appendTo("painting"));
    }
}
