package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> — the edit really lands on the stack, and mints nothing.</b>
 *
 * <p>{@code ForeignItemEditTest} owns the routing decision; this owns what the decision causes.
 * The spike ({@code ForeignItemEditSpikeGameTest}) already proved the two facts underneath — a
 * component write survives a save and the item keeps its registry identity, measured on a real
 * {@code create:wrench}. What is left to prove is that {@code apply} writes the things the wish
 * actually asks for, and that the stack it wrote onto is <b>the same object</b> the child is
 * holding rather than a copy of it.
 *
 * <p>Deliberately vanilla items: the routing does not care which mod an item came from, and a test
 * that needed Create to run would be one nobody runs on the default tier.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ForeignItemEditGameTest {

    @GameTest(template = "empty_test", batch = "foreignItemEdit")
    public static void theEditLandsOnTheStackItselfAndMintsNothing(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        int stockMax = sword.getMaxDamage();

        JsonObject props = new JsonObject();
        props.addProperty("unbreakable", 1);
        props.addProperty("attack_damage", 12);

        var changed = ForeignItemEdits.apply(sword, "Bloop Slayer", props);

        helper.assertTrue(!changed.isEmpty(), Component.literal("an edit that changes nothing is a silent no-op, "
                + "which reads to the child exactly like the wish not working"));
        helper.assertTrue(sword.has(DataComponents.CUSTOM_NAME),
                Component.literal("he asked for the bloop slayer and got back an unchanged 'Diamond Sword' — there "
                        + "is no way for him to tell it worked"));
        helper.assertTrue(sword.has(DataComponents.UNBREAKABLE),
                Component.literal("'make my diamond sword unbreakable' is the motivating wish and it did not take"));
        helper.assertTrue(sword.has(DataComponents.ATTRIBUTE_MODIFIERS),
                Component.literal("attack_damage did not reach the stack"));
        // The identity check, which is the entire argument for editing over reforging.
        helper.assertTrue(sword.getItem() == Items.DIAMOND_SWORD,
                Component.literal("the stack changed identity, so whatever its own mod does in Java would be gone — "
                        + "that is the reforge failure this route exists to avoid"));
        helper.assertTrue(stockMax > 0, Component.literal("the fixture must be a real tool or unbreakable is inert"));
        helper.succeed();
    }

    /**
     * Unbreakable on something with no durability is inert, and must be REPORTED as not applying
     * rather than claimed.
     */
    @GameTest(template = "empty_test", batch = "foreignItemEdit")
    public static void aPropertyThatCannotApplyIsNotClaimed(GameTestHelper helper) {
        ItemStack dirt = new ItemStack(Items.DIRT);
        JsonObject props = new JsonObject();
        props.addProperty("unbreakable", 1);

        var changed = ForeignItemEdits.apply(dirt, null, props);

        helper.assertTrue(!dirt.has(DataComponents.UNBREAKABLE),
                Component.literal("a block has no durability to protect, so writing it would be a component that "
                        + "does nothing — and the property's own doc says so"));
        helper.assertTrue(changed.isEmpty(),
                Component.literal("nothing applied, so nothing may be reported as applied. Reporting it is how a "
                        + "note ends up promising something the child then cannot find (#324)."));
        helper.succeed();
    }

    /** An id nobody is carrying finds nothing — the condition the REFORGE route turns on. */
    @GameTest(template = "empty_test", batch = "foreignItemEdit")
    public static void anItemNobodyHasIsNotFound(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(ForeignItemEdits.resolves(server, "minecraft:diamond_sword"),
                Component.literal("a real vanilla id must resolve, or every wish routes to REFUSE"));
        helper.assertTrue(!ForeignItemEdits.resolves(server, "minecraft:not_a_real_item"),
                Component.literal("an unknown id must NOT resolve — BuiltInRegistries.ITEM.get returns AIR rather "
                        + "than null for a miss, so an unguarded lookup reads every made-up id as "
                        + "a real item and routes it to an edit that can never find anything"));
        helper.assertTrue(ForeignItemEdits.findCarried(server, "minecraft:diamond_sword") == null,
                Component.literal("there are no players in a GameTest level, so nothing can be carried — this is "
                        + "the REFORGE condition, and it must be reached by an empty search "
                        + "rather than by an exception"));
        helper.succeed();
    }
}
