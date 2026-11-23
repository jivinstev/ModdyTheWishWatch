package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Can a player actually get up this ladder? </b> Asked of the WORLD, with the game's own
 * movement code.
 *
 * <p>{@link BuildSupport} answers "does every ladder cell have a wall behind it" from the plan.
 * That is necessary and not sufficient: a column can be backed all the way up and still lead into
 * a ceiling, end a block short of the floor it was meant for, or come out under a roof with no
 * headroom to step off. So this puts a player at the foot of each ladder column, faces it at the
 * wall, and drives {@link Player#travel} — the same code that climbs a ladder for a real player —
 * tick by tick, pushing forward. It passes only if the player ends STANDING on a floor above the
 * top of the ladder: up the ladder and off it, which is what climbing means to a child.
 *
 * <p>The player is a GameTest mock: it never joins the level, so it moves against the level's
 * blocks without being seen, saved or ticked by the server. Every step is synchronous.
 */
public final class ClimbProbe {

    /** Steps of forward push before giving up. A 30-tall ladder at 0.2/tick is ~150. */
    public static final int MAX_STEPS = 600;

    /**
     * One vertical run of ladder blocks — its foot, its top cell's y, and the way it faces — or of
     * scaffolding, whose {@code facing} is null: a player climbs scaffolding by holding jump.
     */
    public record Column(BlockPos foot, int topY, Direction facing) {
        public int height() {
            return topY - foot.getY() + 1;
        }
    }

    /** What happened when a player tried it. */
    public record Climb(Column column, boolean survives, double maxFeetY, double endFeetY,
                        boolean endOnGround) {
        /** Up the ladder and standing on something above its top cell. */
        public boolean climbed() {
            return survives && endOnGround && endFeetY >= column.topY() + 1 - 1e-3;
        }

        public String describe() {
            return (column.facing() == null ? "scaffolding at " : "ladder at ")
                    + column.foot().toShortString() + " (" + column.height() + " tall"
                    + (column.facing() == null ? "" : ", facing " + column.facing().getName()) + "): " + (climbed() ? "CLIMBED" : "NOT CLIMBABLE")
                    // A ladder placed with no wall is still a ladder until the next block update
                    // near it pops it off — measured: the old dome's climbed in the instant it was
                    // built, because a player pushing forward collides with the ladder itself.
                    + (survives ? "" : ", a cell has no wall behind it and drops off at the first "
                            + "block update")
                    + String.format(", reached %.2f of %d, ended at %.2f%s", maxFeetY, column.topY() + 1,
                            endFeetY, endOnGround ? " standing" : " in the air");
        }
    }

    private ClimbProbe() {
    }

    /** Every ladder column among these world positions, foot first. */
    public static List<Column> columns(ServerLevel level, Iterable<BlockPos> positions) {
        Map<Long, BlockState> ladders = new HashMap<>();
        for (BlockPos p : positions) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof LadderBlock) {
                ladders.put(p.asLong(), s);
            }
        }
        List<Column> out = new ArrayList<>();
        for (Map.Entry<Long, BlockState> e : ladders.entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            Direction f = e.getValue().getValue(LadderBlock.FACING);
            BlockState below = ladders.get(p.below().asLong());
            if (below != null && below.getValue(LadderBlock.FACING) == f) {
                continue;                     // not a foot
            }
            int top = p.getY();
            while (true) {
                BlockState up = ladders.get(p.atY(top + 1).asLong());
                if (up == null || up.getValue(LadderBlock.FACING) != f) {
                    break;
                }
                top++;
            }
            out.add(new Column(p, top, f));
        }
        java.util.Set<Long> scaffold = new java.util.HashSet<>();
        for (BlockPos p : positions) {
            if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock) {
                scaffold.add(p.asLong());
            }
        }
        for (long k : scaffold) {
            BlockPos p = BlockPos.of(k);
            if (scaffold.contains(p.below().asLong())) {
                continue;
            }
            int top = p.getY();
            while (scaffold.contains(p.atY(top + 1).asLong())) {
                top++;
            }
            if (top > p.getY()) {
                out.add(new Column(p, top, null));   // a single block of scaffolding is a step, not a climb
            }
        }
        out.sort((a, b) -> Long.compare(a.foot().asLong(), b.foot().asLong()));
        return out;
    }

    /** Put the player at the foot, facing the wall, and push forward until it stops rising. */
    public static Climb climb(ServerLevel level, Player player, Column c) {
        boolean survives = true;
        for (int y = c.foot().getY(); y <= c.topY(); y++) {
            BlockPos at = c.foot().atY(y);
            if (!level.getBlockState(at).canSurvive(level, at)) {
                survives = false;
            }
        }
        boolean scaffolding = c.facing() == null;
        player.setDeltaMovement(Vec3.ZERO);
        player.snapTo(c.foot().getX() + 0.5, c.foot().getY(), c.foot().getZ() + 0.5,
                scaffolding ? 0f : c.facing().getOpposite().toYRot(), 0f);
        player.setJumping(scaffolding);
        player.setOnGround(false);
        // A mock player never runs aiStep, which is where a real one copies its walking speed off
        // the attribute every tick; without it a player standing on the floor does not move at all.
        player.setSpeed((float) player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
        double max = player.getY();
        double lastRise = player.getY();
        int still = 0;
        for (int i = 0; i < MAX_STEPS && still < 60; i++) {
            // A real player's tick runs baseTick before travel, and baseTick is the only thing that
            // CLEARS "in water". A mock never ticks: on 1.21.1 its first step in water (move ->
            // checkFallDamage sets the flag, nothing unsets it) left it swimming the whole way up and
            // off the top, and on 1.21.4 move skips that call for a "local" player, so it was never
            // wet at all. Either way the probe was climbing a different game from the child's.
            player.baseTick();
            player.travel(scaffolding ? Vec3.ZERO : new Vec3(0, 0, 1));
            max = Math.max(max, player.getY());
            if (player.onGround() && player.getY() >= c.topY() + 1 - 1e-3) {
                break;                        // off the top and standing: stop before it walks on
            }
            if (player.getY() > lastRise + 0.01) {
                lastRise = player.getY();
                still = 0;
            } else {
                still++;
            }
        }
        player.setJumping(false);
        return new Climb(c, survives, max, player.getY(), player.onGround());
    }
}
