package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.item.ItemSpecStore;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The registry check where it RUNS, not where it is defined.</b>
 *
 * <p>CLAUDE.md's widest-blast-radius rule: <i>"a rule tested only where it is DEFINED is not tested
 * where it RUNS"</i>. {@code ItemRefPathMatrixTest} drives {@link ItemRef#whyUnusable} with a stub
 * registry, so it proves the mechanism computes — and would go on passing if {@code make_item} were
 * still handing it {@link RegistryExists#ALWAYS}, which is the state every path was in.
 * Only a real server has the real registry, so only here can the question <i>"is the live one
 * actually the one that gets passed"</i> be asked at all.
 *
 * <p>It also covers the row the unit matrix deliberately cannot: a <b>modded</b> item, taken from
 * the live registry rather than named. Hardcoding a namespace would break the repo-agnostic rule
 * and make the check a lie on a machine without that mod.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemRefGameTest {

    private static JsonObject call(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** An id nothing in this world registers. Spelt like a typo because that is the real case. */
    private static final String TYPO = "minecraft:cobblestne";

    /**
     * A real item from a mod that is not vanilla and not ours, or null.
     *
     * <p>Read from the live registry so the test says nothing about WHICH mod is loaded. A skip is
     * announced out loud: a skip that reads as a pass is how a check proves nothing for weeks.
     */
    private static String moddedItem() {
        return BuiltInRegistries.ITEM.keySet().stream()
                .filter(k -> !"minecraft".equals(k.getNamespace())
                        && !ModdingFromAModMod.MODID.equals(k.getNamespace()))
                .map(Object::toString).sorted().findFirst().orElse(null);
    }

    @GameTest(template = "empty_test", timeoutTicks = 4000)
    public static void theLiveRegistryIsTheOneMakeItemActuallyAsks(GameTestHelper helper) {
        List<String> bad = new ArrayList<>();

        // THE TYPO. Shape-perfect, so nothing but the registry can catch it — and
        // nothing did: /give ran, failed, and the effect's particles and sound played anyway.
        ToolResult typo = new MakeItemTool().call(call("""
                {"name":"Bag of Nothing","icon":"orb","colour":"blue","shape":"held",
                 "count":1,"effect":"rich","params":{"rich":{"ITEM":"%s","COUNT":"5"}}}""".formatted(TYPO)));
        if (!typo.isError()) {
            bad.add("make_item ACCEPTED " + TYPO + ", so it is still being handed "
                    + "RegistryExists.ALWAYS — the check computes and nothing asks it");
        } else if (!typo.text().contains("no such item")) {
            bad.add("refused " + TYPO + " for the wrong reason: " + typo.text());
        }

        // A REAL ITEM still goes through. A registry check that refuses everything would pass the
        // assertion above while breaking every wish, which is the opposite failure.
        ToolResult real = new MakeItemTool().call(call("""
                {"name":"Bag of Beans","icon":"orb","colour":"blue","shape":"held",
                 "count":1,"effect":"rich","params":{"rich":{"ITEM":"minecraft:cocoa_beans","COUNT":"5"}}}"""));
        if (real.isError()) {
            bad.add("a real vanilla item was refused: " + real.text());
        }

        // A MOD'S ITEM, resolved rather than named.
        String modded = moddedItem();
        if (modded == null) {
            System.out.println("ITEM REF: SKIPPED the modded row — no mod but ours is loaded. "
                    + "This proved NOTHING about whether a mod's item is accepted.");
        } else {
            ToolResult fromMod = new MakeItemTool().call(call("""
                    {"name":"Bag of Someone Elses","icon":"orb","colour":"blue","shape":"held",
                     "count":1,"effect":"rich","params":{"rich":{"ITEM":"%s","COUNT":"5"}}}"""
                    .formatted(modded)));
            if (fromMod.isError()) {
                bad.add("a real item from a loaded mod (" + modded + ") was refused: "
                        + fromMod.text());
            }
            System.out.println("ITEM REF: modded row used " + modded);
        }

        // A BLOCK IS NOT AN ITEM. place's BLOCK asks the same registry, and minecraft:water is a
        // block with no item — so an item-only check would refuse a perfectly good `place`.
        ToolResult water = new MakeItemTool().call(call("""
                {"name":"Water Wand","icon":"wand","colour":"blue","shape":"held",
                 "count":1,"effect":"place","params":{"place":{"BLOCK":"minecraft:water"}}}"""));
        if (water.isError()) {
            bad.add("minecraft:water was refused as a BLOCK, which means the check is asking the "
                    + "ITEM registry only: " + water.text());
        }

        if (!bad.isEmpty()) {
            // TRUNCATED, because GameTest writes a failure message into a lectern book and the
            // 1.21 codec caps that string at 1024 -- over it, the run dies in ChunkMap.save at
            // shutdown and the real errors are buried under a chunk-save stack trace.
            bad.forEach(b -> System.out.println("ITEM REF FAILURE: " + b));
            String all = "the live registry is not wired where it runs:\n  "
                    + String.join("\n  ", bad);
            helper.fail(Component.literal(all.length() > 512 ? all.substring(0, 512) : all));
            return;
        }
        helper.succeed();
    }

    /**
     * An item the child had made, handed over by {@code give_items} — which could only ever manage
     * a nameless blank before, because naming one meant naming {@code wish_item}.
     */
    @GameTest(template = "empty_test", timeoutTicks = 4000)
    public static void aMadeItemSurvivesTheWholeRoundTrip(GameTestHelper helper) {
        ItemSpecStore.put(new ItemSpecStore.Spec("gate_b_coffee_plain", "Gate B Coffee", "cup",
                "#4a2c14", "plain", 1, List.of(), new JsonObject()));
        try {
            // Every spelling a real agent writes has to reach the same item.
            for (String spelling : List.of("gate_b_coffee_plain",
                    "moddingfromamod:gate_b_coffee_plain", "Gate B Coffee")) {
                ItemRef ref = ItemRef.parse(spelling);
                if (ref.spec() == null) {
                    helper.fail(Component.literal("'" + spelling + "' did not resolve to the item it names"));
                    return;
                }
                String arg = MakeItemTool.giveArg(ref);
                if (!arg.startsWith(ItemBase.id(null) + "[") || !arg.contains("Gate B Coffee")) {
                    helper.fail(Component.literal("a made item must be given as the real item plus its components, "
                            + "or he gets a blank: " + arg));
                    return;
                }
                // THE GIVE HAS TO PARSE. A component list that renders and does not parse is the
                // silent failure this whole issue is about, and only a real server can say.
                var src = helper.getLevel().getServer().createCommandSourceStack()
                        .withSuppressedOutput().withPermission(2);
                try {
                    helper.getLevel().getServer().getCommands().performPrefixedCommand(
                            src, "give @p " + arg + " 1");
                } catch (RuntimeException e) {
                    helper.fail(Component.literal("the rendered give line does not parse: " + arg + " — " + e));
                    return;
                }
            }
            helper.succeed();
        } finally {
            // No ItemSpecStore.clear() here on purpose. Every test in this class uses its own
            // gate_b_* id, so there is nothing to tidy — and a clear() empties the store for every
            // test sharing the batch. RemoveItemGameTest puts two fixture specs and then asserts
            // across ticks that remove_item took them away; wiping the store under it makes that
            // test PASS having proved nothing, which is worse than making it fail.
            // See docs/GATE_PERFORMANCE.md.
        }
    }

    /**
     * <b>The recipe forms actually LOAD.</b>
     *
     * <p>The unit test proves the JSON is shaped as the bytecode says it should be. That is a
     * different claim from <i>"the game accepts it"</i>, and the gap between those two is where
     * this whole issue lives — a recipe that installs and is silently rejected leaves the machine
     * sitting there like a mechanical fault, which is exactly what {@code author_recipe} reports
     * on and why it counts recipes before and after.
     *
     * <p>Asserted through the real codecs rather than by installing a pack, so it runs without
     * Create: an ingredient that does not parse is refused by {@code Ingredient.CODEC} here just as
     * it would be at datapack load.
     */
    @GameTest(template = "empty_test", timeoutTicks = 4000)
    public static void aRecipeCanNameSomethingHeMade(GameTestHelper helper) {
        ItemSpecStore.put(new ItemSpecStore.Spec("gate_b_beans_plain", "Roasted Beans", "seed",
                "brown", "plain", 1, List.of(), new JsonObject()));
        try {
            var lookup = helper.getLevel().registryAccess();
            var ops = net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, lookup);
            ItemRef ref = ItemRef.parse("gate_b_beans_plain");

            var stack = MakeItemTool.stackFor(ref, 1, lookup);
            if (stack == null || stack.isEmpty()) {
                helper.fail(Component.literal("the rendered give line for a made item does not parse into a stack"));
                return;
            }
            var encoded = net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, stack)
                    .result().orElse(null);
            if (encoded == null || !encoded.getAsJsonObject().has("components")) {
                helper.fail(Component.literal("a made item's stack does not encode any components, so a recipe "
                        + "could only ever name the shared wish_item id"));
                return;
            }
            JsonObject components = encoded.getAsJsonObject().getAsJsonObject("components");

            com.google.gson.JsonArray ins = new com.google.gson.JsonArray();
            ins.add(JsonParser.parseString("{\"item\":\"gate_b_beans_plain\"}"));
            com.google.gson.JsonArray outs = new com.google.gson.JsonArray();
            outs.add(JsonParser.parseString("{\"id\":\"gate_b_beans_plain\",\"count\":2}"));
            List<String> bad = RecipeItemRefs.rewrite(ins, outs, r -> components,
                    MakeItemTool.LIVE_REGISTRY);
            if (!bad.isEmpty()) {
                helper.fail(Component.literal("the rewrite refused its own made item: " + bad.get(0)));
                return;
            }

            // THE INGREDIENT, through the game's own codec. neoforge:components is a real
            // ingredient type in 21.1 -- if that were wrong, every recipe written this way would
            // be rejected at load with nothing in the world to show for it.
            var parsed = net.minecraft.world.item.crafting.Ingredient.CODEC
                    .parse(ops, ins.get(0));
            if (parsed.error().isPresent()) {
                helper.fail(Component.literal("the component ingredient does not parse: "
                        + parsed.error().get().message() + " — " + ins.get(0)));
                return;
            }
            // AND IT MATCHES THE RIGHT ITEM, which is the half a codec check cannot see. A blank
            // wish item must NOT match, or the recipe eats whatever he happens to be carrying.
            var ingredient = parsed.result().orElseThrow();
            if (!ingredient.test(stack)) {
                helper.fail(Component.literal("the ingredient does not match the very item it was built from"));
                return;
            }
            // A BLANK ONE OF OUR OWN — whichever item that currently is. The claim is "the recipe
            // must not match an unnamed wish item", and on the carrier route the blank it must not
            // match is a plain leather horse armour. Pinning the literal here would have this leg
            // testing the OTHER route's blank, which nothing hands out, so it could never fail.
            if (ingredient.test(new net.minecraft.world.item.ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.Identifier.parse(ItemBase.id(null)))))) {
                helper.fail(Component.literal("the ingredient matches a BLANK wish item, so the recipe would eat any "
                        + "wish item he is carrying — his wand included"));
                return;
            }
            helper.succeed();
        } finally {
            // No ItemSpecStore.clear() here on purpose. Every test in this class uses its own
            // gate_b_* id, so there is nothing to tidy — and a clear() empties the store for every
            // test sharing the batch. RemoveItemGameTest puts two fixture specs and then asserts
            // across ticks that remove_item took them away; wiping the store under it makes that
            // test PASS having proved nothing, which is worse than making it fail.
            // See docs/GATE_PERFORMANCE.md.
        }
    }

    /**
     * <b>The predicate {@code /clear} will actually be handed — parsed by the game's own parser.</b>
     *
     * <p>This is the single riskiest string and until now nothing had ever run it. A
     * machine that takes a made item renders
     * {@code /clear @s <base>[minecraft:custom_data~{WishItem:"…"}]}, and if that syntax is wrong
     * the clear matches nothing: the machine takes nothing, gives nothing, and reports no error —
     * the exact failure this whole issue exists to end, reintroduced by its own fix.
     *
     * <p>Parsed with {@link net.minecraft.commands.arguments.item.ItemPredicateArgument}, which is
     * literally what {@code /clear} parses its argument with, and whose {@code Result} <i>is</i> the
     * {@code Predicate&lt;ItemStack&gt;} the command tests against every slot. So a pass here is the
     * same question the command asks, not a lookalike.
     *
     * <p>The two negative cases are the point. A predicate that matched everything would satisfy
     * "it takes his item" while making the machine eat his whole inventory.
     */
    @GameTest(template = "empty_test", timeoutTicks = 4000)
    public static void theClearPredicateMatchesHisItemAndNothingElse(GameTestHelper helper) {
        // Built on a real item, like the ones a real agent produced for the roast-then-grind chain.
        ItemSpecStore.put(new ItemSpecStore.Spec("gate_b_roasted_plain", "Roasted Coffee Beans",
                "seed", "brown", "plain", 1, List.of(), new JsonObject(),
                new com.google.gson.JsonArray(), new JsonObject(),
                new com.google.gson.JsonArray(), "minecraft:nether_wart"));
        ItemSpecStore.put(new ItemSpecStore.Spec("gate_b_other_plain", "Something Else", "orb",
                "blue", "plain", 1, List.of(), new JsonObject(),
                new com.google.gson.JsonArray(), new JsonObject(),
                new com.google.gson.JsonArray(), "minecraft:nether_wart"));
        try {
            var level = helper.getLevel();
            var ctx = net.minecraft.commands.CommandBuildContext.simple(
                    level.registryAccess(), level.enabledFeatures());
            ItemRef his = ItemRef.parse("gate_b_roasted_plain");
            String predicate = MakeItemTool.matchArg(his);

            net.minecraft.commands.arguments.item.ItemPredicateArgument.Result test;
            try {
                test = net.minecraft.commands.arguments.item.ItemPredicateArgument
                        .itemPredicate(ctx).parse(new com.mojang.brigadier.StringReader(predicate));
            } catch (Exception e) {
                helper.fail(Component.literal("/clear could not parse the predicate a machine renders — every "
                        + "machine taking a made item silently takes nothing: " + predicate
                        + " — " + e));
                return;
            }

            var hisStack = MakeItemTool.stackFor(his, 1, level.registryAccess());
            if (hisStack == null || !test.test(hisStack)) {
                helper.fail(Component.literal("the predicate does not match the very item it was built from, so the "
                        + "machine would refuse his own Roasted Coffee Beans: " + predicate));
                return;
            }

            // PLAIN NETHER WART OUT OF THE NETHER. This is the leak the base_item workaround has
            // always had, and the reason the identity component exists: without it, "his roasted
            // beans" and "any nether wart" are the same request.
            var plain = new net.minecraft.world.item.ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.Identifier.parse("minecraft:nether_wart")));
            if (test.test(plain)) {
                helper.fail(Component.literal("the predicate matches PLAIN nether wart, so the machine eats anything "
                        + "off the same base — his named item is not distinguished at all"));
                return;
            }

            // ANOTHER MADE ITEM ON THE SAME BASE. Two of his own things must not be confusable, or
            // one machine in a chain quietly consumes another machine's output.
            var other = MakeItemTool.stackFor(ItemRef.parse("gate_b_other_plain"), 1,
                    level.registryAccess());
            if (other != null && test.test(other)) {
                helper.fail(Component.literal("the predicate matches a DIFFERENT made item built on the same base — "
                        + "a chain would consume the wrong thing"));
                return;
            }
            System.out.println("CLEAR PREDICATE: " + predicate + " — matches his item, "
                    + "not plain nether wart, not another made item");
            helper.succeed();
        } finally {
            // No ItemSpecStore.clear() here on purpose. Every test in this class uses its own
            // gate_b_* id, so there is nothing to tidy — and a clear() empties the store for every
            // test sharing the batch. RemoveItemGameTest puts two fixture specs and then asserts
            // across ticks that remove_item took them away; wiping the store under it makes that
            // test PASS having proved nothing, which is worse than making it fail.
            // See docs/GATE_PERFORMANCE.md.
        }
    }
}
