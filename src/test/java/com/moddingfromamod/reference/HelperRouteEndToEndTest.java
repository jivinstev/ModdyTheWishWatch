package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.agent.ModelFallbackRuntime;
import com.moddingfromamod.agent.MuseExec;
import com.moddingfromamod.config.HelperRoute;
import com.moddingfromamod.config.ModdySettings;
import com.moddingfromamod.config.SettingsCatalog;
import com.moddingfromamod.config.SettingsOverride;
import com.moddingfromamod.readymade.ReadyMadeNudge;
import com.moddingfromamod.safety.SafetyGrader;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>A helper never reaches a provider the player did not choose</b>, through EVERY helper job's
 * real code: the wish-text and plan graders, world rules, the ready-made nudge, the character lookup, the picture
 * describer and the reference researcher. A fake {@code claude} and a fake {@code muse} append each call they receive
 * to one log, and Meta's describer endpoint ({@link EmberVision}) is a local server that counts requests, so "no call to
 * Meta" and "no call to Anthropic" are counted, not inferred. Both keys are present in every case: that is the bug.
 *
 * <p><b>Hermetic </b>: "both keys are present" is said through every seam that reads one (MuseExec, EmberVision,
 * HelperRoute, ModelFallbackRuntime), never by the machine. Gate A's public half runs with the model keys REMOVED from
 * its environment, as the public repo's CI runner has none, and this test passed here and failed there while the
 * fallback runtime still read the real {@code MUSE_API_KEY}. What the fake {@code muse} INHERITS is the JVM's own
 * environment, which no test can set, so a key step's {@code env=} is compared with {@link #INHERITED}; that a
 * sign-in step strips the key is {@code HelperRouteTest}'s, on a map, and also checked here wherever a key is inherited.
 */
class HelperRouteEndToEndTest {

    @TempDir
    Path tmp;

    /** Both providers' keys, as every seam is told them. */
    private static final Map<String, String> KEYS = Map.of("MUSE_API_KEY", "LLM_realkey",
            "ANTHROPIC_API_KEY", "sk-ant-realkey");
    /** What the fake {@code muse} sees of a Muse key this JVM itself was started with: "yes", or "" (a CI runner). */
    private static final String INHERITED = present(System.getenv("MUSE_API_KEY"))
            || present(System.getenv("META_API_KEY")) ? "yes" : "";
    /** The fake {@code muse}'s line for a call on the Muse key (on stdin; its environment is the JVM's). */
    private static final String ON_KEY = "muse key=stdin env=" + INHERITED;

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private Path log;
    private HttpServer meta;
    private final AtomicInteger metaHits = new AtomicInteger();
    private final Map<String, String> before = new HashMap<>();
    private java.util.function.Supplier<String> emberKeyWas;
    private String endpointWas;

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"violence\":{\"type\":\"string\"}},"
            + "\"required\":[\"violence\"]}";

    @BeforeEach
    void fakes() throws Exception {
        log = tmp.resolve("calls.log");
        Path claude = script("claude", "echo \"claude key=${ANTHROPIC_API_KEY:+yes}\" >> '" + log + "'\ncat >/dev/null");
        Path muse = script("muse", "k=no; for a in \"$@\"; do [ \"$a\" = --api-key-stdin ] && k=stdin; done\n"
                + "echo \"muse key=$k env=${MUSE_API_KEY:+yes}\" >> '" + log + "'\ncat >/dev/null");
        for (String p : List.of("moddingfromamod.cli", "moddingfromamod.muse.bin", "moddingfromamod.muse.shim")) {
            before.put(p, System.getProperty(p));
        }
        System.setProperty("moddingfromamod.cli", claude.toString());
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", "none");
        // Both providers' keys, everywhere a job could read one.
        MuseExec.keyForTest(() -> "LLM_realkey");
        emberKeyWas = EmberVision.keySource;
        EmberVision.keySource = () -> "LLM_realkey";
        HelperRoute.envForTest(KEYS);
        // The fallback runtime decides whether a key entry can run at all from the environment: tell it, too.
        ModelFallbackRuntime.forTest(line -> { }, KEYS::get, null, null);
        meta = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        meta.createContext("/", ex -> {
            metaHits.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        meta.start();
        endpointWas = EmberVision.endpoint;
        EmberVision.endpoint = "http://127.0.0.1:" + meta.getAddress().getPort() + "/v1/responses";
    }

    @AfterEach
    void restore() {
        before.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
        MuseExec.keyForTest(null);
        EmberVision.keySource = emberKeyWas;
        EmberVision.endpoint = endpointWas;
        HelperRoute.envForTest(null);
        ModelFallbackRuntime.forTest(null, null, null, null);
        ModdySettings.overridesForTest(null);
        meta.stop(0);
    }

    private Path script(String name, String body) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    /** Magician on, with these settings as a host's own picks (not locked) or a dedicated server's settings override (locked). */
    private static void settings(Map<String, String> values, boolean houseLock) {
        Map<String, String> v = new HashMap<>(values);
        v.put(SettingsCatalog.MAGICIAN, "on");
        ModdySettings.overridesForTest(List.of(new SettingsOverride() {
            public Set<String> keys() {
                return houseLock ? Set.of(SettingsCatalog.MAGICIAN, SettingsCatalog.MODEL, "magician.*", "helper.*")
                        : Set.of(SettingsCatalog.MAGICIAN);
            }

            public Optional<String> value(String key) {
                return Optional.ofNullable(v.get(key));
            }
        }));
    }

    private static Map<String, String> picks(String main, String mainMode, String cheap, String cheapMode) {
        return Map.of(SettingsCatalog.MAIN_MODEL, main, SettingsCatalog.MAIN_MODE, mainMode,
                SettingsCatalog.CHEAP_MODEL, cheap, SettingsCatalog.CHEAP_MODE, cheapMode);
    }

    private List<String> calls() throws Exception {
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    /** Every helper job, through its real entry point. Ember is asked for by name wherever a job can be. */
    private void everyHelperJob(String tag) {
        new SafetyGrader.CliTransport().grade("PROMPT", "a wish", SCHEMA, 10_000, "muse:muse-ember-1.0", "low");
        new SafetyGrader.CliTransport().grade("PROMPT", "a wish", SCHEMA, 10_000, null, null);
        new SafetyGrader.CliTransport(() -> "muse:muse-ember-1.0", () -> SafetyGrader.HAIKU_PIN,
                HelperRoute.Job.WORLD_RULES).grade("PROMPT", "a rule", SCHEMA, 10_000);
        new ReadyMadeNudge.CliTransport(System.getProperty("moddingfromamod.cli"), "muse:muse-ember-1.0", "low",
                tmp).ask("PROMPT", "a wish", SCHEMA, 10_000);
        CharacterResolver.resolveSubject("make me a big " + tag + " from my favourite show");
        BriefLooks.describePictures("Zorbix" + tag, List.of(
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)), "");
        ReferenceResearcher.askRaw("Zorbix" + tag);
    }

    @Test
    void aClaudeOnlyPlayerWithAMuseKeySetNeverReachesMeta() throws Exception {
        settings(picks("opus-5.5", "subscription", "haiku-5.5", "subscription"), false);
        everyHelperJob("claudeonly");
        List<String> calls = calls();
        assertEquals(0, metaHits.get(), "the describer billed Meta's API for a Claude-only player (the #2583 bug)");
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("muse")), "a helper started Muse Code: " + calls);
        assertTrue(calls.size() >= 7, "every job asked Claude: " + calls);
        assertTrue(calls.stream().allMatch(c -> c.equals("claude key=")),
                "a Claude-subscription player's helpers ran on the sign-in, never on the key that is set: " + calls);
    }

    @Test
    void aMuseOnlyPlayerWithAnAnthropicKeySetNeverReachesAnthropic() throws Exception {
        // Ember on the Muse key (a sign-in pick is skipped here by the fallback runtime: this machine has no Muse Code
        // login, which is that runtime's job, not the route's).
        settings(picks("muse-spark-1.2", "api", "muse-ember-1.0", "api"), false);
        everyHelperJob("museonly");
        List<String> calls = calls();
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("claude")), "a helper started Claude: " + calls);
        assertTrue(calls.size() >= 6, "every Muse Code job asked Muse Code: " + calls);
        assertTrue(calls.stream().allMatch(c -> c.equals(ON_KEY)),
                "each on the Muse key they chose: " + calls);
        assertEquals(1, metaHits.get(), "the describer took its direct key leg (Ember, the player's choice)");
    }

    @Test
    void aMuseSignInPlayerNeverHasTheMuseKeyUsed() throws Exception {
        settings(picks("muse-spark-1.2", "subscription", "muse-ember-1.0", "subscription"), false);
        everyHelperJob("musesub");
        List<String> calls = calls();
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("claude")), "a helper started Claude: " + calls);
        assertTrue(calls.stream().noneMatch(c -> c.contains("key=stdin") || c.contains("env=yes")),
                "a sign-in player's helper sent the Muse key: " + calls);
        assertEquals(0, metaHits.get(), "the describer's direct key leg ran for a player who chose the sign-in");
    }

    @Test
    void aServersRouteIsHonouredInItsOrder() throws Exception {
        settings(Map.of("helper.route.plan", "claude-subscription,muse-key"), true);
        new SafetyGrader.CliTransport().grade("PROMPT", "a wish", SCHEMA, 10_000, "muse:muse-ember-1.0", "low");
        assertEquals(List.of("claude key=", ON_KEY), calls(),
                "Haiku on the sign-in first (it gave no answer), then Ember on the key");
    }

    @Test
    void aServerWithNoRouteDoesExactlyWhatItDidBefore() throws Exception {
        settings(Map.of(), true);
        new SafetyGrader.CliTransport().grade("PROMPT", "a wish", SCHEMA, 10_000, "muse:muse-ember-1.0", "low");
        // No shim, with a Muse key: Ember on the key, then the pinned Haiku on the CLI as the machine
        // has it (HelperRoute.envForTest says an Anthropic key is set, so the key step; the fake's own environment
        // has none, so that step cannot run and nothing else is tried — as before, one Claude call at most).
        assertEquals(List.of(ON_KEY), calls());
        Files.deleteIfExists(log);
        HelperRoute.envForTest(Map.of("MUSE_API_KEY", "LLM_realkey"));
        new SafetyGrader.CliTransport().grade("PROMPT", "a wish", SCHEMA, 10_000, "muse:muse-ember-1.0", "low");
        assertEquals(List.of(ON_KEY, "claude key="), calls(),
                "no Anthropic key: Ember on the key, then Haiku on the CLI's sign-in");
    }
}
