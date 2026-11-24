package com.moddingfromamod.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.CommandPlan;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A wish with no agent that a world rule refused tells him the rule's words</b>, where it RUNS: the player's chat
 * after a Builder make, an Apprentice make and a replayed wish, each refused by one of this world's rules. The Magician's
 * agent reads a refusal and explains it; these have nobody to, and he used to read only "Your Pal didn't work this
 * time. (some bits didn't work)".
 *
 * <ul>
 *   <li>{@code base_reach}: the make is aimed with his base 60 blocks away (as {@code SmallBaseCreatureGameTest}'s
 *       refused arm), through the real {@link BuilderMake} and {@link McpProtocol} {@code tools/call};</li>
 *   <li>{@code deny_world_effect}: a Builder RULE make lowers to {@code set_rule}, which changes the whole world and is
 *       refused on a world that denies world-wide standing rules and allows no scoped ones;</li>
 *   <li>a replay: {@link WishService#submitReplay} ({@code /moddy try}, the path a watch press runs) of a teleport off
 *       his base, refused at {@code WishExecutor.runOne}.</li>
 * </ul>
 *
 * The bases and rules are globals ({@link WorldModuleHost#forTest}, {@link WorldRulesService#forTest}), so each test has
 * a batch of its own. The pure parts are {@code WorldRefusalTest}'s.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WorldRuleWordsGameTest {

    private WorldRuleWordsGameTest() {
    }

    /** The words each rule's message says, so his chat can be searched for exactly them. */
    static final String PLACE_RULE = "a wish stays on your island";
    static final String PLACE_WHY = "so every island stays its owner's";
    static final String WORLD_RULE = "a rule here can't change the whole world";
    static final String WORLD_WHY = "every island shares it";

    private static final String RULES = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
            + "{\"id\":\"no-world-rules\",\"type\":\"deny_world_effect\",\"effects\":[\"worldwide_datapacks_standing_rules\"],"
            + "\"message\":{\"rule\":\"A rule here can't change the whole world\",\"why\":\"" + WORLD_WHY + "\"}},"
            + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\",\"others_bases\","
            + "\"other_dimensions\"],\"message\":{\"rule\":\"A wish stays on your island\",\"why\":\"" + PLACE_WHY + "\"}}]}";

    private static final String PAL = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,"
            + "\"temper\":\"pet\",\"name\":\"NAME\"}";

    private static final String JUMP_FIREWORKS = "{\"builder\":1,\"kind\":\"rule\",\"from\":\"\","
            + "\"opts\":{\"when\":\"when:jumps\",\"does\":\"rule:fireworks\"}}";

    static WorldRules rules() {
        WorldRules.Parsed p = WorldRules.parse(RULES, "closed");
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.rules();
    }

    @GameTest(template = "empty_test", batch = "worldrulewords_builder", timeoutTicks = 12000)
    public static void aBuilderMakeAimedOffHisBaseTellsHimTheRule(GameTestHelper helper) {
        ServerPlayer child = child(helper, "gtrulewordsb", 60);
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(PAL.replace("NAME", "Pal Far Away")).getAsJsonObject());
        CompletableFuture<BuilderMake.Result> made = make(helper, () -> BuilderMake.run(helper.getLevel().getServer(),
                BuilderMake.Who.of(child), spec, null, protocol(), false, false));
        expect(helper, child, made, "Your Pal Far Away can't go there: " + PLACE_RULE + " — " + PLACE_WHY
                + ". Aim it at your own base and wish again!");
    }

    @GameTest(template = "empty_test", batch = "worldrulewords_apprentice", timeoutTicks = 12000)
    public static void anApprenticeMakeAimedOffHisBaseTellsHimTheRule(GameTestHelper helper) {
        ServerPlayer child = child(helper, "gtrulewordsa", 60);
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(PAL.replace("NAME", "Pal Too Far")).getAsJsonObject());
        CompletableFuture<BuilderMake.Result> made = make(helper, () -> BuilderMake.runApprentice(
                helper.getLevel().getServer(), BuilderMake.Who.of(child), spec, null, protocol(), false, false));
        expect(helper, child, made, "Your Pal Too Far can't go there: " + PLACE_RULE + " — " + PLACE_WHY + ".");
    }

    @GameTest(template = "empty_test", batch = "worldrulewords_deny", timeoutTicks = 12000)
    public static void aBuilderRuleOnAWorldThatDeniesWorldRulesTellsHimTheRule(GameTestHelper helper) {
        ServerPlayer child = child(helper, "gtrulewordsd", 0);   // standing on his base: only the effect is refused
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(JUMP_FIREWORKS).getAsJsonObject());
        CompletableFuture<BuilderMake.Result> made = make(helper, () -> BuilderMake.run(helper.getLevel().getServer(),
                BuilderMake.Who.of(child), spec, null, protocol(), false, false));
        expect(helper, child, made, "can't work on this world: " + WORLD_RULE + " — " + WORLD_WHY + ". It works in "
                + rules().workshop() + "!");
    }

    @GameTest(template = "empty_test", batch = "worldrulewords_replay", timeoutTicks = 12000)
    public static void aReplayedWishOffHisBaseTellsHimTheRule(GameTestHelper helper) {
        ServerPlayer child = child(helper, "gtrulewordsr", 0);
        MinecraftServer server = helper.getLevel().getServer();
        String far = "tp @s " + (child.getBlockX() + 5000) + " 100 " + child.getBlockZ();
        double x0 = child.getX();
        CompletableFuture<WishService.Outcome> sent = new CompletableFuture<>();
        server.execute(() -> sent.complete(WishService.submitReplay(child, "take me to the far hills",
                CommandPlan.of(List.of(far), "Off you go!"))));
        String want = "That wish can't go there: " + PLACE_RULE + " — " + PLACE_WHY + ".";
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(sent.isDone(), Component.literal("still submitting"));
            helper.assertTrue(sent.join() == WishService.Outcome.FULFILLING,
                    Component.literal("the replay was not served: " + sent.join()));
            List<String> chat = MockPlayerNetwork.chat(child);
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains(want)),
                    Component.literal("his chat does not say the rule (want '" + want + "'): " + chat));
            helper.assertTrue(Math.abs(child.getX() - x0) < 100,
                    Component.literal("he was moved off his base: " + child.getX()));
            assertSaidOnce(helper, chat, want);
        });
    }

    // ── the rig ──────────────────────────────────────────────────────────────────────────────────

    /** A stand-in child with his base {@code dz} blocks south and this class's rules pinned, undone after the test. */
    private static ServerPlayer child(GameTestHelper helper, String name, int dz) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, name);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        child.setYRot(0);
        child.setXRot(25);
        MockPlayerNetwork.actingChildFor(helper, child);
        WorldModuleHost.forTest(SmallBaseCreatureGameTest.arenaBaseAround(child, dz));
        WorldRulesService.forTest(rules());
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> {
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
            BuilderMake.riseMillis = rise;
        });
        return child;
    }

    private static CompletableFuture<BuilderMake.Result> make(GameTestHelper helper,
                                                             java.util.function.Supplier<CompletableFuture<BuilderMake.Result>> go) {
        CompletableFuture<BuilderMake.Result> made = new CompletableFuture<>();
        helper.getLevel().getServer().execute(() -> go.get().whenComplete((r, e) -> {
            if (e != null) {
                made.completeExceptionally(e);
            } else {
                made.complete(r);
            }
        }));
        return made;
    }

    /** Refused, nothing made, and his chat says {@code want} once, in place of "(some bits didn't work)". */
    private static void expect(GameTestHelper helper, ServerPlayer child, CompletableFuture<BuilderMake.Result> made,
                               String want) {
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(made.isDone(), Component.literal("still making"));
            helper.assertFalse(made.isCompletedExceptionally(), Component.literal("the make threw"));
            BuilderMake.Result r = made.join();
            helper.assertTrue(r.outcome() == BuilderMake.Outcome.FAILED,
                    Component.literal("the world's rule must still refuse it: " + r.outcome() + " " + r.calls()
                            + " — " + r.detail()));
            List<String> chat = MockPlayerNetwork.chat(child);
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains(want)),
                    Component.literal("his chat does not say the rule (want '" + want + "'): " + chat));
            assertSaidOnce(helper, chat, want);
        });
    }

    private static void assertSaidOnce(GameTestHelper helper, List<String> chat, String want) {
        helper.assertTrue(chat.stream().filter(l -> l.contains(want)).count() == 1,
                Component.literal("the rule is said once: " + chat));
        helper.assertFalse(chat.stream().anyMatch(l -> l.contains("some bits didn't work")),
                Component.literal("the generic line is said as well as the rule: " + chat));
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (WorldRuleWordsGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
