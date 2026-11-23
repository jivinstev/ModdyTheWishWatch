package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A world's Wish Crafters</b> (step 5, docs/OPEN_DOOR_PLAN.md §2, §11.7) — the manifest's
 * {@code crafters} block, read from the bundled {@code /worlds/<id>.json} the way {@link WorldPeople} is.
 *
 * <pre>
 *   "crafters": {
 *     "open": false,
 *     "signup": "https://…/signin?join=isle&amp;from=game",
 *     "ranks": [ {"name": "Wish Crafter", "stars": 1},
 *                {"name": "Skilled Crafter", "stars": 2, "after": "&lt;advancement id&gt;"}, … ],
 *     "links": [ {"label": "…", "url": "https://…", "for": "crafters" | "everyone"}, … ],
 *     "magicianSlots": 5, "wakesPerDay": 40
 *   }
 * </pre>
 *
 * <ul>
 *   <li>{@code open} — the open door. While {@code false} nothing here changes anything: every player
 *       is a Wish Magician (everyone on an invite-only world is on its list) and nobody new may join. The flip
 *       (OPEN_DOOR_PLAN §9 step O7) sets it {@code true}: a player NOT on the world's list is then a
 *       <b>Wish Crafter</b> — ready-made wishes and the Builder, never the agent.
 *       {@code -Dmoddingfromamod.crafters.open=true|false} overrides it (a GameTest, a client test).</li>
 *   <li>{@code signup} — where "become a Wish Magician" goes.</li>
 *   <li>{@code ranks} — a Crafter's ranks, EARNED BY PLAYING: each after an advancement the world's goal
 *       list already tracks. The first has no {@code after}. A Magician is not a rank: it is an unlock.</li>
 *   <li>{@code links} — the pause menu's Server Links; {@code for: "crafters"} only to Crafters.</li>
 *   <li>{@code magicianSlots} — of {@code server.max_players}, the slots a Crafter may never take (step 6,
 *       {@code CrafterSlots}). {@code wakesPerDay} is read by a proxy that wakes the server.</li>
 * </ul>
 *
 * <p>Minecraft-free; {@link #parse} is the Gate A surface ({@code WorldCraftersTest}).
 */
public record WorldCrafters(boolean open, String signup, List<Rank> ranks, List<Link> links, int magicianSlots,
                            int islandDays) {

    /** One rank: its name, its stars, and the advancement that earns it ({@code ""} for the first). */
    public record Rank(String name, int stars, String after) {
    }

    /** One pause-menu link; {@code crafters} = only a Crafter is sent it. */
    public record Link(String label, String url, boolean crafters) {
    }

    /** Of the world's max_players, how many a Crafter may never take, so a Magician always fits (5 of 20). */
    public static final int DEFAULT_MAGICIAN_SLOTS = 5;

    /** How long a Crafter's island waits unseen before it rests (14 days). */
    public static final int DEFAULT_ISLAND_DAYS = 14;

    public static final WorldCrafters NONE = new WorldCrafters(false, "", List.of(), List.of(), DEFAULT_MAGICIAN_SLOTS,
            DEFAULT_ISLAND_DAYS);

    public static final String OPEN_PROPERTY = "moddingfromamod.crafters.open";

    private static final Map<String, WorldCrafters> CACHE = new ConcurrentHashMap<>();

    public WorldCrafters {
        signup = signup == null ? "" : signup;
        ranks = List.copyOf(ranks == null ? List.of() : ranks);
        links = List.copyOf(links == null ? List.of() : links);
        magicianSlots = Math.max(0, magicianSlots);
    }

    /** This world's block, the property winning for {@code open}. */
    /** Overrides {@code magicianSlots} (a GameTest that must fill every Crafter slot without 16 players). */
    public static final String MAGICIAN_SLOTS_PROPERTY = "moddingfromamod.crafters.magicianSlots";

    public static WorldCrafters current() {
        WorldCrafters base = forId(WorldFacts.manifestId());
        String o = System.getProperty(OPEN_PROPERTY);
        boolean open = "true".equals(o) || ("false".equals(o) ? false : base.open());
        int slots = base.magicianSlots();
        try {
            String s = System.getProperty(MAGICIAN_SLOTS_PROPERTY);
            if (s != null) {
                slots = Integer.parseInt(s.strip());
            }
        } catch (NumberFormatException e) {
            // a junk override is no override
        }
        return open == base.open() && slots == base.magicianSlots() ? base
                : new WorldCrafters(open, base.signup(), base.ranks(), base.links(), slots, base.islandDays());
    }

    public static WorldCrafters forId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return NONE;
        }
        return CACHE.computeIfAbsent(id, WorldCrafters::load);
    }

    private static WorldCrafters load(String id) {
        try {
            String text = WorldFacts.manifestText(id);   //: the core's jar or a level jar's
            return text == null ? NONE : parse(text);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /** A manifest's text → its Crafters. Anything malformed is {@link #NONE}-like: closed. Pure. */
    public static WorldCrafters parse(String json) {
        JsonObject c;
        try {
            JsonElement el = JsonParser.parseString(json == null ? "" : json);
            if (!el.isJsonObject() || !(el.getAsJsonObject().get("crafters") instanceof JsonObject o)) {
                return NONE;
            }
            c = o;
        } catch (RuntimeException e) {
            return NONE;
        }
        boolean open = c.get("open") != null && c.get("open").isJsonPrimitive()
                && c.get("open").getAsJsonPrimitive().isBoolean() && c.get("open").getAsBoolean();
        List<Rank> ranks = new ArrayList<>();
        if (c.get("ranks") instanceof JsonArray arr) {
            for (JsonElement r : arr) {
                if (r instanceof JsonObject ro && ro.has("name")) {
                    ranks.add(new Rank(str(ro, "name"), ro.has("stars") ? ro.get("stars").getAsInt() : ranks.size() + 1,
                            str(ro, "after")));
                }
            }
        }
        List<Link> links = new ArrayList<>();
        if (c.get("links") instanceof JsonArray arr) {
            for (JsonElement l : arr) {
                if (l instanceof JsonObject lo && lo.has("label") && lo.has("url")) {
                    links.add(new Link(str(lo, "label"), str(lo, "url"), "crafters".equals(str(lo, "for"))));
                }
            }
        }
        // "wizardSlots" is the key before the Magician rename: a config rendered earlier still says it.
        String slotsKey = c.has("magicianSlots") ? "magicianSlots" : "wizardSlots";
        int magicianSlots = c.get(slotsKey) != null && c.get(slotsKey).isJsonPrimitive()
                && c.get(slotsKey).getAsJsonPrimitive().isNumber() ? c.get(slotsKey).getAsInt()
                : DEFAULT_MAGICIAN_SLOTS;
        int islandDays = c.get("islandDays") != null && c.get("islandDays").isJsonPrimitive()
                && c.get("islandDays").getAsJsonPrimitive().isNumber() ? c.get("islandDays").getAsInt()
                : DEFAULT_ISLAND_DAYS;
        return new WorldCrafters(open, str(c, "signup"), ranks, links, magicianSlots, islandDays);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
