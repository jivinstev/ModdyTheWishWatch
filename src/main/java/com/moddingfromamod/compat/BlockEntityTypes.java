package com.moddingfromamod.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Building a block-entity type (1.21.4 half): 1.21.2 dropped the Builder for a public ctor. */
public final class BlockEntityTypes {
    private BlockEntityTypes() {
    }

    public static <T extends BlockEntity> BlockEntityType<T> of(
            BlockEntityType.BlockEntitySupplier<T> factory, Block... blocks) {
        return new BlockEntityType<>(factory, blocks);
    }
}
