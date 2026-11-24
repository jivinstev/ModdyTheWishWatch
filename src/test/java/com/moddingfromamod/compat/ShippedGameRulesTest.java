package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * <b>No pack the jar ships names a game rule by its pre-1.21.11 name.</b> 1.21.11 replaced the camelCase names with
 * registry ids (Mojang's GameRuleRegistryFix: maxCommandChainLength -> max_command_sequence_length, ...), and one
 * gamerule line the server cannot parse makes it refuse the whole pack. The starter library's ready-made packs were
 * compiled before 1.21.11 with the compiler's {@code gamerule maxCommandChainLength} boilerplate; they are renamed at
 * the source, and this keeps them so. (A pack a player wished before 1.21.11 is upgrade-only: known issue, not here.)
 */
class ShippedGameRulesTest {

    /** Every game rule name 1.21.11 dropped (GameRuleRegistryFix's left-hand side; pvp kept its name). */
    static final Set<String> OLD = Set.of("allowEnteringNetherUsingPortals", "allowFireTicksAwayFromPlayer", "announceAdvancements", "blockExplosionDropDecay", "commandBlockOutput", "commandBlocksEnabled", "commandModificationBlockLimit", "disableElytraMovementCheck", "disablePlayerMovementCheck", "disableRaids", "doDaylightCycle", "doEntityDrops", "doFireTick", "doImmediateRespawn", "doInsomnia", "doLimitedCrafting", "doMobLoot", "doMobSpawning", "doPatrolSpawning", "doTileDrops", "doTraderSpawning", "doVinesSpread", "doWardenSpawning", "doWeatherCycle", "drowningDamage", "enableCommandBlocks", "enderPearlsVanishOnDeath", "fallDamage", "fireDamage", "forgiveDeadPlayers", "freezeDamage", "globalSoundEvents", "keepInventory", "lavaSourceConversion", "locatorBar", "logAdminCommands", "maxCommandChainLength", "maxCommandForkCount", "maxEntityCramming", "minecartMaxSpeed", "mobExplosionDropDecay", "mobGriefing", "naturalRegeneration", "playersNetherPortalCreativeDelay", "playersNetherPortalDefaultDelay", "playersSleepingPercentage", "projectilesCanBreakBlocks", "randomTickSpeed", "reducedDebugInfo", "sendCommandFeedback", "showDeathMessages", "snowAccumulationHeight", "spawnChunkRadius", "spawnMonsters", "spawnRadius", "spawnerBlocksEnabled", "spectatorsGenerateChunks", "tntExplodes", "tntExplosionDropDecay", "universalAnger", "waterSourceConversion");

    private static final Pattern GAMERULE = Pattern.compile("(?:^|\\brun\\s+|^/)(?:minecraft:)?gamerule\\s+([A-Za-z]+)\\b",
            Pattern.MULTILINE);

    @Test
    void noShippedPackNamesAnOldGameRule() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/resources"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".zip") && !p.getFileName().toString().equals("cbscript.zip"))
                    .sorted().toList()) {
                try (ZipInputStream in = new ZipInputStream(Files.newInputStream(f))) {
                    for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                        if (!e.getName().endsWith(".mcfunction")) {
                            continue;
                        }
                        Matcher m = GAMERULE.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        while (m.find()) {
                            if (OLD.contains(m.group(1))) {
                                hits.add(f + "!" + e.getName() + ": gamerule " + m.group(1));
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), hits, "a shipped pack names a game rule 1.21.11 renamed");
    }
}
