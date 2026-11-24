package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishCancel;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.wish.WishText;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for {@code /moddy redirect} being GRADED</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §12.7, decision 7; chunk CS-D2).
 *
 * <p>{@code /moddy redirect <free text>} is the third re-wish path and the only one that puts NEW
 * words into the system without going through {@code WishService.submit}. Until CS-D2 it wrote them
 * into the wish's NOTE and left {@code wish.text()} alone — and since the dequeue grade is memoised
 * per text and reads {@code wish.text()}, the model never saw them. The re-aim had the deterministic
 * floor and nothing else: recorded as a stated limit in the CS-S2 ledger row, decided by a maintainer,
 * and closed here by making the re-aim part of the TEXT.
 *
 * <h2>What only Gate B can see</h2>
 *
 * <p>{@code WishTextTest} settles the string — both halves kept, the re-aim never the part that gets
 * cut, sanitize-stable so the grader and the agent read the same characters. What it cannot reach is
 * the sentence that matters: <b>a new string is a new memo key, so the existing dequeue enforcement
 * covers the redirect for free.</b> That is a claim about {@link SafetyGrader}'s memo, the real
 * {@link AwaitWishTool} and the real {@link WishCancel}, and there is no cheaper place to ask it.
 *
 * <p>The two rows either side of it fail independently: a grade that runs and is not enforced, and
 * enforcement that fires on a wish nobody re-aimed. So one test drives a re-aim the model refuses
 * and one drives a benign one, and the benign one also asserts <b>both</b> halves reach the agent —
 * a text holding only the re-aim would be a wish for a tower rather than a wish to make one bigger.
 *
 * <h2>ONE BATCH PER TEST</h2>
 *
 * <p>These share {@link SafetyGrader}'s injected stub, the {@code childsafety} property and
 * {@code Marshal.global()}, and tests within a batch run concurrently while batches are strictly
 * sequential (CLAUDE.md). A batch each is real isolation.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ChildSafetyReaimGameTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";

    /** A wish a child might type, and the thing he then says he meant. */
    private static final String ORIGINAL = "make it bigger";

    // ---- the fixture: a real player, a real wish, a real LIVE run ----

    private record Wishing(ServerPlayer player, Wish wish, StringBuilder said) { }

    /**
     * A wish in the state {@code WishService.submit} leaves it in, mid-grant.
     *
     * <p>The run is submitted to {@link Marshal#global()} and the bar started because
     * {@link WishCancel#stop} finds the wish through {@code marshal.liveFor} — a fixture without a
     * live run would have nothing to re-aim and the test would assert that nothing happened to
     * nothing.
     */
    private static Wishing begin(GameTestHelper helper, String tag) {
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        // A UNIQUE ORIGINAL, so the memo key assertions below cannot be satisfied by another
        // test's grade and so nothing in the corpus can be what is being measured.
        String text = ORIGINAL + " " + tag + " " + UUID.randomUUID().toString().substring(0, 8);
        Wish wish = WishBook.get(helper.getLevel().getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().name(),
                p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);

        Wishing w = new Wishing(p, wish, new StringBuilder());
        tidyWhenTheTestEnds(helper, w);
        return w;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ A listener rather than a {@code finally} inside {@code succeedWhen}, which re-runs every
     * tick until it stops throwing: a {@code finally} would fire on the first poll and clear the
     * process-wide grader stub before the test had begun (the CS-S3 failure).
     */
    private static void tidyWhenTheTestEnds(GameTestHelper helper, Wishing w) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after,
                                          GameTestRunner runner) {
                end(w);
            }
        });
    }

    private static void end(Wishing w) {
        SafetyGrader.forTest(null);
        System.clearProperty(MODE);
        RuntimeConfig.invalidate();
        try {
            Marshal.global().cancel(w.wish().id(), CancelMode.STOP, "", "");
        } catch (RuntimeException ignored) {
            // Already finished, which is the usual case after a re-aim.
        }
        // `WishCancel.reaim` offers the re-opened wish to the GLOBAL queue, which nothing consumes
        // in a gate run. Emptied here rather than left behind: a wish sitting on a shared queue is
        // exactly the cross-test state a batch split exists to prevent, and this test drives its
        // own copy through a private queue instead.
        WishQueue.global().clear();
        WishProgress.clearWishBar(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    /** Answer every grade with this, whatever the text says, and record what was asked. */
    private static List<String> gradesRecording(SafetyVerdict v) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SafetyGrader.forTest(text -> {
            seen.add(text);
            return v;
        });
        return seen;
    }

    /**
     * Everything the server actually put on this player's wire.
     *
     * <p>⚠ ACCUMULATED, never re-read: {@code readOutbound} is destructive and {@code succeedWhen}
     * re-runs its body every tick, so a plain drain would report "the child was not told" about a
     * child who was — a flaky instrument reading as a flaky feature.
     */
    private static String chat(GameTestHelper helper, Wishing w) {
        var channel = w.player().connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("this test cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            if (o instanceof ClientboundSystemChatPacket chatPacket) {
                w.said().append(chatPacket.content().getString()).append('\n');
            }
        }
        return w.said().toString();
    }

    /** Every {@code safety} record in the log carrying this wish id. */
    private static List<JsonObject> safetyRecordsFor(String wishId) {
        List<JsonObject> out = new ArrayList<>();
        Path p = ActionLog.path();
        if (!Files.exists(p)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank() || !line.contains("\"safety\"") || !line.contains(wishId)) {
                    continue;
                }
                JsonObject o;
                try {   // one torn line (a full disk mid-append) must not hide the rest
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException torn) {
                    continue;
                }
                if ("safety".equals(str(o, "kind")) && wishId.equals(str(o, "wishId"))) {
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
            // A torn read is a miss, and the assertion will say so by name.
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /**
     * Call {@link AwaitWishTool} on its own thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body IS the server thread and this tool hops onto it partway through,
     * so calling it synchronously trips the bridge's own deadlock guard.
     */
    private static AtomicReference<ToolResult> await(WishQueue queue,
                                                     AtomicReference<Throwable> blew) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(new AwaitWishTool(queue).call(new JsonObject()));
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-test-await-reaim");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** The wish as the book holds it now. */
    private static Wish reload(GameTestHelper helper, Wishing w) {
        return WishBook.get(helper.getLevel().getServer()).byId(w.wish().id()).orElse(null);
    }

    // ── (a) the re-aim becomes the wish, and a grade is taken on the NEW words ────────────────

    /**
     * The whole mechanism of decision 7, with no dequeue involved.
     *
     * <p>Three claims, and the third is the one the decision is actually made of: the wish text
     * carries both halves, the text really changed (so the memo key changed), and the grader was
     * asked about the new string rather than the old one — which is what "the existing dequeue
     * grade covers the redirect for free" means in practice.
     */
    @GameTest(template = "empty_test", batch = "childsafety_reaim_text", timeoutTicks = 600)
    public static void theReAimBecomesTheWishTextAndIsGradedAsItsOwnString(GameTestHelper helper) {
        Wishing w = begin(helper, "reaim-text");
        String was = w.wish().text();
        String aim = "the stone tower by the lake";
        List<String> graded = gradesRecording(SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        WishCancel.Outcome outcome = WishCancel.stop(w.player(), w.wish().shortId(),
                CancelMode.REDIRECT, aim);
        helper.assertTrue(outcome.stopped(),
                Component.literal("the re-aim never ran, so nothing below is being asserted"));
        helper.assertValueEqual(outcome.mode(), CancelMode.REDIRECT, Component.literal("cancel mode"));

        Wish after = reload(helper, w);
        helper.assertTrue(after != null, Component.literal("the wish vanished from the book"));
        // 1 — BOTH HALVES. The original says what to do, the re-aim says which thing.
        helper.assertTrue(after.text().contains(was),
                Component.literal("the child's own wish was thrown away: " + after.text()));
        helper.assertTrue(after.text().contains(aim),
                Component.literal("the re-aim is not in the wish text, so the dequeue grade will never see it: "
                        + after.text()));
        // 2 — AND IT IS A DIFFERENT STRING, which is what makes it a different memo key.
        helper.assertTrue(!after.text().equals(was),
                Component.literal("the text did not change, which is the bug decision 7 exists to fix"));
        // 3 — THE GRADER WAS ASKED ABOUT THE NEW WORDS. SafetyGrader memoises per sanitised text,
        // so this is the memo key assertion: had the text been left alone, the only string ever
        // graded would have been the original.
        String reaimed = after.text();
        helper.assertTrue(reaimed.equals(WishText.sanitize(reaimed)),
                Component.literal("the wish text is not sanitize-stable, so the grader reads different characters "
                        + "from the ones the agent is handed: " + reaimed));

        helper.succeedWhen(() -> {
            // The grade runs on a daemon thread (nothing on the server thread may block), so this
            // is polled rather than read once.
            helper.assertTrue(graded.contains(reaimed),
                    Component.literal("the grader was never asked about the re-aimed text. Asked about: " + graded));
            // AND THE WISH IS ALIVE. A re-aim is not a refusal.
            Wish now = reload(helper, w);
            helper.assertTrue(now != null && now.status() == WishStatus.PENDING,
                    Component.literal("the re-aimed wish is not waiting for the agent; status "
                            + (now == null ? "gone" : now.status())));
        });
    }

    // ── (b) a re-aim the model refuses ends the wish at the dequeue ───────────────────────────

    /**
     * The gap closed, end to end: a re-aim the LEXICON passes and the MODEL calls {@code CLEARLY}.
     *
     * <p>This is the case that used to reach the agent verbatim. The text is deliberately something
     * the shipped lexicon has no opinion about — asserted, so the test cannot pass because L0
     * happened to catch it, which would prove nothing about L1.
     */
    @GameTest(template = "empty_test", batch = "childsafety_reaim_blocked", timeoutTicks = 600)
    public static void aReAimTheModelRefusesEndsTheWishAndTheChildIsTold(GameTestHelper helper) {
        Wishing w = begin(helper, "reaim-blocked");
        String aim = "reaim-refused-by-the-model " + UUID.randomUUID().toString().substring(0, 8);
        helper.assertTrue(SafetyLexicon.shared().check(aim).isEmpty(),
                Component.literal("the lexicon has an opinion about this re-aim, so passing would say nothing about "
                        + "whether L1 reaches it: " + aim));
        // THE MODEL REFUSES EVERYTHING, including the re-aimed text.
        SafetyGrader.forTest(text -> new SafetyVerdict(
                SafetyVerdict.Band.CLEARLY, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(SafetyVerdict.Reason.GORE), "",
                SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE));

        helper.assertTrue(WishCancel.stop(w.player(), w.wish().shortId(), CancelMode.REDIRECT, aim)
                        .stopped(),
                Component.literal("the re-aim never ran"));
        Wish reopened = reload(helper, w);
        helper.assertTrue(reopened != null, Component.literal("the wish vanished from the book"));
        helper.assertTrue(reopened.text().contains(aim),
                Component.literal("the re-aim is not in the text, so this test is about the old behaviour: "
                        + reopened.text()));

        // ITS OWN QUEUE. A test parked on the global queue steals another test's wish, which
        // AwaitWishTool's own constructor javadoc records; `end` empties the global one.
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(reopened), Component.literal("the test queue refused the wish"));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));

            // 1 — THE AGENT NEVER SEES IT, and the re-aim is the half that matters: it is the
            // string that used to arrive having been read by the floor alone.
            helper.assertTrue(result.isError(),
                    Component.literal("a refusal must be an error the agent reads and follows: " + result.text()));
            helper.assertTrue(!result.text().contains(aim),
                    Component.literal("the re-aim reached the agent despite being refused: " + result.text()));
            helper.assertTrue(!result.text().contains(ORIGINAL),
                    Component.literal("the original reached the agent as well: " + result.text()));

            // 2 — THE WISH IS OVER.
            Wish now = reload(helper, w);
            helper.assertTrue(now != null, Component.literal("the wish vanished from the book"));
            helper.assertValueEqual(now.status(), WishStatus.REJECTED, Component.literal("wish status"));

            // 3 — AND HE WAS TOLD. The block wording rather than the re-aim's own ("stopped"
            // line), because at this point the wish really has ended.
            String said = chat(helper, w);
            helper.assertTrue(said.contains(SafetyWords.sayBlocked()),
                    Component.literal("the child was not told; chat carried: " + said));

            // 4 — THE RECORD, joined to the wish, saying the model decided it. `grader` is what
            // separates "L1 finally reaches a redirect" from "the floor caught it after all".
            List<JsonObject> records = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!records.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            JsonObject r = records.get(records.size() - 1);
            helper.assertValueEqual(str(r, "action"), "BLOCK", Component.literal("action"));
            helper.assertValueEqual(str(r, "overall"), "CLEARLY", Component.literal("overall"));
            helper.assertValueEqual(str(r, "grader"), "haiku", Component.literal("grader"));
        });
    }

    // ── (c) a benign re-aim reaches the agent with BOTH halves and no ungraded words ──────────

    /**
     * The ordinary case, which has to keep working: the agent gets the wish and the re-aim.
     *
     * <p>Both halves are asserted because each fails on its own and each is a different bug the
     * child would experience. Without the original the agent has a wish for a tower rather than a
     * wish to make one bigger; without the re-aim the whole feature is missing and the agent
     * repeats the mistake he just stopped.
     */
    @GameTest(template = "empty_test", batch = "childsafety_reaim_benign", timeoutTicks = 600)
    public static void aBenignReAimReachesTheAgentWithBothTheWishAndTheReAim(
            GameTestHelper helper) {
        Wishing w = begin(helper, "reaim-benign");
        String was = w.wish().text();
        String aim = "the little wooden house by the lake";
        SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        helper.assertTrue(WishCancel.stop(w.player(), w.wish().shortId(), CancelMode.REDIRECT, aim)
                        .stopped(),
                Component.literal("the re-aim never ran"));
        Wish reopened = reload(helper, w);
        helper.assertTrue(reopened != null, Component.literal("the wish vanished from the book"));
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(reopened), Component.literal("the test queue refused the wish"));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("a benign re-aim was refused: " + result.text()));

            helper.assertTrue(result.text().contains(was),
                    Component.literal("the agent was not told what to DO, only which thing: " + result.text()));
            helper.assertTrue(result.text().contains(aim),
                    Component.literal("the agent was not told WHICH THING he meant, so it will repeat the mistake "
                            + "he just stopped: " + result.text()));
            // AND IT IS STILL INSTRUCTED. The note no longer quotes the re-aim — it points at the
            // wish's own words — so the note being present at all is the thing to check.
            helper.assertTrue(result.text().contains("WRONG thing"),
                    Component.literal("the agent was handed a re-aimed wish with nothing saying why it came back: "
                            + result.text()));

            // NOTHING WAS SAID ABOUT SAFETY. A FINE re-aim is his wish and stays his; the only
            // chat here is the ordinary "I stopped that" line the re-aim path already sent.
            String said = chat(helper, w);
            helper.assertTrue(!said.contains(SafetyWords.sayBlocked()),
                    Component.literal("the child was told his re-aim was a problem: " + said));

            List<JsonObject> records = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!records.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            helper.assertValueEqual(str(records.get(records.size() - 1), "action"), "PASS",
                    Component.literal("action"));
        });
    }
}
