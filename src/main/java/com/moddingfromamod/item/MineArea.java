package com.moddingfromamod.item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>A tool that breaks more than one block </b>: a hammer that takes a 3×3 face, a pickaxe
 * that takes the whole vein, a shovel that digs a hole.
 *
 * <p><b>Every extra block is broken the way he would break it by hand</b> —
 * {@code ServerPlayerGameMode.destroyBlock}, which fires the same break event (so a protected area
 * stays protected), drops what that tool drops (fortune and silk touch included), and wears the tool
 * one use per block, so it is never a duplication path or a free quarry. Only blocks the tool could
 * break and collect are taken; never a chest or anything holding things, never bedrock.
 *
 * <p>The extra blocks are QUEUED and broken {@link MineShape#PER_TICK} a tick: a whole vein in one
 * tick is a lag spike on a shared server (C16). The queue stops if he puts the tool away, walks off,
 * or it breaks.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class MineArea {

    /** E1's label: {@code item.behaviour:mine_area}. */
    public static final Set<String> PARTS = Set.of("mine_area");

    /** How close he must stay for the queue to keep going. */
    static final double REACH = 8.0;

    private static final Map<UUID, Deque<BlockPos>> QUEUE = new ConcurrentHashMap<>();
    /** True while WE are breaking, so our own break events do not start another swing. */
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);

    private MineArea() {
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (BUSY.get() || event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer p)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        String shape = shapeOn(p.getMainHandItem());
        if (shape == null) {
            return;
        }
        BlockPos at = event.getPos();
        BlockState broken = event.getState();
        List<BlockPos> more = plan(level, p, at, broken, shape);
        if (!more.isEmpty()) {
            QUEUE.put(p.getUUID(), new ArrayDeque<>(more));
        }
    }

    /** The extra blocks one swing breaks, nearest first, already filtered to what may go. */
    public static List<BlockPos> plan(ServerLevel level, ServerPlayer p, BlockPos at, BlockState broken,
                                      String shape) {
        ItemStack tool = p.getMainHandItem();
        List<BlockPos> out = new ArrayList<>();
        if ("vein".equals(shape)) {
            Block kind = broken.getBlock();
            Deque<BlockPos> open = new ArrayDeque<>(List.of(at));
            Set<BlockPos> seen = new HashSet<>(List.of(at));
            while (!open.isEmpty() && out.size() < MineShape.MAX_EXTRA) {
                BlockPos c = open.poll();
                for (BlockPos n : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
                    BlockPos q = n.immutable();
                    if (seen.add(q) && q.distManhattan(at) <= MineShape.VEIN_REACH * 2
                            && Math.abs(q.getX() - at.getX()) <= MineShape.VEIN_REACH
                            && Math.abs(q.getY() - at.getY()) <= MineShape.VEIN_REACH
                            && Math.abs(q.getZ() - at.getZ()) <= MineShape.VEIN_REACH
                            && level.getBlockState(q).is(kind) && mayBreak(level, q, tool)) {
                        out.add(q);
                        open.add(q);
                        if (out.size() >= MineShape.MAX_EXTRA) {
                            break;
                        }
                    }
                }
            }
            return out;
        }
        var look = p.getLookAngle();
        List<int[]> offsets = "hole".equals(shape) ? MineShape.hole()
                : MineShape.square("5x5".equals(shape) ? 5 : 3, MineShape.axis(look.x, look.y, look.z));
        for (int[] o : offsets) {
            BlockPos q = at.offset(o[0], o[1], o[2]);
            if (mayBreak(level, q, tool)) {
                out.add(q);
            }
            if (out.size() >= MineShape.MAX_EXTRA) {
                break;
            }
        }
        return out;
    }

    /** A block he could break by hand with this tool and keep what drops; never one that holds things. */
    static boolean mayBreak(ServerLevel level, BlockPos q, ItemStack tool) {
        BlockState s = level.getBlockState(q);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) {
            return false;
        }
        float hardness = s.getDestroySpeed(level, q);
        if (hardness < 0) {
            return false;   // bedrock, barriers, portals
        }
        return !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) {
            return;
        }
        for (var it = QUEUE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null || !step(p, e.getValue())) {
                it.remove();
            }
        }
    }

    /** Break up to {@link MineShape#PER_TICK} queued blocks; false once the swing is over. */
    public static boolean step(ServerPlayer p, Deque<BlockPos> queue) {
        if (shapeOn(p.getMainHandItem()) == null) {
            return false;   // he put the tool away, or it broke
        }
        for (int i = 0; i < MineShape.PER_TICK && !queue.isEmpty(); i++) {
            BlockPos q = queue.poll();
            if (q.distToCenterSqr(p.position()) > REACH * REACH * 4
                    || !mayBreak(p.level(), q, p.getMainHandItem())) {
                continue;
            }
            BUSY.set(true);
            try {
                p.gameMode.destroyBlock(q);
            } finally {
                BUSY.set(false);
            }
            if (shapeOn(p.getMainHandItem()) == null) {
                return false;
            }
        }
        return !queue.isEmpty();
    }

    /** Test seam: run a whole swing now, as the ticks would. */
    public static void finish(ServerPlayer p) {
        Deque<BlockPos> q = QUEUE.remove(p.getUUID());
        while (q != null && step(p, q)) {
            // keep going
        }
    }

    /** The mine_area word on this stack, or null. */
    public static String shapeOn(ItemStack stack) {
        CustomData data = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String w = data.copyTag().getStringOr(com.moddingfromamod.datapack.ItemProperties.MINE_AREA_DATA, "");
        return MineShape.WORDS.contains(w) ? w : null;
    }
}
