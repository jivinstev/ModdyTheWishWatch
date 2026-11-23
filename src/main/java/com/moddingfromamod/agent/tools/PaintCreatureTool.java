package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.runtime.CreaturePaint;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;

/**
 * Repaint <b>one part</b> of a creature — "give him a green belt", "make his wings purple" — without
 * touching the rest of it.
 *
 * <p>Distinct from re-sculpting: the shape is already right and only a colour is wrong, which is the
 * commonest follow-up a child has. Distinct from a tint, too, which multiplies over the whole
 * creature and can only darken.
 *
 * <p>A part, not a pixel. The atom rig draws every box in its own part's colour and
 * has never read a texture, so this recolours the sculpt's parts ({@link CreaturePaint}) and every
 * rig of the creature is rebuilt in the new colour on the next tick. It used to paint
 * {@code texture.png} and say "It changed on the ones already in the world" while nothing a child
 * could see had changed. A band across a part (a belt) is pixels, and is refused by name.
 */
public final class PaintCreatureTool implements McpTool {

    @Override
    public String name() {
        return "paint_creature";
    }

    @Override
    public String description() {
        return "Repaint whole PARTS of a sculpted creature one flat colour — 'make his wings "
                + "purple', 'a red head' — leaving everything else alone. Use this for a colour fix "
                + "instead of sculpting the whole creature again. 'part' is a part name you gave "
                + "it (head, body, armR...); a loose match works, so 'wing' paints wingL and "
                + "wingR. It CANNOT paint a band across a part (a belt, a collar, a stripe) or "
                + "shading: a creature is drawn from whole boxes, one colour each — for a belt, "
                + "sculpt a belt part onto it with sculpt_creature. Call list_creature_parts if "
                + "you can't remember the names. Only works on creatures made with "
                + "sculpt_creature. If the creature is not his (the library's, or another "
                + "child's), the paint makes HIS OWN copy and the one standing nearest him wears "
                + "it. The creatures in the world are rebuilt in the new colour a moment later.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // A STATUS THAT RIDES ALONG WITH THE WORK. Calling set_status separately costs a
        // full model round trip -- ~4s, and 12% of one measured session -- for a tool that
        // executes in under 8ms. Passed here it is shown the moment the call arrives, in the
        // agent's own words, for nothing.
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this "
                        + "call arrives, e.g. 'Painting his shell green'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "id", "string", "The creature's id, as given to sculpt_creature.");
        Schemas.prop(s, "part", "string",
                "Which part: a part name (head, body, armR...). A loose match works — 'wing' "
                        + "hits wingL and wingR.");
        Schemas.prop(s, "colour", "string",
                "A colour name (" + String.join(", ", Species.colourNames())
                        + ") or hex like '#00A000'.");
        return Schemas.required(s, "id", "part", "colour");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String key = ModelRepository.sanitizeKey(str(args, "id"));
        String part = str(args, "part");
        String colour = str(args, "colour");
        if (key.isEmpty() || part.isEmpty() || colour.isEmpty()) {
            return ToolResult.error("paint_creature needs 'id', 'part' and 'colour'.");
        }
        int rgb = Species.parseColour(colour);
        String asker = AgentContext.currentPlayerId();
        Species before = com.moddingfromamod.species.SpeciesRegistry.get(key);
        CreaturePaint.Outcome r;
        try {
            r = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                return CreaturePaint.paint(server, key, part, rgb, asker,
                        server == null ? null
                                : com.moddingfromamod.agent.ActingChild.person(server));
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        if (r.ok() && before != null && !r.painted().isEmpty()) {
            // a repaint is saved to the creature — record it as an edit of his creature.
            com.moddingfromamod.agent.CreatureEdits.record(new com.moddingfromamod.agent.CreatureEdits
                    .Edit(r.painted(), key, before.name(), before.name(), r.forked(), false));
        }
        return r.ok() ? ToolResult.ok(r.message()) : ToolResult.error(r.message());
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
