package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * <b>Every made-up event HAPPENS</b>. Each motion the Builder offers (and the bounce pit) is made
 * through the real Builder, then the world is watched until it shows: a motion that writes blocks has written
 * some, one that brings creatures has brought them, each near where he aimed. Then it is undone.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderEventGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderEventGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderEvents", timeoutTicks = 60000)
    public static void everyMadeUpEventHappens(GameTestHelper helper) {
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtevents");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setYRot(0);
        p.setXRot(25);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<String[]> cases = new ArrayList<>();
        for (BuilderEvents.Motion m : BuilderEvents.ALL) {
            cases.add(new String[] {m.id(), m.whats().get(0).id()});
        }
        cases.add(new String[] {"turn", "minecraft:slime_block"});   // the bounce pit
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (String[] c : cases) {
                    String r = runOne(helper, server, p, c[0], c[1]);
                    LOGGER.info("builder event GT: {} {} -> {}", c[0], c[1], r == null ? "OK" : r);
                    if (r != null) {
                        wrong.add(c[0] + " " + c[1] + ": " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-events-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("made-up events that did not happen: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still staging"));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("; ", wrong)));
        });
    }

    static String runOne(GameTestHelper helper, net.minecraft.server.MinecraftServer server,
                         net.minecraft.server.level.ServerPlayer p, String motion, String what) throws Exception {
        BuilderEvents.Motion m = BuilderEvents.byId(motion);
        String json = "{\"builder\":1,\"kind\":\"event\",\"from\":\"\",\"size\":1,\"count\":4,\"opts\":{\"motion\":\"motion:"
                + m.motion() + "\",\"event\":\"" + motion + "\",\"what\":\"" + what + "\"}}";
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
        // A little meteor can land, hand over to its crater and be forgotten before the first look (a GameTest server
        // ticks as fast as it can), so an event that is NEW since this make counts as this make's too.
        java.util.Set<String> before = BuilderAbilityGameTest.onServer(server, () -> {
            java.util.Set<String> ids = new java.util.HashSet<>();
            EventDirector.all(server).forEach(x -> ids.add(x.id()));
            return ids;
        }).get(10, TimeUnit.SECONDS);
        BuilderMake.Result made = BuilderAbilityGameTest.onServer(server, () -> BuilderMake.run(server,
                BuilderMake.Who.of(p), spec, null, BuilderAbilityGameTest.protocol(), false, false))
                .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
        if (made.outcome() != BuilderMake.Outcome.MADE) {
            return "not made: " + made.outcome() + " " + made.detail() + " " + made.calls();
        }
        String last = "never checked";
        long until = System.currentTimeMillis() + 30_000;
        List<String> ids = new ArrayList<>();
        while (System.currentTimeMillis() < until) {
            last = BuilderAbilityGameTest.onServer(server, () -> {
                List<StagedEvent> mine = EventDirector.all(server).stream()
                        .filter(x -> x.id().equals(made.madeId()) || x.id().startsWith(made.madeId() + "_")
                                || !before.contains(x.id()))
                        .toList();
                if (mine.isEmpty()) {
                    return "it was never staged";
                }
                mine.forEach(x -> {
                    if (!ids.contains(x.id())) {
                        ids.add(x.id());
                    }
                });
                StagedEvent e = mine.stream().filter(x -> x.written() != null).findFirst().orElse(mine.get(0));
                if (m.motion().equals("vortex")) {
                    // A vortex DRAWS (displays round its foot) and writes blocks only where it tears some up.
                    return e.spawned() > 0 ? null : "it drew nothing";
                }
                if (m.field().equals("drop")) {
                    String tag = EventDirector.entityTag(e.id());
                    List<Entity> came = helper.getLevel().getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(64),
                            x -> x.getTags().contains(tag));
                    return came.isEmpty() ? "no " + what + " came" : null;
                }
                return e.written() == null ? "it wrote no blocks" : null;
            }).get(10, TimeUnit.SECONDS);
            if (last == null) {
                break;
            }
            Thread.sleep(250);
        }
        for (String i : ids) {
            BuilderAbilityGameTest.onServer(server, () -> EventDirector.undo(server, i)).get(20, TimeUnit.SECONDS);
        }
        return last;
    }
}
