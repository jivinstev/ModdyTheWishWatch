package com.moddingfromamod.compat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Game rules a newer Minecraft renamed</b>, applied to a STORED datapack's functions when it is installed on a version
 * where the old name is no longer a command. 1.21.11 moved game rules into a registry with snake_case ids, and
 * {@code gamerule maxCommandChainLength 1000000000} (the CBScript compiler's own boilerplate, in the reset function of
 * every pack a player wished for before 1.21.11) is an unknown command there; one line Minecraft cannot parse fails the
 * whole function to load. Minecraft's DataFixers upgrade the world's own rules (GameRuleRegistryFix); nothing upgrades
 * the commands a wish wrote into its pack.
 *
 * <p><b>The table is the mechanism</b>, as {@link BlockRenames}: one {@link Row} per rename with the Minecraft version
 * that introduced it, taken from Mojang's GameRuleRegistryFix (1.21.11). A row whose rule was inverted
 * ({@code disableRaids} -> {@code raids}) flips a {@code true}/{@code false} value, as the fix does. {@code doFireTick}
 * became an integer ({@code fire_spread_radius_around_player}: false -> 0, true -> 128, the fix's own mapping, which
 * reads 128 as "the default"); {@code allowFireTicksAwayFromPlayer} has no one-command equivalent and is left as written.
 *
 * <p>An old name is never valid on a version at or after its row, so a row applies to any line read there: stored
 * packs carry no version marker, and none is needed. A line on an older Minecraft is returned untouched.
 *
 * <p>Pure (no Minecraft imports), tested in Gate A ({@code GameRuleRenamesTest}).
 */
public final class GameRuleRenames {

    private GameRuleRenames() {
    }

    /** One rename: {@code from} is {@code to} from Minecraft {@code since} on; {@code inverted} flips a boolean value. */
    public record Row(String from, String to, String since, boolean inverted) {
    }

    /** Every rename, in GameRuleRegistryFix's order. */
    public static final List<Row> ROWS = List.of(
            new Row("allowEnteringNetherUsingPortals", "allow_entering_nether_using_portals", "1.21.11", false),
            new Row("announceAdvancements", "show_advancement_messages", "1.21.11", false),
            new Row("blockExplosionDropDecay", "block_explosion_drop_decay", "1.21.11", false),
            new Row("commandBlockOutput", "command_block_output", "1.21.11", false),
            new Row("enableCommandBlocks", "command_blocks_work", "1.21.11", false),
            new Row("commandBlocksEnabled", "command_blocks_work", "1.21.11", false),
            new Row("commandModificationBlockLimit", "max_block_modifications", "1.21.11", false),
            new Row("disableElytraMovementCheck", "elytra_movement_check", "1.21.11", true),
            new Row("disablePlayerMovementCheck", "player_movement_check", "1.21.11", true),
            new Row("disableRaids", "raids", "1.21.11", true),
            new Row("doDaylightCycle", "advance_time", "1.21.11", false),
            new Row("doEntityDrops", "entity_drops", "1.21.11", false),
            new Row("doImmediateRespawn", "immediate_respawn", "1.21.11", false),
            new Row("doInsomnia", "spawn_phantoms", "1.21.11", false),
            new Row("doLimitedCrafting", "limited_crafting", "1.21.11", false),
            new Row("doMobLoot", "mob_drops", "1.21.11", false),
            new Row("doMobSpawning", "spawn_mobs", "1.21.11", false),
            new Row("doPatrolSpawning", "spawn_patrols", "1.21.11", false),
            new Row("doTileDrops", "block_drops", "1.21.11", false),
            new Row("doTraderSpawning", "spawn_wandering_traders", "1.21.11", false),
            new Row("doVinesSpread", "spread_vines", "1.21.11", false),
            new Row("doWardenSpawning", "spawn_wardens", "1.21.11", false),
            new Row("doWeatherCycle", "advance_weather", "1.21.11", false),
            new Row("drowningDamage", "drowning_damage", "1.21.11", false),
            new Row("enderPearlsVanishOnDeath", "ender_pearls_vanish_on_death", "1.21.11", false),
            new Row("fallDamage", "fall_damage", "1.21.11", false),
            new Row("fireDamage", "fire_damage", "1.21.11", false),
            new Row("forgiveDeadPlayers", "forgive_dead_players", "1.21.11", false),
            new Row("freezeDamage", "freeze_damage", "1.21.11", false),
            new Row("globalSoundEvents", "global_sound_events", "1.21.11", false),
            new Row("keepInventory", "keep_inventory", "1.21.11", false),
            new Row("lavaSourceConversion", "lava_source_conversion", "1.21.11", false),
            new Row("locatorBar", "locator_bar", "1.21.11", false),
            new Row("logAdminCommands", "log_admin_commands", "1.21.11", false),
            new Row("maxCommandChainLength", "max_command_sequence_length", "1.21.11", false),
            new Row("maxCommandForkCount", "max_command_forks", "1.21.11", false),
            new Row("maxEntityCramming", "max_entity_cramming", "1.21.11", false),
            new Row("minecartMaxSpeed", "max_minecart_speed", "1.21.11", false),
            new Row("mobExplosionDropDecay", "mob_explosion_drop_decay", "1.21.11", false),
            new Row("mobGriefing", "mob_griefing", "1.21.11", false),
            new Row("naturalRegeneration", "natural_health_regeneration", "1.21.11", false),
            new Row("playersNetherPortalCreativeDelay", "players_nether_portal_creative_delay", "1.21.11", false),
            new Row("playersNetherPortalDefaultDelay", "players_nether_portal_default_delay", "1.21.11", false),
            new Row("playersSleepingPercentage", "players_sleeping_percentage", "1.21.11", false),
            new Row("projectilesCanBreakBlocks", "projectiles_can_break_blocks", "1.21.11", false),
            new Row("pvp", "pvp", "1.21.11", false),
            new Row("randomTickSpeed", "random_tick_speed", "1.21.11", false),
            new Row("reducedDebugInfo", "reduced_debug_info", "1.21.11", false),
            new Row("sendCommandFeedback", "send_command_feedback", "1.21.11", false),
            new Row("showDeathMessages", "show_death_messages", "1.21.11", false),
            new Row("snowAccumulationHeight", "max_snow_accumulation_height", "1.21.11", false),
            new Row("spawnMonsters", "spawn_monsters", "1.21.11", false),
            new Row("spawnRadius", "respawn_radius", "1.21.11", false),
            new Row("spawnerBlocksEnabled", "spawner_blocks_work", "1.21.11", false),
            new Row("spectatorsGenerateChunks", "spectators_generate_chunks", "1.21.11", false),
            new Row("tntExplodes", "tnt_explodes", "1.21.11", false),
            new Row("tntExplosionDropDecay", "tnt_explosion_drop_decay", "1.21.11", false),
            new Row("universalAnger", "universal_anger", "1.21.11", false),
            new Row("waterSourceConversion", "water_source_conversion", "1.21.11", false));


    /** doFireTick (a boolean) became fire_spread_radius_around_player (an integer) in 1.21.11. */
    static final String FIRE_TICK = "doFireTick";
    static final String FIRE_SPREAD = "fire_spread_radius_around_player";
    static final String FIRE_SINCE = "1.21.11";

    // a gamerule command where a command starts: the line's start (an optional "/"), or after "run "
    private static final Pattern GAMERULE = Pattern.compile(
            "(^\\s*/?|\\brun\\s+)((?:minecraft:)?gamerule\\s+)([A-Za-z]+)(?=\\s|$)(\\s+(true|false)(?=\\s|$))?");

    /** One function's text as it must read on {@code runningOn}: every old game-rule name in a gamerule command renamed. */
    public static String correct(String text, String runningOn) {
        if (text == null || text.indexOf("gamerule") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(correctLine(lines[i], runningOn));
        }
        return out.toString();
    }

    /** One command line, as {@link #correct}. */
    public static String correctLine(String line, String runningOn) {
        if (line == null || line.indexOf("gamerule") < 0) {
            return line;
        }
        Matcher m = GAMERULE.matcher(line);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String name = m.group(3);
            String value = m.group(5);
            String replacement = null;
            if (name.equals(FIRE_TICK) && value != null && BlockRenames.compare(runningOn, FIRE_SINCE) >= 0) {
                replacement = m.group(1) + m.group(2) + FIRE_SPREAD + " " + (value.equals("true") ? "128" : "0");
            } else {
                for (Row r : ROWS) {
                    if (r.from().equals(name) && BlockRenames.compare(runningOn, r.since()) >= 0) {
                        String v = value == null ? "" : " " + (r.inverted() ? String.valueOf(!Boolean.parseBoolean(value)) : value);
                        replacement = m.group(1) + m.group(2) + r.to() + v;
                        break;
                    }
                }
            }
            if (replacement != null) {
                any = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        if (!any) {
            return line;
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
