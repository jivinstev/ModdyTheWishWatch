package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> — he takes a tile off his watch; it stays off; wishing it again brings it back.</b>
 *
 * <p>On the real stores of a real server, with a thing he really owns (an item, keyed
 * {@code id:<design>} like every {@code make_item} grant — the id shape with a {@code :} that a
 * plain {@code word()} argument cannot carry). Driven through the operator command the account page
 * reaches him by, {@code /moddyop hide}, so the greedy argument and the re-check are what is tested,
 * not a helper. Its own batch: it binds a mock child and writes his (test) shelf.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WatchHideGameTest {

    static final String BATCH = "watch_hide";

    static ItemStack wand(String name, String feature) {
        ItemStack made = new ItemStack(Items.STICK);
        made.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(name));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(com.moddingfromamod.item.WishItem.WISH_FEATURE, feature);
        made.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return made;
    }

    static int op(MinecraftServer server, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, server.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException("'" + line + "' did not parse: " + e.getMessage(), e);
        }
    }

    // The /moddyop hide path is WatchHideCommandGameTest: /moddyop is the private operator root.


    /** The card offers it, and the link runs the registered child command with the tile's id. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theCardOffersHideWithTheTilesOwnId(GameTestHelper helper) {
        WatchPanel.Cell creation = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                "Cake Fork", "id:cake_fork_heal", "", "", null, java.util.List.of());
        WatchPanel.Cell button = new WatchPanel.Cell(0, WatchPanel.Family.PRESET, WatchPanel.Action.WISH,
                "✨ Rain", "chicken rain", "chicken_rain", "", null, java.util.List.of());
        String c = ThingPage.describe(ThingPage.verbs(creation, null));
        String b = ThingPage.describe(ThingPage.verbs(button, null));
        helper.assertTrue(c.contains("Hide from my watch")
                        && c.contains(ChildCommands.hide("id:cake_fork_heal")),
                Component.literal("a creation's card does not offer to hide it by its own id: " + c));
        helper.assertTrue(b.contains(ChildCommands.hide("chicken_rain")),
                Component.literal("a button's card does not offer to hide it by its recipe: " + b));
        helper.succeed();
    }

}
