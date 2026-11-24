package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.compat.Wearables;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a wished thing he WEARS goes on his body, and works only while it is there.</b>
 *
 * <p>Every assertion is on a real {@code ServerPlayer} holding a stack the real renderer produced,
 * because the two failures this shape exists to prevent are both invisible to a unit test: a give
 * line the game refuses (a component written twice, a slot it does not know), and an effect that
 * fires whether or not the boots are on — which is the right-click charm was filed about,
 * wearing a costume.
 *
 * <p>On the 1.21.1 compat build there is no {@code equippable}: the same entry points must REFUSE a
 * worn shape by name, never hand back something to right-click.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WornItemGameTest {

    /** Its own batch: mock players are process-wide. */
    private static final String BATCH = "worn";

    private static JsonObject bootsCall(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "helmet");
        o.addProperty("colour", "#33CC55");
        o.addProperty("shape", "worn_feet");
        o.addProperty("effect", "jump");
        o.addProperty("count", 1);
        JsonObject p = new JsonObject();
        p.addProperty("SECONDS", "3");
        p.addProperty("LEVEL", "2");
        o.add("params", p);
        return o;
    }

    private static ItemStack named(ServerPlayer p, String name) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            var n = s.get(DataComponents.CUSTOM_NAME);
            if (n != null && name.equals(n.getString())) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * <b>From scratch: it goes on his FEET, as leather in its colour, and a right-click puts it
     * there.</b> The give line is the production one ({@code MakeItemTool.itemArg} with the shape),
     * run as a real {@code /give}, so the game's own parser is the judge of every component.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWornThingFromScratchGoesOnHisFeet(GameTestHelper helper) {
        if (!Wearables.SUPPORTED) {
            ToolResult r = new MakeItemTool().call(bootsCall("Refused Boots"));
            helper.assertTrue(r.isError() && r.text().contains("WEARS"),
                    Component.literal("1.21.1 must REFUSE a worn shape by name, and it said: " + r.text()));
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            var server = helper.getLevel().getServer();
            var source = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
            String arg = MakeItemTool.itemArg(new MakeItemTool.Variant("Green Boots", 0x33CC55),
                    com.moddingfromamod.datapack.ItemCatalogue.icon("helmet"), "Put it on!", null, null,
                    WishItem.WISH_FEATURE + ":\"t6d2_green_boots\"," + WishItem.WORN + ":\"feet\"",
                    "worn_feet");
            server.getCommands().performPrefixedCommand(source, "give @s " + arg + " 1");
            ItemStack boots = named(p, "Green Boots");
            helper.assertTrue(!boots.isEmpty(), Component.literal("the game refused the worn give line: " + arg));
            helper.assertValueEqual(Wearables.describe(boots), "feet minecraft:leather",
                    Component.literal("where it goes and what is drawn on him"));
            helper.assertValueEqual(com.moddingfromamod.compat.ModelData.itemModelOf(boots),
                    "minecraft:leather_boots", Component.literal("its icon is the vanilla piece, dyed"));
            helper.assertValueEqual(boots.getMaxStackSize(), 1, Component.literal("armour does not stack"));
            var dyed = boots.get(DataComponents.DYED_COLOR);
            helper.assertTrue(dyed != null && dyed.rgb() == 0x33CC55,
                    Component.literal("the colour he asked for is the colour on his body: " + dyed));

            // Right-click it from the hand, through the real game mode, so our carrier bridge
            // (VanillaCarrierAbilities) is in the path and cannot swallow the equip.
            // ⚠ NOT items.remove(boots): the inventory is a NonNullList and remove() throws
            // UnsupportedOperationException with no message -- which Gate B printed as "null".
            ItemStack held = boots.copy();
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, held);
            p.gameMode.useItem(p, helper.getLevel(), p.getItemInHand(InteractionHand.MAIN_HAND),
                    InteractionHand.MAIN_HAND);
            ItemStack worn = p.getItemBySlot(EquipmentSlot.FEET);
            helper.assertTrue(!worn.isEmpty() && "Green Boots".equals(worn.getHoverName().getString()),
                    Component.literal("a right-click did not put the boots on; FEET holds " + worn));
            // Read through the game's own resolver for THAT slot, not off the player: a mock
            // player never ticks, and equipment modifiers reach a player only on its tick.
            double[] armour = {0, 0};
            worn.forEachModifier(EquipmentSlot.FEET, (attr, mod) -> {
                if (attr.is(Attributes.ARMOR)) {
                    armour[0] += mod.amount();
                }
            });
            worn.forEachModifier(EquipmentSlot.BODY, (attr, mod) -> {
                if (attr.is(Attributes.ARMOR)) {
                    armour[1] += mod.amount();
                }
            });
            helper.assertValueEqual(armour[0], 1.0, Component.literal("leather boots' armour on his FEET"));
            helper.assertValueEqual(armour[1], 0.0, Component.literal("nothing of the carrier's +3 on a horse's body"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /**
     * <b>Built on a real piece, it IS that piece</b> — and a piece asked for in another slot is
     * told where to go.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWornThingOnARealPieceLooksLikeThatPiece(GameTestHelper helper) {
        if (!Wearables.SUPPORTED) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            var server = helper.getLevel().getServer();
            var source = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
            String gold = MakeItemTool.itemArg(new MakeItemTool.Variant("Gold Kicks", 0xFFD700), 0,
                    "Put it on!", null, "minecraft:golden_boots",
                    WishItem.WISH_FEATURE + ":\"t6d2_gold\"," + WishItem.WORN + ":\"feet\"", "worn_feet");
            String hat = MakeItemTool.itemArg(new MakeItemTool.Variant("Boot Hat", 0xFFD700), 0,
                    "Put it on!", null, "minecraft:golden_boots",
                    WishItem.WISH_FEATURE + ":\"t6d2_hat\"," + WishItem.WORN + ":\"head\"", "worn_head");
            helper.assertFalse(gold.contains("minecraft:equippable"),
                    Component.literal("golden boots already go on the feet; writing it again is noise: " + gold));
            helper.assertTrue(hat.contains("minecraft:equippable={slot:\"head\"}"),
                    Component.literal("a piece asked for in ANOTHER slot must be told where to go: " + hat));
            server.getCommands().performPrefixedCommand(source, "give @s " + gold + " 1");
            server.getCommands().performPrefixedCommand(source, "give @s " + hat + " 1");
            ItemStack kicks = named(p, "Gold Kicks");
            ItemStack onHead = named(p, "Boot Hat");
            helper.assertTrue(kicks.is(Items.GOLDEN_BOOTS), Component.literal("Gold Kicks is " + kicks));
            helper.assertValueEqual(Wearables.describe(kicks), "feet minecraft:gold",
                    Component.literal("a real piece keeps its own look on the body"));
            helper.assertTrue(onHead.is(Items.GOLDEN_BOOTS) && Wearables.describe(onHead) != null
                            && Wearables.describe(onHead).startsWith("head "),
                    Component.literal("the boot hat goes on the head: " + Wearables.describe(onHead)));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /**
     * <b>The quiet tick: the effect is ON while he wears it, and only then.</b>
     *
     * <p>Through the real tool, so the datapack is the one a child's wish would compile. The boots
     * are first held in the hand (nothing must happen), then worn (the jump arrives), then the
     * effect is cleared while they stay on (the clock must RENEW it), then they come off and the
     * effect is cleared again (nothing may bring it back). Timed in ticks, which is right here: the
     * clock is a datapack clock and counts ticks too.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void theClockRunsTheEffectOnlyWhileItIsWorn(GameTestHelper helper) {
        if (!Wearables.SUPPORTED) {
            helper.succeed();
            return;
        }
        if (!CbScriptCompiler.available()) {
            System.out.println("WORN CLOCK: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String name = "Spring Boots " + helper.getLevel().getGameTime();
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(bootsCall(name));
        if (r.isError()) {
            MockPlayerNetwork.remove(p);
            helper.fail(Component.literal("make_item refused worn_feet + jump: " + r.text()));
            return;
        }
        String feature = tool.lastMadeId();
        String arg = MakeItemTool.itemArg(new MakeItemTool.Variant(name, 0x33CC55),
                com.moddingfromamod.datapack.ItemCatalogue.icon("helmet"), "Put it on!", null, null,
                WishItem.WISH_FEATURE + ":\"" + feature + "\"," + WishItem.WORN + ":\"feet\"",
                "worn_feet");
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                "give @s " + arg + " 1");
        ItemStack boots = named(p, name).copy();
        if (boots.isEmpty()) {
            MockPlayerNetwork.remove(p);
            helper.fail(Component.literal("no boots to wear: " + arg));
            return;
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, boots.copy());
        p.removeAllEffects();

        // RENEW is 3 s x 20 - 10 = 50 ticks; each phase waits comfortably past it.
        helper.runAfterDelay(60, () -> {
            try {
                helper.assertFalse(p.hasEffect(MobEffects.JUMP_BOOST),
                        Component.literal("the boots are only HELD and the jump arrived anyway — that is a charm"));
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setItemSlot(EquipmentSlot.FEET, boots.copy());
            } catch (RuntimeException e) {
                MockPlayerNetwork.remove(p);
                throw e;
            }
        });
        helper.runAfterDelay(70, () -> {
            try {
                helper.assertTrue(p.hasEffect(MobEffects.JUMP_BOOST),
                        Component.literal("the boots went on and no jump arrived (feature " + feature + ")"));
                p.removeAllEffects();
            } catch (RuntimeException e) {
                MockPlayerNetwork.remove(p);
                throw e;
            }
        });
        helper.runAfterDelay(140, () -> {
            try {
                helper.assertTrue(p.hasEffect(MobEffects.JUMP_BOOST),
                        Component.literal("the boots stayed on and the jump was not RENEWED"));
                p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                p.removeAllEffects();
            } catch (RuntimeException e) {
                MockPlayerNetwork.remove(p);
                throw e;
            }
        });
        helper.runAfterDelay(220, () -> {
            try {
                helper.assertFalse(p.hasEffect(MobEffects.JUMP_BOOST),
                        Component.literal("the boots came OFF and the jump came back — it must stop with them"));
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }
}
