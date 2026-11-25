package com.moddingfromamod.redstone;

import com.moddingfromamod.event.EventWriter;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** A {@link WorldView} over a real level. Read on the server thread. */
public final class LevelView implements WorldView {

    private final ServerLevel level;

    public LevelView(ServerLevel level) {
        this.level = level;
    }

    public static BlockPos bp(RsPos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    public static RsPos rs(BlockPos p) {
        return new RsPos(p.getX(), p.getY(), p.getZ());
    }

    @Override
    public Kind kind(RsPos p) {
        BlockPos at = bp(p);
        BlockState s = level.getBlockState(at);
        if (!s.getFluidState().isEmpty()) {
            return Kind.FLUID;
        }
        // short grass, ferns, a snow layer: what the game itself lets a placed block replace counts as open space
        // (measured: a lever had nowhere to go in front of a mansion on a grassy hill)
        if (s.isAir() || s.canBeReplaced()) {
            return Kind.AIR;
        }
        if (locked(s)) {
            return Kind.LOCKED;
        }
        if (EventWriter.natural(s)) {
            return Kind.NATURAL;
        }
        return s.isRedstoneConductor(level, at) ? Kind.SOLID : Kind.OTHER;
    }

    @Override
    public String blockId(RsPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp(p)).getBlock()).toString();
    }

    @Override
    public boolean conductor(RsPos p) {
        BlockPos at = bp(p);
        return level.getBlockState(at).isRedstoneConductor(level, at);
    }

    /** Never replaced: block entities, anything that is or carries redstone, and the unbreakable blocks. */
    public static boolean locked(BlockState s) {
        return s.hasBlockEntity() || s.isSignalSource() || redstone(s)
                || s.is(Blocks.BEDROCK) || s.is(Blocks.BARRIER) || s.is(Blocks.END_PORTAL_FRAME)
                || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY)
                || s.is(Blocks.REINFORCED_DEEPSLATE);
    }

    /** Redstone parts (powered or not), which a new wire must keep away from. */
    public static boolean redstone(BlockState s) {
        return s.is(Blocks.REDSTONE_WIRE) || s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR)
                || s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON) || s.is(Blocks.PISTON_HEAD)
                || s.is(Blocks.MOVING_PISTON) || s.is(Blocks.OBSERVER) || s.is(Blocks.REDSTONE_TORCH)
                || s.is(Blocks.REDSTONE_WALL_TORCH) || s.is(Blocks.LEVER) || s.is(BlockTags.BUTTONS)
                || s.is(BlockTags.PRESSURE_PLATES) || s.is(Blocks.TRIPWIRE) || s.is(Blocks.TRIPWIRE_HOOK)
                || s.is(Blocks.DAYLIGHT_DETECTOR) || s.is(Blocks.TARGET) || s.is(Blocks.NOTE_BLOCK)
                || s.is(Blocks.REDSTONE_LAMP) || s.is(Blocks.REDSTONE_BLOCK) || s.is(Blocks.IRON_DOOR)
                || s.is(Blocks.IRON_TRAPDOOR) || s.is(Blocks.DISPENSER) || s.is(Blocks.DROPPER);
    }

    /** Positions of redstone already in the box (inclusive), which the router keeps away from. */
    public Set<RsPos> foreignRedstone(RsPos a, RsPos b) {
        Set<RsPos> out = new HashSet<>();
        for (int x = Math.min(a.x(), b.x()); x <= Math.max(a.x(), b.x()); x++) {
            for (int y = Math.min(a.y(), b.y()); y <= Math.max(a.y(), b.y()); y++) {
                for (int z = Math.min(a.z(), b.z()); z <= Math.max(a.z(), b.z()); z++) {
                    if (redstone(level.getBlockState(new BlockPos(x, y, z)))) {
                        out.add(new RsPos(x, y, z));
                    }
                }
            }
        }
        return out;
    }
}
