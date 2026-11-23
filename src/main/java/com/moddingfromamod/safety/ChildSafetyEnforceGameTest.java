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
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>Gate B for the INPUT layer with enforcement ON</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §2's INPUT column, chunk CS-S2).
 *
 * <p>{@code SafetyInputGateTest} settles the policy in milliseconds and is where the bands and the
 * precedence rule are argued about. What it structurally cannot reach is everything this file is
 * for: that a refused wish is never handed to an agent, that the bar a child is watching actually
 * comes down, that the message really goes out on the wire, and that {@link WishStatus#REJECTED} —
 * documented since it was written and, per §0, never once set — is finally set by something.
 *
 * <h2>The grader is stubbed, and that is the point</h2>
 *
 * <p>Every question here is about WIRING. "Does a CLEARLY input reach {@code Marshal.assign}" is a
 * question about this code, and paying ten seconds and a real model to ask it would quietly turn it
 * into a question about the model. The model's own calibration is measured somewhere else entirely
 * — {@code tools/safety-arm.py} over 624 labelled rows, where a rate has a denominator.
 *
 * <h2>ONE BATCH PER TEST</h2>
 *
 * <p>Tests within a batch run concurrently and share every static, and these share <b>two</b> that
 * decide their answers: the {@code childsafety} system property and {@link SafetyGrader}'s injected
 * stub. CS-S1b and CS-S3 both measured the consequence — one test's mode arriving inside another's
 * call, with the failures landing on whichever test was unlucky. Batches are strictly sequential
 * (CLAUDE.md), so a batch each is real isolation and four of them is cheap.
 *
 * <p>Nothing here counts log lines or reads "the last record" either: the action log is one
 * append-only file this whole process writes to, so every assertion finds the record carrying ITS
 * OWN wish id.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>The shadow twin of the block. {@code ChildSafetyWiringGameTest} already drives the real
 * {@link AwaitWishTool} with a {@code CLEARLY} verdict in shadow mode and asserts the wish is
 * handed over with a {@code SHADOW_BLOCK} record beside it — which is exactly the control this file
 * needs, in the file whose subject it is. Copying it here would be a second copy of the one
 * assertion that makes "shadow" a real word.
 */
@GameTestHolder(GateShards.CI)
public class ChildSafetyEnforceGameTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String UNAVAILABLE = ModdingFromAModMod.MODID + ".childSafetyUnavailable";

    /** A wish text no other test and no corpus row can collide with. */
    private static String uniqueWish(String tag) {
        return "make a " + tag + " tower " + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- the fixture: a real player, a real wish, a real run, a private queue ----

    private record Wishing(ServerPlayer player, Wish wish, WishQueue queue,
                           StringBuilder said) { }

    /**
     * Everything a dequeue needs, in the state the real path leaves it in.
     *
     * <p>The run is submitted to {@link Marshal#global()} and the bar started, because that is what
     * {@code WishService.submit} did a moment earlier in a real game — and because "the bar came
     * down" and "the run was wound down" are both claims about state that has to exist first. A
     * fixture that skipped them would assert that nothing happened to nothing.
     */
    private static Wishing begin(GameTestHelper helper, String tag) {
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        String text = uniqueWish(tag);
        Wish wish = WishBook.get(p.getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().getName(),
                p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);

        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        Wishing w = new Wishing(p, wish, queue, new StringBuilder());
        tidyWhenTheTestEnds(helper, w);
        return w;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ A listener rather than a {@code finally} inside {@code succeedWhen}, which CS-S3 paid
     * for: that lambda re-runs every tick until it stops throwing, so a {@code finally} fires on
     * the first poll — the one still waiting — and tears the fixture down before the test has
     * begun. Worse, it would clear the grader's stub, which is process-wide, leaving every later
     * wish in the run graded by whatever was left behind.
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
        System.clearProperty(UNAVAILABLE);
        RuntimeConfig.invalidate();
        try {
            Marshal.global().cancel(w.wish().id(), com.moddingfromamod.marshal.CancelMode.STOP,
                    "", "");
        } catch (RuntimeException ignored) {
            // Already finished, which is the usual case after a refusal.
        }
        WishProgress.clearWishBar(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    /** Answer every grade with this, whatever the text says. */
    private static void grades(SafetyVerdict v) {
        SafetyGrader.forTest(text -> v);
    }

    private static SafetyVerdict verdict(SafetyVerdict.Band band, String redirect,
                                         SafetyVerdict.Reason... reasons) {
        return new SafetyVerdict(band, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(reasons), redirect,
                SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE);
    }

    // ---- driving the tool ----

    /**
     * Call {@link AwaitWishTool} on its own thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body IS the server thread, and this tool hops onto it partway through
     * ({@code AgentBridge.onServer}) to read the world and to end a refused wish — so calling it
     * synchronously trips the bridge's own deadlock guard, which is how CS-S1b's first cut failed
     * with a message about deadlock and nothing about safety. Off-thread plus {@code succeedWhen}
     * means the server keeps ticking and can serve the hop, exactly as it does for a real worker.
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
        }, "mfam-test-await-enforce");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ---- reading what really happened ----

    /** Every {@code safety} record in the log carrying this wish id. */
    private static List<JsonObject> safetyRecordsFor(String wishId) {
        return records(wishId, "safety");
    }

    /** Every {@code wish} record in the log carrying this wish id. */
    private static List<JsonObject> wishRecordsFor(String wishId) {
        return records(wishId, "wish");
    }

    private static List<JsonObject> records(String wishId, String kind) {
        List<JsonObject> out = new ArrayList<>();
        Path p = ActionLog.path();
        if (!Files.exists(p)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank() || !line.contains(wishId)) {
                    continue;
                }
                JsonObject o;
                try {   // one torn line (a full disk mid-append) must not hide the rest
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException torn) {
                    continue;
                }
                if (kind.equals(str(o, "kind")) && wishId.equals(str(o, "wishId"))) {
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
     * Everything the server actually put on this player's wire.
     *
     * <p>The mock's connection is a real netty {@code EmbeddedChannel}, so the packets are captured
     * rather than imagined — the {@code VanillaWatchGameTest} technique. Asserted to BE one rather
     * than defended against: an instrument that silently captures nothing reports a clean run every
     * time, which is the failure shape {@code docs/CAMERA.md} exists about.
     */
    private static String chat(GameTestHelper helper, Wishing w) {
        var channel = w.player().connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("this test cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            if (o instanceof ClientboundSystemChatPacket chatPacket
                    && !isVanillaAdvancement(chatPacket.content())) {
                w.said().append(chatPacket.content().getString()).append('\n');
            }
        }
        // ⚠ ACCUMULATED, never re-read. `readOutbound` is DESTRUCTIVE and `succeedWhen` re-runs its
        // whole body every tick until nothing throws -- so a plain drain would find the message on
        // one poll and an empty channel on the next, and any later assertion failing would report
        // "the child was not told" about a child who was. The failure would name the wrong thing
        // and would look like a flaky feature rather than a flaky instrument.
        return w.said().toString();
    }

    /**
     * <b>Minecraft's own "X has made the advancement …" broadcast is not something WE told him.</b>
     *
     * <p>The mock stands wherever its test plot lands on a shared GameTest world, and plots are
     * handed out in first-seen order, so adding a test elsewhere can land this one beside a beacon
     * a previous test left behind. Measured 2026-09-23 when {@code TwoChildCaptureGameTest} was
     * added: <i>"the child was told about a MAYBE: mfam-mock has made the advancement [Bring Home
     * the Beacon]"</i>. The claim under test is about the safety layer's words, so the vanilla
     * broadcast is excluded by its translation key rather than by its wording.
     */
    private static boolean isVanillaAdvancement(net.minecraft.network.chat.Component c) {
        return c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().startsWith("chat.type.advancement.");
    }

    // ── (a) a CLEARLY wish is never handed to an agent ────────────────────────────────────────

    /**
     * The whole INPUT block, end to end: refused, ended, un-barred, told, logged.
     *
     * <p>Five separate claims on purpose, because four of them can hold while the fifth does not
     * and the child would experience a different failure each time — a wish that vanished with the
     * bar still spinning, or one he was told about that the agent built anyway.
     */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_block", timeoutTicks = 600)
    public static void aClearlyWishIsWithheldEndedAndSaidOutLoud(GameTestHelper helper) {
        Wishing w = begin(helper, "clearly-blocked");
        grades(verdict(SafetyVerdict.Band.CLEARLY, "", SafetyVerdict.Reason.SEXUAL));
        // READ BEFORE THE REFUSAL IS IN FLIGHT. "It came down" is only a fact if it was up, and a
        // count taken after the tool has started racing is a count of whichever happened first.
        int barsBefore = WishProgress.barCount(w.player().getUUID());
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));

            // 1 — THE AGENT NEVER SEES IT. The text is the thing that must not have travelled.
            helper.assertTrue(!result.text().contains(w.wish().text()),
                    Component.literal("the wish text reached the agent despite being blocked: " + result.text()));
            helper.assertTrue(result.isError(),
                    Component.literal("a refusal must be an error the agent reads and follows (the CancelGate "
                            + "shape), not an ok() it can mistake for an empty queue"));
            helper.assertTrue(result.text().contains("await_wish"),
                    Component.literal("a refusal that names no next step makes a worker improvise: " + result.text()));

            // 2 — THE WISH IS OVER, and REJECTED is finally set by something (§0).
            Wish after = WishBook.get(helper.getLevel().getServer()).byId(w.wish().id())
                    .orElse(null);
            helper.assertTrue(after != null, Component.literal("the wish vanished from the book"));
            helper.assertValueEqual(after.status(), WishStatus.REJECTED, Component.literal("wish status"));

            // 3 — THE BAR CAME DOWN. A wish that is not going to happen must not leave a child
            // watching a spinner for it.
            helper.assertTrue(barsBefore > 0,
                    Component.literal("the fixture never put a bar up, so this test is asserting nothing"));
            helper.assertValueEqual(WishProgress.barCount(w.player().getUUID()), 0, Component.literal("bars left"));

            // 4 — HE WAS TOLD, on the wire, in chat (he has already read the opener, "Moddy's on
            // it…", so the action bar alone would leave that sentence as the last word).
            String said = chat(helper, w);
            helper.assertTrue(said.contains(SafetyWords.sayBlocked()),
                    Component.literal("the child was not told; chat carried: " + said));

            // 5 — THE RECORD. Both kinds: the grade that decided it and the wish outcome a report
            // counts, which are read by different halves of tools/safety-report.py.
            List<JsonObject> safety = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            JsonObject r = safety.get(safety.size() - 1);
            helper.assertValueEqual(str(r, "action"), "BLOCK", Component.literal("action"));
            helper.assertValueEqual(str(r, "mode"), "on", Component.literal("mode"));
            helper.assertValueEqual(str(r, "overall"), "CLEARLY", Component.literal("overall"));
            helper.assertTrue(wishRecordsFor(w.wish().shortId()).stream()
                            .anyMatch(x -> "BLOCKED".equals(str(x, "outcome"))),
                    Component.literal("no wish record with outcome BLOCKED — the number a parent would ask for is "
                            + "counted from that row and nothing else"));
        });
    }

    // ── (b) a LIKELY wish runs on the redirect, and the child read it first ───────────────────

    /**
     * §2's "nothing is ever quietly substituted", as two facts that must both hold.
     *
     * <p>The agent building the redirect is worth nothing on its own — that is a substitution — and
     * the child being told is worth nothing on its own either, because he would have been told
     * about something that did not happen. The pair is the promise.
     */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_redirect", timeoutTicks = 600)
    public static void aLikelyWishRunsOnTheRedirectAndTheChildWasToldFirst(GameTestHelper helper) {
        Wishing w = begin(helper, "likely-redirected");
        String safer = "a zombie that bonks villagers with a rubber chicken";
        grades(verdict(SafetyVerdict.Band.LIKELY, safer, SafetyVerdict.Reason.GORE));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(), Component.literal("a redirect is not a refusal: " + result.text()));

            // THE AGENT GETS THE REDIRECT AND NOT THE ORIGINAL. Both halves: a worker that can see
            // both will build the first one.
            helper.assertTrue(result.text().contains(safer),
                    Component.literal("the agent was not handed the redirect: " + result.text()));
            helper.assertTrue(!result.text().contains(w.wish().text()),
                    Component.literal("the agent was handed the original text as well: " + result.text()));

            // THE CHILD READ IT FIRST, verbatim.
            String said = chat(helper, w);
            helper.assertTrue(said.contains(safer),
                    Component.literal("the redirect was never shown to the child; chat carried: " + said));
            helper.assertTrue(said.contains(SafetyWords.REDIRECT_PREFIX.strip()),
                    Component.literal("shown, but not as an offer: " + said));

            // AND THE WISH STILL HAPPENS.
            Wish after = WishBook.get(helper.getLevel().getServer()).byId(w.wish().id())
                    .orElse(null);
            helper.assertTrue(after != null && after.status() != WishStatus.REJECTED,
                    Component.literal("a redirected wish must not be rejected; status "
                            + (after == null ? "gone" : after.status())));

            List<JsonObject> safety = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            JsonObject r = safety.get(safety.size() - 1);
            helper.assertValueEqual(str(r, "action"), "REDIRECT", Component.literal("action"));
            helper.assertTrue(r.has("redirectShown") && r.get("redirectShown").getAsBoolean(),
                    Component.literal("redirectShown is the only place §2's promise stays checkable afterwards"));
            helper.assertValueEqual(str(r, "redirect"), safer, Component.literal("redirect"));
        });
    }

    // ── (b2) a MAYBE passes, and its guidance reaches the agent ───────────────────────────────

    /**
     * §2's MAYBE row: the wish is granted as typed and one line rides out on the wake.
     *
     * <p>Its own test because the two halves fail independently and only one of them is visible.
     * {@code SafetyInputGateTest} settles the WORDING; what it cannot see is whether the string
     * reaches the text the agent actually reads — and a guidance line that is computed, logged and
     * then dropped on the floor looks exactly like a feature that works.
     *
     * <p>None of CS-S2's three Gate C½ runs produced a MAYBE, so this is the only place the wiring
     * is exercised at all.
     */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_note", timeoutTicks = 600)
    public static void aMaybeWishIsGrantedAsTypedWithGuidanceOnTheWake(GameTestHelper helper) {
        Wishing w = begin(helper, "maybe-guided");
        grades(verdict(SafetyVerdict.Band.MAYBE, "", SafetyVerdict.Reason.GORE));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(), Component.literal("a MAYBE is not a refusal: " + result.text()));

            // THE CHILD'S OWN WISH, unchanged. §2: a MAYBE wish is his and stays his.
            helper.assertTrue(result.text().contains(w.wish().text()),
                    Component.literal("the wish was not handed over as typed: " + result.text()));
            // AND THE LINE REACHED THE AGENT.
            helper.assertTrue(result.text().contains(SafetyInputGate.guidanceFor(
                            verdict(SafetyVerdict.Band.MAYBE, "", SafetyVerdict.Reason.GORE))),
                    Component.literal("the guidance never reached the wake text: " + result.text()));

            // NOTHING WAS SAID TO THE CHILD. §5: he is never told what was wrong with his wish,
            // and a MAYBE is not even a "wrong" — it is a note to the builder.
            helper.assertTrue(chat(helper, w).isBlank(),
                    Component.literal("the child was told about a MAYBE: " + chat(helper, w)));

            List<JsonObject> safety = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            helper.assertValueEqual(str(safety.get(safety.size() - 1), "action"), "NOTE", Component.literal("action"));
        });
    }

    // ── (c) a child in distress is never refused, whatever the model says ─────────────────────

    /**
     * The lexicon's WELLBEING flag beating a model CLEARLY (§1, and CS-S1b's binding finding).
     *
     * <p>CS-S1b measured the model calling that row {@code CLEARLY} one time in three after three
     * prompt iterations, which is why the rule is in the deterministic layer: no prompt can be
     * relied on for the one case §1 says is never refused.
     *
     * <p>The lexicon is the REAL shipped list rather than a stub, so this also asserts the list
     * still answers WELLBEING to a phrasing a child might really type — a term quietly moved to the
     * DANGER axis would turn this into a block, and nothing else would notice.
     */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_wellbeing", timeoutTicks = 600)
    public static void aWellbeingWishIsNeverBlockedAndWritesAGrownUpNote(GameTestHelper helper) {
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);

        // A phrasing the SHIPPED lexicon answers WELLBEING to, with a unique tail so no other test
        // and no memoised grade can be what is being measured.
        String text = "make me a knife to hurt myself " + UUID.randomUUID().toString()
                .substring(0, 8);
        helper.assertTrue(SafetyLexicon.shared().check(text)
                        .map(SafetyVerdict::wellbeing).orElse(false),
                Component.literal("the shipped lexicon no longer flags this as WELLBEING, so the precedence rule "
                        + "below is being asserted against nothing: " + text));

        Wish wish = WishBook.get(p.getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().getName(),
                p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        Wishing w = new Wishing(p, wish, queue, new StringBuilder());
        tidyWhenTheTestEnds(helper, w);

        // THE MODEL GETS IT WRONG, exactly as measured.
        grades(new SafetyVerdict(SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.CLEARLY, false,
                List.of(SafetyVerdict.Reason.SELF_HARM), "", SafetyVerdict.Provenance.HAIKU,
                SafetyVerdict.NO_STAGE));
        long notesBefore = countGrownUpNotes();
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(queue, blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("a child in distress was REFUSED, which §1 says is the worst possible answer: "
                            + result.text()));
            helper.assertTrue(result.text().contains(text),
                    Component.literal("the wish was not handed over: " + result.text()));

            Wish after = WishBook.get(helper.getLevel().getServer()).byId(wish.id()).orElse(null);
            helper.assertTrue(after != null && after.status() != WishStatus.REJECTED,
                    Component.literal("the wish was rejected; status " + (after == null ? "gone" : after.status())));

            List<JsonObject> safety = safetyRecordsFor(wish.shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + wish.shortId()));
            JsonObject r = safety.get(safety.size() - 1);
            helper.assertTrue(!"BLOCK".equals(str(r, "action")) && !"REDIRECT".equals(
                            str(r, "action")),
                    Component.literal("the layer recorded a refusal for a wellbeing wish: " + str(r, "action")));
            helper.assertTrue(r.has("wellbeing") && r.get("wellbeing").getAsBoolean(),
                    Component.literal("the flag did not survive into the record, so nothing would write the note"));
            helper.assertTrue(countGrownUpNotes() > notesBefore,
                    Component.literal("no grown-up note was written — the note IS the answer here, and a wish "
                            + "answered kindly with nobody told is the whole feature missing"));
        });
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

    // ── (d) what an ungradeable wish does, per config ─────────────────────────────────────────

    /**
     * {@code closed} refuses an ungradeable wish, and says so in the outage register.
     *
     * <p>The §12.2 seam. Both arms are driven, in two tests rather than one: the config is a
     * process-wide read and the two cannot overlap inside a single {@code succeedWhen}, whose body
     * re-runs every tick. The DIFFERENCE between them — that the dial is read at all rather than
     * returning a constant — is asserted in {@code SafetyInputGateTest}, where it costs nothing;
     * what each of these adds is that the wiring downstream of the decision really happens.
     *
     * <p>Each therefore also asserts the dial it set is the one being read, so neither can pass
     * against a config that ignored it.
     */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_closed", timeoutTicks = 600)
    public static void closedRefusesAnUngradeableWish(GameTestHelper helper) {
        Wishing w = begin(helper, "unavailable-closed");
        System.setProperty(UNAVAILABLE, "closed");
        RuntimeConfig.invalidate();
        helper.assertTrue(ChildSafetyConfig.unavailableClosed(),
                Component.literal("the dial did not take, so this test would pass for the wrong reason"));
        // The shape EVERY failure path produces: no CLI, a timeout, a reply nobody could read.
        grades(SafetyVerdict.unavailable());
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(result.isError(),
                    Component.literal("`closed` must refuse an ungradeable wish: " + result.text()));
            helper.assertTrue(!result.text().contains(w.wish().text()),
                    Component.literal("the wish text reached the agent anyway: " + result.text()));

            // AN OUTAGE MUST SOUND LIKE AN OUTAGE. "Moddy needs a rest" is a statement about
            // the world; the block wording is a statement about the wish, and a child who cannot
            // hear the difference learns the wrong lesson from a flaky subprocess.
            String said = chat(helper, w);
            helper.assertTrue(said.contains(SafetyWords.sayUnavailable()),
                    Component.literal("chat carried: " + said));
            helper.assertTrue(!said.contains(SafetyWords.sayBlocked()),
                    Component.literal("he was told his wish was the problem: " + said));

            List<JsonObject> safety = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            helper.assertValueEqual(str(safety.get(safety.size() - 1), "action"),
                    "UNAVAILABLE_CLOSED", Component.literal("action"));
        });
    }

    /** {@code open} — the opt-out since {@code closed} became the default — passes it, leans on the lexicon, and logs. */
    @GameTest(template = "empty_test", batch = "childsafety_enforce_open", timeoutTicks = 600)
    public static void openAssignsAnUngradeableWish(GameTestHelper helper) {
        Wishing w = begin(helper, "unavailable-open");
        System.setProperty(UNAVAILABLE, "open");
        RuntimeConfig.invalidate();
        helper.assertTrue(!ChildSafetyConfig.unavailableClosed(),
                Component.literal("the dial did not take, so this test would pass for the wrong reason"));
        grades(SafetyVerdict.unavailable());
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicReference<ToolResult> out = await(w.queue(), blew);

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("`open` must pass an ungradeable wish: " + result.text()));
            helper.assertTrue(result.text().contains(w.wish().text()),
                    Component.literal("the wish was not handed over: " + result.text()));

            // AND IT IS STILL A RECORD. "The grader could not run" is not "the grader looked and it
            // was fine", and a log that conflated them could never report an outage -- which is the
            // measurement §12.2 will be decided on.
            List<JsonObject> safety = safetyRecordsFor(w.wish().shortId());
            helper.assertTrue(!safety.isEmpty(), Component.literal("no safety record for " + w.wish().shortId()));
            JsonObject r = safety.get(safety.size() - 1);
            helper.assertValueEqual(str(r, "action"), "UNAVAILABLE_OPEN", Component.literal("action"));
            helper.assertValueEqual(str(r, "overall"), "UNAVAILABLE", Component.literal("overall"));
        });
    }
}
