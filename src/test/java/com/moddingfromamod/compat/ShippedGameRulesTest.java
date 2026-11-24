package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.wish.CommandDialect;

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
 * <b>Every pack the jar ships names only game rules 1.21.11 has, once {@link CommandDialect} has run.</b> 1.21.11
 * replaced the camelCase game-rule names with registry ids, and one gamerule line the server cannot parse makes it
 * refuse the whole pack. The starter library's ready-made packs were compiled before 1.21.11 with the compiler's
 * {@code gamerule maxCommandChainLength} boilerplate; they ship as they were, and the dialect (GameRuleIds) rewrites
 * the line at install, as it does for every datapack source. This reads each shipped pack's functions through the
 * same dialect and fails on any rule name that is still not a 1.21.11 id.
 */
class ShippedGameRulesTest {

    /** 1.21.11's game-rule ids (net.minecraft.world.level.gamerules.GameRules), bare. */
    static final Set<String> IDS_1_21_11 = Set.of("advance_time", "advance_weather", "allow_entering_nether_using_portals", "block_drops", "block_explosion_drop_decay", "command_block_output", "command_blocks_work", "drowning_damage", "elytra_movement_check", "ender_pearls_vanish_on_death", "entity_drops", "fall_damage", "fire_damage", "fire_spread_radius_around_player", "forgive_dead_players", "freeze_damage", "global_sound_events", "immediate_respawn", "keep_inventory", "lava_source_conversion", "limited_crafting", "locator_bar", "log_admin_commands", "max_block_modifications", "max_command_forks", "max_command_sequence_length", "max_entity_cramming", "max_minecart_speed", "max_snow_accumulation_height", "mob_drops", "mob_explosion_drop_decay", "mob_griefing", "natural_health_regeneration", "player_movement_check", "players_nether_portal_creative_delay", "players_nether_portal_default_delay", "players_sleeping_percentage", "projectiles_can_break_blocks", "pvp", "raids", "random_tick_speed", "reduced_debug_info", "respawn_radius", "send_command_feedback", "show_advancement_messages", "show_death_messages", "spawn_mobs", "spawn_monsters", "spawn_patrols", "spawn_phantoms", "spawn_wandering_traders", "spawn_wardens", "spawner_blocks_work", "spectators_generate_chunks", "spread_vines", "tnt_explodes", "tnt_explosion_drop_decay", "universal_anger", "water_source_conversion");

    private static final Pattern GAMERULE = Pattern.compile("(?:^|\\brun\\s+|^/)(?:minecraft:)?gamerule\\s+(?:minecraft:)?([A-Za-z_]+)\\b",
            Pattern.MULTILINE);

    @Test
    void everyShippedPackNamesOnly1_21_11RulesAfterTheDialect() throws IOException {
        List<String> hits = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> s = Files.walk(Path.of("src/main/resources"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".zip") && !p.getFileName().toString().equals("cbscript.zip"))
                    .sorted().toList()) {
                try (ZipInputStream in = new ZipInputStream(Files.newInputStream(f))) {
                    for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                        if (!e.getName().endsWith(".mcfunction")) {
                            continue;
                        }
                        String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        String text = CommandDialect.correct(raw, id -> true).command();
                        Matcher m = GAMERULE.matcher(text);
                        while (m.find()) {
                            seen++;
                            if (!IDS_1_21_11.contains(m.group(1))) {
                                hits.add(f + "!" + e.getName() + ": gamerule " + m.group(1));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(seen > 0, "no shipped pack has a gamerule line: this test reads nothing");
        assertEquals(List.of(), hits, "a shipped pack names a game rule 1.21.11 does not have, even after the dialect");
    }
}
