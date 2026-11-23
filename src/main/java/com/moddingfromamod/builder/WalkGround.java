package com.moddingfromamod.builder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>The ground the walk-through stands a building on</b>: the Builder presets it walks, and flat
 * grass, a hill, a forest or the sea to stand them on. Shared by {@code WalkThroughGameTest} (Gate B) and the
 * walk-through films ({@code client/test/WalkFilmClientTest}, {@code walkterrain=hill|forest}); it lives outside the
 * GameTest because GameTests never ship and the film's client test does.
 */
public final class WalkGround {

    public static final List<String> PRESETS = List.of("cozy_house", "farm", "hedge_maze", "jungle_temple", "lighthouse",
            "mansion", "pirate_ship", "playground", "red_barn", "small_village", "stone_well", "underground_base",
            "village_shop", "wizard_tower");

    public enum Terrain { FLAT, HILL, FOREST, WATER }

    public static final int GROUND_Y = 120;
    public static final int PAD = 12;

    private WalkGround() {
    }

    public static BuildPlan preset(String id) {
        String path = "/builder-library/buildings/" + id + "/build.json";
        try (InputStream in = WalkGround.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return BuildPlan.fromJson(JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The ground this test stands a building on, over {@code x0..x1 × z0..z1} around {@link #GROUND_Y}: flat
     * grass, a hill rising 1 in 3 along x from {@code x0}, a forest of oaks on a 6-block grid, or the sea.
     * Public because the walk-through films lay the same ground ({@code walkterrain=hill|forest}).
     */
    public static void paintGround(ServerLevel level, Terrain terrain, int x0, int x1, int z0, int z1, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int top = surface(terrain, x - x0);
                for (int y = GROUND_Y - 30; y <= GROUND_Y + 40; y++) {
                    BlockState st;
                    if (y < top - 3) {
                        st = Blocks.STONE.defaultBlockState();
                    } else if (y < top) {
                        st = Blocks.DIRT.defaultBlockState();
                    } else if (y == top) {
                        st = terrain == Terrain.WATER ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                    } else if (terrain == Terrain.WATER && y < GROUND_Y) {
                        st = Blocks.WATER.defaultBlockState();
                    } else {
                        st = Blocks.AIR.defaultBlockState();
                    }
                    level.setBlock(new BlockPos(x, y, z), st, 2);
                }
                if (terrain == Terrain.FOREST && Math.floorMod(x, 6) == 0 && Math.floorMod(z, 6) == 3
                        && rnd.nextInt(3) > 0) {
                    tree(level, x, top + 1, z);
                }
            }
        }
    }

    /** The top block of the ground at a column, {@code dx} blocks from the region's west edge. */
    public static int surface(Terrain terrain, int dx) {
        return switch (terrain) {
            case HILL -> GROUND_Y - 1 + Math.min(14, Math.max(0, dx / 3));
            case WATER -> GROUND_Y - 7;
            default -> GROUND_Y - 1;
        };
    }

    private static void tree(ServerLevel level, int x, int y, int z) {
        for (int dy = 0; dy < 5; dy++) {
            level.setBlock(new BlockPos(x, y + dy, z), Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
                    }
                }
            }
        }
    }
}
