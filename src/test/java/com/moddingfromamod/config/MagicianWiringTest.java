package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** which picks put the wish worker on Muse Code, and how it is paid for there. */
class MagicianWiringTest {

    private static Optional<SupportedModels.Mode> muse(String model, String pay) {
        return MagicianWiring.museMain(Optional.of(new MagicianPicks(model, pay, SupportedModels.EMBER, "subscription")));
    }

    @Test
    void aMuseWishModelRunsOnMuseCodeAndSaysHowItIsPaid() {
        assertEquals(Optional.of(SupportedModels.Mode.SUBSCRIPTION), muse(SupportedModels.WONDER, "subscription"));
        assertEquals(Optional.of(SupportedModels.Mode.SUBSCRIPTION), muse(SupportedModels.SPARK, "subscription"));
        assertEquals(Optional.of(SupportedModels.Mode.API), muse(SupportedModels.SPARK_CONTRIBUTOR, "api"));
    }

    @Test
    void opusStaysOnClaudeCode() {
        assertTrue(muse(SupportedModels.OPUS, "api").isEmpty());
        assertTrue(muse(SupportedModels.OPUS, "subscription").isEmpty());
    }

    /**
     * No picks in charge (incomplete, or an override that locks the model, a plug-in's) means no Muse Code worker:
     * that server's own launcher runs exactly as before.
     */
    @Test
    void picksNotInChargeLeaveTheWorkerAlone() {
        assertTrue(MagicianWiring.museMain(Optional.empty()).isEmpty());
    }

    /**
     * A server whose settings override (a plug-in) locks the wish model and SETS picks that would be Wonder on the API
     * key: the lock is what keeps Wonder off a key, because with the model
     * locked the picks are never in charge, so the public Muse Code worker (and Wonder on a key) cannot be reached.
     */
    @Test
    void aHousesModelLockNeverReachesWonderOnTheKey() {
        Map<String, String> house = Map.of(SettingsCatalog.MAIN_MODEL, SupportedModels.WONDER,
                SettingsCatalog.MAIN_MODE, "api", SettingsCatalog.CHEAP_MODEL, SupportedModels.EMBER,
                SettingsCatalog.CHEAP_MODE, "api", SettingsCatalog.MODEL, "muse:muse-spark-1.2-contributor");
        ModdySettings.overridesForTest(List.of(new SettingsOverride() {
            public Set<String> keys() {
                return Set.of(SettingsCatalog.MODEL, "magician.*");
            }

            public Optional<String> value(String key) {
                return Optional.ofNullable(house.get(key));
            }
        }));
        try {
            assertTrue(MagicianWiring.active().isEmpty(), "an override server's picks took charge");
            assertTrue(MagicianWiring.museMain().isEmpty(), "an override server reached the public Muse Code worker");
            assertTrue(MagicianWiring.mainSpec().isEmpty(), "an override server's wish model came from the picks");
            assertTrue(MagicianWiring.mainChain().isEmpty(), "an override server reached the public fallback chain (#2536)");
            assertTrue(MagicianWiring.cheapChain().isEmpty(), "an override server's graders reached the public chain (#2536)");
        } finally {
            ModdySettings.overridesForTest(null);
        }
    }

    /** the chain is the pick, then the fallbacks the host set, in order. */
    @Test
    void theChainIsThePickThenTheFallbacks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        PlayerSettings player = new PlayerSettings(dir.resolve("settings.json"));
        ModdySettings s = new ModdySettings(List::of, k -> Optional.empty(), () -> false, player);
        player.set(SettingsCatalog.MAIN_MODEL, SupportedModels.WONDER);
        player.set(SettingsCatalog.MAIN_MODE, "subscription");
        player.set(SettingsCatalog.CHEAP_MODEL, SupportedModels.EMBER);
        player.set(SettingsCatalog.CHEAP_MODE, "subscription");
        assertEquals(List.of("muse-wonder-1.0:subscription"), keys(MagicianWiring.chain(s, SupportedModels.Role.MAIN)),
                "no fallback set: the pick alone");
        player.set(SettingsCatalog.MAIN_FALLBACK, "muse-spark-1.2-contributor:api,opus-5.5:api");
        player.set(SettingsCatalog.CHEAP_FALLBACK, "muse-ember-1.0:api");
        assertEquals(List.of("muse-wonder-1.0:subscription", "muse-spark-1.2-contributor:api", "opus-5.5:api"),
                keys(MagicianWiring.chain(s, SupportedModels.Role.MAIN)));
        assertEquals(List.of("muse-ember-1.0:subscription", "muse-ember-1.0:api"),
                keys(MagicianWiring.chain(s, SupportedModels.Role.CHEAP)));
    }

    /**, D20: a Muse-only setup never starts Claude Code; any Anthropic pick or fallback allows it. */
    @Test
    void claudeCodeRunsOnlyWhenTheSetupNamesAnAnthropicModel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        PlayerSettings player = new PlayerSettings(dir.resolve("settings.json"));
        ModdySettings s = new ModdySettings(List::of, k -> Optional.empty(), () -> false, player);
        assertTrue(MagicianWiring.claudeCodeChosen(s), "no picks in charge: unchanged");
        player.set(SettingsCatalog.MAIN_MODEL, SupportedModels.SPARK_CONTRIBUTOR);
        player.set(SettingsCatalog.MAIN_MODE, "api");
        player.set(SettingsCatalog.CHEAP_MODEL, SupportedModels.EMBER);
        player.set(SettingsCatalog.CHEAP_MODE, "api");
        assertEquals(false, MagicianWiring.claudeCodeChosen(s), "Muse and Ember only");
        player.set(SettingsCatalog.CHEAP_FALLBACK, "haiku-5.5:api");
        assertTrue(MagicianWiring.claudeCodeChosen(s), "a Haiku fallback names Claude");
        player.set(SettingsCatalog.CHEAP_FALLBACK, "none");
        player.set(SettingsCatalog.CHEAP_MODEL, SupportedModels.HAIKU);
        assertTrue(MagicianWiring.claudeCodeChosen(s), "a Haiku helper");
    }

    private static List<String> keys(Optional<List<com.moddingfromamod.agent.ModelFallback.Entry>> c) {
        return c.orElseThrow().stream().map(com.moddingfromamod.agent.ModelFallback.Entry::key).toList();
    }
}
