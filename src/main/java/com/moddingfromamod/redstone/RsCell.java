package com.moddingfromamod.redstone;

import java.util.Map;
import java.util.TreeMap;

/**
 * One block the planner wants in the world: where, which block, its properties, and what it is for. The role decides
 * how the tool treats what is there now (a {@link Role#CASING} cell keeps a solid block that is already in place; every
 * other role replaces it).
 */
public record RsCell(RsPos pos, String block, Map<String, String> props, Role role) {

    /** What a cell is for. */
    public enum Role {
        /** A moving part or a component of the mechanism (piston, torch, the door's own blocks). */
        PART,
        /** Wire, repeaters and the blocks they sit on. */
        CHANNEL,
        /** What the player uses: a lever, a button, a plate. */
        CONTROL,
        /** A solid block that hides or supports the mechanism; an existing solid block there is kept. */
        CASING,
        /** Must be air (a pocket the wire needs, or an opening). */
        CLEAR
    }

    public RsCell {
        props = props == null ? Map.of() : Map.copyOf(new TreeMap<>(props));
    }

    public static RsCell of(RsPos pos, String block, Role role, String... kv) {
        Map<String, String> p = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return new RsCell(pos, block, p, role);
    }

    /** What a container cell is filled with ({@code "minecraft:arrow*64;minecraft:cookie*16"}), or empty. */
    public String items() {
        return props.getOrDefault("_items", "");
    }

    /** The block state as a command would write it (keys starting with "_" are not block properties), e.g. {@code minecraft:repeater[delay=2,facing=east]}. */
    public String state() {
        Map<String, String> real = new TreeMap<>(props);
        real.keySet().removeIf(k -> k.startsWith("_"));
        if (real.isEmpty()) {
            return block;
        }
        StringBuilder sb = new StringBuilder(block).append('[');
        real.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
        sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }
}
