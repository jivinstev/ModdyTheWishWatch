package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The reveal: what has been sculpted this session, and whether the child has been shown it.</b>
 *
 * <p>This was {@code RigPackService} deleted the pack rig (2026-09-23). That class
 * wrote a per-creature resource pack, hosted it and pushed it; on the atom rig — the shipped default
 * since 2026-09-13 and now the only rig — a creature's models are the constant cube
 * ({@link AtomLibrary}) that arrived in the join-time item pack, so nothing here writes, hosts or
 * pushes anything. What survived is the REVEAL STATE, because the veil still needs it.
 *
 * <h2>Why a rig that delivers nothing still has to record something</h2>
 *
 * <p>{@link RigVeil#hiddenFrom} hides the rig from a stock child while a camera photographs the
 * interim sculpts, until the reveal. "Revealed" is a fact about a particular sculpt, not a boolean:
 * a NEW sculpt after a reveal must be hidden again, and a re-publish of the same geometry must not
 * re-hide a creature the child is already looking at. So the state is a content hash —
 * {@link RigGeometryPrint#combine} over every creature published this session — and "revealed" is
 * {@code alreadyRevealed(current, revealed)}.
 *
 * <p>⚠ <b>ATOM-P3b's lesson, kept:</b> before anything recorded this state on the atom arm,
 * {@code current} was null for the whole session, {@code alreadyRevealed} read false for ever, and
 * {@link #reveal} took an early return — so the veil never lifted and a stock child's creature was
 * invisible at the reveal and after it. Measured off the wire by {@code RigVeilGameTest}.
 */
public final class RigReveal {

    private RigReveal() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-rig-reveal");

    /** Creature key to its {@link RigGeometryPrint}, for everything published this session. */
    private static final Map<String, String> PRINTS = new ConcurrentHashMap<>();

    /** {@link RigGeometryPrint#combine} over {@link #PRINTS}, or null before the first publish. */
    private static volatile String current;

    /** The reveal state the stock route was last cleared for, or null. */
    private static volatile String revealed;

    /**
     * <b>Record a sculpt, so the veil hides it until the reveal.</b>
     *
     * <p>Called for every manifestation and every rebuild. A re-publish of identical geometry is
     * free and moves nothing — which is what keeps a revealed creature revealed when nothing about
     * it changed.
     */
    public static void publish(MinecraftServer server, String key,
                               List<CreatureSculpt.Part> parts, double scale) {
        publish(server, key, parts, scale, false);
    }

    /**
     * @param rebuild a creature that was already standing, rebuilt in a new shape (an edit), rather
     *                than one coming to stand for the first time
     */
    public static void publish(MinecraftServer server, String key,
                               List<CreatureSculpt.Part> parts, double scale, boolean rebuild) {
        // Every manifestation, identical geometry or not: a creature now STANDS in the world, which
        // is what the early reveal waits for (WishEarlyReveal).
        synchronized (STOOD) {
            STOOD.addLast(new Stood(++stoodSeq, key, rebuild, System.currentTimeMillis()));
            while (STOOD.size() > 256) {
                STOOD.removeFirst();
            }
        }
        String print = RigGeometryPrint.of(parts, scale, RigPose.animatedBones(
                com.moddingfromamod.runtime.ModelRepository.animations(key)));
        String previous = PRINTS.put(key, print);
        if (print.equals(previous) && current != null) {
            return;
        }
        current = RigGeometryPrint.combine(PRINTS);
        LOG.info("rig: '{}' recorded for the reveal -- no per-creature pack written or delivered; "
                + "its models are the atom cube already in the join-time item pack. (reveal state "
                + "{})", key, current.substring(0, 8));
    }

    /**
     * <b>Clear the current sculpt for the stock route and release the rig parts</b> — the reveal.
     *
     * <p>Idempotent: revealing a state already revealed does nothing. There is nothing to push —
     * the child's models arrived at join — so the whole reveal is clearing the state (so
     * {@link RigVeil}'s rule stops answering "hidden") and releasing the parts.
     */
    public static void reveal(MinecraftServer server) {
        String state = current;
        if (server == null || state == null) {
            return;
        }
        if (RigVeil.alreadyRevealed(state, revealed)) {
            return;
        }
        revealed = state;
        RigVeilService.reveal(server);
        LOG.info("REVEAL: released the rig parts for reveal state {}", state.substring(0, 8));
    }

    /** Forget everything when the world closes: the next world is not this one. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(
            net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        PRINTS.clear();
        current = null;
        revealed = null;
    }

    // ── what has come to STAND in the world, in order (the early reveal) ────

    private record Stood(long seq, String key, boolean rebuild, long atMs) {
    }

    private static final java.util.ArrayDeque<Stood> STOOD = new java.util.ArrayDeque<>();
    private static long stoodSeq;

    /** A mark to pass to {@link #stoodSince} later. Any thread. */
    public static long stoodMark() {
        synchronized (STOOD) {
            return stoodSeq;
        }
    }

    /** The creature keys that came to stand in the world after {@code mark}, oldest first, once each. */
    public static List<String> stoodSince(long mark) {
        return stoodSince(mark, 0L);
    }

    /**
     * As {@link #stoodSince(long)}, counting only what stood at or after {@code notBeforeMs}. A call
     * that spans the gap between two wishes ({@code await_wish}) would otherwise hand the next wish
     * whatever the last one stood up (filmed 2026-10-22: "make my Spikor bigger" opened with
     * "starting to appear!" before it had a plan).
     */
    public static List<String> stoodSince(long mark, long notBeforeMs) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        synchronized (STOOD) {
            for (Stood s : STOOD) {
                if (s.seq() > mark && s.atMs() >= notBeforeMs) {
                    out.add(s.key());
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Was everything that stood after {@code mark} a creature that was ALREADY standing, rebuilt in
     * a new shape? False when nothing stood. What tells an edit of his creature from a new one.
     */
    public static boolean onlyRebuiltSince(long mark, long notBeforeMs) {
        boolean any = false;
        synchronized (STOOD) {
            for (Stood s : STOOD) {
                if (s.seq() > mark && s.atMs() >= notBeforeMs) {
                    if (!s.rebuild()) {
                        return false;
                    }
                    any = true;
                }
            }
        }
        return any;
    }

    /** The current reveal state, or null. Read by the veil and by the gates. */
    public static String currentSha1() {
        return current;
    }

    /** What the stock route has been cleared for, or null. Read by the veil and by the gates. */
    public static String revealedSha1() {
        return revealed;
    }

    /** Forget the current state — the seam a GameTest needs instead of stopping a server. */
    public static void forgetForTest() {
        RigVeil.forget();
        PRINTS.clear();
        current = null;
        revealed = null;
    }
}
