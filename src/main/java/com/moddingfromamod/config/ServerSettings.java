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
                        releaseStandInHold();
                        Magician.endCheck();
                        if (!Magician.allowsAi()) {
                            com.moddingfromamod.agent.AgentSession.stop();
                        }
                    }
                }));
    }

    /**
     * The stand-in's hold on the world spawn while the check's wish runs. A fake player loads no chunks,
     * and 1.21.9+ no longer keeps the spawn loaded, so without it whatever the test wish made beside
     * the stand-in would land in a chunk nothing loads. Server thread only.
     */
    private static com.moddingfromamod.place.SpawnHold standInHold;

    /** A stand-in player at world spawn to make the start-up check's wish (nobody is online yet). */
    private static net.minecraft.server.level.ServerPlayer standIn(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(CHECKER, "MagicianCheck"));
        var spawn = level.getRespawnData().pos();
        p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        p.setServerLevel(level);
        com.moddingfromamod.agent.ActingChild.standIn(p);
        releaseStandInHold();
        standInHold = com.moddingfromamod.place.SpawnHold.open(level, spawn,
                com.moddingfromamod.place.SpawnHoldPlan.Followup.NONE);
        return p;
    }

    private static void releaseStandInHold() {
        if (standInHold != null) {
            standInHold.close();
            standInHold = null;
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        standInHold = null;
        dedicated = false;
        checkPending = false;
        values = Map.of();
    }
}
