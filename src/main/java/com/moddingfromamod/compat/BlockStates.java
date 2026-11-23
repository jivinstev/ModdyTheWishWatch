package com.moddingfromamod.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Block-state queries whose arguments changed (1.21.4 half): isSolidRender is state-only. */
public final class BlockStates {
    private BlockStates() {
    }

    public static boolean solidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender();
    }
}
