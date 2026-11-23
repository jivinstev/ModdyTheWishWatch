package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * step 4: the end-to-end check runs its six steps, stops at the first failure (so a broken setup spends
 * little), opens the AI doors only while it runs, and Magician turns on only for picks that passed.
 */
class MagicianCheckTest {

    @TempDir
    Path dir;

    /** Stub steps; {@code failAt} names the step that fails (null: none). */
    private static final class Stub implements MagicianCheck.Steps {
        final String failAt;
        final AtomicBoolean wishAsked = new AtomicBoolean();
        final AtomicBoolean doorsOpenDuringWish = new AtomicBoolean();

        Stub(String failAt) {
            this.failAt = failAt;
        }

        private MagicianCheck.Step s(String name) {
            return new MagicianCheck.Step(name, !name.equals(failAt), name.equals(failAt) ? "broke" : "ok", 0.001);
        }

        public MagicianCheck.Step inputAllows() {
            return s("input");
        }

        public MagicianCheck.Step inputRefuses() {
            return s("refusal");
        }

        public MagicianCheck.Step plan() {
            return s("plan");
        }

        public MagicianCheck.Step textSweep() {
            return s("text");
        }

        public MagicianCheck.Step datapackSweep() {
            return s("datapack");
        }

        public void wish(Consumer<MagicianCheck.Step> done) {
            wishAsked.set(true);
            doorsOpenDuringWish.set(Magician.allowsAi());
            new Thread(() -> done.accept(new MagicianCheck.Step("wish", !"wish".equals(failAt), "granted", 0))).start();
        }
    }

    private static MagicianCheck.Report run(Stub stub) throws Exception {
        CompletableFuture<MagicianCheck.Report> f = new CompletableFuture<>();
        MagicianCheck.run(stub, s -> { }, f::complete);
        MagicianCheck.Report r = f.get(10, TimeUnit.SECONDS);
        Thread.sleep(50);   // finish() shuts the doors just after onDone
        return r;
    }

    @Test
    void allSixPassAndTheCostIsSummed() throws Exception {
        ModdySettings.overridesForTest(List.of());
        try {
            Stub stub = new Stub(null);
            var r = run(stub);
            assertTrue(r.pass(), r.summary());
            assertEquals(6, r.steps().size());
            assertEquals(0.005, r.costUsd(), 1e-9);
            assertTrue(r.summary().contains("$0.0050"), r.summary());
            assertTrue(stub.doorsOpenDuringWish.get(), "the wish step ran with the AI doors shut");
            assertFalse(Magician.checking(), "the doors stayed open after the check");
        } finally {
            ModdySettings.overridesForTest(null);
        }
    }

    @Test
    void theWishsOwnCostIsShownWhenMeasuredAndNeverReadsAsFreeWhenNot() {
        // the wish model's spend comes from the worker's usage (Muse Code); a cost nobody measured is UNKNOWN.
        var helper = new MagicianCheck.Step("input", true, "ok", 0.002);
        var measured = new MagicianCheck.Report(true, List.of(helper,
                new MagicianCheck.Step(MagicianCheck.WISH_STEP, true, "granted", 0.0123)), 0.0143);
        assertTrue(measured.summary().contains("the helper model cost $0.0020"), measured.summary());
        assertTrue(measured.summary().contains("the wish cost $0.0123"), measured.summary());
        var unknown = new MagicianCheck.Report(true, List.of(helper,
                new MagicianCheck.Step(MagicianCheck.WISH_STEP, true, "granted", MagicianCheck.Step.UNKNOWN)), 0.002);
        assertTrue(unknown.summary().contains("in your provider's usage"), unknown.summary());
        assertEquals(0.002, unknown.helperCostUsd(), 1e-9);
        var failed = new MagicianCheck.Report(false, List.of(helper,
                MagicianCheck.Step.fail(MagicianCheck.WISH_STEP, "it timed out")), 0.002);
        assertFalse(failed.summary().contains("on the wish"), "an unmeasured failed wish read as a price: "
                + failed.summary());
    }

    @Test
    void aGraderThatFailsStopsTheCheckBeforeTheWishIsPaidFor() throws Exception {
        Stub stub = new Stub("refusal");
        var r = run(stub);
        assertFalse(r.pass());
        assertFalse(stub.wishAsked.get(), "a failed grader still paid for a wish");
        assertTrue(r.failed().startsWith("refusal"), r.failed());
        assertTrue(r.summary().contains("failed at refusal"), r.summary());
    }

    @Test
    void aFailedWishFailsTheCheck() throws Exception {
        var r = run(new Stub("wish"));
        assertFalse(r.pass());
        assertEquals(6, r.steps().size());
    }

    private ModdySettings settings(MagicianCheckRecord record) {
        return new ModdySettings(List::of, k -> Optional.empty(), () -> false,
                new PlayerSettings(dir.resolve("settings.json")))
                .checkPassed(s -> record.passedFor(MagicianPicks.read(s)));
    }

    private static final ModdySettings.Who HOST = new ModdySettings.Who("host", true, false);

    @Test
    void magicianTurnsOnOnlyForPicksThatPassedAndANewPickTurnsItOff() {
        MagicianCheckRecord record = new MagicianCheckRecord(dir.resolve("check.json"));
        ModdySettings s = settings(record);
        var refused = s.change(SettingsCatalog.MAGICIAN, "on", HOST);
        assertFalse(refused.done(), "Magician turned on with no check");
        assertEquals(ModdySettings.NEEDS_CHECK, refused.line());
        for (var e : Map.of(SettingsCatalog.MAIN_MODEL, "opus-5.5", SettingsCatalog.MAIN_MODE, "api",
                SettingsCatalog.CHEAP_MODEL, "muse-ember-1.0", SettingsCatalog.CHEAP_MODE, "subscription").entrySet()) {
            assertTrue(s.change(e.getKey(), e.getValue(), HOST).done());
        }
        record.record(MagicianPicks.read(s), true, "passed");
        assertTrue(s.change(SettingsCatalog.MAGICIAN, "on", HOST).done(), "the picks passed");
        var changed = s.change(SettingsCatalog.MAIN_MODEL, "muse-wonder-1.0", HOST);
        assertTrue(changed.done() && changed.line().endsWith(ModdySettings.RECHECK.strip()), changed.line());
        assertEquals(Optional.of("off"), s.value(SettingsCatalog.MAGICIAN), "a new pick kept Magician on unchecked");
        assertFalse(s.change(SettingsCatalog.MAGICIAN, "on", HOST).done(), "the new pick has not passed");
        assertTrue(s.change(SettingsCatalog.MAGICIAN, "off", HOST).done(), "turning it off is always allowed");
        // The record survives a restart (per machine, beside the settings).
        assertFalse(new MagicianCheckRecord(dir.resolve("check.json")).summary().isEmpty());
    }

    @Test
    void aGuestNeverRunsTheSetupsActions() {
        ModdySettings s = settings(new MagicianCheckRecord(dir.resolve("c.json")));
        var guest = new ModdySettings.Who("guest", false, false);
        assertEquals(ModdySettings.HOST_ONLY, s.change(SettingsCatalog.MAIN_MODEL, "opus-5.5", guest).line());
        assertEquals(ModdySettings.FROM_FILE,
                s.change(SettingsCatalog.MAIN_MODEL, "opus-5.5", new ModdySettings.Who("op", true, true)).line());
    }

    @Test
    void theForcedRefusalIsABlockRowOfThePublicSafetySet() throws Exception {
        try (var in = MagicianCheck.class.getResourceAsStream("/corpus/safety-wishes.jsonl")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "the public safety set is not on the classpath");
            boolean found = false;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.contains("\"" + MagicianCheck.BLOCK_ROW_ID + "\"")) {
                    var o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    assertEquals(MagicianCheck.BLOCK_ROW, o.get("prompt").getAsString());
                    assertEquals("block", o.get("label").getAsString());
                    found = true;
                }
            }
            assertTrue(found, MagicianCheck.BLOCK_ROW_ID + " left the public safety set");
        }
    }
}
