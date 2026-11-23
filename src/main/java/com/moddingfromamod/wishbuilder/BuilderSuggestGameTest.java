package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>Turn-into's suggestions from a real view and a real inventory</b> (§1): he faces a wall of stone with grass
 * at his feet, the From page opens with stone (his crosshair) then grass, the same every time; a chest, bedrock and
 * water in the wall never appear; looking at the sky there is no "looking at" section; his raw iron and sand are
 * offered, never a made item or the helmet he wears. Read through {@link BuilderView} on the real menu path.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderSuggestGameTest {

    private BuilderSuggestGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderSuggest", timeoutTicks = 2000)
    public static void theFromPageOpensWithWhatHeLooksAtAndCarries(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 1));   // the floor under his feet
        List<BlockPos> placed = new ArrayList<>();
        MockPlayerNetwork.afterTest(helper, () -> {
            placed.forEach(b -> level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState()));
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        // Air round him, grass under him, a stone wall four blocks south, three high
        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 6; z++) {
                for (int y = 1; y <= 6; y++) {
                    set(level, placed, base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                set(level, placed, base.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
            for (int y = 1; y <= 4; y++) {
                set(level, placed, base.offset(x, y, 4), Blocks.STONE.defaultBlockState());
            }
        }
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsuggest");
        MockPlayerNetwork.actingChildFor(helper, child);
        child.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 0f, 20f);   // facing south, a little down
        child.getInventory().clearContent();
        child.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 5));
        child.getInventory().setItem(1, new ItemStack(Items.SAND, 20));
        ItemStack made = new ItemStack(Items.STICK, 1);
        CompoundTag mark = new CompoundTag();
        mark.putString("WishItem", "a made wand");
        made.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        child.getInventory().setItem(9, made);   // the backpack
        child.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

        String p = BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND;
        List<PickPage.Tile> first = fromPage(helper, child);
        List<String> ids = first.stream().map(PickPage.Tile::id).toList();
        helper.assertTrue(ids.size() > 2 && ids.get(0).equals(p + "minecraft:stone") && ids.get(1).equals(p + "minecraft:grass_block"),
                Component.literal("stone (his crosshair) then grass first: " + ids));
        helper.assertTrue(first.get(0).lore().equals(List.of(BuilderSuggest.LOOKING_LORE)), Component.literal("lore " + first.get(0).lore()));
        helper.assertTrue(ids.contains(p + "minecraft:raw_iron") && ids.contains(p + "minecraft:sand"),
                Component.literal("his raw iron and sand: " + ids));
        helper.assertTrue(!ids.contains(p + "minecraft:stick") && !ids.contains(p + "minecraft:iron_helmet"),
                Component.literal("never a made item or the helmet he wears: " + ids));
        helper.assertTrue(ids.equals(fromPage(helper, child).stream().map(PickPage.Tile::id).toList()),
                Component.literal("the same view gives the same tiles"));

        // A chest, bedrock and water in the wall, right where he looks: never offered
        set(level, placed, base.offset(0, 1, 4), Blocks.CHEST.defaultBlockState());
        set(level, placed, base.offset(1, 1, 4), Blocks.BEDROCK.defaultBlockState());
        set(level, placed, base.offset(-1, 1, 4), Blocks.WATER.defaultBlockState());
        List<String> after = fromPage(helper, child).stream().map(PickPage.Tile::id).toList();
        for (String no : List.of("minecraft:chest", "minecraft:bedrock", "minecraft:water")) {
            helper.assertTrue(!after.contains(p + no), Component.literal(no + " offered: " + after));
        }

        // The sky: no "looking at" section, and the page still works
        child.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 0f, -80f);
        List<PickPage.Tile> sky = fromPage(helper, child);
        helper.assertTrue(sky.stream().noneMatch(t -> t.lore().equals(List.of(BuilderSuggest.LOOKING_LORE))),
                Component.literal("the sky is nothing to suggest: " + sky.stream().map(PickPage.Tile::id).toList()));
        helper.assertTrue(sky.stream().anyMatch(t -> t.id().equals(BuilderTurnInto.FROM_PREFIX + "any_block")),
                Component.literal("the page's own picks are still there"));
        helper.succeed();
    }

    private static void set(ServerLevel level, List<BlockPos> placed, BlockPos at, BlockState state) {
        level.setBlockAndUpdate(at, state);
        if (!placed.contains(at)) {
            placed.add(at);
        }
    }

    /** Open the Builder fresh, walk to Turn-into's From page through the real menu, and read its tiles. */
    private static List<PickPage.Tile> fromPage(GameTestHelper helper, ServerPlayer child) {
        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens"));
        BuilderMenus.press(child, "item");
        BuilderMenus.press(child, BuilderFlow.STOCK_PREFIX + "wand");
        BuilderMenus.press(child, "itempower:" + BuilderTurnInto.POWER);
        BuilderFlow flow = BuilderMenus.flowOf(child);
        helper.assertTrue(flow != null && flow.step() == BuilderFlow.Step.TURN_FROM,
                Component.literal("at the From page: " + (flow == null ? "no flow" : flow.step())));
        return flow.page().tiles();
    }
}
