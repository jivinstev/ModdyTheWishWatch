package com.moddingfromamod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate B stands on grass, and a player's jar never carries the ground it stands on.</b>
 *
 * <p>26.3's GameTestServer builds its world from the world preset {@code minecraft:flat_all_dimensions}, a desert of
 * sandstone. Up to 26.2 it built {@code minecraft:flat}, grass on plains, and the GameTests that transform, flood
 * and locate were written for that world. The dev runs read an override of {@code flat_all_dimensions} (26.2's flat
 * world: its biome and layers; it names no structures, which the GameTest world never generates, so it is not a copy
 * of a vanilla file, {@code NoVanillaCopyTest}) from the mod's resources. The override is test ground only, so the build leaves it out of the jar
 * ({@code SHIP_EXCLUDES}), and the jar task's finished-jar check refuses a jar that carries it ({@code NEVER_SHIP}).
 */
class GateBWorldPresetTest {

    static final String ENTRY = "data/minecraft/worldgen/world_preset/flat_all_dimensions.json";
    static final Path OVERRIDE = Path.of("src/main/resources").resolve(ENTRY);

    @Test
    @DisplayName("the GameTest world is 26.2's flat world, its grass two below where 26.3 starts the tests (y 4)")
    void theOverrideIsTheFlatWorld() throws IOException {
        assertTrue(Files.isRegularFile(OVERRIDE), "no GameTest world override at " + OVERRIDE);
        JsonObject settings = JsonParser.parseString(Files.readString(OVERRIDE)).getAsJsonObject()
                .getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld")
                .getAsJsonObject("generator").getAsJsonObject("settings");
        assertEquals("minecraft:plains", settings.get("biome").getAsString());
        JsonArray layers = settings.getAsJsonArray("layers");
        List<String> got = new java.util.ArrayList<>();
        layers.forEach(l -> got.add(l.getAsJsonObject().get("block").getAsString() + "x"
                + l.getAsJsonObject().get("height").getAsInt()));
        // 26.2 started its tests at y -59 on grass at -61; 26.3 starts them at y 4 (GameTestServer's startPos), so the
        // grass is at 2: bedrock at -64, 65 dirt, then grass. The same ground, two below the plots, as 26.2 had.
        assertEquals(List.of("minecraft:bedrockx1", "minecraft:dirtx65", "minecraft:grass_blockx1"), got);
    }

    @Test
    @DisplayName("the jar leaves the GameTest world out, and refuses itself if it carries it")
    void theJarNeverCarriesIt() throws IOException {
        String gradle = Files.readString(Path.of("build.gradle"));
        assertTrue(gradle.contains("def GAMETEST_WORLD_PRESET = '" + ENTRY + "'"),
                "build.gradle must name the override as GAMETEST_WORLD_PRESET");
        Matcher ex = Pattern.compile("def SHIP_EXCLUDES = \\[(.*?)\\]", Pattern.DOTALL).matcher(gradle);
        assertTrue(ex.find() && ex.group(1).contains("GAMETEST_WORLD_PRESET"),
                "SHIP_EXCLUDES must leave the GameTest world override out of the jar");
        Matcher ns = Pattern.compile("def NEVER_SHIP = ~/(.*)/\\n").matcher(gradle);
        assertTrue(ns.find(), "build.gradle has no NEVER_SHIP pattern");
        assertTrue(ENTRY.matches(ns.group(1)), "NEVER_SHIP must refuse a jar that carries " + ENTRY);
    }
}
