package com.moddingfromamod.builder;

import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * <b>A named place in a building where something goes</b> — the throne a boss sits on, the
 * roof a dragon perches on, the counter a shopkeeper stands behind, the gate a knight guards.
 *
 * <p>{@code dx/dy/dz} are relative to the building's anchor, exactly like a shape's offsets, and
 * name the block the creature's FEET stand in — one above the seat. {@code facing} is which way it
 * looks ({@code north|south|east|west}), or {@code ""} for "whichever way it was looking".
 *
 * <p>Pure — no Minecraft imports. The rule that turns one into a place in the world is
 * {@link BuildSpots}; the one that puts a creature on it is {@code vanilla.SpotSeat}.
 */
public record BuildSpot(String name, int dx, int dy, int dz, String facing) {

    /** A spot name: what {@code at: "castle:throne"} says after the colon. */
    public static final int NAME_MAX = 24;

    public BuildSpot {
        name = normalise(name);
        facing = facingOf(facing);
        dx = clamp(dx);
        dy = clamp(dy);
        dz = clamp(dz);
    }

    /** {@code "Throne Room"} → {@code throne_room}; anything but letters, digits and _ goes. */
    public static String normalise(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_')
                .replace('-', '_');
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() > NAME_MAX ? sb.substring(0, NAME_MAX) : sb.toString();
    }

    /** One of the four cardinal words, or {@code ""}. */
    static String facingOf(String f) {
        String t = f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "north", "south", "east", "west" -> t;
            case "n" -> "north";
            case "s" -> "south";
            case "e" -> "east";
            case "w" -> "west";
            default -> "";
        };
    }

    private static int clamp(int v) {
        return Math.max(-BuildShape.MAX_OFFSET - BuildShape.MAX_DIM,
                Math.min(BuildShape.MAX_OFFSET + BuildShape.MAX_DIM, v));
    }

    /** Minecraft yaw for this facing ({@code south} = 0), or null when it has none. */
    public Float yaw() {
        return switch (facing) {
            case "south" -> 0f;
            case "west" -> 90f;
            case "north" -> 180f;
            case "east" -> -90f;
            default -> null;
        };
    }

    public boolean isValid() {
        return !name.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("dx", dx);
        o.addProperty("dy", dy);
        o.addProperty("dz", dz);
        if (!facing.isEmpty()) {
            o.addProperty("facing", facing);
        }
        return o;
    }

    /** A spot as the agent or the store wrote it, or null when it has no name. */
    public static BuildSpot fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        BuildSpot s = new BuildSpot(str(o, "name"), num(o, "dx"), num(o, "dy"), num(o, "dz"),
                str(o, "facing"));
        return s.isValid() ? s : null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? (int) Math.round(o.get(k).getAsDouble()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
