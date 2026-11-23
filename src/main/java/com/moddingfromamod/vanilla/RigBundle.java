package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * <b>A rig's packets for one tick reach each viewer as ONE bundle, so a client applies them on one tick.</b>
 *
 * <p>The entity tracker sends each atom's packets on its own, one entity after another ({@code ChunkMap.tick} →
 * {@code ServerEntity.sendChanges} → {@code TrackedEntity.broadcast}). A client applies what has arrived at the start
 * of its tick, so a client tick that falls while the tracker is part-way through a rig takes some of the rig's atoms'
 * moves on this tick and the rest on the next. Every tick, that heals at once. On the rig's 3-tick beat
 * ({@link RigCadence}) it does not: the late atoms are a tick behind for the whole beat, and each new ease starts from
 * where they lag. Measured in the walk film: 57 of 64 atoms on one client tick and 7 on the next, a part drawn
 * 0.058 blocks off its body for 9 ticks (4% of the run's frames). The same split happens over a real connection,
 * wherever a TCP read ends.
 *
 * <p>So while the tracker runs, every packet a rig atom broadcasts is held, per viewer, and when the tracker is done
 * each viewer gets them as one {@code ClientboundBundlePacket}, which a client applies in one go (a remote client's
 * decoder gathers a bundle whole before handing it on). Nothing is delayed past the tick it was sent on, nothing else
 * is held, and the order within a viewer's bundle is the order the tracker sent in.
 * {@code -Dmoddingfromamod.rigbundle=off} sends them one by one as before, the A/B arm.
 *
 * <p>This class only groups; the two mixins ({@code RigBundleTickMixin}, {@code RigBundleBroadcastMixin}) open, fill
 * and send it. Generic so the grouping is tested without a world ({@code RigBundleTest}).
 */
public final class RigBundle<C, P> {

    /** On unless {@code -Dmoddingfromamod.rigbundle=off}. */
    public static final boolean ON = !"off".equals(System.getProperty("moddingfromamod.rigbundle", "on"));

    /** Under the protocol's 4096-packet bundle limit ({@code BundlerInfo.BUNDLE_SIZE_LIMIT}), with room to spare. */
    public static final int MAX_PER_BUNDLE = 4000;

    /** The tracker pass being held, on the thread running it; null outside one. */
    private static RigBundle<Object, Object> current;
    private static Thread owner;

    /** A tracker pass begins ({@code ChunkMap.tick}). Anything left from a pass that threw is sent first, by {@code send}. */
    public static void begin(BiConsumer<Object, List<Object>> send) {
        if (!ON) {
            return;
        }
        if (current != null && !current.isEmpty()) {
            current.flush(send);
        }
        current = new RigBundle<>();
        owner = Thread.currentThread();
    }

    /** Is a tracker pass being held on this thread? */
    public static boolean holding() {
        return current != null && owner == Thread.currentThread();
    }

    /** Hold a rig atom's packet for its viewers ({@link #holding} must be true). */
    @SuppressWarnings("unchecked")
    public static void hold(Collection<?> viewers, Object packet) {
        current.add((Collection<Object>) viewers, packet);
    }

    /** The tracker pass ends: every viewer gets what was held for it. */
    public static void end(BiConsumer<Object, List<Object>> send) {
        RigBundle<Object, Object> b = current;
        current = null;
        owner = null;
        if (b != null) {
            b.flush(send);
        }
    }

    private final Map<C, List<P>> held = new IdentityHashMap<>();
    private final List<C> order = new ArrayList<>();

    /** Hold {@code packet} for every viewer in {@code viewers}. */
    public void add(Collection<C> viewers, P packet) {
        for (C v : viewers) {
            held.computeIfAbsent(v, k -> {
                order.add(k);
                return new ArrayList<>();
            }).add(packet);
        }
    }

    /** Is anything held? */
    public boolean isEmpty() {
        return held.isEmpty();
    }

    /**
     * Hand each viewer its packets, in the order they were held, at most {@link #MAX_PER_BUNDLE} at a time, and forget
     * them. A viewer with one packet gets it alone: a bundle of one is no more atomic than the packet.
     */
    public void flush(BiConsumer<C, List<P>> send) {
        List<C> viewers = new ArrayList<>(order);
        Map<C, List<P>> all = new IdentityHashMap<>(held);
        held.clear();
        order.clear();
        for (C v : viewers) {
            List<P> ps = all.get(v);
            for (int i = 0; i < ps.size(); i += MAX_PER_BUNDLE) {
                send.accept(v, ps.subList(i, Math.min(ps.size(), i + MAX_PER_BUNDLE)));
            }
        }
    }
}
