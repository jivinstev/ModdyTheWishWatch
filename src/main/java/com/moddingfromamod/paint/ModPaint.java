package com.moddingfromamod.paint;

import java.util.function.Supplier;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration for the painted panel — block, its item, and its block entity type.
 *
 * <p>Three registries, because a block you cannot obtain is not a feature and a block entity type
 * that was never registered fails at the one moment it matters: {@code BlockEntity.getType()} throws
 * on save, so the world is fine until it is closed.
 */
public final class ModPaint {

    // DeferredRegister.Blocks/Items: they stamp the registry id into the Properties, which 1.21.2+
    // requires (R25). Same API on 1.21.1, so shared source. See ModItems.
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(ModdingFromAModMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ModdingFromAModMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModdingFromAModMod.MODID);

    public static final String PANEL_ID = "paint_panel";

    /**
     * Deliberately plain: stone-ish hardness, no tool requirement, and nothing exotic in its
     * properties. Every exotic property is a way for Create to refuse to carry it.
     */
    public static final Supplier<Block> PAINT_PANEL = BLOCKS.<Block>registerBlock(PANEL_ID,
            PaintPanelBlock::new, panelProperties());

    private static BlockBehaviour.Properties panelProperties() {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .strength(1.0F)
                    // ⚠ WITHOUT THIS, A TRANSPARENT PICTURE IS A HOLE IN THE TRAIN.
                    //
                    // A panel sits one block proud of the carriage skin, so the two are touching --
                    // and a solid block culls the face of whatever it is against. Draw the block
                    // invisible (see PaintPanelBlock.getRenderShape) while it still occludes, and
                    // the carriage face behind the picture is culled too: you see through the train
                    // rather than at it. Both halves are needed, and either alone looks like the
                    // other one is broken.
                    // Part of the same A/B seam as PaintPanelBlock.VISIBLE_CUBE: with
                    // -Ptestprops=panelmodel=cube the panel goes back to being an ordinary opaque
                    // cube in every respect, so a ladder run answers "did hiding the block break
                    // Create?" directly.
                    .sound(SoundType.WOOD);
        return "cube".equalsIgnoreCase(System.getProperty("moddingfromamod.panelmodel", ""))
                ? p : p.noOcclusion();
    }

    public static final Supplier<BlockItem> PAINT_PANEL_ITEM = ITEMS.registerSimpleBlockItem(PANEL_ID,
            PAINT_PANEL);

    public static final Supplier<BlockEntityType<PaintPanelBlockEntity>> PAINT_PANEL_BE =
            BLOCK_ENTITIES.register(PANEL_ID,
                    () -> com.moddingfromamod.compat.BlockEntityTypes.of(
                            PaintPanelBlockEntity::new, PAINT_PANEL.get()));

    private ModPaint() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(ModPaint::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(PAINT_PANEL_ITEM.get());
        }
    }
}
