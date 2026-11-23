package com.moddingfromamod.safety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The plan-time decision, with no model, no marshal, no server and no child.</b>
 *
 * <p>{@code SafetyPlanGate.decide} is pure by construction so that the whole PLAN column of
 * {@code docs/CHILD_SAFETY_PLAN.md} §2 is answerable in milliseconds — including the two rows that
 * are otherwise expensive to reach: the SECOND divert (which in a live run needs an agent that
 * re-plans) and shadow mode (which by definition does nothing observable).
 *
 * <p>Every grade comes through {@link SafetyGrader#forTest}, so what is being tested is the policy
 * and not Haiku's opinion. The live calibration is {@code tools/safety-arm.py}'s job.
 */
class SafetyPlanGateTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String STRICTNESS = ModdingFromAModMod.MODID + ".childSafetyStrictness";
    private static final String HOME = ModdingFromAModMod.MODID + ".home";

    /** The wish under test. Keyed by WISH since decision 12.11 — see {@link SafetyRetryBudget}. */
    private static final UUID WISH = UUID.nameUUIDFromBytes("steve-plan-gate".getBytes());

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(HOME);
        System.setProperty(HOME, dir.toString());
        // The ENFORCING arm, explicitly: the shipped default is SHADOW (ChildSafetyConfig.mode),
        // and every decision this file asserts is what enforcement does. The shadow test below
        // sets "shadow" itself.
        System.setProperty(MODE, "on");
        System.clearProperty(STRICTNESS);
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyRetryBudget.clearAll();
        SafetyGrader.forTest(null);
    }

    @AfterEach
    void restore() {
        if (homeBefore == null) {
            System.clearProperty(HOME);
        } else {
            System.setProperty(HOME, homeBefore);
        }
        System.clearProperty(MODE);
        System.clearProperty(STRICTNESS);
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyRetryBudget.clearAll();
        SafetyGrader.forTest(null);
    }

    // ---- fixtures ----

    private static void set(String key, String value) {
        System.setProperty(key, value);
        RuntimeConfig.invalidate();
    }

    /** Every grade answers this, whatever the text. */
    private static void grades(SafetyVerdict v) {
        SafetyGrader.forTest(text -> v);
    }

    private static SafetyVerdict band(SafetyVerdict.Band violence, int stage, String redirect,
                                      SafetyVerdict.Reason... reasons) {
        return new SafetyVerdict(violence, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(reasons), redirect,
                SafetyVerdict.Provenance.HAIKU, stage);
    }

    private static JsonObject plan(String... steps) {
        JsonObject args = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String s : steps) {
            arr.add(s);
        }
        args.add("steps", arr);
        args.addProperty("status", "Working out how to make this");
        return args;
    }

    private static SafetyPlanGate.Decision decide(JsonObject args) {
        return decide(args, WISH);
    }

    /** One wish's worth of plan grading. The budget is per WISH, so the id is the fixture. */
    private static SafetyPlanGate.Decision decide(JsonObject args, UUID wishUuid) {
        return SafetyPlanGate.decide("plan_wish", args, "make a scary villager", "abcd1234",
                wishUuid);
    }

    /** Every {@code safety} record this test's own home has collected, oldest first. */
    private static List<JsonObject> safetyRecords() throws Exception {
        Path f = ActionLog.path();
        List<JsonObject> out = new ArrayList<>();
        if (f == null || !Files.exists(f)) {
            return out;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if ("safety".equals(o.get("kind").getAsString())) {
                out.add(o);
            }
        }
        return out;
    }

    // ---- the tool this layer looks at, and the ones it does not ----

    @Test
    void everyOtherToolIsWavedThroughWithoutAGrade() {
        // A model round trip per tool call would add minutes to a wish (§3 L2). The plan is where
        // intent is stated in full and is the cheapest place to catch it; every other tool's
        // child-visible text is L0's job.
        grades(band(SafetyVerdict.Band.CLEARLY, 0, ""));
        for (String tool : List.of("build_structure", "sculpt_creature", "run_command",
                "finish_wish", "await_wish", "notify_player")) {
            SafetyPlanGate.Decision d = SafetyPlanGate.decide(
                    tool, plan("do the thing"), "a wish", "abcd1234", WISH);
            assertSame(SafetyPlanGate.Decision.PASS, d, tool + " must not be graded at this layer");
        }
    }

    @Test
    void theControlArmNeitherGradesNorLogs() throws Exception {
        set(MODE, "off");
        grades(band(SafetyVerdict.Band.CLEARLY, 0, ""));
        assertSame(SafetyPlanGate.Decision.PASS, decide(plan("paint blood everywhere")));
        // Not merely "it passed" -- a control arm that silently keeps grading costs the same money
        // and makes the A/B control-versus-control (ChildSafetyArmTest's own argument).
        assertTrue(safetyRecords().isEmpty(), "the off arm must write no safety record");
    }

    // ---- the four bands ----

    @Test
    void aFinePlanPassesAndIsStillLogged() throws Exception {
        grades(SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        SafetyPlanGate.Decision d = decide(plan("Build the villager", "Give him a spooky laugh"));
        assertEquals(SafetyPlanGate.Act.PASS, d.act());
        assertFalse(d.refuses());
        assertTrue(d.message().isEmpty());
        // The denominator. A log that recorded only the refusals could not produce a
        // false-positive rate, which is the number this whole layer lives or dies on (§6).
        List<JsonObject> records = safetyRecords();
        assertEquals(1, records.size());
        assertEquals("PASS", records.get(0).get("action").getAsString());
        assertEquals("plan", records.get(0).get("layer").getAsString());
        assertEquals("plan_wish", records.get(0).get("tool").getAsString());
        assertEquals("FINE", records.get(0).get("overall").getAsString());
        assertEquals(0, records.get(0).get("retry").getAsInt());
    }

    @Test
    void aMaybePlanIsBuiltAndCarriesItsGuidanceOutOnTheResult() {
        grades(band(SafetyVerdict.Band.MAYBE, -1, "", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Build the villager", "Give him cartoon guts"));
        assertEquals(SafetyPlanGate.Act.NOTE, d.act());
        assertFalse(d.refuses(), "a MAYBE plan is the child's own plan and gets built");

        ToolResult out = SafetyPlanGate.annotate(ToolResult.ok("Plan set, 2 step(s)."), d);
        assertFalse(out.isError());
        assertTrue(out.text().startsWith("Plan set, 2 step(s)."),
                "the tool's own answer must still be the first thing the agent reads: " + out.text());
        assertTrue(out.text().contains("cartoon"), "the violence guidance should ride out: " + out.text());
        assertTrue(out.text().contains("not a refusal"),
                "an agent told only 'keep it cartoon' after a plan may read it as a rejection and "
                        + "re-plan for nothing: " + out.text());
    }

    @Test
    void theGuidanceFollowsTheAXISThatEarnedTheBand() {
        // Per axis, because that is what the advice differs on. Four bands with one sentence
        // between them would be a sentence about none of them.
        assertTrue(SafetyPlanGate.guidanceFor(band(SafetyVerdict.Band.MAYBE, -1, ""))
                .contains("cartoon"));
        assertTrue(SafetyPlanGate.guidanceFor(new SafetyVerdict(
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.MAYBE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(), "",
                SafetyVerdict.Provenance.HAIKU, -1)).contains("gross-out"));
        assertTrue(SafetyPlanGate.guidanceFor(new SafetyVerdict(
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE, SafetyVerdict.Band.MAYBE,
                SafetyVerdict.Band.FINE, false, List.of(), "",
                SafetyVerdict.Provenance.HAIKU, -1)).contains("kind"));
        assertTrue(SafetyPlanGate.guidanceFor(new SafetyVerdict(
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.MAYBE, false, List.of(), "",
                SafetyVerdict.Provenance.HAIKU, -1)).contains("Minecraft"));
    }

    @Test
    void aLikelyPlanIsDivertedAndTheChildIsNeverShownIt() throws Exception {
        grades(band(SafetyVerdict.Band.LIKELY, 1,
                "a cartoon fright — a green glow and a wobble, no blood",
                SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Build the villager",
                "Paint blood pooling under him", "Make the screaming loop"));
        assertEquals(SafetyPlanGate.Act.DIVERT, d.act());
        assertTrue(d.refuses(), "the tool body must never run, or WishFeedback.planned shows the "
                + "child the stage that is about to be diverted");
        assertEquals("DIVERT", safetyRecords().get(0).get("action").getAsString());
    }

    @Test
    void theDivertNamesTheStageByIndexAndByItsOwnWordsAndSaysWhatToDoInstead() {
        // RefusalRedirectTest's rule: a refusal that names no alternative makes the agent
        // improvise, which cost 21s of a 64s build the day it was measured.
        String msg = SafetyPlanGate.divertMessage(
                band(SafetyVerdict.Band.LIKELY, 1, "a cartoon fright, no blood",
                        SafetyVerdict.Reason.GORE),
                List.of("Build the villager", "Paint blood pooling under him", "Loop the scream"));
        assertTrue(msg.contains("Step 1"), "by INDEX, so it cannot be misidentified: " + msg);
        assertTrue(msg.contains("Paint blood pooling under him"),
                "…and by its own TEXT, so an agent that renumbered cannot fix the wrong one: " + msg);
        assertTrue(msg.contains("gore"), "the agent needs the reason to re-plan: " + msg);
        assertTrue(msg.contains("a cartoon fright, no blood"),
                "the grader saw the plan and we did not — use ITS alternative: " + msg);
        assertTrue(msg.contains("plan_wish again"), "say what to do next: " + msg);
        assertTrue(msg.contains("did NOT run"),
                "an agent that thinks the plan landed will build against a bar that does not "
                        + "exist: " + msg);
    }

    @Test
    void theDivertStillNamesAnAlternativeWhenTheGraderProposedNone() {
        // The empty-redirect case is the one that matters: it is exactly when an agent has nothing
        // to go on and starts inventing, which is worse than the step objected to.
        String msg = SafetyPlanGate.divertMessage(
                band(SafetyVerdict.Band.LIKELY, 0, "", SafetyVerdict.Reason.CRUELTY),
                List.of("Make the villager beg"));
        assertTrue(msg.contains("Re-plan"), msg);
        assertTrue(msg.contains("cartoon version"),
                "a generic alternative is still an alternative: " + msg);
    }

    @Test
    void aDivertNeverAsksTheAgentToEXPLAINITSELFToTheChild() {
        // §5: a child is never told what was wrong with his wish. Naming the category is both a
        // lesson in vocabulary he did not have and an invitation to see how close he can get — and
        // an agent left to its own devices narrates its refusals cheerfully.
        String msg = SafetyPlanGate.divertMessage(
                band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE),
                List.of("Make it gory"));
        assertTrue(msg.contains("Do NOT tell the child"), msg);
        assertTrue(msg.contains("do not mention safety"), msg);
    }

    @Test
    void aClearlyPlanEndsTheWishWithoutSpendingTheDivertBudget() throws Exception {
        grades(band(SafetyVerdict.Band.CLEARLY, 0, "", SafetyVerdict.Reason.SEXUAL));
        SafetyPlanGate.Decision d = decide(plan("Do the unambiguous thing"));
        assertEquals(SafetyPlanGate.Act.STOP, d.act());
        assertTrue(d.refuses());
        assertTrue(d.message().contains("await_wish"),
                "the agent is in the marshal's winding-down set and every other tool will be "
                        + "refused — say so once, here: " + d.message());
        // A CLEARLY gets no second chance, so it has no business spending the budget that buys
        // one. Asserting it keeps the two rows of §2's table genuinely separate.
        assertFalse(SafetyRetryBudget.alreadyDiverted(WISH));
        assertEquals("STOP", safetyRecords().get(0).get("action").getAsString());
    }

    @Test
    void aClearlyPlanIsNeverOfferedARewording() {
        // §2: a CLEARLY wish gets no clever rewording, because that invites "how close can I get".
        // The verdict's own redirectIfUsable enforces it; this pins that the message agrees.
        grades(band(SafetyVerdict.Band.CLEARLY, 0, "try it slightly differently",
                SafetyVerdict.Reason.SEXUAL));
        SafetyPlanGate.Decision d = decide(plan("Do the unambiguous thing"));
        assertFalse(d.message().contains("try it slightly differently"), d.message());
        assertFalse(d.message().contains("Re-plan"), d.message());
    }

    // ---- the retry budget ----

    @Test
    void theSecondDivertOfOneWishEndsIt() throws Exception {
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.DIVERT, decide(plan("Make it gory")).act());
        // The agent came back with a plan that leans the same way. A layer that can divert for
        // ever is a child watching a bar that never moves.
        SafetyPlanGate.Decision second = decide(plan("Make it gory, but bigger"));
        assertEquals(SafetyPlanGate.Act.STOP, second.act());
        assertTrue(second.message().contains("this wish is over"), second.message());

        List<JsonObject> records = safetyRecords();
        assertEquals(2, records.size());
        assertEquals(0, records.get(0).get("retry").getAsInt());
        assertEquals(1, records.get(1).get("retry").getAsInt(),
                "the retry number is what lets a report count how often one chance was enough");
    }

    @Test
    void aFinePlanAfterADivertIsLoggedAsTheRetryItIs() throws Exception {
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        decide(plan("Make it gory"));
        grades(SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        assertEquals(SafetyPlanGate.Act.PASS, decide(plan("Bonk him with a rubber chicken")).act());

        List<JsonObject> records = safetyRecords();
        assertEquals(2, records.size());
        // THE most interesting record in the file: a divert that worked. Reading the slot without
        // spending it is what makes this row exist at all.
        assertEquals("PASS", records.get(1).get("action").getAsString());
        assertEquals(1, records.get(1).get("retry").getAsInt());
    }

    @Test
    void theBudgetIsPerWISH() {
        // ⚠ Decision 12.11. It used to be per CHILD, through BuildGate.forPlayer, and that was
        // wrong in BOTH directions at wishslots > 1: WishProgress.started calls
        // BuildGate.startWish(playerId), which REPLACES the gate — so a second wish starting
        // forgot the first one's spent divert (a stop that never happens), while between those
        // resets the two wishes shared one flag (a stop that happens to the wrong wish).
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.DIVERT, decide(plan("Make it gory")).act());

        // A SECOND WISH — same child, in flight at the same time. Its own budget, so it is offered
        // the re-plan this one has already used. Under the old per-player key this was a STOP, and
        // the evidence ending it belonged to a different wish entirely.
        UUID second = UUID.nameUUIDFromBytes("a-second-wish-of-the-same-child".getBytes());
        assertEquals(SafetyPlanGate.Act.DIVERT, decide(plan("Make it gory"), second).act(),
                "each wish gets its own one chance; a budget spent by a sibling wish would stop "
                        + "this one on its first offence");

        // …and the first wish's budget is still spent, which is the half the old key LOST: nothing
        // about starting the second wish may hand the first one a second divert.
        assertTrue(SafetyRetryBudget.alreadyDiverted(WISH));
        assertEquals(SafetyPlanGate.Act.STOP, decide(plan("Make it gory, but bigger")).act(),
                "the first wish has had its chance, and a second wish starting must not give it "
                        + "another — that is the reset the per-player key performed");

        // The second wish is on its own clock: one divert spent, so its next lean stops it too.
        assertEquals(SafetyPlanGate.Act.STOP,
                decide(plan("Make it gory, but bigger"), second).act());
    }

    @Test
    void aDifferentWishOfADifferentChildIsADifferentBudgetToo() {
        // The property the old per-player key did get right, kept: one person's wish must never be
        // able to end another's. It follows from the per-wish key (two people cannot share a wish
        // id) rather than being enforced separately, which is why it is asserted rather than
        // assumed.
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.DIVERT, decide(plan("Make it gory")).act());

        UUID somebodyElsesWish = UUID.nameUUIDFromBytes("someone-else".getBytes());
        assertEquals(SafetyPlanGate.Act.DIVERT, SafetyPlanGate.decide(
                "plan_wish", plan("Make it gory"), "a wish", "ffff0000", somebodyElsesWish).act());
    }

    @Test
    void aWishWithNoOwnerIsStillDivertedRatherThanStopped() {
        // Gate C½ and the headless probe have no player. Falling through to STOP because there is
        // nobody to hold a budget would end every objectionable plan on its first offence in
        // exactly the environment the arms are measured in.
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.DIVERT, SafetyPlanGate.decide(
                "plan_wish", plan("Make it gory"), "a wish", "abcd1234", null).act());
    }

    // ---- the parent's dial ----

    @Test
    void strictPromotesAMaybePlanToADivert() {
        set(STRICTNESS, "strict");
        grades(band(SafetyVerdict.Band.MAYBE, 0, "", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.DIVERT, decide(plan("Give him cartoon guts")).act());
    }

    @Test
    void relaxedDemotesADivertToGuidance() {
        set(STRICTNESS, "relaxed");
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Make it gory"));
        assertEquals(SafetyPlanGate.Act.NOTE, d.act(), "relaxed is log-and-guide, never silence");
        assertFalse(d.refuses());
    }

    @Test
    void theLogCARRIESTheRawBandWhileTheACTIONFollowsTheDial() throws Exception {
        // CS-REVIEW-2. The plan layer used to log the strictness-ADJUSTED verdict while the input
        // layer logged the raw one, and `SafetyInputGate.Decision#verdict` states the rule both
        // are meant to follow: logging the adjusted bands would make every measured base rate a
        // function of one machine's dial, so a floor recorded on `standard` could not be compared
        // with a run on `strict`. Nothing is lost by logging raw -- `action` says what was DONE
        // and `strictness` is in the same record, so the adjusted band is recoverable and the raw
        // band would not have been.
        set(STRICTNESS, "strict");
        grades(band(SafetyVerdict.Band.MAYBE, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Give him cartoon guts"));

        assertEquals(SafetyPlanGate.Act.DIVERT, d.act(), "strict promotes MAYBE -> LIKELY");
        assertEquals(SafetyVerdict.Band.LIKELY, d.verdict().overall(),
                "Decision#verdict is the ADJUSTED grade -- that is what the branches decided on");

        List<JsonObject> records = safetyRecords();
        assertEquals(1, records.size(), "one grade, one record");
        JsonObject o = records.get(0);
        assertEquals("MAYBE", o.get("overall").getAsString(),
                "the log must carry the band the GRADER gave, not the one the dial produced");
        assertEquals("MAYBE", o.getAsJsonObject("bands").get("violence").getAsString(),
                "per axis too -- `bands` is what a report groups by");
        assertEquals("DIVERT", o.get("action").getAsString(),
                "and `action` is what was DONE, which is the adjusted band's answer");
        assertEquals("strict", o.get("strictness").getAsString(),
                "the dial is in the record, which is why logging raw loses nothing");
    }

    @Test
    void andTheSameHoldsWhenTheDialGoesTheOtherWay() throws Exception {
        // The mirror, because a fix that only reads right in one direction is half a fix: relaxed
        // DEMOTES, so a raw LIKELY must still be logged LIKELY beside an action of NOTE.
        set(STRICTNESS, "relaxed");
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        assertEquals(SafetyPlanGate.Act.NOTE, decide(plan("Make it gory")).act());

        JsonObject o = safetyRecords().get(0);
        assertEquals("LIKELY", o.get("overall").getAsString(),
                "the grader said LIKELY and the record has to say so");
        assertEquals("NOTE", o.get("action").getAsString());
        assertEquals("relaxed", o.get("strictness").getAsString());
    }

    @Test
    void neitherDialTouchesCLEARLY() {
        // A dial that could switch off the unambiguous cases would be a different feature.
        set(STRICTNESS, "relaxed");
        grades(band(SafetyVerdict.Band.CLEARLY, 0, "", SafetyVerdict.Reason.SEXUAL));
        assertEquals(SafetyPlanGate.Act.STOP, decide(plan("the unambiguous thing")).act());
    }

    // ---- shadow ----

    @Test
    void shadowModeComputesTheWholeDecisionAndActsOnNothing() throws Exception {
        set(MODE, "shadow");
        grades(band(SafetyVerdict.Band.LIKELY, 1, "a rubber chicken", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Build him", "Make it gory"));

        assertEquals(SafetyPlanGate.Act.PASS, d.act(), "shadow acts on nothing");
        assertFalse(d.refuses());
        assertEquals(SafetyPlanGate.Act.DIVERT, d.assessed(),
                "…and still says what it WOULD have done, which is the only claim shadow can make");
        assertTrue(d.message().isEmpty(),
                "appending guidance an agent reads is an action, so shadow must not do it either");

        // The prefix is what keeps a base rate readable: SHADOW_DIVERT and DIVERT are different
        // facts and a report that pooled them would measure two arms as one.
        assertEquals("SHADOW_DIVERT", safetyRecords().get(0).get("action").getAsString());
        assertEquals("shadow", safetyRecords().get(0).get("mode").getAsString());
    }

    @Test
    void shadowModeSpendsTheBudgetSoItsPREDICTIONStaysTrue() throws Exception {
        set(MODE, "shadow");
        grades(band(SafetyVerdict.Band.LIKELY, 0, "a rubber chicken", SafetyVerdict.Reason.GORE));
        decide(plan("Make it gory"));
        decide(plan("Make it gory again"));
        List<JsonObject> records = safetyRecords();
        // Nothing outside this gate reads the slot, so spending it changes nothing the child or
        // the agent can observe — and a shadow run that logged DIVERT twice would be a base rate
        // predicting behaviour the ON arm does not have.
        assertEquals("SHADOW_DIVERT", records.get(0).get("action").getAsString());
        assertEquals("SHADOW_STOP", records.get(1).get("action").getAsString());
    }

    @Test
    void shadowNeverAnnotatesAResult() {
        set(MODE, "shadow");
        grades(band(SafetyVerdict.Band.MAYBE, -1, "", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Give him cartoon guts"));
        ToolResult plain = ToolResult.ok("Plan set.");
        assertSame(plain, SafetyPlanGate.annotate(plain, d));
    }

    // ---- the grader could not run ----

    @Test
    void anUngradeablePlanPassesAndSaysSo() throws Exception {
        grades(SafetyVerdict.unavailable());
        SafetyPlanGate.Decision d = decide(plan("Build the villager"));
        assertEquals(SafetyPlanGate.Act.UNAVAILABLE, d.act());
        assertFalse(d.refuses(),
                "§2: at PLAN there is no closed mode — the input grade already ran, and ending a "
                        + "wish because a subprocess hiccupped is a refusal about the machine");
        JsonObject record = safetyRecords().get(0);
        assertEquals("UNAVAILABLE", record.get("action").getAsString());
        // Not FINE. "The grader could not run" and "the grader looked and it was fine" are
        // different facts, and a log that conflates them cannot report an outage.
        assertEquals("UNAVAILABLE", record.get("overall").getAsString());
    }

    @Test
    void aGraderThatThrowsIsAnOutageRatherThanARefusal() {
        SafetyGrader.forTest(text -> {
            throw new IllegalStateException("the subprocess was strange");
        });
        SafetyPlanGate.Decision d = decide(plan("Build the villager"));
        assertEquals(SafetyPlanGate.Act.UNAVAILABLE, d.act());
        assertFalse(d.refuses(), "a child must never see a wish break because a grader did");
    }

    // ---- the arguments ----

    @Test
    void aPlanWithNoStepsIsGradedRatherThanSkipped() {
        // `steps` is REQUIRED by the schema, so an absent one is already a tool error — but the
        // gate runs first, and a gate that threw here would turn a malformed call into a broken
        // wish. It grades what it was given.
        grades(band(SafetyVerdict.Band.CLEARLY, 0, "", SafetyVerdict.Reason.SEXUAL));
        JsonObject args = new JsonObject();
        args.addProperty("status", "thinking");
        assertEquals(SafetyPlanGate.Act.STOP,
                SafetyPlanGate.decide("plan_wish", args, "a wish", "abcd1234", WISH).act());
    }

    @Test
    void theStepsAreReadInOrderAndNonStringsAreDropped() {
        JsonObject args = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("first");
        arr.add(new JsonObject());       // the model sent an object where a string belongs
        arr.add("second");
        args.add("steps", arr);
        assertEquals(List.of("first", "second"), SafetyPlanGate.strings(args, "steps"));
        assertEquals(List.of(), SafetyPlanGate.strings(args, "claims"));
        assertEquals("", SafetyPlanGate.string(args, "status"));
    }

    @Test
    void aStageIndexPastTheEndOfThePlanStillProducesAUsableRefusal() {
        // A model that miscounts must not produce a refusal naming a step nobody can find.
        String msg = SafetyPlanGate.divertMessage(
                band(SafetyVerdict.Band.LIKELY, 9, "a rubber chicken", SafetyVerdict.Reason.GORE),
                List.of("Build him"));
        assertTrue(msg.contains("Step 9"), msg);
        assertTrue(msg.contains("Re-plan"), msg);
        assertNotEquals("", msg.strip());
    }

    @Test
    void aWholePlanProblemIsNamedAsOneRatherThanAsStageMinusOne() {
        String msg = SafetyPlanGate.divertMessage(
                band(SafetyVerdict.Band.LIKELY, SafetyVerdict.NO_STAGE, "make it silly",
                        SafetyVerdict.Reason.CRUELTY),
                List.of("Build him", "Be cruel"));
        assertFalse(msg.contains("Step -1"), "\"Step -1\" is not a thing an agent can re-plan: " + msg);
        assertTrue(msg.contains("plan as a whole"), msg);
    }

    // ---- annotate is inert everywhere it should be ----

    @Test
    void annotateLeavesAnErroredResultAlone() {
        grades(band(SafetyVerdict.Band.MAYBE, -1, "", SafetyVerdict.Reason.GORE));
        SafetyPlanGate.Decision d = decide(plan("Give him cartoon guts"));
        ToolResult err = ToolResult.error("Give me at least one step.");
        // The agent has a real problem to read; stacking advice on top buries the thing it has to
        // act on.
        assertSame(err, SafetyPlanGate.annotate(err, d));
    }

    @Test
    void annotateIsInertForEveryActButNOTE() {
        ToolResult plain = ToolResult.ok("Plan set.");
        assertSame(plain, SafetyPlanGate.annotate(plain, SafetyPlanGate.Decision.PASS));
        assertSame(plain, SafetyPlanGate.annotate(plain, null));
    }
}
