package com.moddingfromamod.safety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code safety} record, written to a home of this test's own.
 *
 * <p>§6 is the whole instrument: over-blocking has to fall out of the telemetry rather than out of
 * a child telling somebody. That only works if the record carries its denominator — every band,
 * including FINE — and if the fields a report groups by are actually there. A log that recorded
 * only the refusals would make the false-positive rate, the number this layer lives or dies on,
 * uncomputable.
 */
class SafetyActionLogTest {

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(ModdingFromAModMod.MODID + ".home");
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        RuntimeConfig.invalidate();
        // file() memoises on first use, so without this the records land in the developer's real
        // home if anything logged earlier in this JVM.
        ActionLog.resetForTest();
    }

    @AfterEach
    void restore() {
        if (homeBefore == null) {
            System.clearProperty(ModdingFromAModMod.MODID + ".home");
        } else {
            System.setProperty(ModdingFromAModMod.MODID + ".home", homeBefore);
        }
        System.clearProperty(ModdingFromAModMod.MODID + ".childsafety");
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    private static JsonObject lastRecord(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "nothing was written to " + file);
        return JsonParser.parseString(lines.get(lines.size() - 1)).getAsJsonObject();
    }

    @Test
    void aGradeIsWrittenWithEverythingAReportGroupsBy() throws Exception {
        SafetyVerdict v = SafetyVerdict.parse("""
                {"violence":"LIKELY","sexual":"FINE","hate":"FINE","danger":"MAYBE",
                 "wellbeing":false,"reasons":["GORE","GORE","TORTURE"],
                 "redirect":"a zombie that bonks villagers with a rubber chicken",
                 "stageIndex":2}""");
        ActionLog.safety("abcd1234", "plan", v, "DIVERT", 9612, 0.0055,
                "claude-haiku-4-5-20251001", "{\"structured_output\":{}}", "plan_wish", 1, true);

        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("safety", o.get("kind").getAsString());
        assertEquals("abcd1234", o.get("wishId").getAsString());
        assertEquals("plan", o.get("layer").getAsString());
        assertEquals("DIVERT", o.get("action").getAsString());
        assertEquals("haiku", o.get("grader").getAsString());
        assertEquals("claude-haiku-4-5-20251001", o.get("model").getAsString());
        assertEquals(9612, o.get("latencyMs").getAsLong());
        assertEquals(0.0055, o.get("costUsd").getAsDouble());
        assertEquals("LIKELY", o.get("overall").getAsString());
        assertEquals("LIKELY", o.getAsJsonObject("bands").get("violence").getAsString());
        assertEquals("MAYBE", o.getAsJsonObject("bands").get("danger").getAsString());
        assertEquals(4, o.getAsJsonObject("bands").size(), "all four axes, every time");
        assertEquals(2, o.getAsJsonArray("reasons").size(), "deduped by the record itself");
        assertFalse(o.get("wellbeing").getAsBoolean());
        assertTrue(o.get("redirect").getAsString().contains("rubber chicken"));
        assertTrue(o.get("redirectShown").getAsBoolean());
        assertEquals("plan_wish", o.get("tool").getAsString());
        assertEquals(2, o.get("stageIndex").getAsInt());
        assertEquals(1, o.get("retry").getAsInt());
        assertEquals("on", o.get("mode").getAsString(), "the shipped default mode is stamped");
        assertEquals("standard", o.get("strictness").getAsString());
        assertTrue(o.get("test").getAsBoolean(),
                "tagged at the SOURCE so a report can tell the child's real play from the gates'");
        assertFalse(o.has("rawClipped"));
    }

    @Test
    void aFineGradeIsLoggedToo() throws Exception {
        // The denominator. Without it there is no false-positive RATE, only a count of refusals.
        ActionLog.safety("wish0001", "input", SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU),
                "PASS", 8400, 0.0051, "claude-haiku-4-5-20251001", "{}");
        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("PASS", o.get("action").getAsString());
        assertEquals("FINE", o.get("overall").getAsString());
        assertFalse(o.has("redirect"),
                "omitted rather than written as \"\", so a consumer can tell 'none was proposed' "
                        + "from 'one was proposed and was empty'");
        assertFalse(o.has("stageIndex"), "an input grade has no stage");
    }

    @Test
    void aRedirectTheFLOORREFUSEDSaysSoBesideTheTextItRefused() throws Exception {
        // Decision 12.5. Both halves matter and they are separate fields on purpose: `redirect`
        // carries WHAT the model offered, so a reader can see it and re-run the lexicon over it,
        // and `redirectRejected` says it was not used. Without the second, a refused redirect is
        // indistinguishable in the log from one that was shown to the child -- and "how often does
        // the model propose a redirect the floor will not have" is the rate this makes countable.
        SafetyVerdict refused = SafetyVerdict.parse("""
                {"violence":"LIKELY","sexual":"FINE","hate":"FINE","danger":"FINE",
                 "wellbeing":false,"reasons":["GORE"],
                 "redirect":"two villagers that have sex in a cottage"}""");
        ActionLog.safety("abcd1234", "input", refused, "BLOCK", 9000, 0.005,
                "claude-haiku-4-5-20251001", "{}", "", 0, false);

        JsonObject o = lastRecord(ActionLog.path());
        assertTrue(o.get("redirect").getAsString().contains("have sex"),
                "the refused text must survive into the record, or nobody can see what was "
                        + "refused: " + o);
        assertTrue(o.get("redirectRejected").getAsBoolean());
        assertFalse(o.get("redirectShown").getAsBoolean(),
                "a refused redirect was shown to nobody");
        // And the action really is the BLOCK §2's rule produces, not a REDIRECT to nothing.
        assertEquals("BLOCK", o.get("action").getAsString());
    }

    @Test
    void anOrdinaryRedirectCarriesNoRejectionFlagAtAll() throws Exception {
        // Written only when true, so a consumer filtering on the field's PRESENCE gets exactly the
        // rejections rather than having to read a boolean on every record.
        SafetyVerdict fine = SafetyVerdict.parse("""
                {"violence":"LIKELY","sexual":"FINE","hate":"FINE","danger":"FINE",
                 "wellbeing":false,"reasons":["GORE"],
                 "redirect":"a zombie that bonks villagers with a rubber chicken"}""");
        ActionLog.safety("abcd1234", "input", fine, "REDIRECT", 9000, 0.005,
                "claude-haiku-4-5-20251001", "{}", "", 0, true);

        JsonObject o = lastRecord(ActionLog.path());
        assertTrue(o.has("redirect"));
        assertFalse(o.has("redirectRejected"),
                "the common case must not carry a field that is false on every record");
    }

    @Test
    void anOutageIsDistinguishableFromAnApproval() throws Exception {
        ActionLog.safety("wish0002", "input", SafetyVerdict.unavailable(), "UNAVAILABLE_OPEN",
                20000, 0.0, "", "");
        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("UNAVAILABLE", o.get("overall").getAsString(),
                "'the grader could not run' and 'the grader looked and it was fine' are different "
                        + "facts, and a log that conflates them cannot report an outage rate");
        assertEquals("unavailable", o.get("grader").getAsString());
        assertFalse(o.has("model"), "there was no model, so no model is claimed");
    }

    @Test
    void aLongReplyIsClippedAndSaysSo() throws Exception {
        ActionLog.safety("wish0003", "input", SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU),
                "PASS", 1, 0.0, "haiku", "x".repeat(20_000));
        JsonObject o = lastRecord(ActionLog.path());
        assertTrue(o.get("rawClipped").getAsBoolean(),
                "every wrong conclusion this log has produced came from a consumer not being able "
                        + "to tell a complete record from a cut one");
        assertTrue(o.get("raw").getAsString().length() < 5_000);
    }

    @Test
    void aNullVerdictDoesNotStopTheRecordBeingWritten() throws Exception {
        // Logging must never be able to break a wish, and "the grade is missing" is exactly the
        // moment a caller is most likely to hand this something odd.
        ActionLog.safety(null, null, null, null, 0, 0, null, null);
        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("safety", o.get("kind").getAsString());
        assertFalse(o.has("bands"));
    }

    @Test
    void everyRecordSaysWhichSafetyArmProducedIt() throws Exception {
        System.setProperty(ModdingFromAModMod.MODID + ".childsafety", "shadow");
        RuntimeConfig.invalidate();
        ActionLog.wish("make a spikor", "BLOCKED", "TYPED", "wish0004");
        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("shadow", o.get("childsafety").getAsString(),
                "stampLineage carries the arm on EVERY record, not just the safety ones — a run "
                        + "labelled only by the operator's memory cannot be pooled with any other");
        assertEquals("BLOCKED", o.get("outcome").getAsString());
    }

    @Test
    void aWishRecordCarriesThePlayersUuidAndNeverHisName() throws Exception {
        // The action log is mined into COMMITTED corpora (mine-real-wishes.py,
        // mine-tool-calls.py), so the identifying half of "whose wish was this" must never be in
        // it: the UUID is a join key, the Mojang name lives in the world's journal, and
        // only the machine that holds both can put them back together. This test is the only thing that says so in a form a later edit trips over.
        ActionLog.wish("make me a spikor", "PENDING_ESCALATION", "TYPED", "wish0100",
                "11111111-2222-3333-4444-555555555555");
        JsonObject o = lastRecord(ActionLog.path());
        assertEquals("11111111-2222-3333-4444-555555555555", o.get("playerId").getAsString());
        assertEquals("wish0100", o.get("wishId").getAsString());
        assertFalse(o.has("playerName"), "a name in this file would travel into the repo");
    }

    @Test
    void anUnattributedRecordOmitsPlayerIdRatherThanWritingItEmpty() throws Exception {
        // The omit-not-null rule `world`, `session` and `redirect` already follow. A consumer has
        // to be able to tell "nobody was attributed" from "attributed to the empty player", or a
        // per-child report silently grows a child called "".
        ActionLog.wish("a gate's own traffic", "EMPTY", "COMMAND");
        assertFalse(lastRecord(ActionLog.path()).has("playerId"));

        ActionLog.safety("wish0101", "input", SafetyVerdict.fine(SafetyVerdict.Provenance.LEXICON),
                "PASS", 0, 0.0, "lexicon", "");
        assertFalse(lastRecord(ActionLog.path()).has("playerId"));
    }

    @Test
    void anL0RefusalHasNoWishIdSoItsPlayerIdIsTheOnlyAttributionItWillEverHave() throws Exception {
        // WishService.submit refuses a CLEARLY before the book is written to, deliberately — no
        // rate-limit token, no web prefetch, no bar — so that record can never be joined to a
        // wish. Without `playerId` on it, "how often is MY child being refused" is unanswerable
        // for exactly the records that answer it.
        SafetyVerdict refused = SafetyVerdict.parse("""
                {"violence":"CLEARLY","sexual":"FINE","hate":"FINE","danger":"FINE",
                 "wellbeing":false,"reasons":["GORE"]}""");
        ActionLog.safety("", "input", refused, "BLOCK", 0, 0.0, "lexicon", "", "", 0, false, false,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        JsonObject o = lastRecord(ActionLog.path());
        assertFalse(o.has("wishId"), "there is no wish and there never will be");
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", o.get("playerId").getAsString());
        assertEquals("BLOCK", o.get("action").getAsString());
    }

    @Test
    void whetherTheChildWasToldAboutAWellbeingFlagIsOnEveryRecord() throws Exception {
        // §12.1 decision 1B. Its own field rather than a reuse of redirectShown, because the two
        // answer different questions — "was a substitution announced" and "did a child in distress
        // hear a kind word" — and folded together neither is answerable.
        SafetyVerdict flagged = SafetyVerdict.parse("""
                {"violence":"FINE","sexual":"FINE","hate":"FINE","danger":"FINE",
                 "wellbeing":true,"reasons":["SELF_HARM"]}""");
        ActionLog.safety("wish0006", "input", flagged, "WELLBEING", 0, 0.0, "lexicon", "",
                "", 0, false, true);
        JsonObject told = lastRecord(ActionLog.path());
        assertTrue(told.get("childTold").getAsBoolean());
        assertTrue(told.get("wellbeing").getAsBoolean());
        assertFalse(told.get("redirectShown").getAsBoolean(),
                "the two fields must be independent, or the report cannot count either");

        // AND THE DENOMINATOR. A field written only when true makes "of the wellbeing flags, how
        // many said something" uncountable — the same reason a FINE band is logged at all.
        ActionLog.safety("wish0007", "input", flagged, "SHADOW_WELLBEING", 0, 0.0, "lexicon", "");
        JsonObject quiet = lastRecord(ActionLog.path());
        assertTrue(quiet.has("childTold"), "the field is missing, so nothing can be counted");
        assertFalse(quiet.get("childTold").getAsBoolean(),
                "shadow flagged it and said nothing, and the record has to say so");
    }

    @Test
    void aWellbeingNoteGoesToItsOwnFileAndNowhereElse() throws Exception {
        long before = Files.exists(ActionLog.path())
                ? Files.readAllLines(ActionLog.path()).size() : 0;
        ActionLog.grownUpNote("wish0005", "he said something worth a grown-up reading");

        JsonObject o = lastRecord(ActionLog.grownUpNotes());
        assertEquals("wellbeing", o.get("kind").getAsString());
        assertTrue(o.get("text").getAsString().contains("worth a grown-up reading"));

        long after = Files.exists(ActionLog.path())
                ? Files.readAllLines(ActionLog.path()).size() : 0;
        assertEquals(before, after,
                "the action log is mined, copied into corpora and read by tooling. These lines are "
                        + "the one kind that must not travel, so they get their own file.");
        assertTrue(ActionLog.grownUpNotes().toString().endsWith("grown-up-notes.jsonl"));
    }
}
