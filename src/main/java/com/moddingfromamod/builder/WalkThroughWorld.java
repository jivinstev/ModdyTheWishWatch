package com.moddingfromamod.builder;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>The Minecraft half of {@link WalkThrough}</b>: the same walker over a piece of a REAL world —
 * the building as the {@link Builder} left it, its footing, the terrain round it, trees and water — so a
 * GameTest can ask "can he walk in?" of what actually stands, and a film can walk the route it finds.
 * Reads blocks only; changes nothing.
 */
public final class WalkThroughWorld {

    private WalkThroughWorld() {
    }

    /**
     * The walker over the building standing at {@code anchor} with these plan bounds, the region reaching
     * {@code margin} blocks past it on every side and from well under its floor to above its roof.
     */
    public static WalkThrough of(ServerLevel level, BlockPos anchor, BuildFraming.Bounds b, int margin) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return WalkThrough.world((x, y, z) -> {
            BlockState st = level.getBlockState(m.set(x, y, z));
            if (st.isAir()) {
                return null;
            }
            if (st.getBlock() == Blocks.WATER) {
                return "minecraft:water";
            }
            return BlockStateParser.serialize(st);
        }, anchor.getX() + b.minX() - margin, anchor.getY() + Math.min(b.minY(), -1) - 12,
                anchor.getZ() + b.minZ() - margin, anchor.getX() + b.maxX() + margin, anchor.getY() + b.maxY() + 4,
                anchor.getZ() + b.maxZ() + margin,
                anchor.getX() + b.minX(), anchor.getX() + b.maxX(), anchor.getZ() + b.minZ(), anchor.getZ() + b.maxZ());
    }
}
