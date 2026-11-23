package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * an earlier issue's one PAID smoke (a fraction of a cent), off unless {@code MODDY_FALLBACK_SMOKE=1}: the helper chain
 * {@code [Ember on the Muse Code sign-in, Ember on MUSE_API_KEY]} against the REAL {@code muse} binary on a machine
 * with no Muse Code sign-in. Entry 1 is refused by Muse Code itself ("missing meta credentials: run `muse login`"),
 * which must classify as LOGIN, skip it, and answer on entry 2 with the real key. Archived in
 * {@code corpus/wish-runs/2026-11-18-2536-fallback-smoke/}.
 */
@EnabledIfEnvironmentVariable(named = "MODDY_FALLBACK_SMOKE", matches = "1")
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)
class ModelFallbackRealSmokeTest {

    @AfterEach
    void reset() {
        ModelFallbackRuntime.forTest(null, null, null, null);
    }

    @Test
    void aRealRefusedSignInFallsToTheRealKey(@TempDir Path dir) throws Exception {
        String key = System.getenv("MUSE_API_KEY");
        assertTrue(key != null && !key.isBlank(), "the smoke needs MUSE_API_KEY");
        // The runtime believes a sign-in file is there (so entry 1 is TRIED); muse itself, reading the real
        // ~/.config/muse, finds none and refuses.
        Path xdg = Files.createDirectories(dir.resolve("xdg/muse"));
        Files.writeString(xdg.resolve("auth.json"), "{}");
        Map<String, String> env = Map.of("MUSE_API_KEY", key, "XDG_CONFIG_HOME", dir.resolve("xdg").toString());
        ModelFallbackRuntime.forTest(s -> { }, env::get, x -> Optional.empty(), null);
        // Gate A pins moddingfromamod.muse.bin=none so no test reaches a real binary; this one names it on purpose.
        String bin = System.getenv("MUSE_BIN");
        assertTrue(bin != null && new java.io.File(bin).canExecute(),
                "MUSE_BIN must name a real muse (Muse Code's installer puts it at ~/.local/bin/muse)");
        var chain = List.of(ModelFallbackTest.e("muse-ember-1.0:subscription"), ModelFallbackTest.e("muse-ember-1.0:api"));
        MuseExec.Answer a = MuseExec.runChain(bin, chain, "Answer with the single word OK.", "Say it.", null, "low", key,
                System.currentTimeMillis() + 60_000L);
        System.out.println("SMOKE answer=" + (a == null ? null : a.text().strip()) + " arm=" + (a == null ? null : a.arm())
                + " costUsd=" + (a == null ? null : a.costUsd()) + " wallMs=" + (a == null ? null : a.wallMs())
                + " skips=" + ModelFallbackRuntime.skips());
        assertNotNull(a, "entry 2 answered");
        assertEquals(MuseExec.ARM_API, a.arm());
        var skip = ModelFallbackRuntime.skips().get("muse-ember-1.0:subscription");
        assertNotNull(skip, "entry 1's real refusal skipped it");
        assertEquals(ModelFallback.Failure.LOGIN, skip.failure(), skip.line());
    }
}
