package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** the Magician setup's one-button choices, and the one thing to do when a step fails. */
class MagicianPresetsTest {

    private static final Set<SupportedModels.Harness> BOTH = EnumSet.allOf(SupportedModels.Harness.class);

    private static List<String> ids(Set<SupportedModels.Harness> found, Map<String, String> env, boolean dedicated) {
        return MagicianPresets.offered(found, env::get, dedicated).stream().map(MagicianPresets.Preset::id).toList();
    }

    @Test
    void oneButtonPerProgramFoundAndApiKeysOnlyWithAKey() {
        assertEquals(List.of(MagicianPresets.CLAUDE, MagicianPresets.MUSE), ids(BOTH, Map.of(), false));
        assertEquals(List.of(MagicianPresets.MUSE), ids(EnumSet.of(SupportedModels.Harness.MUSE_CODE), Map.of(), false));
        assertEquals(List.of(), ids(EnumSet.noneOf(SupportedModels.Harness.class), Map.of("ANTHROPIC_API_KEY", "k"),
                false), "a key with no program to run it offers nothing");
        assertEquals(List.of(MagicianPresets.CLAUDE, MagicianPresets.MUSE, MagicianPresets.API),
                ids(BOTH, Map.of("MUSE_API_KEY", "k"), false));
        assertEquals(List.of(MagicianPresets.CLAUDE, MagicianPresets.MUSE),
                ids(BOTH, Map.of("ANTHROPIC_API_KEY", " "), false), "a blank key is not a key");
        // D29: no Claude subscription on a dedicated server
        assertFalse(ids(BOTH, Map.of(), true).contains(MagicianPresets.CLAUDE));
    }

    @Test
    void apiKeysPreferAnthropicsKeyAndElseMuses() {
        assertEquals(MagicianPresets.ANTHROPIC_KEY, MagicianPresets.find(MagicianPresets.API, BOTH,
                Map.of("ANTHROPIC_API_KEY", "a", "MUSE_API_KEY", "m")::get, false).orElseThrow());
        assertEquals(MagicianPresets.MUSE_KEY, MagicianPresets.find(MagicianPresets.API, BOTH,
                Map.of("MUSE_API_KEY", "m")::get, false).orElseThrow());
    }

    @Test
    void everyPresetSetsFourChoicesTheSetupAcceptsWithoutAProbe() {
        Map<String, String> keys = Map.of("ANTHROPIC_API_KEY", "a", "MUSE_API_KEY", "m");
        for (var p : List.of(MagicianPresets.CLAUDE_SUBSCRIPTION, MagicianPresets.MUSE_SUBSCRIPTION,
                MagicianPresets.ANTHROPIC_KEY, MagicianPresets.MUSE_KEY)) {
            MagicianPicks picks = p.picks();
            assertTrue(picks.complete(), p.id() + ": " + picks);
            assertEquals(List.of(), picks.problems(keys::get, false), p.id());
            assertEquals(p.harness(), SupportedModels.harness(picks.main().orElseThrow(), p.mode()));
            assertEquals(p.harness(), SupportedModels.harness(picks.cheap().orElseThrow(), p.mode()));
        }
        assertEquals(new MagicianPicks("opus-5.5", "subscription", "haiku-5.5", "subscription"),
                MagicianPresets.CLAUDE_SUBSCRIPTION.picks());
        assertTrue(MagicianPresets.ANTHROPIC_KEY.lore().stream().anyMatch(l -> l.contains("ANTHROPIC_API_KEY")
                && l.contains("per million tokens")), "a paid button shows its price before it is pressed (D27)");
    }

    private static HarnessProbe.Result said(SupportedModels.Harness h, String sub) {
        return new HarnessProbe.Result(h, true, "1.0", Map.of(SupportedModels.Mode.SUBSCRIPTION, sub));
    }

    @Test
    void aFailureSaysTheOneThingToDo() {
        var claude = MagicianPresets.CLAUDE_SUBSCRIPTION;
        assertEquals("Claude Code isn't installed. Install it, sign in, then press the button again.",
                MagicianPresets.whatToDo(claude, HarnessProbe.Result.missing(SupportedModels.Harness.CLAUDE_CODE))
                        .orElseThrow());
        assertEquals("Claude Code isn't signed in. Run `claude` once in a terminal, sign in, then press the button again.",
                MagicianPresets.whatToDo(claude, said(SupportedModels.Harness.CLAUDE_CODE,
                        "not signed in: run `claude` once and sign in")).orElseThrow());
        assertEquals("Muse Code isn't signed in. Run `muse login` once in a terminal, then press the button again.",
                MagicianPresets.whatToDo(MagicianPresets.MUSE_SUBSCRIPTION, said(SupportedModels.Harness.MUSE_CODE,
                        "not signed in: run `muse login`")).orElseThrow());
        assertTrue(MagicianPresets.whatToDo(claude, said(SupportedModels.Harness.CLAUDE_CODE, "")).isEmpty());
        var refused = new HarnessProbe.Result(SupportedModels.Harness.CLAUDE_CODE, true, "1.0",
                Map.of(SupportedModels.Mode.API, "the key was refused"));
        assertTrue(MagicianPresets.whatToDo(MagicianPresets.ANTHROPIC_KEY, refused).orElseThrow()
                .startsWith("Your ANTHROPIC_API_KEY was refused."));
    }

    @Test
    void theResultAndTileWordsNameTheModelWithoutItsEffortNote() {
        assertEquals("✪ The Magician is on: Opus 5.5 on your Claude subscription. Your typed wishes go to the Magician now.",
                MagicianPresets.turnedOn(MagicianPresets.CLAUDE_SUBSCRIPTION));
        assertEquals("✪ Magician: on (Opus 5.5)", MagicianPresets.onTile(MagicianPresets.CLAUDE_SUBSCRIPTION.picks()));
        assertEquals("✪ Magician: on", MagicianPresets.onTile(new MagicianPicks("", "", "", "")));
        var report = new MagicianCheck.Report(false, List.of(MagicianCheck.Step.fail("the wish", "no reply")), 0);
        assertEquals("✖ The Magician check didn't pass at the wish: no reply. The Magician stays off: press the button"
                + " to try again.", MagicianPresets.checkFailed(report));
    }
}
