package com.moddingfromamod.event.design;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.InspectEventTool;
import com.moddingfromamod.agent.tools.ListEventsTool;
import com.moddingfromamod.agent.tools.RemoveEventTool;
import com.moddingfromamod.agent.tools.StageEventTool;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The agent's event tools, called the way the agent calls them</b> (E5): from a thread
 * that is not the server's, for a child who aimed somewhere. {@code stage_event} keeps the design and
 * stages it where he aimed and its reply says so; {@code list_events} and {@code inspect_event} find
 * it again; {@code remove_event} gives the world back and forgets the design; and a design that does
 * not parse, or names a follow-on that does not exist yet, keeps nothing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventToolGameTest {

    private static final String BATCH = "eventtools";

    /** Between replies; a reply may itself run to several lines (a refusal carries the lesson). */
    private static final String SEP = "\n@@@\n";

    /** The tools hop to the server thread, so a test calls them from off it, in order. */
    private static AtomicReference<String> call(McpTool tool, String... jsons) {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            StringBuilder all = new StringBuilder();
            for (String json : jsons) {
                try {
                    ToolResult r = tool.call(JsonParser.parseString(json).getAsJsonObject());
                    all.append(r.isError() ? "ERROR " : "OK ").append(r.text()).append(SEP);
                } catch (RuntimeException e) {
                    all.append("ERROR threw: ").append(e).append(SEP);
                }
            }
            out.set(all.toString());
        }, "event-tool-test");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static String idIn(String reply) {
        int a = reply.indexOf("happening now (");
        if (a < 0) {
            return null;
        }
        int b = reply.indexOf(')', a);
        return reply.substring(a + "happening now (".length(), b);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aFloodIsStagedWhereHeAimedFoundAgainAndGivenBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        MockPlayerNetwork.actingChildFor(helper, child);   // put back on pass AND fail (the 18-test cluster)
        BlockPos lane = new BlockPos(90_400, 0, 108_000);
        for (int cx = (lane.getX() - 40) >> 4; cx <= (lane.getX() + 40) >> 4; cx++) {
            for (int cz = (lane.getZ() - 40) >> 4; cz <= (lane.getZ() + 40) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BlockPos aim = EventBuild.ground(level, lane.getX(), lane.getZ());
        WishProgress.focusOn(child, aim);
        String name = "tool_flood_" + level.getGameTime();
        AtomicReference<String> staged = call(new StageEventTool(), "{\"name\":\"" + name
                + "\",\"motion\":\"layer\",\"size\":\"little\",\"material\":\"water\","
                + "\"afterwards\":\"until_undo\",\"title\":\"FLOOD!\"}");
        // Phases, because succeedWhen re-runs from the top on every retry: once the flood is given
        // back, a "the water is here" check above the undo would fail forever and hide the result.
        int[] phase = {0};
        String[] id = {null};
        AtomicReference<String> found = new AtomicReference<>(), removed = new AtomicReference<>();
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(staged.get() != null, Component.literal("waiting for stage_event"));
                helper.assertTrue(staged.get().startsWith("OK") && staged.get().contains("Kept '" + name + "'"),
                        Component.literal("stage_event kept it and said what it is: " + staged.get()));
                id[0] = idIn(staged.get());
                helper.assertTrue(id[0] != null, Component.literal("and its reply names the id remove_event takes: " + staged.get()));
                helper.assertTrue(level.getBlockState(aim.above()).is(Blocks.WATER),
                        Component.literal("it happened where he aimed (" + aim + "), not where he stands"));
                AtomicReference<String> l = call(new ListEventsTool(), "{}");
                AtomicReference<String> i = call(new InspectEventTool(), "{\"name\":\"" + name + "\"}",
                        "{\"id\":\"" + id[0] + "\"}");
                join(found, l, i);
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(found.get() != null, Component.literal("waiting for list and inspect"));
                helper.assertTrue(found.get().contains("design " + name) && found.get().contains(id[0]),
                        Component.literal("list_events shows the design and the event: " + found.get()));
                helper.assertTrue(found.get().contains("\"motion\":\"layer\"") && found.get().contains("blocks changed"),
                        Component.literal("inspect_event gives the design back as stage_event arguments, and the event's state: "
                                + found.get()));
                join(removed, call(new RemoveEventTool(), "{\"id\":\"" + id[0] + "\"}",
                        "{\"name\":\"" + name + "\"}"));
                phase[0] = 2;
            }
            helper.assertTrue(removed.get() != null, Component.literal("waiting for remove_event"));
            helper.assertTrue(!removed.get().contains("ERROR"), Component.literal("remove_event took both: " + removed.get()));
            helper.assertTrue(EventDirector.get(server, id[0]) == null, Component.literal("the flood is undone"));
            helper.assertTrue(!level.getBlockState(aim.above()).is(Blocks.WATER), Component.literal("and drained"));
            helper.assertTrue(EventDesigns.get(server, name) == null, Component.literal("and the design forgotten"));
            MockPlayerNetwork.remove(child);
        });
    }

    /**
     * a transform turns what is ALREADY there into its material where it stands, changes only
     * what the design names, and gives every block back on undo. Two designs at once: "the ground into
     * gold", and "the leaves pink" beside a block of his that must stay his.
     */
    // Its own batch: the aim and the acting child are statics, and the flood above, run alongside
    // it, moved this transform onto the flood's lane (measured: 797 blocks of gold, none here).
    @GameTest(template = "empty_test", batch = BATCH + "_transform", timeoutTicks = 600)
    public static void aTransformChangesWhatIsThereAndOnlyThatAndGivesItBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        MockPlayerNetwork.actingChildFor(helper, child);   // put back on pass AND fail (the 18-test cluster)
        BlockPos lane = new BlockPos(90_400, 0, 108_400);
        for (int cx = (lane.getX() - 40) >> 4; cx <= (lane.getX() + 40) >> 4; cx++) {
            for (int cz = (lane.getZ() - 40) >> 4; cz <= (lane.getZ() + 40) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BlockPos aim = EventBuild.ground(level, lane.getX(), lane.getZ());
        BlockState ground = level.getBlockState(aim);
        BlockPos leaf = aim.offset(2, 4, 0), stone = aim.offset(-2, 4, 0);
        level.setBlockAndUpdate(leaf, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        level.setBlockAndUpdate(stone, Blocks.STONE_BRICKS.defaultBlockState());   // his build: not natural, not leaves
        WishProgress.focusOn(child, aim);
        String gold = "tool_gold_" + level.getGameTime(), pink = "tool_pink_" + level.getGameTime();
        AtomicReference<String> a = call(new StageEventTool(), "{\"name\":\"" + gold
                + "\",\"motion\":\"transform\",\"size\":\"little\",\"material\":\"gold_block\"}");
        AtomicReference<String> b = call(new StageEventTool(), "{\"name\":\"" + pink
                + "\",\"motion\":\"transform\",\"size\":\"little\",\"material\":\"pink_wool\","
                + "\"replaces\":\"#leaves\"}");
        int[] phase = {0};
        String[] ids = {null, null};
        AtomicReference<String> removed = new AtomicReference<>();
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(a.get() != null && b.get() != null, Component.literal("waiting for stage_event"));
                helper.assertTrue(a.get().startsWith("OK") && b.get().startsWith("OK"),
                        Component.literal("both staged: " + a.get() + " | " + b.get()));
                ids[0] = idIn(a.get());
                ids[1] = idIn(b.get());
                if (!level.getBlockState(aim).is(Blocks.GOLD_BLOCK)) {
                    int golds = 0;
                    for (BlockPos q : BlockPos.betweenClosed(aim.offset(-20, -4, -20), aim.offset(20, 4, 20))) {
                        golds += level.getBlockState(q).is(Blocks.GOLD_BLOCK) ? 1 : 0;
                    }
                    helper.fail(Component.literal("the ground where he aimed (" + aim + ", was " + ground + ", now "
                            + level.getBlockState(aim) + ") turned to gold; gold nearby: " + golds
                            + "; event " + describeEvent(server, ids[0]) + "; stage_event said: " + a.get()));
                }
                helper.assertTrue(level.getBlockState(aim.above()).isAir(), Component.literal("in place: nothing on top"));
                helper.assertTrue(level.getBlockState(leaf).is(Blocks.WOOL.pink()), Component.literal("the leaves turned pink"));
                helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE_BRICKS),
                        Component.literal("and only the leaves: his stone bricks are neither ground nor leaves"));
                join(removed, call(new RemoveEventTool(), "{\"id\":\"" + ids[0] + "\"}",
                        "{\"id\":\"" + ids[1] + "\"}", "{\"name\":\"" + gold + "\"}", "{\"name\":\"" + pink + "\"}"));
                phase[0] = 1;
            }
            helper.assertTrue(removed.get() != null, Component.literal("waiting for remove_event"));
            helper.assertTrue(!removed.get().contains("ERROR"), Component.literal("remove_event took them: " + removed.get()));
            helper.assertTrue(level.getBlockState(aim).equals(ground), Component.literal("the ground came back as it was"));
            helper.assertTrue(level.getBlockState(leaf).is(Blocks.OAK_LEAVES), Component.literal("and the leaves"));
            level.setBlockAndUpdate(leaf, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
            MockPlayerNetwork.remove(child);
        });
    }

    /**
     * a crowd brings exactly what he asked for — ten horses at the aim, a ring of cats round
     * HIM — keeps them, and one undo takes exactly the crowd away and nothing else (a horse of his own
     * standing in the herd stays).
     */
    @GameTest(template = "empty_test", batch = BATCH + "_crowd", timeoutTicks = 600)
    public static void aCrowdBringsWhatHeAskedKeepsItAndOneUndoTakesExactlyIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        MockPlayerNetwork.actingChildFor(helper, child);   // put back on pass AND fail (the 18-test cluster)
        BlockPos lane = new BlockPos(90_400, 0, 108_800);
        for (int cx = (lane.getX() - 48) >> 4; cx <= (lane.getX() + 48) >> 4; cx++) {
            for (int cz = (lane.getZ() - 48) >> 4; cz <= (lane.getZ() + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        // WHATEVER THIS TEST LEAVES, IT TAKES BACK — passed or failed (2026-10-10). A timeout here left
        // two KEPT crowds (16 creatures, counted by the world's spawn budget) and forced chunks behind,
        // and the next dozen tests' spawns were refused ("no Boss is standing there"): the forced A/B
        // with only the stand-in restored still failed 12. Its own events only: new since it began, and
        // staged by its child.
        java.util.Set<String> eventsBefore = new java.util.HashSet<>();
        EventDirector.all(server).forEach(e -> eventsBefore.add(e.id()));
        String actor = child.getGameProfile().name();
        net.minecraft.world.entity.Entity[] ownHorse = {null};
        MockPlayerNetwork.afterTest(helper, () -> {
            for (var e : EventDirector.all(server)) {
                if (!eventsBefore.contains(e.id()) && actor.equals(e.owner())) {
                    EventDirector.undo(server, e.id());
                }
            }
            if (ownHorse[0] != null) {
                ownHorse[0].discard();
            }
            for (int cx = (lane.getX() - 48) >> 4; cx <= (lane.getX() + 48) >> 4; cx++) {
                for (int cz = (lane.getZ() - 48) >> 4; cz <= (lane.getZ() + 48) >> 4; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
        });
        BlockPos aim = EventBuild.ground(level, lane.getX(), lane.getZ());
        BlockPos his = EventBuild.ground(level, lane.getX() + 30, lane.getZ());
        child.teleportTo(his.getX() + 0.5, his.getY() + 1, his.getZ() + 0.5);
        var own = new net.minecraft.world.entity.animal.equine.Horse(net.minecraft.world.entity.EntityTypes.HORSE, level);
        own.snapTo(aim.getX() + 0.5, aim.getY() + 1, aim.getZ() + 0.5, 0, 0);
        own.setPersistenceRequired();
        level.addFreshEntity(own);
        ownHorse[0] = own;
        WishProgress.focusOn(child, aim);
        String herd = "tool_herd_" + level.getGameTime(), cats = "tool_cats_" + level.getGameTime();
        AtomicReference<String> a = call(new StageEventTool(), "{\"name\":\"" + herd
                + "\",\"motion\":\"crowd\",\"size\":\"medium\",\"drop\":\"horse\",\"count\":10}");
        AtomicReference<String> b = call(new StageEventTool(), "{\"name\":\"" + cats
                + "\",\"motion\":\"crowd\",\"size\":\"little\",\"drop\":\"cat\",\"count\":6,"
                + "\"pattern\":\"around_me\",\"baby\":true}");
        int[] phase = {0};
        String[] ids = {null, null};
        AtomicReference<String> removed = new AtomicReference<>();
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(a.get() != null && b.get() != null, Component.literal("waiting for stage_event"));
                helper.assertTrue(a.get().startsWith("OK") && b.get().startsWith("OK"),
                        Component.literal("both staged: " + a.get() + " | " + b.get()));
                ids[0] = idIn(a.get());
                ids[1] = idIn(b.get());
                // Counted by the EVENT'S TAG (exactly what each crowd spawned) in a wide box, and judged
                // by where each crowd is CENTRED — never "who is still inside 8 blocks". A GameTest
                // server ticks as fast as it can while the two replies cross threads, so on a busy
                // machine hundreds of ticks pass first and kittens wander: that read "2 cats round HIM"
                // and timed the test out (2026-10-10, three runs — and see actingChildFor for what the
                // timeout then did to eighteen other tests). The aim and he are 30 blocks apart, so a
                // centre is unambiguous however far a crowd has strolled.
                var wide = new net.minecraft.world.phys.AABB(aim).inflate(64);
                var herdNow = level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Horse.class, wide,
                        h -> h.entityTags().contains(EventDirector.entityTag(ids[0])));
                helper.assertTrue(herdNow.size() == 10, Component.literal("ten horses came: " + herdNow.size()));
                helper.assertTrue(own.isAlive() && !own.entityTags().contains(EventDirector.entityTag(ids[0])),
                        Component.literal("his own horse is not one of the herd"));
                var kittens = level.getEntitiesOfClass(net.minecraft.world.entity.animal.feline.Cat.class, wide,
                        c -> c.entityTags().contains(EventDirector.entityTag(ids[1])));
                helper.assertTrue(kittens.size() == 6, Component.literal("six cats came: " + kittens.size()));
                helper.assertTrue(kittens.stream().allMatch(c -> c.isBaby()), Component.literal("kittens, as asked"));
                double hx = herdNow.stream().mapToDouble(e -> e.getX()).average().orElse(0);
                double hz = herdNow.stream().mapToDouble(e -> e.getZ()).average().orElse(0);
                double kx = kittens.stream().mapToDouble(e -> e.getX()).average().orElse(0);
                double kz = kittens.stream().mapToDouble(e -> e.getZ()).average().orElse(0);
                helper.assertTrue(Math.hypot(hx - aim.getX(), hz - aim.getZ()) < 10,
                        Component.literal("the herd stands at the aim (its centre is " + (int) Math.hypot(hx - aim.getX(), hz - aim.getZ())
                                + " blocks off)"));
                helper.assertTrue(Math.hypot(kx - his.getX(), kz - his.getZ()) < 10
                                && Math.hypot(kx - aim.getX(), kz - aim.getZ()) > 20,
                        Component.literal("the cats stand round HIM, not at the aim (centre " + (int) Math.hypot(kx - his.getX(), kz - his.getZ())
                                + " from him, " + (int) Math.hypot(kx - aim.getX(), kz - aim.getZ()) + " from the aim)"));
                helper.assertTrue(EventDirector.get(server, ids[0]) != null, Component.literal("the herd is kept: "
                        + describeEvent(server, ids[0])));
                join(removed, call(new RemoveEventTool(), "{\"id\":\"" + ids[0] + "\"}",
                        "{\"id\":\"" + ids[1] + "\"}", "{\"name\":\"" + herd + "\"}", "{\"name\":\"" + cats + "\"}"));
                phase[0] = 1;
            }
            helper.assertTrue(removed.get() != null, Component.literal("waiting for remove_event"));
            helper.assertTrue(!removed.get().contains("ERROR"), Component.literal("remove_event took them: " + removed.get()));
            var wideNow = new net.minecraft.world.phys.AABB(aim).inflate(64);
            int herdLeft = level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Horse.class, wideNow,
                    h -> h.entityTags().contains(EventDirector.entityTag(ids[0]))).size();
            helper.assertTrue(herdLeft == 0 && own.isAlive(), Component.literal("one undo took the herd and left his horse: "
                    + herdLeft + " of the herd left, his " + (own.isAlive() ? "alive" : "GONE")));
            helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.animal.feline.Cat.class, wideNow,
                    c -> c.entityTags().contains(EventDirector.entityTag(ids[1]))).isEmpty(), Component.literal("and the cats"));
            own.discard();
            MockPlayerNetwork.remove(child);
        });
    }

    private static String describeEvent(MinecraftServer server, String id) {
        var e = id == null ? null : EventDirector.get(server, id);
        return e == null ? id + " gone" : id + " phase=" + e.phase() + " age=" + e.age() + " changed="
                + e.changed() + " refused=" + e.refused() + " box=" + java.util.Arrays.toString(e.written()) + " focus=" + e.focus();
    }

    /** Set {@code into} to all the replies joined, once every one of them has arrived. */
    @SafeVarargs
    private static void join(AtomicReference<String> into, AtomicReference<String>... replies) {
        Thread t = new Thread(() -> {
            StringBuilder b = new StringBuilder();
            for (AtomicReference<String> r : replies) {
                while (r.get() == null) {
                    Thread.onSpinWait();
                }
                b.append(r.get());
            }
            into.set(b.toString());
        }, "event-tool-join");
        t.setDaemon(true);
        t.start();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBadEventOrAMissingFollowOnKeepsNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String bad = "tool_bad_" + helper.getLevel().getGameTime();
        String orphan = "tool_orphan_" + helper.getLevel().getGameTime();
        AtomicReference<String> r = call(new StageEventTool(),
                "{\"name\":\"" + bad + "\",\"motion\":\"volcano\",\"size\":\"enormous\"}",
                "{\"name\":\"" + orphan + "\",\"motion\":\"falling_body\",\"size\":\"little\",\"material\":\"stone\","
                        + "\"now\":false,\"then\":[{\"design\":\"no_such_design_anywhere\"}]}");
        helper.succeedWhen(() -> {
            helper.assertTrue(r.get() != null, Component.literal("waiting for stage_event"));
            String[] lines = r.get().split(java.util.regex.Pattern.quote(SEP));
            helper.assertTrue(lines[0].startsWith("ERROR") && lines[0].contains("motion") && lines[0].contains("size"),
                    Component.literal("a bad design is refused with every reason: " + lines[0]));
            helper.assertTrue(lines[1].startsWith("ERROR") && lines[1].contains("Stage it first"),
                    Component.literal("a follow-on that does not exist yet is refused, saying how: " + lines[1]));
            helper.assertTrue(EventDesigns.get(server, bad) == null && EventDesigns.get(server, orphan) == null,
                    Component.literal("and neither was kept"));
        });
    }

    /**
     * C3: "make it ice instead" after a meteor wish must be able to MEAN the meteor. Before,
     * the resolver's candidates had no events at all, so the edit went to the nearest build, and
     * the sticky subject was the running instance id, which names nothing he can restage.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aKeptEventIsSomethingHeCanCallIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        String name = "tool_itmeteor_" + helper.getLevel().getGameTime();
        StageEventTool tool = new StageEventTool();
        AtomicReference<String> r = call(tool,
                "{\"name\":\"" + name + "\",\"motion\":\"falling_body\",\"size\":\"little\","
                        + "\"material\":\"gold_block\",\"now\":false}");
        helper.succeedWhen(() -> {
            helper.assertTrue(r.get() != null, Component.literal("waiting for stage_event"));
            helper.assertTrue(r.get().startsWith("OK"), Component.literal("kept: " + r.get()));
            helper.assertTrue(name.equals(tool.lastSubjectId()),
                    Component.literal("the subject is the design's name, not " + tool.lastSubjectId()));
            boolean candidate = com.moddingfromamod.wish.ArtifactIndex.forPlayer(child).stream()
                    .anyMatch(c -> c.id().equals(name) && "event".equals(c.kind()));
            helper.assertTrue(candidate, Component.literal("the design is a candidate for what \"it\" means"));
            boolean listed = com.moddingfromamod.wish.ArtifactIndex.everything().stream()
                    .anyMatch(e -> e.id().equals(name));
            helper.assertTrue(listed, Component.literal("and it is in everything he owns"));
            EventDesigns.forget(server, name);
        });
    }

    /**
     * C3: "make it ice instead" re-made as a NEW name leaves the gold meteor kept beside it,
     * and the reply must say so (Opus kept both 6 of 6 with the rule only in the lesson). Silent for
     * the same name, and for a design this wish was not about.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aReplacementUnderANewNameSaysTheOldOneIsStillKept(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String crater = "tool_goldc_" + t, rock = "tool_goldm_" + t;
        EventDesign oldCrater = parse("{\"name\":\"" + crater + "\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"gold_block\"}");
        EventDesigns.put(server, oldCrater);
        EventDesign oldRock = parse("{\"name\":\"" + rock + "\",\"motion\":\"falling_body\",\"size\":\"little\","
                + "\"material\":\"gold_block\",\"then\":[{\"design\":\"" + crater + "\"}]}");
        EventDesigns.put(server, oldRock);
        EventDesign iceRock = parse("{\"name\":\"tool_icem_" + t + "\",\"motion\":\"falling_body\",\"size\":\"little\","
                + "\"material\":\"packed_ice\"}");
        EventDesign iceCrater = parse("{\"name\":\"tool_icec_" + t + "\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"packed_ice\"}");
        try {
            String r = StageEventTool.stillKeptOld(server, iceRock, rock);
            helper.assertTrue(r != null && r.contains(rock) && r.contains("remove_event"),
                    Component.literal("a new rock for a wish about the gold rock names it: " + r));
            String c = StageEventTool.stillKeptOld(server, iceCrater, rock);
            helper.assertTrue(c != null && c.contains(crater), Component.literal("and a new crater names the old follow-on: " + c));
            helper.assertTrue(StageEventTool.stillKeptOld(server, oldRock, rock) == null,
                    Component.literal("restaging the same name says nothing"));
            helper.assertTrue(StageEventTool.stillKeptOld(server, iceRock, "something_else") == null,
                    Component.literal("a wish about something else says nothing"));
            helper.succeed();
        } finally {
            EventDesigns.forget(server, rock);
            EventDesigns.forget(server, crater);
        }
    }

    private static EventDesign parse(String json) {
        EventDesign.Parsed p = EventDesign.parse(json);
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.design();
    }
}
