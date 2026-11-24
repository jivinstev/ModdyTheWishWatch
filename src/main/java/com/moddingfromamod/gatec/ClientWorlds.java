package com.moddingfromamod.gatec;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * <b>Creating a fresh client world, on every target</b>.
 *
 * <p>Thirteen client tests each carried the same block: a {@code new GameRules()} and a
 * {@code Function<RegistryAccess, WorldDimensions>} handed to {@code createFreshLevel}. 1.21.2
 * changed BOTH (javap on the two jars): {@code GameRules} needs the enabled {@code FeatureFlagSet},
 * and the dimensions function takes a {@code HolderLookup.Provider}. No shared spelling exists and no
 * rename row can carry either, so this is the migrator's section W7 in one class: the pieces that
 * differ live here, and each test keeps only what it actually varies (difficulty, flat or normal).
 * Its 1.21.1 half went with that build (branch parked/create-trains-mc1.21.1).
 */
public final class ClientWorlds {
    private ClientWorlds() {
    }

    /** Default game rules for a new world. */
    public static GameRules rules() {
        return new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures());
    }

    /**
     * Create and join a fresh world. {@code flat} is the vanilla FLAT preset (resolved through the
     * preset registry: WorldPresets has no flat helper), otherwise normal terrain.
     */
    public static void createFresh(Minecraft mc, String name, LevelSettings settings, boolean flat) {
        mc.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(),
                flat ? p -> p.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions()
                        : WorldPresets::createNormalWorldDimensions,
                new TitleScreen());
    }
    /**
     * Blocks of ground under a DEEP superflat studio: bedrock, then stone, dirt and grass up
     * to y=60 — about where a normal world's ground stands, so a building that digs down (the
     * underwater dome goes 31 blocks under its anchor) is built whole. The vanilla FLAT preset's
     * ground is at y=-60, four blocks above the bottom of the world, so every starter building that
     * reaches below its anchor was clipped at the void and photographed as grass.
     */
    public static final int DEEP_STONE = 120;

    /**
     * The FLAT preset (plains, no features) with {@link #DEEP_STONE} of stone under its grass instead
     * of two blocks of dirt, and no structures at all.
     */
    public static void createDeepFlat(Minecraft mc, String name, LevelSettings settings) {
        mc.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(),
                p -> {
                    var dims = p.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
                    var flat = ((net.minecraft.world.level.levelgen.FlatLevelSource) dims.overworld())
                            .settings();
                    var deep = flat.withBiomeAndLayers(java.util.List.of(
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1,
                                    net.minecraft.world.level.block.Blocks.BEDROCK),
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(DEEP_STONE,
                                    net.minecraft.world.level.block.Blocks.STONE),
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(3,
                                    net.minecraft.world.level.block.Blocks.DIRT),
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1,
                                    net.minecraft.world.level.block.Blocks.GRASS_BLOCK)),
                            // NO STRUCTURES. The FLAT preset carries villages, and a studio site
                            // in one photographed a village wall in front of the picture.
                            java.util.Optional.of(net.minecraft.core.HolderSet.direct()),
                            flat.getBiome());
                    return dims.replaceOverworldGenerator(p,
                            new net.minecraft.world.level.levelgen.FlatLevelSource(deep));
                },
                new TitleScreen());
    }
}
