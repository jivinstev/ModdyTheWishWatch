package com.moddingfromamod.wish;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Who really sent a wish</b> — a child, or somebody testing on a production server.
 *
 * <p>{@code RunContext.isTestRun()} answers "is this JVM a test", and on a dedicated server it is always
 * false. So every wish a session, a canary or a probe sent on a live server was logged
 * {@code test: false} and counted as a child's play: 's 47 "real" {@code made} records,
 * 18 were test traffic (canary wishes from a stand-in account, and synthetic-catalog wishes sent
 * as a real player's account). {@code source}
 * cannot say it either: {@code COMMAND} is also {@code /wish}, which is how the child sends every
 * wish longer than the anvil box's 50 characters.
 *
 * <p>So the origin is decided AT SUBMIT, from facts the server holds, and stamped on the wish's
 * record and on every later record that carries its id ({@code ActionLog.append}). In order:
 * <ol>
 *   <li>an explicit {@link #scope} — the code paths that know they are a test
 *       ({@code /moddyop canary}, the soak and headless probes);</li>
 *   <li>a configured STAND-IN account ({@code -Dmoddingfromamod.testPlayers}, from
 *       {@code MODDY_TEST_PLAYERS}: UUIDs or names, comma separated);</li>
 *   <li>a SESSION window an operator armed for one player ({@code /moddyop testwishes <player>
 *       [minutes]}) — the only way to mark wishes a session sends as a REAL account, which the
 *       server otherwise cannot tell from the child playing.</li>
 * </ol>
 * Anything else is {@link #CHILD}. Minecraft-free on purpose: the decision is unit-tested.
 */
public final class WishOrigin {

    public static final String CHILD = "child";
    public static final String CANARY = "canary";
    public static final String PROBE = "probe";
    public static final String STAND_IN = "stand-in";
    public static final String SESSION = "session";

    /** The JVM property the launcher sets from {@code MODDY_TEST_PLAYERS}. */
    public static final String TEST_PLAYERS_PROPERTY = "moddingfromamod.testPlayers";

    /** The longest a session window may be armed for, so a forgotten one cannot swallow play. */
    public static final int MAX_WINDOW_MINUTES = 240;
    public static final int DEFAULT_WINDOW_MINUTES = 60;

    private static final int REMEMBERED = 2048;

    private static final ThreadLocal<String> SCOPED = new ThreadLocal<>();
    /** player key (lower-case UUID or name) → window end, epoch ms. */
    private static final Map<String, Long> WINDOWS = new ConcurrentHashMap<>();
    /** wish id (full or short) → a non-child origin. Only test wishes are remembered. */
    private static final Map<String, String> BY_WISH = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > REMEMBERED;
                }
            });

    private WishOrigin() {
    }

    /** True for every origin but {@link #CHILD}. */
    public static boolean isTest(String origin) {
        return origin != null && !origin.isBlank() && !CHILD.equals(origin);
    }

    /**
     * The pure decision. {@code scoped} wins, then a stand-in, then an armed window; null-safe
     * throughout, because a wish with no player still has to be logged.
     */
    static String decide(String scoped, String playerId, String playerName, Set<String> standIns,
                         Map<String, Long> windows, long nowMs) {
        if (scoped != null && !scoped.isBlank()) {
            return scoped;
        }
        String id = key(playerId);
        String name = key(playerName);
        if ((id != null && standIns.contains(id)) || (name != null && standIns.contains(name))) {
            return STAND_IN;
        }
        for (String k : new String[] {id, name}) {
            Long until = k == null ? null : windows.get(k);
            if (until != null && until > nowMs) {
                return SESSION;
            }
        }
        return CHILD;
    }

    /** The origin of a wish being submitted NOW by this player. */
    public static String of(String playerId, String playerName) {
        return decide(SCOPED.get(), playerId, playerName,
                parseStandIns(System.getProperty(TEST_PLAYERS_PROPERTY)), WINDOWS,
                System.currentTimeMillis());
    }

    /** {@code MODDY_TEST_PLAYERS}' value as a set of lower-case keys; blanks dropped. */
    static Set<String> parseStandIns(String raw) {
        Set<String> out = new TreeSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\\s]+")) {
            String k = key(part);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    private static String key(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Everything submitted on this thread until {@link Scope#close} is {@code origin}. For the
     * code that IS a test ({@code CanaryWish}, {@code SoakProbe}, {@code HeadlessWishProbe}).
     */
    public static Scope scope(String origin) {
        String before = SCOPED.get();
        SCOPED.set(origin);
        return () -> {
            if (before == null) {
                SCOPED.remove();
            } else {
                SCOPED.set(before);
            }
        };
    }

    /** A scope that cannot throw on close, so it reads as a plain try-with-resources. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Arm a session window: every wish {@code player} (a UUID or a name; both are matched) sends
     * for the next {@code minutes} is {@link #SESSION}. {@code minutes <= 0} disarms it. Clamped to
     * {@link #MAX_WINDOW_MINUTES}. Returns the window's end, or 0 when disarmed.
     */
    public static long arm(String player, int minutes, long nowMs) {
        String k = key(player);
        if (k == null) {
            return 0;
        }
        if (minutes <= 0) {
            WINDOWS.remove(k);
            return 0;
        }
        long until = nowMs + Math.min(minutes, MAX_WINDOW_MINUTES) * 60_000L;
        WINDOWS.put(k, until);
        return until;
    }

    /** The armed windows, for the operator's listing; expired ones are dropped on the way. */
    public static Map<String, Long> windows(long nowMs) {
        WINDOWS.values().removeIf(until -> until <= nowMs);
        return Map.copyOf(WINDOWS);
    }

    /** Remember that this wish is test traffic, so its later records are stamped too. */
    public static void remember(String wishId, String origin) {
        if (wishId == null || wishId.isBlank() || !isTest(origin)) {
            return;
        }
        BY_WISH.put(wishId, origin);
        if (wishId.length() > 8) {
            BY_WISH.put(wishId.substring(0, 8), origin);
        }
    }

    /** The remembered origin of a wish id, or null for a child's wish (or one never seen). */
    public static String ofWish(String wishId) {
        if (wishId == null || wishId.isBlank()) {
            return null;
        }
        String o = BY_WISH.get(wishId);
        if (o == null && wishId.length() > 8) {
            o = BY_WISH.get(wishId.substring(0, 8));
        }
        return o;
    }

    /** Test isolation only. */
    static void resetForTest() {
        WINDOWS.clear();
        BY_WISH.clear();
        SCOPED.remove();
    }
}
