package com.moddingfromamod.testing;

import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import com.moddingfromamod.seam.ProbeExercise;
import com.moddingfromamod.testing.gt.GameTestAdapter;

/**
 * <b>The GameTest harness, wired in</b>. Loaded by name by {@code seam/TestHarness} from the mod constructor,
 * so the shipped jar, which leaves this package out, never names it. Everything here is inert outside a test run or
 * its opt-in property, exactly as when the mod constructor called each piece itself.
 */
public final class HarnessBoot {

    private HarnessBoot() {
    }

    /**
     * Gate B runs with Magician mode ON, the configuration every GameTest that grants, routes or grades a wish
     * was written for: a server's settings override (a plug-in) turns it on in the full build's runs, and the public mod (no plug-in)
     * would otherwise run every one of them with Magician off, where the Apprentice answers every typed wish. Only on a
     * GameTest server (the namespaces are set by {@code runGameTestServer} alone); not a lock, so it reads as a
     * server file's {@code magician.enabled on}. A test of the Magician-off mod hides the overrides
     * ({@code Plugins.hideForTest(SettingsOverride.class, true)}), which hides this one too.
     */
    static void magicianOnForGateB() {
        if (System.getProperty("neoforge.enabledGameTestNamespaces", "").isBlank()) {
            return;
        }
        com.moddingfromamod.plugin.Plugins.shared().add(com.moddingfromamod.config.SettingsOverride.class,
                new com.moddingfromamod.config.SettingsOverride() {
                    @Override
                    public java.util.Set<String> keys() {
                        return java.util.Set.of(com.moddingfromamod.config.SettingsCatalog.MAGICIAN);
                    }

                    @Override
                    public java.util.Optional<String> value(String key) {
                        return com.moddingfromamod.config.SettingsCatalog.MAGICIAN.equals(key)
                                ? java.util.Optional.of("on") : java.util.Optional.empty();
                    }

                    @Override
                    public boolean locks(String key) {
                        return false;
                    }
                });
    }

    public static void boot(IEventBus modBus) {
        GameTestAdapter.register(modBus);   // Gate B on 1.21.8
        // Per-test Gate B timing. Inert outside a test run; wraps rather than replaces whatever reporter is installed,
        // so a failing test still says why. At construction because GlobalTestReporter is read as soon as the first
        // test finishes.
        GateBTimings.install();
        StallWatchdog.register();          //: a Server thread that stops ticking ends the run, by name
        magicianOnForGateB();
        ProbeExercise.set(new ProbeExercise() {
            @Override
            public boolean enabled() {
                return BehaviourExercise.enabled();
            }

            @Override
            public boolean running() {
                return BehaviourExercise.running();
            }

            @Override
            public void tick(MinecraftServer server) {
                BehaviourExercise.tick(server);
            }

            @Override
            public ServerPlayer standInChild(ServerLevel level, UUID id, String name) {
                return BehaviourExercise.standInChild(level, id, name);
            }

            @Override
            public void begin(MinecraftServer server, Path sigDir, String wish, UUID wisherId, String wisherName,
                              Runnable done) {
                BehaviourExercise.begin(server, sigDir, wish, wisherId, wisherName, done);
            }
        });
    }
}
