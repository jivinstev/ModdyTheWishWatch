package com.moddingfromamod.builder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <b>A way in from the water is level with the water</b>. A ladder shaft standing in
 * water (the underwater dome's) has exactly one right height for its rim: the top layer of the water, with open air
 * over the ladder. A rim below that floods the shaft; a rim above it is a block a swimmer cannot climb onto (filmed:
 * the dome as it shipped, its rim one block over the sea). Both are "off by one" and both have one answer, so the
 * engine sets it rather than advising:
 * <ul>
 *   <li>rim too high → the tube and its ladder are cut down to the water's top layer, and any lid over it is opened;</li>
 *   <li>rim too low → the tube and its ladder are carried up to it, in the tube's own block.</li>
 * </ul>
 * It acts only on a real shaft (a ladder of three rungs or more, walled on all eight sides for three layers, water
 * outside it, no ladder already up the outside), and only by {@link #MOST} blocks. A placement rewrite like
 * {@link BuildDoors}: placed over the plan, never saved into it. Pure. On by default; {@code waterentry=off} is the A/B seam.
 */
public final class BuildWaterEntry {

    private BuildWaterEntry() {
    }

    /** The most a rim is moved; further than this it is somebody's design, and is left. */
    public static final int MOST = 4;

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("moddingfromamod.waterentry"));
    }

    /** The cells to place over the plan (air included, as {@code minecraft:air}), and what was done. */
    public record Fixed(Map<Long, String> cells, int lowered, int raised) {
        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    /** The rewrite for this plan when switched on; empty otherwise. */
    public static Fixed fix(BuildPlan plan) {
        return enabled() ? compute(plan) : new Fixed(Map.of(), 0, 0);
    }

    public static Fixed compute(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return new Fixed(Map.of(), 0, 0);
        }
        Map<Long, String> c = BuildDoors.applied(plan, BuildDoors.of(plan));
        c.values().removeIf(Objects::isNull);
        return compute(c);
    }

    static boolean water(String b) {
        return b != null && BuildSupport.base(b).equals("water");
    }

    static boolean ladder(String b) {
        return b != null && BuildSupport.base(b).equals("ladder");
    }

    static boolean wall(String b) {
        return b != null && !water(b) && !ladder(b) && BuildSupport.blocksBody(b);
    }

    public static Fixed compute(Map<Long, String> c) {
        // ladder columns: (x,z) -> lowest and highest rung
        Map<Long, int[]> columns = new HashMap<>();
        for (Map.Entry<Long, String> e : c.entrySet()) {
            if (ladder(e.getValue())) {
                long k = e.getKey();
                int y = ComposedPlan.ky(k);
                columns.merge(ComposedPlan.key(ComposedPlan.kx(k), 0, ComposedPlan.kz(k)), new int[] {y, y},
                        (a, b) -> new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            }
        }
        Map<Long, String> out = new LinkedHashMap<>();
        int lowered = 0;
        int raised = 0;
        for (Map.Entry<Long, int[]> col : columns.entrySet()) {
            int x = ComposedPlan.kx(col.getKey());
            int z = ComposedPlan.kz(col.getKey());
            int top = col.getValue()[1];
            if (top - col.getValue()[0] < 2) {
                continue;
            }
            // The water outside the tube, two cells out: its top layer near the ladder's top.
            int surface = Integer.MIN_VALUE;
            boolean outsideLadder = false;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
                        continue;
                    }
                    for (int y = top + MOST; y >= top - MOST - 1; y--) {
                        String b = c.get(ComposedPlan.key(x + dx, y, z + dz));
                        outsideLadder |= ladder(b);
                        if (water(b) && c.get(ComposedPlan.key(x + dx, y + 1, z + dz)) == null) {
                            surface = Math.max(surface, y);
                        }
                    }
                }
            }
            if (surface == Integer.MIN_VALUE || outsideLadder) {
                continue;
            }
            // The rim: how high the tube stands, walled all round, found from just under the surface downward.
            int rim = surface - 1;
            while (rim > surface - 1 - MOST && !tube(c, x, rim, z)) {
                rim--;
            }
            // A SHAFT is walled for three layers down, not one: a deck or a floor round a ladder is not one (the archive's
            // lifeguard pool, whose chair ladder stands on the pool's deck, was given a collar by the one-layer test).
            if (!tube(c, x, rim, z) || !tube(c, x, rim - 1, z) || !tube(c, x, rim - 2, z)) {
                continue;
            }
            while (rim - surface < MOST + 1 && tube(c, x, rim + 1, z)) {
                rim++;
            }
            String rung = c.get(ComposedPlan.key(x, top, z));
            if (rim > surface && rim - surface <= MOST) {
                // Too high: cut the tube, its ladder and any lid down to the water's top layer.
                for (int y = surface + 1; y <= rim + 2; y++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long k = ComposedPlan.key(x + dx, y, z + dz);
                            if (c.get(k) != null && !water(c.get(k))) {
                                out.put(k, "minecraft:air");
                            }
                        }
                    }
                }
                lowered++;
            } else if (rim < surface && surface - rim <= MOST) {
                // Too low: carry the tube and the ladder up to it, so the water stays out.
                for (int y = rim + 1; y <= surface; y++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long k = ComposedPlan.key(x + dx, y, z + dz);
                            String below = c.get(ComposedPlan.key(x + dx, rim, z + dz));
                            out.put(k, dx == 0 && dz == 0 ? rung : below);
                        }
                    }
                }
                raised++;
            } else {
                continue;
            }
            // Either way: rungs up to the waterline and no higher, and open air over them.
            for (int y = top + 1; y <= surface; y++) {
                out.put(ComposedPlan.key(x, y, z), rung);
            }
            for (int y = surface + 1; y <= Math.max(top, surface + 2); y++) {
                long k = ComposedPlan.key(x, y, z);
                if (c.get(k) != null || out.containsKey(k)) {
                    out.put(k, "minecraft:air");
                }
            }
        }
        return new Fixed(out, lowered, raised);
    }

    /** Is the ladder's column walled on all eight sides at this height? */
    static boolean tube(Map<Long, String> c, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !wall(c.get(ComposedPlan.key(x + dx, y, z + dz)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The plan's blocks with this rewrite over them. */
    public static void apply(Map<Long, String> cells, Fixed fixed) {
        for (Map.Entry<Long, String> e : fixed.cells().entrySet()) {
            cells.put(e.getKey(), BuildSupport.isAir(e.getValue()) ? null : e.getValue());
        }
    }

    public static String note(Fixed f) {
        if (f == null || f.isEmpty()) {
            return "";
        }
        return " WATER ENTRY — the shaft's rim was " + (f.lowered() > 0 ? "above" : "below") + " the water, so I set it level with "
                + "it (" + (f.lowered() > 0 ? "a swimmer cannot climb a block out of the water" : "lower and the shaft floods")
                + "); the ladder ends at the waterline.";
    }
}
