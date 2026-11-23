package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.item.Locate;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.world.WorldRules;
import com.moddingfromamod.world.WorldRulesService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Another player is a place to go, never a subject</b> — on the real paths.
 *
 * <p>Alice and Bob are both on. On a world whose {@code player_reach} is {@code open},
 * {@code run_command tp @s Bob} takes Alice to Bob and Bob is told she came to visit; a second visit
 * inside the cooldown is turned away even when it arrives as a bare function line (the live
 * {@code player_tracker} orb's exact shape, which no Java of ours sees); and moving Bob, or TNT at
 * his feet, is refused. On a world where it is {@code off} the visit is refused by the
 * tool AND by the teleport itself, the datapack compiler refuses the orb, and the compass will not
 * point at him. Seen red on {@code main}: {@code tp @s Bob} was refused as {@code names_another_child}
 * on every world, and the orb's line ran wherever it was pressed.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PlayerReachGameTest {

    private static final int TIMEOUT_TICKS = 4_000;

    private PlayerReachGameTest() {
    }

    private static WorldRules rules(String mode) {
        return WorldRules.parse("{\"store\":\"world-rules\",\"v\":1,\"world\":\"gt\",\"rules\":[{\"id\":\"r\","
                + "\"type\":\"player_reach\",\"params\":{\"mode\":\"" + mode + "\"}}]}", "gt").rules();
    }

    /**
     * Pin the world's rule for this test and put it back however the test ends. The rules are a
     * process-wide static, so every test here has a batch of its OWN: sharing one, the test that
     * finished first put the rules back while another's worker was still asking them (seen red:
     * "take me to Bob is a visit, allowed here: REFUSED").
     */
    private static void world(GameTestHelper helper, String mode) {
        WorldRulesService.forTest(rules(mode));
        VisitCooldown.SHARED.clear();
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                WorldRulesService.forTest(null);
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                WorldRulesService.forTest(null);
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                WorldRulesService.forTest(null);
            }
        });
    }

    private static ServerPlayer at(GameTestHelper helper, String name, int x, int z) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        BlockPos feet = helper.absolutePos(new BlockPos(x, 2, z));
        p.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        return p;
    }

    private static JsonObject cmd(String command) {
        JsonObject o = new JsonObject();
        o.addProperty("command", command);
        return o;
    }

    /** Run these as Alice's wish, on a worker thread as the agent does; the results in order. */
    private static AtomicReference<ToolResult[]> asHerWish(ServerPlayer alice, String... commands) {
        AtomicReference<ToolResult[]> out = new AtomicReference<>();
        var wish = java.util.UUID.randomUUID();
        String worker = "gt-playerreach-" + wish;
        Marshal.global().submit(wish, alice.getUUID(), "gt player reach");
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                ToolResult[] rs = new ToolResult[commands.length];
                for (int i = 0; i < commands.length; i++) {
                    rs[i] = new RunCommandTool().call(cmd(commands[i]));
                }
                out.set(rs);
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-playerreach");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** A line as a datapack function would run it: the server's own source, no agent, no tool. */
    private static void asAFunction(GameTestHelper helper, String line) {
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), line);
    }

    private static boolean sameSpot(ServerPlayer a, ServerPlayer b) {
        return a.position().distanceToSqr(b.position()) < 1e-4;
    }

    private static boolean saidTo(ServerPlayer p, String words) {
        return MockPlayerNetwork.chat(p).stream().anyMatch(s -> s.contains(words));
    }

    @GameTest(template = "empty_test", batch = "playerReachVisit", timeoutTicks = TIMEOUT_TICKS)
    public static void onAnOpenWorldAChildMayVisitAndOnlyVisit(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        world(helper, "open");
        ServerPlayer alice = at(helper, "Alice", 1, 1);
        ServerPlayer bob = at(helper, "Bob", 3, 3);
        Vec3 bobWas = bob.position();
        var rs = asHerWish(alice, "tp @s Bob", "tp Bob @s", "execute at Bob run summon minecraft:tnt ~ ~ ~");
        boolean[] secondDone = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(rs.get() != null, Component.literal("waiting for the three commands"));
            ToolResult[] r = rs.get();
            helper.assertTrue(!r[0].isError(), Component.literal("take me to Bob is a visit, allowed here: " + r[0].text()));
            helper.assertTrue(r[1].isError(), Component.literal("moving Bob is refused: " + r[1].text()));
            helper.assertTrue(r[2].isError(), Component.literal("TNT at Bob's feet is refused: " + r[2].text()));
            helper.assertTrue(bob.position().distanceToSqr(bobWas) < 1e-4, Component.literal("Bob was moved: " + bob.position()));
            helper.assertTrue(helper.getLevel().getEntities(EntityType.TNT,
                    new AABB(bob.blockPosition()).inflate(4), e -> true).isEmpty(), Component.literal("TNT was put by Bob"));
            helper.assertTrue(saidTo(bob, "Alice came to visit!"), Component.literal("Bob was not told: " + MockPlayerNetwork.chat(bob)));
            if (!secondDone[0]) {
                helper.assertTrue(sameSpot(alice, bob), Component.literal("Alice did not arrive at Bob: " + alice.position()));
                // Back home, then the orb's exact line as a FUNCTION runs it — inside the cooldown.
                BlockPos home = helper.absolutePos(new BlockPos(1, 2, 1));
                alice.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                asAFunction(helper, "execute as Alice at Bob run tp @s ~ ~ ~");
                secondDone[0] = true;
            }
            helper.assertTrue(!sameSpot(alice, bob), Component.literal("a second visit inside the cooldown went ahead"));
            helper.assertTrue(saidTo(alice, "You just visited Bob"), Component.literal("Alice was not told why: "
                    + MockPlayerNetwork.chat(alice)));
            // A template's button replays through runOne with no agent: the same rule, as the presser.
            var replay = WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(),
                    alice.position(), alice, "give Bob minecraft:diamond 1");
            helper.assertTrue(!replay.success(), Component.literal("a replay gave Bob a diamond: " + replay.joined()));
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        });
    }

    @GameTest(template = "empty_test", batch = "playerReachCompass", timeoutTicks = TIMEOUT_TICKS)
    public static void onAnOpenWorldTheCompassPointsAtHim(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        world(helper, "open");
        ServerPlayer alice = at(helper, "Alice", 1, 1);
        ServerPlayer bob = at(helper, "Bob", 4, 4);
        Vec3 bobWas = bob.position();
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
        ServerPlayer found = Locate.askPlayer(alice, "Bob");
        var lt = alice.getMainHandItem().get(DataComponents.LODESTONE_TRACKER);
        helper.assertTrue(found == bob, Component.literal("the compass found " + found));
        helper.assertTrue(lt != null && lt.target().isPresent() && lt.target().get().pos().equals(bob.blockPosition()),
                Component.literal("the compass does not point at Bob: " + lt));
        helper.assertTrue(bob.position().distanceToSqr(bobWas) < 1e-4, Component.literal("reading his position moved him"));
        helper.assertTrue(saidTo(alice, "This way to Bob"), Component.literal("Alice was not told the way: " + MockPlayerNetwork.chat(alice)));
        MockPlayerNetwork.remove(alice);
        MockPlayerNetwork.remove(bob);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "playerReachCompile", timeoutTicks = TIMEOUT_TICKS)
    public static void theCompilerTakesTheVisitAndRefusesTheSubject(GameTestHelper helper) {
        world(helper, "open");
        if (!CbScriptCompiler.available()) {
            helper.succeed();   // no compiler here: DatapackReachTest covers the reader in Gate A
            return;
        }
        helper.assertTrue(compile("gt_reach_visit", "/execute at Bob run tp @s ~ ~ ~").ok(),
                Component.literal("the orb's shape is a visit, which an open world allows"));
        CompileResult bring = compile("gt_reach_bring", "/tp Bob @s");
        helper.assertTrue(!bring.ok() && bring.error().contains("NOT COMPILED"),
                Component.literal("moving Bob compiled: " + bring.error()));
        CompileResult gift = compile("gt_reach_gift", "/give Bob minecraft:diamond 1");
        helper.assertTrue(!gift.ok(), Component.literal("a gift to Bob compiled"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "playerReachOff", timeoutTicks = TIMEOUT_TICKS)
    public static void onAClosedWorldAnotherPlayerIsNeverAPlaceToGo(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        world(helper, "off");
        ServerPlayer alice = at(helper, "Alice", 1, 1);
        ServerPlayer bob = at(helper, "Bob", 3, 3);
        Vec3 aliceWas = alice.position();
        var rs = asHerWish(alice, "tp @s Bob");
        boolean[] orbDone = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(rs.get() != null, Component.literal("waiting for the command"));
            ToolResult r = rs.get()[0];
            helper.assertTrue(r.isError() && r.text().contains("visiting is something they can do in"),
                    Component.literal("the visit was not refused kindly: " + r.text()));
            if (!orbDone[0]) {
                // the live orb, compiled before the rule, run as a function: the teleport says no
                asAFunction(helper, "execute as Alice at Bob run tp @s ~ ~ ~");
                orbDone[0] = true;
            }
            helper.assertTrue(alice.position().distanceToSqr(aliceWas) < 1e-4, Component.literal("Alice went to Bob: " + alice.position()));
            helper.assertTrue(saidTo(alice, "visiting is for"), Component.literal("Alice was not told: " + MockPlayerNetwork.chat(alice)));
            helper.assertTrue(!saidTo(bob, "came to visit"), Component.literal("Bob was told of a visit that did not happen"));
            alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
            helper.assertTrue(Locate.askPlayer(alice, "Bob") == null
                            && alice.getMainHandItem().get(DataComponents.LODESTONE_TRACKER) == null,
                    Component.literal("the compass pointed at Bob on a closed world"));
            var replay = WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(),
                    alice.position(), alice, "tp @s Bob");
            helper.assertTrue(!replay.success(), Component.literal("a button took Alice to Bob on a closed world: " + replay.joined()));
            if (CbScriptCompiler.available()) {
                CompileResult orb = compile("gt_reach_orb", "/execute at Bob run tp @s ~ ~ ~");
                helper.assertTrue(!orb.ok() && orb.error().contains("NOT COMPILED"),
                        Component.literal("the orb compiled on a closed world: " + orb.error()));
            }
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        });
    }

    private static CompileResult compile(String id, String line) {
        try {
            Path tmp = Files.createTempDirectory("gt_reach_");
            String source = "function orb()\n    " + line + "\nend\n";
            return CbScriptCompiler.compile(tmp, id, "gametest", source, tmp.resolve(id + ".zip"));
        } catch (Exception e) {
            return CompileResult.failure(id, e.toString(), java.util.List.of());
        }
    }
}
