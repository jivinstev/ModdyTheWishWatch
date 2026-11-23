package com.moddingfromamod.wishbuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.ui.PickPage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What a MAXED-OUT Builder creature costs a real server</b>. The Builder lost its typed counts ("two
 * powers", "three parts", "two special moves"), so a child can now pick everything the tools can make; this is the
 * measurement that says whether that costs anything a limit should be drawn for.
 *
 * <p>Off unless {@code -Ptestprops="buildermax=plain|capped|max,buildermaxn=<N>,buildermaxout=<file>"}; then, on a
 * DEDICATED server (real 20 TPS, as {@code ops/SpawnPerfProbe}): a cat is made through the REAL Builder — its menus
 * pressed by {@link BuilderFlow} and the make run by {@link BuilderMake}, every tool real — with
 * <ul>
 *   <li>{@code plain}: no picks;</li>
 *   <li>{@code capped}: an earlier issue's limits, the first three parts, two powers and two moves the pages offer;</li>
 *   <li>{@code max}: every tile the pages let him take — every power, one move of each effect, parts to the body's room.</li>
 * </ul>
 * then N of it are spawned in a sky pen (walled and roofed: some fly) with six zombies for the moves to aim at, and after a 10 s warm-up {@code
 * buildermaxsecs} (default 60) of tick time is recorded: mean, p95, max MSPT, and how many entities the pen ends with
 * (a move that drops treats fills it). One JSON object to {@code buildermaxout}; then the server halts. ⚠ {@code rm -rf
 * run/server/world} and {@code cameraName=off} before every run (docs/ATOM_RIG_COST.md §7).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class BuilderMaxProbe {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/buildermax");

    private static final String ARM = System.getProperty("moddingfromamod.buildermax", "");
    private static final int N = Integer.getInteger("moddingfromamod.buildermaxn", 30);
    private static final int SECONDS = Integer.getInteger("moddingfromamod.buildermaxsecs", 60);
    private static final String OUT = System.getProperty("moddingfromamod.buildermaxout", "/tmp/buildermax.json");
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int SIDE = 16;

    private static final List<Double> TICK_MS = new ArrayList<>();
    private static volatile String madeId;
    private static volatile String failed;
    private static String spec = "";
    private static int tick;
    private static int since = -1;
    private static long pre;
    private static boolean done;

    private BuilderMaxProbe() {
    }

    private static boolean on() {
        return ARM.equals("plain") || ARM.equals("capped") || ARM.equals("max") || ARM.equals("picks");
    }

    /** The spec the menus give for this arm: pressed, never written by hand. */
    static BuilderSpec spec(String arm) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("species/cat");
        if (arm.equals("picks")) {   // exactly these tiles, in page order: buildermaxpicks=power:flies+ability:spikes
            List<String> want = List.of(System.getProperty("moddingfromamod.buildermaxpicks", "").split("\\+"));
            while (f.step() != BuilderFlow.Step.TUNE) {
                for (PickPage.Tile t : f.page().tiles()) {
                    if (want.contains(t.id()) && !t.selected()) {
                        f.press(t.id());
                    }
                }
                f.press(PickPage.NEXT_ID);
            }
            return f.spec(Map.of("count", "1"));
        }
        int[] most = arm.equals("capped") ? new int[] {3, 2, 2} : arm.equals("max") ? new int[] {99, 99, 99} : new int[] {0, 0, 0};
        BuilderFlow.Step[] pages = {BuilderFlow.Step.PARTS, BuilderFlow.Step.POWERS, BuilderFlow.Step.ABILITIES};
        for (int i = 0; i < pages.length; i++) {
            if (f.step() != pages[i]) {
                continue;
            }
            int taken = 0;
            for (PickPage.Tile t : f.page().tiles()) {
                PickPage p = f.page();
                PickPage.Tile now = p.tiles().stream().filter(x -> x.id().equals(t.id())).findFirst().orElseThrow();
                if (taken < most[i] && !now.selected() && p.picks().counted().contains(now.id()) && p.blocked(now) == null) {
                    if (f.press(now.id()).say().isEmpty()) {
                        taken++;
                    }
                }
            }
            f.press(PickPage.NEXT_ID);
        }
        return f.spec(Map.of("count", "1"));
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!on()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        level.setDayTime(18_000);   // night: the zombies must not burn
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
        for (int cx = -2; cx <= 3; cx++) {
            for (int cz = -2; cz <= 3; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        int y = ORIGIN.getY();
        for (int x = -1; x <= SIDE; x++) {
            for (int z = -1; z <= SIDE; z++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y + 12, z), Blocks.BARRIER.defaultBlockState(), 2);
                boolean edge = x == -1 || x == SIDE || z == -1 || z == SIDE;
                for (int h = 1; h < 12 && edge; h++) {
                    level.setBlock(new BlockPos(x, y + h, z), Blocks.BARRIER.defaultBlockState(), 2);
                }
            }
        }
        BuilderSpec s = spec(ARM);
        spec = s.toJson().toString();
        LOG.info("buildermax: {} spec {}", ARM, spec);
        var who = new BuilderMake.Who(UUID.nameUUIDFromBytes("buildermax".getBytes()), "BuilderMaxProbe",
                level.dimension().location().toString(), SIDE / 2.0, y + 1, SIDE / 2.0);
        BuilderMake.run(server, who, s, null, BuilderMake.protocol(), false, false).whenComplete((r, e) -> {
            if (e != null || r == null || r.madeId() == null || r.madeId().isEmpty()) {
                failed = e != null ? e.toString() : r == null ? "no result" : r.outcome() + " " + r.detail();
            } else {
                madeId = r.madeId();
            }
        });
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
        MinecraftServer server = event.getServer();
        double ms = (System.nanoTime() - pre) / 1e6;
        tick++;
        if (failed != null) {
            done = true;
            write(server, "{\"arm\":\"" + ARM + "\",\"failed\":\"" + failed.replace("\"", "'") + "\"}");
            return;
        }
        if (since < 0) {
            if (madeId != null) {
                since = tick;
                ServerLevel level = server.overworld();
                // `wishmob spawn` answers for a PLAYER (getPlayerOrException): a fake one stands in the pen. Its
                // output is kept — a refused spawn measured an empty pen once (the first run of this probe).
                var stand = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
                stand.snapTo(SIDE / 2.0, ORIGIN.getY() + 1, SIDE / 2.0, 0f, 0f);
                for (int left = N; left > 0; left -= com.moddingfromamod.mob.SpawnBudget.SCULPTED_MAX_COUNT) {
                    server.getCommands().performPrefixedCommand(stand.createCommandSourceStack().withPermission(4),
                            "wishmob spawn " + madeId + " " + Math.min(left, com.moddingfromamod.mob.SpawnBudget.SCULPTED_MAX_COUNT));
                }
                // monsters for the moves that aim at them (a breath, a shove), the same six in every arm
                for (int i = 0; i < 6; i++) {
                    var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    if (z != null) {
                        z.snapTo(2 + i * 2 + 0.5, ORIGIN.getY() + 1, 2.5, 0f, 0f);
                        z.setPersistenceRequired();
                        level.addFreshEntity(z);
                    }
                }
                LOG.info("buildermax: made {}; pen now holds {}", madeId, inPen(level, x -> true));
            } else if (tick > 20 * 300) {
                failed = "the make did not finish in 5 minutes";
            }
            return;
        }
        int t = tick - since;
        if (t % 200 == 0) {
            LOG.info("buildermax: t={}s {} of the {} alive, {} in the pen", t / 20, alive(server.overworld()), N,
                    inPen(server.overworld(), BuilderMaxProbe::ours));
        }
        if (t > 200) {
            TICK_MS.add(ms);
        }
        if (t >= 200 + SECONDS * 20) {
            done = true;
            List<Double> ticks = new ArrayList<>(TICK_MS);
            double mean = ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            List<Double> sorted = new ArrayList<>(ticks);
            sorted.sort(null);
            double p95 = sorted.isEmpty() ? 0 : sorted.get((int) Math.floor(0.95 * (sorted.size() - 1)));
            double max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
            ServerLevel level = server.overworld();
            int entities = inPen(level, x -> true);
            int items = inPen(level, x -> x instanceof net.minecraft.world.entity.item.ItemEntity);
            int alive = alive(level);
            write(server, String.format(Locale.ROOT,
                    "{\"arm\":\"%s\",\"n\":%d,\"made\":\"%s\",\"secs\":%d,\"ticks\":%d,\"meanMs\":%.3f,\"p95Ms\":%.3f,"
                            + "\"maxMs\":%.3f,\"aliveAtEnd\":%d,\"entitiesInPen\":%d,\"itemsInPen\":%d,\"spec\":%s}",
                    ARM, N, madeId, SECONDS, ticks.size(), mean, p95, max, alive, entities, items, spec));
        }
    }

    /** One of the made creatures: a carrier whose rig is its species. */
    private static boolean ours(net.minecraft.world.entity.Entity e) {
        if (!(e instanceof net.minecraft.world.entity.Mob m)) {
            return false;
        }
        var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(m);
        return sp != null && madeId != null && madeId.equals(sp.id());
    }

    /** The made creatures alive ANYWHERE: a max herd shoves itself out of the pen (an earlier issue's first runs). */
    private static int alive(ServerLevel level) {
        int n = 0;
        for (var e : level.getAllEntities()) {
            if (ours(e) && ((net.minecraft.world.entity.LivingEntity) e).isAlive()) {
                n++;
            }
        }
        return n;
    }

    private static int inPen(ServerLevel level, java.util.function.Predicate<net.minecraft.world.entity.Entity> which) {
        var box = new net.minecraft.world.phys.AABB(-1, ORIGIN.getY(), -1, SIDE + 1, ORIGIN.getY() + 13, SIDE + 1);
        return level.getEntities((net.minecraft.world.entity.Entity) null, box, which::test).size();
    }

    private static void write(MinecraftServer server, String json) {
        try {
            Files.writeString(Path.of(OUT), json + "\n");
        } catch (java.io.IOException e) {
            LOG.error("buildermax: could not write {}", OUT, e);
        }
        LOG.info("buildermax RESULT {}", json);
        server.halt(false);
    }
}
