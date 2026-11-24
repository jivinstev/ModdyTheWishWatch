package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Put something in the child's hands.
 *
 * <p><b>Why a tool and not just {@code run_command}.</b> {@code give} is already on the command
 * allowlist, so this adds no capability — it adds the right <i>affordance</i>. The moment that
 * matters is the third turn of a conversation like:
 *
 * <pre>
 *   "Can you build me a rock grinder?"        → a grinder appears
 *   "How do I use it?"                        → Moddy explains: drop rocks in the top
 *   "Can you give me the items to use it?"    → ...and here he needs actual rocks
 * </pre>
 *
 * <p>A machine he cannot try is a diorama. Having an obvious tool named after that need makes the
 * agent far more likely to finish the loop, rather than explaining beautifully and leaving a
 * seven-year-old with empty hands and a machine he can only look at.
 *
 * <p>Generous by default, and clamped: these numbers come from an AI acting on a child's
 * enthusiasm, and "give me rocks" should mean a satisfying pile, not one.
 */
public final class GiveItemsTool implements McpTool {

    /** A satisfying handful. Enough to watch a machine run for a while. */
    private static final int DEFAULT_COUNT = 32;
    /** Four stacks. Past this it stops being a gift and starts being a lag spike. */
    private static final int MAX_COUNT = 256;

    @Override
    public String name() {
        return "give_items";
    }

    @Override
    public String description() {
        return "Put items straight into the child's hands. They land in his HOTBAR, and when the "
                + "hotbar is full in his WISH BAG (hotbar slot 2, beside Moddy) — never his main "
                + "inventory, never the floor; the result says where each one went and Moddy tells "
                + "him, so never tell him to look in his inventory. Use this whenever they ask for "
                + "something to use, and ESPECIALLY right after you build them a machine — a "
                + "juice machine with no apples is just a statue. If you explain how a machine "
                + "works, offer them the items to try it too. Be generous: a big handful is more "
                + "fun than a careful one.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "item", "string", "Item id, e.g. minecraft:cobblestone or minecraft:wheat.");
        Schemas.prop(s, "count", "integer",
                "How many (default " + DEFAULT_COUNT + ", max " + MAX_COUNT + ").");
        Schemas.prop(s, "why", "string",
                "One friendly line telling the child what it is for, e.g. \"apples for your "
                        + "juice machine — hold them and pull the handle!\". They see this.");
        return Schemas.required(s, "item");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String itemId = args.has("item") ? args.get("item").getAsString() : null;
        if (itemId == null || itemId.isBlank()) {
            return ToolResult.error("Which item? Pass an id like minecraft:cobblestone.");
        }
        // AN ITEM HE ALREADY HAD MADE. Until this, the only way to name one here was
        // moddingfromamod:wish_item -- which passes the registry check below, because it IS a real
        // item, and hands over N nameless colourless blanks that WishItemCatalogue then refuses to
        // record. "Give him 10 of his Hot Coffees" had no expressible form at all.
        //
        // Given through the game's own /give with the component list make_item renders, rather than
        // rebuilt as an ItemStack in Java: the two must not be able to drift, and the datapack's
        // give line is the definition of what that item IS.
        com.moddingfromamod.datapack.ItemRef ref =
                com.moddingfromamod.datapack.ItemRef.parse(itemId);
        String badRef = ref.whyUnusable("give_items' item", MakeItemTool.LIVE_REGISTRY);
        if (badRef != null) {
            return ToolResult.error(badRef);
        }
        if (ref.isWished()) {
            int asked = Math.max(1, Math.min(MAX_COUNT, intOr(args, "count", DEFAULT_COUNT)));
            int[] n = {asked};
            String refused = clampGift(itemId, n, 1);
            if (refused != null) {
                return ToolResult.error(refused);
            }
            ToolResult made = giveMade(ref, n[0], args.has("why") ? args.get("why").getAsString() : "");
            return n[0] < asked && !made.isError() ? ToolResult.ok(made.text() + "\n" + clampNote(asked, n[0])) : made;
        }
        if (com.moddingfromamod.wish.ForbiddenBlocks.isForbiddenId(itemId)) {
            return ToolResult.error(com.moddingfromamod.wish.ForbiddenBlocks.refusal(itemId.trim()));   //
        }
        Identifier key;
        try {
            key = Identifier.parse(itemId.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return ToolResult.error("'" + itemId + "' is not a valid item id.");
        }
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            return ToolResult.error("There is no item called '" + itemId + "'. Check the exact id "
                    + "with find_content first.");
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        final int asked = Math.max(1, Math.min(MAX_COUNT, intOr(args, "count", DEFAULT_COUNT)));
        // on a world whose rules clamp gifts: a stack of each kind, a few kinds a wish — shrunk,
        // and said, never refused until this wish has given all it may.
        int[] clampedCount = {asked};
        String refusedGift = clampGift(itemId, clampedCount, item.getDefaultMaxStackSize());
        if (refusedGift != null) {
            return ToolResult.error(refusedGift);
        }
        final int count = clampedCount[0];
        String why = args.has("why") ? args.get("why").getAsString() : "";

        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.person(server);
                if (player == null) {
                    return ToolResult.error(com.moddingfromamod.agent.ActingChild.whyNobody(server, name(),
                            "Nobody is in the game to give anything to."));
                }
                int given = 0;
                int remaining = count;
                // THE ONE DELIVERY PATH: the hotbar, then his Wish Bag — never his
                // main inventory, never the floor. Each landing is recorded for this wish's undo
                // and said to him once, at the end of the tick.
                java.util.List<com.moddingfromamod.item.delivery.DeliveryWords.Landed> landed =
                        new java.util.ArrayList<>();
                while (remaining > 0) {
                    int stackSize = Math.min(remaining, item.getDefaultMaxStackSize());
                    ItemStack stack = new ItemStack(item, stackSize);
                    landed.addAll(com.moddingfromamod.item.delivery.WishDelivery.give(player, stack));
                    given += stackSize;
                    remaining -= stackSize;
                }
                if (!why.isBlank()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("✨ " + why)
                            .setStyle(net.minecraft.network.chat.Style.EMPTY
                                    .withColor(net.minecraft.ChatFormatting.AQUA)));
                }
                if (count < asked) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("✨ "
                            + com.moddingfromamod.world.WishClamp.giftWords()).withStyle(net.minecraft.ChatFormatting.AQUA));
                }
                return ToolResult.ok((count < asked ? clampNote(asked, count) + "\n" : "") + "Gave " + given + "× " + itemId + " to "
                        + player.getName().getString()
                        + (why.isBlank() ? "" : " and told them: " + why) + "\n"
                        + com.moddingfromamod.item.delivery.DeliveryWords.forAgent(landed));
            }, 15_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Hand over one of HIS things — the same stack its own datapack would give.
     *
     * <p>Stack size is the item's own, so a big count arrives as several stacks exactly as a
     * vanilla give does, and overflow lands on the floor rather than vanishing (the rule the
     * registered path below already follows).
     */
    /** shrink {@code n[0]} to what this wish may still give; a refusal when it may give none. */
    static String clampGift(String itemId, int[] n, int maxStack) {
        var clamp = com.moddingfromamod.world.WishClamp.of(com.moddingfromamod.world.WorldRulesService.loadedOrNull());
        if (clamp == null) {
            return null;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String key = run == null ? "nowish" : run.wishId().toString();
        int ok = com.moddingfromamod.world.WishClamp.ledger(key).give(clamp, itemId, n[0], maxStack);
        if (ok <= 0) {
            return "REFUSED — A MODEST WISH: this wish has already given him all it may on this world (" + clamp.stacksPerKind()
                    + " stack(s) of each of " + clamp.kindsPerWish() + " kinds). Nothing was given. Finish the wish with what "
                    + "he has, and tell him gifts here are modest.";
        }
        n[0] = ok;
        return null;
    }

    /** Tell the child whose wish this is one line (an earlier issue's clamp words). Off the server thread. */
    static void tellChild(String words) {
        try {
            AgentBridge.onServer(() -> {
                ServerPlayer p = com.moddingfromamod.agent.ActingChild.person(AgentBridge.serverOrNull());
                if (p != null) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("✨ " + words)
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                }
                return null;
            }, 5_000L);
        } catch (RuntimeException ignored) {
            // the words are a courtesy; the clamp already happened
        }
    }

    static String clampNote(int asked, int got) {
        return "CLAMPED (this world's clamp rule): asked for " + asked + ", gave " + got + ". He was told: \""
                + com.moddingfromamod.world.WishClamp.giftWords() + "\" The wish is granted — finish it.";
    }

    private ToolResult giveMade(com.moddingfromamod.datapack.ItemRef ref, int count, String why) {
        String arg = MakeItemTool.giveArg(ref);
        String name = MakeItemTool.readableName(ref);
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.person(server);
                if (player == null) {
                    return ToolResult.error(com.moddingfromamod.agent.ActingChild.whyNobody(server, name(),
                            "Nobody is in the game to give anything to."));
                }
                ItemStack probe = MakeItemTool.stackFor(ref, 1, player.registryAccess());
                if (probe == null || probe.isEmpty()) {
                    // The rendered give line does not parse. Previously this path reported
                    // success and handed over nothing, which is an earlier issue's silent failure surviving
                    // its own fix.
                    return ToolResult.error("I could not build '" + name + "' to give — the give "
                            + "line make_item renders for it does not parse, so nothing would have "
                            + "arrived. Check the id make_item handed back.");
                }
                // THE ONE DELIVERY PATH. The stack is the very one this item's give
                // line renders (stackFor parses it through the game's own ItemParser), handed over
                // the way a wish's own /give now is: the hotbar, then his Wish Bag. The old dance —
                // ask for room, run /give, else hand over by hand, then count what arrived — was all
                // to dodge Creative's silent discard on a full bag, and there is no full bag now.
                java.util.List<com.moddingfromamod.item.delivery.DeliveryWords.Landed> landed =
                        new java.util.ArrayList<>();
                int remaining = count;
                while (remaining > 0) {
                    int size = Math.min(remaining, probe.getMaxStackSize());
                    ItemStack stack = MakeItemTool.stackFor(ref, size, player.registryAccess());
                    if (stack == null || stack.isEmpty()) {
                        break;
                    }
                    landed.addAll(com.moddingfromamod.item.delivery.WishDelivery.give(player, stack));
                    remaining -= size;
                }
                if (!why.isBlank()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("✨ " + why));
                }
                // WHAT ACTUALLY ARRIVED, not what was asked for.
                int arrived = 0;
                for (var l : landed) {
                    arrived += l.count();
                }
                String who = player.getName().getString();
                if (arrived <= 0) {
                    return ToolResult.error("Asked for " + count + "x " + name + " and NONE of it "
                            + "could be built, so the item's own definition is the suspect: " + arg);
                }
                return ToolResult.ok("Gave " + arrived + "x " + name + " to " + who + ".\n"
                        + com.moddingfromamod.item.delivery.DeliveryWords.forAgent(landed));
            });
        } catch (Exception e) {
            return ToolResult.error("Could not give that: " + e);
        }
    }
}
