package com.moddingfromamod.mob;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What N wished mobs cost a real server</b> — an earlier issue's probe, the measurement {@link SpawnBudget}'s
 * numbers stand on. The sculpted-creature arm is {@code vanilla/RigPerfProbe} (its
 * {@code rigperfcopies} knob: ten rigs a copy).
 *
 * <p>Off unless {@code -Ptestprops="spawnperf=pen|creeper,spawnperfn=<N>,spawnperfout=<file>"};
 * then, on a DEDICATED server (real 20 TPS — a GameTest server ticks as fast as it can, so its
 * milliseconds mean nothing):
 *
 * <ul>
 *   <li>{@code pen} — N vanilla mobs (zombie, skeleton, cow, pig, sheep, chicken in turn) in a
 *       barrier pen in the sky, at midnight so nobody burns, persistent so nobody despawns, their
 *       own AI wandering and pathing. After a 10 s warm-up, {@code spawnperfsecs} (default 60) of
 *       tick time: mean, p95, max MSPT.</li>
 *   <li>{@code creeper} — N creepers standing a block apart on an eight-deep slab of grass, dirt and
 *       stone, all ignited in ONE tick after a 5 s settle; every tick for the next 10 s is recorded
 *       — the worst one is the number (the way measured TNT), with how many ticks ran over 50
 *       and over 1,000 ms and the blocks the blast broke.</li>
 * </ul>
 *
 * <p>Writes one JSON object to {@code spawnperfout} and halts the server. ⚠ {@code rm -rf
 * run/server/world} and {@code cameraName=off} before every run, for the reasons
 * {@code docs/ATOM_RIG_COST.md} §7 records: these mobs are persistent, and the camera client a
 * dedicated server starts outlives it.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class SpawnPerfProbe {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/spawnperf");

    private static final String MODE = System.getProperty("moddingfromamod.spawnperf", "");
    private static final int N = Integer.getInteger("moddingfromamod.spawnperfn", 50);
    private static final int SECONDS = Integer.getInteger("moddingfromamod.spawnperfsecs", 60);
    private static final String OUT = System.getProperty("moddingfromamod.spawnperfout",
            "/tmp/spawnperf.json");
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final List<EntityType<?>> HERD = List.of(EntityType.ZOMBIE, EntityType.SKELETON,
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN);

    private static final List<Double> TICK_MS = new ArrayList<>();
    private static final List<Mob> MOBS = new ArrayList<>();
    private static int side;
    private static int tick;
    private static long pre;
    private static int solidBefore;
    private static boolean done;

    private SpawnPerfProbe() {
    }

    private static boolean on() {
        return MODE.equals("pen") || MODE.equals("creeper");
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!on()) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        level.setDayTime(18_000);
        event.getServer().overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, event.getServer());
        // a mob a block and a half square in the pen; a creeper a block apart on the slab
        side = MODE.equals("pen") ? Math.max(8, (int) Math.ceil(Math.sqrt(N * 2.25)))
                : Math.max(4, (int) Math.ceil(Math.sqrt(N)));
        for (int cx = -2; cx <= (side + 24) / 16 + 1; cx++) {
            for (int cz = -2; cz <= (side + 24) / 16 + 1; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        if (MODE.equals("pen")) {
            pen(level);
        } else {
            slab(level);
        }
        LOG.info("spawnperf: {} {} up", MOBS.size(), MODE);
    }

    private static void pen(ServerLevel level) {
        int y = ORIGIN.getY();
        for (int x = -1; x <= side; x++) {
            for (int z = -1; z <= side; z++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                boolean edge = x == -1 || x == side || z == -1 || z == side;
                for (int h = 1; h <= 4 && edge; h++) {
                    level.setBlock(new BlockPos(x, y + h, z), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }
        }
        for (int i = 0; i < N; i++) {
            spawn(level, HERD.get(i % HERD.size()), i);
        }
    }

    /** Eight deep — grass, dirt, stone — and three blocks of air margin round the crowd. */
    private static void slab(ServerLevel level) {
        int y = ORIGIN.getY() - 100;
        for (int x = -6; x <= side + 6; x++) {
            for (int z = -6; z <= side + 6; z++) {
                for (int d = 0; d < 8; d++) {
                    var state = d == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : d < 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(new BlockPos(x, y - d, z), state, 2);
                    solidBefore++;
                }
            }
        }
        for (int i = 0; i < N; i++) {
            spawn(level, EntityType.CREEPER, i);
        }
    }

    private static void spawn(ServerLevel level, EntityType<?> type, int i) {
        Entity e = type.create(level, EntitySpawnReason.COMMAND);
        if (!(e instanceof Mob mob)) {
            return;
        }
        int y = MODE.equals("pen") ? ORIGIN.getY() + 1 : ORIGIN.getY() - 99;
        double x = (i % side) + 0.5;
        double z = (i / side) % side + 0.5;
        mob.snapTo(x, y, z, 0f, 0f);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        MOBS.add(mob);
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
        double ms = (System.nanoTime() - pre) / 1e6;
        tick++;
        if (MODE.equals("pen")) {
            if (tick > 200) {
                TICK_MS.add(ms);
            }
            if (tick >= 200 + SECONDS * 20) {
                done = true;
                finish(event.getServer());
            }
            return;
        }
        if (tick == 100) {
            for (Mob m : MOBS) {
                if (m instanceof Creeper c && c.isAlive()) {
                    c.ignite();
                }
            }
        }
        if (tick > 100) {
            TICK_MS.add(ms);
        }
        if (tick >= 300) {
            done = true;
            finish(event.getServer());
        }
    }

    private static void finish(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<Double> ticks = new ArrayList<>(TICK_MS);
        double mean = ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long over50 = ticks.stream().filter(t -> t > 50).count();
        long over1000 = ticks.stream().filter(t -> t > 1000).count();
        double lag = ticks.stream().mapToDouble(t -> Math.max(0, t - 50)).sum();
        ticks.sort(Double::compare);
        double p95 = ticks.isEmpty() ? 0 : ticks.get((int) Math.floor(0.95 * (ticks.size() - 1)));
        double max = ticks.isEmpty() ? 0 : ticks.get(ticks.size() - 1);
        long alive = MOBS.stream().filter(Entity::isAlive).count();
        int broken = 0;
        if (MODE.equals("creeper")) {
            int y = ORIGIN.getY() - 100;
            for (int x = -6; x <= side + 6; x++) {
                for (int z = -6; z <= side + 6; z++) {
                    for (int d = 0; d < 8; d++) {
                        if (level.getBlockState(new BlockPos(x, y - d, z)).isAir()) {
                            broken++;
                        }
                    }
                }
            }
        }
        String json = String.format(Locale.ROOT,
                "{\"mode\":\"%s\",\"n\":%d,\"spawned\":%d,\"aliveAtEnd\":%d,\"ticks\":%d,"
                        + "\"msptMean\":%.3f,\"msptP95\":%.3f,\"msptMax\":%.3f,"
                        + "\"ticksOver50ms\":%d,\"ticksOver1000ms\":%d,\"lagMsOverBudget\":%.1f,"
                        + "\"blocksBroken\":%d,\"slabBlocks\":%d,\"cpus\":%d}",
                MODE, N, MOBS.size(), alive, ticks.size(), mean, p95, max, over50, over1000, lag,
                broken, solidBefore, Runtime.getRuntime().availableProcessors());
        try {
            Files.writeString(Path.of(OUT), json + "\n");
        } catch (Exception e) {
            LOG.error("spawnperf: could not write {}", OUT, e);
        }
        LOG.info("spawnperf RESULT {}", json);
        server.halt(false);
    }
}
