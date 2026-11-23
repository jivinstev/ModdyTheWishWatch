package com.moddingfromamod.vanilla;

import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Clock;

/**
 * <b>What the pack host will and will not answer</b> — the two caps its plan asks for (
 * <i>"the pack host public but serving only content-addressed zips — the one thing worth adding is
 * a request-rate cap and a size cap, since it is now an internet service"</i>).
 *
 * <ul>
 *   <li><b>Rate</b>, per remote address: {@link RateLimiter}, default 60/min, answering <b>429</b>.
 *       {@code -Dmoddingfromamod.packhost.rpm=0} turns it off.</li>
 *   <li><b>Concurrency</b>, over the whole host: at most {@value #DEFAULT_MAX_CONCURRENT} responses
 *       being written at once, answering <b>503</b> beyond that. This is the "size" cap in the only
 *       form that means anything here — every body is one cached, content-addressed zip of a known
 *       length (measured: 44,855 bytes for a Spikor), so what a stranger can actually cost us is
 *       the number of them in flight, not the size of any one.</li>
 * </ul>
 *
 * <h2>{@code /healthz} is exempt from the rate limit, on purpose</h2>
 *
 * <p>It exists so a free external pinger can notice the server going away, and a pinger that got a
 * 429 would report the server DOWN — turning the rate limit into an alerting bug. It costs one
 * boolean and no bytes. It is <i>not</i> exempt from the concurrency gate, because a 503 is exactly
 * what it should say when the host is saturated.
 *
 * <h2>Read once, at {@link PackHost#start}</h2>
 *
 * <p>Properties are read when the host starts rather than per request, so the cost is not paid on
 * the hot path and a test can set the property and restart the host to get a limiter it can drive.
 */
public final class PackHostLimits {

    /** {@code -Dmoddingfromamod.packhost.rpm=60} — requests per minute per remote address; 0 = off. */
    public static final String RPM_PROPERTY = ModdingFromAModMod.MODID + ".packhost.rpm";

    /** {@code -Dmoddingfromamod.packhost.maxconcurrent=16} — responses being written at once. */
    public static final String CONCURRENT_PROPERTY =
            ModdingFromAModMod.MODID + ".packhost.maxconcurrent";

    /**
     * Sixty a minute per address.
     *
     * <p>A small server's whole day is a few dozen: a stock client fetches the item pack once at join
     * and nothing per wish (the per-wish rig pack went with the pack rig). So this is roughly two orders of
     * magnitude above what playing looks like, which is where a limit belongs — it must never be
     * something a child can reach by playing.
     */
    public static final int DEFAULT_RPM = 60;

    /** Sixteen at once. Above this the answer is 503 rather than a longer and longer queue. */
    public static final int DEFAULT_MAX_CONCURRENT = 16;

    private final RateLimiter limiter;
    private final int maxConcurrent;
    private final AtomicInteger inFlight = new AtomicInteger();

    public PackHostLimits(int perMinute, int maxConcurrent, Clock clock) {
        this.limiter = new RateLimiter(perMinute, clock);
        this.maxConcurrent = maxConcurrent;
    }

    /** The shipped configuration, read from the properties above. */
    public static PackHostLimits fromProperties() {
        return new PackHostLimits(
                intProperty(RPM_PROPERTY, DEFAULT_RPM),
                Math.max(1, intProperty(CONCURRENT_PROPERTY, DEFAULT_MAX_CONCURRENT)),
                Clock.system());
    }

    private static int intProperty(String key, int fallback) {
        try {
            String raw = System.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** May this caller be served, or is it 429? */
    public boolean allow(String remoteAddress) {
        return limiter.allow(remoteAddress);
    }

    /**
     * Take a slot, or refuse with 503.
     *
     * <p><b>Every {@code tryEnter} that returns true must be matched by an {@link #exit()}</b>, in a
     * {@code finally}. A leaked slot is a host that answers 503 forever with nothing running, which
     * looks exactly like being under attack.
     */
    public boolean tryEnter() {
        if (inFlight.incrementAndGet() > maxConcurrent) {
            inFlight.decrementAndGet();
            return false;
        }
        return true;
    }

    /** @see #tryEnter() */
    public void exit() {
        inFlight.decrementAndGet();
    }

    /** How many responses are being written right now. */
    public int inFlight() {
        return inFlight.get();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** The bucket, for a status line and for a test. */
    public RateLimiter limiter() {
        return limiter;
    }

    /**
     * The address half of a {@code host:port} or {@code /1.2.3.4:5678} remote address.
     *
     * <p>Keyed on the ADDRESS, never the socket: a port changes per connection, so keying on the
     * whole thing gives every request a fresh full bucket — a limiter that measures nothing while
     * looking exactly like one that works.
     */
    public static String keyFor(java.net.InetSocketAddress remote) {
        if (remote == null || remote.getAddress() == null) {
            return "";
        }
        return remote.getAddress().getHostAddress();
    }
}
