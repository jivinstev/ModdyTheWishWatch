package com.moddingfromamod.builder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <b>Fences, bars and panes join up</b> (a grading film: a playground's fence stood as a
 * row of loose posts). The Builder places blocks without neighbour updates, so a fence, an iron bar or a glass pane
 * placed from a plain name ({@code minecraft:oak_fence}) never gets its {@code north/south/east/west} arms and stands
 * as a post. This gives each one the arms the game would: toward the same kind of block, a gate, or a full block
 * beside it. A placement rewrite like {@link BuildDoors}, over the same cells the plan draws; pure.
 * {@code fenceconnect=off} is the A/B seam.
 */
public final class BuildConnect {

    private BuildConnect() {
    }

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("moddingfromamod.fenceconnect"));
    }

    static boolean arms(String base) {
        return base.endsWith("_fence") || base.equals("iron_bars") || base.endsWith("glass_pane");
    }

    /** Does a fence/bar/pane reach toward this neighbour? */
    static boolean joins(String self, String other) {
        if (other == null) {
            return false;
        }
        String o = BuildSupport.base(other);
        if (arms(o)) {
            // wood fences join wood fences; bars and panes join each other; a nether brick fence only its own kind
            boolean selfBar = !self.endsWith("_fence");
            boolean otherBar = !o.endsWith("_fence");
            return selfBar == otherBar && (selfBar || self.equals("nether_brick_fence") == o.equals("nether_brick_fence"));
        }
        if (o.endsWith("_fence_gate")) {
            return self.endsWith("_fence");
        }
        if (o.endsWith("_slab") || o.endsWith("_stairs") || o.endsWith("_door") || o.endsWith("_trapdoor") || o.endsWith("_carpet")
                || o.endsWith("_leaves") || o.equals("ladder") || o.equals("chain") || o.endsWith("lantern") || o.endsWith("torch")
                || o.endsWith("_bed") || o.endsWith("_sign") || o.endsWith("_banner") || o.equals("water") || o.equals("lava")
                || o.endsWith("_wall")) {
            return false;
        }
        return BuildSupport.blocksBody(other);
    }

    /** The cells to place over the plan: each fence, bar or pane with its arms. Empty when switched off. */
    public static Map<Long, String> of(BuildPlan plan) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (!enabled() || plan == null || plan.shapes().isEmpty()) {
            return out;
        }
        Map<Long, String> c = BuildDoors.applied(plan, BuildDoors.of(plan));
        c.values().removeIf(Objects::isNull);
        return of(c);
    }

    public static Map<Long, String> of(Map<Long, String> c) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<Long, String> e : c.entrySet()) {
            String block = e.getValue();
            String base = BuildSupport.base(block);
            if (!arms(base) || block.contains("north=") || block.contains("east=")) {
                continue;   // not one of these, or drawn with its arms already
            }
            long k = e.getKey();
            int x = ComposedPlan.kx(k);
            int y = ComposedPlan.ky(k);
            int z = ComposedPlan.kz(k);
            boolean n = joins(base, c.get(ComposedPlan.key(x, y, z - 1)));
            boolean s = joins(base, c.get(ComposedPlan.key(x, y, z + 1)));
            boolean w = joins(base, c.get(ComposedPlan.key(x - 1, y, z)));
            boolean ea = joins(base, c.get(ComposedPlan.key(x + 1, y, z)));
            if (!(n || s || w || ea)) {
                continue;
            }
            String name = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
            String rest = block.contains("[") ? "," + block.substring(block.indexOf('[') + 1, block.length() - 1) : "";
            out.put(k, name + "[east=" + ea + ",north=" + n + ",south=" + s + ",west=" + w + rest + "]");
        }
        return out;
    }
}
