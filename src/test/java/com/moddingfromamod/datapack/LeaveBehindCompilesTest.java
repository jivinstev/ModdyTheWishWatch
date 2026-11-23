package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * the two leave-behind templates compile through the REAL compiler, with the parameters the
 * tool actually derives. Their behaviour GameTests run the rendered commands directly, which proves
 * what they do and not that CBScript accepts them — and an entry that renders and does not compile is
 * one the agent is told exists and cannot install.
 */
class LeaveBehindCompilesTest {

    @Test
    void trailAndDropsCompile() throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(), "SKIPPED — no compiler here");
        EffectCatalogue c = EffectCatalogue.load();
        Map<String, Map<String, String>> cases = Map.of(
                "trail", Map.of("TAG", "snow_fox", "BLOCK", "minecraft:snow", "LASTS_TICKS", "200",
                        "STEP_TICKS", "4", "MOST", "60"),
                "drops", Map.of("TAG", "honey_bee", "ITEM", "minecraft:honey_bottle", "PERIOD_TICKS", "400",
                        "MOST", "6"));
        for (var e : cases.entrySet()) {
            Path dir = Files.createTempDirectory("leavebehind");
            String src = c.render(e.getKey(), e.getValue());
            CompileResult r = CbScriptCompiler.compile(dir, "lb_" + e.getKey(), "test", src, dir.resolve("p.zip"));
            assertTrue(r.ok(), e.getKey() + " does not compile: " + r.failureSummary());
        }
    }
}
