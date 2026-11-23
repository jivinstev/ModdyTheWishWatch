package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentSession;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The hard-coded built-in wishes are gone, on every lane</b>.
 *
 * <p>{@code WishCatalog} used to ship ~18 templates ({@code super_fast}, {@code night_spooky},
 * {@code diamonds}, {@code tower} …) that answered a typed wish with a fixed list of commands. They
 * were untuned, and the loose no-agent lane matched them on SUBSTRINGS ("breakfast" was
 * {@code super_fast}). This drives the real {@link WishService#submit} with the words they used to
 * catch, on both sides of the agent seam:
 *
 * <ul>
 *   <li><b>an agent can answer</b> → every one is PENDING_ESCALATION, no template, on the queue;</li>
 *   <li><b>no agent anywhere</b> → the loose lane runs, and still no built-in answers.</li>
 * </ul>
 *
 * <p>And the control that keeps the first arm honest: a STARTER phrase is still served instantly
 * from the library, so a router that had stopped matching altogether would fail here by name.
 *
 * <p>One batch per test: {@link AgentSession#answerableForTest} is process-wide. A fresh mock child
 * per phrase, because the rate limiter allows one child five wishes in six seconds.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuiltInPresetsRetiredGameTest {

    /** Each one was a built-in's own wish text (its {@code phrase} or retired button). */
    private static final List<String> OLD_PRESET_WORDS = List.of(
            "make me super fast",
            "make it night and spooky",
            "give me diamonds",
            "give me treasure",
            "build me a tower",
            "make me fly",
            "spawn some zombies",
            "give me cats",
            "rainbow sheep",
            "make it sunny",
            "cake party",
            "build me a rock crusher machine",
            "build me a factory",
            "build me a windmill with sails",
            "build me a conveyor belt",
            "build me a spinning carousel",
            "build me a water wheel mill");

    private static ServerPlayer child(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    private static Wish newest(GameTestHelper helper, ServerPlayer p, String text) {
        for (Wish w : WishBook.get(helper.getLevel().getServer()).recent(80)) {
            if (w.text().equals(text) && p.getUUID().equals(w.playerId())) {
                return w;
            }
        }
        return null;
    }

    private static void retire(GameTestHelper helper, Wish w) {
        if (w != null) {
            WishBook.get(helper.getLevel().getServer()).complete(w.id(), WishStatus.REJECTED,
                    "gate fixture, never granted");
        }
    }

    /**
     * The headline: with an agent to hear it, no old preset's words are answered by a template.
     * 🔴 Seen RED on the tree before the deletion (this class alone, on {@code 501d2a0a7}): all 17
     * FULFILLING by their built-in, e.g. 'make me super fast' → {@code super_fast}; so was the
     * no-agent arm. The starter arm passed on both trees.
     */
    @GameTest(template = "empty_test", batch = "i1411_retired_wakeable", timeoutTicks = 400)
    public static void withAnAgentEveryOldPresetWishReachesTheAgent(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        List<String> wrong = new ArrayList<>();
        List<ServerPlayer> kids = new ArrayList<>();
        try {
            AgentSession.answerableForTest(true);
            for (String words : OLD_PRESET_WORDS) {
                ServerPlayer p = child(helper);
                kids.add(p);
                WishService.Outcome outcome = WishService.submit(p, words, WishSource.TYPED);
                Wish w = newest(helper, p, words);
                boolean queued = w != null && WishQueue.global().peekRest(128).stream()
                        .anyMatch(q -> q.id().equals(w.id()));
                if (outcome != WishService.Outcome.PENDING_ESCALATION || w == null
                        || (w.templateId() != null && !w.templateId().isBlank()) || !queued) {
                    wrong.add("'" + words + "' → " + outcome + ", template '"
                            + (w == null ? "<no wish>" : w.templateId()) + "', queued=" + queued);
                }
                retire(helper, w);
            }
        } finally {
            AgentSession.answerableForTest(null);
            WishQueue.global().clear();
            kids.forEach(MockPlayerNetwork::remove);
        }
        helper.assertTrue(wrong.isEmpty(), Component.literal("an old preset's words did not reach the agent:\n  "
                + String.join("\n  ", wrong)));
        helper.succeed();
    }

    /** The loose lane still runs with no agent; it simply has no built-in left to guess. */
    @GameTest(template = "empty_test", batch = "i1411_retired_no_agent", timeoutTicks = 400)
    public static void withNoAgentNoOldPresetAnswers(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        List<String> wrong = new ArrayList<>();
        List<ServerPlayer> kids = new ArrayList<>();
        try {
            AgentSession.answerableForTest(false);
            for (String words : OLD_PRESET_WORDS) {
                ServerPlayer p = child(helper);
                kids.add(p);
                WishService.Outcome outcome = WishService.submit(p, words, WishSource.TYPED);
                Wish w = newest(helper, p, words);
                if (outcome == WishService.Outcome.FULFILLING
                        || (w != null && w.templateId() != null && !w.templateId().isBlank())) {
                    wrong.add("'" + words + "' → " + outcome + ", template '"
                            + (w == null ? "<no wish>" : w.templateId()) + "'");
                }
                retire(helper, w);
            }
        } finally {
            AgentSession.answerableForTest(null);
            WishQueue.global().clear();
            kids.forEach(MockPlayerNetwork::remove);
        }
        helper.assertTrue(wrong.isEmpty(), Component.literal("a template answered an old preset's words with no "
                + "agent:\n  " + String.join("\n  ", wrong)));
        helper.succeed();
    }

    /**
     * The starter library is untouched: its own words are still an instant answer, with an agent
     * available — so this is the confident lane, not the no-agent fallback. The child stands in a far
     * lane of his own, because the answer really runs and rains chickens.
     */
    @GameTest(template = "empty_test", batch = "i1411_retired_starter", timeoutTicks = 400)
    public static void aStarterPhraseIsStillInstant(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerLevel level = helper.getLevel();
        int x = 95_600;
        int z = 112_000;
        level.setChunkForced(x >> 4, z >> 4, true);
        String words = "make it rain chickens";
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        WishService.Outcome outcome;
        Wish w;
        try {
            AgentSession.answerableForTest(true);
            BlockPos ground = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos(x, 0, z));
            p.snapTo(x + 0.5, ground.getY() + 1, z + 0.5, 0f, 10f);
            outcome = WishService.submit(p, words, WishSource.TYPED);
            w = newest(helper, p, words);
        } finally {
            AgentSession.answerableForTest(null);
            WishQueue.global().clear();
        }
        helper.assertValueEqual(outcome, WishService.Outcome.FULFILLING,
                Component.literal("a starter's own words must be served instantly"));
        helper.assertTrue(w != null, Component.literal("the wish was not recorded at all"));
        helper.assertValueEqual(w.templateId(), "starter_chicken_rain", Component.literal("the starter that served it"));
        MockPlayerNetwork.remove(p);
        helper.succeed();
    }
}
