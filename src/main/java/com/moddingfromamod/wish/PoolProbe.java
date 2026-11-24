package com.moddingfromamod.wish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.PhotographTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Two concurrent wishes, two REAL camera clients, two framed orbits</b> — the real-client arm.
 *
 * <p>{@code CameraPoolGameTest} proves the pool on MOCK players: each wish is handed its own body.
 * That is the lease, and a lease is not a photograph. This drives the shipped capture path end to
 * end on two real camera clients: two wishes are bound (a {@link Marshal} run each, exactly what
 * {@code await_wish} does for a real worker), two creatures are sculpted forty blocks apart, and
 * both wishes call {@link PhotographTool#capture} <b>at the same instant</b> — the same call the
 * agent's {@code photograph} tool makes. No model is involved, so the arm costs nothing.
 *
 * <p>Each row of {@code pool.tsv} records which body the wish was given and whether the result was
 * a framed four-angle orbit ({@code Orbited '…'}) or the unframed fallback. The script
 * ({@code tools/pool-proof.sh}) then cross-checks the verdict against the two clients' OWN logs:
 * every shutter for one wish must be in one client's log and none in the other's.
 *
 * <p>Inert unless {@code -Dmoddingfromamod.poolprobe=<dir>} is set, and registered on the bus only
 * then. It spawns creatures and teleports cameras, which is not a thing to leave one unset property
 * away from a real game.
 */
public final class PoolProbe {

    private PoolProbe() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-pool-probe");
    private static final String PROPERTY = ModdingFromAModMod.MODID + ".poolprobe";

    /** How many real cameras must be connected before the orbits fire. The control run sets 1. */
    private static final String CAMS_PROPERTY = ModdingFromAModMod.MODID + ".poolprobecams";

    /** Far enough apart that one camera's frame of one creature cannot contain the other. */
    private static final double SEPARATION = 40.0;

    public static boolean active() {
        return !System.getProperty(PROPERTY, "").isBlank();
    }

    private static int camsWanted() {
        try {
            return Math.max(1, Integer.parseInt(System.getProperty(CAMS_PROPERTY, "2").trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private static int settled;
    private static volatile boolean started;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (started) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (cameras(server).size() < camsWanted()) {
            settled = 0;
            return;
        }
        // Joined is not drawn: a client that has just connected is still streaming chunks, and a
        // photograph then is a grey void that says nothing.
        if (++settled < 20 * 20) {
            return;
        }
        started = true;
        Thread t = new Thread(PoolProbe::run, "mfam-pool-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            sequence(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
        } catch (Throwable e) {
            LOG.error("pool probe failed", e);
            write("pool.tsv", "FAILED\t" + e + "\n");
            write("done", "FAILED");
        }
    }

    /** One wish's view of the run. */
    private record Row(String label, String species, UUID wish, String worker) {
    }

    private static void sequence(MinecraftServer server) throws Exception {
        // ⚠ A FRESH SPECIES PER RUN. The world is reused between runs (lite-server keeps run/), so
        // a fixed id finds the LAST run's creature too — measured: wish A's orbit aimed at (1, 21),
        // a carrier left over from an earlier run, 25 blocks from the one staged at (-19.5, 0.5),
        // and every frame was of the wrong creature. A real wish's species id is unique by
        // construction; this one is made so.
        String run = Long.toHexString(System.currentTimeMillis() & 0xffffffL);
        List<Row> rows = List.of(
                new Row("pool_a", "poola" + run, UUID.randomUUID(), "pool-a"),
                new Row("pool_b", "poolb" + run, UUID.randomUUID(), "pool-b"));

        // ── stage: two creatures SEPARATION apart, their chunks forced, two bound wishes ────
        onServer(server, () -> {
            ServerLevel level = server.overworld();
            BlockPos spawn = level.getRespawnData().pos();
            for (int i = 0; i < rows.size(); i++) {
                double x = spawn.getX() + 0.5 + (i == 0 ? -SEPARATION / 2 : SEPARATION / 2);
                double z = spawn.getZ() + 0.5;
                ChunkPos c = new ChunkPos(BlockPos.containing(x, 0, z));
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        level.setChunkForced(c.x + dx, c.z + dz, true);
                    }
                }
                double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int) Math.floor(x), (int) Math.floor(z));
                var rig = com.moddingfromamod.vanilla.DisplayRig.spawn(level, new Vec3(x, y, z), 0f,
                        sculpt(), 1.0, 1.4, 2.4, rows.get(i).species());
                // 🔴 PIN THE CARRIER. A bare DisplayRig is a carrier with AI and parts that stay
                // where they were put; the orbit aims at the CARRIER (that is what the species tag
                // is on), so a carrier that wanders leaves the camera framing empty grass while the
                // tool reports "Orbited". Measured on this probe's first run: wish B's aim walked
                // from x=22 to x=24 against a creature spawned at 20.5, and three of its four
                // frames were grass. Handing the rig to VanillaRigAnimator with no clips did NOT
                // fix it (the aim still walked 6 blocks and every frame was empty; not chased
                // here). So the carrier stands still — VanillaClipsClientTest's own pattern — and
                // this probe asks only which BODY shoots which wish, which a still subject answers.
                if (rig.base() instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setNoAi(true);
                }
                rig.base().setInvulnerable(true);
                rig.base().setSilent(true);
                LOG.info("POOL: staged {} at {} {} {}", rows.get(i).species(), r(x), r(y), r(z));
            }
            // A wish is a Marshal run bound to a worker — what await_wish does for a real agent,
            // and the only thing that gives each capture its own OWNER in the camera queue. The
            // submitter is a made-up child id: nothing on the photograph path reads it.
            UUID child = UUID.nameUUIDFromBytes("pool-probe-child".getBytes(StandardCharsets.UTF_8));
            for (Row row : rows) {
                Marshal.global().submit(row.wish(), child, "pool probe " + row.label());
                Marshal.global().assign(row.worker(), row.wish());
            }
        });
        Thread.sleep(3000);

        // ── fire both orbits at the same instant ──────────────────────────────────────────────
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(rows.size());
        String[] body = new String[rows.size()];
        String[] text = new String[rows.size()];
        long[] ms = new long[rows.size()];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            final int k = i;
            Row row = rows.get(i);
            Thread t = new Thread(() -> {
                AgentContext.bind(row.worker());
                try {
                    go.await();
                    long t0 = System.currentTimeMillis();
                    ToolResult res = PhotographTool.capture(row.label(), row.species(), "");
                    ms[k] = System.currentTimeMillis() - t0;
                    text[k] = res == null ? "(null)" : res.text();
                } catch (Throwable e) {
                    text[k] = "THREW " + e;
                } finally {
                    AgentContext.unbind();
                    done.countDown();
                }
            }, "mfam-pool-probe-" + row.label());
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        // Which body each wish HELD, sampled while the orbits run. Sampled rather than asked
        // afterwards, because the capture watchdog releases a lease a few seconds after the last
        // shot and "who holds it now" would answer nobody.
        java.util.Map<UUID, String> heldBy = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> clash = java.util.concurrent.ConcurrentHashMap.newKeySet();
        go.countDown();
        while (!done.await(250, TimeUnit.MILLISECONDS)) {
            onServer(server, () -> {
                for (ServerPlayer cam : cameras(server)) {
                    UUID holder = WishCapture.cameraHeldBy(cam.getUUID());
                    if (holder == null) {
                        continue;
                    }
                    String name = cam.getGameProfile().name();
                    String prev = heldBy.putIfAbsent(holder, name);
                    if (prev != null && !prev.equals(name)) {
                        clash.add(holder + " held " + prev + " then " + name);
                    }
                }
            });
        }
        for (int i = 0; i < rows.size(); i++) {
            body[i] = heldBy.getOrDefault(rows.get(i).wish(), "?");
        }
        if (!clash.isEmpty()) {
            LOG.warn("POOL: a wish changed bodies mid-orbit: {}", clash);
        }

        // ── verdict ──────────────────────────────────────────────────────────────────────────
        StringBuilder tsv = new StringBuilder("label\tframed\tbody\tms\tresult\n");
        for (int i = 0; i < rows.size(); i++) {
            String t = text[i] == null ? "(unfinished)" : text[i];
            boolean framed = t.contains("Orbited '");
            tsv.append(rows.get(i).label()).append('\t').append(framed ? "FRAMED" : "UNFRAMED")
                    .append('\t').append(body[i]).append('\t').append(ms[i]).append('\t')
                    .append(t.replace('\n', ' ').replace('\t', ' ')).append('\n');
            LOG.info("POOL: {} -> {} on {} in {} ms", rows.get(i).label(),
                    framed ? "FRAMED" : "UNFRAMED", body[i], ms[i]);
        }
        if (!clash.isEmpty()) {
            tsv.append("# CLASH\t").append(String.join("; ", clash)).append('\n');
        }
        write("pool.tsv", tsv.toString());
        onServer(server, () -> {
            for (Row row : rows) {
                Marshal.global().finish(row.wish());
            }
        });
        write("done", "DONE");
        LOG.info("POOL: done");
    }

    private static List<ServerPlayer> cameras(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                out.add(p);
            }
        }
        return out;
    }

    private static void onServer(MinecraftServer server, Runnable body) throws Exception {
        java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                body.run();
                f.complete(null);
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        f.get(30, TimeUnit.SECONDS);
    }

    private static List<CreatureSculpt.Part> sculpt() {
        return List.of(
                new CreatureSculpt.Part("body", null, new float[] { 0, 0, 0 },
                        new float[] { -5, 0, -4 }, new float[] { 5, 12, 4 }, 0x2f8f3f),
                new CreatureSculpt.Part("head", null, new float[] { 0, 12, 0 },
                        new float[] { -4, 12, -4 }, new float[] { 4, 19, 4 }, 0xe0c020),
                new CreatureSculpt.Part("tail", null, new float[] { 0, 3, 4 },
                        new float[] { -2, 3, 4 }, new float[] { 2, 7, 10 }, 0xc03020));
    }

    private static void write(String name, String body) {
        try {
            Path dir = Path.of(System.getProperty(PROPERTY));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("could not write {}: {}", name, e.toString());
        }
    }

    private static String r(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
