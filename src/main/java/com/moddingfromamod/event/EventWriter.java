package com.moddingfromamod.event;

import com.moddingfromamod.compat.LevelHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * <b>The only way an event touches the world</b> (E1). One per event per tick.
 *
 * <p>Every write goes through here, so every rule holds for every motion without the motion knowing
 * it: the guards decide whether a cell may be written at all, the budget whether there is any left,
 * the journal remembers what was there first, the flags reach the client, and every fluid written
 * this tick is pinned before the level can let it flow.
 */
public final class EventWriter {

    /**
     * Send to clients; skip neighbour shape updates. Without {@link Block#UPDATE_CLIENTS} the server
     * holds a magnificent wave nobody can see (an earlier mod's tidal wave).
     */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final StagedEvent event;
    private final EventBudget budget;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private int written;

    EventWriter(ServerLevel level, StagedEvent event, EventBudget budget) {
        this.level = level;
        this.event = event;
        this.budget = budget;
    }

    public ServerLevel level() {
        return level;
    }

    /** True when this tick's allowance is spent; a motion can stop early rather than keep asking. */
    public boolean full() {
        return budget.tickSpent();
    }

    /** True when this cell is one the event has written and still holds in its journal. */
    public boolean ours(BlockPos pos) {
        return event.journal.holds(pos.asLong());
    }

    /**
     * Put {@code state} at {@code pos}, if the guards and the budget allow it. Returns whether the
     * block was written. A cell the event already holds is always writable (it is the event's own
     * water or rubble); anything else must pass the event's {@link EventGuard.Policy}.
     */
    public boolean put(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        boolean held = event.journal.holds(key);
        if (!held && !level.hasChunkAt(pos)) {
            event.refused++;
            return false;
        }
        BlockState current = level.getBlockState(pos);
        if (held) {
            if (current != event.journal.written(key)) {
                // Something else changed our cell since we wrote it — the child's block in the
                // water. It is his now: never write over it.
                event.refused++;
                return false;
            }
            if (current == state) {
                return true; // already ours and already right: nothing written, nothing spent
            }
        } else if (!EventGuard.mayWrite(facts(level, pos, current), event.policy)) {
            event.refused++;
            return false;
        } else if (current == state) {
            return true; // already what we want (air dug out of air): nothing to change or remember
        }
        if (!budget.tryTake(event.journal.size(), !held)) {
            event.refused++;
            return false;
        }
        event.journal.record(key, current, state);
        level.setBlock(pos, state, FLAGS);
        event.reach(pos.getX(), pos.getY(), pos.getZ());
        grow(pos);
        return true;
    }

    /**
     * Give one cell back mid-event (a moving front that has passed it). Does nothing when the cell
     * is not ours, or no longer holds what we wrote (the child built on it).
     */
    public void release(BlockPos pos) {
        BlockState back = event.journal.release(pos.asLong(), level.getBlockState(pos));
        if (back != null) {
            level.setBlock(pos, back, FLAGS);
            grow(pos);
        }
    }

    /**
     * Leave {@code state} in a cell the event holds, for good: a wrecking wave that smashed a wall leaves AIR where
     * its water recedes, not the wall. The journal keeps what was there FIRST, so undo still
     * rebuilds the wall. Does nothing when the cell is not ours or no longer holds what we wrote.
     */
    public void leave(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (!event.journal.holds(key) || level.getBlockState(pos) != event.journal.written(key)) {
            return;
        }
        event.journal.record(key, null, state);   // held: its prior is kept, only what we wrote changes
        level.setBlock(pos, state, FLAGS);
        grow(pos);
    }

    /**
     * The Y of the top block of the ground at a column, reading PAST the event's own blocks — a wave
     * that took its own water for the ground climbed into the sky one block a tick (an earlier mod). Returns
     * {@link Integer#MIN_VALUE} for a column whose chunk is not loaded: an event never generates
     * world ahead of itself.
     */
    public int surface(int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return Integer.MIN_VALUE;
        }
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z) - 1;
        int floor = LevelHeights.bottom(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        while (y > floor && (ours(p.setY(y)) || level.getBlockState(p).canBeReplaced()
                && level.getBlockState(p).getFluidState().isEmpty())) {
            y--;
        }
        return y;
    }

    /** The facts the pure guard needs about one cell. */
    static EventGuard.Cell facts(ServerLevel level, BlockPos pos, BlockState state) {
        return new EventGuard.Cell(
                true,
                pos.getY() > LevelHeights.bottom(level),
                state.hasBlockEntity(),
                state.getDestroySpeed(level, pos) < 0,
                !state.getFluidState().isEmpty(),
                open(state),
                natural(state));
    }

    /**
     * Space an event may fill: air, anything vanilla lets you place into (grass, snow layers), and
     * the small plants of a meadow. A wave rolls over a field of flowers and they are there again
     * afterwards (an earlier mod's own wave), so a poppy must not stop it — vanilla says a poppy is not
     * replaceable, which is about a player's click, not about water.
     */
    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockItemTags.SAPLINGS.block());
    }

    /**
     * Ground a crater or a crack may cut through: soil, the stone the world is made of, sand, snow
     * and ice. Nothing a child builds with by choice — planks, bricks, glass, wool, cut or polished
     * stone — is on this list, and that is what protects his builds from an event that digs.
     */
    public static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.SAND)
                || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY);
    }

    /**
     * Put an entity into the world as part of this event: tagged, so the undo can find it again and
     * so an effect never mistakes his own snowball for the event's hail, and counted, so an event that
     * wrote no block but rained twenty chickens is still there to be undone.
     */
    public void spawn(net.minecraft.world.entity.Entity e) {
        adopt(e);
        // A wished creature: the world guard counts it, and stops a shower at the class's
        // ceiling when earlier waves already fill the world.
        com.moddingfromamod.mob.SpawnScope.during("event:" + event.id, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.BEHAVIOUR, () -> level.addFreshEntity(e));
        // a crowd he wished is his, so a horn he asks for ("call my wolves") finds it.
        if (e instanceof net.minecraft.world.entity.Mob m && event.owner != null && !event.owner.isEmpty()) {
            var who = level.getServer().getPlayerList().getPlayerByName(event.owner);
            if (who != null) {
                com.moddingfromamod.mob.PetBehaviours.claim(m, who.getUUID());
            }
        }
        // what it IS from the moment it arrives — before it can teleport off, wander away or
        // pick a fight — through modify_mob's own applier, so the words mean what they mean there.
        if (!event.traits.isEmpty() && e instanceof net.minecraft.world.entity.LivingEntity living
                && living.isAlive()) {
            try {
                var tweak = com.moddingfromamod.mob.MobTweak.fromJson(
                        com.google.gson.JsonParser.parseString(event.traits).getAsJsonObject());
                var who = event.owner == null || event.owner.isEmpty() ? null
                        : level.getServer().getPlayerList().getPlayerByName(event.owner);
                com.moddingfromamod.agent.tools.ModifyMobTool.applyTo(level.getServer().getScoreboard(), living,
                        tweak, who == null ? null : who.getUUID());
            } catch (RuntimeException bad) {
                org.slf4j.LoggerFactory.getLogger("moddingfromamod").warn(
                        "event {}: could not give a spawned {} its traits", event.id, e.getType(), bad);
            }
        }
    }

    /** Claim an entity something else already added (a falling block made by {@code fall}). */
    public void adopt(net.minecraft.world.entity.Entity e) {
        e.addTag(EventDirector.entityTag(event.id));
        event.spawned++;
    }

    /** Count a cell the director wrote itself (a restore), so {@link #pin} covers it. */
    void touched(BlockPos p) {
        grow(p);
    }

    private void grow(BlockPos p) {
        written++;
        minX = Math.min(minX, p.getX());
        minY = Math.min(minY, p.getY());
        minZ = Math.min(minZ, p.getZ());
        maxX = Math.max(maxX, p.getX());
        maxY = Math.max(maxY, p.getY());
        maxZ = Math.max(maxZ, p.getZ());
    }

    /**
     * Pin every fluid this tick wrote. Placing water schedules it to flow whatever the flags say
     * ({@code LiquidBlock.onPlace}); the director runs on {@code ServerTickEvent.Post}, after this
     * tick's fluid ticks have already run, so clearing them here means none of ours ever fires.
     * Removing water is pinned too: a neighbour of the removed cell may have been told to spread.
     */
    void pin() {
        if (written == 0) {
            return;
        }
        level.getFluidTicks().clearArea(new BoundingBox(minX - 1, minY - 1, minZ - 1,
                maxX + 1, maxY + 1, maxZ + 1));
    }

    /** The event's id, which is also how everything it spawned is tagged. */
    public String eventId() {
        return event.id;
    }

    /**
     * The box of every cell the event has written, grown by {@code pad}, or null before the first
     * write. What an event catches is looked for here.
     */
    public net.minecraft.world.phys.AABB written(double pad) {
        if (event.bx0 == Integer.MAX_VALUE) {
            return null;
        }
        return new net.minecraft.world.phys.AABB(event.bx0 - pad, event.by0 - pad, event.bz0 - pad,
                event.bx1 + 1 + pad, event.by1 + 1 + pad, event.bz1 + 1 + pad);
    }

    /**
     * Break a block the way a hailstone or a passing wave does: only if the hard guards allow it
     * (never a block entity, an unbreakable block, a fluid, an unloaded chunk or the world floor),
     * journaled like any write so undo puts it back. No drops, deliberately: the undo gives the glass
     * back, and a drop as well would be a second pane out of nowhere.
     */
    public boolean breakBlock(BlockPos pos) {
        long key = pos.asLong();
        if (event.journal.holds(key) || !level.hasChunkAt(pos)) {
            return false;
        }
        BlockState current = level.getBlockState(pos);
        EventGuard.Cell c = facts(level, pos, current);
        if (current.isAir() || !c.aboveFloor() || c.blockEntity() || c.unbreakable() || c.fluid()) {
            event.refused++;
            return false;
        }
        if (!budget.tryTake(event.journal.size(), true)) {
            event.refused++;
            return false;
        }
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        event.journal.record(key, current, air);
        level.setBlock(pos, air, FLAGS);
        event.reach(pos.getX(), pos.getY(), pos.getZ());
        grow(pos);
        return true;
    }

    /** Test seam: how many blocks this writer changed. */
    int written() {
        return written;
    }
}
