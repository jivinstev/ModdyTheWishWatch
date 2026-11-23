package com.moddingfromamod.wish;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Does this wish need a camera, and how much of one?</b> Minecraft-free.
 *
 * <p>Before this, every wish with a progress bar woke a camera ({@link WishProgress#inFlight}), so an
 * item wish paid a VM resume and a linger to take one first-person shot, and the pool was sized by
 * how many wishes had already queued behind a busy body. A camera's real load is the CAMERA TIME of
 * the wishes in flight — a building orbit is eight poses, an item none — so this keeps, per wish,
 * a small state machine, and answers in camera-seconds.
 *
 * <h2>The state machine (one per wish)</h2>
 *
 * <pre>
 *   open ─▶ PENDING ──declared(camera class)──▶ NEEDS ◀──made(camera class) / photograph──┐
 *              │                                  ▲                                          │
 *              └──declared(item, other)──▶ NONE ──┘ (a NONE wish that makes a creature, or   │
 *                                                    asks for a photograph, needs one after all)
 *   close (settled, cancelled, failed) ─▶ gone, from any state
 * </pre>
 *
 * <ul>
 *   <li><b>PENDING has no clock</b> (owner: "if a wish is long, &gt;60 s to declare its type, we
 *       must not prematurely suspend"). A wish whose kind is not known yet holds its camera for as
 *       long as it is in flight, priced at {@link #PENDING_PRIOR_S} — the class that is commonest
 *       and dearest — so a slow plan is never the reason a camera went to sleep.</li>
 *   <li><b>Only upward moves are automatic.</b> NONE → NEEDS on any sign of a subject; NEEDS never
 *       falls back to NONE, because a camera released under a wish that photographs anyway costs
 *       a whole resume, and one kept a minute too long costs a fraction of a cent.</li>
 *   <li><b>A wedge has a cap</b> ({@link #STALE_MS}): a wish in flight longer than a wish ever
 *       runs stops holding a camera, or one stuck wish would keep a VM awake all night.</li>
 *   <li><b>The source of truth for "in flight" is the caller's</b> ({@link #demand}'s set — the
 *       wish bars), so an entry whose wish ended by a path that never called {@link #close} is
 *       pruned rather than leaked, and a wish in flight that nobody opened counts as PENDING —
 *       exactly the behaviour before this class.</li>
 * </ul>
 *
 * <h2>Sizing</h2>
 *
 * <p>{@link #bodiesFor}: ⌈camera-seconds in flight ÷ {@link #PER_BODY_S}⌉. The divisor is the one
 * measured point: one camera kept up with three concurrent CREATURE wishes
 * and lost orbits at six, so a body carries about 3 × {@link #CREATURE_S} seconds of in-flight
 * camera work. Shorter shots make every class cheaper and the same divisor then fits more
 * wishes on a body, which is the point.
 */
public final class CameraNeed {

    /**
     * Per-class camera seconds a wish costs 's spin: a hero still (~3 s a pose on a camera
     * VM, CAMERA_CLIENT §11a) plus one filmed turn (20 frames at the render rate, measured 3.4–4.4 s
     * with its settle on the llvmpipe client). Before it they were 14 / 24 / 9 / 3.
     */
    public static final double CREATURE_S = 8;    // hero + a turn            (was four stills + a clip)
    public static final double BUILDING_S = 10;   // hero + one room + a turn (was eight stills)
    public static final double PICTURE_S = 3;     // the front                (was front, left, right)
    public static final double EVENT_S = 10;      // a 6 s held clip, filmed as it starts (was one plain shot)
    /** A wish that declared no camera class and asked for a photograph anyway: one plain shot. */
    public static final double PHOTO_S = 3;
    /** A wish whose kind is not known yet: priced as a creature, the commonest camera class. */
    public static final double PENDING_PRIOR_S = CREATURE_S;
    /**
     * One body's share of camera-seconds in flight: three concurrent creature wishes at the OLD
     * creature cost of 14 s, the one measured point (CAPACITY §4.2) — a fact about how much camera
     * work a body keeps up with, so it stays put while the per-wish costs fall.
     */
    public static final double PER_BODY_S = 42;
    /** Longer than any wish runs (the agent's own budget is minutes): a wedge stops holding a VM. */
    public static final long STALE_MS = 15 * 60_000L;

    /** Where a wish stands. */
    public enum Phase { PENDING, NEEDS, NONE }

    /** One wish's need: its phase, its camera class ("" while pending), and when it was opened. */
    public record Need(Phase phase, String cls, long openedMs) { }

    /**
     * What the supervisor reads: how many wishes hold a camera (every one in flight that is not stale,
     *), and how much camera work they price — which only NEEDS and PENDING wishes add to.
     */
    public record Demand(int wanting, double seconds, int pending, int needing, int none, int stale) {
        public static final Demand NONE = new Demand(0, 0, 0, 0, 0, 0);
    }

    private static final CameraNeed SHARED = new CameraNeed();

    /** The server's one register. Tests make their own ({@code new CameraNeed()}). */
    public static CameraNeed shared() {
        return SHARED;
    }

    private final Map<UUID, Need> needs = new ConcurrentHashMap<>();
    /**
     * Wishes that RE-MADE something already kept, unchanged (skip the camera: no camera time
     * spent and no wait for one), and what they re-made ({@code kind:id}).
     */
    private final Map<UUID, Set<String>> reused = new ConcurrentHashMap<>();
    /** Wishes that made something NEW: a reuse never releases their camera. */
    private final Set<UUID> authored = ConcurrentHashMap.newKeySet();

    /** A wish was submitted: it wants a camera until its kind says otherwise. Idempotent. */
    public void open(UUID wish, long nowMs) {
        if (wish != null) {
            needs.putIfAbsent(wish, new Need(Phase.PENDING, "", nowMs));
        }
    }

    /** {@code plan_wish}'s kind ({@link WishAim.Kind}'s words). Settles a PENDING wish; upgrades a NONE. */
    public void declared(UUID wish, String planKind, long nowMs) {
        if (reuseOnly(wish)) {
            return;          // it is putting back something kept: no plan makes that need a camera
        }
        move(wish, classOfPlan(planKind), nowMs);
    }

    /** A making tool recorded what it made ({@code MadeKind}). Upgrades only — never releases a camera. */
    public void made(UUID wish, String madeKind, long nowMs) {
        made(wish, madeKind, null, nowMs);
    }

    /**
     * The same, naming WHAT was made: a thing this wish already said it re-made unchanged
     * ({@link #reused}) is not new, so it neither marks the wish as authoring nor wants a camera.
     */
    public void made(UUID wish, String madeKind, String id, long nowMs) {
        if (wish == null) {
            return;
        }
        Set<String> again = reused.get(wish);
        if (id != null && again != null && again.contains(key(madeKind, id))) {
            return;
        }
        String cls = classOfMade(madeKind);
        if (cls != null && !cls.isEmpty()) {
            authored.add(wish);
            move(wish, cls, nowMs);
        }
    }

    /**
     * <b>The wish re-made something already kept, unchanged</b> — a replay command, a kept event
     * staged again as it was, a saved machine rebuilt. Nothing new exists to be seen, so unless it
     * also made something new its camera is released at once and its photograph is skipped
     * ({@link #reuseOnly}): the pictures from when the thing was first made stand. {@code kind}
     * and {@code id} name it as {@code WishRun.made} will ({@code MadeKind}); either may be null for
     * a reuse that records nothing (a replay command through {@code run_command}).
     */
    public void reused(UUID wish, String kind, String id, long nowMs) {
        if (wish == null) {
            return;
        }
        reused.computeIfAbsent(wish, k -> ConcurrentHashMap.newKeySet())
                .add(kind == null || id == null ? "" : key(kind, id));
        if (!authored.contains(wish)) {
            needs.compute(wish, (k, n) -> new Need(Phase.NONE, "reuse", n == null ? nowMs : n.openedMs()));
        }
    }

    /** Did this wish only put back things already kept? Then it takes no camera and no photograph. */
    public boolean reuseOnly(UUID wish) {
        return wish != null && reused.containsKey(wish) && !authored.contains(wish);
    }

    private static String key(String kind, String id) {
        return kind.strip().toLowerCase(Locale.ROOT) + ":" + id.strip();
    }

    /** A photograph was asked for: whatever was declared, this wish needs a camera now. */
    public void photographing(UUID wish, long nowMs) {
        if (wish == null || reuseOnly(wish)) {
            return;
        }
        needs.compute(wish, (k, n) -> {
            long opened = n == null ? nowMs : n.openedMs();
            if (n != null && n.phase() == Phase.NEEDS) {
                return n;
            }
            String cls = n != null && !n.cls().isEmpty() && costOf(n.cls()) > 0 ? n.cls() : "photo";
            return new Need(Phase.NEEDS, cls, opened);
        });
    }

    /** The wish ended — granted, failed or cancelled. */
    public void close(UUID wish) {
        if (wish != null) {
            needs.remove(wish);
            reused.remove(wish);
            authored.remove(wish);
        }
    }

    /** This wish's phase, or null when nothing was recorded for it (it then counts as PENDING). */
    public Phase phase(UUID wish) {
        Need n = wish == null ? null : needs.get(wish);
        return n == null ? null : n.phase();
    }

    /** Its camera class ("" while pending, null when unknown). */
    public String cls(UUID wish) {
        Need n = wish == null ? null : needs.get(wish);
        return n == null ? null : n.cls();
    }

    /**
     * Is a camera worth taking for this wish's photograph? False only for a declared NONE. (The
     * photograph side; whether a camera stays AWAKE is {@link #demand}, where every wish
     * in flight holds one.)
     */
    public boolean wantsCamera(UUID wish) {
        return phase(wish) != Phase.NONE;
    }

    private void move(UUID wish, String cls, long nowMs) {
        if (wish == null || cls == null) {
            return;
        }
        boolean camera = costOf(cls) > 0;
        needs.compute(wish, (k, n) -> {
            long opened = n == null ? nowMs : n.openedMs();
            if (n == null || n.phase() == Phase.PENDING) {
                return new Need(camera ? Phase.NEEDS : Phase.NONE, cls, opened);
            }
            if (n.phase() == Phase.NONE) {
                return camera ? new Need(Phase.NEEDS, cls, opened) : n;
            }
            // NEEDS: never released; the dearer class wins (a building that also hangs a picture)
            return camera && costOf(cls) > costOf(n.cls()) ? new Need(Phase.NEEDS, cls, opened) : n;
        });
    }

    /**
     * <b>Which wishes are in flight, for the camera</b>: every wish that has a progress bar, and every
     * wish {@code Marshal} still holds live, less those already over for the child (their bar came
     * down at "All done" — the same rule as before, so a finished wish's grading holds no camera). Pure.
     *
     * <p>⚠ The bar alone is not enough, and the camera used to read nothing else. A bar belongs to
     * whoever is WATCHING, and {@code WishProgress}'s ticker takes down the bars of a wisher who is
     * not in the player list — the canary's FakePlayer stand-in always, and a child who logged off
     * mid-grant. Measured on a live server, 2026-10-29: two canaries asked for a camera 18 s and 13.6 s
     * after submit (not ~0.5 s, as every child's wish did), because the ticker cleared the bar within
     * 2 s and only the agent's {@code conjure_at} opened it again; the photograph then waited ~7 s for
     * a camera that had been asked for late. The wish is still in flight whoever is watching.
     */
    public static Set<UUID> inFlight(Collection<UUID> withBars, Collection<UUID> live,
                                     java.util.function.Predicate<UUID> endedForChild) {
        Set<UUID> out = new HashSet<>();
        if (withBars != null) {
            out.addAll(withBars);
        }
        if (live != null) {
            for (UUID id : live) {
                if (id != null && (endedForChild == null || !endedForChild.test(id))) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /**
     * The demand from the wishes in flight. Entries for wishes no longer in flight are pruned; a wish
     * in flight with no entry is PENDING (unknown, so it keeps its camera).
     */
    public Demand demand(Collection<UUID> inFlight, long nowMs) {
        Set<UUID> live = inFlight == null ? Set.of() : new HashSet<>(inFlight);
        needs.keySet().removeIf(id -> !live.contains(id));
        reused.keySet().removeIf(id -> !live.contains(id));
        authored.removeIf(id -> !live.contains(id));
        int wanting = 0;
        int pending = 0;
        int needing = 0;
        int none = 0;
        int stale = 0;
        double seconds = 0;
        for (UUID id : live) {
            Need n = needs.get(id);
            if (n != null && nowMs - n.openedMs() > STALE_MS) {
                stale++;
                continue;
            }
            // EVERY wish in flight holds a camera; it goes 60 s after the
            // last one ends. A declared NONE (an item, an "other", a put-back) used to let it go the
            // moment it was declared — and then photographed at the reveal anyway, waiting for a
            // resume: the tower's p95 of 10 s was one teleport wish doing exactly that. It still
            // prices NO camera-seconds, so it never asks for a second body; it only keeps one awake.
            wanting++;
            if (n != null && n.phase() == Phase.NONE) {
                none++;
            } else if (n == null || n.phase() == Phase.PENDING) {
                pending++;
                seconds += PENDING_PRIOR_S;
            } else {
                needing++;
                seconds += costOf(n.cls());
            }
        }
        return new Demand(wanting, seconds, pending, needing, none, stale);
    }

    /** Bodies this demand wants: 0 with nobody wanting, else ⌈seconds ÷ a body's share⌉ in [1, max]. */
    public static int bodiesFor(Demand d, int max) {
        if (d == null || d.wanting() <= 0) {
            return 0;
        }
        int cap = Math.max(1, max);
        int n = (int) Math.ceil(d.seconds() / PER_BODY_S - 1e-9);
        return Math.max(1, Math.min(cap, n));
    }

    /** Camera seconds for a class; 0 for one that needs no camera. */
    public static double costOf(String cls) {
        if (cls == null) {
            return 0;
        }
        return switch (cls) {
            case "creature" -> CREATURE_S;
            case "building" -> BUILDING_S;
            case "picture" -> PICTURE_S;
            case "event" -> EVENT_S;
            case "photo" -> PHOTO_S;          // an undeclared wish that asked for a photograph: one shot
            default -> 0;                     // "item", "none"
        };
    }

    /** {@code plan_wish}'s kind → a camera class. Unknown or blank words are "none" ({@link WishAim.Kind#OTHER}). */
    public static String classOfPlan(String kind) {
        return switch (WishAim.Kind.parse(kind)) {
            case CREATURE -> "creature";
            case BUILDING -> "building";
            case PICTURE -> "picture";
            case EVENT -> "event";
            case ITEM -> "item";
            case OTHER -> "none";
        };
    }

    /** {@code MadeKind} → a camera class, or "" for what says nothing about the camera (a datapack). */
    public static String classOfMade(String madeKind) {
        if (madeKind == null) {
            return "";
        }
        return switch (madeKind.strip().toLowerCase(Locale.ROOT)) {
            case "creature", "mob" -> "creature";
            case "build" -> "building";
            case "painting" -> "picture";
            case "event" -> "event";
            case "item" -> "item";
            default -> "";
        };
    }
}
