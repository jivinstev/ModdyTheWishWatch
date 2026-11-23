package com.moddingfromamod.vanilla;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What a herd of rigs costs a real server</b> — an earlier issue's performance arm, and the harness
 * {@code docs/ATOM_RIG_COST.md} §7 names for re-measuring it.
 *
 * <p>Off unless {@code -Ptestprops="rigperf=<seconds>,rigperfout=<file>"}; then, on a DEDICATED
 * server (real 20 TPS — a GameTest server ticks as fast as it can, so its milliseconds mean
 * nothing), it builds a fenced pen and a walled pool in the sky, spawns ten rigs of different
 * shapes through {@link DisplayRig#spawn} and hands them to {@link VanillaRigAnimator#drive}
 * exactly as the bridge does, lets their carriers' own AI walk and swim them, and records:
 *
 * <ul>
 *   <li><b>tick time</b> — {@code ServerTickEvent.Pre} (first) to {@code Post} (last), every tick
 *       of the window: mean and p95 MSPT;</li>
 *   <li><b>traffic per tracking player</b> — at the end of each tick, the rig entities whose
 *       synced data is dirty (one {@code SetEntityData} packet each to every player tracking it,
 *       sent next tick) and whose position moved (one move packet each). Counted on the server, so
 *       it needs no client and is exactly what vanilla's tracker will send;</li>
 *   <li><b>spawn cost</b> — {@link DisplayRig#spawn} wall time over repeated spawns, and
 *       {@link DisplayRig#candidates} alone, which on the pre-#774 path created twenty-one probe
 *       entities per call.</li>
 * </ul>
 *
 * <p>Writes one JSON object to {@code rigperfout} and halts the server.
 *
 * <p>⚠ <b>Two things every run needs, each of which once produced a plausible wrong table</b>
 * ({@code docs/ATOM_RIG_COST.md} §7): {@code rm -rf run/server/world} first, because these rigs are
 * persistent and a reused world carries every earlier run's herd into the next (tick time
 * "drifted" 7 → 13 ms over five runs); and {@code cameraName=off}, because the camera client a
 * dedicated server starts outlives the server and piles up across runs.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigPerfProbe {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/rigperf");

    private static final int SECONDS = Integer.getInteger("moddingfromamod.rigperf", 0);
    private static final String OUT = System.getProperty("moddingfromamod.rigperfout",
            "/tmp/rigperf.json");
    private static final int WARMUP_TICKS = 200;
    /** Copies of the whole scene, each in its own pens 24 blocks further south. */
    private static final int COPIES = Integer.getInteger("moddingfromamod.rigperfcopies", 1);
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    /**
     * an earlier issue's cost arm: {@code rigperflight=on} gives the herd LIGHT that changes as it moves — the
     * west half of the land pen roofed and lit by glowstone in the roof, torches across the open
     * half, sea lanterns on the pool floor — and spins the clock 50× (two and a half days in the
     * window), so day → night is in the measurement too. Pair it with {@code riglight=off|on}.
     */
    private static final boolean LIGHT = Boolean.getBoolean("moddingfromamod.rigperflight");

    private static final List<DisplayRig> RIGS = new ArrayList<>();
    private static final Map<Entity, Vec3> LAST = new HashMap<>();
    private static final List<Double> TICK_MS = new ArrayList<>();
    private static long dirty;
    private static long moved;
    private static long carrierMovingTicks;
    private static long proxyMoved;
    private static long proxyDirty;
    private static long lightWrites0;
    private static long lightOwn0;
    private static int tick;
    private static long pre;
    private static boolean done;

    private RigPerfProbe() {
    }

    /** The one line that differs between the arms: how a sculpt becomes rig space. */
    static List<CreatureSculpt.Part> rigSpace(List<CreatureSculpt.Part> parts) {
        return RigFit.intoRigSpace(parts);
    }

    static List<CreatureSculpt.Part> whale() {
        return List.of(
                p("body", -8, 2, -56, 8, 18, 0), p("head", -7, 3, -72, 7, 16, -56),
                p("tail", -5, 6, 0, 5, 14, 12), p("fluke", -14, 8, 12, 14, 10, 20),
                p("finL", 8, 4, -44, 16, 6, -36), p("finR", -16, 4, -44, -8, 6, -36));
    }

    static List<CreatureSculpt.Part> shark() {
        return List.of(
                p("body", -6, 2, -20, 6, 14, 16), p("head", -5, 3, -30, 5, 12, -20),
                p("dorsal_fin", -1, 14, -4, 1, 24, 4), p("tail", -3, 5, 16, 3, 11, 26),
                p("tail_fin", -1, 2, 26, 1, 18, 30), p("finL", 6, 3, -10, 14, 5, -4),
                p("finR", -14, 3, -10, -6, 5, -4));
    }

    private static CreatureSculpt.Part p(String n, float x0, float y0, float z0,
                                         float x1, float y1, float z1) {
        return new CreatureSculpt.Part(n, null, new float[]{0, y0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, 0x3050E0);
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (SECONDS <= 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        for (int cx = -2; cx <= 3; cx++) {
            for (int cz = -2; cz <= 3 + (COPIES * 24) / 16; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int copy = 0; copy < COPIES; copy++) {
            build(level, copy * 24);
            scene(level, copy * 24, copy);
        }
        LOG.info("rigperf: {} rigs up: {}", RIGS.size(), RIGS.stream()
                .map(r -> r.base().getType().toShortString()).toList());
    }

    private static void scene(ServerLevel level, int dz, int copy) {
        // land: three real sculpts, twice each; water: the two long swimmers, twice each
        int i = 0;
        for (String c : List.of("bowser-armB", "sigmaboy-armA", "tralalelo-armA")) {
            for (int k = 0; k < 2; k++) {
                add(level, "perf_land_" + copy + "_" + i, SpikeFixture.parts(c),
                        SpikeFixture.scale(c),
                        new Vec3(ORIGIN.getX() + 4 + 7 * k, ORIGIN.getY() + 1,
                                ORIGIN.getZ() + dz + 4 + 5 * (i % 3)));
                i++;
            }
        }
        int j = 0;
        for (List<CreatureSculpt.Part> sea : List.of(whale(), shark())) {
            for (int k = 0; k < 2; k++) {
                add(level, "perf_sea_" + copy + "_" + j, sea, sea.size() == 6 ? 1.5 : 1.2,
                        new Vec3(ORIGIN.getX() + 28 + 6 * k, ORIGIN.getY() + 1,
                                ORIGIN.getZ() + dz + 6 + 8 * (j % 2)));
                j++;
            }
        }
    }

    private static void add(ServerLevel level, String key, List<CreatureSculpt.Part> parts,
                            double scale, Vec3 at) {
        Map<String, com.moddingfromamod.runtime.BedrockAnimation> clips = Map.of();
        try {
            CreatureFiles.write(key, parts, "{}");
            clips = ModelRepository.animations(key);
        } catch (Exception e) {
            LOG.warn("rigperf: no clips for {}: {}", key, e.toString());
        }
        DisplayRig rig = spawn(level, at, parts, scale, key);
        VanillaRigAnimator.drive(rig, rigSpace(parts), clips);
        RIGS.add(rig);
    }

    /**
     * The bridge's call, as each arm makes it: the carrier was chosen from these two
     * numbers; since, they are ignored and the rig as drawn decides.
     */
    private static DisplayRig spawn(ServerLevel level, Vec3 at, List<CreatureSculpt.Part> parts,
                                    double scale, String key) {
        var ext = com.moddingfromamod.runtime.CreatureAnatomy.extents(RigGeometry.of(parts));
        return DisplayRig.spawn(level, at, 0f, parts, scale,
                com.moddingfromamod.runtime.CreatureAnatomy.widthBlocks(ext) * scale,
                com.moddingfromamod.runtime.CreatureAnatomy.heightBlocks(ext) * scale, key);
    }

    /** A barrier-fenced stone pen (x 0..20) and a glass-walled pool (x 24..44), 20 deep in z. */
    private static void build(ServerLevel level, int dz) {
        int y = ORIGIN.getY();
        for (int x = -1; x <= 45; x++) {
            for (int z = -1; z <= 21; z++) {
                boolean pool = x >= 23;
                level.setBlock(new BlockPos(x, y - (pool ? 6 : 0), z + dz), Blocks.STONE.defaultBlockState(), 2);
                boolean edge = x == -1 || x == 45 || z == -1 || z == 21 || x == 22 || x == 23;
                for (int h = pool ? -5 : 1; h <= 4; h++) {
                    BlockPos b = new BlockPos(x, y + h, z + dz);
                    if (edge) {
                        level.setBlock(b, Blocks.BARRIER.defaultBlockState(), 2);
                    } else if (pool && h <= 1) {
                        level.setBlock(b, Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
        }
        if (!LIGHT) {
            return;
        }
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= 20; z++) {
                if (x <= 10) {
                    // the roofed half: dark under stone, a glowstone every 5 blocks
                    boolean lamp = x % 5 == 2 && z % 5 == 2;
                    level.setBlock(new BlockPos(x, y + 5, z + dz),
                            (lamp ? Blocks.GLOWSTONE : Blocks.STONE).defaultBlockState(), 2);
                } else if (x % 5 == 3 && z % 6 == 3) {
                    level.setBlock(new BlockPos(x, y + 1, z + dz), Blocks.TORCH.defaultBlockState(), 2);
                }
            }
        }
        for (int x = 26; x <= 42; x += 6) {
            for (int z = 2; z <= 18; z += 6) {
                level.setBlock(new BlockPos(x, y - 6, z + dz), Blocks.SEA_LANTERN.defaultBlockState(), 2);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPre(ServerTickEvent.Pre event) {
        if (SECONDS > 0) {
            pre = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPost(ServerTickEvent.Post event) {
        if (SECONDS <= 0 || RIGS.isEmpty() || done) {
            return;
        }
        long ns = System.nanoTime() - pre;
        tick++;
        boolean sampling = tick > WARMUP_TICKS;
        if (tick == WARMUP_TICKS) {
            lightWrites0 = DisplayRig.lightWrites;
            lightOwn0 = DisplayRig.lightOwnPackets;
        }
        if (LIGHT && sampling) {
            ServerLevel level = event.getServer().overworld();
            level.setDayTime(level.getDayTime() + 50);
        }
        for (DisplayRig r : RIGS) {
            Vec3 was = LAST.get(r.base());
            if (sampling && was != null && was.distanceToSqr(r.base().position()) > 1e-8) {
                carrierMovingTicks++;
            }
            count(r.base(), sampling);
            for (Entity d : r.displays()) {
                count(d, sampling);
            }
            for (Entity d : r.proxies()) {   //: hit proxies are entities the server pays for
                long m0 = moved;
                long d0 = dirty;
                count(d, sampling);
                proxyMoved += moved - m0;
                proxyDirty += dirty - d0;
            }
        }
        if (sampling) {
            TICK_MS.add(ns / 1e6);
        }
        if (tick >= WARMUP_TICKS + SECONDS * 20) {
            done = true;
            finish(event.getServer());
        }
    }

    private static void count(Entity e, boolean sampling) {
        Vec3 now = e.position();
        Vec3 was = LAST.put(e, now);
        if (!sampling) {
            return;
        }
        if (was != null && was.distanceToSqr(now) > 1e-8) {
            moved++;
        }
        if (e.getEntityData().isDirty()) {
            dirty++;
        }
    }

    private static void finish(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<Double> ticks = new ArrayList<>(TICK_MS);
        ticks.sort(Double::compare);
        double mean = ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = ticks.isEmpty() ? 0 : ticks.get((int) Math.floor(0.95 * (ticks.size() - 1)));
        double p50 = ticks.isEmpty() ? 0 : ticks.get((ticks.size() - 1) / 2);
        double p99 = ticks.isEmpty() ? 0 : ticks.get((int) Math.floor(0.99 * (ticks.size() - 1)));
        double max = ticks.isEmpty() ? 0 : ticks.get(ticks.size() - 1);
        int displays = RIGS.stream().mapToInt(r -> r.displays().size()).sum();
        int proxies = RIGS.stream().mapToInt(r -> r.proxies().size()).sum();
        List<String> carriers = RIGS.stream()
                .map(r -> r.base().getType().toShortString()).toList();

        // spawn cost, measured AFTER the window so it cannot disturb it
        List<Double> spawnMs = new ArrayList<>();
        List<CreatureSculpt.Part> spikor = SpikeFixture.parts("bowser-armB");
        for (int n = 0; n < 30; n++) {
            long t0 = System.nanoTime();
            DisplayRig r = spawn(level, new Vec3(10, ORIGIN.getY() + 1, 10), spikor, 2.2,
                    "perf_spawn");
            spawnMs.add((System.nanoTime() - t0) / 1e6);
            r.discard();
        }
        spawnMs.sort(Double::compare);
        // the planner's own cost, UNCACHED -- what the first creature of a new shape pays.
        // (The spawns above hit the per-shape cache after the first.)
        List<Double> planMs = new ArrayList<>();
        var spikorAtoms = RigFit.atoms(RigPlan.atoms(RigFit.intoRigSpace(spikor)), 2.2);
        for (int n = 0; n < 10; n++) {
            long t0 = System.nanoTime();
            HitProxyPlan.plan(spikorAtoms, DisplayRig.candidates(level),
                    CarrierPool.allowed(CarrierPool.Move.WALK, false), HitProxyPlan.proxyPool(),
                    HitProxyPlan.Bounds.VANILLA);
            planMs.add((System.nanoTime() - t0) / 1e6);
        }
        planMs.sort(Double::compare);
        long c0 = System.nanoTime();
        for (int n = 0; n < 100; n++) {
            DisplayRig.candidates(level);
        }
        double candMs = (System.nanoTime() - c0) / 1e6 / 100;
        // the light work itself, timed alone — the window's MSPT cannot resolve it. A sample
        // is what every rig pays every 3rd tick; a write is what each display pays on a change.
        long s0 = System.nanoTime();
        int samples = 0;
        for (int n = 0; n < 20000; n++) {
            for (DisplayRig r : RIGS) {
                DisplayRig.lightAt(level, r.base());
                samples++;
            }
        }
        double sampleUs = (System.nanoTime() - s0) / 1e3 / samples;
        long w0 = System.nanoTime();
        int writes = 0;
        for (int n = 0; n < 200; n++) {
            RigLight l = new RigLight(n % 2 == 0 ? 3 : 4, 0);
            for (DisplayRig r : RIGS) {
                for (Entity d : r.displays()) {
                    DisplayRig.writeLight(d, l);
                    writes++;
                }
            }
        }
        double writeUs = (System.nanoTime() - w0) / 1e3 / writes;
        double seconds = TICK_MS.size() / 20.0;
        long lightWrites = DisplayRig.lightWrites - lightWrites0;
        long lightOwn = DisplayRig.lightOwnPackets - lightOwn0;
        String json = String.format(Locale.ROOT,
                "{\"ticks\":%d,\"seconds\":%.1f,\"rigs\":%d,\"displays\":%d,\"proxies\":%d,"
                        + "\"proxyTypes\":\"%s\",\"planMsMedian\":%.2f,\"carriers\":\"%s\","
                        + "\"msptMean\":%.3f,\"msptP95\":%.3f,"
                        + "\"msptMedian\":%.3f,\"msptP99\":%.3f,\"msptMax\":%.3f,"
                        + "\"dataPacketsPerSecPerPlayer\":%.1f,\"movePacketsPerSecPerPlayer\":%.1f,"
                        + "\"carrierMovingSecs\":%.1f,\"dataPerMovingRigSec\":%.1f,"
                        + "\"movesPerMovingRigSec\":%.1f,"
                        + "\"proxyMovesPerSec\":%.1f,\"proxyDataPerSec\":%.1f,"
                        + "\"spawnMsMedian\":%.3f,\"spawnMsP95\":%.3f,\"candidatesMs\":%.4f,"
                        + "\"light\":%b,\"follow\":%b,\"lightWritesPerSec\":%.2f,"
                        + "\"lightOwnPacketsPerSec\":%.2f,\"lightSampleUs\":%.3f,\"lightWriteUs\":%.3f}",
                TICK_MS.size(), seconds, RIGS.size(), displays, proxies,
                String.join(" ", RIGS.stream().map(r -> r.proxies().size() + "")
                        .toList()), planMs.get(planMs.size() / 2), String.join(" ", carriers),
                mean, p95, p50, p99, max, dirty / seconds, moved / seconds,
                carrierMovingTicks / 20.0, dirty / Math.max(1e-9, carrierMovingTicks / 20.0),
                moved / Math.max(1e-9, carrierMovingTicks / 20.0),
                proxyMoved / seconds, proxyDirty / seconds,
                spawnMs.get(spawnMs.size() / 2),
                spawnMs.get((int) Math.floor(0.95 * (spawnMs.size() - 1))), candMs,
                LIGHT, RigLight.FOLLOW, lightWrites / seconds, lightOwn / seconds, sampleUs, writeUs);
        try {
            Files.writeString(Path.of(OUT), json + "\n");
        } catch (Exception e) {
            LOG.error("rigperf: could not write {}", OUT, e);
        }
        LOG.info("rigperf RESULT {}", json);
        server.halt(false);
    }
}
