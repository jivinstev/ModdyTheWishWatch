package com.moddingfromamod.compat;

import java.util.List;

/**
 * <b>Block ids a newer Minecraft renamed</b>, one {@link Row} per rename with the Minecraft version that introduced it.
 * A later rename is one more row.
 *
 * <p>Shipped content is renamed at the source when a port lands ({@code .port/<mc>/renames.tsv});
 * {@code BlockRenamesTest} (Gate A) fails while any shipped resource still names an id a row in force here renamed, so
 * a fresh world never meets one. A player's files saved on an older Minecraft are NOT remapped on read: an upgrade
 * across versions is a known issue, not fixed here.
 *
 * <p>Pure (no Minecraft imports).
 */
public final class BlockRenames {

    private BlockRenames() {
    }

    /**
     * One rename: {@code from} no longer exists from Minecraft {@code since} on, and is {@code to} there. {@code item}:
     * the block's item was renamed the same way.
     */
    public record Row(String from, String to, String since, boolean item) {
    }

    /** Every rename, oldest first. */
    public static final List<Row> ROWS = List.of(
            // 1.21.9: DataFixers schema 4541, "Rename chain to iron_chain" (block and item)
            new Row("minecraft:chain", "minecraft:iron_chain", "1.21.9", true));

    /** The rows in force on Minecraft {@code runningOn}: every rename introduced at or before it. */
    public static List<Row> inForce(String runningOn) {
        return ROWS.stream().filter(r -> compare(runningOn, r.since()) >= 0).toList();
    }

    /** Dotted Minecraft versions, numerically: {@code 1.21.10} is after {@code 1.21.9}; {@code 1.21} is {@code 1.21.0}. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }
}
