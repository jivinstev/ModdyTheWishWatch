package com.moddingfromamod.config;

import java.util.Map;
import java.util.Optional;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * <b>The dedicated server's layer</b> (D28): on a dedicated server, the settings come from its configuration file
 * ({@link ServerConfigFile}), read once as the server starts and judged by {@link ServerConfigRules}. Nobody changes
 * them in game. Anywhere else (single-player, a LAN host) this layer is empty and {@link #dedicated()} is false.
 */
public final class ServerSettings {

    private static volatile boolean dedicated;
    private static volatile Map<String, String> values = Map.of();
    /** The file passed every rule; the start-up check runs once the server has started (D28). */
    private static volatile boolean checkPending;
    private static final java.util.UUID CHECKER = java.util.UUID.nameUUIDFromBytes(
            "moddingfromamod:magician-check".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private ServerSettings() {
    }

    public static boolean dedicated() {
        return dedicated;
    }

    public static Optional<String> value(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        dedicated = event.getServer().isDedicatedServer();
        if (!dedicated) {
            values = Map.of();
            return;
        }
        var result = ServerConfigRules.apply(ServerConfigFile.raw(), System::getenv, false);
        var log = org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);
        for (String line : result.log()) {
            if (result.magician()) {
                log.info("settings: {}", line);
            } else {
                log.warn("settings: {}", line);
            }
        }
        values = Map.copyOf(result.values());
        checkPending = result.readyForCheck();
    }

    /**
     * D28: the same end-to-end check the host runs in single-player ({@link MagicianCheck}), once, as the server
     * starts. Magician turns on only if it passes; either way the log says why, step by step.
     */
    @SubscribeEvent
    public static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!dedicated || !checkPending) {
            return;
        }
        checkPending = false;
        var server = event.getServer();
        var log = org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);
        MagicianPicks picks = MagicianPicks.current();
        log.info("settings: running the Magician start-up check with {}", picks.describe());
        Magician.beginCheck();
        MagicianCheck.run(new LiveCheckSteps(server, () -> standIn(server), picks),
                step -> log.info("settings: check {} {}: {}", step.pass() ? "ok" : "FAILED", step.name(), step.line()),
                report -> server.execute(() -> {
                    try {
                        if (report.pass() && dedicated) {
                            java.util.Map<String, String> on = new java.util.LinkedHashMap<>(values);
                            on.put(SettingsCatalog.MAGICIAN, "on");
                            values = Map.copyOf(on);
                            log.info("settings: Magician is ON: {}", report.summary());
                        } else {
                            log.warn("settings: Magician stays OFF: {}", report.summary());
                        }
                    } finally {
                        Magician.endCheck();
                        if (!Magician.allowsAi()) {
                            com.moddingfromamod.agent.AgentSession.stop();
                        }
                    }
                }));
    }

    /** A stand-in player at world spawn to make the start-up check's wish (nobody is online yet). */
    private static net.minecraft.server.level.ServerPlayer standIn(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(CHECKER, "MagicianCheck"));
        var spawn = level.getSharedSpawnPos();
        p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        p.setServerLevel(level);
        com.moddingfromamod.agent.ActingChild.standIn(p);
        return p;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        dedicated = false;
        checkPending = false;
        values = Map.of();
    }
}
