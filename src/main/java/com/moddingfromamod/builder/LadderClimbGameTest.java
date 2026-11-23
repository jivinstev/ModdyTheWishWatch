package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The library's ladders, climbed by a player in a real world.</b>
 *
 * <p>The Secret Treehouse climbed on scaffolding that fell when it was wished anywhere but flat
 * ground, and the Underwater Dome's ladder had nothing behind seven of its cells. Both passed
 * every check that existed, because nothing asked the one question a child asks: <i>can I get
 * up it?</i> {@code BuildSupportSweepTest} asks the plan; this asks the world. Each building is
 * put up by the real {@link Builder}, the world ticks, nothing may be falling, and then every
 * ladder or scaffolding column still standing is climbed by {@link ClimbProbe} with the game's own
 * movement code.
 *
 * <p><b>Any other plans:</b> {@code -Ptestprops="climbplans=/abs/dir"} climbs every
 * {@code *.json} building plan in that directory too — how an earlier issue's smoke grants were checked.
 *
 * <p>Built far from the test plots in force-loaded chunks (a block tick only runs in a ticking
 * chunk, so scaffolding left unforced would never get the chance to fall) and taken down after.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class LadderClimbGameTest {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("moddingfromamod/ladderClimb");

    /** Its own batch: it builds up to 50k blocks and forces chunks, and shares no statics. */
    private static final String BATCH = "ladderClimb";

    /** The starters that climb. Every other starter has no ladder and is swept in Gate A. */
    static final List<String> STARTERS = List.of("secret_treehouse", "underwater_dome");

    /** Far from every plot, spaced so no two subjects share a chunk. */
    private static final int ORIGIN_X = 24_000;
    private static final int ORIGIN_Z = 24_000;
    private static final int SPACING = 160;
    private static final int GROUND_Y = 140;

    private static BuildPlan starter(String id) {
        String path = "/starter-library/buildings/" + id + "/build.json";
        try (InputStream in = LadderClimbGameTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return BuildPlan.fromJson(JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    /** The plans in {@code moddingfromamod.climbplans}, or none. */
    static List<BuildPlan> extraPlans() {
        String dir = System.getProperty(ModdingFromAModMod.MODID + ".climbplans", "");
        List<BuildPlan> out = new ArrayList<>();
        if (dir.isBlank()) {
            return out;
        }
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                BuildPlan p = BuildPlan.fromJson(JsonParser.parseString(Files.readString(f))
                        .getAsJsonObject());
                if (p != null) {
                    out.add(p);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("climbplans: could not read {}: {}", dir, e.toString());
        }
        return out;
    }

    /** One subject, built and climbed; the falling-block count comes later, after ticks. */
    private record Subject(BuildPlan plan, BlockPos anchor, BuildFraming.Bounds bounds,
                           List<ClimbProbe.Climb> climbs, List<long[]> forced) {
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void everyStarterLadderCanBeClimbedAndNothingFalls(GameTestHelper helper) {
        List<BuildPlan> plans = new ArrayList<>();
        for (String id : STARTERS) {
            BuildPlan p = starter(id);
            helper.assertTrue(p != null, Component.literal("starter " + id + " did not load"));
            plans.add(p);
        }
        plans.addAll(extraPlans());
        ServerLevel level = helper.getLevel();
        Player climber = helper.makeMockPlayer(GameType.SURVIVAL);
        List<Subject> subjects = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            subjects.add(build(level, plans.get(i), i));
        }
        // Let the world tick: scaffolding and sand with nothing under them turn into falling
        // blocks on their first scheduled tick. Then climb what is left standing.
        helper.runAfterDelay(40, () -> {
            List<String> failures = new ArrayList<>();
            try {
                for (Subject s : subjects) {
                    climbAll(level, climber, s);
                    report(level, s, failures);
                }
            } finally {
                for (Subject s : subjects) {
                    takeDown(level, s);
                }
            }
            if (failures.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(Component.literal(String.join("\n", failures)));
            }
        });
    }

    private static Subject build(ServerLevel level, BuildPlan plan, int i) {
        BuildFraming.Bounds b = BuildFraming.bounds(plan);
        BlockPos anchor = new BlockPos(ORIGIN_X + i * SPACING, GROUND_Y - b.minY(), ORIGIN_Z);
        List<long[]> forced = new ArrayList<>();
        for (int cx = (anchor.getX() + b.minX() - 1) >> 4; cx <= (anchor.getX() + b.maxX() + 1) >> 4; cx++) {
            for (int cz = (anchor.getZ() + b.minZ() - 1) >> 4; cz <= (anchor.getZ() + b.maxZ() + 1) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                forced.add(new long[]{cx, cz});
            }
        }
        // Flat ground under the whole footprint: the plan's lowest layer rests on it. What lies
        // under a build on a slope or a tree is an earlier issue's question, not this one.
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
            for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) {
                level.setBlock(anchor.offset(x, b.minY() - 1, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        String r = Builder.submit(level, anchor, plan, false);
        Builder.drainNow(level);
        LOGGER.info("ladderClimb: {} at {}: {}", plan.id(), anchor.toShortString(), r);
        return new Subject(plan, anchor, b, new ArrayList<>(), forced);
    }

    /**
     * Climb every column, on the SETTLED world: scaffolding works out how far it is from support on
     * its first scheduled tick, so asked in the tick it was placed every piece reads as unsupported.
     */
    private static void climbAll(ServerLevel level, Player climber, Subject s) {
        List<BlockPos> owned = new ArrayList<>();
        s.plan().forEachBlock((x, y, z, block) -> owned.add(s.anchor().offset(x, y, z)));
        for (ClimbProbe.Column c : ClimbProbe.columns(level, new java.util.LinkedHashSet<>(owned))) {
            ClimbProbe.Climb climb = ClimbProbe.climb(level, climber, c);
            LOGGER.info("ladderClimb: {}: {}", s.plan().id(), climb.describe());
            s.climbs().add(climb);
        }
    }

    private static void report(ServerLevel level, Subject s, List<String> failures) {
        String id = s.plan().id();
        List<BuildSupport.Problem> design = BuildSupport.check(s.plan());
        if (!design.isEmpty()) {
            failures.add(id + ": " + design.size() + " blocks cannot stand by design, e.g. "
                    + design.get(0).describe());
        }
        boolean hasLadderShape = s.plan().shapes().stream()
                .anyMatch(x -> BuildSupport.base(x.block()).equals("ladder"));
        if (hasLadderShape && s.climbs().isEmpty()) {
            failures.add(id + ": the plan names a ladder and none is in the world (was it placed as "
                    + "stone? a ladder's [state] must reach Builder)");
        }
        for (ClimbProbe.Climb c : s.climbs()) {
            if (!c.climbed()) {
                failures.add(id + ": " + c.describe());
            }
        }
        BuildFraming.Bounds b = s.bounds();
        AABB box = new AABB(s.anchor().getX() + b.minX() - 2, s.anchor().getY() + b.minY() - 4,
                s.anchor().getZ() + b.minZ() - 2, s.anchor().getX() + b.maxX() + 3,
                s.anchor().getY() + b.maxY() + 3, s.anchor().getZ() + b.maxZ() + 3);
        List<FallingBlockEntity> falling = level.getEntitiesOfClass(FallingBlockEntity.class, box);
        if (!falling.isEmpty()) {
            failures.add(id + ": " + falling.size() + " blocks fell, e.g. "
                    + falling.get(0).getBlockState() + " at " + falling.get(0).blockPosition().toShortString());
        }
        LOGGER.info("ladderClimb: {}: {} ladder column(s), {} climbed, {} falling",
                id, s.climbs().size(), s.climbs().stream().filter(ClimbProbe.Climb::climbed).count(),
                falling.size());
    }

    private static void takeDown(ServerLevel level, Subject s) {
        try {
            Builder.clearBuild(level, s.anchor(), s.plan());
            BuildFraming.Bounds b = s.bounds();
            for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
                for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) {
                    level.setBlock(s.anchor().offset(x, b.minY() - 1, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            AABB all = new AABB(s.anchor()).inflate(SPACING / 2.0, 80, SPACING / 2.0);
            level.getEntitiesOfClass(FallingBlockEntity.class, all).forEach(e -> e.discard());
        } finally {
            for (long[] c : s.forced()) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
        }
    }

    /**
     * <b>A ladder survives its building being changed.</b> The held-out grant of the
     * ladders smoke built a tower whose ladder was backed all the way up, widened it with a
     * {@code start_from} edit, and then its own claim check found <i>0 ladders</i> in the world —
     * so it swapped them for scaffolding. Built fresh, both versions stand and climb; this drives
     * the agent's exact two calls through the real tool, the way the agent does.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aLadderSurvivesItsBuildingBeingEditedInPlace(GameTestHelper helper) {
        String id = "gt_ladder_edit_tower";
        String first = """
            {"id":"$ID","name":"Tower","note":"#1374 fixture","shapes":[
             {"name":"wall","kind":"cylinder","anchor":"centre","width":9,"height":24,"depth":9,"block":"minecraft:purpur_block","hollow":true},
             {"name":"floor","kind":"cylinder","anchor":"centre","width":9,"height":1,"depth":9,"block":"minecraft:amethyst_block"},
             {"name":"top_floor","kind":"cylinder","anchor":"centre","dy":20,"width":9,"height":1,"depth":9,"block":"minecraft:amethyst_block"},
             {"name":"hatch","kind":"box","dx":0,"dy":20,"dz":-3,"width":1,"height":1,"depth":1,"block":"minecraft:air"},
             {"name":"ladder","kind":"box","dx":0,"dy":1,"dz":-3,"width":1,"height":20,"depth":1,"block":"minecraft:ladder[facing=south]"},
             {"name":"door","kind":"box","dx":0,"dy":1,"dz":4,"width":1,"height":3,"depth":1,"block":"minecraft:air"}]}
            """.replace("$ID", id);
        String second = """
            {"id":"$ID","start_from":"$ID","note":"widen the hatch","shapes":[
             {"name":"hatch","kind":"box","dx":-1,"dy":20,"dz":-3,"width":2,"height":1,"depth":1,"block":"minecraft:air"},
             {"name":"ladder","kind":"box","dx":-1,"dy":1,"dz":-3,"width":2,"height":20,"depth":1,"block":"minecraft:ladder[facing=south]"}]}
            """.replace("$ID", id);
        java.util.concurrent.atomic.AtomicReference<String> done = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                var a = new com.moddingfromamod.agent.tools.BuildStructureTool().call(
                        JsonParser.parseString(first).getAsJsonObject());
                var b = new com.moddingfromamod.agent.tools.BuildStructureTool().call(
                        JsonParser.parseString(second).getAsJsonObject());
                done.set((a.isError() ? "ERR1 " + a.text() : "") + (b.isError() ? "ERR2 " + b.text() : "ok"));
            } catch (RuntimeException e) {
                done.set("threw: " + e);
            }
        }, "ladder-edit-gametest");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for the two build calls"));
            helper.assertTrue(done.get().equals("ok"), Component.literal(done.get()));
            BuildPlan plan = BuildRegistry.get(id);
            Long anchor = BuildRegistry.anchorOf(id);
            helper.assertTrue(plan != null && anchor != null, Component.literal("the building was not saved"));
            ServerLevel level = helper.getLevel().getServer().overworld();
            BlockPos at = BlockPos.of(anchor);
            int want = 0;
            int have = 0;
            java.util.Set<BlockPos> owned = new java.util.HashSet<>();
            java.util.Map<BlockPos, String> last = new java.util.HashMap<>();
            plan.forEachBlock((x, y, z, block) -> last.put(at.offset(x, y, z), block));
            for (var e : last.entrySet()) {
                owned.add(e.getKey());
                if (BuildSupport.base(e.getValue()).equals("ladder")) {
                    want++;
                }
            }
            for (BlockPos p : owned) {
                if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.LadderBlock) {
                    have++;
                }
            }
            helper.assertTrue(have == want, Component.literal("after an edit in place " + have + " of " + want
                    + " ladder cells are in the world"));
        });
    }
}
