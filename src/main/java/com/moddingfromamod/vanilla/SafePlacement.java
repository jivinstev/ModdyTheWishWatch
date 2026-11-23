package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.runtime.SafeSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The Minecraft half of {@link SafeSpot}</b>: read the world as the rule needs it, and
 * carry out what it decides — a footing, a move, a hop, or nothing. {@link SafeSpot} decides; this
 * only asks the world and executes.
 *
 * <p>Four placements ask it, one per way a creature of his died:
 * <ul>
 *   <li>{@link #settleSpawn} — a wished creature just conjured (sculpt, define, {@code wishmob
 *   spawn}): over water, lava or air it gets a FOOTING; in rock it MOVES;</li>
 *   <li>{@link #hop} — a follower hopping to him, vanilla's way;</li>
 *   <li>{@link #clearForBuild} — a build about to land on a creature (or a player): only one
 *   whose box would be inside the build's SOLID blocks is moved, the shortest safe distance, which may be into a room
 *   of the new build or onto its roof. A creature where the build has air stays put — "a castle for
 *   my Spikor" keeps Spikor inside it.</li>
 * </ul>
 *
 * <p>A move he could lose ({@link SafeSpot#SILENT_MOVE}) is told three ways: Moddy says where it
 * went, the creature glows for {@value #GLOW_TICKS} ticks (vanilla glowing, which a stock client
 * draws through walls), and — for a spawn — the wish's spot moves to it through
 * {@code WishProgress.focusOn}, so its pillar GLIDES there (an earlier issue's one glide, never a rebuild) and
 * the reveal and its camera frame where it really is.
 */
public final class SafePlacement {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-safespot");

    /** How long a moved creature glows: about five seconds. */
    public static final int GLOW_TICKS = 100;

    private SafePlacement() {
    }

    // ── the world, as the rule reads it ──────────────────────────────────────────────────────

    /** {@link #world(ServerLevel, Map)} with nothing overlaid. */
    public static SafeSpot.World world(ServerLevel level) {
        return world(level, Map.of());
    }

    /**
     * The world as {@link SafeSpot} reads it: loaded chunks only (reading an unloaded one generates
     * it), with {@code overlay} (packed positions → states) standing in for the world where a build
     * is about to put blocks.
     */
    public static SafeSpot.World world(ServerLevel level, Map<Long, BlockState> overlay) {
        var chunks = level.getChunkSource();
        return new SafeSpot.World() {
            final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

            @Override
            public SafeSpot.Cell at(int x, int y, int z) {
                if (chunks.getChunkNow(x >> 4, z >> 4) == null) {
                    return SafeSpot.Cell.UNLOADED;
                }
                m.set(x, y, z);
                BlockState s = overlay.isEmpty() ? null : overlay.get(m.asLong());
                if (s == null) {
                    s = level.getBlockState(m);
                }
                return cell(level, m, s);
            }
        };
    }

    /** One block, as the rule sees it. */
    static SafeSpot.Cell cell(Level level, BlockPos pos, BlockState s) {
        if (s.isAir()) {
            return SafeSpot.Cell.OPEN;
        }
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return SafeSpot.Cell.LAVA;
        }
        if (s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.WITHER_ROSE)) {
            return SafeSpot.Cell.HAZARD;
        }
        if (s.is(BlockTags.LEAVES)) {
            return SafeSpot.Cell.LEAVES;
        }
        if (!s.getCollisionShape(level, pos).isEmpty()) {
            return s.isFaceSturdy(level, pos, Direction.UP) ? SafeSpot.Cell.FLOOR : SafeSpot.Cell.OBSTACLE;
        }
        if (s.getFluidState().is(FluidTags.WATER)) {
            return SafeSpot.Cell.WATER;
        }
        return SafeSpot.Cell.OPEN;
    }

    /** Its box and how it gets about — a flier by its species' power or its carrier, a swimmer by its navigation. */
    public static SafeSpot.Body body(LivingEntity e) {
        SafeSpot.Moves moves = SafeSpot.Moves.WALK;
        if (e instanceof Mob mob) {
            if (RigPowers.flies(mob) || com.moddingfromamod.compat.Mobs.isFlyingMob(mob)
                    || mob.getNavigation() instanceof FlyingPathNavigation || mob.isNoGravity()) {
                moves = SafeSpot.Moves.FLY;
            } else if (mob.getNavigation() instanceof WaterBoundPathNavigation) {
                moves = SafeSpot.Moves.SWIM;
            }
        }
        return new SafeSpot.Body(e.getBbWidth(), e.getBbHeight(), moves);
    }

    // ── what happened, for the agent and the tests ───────────────────────────────────────────

    /**
     * What a placement did. {@code to} is where it stands now; {@code said} is Moddy's line, or null
     * when the move was too small to mention (or nothing moved).
     */
    public record Settled(String what, SafeSpot.Spot from, SafeSpot.Spot to, String said) {

        static Settled stayed(LivingEntity e) {
            SafeSpot.Spot at = new SafeSpot.Spot(e.getX(), e.getY(), e.getZ());
            return new Settled("ok", at, at, null);
        }

        public boolean moved() {
            return "moved".equals(what);
        }

        /** One clause the agent's tool result carries, so its announcement says the right thing. */
        public String forTheAgent() {
            return switch (what) {
                case "footing" -> " (The aimed spot had nothing safe to stand on, so it got a little"
                        + " footing there.)";
                case "moved" -> String.format(java.util.Locale.ROOT,
                        " (The aimed spot was not safe, so it stands %.0f block(s) away, at %d %d %d%s.)",
                        from.distanceTo(to), (int) Math.floor(to.x()), (int) Math.floor(to.y()),
                        (int) Math.floor(to.z()), said == null ? "" : "; I told him where");
                case "stuck" -> " (⚠ Nowhere near the aimed spot is safe for it — check it is alive.)";
                default -> "";
            };
        }
    }

    // ── 1. a spawn: build a footing, or move it ──────────────────────────────────────────────

    /**
     * <b>Make the spot a creature was just conjured at safe</b> — the same tick, before it can
     * suffocate, fall or burn. Over water, lava or open air a walker gets a footing (journalled, so
     * undoing the wish takes it away); anything else that fails moves to the nearest safe spot.
     *
     * @param first the first creature of the spawn: only it moves the wish's spot and is talked
     *              about (a herd of moved cows is one line, not ten)
     */
    public static Settled settleSpawn(ServerPlayer player, LivingEntity carrier, boolean first) {
        if (carrier == null || !(carrier.level() instanceof ServerLevel level)) {
            return new Settled("ok", null, null, null);
        }
        SafeSpot.Body body = body(carrier);
        SafeSpot.Spot from = new SafeSpot.Spot(carrier.getX(), carrier.getY(), carrier.getZ());
        SafeSpot.Fix fix = SafeSpot.fix(world(level), body, from.x(), from.y(), from.z());
        String name = nameOf(carrier);
        return switch (fix) {
            case SafeSpot.Ok ok -> Settled.stayed(carrier);
            case SafeSpot.Footing f -> {
                int laid = lay(level, f, wishOf());
                LOG.info("[safespot] {} at {} was {} -> laid a {}-block footing", name, fmt(from), f.why(), laid);
                yield new Settled("footing", from, from, null);
            }
            case SafeSpot.Move mv -> {
                String said = move(carrier, mv.to(), SafeSpot.whyFor(mv.why()), player, first, true);
                LOG.info("[safespot] {} at {} was {} -> moved to {}", name, fmt(from), mv.why(), fmt(mv.to()));
                yield new Settled("moved", from, mv.to(), said);
            }
            case SafeSpot.Stuck st -> {
                LOG.warn("[safespot] {} at {} is {} and nothing in reach is safe -> left where it was",
                        name, fmt(from), st.why());
                yield new Settled("stuck", from, from, null);
            }
        };
    }

    /**
     * Put it there, and — for a move he could lose — tell him: it glows, Moddy says where, and (for
     * a spawn) the wish's spot glides to it.
     */
    static String move(LivingEntity e, SafeSpot.Spot to, SafeSpot.Why why, ServerPlayer tell,
                       boolean announce, boolean moveTheWish) {
        SafeSpot.Spot from = new SafeSpot.Spot(e.getX(), e.getY(), e.getZ());
        if (e instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        e.teleportTo(to.x(), to.y(), to.z());
        e.setDeltaMovement(0, 0, 0);
        e.resetFallDistance();
        if (from.distanceTo(to) <= SafeSpot.SILENT_MOVE) {
            return null;
        }
        // Vanilla glowing, so a stock client draws it through the wall it was moved out of; the
        // rig mirrors the carrier's glow onto its displays (RigGlow).
        e.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, false, false));
        if (!announce || tell == null) {
            return null;
        }
        String line = SafeSpot.told(nameOf(e), from, to, why);
        if (line != null) {
            com.moddingfromamod.wish.WishFeedback.moved(tell, line);
        }
        if (moveTheWish) {
            // THE SAME SEAM conjure_at uses: it locks the run's anchor, so the pillar GLIDES to the
            // new spot (one glide, never torn down — WishSpotGameTest), and FOCUS, so the reveal
            // and its camera frame where the creature really is.
            BlockPos at = BlockPos.containing(to.x(), to.y(), to.z());
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            com.moddingfromamod.wish.WishAimer.logMove(run == null ? "-" : run.shortId(),
                    BlockPos.containing(from.x(), from.y(), from.z()), at, "safe spot: " + why);
            com.moddingfromamod.wish.WishProgress.focusOn(tell, at);
        }
        return line;
    }

    // ── 2. the follower hop ──────────────────────────────────────────────────────────────────

    /**
     * <b>Hop a follower to its owner</b>, vanilla's way: ten tries within three blocks of
     * him, each one a {@link SafeSpot}; never while he is airborne unless it flies. False when no
     * spot passed — it does not hop this time, and keeps walking.
     */
    public static boolean hop(ServerLevel level, Mob mob, Entity owner) {
        SafeSpot.Body body = body(mob);
        SafeSpot.Spot to = SafeSpot.hop(world(level), body, owner.getX(), owner.getY(), owner.getZ(),
                airborne(level, owner), new java.util.Random(mob.getRandom().nextLong()));
        if (to == null) {
            return false;
        }
        mob.getNavigation().stop();
        mob.teleportTo(to.x(), to.y(), to.z());
        mob.resetFallDistance();
        return true;
    }

    /**
     * Is he off the ground — flying in Creative, gliding, or with nothing under his feet? Read from
     * the WORLD, never from {@code onGround()}, which a server only knows from what his client says.
     */
    public static boolean airborne(ServerLevel level, Entity owner) {
        if (owner instanceof Player p && (p.getAbilities().flying || p.isFallFlying())) {
            return true;
        }
        if (owner.isPassenger() || owner.isInWater()) {
            return false;
        }
        SafeSpot.World w = world(level);
        int x = (int) Math.floor(owner.getX()), z = (int) Math.floor(owner.getZ());
        int y = (int) Math.floor(owner.getY() - 0.01);
        for (int k = 0; k < 2; k++) {
            SafeSpot.Cell c = w.at(x, y - k, z);
            if (c != SafeSpot.Cell.OPEN && c != SafeSpot.Cell.UNLOADED) {
                return false;
            }
        }
        return true;
    }

    // ── 3. a build about to land on a creature ───────────────────────────────────────────────

    /**
     * <b>Before a build places a block, move every creature its SOLID blocks would bury</b> — the
     * shortest safe distance, judged against the world as it will be once the build is in, so the
     * nearest safe spot may be a room inside the new building or its roof. A creature standing
     * where the build has air is left alone.
     *
     * @param work    the build's blocks, packed {@code [BlockPos.asLong, paletteIndex]}
     * @param palette their states
     * @param tell    the child to tell about a wished creature that moved far, or null
     * @return the creatures moved
     */
    public static List<Settled> clearForBuild(ServerLevel level, List<long[]> work, List<BlockState> palette,
                                              ServerPlayer tell) {
        List<Settled> out = new ArrayList<>();
        if (level == null || work == null || work.isEmpty()) {
            return out;
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (long[] e : work) {
            long p = e[0];
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            z0 = Math.min(z0, z); z1 = Math.max(z1, z);
        }
        AABB bounds = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        List<Mob> inside = level.getEntitiesOfClass(Mob.class, bounds,
                m -> m.isAlive() && !m.isPassenger() && !m.isRemoved());
        // PLAYERS too. A spaghetti volcano went up around the child who wished it, and he
        // had to wish himself out; only the nine creatures beside him were moved.
        List<ServerPlayer> people = level.getEntitiesOfClass(ServerPlayer.class, bounds,
                p -> p.isAlive() && !p.isSpectator() && !p.isRemoved());
        if (inside.isEmpty() && people.isEmpty()) {
            return out;
        }
        // Only the blocks near a creature are worth overlaying: a 200,000-block castle with one
        // pig in the corner needs a few hundred of them, not a map of the whole thing. A player
        // gets the wider reach his rescue may need (a child in the middle of a volcano).
        int r = SafeSpot.RADIUS + 3;
        List<AABB> near = new ArrayList<>();
        for (Mob m : inside) {
            near.add(m.getBoundingBox().inflate(r, 0, r).expandTowards(0, SafeSpot.UP + 3, 0)
                    .expandTowards(0, -(SafeSpot.DOWN + 3), 0));
        }
        int wide = SafeSpot.WIDE_RADIUS + 3;
        for (ServerPlayer p : people) {
            near.add(p.getBoundingBox().inflate(wide, 0, wide).expandTowards(0, SafeSpot.WIDE_UP + 3, 0)
                    .expandTowards(0, -(SafeSpot.DOWN + 3), 0));
        }
        Map<Long, BlockState> overlay = new HashMap<>();
        for (long[] e : work) {
            long p = e[0];
            double x = BlockPos.getX(p) + 0.5, y = BlockPos.getY(p) + 0.5, z = BlockPos.getZ(p) + 0.5;
            for (AABB a : near) {
                if (a.contains(x, y, z)) {
                    overlay.put(p, palette.get((int) e[1]));
                    break;
                }
            }
        }
        SafeSpot.World after = world(level, overlay);
        for (Mob m : inside) {
            SafeSpot.Body body = body(m);
            SafeSpot.Problem p = SafeSpot.check(after, body, m.getX(), m.getY(), m.getZ());
            if (p != SafeSpot.Problem.NO_ROOM && p != SafeSpot.Problem.HAZARD) {
                continue;       // the build has air (or a floor) where it stands: it stays
            }
            SafeSpot.Spot from = new SafeSpot.Spot(m.getX(), m.getY(), m.getZ());
            SafeSpot.Spot to = SafeSpot.nearest(after, body, from.x(), from.y(), from.z());
            if (to == null) {
                LOG.warn("[safespot] {} at {} would be inside the build and nothing near is safe", nameOf(m), fmt(from));
                out.add(new Settled("stuck", from, from, null));
                continue;
            }
            boolean his = DisplayRig.speciesOn(m) != null;
            String said = move(m, to, SafeSpot.Why.BUILDING, tell, his, false);
            LOG.info("[safespot] {} at {} was in the build's way -> moved to {}", nameOf(m), fmt(from), fmt(to));
            out.add(new Settled("moved", from, to, said));
        }
        for (ServerPlayer p : people) {
            SafeSpot.Spot from = new SafeSpot.Spot(p.getX(), p.getY(), p.getZ());
            SafeSpot.Spot to = rescueSpot(after, body(p), from);
            if (to == null) {
                continue;       // the build has air where he stands (or nowhere is safe: logged there)
            }
            String said = movePlayer(p, to, from, SafeSpot.YouWhy.BUILDING);
            LOG.info("[safespot] player {} at {} was in the build's way -> moved to {}",
                    p.getScoreboardName(), fmt(from), fmt(to));
            out.add(new Settled("moved", from, to, said));
        }
        return out;
    }

    // ── 4. a PLAYER who cannot stay where he is ──────────────────────────────────────

    /**
     * Where a player at {@code from} must go, or null when he may stay: a spot is left alone unless
     * it is {@link SafeSpot#buried} (inside blocks, or burning). Null as well when nothing in the
     * wider reach is safe, which is logged, because vanilla then does what it always did.
     */
    static SafeSpot.Spot rescueSpot(SafeSpot.World w, SafeSpot.Body b, SafeSpot.Spot from) {
        if (!SafeSpot.buried(SafeSpot.check(w, b, from.x(), from.y(), from.z()))) {
            return null;
        }
        SafeSpot.Spot to = SafeSpot.nearestOrWider(w, b, from.x(), from.y(), from.z());
        if (to == null) {
            LOG.warn("[safespot] a player at {} is buried and nothing within {} blocks is safe", fmt(from),
                    SafeSpot.WIDE_RADIUS);
        }
        return to;
    }

    /** {@link #rescueSpot} against the world as it stands. */
    public static SafeSpot.Spot rescueSpot(ServerPlayer p) {
        return rescueSpot(world(p.level()), body(p), new SafeSpot.Spot(p.getX(), p.getY(), p.getZ()));
    }

    /**
     * <b>A home spot he can actually stand on</b>. A home is one fixed point, and a wish can
     * build on it — the child's volcano did, and every respawn put him back inside it. Returns the
     * nearest safe spot when the home is buried, else the home itself. An UNLOADED home is returned
     * as it is (reading it would generate or load chunks on the server thread);
     * {@link StuckRescue} catches a buried one the moment he takes damage there.
     */
    public static SafeSpot.Spot safeHome(ServerLevel level, ServerPlayer p, double x, double y, double z) {
        SafeSpot.Spot home = new SafeSpot.Spot(x, y, z);
        SafeSpot.Spot to = rescueSpot(world(level), body(p), home);
        return to == null ? home : to;
    }

    /** is {@code (x, y, z)} somewhere he cannot stand — inside blocks, or in or over lava or fire? */
    public static boolean buriedAt(ServerLevel level, ServerPlayer p, double x, double y, double z) {
        return SafeSpot.buried(SafeSpot.check(world(level), body(p), x, y, z));
    }

    /** Move a player and tell him why (unless it was a step). Returns what he was told, or null. */
    public static String movePlayer(ServerPlayer p, SafeSpot.Spot to, SafeSpot.Spot toldFrom, SafeSpot.YouWhy why) {
        com.moddingfromamod.compat.Teleports.player(p, p.level(), to.x(), to.y(), to.z(),
                p.getYRot(), p.getXRot());
        p.setDeltaMovement(0, 0, 0);
        p.resetFallDistance();
        String line = SafeSpot.toldYou(toldFrom, to, why);
        com.moddingfromamod.wish.WishFeedback.moved(p, line);
        return line;
    }

    // ── the footing, and taking it away again ────────────────────────────────────────────────

    /** One footing block: where, what was there, and what we put. */
    private record Laid(ResourceKey<Level> dimension, long pos, BlockState prior, BlockState placed) {
    }

    /** Footings by wish, so an undo takes them away. The last {@value #KEEP} wishes. */
    private static final int KEEP = 64;
    private static final Map<UUID, List<Laid>> FOOTINGS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, List<Laid>> eldest) {
            return size() > KEEP;
        }
    };

    private static UUID wishOf() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : run.wishId();
    }

    /** Lay a footing: obsidian over lava, stone over anything else. Returns how many blocks. */
    static int lay(ServerLevel level, SafeSpot.Footing f, UUID wishId) {
        List<Laid> laid = new ArrayList<>();
        for (int[] c : f.cells()) {
            BlockPos p = new BlockPos(c[0], c[1], c[2]);
            BlockState prior = level.getBlockState(p);
            BlockState put = prior.getFluidState().is(FluidTags.LAVA) ? Blocks.OBSIDIAN.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
            level.setBlock(p, put, Block.UPDATE_ALL);
            laid.add(new Laid(level.dimension(), p.asLong(), prior, put));
        }
        if (wishId != null) {
            synchronized (FOOTINGS) {
                FOOTINGS.computeIfAbsent(wishId, k -> new ArrayList<>()).addAll(laid);
            }
        }
        return laid.size();
    }

    /** How many footing blocks this wish laid that an undo would take back. */
    public static int footings(UUID wishId) {
        synchronized (FOOTINGS) {
            List<Laid> l = FOOTINGS.get(wishId);
            return l == null ? 0 : l.size();
        }
    }

    /**
     * <b>Undo a wish's footings</b>: every block still the one we put goes back to what it was
     * (lava, water, air). A block he has since changed is his, and stays. Returns how many.
     */
    public static int undoFootings(MinecraftServer server, UUID wishId) {
        List<Laid> laid;
        synchronized (FOOTINGS) {
            laid = wishId == null ? null : FOOTINGS.remove(wishId);
        }
        if (server == null || laid == null) {
            return 0;
        }
        int back = 0;
        for (Laid l : laid) {
            ServerLevel level = server.getLevel(l.dimension());
            if (level == null) {
                continue;
            }
            BlockPos p = BlockPos.of(l.pos());
            if (level.getBlockState(p) == l.placed()) {
                level.setBlock(p, l.prior(), Block.UPDATE_ALL);
                back++;
            }
        }
        if (back > 0) {
            LOG.info("[safespot] undo took {} footing block(s) away", back);
        }
        return back;
    }

    private static String nameOf(Entity e) {
        String label = com.moddingfromamod.mob.Creatures.label(e);
        if (label != null && !label.isBlank()) {
            return label;
        }
        return e.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
    }

    static String fmt(SafeSpot.Spot s) {
        return s == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", s.x(), s.y(), s.z());
    }
}
