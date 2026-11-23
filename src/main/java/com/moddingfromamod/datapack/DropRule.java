package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

import com.google.gson.JsonObject;

/**
 * <b>"Zombies drop diamonds", "trees drop apples way more often", "every chest has extra snacks"</b>
 * — one extra drop, added to what vanilla already drops. (the {@code loot_table} part.)
 *
 * <p>The data half of {@code change_drops}: parsed, clamped and matched here, with no Minecraft
 * imports, so every rule is unit-tested without a game. The game half is
 * {@link AddDropModifier}, a NeoForge global loot modifier that runs this rule's {@link #matches}
 * against the loot table being rolled.
 *
 * <p>Three decisions worth knowing:
 *
 * <ul>
 *   <li><b>It ADDS, never replaces.</b> A datapack that overwrites {@code entities/zombie} to add a
 *       diamond also deletes the rotten flesh, the iron and the carrots. A loot modifier adds on
 *       top of whatever the table already rolled, which is what "zombies drop diamonds" means.</li>
 *   <li><b>It matches on the TABLE ID, not on context parameters.</b> Reading a loot context's
 *       parameters is spelled differently on 1.21.4 ({@code getOptionalParameter}) and 1.21.1
 *       ({@code getParamOrNull}). The table id is the same on both: a zombie drops from
 *       {@code minecraft:entities/zombie} and stone from {@code minecraft:blocks/stone}. A tag
 *       ({@code #minecraft:leaves}) is answered by looking the block or entity up from that path.</li>
 *   <li><b>{@code source} is required and closed.</b> "Stone" is a block and "zombie" is a mob, and
 *       guessing which a bare word means is the kind of silent substitution this repo refuses
 *       everywhere else. The agent names the source; the tool never infers it.</li>
 * </ul>
 */
public record DropRule(String name, Source source, String which, String item, double chance, int count) {

    /** Where the drop comes from. */
    public enum Source {
        /** A mob dying. Its table is {@code <ns>:entities/<id>}. */
        MOB("entities/"),
        /** A block broken. Its table is {@code <ns>:blocks/<id>}. */
        BLOCK("blocks/"),
        /** A chest a structure generates. Its table is {@code <ns>:chests/<id>}. */
        CHEST("chests/"),
        /** Something fished up. The one table is {@code minecraft:gameplay/fishing}. */
        FISHING("gameplay/fishing");

        final String prefix;

        Source(String prefix) {
            this.prefix = prefix;
        }

        public static Source of(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "mob", "mobs", "entity", "entities", "animal", "creature" -> MOB;
                case "block", "blocks", "mining", "mine" -> BLOCK;
                case "chest", "chests", "loot_chest" -> CHEST;
                case "fishing", "fish" -> FISHING;
                default -> null;
            };
        }
    }

    /** Anything with this `which` matches every table of its source. */
    public static final String ANY = "any";

    public static final double MIN_CHANCE = 0.01;
    public static final double MAX_CHANCE = 1.0;
    public static final int MAX_COUNT = 64;

    public DropRule {
        chance = Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, chance));
        count = Math.max(1, Math.min(MAX_COUNT, count));
    }

    /**
     * Parse the tool's arguments. Returns the rule, or throws {@link IllegalArgumentException} whose
     * message is what the agent should be told — every refusal names the fix.
     */
    public static DropRule parse(JsonObject a) {
        List<String> problems = new ArrayList<>();
        Source source = Source.of(str(a, "source"));
        if (source == null) {
            problems.add("'source' must be one of: mob, block, chest, fishing (got '" + str(a, "source")
                    + "'). A zombie is a mob, stone is a block.");
        }
        String which = id(str(a, "which"));
        if (which.isEmpty() || which.equals("minecraft:any") || which.equals("minecraft:all")) {
            which = ANY;
        }
        if (source == Source.FISHING) {
            which = ANY;   // one fishing table; a `which` would only ever fail to match it
        }
        String item = id(str(a, "item"));
        if (item.isEmpty()) {
            problems.add("'item' is required: what extra thing drops, e.g. 'minecraft:diamond', or a "
                    + "tag like '#minecraft:wool' for a random one of those.");
        }
        double chance = num(a, "chance", 1.0);
        int count = (int) Math.round(num(a, "count", 1));
        String name = clean(str(a, "name"));
        if (name.isEmpty() && source != null && !item.isEmpty()) {
            name = clean(source.name().toLowerCase(Locale.ROOT) + "_" + bare(which) + "_" + bare(item));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        return new DropRule(name, source, which, item, chance, count);
    }

    /**
     * Does this rule apply to the loot table being rolled?
     *
     * @param tableId    the table's id, {@code namespace:path}
     * @param inTag      answers "is the entity or block this path names in this tag?" — the one
     *                   question that needs the game; called as {@code (tag, idFromPath)}
     */
    public boolean matches(String tableId, BiPredicate<String, String> inTag) {
        if (tableId == null) {
            return false;
        }
        int colon = tableId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : tableId.substring(0, colon);
        String path = colon < 0 ? tableId : tableId.substring(colon + 1);
        if (source == Source.FISHING) {
            // The top-level fishing table only; its fish/junk/treasure sub-tables are rolled
            // INSIDE it, and matching them too would add the drop twice to one catch.
            return path.equals(Source.FISHING.prefix);
        }
        if (!path.startsWith(source.prefix)) {
            return false;
        }
        String rest = path.substring(source.prefix.length());
        if (rest.isEmpty()) {
            return false;
        }
        // A sheep's wool colour is a sub-table (entities/sheep/white). The drop belongs to the
        // sheep, once, not to each colour table under it. Only MOBS nest that way: a village
        // chest's own table IS nested (chests/village/village_weaponsmith) and is the chest.
        if (source == Source.MOB && rest.contains("/")) {
            return false;
        }
        if (which.equals(ANY)) {
            return true;
        }
        String thing = ns + ":" + rest;
        if (which.startsWith("#")) {
            return inTag != null && inTag.test(which.substring(1), thing);
        }
        return which.equals(thing);
    }

    /** The loot modifier's JSON, exactly what {@link AddDropModifier}'s codec reads. */
    public JsonObject toModifierJson(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.add("conditions", new com.google.gson.JsonArray());
        o.addProperty("source", source.name().toLowerCase(Locale.ROOT));
        o.addProperty("which", which);
        o.addProperty("item", item);
        o.addProperty("chance", (float) chance);
        o.addProperty("count", count);
        return o;
    }

    /** In words, for the agent to tell the child exactly what now happens. */
    public String describe() {
        String what = count + " × " + item;
        String odds = chance >= 1.0 ? "every time" : Math.round(chance * 100) + "% of the time";
        String from = switch (source) {
            case MOB -> which.equals(ANY) ? "any mob dies" : "a " + bare(which) + " dies";
            case BLOCK -> which.equals(ANY) ? "any block is broken" : bare(which) + " is broken";
            case CHEST -> which.equals(ANY) ? "a new structure chest is opened"
                    : "a new " + bare(which) + " chest is opened";
            case FISHING -> "he catches something fishing";
        };
        return "When " + from + ", it also drops " + what + ", " + odds + ".";
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** "Zombie", "minecraft:zombie" and "#minecraft:leaves" in their one canonical spelling. */
    static String id(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (t.isEmpty()) {
            return "";
        }
        boolean tag = t.startsWith("#");
        if (tag) {
            t = t.substring(1);
        }
        if (!t.contains(":")) {
            t = "minecraft:" + t;
        }
        return (tag ? "#" : "") + t;
    }

    private static String bare(String id) {
        String t = id.startsWith("#") ? id.substring(1) : id;
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t.replace(':', '_');
    }

    /** A rule name in its one canonical spelling: lower case, a-z 0-9 and underscores. */
    public static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k, double dflt) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
