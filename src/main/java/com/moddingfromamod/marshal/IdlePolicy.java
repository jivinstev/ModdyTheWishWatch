package com.moddingfromamod.marshal;

/**
 * <b>When the wish pool should stop existing, and when it should come back</b> (steps 2
 * and 4).
 *
 * <p>Pure and Minecraft-free, so the rule that decides whether to take a child's wish-granter away
 * is unit-testable rather than discovered in a play session. {@link PoolPlan} answers "how many
 * workers should exist"; this answers "should any".
 *
 * <h2>The case this exists for is a world nobody is sitting at</h2>
 *
 * <p>{@link PoolPlan} cut the pool from three parked workers to one, and {@link
 * com.moddingfromamod.agent.PromptCache} halved what its writes cost. Neither of those helps the
 * scenario that actually costs the most: <b>a Minecraft session left open for three days with a
 * child at the keyboard for two hours of it.</b> Nothing in the pool stops a worker polling all
 * night; only made it poll half as often.
 *
 * <p>Measured: an idle {@code await_wish} poll costs ~$0.041 and lands every {@link
 * com.moddingfromamod.agent.PromptCache#POLL_MS} — <b>~$1.33 an hour per parked worker</b>. Over
 * 72 hours that is ~$96 for a pool nobody spoke to.
 *
 * <h2>THE SIGNAL IS PRESENCE, NOT A TIMER, AND THAT IS THE WHOLE DESIGN</h2>
 *
 * <p>The obvious rule is "reap after N minutes with no wish". It is the wrong first rule, because
 * it costs a child something: the pool's own promise is that <i>"by the time a child has found the
 * watch, the agent is already parked on await_wish and answers in milliseconds"</i>, and an idle
 * timeout trades that for a ~10–13 s boot on the next wish after a quiet spell. Whether that is
 * acceptable is a UX decision (docs/CONCURRENT_WISHES_UX.md), not a cost one.
 *
 * <p><b>Player absence costs nobody anything</b>, because there is nobody there to wait. So that was
 * the first rule, {@link #shouldReap}.
 *
 * <h2>The second rule: quiet while they play, and woken by INTENT (step 4, 2026-09-24)</h2>
 *
 * <p>The UX call was option C: reap after {@link #ONLINE_IDLE_MS} with nobody touching
 * Moddy even though children are online, and wake the pool on the first sign of a wish — the
 * watch right-click that opens the panel — so the boot overlaps the child choosing or typing. A
 * submitted wish wakes it too (the backstop for {@code /wish} typed in chat), and if that wish
 * really has to wait the child is told once ({@link #wakeSaysSo}). {@link #shouldReapWhileOnline}
 * is the rule; {@link #shouldBoot} is its mirror and must never contradict it.
 *
 * <h2>The threshold is DERIVED, not chosen</h2>
 *
 * <p>Per docs/WISH_LINTERS.md P9 — derive a threshold from measurement, never invent one. There is
 * no UX cost to reaping an empty world, so the only reason to wait at all is to avoid paying a boot
 * for a player who disconnected and came straight back (a relog, a crash-and-rejoin, a lobby hop).
 * That makes the correct grace exactly the <b>break-even</b>: below it, keeping the worker parked is
 * cheaper than rebooting it; above it, it is not.
 *
 * <pre>
 *   $0.57 cold reboot / $1.33 per idle hour = 0.429 h = ~25.7 minutes
 * </pre>
 *
 * <p>{@link #GRACE_MS} is computed from those two measured numbers rather than typed, so the
 * arithmetic is the constant. Re-measure either and the threshold moves with it.
 */
public final class IdlePolicy {

    private IdlePolicy() {
    }

    /**
     * What one parked worker costs per hour doing nothing — MEASURED on the model the pool really
     * runs.
     *
     * <p>{@code tools/pool-economics.py} on the live harvest, 2026-09-26…30, {@code muse-spark-1.2-contributor}
     * at list price: a parked poll costs $0.0027 and lands every 112 s — <b>$0.087 per worker-hour</b>
     * (n = 412 polls). It was $1.33, measured on Claude (2026-08-31), and the pool has not
     * run on Claude since; that constant is why an empty world waited ~26 minutes for its reap.
     * Held as dollars rather than as a derived interval because the poll interval is
     * {@code PromptCache.POLL_MS}'s business and this is the number that was actually measured.
     */
    public static final double IDLE_DOLLARS_PER_HOUR = 0.087;

    /**
     * What starting a worker costs after a reap: its boot turns, priced COLD (2026-09-24).
     *
     * <p><b>Re-measured on Muse, $0.023</b> — pool-economics' launch cost at the DEAREST
     * TENTH (n = 32 launches, median $0.008), because the tool's own caution applies: a reboot after
     * a reap is always cold, and the median mixes in the pool's warm JIT second worker. The history
     * below is the Claude figure this replaced.
     *
     * <p>This was {@code WARM_REBOOT_DOLLARS = 0.21}, a figure from the stub harness (an earlier issue's
     * fork-arm, a shorter prompt and no real tool list). an earlier issue's REAL-game boot turns — read 24,366
     * / write 19,653, then read 44,019 / write 29,998, then read 74,017 / write 5,976 — priced at
     * Opus 5 list with today's 5-minute TTL (writes 1.25x) come to $0.43 with the prompt head still
     * cached and <b>$0.57 without it</b>. A reboot after a reap is always the second kind: the
     * head lives five minutes and every reap here waits longer than that, so nothing is left to
     * read. Decided 2026-09-24: use the measured figure rather than wait for a week of
     * records; a pool-economics report re-derives it from the real spend records and
     * prints it beside this constant, so a drift is one command away.
     */
    public static final double REBOOT_DOLLARS = 0.023;

    /**
     * How long the world must be empty before the pool is stopped.
     *
     * <p><b>Derived</b>, not chosen: the point at which an absence has cost more than the reboot
     * that ends it. ~15.9 minutes at the numbers above (Muse; it was ~25.7 on Claude's, and
     * ~9.5 on the stub-harness reboot). It only decides an AWAKE server's pool now: an
     * idle pool never holds a server up — the sleep sequence reaps it ({@code /moddyop agent reap}).
     */
    public static final long GRACE_MS =
            Math.round(REBOOT_DOLLARS / IDLE_DOLLARS_PER_HOUR * 3_600_000.0);

    /**
     * How long nobody may touch Moddy, <b>while children ARE online</b>, before the pool is stopped
     * (step 4, 2026-09-24: 20 minutes).
     *
     * <p><b>Chosen, not derived</b>, and that difference from {@link #GRACE_MS} is deliberate. An
     * empty world costs nobody anything to reap, so its grace is pure arithmetic. Reaping while a
     * child plays costs that child a ~10–13 s wake on his next wish, which is a UX price no dollar
     * figure settles — so it was picked near the break-even, and it is a knob
     * ({@code pool.onlineIdleReapSeconds}).
     *
     * <p>What makes the price small is that the pool is woken by the first sign a child is ABOUT
     * to wish — right-clicking the watch, which opens the panel he then reads or types into — so
     * the boot mostly happens while he is choosing. {@link #wakeSaysSo} covers the rest.
     */
    public static final long ONLINE_IDLE_MS = 20 * 60_000L;

    /**
     * Should the pool be stopped right now?
     *
     * <p>Three conditions, and every one of them is a way this could take a granter away from
     * somebody:
     *
     * <ol>
     *   <li><b>Nobody online.</b> The only signal that costs a child nothing.</li>
     *   <li><b>The absence has outlasted {@link #GRACE_MS}</b>, so a relog does not pay for a
     *       boot.</li>
     *   <li><b>No worker is holding a wish.</b> A player can disconnect while a wish is being
     *       granted — a crash, a closed laptop — and the artifact is still worth finishing and
     *       persisting for when they come back. Reaping mid-grant would throw away work that is
     *       already paid for, to save the cheaper thing.</li>
     * </ol>
     *
     * @param playersOnline    how many players the server has right now
     * @param msSinceLastSeen  how long since the last one left; ignored while anyone is online
     * @param workersHoldingAWish {@code Marshal.working().size()}
     */
    public static boolean shouldReap(int playersOnline, long msSinceLastSeen,
                                     int workersHoldingAWish) {
        // `!= 0`, not `> 0`. The two failure modes are not symmetric — an unreaped pool costs
        // money, a wrongly reaped one costs a child their wish — so a nonsense count (a negative
        // from a caller that has not been written yet) must land on the money side.
        if (playersOnline != 0) {
            return false;
        }
        if (workersHoldingAWish != 0) {
            return false;
        }
        return msSinceLastSeen >= graceMs();
    }

    /**
     * {@link #GRACE_MS}, unless an operator has moved the {@code pool.idleReapSeconds} knob.
     * The derivation above stays the default; the knob is for a server whose evenings do not
     * match the measured numbers.
     */
    public static long graceMs() {
        return com.moddingfromamod.config.ModdySettings.get("pool.idleReapSeconds")
                .map(v -> Long.parseLong(v) * 1000L).orElse(bootSeconds(IDLE_REAP_PROPERTY, GRACE_MS));
    }

    /** The boot default of {@code pool.idleReapSeconds} (a launcher's MODDY_POOL_IDLE_REAP_S → -D). */
    public static final String IDLE_REAP_PROPERTY = "moddingfromamod.pool.idleReapSeconds";
    /** The boot default of {@code pool.onlineIdleReapSeconds} (MODDY_POOL_ONLINE_REAP_S → -D). */
    public static final String ONLINE_REAP_PROPERTY = "moddingfromamod.pool.onlineIdleReapSeconds";

    /**
     * A -D given in SECONDS, or {@code fallbackMs}. The layer order is Knobs': the runtime knob wins,
     * then this boot default (the world manifest's value, through the launcher), then the code's.
     * A value that is not a positive number is ignored rather than trusted — a typo must not reap
     * a pool every tick.
     */
    static long bootSeconds(String property, long fallbackMs) {
        String v = System.getProperty(property);
        try {
            long s = v == null ? -1L : Long.parseLong(v.trim());
            return s > 0 ? s * 1000L : fallbackMs;
        } catch (NumberFormatException e) {
            return fallbackMs;
        }
    }

    /**
     * Should the pool be stopped although children ARE online, because nobody has touched Moddy
     * for {@link #onlineIdleMs()}? (step 4.)
     *
     * <p>"Touched" is the pool's activity clock: a join, a watch right-click, a wish submitted, and
     * any moment a worker holds a wish all reset it (see {@code AgentSession.touch}). So this is
     * "twenty quiet minutes", never "twenty minutes since the last wish STARTED" — a long grant is
     * activity for as long as it runs.
     *
     * <p>{@code playersOnline > 0}, not {@code != 0}: the unknown answer (-1) must reap on neither
     * rule, and the empty world is {@link #shouldReap}'s case.
     */
    public static boolean shouldReapWhileOnline(int playersOnline, long msSinceActivity,
                                                int workersHoldingAWish) {
        if (playersOnline <= 0 || workersHoldingAWish != 0) {
            return false;
        }
        return msSinceActivity >= onlineIdleMs();
    }

    /** {@link #ONLINE_IDLE_MS}, unless the {@code pool.onlineIdleReapSeconds} knob is set. */
    public static long onlineIdleMs() {
        return com.moddingfromamod.config.ModdySettings.get("pool.onlineIdleReapSeconds")
                .map(v -> Long.parseLong(v) * 1000L).orElse(bootSeconds(ONLINE_REAP_PROPERTY, ONLINE_IDLE_MS));
    }

    /**
     * Should the pool be started because somebody is here?
     *
     * <p>Booting on <b>join</b> rather than on the first wish is what keeps reaping free at the
     * child's end: the ~10–13 s launch happens behind the loading screen, and by the time they have
     * found the watch a worker is parked exactly as before. A join is activity, so it resets the
     * clock this reads.
     *
     * <p>⚠ <b>It must go false once the pool has been reaped for idleness while children play</b>,
     * or it undoes the reap. {@code PoolPlan.desiredWorkers} publishes the level the worker
     * container converges on (HO-6), and a {@code shouldBoot} that answered "somebody is online and
     * nothing runs" would re-launch the worker {@link #shouldReapWhileOnline} just stopped, on the
     * next scrape, forever. So it asks whether that somebody has touched Moddy lately — which is
     * also exactly how a watch right-click wakes a containerised pool: it resets the clock, this
     * goes true, and the runner boots one.
     *
     * <p>Idempotent at the caller — {@code AgentSession.start} returns immediately if the pool is
     * already up — so this only has to answer the question, not track state.
     */
    public static boolean shouldBoot(int playersOnline, boolean poolRunning, long msSinceActivity) {
        return playersOnline > 0 && !poolRunning && msSinceActivity < onlineIdleMs();
    }

    /**
     * Should the pool be started as the world OPENS, before anybody has joined? (.)
     *
     * <p><b>Not on a dedicated server.</b> A dedicated server always opens empty, and a pool booted
     * there is a worker nobody asked for: a cold boot ({@link #REBOOT_DOLLARS}) plus polls until
     * {@link #shouldReap} lets it go {@link #GRACE_MS} later. On a server that suspends when idle it was worse
     * than the dollars — such a server never sleeps holding a model session, so every restart (a
     * release, a queued restart) held the machine awake for the whole grace. Measured on a live server,
     * 2026-09-29: two release wakes stayed up 33.8 and 39.1 minutes where the work rule says ~3,
     * every minute of the tail reading "the wish pool still has 1 worker(s)".
     *
     * <p>Nothing a child sees changes: the first join boots the pool behind the loading screen,
     * exactly as it does after every reap, and any wish offered with no pool wakes it
     * ({@code WishQueue.offer}), so a headless or probe wish is heard too.
     *
     * <p><b>Singleplayer keeps the boot at open</b>: its one player is already on the way in, and
     * paying the ~40 s launch while the world loads is the promise the pool was built on.
     */
    public static boolean bootAtWorldOpen(boolean dedicatedServer) {
        return !dedicatedServer;
    }

    /**
     * Should a child whose wish was just submitted be told Moddy is waking up?
     *
     * <p>Only when the wish will really wait for a boot: the pool is not running, or it launched
     * within {@link PoolPlan#BOOT_GRACE_MS} and no worker has started listening yet. A parked pool,
     * or one whose worker is between two polls, answers in milliseconds and says nothing extra —
     * a line on every wish would be noise, and one on a wish that did not wait would be untrue.
     */
    public static boolean wakeSaysSo(boolean poolRunning, long msSinceLaunch, int workersListening) {
        if (!poolRunning) {
            return true;
        }
        return workersListening == 0 && msSinceLaunch >= 0 && msSinceLaunch < PoolPlan.BOOT_GRACE_MS;
    }
}
