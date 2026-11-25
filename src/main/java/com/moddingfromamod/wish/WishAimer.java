package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.WishRun;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Minecraft half of {@link WishAim}: the ray, the world it reads, and the log line.
 * {@link WishAim} decides; this only asks the world and executes.
 */
public final class WishAimer {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-aim");

    private WishAimer() {
    }

    /** His feet, eye height, yaw and pitch right now — taken once, when he wishes. */
    public static WishRun.Aim snapshot(ServerPlayer p) {
        return new WishRun.Aim(p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ(),
                p.getEyeY(), p.getYRot(), p.getXRot());
    }

    static WishAim.Snapshot pure(WishRun.Aim a) {
        return new WishAim.Snapshot(a.x(), a.y(), a.z(), a.eyeY(), a.yaw(), a.pitch());
    }

    /** The world as {@link WishAim} reads it: loaded chunks only, leaves see-through. */
    static WishAim.World world(ServerLevel level) {
        var chunks = level.getChunkSource();
        int bottom = com.moddingfromamod.compat.LevelHeights.bottom(level);
        int top = com.moddingfromamod.compat.LevelHeights.topExclusive(level) - 1;
        return new WishAim.World() {
            final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

            @Override
            public WishAim.Cell at(int x, int y, int z) {
                if (chunks.getChunkNow(x >> 4, z >> 4) == null) {
                    return WishAim.Cell.UNLOADED;
                }
                m.set(x, y, z);
                BlockState s = level.getBlockState(m);
                if (s.isAir() || s.is(BlockTags.LEAVES)) {
                    return WishAim.Cell.AIR;      // a wish goes down through a canopy to the ground
                }
                if (s.isFaceSturdy(level, m, Direction.UP)) {
                    return WishAim.Cell.SOLID;
                }
                if (s.getFluidState().isEmpty()) {
                    return WishAim.Cell.AIR;
                }
                return s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? WishAim.Cell.LAVA
                        : WishAim.Cell.FLUID;
            }

            @Override
            public int bottom() {
                return bottom;
            }

            @Override
            public int top() {
                return top;
            }
        };
    }

    /**
     * <b>Where this wish lands</b>, decided now from where he looks: a ray along his gaze through
     * loaded chunks only (reading an unloaded chunk generates it), stopping at a water
     * surface, then {@link WishAim#aim}.
     */
    public static WishAim.Spot aim(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(WishAim.REACH));
        var chunks = level.getChunkSource();
        double t = com.moddingfromamod.builder.LoadedRay.loadedFraction(eye.x, eye.z, end.x, end.z,
                (cx, cz) -> chunks.getChunkNow(cx, cz) != null);
        WishAim.Hit hit = null;
        boolean sure = false;
        if (t > 0.0) {
            if (t < 1.0) {
                end = eye.add(end.subtract(eye).scale(t));
            }
            BlockHitResult r = level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, player));
            if (r.getType() == HitResult.Type.BLOCK) {
                BlockPos b = r.getBlockPos();
                Direction f = r.getDirection();
                hit = new WishAim.Hit(b.getX(), b.getY(), b.getZ(), f.getStepX(), f.getStepY(), f.getStepZ());
                sure = sure(player, level, r, eye);
            }
        }
        return WishAim.aim(pure(snapshot(player)), hit, world(level), sure);
    }

    /** {@link AimConfidence} for this hit: the face, where on it, how square on, how far, and his last looks. */
    static boolean sure(ServerPlayer player, ServerLevel level, BlockHitResult r, Vec3 eye) {
        BlockPos b = r.getBlockPos();
        Direction f = r.getDirection();
        Vec3 at = r.getLocation();
        double lx = at.x - b.getX(), ly = at.y - b.getY(), lz = at.z - b.getZ();
        double u, v;
        switch (f.getAxis()) {
            case X -> { u = ly; v = lz; }
            case Y -> { u = lx; v = lz; }
            default -> { u = lx; v = ly; }
        }
        Vec3 view = player.getViewVector(1.0f);
        double dot = Math.abs(view.x * f.getStepX() + view.y * f.getStepY() + view.z * f.getStepZ());
        double incidence = Math.toDegrees(Math.asin(Math.min(1.0, dot)));
        boolean solid = world(level).at(b.getX(), b.getY(), b.getZ()) == WishAim.Cell.SOLID;
        return AimConfidence.confident(solid, u, v, incidence, eye.distanceTo(at), GazeHistory.recent(player));
    }

    /**
     * <b>The wish-time lock</b>, used by every path that turns his words into a run: snapshot him,
     * aim, record both on the run, and log the line a later report is checked against. Returns the
     * spot (also for a caller with no run).
     */
    /**
     * (Moddy Media): the next wish this player locks lands HERE, not where he looks (a film's staged pillar).
     * One-shot, consumed by {@link #lockAtWishTime}.
     */
    private static final java.util.Map<java.util.UUID, BlockPos> STAGED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void stageNext(ServerPlayer player, BlockPos at) {
        STAGED.put(player.getUUID(), at.immutable());
    }

    public static boolean hasStage(java.util.UUID player) {
        return STAGED.containsKey(player);
    }

    public static void clearStage(java.util.UUID player) {
        STAGED.remove(player);
    }

    public static BlockPos lockAtWishTime(ServerPlayer player, WishRun run, String wishShort) {
        WishRun.Aim from = snapshot(player);
        BlockPos staged = STAGED.remove(player.getUUID());
        WishAim.Spot s = staged != null ? new WishAim.Spot(staged.getX(), staged.getY(), staged.getZ(), "staged") : aim(player);
        BlockPos at = new BlockPos(s.x(), s.y(), s.z());
        if (run != null) {
            run.aimedFrom(from);
            // §3: what he was looking at and carrying, read now and never again ("turn THIS into gold")
            run.sawWhenWished(com.moddingfromamod.wishbuilder.BuilderView.sample(player));
            run.defaultAnchor(new com.moddingfromamod.marshal.Spot(from.dimension(), s.x(), s.y(), s.z()));
        }
        logAim(wishShort, from, s);
        return at;
    }

    /** {@link WishAim#slide} against this level, or null when the spot does not move. */
    public static WishAim.Spot slide(ServerLevel level, WishRun.Aim from, BlockPos at, WishAim.Kind kind) {
        return slide(level, from, at, kind, null);
    }

    /** …for {@code caster}'s wish: the slide stays inside where this world lets his wish stand (its fence, if any). */
    public static WishAim.Spot slide(ServerLevel level, WishRun.Aim from, BlockPos at, WishAim.Kind kind,
                                     java.util.UUID caster) {
        if (level == null || from == null || at == null) {
            return null;
        }
        com.moddingfromamod.world.Region fence = caster == null ? null
                : com.moddingfromamod.world.WorldModuleHost.fenceFor(caster);
        return WishAim.slide(pure(from), at.getX(), at.getY(), at.getZ(), kind, world(level),
                fence == null ? null : (x, z) -> fence.contains(x + 0.5, z + 0.5));
    }

    /** The log line a report about "where did it land" is checked against. */
    public static void logAim(String wishShort, WishRun.Aim a, WishAim.Spot s) {
        LOG.info("[aim] wish {} from {} {} {} yaw {} pitch {} → {} {} {} ({})", wishShort,
                Math.round(a.x()), Math.round(a.y()), Math.round(a.z()), Math.round(a.yaw()),
                Math.round(a.pitch()), s.x(), s.y(), s.z(), s.how());
    }

    /** A later move of the spot, and why. */
    public static void logMove(String wishShort, BlockPos from, BlockPos to, String why) {
        LOG.info("[aim] wish {} moved {} → {} ({})", wishShort,
                from == null ? "-" : from.toShortString(), to.toShortString(), why);
    }

    /** The spot the thing really landed at, at the end. */
    public static void logFinal(String wishShort, com.moddingfromamod.marshal.Spot s) {
        LOG.info("[aim] wish {} final anchor {}", wishShort, s == null ? "none" : s.x() + ", " + s.y() + ", " + s.z());
    }
}
