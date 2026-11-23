package com.moddingfromamod.seam;

import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The behaviour exercise, as the headless wish probe sees it</b>. The probe
 * ({@code agent/HeadlessWishProbe}) ships; the exercise ({@code testing/BehaviourExercise}) is harness and does not,
 * so the probe asks this. {@code testing/HarnessBoot} sets the real one; without the harness (a shipped jar) the
 * exercise is never enabled, never running, and the probe behaves exactly as it does with the property unset.
 *
 * <p>An internal seam: no plug-in fills it, only the public test harness.
 */
public interface ProbeExercise {

    /** Opt-in beside a headless wish ({@code -Dmoddingfromamod.behaviourexercise=on}). */
    boolean enabled();

    boolean running();

    void tick(MinecraftServer server);

    /** The child who wished, in the world from the start (a stock mock in the player list). */
    ServerPlayer standInChild(ServerLevel level, UUID id, String name);

    /** Start on the creature this wish made; {@code done} runs once {@code behaviour.json} is written. */
    void begin(MinecraftServer server, Path sigDir, String wish, UUID wisherId, String wisherName, Runnable done);

    /** No harness: nothing is ever enabled. */
    ProbeExercise NONE = new ProbeExercise() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public boolean running() {
            return false;
        }

        @Override
        public void tick(MinecraftServer server) {
        }

        @Override
        public ServerPlayer standInChild(ServerLevel level, UUID id, String name) {
            throw new IllegalStateException("no behaviour exercise in this build");
        }

        @Override
        public void begin(MinecraftServer server, Path sigDir, String wish, UUID wisherId, String wisherName,
                          Runnable done) {
            throw new IllegalStateException("no behaviour exercise in this build");
        }
    };

    /** The exercise this build has: the harness's, or {@link #NONE}. */
    static ProbeExercise get() {
        return Holder.current;
    }

    /** Set by the harness at boot. */
    static void set(ProbeExercise e) {
        Holder.current = e == null ? NONE : e;
    }

    /** Where the current one is kept (an interface cannot hold mutable state itself). */
    final class Holder {
        private static volatile ProbeExercise current = NONE;

        private Holder() {
        }
    }
}
