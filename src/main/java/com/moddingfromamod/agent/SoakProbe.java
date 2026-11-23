package com.moddingfromamod.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.SoakPlan;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The pool soak</b>: a real dedicated server, a real agent pool, no client, and hours of
 * a household's comings and goings compressed into {@link SoakPlan} cycles — join, two wishes a
 * minute apart, leave, sit empty long enough to be reaped, come back.
 *
 * <pre>
 *   ./tools/soak-pool.sh &lt;arm&gt;        # the driver; it sets the properties below
 *   -Dmoddingfromamod.soak=180            minutes; the probe is off without it
 *   -Dmoddingfromamod.soakcycle=60        minutes per cycle (≥ SoakPlan.minCycleMs, ~56)
 *   -Dmoddingfromamod.soakwishes=&lt;b64&gt;   newline-separated wishes, base64 (a default set otherwise)
 *   -Dmoddingfromamod.soaksig=&lt;dir&gt;      where soak.jsonl and summary.json go
 *   -Dmoddingfromamod.soakdeafboot=2      the Nth worker launched is given a wrong bearer token
 * </pre>
 *
 * <p><b>Why presence is simulated rather than real.</b> The reaper keys on the player
 * count; a FakePlayer is not in the player list, so without a substitute every cycle would look
 * like an empty world from the first second and the pool would be reaped mid-wish. The substitute
 * is the same seam {@code AgentReapGameTest} drives ({@link AgentSession#playerCount}), and a JOIN
 * calls {@link AgentSession#start} exactly as {@code WishAgent.onPlayerJoin} does.
 *
 * <p><b>Why a deaf worker is INJECTED.</b> an earlier issue's deafness is real but not reliable: 5 of 9 on one
 * day, 0 of 2 on another. A soak that merely hopes to meet one measures luck, so
 * {@code soakdeafboot} makes one worker deaf on purpose — its MCP config carries a wrong token, it
 * never reaches {@code await_wish}, and from the pool's side it is exactly a worker that booted and
 * never listened. The organic ones are still counted, by {@code tools/park-rate.py} afterwards.
 *
 * <p>Registered through {@link HeadlessWishProbe#active()} so the mod's entry point is untouched,
 * and for its reason: the probe halts the server, which is not something to leave one unset
 * property away from a child's game.
 */
public final class SoakProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final String TAG = "[soak]";

    private static final UUID PROBE_ID =
            UUID.nameUUIDFromBytes("moddingfromamod:soak".getBytes(StandardCharsets.UTF_8));

    /** A wish nobody has picked up or finished in this long is recorded as UNANSWERED. */
    private static final long WISH_TIMEOUT_MS = 20 * 60_000L;

    /** Cheap wishes that need no player standing next to them: builds anchored where aimed. */
    static final List<String> DEFAULT_WISHES = List.of(
            "build a little tower of red wool five blocks tall",
            "build a small stone bench with a lantern on it",
            "build a tiny wooden hut with a door",
            "build a pond with a lily pad in it");

    private SoakProbe() {
    }

    public static boolean active() {
        return minutes() > 0;
    }

    private static long minutes() {
        return Long.getLong(P + "soak", 0L);
    }

    private static List<String> wishes() {
        String raw = System.getProperty(P + "soakwishes", "");
        if (raw.isBlank()) {
            return DEFAULT_WISHES;
        }
        String text = new String(Base64.getDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
        List<String> out = text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return out.isEmpty() ? DEFAULT_WISHES : out;
    }

    /** One wish the soak made, and what became of it. */
    private static final class Tracked {
        final String text;
        final long submittedMs;
        UUID id;
        long pickedUpMs;
        long settledMs;
        String outcome;

        Tracked(String text, long submittedMs) {
            this.text = text;
            this.submittedMs = submittedMs;
        }
    }

    private static SoakPlan plan;
    private static long startedMs;
    private static int nextStep;
    private static boolean done;
    private static final List<Tracked> tracked = new ArrayList<>();
    private static int poolStarts;
    private static int poolStops;
    private static int maxRunning;
    private static int lastRunning = -1;

    /** Called every server tick by {@link HeadlessWishProbe} when the soak is armed. */
    static void tick(MinecraftServer server) {
        if (done) {
            return;
        }
        long now = System.currentTimeMillis();
        if (plan == null) {
            startedMs = now;
            plan = SoakPlan.of(minutes() * 60_000L,
                    Long.getLong(P + "soakcycle", 60L) * 60_000L, wishes());
            AgentSession.playerCount = () -> plan.presentAt(System.currentTimeMillis() - startedMs)
                    ? 1 : 0;
            System.setProperty("moddingfromamod.forceloadbuilds", "true");
            record("armed", obj("steps", plan.steps().size(), "minutes", minutes()));
            startSampler();
        }
        long at = now - startedMs;
        while (nextStep < plan.steps().size() && plan.steps().get(nextStep).atMs() <= at) {
            SoakPlan.Step step = plan.steps().get(nextStep++);
            switch (step.kind()) {
                case JOIN -> {
                    record("join", null);
                    // As WishAgent.onPlayerJoin does. Idempotent while the pool is up.
                    AgentSession.start(ClaudeCli.resolve());
                }
                case WISH -> submit(server, step.wish(), now);
                case LEAVE -> record("leave", null);
                case END -> {
                    finish(server);
                    return;
                }
            }
        }
        if (server.getTickCount() % 20 == 0) {
            watch(server, now);
        }
    }

    private static void submit(MinecraftServer server, String text, long now) {
        Tracked t = new Tracked(text, now);
        tracked.add(t);
        try {
            ServerLevel level = server.overworld();
            FakePlayer child = FakePlayerFactory.get(level, new GameProfile(PROBE_ID, "SoakWisher"));
            BlockPos spawn = level.getSharedSpawnPos();
            child.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            child.setServerLevel(level);
            WishBook book = WishBook.get(server);
            java.util.Set<UUID> before = book.recent(50).stream().map(Wish::id)
                    .collect(java.util.stream.Collectors.toSet());
            WishService.Outcome outcome;
            try (var origin = com.moddingfromamod.wish.WishOrigin.scope(
                    com.moddingfromamod.wish.WishOrigin.PROBE)) {   //
                outcome = WishService.submit(child, text, WishSource.COMMAND);
            }
            t.id = HeadlessWishProbe.ourNewWish(WishBook.get(server).recent(50), before, PROBE_ID);
            if (t.id == null) {
                t.outcome = "NOT_RECORDED(" + outcome + ")";
                t.settledMs = now;
            }
            record("wish", obj("text", text, "outcome", String.valueOf(outcome)));
        } catch (RuntimeException e) {
            t.outcome = "SUBMIT_THREW";
            t.settledMs = now;
            record("wish-error", obj("text", text, "error", e.toString()));
        }
    }

    private static void watch(MinecraftServer server, long now) {
        for (Tracked t : tracked) {
            if (t.outcome != null || t.id == null) {
                continue;
            }
            var found = WishBook.get(server).byId(t.id);
            if (found.isEmpty()) {
                t.outcome = "VANISHED";
                t.settledMs = now;
                continue;
            }
            WishStatus s = found.get().status();
            if (s == WishStatus.RUNNING && t.pickedUpMs == 0) {
                t.pickedUpMs = now;
                record("picked-up", obj("text", t.text, "afterSec", (now - t.submittedMs) / 1000));
            }
            if (s != WishStatus.PENDING && s != WishStatus.RUNNING) {
                t.outcome = s.name();
                t.settledMs = now;
                record("settled", obj("text", t.text, "status", s.name(),
                        "afterSec", (now - t.submittedMs) / 1000));
            } else if (now - t.submittedMs > WISH_TIMEOUT_MS) {
                t.outcome = t.pickedUpMs == 0 ? "UNANSWERED" : "STUCK_" + s.name();
                t.settledMs = now;
                record("timed-out", obj("text", t.text, "status", s.name()));
            }
        }
    }

    /**
     * The pool's own state every 30 s, off the server thread: {@link AgentSession#runningWorkers}
     * takes the pool's monitor, which it holds across process launches.
     */
    private static void startSampler() {
        Thread t = new Thread(() -> {
            while (!done) {
                try {
                    int running = AgentSession.runningWorkers();
                    if (lastRunning <= 0 && running > 0) {
                        poolStarts++;
                    } else if (lastRunning > 0 && running == 0) {
                        poolStops++;
                    }
                    lastRunning = running;
                    maxRunning = Math.max(maxRunning, running);
                    record("sample", obj(
                            "present", AgentSession.playerCount.getAsInt(),
                            "running", running,
                            "sessions", AgentSession.workerCount(),
                            "parked", WishQueue.global().waitingWorkers(),
                            "busy", com.moddingfromamod.marshal.Marshal.global().working().size()));
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOGGER.warn("{} sampler: {}", TAG, e.toString());
                }
            }
        }, "soak-sampler");
        t.setDaemon(true);
        t.start();
    }

    private static void finish(MinecraftServer server) {
        long now = System.currentTimeMillis();
        watch(server, now);
        done = true;
        JsonObject s = new JsonObject();
        s.addProperty("minutes", (now - startedMs) / 60_000);
        s.addProperty("deafBoot", Integer.getInteger(P + "soakdeafboot", 0));
        s.addProperty("poolStarts", poolStarts);
        s.addProperty("poolStops", poolStops);
        s.addProperty("maxRunning", maxRunning);
        s.addProperty("deafReplaced", AgentSession.deafReplaced());
        int answered = 0;
        JsonArray ws = new JsonArray();
        for (Tracked t : tracked) {
            JsonObject w = new JsonObject();
            w.addProperty("text", t.text);
            w.addProperty("atMin", (t.submittedMs - startedMs) / 60_000);
            w.addProperty("pickupSec", t.pickedUpMs == 0 ? -1 : (t.pickedUpMs - t.submittedMs) / 1000);
            w.addProperty("settleSec", t.settledMs == 0 ? -1 : (t.settledMs - t.submittedMs) / 1000);
            w.addProperty("outcome", t.outcome == null ? "OPEN_AT_END" : t.outcome);
            if (t.pickedUpMs != 0) {
                answered++;
            }
            ws.add(w);
        }
        s.addProperty("wishes", tracked.size());
        s.addProperty("answered", answered);
        s.add("each", ws);
        record("end", s);
        String sig = System.getProperty(P + "soaksig", "");
        if (!sig.isEmpty()) {
            try {
                Files.writeString(Path.of(sig).resolve("summary.json"), s + System.lineSeparator());
            } catch (Exception e) {
                LOGGER.error("{} could not write summary: {}", TAG, e.toString());
            }
        }
        LOGGER.info("{} RESULT answered {}/{} — {}", TAG, answered, tracked.size(), s);
        AgentSession.stop();
        server.halt(false);
    }

    private static synchronized void record(String event, JsonObject data) {
        JsonObject line = new JsonObject();
        line.addProperty("t", System.currentTimeMillis());
        line.addProperty("min", startedMs == 0 ? 0 : (System.currentTimeMillis() - startedMs) / 60_000.0);
        line.addProperty("event", event);
        if (data != null) {
            line.add("data", data);
        }
        LOGGER.info("{} {}", TAG, line);
        String sig = System.getProperty(P + "soaksig", "");
        if (sig.isEmpty()) {
            return;
        }
        try {
            Path dir = Path.of(sig);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("soak.jsonl"), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("{} could not record: {}", TAG, e.toString());
        }
    }

    private static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) {
                o.addProperty(String.valueOf(kv[i]), n);
            } else {
                o.addProperty(String.valueOf(kv[i]), String.valueOf(v));
            }
        }
        return o;
    }
}
