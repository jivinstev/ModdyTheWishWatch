package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.marshal.HandoverRecord;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.marshal.WishCardContract;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Filling in the watch — turning what the server knows into the rows the screen draws.
 *
 * <p>Everything here is <b>already persisted and, until now, unused</b>: a creature's sculpt carries
 * health, damage, scale and its powers; a build carries its shapes, and therefore its block count,
 * footprint and height; a manifest carries the child's own words and every re-wish. Spikor's own
 * record says health 300, damage 12, powers hostile/fireproof/boss — a stats sheet already written
 * that nothing has ever shown him.
 *
 * <p>Sent on join and whenever the picture changes, on a cheap signature compare — the same shape
 * a real mod repo's city beacons use. <b>Never every tick</b>: the payload carries a card's worth of
 * text per creation, and a screen nobody has open does not need it sixty times a minute.
 *
 * <p>On {@code NeoForge.EVENT_BUS}.
 */
public final class WatchSnapshot {

    /** How often to look. Half a second is far faster than a child can open the watch. */
    private static final int CHECK_INTERVAL = 10;

    private static int tickCounter;
    private static final WatchSignatures SIGNATURES = new WatchSignatures();

    private WatchSnapshot() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % CHECK_INTERVAL != 0) {
            return;
        }
        var server = event.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // ONLY FOR A CLIENT THAT CAN RECEIVE IT (2026-10-31). The payload is mod-only; a stock
            // client never gets it (Send.to drops it), yet it was built — every creation, recipe,
            // photo and icon of his — every half second for every player. On a server where every child
            // is on a stock client, so this was all of the cost and none of the use.
            if (!com.moddingfromamod.net.ModdedClient.can(player,
                    com.moddingfromamod.net.ModdedClient.Channel.WATCH_SYNC)) {
                continue;
            }
            Watch payload = forPlayer(player);
            // Per child, and `continue`: one child's unchanged watch is no reason to skip
            // the children after him.
            if (!SIGNATURES.changed(player.getUUID(), payload.happening() + "|" + payload.creations())) {
                continue;
            }
            send(player, payload);
        }
    }

    /** A fresh watch on join — his things are there before he opens it, not after. */
    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SIGNATURES.forget(player.getUUID());
            send(player, forPlayer(player));
        }
    }

    /** He left: nothing to compare against next time, and nothing kept for him. */
    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        SIGNATURES.forget(event.getEntity().getUUID());
    }

    /**
     * Send it, unless this "player" cannot be sent to.
     *
     * <p>A GameTest's mock player is in the player list but has no real connection, and sending it a
     * custom payload <b>crashes the server</b> — "Payload moddingfromamod:watch_sync may not be sent
     * to the client". Because this is a per-tick broadcast over the whole player list, that made ANY
     * GameTest which puts a mock player in the level fatal, whether or not the test had anything to
     * do with the watch. The failure also names this payload rather than the test that created the
     * player, so it reads as a bug in the watch.
     *
     * <p>Costs a real player nothing: a connected client always has the channel.
     */
    private static void send(ServerPlayer player, Watch watch) {
        com.moddingfromamod.net.Send.to(player,
                com.moddingfromamod.net.ModdedClient.watch(watch.happening(), watch.creations()));
    }

    /**
     * What a child's watch shows: what is happening ({@code WatchRow.Happening} rows) and what he made
     * ({@code WatchRow.Creation} rows), encoded. The modded client is sent it as its watch list (through
     * {@code ModdedClient.watch}); the button chests read it directly.
     */
    public record Watch(List<String> happening, List<String> creations) {
    }

    /** Everything this child should see on his watch, right now. Server thread only. */
    public static Watch forPlayer(ServerPlayer player) {
        List<String> happening = new ArrayList<>();
        for (WishRun run : Marshal.global().liveFor(player.getUUID())) {
            String subject = run.displayName();
            happening.add(new WatchRow.Happening(run.shortId(), subject, percentOf(player, run),
                    "wish").encode());
        }
        return new Watch(happening, creationsFor(player.getStringUUID()));
    }

    /**
     * <b>His things, by his id</b> — what his watch lays out, whether or not he is online
     * (2026-10-12: the account page is sent the same tiles, {@code WatchMenu.planFor(String, int)}).
     */
    public static List<String> creationsFor(String me) {
        // ONE COPY OF HIS RECIPES PER REFRESH (2026-10-31). Each card asked for the whole sorted
        // recipe list twice (asPresets, taught) — ~100 rebuilds per refresh for ~50 things, every
        // half second, which a live server's [tick] sampler caught as one slow tick in ten.
        Recipes outer = RECIPES.get();
        if (outer == null) {
            RECIPES.set(new Recipes(LearnedTemplates.asPresets(), LearnedTemplates.taught(), builderMakes(me)));
        }
        try {
            return creationsForUncached(me);
        } finally {
            if (outer == null) {
                RECIPES.remove();
            }
        }
    }

    /** His recipes for the refresh in progress, read once ({@link #creationsFor}). */
    private record Recipes(List<WishCatalog.Preset> presets,
                           List<com.moddingfromamod.marshal.TaughtJoin.Taught> taught,
                           java.util.Map<String, com.moddingfromamod.wishbuilder.BuilderSpecStore.Made> builder) {
    }

    private static final ThreadLocal<Recipes> RECIPES = new ThreadLocal<>();

    private static List<WishCatalog.Preset> presets() {
        Recipes r = RECIPES.get();
        return r != null ? r.presets() : LearnedTemplates.asPresets();
    }

    private static List<com.moddingfromamod.marshal.TaughtJoin.Taught> taughtAll() {
        Recipes r = RECIPES.get();
        return r != null ? r.taught() : LearnedTemplates.taught();
    }

    /** His kept Builder makes, read once per refresh like his recipes. */
    private static java.util.Map<String, com.moddingfromamod.wishbuilder.BuilderSpecStore.Made> builderMakes(String me) {
        Recipes r = RECIPES.get();
        if (r != null) {
            return r.builder();
        }
        try {
            return com.moddingfromamod.wishbuilder.BuilderSpecStore.all(
                    com.moddingfromamod.runtime.RuntimeConfig.playersDir(), me);
        } catch (RuntimeException e) {
            return java.util.Map.of();   // no shelf here (a gate's world): no Builder recipes, nothing else changes
        }
    }

    private static List<String> creationsForUncached(String me) {

        // HIS things. This listed every creature, building and item in the world, so on a
        // shared server a brother's dragon sat in this child's history as if he had made it (and
        // the retired built-ins did, on every server). The rule is the one the preset row already
        // follows: his own, or a thing nobody owns (made before shelves, or by a gate) --
        // never another child's.
        List<WatchRow.Creation> creations = new ArrayList<>();
        for (Species species : SpeciesRegistry.all()) {
            if (isHis(me, species.ownerId()) && !isTheLibrarys("species", species.id())) {
                creations.add(creature(me, species).withUpdatedAt(
                        SpeciesRegistry.timesOf(species.id()).recency()));
            }
        }
        for (String id : BuildRegistry.ids()) {
            BuildPlan plan = BuildRegistry.get(id);
            if (plan != null && isHis(me, plan.ownerId()) && !isTheLibrarys("buildings", id)) {
                creations.add(building(me, id, plan).withUpdatedAt(
                        BuildRegistry.timesOf(id).recency()));
            }
        }
        // THINGS HE CAN HOLD. Left out until now, which is why a wish for a javelin produced a real
        // item he could use and no card anywhere — the sweep covered every registry except the one
        // holding the items. Nothing server-side could notice: each registry was read correctly.
        for (com.moddingfromamod.item.WishItemCatalogue.Entry e
                : com.moddingfromamod.item.WishItemCatalogue.all()) {
            if (isHis(me, e.ownerId())) {
                String design = com.moddingfromamod.item.ItemDesignId.designIdFor(e.key());
                creations.add(item(me, e).withUpdatedAt(Math.max(
                        com.moddingfromamod.item.WishItemCatalogue.timesOf(e.key()).recency(),
                        com.moddingfromamod.item.ItemSpecStore.timesOf(design).recency())));
            }
        }

        // NEWEST FIRST, and capped. After a summer this is a hundred rows, and the cap has to drop
        // the oldest rather than the newest — what he made this afternoon is what he wants.
        //
        // ONE sort across every kind, by when each thing was made or last changed, and
        // BEFORE the cap. This used to reverse the list it had built kind by kind — items, then
        // trains, machines, buildings, creatures, the creatures in hash order — so "newest" was
        // really "a creature", and the cap could drop his newest creature to keep an old item.
        // A re-teach of a thing's recipe is a change to it too, so it moves to the front.
        List<WatchRow.Creation> timed = new ArrayList<>();
        for (WatchRow.Creation c : creations) {
            timed.add(c.withUpdatedAt(Math.max(c.updatedAt(),
                    LearnedTemplates.timesOf(c.recipeId()).recency())));
        }
        java.util.Collections.reverse(timed);   // unknown ages keep the old newest-added-first
        // what he took off his watch is not on it — nor on his account page, which is laid
        // from this. Before the cap, so a hidden thing never costs him a slot.
        java.util.Set<String> hidden = WatchHides.hiddenFor(me);
        List<String> rows = new ArrayList<>();
        for (WatchRow.Creation c : com.moddingfromamod.marshal.NewestFirst.sort(timed,
                WatchRow.Creation::updatedAt)) {
            if (rows.size() >= com.moddingfromamod.net.ModdedClient.MAX_WATCH_CREATIONS) {
                break;
            }
            if (hidden.contains(c.id())) {
                continue;
            }
            rows.add(c.encode());
        }
        return rows;
    }

    /** How far along, from the plan when there is one — never invented. */
    private static int percentOf(ServerPlayer player, WishRun run) {
        // ITS OWN plan. This read the single per-player plan, so with more than one wish in
        // flight the watch showed every one of them the SAME percentage — the first wish's.
        WishPlan plan = WishProgress.planFor(run.wishId());
        if (plan == null) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(1, plan.fraction())) * 100);
    }

    /**
     * a thing the library serves is the LIBRARY's, not a creation of his — the treehouse and
     * the lava castle every child can wish for, the world's shared record of a library creature a
     * cached wish spawned, and one a cached wish copied onto his shelf before that was fixed. His own change to one is a fork ({@code <id>~<owner>}),
     * which is not a library key, so it still shows.
     */
    static boolean isTheLibrarys(String kind, String id) {
        return !com.moddingfromamod.marshal.ArtifactOwner.isFork(id)
                && com.moddingfromamod.library.WishLibrary.artifactDir(kind, id) != null;
    }

    /** whether a creation belongs in this child's history — his, or nobody's. */
    static boolean isHis(String viewer, String owner) {
        return com.moddingfromamod.marshal.ArtifactOwner.visibleTo(viewer, owner);
    }

    private static WatchRow.Creation creature(String me, Species species) {
        var row = WishCardContract.rowFor("species");
        List<String> vitals = List.of(
                WishCardContract.render(row.vitals().get(0), species.health()),
                WishCardContract.render(row.vitals().get(1), species.damage()),
                WishCardContract.render(row.vitals().get(2),
                        WishCardContract.blocksTall(52.0, species.scale())),
                WishCardContract.render(row.vitals().get(3), species.speed()));
        var tier = WishCardContract.tierFor(
                WishCardContract.creatureScore(species.health(), species.damage()));
        List<String> powers = new ArrayList<>();
        species.powers().forEach(p -> powers.add(p.name().toLowerCase(Locale.ROOT)));
        species.abilities().forEach(a -> powers.add(a.name().toLowerCase(Locale.ROOT)));
        // An egg in the creature's OWN colour, so six creatures are six different eggs.
        // ⚠ Its SCULPT's colour, not species.tint(): a sculpted species' tint is the white default,
        // which drew every agent-made creature as the same white egg; an untinted vanilla-skinned
        // one is that mob's own spawn egg (PresetIcons.creatureIcon).
        return build(me, species.id(), "species", species.name(), vitals, tier.badge(), powers)
                .withIcon(PresetIcons.creatureIcon(species));
    }

    /** A building's numbers and icon, worked out once per PLAN: they are pure functions of it. */
    private record BuildFacts(BuildPlan plan, List<String> vitals, String badge, String icon) {
    }

    /** Id → its facts, recomputed only when the registry hands back a different plan object. */
    private static final java.util.Map<String, BuildFacts> BUILD_FACTS = new java.util.concurrent.ConcurrentHashMap<>();

    private static WatchRow.Creation building(String me, String id, BuildPlan plan) {
        BuildFacts f = BUILD_FACTS.get(id);
        if (f == null || f.plan() != plan) {
            f = buildFacts(plan);
            BUILD_FACTS.put(id, f);
        }
        return build(me, id, "build", plan.name(), f.vitals(), f.badge(), prettyBlocks(plan.blockIds()))
                .withIcon(f.icon());
    }

    private static BuildFacts buildFacts(BuildPlan plan) {
        var row = WishCardContract.rowFor("build");
        int blocks = plan.volume();
        List<String> vitals = List.of(
                WishCardContract.render(row.vitals().get(0), (double) blocks),
                // Footprint and height are a shape rather than a number, so they are written out
                // rather than pushed through the numeric renderer.
                row.vitals().get(1).icon() + " " + footprint(plan),
                row.vitals().get(2).icon() + " " + heightOf(plan) + " blocks tall",
                row.vitals().get(3).icon() + " " + plan.blockIds().size() + " kinds of block");
        var tier = WishCardContract.tierFor(WishCardContract.buildScore(blocks));
        // The block it is mostly made of.
        // counted from the building as it stands, not from shape volumes (ComposedPlan).
        java.util.Map<String, Integer> volume =
                com.moddingfromamod.builder.ComposedPlan.blockCounts(plan);
        return new BuildFacts(plan, vitals, tier.badge(), com.moddingfromamod.marshal.WatchIcons.forBuild(volume));
    }

    /**
     * A kind whose numbers are not on the server in a usable shape yet.
     *
     * <p>Every slot still filled, with a dash where a figure is genuinely unknown — a hole in a
     * fixed grid reads as a bug, and a confidently wrong stats sheet is worse than an incomplete
     * one.
     */
    /**
     * One thing he can hold.
     *
     * <p>Its vitals come from what is actually known about it — the base item it is made from and
     * the effect it carries — rather than being invented to fill the grid; anything unknown renders
     * as the absent marker, which is the same rule every other class follows.
     */
    private static WatchRow.Creation item(String me, com.moddingfromamod.item.WishItemCatalogue.Entry e) {
        // the design by its OWN id — a behaviour key (`<feature>:act`, a datapack's
        // `player_tracker:track`) names its design by its namespace — and the stored stack beside
        // it, which carries everything a design does not: the base item, its enchantments, attack
        // damage, unbreakable, dye.
        var spec = com.moddingfromamod.item.ItemDesignId.designFor(e.key());
        var facts = com.moddingfromamod.item.WishItemCatalogue.factsOf(storedStack(e));
        java.util.Map<String, String> params = spec == null ? java.util.Map.of() : paramsOf(spec.params());
        HandoverRecord record = spec == null ? null : HandoverStore.read(spec.id());
        if (record == null) {
            record = HandoverStore.read(e.key());
        }
        var lines = itemLines(spec, params, facts, record == null ? 1 : record.revision());
        // The item itself, exactly as he holds it.
        return build(me, e.key(), "item", e.name(), lines.vitals(),
                WishCardContract.Tier.COMMON.badge(), lines.stats())
                .withIcon(com.moddingfromamod.marshal.WatchIcons.forItem(e.key()));
    }

    /** The four vitals and the numbers of one item card. */
    record ItemLines(List<String> vitals, List<String> stats) {
    }

    /**
     * <b>An item card's lines</b> from its design (when there is one) and its stored stack (when it
     * decodes) — 2026-10-12 for the design, for the stack. The design's own words win where it
     * has any; the stack fills what it does not say. ✋ and 🔁 are always known, so a card never reads
     * "—" four times: at worst it says what kind of thing it is and that it was made once.
     */
    static ItemLines itemLines(com.moddingfromamod.item.ItemSpecStore.Spec spec,
                               java.util.Map<String, String> params,
                               com.moddingfromamod.marshal.StackVitals.Facts facts, int made) {
        var row = WishCardContract.rowFor("item");
        String fromStack = com.moddingfromamod.marshal.StackVitals.whatItIs(facts);
        String what;
        String does = "";
        String colour = null;
        List<String> stats = new ArrayList<>();
        if (spec != null) {
            does = com.moddingfromamod.marshal.ItemVitals.whatItDoes(spec.effects(), params,
                    ItemCatalogueLines.INSTANCE);
            colour = com.moddingfromamod.marshal.ItemVitals.colour(spec.colour(),
                    com.moddingfromamod.agent.tools.MakeItemTool::colourName);
            what = com.moddingfromamod.marshal.ItemVitals.whatItIs(spec.shape(), spec.baseItem(),
                    spec.count());
            // A plain design is "a keepsake" — unless it is built on a real thing (a pickaxe),
            // which says far more about it.
            if ("plain".equals(spec.shape()) && fromStack != null) {
                what = spec.count() > 1 ? fromStack + " — " + spec.count() + " of them" : fromStack;
            }
            stats.addAll(com.moddingfromamod.marshal.ItemVitals.stats(params));
        } else {
            what = fromStack != null ? fromStack : "a thing you can hold";
        }
        if (does.isBlank()) {
            does = com.moddingfromamod.marshal.StackVitals.whatItDoes(facts);
        }
        if (colour == null) {
            colour = com.moddingfromamod.marshal.StackVitals.colour(facts,
                    com.moddingfromamod.agent.tools.MakeItemTool::colourName);
        }
        // The stack's attack damage, unless the design already named its own.
        boolean namedDamage = stats.stream().anyMatch(x -> x.endsWith("of damage"));
        for (String s : com.moddingfromamod.marshal.StackVitals.stats(facts)) {
            if (!namedDamage && !stats.contains(s)) {
                stats.add(s);
            }
        }
        List<String> vitals = new ArrayList<>();
        vitals.add(row.vitals().get(0).icon() + " " + what);
        vitals.add(does.isBlank()
                ? (spec != null ? row.vitals().get(1).icon() + " nothing — it's yours to keep"
                        : WishCardContract.render(row.vitals().get(1), null))
                : row.vitals().get(1).icon() + " " + does);
        vitals.add(colour == null ? WishCardContract.render(row.vitals().get(2), null)
                : row.vitals().get(2).icon() + " " + colour);
        vitals.add(row.vitals().get(3).icon() + " " + com.moddingfromamod.marshal.ItemVitals.made(made));
        return new ItemLines(vitals, stats);
    }

    /** The catalogue's stack, rebuilt against the running server's registries; EMPTY without one. */
    private static net.minecraft.world.item.ItemStack storedStack(
            com.moddingfromamod.item.WishItemCatalogue.Entry e) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? net.minecraft.world.item.ItemStack.EMPTY
                : com.moddingfromamod.item.WishItemCatalogue.stack(e, server.registryAccess());
    }

    /** A design's parameters as plain strings. */
    public static java.util.Map<String, String> paramsOf(com.google.gson.JsonObject params) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (params != null) {
            for (String k : params.keySet()) {
                var v = params.get(k);
                if (v != null && v.isJsonPrimitive()) {
                    out.put(k, v.getAsString());
                }
            }
        }
        return out;
    }

    /** The item catalogue's {@code child_when} per effect, loaded once. */
    private enum ItemCatalogueLines implements java.util.function.Function<String, String> {
        INSTANCE;

        private final com.moddingfromamod.datapack.ItemCatalogue catalogue =
                com.moddingfromamod.datapack.ItemCatalogue.load();

        @Override
        public String apply(String effect) {
            var entry = catalogue.get(effect);
            return entry == null ? "" : entry.childWhen();
        }
    }

    private static WatchRow.Creation build(String me, String id, String kind, String name,
                                           List<String> vitals, String tier, List<String> powers) {
        HandoverRecord record = HandoverStore.read(id);
        List<String> aliases = record == null ? List.of() : record.aliases();
        String intent = record == null ? "" : record.whatItIs();
        int times = record == null ? 1 : record.revision();
        boolean inFlight = false;
        for (WishRun run : Marshal.global().live()) {
            for (WishRun.Made made : run.made()) {
                if (made.id().equalsIgnoreCase(id)) {
                    inFlight = true;
                }
            }
        }
        // ITS OWN PHOTOGRAPHS. Matched by name rather than migrated: sculpts already shoot
        // four-angle turnarounds keyed to the id, and an agent naming a shot after the thing it
        // photographed lands on the id most of the time anyway. So the whole existing library
        // becomes usable without renaming or moving a single file.
        List<String> photos = com.moddingfromamod.marshal.PhotoIndex.forArtifact(
                com.moddingfromamod.runtime.RuntimeConfig.home().resolve("photos"), id);
        // THE RECIPE THAT BUILT IT, so the card can offer another one instantly and can seed his
        // wish box with words that cannot be mis-matched. Empty when nothing matches confidently —
        // no button at all beats a button that quietly makes the wrong thing.
        //
        // ONE TILE PER THING HE MADE. First the EXPLICIT link: a taught recipe whose own
        // commands replay this very thing (TaughtJoin). That recipe is the one his taught button
        // pressed, so the card's [Make another one] replays it exactly as the button did — and the
        // panel shows no separate button for it (WatchMenu.planFor). Only when no recipe names it
        // does the older word match below still offer one, which is today's behaviour unchanged.
        //
        // PER CHILD: only HIS recipes (or unowned legacy ones) can become his
        // tile's [Make another one] — never a brother's, even one that names the same shared thing.
        var templates = learnedTemplates(me);
        var taught = com.moddingfromamod.marshal.TaughtJoin.visibleTo(me, taughtAll());
        String linked = com.moddingfromamod.marshal.TaughtJoin.recipeFor(id, taught);
        // A WISH AND ITS PARTS. A thing one of his wishes made among others is a PART: its
        // tile re-wishes only it (recipeFor above), and it says which wishes it is part of.
        List<String> partOf = new ArrayList<>();
        for (var wish : com.moddingfromamod.marshal.TaughtJoin.wishesContaining(id, taught)) {
            partOf.add(WishPresets.labelFor(wish.id()));
        }
        com.moddingfromamod.marshal.WishSeed.Template recipe = null;
        if (!linked.isEmpty()) {
            for (var t : templates) {
                if (t.id().equals(linked)) {
                    recipe = t;
                    break;
                }
            }
        }
        // Never the word match for a PART: its words are the whole wish's, and a part tile that
        // re-wished the whole oasis is the bug exists for.
        if (recipe == null && linked.isEmpty() && partOf.isEmpty()) {
            var answersTo = new ArrayList<String>();
            answersTo.add(name);
            answersTo.add(id);
            answersTo.addAll(aliases);
            recipe = com.moddingfromamod.marshal.WishSeed.bestMatch(answersTo, templates);
        }
        // a thing the Builder or the Apprentice made carries its own recipe, the kept make ("builder:<id>"),
        // so its page offers "Make another one" and "Add to hotbar". After a taught recipe that names it (that IS
        // his recipe for it) and before a word match (a guess beats nothing, never an explicit link).
        String builderRecipe = !linked.isEmpty() ? ""
                : com.moddingfromamod.wishbuilder.BuilderAgain.recipeFor(builderMakes(me), id, name);
        if (!builderRecipe.isEmpty()) {
            recipe = null;
        }
        String recipeId = !linked.isEmpty() ? linked : !builderRecipe.isEmpty() ? builderRecipe
                : recipe == null ? "" : recipe.id();
        String seed = recipe == null ? name : String.join(" ", recipe.terms());

        return new WatchRow.Creation(id, kind, name, aliases, vitals, tier, powers, intent,
                times, inFlight, photos, recipeId, seed).withPartOf(partOf);
    }

    /**
     * Every learned recipe, as the terms guaranteed to match it.
     *
     * <p>Read from {@code asPresets()} rather than the raw store because that is already the one
     * place which joins a recipe's {@code all} array into matchable text — the preset a child can
     * press and the seed we put in his box must be the same words, or the button and the sentence
     * disagree about what he owns.
     */
    private static List<com.moddingfromamod.marshal.WishSeed.Template> learnedTemplates(String me) {
        List<com.moddingfromamod.marshal.WishSeed.Template> out = new ArrayList<>();
        for (var preset : presets()) {
            if (!isHis(me, preset.ownerId())) {
                continue;   // a brother's recipe never seeds this child's card
            }
            out.add(new com.moddingfromamod.marshal.WishSeed.Template(
                    preset.templateId(), List.of(preset.wishText().split("\\s+"))));
        }
        return out;
    }

    /**
     * {@code minecraft:stone_bricks} → {@code stone bricks}.
     *
     * <p>A child does not read namespaces, and a card that shows him one is showing him our
     * plumbing. Seen on the first screenshot of the finished card, where the powers row read
     * "minecraft:stone".
     */
    private static List<String> prettyBlocks(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String path = id.substring(id.indexOf(':') + 1).replace('_', ' ').trim();
            // AIR IS NOT A MATERIAL. A hollow building names it as the stuff inside, and a card
            // that lists "air" among what a castle is made of is listing our plumbing again.
            if (path.isEmpty() || path.equals("air") || path.equals("cave air")
                    || path.equals("void air") || out.contains(path)) {
                continue;
            }
            out.add(path);
        }
        return out;
    }

    private static String footprint(BuildPlan plan) {
        int w = 0;
        int d = 0;
        for (var shape : plan.shapes()) {
            w = Math.max(w, shape.width());
            d = Math.max(d, shape.depth());
        }
        return w + " × " + d;
    }

    private static int heightOf(BuildPlan plan) {
        int h = 0;
        for (var shape : plan.shapes()) {
            h = Math.max(h, shape.height());
        }
        return h;
    }

}
