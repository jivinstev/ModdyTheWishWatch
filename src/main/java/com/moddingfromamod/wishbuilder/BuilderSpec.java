package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>A Wish Builder make, as data</b> (B0a): what the player picked, in a versioned shape that a
 * pure {@link BuilderLowering} turns into the same MCP tool calls the wish worker makes
 * (docs/WISH_BUILDER_PLAN.md §2). Minecraft-free.
 *
 * <pre>{"builder":1, "kind":"creature", "from":"preset:pet_dragon", "scheme":"ocean", "size":2,
 *  "powers":["power:flies","ability:breath_cone"], "temper":"friendly", "count":1, "at":"me:front",
 *  "name":"Sparky", "opts":{…}}</pre>
 *
 * <p>Every chosen value is a CATALOGUE ID whose prefix names the catalogue it comes from
 * ({@link Catalogue}), so a spec can be checked against what the tools really take, and a stored spec
 * written by an older Builder is upgraded by {@link SpecMigrations}, never read loosely. The only free
 * text is {@code name}, swept like any wish text. {@code opts} carries the few kind-specific picks
 * (an event's size, a rule's trigger, an item's shape) as catalogue ids too.
 */
public record BuilderSpec(int version, Kind kind, String from, String scheme, int size,
                          List<String> powers, String temper, int count, String at, String name,
                          Map<String, String> opts) {

    /** The version this build writes. A stored spec of any other version goes through {@link SpecMigrations}. */
    public static final int CURRENT = 1;

    /**
     * The kinds on the Builder's first page — the wish classes a menu can make.: SUPPLIES (a bundle of a vanilla
     * thing) is last, so no older kind's tile moves. No version bump: an older reader refuses "supplies" by name
     * ({@link #read}: "unknown kind"), it never misreads it, and a supplies make is never kept on the shelf.
     */
    public enum Kind {
        CREATURE, RIDE, ITEM, MACHINE, MAGIC_BLOCK, BUILDING, PICTURE, EVENT, RULE, SUPPLIES;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind of(String id) {
            return id == null ? null : switch (id.toLowerCase(Locale.ROOT)) {
                case "creature" -> CREATURE;
                case "ride" -> RIDE;
                case "item" -> ITEM;
                case "machine" -> MACHINE;
                case "magic_block" -> MAGIC_BLOCK;
                case "building" -> BUILDING;
                case "picture" -> PICTURE;
                case "event" -> EVENT;
                case "rule" -> RULE;
                case "supplies" -> SUPPLIES;
                default -> null;
            };
        }
    }

    public BuilderSpec {
        powers = powers == null ? List.of() : List.copyOf(powers);
        opts = opts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(opts));
        from = from == null ? "" : from;
        scheme = scheme == null ? "" : scheme;
        temper = temper == null ? "" : temper;
        at = at == null ? "" : at;
        name = name == null ? "" : name.strip();
    }

    /**
     * Read a stored spec of ANY version: upgraded to {@link #CURRENT} first, then parsed strictly.
     * Throws {@link IllegalArgumentException} naming what is wrong rather than guessing — a spec is a
     * record of what a child chose, and a silent default would make something he did not pick.
     */
    public static BuilderSpec read(JsonObject stored) {
        JsonObject j = SpecMigrations.upgrade(stored);
        Kind kind = Kind.of(str(j, "kind"));
        if (kind == null) {
            throw new IllegalArgumentException("unknown kind '" + str(j, "kind") + "'");
        }
        List<String> powers = new ArrayList<>();
        if (j.has("powers") && j.get("powers").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("powers")) {
                powers.add(e.getAsString());
            }
        }
        Map<String, String> opts = new LinkedHashMap<>();
        if (j.has("opts") && j.get("opts").isJsonObject()) {
            for (var e : j.getAsJsonObject("opts").entrySet()) {
                opts.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new BuilderSpec(CURRENT, kind, str(j, "from"), str(j, "scheme"), num(j, "size", 2), powers,
                str(j, "temper"), num(j, "count", 1), str(j, "at"), str(j, "name"), opts);
    }

    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("builder", version);
        j.addProperty("kind", kind.id());
        j.addProperty("from", from);
        if (!scheme.isEmpty()) {
            j.addProperty("scheme", scheme);
        }
        j.addProperty("size", size);
        JsonArray p = new JsonArray();
        powers.forEach(p::add);
        j.add("powers", p);
        if (!temper.isEmpty()) {
            j.addProperty("temper", temper);
        }
        j.addProperty("count", count);
        if (!at.isEmpty()) {
            j.addProperty("at", at);
        }
        j.addProperty("name", name);
        if (!opts.isEmpty()) {
            JsonObject o = new JsonObject();
            opts.forEach(o::addProperty);
            j.add("opts", o);
        }
        return j;
    }

    /** This spec under another name (Moddy's, or a ready-made's own in a test that compares the two). */
    public BuilderSpec withName(String newName) {
        return new BuilderSpec(version, kind, from, scheme, size, powers, temper, count, at, newName, opts);
    }

    public String opt(String key) {
        return opts.getOrDefault(key, "");
    }

    static String str(JsonObject j, String k) {
        return j.has(k) && j.get(k).isJsonPrimitive() ? j.get(k).getAsString() : "";
    }

    static int num(JsonObject j, String k, int dflt) {
        if (!j.has(k) || !j.get(k).isJsonPrimitive()) {
            return dflt;
        }
        try {
            return Math.round(Float.parseFloat(j.get(k).getAsString().strip()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + k + "' is not a number: " + j.get(k));
        }
    }
}
