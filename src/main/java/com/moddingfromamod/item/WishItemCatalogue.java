package com.moddingfromamod.item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.store.WishStoreFile;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Every THING a wish has ever handed the child, so it stays findable after he loses it.</b>
 *
 * <p>The gap this closes: creatures and things were not symmetrical, in one file. A wished-for
 * creature gets a spawn egg in the creative menu because {@link ModItems} enumerates
 * {@code SpeciesRegistry} — one egg per creature, refreshed mid-session. A wished-for <i>thing</i> got
 * nothing, because there was no list to enumerate: {@link ModItems} looped {@link WishIcons} instead
 * and put the <i>icon vocabulary</i> in the menu. So the creative menu held two dozen blank icons and
 * not one of the child's actual wishes.
 *
 * <p>What that cost, in the wish that produced this class: a BUTT CHEEK WATER SWORD existed only as
 * the single stack that was handed over. It was in his inventory and nowhere else — not searchable,
 * and one death or one misclick from being gone for good while the datapack driving it lived on. He
 * went looking for it under "E", which is exactly the right instinct, and it was not there.
 *
 * <p><b>The whole stack is stored, not a description of it.</b> Storing icon + name + colour and
 * rebuilding from those would put an item in the menu that <i>looks</i> right and does nothing — the
 * {@code WishOnUse} function, the attribute modifiers, the glint would all be dropped, which is the
 * same class of dishonesty as the missing entry. So each entry keeps the stack encoded through
 * {@link ItemStack#CODEC}: what comes out of the creative menu is the item he was given.
 *
 * <p><b>Strings only, deliberately.</b> Encoding needs a {@link HolderLookup.Provider} and both sides
 * need this list — the server to persist it, the client to build the menu from it. Rather than pass a
 * provider around (or hold decoded stacks built against the wrong side's registry copy — a mistake
 * with a nasty signature, a "Disconnected" kick and no crash report), entries hold JSON and are
 * decoded at the point of use, where a provider is genuinely available.
 *
 * <p>Persisted as JSON in the world folder next to {@code species.json}, so a wish travels with the
 * save and a grown-up can read it.
 */
public final class WishItemCatalogue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /**
     * Bounded, oldest evicted first. A child cannot wish 256 distinct things into being by accident,
     * so hitting this means something is recording noise; dropping the oldest keeps the menu usable
     * either way.
     */
    private static final int MAX_ENTRIES = 256;

    /** Insertion-ordered so eviction has a meaning and the creative menu has a stable order. */
    private static final Map<String, Entry> ENTRIES =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Things that could not be encoded, so we stop trying.
     *
     * <p>Without this the retry lives on the item-tick path: an unencodable stack is never added, so
     * it is never "known", so it is attempted again next tick — a warning per tick per stack, forever,
     * which buries the one line that mattered.
     */

    /** When each item tile was made and last changed — what newest-first sorts on. */
    private static final com.moddingfromamod.store.ArtifactTimes.Book TIMES =
            new com.moddingfromamod.store.ArtifactTimes.Book();

    /** When this item tile was made and last changed; {@code NONE} when nobody knows. */
    public static com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return TIMES.get(id);
    }

    private static final java.util.Set<String> UNENCODABLE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WishItemCatalogue() {
    }

    /**
     * One remembered thing.
     *
     * @param key       cheap identity — name + icon. See {@link #keyOf}.
     * @param name      the display name, for ordering and for logs
     * @param stackJson the whole stack, {@link ItemStack#CODEC}-encoded
     */
    public record Entry(String key, String name, String stackJson, String ownerId) {

        /**
         * Every item remembered before owner stamps, and every caller that does not know whose it is.
         * Empty means SHARED ({@link com.moddingfromamod.marshal.ArtifactOwner}).
         */
        public Entry(String key, String name, String stackJson) {
            this(key, name, stackJson, "");
        }

        public Entry {
            ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        }

        /** The same thing, stamped for a child. */
        public Entry withOwner(String owner) {
            return new Entry(key, name, stackJson, owner);
        }

        boolean isValid() {
            return key != null && !key.isBlank()
                    && name != null && !name.isBlank()
                    && stackJson != null && !stackJson.isBlank();
        }

        /** Public because the sync payload lives in another package and ships these over the wire. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("name", name);
            o.add("stack", JsonParser.parseString(stackJson));
            // Only when there is one, so an item remembered before owner stamps stays byte-identical.
            com.moddingfromamod.marshal.ArtifactOwner.stamp(o, ownerId);
            return o;
        }

        /** @return the entry, or null if this record is damaged — never a half-built one. */
        public static Entry fromJson(JsonObject o) {
            try {
                Entry e = new Entry(o.get("key").getAsString(), o.get("name").getAsString(),
                        o.get("stack").toString(),
                        com.moddingfromamod.marshal.ArtifactOwner.of(o));
                return e.isValid() ? e : null;
            } catch (RuntimeException ignored) {
                // One damaged entry must never cost the child every other wish, nor stop world load.
                return null;
            }
        }
    }

    /**
     * <b>The identity of a wished thing is what it LOOKS like plus what it DOES.</b>
     *
     * <p>Encoding the whole stack to compare it would be exact and is the wrong rule: it would treat
     * one more copy of the same sword — a second {@code /give}, a stack that picked up a durability
     * along the way — as a whole new thing, filling the menu with near-identical swords.
     *
     * <p>So: name, icon, and the {@code custom_data} that carries the behaviour
     * ({@code WishOnUse}, {@code WishThrowTag}, {@code WishPlaceTag}, {@code WishHitFunction}).
     * Name and icon alone were the first cut and are not enough now that {@code make_item} can
     * produce items cheaply: two things sharing a name and an icon but running different functions are
     * two different things to the child, and keying on appearance alone would silently drop the second
     * from the menu — the same disappearance this class exists to prevent.
     *
     * <p>Exactness here costs a string, so the caller throttles instead of hashing: see
     * {@link WishItem#inventoryTick}. A hash could collide and drop an item silently, which is the one
     * outcome not worth trading for.
     */
    static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        // MODDY'S WATCH IS NEVER ONE OF HIS THINGS. It is the reserved
        // thing pinned to slot one, like the Wish Bag — not a wish he made — and catalogued it became
        // a "Moddy" tile on his own watch (and on his profile), whose "Give me one" handed out a
        // second watch. No key means nothing records it, from the scan or any other door.
        if (com.moddingfromamod.wish.VanillaWatchItem.isWatch(stack)) {
            return "";
        }
        if (!stack.has(DataComponents.CUSTOM_NAME)) {
            // No name means this is a blank out of the icon library, not something a wish made. The
            // creative menu already offers those; recording them would bury the real wishes.
            return "";
        }
        // THE FEATURE ID, WHEN THE ITEM CARRIES ONE —, and it closes the hole the comment
        // below used to end on. Every item made since the identity component landed says which one
        // it is outright, so a PLAIN keepsake ("a trophy that just says CHAMPION") is now keyed as
        // stably as a wand: changing its icon updates the row it already had instead of minting a
        // second. Items made before it have no such tag and fall through to the rules below, which
        // is why this is a preference rather than a replacement.
        String made = WishItem.wishFeature(stack);
        if (!made.isBlank()) {
            return "id:" + made.toLowerCase(Locale.ROOT).trim();
        }

        // PREFER THE THING'S OWN ID —.
        //
        // The key used to be name + icon + custom_data, all three of which a REFINEMENT changes. So
        // "make the wand blue" or "give it a different icon" minted a second key and the child ended
        // up with one wand and two rows in his menu, the older one no longer doing what it says.
        //
        // An item's behaviour id is derived from its featureId, which is now stable for the item's
        // LIFE (deliberately, so a rename cannot produce a second item). Keying on it means an edit
        // updates the row it already had.
        String behaviour = WishItem.onUse(stack);
        if (behaviour.isEmpty()) {
            behaviour = WishItem.throwTag(stack);
        }
        if (behaviour.isEmpty()) {
            behaviour = WishItem.placeTag(stack);
        }
        if (!behaviour.isBlank()) {
            return "id:" + behaviour.toLowerCase(Locale.ROOT).trim();
        }

        // No behaviour and no feature tag — a plain keepsake made BEFORE, or a stack somebody
        // built by hand. Nothing stable to key on, so this stays the old identity and an icon
        // change on one of these still makes a second row. Left as it was rather than invented: a
        // world already has these in it, and guessing at their identity is worse than the old rule.
        CustomModelData icon = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        // ⚠ WITHOUT THE CARRIER MARK. It says which ROUTE minted the stack, which is metadata
        // about us and not a property of the thing the child was given — and this branch is the
        // identity of a plain keepsake, which has nothing else to be keyed on. Leave it in and the
        // same trophy has two identities either side of a route change, so it appears twice in his
        // menu and remove_item finds only one of them. Caught by PlainKeepsakeIdentityTest the
        // moment the route was switched on by default.
        net.minecraft.nbt.CompoundTag identity = data == null
                ? new net.minecraft.nbt.CompoundTag() : data.copyTag();
        identity.remove(WishItem.CARRIER);
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).trim()
                + "#" + (icon == null ? -1 : com.moddingfromamod.compat.ModelData.id(icon))
                + "#" + (data == null ? "" : identity);
    }

    /**
     * a catalogue key minted for Moddy's watch before {@link #keyOf} refused it — a plain
     * keepsake key ({@code name#model#nbt}) whose custom data carries the watch mark. Pure.
     */
    static boolean isWatchKey(String key) {
        return key != null && !key.startsWith("id:") && key.contains(WishItem.WATCH + ":1b");
    }

    /** Is this already remembered? The cheap guard that keeps {@link #remember} off the hot path. */
    public static boolean knows(ItemStack stack) {
        String key = keyOf(stack);
        return !key.isEmpty() && ENTRIES.containsKey(key);
    }

    /**
     * Remember a thing a wish handed over, and persist it.
     *
     * @return true if this was NEW — the caller uses that to decide whether to re-sync clients, so
     *         that the common case (an item sitting in an inventory, tick after tick) costs nothing
     *         but a map lookup.
     */
    public static boolean remember(MinecraftServer server, ItemStack stack) {
        // From a TOOL CALL, where the wish is bound to the calling worker and is therefore the
        // right one. The inventory scan has no such binding and passes the holder instead.
        return rememberGranted(server, stack,
                com.moddingfromamod.agent.AgentContext.currentPlayerId());
    }

    /**
     * <b>: whose an item is, without guessing from "the wish in flight".</b> The design's own
     * owner when there is a design (it was stamped at the grant, by the tool call that made it),
     * else the child holding it — never another child's wish that happens to be running, which is
     * how a scan with two wishes in flight, or none, filed his thing under another child or the world.
     */
    static String ownerFor(String designOwner, String holder) {
        String d = com.moddingfromamod.marshal.ArtifactOwner.normalise(designOwner);
        return d.isEmpty() ? com.moddingfromamod.marshal.ArtifactOwner.normalise(holder) : d;
    }

    /** The owner of the design behind this stack's {@code id:} key, or {@code ""}. */
    private static String designOwnerOf(String key) {
        if (key == null || !key.startsWith("id:")) {
            return "";
        }
        // a behaviour key ({@code <feature>:act}) names its design by its namespace.
        for (String id : ItemDesignId.candidates(key)) {
            ItemSpecStore.Spec spec = ItemSpecStore.own(id);
            if (spec != null) {
                return spec.ownerId();
            }
        }
        return "";
    }

    /**
     * <b>Record a thing a wish has just GRANTED</b> — from the tool call that made it, not
     * from the inventory scan, so it gets its tile, its "Give me one" and its creative entry even
     * when it never arrived (a full bag, a give that missed). Owned by the wisher.
     *
     * @return true if the catalogue changed
     */
    public static boolean rememberGranted(MinecraftServer server, ItemStack stack, String wisher) {
        boolean changed = remember(server, stack, wisher);
        // GRANTED again — back on his watch if he had hidden it. Here and not in the
        // inventory scan, which re-reads a hidden thing still in his bag tick after tick.
        String key = keyOf(stack);
        if (!key.isEmpty()) {
            com.moddingfromamod.wish.WatchHides.unhideSaved(wisher, key);
        }
        return changed;
    }

    /**
     * The door with the fallback owner named: the wisher at a grant, the child whose bag it is in
     * for the inventory scan. The design's own owner wins over either ({@link #ownerFor}).
     */
    public static boolean remember(MinecraftServer server, ItemStack stack, String holder) {
        String key = keyOf(stack);
        if (key.isEmpty() || UNENCODABLE.contains(key)) {
            return false;
        }
        String json = encode(stack.copyWithCount(1), server.registryAccess());
        if (json == null) {
            UNENCODABLE.add(key);
            return false;
        }
        // WHOSE IT IS. The DESIGN's owner when there is one — stamped at the grant
        // by the tool call that made it — else the child whose bag it is in. Never "the wish in
        // flight": with none running that filed it under the world, and with another child's
        // running, under HIM. An edit to an item already on file keeps the owner it had unless
        // its design says otherwise, because unowned means SHARED and a first refinement must not
        // quietly take another child's sword off him.
        String designOwner = designOwnerOf(key);
        String asker = ownerFor(designOwner, holder);
        Entry entry = new Entry(key, stack.getHoverName().getString(), json, asker);
        if (!entry.isValid()) {
            return false;
        }
        synchronized (ENTRIES) {
            Entry had = ENTRIES.get(key);
            if (had != null) {
                entry = entry.withOwner(designOwner.isEmpty() ? had.ownerId() : designOwner);
                // SAME ITEM, EDITED — replace it in place rather than skipping.
                //
                // Skipping was right while the key was name+icon+data, because an edit produced a
                // DIFFERENT key and therefore a new row. Now the key is the item's own stable id, so
                // skipping would keep the OLD stack under it: one row, showing a wand that no longer
                // matches the one in his hand. Unchanged stacks still cost a lookup and nothing else.
                if (had.stackJson().equals(json)) {
                    return false;
                }
                ENTRIES.put(key, entry);
                TIMES.touch(key, com.moddingfromamod.store.ShelfTimes.now());
                save(server);
                LOGGER.info("updated a wished item: {}", entry.name());
                return true;
            }
            while (ENTRIES.size() >= MAX_ENTRIES) {
                var it = ENTRIES.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                TIMES.forget(it.next());
                it.remove();
            }
            ENTRIES.put(key, entry);
            TIMES.touch(key, com.moddingfromamod.store.ShelfTimes.now());
        }
        save(server);
        LOGGER.info("remembered a wished item: {}", entry.name());
        return true;
    }

    public static List<Entry> all() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES.values());
        }
    }

    public static int count() {
        return ENTRIES.size();
    }

    /**
     * Forget one remembered item, by its key or its name — the catalogue half of undo.
     *
     * <p>Matched leniently on the name as well as the key, because the agent knows the item by what
     * the child calls it and the key is an implementation detail it never sees.
     *
     * @return how many entries were forgotten
     */
    public static int forget(String keyOrName) {
        if (keyOrName == null || keyOrName.isBlank()) {
            return 0;
        }
        String want = keyOrName.trim().toLowerCase(java.util.Locale.ROOT);
        synchronized (ENTRIES) {
            List<String> doomed = new ArrayList<>();
            for (Entry e : ENTRIES.values()) {
                String key = e.key() == null ? "" : e.key().toLowerCase(java.util.Locale.ROOT);
                String name = e.name() == null ? "" : e.name().toLowerCase(java.util.Locale.ROOT);
                if (key.equals(want) || name.equals(want) || key.contains(want)) {
                    doomed.add(e.key());
                }
            }
            doomed.forEach(ENTRIES::remove);
            doomed.forEach(TIMES::forget);
            return doomed.size();
        }
    }

    /**
     * <b>Find what a child means by a name he can actually say — the lookup behind "give me that one
     * again".</b>
     *
     * <p>Deliberately returns every match rather than a best guess. A key is
     * {@code id:<feature>} for anything made and {@code name#icon#nbt} for a keepsake
     * made before it, so a child cannot be expected to type one — and two of his things may
     * genuinely share a name, which is the entire reason {@link #keyOf} is not the name. Handing
     * back the wrong sword silently is worse than saying which two you meant.
     *
     * <p>Precedence is exact key, then exact name, then a loose contains — and it stops at the
     * first tier that matches anything, so a thing called "wand" is never ambiguous with
     * "wand of fire" merely because the looser tier would have caught both.
     *
     * @return every entry at the tightest tier that matched; empty when nothing did
     */
    public static List<Entry> resolve(String keyOrName) {
        if (keyOrName == null || keyOrName.isBlank()) {
            return List.of();
        }
        synchronized (ENTRIES) {
            List<Entry> rows = new ArrayList<>();
            List<NameMatch.Candidate> candidates = new ArrayList<>();
            for (Entry e : ENTRIES.values()) {
                if (e == null || !e.isValid()) {
                    continue;
                }
                rows.add(e);
                candidates.add(new NameMatch.Candidate(e.key(), e.name()));
            }
            List<Entry> out = new ArrayList<>();
            for (int i : NameMatch.resolve(candidates, keyOrName)) {
                out.add(rows.get(i));
            }
            return List.copyOf(out);
        }
    }

    /** Replace the whole set — used by the client when a sync packet arrives. */
    public static void replaceAll(List<Entry> entries) {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            for (Entry e : entries) {
                if (e != null && e.isValid()) {
                    ENTRIES.put(e.key(), e);
                }
            }
        }
    }

    public static void clear() {
        ENTRIES.clear();
        TIMES.clear();
        UNENCODABLE.clear();
    }

    /**
     * <b>Run a test against an EMPTY catalogue, and put back whatever was there.</b>
     *
     * <p>A bare {@code clear()} in a GameTest wipes state that other GameTest classes read, and the
     * damage shows up as the neighbour going <i>green having asserted nothing</i> rather than red —
     * which is why {@code GateShardsTest.nothingWipesStateAnotherTestClassReads} fails the build for
     * it. It became a real hazard here the moment a second class started reading this store
     * ({@code VanillaCarrierAbilityGameTest}, which spans ticks while it waits for a once-a-second
     * sweep, so it is exposed for its whole life).
     *
     * <p>Same shape as {@code BuiltAbility.inIsolationForTest}, deliberately: a scoped swap rather
     * than a reset, so a test cannot leave the store emptier than it found it even if it throws.
     * {@code UNENCODABLE} is saved too — it is a negative cache, and losing it would make a later
     * test silently re-encode a stack an earlier one had already proved unencodable.
     */
    public static void inIsolationForTest(Runnable body) {
        java.util.Map<String, Entry> savedEntries;
        java.util.Set<String> savedUnencodable;
        synchronized (ENTRIES) {
            savedEntries = new java.util.LinkedHashMap<>(ENTRIES);
            savedUnencodable = new java.util.LinkedHashSet<>(UNENCODABLE);
            ENTRIES.clear();
            UNENCODABLE.clear();
        }
        ISOLATED.set(ISOLATED.get() + 1);
        try {
            body.run();
        } finally {
            ISOLATED.set(ISOLATED.get() - 1);
            synchronized (ENTRIES) {
                ENTRIES.clear();
                ENTRIES.putAll(savedEntries);
                UNENCODABLE.clear();
                UNENCODABLE.addAll(savedUnencodable);
            }
        }
    }

    /** Depth of the enclosing {@link #inIsolationForTest} scopes on this thread. */
    private static final ThreadLocal<Integer> ISOLATED = ThreadLocal.withInitial(() -> 0);

    /**
     * <b>Empty the in-memory catalogue MID-TEST, only inside an isolation scope.</b>
     *
     * <p>Exists for one shape of test and it is a real one: proving that {@link #load} re-reads
     * from the world folder means emptying memory first and asserting the count really went to
     * zero, so the reload cannot be confused with the entry simply still being there. That clear is
     * <i>load-bearing</i>, unlike the "start from empty" clear at the top of a test, which
     * {@link #inIsolationForTest} now does.
     *
     * <p><b>Why it throws rather than trusting the caller.</b> A bare {@code clear()} in a GameTest
     * is what {@code GateShardsTest.nothingWipesStateAnotherTestClassReads} fails the build for,
     * and rightly — it wipes state a neighbouring class reads, and the neighbour goes <i>green
     * having asserted nothing</i>. Inside an isolation scope the store is already saved and will be
     * put back, so emptying it is safe; outside one it is exactly the forbidden thing wearing a
     * different name. Guarding it structurally is what stops this becoming the loophole that
     * re-opens the rule.
     *
     * <p>Learned by breaking it: a blanket conversion of that test file stripped <i>every</i>
     * {@code clear()} from each method, including this one, and the test went red with "the
     * catalogue did not actually clear" — which is the assertion right after it doing its job.
     */
    public static void emptyWithinIsolationForTest() {
        if (ISOLATED.get() <= 0) {
            throw new IllegalStateException(
                    "emptyWithinIsolationForTest outside inIsolationForTest — that is a bare "
                            + "clear() of process-wide state under another name, and it is what "
                            + "GateShardsTest.nothingWipesStateAnotherTestClassReads forbids");
        }
        synchronized (ENTRIES) {
            ENTRIES.clear();
            UNENCODABLE.clear();
        }
    }

    // ── encoding ────────────────────────────────────────────────────────────────────────────────

    /** @return the stack as JSON, or null if it could not be encoded (never throws at the caller) */
    static String encode(ItemStack stack, HolderLookup.Provider provider) {
        try {
            return ItemStack.CODEC
                    .encodeStart(RegistryOps.create(JsonOps.INSTANCE, provider), stack)
                    .result()
                    .map(JsonElement::toString)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("could not encode a wished item: {}", e.toString());
            return null;
        }
    }

    /**
     * Rebuild an entry's stack.
     *
     * @return the stack, or {@link ItemStack#EMPTY} if this entry can no longer be read — which is the
     *         honest answer when, say, the item's mod is gone. The caller skips empties rather than
     *         putting a broken row in the creative menu.
     */
    public static ItemStack stack(Entry entry, HolderLookup.Provider provider) {
        if (entry == null || !entry.isValid() || provider == null) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.CODEC
                    .parse(RegistryOps.create(JsonOps.INSTANCE, provider),
                            JsonParser.parseString(entry.stackJson()))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            LOGGER.warn("could not rebuild the wished item '{}': {}", entry.name(), e.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * <b>What the stored stack itself says</b> — base item, enchantments, attack damage,
     * unbreakable, dye — for the item card ({@link com.moddingfromamod.marshal.StackVitals}). Null
     * for an empty stack.
     */
    public static com.moddingfromamod.marshal.StackVitals.Facts factsOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String base = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        var ench = stack.getOrDefault(DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        for (var en : ench.entrySet()) {
            en.getKey().unwrapKey().ifPresent(k ->
                    enchants.put(k.identifier().toString(), en.getIntValue()));
        }
        Double damage = null;
        var mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        double sum = 0;
        boolean any = false;
        for (var m : mods.modifiers()) {
            boolean attack = m.attribute().unwrapKey()
                    .map(k -> k.identifier().getPath().endsWith("attack_damage")).orElse(false);
            if (attack && m.modifier().operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                sum += m.modifier().amount();
                any = true;
            }
        }
        if (any) {
            damage = 1.0 + sum;   // a player's own base damage is 1, and the modifier adds to it
        }
        var dyed = stack.get(DataComponents.DYED_COLOR);
        return new com.moddingfromamod.marshal.StackVitals.Facts(base, WishItem.isOurCarrier(stack),
                enchants, stack.has(DataComponents.UNBREAKABLE), damage,
                dyed == null ? null : dyed.rgb());
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("items.json");
    }

    /** The name this store owns in the envelope, and the newest schema it can read. */
    private static final String STORE = "items";
    private static final int SCHEMA = 1;

    /**
     * The things handed to a child are remembered on his shelf ({@code <players>/<uuid>/
     * items.json}), so his creative-menu search finds them in Survival too. Keyed exactly on
     * {@code key} (name + icon), case and all, because that is the identity {@link #keyOf} mints.
     */
    private static final com.moddingfromamod.store.PlayerShelf SHELF =
            new com.moddingfromamod.store.PlayerShelf(STORE, "items.json", "items", o -> {
                JsonElement k = o.get("key");
                return k != null && k.isJsonPrimitive() ? k.getAsString() : "";
            });

    static {
        com.moddingfromamod.store.PlayerShelves.register(STORE, SHELF, WishItemCatalogue::load);
    }

    private static Path wishesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes");
    }

    private static JsonObject payload() {
        JsonArray arr = new JsonArray();
        for (Entry e : all()) {
            arr.add(TIMES.stamp(e.key(), e.toJson()));
        }
        JsonObject payload = new JsonObject();
        payload.add("items", arr);
        return payload;
    }

    private static synchronized void save(MinecraftServer server) {
        Path p = file(server);
        if (!SHELF.mayWrite()) {
            // ⚠ The branch that stops the data loss. Before the store envelope a file that could not be read left
            // ENTRIES cleared and returned quietly, and the next remember() rewrote the whole file
            // from the empty map — silently, permanently.
            LOGGER.error("NOT saving wished items — {} could not be read ({}). "
                    + "The file has been left alone on purpose; a grown-up should look at it.",
                    p, SHELF.refusalReason());
            return;
        }
        // Losing the write is bad; taking the game down over it is worse — the shelf logs and goes on.
        SHELF.write(wishesDir(server), com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                SCHEMA, payload(), com.moddingfromamod.marshal.ArtifactOwner::of, LOGGER::warn);
    }

    /** Load this world's remembered things, and every child's. Never stops the world loading. */
    public static synchronized void load(MinecraftServer server) {
        ENTRIES.clear();
        UNENCODABLE.clear();
        TIMES.clear();
        Path p = file(server);
        WishStoreFile.Read read = SHELF.read(wishesDir(server),
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read wished items from {}: {}. Nothing will be saved over it.",
                    p, r.reason());
            return;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s wished items: {}. Their shelf will not be written.", who, why));
        if (!(read instanceof WishStoreFile.Read.Absent)) {
            try {
                JsonObject root = read instanceof WishStoreFile.Read.Ok ok ? ok.root()
                        : ((WishStoreFile.Read.Legacy) read).root();
                JsonElement arr = root.get("items");
                // ⚠ Legacy items.json may hold the DESIGNS instead (before the split). That
                // is ItemSpecStore's to migrate, and nothing here is lost by leaving it alone.
                if (arr != null && arr.isJsonArray()) {
                    int bad = 0;
                    int watches = 0;
                    for (JsonElement el : arr.getAsJsonArray()) {
                        Entry e = el.isJsonObject() ? Entry.fromJson(el.getAsJsonObject()) : null;
                        if (e == null) {
                            bad++;
                        } else if (isWatchKey(e.key())) {
                            watches++;            //: a row from before the watch was refused
                        } else {
                            ENTRIES.put(e.key(), e);
                            TIMES.read(e.key(), el.getAsJsonObject());
                        }
                    }
                    if (bad > 0) {
                        LOGGER.warn("skipped {} unreadable wished item(s)", bad);
                    }
                    if (watches > 0) {
                        LOGGER.info("dropped {} catalogued Moddy watch row(s) (#1551)", watches);
                    }
                }
            } catch (RuntimeException e) {
                // The read itself is done above; anything here is a damaged RECORD inside a file
                // that parsed, which is skipped rather than refused.
                LOGGER.warn("could not read wished items at {}: {}", p, e.toString());
            }
        }
        SHELF.remember(payload(), com.moddingfromamod.marshal.ArtifactOwner::of);
        // an earlier issue's backfill, after the base (SpeciesRegistry.load says why the order matters).
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(wishesDir(server));
        for (String key : new ArrayList<>(ENTRIES.keySet())) {
            if (TIMES.fill(key, known.timesOf(com.moddingfromamod.store.TimesBackfill.ITEM, key))) {
                filled++;
            }
        }
        if (filled > 0 || SHELF.needsConverge()) {
            save(server);   // a pre-T9.1b world: each child's things onto his own shelf, now
        }
    }
}
