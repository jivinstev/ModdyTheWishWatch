package com.moddingfromamod.seam;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Every sound the server told a player's client to play</b> (chunk 0) — while a harness has
 * it switched on, and never otherwise.
 *
 * <p>A wished creature's voice can come from anywhere: a vanilla mob's own sounds, a datapack's
 * {@code /playsound}, an effect template's {@code SOUND}, an explosion. {@code /playsound} sends
 * its packet straight to each player and never passes through {@code Level.playSound}, so NeoForge's
 * {@code PlayLevelSoundEvent} cannot see it. The one place every one of them passes is the packet
 * going out to the player, so that is where {@code SoundSentMixin} reads it.
 *
 * <p>What this is evidence OF is narrow and stated: the SERVER SENT it. Whether the client played
 * it, and whether it sounds right, is {@code tools/audio-check.py} on a recording of a real client.
 */
public final class SoundLedger {

    /**
     * One sound sent: a position, or an entity id when it is attached to an entity (then NaN xyz);
     * and where the SUBJECT stood the moment it was sent ({@link #follow}; NaN when nobody is
     * followed), because by the time a harness reads the ledger it may be blocks away.
     */
    public record Sent(long tick, String player, String sound, String source, double x, double y, double z,
                       int entityId, float volume, float pitch, double subjectX, double subjectY, double subjectZ) {
    }

    private static volatile java.util.function.Supplier<double[]> subject = () -> null;

    /**
     * Note where this is every time a sound is sent (harness fault 19). A sound was placed
     * against where the creature stood when the phase was read, not when it cried: a small creature's
     * death cry, played where it died, read 5.1 blocks from it and "no sound from it" (bh-c06).
     */
    public static void follow(java.util.function.Supplier<double[]> where) {
        subject = where == null ? () -> null : where;
    }

    private static volatile boolean on;
    private static volatile long tick;
    private static final List<Sent> SENT = new ArrayList<>();

    private SoundLedger() {
    }

    public static void start() {
        synchronized (SENT) {
            SENT.clear();
        }
        on = true;
    }

    public static void stop() {
        on = false;
        subject = () -> null;
    }

    public static boolean on() {
        return on;
    }

    /** The harness's own clock, so a record lines up with its phases rather than with wall time. */
    public static void tick(long t) {
        tick = t;
    }

    public static List<Sent> drain() {
        synchronized (SENT) {
            List<Sent> out = List.copyOf(SENT);
            SENT.clear();
            return out;
        }
    }

    /** Called by the mixin for every packet sent to a player; cheap when off. */
    public static void sent(ServerPlayer player, Packet<?> packet) {
        if (!on || player == null) {
            return;
        }
        Sent s = null;
        double[] at = null;
        try {
            at = subject.get();
        } catch (RuntimeException ignored) {
            // a subject that cannot say where it is is simply not placed
        }
        double sx = at == null ? Double.NaN : at[0];
        double sy = at == null ? Double.NaN : at[1];
        double sz = at == null ? Double.NaN : at[2];
        if (packet instanceof ClientboundSoundPacket p) {
            s = new Sent(tick, player.getGameProfile().name(), id(p.getSound()), p.getSource().getName(),
                    p.getX(), p.getY(), p.getZ(), -1, p.getVolume(), p.getPitch(), sx, sy, sz);
        } else if (packet instanceof ClientboundSoundEntityPacket p) {
            s = new Sent(tick, player.getGameProfile().name(), id(p.getSound()), p.getSource().getName(),
                    Double.NaN, Double.NaN, Double.NaN, p.getId(), p.getVolume(), p.getPitch(), sx, sy, sz);
        }
        if (s != null) {
            synchronized (SENT) {
                SENT.add(s);
            }
        }
    }

    private static String id(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> h) {
        return h.unwrapKey().map(k -> k.identifier().toString()).orElseGet(() -> h.value().location().toString());
    }
}
