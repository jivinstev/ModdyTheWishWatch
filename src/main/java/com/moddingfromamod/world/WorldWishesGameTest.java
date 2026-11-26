package com.moddingfromamod.world;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.MakeFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * <b>#178: a world wishes through the Builder.</b> A husk is a Builder real mob ({@code mob:minecraft:husk}, Big, How
 * strong 3, fast): it rises under a crimson pillar and arrives with the Builder's numbers, held at the ceiling by the
 * live fit, never weaker than a plain husk, and never as a player's wish. A boss's wish is UNCAPPED. A champion is a
 * real Builder design the world made ({@code BuilderMake.forWorld}: a rock golem in the shadow scheme), spawned as a
 * copy. The queue stands up no more pillars per tick than its caps.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WorldWishesGameTest {

    private static final String BATCH = "worldwishes";

    static BuilderSpec husk(String size, String strength, String speed) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "mob:minecraft:husk", "", 2, List.of(), "",
                1, "", "", Map.of("mobsize", size, "strength", strength, "speed", speed));
    }

    private static final Map<String, Double> CEILING = Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0,
            MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 5.0, MakeFacts.SCALE_TIMES, 2.0);

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBuilderHuskArrivesAtTheBuildersNumbersHeldAtTheCeiling(GameTestHelper helper) {
        String module = "gt_world_" + helper.getLevel().getGameTime();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        AtomicReference<LivingEntity> got = new AtomicReference<>();
        AtomicReference<LivingEntity> boss = new AtomicReference<>();
        AtomicReference<LivingEntity> plain = new AtomicReference<>();
        WorldWishes.creature(helper.getLevel(), module, husk("5", "3", "3"), at.x, at.y, at.z,
                WorldWishes.Limits.of(CEILING), false, got::set);
        WorldWishes.creature(helper.getLevel(), module, husk("5", "3", "3"), at.x + 1, at.y, at.z,
                WorldWishes.Limits.UNCAPPED, false, boss::set);
        WorldWishes.creature(helper.getLevel(), module, husk("4", "2", ""), at.x + 2, at.y, at.z,
                WorldWishes.Limits.of(Map.of(MakeFacts.HEALTH_HALF_HEARTS, 10.0, MakeFacts.ATTACK_DAMAGE_HALF_HEARTS,
                        1.0)), true, plain::set);
        helper.runAfterDelay(WorldWishes.RISE_TICKS / 2, () -> helper.assertTrue(got.get() == null,
                Component.literal("the husk arrived before its pillar had risen")));
        helper.succeedWhen(() -> {
            LivingEntity m = got.get(), b = boss.get(), p = plain.get();
            helper.assertTrue(m != null && b != null && p != null, Component.literal("not every husk arrived"));
            // the Builder's Big + How strong 3 on a husk: 45 health, 5.5 damage; held at 40 and 5 by the live fit
            helper.assertTrue(m.getAttributeBaseValue(Attributes.MAX_HEALTH) == 45.0,
                    Component.literal("the Builder's health " + m.getAttributeBaseValue(Attributes.MAX_HEALTH) + ", not 45"));
            helper.assertTrue(m.getMaxHealth() == 40f, Component.literal("held at " + m.getMaxHealth() + ", not 40"));
            helper.assertTrue(m.getAttributeValue(Attributes.ATTACK_DAMAGE) == 5.0,
                    Component.literal("damage " + m.getAttributeValue(Attributes.ATTACK_DAMAGE) + ", not 5"));
            helper.assertTrue(Math.abs(m.getAttributeValue(Attributes.SCALE) - 1.5) < 1e-6,
                    Component.literal("scale " + m.getAttributeValue(Attributes.SCALE)));
            helper.assertTrue(m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) > 0.23 * 1.2,
                    Component.literal("not fast: " + m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)));
            // the boss: the same spec, uncapped
            helper.assertTrue(b.getMaxHealth() == 45f && b.getAttributeValue(Attributes.ATTACK_DAMAGE) == 5.5
                            && b.getTags().contains(WorldWishes.UNCAPPED_TAG),
                    Component.literal("the uncapped boss was held: " + b.getMaxHealth() + " / "
                            + b.getAttributeValue(Attributes.ATTACK_DAMAGE)));
            // a ceiling below a plain husk never makes it weaker than one
            helper.assertTrue(p.getMaxHealth() == 20f && p.getAttributeValue(Attributes.ATTACK_DAMAGE) == 3.0,
                    Component.literal("weaker than a plain husk: " + p.getMaxHealth() + " / "
                            + p.getAttributeValue(Attributes.ATTACK_DAMAGE)));
            for (LivingEntity e : List.of(m, b, p)) {
                helper.assertTrue(Wished.isWorldWished(e) && module.equals(Wished.worldOwnerOf(e).orElse("")),
                        Component.literal("not marked as the world's: " + e.getTags()));
                helper.assertTrue(!Wished.isWished(e), Component.literal("a world's monster reads as a PLAYER's wish"));
            }
            helper.assertTrue(WorldWishes.pending(module) == 0, Component.literal("still pending after arriving"));
            m.discard();
            b.discard();
            p.discard();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void theQueueStandsUpNoMorePillarsPerTickThanItsCaps(GameTestHelper helper) {
        String module = "gt_caps_" + helper.getLevel().getGameTime();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        int before = WorldWishes.rising();
        for (int i = 0; i < 10; i++) {
            WorldWishes.creature(helper.getLevel(), module, husk("4", "2", ""), base.getX() + i, base.getY(),
                    base.getZ(), WorldWishes.Limits.of(Map.of()), false, m -> { });
        }
        for (int i = 0; i < 20; i++) {
            WorldWishes.creature(helper.getLevel(), module, husk("4", "2", ""), base.getX() + i, base.getY(),
                    base.getZ() + 2, WorldWishes.Limits.of(Map.of()), true, m -> { });
        }
        // same tick, server thread: nothing else steps the queue between these lines
        WorldWishes.step(helper.getLevel().getServer().getTickCount());
        int started = WorldWishes.rising() - before;
        int cancelled = WorldWishes.cancelAll(module);
        helper.assertValueEqual(started, WorldWishes.FULL_PER_TICK + WorldWishes.SLIM_PER_TICK,
                Component.literal("pillars stood up in one tick"));
        helper.assertValueEqual(cancelled, 30, Component.literal("wishes called off"));
        helper.assertValueEqual(WorldWishes.pending(module), 0, Component.literal("left after cancelAll"));
        helper.succeed();
    }

    /**
     * <b>Perf: is a battle's first champion ready before its first wave?</b> The arena makes its champion designs at a
     * battle's start, one at a time (one Builder make in flight per owner), and the first wave comes after the
     * countdown ({@code countdownSeconds}, 5 by default). Every canned body a champion can be, in the shadow scheme,
     * made back to back with a child connected, each timed; the first TWO in a row (this rung's and the next) must be
     * done inside the countdown. The times are printed ({@code [champion perf]}) to read beside a real battle's log.
     */
    @GameTest(template = "empty_test", batch = "worldwishes_perf", timeoutTicks = 20000)
    public static void everyChampionBodyIsMadeWellInsideTheCountdown(GameTestHelper helper) {
        String module = "gt_perf_" + helper.getLevel().getGameTime();
        var server = helper.getLevel().getServer();
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        net.minecraft.server.level.ServerPlayer child =
                com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);   // a child connected, as in a real battle
        List<String> bodies = List.of("rock_golem", "ogre", "yeti", "raptor", "t_rex");
        long[] ms = new long[bodies.size()];
        String[] outcome = new String[bodies.size()];
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        // one after another, as the arena makes them
        java.util.function.IntConsumer[] next = new java.util.function.IntConsumer[1];
        next[0] = i -> {
            if (i >= bodies.size()) {
                return;
            }
            BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE,
                    "preset:bodies/" + bodies.get(i), "shadow", 2,
                    List.of("power:hostile", "power:glowing", "power:fireproof", "power:leaps"), "", 1, "",
                    "Shadow " + bodies.get(i), Map.of("strength", "3"));
            long t0 = System.nanoTime();
            BuilderMake.forWorld(server, module, "The Arena", spec).whenComplete((r, err) -> server.execute(() -> {
                ms[i] = (System.nanoTime() - t0) / 1_000_000;
                outcome[i] = err != null ? err.toString() : r.outcome() + (r.detail().isEmpty() ? "" : " " + r.detail());
                org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID).info("[champion perf] {} made in {} ms ({})",
                        bodies.get(i), ms[i], outcome[i]);
                done.incrementAndGet();
                next[0].accept(i + 1);
            }));
        };
        next[0].accept(0);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() == bodies.size(),
                    Component.literal("made " + done.get() + " of " + bodies.size() + " champion designs so far"));
            for (int i = 0; i < bodies.size(); i++) {
                helper.assertTrue(outcome[i].startsWith("MADE") || outcome[i].startsWith("PARTIAL"),
                        Component.literal(bodies.get(i) + ": " + outcome[i]));
            }
            long worstPair = 0;
            for (int i = 0; i + 1 < bodies.size(); i++) {
                worstPair = Math.max(worstPair, ms[i] + ms[i + 1]);
            }
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID).info(
                    "[champion perf] {} ms each {}; the slowest two in a row {} ms against a 5000 ms countdown",
                    java.util.Arrays.toString(ms), bodies, worstPair);
            helper.assertTrue(worstPair < 5000, Component.literal("two champion makes in a row took " + worstPair
                    + " ms, longer than the arena's 5 s countdown: " + java.util.Arrays.toString(ms)));
            // a world's design has no audience: nothing is said to the child, and no preview stands beside him
            for (String line : com.moddingfromamod.testing.MockPlayerNetwork.chat(child)) {
                helper.assertTrue(!line.contains("plan") && !line.contains("Shadow"),
                        Component.literal("the child was told about the world's make: " + line));
            }
            var near = child.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    child.getBoundingBox().inflate(48), e -> Wished.isWished(e));
            helper.assertTrue(near.isEmpty(), Component.literal("a world's design stood a creature beside the child: "
                    + near.stream().map(e -> e.getName().getString()).toList()));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 12000)
    public static void aWorldMakesAShadowGolemDesignAndWishesACopy(GameTestHelper helper) {
        String module = "gt_design_" + helper.getLevel().getGameTime();
        var server = helper.getLevel().getServer();
        BuilderSpec golem = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/rock_golem",
                "shadow", 2, List.of("power:hostile", "power:leaps", "power:glowing"), "", 1, "", "Shadow Golem",
                Map.of("strength", "3"));
        final long localBefore = com.moddingfromamod.marshal.LocalOnly.refusedSoFar();
        helper.assertFalse(com.moddingfromamod.config.Magician.enabled(),
                Component.literal("the Magician is on: this test proves a world's wish needs nothing of it"));
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        AtomicReference<BuilderMake.Result> again = new AtomicReference<>();
        AtomicReference<LivingEntity> copy = new AtomicReference<>();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        // a name a child could not type is not a world's either: refused, nothing made
        BuilderSpec rude = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/rock_golem",
                "shadow", 2, List.of("power:hostile"), "", 1, "", "Hardcore " + "Porn", Map.of());
        BuilderMake.Result refused = BuilderMake.forWorld(server, module, "The Arena", rude).join();
        helper.assertTrue(refused.outcome() == BuilderMake.Outcome.REFUSED,
                Component.literal("a world's make with an unsafe name was not refused: " + refused.outcome()));
        BuilderMake.forWorld(server, module, "The Arena", golem).thenAcceptAsync(r -> server.execute(() -> {
            made.set(r);
            // the same design asked again is the one already made: a world's makes never fill the registry
            BuilderMake.forWorld(server, module, "The Arena", golem).thenAcceptAsync(r2 -> server.execute(() -> again.set(r2)));
            if (r.outcome() == BuilderMake.Outcome.MADE || r.outcome() == BuilderMake.Outcome.PARTIAL) {
                WorldWishes.species(helper.getLevel(), module, r.madeId(), at.x, at.y, at.z,
                        WorldWishes.Limits.of(Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0)), false, copy::set);
            }
        }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null, Component.literal("the world's make has not finished"));
            helper.assertTrue(r.outcome() == BuilderMake.Outcome.MADE || r.outcome() == BuilderMake.Outcome.PARTIAL,
                    Component.literal("the world's Builder make: " + r.outcome() + " " + r.detail()));
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(r.madeId());
            helper.assertTrue(sp != null, Component.literal("no species " + r.madeId()));
            helper.assertTrue(BuilderMake.worldOwner(module).toString().equals(sp.ownerId()),
                    Component.literal("the design is owned by " + sp.ownerId() + ", not the world"));
            helper.assertTrue(WorldOwners.is(java.util.UUID.fromString(sp.ownerId()))
                            && com.moddingfromamod.wish.reveal.WishOrbs.paletteOf(java.util.UUID.fromString(sp.ownerId()))
                            == com.moddingfromamod.wish.reveal.PillarPalette.WORLD,
                    Component.literal("the world's owner id does not read as a world's"));
            BuilderMake.Result r2 = again.get();
            helper.assertTrue(r2 != null && r2.outcome() == BuilderMake.Outcome.MADE && r.madeId().equals(r2.madeId()),
                    Component.literal("the same design was made again: " + (r2 == null ? "pending" : r2.madeId())));
            LivingEntity c = copy.get();
            helper.assertTrue(c != null && c.isAlive(), Component.literal("the copy has not arrived"));
            helper.assertTrue(Wished.isWorldWished(c) && !Wished.isWished(c),
                    Component.literal("the world's golem reads as a player's wish: " + c.getTags()));
            // its design is How strong 3 (150 health); the world held the copy at 40, and nothing undoes that
            helper.assertTrue(c.getMaxHealth() <= 40f, Component.literal("the design's copy has " + c.getMaxHealth()
                    + " health, over the ceiling of 40"));
            // made with the Magician off and LOCAL ONLY, the make and the copy's arrival alike: no model or web call
            helper.assertTrue(com.moddingfromamod.marshal.LocalOnly.refusedSoFar() == localBefore,
                    Component.literal((com.moddingfromamod.marshal.LocalOnly.refusedSoFar() - localBefore)
                            + " model or web call(s) asked during a world's wish (see the 'local only: refused' lines)"));
            c.discard();
        });
    }
}
