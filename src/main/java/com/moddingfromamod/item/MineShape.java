package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>What one swing of a mine_area tool also breaks</b>. Pure: offsets from the block he
 * broke, in the order they go (nearest first), and the caps that keep a tool from being a quarry.
 *
 * <p>Four words: {@code 3x3} (a hammer: the face he hit, three by three), {@code 5x5} (a bigger one),
 * {@code vein} (every touching block of the same kind — a whole ore vein, a whole tree trunk) and
 * {@code hole} (a shovel that digs a round hole). The face is the plane square to the way he is
 * LOOKING, which is the face he hit for any swing he can make.
 */
public final class MineShape {

    private MineShape() {
    }

    public static final List<String> WORDS = List.of("3x3", "5x5", "vein", "hole");

    /** Most extra blocks one swing may take: a whole vein or a big tree, not a mountain. */
    public static final int MAX_EXTRA = 48;
    /** How far a vein may reach from the block he broke. */
    public static final int VEIN_REACH = 6;
    /** The hole's radius: a 5-wide bowl round the block he dug. */
    public static final int HOLE_RADIUS = 2;
    /** Blocks broken per tick, so a big swing never stalls the server (C16). */
    public static final int PER_TICK = 12;

    /** A child's word, or null. */
    public static String word(String raw) {
        if (raw == null) {
            return null;
        }
        String w = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('×', 'x');
        return switch (w) {
            case "3x3", "square", "hammer", "3" -> "3x3";
            case "5x5", "big_square", "5" -> "5x5";
            case "vein", "whole_vein", "veinmine", "tree", "connected" -> "vein";
            case "hole", "crater", "dig", "bowl" -> "hole";
            default -> null;
        };
    }

    /**
     * The square's offsets (excluding the block itself) in the plane square to {@code axis} (0 = he
     * looks along x, 1 = up/down, 2 = along z).
     */
    public static List<int[]> square(int size, int axis) {
        int h = size / 2;
        List<int[]> out = new ArrayList<>();
        for (int a = -h; a <= h; a++) {
            for (int b = -h; b <= h; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                out.add(switch (axis) {
                    case 0 -> new int[] {0, a, b};
                    case 1 -> new int[] {a, 0, b};
                    default -> new int[] {a, b, 0};
                });
            }
        }
        out.sort((p, q) -> Integer.compare(d2(p), d2(q)));
        return out;
    }

    /** A bowl round the dug block: everything within the radius at or below it. */
    public static List<int[]> hole() {
        List<int[]> out = new ArrayList<>();
        int r = HOLE_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= 0; y++) {
                for (int z = -r; z <= r; z++) {
                    int[] p = {x, y, z};
                    if ((x | y | z) != 0 && d2(p) <= r * r + 1) {
                        out.add(p);
                    }
                }
            }
        }
        out.sort((p, q) -> Integer.compare(d2(p), d2(q)));
        return out;
    }

    /** Which axis he is looking along, from his look vector: the biggest component wins. */
    public static int axis(double lx, double ly, double lz) {
        double ax = Math.abs(lx), ay = Math.abs(ly), az = Math.abs(lz);
        return ay >= ax && ay >= az ? 1 : ax >= az ? 0 : 2;
    }

    static int d2(int[] p) {
        return p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
    }
}
