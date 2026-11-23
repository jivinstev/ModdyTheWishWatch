package com.moddingfromamod.world;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a button pressed on a world with rules is held to that world's rules.</b>
 *
 * <p>The agent's commands are judged at {@code McpProtocol}'s {@code tools/call}
 * ({@code IslandReachGameTest}); a button, a template or a standing rule replays through
 * {@link WishExecutor#runOne} with no agent, and a child's buttons follow him between worlds. Driven
 * through that real entry point with an island world's kind of rules pinned and his base around him; the
 * same button with no rules runs (the control).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReplayWorldRulesGameTest {

    private static final String RULES = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
            + "{\"id\":\"same-time-for-all\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],"
            + "\"message\":{\"rule\":\"time is the same for every island\",\"why\":\"so the sun rises for everyone together\"}},"
            + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\",\"others_bases\","
            + "\"other_dimensions\"],\"message\":{\"rule\":\"a wish stays on your own island\",\"why\":\"every island "
            + "belongs to the player who lives there\"}}]}";

    @GameTest(template = "empty_test", batch = "replayworldrules", timeoutTicks = 100)
    public static void aButtonPressedOnAClosedWorldKeepsToItsRules(GameTestHelper helper) {
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-replay-closed");
        var server = helper.getLevel().getServer();
        int x = him.getBlockX();
        int z = him.getBlockZ();
        UUID owner = him.getUUID();
        Base base = new Base(owner, Bases.OVERWORLD, x, him.getBlockY(), z, him.getBlockY(),
                new Region(x - 48, x + 48, z - 48, z + 48), new Region(x - 40, x + 40, z - 40, z + 40));
        Bases world = new Bases() {
            @Override public String noun() {
                return "island";
            }

            @Override public Base baseOf(UUID player) {
                return owner.equals(player) ? base : null;
            }

            @Override public UUID ownerAt(String dimension, double px, double pz) {
                return base.area().contains(px, pz) ? owner : null;
            }

            @Override public Bases.Pos positionOf(UUID player) {
                return new Bases.Pos(Bases.OVERWORLD, him.getX(), him.getY(), him.getZ());
            }
        };
        WorldRules.Parsed parsed = WorldRules.parse(RULES, "closed");
        helper.assertTrue(parsed.ok(), Component.literal("rules: " + parsed.errors()));
        long dayBefore = helper.getLevel().getDayTime();
        try {
            WorldModuleHost.forTest(world);
            WorldRulesService.forTest(parsed.rules());

            var time = WishExecutor.runOne(server, helper.getLevel(), him.position(), him, "time set 18000");
            helper.assertTrue(!time.success() && time.joined().contains("time is the same for every island"),
                    Component.literal("a time button on an island world must be refused in the rule's words: " + time.joined()));
            var far = WishExecutor.runOne(server, helper.getLevel(), him.position(), him,
                    "tp @s " + (x + 5000) + " 100 " + z);
            helper.assertTrue(!far.success() && far.joined().contains("a wish stays on your own island"),
                    Component.literal("a button that takes him off his island must be refused: " + far.joined()));
            helper.assertTrue(Math.abs(him.getX() - x) < 100, Component.literal("he was moved off his island: " + him.getX()));
            var near = WishExecutor.runOne(server, helper.getLevel(), him.position(), him,
                    "particle minecraft:heart ~ ~1 ~ 0.5 0.5 0.5 0 5");
            helper.assertTrue(near.success(), Component.literal("a button on his own island must run: " + near.joined()));
            // A standing rule that fires with nobody near (no player to run as): it is the rule of the
            // base it fires on; on nobody's base it is refused.
            var ruleHere = WishExecutor.runOne(server, helper.getLevel(), him.position(), null,
                    "particle minecraft:heart ~ ~1 ~ 0.5 0.5 0.5 0 5");
            helper.assertTrue(ruleHere.success(), Component.literal("a rule firing on his island runs: " + ruleHere.joined()));
            var ruleAway = WishExecutor.runOne(server, helper.getLevel(), him.position().add(5000, 0, 0), null,
                    "particle minecraft:heart ~ ~1 ~ 0.5 0.5 0.5 0 5");
            helper.assertTrue(!ruleAway.success() && ruleAway.joined().contains("a wish stays on your own island"),
                    Component.literal("a rule firing on nobody's island is refused: " + ruleAway.joined()));
        } finally {
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
        }
        // The control: no rules and no bases — the same button runs.
        var control = WishExecutor.runOne(server, helper.getLevel(), him.position(), him, "time set " + dayBefore);
        helper.assertTrue(control.success(), Component.literal("with no world rules the same button runs: " + control.joined()));
        MockPlayerNetwork.remove(him);
        helper.succeed();
    }
}
