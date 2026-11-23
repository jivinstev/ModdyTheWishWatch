package com.moddingfromamod.vanilla;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What a herd of rigged creatures costs the server when it LOADS</b> — the join stall
 * (2026-11-08: ~35 creatures adopted in 25 s after a child joined, then "Can't keep up! 13718 ms").
 *
 * <p>Two boots of one dedicated server on one world, because the cost is the load and nothing
 * else reproduces a load:
 * <ol>
 *   <li>{@code -Ptestprops="adoptperf=spawn,cameraName=off"} — writes the committed rig-fit
 *       corpus ({@code src/test/resources/rig-fit/real}) as species, conjures each one through
 *       the real wish-mob path in force-loaded chunks, waits for every rig, saves and halts;</li>
 *   <li>{@code -Ptestprops="adoptperf=load,adoptperfout=FILE,cameraName=off"} — boots the saved
 *       world, so every carrier comes back from disk and is adopted by
 *       {@link VanillaRigAnimator}'s own load path. Records every tick until all are driven (and
 *       {@value #TAIL_TICKS} after), and samples the server thread's stack every millisecond, so
 *       the breakdown is what actually ran, not what anybody guessed ran.</li>
 * </ol>
 *
 * <p><b>Twins</b> ({@code adoptperftwins=true}, LOAD only): once every rig is driven, a plain
 * vanilla mob of each carrier's exact type is spawned beside the herd, and after
 * {@value #TWIN_SETTLE} ticks every entity's tick is timed (EXCLUSIVE of any passenger it ticks), by
 * group — carrier, its hit proxies, its displays, and the twin. Same server, same ticks, same JIT: the
 * only fair answer to "does a carrier cost more than the mob it is". {@code adoptperfspeed} and
 * {@code adoptperfbehaves} set the species (default 0.0 and none: the herd measured).
 *
 * <p>Off unless {@code adoptperf} is set. ⚠ {@code rm -rf run/server/world} before the SPAWN boot,
 * and never between the two.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class AdoptPerfProbe {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/adoptperf");

    private static final String MODE = System.getProperty("moddingfromamod.adoptperf", "");
    private static final String OUT = System.getProperty("moddingfromamod.adoptperfout",
            "/tmp/adoptperf.json");
    private static final int COUNT = Integer.getInteger("moddingfromamod.adoptperfcount", 40);
    private static final BlockPos ORIGIN = new BlockPos(0, 120, 0);
    private static final int COLS = 8;
    private static final int SPACING = 8;
    private static final int TAIL_TICKS = Integer.getInteger("moddingfromamod.adoptperftail", 200);
    private static final String PREFIX = "adoptperf_";
    private static final boolean TWINS = Boolean.getBoolean("moddingfromamod.adoptperftwins");
    private static final String SPEED = System.getProperty("moddingfromamod.adoptperfspeed", "0.0");
    private static final String BEHAVES = System.getProperty("moddingfromamod.adoptperfbehaves", "");
    private static final String TWIN_TAG = "adoptperf_twin";
    /** What a twin copies from its carrier: "scale", "invis", or both ("scale+invis"): which difference costs. */
    private static final String TWIN_LIKE = System.getProperty("moddingfromamod.adoptperftwinlike", "");
    private static final int TWIN_SETTLE = 100;
    /** Per group: [nanos, entity-ticks]. */
    private static final Map<String, long[]> GROUP = new HashMap<>();
    private static final Map<String, Set<java.util.UUID>> GROUP_MEMBERS = new HashMap<>();
    private static final java.util.ArrayDeque<long[]> STACK = new java.util.ArrayDeque<>();
    private static boolean timing;
    private static double postPhaseMs;
    private static int postPhaseTicks;
    private static long postStart;

    private static int tick;
    private static long pre;
    private static boolean done;
    private static int allDrivenAt = -1;
    private static final List<Double> TICK_MS = new ArrayList<>();
    private static volatile boolean sampling;
    private static final Map<String, Integer> INCLUSIVE = new HashMap<>();
    private static final Map<String, Integer> SELF = new HashMap<>();
    /** The same, from the tick every rig is driven: what a herd costs once it has loaded. */
    private static final Map<String, Integer> STEADY = new HashMap<>();
    /** And before it: what the load itself cost. */
    private static final Map<String, Integer> LOAD = new HashMap<>();
    private static volatile boolean steady;
    private static int samples;
    private static int steadySamples;

    private AdoptPerfProbe() {
    }

    private static boolean on() {
        return MODE.equals("spawn") || MODE.equals("load") || MODE.equals("soak");
    }

    /** soak: "cx:cz;cx:cz" chunk centres to force-load (radius {@link #SOAK_RADIUS}), on a REAL world copy. */
    private static final String FORCE = System.getProperty("moddingfromamod.adoptperfforce", "");
    private static final int SOAK_RADIUS = 6;
    private static final int SOAK_WARM_TICKS = Integer.getInteger("moddingfromamod.adoptperfwarm", 600);
    private static final int SOAK_TICKS = Integer.getInteger("moddingfromamod.adoptperfsoak", 2400);

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!on()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (MODE.equals("soak")) {
            int n = 0;
            for (String c : FORCE.split(";")) {
                String[] xz = c.strip().split(":");
                if (xz.length != 2) {
                    continue;
                }
                int cx = Integer.parseInt(xz[0].strip());
                int cz = Integer.parseInt(xz[1].strip());
                for (int x = cx - SOAK_RADIUS; x <= cx + SOAK_RADIUS; x++) {
                    for (int z = cz - SOAK_RADIUS; z <= cz + SOAK_RADIUS; z++) {
                        level.setChunkForced(x, z, true);
                        n++;
                    }
                }
            }
            LOG.info("adoptperf soak: {} chunk(s) forced; warming {} ticks, then recording {}", n,
                    SOAK_WARM_TICKS, SOAK_TICKS);
            startSampler(server);
            return;
        }
        int rows = (COUNT + COLS - 1) / COLS;
        int zMax = TWINS ? twinZ0() + rows * SPACING : rows * SPACING;
        for (int cx = -1; cx <= (COLS * SPACING) / 16 + 1; cx++) {
            for (int cz = -1; cz <= zMax / 16 + 1; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        if (TWINS) {
            platform(level, twinZ0(), rows);
            var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            bus.addListener(EventPriority.HIGHEST, EntityTickEvent.Pre.class, AdoptPerfProbe::onEntityPre);
            bus.addListener(EventPriority.LOWEST, EntityTickEvent.Post.class, AdoptPerfProbe::onEntityPost);
            bus.addListener(EventPriority.HIGHEST, ServerTickEvent.Post.class, AdoptPerfProbe::onPostFirst);
        }
        if (MODE.equals("spawn")) {
            spawnHerd(level);
        } else {
            startSampler(server);
        }
    }

    private static void spawnHerd(ServerLevel level) {
        int rows = (COUNT + COLS - 1) / COLS;
        platform(level, 0, rows);
        List<Path> files = corpus();
        int n = 0;
        for (Path f : files) {
            if (n >= COUNT) {
                break;
            }
            try (Reader r = Files.newBufferedReader(f)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                String id = PREFIX + n;
                List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
                CreatureFiles.write(id, parts, o.toString());
                double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;
                SpeciesRegistry.put(level.getServer(), Species.fromJson(JsonParser.parseString(
                        "{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\",\"health\":40,"
                                + "\"speed\":" + SPEED + ",\"scale\":" + scale
                                + (BEHAVES.isEmpty() ? "" : ",\"behaves\":\"" + BEHAVES + "\"")
                                + ",\"powers\":[\"friendly\",\"persistent\"]}").getAsJsonObject()));
                WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
                if (mob == null) {
                    continue;
                }
                int x = ORIGIN.getX() + (n % COLS) * SPACING + 4;
                int z = ORIGIN.getZ() + (n / COLS) * SPACING + 4;
                mob.snapTo(x + 0.5, ORIGIN.getY(), z + 0.5, 0f, 0f);
                mob.setSpeciesId(id);
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                        EntitySpawnReason.COMMAND, null);
                level.addFreshEntity(mob);
                n++;
            } catch (Exception e) {
                LOG.warn("adoptperf: could not spawn {}: {}", f.getFileName(), e.toString());
            }
        }
        LOG.info("adoptperf: conjured {} creature(s)", n);
    }

    private static int twinZ0() {
        return ((COUNT + COLS - 1) / COLS) * SPACING + 24;
    }

    /** A stone floor (with a wall round it, so twins stay in their forced chunks). */
    private static void platform(ServerLevel level, int z0, int rows) {
        for (int x = -2; x <= COLS * SPACING + 2; x++) {
            for (int z = z0 - 2; z <= z0 + rows * SPACING + 2; z++) {
                level.setBlock(new BlockPos(x, ORIGIN.getY() - 1, z), Blocks.STONE.defaultBlockState(), 2);
                boolean edge = x == -2 || x == COLS * SPACING + 2 || z == z0 - 2 || z == z0 + rows * SPACING + 2;
                if (edge && z0 > 0) {
                    for (int y = 0; y < 3; y++) {
                        level.setBlock(new BlockPos(x, ORIGIN.getY() + y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** One plain vanilla mob of each carrier's type, in the same grid, {@link #twinZ0()} south. */
    private static void spawnTwins(ServerLevel level) {
        int gone = 0;
        for (Entity e : toList(level)) {
            if (e.getTags().contains(TWIN_TAG)) {
                e.discard();    // a previous boot's twins, saved with the world: never mix two arms
                gone++;
            }
        }
        if (gone > 0) {
            LOG.info("adoptperf: removed {} twin(s) from an earlier boot", gone);
        }
        int n = 0;
        for (Entity e : toList(level)) {
            if (!(e instanceof Mob m) || !m.getTags().contains(DisplayRig.RIG_TAG)) {
                continue;
            }
            Entity twin = m.getType().create(level, EntitySpawnReason.COMMAND);
            if (!(twin instanceof Mob t)) {
                continue;
            }
            int x = ORIGIN.getX() + (n % COLS) * SPACING + 4;
            int z = twinZ0() + (n / COLS) * SPACING + 4;
            t.snapTo(x + 0.5, ORIGIN.getY(), z + 0.5, 0f, 0f);
            t.finalizeSpawn(level, level.getCurrentDifficultyAt(t.blockPosition()), EntitySpawnReason.COMMAND, null);
            t.setPersistenceRequired();
            t.addTag(TWIN_TAG);
            if (TWIN_LIKE.contains("scale")) {
                var from = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
                var to = t.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
                if (from != null && to != null) {
                    to.setBaseValue(from.getBaseValue());
                    t.refreshDimensions();
                }
            }
            if (TWIN_LIKE.contains("invis")) {
                t.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, true, false, false));
            }
            level.addFreshEntity(t);
            n++;
        }
        LOG.info("adoptperf: spawned {} vanilla twin(s)", n);
    }

    private static List<Entity> toList(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        level.getEntities().getAll().forEach(out::add);
        return out;
    }

    private static String group(Entity e) {
        Set<String> tags = e.getTags();
        String type = net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath();
        if (tags.contains(TWIN_TAG)) {
            return "twin:" + type;
        }
        if (tags.contains(DisplayRig.RIG_TAG)) {
            return "carrier:" + type;
        }
        if (tags.contains(HitProxies.PROXY_TAG)) {
            return "proxy";
        }
        if (e instanceof net.minecraft.world.entity.Display) {
            return "display";
        }
        return "other:" + type;
    }

    /** Not @SubscribeEvent: a listener on every entity's tick is paid by every server, probe or not. */
    static void onEntityPre(EntityTickEvent.Pre event) {
        if (timing && !event.getEntity().level().isClientSide()) {
            STACK.push(new long[]{System.nanoTime(), 0});
        }
    }

    static void onEntityPost(EntityTickEvent.Post event) {
        if (!timing || event.getEntity().level().isClientSide() || STACK.isEmpty()) {
            return;
        }
        long[] f = STACK.pop();
        long total = System.nanoTime() - f[0];
        long self = total - f[1];
        if (!STACK.isEmpty()) {
            STACK.peek()[1] += total;
        }
        Entity e = event.getEntity();
        String g = group(e);
        long[] acc = GROUP.computeIfAbsent(g, k -> new long[2]);
        acc[0] += self;
        acc[1]++;
        GROUP_MEMBERS.computeIfAbsent(g, k -> new HashSet<>()).add(e.getUUID());
    }

    static void onPostFirst(ServerTickEvent.Post event) {
        if (timing) {
            postStart = System.nanoTime();
        }
    }

    static List<Path> corpus() {
        Path dir = SpikeFixture.find("src/test/resources/rig-fit/real");
        if (dir == null) {
            throw new IllegalStateException("adoptperf: the rig-fit corpus is not reachable");
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int carriers(ServerLevel level, boolean drivenOnly) {
        int c = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof Mob m && m.getTags().contains(DisplayRig.RIG_TAG)
                    && (!drivenOnly || VanillaRigAnimator.isDriving(m))) {
                c++;
            }
        }
        return c;
    }

    /** Samples the server thread every ~1 ms while the load is measured. */
    private static void startSampler(MinecraftServer server) {
        Thread target = server.getRunningThread();
        sampling = true;
        Thread t = new Thread(() -> {
            while (sampling) {
                StackTraceElement[] st = target.getStackTrace();
                synchronized (INCLUSIVE) {
                    samples++;
                    Set<String> seen = new HashSet<>();
                    for (StackTraceElement el : st) {
                        String cls = el.getClassName();
                        String k = cls.substring(cls.lastIndexOf('.') + 1) + "." + el.getMethodName();
                        if (seen.add(k)) {
                            INCLUSIVE.merge(k, 1, Integer::sum);
                        }
                    }
                    for (String k : seen) {
                        (steady ? STEADY : LOAD).merge(k, 1, Integer::sum);
                    }
                    if (steady) {
                        steadySamples++;
                    }
                    if (st.length > 0) {
                        String cls = st[0].getClassName();
                        SELF.merge(cls.substring(cls.lastIndexOf('.') + 1) + "." + st[0].getMethodName(),
                                1, Integer::sum);
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "adoptperf-sampler");
        t.setDaemon(true);
        t.start();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPre(ServerTickEvent.Pre event) {
        if (on()) {
            pre = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPost(ServerTickEvent.Post event) {
        if (!on() || done) {
            return;
        }
        if (timing) {
            postPhaseMs += (System.nanoTime() - postStart) / 1e6;
            postPhaseTicks++;
        }
        tick++;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (MODE.equals("spawn")) {
            if (tick % 20 == 0 && carriers(level, true) >= COUNT || tick > 1200) {
                done = true;
                LOG.info("adoptperf: {} rigs driven after {} ticks; saving and halting",
                        carriers(level, true), tick);
                server.halt(false);
            }
            return;
        }
        if (MODE.equals("soak")) {
            if (tick == SOAK_WARM_TICKS) {
                allDrivenAt = 0;    // the recorded window starts here; LOAD before it, STEADY after
                steady = true;
                soak0 = new long[]{com.moddingfromamod.selector.SelectorIndex.INDEXED.get(),
                        com.moddingfromamod.selector.SelectorIndex.CANDIDATES.get(),
                        com.moddingfromamod.selector.SelectorIndex.NOT_INDEXABLE.get(),
                        com.moddingfromamod.selector.SelectorIndex.MISMATCHES.get()};
            }
            if (tick > SOAK_WARM_TICKS) {
                TICK_MS.add((System.nanoTime() - pre) / 1e6);
            }
            if (tick >= SOAK_WARM_TICKS + SOAK_TICKS) {
                done = true;
                sampling = false;
                finishSoak(server, level);
            }
            return;
        }
        TICK_MS.add((System.nanoTime() - pre) / 1e6);
        if (allDrivenAt < 0 && tick % 5 == 0 && carriers(level, true) >= COUNT) {
            allDrivenAt = tick;
            steady = true;
            if (TWINS) {
                spawnTwins(level);
            }
        }
        if (TWINS && allDrivenAt >= 0 && tick == allDrivenAt + TWIN_SETTLE) {
            timing = true;
        }
        if ((allDrivenAt >= 0 && tick >= allDrivenAt + TAIL_TICKS) || tick > 2400) {
            done = true;
            sampling = false;
            finish(server, level);
        }
    }

    private static void finish(MinecraftServer server, ServerLevel level) {
        List<Double> sorted = new ArrayList<>(TICK_MS);
        sorted.sort(Double::compare);
        double total = TICK_MS.stream().mapToDouble(Double::doubleValue).sum();
        long over50 = TICK_MS.stream().filter(v -> v > 50).count();
        StringBuilder json = new StringBuilder();
        json.append(String.format(Locale.ROOT,
                "{\"count\":%d,\"carriers\":%d,\"driven\":%d,\"allDrivenAtTick\":%d,\"ticks\":%d,"
                        + "\"tickMsTotal\":%.1f,\"tickMsMax\":%.1f,\"tickMsP95\":%.1f,\"over50\":%d,"
                        + "\"samples\":%d,\"steadySamples\":%d,\"recalled\":%d,\"replanned\":%d,",
                COUNT, carriers(level, false), carriers(level, true), allDrivenAt, TICK_MS.size(),
                total, sorted.get(sorted.size() - 1),
                sorted.get((int) Math.floor(0.95 * (sorted.size() - 1))), over50, samples, steadySamples,
                HitProxies.RECALLED.get(), HitProxies.REPLANNED.get()));
        List<Double> tail = allDrivenAt < 0 ? List.of() : new ArrayList<>(TICK_MS.subList(allDrivenAt, TICK_MS.size()));
        json.append(String.format(Locale.ROOT, "\"steadyMsptMean\":%.2f,",
                tail.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        if (TWINS) {
            json.append("\"timedTicks\":").append(postPhaseTicks).append(',');
            json.append(String.format(Locale.ROOT, "\"postPhaseMsMean\":%.3f,",
                    postPhaseTicks == 0 ? 0 : postPhaseMs / postPhaseTicks));
        json.append("\"twinLike\":\"").append(TWIN_LIKE).append("\",");
            json.append("\"groups\":{");
            boolean first = true;
            for (var en : new java.util.TreeMap<>(GROUP).entrySet()) {
                long[] a = en.getValue();
                json.append(first ? "" : ",").append(String.format(Locale.ROOT,
                        "\"%s\":{\"entities\":%d,\"entityTicks\":%d,\"usPerEntityTick\":%.2f,\"msPerServerTick\":%.4f}",
                        en.getKey(), GROUP_MEMBERS.get(en.getKey()).size(), a[1], a[0] / 1e3 / a[1],
                        postPhaseTicks == 0 ? 0 : a[0] / 1e6 / postPhaseTicks));
                first = false;
            }
            json.append("},");
        }
        json.append("\"firstTicks\":").append(TICK_MS.subList(0, Math.min(60, TICK_MS.size())).stream()
                .map(v -> String.format(Locale.ROOT, "%.0f", v)).toList()).append(',');
        synchronized (INCLUSIVE) {
            json.append("\"inclusive\":").append(top(INCLUSIVE, 70)).append(',');
            json.append("\"self\":").append(top(SELF, 30)).append(',');
            json.append("\"load\":").append(top(LOAD, 120)).append(',');
            json.append("\"steady\":").append(top(STEADY, 120)).append('}');
        }
        try {
            Files.writeString(Path.of(OUT), json + "\n");
        } catch (Exception e) {
            LOG.error("adoptperf: could not write {}", OUT, e);
        }
        LOG.info("adoptperf RESULT {}", json);
        server.halt(false);
    }

    private static long[] soak0 = new long[4];

    private static void finishSoak(MinecraftServer server, ServerLevel level) {
        List<Double> sorted = new ArrayList<>(TICK_MS);
        sorted.sort(Double::compare);
        int entities = 0;
        for (Entity e : level.getEntities().getAll()) {
            entities++;
        }
        StringBuilder json = new StringBuilder();
        json.append(String.format(Locale.ROOT,
                "{\"mode\":\"soak\",\"selectorindex\":\"%s\",\"ticks\":%d,\"entitiesLoaded\":%d,"
                        + "\"msptMean\":%.3f,\"msptMedian\":%.3f,\"msptP95\":%.3f,\"msptMax\":%.1f,"
                        + "\"indexed\":%d,\"candidates\":%d,\"notIndexable\":%d,\"mismatches\":%d,"
                        + "\"mismatchesWholeRun\":%d,\"poisoned\":%d,\"samples\":%d,\"steadySamples\":%d,",
                com.moddingfromamod.selector.SelectorIndex.mode(), TICK_MS.size(), entities,
                TICK_MS.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                sorted.get((sorted.size() - 1) / 2), sorted.get((int) Math.floor(0.95 * (sorted.size() - 1))),
                sorted.get(sorted.size() - 1),
                com.moddingfromamod.selector.SelectorIndex.INDEXED.get() - soak0[0],
                com.moddingfromamod.selector.SelectorIndex.CANDIDATES.get() - soak0[1],
                com.moddingfromamod.selector.SelectorIndex.NOT_INDEXABLE.get() - soak0[2],
                com.moddingfromamod.selector.SelectorIndex.MISMATCHES.get() - soak0[3],
                com.moddingfromamod.selector.SelectorIndex.MISMATCHES.get(),
                com.moddingfromamod.selector.SelectorIndex.POISONED.get(), samples, steadySamples));
        synchronized (INCLUSIVE) {
            json.append("\"steady\":").append(top(STEADY, 150)).append('}');
        }
        try {
            Files.writeString(Path.of(OUT), json + "\n");
        } catch (Exception e) {
            LOG.error("adoptperf: could not write {}", OUT, e);
        }
        LOG.info("adoptperf SOAK RESULT {}", json.substring(0, Math.min(600, json.length())));
        server.halt(false);
    }

    private static String top(Map<String, Integer> m, int n) {
        StringBuilder sb = new StringBuilder("{");
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).forEach(e -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey().replace("\"", "")).append("\":").append(e.getValue());
        });
        return sb.append('}').toString();
    }
}
