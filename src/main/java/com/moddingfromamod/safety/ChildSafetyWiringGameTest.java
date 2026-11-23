package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the child-safety input layer, in SHADOW mode</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §3 L0+L1, chunk CS-S1b).
 *
 * <p>Every question here is about WIRING, which is why the grader is stubbed rather than live:
 * "does a CLEARLY input reach {@code Marshal.assign}" is a question about this code, and paying ten
 * seconds and a real model to ask it would quietly turn it into a question about the model. The
 * model's own calibration is measured somewhere else entirely — {@code tools/safety-arm.py} over
 * {@code corpus/safety-wishes.jsonl}, 971 public labelled rows, where a rate has a denominator.
 *
 * <h2>What shadow mode has to prove, and it is mostly a NEGATIVE</h2>
 *
 * <p>§8: shadow "grades and logs everything and acts on nothing", and it ships first so a base rate
 * exists before anything is blocked. So the load-bearing assertion is that a wish graded
 * {@code CLEARLY} is queued, assigned and worked on <b>exactly as it was before this layer
 * existed</b> — a shadow layer that changed an outcome would have quietly become an enforcement
 * layer, and the base rate it produced would be measuring its own interference.
 *
 * <h2>ONE BATCH PER TEST, and every assertion keyed on a wish id</h2>
 *
 * <p>Batches are strictly sequential (CLAUDE.md), and tests WITHIN one batch run concurrently and
 * share every static. These four share <b>two</b> statics that decide their answers — the
 * {@code childsafety} system property and {@link SafetyGrader}'s injected stub — so one batch
 * between them would have the {@code off} test clearing the mode out from under the {@code shadow}
 * ones and vice versa, at a rate that depends on scheduling. That is the failure CLAUDE.md
 * describes as "failing against another test rather than against the code", and it would have read
 * as flakiness in the layer instead of in the harness. A batch each is real serialisation, and four
 * of them is cheap.
 *
 * <p>Even so, nothing here counts log lines or reads "the last record": the action log is one
 * append-only file this whole process writes to, so every assertion finds the record carrying ITS
 * OWN wish id, which is unique per wish and therefore cannot be another test's.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ChildSafetyWiringGameTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";

    /** A wish text no other test and no corpus row can collide with. */
    private static String uniqueWish(String tag) {
        return "make a " + tag + " tower " + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Every {@code safety} record in the log for one wish id.
     *
     * <p>Reads the file rather than a captured sink because the file is what a report, a floor and
     * {@code tools/safety-report.py} all read — §6's record shape is a promise about THIS file, and
     * a test that asserted on an in-memory object would pass over a record that never reached disk.
     */
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

    private static ServerPlayer player(GameTestHelper helper) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    private static void tidy(ServerPlayer p) {
        SafetyGrader.forTest(null);
        System.clearProperty(MODE);
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        if (p != null) {
            com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        }
    }

    // ── (a) shadow mode changes NOTHING about where the wish goes ─────────────────────────────

    /**
     * A wish the grader calls CLEARLY is still escalated, exactly as before.
     *
     * <p>This is the assertion that makes "shadow" a real word rather than a config value. It would
     * go red the moment somebody wired CS-S2's refusal in without moving the mode, which is the one
     * mistake that would silently invalidate every base-rate number measured in the meantime.
     */
    @GameTest(template = "empty_test", batch = "childsafety_escalate")
    public static void aClearlyWishIsStillEscalatedInShadowMode(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(MODE, "shadow");
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
            SafetyGrader.forTest(text -> new SafetyVerdict(
                    SafetyVerdict.Band.CLEARLY, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                    SafetyVerdict.Band.FINE, false, List.of(SafetyVerdict.Reason.GORE), "",
                    SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE));
            p = player(helper);
            String text = uniqueWish("clearly-graded");
            WishService.Outcome outcome = WishService.submit(p, text, WishSource.COMMAND);
            // ACCEPTED, not a specific outcome. Which of the two acceptances a wish gets is the
            // template matcher's business and is not this layer's to predict -- the first cut
            // demanded PENDING_ESCALATION and went red because "make a ... tower ..." matched a
            // template and came back FULFILLING, which is the layer behaving perfectly. The claim
            // shadow mode actually makes is that the wish was not refused, so that is the claim.
            helper.assertTrue(outcome == WishService.Outcome.PENDING_ESCALATION
                            || outcome == WishService.Outcome.FULFILLING,
                    Component.literal("shadow mode must not refuse a wish; got " + outcome + ". EMPTY or THROTTLED "
                            + "would be a harness problem; anything else means an enforcement path "
                            + "landed without the mode moving with it."));
            helper.succeed();
        } finally {
            tidy(p);
        }
    }

    // ── (b) the record, joined to the wish, saying what enforcement WOULD have done ────────────

    /**
     * The grade is awaited at the dequeue point, logged against the wish, and the wish is assigned.
     *
     * <p>Drives the real {@link AwaitWishTool} on a queue of its own — a test parked on the global
     * queue steals another test's wish, which is the failure that tool's own constructor javadoc
     * records.
     */
    @GameTest(template = "empty_test", batch = "childsafety_dequeue", timeoutTicks = 600)
    public static void theGradeIsLoggedAgainstTheWishAndTheWishIsStillAssigned(
            GameTestHelper helper) {
        System.setProperty(MODE, "shadow");
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        SafetyGrader.forTest(text -> new SafetyVerdict(
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.CLEARLY, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(SafetyVerdict.Reason.SEXUAL), "",
                SafetyVerdict.Provenance.HAIKU, SafetyVerdict.NO_STAGE));

        WishQueue queue = new WishQueue();
        Wish wish = Wish.create(uniqueWish("dequeue-graded"), WishSource.COMMAND,
                System.currentTimeMillis(), java.util.UUID.randomUUID(), "mfam-mock",
                "minecraft:overworld", 0, 64, 0);
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));

        // ON ITS OWN THREAD, and that is the faithful arrangement rather than a workaround.
        // await_wish is an MCP HTTP handler in production and hops to the server thread partway
        // through (AgentBridge.onServer) to read the world; driven straight from a GameTest it is
        // ALREADY the server thread, and the bridge's own deadlock guard refuses — which is how
        // the first cut of this test failed, with a message about deadlock and nothing about
        // safety. Off-thread plus succeedWhen means the server keeps ticking and can serve the
        // hop, exactly as it does for a real worker.
        java.util.concurrent.atomic.AtomicReference<com.moddingfromamod.agent.ToolResult> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> blew =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                out.set(new AwaitWishTool(queue).call(new JsonObject()));
            } catch (Throwable t) {
                blew.set(t);
            } finally {
                // Cleared HERE rather than in a finally around the whole test: succeedWhen returns
                // immediately and re-runs its body per tick, so there is no scope that outlives
                // the assertion. The assertions read the LOG FILE, which is already written by the
                // time this runs, so dropping the stub now costs them nothing — and leaving a stub
                // behind for a later batch is the cross-test failure this file's batch split is
                // about.
                SafetyGrader.forTest(null);
                // And the shadow mode with it: left set, it switched the lexicon's enforcement off for every
                // later batch, which BuilderFindGameTest's refused term found once the public cut's order put it after.
                System.clearProperty(MODE);
                com.moddingfromamod.runtime.RuntimeConfig.invalidate();
            }
        }, "mfam-test-await");
        worker.setDaemon(true);
        worker.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null,
                    Component.literal("await_wish threw: " + blew.get()));
            com.moddingfromamod.agent.ToolResult result = out.get();
            helper.assertTrue(result != null, Component.literal("await_wish has not returned yet"));
            helper.assertTrue(!result.isError(),
                    Component.literal("await_wish refused the wish in shadow mode: " + result.text()));
            helper.assertTrue(result.text().contains(wish.text()),
                    Component.literal("await_wish did not hand the wish over; shadow mode must assign exactly as "
                            + "before. Got: " + result.text()));

            List<JsonObject> records = safetyRecordsFor(wish.shortId());
            helper.assertTrue(!records.isEmpty(),
                    Component.literal("no safety record was written for wish " + wish.shortId()
                            + " — the grade is the whole instrument, and a shadow run that logs "
                            + "nothing is indistinguishable from a quiet evening"));
            JsonObject r = records.get(records.size() - 1);
            helper.assertValueEqual(str(r, "layer"), "input", Component.literal("layer"));
            helper.assertValueEqual(str(r, "action"), "SHADOW_BLOCK", Component.literal("action"));
            helper.assertValueEqual(str(r, "mode"), "shadow", Component.literal("mode"));
            helper.assertValueEqual(str(r, "overall"), "CLEARLY", Component.literal("overall"));
        });
    }

    // ── (c) the lexicon answers inside submit, with no wait anywhere ──────────────────────────

    /**
     * The lexicon's verdict is on disk by the time {@code submit} returns.
     *
     * <p>Nothing sleeps and nothing polls here, deliberately: L0 is a regex sweep over one short
     * string and the record must exist the instant the method returns. If it ever becomes
     * asynchronous this test goes red rather than going slow, which is the distinction that
     * matters — the position rule (§3: before the rate limiter, before the web prefetch) only
     * works because it is synchronous.
     */
    @GameTest(template = "empty_test", batch = "childsafety_lexicon")
    public static void theLexiconLogsSynchronouslyFromSubmit(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(MODE, "shadow");
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
            // The MODEL is stubbed to say FINE, so anything logged with grader=lexicon can only
            // have come from L0 — a stub that agreed with the lexicon would prove nothing.
            SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
            p = player(helper);
            // A term from the shipped list, in a sentence a child would not type. It is here for
            // the WIRING; which terms belong on the list is lexicon.txt's own question.
            String text = "a cocaine machine " + java.util.UUID.randomUUID().toString()
                    .substring(0, 8);
            WishService.submit(p, text, WishSource.COMMAND);

            String wishId = lastWishIdFor(helper, text);
            helper.assertTrue(!wishId.isEmpty(), Component.literal("the wish was never recorded in the book"));
            List<JsonObject> records = safetyRecordsFor(wishId);
            boolean lexicon = records.stream().anyMatch(r -> "lexicon".equals(str(r, "grader")));
            helper.assertTrue(lexicon,
                    Component.literal("the lexicon's verdict was not on disk when submit returned — found "
                            + records.size() + " safety record(s) for " + wishId));
            helper.succeed();
        } finally {
            tidy(p);
        }
    }

    /**
     * <b>A lexicon CLEARLY is refused AT THE FRONT DOOR, and costs the child nothing</b> — the
     * enforcing arm of the test above, which the audit found un-driven: nothing asserted that
     * {@code submit} really returns {@code BLOCKED}. Position is the guarantee (§3): no rate-limit
     * token, no book entry, and so no queue, no bar and no web search — all of which happen below
     * the refusal in {@code submit}.
     */
    @GameTest(template = "empty_test", batch = "childsafety_lexicon_block")
    public static void aLexiconClearlyIsRefusedAtTheFrontDoor(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(MODE, "on");
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
            // The model says FINE, so a refusal can only be the lexicon's.
            SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
            p = player(helper);
            WishService.forget(p);
            int tokensBefore = WishService.wishesLeft(p);
            String text = "a cocaine machine " + java.util.UUID.randomUUID().toString()
                    .substring(0, 8);
            WishService.Outcome outcome = WishService.submit(p, text, WishSource.COMMAND);

            helper.assertValueEqual(outcome, WishService.Outcome.BLOCKED, Component.literal("submit outcome"));
            helper.assertValueEqual(WishService.wishesLeft(p), tokensBefore,
                    Component.literal("a refused wish must not cost a rate-limit token"));
            helper.assertTrue(lastWishIdFor(helper, text).isEmpty(),
                    Component.literal("a refused wish must never reach the book — nothing to queue, bar or research"));
            helper.succeed();
        } finally {
            tidy(p);
        }
    }

    // ── (d) the control arm writes nothing at all ─────────────────────────────────────────────

    /**
     * {@code childsafety=off} grades nothing, logs nothing and costs nothing.
     *
     * <p>The A/B control has to be a real absence rather than a quieter version of the treatment:
     * a control arm that still paid for a grade would make every latency and cost comparison
     * between the arms meaningless, and one that still wrote records would put the treatment's own
     * rows in the control's denominator.
     */
    @GameTest(template = "empty_test", batch = "childsafety_off")
    public static void nothingIsGradedOrLoggedWhenTheArmIsOff(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(MODE, "off");
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
            SafetyGrader.forTest(text -> new SafetyVerdict(
                    SafetyVerdict.Band.CLEARLY, SafetyVerdict.Band.CLEARLY,
                    SafetyVerdict.Band.CLEARLY, SafetyVerdict.Band.CLEARLY, true,
                    List.of(SafetyVerdict.Reason.GORE), "", SafetyVerdict.Provenance.HAIKU,
                    SafetyVerdict.NO_STAGE));
            p = player(helper);
            String text = "a cocaine machine " + java.util.UUID.randomUUID().toString()
                    .substring(0, 8);
            WishService.submit(p, text, WishSource.COMMAND);
            String wishId = lastWishIdFor(helper, text);
            helper.assertTrue(!wishId.isEmpty(), Component.literal("the wish was never recorded in the book"));
            List<JsonObject> records = safetyRecordsFor(wishId);
            helper.assertTrue(records.isEmpty(),
                    Component.literal("the control arm wrote " + records.size() + " safety record(s) for " + wishId
                            + "; `off` must be an absence, not a quieter treatment"));
            helper.succeed();
        } finally {
            tidy(p);
        }
    }

    /** The short id the book gave this exact text, or "". */
    private static String lastWishIdFor(GameTestHelper helper, String text) {
        var book = com.moddingfromamod.wish.WishBook.get(helper.getLevel().getServer());
        for (Wish w : book.recent(200)) {
            if (text.equals(w.text())) {
                return w.shortId();
            }
        }
        return "";
    }
}
