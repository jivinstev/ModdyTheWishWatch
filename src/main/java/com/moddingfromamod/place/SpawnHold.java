package com.moddingfromamod.place;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>A short chunk ticket for an action that fell back to the world spawn</b> — the Minecraft half of
 * {@link SpawnHoldPlan}, which decides the width, the wait and when to let go. This class only puts
 * the ticket on and takes it off.
 *
 * <pre>
 *   try (var hold = SpawnHold.open(level, spawn, SpawnHoldPlan.Followup.AGENT)) {
 *       ... summon, then look for what was summoned ...
 *   }
 * </pre>
 *
 * <p>Only the spawn-FALLBACK path takes one: an action anchored on a child is in a chunk the child
 * keeps loaded, and is unchanged.
 *
 * <p><b>The ticket is vanilla's {@code ender_pearl}</b>: it loads and simulates, keeps the dimension
 * ticking, is never saved with the world, and times out by itself 40 ticks after it was last put on.
 * That last property is the safety net: the hold refreshes it every tick while it lasts, and if
 * nothing refreshed it (a crash in a tick handler, a server that stopped mid-hold) it is gone two
 * seconds later. Nothing here can leave the spawn loaded for good.
 *
 * <p><b>The wait.</b> A ticket is only processed on the chunk manager's next pass, and an entity added
 * before its chunk is promoted lands in a hidden section that no selector reaches (measured: see
 * {@code WishExecutor}'s summon note). So {@link #open} runs the chunk manager's own queue — what
 * {@code getChunk} does — until the middle of the hold is entity-ticking, at most
 * {@link SpawnHoldPlan#READY_WAIT_MS}.
 */
public final class SpawnHold implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mfam-spawn-hold");

    private static final SpawnHoldPlan.Book BOOK = new SpawnHoldPlan.Book();

    /** The vanilla ticket: load + simulate + keep the dimension active, not persisted, 40-tick timeout. */
    private static final TicketType TICKET = TicketType.ENDER_PEARL;

    private static final SpawnHold NONE = new SpawnHold(0L, SpawnHoldPlan.Followup.NONE);

    private final long token;
    private final SpawnHoldPlan.Followup followup;

    private SpawnHold(long token, SpawnHoldPlan.Followup followup) {
        this.token = token;
        this.followup = followup;
    }

    /**
     * Hold the spot round {@code at} for the action about to run there, and wait until a creature
     * summoned there can be found. Server thread only; anywhere else it holds nothing and says so.
     */
    public static SpawnHold open(ServerLevel level, BlockPos at, SpawnHoldPlan.Followup followup) {
        if (level == null || at == null) {
            return NONE;
        }
        if (!level.getServer().isSameThread()) {
            LOG.warn("spawn hold asked for off the server thread at {}; nothing held", at);
            return NONE;
        }
        SpawnHoldPlan.Key key = keyOf(level, at);
        long token = BOOK.open(key, System.currentTimeMillis());
        put(level, key);
        if (!waitUntilReady(level, key)) {
            LOG.info("spawn hold at {} was not entity-ticking after {} ms; the action runs anyway",
                    at, SpawnHoldPlan.READY_WAIT_MS);
        }
        return new SpawnHold(token, followup);
    }

    /**
     * Run {@code action}, holding the spot round {@code at} for it only when {@code fallback} — when
     * the action really did fall back to the spawn. Otherwise it runs exactly as it would have.
     */
    public static <T> T during(boolean fallback, ServerLevel level, BlockPos at,
                               SpawnHoldPlan.Followup followup, java.util.function.Supplier<T> action) {
        if (!fallback) {
            return action.get();
        }
        try (SpawnHold ignored = open(level, at, followup)) {
            return action.get();
        }
    }

    /** The action ended: the hold's tail starts (and for {@code NONE}, it is let go next tick). */
    @Override
    public void close() {
        if (token != 0L) {
            BOOK.close(token, System.currentTimeMillis(), followup);
        }
    }

    /** Is a hold's ticket on the chunk at {@code at} now? For tests. */
    public static boolean isHeld(ServerLevel level, BlockPos at) {
        return BOOK.ticketed().contains(keyOf(level, at));
    }

    private static SpawnHoldPlan.Key keyOf(ServerLevel level, BlockPos at) {
        return new SpawnHoldPlan.Key(level.dimension().identifier().toString(),
                SpawnHoldPlan.chunkOf(at.getX()), SpawnHoldPlan.chunkOf(at.getZ()));
    }

    private static void put(ServerLevel level, SpawnHoldPlan.Key key) {
        level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(key.cx(), key.cz()),
                SpawnHoldPlan.radiusChunks());
    }

    private static void takeOff(ServerLevel level, SpawnHoldPlan.Key key) {
        level.getChunkSource().removeTicketWithRadius(TICKET, new ChunkPos(key.cx(), key.cz()),
                SpawnHoldPlan.radiusChunks());
    }

    /** Run the chunk manager's queue until the ready ring is entity-ticking, within the plan's wait. */
    private static boolean waitUntilReady(ServerLevel level, SpawnHoldPlan.Key key) {
        long deadline = System.currentTimeMillis() + SpawnHoldPlan.READY_WAIT_MS;
        while (!ready(level, key)) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            if (!level.getChunkSource().pollTask()) {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            }
        }
        return true;
    }

    private static boolean ready(ServerLevel level, SpawnHoldPlan.Key key) {
        for (int[] c : SpawnHoldPlan.chunksAround(key.cx(), key.cz(), SpawnHoldPlan.readyRing())) {
            if (!level.isPositionEntityTicking(new BlockPos(c[0] << 4, level.getMinY(), c[1] << 4))) {
                return false;
            }
        }
        return true;
    }

    /** Refresh every live hold's ticket; take off the ones whose last hold has ended. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SpawnHoldPlan.Step step = BOOK.step(System.currentTimeMillis());
        if (step.keep().isEmpty() && step.release().isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (SpawnHoldPlan.Key k : step.keep()) {
            ServerLevel level = levelOf(server, k);
            if (level != null) {
                put(level, k);
            }
        }
        for (SpawnHoldPlan.Key k : step.release()) {
            ServerLevel level = levelOf(server, k);
            if (level != null) {
                takeOff(level, k);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BOOK.clear();
    }

    private static ServerLevel levelOf(MinecraftServer server, SpawnHoldPlan.Key k) {
        Identifier id = Identifier.tryParse(k.dimension());
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
