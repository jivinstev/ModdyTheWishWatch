package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** the program check reads each program's real answer shape (captured 2026-11-18), and words its failures. */
class CliHarnessProbeTest {

    private static final SupportedModels.Harness CLAUDE = SupportedModels.Harness.CLAUDE_CODE;
    private static final SupportedModels.Harness MUSE = SupportedModels.Harness.MUSE_CODE;

    @Test
    void aRealAnswerCountsAndAnErrorThatMentionsATokenDoesNot() {
        assertTrue(CliHarnessProbe.answered(CLAUDE, new CliHarnessProbe.Run(0, "OK\n")));
        assertTrue(CliHarnessProbe.answered(MUSE, new CliHarnessProbe.Run(0,
                "{\"kind\":\"model_completed\",\"text\":\"OK\"}\n")));
        assertFalse(CliHarnessProbe.answered(CLAUDE, new CliHarnessProbe.Run(0, "Invalid API TOKEN")));
        assertFalse(CliHarnessProbe.answered(MUSE, new CliHarnessProbe.Run(0, "missing meta credentials: run `muse login`")));
        assertFalse(CliHarnessProbe.answered(CLAUDE, new CliHarnessProbe.Run(1, "OK")));
    }

    @Test
    void failuresAreWordedForAPerson() {
        assertEquals("not signed in: run `muse login`", CliHarnessProbe.why(MUSE, false, new CliHarnessProbe.Run(1,
                "missing meta credentials: run `muse login` or set META_API_KEY, or save credentials at ~/.config/muse/auth.json")));
        assertEquals("the key was refused", CliHarnessProbe.why(CLAUDE, true, new CliHarnessProbe.Run(1, "401 Unauthorized")));
        assertEquals("it did not answer in time", CliHarnessProbe.why(CLAUDE, false, new CliHarnessProbe.Run(-2, "")));
    }
}
