package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"The piglin has it" is a thing you look at, so this looks.</b>
 *
 * <p><b>⚠ THE BUG THIS EXISTS FOR, AND IT SHIPPED.</b> RECIPIENT first rendered
 * {@code /give @e[type=…] …}, and <b>vanilla {@code /give} takes
 * {@code EntityArgument.players()}</b> — so every wish that handed a thing to a creature produced a
 * command refused with <i>"No player was found"</i>, for every mob, forever.
 *
 * <p>Nothing caught it. The unit tests asserted on the selector, which was correct. The give-line
 * matrix parsed the ITEM ARGUMENT with the game's own parser — and never looked at the verb in
 * front of it, because until this axis existed the verb could only ever be {@code /give}. A
 * perfectly well-formed item argument behind a command that cannot carry it.
 *
 * <p>So this runs the REAL command through the REAL dispatcher at a REAL mob and then asks the mob
 * what it is holding. That is the only question that could not be answered by looking at a string.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ItemRecipientGameTest {

    /**
     * The one command a wish renders, run exactly as the datapack would run it.
     *
     * <p><b>Positioned where the CHILD is</b>, because that is where it really runs — the give
     * function is executed from the player's own command source — and because
     * {@code distance=..24} and {@code sort=nearest} are measured from the source. A stack left at
     * its default position sits at the world origin, millions of blocks from a GameTest plot, and
     * reports "the creature is holding nothing" about a command that is perfectly correct.
     */
    private static void run(GameTestHelper helper, String line, BlockPos standingAt) {
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
                .withLevel(helper.getLevel()).withSuppressedOutput().withPermission(4)
                .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(
                        helper.absolutePos(standingAt)));
        helper.getLevel().getServer().getCommands()
                .performPrefixedCommand(source, line.strip().substring(1));
    }

    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void aCreatureEndsUpHOLDINGIt(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        Zombie mob = helper.spawn(EntityType.ZOMBIE, at);
        mob.setPersistenceRequired();

        String line = MakeItemTool.gives("plain", "gold_axe_plain", 17, 1, "It's yours to keep.",
                List.of(new MakeItemTool.Variant("Unbreaking Gold Axe", 0xFFD700,
                        com.google.gson.JsonParser.parseString("{\"unbreakable\":\"1\"}")
                                .getAsJsonObject())),
                "minecraft:zombie", null, "minecraft:golden_axe");

        if (line.contains("/give ")) {
            helper.fail(Component.literal("a wish for a CREATURE still renders /give, which only takes players — the "
                    + "command is refused every time and nothing anywhere says so: " + line));
            return;
        }
        run(helper, line, at);

        ItemStack held = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty()) {
            helper.fail(Component.literal("the creature is holding nothing — the command ran and did not arrive: "
                    + line));
            return;
        }
        if (!held.getHoverName().getString().contains("Unbreaking Gold Axe")) {
            helper.fail(Component.literal("the creature is holding '" + held.getHoverName().getString()
                    + "', not the thing the wish named"));
            return;
        }
        if (held.get(net.minecraft.core.component.DataComponents.UNBREAKABLE) == null) {
            helper.fail(Component.literal("the axe arrived without the property the whole wish was about"));
            return;
        }
        helper.succeed();
    }

    /**
     * And the child's own wishes still use {@code /give}, which is the only verb that reaches a
     * player's inventory rather than a slot.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void hisOwnWishesStillUseGive(GameTestHelper helper) {
        String line = MakeItemTool.gives("held", "zonkbolt_hurt", 9, 4, "Right-click to use it.",
                List.of(new MakeItemTool.Variant("Zonkbolt", 0x9662D6)));
        if (!line.strip().startsWith("/give @s ")) {
            helper.fail(Component.literal("a wish for the child himself must still be a /give to @s: " + line));
            return;
        }
        if (!line.strip().endsWith(" 4")) {
            helper.fail(Component.literal("the count no longer rides on the end of the give line: " + line));
            return;
        }
        helper.succeed();
    }
}
