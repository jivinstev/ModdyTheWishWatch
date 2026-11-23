package com.moddingfromamod.agent.tools;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.runtime.BedrockGeometry;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

/**
 * Read a sculpted creature's SHAPE back out: how tall it really is, and where its mouth, eyes and
 * other parts sit — in blocks, at its live scale.
 *
 * <p><b>Why this tool exists.</b> The agent could write geometry and never read it. A session that
 * did not personally sculpt a creature — which is every session after the first — knew only its id,
 * scale and health. Asked to make Spikor breathe fire "from his mouth", it had to guess, and put the
 * jet 0.55 blocks too high and 1.2 blocks too far forward, firing from mid-air past his snout. The
 * one measurement it COULD query, the hitbox, was a fixed player-sized box that said 5.07 blocks for
 * a creature drawn 9.10 blocks tall.
 *
 * <p>The output is deliberately not raw model units. It is the exact frame a datapack aims in, so
 * "mouth: 5.77 up, 2.28 forward" can be pasted straight into
 * {@code positioned ^0 ^5.77 ^2.28} with no conversion — turning a guess into arithmetic.
 */
public final class InspectCreatureTool implements McpTool {

    @Override
    public String name() {
        return "inspect_creature";
    }

    @Override
    public String description() {
        return "Measure a saved creature: its REAL height and where its mouth, eyes, head, hands "
                + "and other parts are, in blocks. CALL THIS BEFORE aiming anything at a creature "
                + "you did not just sculpt — a fire breath, a beam, particles, a held item. The "
                + "numbers come back ready for 'positioned ^0 ^<up> ^<forward>', so you never have "
                + "to guess a body part's position from a photo. Args: id (the creature id, e.g. "
                + "'turtle_king').";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "The creature id, as shown by list_creatures (e.g. 'turtle_king').");
        return Schemas.required(s, "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        if (id.isEmpty()) {
            return ToolResult.error("Need the creature's id — call list_creatures to see them.");
        }
        Species s = SpeciesRegistry.get(id);
        if (s == null) {
            return ToolResult.error("No creature called '" + id + "' has been designed in this "
                    + "world. Call list_creatures to see what exists.");
        }
        if (!s.hasCustomModel()) {
            return ToolResult.ok("'" + id + "' (" + s.name() + ") has no sculpted model — it reuses "
                    + "a built-in mob's body at scale " + fmt(s.scale()) + ", so there are no part "
                    + "positions to measure. Aim at roughly " + fmt(1.8 * s.scale()) + " blocks up. "
                    + "To get real anatomy, sculpt_creature it.");
        }
        BedrockGeometry geometry = ModelRepository.geometry(s.modelKey());
        if (geometry == null) {
            return ToolResult.error("'" + id + "' says it has a custom model ('" + s.modelKey()
                    + "') but its geometry could not be read. Re-sculpt it with sculpt_creature.");
        }
        CreatureAnatomy.Extents e = CreatureAnatomy.extents(geometry);
        if (e.isEmpty()) {
            return ToolResult.error("'" + id + "' has a model with no measurable boxes. Re-sculpt "
                    + "it with sculpt_creature.");
        }
        double scale = s.scale();
        double height = CreatureAnatomy.heightBlocks(e) * scale;
        double width = CreatureAnatomy.widthBlocks(e) * scale;

        StringBuilder sb = new StringBuilder();
        sb.append(id).append(" (").append(s.name()).append(") — ")
          .append(fmt(height)).append(" blocks tall, ")
          .append(fmt(width)).append(" wide, scale ").append(fmt(scale)).append(".\n");

        // WHAT COLOUR IT IS. Measured omission: this tool answered "what is this thing" with
        // its shape and nothing else, so a worker that did not sculpt it asked, was told the
        // geometry, then had to supply a palette to sculpt_creature and invented one. Two of three
        // cross-worker ladders repainted a bright-orange creature white on a wish about WINGS, and
        // the next three rungs faithfully preserved the wrong thing.
        // AUTHORED ABILITIES, LISTED WITH THE SPECIES —. Until they were bound, an ability was a
        // world rule that merely mentioned a mob: not listed with it, not removed with it, and
        // invisible to a second worker asked to change it.
        // WHAT IT DOES ON ITS OWN: a second worker changing a creature cannot see that it
        // follows him, and so cannot tell a follower nobody asked for from a pet.
        sb.append("Instinct: ").append(s.base().name().toLowerCase(java.util.Locale.ROOT))
          .append(s.base() == com.moddingfromamod.species.Species.Base.COMPANION
                  ? " (FOLLOWS the nearest player everywhere)" : "")
          .append(s.has(com.moddingfromamod.species.Species.Power.FRIENDLY) ? ", friendly (harmless)"
                  : "")
          .append(".\n");
        if (!s.abilityPacks().isEmpty()) {
            sb.append("Its authored abilities: ").append(String.join(", ", s.abilityPacks()))
              .append("\n  (each is a datapack bound to this creature. To CHANGE one, author it "
                      + "again under the same feature id rather than adding a second.)\n");
        }
        List<String> palette = ModelRepository.palette(s.modelKey());
        if (!palette.isEmpty()) {
            sb.append("Its colours: ").append(String.join(", ", palette))
              .append(". KEEP THESE unless the child asked you to change them — if you re-sculpt "
                      + "it, pass this same palette.\n");
        }

        List<CreatureAnatomy.Landmark> marks = CreatureAnatomy.landmarks(geometry, scale);
        if (marks.isEmpty()) {
            sb.append("No named body parts found in its model — the boxes have generic names. Aim ")
              .append("at about ").append(fmt(height * 0.8)).append(" blocks up.");
            return ToolResult.ok(sb.toString());
        }
        // THE CREATURE STANDS ON ITS MIDDLE, NOT ITS ORIGIN. The entity a selector finds is
        // the rig's carrier, and the carrier stands under the creature's mass centre; the landmarks
        // above are measured from the sculpt's origin. For a creature authored from its tail those
        // differ by the whole body, and `positioned ^0 ^up ^forward` would fire from behind it.
        double shift = com.moddingfromamod.vanilla.RigFit.forwardShiftBlocks(
                ModelRepository.sculptedParts(s.modelKey()), scale);
        sb.append("Body parts, measured from its FEET, on its centre line:\n");
        for (CreatureAnatomy.Landmark m : marks) {
            sb.append("  ").append(m.name()).append(": ")
              .append(fmt(m.up())).append(" up, ")
              .append(fmt(m.forward() + shift)).append(" forward\n");
        }
        // The selector matters as much as the geometry: knowing the mouth is 4.74 up is useless if
        // you cannot say WHICH creature's mouth. Both halves of "aim at this thing" in one result.
        String selector = com.moddingfromamod.entity.WishMobTags.selector(id);
        sb.append("Select it with: ").append(selector)
          .append("  (or ").append(com.moddingfromamod.entity.WishMobTags.nearestSelector(id))
          .append(" for just the closest one). NEVER match on its name — names carry colour codes "
                  + "and spaces and break commands.\n")
          .append("Put an effect on a part with: 'execute as ").append(selector)
          .append(" at @s positioned ^0 ^<up> ^<forward> run ...'. Do NOT add a downward pitch "
                  + "(rotated ~ <n>) unless you actually want the effect to dive into the ground.");
        return ToolResult.ok(sb.toString());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
