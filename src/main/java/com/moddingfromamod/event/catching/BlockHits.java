package com.moddingfromamod.event.catching;

import java.util.function.IntFunction;

import com.moddingfromamod.event.EventWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

/**
 * <b>What an event does to the blocks it passes over</b> (E3): {@code break_fragile} (hail
 * through a greenhouse, a quake shaking the windows out) and {@code ignite} (scorched ground round a
 * crater). Each tick it strikes a few random columns within a disc — the top block of each — and both
 * go through the event's writer, so both are journaled and undo gives everything back.
 */
@FunctionalInterface
public interface BlockHits {

    void hit(EventWriter w, int age);

    /**
     * Break fragile blocks: {@code perTick} random columns within {@code reach} of wherever
     * {@code centre} says the event is at this age, each broken with {@link CatchRules#breakChance}.
     */
    static BlockHits breakFragile(IntFunction<BlockPos> centre, int reach, int perTick, double strength) {
        Strike s = (w, age, r) -> {
            BlockPos top = top(w, centre.apply(age), reach, r);
            if (top != null && r.nextDouble() < CatchRules.breakChance(fragile(w.level().getBlockState(top)), strength)) {
                w.breakBlock(top);
            }
        };
        return s.times(perTick);
    }

    /**
     * Light fires on the ground: {@code perTick} random columns, each lit with {@code chance} where
     * {@link CatchRules#mayLight} allows — never next to anything that would carry it to his build.
     */
    static BlockHits lightFires(IntFunction<BlockPos> centre, int reach, int perTick, double chance) {
        Strike s = (w, age, r) -> {
            BlockPos top = top(w, centre.apply(age), reach, r);
            if (top == null || r.nextDouble() >= chance) {
                return;
            }
            BlockPos above = top.above();
            ServerLevel level = w.level();
            BlockState ground = level.getBlockState(top);
            if (CatchRules.mayLight(level.getBlockState(above).isAir(),
                    ground.isFaceSturdy(level, top, Direction.UP), flammableNear(level, above))) {
                w.put(above, Blocks.FIRE.defaultBlockState());
            }
        };
        return s.times(perTick);
    }

    /** One strike, repeated {@code n} times a tick with the level's own random. */
    interface Strike {
        void strike(EventWriter w, int age, RandomSource r);

        default BlockHits times(int n) {
            return (w, age) -> {
                for (int i = 0; i < n; i++) {
                    strike(w, age, w.level().getRandom());
                }
            };
        }
    }

    /** The top block of a random column in the disc, or null (unloaded, or nothing there). */
    private static BlockPos top(EventWriter w, BlockPos c, int reach, RandomSource r) {
        if (c == null) {
            return null;
        }
        double a = r.nextDouble() * Math.PI * 2;
        double d = Math.sqrt(r.nextDouble()) * reach;
        int x = c.getX() + (int) Math.round(Math.cos(a) * d);
        int z = c.getZ() + (int) Math.round(Math.sin(a) * d);
        if (!w.level().hasChunkAt(new BlockPos(x, 0, z))) {
            return null;
        }
        int y = w.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        BlockPos p = new BlockPos(x, y, z);
        return w.level().getBlockState(p).isAir() ? null : p;
    }

    /** What sort of fragile thing a block is. */
    static CatchRules.Fragile fragile(BlockState s) {
        if (s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES) || s.is(BlockTags.ICE)) {
            return CatchRules.Fragile.GLASS;
        }
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.CROPS) || s.is(BlockTags.FLOWERS)
                || s.is(net.minecraft.tags.BlockItemTags.SAPLINGS.block()) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)) {
            return CatchRules.Fragile.PLANT;
        }
        if (s.is(BlockTags.CANDLES) || s.is(BlockTags.FLOWER_POTS) || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN)) {
            return CatchRules.Fragile.CLUTTER;
        }
        return CatchRules.Fragile.NONE;
    }

    /** Anything flammable in the box vanilla fire spreads over (±1 across, down 1, up 4). */
    private static boolean flammableNear(ServerLevel level, BlockPos fire) {
        for (BlockPos p : BlockPos.betweenClosed(fire.offset(-1, -1, -1), fire.offset(1, 4, 1))) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.ignitedByLava()) {
                return true;
            }
        }
        return false;
    }
}
