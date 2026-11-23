package com.moddingfromamod.datapack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What every wish datapack costs, tick by tick — and the call to pause the one eating the server</b>
 *. The decision is {@link QuarantineRule}'s (pure); the pausing is {@link DatapackQuarantine}'s.
 *
 * <p>Three readings, all on the server thread:
 * <ul>
 *   <li><b>function time</b>: {@code NoiseMeter} already marks which datapack function is running (every tick
 *       and scheduled function passes {@code ServerFunctionManager.execute}); the OUTERMOST call's wall time is
 *       charged to its namespace, when that namespace is a wish pack;</li>
 *   <li><b>what it spawned</b>: an entity that joins a level while a wish pack's function runs is that pack's
 *       ({@link EntityJoinLevelEvent}); kept while alive, for {@link QuarantineRule#RECENT_SPAWN_MS};</li>
 *   <li><b>the tick</b>: {@link ServerTickEvent} Pre to Post, which covers everything the server does.</li>
 * </ul>
 * And one stop that needs no window: {@code ExecutionContextHardStopMixin} asks {@link #overBudget} on every
 * command a function runs, and a wish pack's outermost call past {@link QuarantineRule#HARD_STOP_MS} has its
 * command quota spent where it stands (vanilla's own way out of a chain that is too long).
 *
 * <p>Not counted: players' own commands, {@code run_command} and buttons (no function is running), and any
 * namespace that is not a wish pack ({@code DatapackStore}) — the world's own packs are not ours to pause.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PackCostMeter {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-quarantine");

    private static final QuarantineRule RULE = new QuarantineRule();

    /** The outermost running wish-pack function, and when it started; null between functions. */
    private static String outerPack;
    private static long outerStartNs;
    private static int depth;
    private static boolean stoppedThisCall;

    private static final Map<String, Double> FN_MS = new HashMap<>();
    private record Spawn(Entity entity, long atMs) {
    }
    private static final Map<String, Deque<Spawn>> SPAWNS = new HashMap<>();

    private static long tickStartNs;
    private static long lastCalmLogMs;
    private static volatile boolean quarantineOn = !"off".equals(System.getProperty("moddingfromamod.quarantine"));
    private static volatile QuarantineRule.Verdict lastVerdict = QuarantineRule.Verdict.calm("no ticks yet");
    private static final List<String> HARD_STOPPED = new ArrayList<>();
    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile double maxTickMs;
    private static volatile double lastTickMs;
    /** Test seam: a wish pack for the meter whether or not {@link DatapackStore} holds it. */
    private static final java.util.Set<String> FORCED = new java.util.HashSet<>();

    private PackCostMeter() {
    }

    // ── function time (called by NoiseMeter, which FunctionNoiseMixin drives) ───────────────────────

    static void enter(String namespace) {
        if (depth++ == 0) {
            outerPack = isWishPack(namespace) ? namespace : null;
            outerStartNs = System.nanoTime();
            stoppedThisCall = false;
        }
    }

    static void exit() {
        if (depth <= 0) {
            depth = 0;
            return;
        }
        if (--depth == 0) {
            if (outerPack != null) {
                FN_MS.merge(outerPack, (System.nanoTime() - outerStartNs) / 1e6, Double::sum);
            }
            outerPack = null;
        }
    }

    /** A new tick: whatever a throwing function left on the stack is not running any more. */
    static void tickStart() {
        depth = 0;
        outerPack = null;
    }

    /** The wish pack whose function is running right now, or null. */
    public static String runningPack() {
        return depth > 0 ? outerPack : null;
    }

    /**
     * Asked on every command a function runs ({@code ExecutionContextHardStopMixin}): has the running wish pack's
     * outermost call run past {@link QuarantineRule#HARD_STOP_MS}? True once per call — the caller then spends the
     * command quota, and the chain stops.
     */
    public static boolean overBudget() {
        if (outerPack == null || depth <= 0 || stoppedThisCall) {
            return false;
        }
        if ((System.nanoTime() - outerStartNs) / 1_000_000L < QuarantineRule.HARD_STOP_MS) {
            return false;
        }
        stoppedThisCall = true;
        synchronized (HARD_STOPPED) {
            HARD_STOPPED.add(outerPack);
        }
        LOG.warn("[quarantine] wish datapack {} ran one function for over {} ms in one tick — stopped where it stood",
                outerPack, QuarantineRule.HARD_STOP_MS);
        return true;
    }

    private static boolean isWishPack(String ns) {
        if (ns == null || ns.isEmpty() || "minecraft".equals(ns) || ModdingFromAModMod.MODID.equals(ns)) {
            return false;
        }
        return FORCED.contains(ns) || DatapackStore.exists(ns);
    }

    // ── what a pack spawned ──────────────────────────────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.loadedFromDisk() || event.getLevel().isClientSide()) {
            return;
        }
        String pack = runningPack();
        if (pack == null || event.getEntity() instanceof Player) {
            return;
        }
        SPAWNS.computeIfAbsent(pack, k -> new ArrayDeque<>())
                .addLast(new Spawn(event.getEntity(), System.currentTimeMillis()));
    }

    /** How many entities this pack spawned in the last minute are still alive (and prune the rest). */
    static int liveRecent(String pack, long nowMs) {
        Deque<Spawn> d = SPAWNS.get(pack);
        if (d == null) {
            return 0;
        }
        while (!d.isEmpty() && d.peekFirst().atMs() < nowMs - QuarantineRule.RECENT_SPAWN_MS) {
            d.removeFirst();
        }
        int n = 0;
        for (Iterator<Spawn> it = d.iterator(); it.hasNext(); ) {
            if (it.next().entity().isRemoved()) {
                it.remove();
            } else {
                n++;
            }
        }
        if (d.isEmpty()) {
            SPAWNS.remove(pack);
        }
        return n;
    }

    /** Remove everything this pack spawned in the last minute that is still alive. Server thread. */
    static int discardRecentSpawns(String pack) {
        Deque<Spawn> d = SPAWNS.remove(pack);
        if (d == null) {
            return 0;
        }
        int n = 0;
        long cutoff = System.currentTimeMillis() - QuarantineRule.RECENT_SPAWN_MS;
        for (Spawn s : d) {
            if (s.atMs() >= cutoff && !s.entity().isRemoved()) {
                s.entity().discard();
                n++;
            }
        }
        return n;
    }

    static void forget(String pack) {
        RULE.forget(pack);
        SPAWNS.remove(pack);
    }

    // ── the tick ─────────────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onTickStart(ServerTickEvent.Pre event) {
        tickStartNs = System.nanoTime();
    }

    @SubscribeEvent
    public static void onTickEnd(ServerTickEvent.Post event) {
        if (tickStartNs == 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        double ms = (System.nanoTime() - tickStartNs) / 1e6;
        Map<String, Integer> spawned = new HashMap<>();
        for (String pack : new ArrayList<>(SPAWNS.keySet())) {
            int n = liveRecent(pack, now);
            if (n > 0) {
                spawned.put(pack, n);
            }
        }
        // Counting every entity is O(n); only a slow tick needs it (a fast one blames nothing worth having).
        int entities = 0;
        if (ms > QuarantineRule.SLOW_TICK_MS && !spawned.isEmpty()) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e != null) {
                        entities++;
                    }
                }
            }
        }
        QuarantineRule.Tick tick = new QuarantineRule.Tick(now, ms, new HashMap<>(FN_MS), spawned, entities);
        SEEN.addAll(FN_MS.keySet());
        maxTickMs = Math.max(maxTickMs, ms);
        lastTickMs = ms;
        FN_MS.clear();
        List<String> stopped;
        synchronized (HARD_STOPPED) {
            stopped = new ArrayList<>(HARD_STOPPED);
            HARD_STOPPED.clear();
        }
        QuarantineRule.Verdict v = RULE.add(tick);
        lastVerdict = v;
        for (String pack : stopped) {
            if (RULE.hardStop(pack, now) && quarantineOn) {
                DatapackQuarantine.quarantine(server, pack, "one of its functions ran for over "
                        + QuarantineRule.HARD_STOP_MS + " ms in a single tick, " + QuarantineRule.HARD_STOPS
                        + " times inside a minute (a loop that never ends, or a function that calls itself)",
                        "hard-stop");
            }
        }
        if (ms > QuarantineRule.SLOW_TICK_MS) {
            String top = topBlamed(v);
            if (top != null) {
                DatapackQuarantine.noteSuspect(top, now);
            }
        }
        if (!v.breach()) {
            return;
        }
        if (v.pack() != null) {
            if (quarantineOn) {
                DatapackQuarantine.quarantine(server, v.pack(), v.why(), "meter");
            } else if (now - lastCalmLogMs > 10_000) {
                lastCalmLogMs = now;
                LOG.warn("[quarantine] OFF — would pause {}: {}", v.pack(), v.why());
            }
        } else if (now - lastCalmLogMs > 60_000) {
            lastCalmLogMs = now;
            LOG.warn("[quarantine] {} — nothing paused", v.why());
        }
    }

    private static String topBlamed(QuarantineRule.Verdict v) {
        if (v.pack() != null) {
            return v.pack();
        }
        String top = null;
        double best = 0;
        for (var e : v.blame().entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                top = e.getKey();
            }
        }
        return top;
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        reset();
        DatapackQuarantine.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        reset();
        DatapackQuarantine.forgetForTest();
    }

    private static void reset() {
        RULE.reset();
        SPAWNS.clear();
        FN_MS.clear();
        depth = 0;
        outerPack = null;
        tickStartNs = 0;
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────

    /** The latest verdict (a test's red control reads it with quarantine off). */
    public static QuarantineRule.Verdict lastVerdict() {
        return lastVerdict;
    }

    /** Tests: switch the pausing off (the meter still measures and judges) or back on. */
    public static void quarantineForTest(boolean on) {
        quarantineOn = on;
    }

    /** Tests: meter this namespace as a wish pack whether or not the store holds it. */
    public static void forceForTest(String ns, boolean on) {
        if (on) {
            FORCED.add(ns);
        } else {
            FORCED.remove(ns);
        }
    }

    /** Tests: start the window from nothing. */
    public static void resetForTest() {
        RULE.reset();
        SEEN.clear();
        maxTickMs = 0;
    }

    /** Tests: the wish packs whose functions the meter has timed since {@link #resetForTest}. */
    public static java.util.Set<String> seenForTest() {
        return java.util.Set.copyOf(SEEN);
    }

    /** Tests: the slowest tick since {@link #resetForTest}, and the latest, in ms. */
    public static double maxTickMsForTest() {
        return maxTickMs;
    }

    public static double lastTickMsForTest() {
        return lastTickMs;
    }

    /** Tests: how many entities this pack spawned in the last minute are alive. */
    public static int liveRecentForTest(String pack) {
        return liveRecent(pack, System.currentTimeMillis());
    }
}
