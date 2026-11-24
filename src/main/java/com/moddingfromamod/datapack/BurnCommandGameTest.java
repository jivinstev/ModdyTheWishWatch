package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Make it BURN them": which one command really sets a mob alight?</b> (item 2)
 *
 * <p>The effect catalogue taught {@code damage @s 4 minecraft:on_fire} for "make it BURN them", and
 * both fire-breath grants in the panel used exactly that: the monsters took the damage and
 * never caught fire, so the row failed on both models every time. This pins the command the
 * catalogue now teaches (the same mechanism the item {@code ignite} template uses) and the one it
 * no longer does, so the doc cannot drift back to the form that only hurts.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BurnCommandGameTest {

    private static void runAs(GameTestHelper helper, Entity e, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "execute as " + e.getStringUUID() + " run " + command);
    }

    @GameTest(template = "empty_test")
    public static void theTaughtBurnSetsItAlightAndDamageOnFireDoesNot(GameTestHelper helper) {
        var lit = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 2, 1));
        var hurt = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(3, 2, 3));
        float before = hurt.getHealth();

        runAs(helper, lit, "data modify entity @s Fire set value 100s");
        runAs(helper, hurt, "damage @s 4 minecraft:on_fire");

        helper.assertTrue(lit.getRemainingFireTicks() > 0,
                Component.literal("the command the catalogue teaches for BURN did not set the mob alight"));
        helper.assertTrue(hurt.getHealth() < before,
                Component.literal("damage on_fire did not even hurt, so this test is not measuring what it says"));
        helper.assertTrue(hurt.getRemainingFireTicks() <= 0,
                Component.literal("damage on_fire set the mob alight after all; the catalogue's warning is now wrong"));
        helper.succeed();
    }
}
