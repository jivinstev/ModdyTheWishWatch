package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * <b>The probe for Gate A's one retry </b>, run by {@code tools/test-gate-a-retry.sh} through real Gradle.
 *
 * <p>Inert unless asked: with no {@code -Pgate.probe=DIR} every test here passes at once. With it, a test misbehaves
 * only when its switch file exists in DIR, counting attempts in a file (a retry may be a new test JVM):
 * {@code flaky.on} — {@link #failsOnceThenPasses} fails its first attempt; {@code always.on} — {@link #alwaysFails}
 * fails every attempt; {@code six.on} — the six {@code oneOfSix…} tests all fail (more than {@code maxFailures} = 5,
 * so nothing may be retried).
 */
class GateRetryProbeTest {

    private static int attempt(String sw, String counter) throws IOException {
        String dir = System.getProperty("moddy.gate.probe", "");
        if (dir.isBlank() || !Files.exists(Path.of(dir, sw + ".on"))) {
            return 0;
        }
        Path c = Path.of(dir, counter + ".count");
        int n = (Files.exists(c) ? Integer.parseInt(Files.readString(c).trim()) : 0) + 1;
        Files.writeString(c, Integer.toString(n));
        return n;
    }

    @Test
    void failsOnceThenPasses() throws IOException {
        if (attempt("flaky", "failsOnceThenPasses") == 1) {
            fail("gate probe: the first attempt fails on purpose (#2598)");
        }
    }

    @Test
    void alwaysFails() throws IOException {
        int n = attempt("always", "alwaysFails");
        if (n > 0) {
            fail("gate probe: fails every attempt on purpose (attempt " + n + ", #2598)");
        }
    }

    private static void sixth(String name) throws IOException {
        if (attempt("six", name) > 0) {
            fail("gate probe: one of six failing tests (#2598)");
        }
    }

    @Test
    void oneOfSix1() throws IOException {
        sixth("oneOfSix1");
    }

    @Test
    void oneOfSix2() throws IOException {
        sixth("oneOfSix2");
    }

    @Test
    void oneOfSix3() throws IOException {
        sixth("oneOfSix3");
    }

    @Test
    void oneOfSix4() throws IOException {
        sixth("oneOfSix4");
    }

    @Test
    void oneOfSix5() throws IOException {
        sixth("oneOfSix5");
    }

    @Test
    void oneOfSix6() throws IOException {
        sixth("oneOfSix6");
    }
}
