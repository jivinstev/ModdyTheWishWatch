package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.health.HealthHook;
import com.moddingfromamod.marshal.FakeClock;
import com.moddingfromamod.plugin.PluginScope;
import com.moddingfromamod.plugin.Plugins;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@code /canary}: an external monitor reads a health plug-in's last canary verdict
 * here. Driven over real HTTP against a temporary mod home.
 *
 * <p>Since the verdict comes from a server's {@link HealthHook} plug-in. A
 * unit JVM loads no plug-in, so this test registers a STUB hook answering what {@link #VERDICT} holds, and pins what
 * the pack host itself owns: 404 with no verdict, the line served as is, and the rate limit.
 *
 * <p>The class runs with a registry of its own ({@link PluginScope}), so its stub is the only hook, and it is gone
 * for the next test class in the JVM ({@code PackHostCapsTest}'s "no health plug-in answers /healthz" sees none).
 */
@ExtendWith(PluginScope.class)
class PackHostCanaryTest {
    private static final String HOME_PROPERTY = "moddingfromamod.home";
    private static final AtomicReference<Optional<String>> VERDICT = new AtomicReference<>(Optional.empty());
    private String previousHome;
    private Path home;

    @BeforeAll
    static void stubHook() {
        Plugins.shared().add(HealthHook.class, new HealthHook() {
            @Override public boolean healthy(long nowMs) {
                return true;
            }

            @Override public Optional<String> canary() {
                return VERDICT.get();
            }
        });
    }

    /** The verdict, as a canary job writes it (a real hook may read this) and as the stub answers it. */
    private void verdict(String json, String line) throws IOException {
        Files.writeString(home.resolve("canary.json"), json);
        VERDICT.set(Optional.of(line));
    }

    @BeforeEach
    void aTemporaryModHome() throws IOException {
        previousHome = System.getProperty(HOME_PROPERTY);
        home = Files.createTempDirectory("mfam-canary-home");
        System.setProperty(HOME_PROPERTY, home.toString());
        VERDICT.set(Optional.empty());
    }

    @AfterEach
    void stopTheHost() {
        VERDICT.set(Optional.empty());
        PackHost.stop();
        PackHost.limitsForTest(null);
        if (previousHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, previousHome);
        }
    }

    private record Reply(int code, String body) { }

    private static Reply get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5_000);
        c.setReadTimeout(5_000);
        int code = c.getResponseCode();
        String body = "";
        if (code == 200) {
            try (InputStream in = c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        c.disconnect();
        return new Reply(code, body);
    }

    @Test
    @DisplayName("no verdict yet (the timer is not loaded) is a 404, never an empty OK")
    void noVerdictIs404() throws IOException {
        int port = PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        assertEquals(404, get("http://127.0.0.1:" + port + "/canary").code());
    }

    @Test
    @DisplayName("the hook's verdict line comes back as its one line")
    void aVerdictIsServed() throws IOException {
        verdict("{\"v\":1,\"verdict\":\"STUCK\",\"seconds\":1200,\"at\":1727200000}", "STUCK 1200 1727200000");
        int port = PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        Reply r = get("http://127.0.0.1:" + port + "/canary");
        assertEquals(200, r.code());
        assertEquals("STUCK 1200 1727200000", r.body());
    }

    @Test
    @DisplayName("unlike /healthz it IS rate limited — a refused read is 'no data' to an external monitor, never 'down'")
    void itIsRateLimited() throws IOException {
        verdict("{\"v\":1,\"verdict\":\"OK\",\"seconds\":31,\"at\":9}", "OK 31 9");
        int port = PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        PackHost.limitsForTest(new PackHostLimits(1, 16, new FakeClock(0L)));
        String url = "http://127.0.0.1:" + port + "/canary";
        assertEquals(200, get(url).code());
        assertEquals(429, get(url).code());
    }
}
