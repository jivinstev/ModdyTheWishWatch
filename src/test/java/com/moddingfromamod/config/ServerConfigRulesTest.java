package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D28 and D29: what a dedicated server's file may turn on, and every way it keeps Magician off. */
class ServerConfigRulesTest {

    private static Map<String, String> file(String provider, String mode, String model, String cheap) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("magician.enabled", "true");
        m.put("magician.provider", provider);
        m.put("magician.mode", mode);
        m.put("magician.model", model);
        m.put("magician.cheapModel", cheap);
        return m;
    }

    private static final Map<String, String> KEYS = Map.of("ANTHROPIC_API_KEY", "k", "MUSE_API_KEY", "k");

    @Test
    void noFileMeansMagicianOff() {
        var r = ServerConfigRules.apply(Map.of("magician.enabled", "false"), KEYS::get, true);
        assertFalse(r.magician());
        assertEquals("off", r.values().get(SettingsCatalog.MAGICIAN));
    }

    @Test
    void anUnknownModelKeepsMagicianOffAndSaysWhich() {
        var r = ServerConfigRules.apply(file("claude", "api", "opus-9", "haiku-5.5"), KEYS::get, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("opus-9"), r.log().toString());
        assertEquals("off", r.values().get(SettingsCatalog.MAGICIAN), "never a silent fallback");
    }

    @Test
    void claudeSubscriptionIsRefusedOnADedicatedServer() {
        var r = ServerConfigRules.apply(file("claude", "subscription", "opus-5.5", "haiku-5.5"), KEYS::get, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("Claude subscription mode is not allowed"), r.log().toString());
    }

    /**
     * D15/D29 allow Muse Code's subscription on a server, and the public mod has a Muse Code wish worker
     * for it ({@code agent/MuseWorker}). A Muse model through the server's own MUSE_API_KEY runs too, Wonder included
     * (D27).
     */
    @Test
    void museRunsOnTheSignInAndOnTheKey() {
        var sub = ServerConfigRules.apply(file("muse", "subscription", "muse-wonder-1.0", "muse-ember-1.0"),
                k -> null, true);
        assertTrue(sub.magician(), sub.log().toString());
        assertEquals("subscription", sub.values().get(SettingsCatalog.CHEAP_MODE));
        var wonderKey = ServerConfigRules.apply(file("muse", "api", "muse-wonder-1.0", "muse-ember-1.0"),
                Map.of("MUSE_API_KEY", "k")::get, true);
        assertTrue(wonderKey.magician(), wonderKey.log().toString());
        assertEquals("api", wonderKey.values().get(SettingsCatalog.CHEAP_MODE));
    }

    @Test
    void haikuOnAServerAlwaysNeedsTheAnthropicKey() {
        var r = ServerConfigRules.apply(file("muse", "api", "muse-spark-1.2", "haiku-5.5"),
                Map.of("MUSE_API_KEY", "k")::get, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("ANTHROPIC_API_KEY"), r.log().toString());
    }

    @Test
    void apiModeNeedsTheKeyInTheEnvironmentNeverTheFile() {
        var r = ServerConfigRules.apply(file("claude", "api", "opus-5.5", "haiku-5.5"), k -> null, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("ANTHROPIC_API_KEY"), r.log().toString());
        assertTrue(ServerConfigRules.apply(file("claude", "api", "opus-5.5", "haiku-5.5"), KEYS::get, true).magician());
    }

    @Test
    void untilTheStartUpCheckPassesMagicianStaysOff() {
        var r = ServerConfigRules.apply(file("claude", "api", "opus-5.5", "haiku-5.5"), KEYS::get, false);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("start-up check"), r.log().toString());
        assertTrue(r.readyForCheck(), "a file that passed every rule waits only for the check");
        assertEquals("opus-5.5", r.values().get(SettingsCatalog.MAIN_MODEL));
        assertFalse(ServerConfigRules.apply(file("claude", "api", "opus-9", "haiku-5.5"), KEYS::get, false)
                .readyForCheck(), "a refused file never runs the check");
    }

    @Test
    void theFileHasNoSafetySwitch() {
        Map<String, String> m = file("claude", "api", "opus-5.5", "haiku-5.5");
        m.put("safety.mode", "off");
        var r = ServerConfigRules.apply(m, KEYS::get, true);
        assertFalse(r.values().containsKey("safety.mode"), "the file must never carry a safety value");
    }

    /** the file's fallback chain, checked like the rest of it; a bad entry keeps Magician off and says which. */
    @Test
    void theFallbackChainIsCheckedLikeTheRestOfTheFile() {
        var f = file("muse", "subscription", "muse-wonder-1.0", "muse-ember-1.0");
        f.put(SettingsCatalog.MAIN_FALLBACK, "muse-spark-1.2-contributor:api, opus-5.5:api");
        f.put(SettingsCatalog.CHEAP_FALLBACK, "muse-ember-1.0:api");
        var r = ServerConfigRules.apply(f, KEYS::get, true);
        assertTrue(r.magician(), r.log().toString());
        assertEquals("muse-spark-1.2-contributor:api,opus-5.5:api", r.values().get(SettingsCatalog.MAIN_FALLBACK));
        assertEquals("muse-ember-1.0:api", r.values().get(SettingsCatalog.CHEAP_FALLBACK));

        var none = ServerConfigRules.apply(file("muse", "subscription", "muse-wonder-1.0", "muse-ember-1.0"), KEYS::get,
                true);
        assertEquals("none", none.values().get(SettingsCatalog.MAIN_FALLBACK), "no key in the file: no fallback");

        f.put(SettingsCatalog.MAIN_FALLBACK, "opus-5.5:subscription");
        r = ServerConfigRules.apply(f, KEYS::get, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("not a Claude subscription"), r.log().toString());

        f.put(SettingsCatalog.MAIN_FALLBACK, "muse-spark-1.2-contributor:api");
        r = ServerConfigRules.apply(f, Map.<String, String>of()::get, true);
        assertFalse(r.magician());
        assertTrue(r.log().get(0).contains("needs MUSE_API_KEY"), r.log().toString());

        f.put(SettingsCatalog.MAIN_FALLBACK, "muse-sprak-1.2:api");
        r = ServerConfigRules.apply(f, KEYS::get, true);
        assertFalse(r.magician(), "a misspelt fallback is never a silent shorter chain");
        assertTrue(r.log().get(0).contains("muse-sprak-1.2"), r.log().toString());
    }
}
