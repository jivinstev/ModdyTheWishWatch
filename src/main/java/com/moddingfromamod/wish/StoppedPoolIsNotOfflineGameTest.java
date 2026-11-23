package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentSession;
import com.moddingfromamod.agent.WishQueue;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A stopped pool is not "no agent"</b>.
 *
 * <p>The offline matcher's loose lane is for a server that cannot reach an agent at all. It used to
 * fire whenever no pool was RUNNING, which is also true after a twenty-minute idle reap and on a
 * dedicated server before anyone has joined — both of which the routed wish wakes itself.
 * So a wish for fast boats on ice, with the pool merely stopped, was served as the {@code super_fast}
 * template: a speed effect on the child, never the agent. Found 's real grants, 2 of 2.
 *
 * <p>Driven through the real {@code WishService.submit}. A gate can launch no pool, so the
 * answer "would a wish reach an agent" is set at {@link AgentSession#answerableForTest}; everything
 * after it is the shipped path. Both arms, so the test cannot pass against a router that never
 * matches at all: with no agent anywhere the same words still take the loose lane.
 *
 * <p>The template the loose lane finds is this test's own learned {@code fast} recipe. It was the
 * built-in {@code super_fast} until the built-ins were deleted; a learned
 * template is what the loose lane can still reach, so the control arm proves the lane itself, not
 * a particular catalogue (decides the lane's future).
 *
 * <p>One batch per test: the seam is process-wide.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StoppedPoolIsNotOfflineGameTest {

    private static final String BOATS = "make boats go super fast on ice";

    /** Matched loosely by BOATS ("fast"), declined by the confident lane ("boats", "ice"). */
    private static final String FAST_ID = "gt_i1411_fast";

    private static java.util.List<String> teachFast(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        java.util.List<String> saved = LearnedTemplates.savedJsonForTest();
        LearnedTemplates.addAndSave(server, com.google.gson.JsonParser.parseString(
                "{\"id\":\"" + FAST_ID + "\",\"phrase\":\"make me fast\",\"all\":[\"fast\"],"
                        + "\"commands\":[\"effect give @p minecraft:speed 5 0\"],"
                        + "\"say\":\"Zoom!\"}").getAsJsonObject());
        return saved;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** This child's wish: both arms type the same words, and the book outlives a batch. */
    private static Wish newest(GameTestHelper helper, ServerPlayer p, String text) {
        for (Wish w : WishBook.get(helper.getLevel().getServer()).recent(40)) {
            if (w.text().equals(text) && p.getUUID().equals(w.playerId())) {
                return w;
            }
        }
        return null;
    }

    /** 🔴 Seen RED with {@code route} asking {@code isRunning()} again: FULFILLING as 'super_fast'
     *  (then a built-in; now the test's own learned {@code fast} recipe). */
    @GameTest(template = "empty_test", batch = "i1411_stopped_pool")
    public static void aStoppedPoolHearsTheWishInsteadOfAKeyword(GameTestHelper helper) {
        WishService.Outcome outcome;
        Wish w;
        boolean queued;
        java.util.List<String> saved = teachFast(helper);
        try {
            AgentSession.answerableForTest(true);
            ServerPlayer p = player(helper);
            outcome = WishService.submit(p, BOATS, WishSource.TYPED);
            w = newest(helper, p, BOATS);
            queued = w != null && WishQueue.global().peekRest(64).stream()
                    .anyMatch(q -> q.id().equals(w.id()));
        } finally {
            AgentSession.answerableForTest(null);
            LearnedTemplates.restoreForTest(saved, helper.getLevel().getServer());
        }
        if (w != null) {
            WishBook.get(helper.getLevel().getServer()).complete(w.id(), WishStatus.REJECTED,
                    "gate fixture, never granted");
        }
        WishQueue.global().clear();
        helper.assertValueEqual(outcome, WishService.Outcome.PENDING_ESCALATION,
                Component.literal("a wish with a stopped (wakeable) pool must go to the agent"));
        helper.assertTrue(w != null, Component.literal("the wish was not recorded at all"));
        helper.assertTrue(w.templateId() == null || w.templateId().isBlank(),
                Component.literal("a template claimed the agent's wish: '" + w.templateId() + "'"));
        helper.assertTrue(queued, Component.literal("the wish never reached the agent's queue"));
        helper.succeed();
    }

    /** The other arm: no agent can be woken at all, so the old best guess still answers. */
    @GameTest(template = "empty_test", batch = "i1411_no_agent")
    public static void withNoAgentAnywhereTheLooseLaneStillAnswers(GameTestHelper helper) {
        WishService.Outcome outcome;
        Wish w;
        java.util.List<String> saved = teachFast(helper);
        try {
            AgentSession.answerableForTest(false);
            ServerPlayer p = player(helper);
            outcome = WishService.submit(p, BOATS, WishSource.TYPED);
            w = newest(helper, p, BOATS);
        } finally {
            AgentSession.answerableForTest(null);
            LearnedTemplates.restoreForTest(saved, helper.getLevel().getServer());
        }
        WishQueue.global().clear();
        helper.assertValueEqual(outcome, WishService.Outcome.FULFILLING,
                Component.literal("with nothing to wake, the loose lane is the only answer"));
        helper.assertTrue(w != null, Component.literal("the wish was not recorded at all"));
        helper.assertValueEqual(w.templateId(), FAST_ID, Component.literal("the loose lane's template"));
        helper.succeed();
    }
}
