package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>What a real mob DOES for the child</b>: follows him, is friendly to him, carries him, finds
 * things for him. (wave 1's {@code creature.behaviour} parts.)
 *
 * <p>The measured gap (E1, {@code docs/PARTS_COVERAGE.md}): 44% of creature wishes read "partly",
 * and the missing half is behaviour on a VANILLA mob. {@code COMPANION} and {@code FRIENDLY}
 * existed only for sculpted and defined species. So <i>"a snow golem pet that follows me"</i> or
 * <i>"a tiny blaze pet that never burns me"</i> had a body and no way to be a pet.
 *
 * <p>These ride on {@code modify_mob} as four more fields, so any mob, from any mod, can take them.
 * The replay a taught button runs ({@code wishsummon}) reads the same JSON, so a pet the agent made
 * can be taught as a button for free. Everything here is pure; {@link PetBehaviours} is the part
 * that moves mobs.
 *
 * <p><b>Null means "leave it alone"</b>, the same rule as every {@link MobTweak} field: sending
 * {@code {"follow": true}} to a mob that already finds diamonds must not stop it finding them.
 */
public record PetBehaviour(Boolean follow, Boolean friendly, Boolean rideable, String finds, Integer stay,
                           Long home) {

    /**
     * The parts this record IS, in the parts inventory ({@code creature.behaviour:<name>}). Read by
     * {@code tools/parts-coverage.py}.
     */
    public static final Set<String> PARTS = Set.of("follow_owner", "pacify", "rideable",
            "find_treasure", "stay");

    /** the farthest a staying mob may potter from its home spot. */
    public static final int MAX_STAY = 16;
    /** The value {@link #stay} holds when the agent let it go again ("it can wander now"). */
    public static final int STAY_OFF = -1;

    /** No behaviour at all. */
    public static final PetBehaviour NONE = new PetBehaviour(null, null, null, null, null, null);

    /** The four behaviours wave 1 had, before {@code stay}. */
    public PetBehaviour(Boolean follow, Boolean friendly, Boolean rideable, String finds) {
        this(follow, friendly, rideable, finds, null, null);
    }

    /** What "finds" means when the child names a thing rather than a block id. */
    private static final Map<String, String> FINDS = Map.ofEntries(
            Map.entry("diamond", "#minecraft:diamond_ores"),
            Map.entry("gold", "#minecraft:gold_ores"),
            Map.entry("iron", "#minecraft:iron_ores"),
            Map.entry("coal", "#minecraft:coal_ores"),
            Map.entry("emerald", "#minecraft:emerald_ores"),
            Map.entry("redstone", "#minecraft:redstone_ores"),
            Map.entry("lapis", "#minecraft:lapis_ores"),
            Map.entry("copper", "#minecraft:copper_ores"),
            Map.entry("ore", "#c:ores"),
            Map.entry("carrot", "minecraft:carrots"),
            Map.entry("potato", "minecraft:potatoes"),
            Map.entry("treasure", "minecraft:chest"),
            Map.entry("loot", "minecraft:chest"),
            Map.entry("chest", "minecraft:chest"),
            Map.entry("netherite", "minecraft:ancient_debris"),
            Map.entry("ancient_debris", "minecraft:ancient_debris"),
            Map.entry("amethyst", "minecraft:amethyst_cluster"),
            Map.entry("spawner", "minecraft:spawner"));

    /** Words that switch finding OFF. */
    private static final Set<String> NOTHING = Set.of("none", "nothing", "off", "stop", "no");

    /** The value {@link #finds} holds when the agent asked for finding to stop. */
    public static final String FINDS_OFF = "";

    public PetBehaviour {
        if (finds != null) {
            finds = resolveFinds(finds);
        }
    }

    /**
     * A thing to find, in its block id or block tag spelling: {@code "diamonds"} →
     * {@code #minecraft:diamond_ores}, {@code "carrots"} → {@code minecraft:carrots}, an id or a
     * {@code #tag} as it is, {@code "none"} → {@link #FINDS_OFF}.
     */
    public static String resolveFinds(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (t.isEmpty() || NOTHING.contains(t)) {
            return FINDS_OFF;
        }
        if (t.startsWith("#") || t.contains(":")) {
            return t.startsWith("#") && !t.contains(":") ? "#minecraft:" + t.substring(1) : t;
        }
        String singular = t.endsWith("es") && FINDS.containsKey(t.substring(0, t.length() - 2))
                ? t.substring(0, t.length() - 2)
                : t.endsWith("s") && FINDS.containsKey(t.substring(0, t.length() - 1))
                ? t.substring(0, t.length() - 1) : t;
        return FINDS.getOrDefault(singular, "minecraft:" + t);
    }

    /** Nothing asked for. */
    public boolean isEmpty() {
        return follow == null && friendly == null && rideable == null && finds == null && stay == null;
    }

    /** Anything switched ON, i.e. a mob that needs looking after every few ticks. */
    public boolean isActive() {
        return Boolean.TRUE.equals(follow) || Boolean.TRUE.equals(friendly)
                || Boolean.TRUE.equals(rideable) || (finds != null && !finds.isEmpty()) || stays();
    }

    /** It keeps to its home spot. */
    public boolean stays() {
        return stay != null && stay >= 0 && home != null;
    }

    /** It was asked to stay (a home is given to it when it is applied). */
    public boolean asksToStay() {
        return stay != null && stay >= 0;
    }

    /** This behaviour with its home spot set: where it stands when it is told to stay. */
    public PetBehaviour homedAt(long pos) {
        return new PetBehaviour(follow, friendly, rideable, finds, stay, pos);
    }

    /** This request laid over what the mob already does: null keeps the old value. */
    public PetBehaviour over(PetBehaviour current) {
        PetBehaviour c = current == null ? NONE : current;
        return new PetBehaviour(
                follow != null ? follow : c.follow,
                friendly != null ? friendly : c.friendly,
                rideable != null ? rideable : c.rideable,
                finds != null ? finds : c.finds,
                stay != null ? stay : c.stay,
                stay != null ? home : c.home);
    }

    /** In words, for the agent to tell the child exactly what the mob now does. */
    public List<String> summary() {
        List<String> parts = new ArrayList<>();
        if (follow != null) {
            parts.add(follow ? "follows him" : "stops following");
        }
        if (friendly != null) {
            parts.add(friendly ? "friendly (never attacks a player)" : "no longer friendly");
        }
        if (rideable != null) {
            parts.add(rideable ? "rideable (use it with a free hand or the watch to climb on; look where to go, walk forward to "
                    + "move, sneak to get off)" : "no longer rideable");
        }
        if (finds != null) {
            parts.add(finds.isEmpty() ? "stops looking for things" : "finds " + finds
                    + " nearby and shows him where");
        }
        if (stay != null) {
            parts.add(stay < 0 ? "free to wander again" : stay == 0
                    ? "stays right on its spot, and walks back if it is pushed or lured off it"
                    : "stays within " + stay + " blocks of its spot, and walks back if it is pushed or lured away");
        }
        return parts;
    }

    // ── JSON: the tool's fields, and the copy kept on the entity ────────────────────────────────

    /** The four {@code modify_mob} fields. Absent or unreadable → null (leave it alone). */
    public static PetBehaviour fromJson(JsonObject o) {
        if (o == null) {
            return NONE;
        }
        // The tool's fields never carry a home: a home is where the mob stands when it is told
        // to stay, so a replayed button stays where IT was summoned, not where the first one stood.
        return new PetBehaviour(bool(o, "follow"), bool(o, "friendly"), bool(o, "rideable"),
                o.has("finds") && o.get("finds").isJsonPrimitive() ? o.get("finds").getAsString() : null,
                stayOf(o), null);
    }

    /**
     * {@code stay}: true = right here (0), a number = how far it may potter (0 to {@link #MAX_STAY}),
     * false = free to wander again. Absent or unreadable = leave it alone.
     */
    static Integer stayOf(JsonObject o) {
        if (o == null || !o.has("stay") || !o.get("stay").isJsonPrimitive()) {
            return null;
        }
        var p = o.get("stay").getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean() ? 0 : STAY_OFF;
        }
        try {
            double d = p.getAsDouble();
            return d < 0 ? STAY_OFF : (int) Math.min(MAX_STAY, Math.round(d));
        } catch (RuntimeException e) {
            String t = p.getAsString().trim().toLowerCase(Locale.ROOT);
            return t.equals("true") ? Integer.valueOf(0) : t.equals("false") ? Integer.valueOf(STAY_OFF) : null;
        }
    }

    /** What is stored on the mob: only what is set. */
    public String toStored() {
        JsonObject o = new JsonObject();
        if (follow != null) {
            o.addProperty("follow", follow);
        }
        if (friendly != null) {
            o.addProperty("friendly", friendly);
        }
        if (rideable != null) {
            o.addProperty("rideable", rideable);
        }
        if (finds != null) {
            o.addProperty("finds", finds);
        }
        if (stay != null) {
            o.addProperty("stay", stay);
        }
        if (home != null) {
            o.addProperty("home", home);
        }
        return o.toString();
    }

    /** Read the copy kept on a mob back; anything unreadable is {@link #NONE}, never a guess. */
    public static PetBehaviour fromStored(String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            PetBehaviour b = fromJson(o);
            return o.has("home") && o.get("home").isJsonPrimitive() ? b.homedAt(o.get("home").getAsLong()) : b;
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    private static Boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
