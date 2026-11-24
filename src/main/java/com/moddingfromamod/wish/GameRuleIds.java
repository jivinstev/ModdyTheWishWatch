package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The pre-1.21.11 game-rule ids, spelled the 1.21.11 way</b> (ported from the 26.2 fork's GameRuleIds).
 *
 * <p>Minecraft 1.21.11 moved game rules into a registry with snake_case ids ({@code doDaylightCycle} →
 * {@code advance_time}, {@code keepInventory} → {@code keep_inventory}) and keeps no alias, so {@code /gamerule
 * doDaylightCycle false} fails outright. The agent learned the old names; the starter library's ready-made packs (and
 * every pack a player wished before 1.21.11) carry the compiler's {@code gamerule maxCommandChainLength}; and inside a
 * datapack function one bad line drops the whole function. So the old id is rewritten to the new one here, in
 * {@link CommandDialect}, which already runs over every command and every datapack source at install.
 *
 * <p>Three 1.21.4 rules were INVERTED rather than renamed ({@code disableRaids} → {@code raids}
 * and the two movement checks), so a literal {@code true}/{@code false} after one of those is
 * flipped. A query, or a value that is not a literal, is left alone rather than guessed at.
 *
 * <p>The map is Mojang's own 1.21.11 {@code GameRuleRegistryFix} (old name → id), every target checked against
 * 1.21.11's {@code GameRules} registry; rules that exist only in 26.x stay out. {@code doFireTick} became an integer
 * ({@code fire_spread_radius_around_player}), not a rename, so it is not mapped: the game refuses it, which is the
 * truth.
 *
 * <p>Pure: no Minecraft ({@code GameRuleIdsTest}).
 */
public final class GameRuleIds {

    private GameRuleIds() {
    }

    /** Old id (lower-cased) → new id. */
    static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("dodaylightcycle", "advance_time"),
            Map.entry("doweathercycle", "advance_weather"),
            Map.entry("dotiledrops", "block_drops"),
            Map.entry("blockexplosiondropdecay", "block_explosion_drop_decay"),
            Map.entry("commandblockoutput", "command_block_output"),
            Map.entry("drowningdamage", "drowning_damage"),
            Map.entry("enderpearlsvanishondeath", "ender_pearls_vanish_on_death"),
            Map.entry("doentitydrops", "entity_drops"),
            Map.entry("falldamage", "fall_damage"),
            Map.entry("firedamage", "fire_damage"),
            Map.entry("forgivedeadplayers", "forgive_dead_players"),
            Map.entry("freezedamage", "freeze_damage"),
            Map.entry("globalsoundevents", "global_sound_events"),
            Map.entry("doimmediaterespawn", "immediate_respawn"),
            Map.entry("keepinventory", "keep_inventory"),
            Map.entry("lavasourceconversion", "lava_source_conversion"),
            Map.entry("dolimitedcrafting", "limited_crafting"),
            Map.entry("logadmincommands", "log_admin_commands"),
            Map.entry("commandmodificationblocklimit", "max_block_modifications"),
            Map.entry("maxcommandforkcount", "max_command_forks"),
            Map.entry("maxcommandchainlength", "max_command_sequence_length"),
            Map.entry("maxentitycramming", "max_entity_cramming"),
            Map.entry("snowaccumulationheight", "max_snow_accumulation_height"),
            Map.entry("domobloot", "mob_drops"),
            Map.entry("mobexplosiondropdecay", "mob_explosion_drop_decay"),
            Map.entry("mobgriefing", "mob_griefing"),
            Map.entry("naturalregeneration", "natural_health_regeneration"),
            Map.entry("playerssleepingpercentage", "players_sleeping_percentage"),
            Map.entry("projectilescanbreakblocks", "projectiles_can_break_blocks"),
            Map.entry("randomtickspeed", "random_tick_speed"),
            Map.entry("reduceddebuginfo", "reduced_debug_info"),
            Map.entry("spawnradius", "respawn_radius"),
            Map.entry("sendcommandfeedback", "send_command_feedback"),
            Map.entry("announceadvancements", "show_advancement_messages"),
            Map.entry("showdeathmessages", "show_death_messages"),
            Map.entry("domobspawning", "spawn_mobs"),
            Map.entry("dopatrolspawning", "spawn_patrols"),
            Map.entry("doinsomnia", "spawn_phantoms"),
            Map.entry("dotraderspawning", "spawn_wandering_traders"),
            Map.entry("dowardenspawning", "spawn_wardens"),
            Map.entry("spectatorsgeneratechunks", "spectators_generate_chunks"),
            Map.entry("dovinesspread", "spread_vines"),
            Map.entry("tntexplodes", "tnt_explodes"),
            Map.entry("tntexplosiondropdecay", "tnt_explosion_drop_decay"),
            Map.entry("universalanger", "universal_anger"),
            Map.entry("watersourceconversion", "water_source_conversion"),
            // rules added after 1.21.4 (still in 1.21.8's camelCase), from the same 1.21.11 fix
            Map.entry("allowenteringnetherusingportals", "allow_entering_nether_using_portals"),
            Map.entry("commandblocksenabled", "command_blocks_work"),
            Map.entry("enablecommandblocks", "command_blocks_work"),
            Map.entry("locatorbar", "locator_bar"),
            Map.entry("minecartmaxspeed", "max_minecart_speed"),
            Map.entry("playersnetherportalcreativedelay", "players_nether_portal_creative_delay"),
            Map.entry("playersnetherportaldefaultdelay", "players_nether_portal_default_delay"),
            Map.entry("spawnerblocksenabled", "spawner_blocks_work"),
            Map.entry("spawnmonsters", "spawn_monsters"),
            // INVERTED: "disable X = true" is "X = false".
            Map.entry("disableraids", "raids"),
            Map.entry("disableelytramovementcheck", "elytra_movement_check"),
            Map.entry("disableplayermovementcheck", "player_movement_check"));

    /** The rules whose meaning flipped, so a literal boolean after them flips too. */
    static final java.util.Set<String> INVERTED = java.util.Set.of(
            "disableraids", "disableelytramovementcheck", "disableplayermovementcheck");

    private static final Pattern GAMERULE = Pattern.compile(
            "(?i)(?<![\\w:])(gamerule\\s+)(?:minecraft:)?([A-Za-z]+)(?![\\w:])(\\s+(true|false)\\b)?");

    /** The text with every old game-rule id renamed; each rename is added to {@code notes}. */
    public static String correct(String text, List<String> notes) {
        if (text == null || !text.toLowerCase(Locale.ROOT).contains("gamerule")) {
            return text;
        }
        Matcher m = GAMERULE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String was = m.group(2);
            String key = was.toLowerCase(Locale.ROOT);
            String now = RENAMED.get(key);
            if (now == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String value = m.group(4);
            String tail = m.group(3) == null ? "" : m.group(3);
            if (value != null && INVERTED.contains(key)) {
                String flipped = value.equalsIgnoreCase("true") ? "false" : "true";
                tail = tail.substring(0, tail.length() - value.length()) + flipped;
            }
            String note = "the game rule '" + was + "' to '" + now + "'"
                    + (value != null && INVERTED.contains(key) ? " (its meaning is inverted, so the value was flipped)" : "");
            if (!notes.contains(note)) {
                notes.add(note);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + now + tail));
        }
        m.appendTail(out);
        return out.toString();
    }
}
