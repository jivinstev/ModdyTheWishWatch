package com.moddingfromamod.wish;

import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.store.PlayerShelf;
import com.moddingfromamod.store.PlayerShelves;
import com.moddingfromamod.store.WishStoreFile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wishes the agent has taught the watch, stored with the world.
 *
 * <p>This is what turns a one-off answer into a permanent capability: the first time the child asks
 * for something new it costs an agent round-trip; every time after, it's instant and works offline.
 * The saved file is plain JSON in the same shape as the library's templates, so a grown-up can read,
 * edit or delete a learned wish by hand.
 *
 * <p>Learned templates are matched <b>after</b> the library; there are no hard-coded
 * built-ins behind them any more.
 */
public final class LearnedTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final int MAX_TEMPLATES = 500;

    private static JsonArray learned = new JsonArray();

    private LearnedTemplates() {
    }

    private static final String FILE = "learned.json";

    /** The envelope name, and the newest schema this build reads (stamped this store). */
    static final String STORE = "learned";
    static final int SCHEMA = 1;

    /**
     * A child's recipes live on his shelf ({@code <players>/<uuid>/learned.json}) so his
     * buttons follow him from Creative to Survival; unowned ones stay with the world.
     */
    private static final PlayerShelf SHELF =
            new PlayerShelf(STORE, FILE, "templates", PlayerShelf.byField("id"));

    static {
        PlayerShelves.register(STORE, SHELF, LearnedTemplates::load);
    }

    private static Path wishesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes");
    }

    /**
     * Learned entries that a shipped fix has superseded, and must be dropped on load.
     *
     * <p>When the agent gets something wrong it does not just disappoint once — it <b>teaches the
     * mistake</b>, and the wrong answer is then served instantly forever. So a fix has to invalidate
     * the bad entry too. This list is the mechanism: add an id here whenever a shipped change means
     * a previously-learned answer is now wrong, and it is purged from every world on next load. Only
     * the named entries go; everything else the child has taught is kept.
     *
     * <p>{@code wither_storm}: learned as a giant vanilla SPIDER, because the agent could not see
     * that witherstormmod was installed. Superseded by the find_content tool.
     * <p>{@code pink_dragon}: learned as a spider-bodied "dragon" before honest naming.
     */
    private static final java.util.Set<String> SUPERSEDED =
            java.util.Set.of("wither_storm", "storm_tentacle", "pink_dragon");

    /** Load learned wishes for this world and fold them into the active template set. */
    public static synchronized void load(MinecraftServer server) {
        learned = new JsonArray();
        Path wishes = wishesDir(server);
        WishStoreFile.Read read = SHELF.read(wishes,
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            // The rule, now for this store too: before the envelope a damaged file was logged,
            // the set came back EMPTY, and the next add_template rewrote the file from nothing.
            LOGGER.error("could not read learned wishes at {}: {}. Nothing will be saved over it.",
                    wishes.resolve(FILE), r.reason());
            rebuild();
            return;
        }
        JsonObject root = read instanceof WishStoreFile.Read.Ok ok ? ok.root()
                : read instanceof WishStoreFile.Read.Legacy l ? l.root() : null;
        JsonElement arr = root == null ? null : root.get("templates");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                learned.add(el);
            }
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s learned wishes: {}. Their shelf will not be written.", who, why));
        int purged = purgeSuperseded();
        rebuild();
        SHELF.remember(payload(), com.moddingfromamod.marshal.ArtifactOwner::of);
        // an earlier issue's backfill, after the base (SpeciesRegistry.load says why the order matters): a
        // recipe's time is the add_template call that taught it.
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(wishes);
        for (JsonElement el : learned) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("id")) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!com.moddingfromamod.store.ArtifactTimes.of(o).known()) {
                var t = known.timesOf(com.moddingfromamod.store.TimesBackfill.TEMPLATE,
                        o.get("id").getAsString());
                if (t.known()) {
                    com.moddingfromamod.store.ArtifactTimes.write(o, t);
                    filled++;
                }
            }
        }
        if (purged > 0 || filled > 0 || SHELF.needsConverge()) {
            // Persist immediately so a bad entry cannot come back on the next load, and so a world
            // from before shelves moves each child's recipes onto his shelf now rather than at the
            // next unrelated add_template.
            save(server);
            if (purged > 0) {
                LOGGER.info("dropped {} superseded wish template(s) — they will be re-learned "
                        + "correctly", purged);
            }
        }
        if (!learned.isEmpty()) {
            LOGGER.info("loaded {} learned wish template(s)", learned.size());
        }
    }

    /** Forget this world's learned set (server stop). */
    public static synchronized void reset() {
        learned = new JsonArray();
        WishCatalog.resetToDefaults();
    }

    /** Drop learned entries a shipped fix has invalidated. Returns how many went. */
    private static int purgeSuperseded() {
        JsonArray keep = new JsonArray();
        int dropped = 0;
        for (JsonElement el : learned) {
            String id = el.isJsonObject() && el.getAsJsonObject().has("id")
                    ? el.getAsJsonObject().get("id").getAsString() : "";
            if (SUPERSEDED.contains(id)) {
                dropped++;
            } else {
                keep.add(el);
            }
        }
        learned = keep;
        return dropped;
    }

    /** Add (or replace by id) a template, persist it, and make it live immediately. */
    /**
     * The child-facing "it's saved, and it'll be quick" line. Pure, so the wording is unit-tested:
     * a NEW wish reads as "I'll remember this", an UPDATE as "saved your changes" — either way it names
     * the thing and promises the button brings it back in a flash. Not "instantly" any more: since
     * A button gets a few seconds of pillar and reveal, and a promise of "instant" would
     * make the moment read as lag.
     */
    static String savedMessage(String label, boolean update) {
        return update
                ? "✨ Saved your new changes to " + label + "! Tap its button any time and it comes "
                + "back exactly like this, in a flash! 🪄"
                : "✨ Ta-da! I'll remember your " + label + " forever — next time just tap its button "
                + "and it appears in a flash! 🪄";
    }

    /**
     * Tell the child whose wish taught this button — and nobody else (follow-up).
     *
     * <p>This used to go to EVERY online player, the same shape removed from
     * {@code finish_building}: a second child read "Ta-da! I'll remember your …" about a button that
     * was not his. The audience is {@link com.moddingfromamod.marshal.WishAudience#forNarration},
     * the rule {@code plan_wish}, {@code set_status}, {@code notify_player} and
     * {@code finish_building} already use: the calling wish's owner, and nobody when the calling
     * wish cannot be identified. The two production callers ({@code add_template},
     * {@code author_datapack}) both run inside a bound agent call.
     */
    private static void announceSaved(MinecraftServer server, String id, boolean update) {
        String msg = savedMessage(WishPresets.labelFor(id), update);
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        java.util.List<java.util.UUID> online = server.getPlayerList().getPlayers().stream()
                .map(net.minecraft.server.level.ServerPlayer::getUUID).toList();
        for (java.util.UUID who : com.moddingfromamod.marshal.WishAudience.forNarration(
                run == null ? null : run.playerId(), online)) {
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p == null) {
                continue;
            }
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg)
                    .setStyle(net.minecraft.network.chat.Style.EMPTY.withColor(
                            net.minecraft.ChatFormatting.LIGHT_PURPLE)));
        }
    }

    public static synchronized String addAndSave(MinecraftServer server, JsonObject template) {
        String id = template.get("id").getAsString();
        // WHOSE RECIPE THIS IS. Stamped here rather than in `add_template`, because this is
        // the one door every learned entry comes through and the tool is not the only caller.
        // A REPLACEMENT keeps the original owner: re-teaching an id the whole room already has is
        // still the room's, and an entry with no owner stays unowned — "absent = legacy = shared".
        String previous = ownerOf(id);
        // WHEN: a re-teach is an update, and an update moves the button to the front of
        // his history. Teaching the very same recipe again keeps its times — nothing changed.
        JsonObject before = find(id);
        var had = com.moddingfromamod.store.ArtifactTimes.of(before);
        template.remove(com.moddingfromamod.store.ArtifactTimes.CREATED);
        template.remove(com.moddingfromamod.store.ArtifactTimes.UPDATED);
        com.moddingfromamod.marshal.ArtifactOwner.stamp(template,
                previous.isEmpty() && !isLearned(id)
                        ? com.moddingfromamod.agent.AgentContext.currentPlayerId()
                        : previous);
        // ⚠ WORK OUT THE RIVALS BEFORE THE ADD, or the new template is compared against itself.
        // five real grants of one wish left three templates all matching `crush`, and the
        // two that minted rivals were the two that never called list_templates. Calling that is
        // optional; calling add_template is not, so the report rides out on this path.
        // THE THINGS THIS WISH MADE, recorded at the grant — a wish and its parts. What the
        // run has made so far; finish_wish brings it up to date (recordParts). Never the agent's to
        // write, and a re-teach outside a wish keeps the parts it had.
        template.remove(PARTS);
        // The wish teaching it: AgentBridge.onServer carries the worker's binding to this thread.
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.taught(id);
        }
        JsonArray parts = partsJson(run);
        if (parts.isEmpty() && before != null && before.has(PARTS)) {
            parts = before.get(PARTS).getAsJsonArray().deepCopy();
        }
        if (!parts.isEmpty()) {
            template.add(PARTS, parts);
        }
        // THE SEATS RIDE THE RECIPE — a creature this wish put on castle:throne is re-wished
        // onto it, even when the line taught was a plain `wishmob spawn boss`.
        java.util.List<String> linked = new java.util.ArrayList<>();
        carrySeats(template, run, linked);
        if (before == null || !withoutTimes(before).equals(template)) {
            com.moddingfromamod.store.ArtifactTimes.write(template,
                    com.moddingfromamod.store.ArtifactTimes.touched(had,
                            com.moddingfromamod.store.ShelfTimes.now()));
        } else {
            com.moddingfromamod.store.ArtifactTimes.write(template, had);
        }
        String rivalWarning = TemplateOverlap.describe(
                TemplateOverlap.rivals(id, termsOf(template), termsByIdOfLearned()));
        JsonArray next = new JsonArray();
        boolean replaced = false;
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                next.add(template);
                replaced = true;
            } else {
                next.add(el);
            }
        }
        if (!replaced) {
            next.add(template);
        }
        while (next.size() > MAX_TEMPLATES) {
            next.remove(0);
        }
        learned = next;

        rebuild();
        // Prove it works before claiming success — a template that matches nothing is worse than
        // no template, because it looks like it was learned.
        boolean matches = false;
        if (template.has("all") && template.get("all").isJsonArray()
                && !template.getAsJsonArray("all").isEmpty()) {
            StringBuilder probe = new StringBuilder();
            for (JsonElement t : template.getAsJsonArray("all")) {
                probe.append(t.getAsString()).append(' ');
            }
            matches = WishCatalog.templates().match(WishText.sanitize(probe.toString())).isPresent();
        }

        save(server);
        pushPresets(server);
        // Tell the child, in plain words, that this is SAVED and will be instant next time — whether
        // it's a brand-new wish or a change to one he already had. He shouldn't have to wonder whether
        // an edit "took"; a saved thing is only useful if he knows it's there.
        announceSaved(server, id, replaced);
        // Capture the agent's design reasoning (if the tool passed a 'note') for the manifest.
        if (template.has("note") && template.get("note").isJsonPrimitive()) {
            com.moddingfromamod.provenance.ProvenanceContext.note(template.get("note").getAsString());
        }
        // Provenance Contract: publish this template's recipe + its intent lineage into the Mod Repo,
        // so a learned wish is a first-class, upgradable Artifact (not only world-local). Best-effort.
        com.moddingfromamod.provenance.ProvenancePublisher.publish(
                "template", id, "template.json", template.toString());
        // BE PRECISE ABOUT WHICH PATH WORKS. This message used to say "Verified: the watch now
        // grants it instantly" whenever the raw matcher liked the terms — but a press and a typed
        // wish take different paths, and the difference is invisible from here. The button is
        // always exact now (it names this id), while TYPING these words still has to get past the
        // question/contextual gates, which terms like "again" or "another" deliberately trip.
        String typed = WishText.sanitize(probeText(template));
        boolean typingGated = !typed.isEmpty()
                && (WishIntent.isQuestion(typed) || WishReference.isContextual(typed));
        return (replaced ? "Updated" : "Learned") + " wish template '" + id + "'."
                + " The button on the watch grants it instantly."
                + (matches && !typingGated
                        ? " Typing those words is instant too."
                        : typingGated
                            ? " (Typing those words will still reach you, not the cache: they read"
                                + " as a follow-up — that's deliberate, the button is the fast path.)"
                            : " (Heads-up: I couldn't verify the words match — check the keywords.)")
                + " Total learned: " + learned.size() + "."
                + (linked.isEmpty() ? "" : " Kept where this wish put its creatures, so a re-wish "
                        + "seats them again: " + String.join(", ", linked) + ".")
                + rivalWarning;
    }

    /**
     * rewrite a recipe's {@code wishmob spawn} lines with the spots its wish seated them
     * on ({@link com.moddingfromamod.marshal.SpotLink#carry}). True when anything changed.
     */
    private static boolean carrySeats(JsonObject template, com.moddingfromamod.marshal.WishRun run,
                                      java.util.List<String> notes) {
        if (run == null || run.seats().isEmpty() || !template.has("commands")
                || !template.get("commands").isJsonArray()) {
            return false;
        }
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (JsonElement el : template.getAsJsonArray("commands")) {
            cmds.add(el.isJsonPrimitive() ? el.getAsString() : el.toString());
        }
        java.util.List<String> out = com.moddingfromamod.marshal.SpotLink.carry(cmds, run.seats(), notes);
        if (out == cmds) {
            return false;
        }
        JsonArray arr = new JsonArray();
        out.forEach(arr::add);
        template.add("commands", arr);
        return true;
    }

    /** Every matching word a template claims — `all` and `any` together, since either can match. */
    private static java.util.List<String> termsOf(JsonObject template) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String key : new String[] {"all", "any"}) {
            if (template.has(key) && template.get(key).isJsonArray()) {
                for (JsonElement t : template.getAsJsonArray(key)) {
                    if (t.isJsonPrimitive()) {
                        out.add(t.getAsString());
                    }
                }
            }
        }
        return out;
    }

    /**
     * The learned templates' terms, by id.
     *
     * <p>Learned only, not the library. The measured pile-up is entirely among learned templates.
     */
    private static java.util.Map<String, java.util.List<String>> termsByIdOfLearned() {
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        for (JsonElement el : learned) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive()) {
                out.put(o.get("id").getAsString(), termsOf(o));
            }
        }
        return out;
    }

    /**
     * Un-teach one learned wish, permanently.
     *
     * <p>The child-facing half of {@link #SUPERSEDED}. That list needs a grown-up to edit Java and
     * redeploy — far too slow a loop for the one person who actually sees the mistake — so
     * rejecting a wish in-game calls this instead.
     *
     * <p>Library templates are not stored here and so cannot be forgotten from a game: they are the
     * server's curated set, changed by a library push.
     *
     * @return true if a learned template was actually removed
     */
    public static synchronized boolean forget(MinecraftServer server, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        JsonArray keep = new JsonArray();
        boolean removed = false;
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                removed = true;
            } else {
                keep.add(el);
            }
        }
        if (!removed) {
            return false;
        }
        learned = keep;
        rebuild();
        save(server);
        LOGGER.info("forgot learned wish template '{}' — the child said it was wrong", id);
        pushPresets(server);
        return true;
    }

    /** What {@link #forgetOwned} found. */
    public enum Forgot { FORGOT, ABSENT, NOT_HIS }

    /**
     * <b>Un-teach one of HIS buttons, because he asked on his account page</b> (2026-10-12). An
     * account service can list each player's own saved buttons and pass his delete to every server
     * that runs this. Only a button
     * whose owner IS this player is removed — an id another child taught, or a shared one, is
     * {@link Forgot#NOT_HIS} and untouched. One already gone is {@link Forgot#ABSENT}, which is done:
     * every server runs the same line, and a second run must not read as a failure.
     */
    public static synchronized Forgot forgetOwned(MinecraftServer server, String ownerId, String id) {
        if (ownerId == null || ownerId.isBlank() || id == null || id.isBlank()) {
            return Forgot.NOT_HIS;
        }
        if (!isLearned(id)) {
            return Forgot.ABSENT;
        }
        if (!ownerId.equalsIgnoreCase(ownerOf(id))) {
            return Forgot.NOT_HIS;
        }
        return forget(server, id) ? Forgot.FORGOT : Forgot.ABSENT;
    }

    /** Whose learned recipe this is, or {@code ""} — absent means shared. */
    /** Where a wish's parts are kept on its recipe: {@code [{"kind","id"}, …]}. */
    static final String PARTS = "parts";

    /**
     * The run's PARTS: the distinct new things it made — never a mob handle, an ability
     * pack folded into its creature or item, a version the wish took away again, or a thing that
     * was there before it ({@link com.moddingfromamod.marshal.WishParts}).
     */
    public static java.util.List<com.moddingfromamod.marshal.WishRun.Made> partsOf(
            com.moddingfromamod.marshal.WishRun run) {
        if (run == null) {
            return java.util.List.of();
        }
        return com.moddingfromamod.marshal.WishParts.of(run.made(), run.removedThings(),
                run.existedBefore(), LearnedTemplates::itemName);
    }

    private static String itemName(String id) {
        try {
            var spec = com.moddingfromamod.item.ItemSpecStore.get(id);
            return spec == null || spec.name() == null ? "" : spec.name();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static JsonArray partsJson(com.moddingfromamod.marshal.WishRun run) {
        JsonArray out = new JsonArray();
        for (var m : partsOf(run)) {   // oldest first
            JsonObject one = new JsonObject();
            one.addProperty("kind", m.kind());
            one.addProperty("id", m.id());
            // "boss sits on castle:throne" — the part remembers its seat.
            String on = run.seats().get(m.id().trim().toLowerCase(java.util.Locale.ROOT));
            if (on != null && com.moddingfromamod.marshal.MadeKind.CREATURE.equals(m.kind())) {
                one.addProperty("on", on);
            }
            out.add(one);
        }
        return out;
    }

    /** A learned recipe's commands, or empty when there is no such recipe. */
    public static synchronized java.util.List<String> commandsOf(String id) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonObject row = id == null ? null : find(id);
        if (row != null && row.has("commands") && row.get("commands").isJsonArray()) {
            for (JsonElement el : row.getAsJsonArray("commands")) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }

    /**
     * <b>Record what a granted wish made, on the recipes it taught</b> — called once the
     * wish has finished, when its run knows everything it made. A recipe whose parts did not
     * change is left alone; nothing is ever recorded for a wish granted before this (no
     * backfill). A wish that made nothing NEW (an edit) records nothing: its recipe's
     * commands say what it makes.
     */
    public static synchronized void recordParts(MinecraftServer server,
            com.moddingfromamod.marshal.WishRun run) {
        if (server == null || run == null || run.taughtIds().isEmpty()) {
            return;
        }
        JsonArray parts = partsJson(run);
        boolean changed = false;
        for (String id : run.taughtIds()) {
            JsonObject row = find(id);
            if (row != null && !parts.isEmpty() && !parts.equals(row.get(PARTS))) {
                row.add(PARTS, parts.deepCopy());
                changed = true;
            }
            // a creature seated after the recipe was taught is linked now.
            if (row != null && carrySeats(row, run, new java.util.ArrayList<>())) {
                changed = true;
            }
        }
        if (changed) {
            save(server);
            pushPresets(server);
        }
    }

    private static java.util.List<String> partIds(JsonObject o) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (o.has(PARTS) && o.get(PARTS).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(PARTS)) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    out.add(el.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        return out;
    }

    /** The stored row for {@code id}, or null. */
    private static JsonObject find(String id) {
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return el.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject withoutTimes(JsonObject o) {
        JsonObject copy = o.deepCopy();
        copy.remove(com.moddingfromamod.store.ArtifactTimes.CREATED);
        copy.remove(com.moddingfromamod.store.ArtifactTimes.UPDATED);
        return copy;
    }

    /** When this recipe was taught and last re-taught; {@code NONE} when nobody knows. */
    public static synchronized com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return id == null ? com.moddingfromamod.store.ArtifactTimes.Times.NONE
                : com.moddingfromamod.store.ArtifactTimes.of(find(id));
    }

    public static synchronized String ownerOf(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return com.moddingfromamod.marshal.ArtifactOwner.of(el.getAsJsonObject());
            }
        }
        return "";
    }

    /** Whether this id is something the agent taught, rather than a shipped built-in. */
    public static synchronized boolean isLearned(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A new wish should become a button immediately, not on the next login.
     *
     * <p>Package-open rather than private: a library reload changes the button set
     * without adding or forgetting anything learned, and it must push too.
     */
    public static void pushPresets(MinecraftServer server) {
        try {
            // PER CHILD — `Send.all(snapshot())` would deliver one child's panel to
            // everybody, which is the exact bug the per-child panel exists to fix, arriving through
            // the push instead of through the build.
            com.moddingfromamod.net.ModdedClient.broadcastPresets(server);
        } catch (RuntimeException e) {
            LOGGER.debug("could not sync Moddy's buttons: {}", e.toString());
        }
    }

    private static void save(MinecraftServer server) {
        if (!SHELF.mayWrite()) {
            LOGGER.error("NOT saving learned wishes — {} could not be read ({}). The file has been "
                    + "left alone on purpose; a grown-up should look at it.",
                    wishesDir(server).resolve(FILE), SHELF.refusalReason());
            return;
        }
        SHELF.write(wishesDir(server), com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                SCHEMA, payload(), com.moddingfromamod.marshal.ArtifactOwner::of, LOGGER::warn);
    }

    private static JsonObject payload() {
        JsonObject root = new JsonObject();
        root.add("templates", learned.deepCopy());
        return root;
    }

    /**
     * Rebuild the active set: <b>library first, then learned</b> (the built-ins were deleted,
     *).
     *
     * <p>The order IS the precedence, because both the matcher and {@code byId} take the first
     * template whose id or terms fit. The server's curated library beats a child's accidental
     * answer for the same id. The composition itself lives in
     * {@link com.moddingfromamod.library.WishLibrary#composeTemplatesJson}, which is Minecraft-free
     * and unit-tested; an absent library composes to the learned set alone.
     */
    private static void rebuild() {
        WishCatalog.setTemplates(WishTemplates.fromJson(
                com.moddingfromamod.library.WishLibrary.composeTemplatesJson(learned)));
    }

    /**
     * The learned wishes as screen buttons, <b>newest first</b>.
     *
     * <p>The button's wish text is the template's own {@code all} terms joined — which is exactly
     * the probe {@link #addAndSave} uses to verify a template matches, so a button built this way
     * is guaranteed to hit the template it came from.
     */
    public static synchronized java.util.List<WishCatalog.Preset> asPresets() {
        java.util.List<WishCatalog.Preset> out = new java.util.ArrayList<>();
        for (int i = learned.size() - 1; i >= 0; i--) {
            JsonElement el = learned.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) {
                continue;
            }
            // ⚠ `any` COUNTS. This used to demand an `all` array and skip everything else, which
            // silently withheld the button from every recipe the agent chose to write with `any` --
            // a shape its own tool schema offers as an equal alternative. See
            // WishPresets.matchingText for the measurement.
            String text = WishPresets.matchingText(
                    terms(o, "all"), terms(o, "any"), terms(o, "none"));
            if (text == null) {
                continue;
            }
            String id = o.get("id").getAsString();
            // AN ID THE LIBRARY CLAIMS GETS NO SECOND BUTTON. rebuild() already drops the
            // learned copy from the matcher, so a press of this button would serve the LIBRARY's
            // recipe — and two buttons that run the same thing, with whatever words the agent
            // happened to teach on one of them, is a panel that misrepresents itself. The
            // de-duplication in WishPresets.forScreen keys on the button's TEXT and would not
            // catch this: same id, different words.
            if (com.moddingfromamod.library.WishLibrary.snapshot().hasTemplate(id)) {
                continue;
            }
            // A learned recipe may carry an `icon` too (add_template offers the field): the panel
            // reads the template's own choice first and keyword-scans only when there isn't one.
            //
            // THE TEACHER RIDES ALONG. It is not drawn; it is what lets
            // WishPresets.forScreen(viewer, …) keep one child's teaching off his brother's panel.
            // Carried on the Preset rather than filtered here so the per-child RULE stays pure and
            // unit-tested in one place, and so this method keeps answering one question.
            out.add(new WishCatalog.Preset(WishPresets.labelFor(id), text, id,
                    com.moddingfromamod.library.LibrarySnapshot.iconOf(o),
                    com.moddingfromamod.marshal.ArtifactOwner.of(o)));
        }
        // NEWEST FIRST BY WHEN. The reverse walk was newest-first by when it was ADDED,
        // and a re-teach replaces in place — so an updated recipe never moved up.
        return new java.util.ArrayList<>(com.moddingfromamod.marshal.NewestFirst.sort(out,
                p -> com.moddingfromamod.store.ArtifactTimes.of(find(p.templateId())).recency()));
    }

    /**
     * <b>Every learned recipe as its id and commands, newest first</b> — the input to
     * {@link com.moddingfromamod.marshal.TaughtJoin}, which joins a recipe to the thing it replays
     * (one tile per thing he made).
     *
     * <p>The same ids {@link #asPresets} offers as buttons: an id the library claims is skipped here
     * for the same reason it gets no button there — {@code /moddy again} with it would serve the
     * LIBRARY's recipe, not this one.
     */
    public static synchronized java.util.List<com.moddingfromamod.marshal.TaughtJoin.Taught> taught() {
        java.util.List<com.moddingfromamod.marshal.TaughtJoin.Taught> out =
                new java.util.ArrayList<>();
        for (int i = learned.size() - 1; i >= 0; i--) {
            JsonElement el = learned.get(i);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("id")) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            if (com.moddingfromamod.library.WishLibrary.snapshot().hasTemplate(id)) {
                continue;
            }
            out.add(new com.moddingfromamod.marshal.TaughtJoin.Taught(id, terms(o, "commands"),
                    com.moddingfromamod.marshal.ArtifactOwner.of(o), partIds(o)));
        }
        // Newest by when it was taught or re-taught, so a thing's newest recipe wins.
        return new java.util.ArrayList<>(com.moddingfromamod.marshal.NewestFirst.sort(out,
                t -> com.moddingfromamod.store.ArtifactTimes.of(find(t.id())).recency()));
    }

    /** One term array off a template, as plain strings. */
    private static java.util.List<String> terms(JsonObject o, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement t : o.getAsJsonArray(key)) {
                if (t.isJsonPrimitive()) {
                    out.add(t.getAsString());
                }
            }
        }
        return out;
    }

    /**
     * The learned set, exactly as it would be written to disk.
     *
     * <p>Exists so a test can tell "the recipe was UPDATED" from "a recipe exists". Those look
     * identical through {@link #count()} — re-teaching an id replaces in place and leaves the count
     * unchanged — and the difference is the whole point of asking for a change.
     */
    public static synchronized String snapshotJson() {
        return learned.toString();
    }

    /** One learned template by id, or null. Lets a test diff a single recipe across an edit. */
    public static synchronized String snapshotJson(String id) {
        for (JsonElement el : learned) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return el.toString();
            }
        }
        return null;
    }

    /** The terms joined — the same text the button will carry. */
    private static String probeText(JsonObject template) {
        StringBuilder probe = new StringBuilder();
        if (template.has("all") && template.get("all").isJsonArray()) {
            for (JsonElement t : template.getAsJsonArray("all")) {
                probe.append(t.getAsString()).append(' ');
            }
        }
        return probe.toString();
    }

    /** Test seam: the learned set as raw JSON strings, so a test can restore it afterwards. */
    public static synchronized java.util.List<String> savedJsonForTest() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonElement el : learned) {
            out.add(el.toString());
        }
        return out;
    }

    /** Test seam: put the learned set back exactly as it was, so one test cannot leak into another. */
    public static synchronized void restoreForTest(java.util.List<String> json,
                                                   MinecraftServer server) {
        JsonArray next = new JsonArray();
        for (String one : json) {
            next.add(JsonParser.parseString(one).getAsJsonObject());
        }
        learned = next;
        rebuild();
        if (server != null) {
            save(server);
            pushPresets(server);
        }
    }

    public static synchronized int count() {
        return learned.size();
    }
}
