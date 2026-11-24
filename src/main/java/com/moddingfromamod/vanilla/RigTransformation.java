package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A creature TRANSFORMING from one body to another</b> — the Minecraft half of
 * {@link TransformPlan}.
 *
 * <p>Two edits cannot morph the creature's own displays, and used to pop:
 * <ul>
 *   <li><b>a new carrier</b> ({@link #newCarrier}) — the edit changed how it moves (it now flies), so
 *       a fresh carrier is spawned and the old one goes;</li>
 *   <li><b>a new set of displays on the same carrier</b> ({@link #sameCarrier}) — a different part
 *       count, which {@code DisplayRig.reshapeInPlace} hangs beside the old set.</li>
 * </ul>
 * Both now run the same two seconds: the old body whitens, glows and swells; the two cross over
 * (old shrinks to nothing, new grows out of it, white, under a burst of sparkles); the new body
 * colours in. While it runs neither body is driven by {@code VanillaRigAnimator} — the carrier
 * stands still and takes no hits — and the new body is handed to the animator ({@code onSwapped})
 * the moment the old one is gone, so its colour-in already walks and turns.
 *
 * <p>Server thread only. Nothing here is saved: a server stop in the middle leaves the old body
 * tagged {@link DisplayRig#LEAVING_TAG}, which the next load discards, and a body part-way to white
 * is given its own colours back by {@code DisplayRig.adopt}.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigTransformation {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/transform");

    private RigTransformation() {
    }

    /** Ticked on the server thread; read from the agent's (the photograph waits on it), so copy-on-write. */
    private static final List<Run> RUNS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Is any creature transforming right now? The wish camera waits for it (a white body is no photograph). */
    public static boolean anyRunning() {
        return !RUNS.isEmpty();
    }

    /** Is this carrier in the middle of a transformation (either body's)? */
    public static boolean transforming(java.util.UUID carrier) {
        for (Run r : RUNS) {
            if (carrier.equals(r.carrierOut) || (r.arriving != null && r.arriving.base() != null
                    && carrier.equals(r.arriving.base().getUUID()))) {
                return true;
            }
        }
        return false;
    }

    private static final class Run {
        final ServerLevel level;
        final DisplayRig leaving;
        /** The carrier that goes with the leaving body, or null when the carrier stays (a part-count change). */
        final Mob carrierOutMob;
        final java.util.UUID carrierOut;
        final Supplier<DisplayRig> hang;
        final Consumer<DisplayRig> onSwapped;
        /** The new body could not be made: the old creature is put back, never lost. */
        final Runnable onAbort;
        DisplayRig arriving;
        /** The carriers this froze, and what they were before, so they are put back exactly. */
        final List<Frozen> frozen = new ArrayList<>();
        int t;
        int veilWait;
        boolean swapped;

        Run(ServerLevel level, DisplayRig leaving, Mob carrierOut, Supplier<DisplayRig> hang,
            DisplayRig arriving, Consumer<DisplayRig> onSwapped, Runnable onAbort) {
            this.onAbort = onAbort;
            this.level = level;
            this.leaving = leaving;
            this.carrierOutMob = carrierOut;
            this.carrierOut = carrierOut == null ? null : carrierOut.getUUID();
            this.hang = hang;
            this.arriving = arriving;
            this.onSwapped = onSwapped;
        }
    }

    private record Frozen(Mob mob, boolean noAi, boolean invulnerable) {
        static Frozen of(Mob m) {
            Frozen f = new Frozen(m, m.isNoAi(), m.isInvulnerable());
            m.setNoAi(true);
            m.setPermanentlyInvulnerable(true);
            m.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            // 🔴 A BAT IS BORN RESTING, and only its AI wakes it (RigPowers.tick does, for a driven
            // rig). A resting bat snaps itself under the block above every tick -- floor(y)+1-height
            // -- and a bat carrier scaled to a creature is taller than a block, so a frozen one sank
            // ~3.5 blocks a tick, through the floor to the bottom of the world (filmed).
            if (m instanceof net.minecraft.world.entity.ambient.Bat bat) {
                bat.setResting(false);
            }
            return f;
        }

        void restore() {
            if (!mob.isRemoved()) {
                mob.setNoAi(noAi);
                mob.setPermanentlyInvulnerable(invulnerable);
            }
        }
    }

    /**
     * The carrier must change: whiten {@code leaving} on its old carrier, call {@code hang} just
     * before the swap for the new carrier and body, and hand the new body to {@code onSwapped}
     * once the old carrier and its displays are gone. {@code hang} returning null puts the OLD
     * creature back exactly as it was and calls {@code onAbort} (the caller drives it again) —
     * never a creature lost, never one left white.
     */
    public static void newCarrier(ServerLevel level, DisplayRig leaving, Supplier<DisplayRig> hang,
                                  Consumer<DisplayRig> onSwapped, Runnable onAbort) {
        Mob out = leaving.base();
        out.addTag(DisplayRig.LEAVING_TAG);
        for (Entity d : leaving.displays()) {
            d.addTag(DisplayRig.LEAVING_TAG);
        }
        // its hit proxies stay until the swap: the carrier is invulnerable meanwhile, and a swap that
        // has to give the old creature back (restoreOld) needs them
        Run r = new Run(level, leaving, out, hang, null, onSwapped, onAbort);
        r.frozen.add(Frozen.of(out));
        start(r);
    }

    /**
     * The same carrier, a new set of displays (a different part count): {@code arriving} is already
     * hung, beside {@code leaving}'s displays (tagged leaving by {@code reshapeInPlace}).
     */
    public static void sameCarrier(ServerLevel level, DisplayRig leaving, DisplayRig arriving,
                                   Consumer<DisplayRig> onSwapped) {
        Run r = new Run(level, leaving, null, null, arriving, onSwapped, () -> { });
        r.frozen.add(Frozen.of(arriving.base()));
        arrive(r);
        start(r);
    }

    private static void start(Run r) {
        r.leaving.whiten(TransformPlan.leavingWhiteness(0));
        r.leaving.fullBright(r.level, true);
        r.leaving.scaleBody(TransformPlan.SWELL, TransformPlan.WHITEN);
        Mob at = r.leaving.base();
        if (at != null) {
            r.level.playSound(null, at.blockPosition(), SoundEvents.ILLUSIONER_PREPARE_MIRROR,
                    SoundSource.NEUTRAL, 1.0f, 1.2f);
        }
        RUNS.add(r);
        LOG.info("transforming {} ({}) -- {} ticks", at == null ? "?" : at.getUUID(),
                r.carrierOut != null ? "a new carrier" : "a new set of displays on the same carrier",
                TransformPlan.total());
    }

    /** The new body, the moment it exists: white, glowing, and too small to see. */
    private static void arrive(Run r) {
        r.arriving.whiten(1f);
        r.arriving.fullBright(r.level, true);
        r.arriving.scaleBody(TransformPlan.TINY, 0);
        // its hit proxies stay: the carrier is frozen and invulnerable until the swap, so a hit
        // on it does nothing, and re-planning them afterwards would be a second source of truth
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (RUNS.isEmpty()) {
            return;
        }
        for (Run r : new ArrayList<>(RUNS)) {
            try {
                if (step(r)) {
                    RUNS.remove(r);
                }
            } catch (RuntimeException e) {
                LOG.warn("a transformation failed half way -- finishing it at once", e);
                finishNow(r);
                RUNS.remove(r);
            }
        }
    }

    /** One tick of one run; true when it is over. */
    private static boolean step(Run r) {
        int t = r.t;
        // the new carrier is hung a little before the swap, so every client has its TINY start
        if (r.arriving == null && r.hang != null && t >= TransformPlan.WHITEN - TransformPlan.HANG_LEAD) {
            r.arriving = r.hang.get();
            if (r.arriving == null) {
                restoreOld(r);
                return true;
            }
            r.frozen.add(Frozen.of(r.arriving.base()));
            arrive(r);
            LOG.info("new body hung: {} at {} (the old one at {})", r.arriving.base().getType().toShortString(),
                    r.arriving.base().position(), r.leaving.base() == null ? "?" : r.leaving.base().position());
        }
        if (t < TransformPlan.WHITEN) {
            if (TransformPlan.colourStep(t)) {
                r.leaving.whiten(TransformPlan.leavingWhiteness(t));
            }
            sparkle(r, 2);
        } else if (t == TransformPlan.WHITEN) {
            // hold the white body until every client can SEE the new one (a rig veiled until its
            // wish's early reveal): a swap nobody sees is a creature vanishing
            if (veiled(r.arriving) && r.veilWait < TransformPlan.VEIL_WAIT_MAX) {
                r.veilWait++;
                sparkle(r, 1);
                return false;
            }
            r.leaving.scaleBody(TransformPlan.TINY, TransformPlan.SWAP);
            r.arriving.scaleBody(1f, TransformPlan.SWAP);
            burst(r);
        } else if (t == TransformPlan.WHITEN + TransformPlan.SWAP) {
            swap(r);
        }
        if (r.swapped && TransformPlan.colourStep(t)) {
            r.arriving.setWhiteness(TransformPlan.arrivingWhiteness(t));
            if (r.arriving.whiteness() <= 0.5f) {
                r.arriving.fullBright(r.level, false);
            }
        }
        r.t++;
        if (r.t >= TransformPlan.total()) {
            r.arriving.setWhiteness(0f);
            r.arriving.tint(RigTint.NONE);
            return true;
        }
        return false;
    }

    /** The old body goes; the new one is the creature from now on, driven and able to be hit. */
    private static void swap(Run r) {
        if (r.swapped) {
            return;
        }
        r.swapped = true;
        if (r.carrierOutMob != null) {
            r.leaving.discardProxies();
            DisplayRig.reap(r.level, r.carrierOut);
            r.carrierOutMob.discard();
        } else {
            for (Entity d : r.leaving.displays()) {
                d.discard();
            }
        }
        for (Frozen f : r.frozen) {
            if (f.mob() != r.carrierOutMob) {
                f.restore();
            }
        }
        Mob in = r.arriving.base();
        if (in != null) {
            LOG.info("swapped: the new body stands at {}", in.position());
            r.level.playSound(null, in.blockPosition(), SoundEvents.FIREWORK_ROCKET_TWINKLE,
                    SoundSource.NEUTRAL, 0.9f, 1.4f);
        }
        r.onSwapped.accept(r.arriving);
    }

    /**
     * Something went wrong, or the server is stopping: finish NOW. A new body that exists becomes
     * the creature at once; one not made yet is made now; and if it cannot be, the old creature is
     * put back — the one outcome never allowed is no creature at all.
     */
    private static void finishNow(Run r) {
        try {
            if (r.arriving == null && r.hang != null && !r.swapped) {
                r.arriving = r.hang.get();
                if (r.arriving != null) {
                    r.frozen.add(Frozen.of(r.arriving.base()));
                }
            }
            if (r.arriving == null) {
                restoreOld(r);
                return;
            }
            r.arriving.scaleBody(1f, 0);
            r.arriving.setWhiteness(0f);
            r.arriving.tint(RigTint.NONE);
            r.arriving.fullBright(r.level, false);
            swap(r);
        } catch (RuntimeException e) {
            LOG.error("could not finish a failed transformation cleanly", e);
        }
    }

    /** The new body could not be made: the old one, its colours, its size, its light and its freedom, back. */
    private static void restoreOld(Run r) {
        r.leaving.scaleBody(1f, 0);
        r.leaving.whiten(0f);
        r.leaving.fullBright(r.level, false);
        if (r.carrierOutMob != null) {
            r.carrierOutMob.removeTag(DisplayRig.LEAVING_TAG);
        }
        for (Entity d : r.leaving.displays()) {
            d.removeTag(DisplayRig.LEAVING_TAG);
        }
        for (Frozen f : r.frozen) {
            f.restore();
        }
        r.onAbort.run();
        LOG.warn("a transformation could not make the new body -- the old creature is back");
    }

    private static boolean veiled(DisplayRig rig) {
        int[] v = RigVeil.veiled();
        if (v.length == 0 || rig == null) {
            return false;
        }
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int id : v) {
            ids.add(id);
        }
        for (Entity d : rig.displays()) {
            if (ids.contains(d.getId())) {
                return true;
            }
        }
        return false;
    }

    private static void sparkle(Run r, int count) {
        Mob m = r.leaving.base();
        if (m == null || m.isRemoved()) {
            return;
        }
        var box = m.getBoundingBox();
        var c = box.getCenter();
        r.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, count,
                Math.max(0.4, box.getXsize() / 2), Math.max(0.4, box.getYsize() / 2),
                Math.max(0.4, box.getZsize() / 2), 0.02);
    }

    private static void burst(Run r) {
        Mob m = r.arriving.base() != null ? r.arriving.base() : r.leaving.base();
        if (m == null) {
            return;
        }
        var box = m.getBoundingBox();
        var c = box.getCenter();
        double w = Math.max(0.5, box.getXsize() / 2), h = Math.max(0.5, box.getYsize() / 2);
        double d = Math.max(0.5, box.getZsize() / 2);
        r.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, w, h, d, 0.12);
        r.level.sendParticles(ParticleTypes.FIREWORK, c.x, c.y, c.z, 20, w, h, d, 0.08);
        r.level.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 12, w * 0.6, h * 0.6, d * 0.6, 0.02);
    }

    /** A stopping server: finish every run now, so nothing is saved white, tiny or frozen. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Run r : new ArrayList<>(RUNS)) {
            finishNow(r);
        }
        RUNS.clear();
    }
}
