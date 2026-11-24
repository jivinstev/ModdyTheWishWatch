package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * <b> where it RUNS: a Builder creature aimed inside a SMALL base lands.</b> The battle arena gives each player a
 * base of about 31 by 31 blocks to aim in, inside an area of 33 by 43. A "Follows you" creature is lowered to
 * {@code sculpt_creature} plus {@code modify_mob} with a 64-block search radius, and the reach rule used to ask whether
 * that whole 64-block square round him fitted inside his base: on a base smaller than the radius it never does, so
 * every such make was refused ("Pal the Cat didn't work") while a building at the same spot landed. The rule now asks
 * whether he stands on his base, and {@code modify_mob} acts only on the creatures inside it.
 *
 * <p>Through the real {@link BuilderMake} and {@link McpProtocol} {@code tools/call}, with the base and a
 * {@code base_reach} world's rules pinned ({@link WorldModuleHost#forTest}, {@link WorldRulesService#forTest}). Its
 * own batches: the stand-in child and both pins are globals.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SmallBaseCreatureGameTest {

    private SmallBaseCreatureGameTest() {
    }

    /** The arena's shape round him: an area 33 by 43, its reach (where a wish may be aimed) the inner 31 by 31. */
    static Bases arenaBaseAround(ServerPlayer him) {
        return arenaBaseAround(him, 0);
    }

    /** …centred {@code dz} blocks south of him ({@code dz} past 21 leaves him standing off it). */
    static Bases arenaBaseAround(ServerPlayer him, int dz) {
        int x = him.getBlockX();
        int z = him.getBlockZ() + dz;
        UUID owner = him.getUUID();
        Base base = new Base(owner, Bases.OVERWORLD, x, him.getBlockY(), z, him.getBlockY(),
                new Region(x - 16, x + 16, z - 21, z + 21), new Region(x - 15, x + 15, z - 15, z + 15));
        return new Bases() {
            @Override public String noun() {
                return "base";
            }

            @Override public Base baseOf(UUID player) {
                return owner.equals(player) ? base : null;
            }

            @Override public UUID ownerAt(String dimension, double px, double pz) {
                return base.area().contains(px, pz) ? owner : null;
            }

            @Override public Bases.Pos positionOf(UUID player) {
                return owner.equals(player) ? new Bases.Pos(Bases.OVERWORLD, him.getX(), him.getY(), him.getZ()) : null;
            }
        };
    }

    private static final String PAL = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,"
            + "\"temper\":\"pet\",\"name\":\"Pal the Cat\"}";

    @GameTest(template = "empty_test", batch = "smallbasecreature", timeoutTicks = 12000)
    public static void aFollowsYouCreatureLandsInASmallBase(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsmallbase");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        child.setYRot(0);
        child.setXRot(25);
        MockPlayerNetwork.actingChildFor(helper, child);
        Bases bases = arenaBaseAround(child);
        WorldModuleHost.forTest(bases);
        WorldRulesService.forTest(ScopedPacksGameTest.closedRules());
        MockPlayerNetwork.afterTest(helper, () -> {
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
        });
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);

        MinecraftServer server = helper.getLevel().getServer();
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(PAL).getAsJsonObject());
        CompletableFuture<BuilderMake.Result> made = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, BuilderMake.Who.of(child), spec, null, protocol(), false, false)
                .whenComplete((r, e) -> {
                    if (e != null) {
                        made.completeExceptionally(e);
                    } else {
                        made.complete(r);
                    }
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(made.isDone(), Component.literal("still making"));
            helper.assertFalse(made.isCompletedExceptionally(), Component.literal("the make threw"));
            BuilderMake.Result r = made.join();
            helper.assertTrue(r.outcome() == BuilderMake.Outcome.MADE,
                    Component.literal("a Follows-you creature aimed inside a 31-block base: " + r.outcome() + " "
                            + r.calls() + " — " + r.detail()));
            helper.assertTrue(r.calls().contains("modify_mob"), Component.literal("modify_mob was not called: " + r.calls()));
            // It stands on his base.
            List<net.minecraft.world.entity.Entity> his = new ArrayList<>();
            Base base = bases.baseOf(child.getUUID());
            for (var e : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(at).inflate(64))) {
                var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
                if (sp != null && sp.id().equals(r.madeId())) {
                    his.add(e);
                }
            }
            helper.assertFalse(his.isEmpty(), Component.literal("no " + r.madeId() + " stands near him"));
            for (var e : his) {
                helper.assertTrue(base.area().contains(e.getX(), e.getZ()), Component.literal(r.madeId() + " stands at "
                        + e.blockPosition().toShortString() + ", off his base " + base.area().describe()));
            }
            his.forEach(net.minecraft.world.entity.Entity::discard);
        });
    }

    /**
     * The same make with the base 60 blocks south of him: the spot locked in front of him is off his base, so the
     * creature is refused in the rule's words and nothing is made — the fix let no wish land outside its base.
     */
    @GameTest(template = "empty_test", batch = "smallbasecreature_off", timeoutTicks = 12000)
    public static void aCreatureAimedOffHisSmallBaseIsRefused(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsmallbaseoff");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        child.setYRot(0);
        child.setXRot(25);
        MockPlayerNetwork.actingChildFor(helper, child);
        WorldModuleHost.forTest(arenaBaseAround(child, 60));
        WorldRulesService.forTest(ScopedPacksGameTest.closedRules());
        MockPlayerNetwork.afterTest(helper, () -> {
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
        });
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        MinecraftServer server = helper.getLevel().getServer();
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(PAL.replace("Pal the Cat", "Pal Off Base"))
                .getAsJsonObject());
        CompletableFuture<BuilderMake.Result> made = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, BuilderMake.Who.of(child), spec, null, protocol(), false, false)
                .whenComplete((r, e) -> {
                    if (e != null) {
                        made.completeExceptionally(e);
                    } else {
                        made.complete(r);
                    }
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(made.isDone(), Component.literal("still making"));
            BuilderMake.Result r = made.join();
            helper.assertTrue(r.outcome() != BuilderMake.Outcome.MADE && r.outcome() != BuilderMake.Outcome.PARTIAL,
                    Component.literal("a creature aimed OFF his base was made: " + r.outcome() + " " + r.calls()));
            helper.assertTrue(r.detail().contains("NOT HIS BASE"),
                    Component.literal("refused, but not in the rule's words: " + r.outcome() + " " + r.calls() + " — " + r.detail()));
        });
    }

    /**
     * {@code modify_mob} with a radius wider than his base changes only the creatures INSIDE it: a pig on his base
     * and one 30 blocks south (past the base's edge, well inside the 64-block radius) — only the first is renamed.
     * And from off his base the same call is refused. Through the real {@code tools/call} with a wish bound.
     */
    @GameTest(template = "empty_test", batch = "smallbasecreature_clamp", timeoutTicks = 12000)
    public static void aSearchWiderThanHisBaseActsOnlyInsideIt(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "gtsmallbaseclamp");
        MockPlayerNetwork.actingChildFor(helper, him);
        var server = helper.getLevel().getServer();
        String tag = "gt_smallbase_" + helper.getLevel().getGameTime();
        Bases bases = arenaBaseAround(him);
        WorldRules rules = ScopedPacksGameTest.closedRules();
        WorldModuleHost.forTest(bases);
        WorldRulesService.forTest(rules);
        for (String where : List.of("~ ~ ~3", "~ ~ ~30")) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput()
                    .withPosition(him.position()), "summon minecraft:pig " + where + " {NoAI:1b,Tags:[\"" + tag + "\"]}");
        }
        java.util.concurrent.atomic.AtomicReference<String[]> inside = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String[]> fromOff = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try (ScopedPacksGameTest.Wish w = wish(him, bases, rules)) {
                inside.set(w.call("modify_mob", com.google.gson.JsonParser.parseString(
                        "{\"scope\":\"group\",\"target\":\"pig\",\"radius\":64,\"count\":10,\"name\":\"Changed\"}")
                        .getAsJsonObject()));
            } catch (Throwable e) {
                err.set(e);
            }
            try (ScopedPacksGameTest.Wish w = wish(him, arenaBaseAround(him, 60), rules)) {
                WorldModuleHost.forTest(arenaBaseAround(him, 60));
                fromOff.set(w.call("modify_mob", com.google.gson.JsonParser.parseString(
                        "{\"scope\":\"group\",\"target\":\"pig\",\"radius\":8,\"name\":\"Off\"}").getAsJsonObject()));
            } catch (Throwable e) {
                err.set(e);
            } finally {
                done.set(true);
            }
        }, "gametest-smallbase");
        t.setDaemon(true);
        t.start();
        // Judged ONCE, when both calls are done: the pigs are taken away as it is judged, so a poll that ran it
        // again would read their absence instead of the verdict.
        java.util.concurrent.atomic.AtomicReference<String> verdict = new java.util.concurrent.atomic.AtomicReference<>();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get(), Component.literal("the calls never finished"));
            if (verdict.get() == null) {
                verdict.set(judge(helper, him, bases, tag, inside.get(), fromOff.get(), err.get()));
                WorldModuleHost.forTest(null);
                WorldRulesService.forTest(null);
                MockPlayerNetwork.remove(him);
            }
            helper.assertTrue(verdict.get().isEmpty(), Component.literal(verdict.get()));
        });
    }

    /** "" when the search changed only the pig on his base and the call from off it was refused; else what went wrong. */
    private static String judge(GameTestHelper helper, ServerPlayer him, Bases bases, String tag, String[] inside,
                                String[] fromOff, Throwable err) {
        List<net.minecraft.world.entity.animal.pig.Pig> pigs = helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.animal.pig.Pig.class, him.getBoundingBox().inflate(48), p -> p.entityTags().contains(tag));
        String seen = pigs.stream().map(p -> p.blockPosition().toShortString() + "="
                + (p.hasCustomName() ? p.getCustomName().getString() : "-")).toList().toString();
        pigs.forEach(net.minecraft.world.entity.Entity::discard);
        if (err != null) {
            return "the rig threw: " + err;
        }
        if (pigs.size() != 2) {
            return "want the two pigs, saw " + seen;
        }
        if (Boolean.parseBoolean(inside[0])) {
            return "modify_mob radius 64 on his base was refused: " + inside[1];
        }
        Base base = bases.baseOf(him.getUUID());
        for (var p : pigs) {
            boolean on = base.area().contains(p.getX(), p.getZ());
            boolean renamed = p.hasCustomName() && p.getCustomName().getString().equals("Changed");
            if (on != renamed) {
                return (on ? "the pig ON his base was not changed: " : "a pig OFF his base was changed: ") + seen
                        + " " + inside[1];
            }
        }
        if (!Boolean.parseBoolean(fromOff[0]) || !fromOff[1].contains("NOT HIS BASE")) {
            return "modify_mob from off his base was not refused in the rule's words: " + fromOff[1];
        }
        return "";
    }

    /** A wish of his on a protocol with modify_mob, judging with {@code world}/{@code rules}. */
    private static ScopedPacksGameTest.Wish wish(ServerPlayer him, Bases world, WorldRules rules) {
        McpProtocol p = new McpProtocol(new com.moddingfromamod.agent.ToolRegistry().add(ScopedPacksGameTest.planStub())
                .add(new com.moddingfromamod.agent.tools.ModifyMobTool()), new McpSessions(),
                com.moddingfromamod.marshal.Marshal.global(), () -> false, () -> world, () -> rules);
        String session = p.handlePost(ScopedPacksGameTest.headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        UUID id = UUID.randomUUID();
        com.moddingfromamod.marshal.Marshal.global().submit(id, him.getUUID(), "name the pigs near me Changed");
        com.moddingfromamod.marshal.Marshal.global().assign(session, id);
        p.handlePost(ScopedPacksGameTest.headers(session), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"plan_wish\",\"arguments\":{}}}", 0L);
        return new ScopedPacksGameTest.Wish(p, session, id);
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (SmallBaseCreatureGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
