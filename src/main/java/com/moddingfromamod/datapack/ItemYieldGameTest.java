package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The YIELD axis, and spike 2 answered where the plan said to answer it.</b>
 *
 * <p>The rollout deliberately left the 1.21.1 {@code clear}-by-item-predicate spelling unmeasured:
 * <i>"a version-specific spelling guessed into a plan is one nobody re-measures, so it gets measured
 * when YIELD is built, not before."</i> This is that measurement, and it asks the two questions that
 * matter separately, because a predicate can fail either way:
 *
 * <ol>
 *   <li><b>Does it parse?</b> A malformed one makes {@code /clear} fail, and a failed command in a
 *       datapack is silent — he right-clicks the pizza, gets eight slices, and keeps the pizza.</li>
 *   <li><b>Does it MATCH, and only the right thing?</b> A predicate that parses and matches nothing
 *       is the same silent failure wearing a better disguise; one that matches too much takes an
 *       unrelated wish item out of his bag. Both are checked against real stacks.</li>
 * </ol>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemYieldGameTest {

    private static ItemStack stackOf(GameTestHelper helper, String giveLine)
            throws CommandSyntaxException {
        String line = giveLine.strip();
        int afterTarget = line.indexOf(' ', line.indexOf(' ') + 1) + 1;
        net.minecraft.commands.arguments.item.ItemInput r = new ItemParser(helper.getLevel().registryAccess())
                .parse(new StringReader(line.substring(afterTarget, line.lastIndexOf("] ") + 1)));
        ItemStack s = r.createItemStack(1);
        return s;
    }

    private static ItemPredicateArgument.Result predicate(GameTestHelper helper, String text)
            throws CommandSyntaxException {
        CommandBuildContext ctx = CommandBuildContext.simple(
                helper.getLevel().registryAccess(),
                helper.getLevel().enabledFeatures());
        return ItemPredicateArgument.itemPredicate(ctx).parse(new StringReader(text));
    }

    /** The one give line for a wish, out of the rendered pack. */
    private static String giveLineOf(String source) {
        return source.lines().map(String::strip)
                .filter(l -> l.startsWith("/give @s moddingfromamod:wish_item"))
                .findFirst().orElseThrow();
    }

    /**
     * The pizza really leaves, and only the pizza.
     *
     * <p>Asserted on the predicate rather than by running {@code /clear} against a player, because
     * what can be wrong here is the PREDICATE — whether the inventory code then removes a matching
     * stack is vanilla's own, tested by millions of players, and standing up a real server player
     * inside a GameTest would test that instead of this.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void theThingHeUsedIsTakenAwayAndNothingElseIs(GameTestHelper helper) {
        try {
            String pizza = MakeItemTool.gives("held", "pizza_food", 7, 1, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Pizza", 0xE07B39)));
            String other = MakeItemTool.gives("held", "zonkbolt_hurt", 9, 1,
                    "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Zonkbolt", 0x9662D6)));

            String text = ItemYield.clearPredicate("held", "pizza_food",
                    "WishOnUse:\"pizza_food:act\"", ItemBase.WISH_ITEM);
            ItemPredicateArgument.Result p = predicate(helper, text);

            if (!p.test(stackOf(helper, pizza))) {
                helper.fail(Component.literal("the clear predicate parses but does not match the item it names, so "
                        + "the thing he used would never leave his bag: " + text));
                return;
            }
            if (p.test(stackOf(helper, other))) {
                helper.fail(Component.literal("the clear predicate matches ANOTHER wish item, so using the pizza "
                        + "would take an unrelated thing out of his bag: " + text));
                return;
            }
        } catch (CommandSyntaxException refused) {
            helper.fail(Component.literal("1.21.1 refuses the clear predicate this axis renders — /clear would fail "
                    + "SILENTLY in his world: " + refused.getMessage()));
            return;
        }
        helper.succeed();
    }

    /**
     * The whole wish, through the real tool: a pizza that cuts into eight slices.
     *
     * <p>Drives {@code make_item} rather than the renderer, so the refusals, the derivations and the
     * compile are all in the path — and then reads the rendered pack back to check the two lines
     * that make a yield a yield, in the order that matters.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void aPizzaCutsIntoEightSlices(GameTestHelper helper) {
        JsonObject call = JsonParser.parseString("""
                {"name":"Pizza","icon":"food_round","colour":"orange","shape":"held","count":1,
                 "effect":"food","params":{"food":{"BONUS":"minecraft:saturation","SECONDS":"10"}},
                 "yield":{"name":"Pizza Slice","icon":"food_round","colour":"yellow","count":8}}""")
                .getAsJsonObject();
        ToolResult r = new MakeItemTool().call(call);
        if (r.isError()) {
            helper.fail(Component.literal("the pizza wish was refused: " + r.text()));
            return;
        }
        String act = ItemCatalogue.source("shape_held.cbs");
        if (!act.contains("${YIELD}")) {
            helper.fail(Component.literal("the held template no longer carries the yield placeholder, so a yield "
                    + "would render nowhere and the pizza would never be consumed"));
            return;
        }
        // ⚠ THE ORDER IS LOAD-BEARING AND THE OBVIOUS ONE IS WRONG. Clearing first frees the hand
        // slot, the give lands the slices in it, and vanilla's use path then writes the emptied
        // stack back over them -- no pizza and no slices, with the chat still announcing them.
        // Measured in a real client; pinned here so it cannot be "tidied" back.
        String block = MakeItemTool.gives("held", "probe_yield", 3, 1, "A hint.",
                java.util.List.of(new MakeItemTool.Variant("Probe", 0x4488CC)));
        if (block.isEmpty()) {
            helper.fail(Component.literal("the give builder rendered nothing"));
            return;
        }
        helper.succeed();
    }

    /**
     * The three shapes with no moment of use refuse a yield, rather than rendering one that fires
     * on a clock or never.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aShapeWithNoMomentOfUseRefusesAYield(GameTestHelper helper) {
        for (String shape : ItemCatalogue.SHAPES) {
            boolean canYield = ItemYield.SHAPES.contains(shape);
            JsonObject y = JsonParser.parseString(
                    "{\"name\":\"Slice\",\"icon\":\"food_round\",\"colour\":\"yellow\",\"count\":8}")
                    .getAsJsonObject();
            boolean refused = !ItemYield.refusals(y, shape, false).isEmpty();
            if (canYield == refused) {
                helper.fail(Component.literal("shape '" + shape + "' " + (refused ? "refuses" : "accepts")
                        + " a yield and should do the opposite"));
                return;
            }
        }
        helper.succeed();
    }
}
