package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.moddingfromamod.marshal.FakeClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The caps, over real HTTP, through the handler that ships</b> (plan §5).
 *
 * <p>{@code RateLimiterTest} proves the arithmetic. This proves the <b>wiring</b>, which is the
 * half that has failed before in this repo: an earlier issue's feed gate and an earlier issue's two guards were all green
 * where they were defined and had never once fired where they ran. So this starts the real
 * {@link PackHost} on an ephemeral port, publishes a real pack, and fetches it with a real
 * {@code HttpURLConnection}.
 *
 * <p>The clock is driven rather than waited on — at the shipped 60/min a refill test would sleep
 * for a minute, and at 2/min for thirty seconds.
 */
class PackHostCapsTest {

    @AfterEach
    void stopTheHost() {
        PackHost.stop();
        PackHost.limitsForTest(null);
    }

    private static int get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5_000);
        c.setReadTimeout(5_000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static String publishAPack() throws IOException {
        Path dir = Files.createTempDirectory("mfam-pack-caps");
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        return PackHost.publish(dir, "127.0.0.1");
    }

    @Test
    @DisplayName("200, then 429 once the bucket is empty, then 200 again after it refills")
    void theRateCapIsWiredToTheHandler() throws IOException {
        PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        FakeClock clock = new FakeClock(0L);
        // Two a minute, so the third request in an instant is over the line. rpm is what an
        // operator sets; the clock is what a test drives.
        PackHost.limitsForTest(new PackHostLimits(2, 16, clock));
        String url = publishAPack();

        assertEquals(200, get(url), "the first fetch is an ordinary one");
        assertEquals(200, get(url), "and so is the second");
        assertEquals(429, get(url),
                "the third must be refused — this is the assertion that fails when the cap is not "
                        + "wired into the handler, however green RateLimiterTest is");

        clock.advance(60_000L);
        assertEquals(200, get(url), "a minute later the bucket has refilled and it serves again");
    }

    @Test
    @DisplayName("/healthz is EXEMPT from the rate limit — a 429 there would read as the server down")
    void healthzIsExempt() throws IOException {
        int port = PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        FakeClock clock = new FakeClock(0L);
        PackHost.limitsForTest(new PackHostLimits(1, 16, clock));
        String url = publishAPack();
        assertEquals(200, get(url));
        assertEquals(429, get(url), "the pack route really is limited at one a minute");

        String health = "http://127.0.0.1:" + port + "/healthz";
        for (int i = 0; i < 5; i++) {
            int code = get(health);
            // 404 is this test's answer: no health plug-in answers /healthz in a unit test. What is pinned is
            // that the rate limit never answers it.
            assertTrue(code == 200 || code == 503 || code == 404,
                    "a pinger must never get 429 — it would report the server DOWN; got " + code);
        }
    }

    @Test
    @DisplayName("503 once the concurrency cap is taken, 200 again when the slot is given back")
    void theConcurrencyCapIsWiredToTheHandler() throws IOException {
        PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        PackHostLimits limits = new PackHostLimits(0, 1, new FakeClock(0L));
        PackHost.limitsForTest(limits);
        String url = publishAPack();

        assertEquals(200, get(url), "with the one slot free it serves");
        // ⚠ WAIT FOR THE SLOT TO COME BACK FIRST. `getResponseCode()` returns when the HEADERS
        // arrive, so the handler's own `finally` may not have run yet — the first version of this
        // test asserted straight afterwards and went red against correct code.
        awaitNoSlotsHeld(limits);
        // HOLD the only slot, exactly as an in-flight response does. Racing two real downloads
        // would be a flaky way to ask the same question.
        assertTrue(limits.tryEnter(), "the handler never gave its slot back");
        assertEquals(503, get(url),
                "with every slot taken the host must refuse fast rather than queue — the assertion "
                        + "that fails if tryEnter() is not called on the request path");
        limits.exit();
        assertEquals(200, get(url), "and the slot really is given back");
        awaitNoSlotsHeld(limits);
        assertEquals(0, limits.inFlight(), "no slot was leaked by the handler");
    }

    @Test
    @DisplayName("a slot is given back to the limits it was TAKEN from, not to whatever is current")
    void aSlotIsReleasedOnTheLimitsItWasTakenFrom() throws Exception {
        // ⚠ THIS IS THE FLAKE THAT TOOK CI RED ON `main` ON 2026-09-21, reproduced without any
        // timing luck. The handler snapshotted `limits` into `caps`, gated on `caps.tryEnter()`,
        // and then released with `limits.exit()` in its finally -- two reads of a volatile with a
        // whole response in between. Take from A, give back to whatever B happens to be now.
        //
        // In production the field only moves in a test fixture, so this never mis-served a child.
        // In the SUITE it is a cross-test corruption: a handler still in its finally when the next
        // test installs its own caps decrements THAT object to -1, `awaitNoSlotsHeld` sees -1 (it
        // waits for `> 0`, and -1 is not > 0) and returns instantly, the test's own `tryEnter`
        // then succeeds off the negative count, and the request that must be refused is served.
        // `theConcurrencyCapIsWiredToTheHandler` then reads 200 where it demands 503 -- which is
        // how a real accounting bug presented as an unreliable test.
        PackHost.start("127.0.0.1", PackHost.ANY_PORT);
        PackHostLimits taken = new PackHostLimits(0, 1, new FakeClock(0L));
        PackHost.limitsForTest(taken);
        // Big and incompressible, so the handler BLOCKS in out.write() with the slot held rather
        // than finishing before we can swap. That is what makes this deterministic instead of a
        // race we would be running repeatedly and hoping to lose.
        String url = publishABigPack();

        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5_000);
        c.setReadTimeout(5_000);
        assertEquals(200, c.getResponseCode(), "headers are out, so the handler holds the slot");
        awaitInFlight(taken, 1);
        assertEquals(1, taken.inFlight(), "the in-flight request is accounted on the caps it took");

        // Exactly what @AfterEach + the next test's fixture do between two test methods.
        PackHostLimits current = new PackHostLimits(0, 1, new FakeClock(0L));
        PackHost.limitsForTest(current);

        c.disconnect();                       // the write fails; the handler runs its finally
        awaitInFlight(taken, 0);

        assertEquals(0, taken.inFlight(),
                "the slot must come back to the caps it was taken from -- 1 here is the leak");
        assertEquals(0, current.inFlight(),
                "and it must NOT be subtracted from caps that never lent it -- -1 here is the "
                        + "corruption that makes the next test read 200 where it demands 503");
    }

    /** Wait (briefly) for {@code limits.inFlight()} to reach {@code want}. */
    private static void awaitInFlight(PackHostLimits limits, int want) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (limits.inFlight() != want && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * A pack too big for the socket buffers, so the handler is still inside its {@code try} when
     * we come back. Random bytes because the zip would otherwise compress a constant to nothing.
     */
    private static String publishABigPack() throws IOException {
        Path dir = Files.createTempDirectory("mfam-pack-caps-big");
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        byte[] noise = new byte[4 * 1024 * 1024];
        new Random(20260921L).nextBytes(noise);
        Files.write(dir.resolve("assets").resolve("noise.bin"), noise);
        try {
            return PackHost.publish(dir, "127.0.0.1");
        } finally {
            // publish zips into memory, so the directory is spent the moment it returns. Left
            // behind it was 4 MB of random bytes per run in /tmp: 1,100+ of them (~480 MB) had
            // piled up in one cloud container and helped fill its disk (2026-09-27).
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Wait (briefly) for every handler to have run its {@code finally}.
     *
     * <p>⚠ {@code != 0}, NOT {@code > 0}. It waited for {@code > 0} to clear, and a count of
     * <b>-1</b> is not {@code > 0} -- so a corrupted accounting returned from here instantly and
     * looking perfect, which is half of why the bug above read as a flaky test instead of as a
     * leak. An instrument must not treat "impossible" as "done".
     */
    private static void awaitNoSlotsHeld(PackHostLimits limits) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (limits.inFlight() != 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
