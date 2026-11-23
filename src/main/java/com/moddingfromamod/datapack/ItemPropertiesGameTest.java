package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The PROPERTIES axis, checked against the game's own parser rather than against my memory.</b>
 *
 * <p>{@link ItemPropertiesTest} covers every rule that is true without Minecraft — ranges, refusals,
 * merge order, the injection guard. The one thing it cannot cover is the part most likely to be
 * wrong: <b>whether the component spelling is the one 1.21.1 accepts.</b> That is a version-specific
 * fact, and this repo's standing rule is that such a fact is measured or it is not known — a wrong
 * spelling renders perfectly, compiles perfectly, and then the {@code /give} silently fails in his
 * world while every gate stays green.
 *
 * <p>So this drives the REAL {@link ItemParser} — the same one {@code /give} uses — over the give
 * line the real tool rendered, and then reads the components back off a real {@link ItemStack}. A
 * misspelled component name throws {@code ERROR_UNKNOWN_COMPONENT} and a malformed value throws
 * {@code ERROR_MALFORMED_COMPONENT}, which is exactly the failure worth catching here.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemPropertiesGameTest {

    /**
     * The {@code <item>[<components>]} argument out of a rendered give line.
     *
     * <p>Found by POSITION rather than by looking for our own item id, which is what it did until
     * the base-item axis existed: `/give @s minecraft:golden_axe[...]` then read as "not a give
     * line", and the matrix reported a harness bug in the voice of a product one. The lore may
     * contain spaces, so the end is the last `] `, never a split.
     */
    private static String itemArgument(String giveLine) {
        String line = giveLine.strip();
        int afterGive = line.indexOf(' ') + 1;              // past "/give"
        int afterTarget = line.indexOf(' ', afterGive) + 1;  // past the selector
        int close = line.lastIndexOf("] ");
        if (afterGive == 0 || afterTarget == 0 || close < afterTarget) {
            throw new IllegalStateException("not a give line: " + giveLine);
        }
        return line.substring(afterTarget, close + 1);
    }

    /** The stack {@code /give} would actually hand him. Throws if the spelling is wrong. */
    private static ItemStack parse(GameTestHelper helper, String giveLine)
            throws CommandSyntaxException {
        ItemParser.ItemResult r = new ItemParser(helper.getLevel().registryAccess())
                .parse(new StringReader(itemArgument(giveLine)));
        ItemStack stack = new ItemStack(r.item(), 1);
        stack.applyComponents(r.components());
        return stack;
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /**
     * The wish the axis exists for, end to end: three cups that are actually three things.
     *
     * <p>Asserted on the STACK rather than on the rendered text, because the text is what the unit
     * test already checks and the stack is the thing he ends up holding.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aLargeCupIsActuallyDifferentFromASmallOne(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        try {
            // Built directly rather than through parseVariants: the merge of a wish's properties
            // with a member's is unit-tested where it can be asserted on its own, and what this
            // test is for is the two inches between a rendered give line and a real ItemStack.
            List<MakeItemTool.Variant> family = List.of(
                    new MakeItemTool.Variant("Small Cup", 0xFFFFFF,
                            json("{\"max_stack_size\":\"64\"}")),
                    new MakeItemTool.Variant("Medium Cup", 0xC0C0C0,
                            json("{\"max_stack_size\":\"16\"}")),
                    new MakeItemTool.Variant("Large Cup", 0x808080,
                            json("{\"max_stack_size\":\"1\"}")));
            String[] lines = MakeItemTool.gives("plain", "small_cup_plain", 3, 6,
                    "It's yours to keep.", family).split("\n");
            if (lines.length != 3) {
                helper.fail(Component.literal("expected three give lines, got " + lines.length));
                return;
            }
            int[] want = {64, 16, 1};
            for (int i = 0; i < 3; i++) {
                ItemStack stack = parse(helper, lines[i]);
                int got = stack.getOrDefault(DataComponents.MAX_STACK_SIZE, -1);
                if (got != want[i]) {
                    wrong.add("member " + i + " stacks to " + got + ", wanted " + want[i]);
                }
            }
        } catch (CommandSyntaxException bad) {
            // The parser's own message names the component it could not read, which is the whole
            // value of asking it rather than asking myself. Caught NARROWLY: an `Exception` here
            // also swallows GameTestAssertException, so a failed assertion is reported as a parse
            // failure and you go looking in the wrong place. It did, on the first run.
            helper.fail(Component.literal("the game refused the give line this axis renders: " + bad));
            return;
        }
        if (!wrong.isEmpty()) {
            helper.fail(Component.literal("a size did not survive to the stack: " + String.join("; ", wrong)));
            return;
        }
        helper.succeed();
    }

    /**
     * The attribute modifier arrives, carries our own id, and is the only one on the item.
     *
     * <p>The id matters more than it looks: 1.21 replaced the old UUID-and-name pair with a single
     * {@code ResourceLocation}, and two modifiers sharing an id is not an error — the second
     * silently replaces the first. An unnamespaced one is a collision waiting for another mod.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void swingingItHarderReachesTheStack(GameTestHelper helper) {
        try {
            String line = MakeItemTool.gives("plain", "club_plain", 3, 1, "It's yours to keep.",
                    List.of(new MakeItemTool.Variant("Club", 0x8B5A2B,
                            json("{\"attack_damage\":\"14\"}"))));
            ItemStack stack = parse(helper, line);
            ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods == null || mods.modifiers().isEmpty()) {
                helper.fail(Component.literal("the item carries no attribute modifier at all"));
                return;
            }
            if (mods.modifiers().size() != 1) {
                helper.fail(Component.literal("expected exactly one modifier, got " + mods.modifiers().size()));
                return;
            }
            var entry = mods.modifiers().get(0);
            if (entry.modifier().amount() != 14.0) {
                helper.fail(Component.literal("the modifier is " + entry.modifier().amount() + ", wanted 14"));
                return;
            }
            if (!entry.modifier().id().getNamespace().equals(ModdingFromAModMod.MODID)) {
                helper.fail(Component.literal("the modifier id is not ours, so anything can replace it: "
                        + entry.modifier().id()));
                return;
            }
        } catch (CommandSyntaxException bad) {
            helper.fail(Component.literal("the game refused the give line this axis renders: " + bad));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>Making a real item hit harder must not take away everything ELSE it does.</b>
     *
     * <p>The test above is the from-scratch case, where an item has no modifiers to lose. This is
     * the {@code base_item} case, and it is the one that goes wrong: NeoForge's
     * {@code IItemStackExtension.getAttributeModifiers()} uses the component when it is non-empty
     * and <b>skips the item's own defaults entirely</b> —
     *
     * <pre>
     *   23: .modifiers().isEmpty()
     *   28: ifeq 50            ← non-empty: use OURS, skip the default
     *   31–49:                 ← only when empty: item.getDefaultAttributeModifiers(stack)
     * </pre>
     *
     * <p>So a one-entry {@code attack_damage} component REPLACES a sword's own list. Vanilla's
     * diamond sword carries {@code attack_speed -2.4} for 1.6 swings a second; without it the sword
     * runs at the base 4.0, and a wish for <i>"a stronger sword"</i> is answered with one about
     * 2.5× stronger than asked. That direction is luck — on armour, or on a mod's item with a
     * custom attribute, the same code deletes them.
     *
     * <p><b>It reads the modifiers back through {@code forEachModifier}</b>, which is the path the
     * game itself uses, rather than inspecting the component. A component that parses and wipes the
     * rest is indistinguishable from one that works, which is why nothing caught this: the sibling
     * test above proves the give line PARSES, and it does.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void makingARealItemStrongerKeepsWhatElseItDid(GameTestHelper helper) {
        try {
            // THE CONTROL FIRST. If a plain diamond sword does not carry an attack_speed modifier,
            // the assertion below passes by measuring nothing.
            ItemStack plain = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
            if (!carries(plain, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)) {
                helper.fail(Component.literal("a stock diamond sword has no attack_speed modifier, so this test "
                        + "cannot tell a wipe from the normal case"));
                return;
            }

            String line = MakeItemTool.gives("plain", "super_sword_plain", 3, 1,
                    "It's yours to keep.",
                    List.of(new MakeItemTool.Variant("SUPER SWORD", 0x66CCFF,
                            json("{\"attack_damage\":\"20\"}"))),
                    com.moddingfromamod.datapack.ItemRecipient.ME, null,
                    "minecraft:diamond_sword");
            ItemStack made = parse(helper, line);

            if (!carries(made, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
                helper.fail(Component.literal("the wish asked for more damage and the item has no attack_damage "
                        + "modifier at all"));
                return;
            }
            if (!carries(made, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)) {
                helper.fail(Component.literal("making the sword stronger DELETED its attack_speed (#385). The "
                        + "component replaces the item's own modifiers rather than merging with "
                        + "them, so the sword now swings at the base 4.0/sec instead of 1.6 — "
                        + "about 2.5x the damage per second the child asked for, and on any item "
                        + "whose modifiers are not just damage and speed this deletes them "
                        + "outright. Merge the base item's own list in rather than replacing it"));
                return;
            }
        } catch (CommandSyntaxException bad) {
            helper.fail(Component.literal("the game refused the give line this axis renders: " + bad));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>An enchantment he wished for is on the stack, and it DOES something.</b>
     *
     * <p>"A pickaxe that gives extra diamonds": the real tool path renders the give line, the game's
     * parser reads it, and the stack carries Fortune at the level asked. Then the behaviour, because
     * a component on a stack is not a feature: diamond ore broken with that pickaxe is rolled
     * through the game's own loot table, against the same pickaxe without it, and the enchanted one
     * must give more. Red on {@code main} before the property existed: the key was unknown, so the
     * stack came out plain.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aPickaxeThatGivesExtraDiamondsReallyDoes(GameTestHelper helper) {
        try {
            String line = MakeItemTool.gives("plain", "lucky_pick_plain", 3, 1, "It's yours to keep.",
                    List.of(new MakeItemTool.Variant("Lucky Pick", 0x66CCFF,
                            json("{\"enchantments\":{\"fortune\":5,\"unbreaking\":3}}"))),
                    com.moddingfromamod.datapack.ItemRecipient.ME, null, "minecraft:diamond_pickaxe");
            ItemStack made = parse(helper, line);
            var fortune = helper.getLevel().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE);
            int level = net.minecraft.world.item.enchantment.EnchantmentHelper
                    .getItemEnchantmentLevel(fortune, made);
            helper.assertValueEqual(level, 5, Component.literal("the pickaxe carries the Fortune he asked for"));

            ItemStack plain = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
            int lucky = diamondsFrom(helper, made);
            int control = diamondsFrom(helper, plain);
            helper.assertValueEqual(control, 200, Component.literal("the control: a plain pickaxe gives one diamond "
                    + "per ore (if not, this test measures nothing)"));
            helper.assertTrue(lucky > control * 2, Component.literal("Fortune V must give far more diamonds than a "
                    + "plain pickaxe over 200 ores: " + lucky + " vs " + control));
        } catch (CommandSyntaxException bad) {
            helper.fail(Component.literal("the game refused the enchanted give line: " + bad));
            return;
        }
        helper.succeed();
    }

    /** Diamonds from 200 diamond ores broken with this tool, through the game's own loot table. */
    private static int diamondsFrom(GameTestHelper helper, ItemStack tool) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        var ore = net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState();
        int n = 0;
        for (int i = 0; i < 200; i++) {
            for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(ore, level, pos, null,
                    null, tool)) {
                if (drop.is(net.minecraft.world.item.Items.DIAMOND)) {
                    n += drop.getCount();
                }
            }
        }
        return n;
    }

    /**
     * <b>An enchanted wish made from scratch does not glint.</b> On a composed 1.21.4 icon
     * the glint lit the sparkle backing into a pink ball, and an enchantments component
     * would bring it straight back; the give line says so explicitly.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void anEnchantedWishFromScratchKeepsItsIconReadable(GameTestHelper helper) {
        try {
            String line = MakeItemTool.gives("held", "lucky_charm_hurt", 3, 1, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Lucky Charm", 0x66CCFF,
                            json("{\"enchantments\":{\"looting\":3}}"))));
            ItemStack made = parse(helper, line);
            helper.assertTrue(!made.getEnchantments().isEmpty(), Component.literal("the charm carries its enchantment"));
            if (com.moddingfromamod.compat.ModelData.COMPOSED_ICONS) {
                helper.assertValueEqual(made.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), Boolean.FALSE,
                        Component.literal("a composed icon is told not to glint, enchanted or not"));
            }
        } catch (CommandSyntaxException bad) {
            helper.fail(Component.literal("the game refused the enchanted give line: " + bad));
            return;
        }
        helper.succeed();
    }

    /** Does this stack, as the GAME resolves it, carry a modifier for this attribute in the hand? */
    private static boolean carries(ItemStack stack,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        boolean[] found = {false};
        stack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (held, mod) -> {
            if (held.value() == attr.value()) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * <b>EVERY give line this tool can render, parsed by the game — a matrix, not a list of cells.</b>
     *
     * <p><b>This is in a second surface, and the existing guard could not see it.</b>
     * {@code ItemMatrixGameTest.everyHardcodedRegistryIdResolves} was written because {@code grow}
     * named {@code minecraft:scale}, which is not an attribute in 1.21.1, and the whole pack failed
     * to LOAD with nothing upstream able to say so. That guard sweeps TEMPLATE SOURCE. A property
     * renders its component from Java, so {@code minecraft:attack_damage} — also not an attribute
     * in 1.21.1 — walked straight past it, past the compiler, and past nine unit tests.
     *
     * <p>Two cells written by hand caught it, and only by the luck of one of the two shipped
     * properties being the broken one. So the cover is derived instead: every shape the catalogue
     * has, crossed with every rule in {@link ItemProperties#rules()} at both ends of its range and
     * all of them together. <b>A shape or a property added tomorrow is covered the day it exists</b>,
     * which is the argument {@code ItemMatrixGameTest} already makes for effect × shape.
     *
     * <p>It sweeps the WHOLE give line, so it is also the first thing to check the six components
     * every wish item has always carried — {@code custom_model_data}, {@code dyed_color},
     * {@code custom_name}, {@code lore}, {@code enchantment_glint_override} and each shape's own
     * {@code custom_data}. Those were parsed only from two frozen spike fixtures, never from what
     * the tool renders now.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void everyGiveLineThisToolCanRenderIsAcceptedByTheGame(GameTestHelper helper) {
        List<String> bad = new ArrayList<>();
        for (String shape : ItemCatalogue.SHAPES) {
            for (var probe : probes()) {
                for (String base : BASES) {
                    String where = shape + " / " + base + " / " + probe.what();
                    if (CLASHES.contains(base + " / " + probe.what())) {
                        // A combination the GAME rejects for a real reason, asserted from the other
                        // side below rather than skipped quietly.
                        continue;
                    }
                    String line = MakeItemTool.gives(shape, "probe_item", 3, 1, "A hint.",
                            List.of(new MakeItemTool.Variant("Probe", 0x4488CC, probe.props())),
                            ItemRecipient.ME, null, base);
                    try {
                        if (parse(helper, line).isEmpty()) {
                            bad.add(where + ": parsed to an empty stack");
                        }
                    } catch (CommandSyntaxException refused) {
                        // The parser names the component it could not read, which is the entire
                        // value of asking it rather than asking myself.
                        bad.add(where + ": " + refused.getMessage());
                    }
                }
            }
        }
        if (!bad.isEmpty()) {
            helper.fail(Component.literal("the game refuses a give line this tool can render — every one of these "
                    + "would fail SILENTLY in his world:\n  " + String.join("\n  ", bad)));
            return;
        }
        // THE OTHER SIDE OF THE SKIP. Every clash above must actually clash: a list of exemptions
        // nobody checks is a list of things quietly stopped being tested.
        for (String clash : CLASHES) {
            String base = clash.substring(0, clash.indexOf(" / "));
            String what = clash.substring(clash.indexOf(" / ") + 3);
            var probe = probes().stream().filter(x -> x.what().equals(what)).findFirst();
            if (probe.isEmpty()) {
                bad.add("the exemption '" + clash + "' names a probe that no longer exists");
                continue;
            }
            String line = MakeItemTool.gives("held", "probe_item", 3, 1, "A hint.",
                    List.of(new MakeItemTool.Variant("Probe", 0x4488CC, probe.get().props())),
                    ItemRecipient.ME, null, base);
            try {
                parse(helper, line);
                bad.add("'" + clash + "' is exempted as a clash and the game accepts it — the "
                        + "exemption is now hiding a case that works");
            } catch (CommandSyntaxException expected) {
                // Correct: the game refuses it, which is why it is exempted.
            }
        }
        if (!bad.isEmpty()) {
            helper.fail(Component.literal("the give-line matrix disagrees with the game:\n  "
                    + String.join("\n  ", bad)));
            return;
        }
        System.out.println("GIVE LINES: " + ItemCatalogue.SHAPES.size() + " shapes x "
                + probes().size() + " property combinations x " + BASES.size()
                + " base items all parse (" + CLASHES.size() + " exempted clashes, each verified "
                + "to still clash).");
        helper.succeed();
    }

    /**
     * The items a wish may be built on: ours, a damageable tool, a stackable food, a block.
     *
     * <p>Four rather than every item in the game, chosen for the ways an item can DIFFER —
     * durability, stackability, having a block behind it — because those are what a component
     * combination collides with. A tool carries {@code max_damage} by default and a food does not,
     * and that difference is the whole of the clash below.
     */
    private static final List<String> BASES = List.of(
            ItemBase.WISH_ITEM, "minecraft:golden_axe", "minecraft:bread", "minecraft:stone");

    /**
     * Combinations 1.21.1 refuses <b>correctly</b>, and the refusal is asserted rather than skipped.
     *
     * <p>A tool has {@code max_damage} among its default components, so asking for a stack of 64
     * of them is <i>"Item cannot be both damageable and stackable"</i>. That is the game being
     * right; what would be wrong is the tool rendering it, the give failing silently, and the child
     * getting nothing.
     */
    private static final List<String> CLASHES = List.of(
            "minecraft:golden_axe / max_stack_size=64",
            "minecraft:golden_axe / every property at once");

    /** One combination of properties to render, and what to call it in a failure. */
    private record Probe(String what, JsonObject props) {
    }

    /**
     * Nothing, each rule at both ends of its range, and all of them at once.
     *
     * <p>Derived from {@link ItemProperties#rules()} rather than listed, so the matrix grows with
     * the table. Both ENDS because a bound that renders is not the same as a bound that parses —
     * an amount of 1 and an amount of 40 go through different code in a component codec.
     */
    private static List<Probe> probes() {
        List<Probe> out = new ArrayList<>();
        out.add(new Probe("no properties", null));
        JsonObject all = new JsonObject();
        for (ItemProperties.Rule r : ItemProperties.rules()) {
            if (ItemProperties.ENCHANTMENTS_KEY.equals(r.key())) {
                // Its value is {enchantment: level}: every enchantment in the table at level 1, and
                // every one at its cap — so each NAME is asked of the game's registry, both ends.
                JsonObject low = new JsonObject();
                JsonObject high = new JsonObject();
                ItemProperties.ENCHANTMENTS.forEach((k, max) -> {
                    low.addProperty(k, 1);
                    high.addProperty(k, max);
                });
                for (JsonObject lv : new JsonObject[]{low, high}) {
                    JsonObject one = new JsonObject();
                    one.add(r.key(), lv);
                    out.add(new Probe(r.key() + (lv == low ? "=all at 1" : "=all at cap"), one));
                }
                all.add(r.key(), high);
                continue;
            }
            if (ItemProperties.CATCHES_KEY.equals(r.key())) {
                // An object: one id, and a random list with a special word in it.
                for (String what : new String[]{"minecraft:diamond", "random: minecraft:emerald, treasure_chest"}) {
                    JsonObject c = new JsonObject();
                    c.addProperty("what", what);
                    c.addProperty("chance", 100);
                    JsonObject one = new JsonObject();
                    one.add(r.key(), c);
                    out.add(new Probe(r.key() + "=" + what, one));
                    all.add(r.key(), c);
                }
                continue;
            }
            if (ItemProperties.REVEALS_KEY.equals(r.key())) {
                // An object: each output once; the ore word is ours and never parsed by the game.
                for (String how : com.moddingfromamod.item.RevealRule.HOW) {
                    JsonObject rv = new JsonObject();
                    rv.addProperty("ore", "diamond");
                    rv.addProperty("how", how);
                    JsonObject one = new JsonObject();
                    one.add(r.key(), rv);
                    out.add(new Probe(r.key() + "=" + how, one));
                    all.add(r.key(), rv);
                }
                continue;
            }
            if (ItemProperties.MINE_AREA_KEY.equals(r.key())) {
                // One word: every shape, since there are four.
                for (String w : com.moddingfromamod.item.MineShape.WORDS) {
                    JsonObject one = new JsonObject();
                    one.addProperty(r.key(), w);
                    out.add(new Probe(r.key() + "=" + w, one));
                }
                all.addProperty(r.key(), com.moddingfromamod.item.MineShape.WORDS.get(0));
                continue;
            }
            if (ItemProperties.TRAIL_KEY.equals(r.key())) {
                // One word: every trail, since there are four.
                for (String w : ItemProperties.TRAILS) {
                    JsonObject one = new JsonObject();
                    one.addProperty(r.key(), w);
                    out.add(new Probe(r.key() + "=" + w, one));
                }
                all.addProperty(r.key(), ItemProperties.TRAILS.get(0));
                continue;
            }
            if (ItemProperties.HOLDS_ONLY_KEY.equals(r.key())) {
                // One word: the first and the last of the table, so both ends are parsed.
                java.util.List<String> words = new java.util.ArrayList<>(ItemProperties.HOLDS_ONLY.keySet());
                for (String w : java.util.List.of(words.get(0), words.get(words.size() - 1))) {
                    JsonObject one = new JsonObject();
                    one.addProperty(r.key(), w);
                    out.add(new Probe(r.key() + "=" + w, one));
                }
                all.addProperty(r.key(), words.get(0));
                continue;
            }
            if (ItemProperties.STRONGER_WHEN_KEY.equals(r.key())) {
                // Its value is an object: one probe per stat at its cap. The condition word
                // is ours and the parser never reads it, so every condition is a unit test's job.
                JsonObject last = null;
                for (String stat : com.moddingfromamod.item.StrongerWhenRule.STATS) {
                    JsonObject sw = new JsonObject();
                    sw.addProperty("when", "below_y");
                    sw.addProperty("stat", stat);
                    sw.addProperty("amount", "attack_damage".equals(stat)
                            ? com.moddingfromamod.item.StrongerWhenRule.MAX_DAMAGE
                            : com.moddingfromamod.item.StrongerWhenRule.MAX_MINING);
                    sw.addProperty("y", -64);
                    JsonObject one = new JsonObject();
                    one.add(r.key(), sw);
                    out.add(new Probe(r.key() + "=" + stat, one));
                    last = sw;
                }
                all.add(r.key(), last);
                continue;
            }
            for (int v : new int[]{r.min(), r.max()}) {
                JsonObject one = new JsonObject();
                one.addProperty(r.key(), v);
                out.add(new Probe(r.key() + "=" + v, one));
            }
            all.addProperty(r.key(), r.max());
        }
        out.add(new Probe("every property at once", all));
        return out;
    }

    /**
     * An item that asked for no properties gets none.
     *
     * <p>The axis is opt-in and every item ever made carries none, so a new component appearing on
     * all of them would be a regression the matrix above cannot see — it only asks whether a line
     * parses, and a line with an unwanted component parses perfectly.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void anItemWithNoPropertiesIsUnchanged(GameTestHelper helper) {
        try {
            String line = MakeItemTool.gives("held", "zonkbolt_hurt", 9, 1, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Zonkbolt", 0x9662D6)));
            // THE CARRIER'S INERT ENTRY IS NOT A PROPERTY, and excluding it is not a weakening.
            // The route has to write one attribute_modifiers component to stop a vanilla carrier
            // contributing its own armour (see MakeItemTool.itemArg), so on that route the string
            // is legitimately there. What must still be true — and is what this test exists for —
            // is that no PROPERTY was written: no max_stack_size, and no attribute modifier other
            // than the carrier's own inert one. Any other modifier is rejected exactly as before,
            // so the check narrows by one known-good entry rather than dropping an axis.
            boolean unwantedModifier = line.contains("attribute_modifiers")
                    && !line.contains("carrier_inert");
            // AND THE SAME FOR max_stack_size, for the same reason and with the opposite sign. The
            // carrier writes one to MATCH what wish_item already did (it stacks to 64; leather
            // horse armour stacks to 1), so that value is parity, not a property — writing it is
            // how the item stays unchanged. Any OTHER value is a property nobody asked for, and
            // that is what this still rejects.
            boolean unwantedStack = line.contains("max_stack_size")
                    && !line.contains("minecraft:max_stack_size="
                            + com.moddingfromamod.agent.tools.MakeItemTool.WISH_ITEM_STACKS_TO);
            if (unwantedStack || unwantedModifier) {
                helper.fail(Component.literal("an item that asked for no properties got some: " + line));
                return;
            }
            ItemStack stack = parse(helper, line);
            // EVERY item carries a default ATTRIBUTE_MODIFIERS component since 1.20.5, so `!= null`
            // asks nothing. "Is it empty" was the right question while there was one route; on the
            // carrier route the component is deliberately non-empty, because a non-empty one is the
            // only thing that stops NeoForge falling back to the carrier's own +3 armor.
            //
            // So ask what the test actually means instead: does this item CHANGE anything about
            // him? A modifier of amount 0 does not, whoever put it there. That is route-independent
            // and it is strictly stronger than counting with an exception carved out for a known
            // id — a real property leaking in fails here even if it is spelled carrier_inert.
            ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                    if (e.modifier().amount() != 0.0D) {
                        helper.fail(Component.literal("an item with no properties changes " + e.attribute().value()
                                + " by " + e.modifier().amount() + " (" + e.modifier().id() + ")"));
                        return;
                    }
                }
            }
        } catch (CommandSyntaxException bad) {
            helper.fail(Component.literal("the plain give line no longer parses: " + bad));
            return;
        }
        helper.succeed();
    }
}
