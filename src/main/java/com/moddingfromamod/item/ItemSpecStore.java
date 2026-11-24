package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What each wish item was MADE with — so the next wish can change one thing about it.</b>
 *
 * <p>This is the items class's {@code BuildRegistry}: the small, durable record of a thing's
 * design, kept apart from the thing itself. A building has had one since the beginning, which is
 * why {@code build_structure} can take a {@code start_from} and merge one shape into a castle.
 * Items had nothing of the sort, and the cost was measured rather than guessed.
 *
 * <h2>The bug this exists to end</h2>
 * {@code make_item} derived the item's identity from its own contents:
 *
 * <pre>featureId = slug(name) + "_" + join(effects)</pre>
 *
 * <p>So <i>"make it hurt way more"</i> — which the agent answers by renaming it {@code Zonkbolt
 * MEGA} — produced a <b>different feature id</b>, a different datapack, and therefore a second
 * item, with the first left untouched in the child's inventory. <i>"Can it set things on fire
 * too"</i> changed the effect list and produced a third. Measured over an eight-cell ladder matrix,
 * {@code refinements that LANDED} equalled {@code EXTRA items} <b>exactly</b>, in both arms: every
 * refinement that worked, worked by creating. A child who asked for one wand ended a five-rung
 * ladder holding three, and the middle one had lost a property the first one had.
 *
 * <p>You cannot edit a thing whose id is a function of its contents. So the id is stored here,
 * once, and never recomputed.
 *
 * <h2>Kept as a file, next to the wishes</h2>
 * Small (a few hundred bytes per item), read on the server thread, and it has to outlive a restart
 * because a refinement can come days later. Defensive on load in the way every store in this repo
 * learned to be: one damaged record is skipped, never fatal — a child must not lose every item
 * because one of them was written during a crash.
 *
 * <h2>Two files: what it IS, and what it LOOKS like</h2>
 * {@code item-specs.json} (v2) holds the design; {@code item-icons.json} ({@link ItemIconStore})
 * holds each design's {@link ItemLook}. This class writes both and joins them on load, so every
 * caller still sees one {@link Spec} with its {@code icon} and {@code colour}. The split is in the
 * STORAGE, which is what lets the icon revamp bump the icon schema without the design
 * store moving; see {@code docs/WISH_STORES.md} §8.
 */
public final class ItemSpecStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Plenty for a child, and a bound so a runaway agent cannot fill a disk. */
    private static final int MAX = 256;

    /** id -> the parameters it was made with. Insertion-ordered, so "the newest" is meaningful. */
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    /** When each design was made and last changed — what newest-first sorts on. */
    private static final com.moddingfromamod.store.ArtifactTimes.Book TIMES =
            new com.moddingfromamod.store.ArtifactTimes.Book();

    /** Each design's times before its last {@link #put}, so {@link #restore} puts them back too. */
    private static final Map<String, com.moddingfromamod.store.ArtifactTimes.Times> TIMES_BEFORE =
            new LinkedHashMap<>();

    /** When this design was made and last changed; {@code NONE} when nobody knows. */
    public static synchronized com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return TIMES.get(key(id));
    }

    private ItemSpecStore() {
    }

    /**
     * Everything {@code make_item} needs to render an item again.
     *
     * @param id       the STABLE id — the feature id, the datapack, and the item, for its whole life
     * @param name     what the child calls it; may change without changing {@code id}
     * @param effects  the catalogue effect ids, in order; the first one drives the shape
     * @param params   per-effect parameters, exactly as the agent sent them
     * @param variants the FAMILY — {name, colour} objects, exactly as the agent sent them, empty
     *                 when the item is a single thing rather than a set. Stored raw for the same
     *                 reason {@code params} is: this record's job is to hand the arguments back
     *                 unchanged so a refinement starts from what was actually asked for, and a
     *                 parsed-and-reserialised copy is one more thing that can drift from it.
     */
    public record Spec(String id, String name, String icon, String colour, String shape,
                       int count, List<String> effects, JsonObject params, JsonArray variants,
                       JsonObject properties, JsonArray parts, String baseItem, String ownerId) {

        /** Every design that predates the owner stamp. Empty means SHARED. */
        public Spec(String id, String name, String icon, String colour, String shape,
                    int count, List<String> effects, JsonObject params, JsonArray variants,
                    JsonObject properties, JsonArray parts, String baseItem) {
            this(id, name, icon, colour, shape, count, effects, params, variants, properties,
                    parts, baseItem, "");
        }

        public Spec {
            ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        }

        /** The same design, stamped for a child. */
        public Spec withOwner(String owner) {
            return new Spec(id, name, icon, colour, shape, count, effects, params, variants,
                    properties, parts, baseItem, owner);
        }

        /** Every design that predates the BASE_ITEM axis being remembered —. */
        public Spec(String id, String name, String icon, String colour, String shape,
                    int count, List<String> effects, JsonObject params, JsonArray variants,
                    JsonObject properties, JsonArray parts) {
            this(id, name, icon, colour, shape, count, effects, params, variants, properties,
                    parts, null);
        }

        /**
         * A single item, with no family — the shape every caller had.
         *
         * <p>Kept as a real constructor rather than made everyone pass an empty array, because the
         * overwhelming majority of items are one thing and reading {@code new JsonArray()} at forty
         * call sites teaches nothing.
         */
        public Spec(String id, String name, String icon, String colour, String shape,
                    int count, List<String> effects, JsonObject params) {
            this(id, name, icon, colour, shape, count, effects, params, new JsonArray());
        }

        /** A family with no static properties — every call site that predates the PROPERTIES axis. */
        public Spec(String id, String name, String icon, String colour, String shape,
                    int count, List<String> effects, JsonObject params, JsonArray variants) {
            this(id, name, icon, colour, shape, count, effects, params, variants, new JsonObject());
        }

        /** A design with properties but no parts — the call sites between the two axes. */
        public Spec(String id, String name, String icon, String colour, String shape,
                    int count, List<String> effects, JsonObject params, JsonArray variants,
                    JsonObject properties) {
            this(id, name, icon, colour, shape, count, effects, params, variants, properties,
                    new JsonArray());
        }

        /**
         * This spec with only the named fields replaced — the merge {@code start_from} performs.
         *
         * <p>Null means "not mentioned", and not-mentioned means <b>keep</b>. That is the same rule
         * the buildings lesson teaches for shapes, and it is the whole difference between a
         * refinement and a re-description: an agent that has to restate every field in order to
         * change one is an agent that will get one of them wrong.
         */
        public Spec with(String newName, String newIcon, String newColour, String newShape,
                         Integer newCount, List<String> newEffects, JsonObject newParams) {
            return new Spec(id,
                    newName == null ? name : newName,
                    newIcon == null ? icon : newIcon,
                    newColour == null ? colour : newColour,
                    newShape == null ? shape : newShape,
                    newCount == null ? count : newCount,
                    newEffects == null ? effects : newEffects,
                    newParams == null ? params : mergeParams(params, newParams),
                    variants,
                    properties,
                    parts,
                    baseItem,
                    ownerId);
        }

        /**
         * Per-effect parameters, merged one effect at a time.
         *
         * <p>Deliberately not a whole-object replace. <i>"Make it hurt way more"</i> arrives as
         * {@code {"hurt":{"HEARTS":"12"}}} on an item that also ignites, and replacing the object
         * would silently drop the burn's SECONDS — the item would still claim both effects and one
         * of them would quietly stop working, which is the failure no appearance check can see.
         */
        public static JsonObject mergeParams(JsonObject base, JsonObject over) {
            JsonObject out = base == null ? new JsonObject() : base.deepCopy();
            if (over == null) {
                return out;
            }
            for (String effect : over.keySet()) {
                if (over.get(effect).isJsonObject() && out.has(effect)
                        && out.get(effect).isJsonObject()) {
                    JsonObject merged = out.getAsJsonObject(effect).deepCopy();
                    for (String k : over.getAsJsonObject(effect).keySet()) {
                        merged.add(k, over.getAsJsonObject(effect).get(k));
                    }
                    out.add(effect, merged);
                } else {
                    out.add(effect, over.get(effect));
                }
            }
            return out;
        }

        /**
         * <b>The icon half of this design</b> — or null when it has none, which since the
         * split can happen: a design whose {@code item-icons.json} row was lost reads back without
         * one rather than not at all.
         */
        public ItemLook look() {
            return icon == null || icon.isBlank() ? null : new ItemLook(icon, colour);
        }

        /** This design wearing another look; a null look keeps the one it has. */
        public Spec withLook(ItemLook look) {
            if (look == null) {
                return this;
            }
            return new Spec(id, name, look.sprite(), look.tint(), shape, count, effects, params,
                    variants, properties, parts, baseItem, ownerId);
        }

        /**
         * <b>The JOINED record</b> — the design with its look inline as {@code icon}/{@code colour}.
         *
         * <p>This is the shape every design had up to {@code item-specs.json} v1 and the shape a
         * library {@code item.json} promoted before the split still has. It is no longer what the
         * store writes ({@link #designJson} is); it stays because it is the whole of a design in one
         * object, which is what a round-trip test and an export want.
         */
        JsonObject toJson() {
            JsonObject o = designJson();
            if (icon != null) {
                o.addProperty("icon", icon);
            }
            if (colour != null) {
                o.addProperty("colour", colour);
            }
            return o;
        }

        /**
         * <b>What {@code item-specs.json} v2 holds for this design: everything but its look.</b>
         * The look goes to {@code item-icons.json} ({@link ItemIconStore}) — that is the split.
         */
        JsonObject designJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("shape", shape);
            o.addProperty("count", count);
            o.add("effects", JsonParser.parseString(gson(effects)));
            o.add("params", params == null ? new JsonObject() : params);
            o.add("variants", variants == null ? new JsonArray() : variants);
            o.add("properties", properties == null ? new JsonObject() : properties);
            o.add("parts", parts == null ? new JsonArray() : parts);
            // WHAT IT IS BUILT ON. Absent for the overwhelming majority -- an item made from
            // scratch -- and written only when there is something to remember, so an old file
            // reads back identically and a new one does not grow a null for every item.
            if (baseItem != null && !baseItem.isBlank()) {
                o.addProperty("base_item", baseItem);
            }
            // Same rule again: only written when there is one, so a design from before owner stamps
            // round-trips byte for byte.
            com.moddingfromamod.marshal.ArtifactOwner.stamp(o, ownerId);
            return o;
        }

        private static String gson(List<String> xs) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < xs.size(); i++) {
                sb.append(i == 0 ? "" : ",").append('"').append(xs.get(i)).append('"');
            }
            return sb.append(']').toString();
        }

        /** @return the spec, or null when the record is damaged — never a half-built one. */
        public static Spec fromJson(JsonObject o) {
            try {
                List<String> fx = new ArrayList<>();
                for (var e : o.getAsJsonArray("effects")) {
                    fx.add(e.getAsString());
                }
                // The LOOK is optional since the icon split: a v2 design record carries none (it lives in
                // item-icons.json), and every older record carries it inline. Both read here, and
                // the store joins the icon file over the top.
                ItemLook inline = ItemLook.inline(o);
                Spec s = new Spec(o.get("id").getAsString(), o.get("name").getAsString(),
                        inline == null ? null : inline.sprite(),
                        inline == null ? null : inline.tint(),
                        o.get("shape").getAsString(), o.get("count").getAsInt(), fx,
                        o.has("params") && o.get("params").isJsonObject()
                                ? o.getAsJsonObject("params") : new JsonObject(),
                        // ABSENT and DAMAGED both mean "no family", and deliberately mean the same
                        // thing. Absent is every item written; damaged is a half-written
                        // record. Half a family is the worse of the two failures — it renders a give
                        // block missing members the child can count, with nothing saying why.
                        o.has("variants") && o.get("variants").isJsonArray()
                                ? o.getAsJsonArray("variants") : new JsonArray(),
                        // Same rule for the same reason: absent is every item written before the
                        // PROPERTIES axis, damaged is a half-written record, and both mean "no
                        // static properties" rather than losing the whole design.
                        o.has("properties") && o.get("properties").isJsonObject()
                                ? o.getAsJsonObject("properties") : new JsonObject(),
                        o.has("parts") && o.get("parts").isJsonArray()
                                ? o.getAsJsonArray("parts") : new JsonArray(),
                        o.has("base_item") && o.get("base_item").isJsonPrimitive()
                                ? o.get("base_item").getAsString() : null,
                        com.moddingfromamod.marshal.ArtifactOwner.of(o));
                // NO EFFECTS IS A REAL DESIGN WHEN THE SHAPE IS `plain` — a keepsake, or a
                // pickaxe that is just a very good pickaxe; make_item mints exactly that. This used
                // to refuse one as damaged, so every plain design was written perfectly and gone at
                // the next world load, and its card printed "—" four times (docs/WISH_STORES.md §7).
                // Any other shape with no effect still is damaged: it would run nothing — except a
                // magic_block, whose shape is complete with nothing to set off ("a new block
                // that no mod has"). Found 's rehearsal: the v3 converge re-saved a world and
                // dropped one such design that every load before had silently skipped.
                return s.id().isBlank() || (fx.isEmpty() && !"plain".equals(s.shape())
                        && !com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(s.shape())) ? null : s;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Remember what this item is made of. Replaces any earlier design under the same id.
     *
     * <p>The owner stamp goes on AT CREATION and never re-stamps an existing design — the
     * same rule {@code SpeciesRegistry.put} keeps, and for the same reason: unowned means SHARED,
     * and a refinement must not take another player's wand off him by touching it.
     */
    public static synchronized void put(Spec spec) {
        if (spec == null || spec.id() == null || spec.id().isBlank()) {
            return;
        }
        spec = turnInto(spec);   //: never store an old effect name, whoever hands one in
        Spec had = SPECS.get(key(spec.id()));
        if (spec.ownerId().isEmpty() || had != null) {
            String keep = had != null ? had.ownerId()
                    : com.moddingfromamod.agent.AgentContext.currentPlayerId();
            spec = spec.withOwner(keep);
        }
        SPECS.remove(key(spec.id()));
        SPECS.put(key(spec.id()), spec);
        if (had == null || !had.equals(spec)) {
            TIMES_BEFORE.put(key(spec.id()), TIMES.get(key(spec.id())));
            TIMES.touch(key(spec.id()), com.moddingfromamod.store.ShelfTimes.now());
        }
        while (SPECS.size() > MAX) {
            String oldest = SPECS.keySet().iterator().next();
            SPECS.remove(oldest);
            TIMES.forget(oldest);
        }
        save();
    }

    /**
     * Forget this design — the inverse of {@link #put}, and the reason items can be undone at all.
     *
     * <p>Without an inverse the wish could not record having made an item ({@code MadeKind} refuses
     * to record a kind nothing can reverse, because a kind recorded but never reversed makes the
     * {@code [undo]} button lie). See.
     *
     * @return true if there was something here to forget
     */
    public static synchronized boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        boolean had = SPECS.remove(key(id)) != null;
        TIMES.forget(key(id));
        if (had) {
            save();
        }
        return had;
    }

    /**
     * <b>Put back exactly what this store held for {@code id} before a {@link #put}</b> — the design
     * that was there, or nothing. A design is written BEFORE its pack builds, so a build that
     * then fails must take it back, or the store keeps a design no pack carries and the next
     * re-wish inherits it (the item-fork bug left such a ghost behind).
     *
     * @param before what {@link #own} said before the put; null means there was none
     */
    public static synchronized void restore(String id, Spec before) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (before == null) {
            remove(id);
            return;
        }
        SPECS.remove(key(id));
        SPECS.put(key(id), before);
        var times = TIMES_BEFORE.remove(key(id));
        if (times != null) {
            TIMES.set(key(id), times);   // the design came back, and so did when it last changed
        }
        save();
    }

    /** This store's OWN design for {@code id}, never the library's read-only one. */
    public static synchronized Spec own(String id) {
        return id == null ? null : SPECS.get(key(id));
    }

    /** The design behind this item, or null if nothing here made it. */
    public static synchronized Spec get(String id) {
        Spec own = SPECS.get(key(id));
        return own != null ? own : fromLibrary(key(id));
    }

    /**
     * <b>A curated item design this world has never made</b> — read out of
     * {@code <libraryDir>/items/<id>/item.json} on demand, read-only.
     *
     * <p>Not cached into {@code SPECS}, for the same reason {@code BuildRegistry} does not cache a
     * library plan: this map is what {@code save()} writes back, so caching would copy the owner's
     * design into the world's {@code items.json} at the next unrelated write.
     *
     * <p><b>What this gets you and what it does not.</b> An item's BEHAVIOUR is a datapack, and the
     * library serves that separately ({@code DatapackStore}); this is the DESIGN, which is what
     * {@code inspect_item} reads and what {@code start_from} needs so "another wand but green" edits
     * a curated wand instead of inventing a second one.
     */
    private static Spec fromLibrary(String id) {
        java.nio.file.Path f =
                com.moddingfromamod.library.WishLibrary.artifactFile("items", id, "item.json");
        if (f == null) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(java.nio.file.Files.readString(f))
                    .getAsJsonObject();
            Spec s = Spec.fromJson(o);
            // A curated item's look lives beside its design, in icon.json, so the icon
            // revamp can re-dress a library item without touching what it does. An item.json
            // promoted before the split carries its look inline and still reads.
            java.nio.file.Path lookFile =
                    com.moddingfromamod.library.WishLibrary.artifactFile("items", id, "icon.json");
            if (s != null && lookFile != null && java.nio.file.Files.isRegularFile(lookFile)) {
                s = s.withLook(ItemLook.fromJson(JsonParser
                        .parseString(java.nio.file.Files.readString(lookFile)).getAsJsonObject()));
            }
            return s;
        } catch (Exception e) {
            LOGGER.warn("could not read library item '{}' at {}: {}", id, f, e.toString());
            return null;
        }
    }

    /**
     * The design behind an item the child NAMED, matched the way he would say it.
     *
     * <p>The agent is given a `start_from` by id, but a child says "the zonkbolt", and the id and
     * the name drift apart the moment a refinement renames it. Matching on both is what keeps
     * "make the zonkbolt green" working after "make it MEGA".
     */
    public static synchronized Spec find(String idOrName) {
        Spec byId = get(idOrName);
        if (byId != null) {
            return byId;
        }
        String want = key(idOrName);
        if (want.isEmpty()) {
            return null;
        }
        Spec best = null;
        for (Spec s : SPECS.values()) {
            if (key(s.name()).equals(want) || key(s.name()).contains(want)
                    || key(s.id()).contains(want)) {
                best = s;                  // the LAST match: the newest thing by that name
            }
        }
        return best;
    }

    /** Every design, oldest first. */
    public static synchronized List<Spec> all() {
        return new ArrayList<>(SPECS.values());
    }

    public static synchronized int count() {
        return SPECS.size();
    }

    /** Test seam, and what a fresh world starts from. */
    public static synchronized void clear() {
        SPECS.clear();
        TIMES.clear();
        TIMES_BEFORE.clear();
    }

    /**
     * <b>, fixed: the designs live in their OWN file.</b> They used to share
     * {@code items.json} with {@link WishItemCatalogue}, and both whole-file overwrite — so
     * whichever saved last won and the other came back empty at the next world load, with nothing
     * reported. One writer per file is a structural guarantee; a merge would have been another rule
     * for both sides to remember.
     */
    private static java.nio.file.Path file() {
        return WorldPaths.wishesDir().resolve("item-specs.json");
    }

    /** Where the designs lived before the store envelope, shared with the catalogue. Read once, never written. */
    private static java.nio.file.Path legacyFile() {
        return WorldPaths.wishesDir().resolve("items.json");
    }

    /** The name this store owns in the envelope. */
    static final String STORE = "item-specs";

    /**
     * The newest schema this build reads and the one it writes.
     *
     * <ul>
     *   <li><b>v0</b> (unstamped) — pre-T9.1, in {@code items.json}, the look inline.</li>
     *   <li><b>v1</b>, {@code item-specs.json}, the look still inline as
     *       {@code icon}/{@code colour}.</li>
     *   <li><b>v2</b>, the DESIGN only; the look moved to {@code item-icons.json}
     *       ({@link ItemIconStore}).</li>
     *   <li><b>v3</b> —, {@code convert} and {@code change_block} are ONE effect,
     *       {@code turn_into}: a design's effects say {@code turn_into} and its parameters are
     *       FROM/TO/RADIUS (convert's INPUT/OUTPUT, change_block's BLOCK renamed, flat or grouped).
     *       Migrated on load ({@link #turnInto}). Bumped because a v2 jar reading a turn_into
     *       design would find an effect it has never heard of and refuse the item at the next edit;
     *       bumped, it refuses the FILE and writes nothing, the designed downgrade. The compiled
     *       packs are NOT rebuilt: an old item keeps doing exactly what it did (an old held convert
     *       stays bag-only) until he edits it, when it is made as turn_into.</li>
     * </ul>
     *
     * <p>⚠ <b>v2 had to be a bump, and not merely a quieter v1.</b> A v1 reader's
     * {@code Spec.fromJson} requires {@code icon}, so a v1 jar handed an un-bumped file of
     * icon-less records would have skipped every one as damaged, found nothing refused, and then
     * saved its empty map over the child's designs at the next {@code put} — an earlier issue's exact
     * mechanism. Bumped, a v1 jar REFUSES the file and latches its writes off, which is the
     * designed downgrade: nothing read, nothing lost.
     */
    static final int SCHEMA = 3;

    /**
     * A child's designs live on his shelf ({@code <players>/<uuid>/item-specs.json}) so a
     * wand he made in Creative is still his to "make another of" in Survival. Unowned designs stay
     * with the world. The world file keeps the latch; a child's shelf latches only him.
     */
    private static final com.moddingfromamod.store.PlayerShelf SHELF =
            new com.moddingfromamod.store.PlayerShelf(STORE, "item-specs.json", "specs",
                    com.moddingfromamod.store.PlayerShelf.byField("id"));

    static {
        com.moddingfromamod.store.PlayerShelves.register(STORE, SHELF, server -> load());
    }

    /**
     * Write both halves: the looks to {@code item-icons.json} FIRST, then the designs.
     *
     * <p>⚠ <b>The order is the migration's crash safety.</b> Converging an old file moves each look
     * out of {@code item-specs.json}; if the designs were written first and the icon write then
     * failed, the looks would exist nowhere. Icons first means a crash in between leaves the old
     * design file — looks inline — still on disk, and the next load simply migrates again.
     *
     * <p>⚠ And for the same reason <b>a latched icon store stops the design write too.</b> If
     * {@code item-icons.json} could not be read, the looks in memory may be the only copy (peeled
     * off an old design file); writing the design file as v2 would drop them from the one place
     * they are still readable.
     */
    private static synchronized void save() {
        if (!SHELF.mayWrite()) {
            // ⚠ This is the branch that stops the data loss. Refusing to READ an unreadable file is
            // useless on its own: the old code cleared the map, returned quietly, and the next
            // put() rewrote the whole file from nothing.
            LOGGER.error("NOT saving item designs — {} could not be read ({}). "
                    + "The file has been left alone on purpose; a grown-up should look at it.",
                    file(), SHELF.refusalReason());
            return;
        }
        if (!ItemIconStore.mayWrite()) {
            LOGGER.error("NOT saving item designs — their icons file {} could not be read, and "
                    + "writing the designs without it would lose the looks they still carry.",
                    ItemIconStore.where());
            return;
        }
        Map<String, ItemLook> looks = new LinkedHashMap<>();
        JsonArray specs = new JsonArray();
        for (Spec s : SPECS.values()) {
            specs.add(TIMES.stamp(key(s.id()), s.designJson()));
            ItemLook look = s.look();
            if (look != null) {
                looks.put(key(s.id()), look);
            }
        }
        if (!ItemIconStore.save(looks, owners())) {
            return;   // said why already; the designs stay as they were on disk
        }
        JsonObject payload = new JsonObject();
        payload.add("specs", specs);
        // Never let a failed write cost the child the item he is holding: the shelf logs and goes on.
        SHELF.write(WorldPaths.wishesDir(), com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                SCHEMA, payload, com.moddingfromamod.marshal.ArtifactOwner::of, LOGGER::warn);
    }

    /** Design id to owner — where each design's look goes. */
    private static Map<String, String> owners() {
        Map<String, String> out = new LinkedHashMap<>();
        SPECS.forEach((k, s) -> out.put(k, s.ownerId()));
        return out;
    }

    /** The designs and their looks, as {@link #save} would write them — the shelves' merge base. */
    private static void rememberBoth() {
        Map<String, ItemLook> looks = new LinkedHashMap<>();
        JsonArray specs = new JsonArray();
        for (Spec s : SPECS.values()) {
            specs.add(TIMES.stamp(key(s.id()), s.designJson()));
            if (s.look() != null) {
                looks.put(key(s.id()), s.look());
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("specs", specs);
        SHELF.remember(payload, com.moddingfromamod.marshal.ArtifactOwner::of);
        ItemIconStore.remember(looks, owners());
    }

    /**
     * Read the designs for this world, and join each one's look from {@code item-icons.json}. One
     * damaged record is skipped, never fatal — but a file that cannot be read AT ALL is a refusal,
     * and {@link #save()} is latched off after one.
     */
    public static synchronized void load() {
        SPECS.clear();
        TIMES.clear();
        TIMES_BEFORE.clear();
        Map<String, ItemLook> looks = ItemIconStore.load();
        WishStoreFile.Read read = SHELF.read(WorldPaths.wishesDir(),
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);

        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read item designs from {}: {}. Nothing will be saved over it.",
                    file(), r.reason());
            return;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s item designs: {}. Their shelf will not be written.", who, why));
        int version = SCHEMA;
        if (!(read instanceof WishStoreFile.Read.Absent)) {
            version = read instanceof WishStoreFile.Read.Ok ok ? ok.version()
                    : WishStoreFile.LEGACY_VERSION;
            JsonObject root = read instanceof WishStoreFile.Read.Ok ok ? ok.root()
                    : ((WishStoreFile.Read.Legacy) read).root();
            adopt(root, looks);
        }
        rememberBoth();
        // an earlier issue's backfill, after the base (SpeciesRegistry.load says why the order matters).
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(WorldPaths.wishesDir());
        for (String k : SPECS.keySet()) {
            if (TIMES.fill(k, known.timesOf(com.moddingfromamod.store.TimesBackfill.ITEM, k))) {
                filled++;
            }
        }
        if (SHELF.worldWasAbsent()) {
            // Store migration: before the split, the designs shared items.json with the catalogue.
            // Keyed on the WORLD file, not the merged read: since shelves landed, a world opened for the
            // first time can have this old file AND a child whose shelf is full from elsewhere.
            if (migrateFromLegacy(looks)) {
                return;
            }
        }
        if ((version < SCHEMA || SHELF.needsConverge() || filled > 0) && !SPECS.isEmpty()) {
            // Converge now rather than at the next unrelated put(), so the disk says
            // what the code means — the split looks, and each child's designs on his own shelf.
            LOGGER.info("converging item designs (v{} -> v{}, shelves: {})",
                    version, SCHEMA, SHELF.needsConverge());
            save();
        }
    }

    /**
     * ⚠ The migration reads {@code items.json} and takes only a {@code specs} key. Finding
     * {@code items} instead is <b>not</b> an error: that is the catalogue's own file, in the state
     * where the catalogue won the old race, and there is simply nothing here to recover.
     */
    private static boolean migrateFromLegacy(Map<String, ItemLook> looks) {
        String text = textOf(legacyFile());
        if (text == null) {
            return false;
        }
        WishStoreFile.Read legacy = WishStoreFile.read(text, STORE, SCHEMA);
        if (!(legacy instanceof WishStoreFile.Read.Legacy l) || !l.root().has("specs")) {
            return false;
        }
        LOGGER.info("migrating item designs out of {} into {} and {} (#585)",
                legacyFile(), file(), ItemIconStore.where());
        adopt(l.root(), looks);
        save();     // writes both new files; the legacy file is left for the catalogue
        return true;
    }

    /**
     * The {@code specs} array into memory, each design wearing its look.
     *
     * <p><b>Precedence: {@code item-icons.json} over an inline look.</b> A v2 design has no inline
     * look, so the icon file is the only source. A v0/v1 design carries one inline, and the icon
     * file has it too only when a previous migration wrote the icons and crashed before the designs
     * (see {@link #save}) — the same look either way. An icon row whose design is absent is simply
     * not joined, and drops at the next save.
     */
    private static void adopt(JsonObject root, Map<String, ItemLook> looks) {
        if (!root.has("specs") || !root.get("specs").isJsonArray()) {
            return;
        }
        int skipped = 0;
        for (var e : root.getAsJsonArray("specs")) {
            Spec s = e.isJsonObject() ? Spec.fromJson(e.getAsJsonObject()) : null;
            if (s == null) {
                skipped++;
                continue;
            }
            s = turnInto(s);
            SPECS.put(key(s.id()), s.withLook(looks.get(key(s.id()))));
            TIMES.read(key(s.id()), e.getAsJsonObject());
        }
        if (skipped > 0) {
            LOGGER.warn("skipped {} damaged item design(s)", skipped);
        }
    }

    /**
     * <b>: a design made with {@code convert} or {@code change_block} is a {@code turn_into}
     * design.</b> The effect is renamed and its parameters with it (INPUT/OUTPUT/BLOCK → FROM/TO,
     * flat or grouped; a convert off a machine gains the RADIUS its block side needs) — the same
     * rename {@code make_item} applies to an old-shaped call, so the next edit inherits a complete
     * design. Everything else, the id included, is untouched: the pack on disk is named by it.
     */
    static Spec turnInto(Spec s) {
        if (s.effects() == null || s.effects().stream()
                .noneMatch(com.moddingfromamod.datapack.TurnInto.OLD_IDS::contains)) {
            return s;
        }
        List<String> effects = com.moddingfromamod.datapack.TurnInto.canonicalEffects(s.effects());
        JsonObject params = s.params() == null ? new JsonObject() : s.params().deepCopy();
        com.moddingfromamod.datapack.TurnInto.normaliseParams(params, effects, s.shape());
        return new Spec(s.id(), s.name(), s.icon(), s.colour(), s.shape(), s.count(), effects, params,
                s.variants(), s.properties(), s.parts(), s.baseItem(), s.ownerId());
    }

    /** The file's text, or null when it is not there. */
    private static String textOf(java.nio.file.Path f) {
        try {
            return java.nio.file.Files.isReadable(f) ? java.nio.file.Files.readString(f) : null;
        } catch (Exception e) {
            LOGGER.warn("could not read {}: {}", f, e.toString());
            return null;
        }
    }
}
