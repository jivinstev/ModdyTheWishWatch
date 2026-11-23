package com.moddingfromamod.marshal;

/**
 * <b>How many wish workers should exist right now.</b>
 *
 * <p>Pure and Minecraft-free, so the rule that decides how much money the pool spends can be
 * unit-tested rather than discovered in a play session.
 *
 * <h2>The rule: always keep ONE worker free, and never more than the cap</h2>
 *
 * <p>The pool used to start {@link WishSlots#slots()} workers at world open and keep them all
 * parked for the session. Measured, that is the most expensive thing the agent does — not because
 * booting is dear ($0.21, once) but because a parked worker is <b>not free</b>: {@code await_wish}
 * returns on a timeout and the agent loops, and every loop is a full turn that re-reads the whole
 * context. At the old 55 s timeout that is ~65 turns an hour at ~$0.033 each, so three parked
 * workers cost about <b>$6.40 an hour doing nothing</b>.
 *
 * <p>So: start one, and add another only when the last free one takes a wish. A child who wishes
 * once at a time is never charged for the second and third worker at all; a child who wishes
 * twice at once waits ~10 s for the second worker, which is the measured launch-to-listening time.
 *
 * <h2>Do not add a stagger — the herd was measured and is not the cause</h2>
 *
 * <p>Three cold cache writes in a log look like workers racing each other, and the pool used to
 * carry a 12 s stagger written for exactly that. It was measured and it never produced sharing:
 * the workers already shared a 24,366-token head, and each still wrote its own tail whatever the
 * spacing. Starting one at a time here is a consequence of the rule below, not a fix for a herd —
 * the herd was not real. Full numbers.
 *
 * <h2>It counts BUSY workers, not parked ones — and that distinction is a measured bug</h2>
 *
 * <p>The obvious signal is {@code WishQueue.waitingWorkers()}: how many are actually sitting in
 * {@code await_wish}. It is wrong here. A parked worker leaves that set every time its call returns
 * and only rejoins it a turn later, so for a second or two the pool has no "parked" worker while
 * being perfectly healthy. A supervisor sampling in that gap concludes it has no spare and starts
 * one. Observed doing exactly that in a real game: a second worker appeared at 05:30 for a pool
 * whose only worker was fine.
 *
 * <p>So the question asked is "is anyone FREE", answered as {@code running - busy}, where busy is
 * the marshal's own count of workers holding a wish. A worker between polls is not busy, and a
 * worker still booting is not busy either — both are spares, one just has not arrived yet. No
 * timing window, no grace period, nothing to tune.
 *
 * <p>The cost is that a worker wedged without ever listening still counts as a spare, so the
 * pool will not replace it. That is today's behaviour and it belongs to that issue; inventing a
 * liveness rule here would mean guessing at a timeout, which is what the parked-based version did
 * wrong.
 *
 */
public final class PoolPlan {

    private PoolPlan() {
    }

    /** One free worker is enough: the second wish's worker is started the moment the first is taken. */
    public static final int MIN_SPARE = 1;

    /**
     * How long after a launch to start believing a worker should have been heard from.
     *
     * <p>Launch to parked measured 4.4–8.1 s in the stub harness and ~9–13 s in a real game. 30 s
     * clears the slow end with room.
     */
    public static final long BOOT_GRACE_MS = 30_000L;

    /**
     * Consecutive supervisor ticks a worker must be unaccounted for before it is presumed deaf.
     *
     * <p>Not one: a healthy worker is neither busy nor parked for the second or two between its
     * {@code await_wish} returning and the next one being made, so a single sample catches ordinary
     * workers mid-stride. Three ticks is ~9 s, comfortably longer than that gap and far shorter
     * than a child's patience.
     */
    public static final int DEAF_TICKS = 3;

    /**
     * How long ONE NAMED worker must be continuously unaccounted for before it is STOPPED and
     * replaced — after {@link #BOOT_GRACE_MS}, so a worker that never listens goes at ~3.5
     * minutes.
     *
     * <p>Deliberately much longer than {@link #DEAF_TICKS}, because the two answer different
     * questions at different prices. {@code DEAF_TICKS} only stops COUNTING a worker as a listener,
     * and being wrong costs one spare boot. This one ends a session, and being wrong about a worker
     * that was merely thinking between two {@code await_wish} calls throws away a warm worker — a
     * ~$0.21 boot, and a worker the next child would have had instantly. The two failure costs are
     * not symmetric the other way either: while the stop waits, {@code DEAF_TICKS} has already had
     * the pool start a replacement listener if there was room, so a child is not the one waiting.
     *
     * <p>180 s is over twenty times the slowest measured launch-to-parked (8.1 s) and several times
     * a slow model turn on a warm cache; nothing healthy is silent that long between listens.
     */
    public static final long DEAF_STOP_MS = 180_000L;

    /**
     * <b>Deaf from birth</b>: a worker that was sent its tool list this long ago and has never
     * called a tool is stopped and replaced at once, without waiting out {@link #BOOT_GRACE_MS} and
     * {@link #DEAF_STOP_MS}. A healthy worker's first action IS a tool call ({@code await_wish}),
     * measured 1.5 s after its tool list on a fresh machine; the CLI can build that first turn before
     * the tool list lands (the race measured: 23 ms late), and such a worker ends its only turn
     * with no tools and never speaks again. 15 s is ten times the measured gap; being wrong costs one
     * worker boot, being slow costs the child's first wish.
     */
    public static final long LISTED_SILENT_MS = 15_000L;

    /** {@link #DEAF_STOP_MS}, unless the {@code pool.deafStopSeconds} knob is set. */
    public static long deafStopMs() {
        return com.moddingfromamod.config.ModdySettings.longOr("pool.deafStopSeconds", DEAF_STOP_MS / 1000) * 1000L;
    }

    /**
     * <b>How long an EXTRA worker may sit parked before it is stopped</b>
     * (2026-10-08): 2 minutes.
     *
     * <p>"Extra" is every listening worker beyond the one {@link #MIN_SPARE}. The pool grows one at
     * a time when a wish takes the last free worker, and before this it never shrank again until
     * the WHOLE pool was reaped ({@link IdlePolicy#ONLINE_IDLE_MS}, 20 quiet minutes) — so one
     * burst of three wishes left three or four workers parked, each polling {@code await_wish}
     * every ~110 s. Measured on a live server (24 Sep–3 Oct, Muse Spark 1.2): <b>98 worker-hours of
     * standby cost nearly four times what the 90 wishes did</b> — 79% of the spend was standby, with
     * up to four workers alive.
     *
     * <p><b>Chosen, not derived — and the break-even is the wrong frame here, said out loud.</b>
     * {@link IdlePolicy#GRACE_MS}'s formula (a cold boot / an idle hour, ~16 min on Muse) prices
     * the reboot a reap makes CERTAIN: the next child to join needs a worker. Stopping an extra
     * makes no reboot certain. The one spare is still parked, so the next wish is answered in
     * milliseconds and only a SECOND simultaneous wish starts a worker — the JIT launch the pool
     * already makes, overlapping the first wish's grant, which nobody waits on. The real
     * break-even is therefore boot cost ÷ (idle cost × the chance the extra is needed again before
     * the reap), and no record yet says how often a burst comes back; the knob
     * (2 min) was chosen, for these reasons. What 2 minutes does buy, beyond "aggressive":
     *
     * <ul>
     *   <li>It is longer than one {@code await_wish} cycle (~110 s), so a worker that just
     *       finished a wish gets one full poll's window to catch the child's follow-up — "now give
     *       him wings" — which {@link ArtifactAffinity} prefers to route to the worker that built
     *       the thing, with its context warm.</li>
     *   <li>Its worst case is bounded and small: an extra stopped one poll too early costs one
     *       boot (~$0.02 cold on Muse, {@link IdlePolicy#REBOOT_DOLLARS}) that nobody waits for;
     *       one kept for the old 20 minutes costs ~11 polls (~$0.03) per extra, every burst.</li>
     * </ul>
     *
     * <p>Knob {@code pool.extraIdleStopSeconds}. Re-derive it once {@code tools/pool-economics.py}
     * can say how often a stopped extra was relaunched within N minutes.
     */
    public static final long EXTRA_IDLE_STOP_MS = 120_000L;

    /** {@link #EXTRA_IDLE_STOP_MS}, unless the {@code pool.extraIdleStopSeconds} knob is set. */
    public static long extraIdleStopMs() {
        return com.moddingfromamod.config.ModdySettings.longOr("pool.extraIdleStopSeconds",
                EXTRA_IDLE_STOP_MS / 1000) * 1000L;
    }

    /**
     * One worker as the shrink rule sees it.
     *
     * @param name        its slot name ({@code wish-granter-2})
     * @param bootedAtMs  when it was launched
     * @param idleSinceMs when it last stopped holding a wish, or its boot if it never held one
     * @param parked      sitting in {@code await_wish} right now — the only state it may be stopped in
     * @param busy        holding a wish (working, or winding one down)
     * @param stoppable   the pool can name it to {@code claude stop} and to the queue (it has both a
     *                    CLI session id and an MCP session id); a worker that cannot be stopped by id
     *                    must not be retired from the queue either, or it would listen and never hear
     */
    public record Worker(String name, long bootedAtMs, long idleSinceMs, boolean parked,
                         boolean busy, boolean stoppable) {
    }

    /**
     * <b>Which worker, if any, to stop because the pool has more free workers than it needs</b>.
     * At most ONE per call — the supervisor calls this once per tick — so the pool cannot
     * thrash, and a burst drains one worker every ~3 s at the earliest.
     *
     * <p>Every rule here is a way stopping could cost a child something, so each is a refusal:
     * <ol>
     *   <li><b>Never a worker holding a wish</b> (busy), and never one that is not PARKED. A worker
     *       between two {@code await_wish} calls may be in the seconds after taking a wish and
     *       before the marshal records it (the safety grade sits there), so "not parked" is never
     *       "safe to stop" — it just waits for the next tick.</li>
     *   <li><b>Never the last free worker</b>: after the stop at least {@link #MIN_SPARE} must still
     *       be parked. Counted from PARKED rather than {@code running - busy}, so a deaf or booting
     *       worker is never what the spare is assumed to be — and so {@link #spawnsNeeded} cannot
     *       answer the stop with a launch on the next tick (its free count is at least this one).</li>
     *   <li><b>Never inside {@link #BOOT_GRACE_MS}</b> of its launch.</li>
     *   <li><b>Only after {@code graceMs} idle</b> ({@link #extraIdleStopMs()}).</li>
     * </ol>
     *
     * <p>Among the eligible, the one idle LONGEST; a tie goes to the highest slot name, so the pool
     * drains from the top and {@code wish-granter-1} is the one that stays.
     *
     * @return the slot name to stop, or {@code null} for none
     */
    public static String shrinkCandidate(java.util.List<Worker> workers, long nowMs, long graceMs) {
        if (workers == null || workers.isEmpty() || graceMs <= 0) {
            return null;
        }
        int listening = 0;
        for (Worker w : workers) {
            if (w != null && w.parked() && !w.busy()) {
                listening++;
            }
        }
        if (listening - 1 < MIN_SPARE) {
            return null;
        }
        Worker best = null;
        for (Worker w : workers) {
            if (w == null || w.name() == null || !w.parked() || w.busy() || !w.stoppable()) {
                continue;
            }
            if (nowMs - w.bootedAtMs() < BOOT_GRACE_MS) {
                continue;
            }
            long idleSince = Math.max(w.idleSinceMs(), w.bootedAtMs());
            if (nowMs - idleSince < graceMs) {
                continue;
            }
            if (best == null) {
                best = w;
                continue;
            }
            long bestSince = Math.max(best.idleSinceMs(), best.bootedAtMs());
            if (idleSince < bestSince
                    || (idleSince == bestSince && slotOrder(w.name(), best.name()) > 0)) {
                best = w;
            }
        }
        return best == null ? null : best.name();
    }

    /** Slot names compared by their number, so {@code -10} sorts above {@code -9}. */
    private static int slotOrder(String a, String b) {
        int na = trailingNumber(a);
        int nb = trailingNumber(b);
        return na != nb ? Integer.compare(na, nb) : a.compareTo(b);
    }

    private static int trailingNumber(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) {
            i--;
        }
        if (i == name.length() || name.length() - i > 9) {
            return 0;
        }
        return Integer.parseInt(name.substring(i));
    }

    /**
     * Workers that are running but neither holding a wish nor listening for one.
     *
     * <p>This is seen from the pool's side: a worker can finish booting, announce that it is
     * ready, and never call {@code await_wish}. Measured at 5 of 9 workers, and again while testing
     * this class — the JIT-spawned worker 2 produced two text-only turns and then nothing.
     *
     * <p>It matters far more now than it used to. A pool of three parked workers hid it: some
     * subset listened and a child was heard anyway. A pool that keeps ONE spare has no redundancy
     * to hide behind, so a deaf spare means nobody is listening at all. Discounting them here is
     * what stops this class's saving being paid for out of a child's wish going unheard.
     */
    public static int deafCount(int running, int busy, int parked) {
        return Math.max(0, Math.max(0, running) - Math.max(0, busy) - Math.max(0, parked));
    }

    /**
     * How many workers to start right now.
     *
     * <p>⚠ {@code live} and {@code running} are deliberately separate, and conflating them is a bug
     * a test caught here: a deaf worker must be discounted when asking "is anyone free", but must
     * still COUNT against the cap — or a pool whose workers all go deaf launches past its limit
     * forever instead of stopping at three and saying so in the log.
     *
     * @param live    workers believed able to listen — {@code running} minus any presumed deaf
     * @param busy    how many are holding a wish ({@code Marshal.working()})
     * @param running how many worker processes the pool has launched and not stopped
     * @param cap     {@link WishSlots#slots()}, the most that may ever run at once
     */
    public static int spawnsNeeded(int live, int busy, int running, int cap) {
        // Clamped at zero BEFORE the subtraction below, so a nonsense busy count cannot make
        // `free` deeply negative and turn one missing spare into a burst of launches. The
        // supervisor re-runs every few seconds, so starting one at a time always catches up —
        // and one at a time is also what keeps the pool from writing the cached prompt twice.
        int free = Math.max(0, Math.max(0, live) - Math.max(0, busy));
        int room = Math.max(0, cap - Math.max(0, running));
        return free >= MIN_SPARE ? 0 : Math.max(0, Math.min(room, MIN_SPARE - free));
    }

    /**
     * How many workers the pool WANTS alive right now — the one number a runner converges on.
     *
     * <p>HO-6. Today the pool both decides and acts, because a worker is a subprocess of this JVM.
     * Split across a container boundary those separate: the server keeps deciding (this class does
     * not move) and something in the worker container has to be told the answer. This is the
     * answer, expressed as a level rather than as a delta, which is what makes it safe to publish:
     * a runner that reads it twice, or reads a stale copy, converges on the same place — where a
     * "spawn one more" instruction read twice launches two.
     *
     * <p>⚠ <b>Reaping is the same number, not a second channel.</b> `desired &lt; running` IS the
     * instruction to stop workers, so there is no path where a runner can start them and never be
     * told to stop — which is the failure this chunk's risk section prices at ~$6.40/h for three
     * parked workers doing nothing.
     *
     * @param running    worker processes launched and not stopped
     * @param live       of those, the ones believed able to listen ({@code running} minus deaf)
     * @param busy       how many hold a wish
     * @param cap        {@link WishSlots#slots()}
     * @param shouldReap {@link IdlePolicy#shouldReap} — nobody is playing and the grace has passed
     * @param shouldBoot {@link IdlePolicy#shouldBoot} — somebody is here and nothing is running
     */
    public static int desiredWorkers(int running, int live, int busy, int cap,
                                     boolean shouldReap, boolean shouldBoot) {
        if (shouldReap) {
            return 0;
        }
        int want = Math.max(0, running) + spawnsNeeded(live, busy, running, cap);
        if (shouldBoot) {
            want = Math.max(want, 1);
        }
        // Clamped to the cap LAST. `shouldBoot` raising the floor to 1 must not beat a cap of 0,
        // which is how an operator turns the pool off.
        return Math.min(want, Math.max(0, cap));
    }
}
