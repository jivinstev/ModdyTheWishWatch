package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the OTHER concurrency axis — <b>two children</b>, not two wishes.
 *
 * <p>{@code ConcurrentWishGameTest} covers one child with several wishes and is hardened. This
 * covers two children with one wish each, which nothing did: {@code grep} for a second player across
 * the whole tree returned one unrelated word match before this file existed.
 *
 * <h2>Why the bug survived, which is the reusable part</h2>
 *
 * <p>{@code WishProgress}'s tick loop renders only to players <b>waiting on a wish of their own</b>.
 * So a fixture with one child wishing and a second child merely standing there shows nothing and
 * passes — over a defect that is live the whole time. <b>Both children must be mid-wish at once</b>,
 * which is the actual family case and the one no fixture had ever built.
 *
 * <h2>Every assertion here is an identity, never an absence</h2>
 *
 * <p>"B saw nothing of A's wish" passes when the feature is dead in both directions. The claim is
 * <b>"B saw B's own wish, named as B's"</b> — which fails when the addressing breaks AND fails when
 * the narration stops working at all.
 *
 * <p><b>Own batch, and NO {@code clearStaleMocks}.</b> That sweep matches on the mock NAME, so it
 * deletes a concurrent sibling's players too — it has already indicted 17 innocent tests once, and
 * not one of the 17 messages mentioned a player. Batches are strictly sequential so the batch keeps
 * this file away from the rest of the suite; what the batch does NOT do is separate these three
 * tests from each other, and calling the sweep here made them clear each other's children
 * mid-flight. Measured: the narrator test then addressed a child who had been removed from the
 * player list and reported "Alice sees ''" — which reads exactly like the fix narrating to nobody.
 * Each test removes only the two children it created, in a finally.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TwoChildrenGameTest {

    private static final String BATCH = "twochildren";

    /** A distinct wish id per test, so two tests in this file cannot read each other's runs. */
    private static UUID wishFor(String testName, String who) {
        return UUID.nameUUIDFromBytes(("2kids-" + testName + "-" + who).getBytes());
    }

    /**
     * Put a child mid-wish: a run the marshal knows about, assigned to a worker so
     * {@code AgentContext.currentRun()} can resolve it, and a WAITING entry so the ticker would
     * render to them.
     *
     * @return the worker id to bind when speaking as this child's agent
     */
    private static String startWishing(ServerPlayer child, UUID wishId, String text) {
        Marshal m = Marshal.global();
        m.submit(wishId, child.getUUID(), text);
        String worker = "gt-worker-" + wishId;
        m.assign(worker, wishId);
        Wish wish = new Wish(wishId, text, WishSource.TYPED,
                WishStatus.RUNNING, System.currentTimeMillis(), child.getUUID(),
                child.getGameProfile().getName(), "minecraft:overworld", 0, 64, 0, "", "", 0L);
        // The WISH BOOK, not just the progress map. A wish the book does not know is a wish the
        // ticker treats as finished, and it is also the only place anything can look up WHOSE it
        // is — which is what the bar's ownership now depends on. A fixture that skips it measures
        // a wish the mod does not believe in.
        WishBook.get(child.getServer()).add(wish);
        WishProgress.started(child, wish);
        return worker;
    }

    /** Speak as one child's agent — the ThreadLocal binding the MCP dispatch site would have made. */
    private static void asAgentFor(String workerId, Runnable body) {
        AgentContext.bind(workerId);
        try {
            body.run();
        } finally {
            AgentContext.unbind();
        }
    }

    private static void cleanUp(UUID... wishIds) {
        for (UUID id : wishIds) {
            try {
                WishProgress.clearWishBar(id);
                Marshal.global().finish(id);
            } catch (RuntimeException ignored) {
                // best effort — a failed test must not poison the next one
            }
        }
    }

    /**
     * <b>THE GATE. Two children, both mid-wish, each narrating — each must see his OWN words.</b>
     *
     * <p>Verified to fail on the pre-fix code, where {@code SetStatusTool} looped the whole player
     * list: both children's status slots were overwritten by whichever agent spoke last, so one of
     * the two identity assertions below goes red whichever order they run in.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void eachChildSeesHisOwnStatusLine(GameTestHelper helper) {
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID aliceWish = wishFor("status", "alice");
        UUID bobWish = wishFor("status", "bob");
        String aliceWorker = startWishing(alice, aliceWish, "a purple castle");
        String bobWorker = startWishing(bob, bobWish, "a rocket ship");

        // OFF THE SERVER THREAD, because that is where a real tool call comes from and because
        // AgentBridge refuses a same-thread call outright ("that would deadlock"). The first cut
        // of this test drove the tool inline and failed with exactly that message — a harness
        // fault that reads like a broken tool. AgentBridge re-binds the worker across the hop, so
        // the run each call sees is the one bound here.
        AtomicReference<String> aliceSaid = new AtomicReference<>("(never ran)");
        AtomicReference<String> bobSaid = new AtomicReference<>("(never ran)");
        Thread agents = new Thread(() -> {
            asAgentFor(aliceWorker, () -> setStatus("Building the purple castle", aliceSaid));
            asAgentFor(bobWorker, () -> setStatus("Building the rocket ship", bobSaid));
        }, "gt-two-children");
        agents.setDaemon(true);
        agents.start();

        // The tool bodies are queued onto this very thread, so the test must RETURN for them to
        // run. Ticks are what lets that happen.
        long deadline = System.currentTimeMillis() + 20_000L;
        pollUntil(helper, deadline, 2400,
                () -> !WishProgress.currentStatus(alice.getUUID()).isEmpty()
                        && !WishProgress.currentStatus(bob.getUUID()).isEmpty(),
                () -> {
            try {
                String aliceSees = WishProgress.currentStatus(alice.getUUID());
                String bobSees = WishProgress.currentStatus(bob.getUUID());
                helper.assertTrue(aliceSees.contains("purple castle"),
                        Component.literal("Alice asked for a purple castle and her status line says '" + aliceSees
                                + "'. She must see HER wish — if this says 'rocket ship', her "
                                + "brother's agent wrote into her status slot. The tool said ["
                                + aliceSaid.get() + "]"));
                helper.assertTrue(bobSees.contains("rocket ship"),
                        Component.literal("Bob asked for a rocket ship and his status line says '" + bobSees
                                + "'. He must see HIS wish — this is the assertion that fails on "
                                + "the pre-fix code, where the last agent to speak overwrote both. "
                                + "The tool said [" + bobSaid.get() + "]"));
                helper.succeed();
            } finally {
                cleanUp(aliceWish, bobWish);
                MockPlayerNetwork.remove(alice);
                MockPlayerNetwork.remove(bob);
            }
        });
    }

    /**
     * The boss bar, opened by each child's own agent. Asserted as an identity so this is a claim
     * about B's bar rather than about A's absence: if the bars stopped opening at all,
     * {@code barCount == 1} fails for both children.
     *
     * <p>⚠ This test passed all along while production was broken, and that is worth knowing rather
     * than quietly fixing: it hands {@link WishProgress#startThinking} the RIGHT child, so it proves
     * the rule where it is DEFINED and says nothing about where it RUNS. The two real callers hand
     * over the wrong one. {@link #aBarOpenedByTheWrongChildStillBelongsToItsOwner} is the test that
     * covers that, and it is the one that fails on the pre-fix code.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void eachChildHoldsExactlyHisOwnProgressBar(GameTestHelper helper) {
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID aliceWish = wishFor("bars", "alice");
        UUID bobWish = wishFor("bars", "bob");
        try {
            startWishing(alice, aliceWish, "a purple castle");
            startWishing(bob, bobWish, "a rocket ship");
            WishProgress.startThinking(alice, aliceWish, "Purple Castle");
            WishProgress.startThinking(bob, bobWish, "Rocket Ship");

            helper.assertTrue(WishProgress.barCount(alice.getUUID()) == 1,
                    Component.literal("Alice must hold exactly her own bar, not her brother's too — she holds "
                            + WishProgress.barCount(alice.getUUID())));
            helper.assertTrue(WishProgress.barCount(bob.getUUID()) == 1,
                    Component.literal("Bob must hold exactly his own bar — he holds "
                            + WishProgress.barCount(bob.getUUID())));
            helper.succeed();
        } finally {
            cleanUp(aliceWish, bobWish);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        }
    }

    /**
     * <b>🔴 A bar opened by the WRONG child still belongs to its owner (D1).</b>
     *
     * <p>This is the production shape, not a hypothetical: {@code PlanWishTool:112} and
     * {@code ConjureAtTool:72} both resolve "the player" as
     * {@code getPlayers().stream().findFirst()} — <i>whoever joined first</i> — and hand that
     * player to the bar API. With one child those are the same person, which is why nothing has
     * ever caught it; with two, every wish's bar lands on the first child. It was found by
     * <b>looking at a photograph</b> from the first two-child session ever run: Alice's screen
     * carried her castle bar AND Bob's rocket bar, and Bob's screen carried none
     * (docs/MULTIPLAYER_DECISIONS.md §4c).
     *
     * <p>So the fixture deliberately does the wrong thing — it opens Bob's bar naming Alice — and
     * requires the bar to reach Bob anyway. <b>Verified to fail on the pre-fix code</b>, where
     * Alice held two bars and Bob held none.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aBarOpenedByTheWrongChildStillBelongsToItsOwner(GameTestHelper helper) {
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID aliceWish = wishFor("wrongcaller", "alice");
        UUID bobWish = wishFor("wrongcaller", "bob");
        try {
            startWishing(alice, aliceWish, "a purple castle");
            startWishing(bob, bobWish, "a rocket ship");

            // Alice's own bar, opened correctly.
            WishProgress.startThinking(alice, aliceWish, "Purple Castle");
            // BOB'S bar, opened by a caller that resolved the player as findFirst() and got Alice.
            WishProgress.startThinking(alice, bobWish, "Rocket Ship");

            helper.assertTrue(WishProgress.barCount(bob.getUUID()) == 1,
                    Component.literal("Bob's rocket bar must reach BOB even though the caller named Alice — he holds "
                            + WishProgress.barCount(bob.getUUID())
                            + ". This is the bug the two-child photograph showed."));
            helper.assertTrue(WishProgress.barCount(alice.getUUID()) == 1,
                    Component.literal("…and Alice must still hold exactly her own, not her brother's too — she holds "
                            + WishProgress.barCount(alice.getUUID())));
            helper.succeed();
        } finally {
            cleanUp(aliceWish, bobWish);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        }
    }

    /**
     * <b>The narrator path, which the ThreadLocal cannot reach.</b>
     *
     * <p>{@code ToolNarrator} speaks from its own daemon thread, where {@code AgentContext} is
     * unbound — so a fix that only reads {@code currentRun()} at the sink would narrate to nobody
     * with two children in flight, which is a different bug wearing the same green run. The owner
     * has to be captured at {@code begin()} on the dispatch thread and carried in.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void theNarratorAddressesTheOwnerFromItsOwnThread(GameTestHelper helper) {
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID aliceWish = wishFor("narrator", "alice");
        UUID bobWish = wishFor("narrator", "bob");
        String aliceWorker = startWishing(alice, aliceWish, "a purple castle");
        startWishing(bob, bobWish, "a rocket ship");
        WishProgress.setStatus(bob, "Bob's own line", "building");

        // The ride-along: begin() shows the agent's own `status` argument immediately, through the
        // same sink the daemon thread uses. Off-thread for the AgentBridge reason above — and note
        // show() SWALLOWS the exception, so driving this inline produced an empty status line and
        // no error at all, which reads as "the narrator is dead" rather than "the harness is wrong".
        Thread agent = new Thread(() -> asAgentFor(aliceWorker,
                () -> ToolNarrator.begin("author_datapack",
                        JsonParser.parseString("{\"status\":\"Alice's own line\"}")
                                .getAsJsonObject()).stop()), "gt-two-children-narrator");
        agent.setDaemon(true);
        agent.start();

        long narrDeadline = System.currentTimeMillis() + 20_000L;
        pollUntil(helper, narrDeadline, 2400,
                () -> WishProgress.currentStatus(alice.getUUID()).contains("Alice's own"),
                () -> {
            try {
                String aliceSees = WishProgress.currentStatus(alice.getUUID());
                String bobSees = WishProgress.currentStatus(bob.getUUID());
                helper.assertTrue(aliceSees.contains("Alice's own"),
                        Component.literal("the narrator's ride-along must reach the child whose wish it is; Alice "
                                + "sees '" + aliceSees + "'"));
                helper.assertTrue(bobSees.contains("Bob's own"),
                        Component.literal("Bob's line must survive Alice's narrator; he sees '" + bobSees + "'"));
                helper.succeed();
            } finally {
                cleanUp(aliceWish, bobWish);
                MockPlayerNetwork.remove(alice);
                MockPlayerNetwork.remove(bob);
            }
        });
    }

    /** Call the real tool and keep what it SAID, so a refusal is reportable rather than silent. */
    private static void setStatus(String doing, AtomicReference<String> said) {
        try {
            var r = new com.moddingfromamod.agent.tools.SetStatusTool().call(
                    JsonParser.parseString("{\"doing\":\"" + doing + "\"}").getAsJsonObject());
            said.set((r.isError() ? "ERROR: " : "ok: ") + r.text());
        } catch (Throwable t) {
            said.set("THREW: " + t);
        }
    }

    /**
     * <b>Wait on the RESULT, in wall-clock, never on a tick count.</b>
     *
     * <p>A GameTest server ticks as fast as it can, so {@code runAfterDelay(40, ...)} is not two
     * seconds — the first cut of these tests asserted after 40 ticks and read an empty status line
     * every time, because 40 ticks had gone by before the agent thread had started. And the work
     * cannot simply be joined: the tool bodies are queued onto the SERVER thread, so blocking it
     * is the one thing that guarantees they never run.
     */
    private static void pollUntil(GameTestHelper helper, long deadlineMs, int attemptsLeft,
            java.util.function.BooleanSupplier ready, Runnable check) {
        // BOTH bounds are needed and they guard different failures. The wall clock is what the
        // background thread actually needs; the attempt count is what keeps the final check inside
        // the test's own tick budget, because ticks here are unbounded-fast and 600 of them can
        // pass in under a second — so a wall-clock-only wait dies as "didn't succeed or fail
        // within 600 ticks", which says nothing about the thing under test.
        if (ready.getAsBoolean() || attemptsLeft <= 0 || System.currentTimeMillis() > deadlineMs) {
            check.run();
            return;
        }
        helper.runAfterDelay(2, () -> pollUntil(helper, deadlineMs, attemptsLeft - 1, ready, check));
    }
}
