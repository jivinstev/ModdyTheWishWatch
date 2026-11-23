package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.item.ItemSpecStore;

/**
 * <b>What the child is already holding, so a refinement can change it instead of duplicating it.</b>
 *
 * <p>Deliberately the same pair as {@code list_builds} / {@code inspect_build}, because items had
 * neither and the cost was measured: over an eight-cell ladder matrix, {@code refinements that
 * LANDED} equalled {@code EXTRA items} <b>exactly</b> in both arms. Every refinement that worked
 * worked by making a new item, and a child who asked for one wand ended a five-rung ladder holding
 * three — the middle one having lost a property the first one had.
 *
 * <p>Two of the three causes are fixed elsewhere ({@code ArtifactIndex} could not see items at all,
 * and {@code make_item} derived the item's id from its own name and effects). This is the third:
 * <b>an agent cannot edit what it cannot see.</b> Asked to "make it green", an agent with no way to
 * read back the wand's icon, shape and effects has only one correct-looking move left, which is to
 * describe a whole new wand.
 *
 * <p>Lists when asked for nothing, describes when asked for one. One tool rather than two, because
 * the list is short — a child owns a handful of wished things, not a warehouse — and because the
 * name is what he actually says.
 */
public final class InspectItemTool implements McpTool {

    @Override
    public String name() {
        return "inspect_item";
    }

    @Override
    public String description() {
        return "See a thing he ALREADY HAS — its name, icon, colour, how many, and what it does. "
                + "Call this BEFORE changing an item, then pass the id you find here as "
                + "make_item's start_from. With no id at all it lists everything he owns.\n"
                + "This is how \"make it green\", \"make it hurt more\" and \"can it freeze "
                + "things as well\" become a CHANGE to the thing in his hand rather than a second thing "
                + "beside it. He notices: he ends up with two wands and only one of them works the "
                + "way he asked.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string",
                "OPTIONAL — the item's id, or the name he calls it. Leave it out to list them all.");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args != null && args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim() : "";

        if (id.isEmpty()) {
            var all = ItemSpecStore.all();
            if (all.isEmpty()) {
                // SAY WHICH EMPTINESS. "Nothing here" and "he has nothing" read the same to an
                // agent and mean opposite things: the second is a fact about the child, the first
                // would be a broken store. Only one of them means "go ahead and make a new one".
                return ToolResult.ok("He has no wished items yet — nothing has been made for him "
                        + "with make_item. So this wish is a NEW thing; make it without start_from."
                        + com.moddingfromamod.wish.WishLessons.appendTo("item"));
            }
            StringBuilder sb = new StringBuilder("He has " + all.size() + " wished item(s):");
            for (ItemSpecStore.Spec s : all) {
                sb.append("\n  • ").append(s.id()).append("  \"").append(s.name()).append('"')
                  .append("  ").append(s.colour()).append(' ').append(s.icon())
                  .append(", ").append(s.shape())
                  .append(", x").append(s.count())
                  .append(", does: ").append(String.join(" + ", s.effects()));
            }
            sb.append("\n\nTo change one, call make_item with start_from set to its id and ONLY the "
                    + "fields that change. Everything else is kept.");
            // THE LESSON RIDES ON THE LIST, the same way the train and building ones do: this is
            // the call an agent makes before it edits anything, so it is the one place the rule
            // about start_from is guaranteed to be read at the moment it matters.
            return ToolResult.ok(sb + com.moddingfromamod.wish.WishLessons.appendTo("item"));
        }

        ItemSpecStore.Spec s = ItemSpecStore.find(id);
        if (s == null) {
            return ToolResult.error("he has no item called '" + id + "'. Call inspect_item with no "
                    + "id to see what he does have. If this really is a new thing, make it without "
                    + "start_from — but do not invent an id, because making a second item when he "
                    + "asked to change the first is the failure this tool exists to prevent.");
        }
        return ToolResult.ok(describe(s)
                + "\n\nTo change it: make_item with start_from=\"" + s.id()
                + "\" and only the fields that change. Everything you leave out is kept, and he "
                + "keeps the same item rather than gaining a second one."
                + com.moddingfromamod.wish.WishLessons.appendTo("item"));
    }

    /**
     * <b>Everything about one item, in the words a refinement will have to use.</b>
     *
     * <p>Extracted from {@code call} so it can be tested without a server, because
     * <em>"the next wish can only aim at what this reports"</em> is a claim every axis makes and
     * none of them could check. An axis that is stored and never reported is one a refinement can
     * never mention — which is the same defect as not storing it, arriving a wish later.
     */
    static String describe(ItemSpecStore.Spec s) {
        StringBuilder sb = new StringBuilder("\"" + s.name() + "\"  (id: " + s.id() + ")");
        sb.append("\n  icon:   ").append(s.icon());
        sb.append("\n  colour: ").append(s.colour());
        sb.append("\n  shape:  ").append(s.shape());
        sb.append("\n  count:  ").append(s.count());
        sb.append("\n  does:   ").append(String.join(" + ", s.effects()));
        if (s.params() != null && !s.params().keySet().isEmpty()) {
            sb.append("\n  with:   ").append(s.params());
        }
        // WHAT IT IS, not only what it does. Without this line a refinement can never be AIMED at
        // a property: the agent asks what the item is, is told nothing about its size, and either
        // leaves it alone forever or restates a guess. Rule 5 of "what every step inherits".
        var props = com.moddingfromamod.datapack.ItemProperties.describe(s.properties());
        if (!props.isEmpty()) {
            sb.append("\n  is:     ").append(String.join(", ", props));
        }
        // WHAT IT IS MADE OF. The corpus names "the parts read back" as one of the two things the
        // cone wish needs, so a parts list nothing can report is half the axis.
        if (s.parts() != null && !s.parts().isEmpty()) {
            sb.append("\n  made of: ").append(String.join(", ",
                    com.moddingfromamod.datapack.ItemParts.of(s.parts())));
        }
        return sb.toString();
    }
}