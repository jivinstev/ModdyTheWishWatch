package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * D20: <b>only supported models are offered, and the list lives in one place</b>. This pins it. A
 * new model is added here only once it is measured in its role, and the maintainers' decision moves with it.
 */
class SupportedModelsTest {

    @Test
    void theTableIsExactlyTheSupportedList() {
        assertEquals(List.of("haiku-5.5", "muse-ember-1.0"), SupportedModels.ids(SupportedModels.Role.CHEAP));
        assertEquals(List.of("opus-5.5", "muse-spark-1.2", "muse-spark-1.2-contributor", "muse-wonder-1.0"),
                SupportedModels.ids(SupportedModels.Role.MAIN));
    }

    @Test
    void neverFable() {
        for (SupportedModels.Model m : SupportedModels.ALL) {
            String all = (m.id() + m.label() + m.workerSpec()).toLowerCase(Locale.ROOT);
            assertFalse(all.contains("fable"), m.toString());
        }
    }

    @Test
    void everyMenuAndTheServerFileReadTheTable() {
        assertEquals(SupportedModels.ids(SupportedModels.Role.MAIN),
                SettingsCatalog.find(SettingsCatalog.MAIN_MODEL).orElseThrow().choices());
        assertEquals(SupportedModels.ids(SupportedModels.Role.CHEAP),
                SettingsCatalog.find(SettingsCatalog.CHEAP_MODEL).orElseThrow().choices());
        assertEquals(SupportedModels.ids(SupportedModels.Role.CHEAP), ServerConfigRules.CHEAP_MODELS);
        assertEquals(List.of("opus-5.5"), ServerConfigRules.MAIN_MODELS.get("claude"));
        assertEquals(List.of("muse-spark-1.2", "muse-spark-1.2-contributor", "muse-wonder-1.0"),
                ServerConfigRules.MAIN_MODELS.get("muse"));
    }

    @Test
    void everyModelShowsItsApiPrice() {
        // D27: Wonder through a player's own API key is offered WITH its price; every API-key choice shows one.
        for (SupportedModels.Model m : SupportedModels.ALL) {
            assertTrue(m.apiPrice().contains("$"), m.id());
        }
    }

    @Test
    void keysComeOnlyFromTheseVariables() {
        assertEquals("ANTHROPIC_API_KEY", SupportedModels.Provider.ANTHROPIC.keyVar);
        assertEquals("MUSE_API_KEY", SupportedModels.Provider.MUSE.keyVar);
    }

    @Test
    void whichProgramRunsEachPick() {
        var opus = SupportedModels.find("opus-5.5").orElseThrow();
        var spark = SupportedModels.find("muse-spark-1.2").orElseThrow();
        var ember = SupportedModels.find("muse-ember-1.0").orElseThrow();
        assertEquals(SupportedModels.Harness.CLAUDE_CODE, SupportedModels.harness(opus, SupportedModels.Mode.SUBSCRIPTION));
        // a Muse wish model runs on Muse Code both ways (agent/MuseWorker), so Claude Code is never needed for it.
        assertEquals(SupportedModels.Harness.MUSE_CODE, SupportedModels.harness(spark, SupportedModels.Mode.API));
        assertEquals(SupportedModels.Harness.MUSE_CODE, SupportedModels.harness(spark, SupportedModels.Mode.SUBSCRIPTION));
        assertEquals(SupportedModels.Harness.MUSE_CODE, SupportedModels.harness(ember, SupportedModels.Mode.SUBSCRIPTION));
        assertEquals(SupportedModels.Harness.MUSE_CODE, SupportedModels.harness(ember, SupportedModels.Mode.API));
        assertTrue(SupportedModels.refusedOnServer(opus, SupportedModels.Mode.SUBSCRIPTION).isPresent(), "D29");
        assertTrue(SupportedModels.refusedOnServer(ember, SupportedModels.Mode.SUBSCRIPTION).isEmpty(), "D15");
    }
}
