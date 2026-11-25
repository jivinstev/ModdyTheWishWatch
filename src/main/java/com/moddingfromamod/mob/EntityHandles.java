package com.moddingfromamod.mob;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Stable, readable names for the things a wish has created — so "make it bigger" stops being a
 * guess.
 *
 * <p><b>The problem this solves.</b> When a child says "make it bigger", "it" has to resolve to
 * something. Without a record of what was just created, the only available answer is <i>the nearest
 * mob</i> — which is a heuristic, and a bad one: a cow that wanders closer than the boss wins, and
 * the child watches the wrong thing grow. Worse, the offline template path creates mobs too, so an
 * agent could be asked about something it never saw created.
 *
 * <p>So every creation path registers what it made and gets back a handle like {@code @wither_storm}.
 * The agent can then say exactly which entity it means, and the lookup is a UUID, not a distance
 * sort. Names come from the entity's own id, so they read the way the child talks.
 *
 * <h2>It outlives the server, and that is a change </h2>
 *
 * <p>This was once deliberately in memory only, on the reasoning that the agent's context resets per
 * run too. That reasoning does not survive contact with a child: he goes to lunch, the game is
 * closed, and in the evening he says <i>"make him even bigger"</i> about the same animal. So the
 * table is written to {@code wishes/mobs.json} through {@link MobHandleStore} and loaded again with
 * the world. It stays pure — no Minecraft imports — by handing the file work to that class and
 * notifying it through {@link #onChange}.
 *
 * <h2>Created is not the same as talked about</h2>
 *
 * <p>A handle used to record only that something was made. But the majority of the mobs a child
 * points at were never made by a wish at all — <i>"make that cow huge"</i> is about a cow that was
 * already standing there — so a table of creations cannot answer "which one did the last wish mean".
 * {@link #targeted} is the other half: <b>acting on a mob makes it addressable</b>, whoever created
 * it, and {@link #referent()} is then the one the conversation is currently about.
 *
 * <p><b>Why that has to live here rather than on the worker.</b> A pool of granters serves one child
 * and a wish routinely lands on a worker that has never seen the animal;
 * {@code ArtifactAffinity} is only a routing preference and expires to "anyone". Anything remembering
 * the referent on the worker that did it is broken by construction.
 *
 * <p>Pure — no Minecraft imports, entities are just UUIDs and id strings — so naming, collision
 * handling and eviction are unit-tested without booting a game.
 */
public final class EntityHandles {

    /**
     * How many creations to remember. Comfortably more than a play session's worth of "the thing I
     * just made", while bounded so a chicken-rain wish can't fill memory with 60 handles.
     */
    public static final int MAX_HANDLES = 32;

    /** What the agent writes to mean "whatever I made most recently". */
    public static final String LAST = "@last";

    /**
     * How many changes to remember per mob.
     *
     * <p>Bounded for the same reason the table is: a ladder is a handful of rungs, and an agent in a
     * loop must not be able to grow one record without limit. The OLDEST is dropped, which costs the
     * ability to undo the earliest wish about a mob that has had eight — a fair trade against a
     * save file that grows forever.
     */
    public static final int MAX_TOUCHES = 8;

    /**
     * One remembered mob.
     *
     * @param createdMs      when a wish first learned about it
     * @param lastTargetedMs when a wish last <b>acted on</b> it, or 0 if never — this is what
     *                       {@link #referent()} sorts on, and the difference between "a thing that
     *                       exists" and "the thing he is talking about"
     */
    public record Handle(String name, UUID id, String typeId, String label,
                         long createdMs, long lastTargetedMs, List<Touch> history,
                         String ownerId) {

        public Handle {
            history = history == null ? List.of() : List.copyOf(history);
            ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        }

        /** Every handle written before the owner stamp. Empty means SHARED. */
        public Handle(String name, UUID id, String typeId, String label,
                      long createdMs, long lastTargetedMs, List<Touch> history) {
            this(name, id, typeId, label, createdMs, lastTargetedMs, history, "");
        }

        Handle(String name, UUID id, String typeId, String label, long createdMs,
               long lastTargetedMs) {
            this(name, id, typeId, label, createdMs, lastTargetedMs, List.of(), "");
        }

        Handle(String name, UUID id, String typeId, String label, long createdMs,
               long lastTargetedMs, String ownerId) {
            this(name, id, typeId, label, createdMs, lastTargetedMs, List.of(), ownerId);
        }

        /** The later of the two, i.e. how recently this handle mattered at all. */
        public long lastActiveMs() {
            return Math.max(createdMs, lastTargetedMs);
        }

        Handle with(long targetedMs, Touch touch) {
            List<Touch> next = new ArrayList<>(history);
            if (touch != null) {
                next.add(touch);
                while (next.size() > MAX_TOUCHES) {
                    next.remove(0);
                }
            }
            return new Handle(name, id, typeId, label, createdMs, targetedMs, next, ownerId);
        }
    }

    /**
     * One time a wish changed this mob.
     *
     * <p>The user's explicit ask: <i>on the second and third wishes, retain the history of
     * who was targeted.</i> It is also the only place an honest undo can come from — see
     * {@link MobState}.
     *
     * @param wishId  which wish did it, so an undo reverses only that one's work
     * @param summary what it changed, in the words the child was told
     * @param before  how the mob read immediately BEFORE this change
     */
    public record Touch(String wishId, String tool, long whenMs, String summary, MobState before) {

        public Touch {
            wishId = wishId == null ? "" : wishId.trim();
            tool = tool == null ? "" : tool.trim();
            summary = summary == null ? "" : summary.trim();
            before = before == null ? MobState.UNKNOWN : before;
        }
    }

    private static final EntityHandles GLOBAL = new EntityHandles();

    private final Map<String, Handle> byName = new HashMap<>();
    private final Deque<Handle> newestFirst = new ArrayDeque<>();
    private final Map<String, Integer> nameCounters = new HashMap<>();

    /**
     * Told whenever the table changes, so the store can write it out.
     *
     * <p>A callback rather than a call to the store, because this class must not import Minecraft —
     * that purity is what lets every naming, eviction and referent rule below be a unit test.
     */
    private Runnable onChange = () -> { };

    /** Overridable so a test can make "when" deterministic instead of racing the wall clock. */
    private LongSupplier clock = System::currentTimeMillis;

    public static EntityHandles global() {
        return GLOBAL;
    }

    /**
     * Remember something a wish just created.
     *
     * @param typeId the entity's registry id, e.g. {@code witherstormmod:wither_storm}
     * @param label  what to call it in prose, or null to use the type
     * @return the handle name, e.g. {@code @wither_storm} — or {@code @wither_storm2} if that name
     *         is already taken by something still remembered
     */
    public synchronized String remember(UUID id, String typeId, String label) {
        if (id == null) {
            return "";
        }
        id = live(id);
        String existing = handleFor(id);
        if (existing != null) {
            // Registering the same animal twice must not give it a SECOND name: an agent still
            // holding the first one would then be addressing a mob nothing else calls that any
            // more. Reachable now that targeted() also mints, and once a handle survives a reload.
            // The wish still claims it, or its own "@last" would skip a mob it genuinely made.
            claimForThisWish(existing);
            return existing;
        }
        String base = baseName(typeId);
        String name = uniqueName(base);
        // WHOSE ANIMAL THIS IS. The TABLE stays shared — a handle names something standing
        // in the world and any agent may legitimately address it (D3) — but "him" resolves to the
        // asker's own first, and that needs a stamp to sort on. "" for a gate or an unbound call,
        // which is SHARED and is the behaviour every existing world already has.
        Handle handle = new Handle(name, id, typeId == null ? "" : typeId,
                label == null || label.isBlank() ? base.replace('_', ' ') : label,
                clock.getAsLong(), 0L,
                com.moddingfromamod.agent.AgentContext.currentPlayerId());
        byName.put(name, handle);
        newestFirst.addFirst(handle);
        claimForThisWish(name);
        evictBeyondLimit();
        onChange.run();
        return name;
    }

    /**
     * Whose creation this was. The registry stays shared — a handle names something in the world and
     * every agent may legitimately address it — but {@code @last} must not be.
     */
    private void claimForThisWish(String name) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.rememberedHandle(name);
        }
    }

    /**
     * <b>A wish just acted on this mob.</b> Give it a handle if it has none, and record that it is
     * now what the conversation is about.
     *
     * <p>This is the half that makes "him" durable for a mob nobody created. Rung 2 says "make that
     * cow huge" and resolves it by walking to the nearest cow; rung 3 must not have to walk again,
     * because by then the child has moved and another cow is nearer. Calling this at the moment of
     * the change is what leaves a mark the next worker can read.
     *
     * @return the handle name it can be addressed by from now on
     */
    public synchronized String targeted(UUID id, String typeId, String label) {
        return targeted(id, typeId, label, null, null, null, null);
    }

    /**
     * The same, <b>recording who did it and what it was like before</b>.
     *
     * @param wishId which wish is doing this, so an undo can reverse only its own work
     * @param tool   the tool that did it, for the audit trail
     * @param summary what changed, in the words the child was told
     * @param before how the mob read immediately before the change
     */
    /**
     * <b>The body that is really there.</b>
     *
     * <p>A wished creature is manifested twice — it joins as a {@code wish_mob} and the rig bridge
     * replaces it with a rig on a vanilla carrier — and a caller minting a handle may be on either
     * side of that swap. {@link EntityReplacements} is deliberately Minecraft-free for this: the
     * purity note above is a rule, not a preference.
     * {@code DefineSpeciesTool} is on the far side: it spawns and remembers on the next line, so
     * asking here is the difference between a handle the agent can use and one that answers
     * <i>"it died or unloaded"</i> the moment it does.
     *
     * <p>Identity for anything that was never swapped, which is almost everything.
     */
    private static UUID live(UUID id) {
        return EntityReplacements.settled(id);
    }

    public synchronized String targeted(UUID id, String typeId, String label,
                                        String wishId, String tool, String summary,
                                        MobState before) {
        id = live(id);
        if (id == null) {
            return "";
        }
        String name = handleFor(id);
        if (name == null) {
            name = remember(id, typeId, label);
        }
        Handle h = byName.get(name);
        if (h == null) {
            return name;
        }
        long now = clock.getAsLong();
        Touch touch = wishId == null || wishId.isBlank()
                ? null
                : new Touch(wishId, tool, now, summary, before);
        Handle stamped = h.with(now, touch);
        byName.put(name, stamped);
        replaceInDeque(h, stamped);
        onChange.run();
        return name;
    }

    /**
     * <b>What to put this mob back to in order to undo one wish</b> — the state it was in before
     * that wish <i>first</i> touched it.
     *
     * <p>First, not last, and the difference is the whole point on a ladder. A wish that made him
     * ×2 and then ×4 must undo to ×1; reversing only the last change would leave the child with a
     * mob that is still twice the size he asked to undo.
     *
     * @return null when this wish never touched it, or when nothing was recorded
     */
    public synchronized MobState restorePointFor(String handleName, String wishId) {
        if (wishId == null || wishId.isBlank()) {
            return null;
        }
        Optional<Handle> h = resolve(handleName);
        if (h.isEmpty()) {
            return null;
        }
        String want = wishId.trim();
        for (Touch t : h.get().history()) {
            if (want.equals(t.wishId())) {
                return t.before().isEmpty() ? null : t.before();
            }
        }
        return null;
    }

    /**
     * <b>The mob the conversation is currently about</b>, or empty if no wish has acted on one.
     *
     * <p>Deliberately "most recently acted on" rather than "most recently created": a child who
     * summons a goat and then spends three wishes on a cow is talking about the cow. Empty rather
     * than a fallback to the newest creation — a referent nobody has established is exactly the
     * silent pick the whole mechanism exists to remove.
     *
     * <p>A handle whose mob has since died is still returned. That is the point: <i>"he's gone"</i>
     * is the correct answer, and it can only be given by something that still knows who "he" was.
     */
    public synchronized Optional<Handle> referent() {
        return newestFirst.stream()
                .filter(h -> h.lastTargetedMs() > 0)
                .max(Comparator.comparingLong(Handle::lastTargetedMs));
    }

    /** Everything a wish has acted on, most recently acted on first. */
    public synchronized List<Handle> targetedRecently(int limit) {
        List<Handle> out = new ArrayList<>(newestFirst);
        out.removeIf(h -> h.lastTargetedMs() <= 0);
        out.sort(Comparator.comparingLong(Handle::lastTargetedMs).reversed());
        return out.size() > Math.max(0, limit) ? out.subList(0, Math.max(0, limit)) : out;
    }

    /** Where to send a notification when the table changes — see {@link MobHandleStore}. */
    public synchronized void onChange(Runnable listener) {
        this.onChange = listener == null ? () -> { } : listener;
    }

    /** Test seam: make "when" deterministic. */
    synchronized void clockIs(LongSupplier source) {
        this.clock = source == null ? System::currentTimeMillis : source;
    }

    private void replaceInDeque(Handle before, Handle after) {
        List<Handle> all = new ArrayList<>(newestFirst);
        newestFirst.clear();
        for (Handle h : all) {
            newestFirst.addLast(h.equals(before) ? after : h);
        }
    }

    /**
     * Resolve a handle the agent wrote. Accepts {@code @name}, bare {@code name}, or {@code @last}.
     *
     * <p><b>{@code @last} is per wish, not per process.</b> With one wish at a time "whatever I made
     * most recently" and "whatever was made most recently" are the same sentence; with two, they are
     * not, and the wrong reading means one agent silently addressing the <i>other</i> wish's
     * creature. That is exactly the failure handles were introduced to prevent — the nearest-mob
     * heuristic, in a new costume — so it resolves against the calling wish's own creations first
     * and only falls back to the global newest when there is no run to ask.
     */
    public synchronized Optional<Handle> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String key = reference.trim().toLowerCase(Locale.ROOT);
        if (!key.startsWith("@")) {
            key = "@" + key;
        }
        if (key.equals(LAST)) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            if (run != null) {
                for (String mine : run.handles()) {
                    Handle h = byName.get(mine);
                    if (h != null) {
                        return Optional.of(h);
                    }
                }
                // A wish that has made nothing yet genuinely has no "last thing I made". Falling
                // through to the global newest here would hand it the other wish's creature, which
                // is the whole bug; but on a single-wish run the two are identical, so the fallback
                // below is still correct whenever nothing else is in flight.
            }
            // HIS OWN FIRST, THEN SHARED, THEN ANYONE'S. The fallback above answers "the
            // newest thing anyone made", which with one child is the right answer and with two is
            // a coin toss — the nearest-mob heuristic in a new costume, one rung further down.
            // A wish with no run bound still gets the plain global newest, unchanged.
            return Optional.ofNullable(newestOwnedBy(
                    com.moddingfromamod.agent.AgentContext.currentPlayerId()));
        }
        return Optional.ofNullable(byName.get(key));
    }

    /**
     * The newest handle this child may claim, preferring his own over a shared one over a
     * brother's — and the plain newest when there is nobody to ask for.
     */
    private Handle newestOwnedBy(String askerId) {
        List<Handle> all = new ArrayList<>(newestFirst);
        if (all.isEmpty()) {
            return null;
        }
        List<Handle> preferred = com.moddingfromamod.marshal.ArtifactOwner.preferOwn(
                askerId, all, Handle::ownerId);
        return preferred.isEmpty() ? all.get(0) : preferred.get(0);
    }

    /** True when this looks like a handle rather than a mob-type word. */
    public static boolean looksLikeHandle(String reference) {
        return reference != null && reference.trim().startsWith("@");
    }

    /** Everything still remembered, newest first — this is what the agent is shown. */
    public synchronized List<Handle> recent(int limit) {
        List<Handle> out = new ArrayList<>();
        for (Handle h : newestFirst) {
            if (out.size() >= Math.max(0, limit)) {
                break;
            }
            out.add(h);
        }
        return out;
    }

    /**
     * The handle this entity already owns, or null if it was never registered by a wish.
     *
     * <p>Needed so a report of what's nearby can show the agent the exact name to target, instead
     * of leaving it to match by type word and hope.
     */
    /**
     * <b>The thing this handle names has been REPLACED by another entity — follow it.</b>
     *
     * <p>A wished creature is manifested twice: it joins the world as a {@code wish_mob} and
     * {@link com.moddingfromamod.vanilla.VanillaRigBridge} cancels it and spawns a display rig on a
     * vanilla carrier at the end of the same tick. Anything that remembered the first UUID is now
     * holding a dead entity.
     *
     * <p>⚠ <b>That was a live bug and it was reachable from the agent's own tools.</b>
     * {@code DefineSpeciesTool} spawns the mob and calls {@link #remember} on its UUID <i>in the
     * same tick</i>, then hands the agent that handle with the words <i>"use it as modify_mob's
     * target"</i> — so for any species the bridge converts, the very next {@code modify_mob} answered
     * <i>"… is gone — it died or unloaded"</i>. The handle was right when it was minted and wrong
     * one tick later, which is why nothing pointed at the tool that minted it.
     *
     * <p>The rename is deliberate too: a handle keeps its NAME. The child said "make the robot
     * bigger" about {@code @wish_mob1}, and re-minting would silently give the same creature a
     * second name while the agent still held the first.
     *
     * @param from the UUID that has stopped existing
     * @param to the entity that replaced it
     * @param typeId the replacement's kind ({@code Creatures.kindId}: {@code wishmob:<species>} for a
     *               creature, never its carrier's type)
     * @return the number of handles moved (0 is the ordinary case — most entities have none)
     */
    public synchronized int repoint(UUID from, UUID to, String typeId) {
        if (from == null || to == null || from.equals(to)) {
            return 0;
        }
        int moved = 0;
        java.util.List<Handle> snapshot = new java.util.ArrayList<>(newestFirst);
        newestFirst.clear();
        for (Handle h : snapshot) {
            if (h.id().equals(from)) {
                Handle next = new Handle(h.name(), to,
                        typeId == null || typeId.isBlank() ? h.typeId() : typeId,
                        h.label(), h.createdMs(), h.lastTargetedMs(), h.history(), h.ownerId());
                newestFirst.addLast(next);
                byName.put(next.name(), next);
                moved++;
            } else {
                newestFirst.addLast(h);
            }
        }
        if (moved > 0) {
            onChange.run();
        }
        return moved;
    }

    public synchronized String handleFor(UUID id) {
        if (id == null) {
            return null;
        }
        for (Handle h : newestFirst) {
            if (h.id().equals(id)) {
                return h.name();
            }
        }
        return null;
    }

    /** Forget one (it died, or was replaced). */
    public synchronized void forget(UUID id) {
        newestFirst.removeIf(h -> h.id().equals(id));
        byName.values().removeIf(h -> h.id().equals(id));
        onChange.run();
    }

    public synchronized int size() {
        return newestFirst.size();
    }

    /**
     * Server stop, or a new world: this world's names must not be inherited by the next one.
     *
     * <p><b>Does not notify.</b> Clearing is not an edit — a save triggered from here would write an
     * empty table over the world that is closing, which is the whole point of persisting undone.
     * The listener is dropped for the same reason.
     */
    public synchronized void clear() {
        byName.clear();
        newestFirst.clear();
        nameCounters.clear();
        onChange = () -> { };
    }

    // ===================== naming =====================

    /** {@code witherstormmod:wither_storm} → {@code wither_storm}; anything unusable → "thing". */
    private static String baseName(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return "thing";
        }
        String path = typeId.substring(typeId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            }
        }
        String cleaned = sb.toString();
        return cleaned.isEmpty() ? "thing" : cleaned;
    }

    /**
     * First one gets the plain name; later ones get a number.
     *
     * <p>The counter is <b>monotonic and survives eviction</b>, which is subtler than it looks. An
     * earlier version only avoided names currently in use — so once {@code @chicken} was evicted,
     * the next chicken took that name back, and an agent still holding {@code @chicken} silently
     * started addressing a <i>different animal</i>. That is precisely the failure handles exist to
     * prevent, so a name is retired for the whole run once issued. Only {@link #clear()} resets it.
     */
    private String uniqueName(String base) {
        int n = nameCounters.merge(base, 1, Integer::sum);
        String candidate = n == 1 ? "@" + base : "@" + base + n;
        while (byName.containsKey(candidate)) {
            n = nameCounters.merge(base, 1, Integer::sum);
            candidate = "@" + base + n;
        }
        return candidate;
    }

    /**
     * Drop something he has <b>never talked about</b> first, and only then the stalest of the rest.
     *
     * <p>Creation order alone was fine while a handle only recorded a creation. It is not any more:
     * a "rain sixty chickens" wish would evict the animal he has spent the whole session refining,
     * which is precisely the handle the next wish needs. Nor is plain recency enough — the pet is
     * genuinely the least recently touched thing in the world by the time sixty chickens exist. What
     * actually distinguishes them is that <b>a wish acted on one of them</b>.
     *
     * <p>Iterated oldest-first, so that among equals — and creation timestamps collide constantly,
     * since a loop makes many handles inside one millisecond — the oldest is the one that goes. The
     * first cut compared newest-first with a strict {@code <}, so every tie kept the oldest and
     * evicted the NEWEST handle: the table stayed the right size and threw away the thing that had
     * just been made.
     */
    private void evictBeyondLimit() {
        while (newestFirst.size() > MAX_HANDLES) {
            Handle stalest = null;
            for (var it = newestFirst.descendingIterator(); it.hasNext(); ) {
                Handle h = it.next();
                if (stalest == null || rankForEviction(h) < rankForEviction(stalest)) {
                    stalest = h;
                }
            }
            if (stalest == null) {
                return;
            }
            newestFirst.remove(stalest);
            byName.remove(stalest.name());
        }
    }

    /** Lower goes first: everything never targeted sorts below everything that was. */
    private static long rankForEviction(Handle h) {
        return h.lastTargetedMs() > 0 ? Long.MAX_VALUE / 2 + h.lastTargetedMs() : h.createdMs();
    }

    // ===================== persistence =====================

    /**
     * The whole table as JSON, for {@link MobHandleStore} to write.
     *
     * <p>The name counters go too. Without them a reloaded world starts numbering at 1 again and the
     * next zombie takes back a name an evicted one used to have — the "silently addressing a
     * different animal" failure the counters were introduced to prevent, reintroduced by the save.
     */
    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Handle h : newestFirst) {
            JsonObject o = new JsonObject();
            o.addProperty("name", h.name());
            o.addProperty("id", h.id().toString());
            o.addProperty("type", h.typeId());
            o.addProperty("label", h.label());
            o.addProperty("created", h.createdMs());
            o.addProperty("targeted", h.lastTargetedMs());
            // Only when there is one, so a mobs.json from before owner stamps round-trips unchanged.
            com.moddingfromamod.marshal.ArtifactOwner.stamp(o, h.ownerId());
            if (!h.history().isEmpty()) {
                JsonArray touches = new JsonArray();
                for (Touch t : h.history()) {
                    JsonObject to = new JsonObject();
                    to.addProperty("wish", t.wishId());
                    to.addProperty("tool", t.tool());
                    to.addProperty("when", t.whenMs());
                    to.addProperty("did", t.summary());
                    to.add("before", t.before().toJson());
                    touches.add(to);
                }
                o.add("history", touches);
            }
            arr.add(o);
        }
        root.add("handles", arr);
        JsonObject counters = new JsonObject();
        nameCounters.forEach(counters::addProperty);
        root.add("counters", counters);
        return root;
    }

    private volatile boolean refusedNewer;

    /**
     * Did the last {@link #loadJson} meet an undo record written by a newer Moddy ({@link MobState.Newer}, #115)? Then
     * the table holds less than the file, and {@code MobHandleStore} never writes it back over that file.
     */
    public boolean refusedNewer() {
        return refusedNewer;
    }

    /**
     * Replace the table from JSON. <b>A damaged record is skipped, never fatal.</b>
     *
     * <p>These names are a convenience for talking about animals. A world that refuses to load
     * because one of them is malformed would be an enormously worse outcome than a world where one
     * handle has been forgotten, so every field is read defensively and one bad entry costs only
     * itself.
     *
     * @return how many handles were restored
     */
    public synchronized int loadJson(JsonObject root) {
        refusedNewer = false;
        byName.clear();
        newestFirst.clear();
        nameCounters.clear();
        if (root == null) {
            return 0;
        }
        try {
            JsonElement arr = root.get("handles");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    Handle h;
                    try {
                        h = handleFromJson(el);
                    } catch (MobState.Newer n) {
                        refusedNewer = true;   // a newer Moddy's undo record: this table is never written back
                        continue;
                    }
                    if (h != null && !byName.containsKey(h.name())) {
                        byName.put(h.name(), h);
                        newestFirst.addLast(h);
                    }
                }
            }
            JsonElement counters = root.get("counters");
            if (counters != null && counters.isJsonObject()) {
                for (var e : counters.getAsJsonObject().entrySet()) {
                    try {
                        nameCounters.put(e.getKey(), e.getValue().getAsInt());
                    } catch (RuntimeException ignored) {
                        // one unreadable counter costs one name, not the world
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A container that is the wrong shape entirely leaves us with whatever parsed.
        }
        evictBeyondLimit();
        return newestFirst.size();
    }

    /** One entry of a mob's history. A damaged one costs that entry's undo, nothing more. */
    private static Touch touchFromJson(JsonElement el) {
        try {
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            return new Touch(
                    o.has("wish") ? o.get("wish").getAsString() : "",
                    o.has("tool") ? o.get("tool").getAsString() : "",
                    o.has("when") ? o.get("when").getAsLong() : 0L,
                    o.has("did") ? o.get("did").getAsString() : "",
                    o.has("before") && o.get("before").isJsonObject()
                            ? MobState.fromJson(o.getAsJsonObject("before"))
                            : MobState.UNKNOWN);
        } catch (MobState.Newer n) {
            throw n;   // refused, never dropped (#115): the table that read it is never written back
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Handle handleFromJson(JsonElement el) {
        try {
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            String name = o.get("name").getAsString();
            UUID id = UUID.fromString(o.get("id").getAsString());
            if (name.isBlank() || !name.startsWith("@")) {
                return null;
            }
            List<Touch> history = new ArrayList<>();
            if (o.has("history") && o.get("history").isJsonArray()) {
                for (JsonElement te : o.getAsJsonArray("history")) {
                    Touch t = touchFromJson(te);
                    if (t != null) {
                        history.add(t);
                    }
                }
            }
            return new Handle(name, id,
                    o.has("type") ? o.get("type").getAsString() : "",
                    o.has("label") ? o.get("label").getAsString() : "",
                    o.has("created") ? o.get("created").getAsLong() : 0L,
                    o.has("targeted") ? o.get("targeted").getAsLong() : 0L,
                    history,
                    com.moddingfromamod.marshal.ArtifactOwner.of(o));
        } catch (MobState.Newer n) {
            throw n;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
