package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wished item's icon wears what it DOES, and nothing else about it moves.</b>
 *
 * <p>The give line is the production one ({@link MakeItemTool#gives} with the overlay
 * {@link IconOverlays#forEffects} picks), run as a real {@code /give}, so the game's own component
 * parser is the judge — an SNBT {@code strings} list it refuses is a wish that hands over nothing.
 * Then the stack is asked the three things the rest of the mod reads off it: its overlay (what the
 * composite selects on), its NUMBER (what the catalogue and the carrier's table read), and its
 * catalogue key — which must be the same with and without the overlay, or adding a picture to an
 * item would give him a second row for it in his menu.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class IconOverlayGameTest {

    /** Its own batch: mock players are process-wide. */
    private static final String BATCH = "iconoverlay";

    private static ItemStack named(ServerPlayer p, String name) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            var n = s.get(DataComponents.CUSTOM_NAME);
            if (n != null && name.equals(n.getString())) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aLightningWandWearsSparksAndKeepsItsNumber(GameTestHelper helper) {
        int wand = ItemCatalogue.icon("wand");
        String overlay = IconOverlays.forEffects(List.of("lightning"));
        helper.assertValueEqual(overlay, "sparks", Component.literal("lightning is drawn as sparks"));

        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            var server = helper.getLevel().getServer();
            var source = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
            String sparky = MakeItemTool.gives("held", "t6d3c_zap", wand, 1, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Sparky Wand", 0x9966FF)),
                    com.moddingfromamod.datapack.ItemRecipient.ME, null,
                    com.moddingfromamod.datapack.ItemBase.WISH_ITEM, overlay).trim();
            String plain = MakeItemTool.gives("held", "t6d3c_zap", wand, 1, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Plain Wand", 0x9966FF))).trim();
            for (String line : new String[]{sparky, plain}) {
                server.getCommands().performPrefixedCommand(source,
                        line.startsWith("/") ? line.substring(1) : line);
            }
            ItemStack withIt = named(p, "Sparky Wand");
            ItemStack without = named(p, "Plain Wand");
            helper.assertTrue(!withIt.isEmpty(), Component.literal("the game refused the overlaid give line: " + sparky));
            helper.assertTrue(!without.isEmpty(), Component.literal("the game refused the plain give line: " + plain));

            var cmd = withIt.get(DataComponents.CUSTOM_MODEL_DATA);
            helper.assertTrue(cmd != null && ModelData.id(cmd) == wand,
                    Component.literal("the NUMBER must survive the overlay — the catalogue and the old table read it: " + cmd));
            if (ModelData.COMPOSED_ICONS) {
                helper.assertValueEqual(ModelData.overlayOf(cmd), "sparks",
                        Component.literal("strings[0] is what the icon's composite selects its overlay on"));
                helper.assertValueEqual(ModelData.itemModelOf(withIt), ModelData.itemModelOf(without),
                        Component.literal("the overlay rides on the SAME item_model; it is not a second picture"));
            } else {
                helper.assertValueEqual(cmd, without.get(DataComponents.CUSTOM_MODEL_DATA),
                        Component.literal("1.21.1 has nowhere to carry an overlay, so the stamp is the plain one"));
            }
            helper.assertTrue(ModelData.overlayOf(without.get(DataComponents.CUSTOM_MODEL_DATA)) == null,
                    Component.literal("an item that does nothing picturable carries no overlay"));
            helper.assertValueEqual(WishItemCatalogue.keyOf(withIt), WishItemCatalogue.keyOf(without),
                    Component.literal("adding a picture to an item must not give him a second row for it"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }
}
