package com.moddingfromamod.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** smoke: the Magician's programs line read "Muse Code Muse Code 1.4.24" (muse --version names itself). */
class HarnessProbeLineTest {

    @Test
    void theProgramIsNamedOnce() {
        var muse = new HarnessProbe.Result(SupportedModels.Harness.MUSE_CODE, true, "Muse Code 1.4.24",
                Map.of(SupportedModels.Mode.SUBSCRIPTION, ""));
        assertEquals("Muse Code 1.4.24: sign-in works.", muse.line());
        var claude = new HarnessProbe.Result(SupportedModels.Harness.CLAUDE_CODE, true, "2.1.3 (Claude Code)",
                Map.of(SupportedModels.Mode.SUBSCRIPTION, ""));
        assertEquals("Claude Code 2.1.3 (Claude Code): sign-in works.", claude.line(), "a version after the name stays");
    }

    @Test
    void aFailedWishSaysItsCauseWhenKnown() {
        assertEquals("", LiveCheckSteps.why(null));
        assertEquals(" — the workers refused", LiveCheckSteps.why("the workers refused"));
    }
}
