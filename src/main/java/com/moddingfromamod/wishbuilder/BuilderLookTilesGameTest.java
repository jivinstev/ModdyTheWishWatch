package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * <b>Every look tile shows the look's own drawn picture</b>: the crown tile is the crown, the cupcake tile the
 * cupcake — the library icon the made item wears, not its category's vanilla stand-in. Opens the real Builder, presses
 * "🎨 More looks…" and every look category, and reads each tile STACK in the chest: its icon number must be the look's
 * ({@code ItemCatalogue.ICONS}), and a tinted icon must be dyed (a greyscale icon reads as grey until it is).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderLookTilesGameTest {

    private BuilderLookTilesGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderLookTiles", timeoutTicks = 400)
    public static void everyLookTileIsTheLooksOwnDrawnIcon(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtlooktiles");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens"));
        BuilderSnackGameTest.click(helper, child, "item");
        BuilderSnackGameTest.click(helper, child, BuilderLooks.MORE_ID);
        List<String> wrong = new ArrayList<>();
        int seen = 0;
        for (BuilderLooks.Group g : BuilderLooks.groups()) {
            BuilderSnackGameTest.click(helper, child, BuilderLooks.GROUP_PREFIX + g.id());
            if (!(child.containerMenu instanceof MenuKit.PickMenu menu)) {
                helper.fail(Component.literal("no pick chest after " + g.id()));
                return;
            }
            for (Map.Entry<Integer, String> e : menu.page().slots().entrySet()) {
                if (!e.getValue().startsWith(BuilderLooks.PREFIX) || e.getValue().equals(BuilderLooks.MORE_ID)) {
                    continue;
                }
                String look = e.getValue().substring(BuilderLooks.PREFIX.length());
                ItemStack s = menu.getSlot(e.getKey()).getItem();
                var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
                int want = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get(look);
                int got = cmd == null ? -1 : com.moddingfromamod.compat.ModelData.id(cmd);
                if (got != want) {
                    wrong.add(look + ": icon " + got + ", not " + want + " (" + s + ")");
                }
                var dyed = s.get(DataComponents.DYED_COLOR);
                int colour = BuilderLooks.tileColour(look);
                if (colour != 0xFFFFFF && (dyed == null || (dyed.rgb() & 0xFFFFFF) != colour)) {
                    wrong.add(look + ": dyed " + dyed + ", not #" + Integer.toHexString(colour));
                }
                seen++;
            }
            BuilderSnackGameTest.click(helper, child, PickPage.BACK_ID);
        }
        helper.assertTrue(seen == BuilderLooks.all().size(),
                Component.literal("walked " + seen + " look tiles of " + BuilderLooks.all().size()));
        helper.assertTrue(wrong.isEmpty(), Component.literal("look tiles not drawn as their look: " + wrong));
        child.closeContainer();
        helper.succeed();
    }
}
