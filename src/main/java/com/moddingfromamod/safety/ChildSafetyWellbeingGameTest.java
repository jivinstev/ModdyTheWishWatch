package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
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
 * <b>Gate B for the one kind word this layer says</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §12.1, decision 1B; chunk CS-D2).
 *
 * <p>The decided answer to the wellbeing question was <i>A + B</i>: the grown-up note stays the
 * record on <b>any</b> flag and never blocks, and the child hears one warm line on a <b>lexicon</b>
 * hit — deterministic, so there can be no false alarm. Until CS-D2 a wellbeing flag wrote a note to
 * a file and said nothing at all to the child who had just typed it.
 *
 * <h2>What only Gate B can see here</h2>
 *
 * <p>{@code SafetyInputGateTest} settles the RULE in milliseconds — who triggers the line, which arm
 * says it — and {@code SafetyWordsTest} settles the WORDS. Neither can see the two things that make
 * it a feature rather than a constant:
 *
 * <ul>
 *   <li><b>That it reaches the wire.</b> A string computed, logged and dropped on the floor looks
 *       exactly like a string that works, which is the failure {@code ChildSafetyEnforceGameTest}
 *       already records for the MAYBE guidance line.</li>
 *   <li><b>That it is said ONCE.</b> The dequeue point writes a SECOND grown-up note for the same
 *       wish, deliberately — {@code AwaitWishTool} logs one whenever the combined verdict carries
 *       the flag. A note is a record and can be written twice harmlessly; a line is an event, and a
 *       child told twice about one sentence he typed would read as being fussed over. Only a test
 *       that drives {@code submit} <i>and then</i> the real dequeue can tell those apart.</li>
 * </ul>
 *
 * <p>And the negative is the half that was actually asked for: a MODEL-only wellbeing verdict must
 * write the note and say nothing, because CS-S1b measured the model calling that row {@code CLEARLY}
 * one time in three after three prompt iterations.
 *
 * <h2>ONE BATCH PER TEST</h2>
 *
 * <p>These share the two process-wide statics that decide their answers — the {@code childsafety}
 * property and {@link SafetyGrader}'s injected stub — and tests within a batch run concurrently
 * while batches are strictly sequential (CLAUDE.md). A batch each is real isolation; CS-S1b and
 * CS-S3 both paid for learning that, with the failures landing on whichever test was unlucky.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ChildSafetyWellbeingGameTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";

    // ---- the fixture ----

    /** A player, and somewhere to accumulate what the server put on his wire. */
    private record Child(ServerPlayer player, StringBuilder said) { }

    private static Child begin(GameTestHelper helper) {
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Child c = new Child(p, new StringBuilder());
        tidyWhenTheTestEnds(helper, c);
        return c;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ A listener rather than a {@code finally} inside {@code succeedWhen}: that lambda re-runs
     * every tick until it stops throwing, so a {@code finally} fires on the first poll and tears the
     * fixture down before the test has begun — clearing the grader's process-wide stub, after which
     * the failures land on files with nothing to do with this one (CS-S3 paid for it).
     */
    private static void tidyWhenTheTestEnds(GameTestHelper helper, Child c) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(c);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(c);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after,
                                          GameTestRunner runner) {
                end(c);
            }
        });
    }

    private static void end(Child c) {
        SafetyGrader.forTest(null);
        System.clearProperty(MODE);
        RuntimeConfig.invalidate();
        // ⚠ SETTLE WHAT THIS TEST STARTED. A wellbeing wish is never refused, so `submit` registers
        // a real run in the GLOBAL Marshal -- and the dequeue below hands the wish to a private
        // queue, so no worker ever claims that run. Left live, it aged past the never-claimed limit
        // and ModdyTiredGameTest's sweep (which reads every live run on the server) put Moddy to bed
        // for agent_unresponsive two minutes later, red on every `--all` since (measured 2026-09-24,
        // 3 runs of 3, on main before the 1.21.4 flip as well as after it).
        for (var run : com.moddingfromamod.marshal.Marshal.global().liveFor(c.player().getUUID())) {
            com.moddingfromamod.marshal.Marshal.global().finish(run.wishId());
        }
        MockPlayerNetwork.remove(c.player());
    }

    /** A wish text no other test and no corpus row can collide with. */
    private static String unique(String tag) {
        return tag + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Everything the server actually put on this player's wire.
     *
     * <p>⚠ ACCUMULATED, never re-read. {@code readOutbound} is DESTRUCTIVE and {@code succeedWhen}
     * re-runs its whole body every tick — so a plain drain would find the message on one poll and an
     * empty channel on the next, and any later assertion failing would report "the child was not
     * told" about a child who was. The failure would name the wrong thing and read as a flaky
     * feature rather than a flaky instrument.
     */
    private static String chat(GameTestHelper helper, Child c) {
        var channel = c.player().connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("this test cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            if (o instanceof ClientboundSystemChatPacket chatPacket) {
                c.said().append(chatPacket.content().getString()).append('\n');
            }
        }
        return c.said().toString();
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
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
            // A torn read is a miss, and the assertion below will say so by name.
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** Lines in the wellbeing file, or 0. Its own file, holding nothing else (§6). */
    private static long countGrownUpNotes() {
        try {
            Path p = ActionLog.grownUpNotes();
            return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8).size() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** The short id the book gave this exact text, or "". */
    private static String wishIdFor(GameTestHelper helper, String text) {
        var book = WishBook.get(helper.getLevel().getServer());
        for (Wish w : book.recent(200)) {
            if (text.equals(w.text())) {
                return w.shortId();
            }
        }
        return "";
    }

    private static Wish wishFor(GameTestHelper helper, String text) {
        var book = WishBook.get(helper.getLevel().getServer());
        for (Wish w : book.recent(200)) {
            if (text.equals(w.text())) {
                return w;
            }
        }
        return null;
    }

    /**
     * Call {@link AwaitWishTool} on its own thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body IS the server thread, and this tool hops onto it partway through
     * ({@code AgentBridge.onServer}) to read the world — so calling it synchronously trips the
     * bridge's own deadlock guard, which is how CS-S1b's first cut failed with a message about
     * deadlock and nothing about safety.
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
        }, "mfam-test-await-wellbeing");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ── (a) the line is said, the wish carries on, and it is said exactly ONCE ─────────────────

    /**
     * A lexicon wellbeing hit: one warm line, the wish still granted, and the record says so.
     *
     * <p>Drives the REAL {@code submit} and then the REAL dequeue, because "once" is a claim about
     * the whole journey. The lexicon is the shipped list rather than a stub, so this also asserts
     * the list still answers {@code WELLBEING} to a phrasing a child might really type — a term
     * quietly moved to the DANGER axis would turn this into a block and nothing else would notice.
     */
    @GameTest(template = "empty_test", batch = "childsafety_wellbeing_line", timeoutTicks = 600)
    public static void aLexiconWellbeingHitTellsTheChildOnceAndStillGrantsTheWish(
            GameTestHelper helper) {
        Child c = begin(helper);
        // The MODEL says FINE, so anything said to the child can only have come from L0.
        SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        String text = unique("make me a knife to hurt myself");
        helper.assertTrue(SafetyLexicon.shared().check(text)
                        .map(SafetyVerdict::wellbeing).orElse(false),
                Component.literal("the shipped lexicon no longer flags this as WELLBEING, so everything below is "
                        + "being asserted against nothing: " + text));
        long notesBefore = countGrownUpNotes();

        WishService.Outcome outcome = WishService.submit(c.player(), text, WishSource.COMMAND);
        // ACCEPTED, not a specific outcome: which acceptance a wish gets is the template matcher's
        // business and not this layer's to predict (the CS-S1b lesson).
        helper.assertTrue(outcome == WishService.Outcome.PENDING_ESCALATION
                        || outcome == WishService.Outcome.FULFILLING,
                Component.literal("a wellbeing wish must never be refused — §1 calls that the worst answer "
                        + "available; got " + outcome));

        // 1 — HE WAS TOLD, synchronously, before anything was awaited.
        String saidAtSubmit = chat(helper, c);
        helper.assertTrue(saidAtSubmit.contains(SafetyWords.sayWellbeingNoticed()),
                Component.literal("the child was not told; chat carried: " + saidAtSubmit));

        // 2 — THE NOTE WAS WRITTEN TOO. The note is the record a grown-up reads and is the half
        // §1 actually promises; the line is the half that was missing before CS-D2.
        helper.assertTrue(countGrownUpNotes() > notesBefore,
                Component.literal("no grown-up note was written, so decision 1B's 'A' half is missing"));

        // 3 — THE RECORD SAYS THE CHILD WAS TOLD. The only place that stays checkable afterwards.
        String wishId = wishIdFor(helper, text);
        helper.assertTrue(!wishId.isEmpty(), Component.literal("the wish was never recorded in the book"));
        List<JsonObject> records = safetyRecordsFor(wishId);
        helper.assertTrue(records.stream().anyMatch(r -> "lexicon".equals(str(r, "grader"))
                        && r.has("childTold") && r.get("childTold").getAsBoolean()),
                Component.literal("no lexicon record with childTold=true for " + wishId + "; found "
                        + records.size() + " safety record(s)"));

        // 4 — AND NOW THE DEQUEUE, where a second grown-up note is written on purpose and a
        // second LINE must not be. Re-offered to a private queue: a test parked on the global one
        // steals another test's wish, which AwaitWishTool's own constructor javadoc records.
        Wish wish = wishFor(helper, text);
        helper.assertTrue(wish != null, Component.literal("the wish vanished from the book"));
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        long notesAfterSubmit = countGrownUpNotes();
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("a child in distress was REFUSED at the dequeue: " + result.text()));
            helper.assertTrue(result.text().contains(text),
                    Component.literal("the wish was not handed over: " + result.text()));

            // SAID ONCE. Counted after the tool has returned, which is the only moment this is a
            // fact: there is no line-sending code anywhere but WishService.submit, so a second
            // line could only come from new code — which is exactly what this pins.
            String said = chat(helper, c);
            helper.assertValueEqual(countOf(said, SafetyWords.sayWellbeingNoticed()), 1,
                    Component.literal("times the child was told"));
            // …while the NOTE is deliberately written again. Asserted so that "once" can never be
            // achieved by quietly dropping the second note as well.
            helper.assertTrue(countGrownUpNotes() > notesAfterSubmit,
                    Component.literal("the dequeue stopped writing its note, which is a different feature"));
        });
    }

    // ── (b) the model's flag alone: the note, and not a word to the child ─────────────────────

    /**
     * A MODEL-only wellbeing verdict writes the note and says nothing (the "B" half).
     *
     * <p>The text is one the shipped lexicon has no opinion about, so the flag can only be the
     * stub's — which is the situation the decision chose against speaking in, because CS-S1b measured
     * the model's wellbeing judgement unstable one time in three.
     */
    @GameTest(template = "empty_test", batch = "childsafety_wellbeing_model", timeoutTicks = 600)
    public static void aModelOnlyWellbeingFlagWritesTheNoteAndSaysNothingToTheChild(
            GameTestHelper helper) {
        Child c = begin(helper);
        // ⚠ NOT "tower", and that was load-bearing rather than cosmetic. `WishCatalog` had a
        // template whose whole match was the word "tower" (deleted), so the text was GRANTED offline
        // and the wish reached the queue with the book saying DONE — which since CS-D7 the dequeue
        // correctly drops, because a wish the child stopped used to sit in the queue and be handed
        // to the next worker that woke. Production never offers a finished wish to the queue, so
        // this test's subject (a model grade AT THE DEQUEUE) needs a wish that really escalated.
        // "lighthouse" is in none of the catalogue's keyword sets; the assertion below is what
        // makes a future template matching it fail by name instead of silently.
        String text = unique("make a wellbeing-model lighthouse");
        helper.assertTrue(SafetyLexicon.shared().check(text).isEmpty(),
                Component.literal("the lexicon has an opinion about this text, so the flag below would not be the "
                        + "model's alone: " + text));
        SafetyGrader.forTest(t -> new SafetyVerdict(
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, true, List.of(SafetyVerdict.Reason.SELF_HARM), "",
                SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE));

        // ESCALATED, and asserted rather than tolerated. Test (a) above is deliberately loose
        // about which acceptance a wish gets, because its subject is what happens AT SUBMIT and
        // either acceptance exercises it. This test's subject is at the DEQUEUE, which a fulfilled
        // wish never reaches, so here the outcome is part of the fixture.
        helper.assertValueEqual(WishService.submit(c.player(), text, WishSource.COMMAND),
                WishService.Outcome.PENDING_ESCALATION,
                Component.literal("this wish did not escalate, so it never reaches the dequeue this test is about — "
                        + "an offline template has started matching \"" + text + "\"; pick a noun "
                        + "the catalogue has no keyword for"));
        final Wish wish = wishFor(helper, text);
        helper.assertTrue(wish != null, Component.literal("the wish was never recorded in the book"));
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        long notesBefore = countGrownUpNotes();
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("a wellbeing flag refused the wish: " + result.text()));

            // THE NOTE, on ANY flag — that is the "A" half and it is not conditional on
            // which grader raised it.
            helper.assertTrue(countGrownUpNotes() > notesBefore,
                    Component.literal("no grown-up note for a model wellbeing flag, so the record a grown-up reads "
                            + "depends on which grader happened to notice"));

            // AND NOT A WORD TO HIM.
            String said = chat(helper, c);
            helper.assertTrue(!said.contains(SafetyWords.sayWellbeingNoticed()),
                    Component.literal("the model's flag spoke to the child, which is the false-alarm the design "
                            + "chose against; chat carried: " + said));

            List<JsonObject> records = safetyRecordsFor(wish.shortId());
            helper.assertTrue(!records.isEmpty(), Component.literal("no safety record for " + wish.shortId()));
            helper.assertTrue(records.stream().noneMatch(
                            r -> r.has("childTold") && r.get("childTold").getAsBoolean()),
                    Component.literal("a record claims the child was told and he was not, which would make the one "
                            + "number decision 1B creates a lie"));
        });
    }
}
