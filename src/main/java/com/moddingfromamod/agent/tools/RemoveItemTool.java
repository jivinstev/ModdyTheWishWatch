package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.item.WishItemCatalogue;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * <b>Take back an item a wish made</b> — the inverse `make_item` never had.
 *
 * <p>Items were the only wish class with no verb but "make": no way to list, inspect, edit or
 * remove. Three of those four have since arrived ({@code inspect_item}, which also lists when given
 * no id, and {@code make_item}'s {@code start_from}). This is the fourth, and it is the one the
 * {@code [undo]} button needs.
 *
 * <p><b>Why the ORDER of this matters.</b> {@code MadeKind} deliberately refuses to record a kind
 * nothing can reverse, because "a kind recorded but never reversed makes the [undo] button lie,
 * which is the one thing the design says must never happen". So an item wish recorded nothing, and
 * undoing it silently left the item behind. The inverse has to exist before the kind can, which is
 * why both land together.
 *
 * <p>Removes the design, the catalogue entry AND the stacks in his inventory. Leaving the stacks
 * would be the worst of both: the child still holds a wand that no longer knows what it does.
 */
public final class RemoveItemTool implements McpTool {

    @Override
    public String name() {
        return "remove_item";
    }

    @Override
    public String description() {
        return "Take away an item a wish made — the undo for make_item. Give the id or the name he "
                + "uses ('zonkbolt', 'the ice wand'). It forgets the design, forgets it from his "
                + "collection, and clears the ones he is carrying, so nothing is left behind that "
                + "looks like it should still work. Use it when he says he does not want something "
                + "any more, or to clean up after a wish that went wrong.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string",
                "Which item — its id, or just the name he calls it. Find it with inspect_item.");
        Schemas.required(s, "id");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String want = args.has("id") && !args.get("id").isJsonNull()
                ? args.get("id").getAsString().trim() : "";
        if (want.isEmpty()) {
            return ToolResult.error("Say which item to take away — its id, or the name he uses.");
        }

        // RESOLVE BEFORE REMOVING, so "there is no such item" is distinguishable from "it is gone
        // now". An agent told a removal succeeded when nothing matched would report a clean undo
        // over an item still sitting in his hand.
        ItemSpecStore.Spec spec = ItemSpecStore.find(want);
        String id = spec != null ? spec.id() : want;
        String name = spec != null ? spec.name() : want;

        try {
            return AgentBridge.onServer(() -> {
                int carried = 0;
                var server = AgentBridge.serverOrNull();
                if (server != null) {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                            ItemStack st = p.getInventory().getItem(i);
                            if (!st.isEmpty() && WishItemCatalogue.knows(st)
                                    && matches(st, name, id)) {
                                carried += st.getCount();
                                p.getInventory().setItem(i, ItemStack.EMPTY);
                            }
                        }
                    }
                }
                // A MACHINE he already put down is still standing, handle and sign and all, and
                // it looks like it works. Take every placed one down (no refund) before forgetting.
                int placedGone = 0;
                if (server != null && spec != null && ("machine".equals(spec.shape())
                        || com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(spec.shape()))) {
                    for (var level : server.getAllLevels()) {
                        int before = placed(level, id);
                        if (before > 0) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                                    .withLevel(level).withSuppressedOutput().withPermission(2),
                                    "function " + id + ":remove_all");
                            placedGone += before - placed(level, id);
                        }
                    }
                }
                // WHAT IT FILLED: fill_blocks remembers every place it poured something,
                // and undo turns it back into air. Any shape can carry the effect, so this asks the
                // pack rather than the shape.
                int fillsGone = 0;
                if (server != null && spec != null && spec.effects().contains("fill_blocks")) {
                    var fn = net.minecraft.resources.Identifier.tryParse(id + ":undo_fill");
                    if (fn != null && server.getFunctions().get(fn).isPresent()) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                                .withSuppressedOutput().withPermission(2), "function " + id + ":undo_fill");
                        fillsGone = 1;
                    }
                }
                boolean hadDesign = ItemSpecStore.remove(id);
                int forgotten = WishItemCatalogue.forget(id) + WishItemCatalogue.forget(name);

                if (!hadDesign && forgotten == 0 && carried == 0 && placedGone == 0) {
                    return ToolResult.error("There is no item called '" + want + "'. Use "
                            + "inspect_item with no id to see everything he has.");
                }
                return ToolResult.ok("Took away '" + name + "'"
                        + (carried > 0 ? " — " + carried + " from his inventory" : "")
                        + (placedGone > 0 ? (carried > 0 ? " and " : " — ") + placedGone
                                + " placed in the world" : "")
                        + (fillsGone > 0 ? ", took back everything it filled" : "")
                        + (hadDesign ? ", and forgot how to make it." : "."));
            });
        } catch (RuntimeException e) {
            return ToolResult.error("Could not take that away: " + e);
        }
    }

    /** How many of this machine stand (loaded) in a level: its anchors carry {@code <id>_built}. */
    private static int placed(net.minecraft.server.level.ServerLevel level, String id) {
        int n = 0;
        for (var e : level.getEntities().getAll()) {
            if (e != null && !e.isRemoved() && e.getTags().contains(id + "_built")) {
                n++;
            }
        }
        return n;
    }

    /** A carried stack belongs to this item if its display name matches what we are removing. */
    private static boolean matches(ItemStack stack, String name, String id) {
        String shown = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return shown.equals(name.toLowerCase(java.util.Locale.ROOT))
                || shown.contains(id.toLowerCase(java.util.Locale.ROOT));
    }
}
