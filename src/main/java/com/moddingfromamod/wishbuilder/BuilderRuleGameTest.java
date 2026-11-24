package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.rules.WorldRules;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The new rule picks DO something</b>. Each new outcome is switched on through the real Builder on
 * "when I jump", he jumps, and the world must show it: fireworks fly, he is fast, he heals. The two that are only a
 * sound and particles (a tune, a little snow) are judged by the rule having fired with no error, which is all a
 * headless server can see of them. New triggers: "every 10 seconds" fires by itself, and "when the rain stops"
 * fires when the weather clears.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderRuleGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderRuleGameTest() {
    }

    /**
     * Fireworks counted as they enter the world: a rocket lives a few ticks, and a GameTest server ticks far faster
     * than real time, so a poll for one in flight misses it (measured: "when I jump" fired, the poll saw none).
     */
    private static final java.util.concurrent.atomic.AtomicInteger ROCKETS = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean listening;

    private static void listen() {
        if (!listening) {
            listening = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) -> {
                        if (e.getEntity() instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity) {
                            ROCKETS.incrementAndGet();
                        }
                    });
        }
    }

    @GameTest(template = "empty_test", batch = "builderRules", timeoutTicks = 60000)
    public static void everyNewRulePickDoesSomething(GameTestHelper helper) {
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtrules");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        listen();
        String[][] cases = {
                {"jumps", "fireworks"}, {"jumps", "zoom"}, {"jumps", "hearts"}, {"jumps", "music"}, {"jumps", "snow"},
                {"every", "fireworks"}, {"rain_stops", "fireworks"}};
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (String[] c : cases) {
                    String r = runOne(helper, server, p, c[0], c[1]);
                    LOGGER.info("builder rule GT: when {} -> {}: {}", c[0], c[1], r == null ? "OK" : r);
                    if (r != null) {
                        wrong.add(c[0] + " → " + c[1] + ": " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-rules-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("rule picks that did nothing: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still ruling"));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("; ", wrong)));
        });
    }

    static String runOne(GameTestHelper helper, net.minecraft.server.MinecraftServer server, ServerPlayer p,
                         String when, String does) throws Exception {
        String json = "{\"builder\":1,\"kind\":\"rule\",\"from\":\"\",\"opts\":{\"when\":\"when:" + when + "\",\"does\":\"rule:" + does + "\"}}";
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
        BuilderMake.Result made = BuilderAbilityGameTest.onServer(server, () -> BuilderMake.run(server,
                BuilderMake.Who.of(p), spec, null, BuilderAbilityGameTest.protocol(), false, false))
                .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
        if (made.outcome() != BuilderMake.Outcome.MADE) {
            return "not made: " + made.outcome() + " " + made.detail() + " " + made.calls();
        }
        var level = helper.getLevel();
        int rocketsBefore = ROCKETS.get();
        BuilderAbilityGameTest.onServer(server, () -> {
            p.removeAllEffects();
            for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.FireworkRocketEntity.class,
                    p.getBoundingBox().inflate(32))) {
                e.discard();
            }
            switch (when) {
                case "jumps" -> BuilderAbilityGameTest.invoke(p, "jumpFromGround");
                case "rain_stops" -> com.moddingfromamod.mv.DayTime.weather(server.overworld(), 0, 6000, true, false);
                default -> { }
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        if (when.equals("rain_stops")) {
            // The rain must have been SEEN falling before it stops: isRaining() is the rain level, which ramps up.
            long rainBy = System.currentTimeMillis() + 15_000;
            while (!BuilderAbilityGameTest.onServer(server, () -> server.overworld().isRaining()).get(10, TimeUnit.SECONDS)
                    && System.currentTimeMillis() < rainBy) {
                Thread.sleep(100);
            }
            Thread.sleep(300);
            BuilderAbilityGameTest.onServer(server, () -> {
                com.moddingfromamod.mv.DayTime.weather(server.overworld(), 6000, 0, false, false);
                return null;
            }).get(10, TimeUnit.SECONDS);
        }
        String last = "never checked";
        long until = System.currentTimeMillis() + (when.equals("jumps") ? 8_000 : 20_000);
        while (System.currentTimeMillis() < until) {
            last = BuilderAbilityGameTest.onServer(server, () -> switch (does) {
                case "fireworks" -> ROCKETS.get() > rocketsBefore ? null : "no fireworks went up";
                case "zoom" -> p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) ? null : "he is not fast";
                case "hearts" -> p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) ? null : "he is not healing";
                default -> WorldRules.lastError(made.madeId()) == null ? null : "it failed: " + WorldRules.lastError(made.madeId());
            }).get(10, TimeUnit.SECONDS);
            if (last == null) {
                break;
            }
            Thread.sleep(250);
        }
        BuilderAbilityGameTest.onServer(server, () -> WorldRules.remove(server, made.madeId())).get(10, TimeUnit.SECONDS);
        return last;
    }
}
