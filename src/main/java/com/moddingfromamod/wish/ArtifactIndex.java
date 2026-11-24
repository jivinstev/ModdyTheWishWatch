package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.marshal.AliasBackfill;
import com.moddingfromamod.marshal.HandoverRecord;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishLadder;
import com.moddingfromamod.marshal.WorldCatalogue;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything the child owns, as the ladder sees it.
 *
 * <p>The ladder is pure and knows nothing about Minecraft; this is the one place that looks at the
 * world and turns it into {@link WishLadder.Candidate}s. Which means the ladder's rules are all
 * unit-tested and this class only has to be <b>honest about what exists and where it is</b>.
 *
 * <p>Two rules it follows, both of which matter more than they look:
 *
 * <ul>
 *   <li><b>Distance is to the thing, not to its design.</b> A creature's position is where its mobs
 *       actually are right now; a building's is the anchor it was raised at. Using a stale or
 *       nominal position makes rung 5 confidently pick the wrong thing, which is the exact bug the
 *       margin rule exists to prevent.</li>
 *   <li><b>In view means he can see it happen.</b> That is the difference between guessing being
 *       free and guessing being invisible, and the ask policy turns on it.</li>
 * </ul>
 */
public final class ArtifactIndex {

    /** Beyond this, a thing is not "one of the ones he might mean" — it is somewhere else entirely. */
    public static final double CONSIDER_RADIUS = 512;

    /** Roughly what is on screen: within this and roughly in front of him. */
    public static final double IN_VIEW_RADIUS = 64;

    /**
     * How many changed mobs to offer the ladder.
     *
     * <p>Small deliberately. A long list of animals makes every rung below NAMED harder — the ask
     * rung in particular has a cap on how many things it is willing to read out — and the useful
     * part of "which one does he mean" is the last few he talked about.
     */
    public static final int MAX_MOB_CANDIDATES = 6;

    private ArtifactIndex() {
    }

    /** Build the candidate list for this child, right now. Server thread only. */
    public static List<WishLadder.Candidate> forPlayer(ServerPlayer player) {
        List<WishLadder.Candidate> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        ServerLevel level = player.level();
        String dim = level.dimension().identifier().toString();

        // ── creatures: where the mobs actually ARE ──────────────────────────────────────────
        //
        // ⚠ FROM THEIR CARRIERS. This walked `WISH_MOB` entities, and
        // there are none — the bridge cancels that join and hangs the creature on a vanilla
        // carrier — so every creature read as distance −1, "in another world", and could never
        // be the nearest thing or in view: "make it bigger" beside his creature meant the castle.
        //
        // AND ONE CREATURE IS ONE REFERENT. A handle that names a creature's carrier (minted by a
        // command diff or a modify_mob) is folded into the species entry below rather than listed
        // again as a "mob", or the ladder asks him to choose between his creature and itself.
        java.util.Map<String, Long> touchedSpecies = new java.util.HashMap<>();
        java.util.Set<String> foldedHandles = new java.util.HashSet<>();
        for (var handle : com.moddingfromamod.mob.EntityHandles.global()
                .targetedRecently(Integer.MAX_VALUE)) {
            String sp = creatureSpeciesOf(level.getServer(), handle);
            if (sp != null) {
                touchedSpecies.merge(sp, handle.lastTargetedMs(), Math::max);
                foldedHandles.add(handle.name());
            }
        }
        var carriers = com.moddingfromamod.mob.Creatures.carriersBySpecies(level);
        for (var species : SpeciesRegistry.all()) {
            String id = species.id();
            String key = id.toLowerCase(java.util.Locale.ROOT);
            double nearest = -1;
            boolean inView = false;
            for (var mob : carriers.getOrDefault(key, List.of())) {
                double d = Math.sqrt(mob.distanceToSqr(player));
                if (nearest < 0 || d < nearest) {
                    nearest = d;
                    inView = d <= IN_VIEW_RADIUS && looksAt(player, mob.getX(), mob.getY(), mob.getZ());
                }
            }
            if (nearest > CONSIDER_RADIUS) {
                continue;
            }
            out.add(new WishLadder.Candidate(id, "species", species.name(), aliasesFor(id),
                    nearest, false, touchedSpecies.getOrDefault(key, 0L), inView,
                    species.ownerId()));
        }

        // ── buildings: where they were raised ──────────────────────────
        for (String id : BuildRegistry.ids()) {
            Long packed = BuildRegistry.anchorOf(id);
            addAt(out, id, "build", nameOf(id), packed == null ? null : BlockPos.of(packed),
                    player, dim, BuildRegistry.ownerOf(id));
        }

        // ── items: the only kind he CARRIES, so "where is it" is always "here" ──────────────
        //
        // Items were missing from this list entirely, and the cost was not subtle. Nothing that
        // reads this index could see them: the wish resolver could not tell which wand "make it
        // hurt way more" meant, the marshal had no artifact to route the refinement by, and the
        // handover brief had no history to hand over. Measured on a five-rung ladder, every
        // refinement routed as `anyone` — 16 of 16 — so the arm under test never bound at all and
        // the run measured nothing. The child ended it holding three wands.
        //
        // NO DISTANCE, deliberately, rather than a made-up one. A building is somewhere and a mob
        // is somewhere; an item is in his hands or in his chest, and the honest answer to "how far
        // away is it" is that the question does not apply. `add` already treats a negative
        // distance as unknown, which is the same thing a building with no recorded anchor gets.
        for (var entry : com.moddingfromamod.item.WishItemCatalogue.all()) {
            add(out, entry.key(), "item", entry.name(), -1, false, dim, entry.ownerId());
        }

        // ── events: the designs this world keeps (C3) ──────────────────────────────────
        //
        // Missing, and it cost a ladder: after "a gold meteor that makes a crater", "make it ice
        // instead" resolved to the floating island at rung NEAREST, because the meteor was not a
        // candidate at all. An event is a DESIGN he can stage again, so, like an item, it has no
        // distance: the running instance is gone in a minute, the design is what "it" means.
        // The library's starters are not his and are not listed.
        for (String id : worldEventDesigns(level.getServer())) {
            add(out, id, "event", nameOf(id), -1, false, dim);
        }

        // ── mobs a wish has CHANGED: the animals "him" can mean ─────────────────────────────
        //
        //. Not every mob nearby — that would put a wandering cow into the running for "make it
        // bigger" against his castle — but every mob a wish has actually acted on, which is a small
        // and deliberate set. Without them here the ladder has nothing to resolve "make him even
        // bigger" TO: it returns NONE, the child is told nothing, routing records no artifact, and
        // three wishes about one animal look in the log like three wishes about nothing. That is
        // an earlier issue's "works for the child, unmeasurable for us", exactly.
        //
        // A mob that has DIED is still listed, with no distance. It has to be: the ladder resolving
        // to it is what lets the answer be "he's gone" instead of a different animal quietly
        // growing. `add` already treats a negative distance as unknown, as it does for a building
        // whose anchor was never recorded.
        for (var handle : com.moddingfromamod.mob.EntityHandles.global()
                .targetedRecently(MAX_MOB_CANDIDATES)) {
            if (foldedHandles.contains(handle.name())) {
                continue;     // a creature: already its species entry above, one referent
            }
            var mob = com.moddingfromamod.mob.EntityCapture.findAnywhere(
                    level.getServer(), handle.id());
            double d = -1;
            boolean inView = false;
            if (mob != null) {
                d = Math.sqrt(mob.distanceToSqr(player));
                inView = d <= IN_VIEW_RADIUS && looksAt(player, mob.getX(), mob.getY(), mob.getZ());
            }
            out.add(new WishLadder.Candidate(handle.name(), "mob",
                    handle.label().isBlank() ? handle.name() : handle.label(),
                    mobAliases(handle), d, false, handle.lastTargetedMs(), inView,
                    handle.ownerId()));
        }

        // a thing he took off his watch is not one of the things he can mean — the ladder
        // must not quietly pick a design he hid. It comes back when he wishes it again.
        java.util.Set<String> hidden = WatchHides.hiddenFor(player.getStringUUID());
        if (!hidden.isEmpty()) {
            out.removeIf(c -> hidden.contains(c.id()));
        }
        return markInFlight(out, player);
    }

    /**
     * <b>Everything he owns, with none of this class's narrowing (item 3).</b>
     *
     * <p>The same five stores {@link #forPlayer} reads, and deliberately <b>not</b> the same list.
     * {@code forPlayer} exists to feed the ladder a decidable set, so it drops anything past
     * {@link #CONSIDER_RADIUS} and keeps only {@link #MAX_MOB_CANDIDATES} changed mobs. Both are
     * right for picking between things and wrong for telling an agent what exists: an earlier issue's recorded
     * failure is <b>a new wish read as a modification</b>, and ruling that out needs the whole
     * world, not the part of it within 512 blocks of wherever the child happened to be standing.
     *
     * <p>So this takes no player and no position. It cannot: a distance is the thing it must not
     * have. That also makes it callable for a wish that never looked contextual at all, which is
     * item 2's "always hand the agent the candidates, <i>whether or not the ladder had an
     * opinion</i>".
     *
     * <p><b>Scope, stated rather than implied:</b> creatures, buildings, items,
     * event designs, the mobs a wish has changed, and the things a rule made — the datapacks
     * this world runs that are not an item's own, and every {@code set_rule} rule.
     *
     * <p>Server thread only, like {@link #forPlayer}.
     */
    public static List<WorldCatalogue.Entry> everything() {
        List<WorldCatalogue.Entry> out = new ArrayList<>();
        for (var species : SpeciesRegistry.all()) {
            out.add(entry(species.id(), "species", species.name(), species.ownerId()));
        }
        for (String id : BuildRegistry.ids()) {
            out.add(entry(id, "build", nameOf(id), BuildRegistry.ownerOf(id)));
        }
        for (var e : com.moddingfromamod.item.WishItemCatalogue.all()) {
            out.add(entry(e.key(), "item", e.name(), e.ownerId()));
        }
        for (String id : worldEventDesigns(com.moddingfromamod.agent.AgentBridge.serverOrNull())) {
            out.add(entry(id, "event", nameOf(id), ""));
        }
        // THINGS A RULE MADE. "make the black hole pull harder" and "make the cloud bounce
        // me higher" are about a datapack or a set_rule rule, and until these were listed plan_wish
        // refused them as `about` ("not one of his things"), so a follow-up could not even declare
        // its target and the agent built a second one. Only what this world RUNS: a datapack that is
        // loaded here and is not an item's own effect pack, and every rule.
        var srv = com.moddingfromamod.agent.AgentBridge.serverOrNull();
        if (srv != null) {
            for (String id : ruleMadeDatapacks(srv, out)) {
                out.add(entry(id, "datapack", nameOf(id), ""));
            }
            try {
                for (var rule : com.moddingfromamod.rules.WorldRules.all(srv)) {
                    out.add(entry(rule.name(), "rule", nameOf(rule.name()), ""));
                }
            } catch (RuntimeException e) {
                // a catalogue without its rules is still a catalogue
            }
        }
        // EVERY changed mob, not MAX_MOB_CANDIDATES of them. The cap is a property of the ask rung,
        // which has to read its options out loud to a child; a note has no such constraint.
        var server = com.moddingfromamod.agent.AgentBridge.serverOrNull();
        for (var handle : com.moddingfromamod.mob.EntityHandles.global()
                .targetedRecently(Integer.MAX_VALUE)) {
            if (creatureSpeciesOf(server, handle) != null) {
                continue;     // a creature is its species entry, not a second "mob"
            }
            out.add(new WorldCatalogue.Entry(handle.name(), "mob",
                    handle.label().isBlank() ? handle.name() : handle.label(),
                    mobAliases(handle), handle.ownerId()));
        }
        // HIS OWN FIRST. Nothing is dropped — an earlier issue's whole point is that the agent must see
        // what EXISTS in order not to read a refinement as a new wish — but the order is the cheap
        // half of "my Spikor means mine": a model reads a list top-down, and with nothing bound
        // this is a no-op.
        // what the bound child HID is not in the catalogue the agent reads, so a wish that
        // names it builds it afresh (and the save un-hides it) instead of reusing the hidden design.
        String viewer = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        java.util.Set<String> hidden = WatchHides.hiddenFor(viewer);
        if (!hidden.isEmpty()) {
            out.removeIf(e -> hidden.contains(e.id()));
        }
        return WorldCatalogue.ownFirst(viewer, out);
    }

    /**
     * The datapacks this world runs that a wish of his authored as a THING: loaded here,
     * not a library starter, and not an item's own effect pack (those are the item). Best-effort.
     */
    private static List<String> ruleMadeDatapacks(net.minecraft.server.MinecraftServer server,
                                                  List<WorldCatalogue.Entry> already) {
        List<String> out = new ArrayList<>();
        try {
            java.util.Set<String> skip = new java.util.HashSet<>();
            for (var e : already) {
                skip.add(e.id().toLowerCase(java.util.Locale.ROOT));
            }
            for (var spec : com.moddingfromamod.item.ItemSpecStore.all()) {
                skip.add(spec.id().toLowerCase(java.util.Locale.ROOT));
            }
            skip.addAll(com.moddingfromamod.library.WishLibrary.keys("datapacks"));
            for (String id : com.moddingfromamod.datapack.DatapackStore.listIds()) {
                if (!skip.contains(id.toLowerCase(java.util.Locale.ROOT))
                        && com.moddingfromamod.datapack.DatapackReloader.isEnabled(server, id)) {
                    out.add(id);
                }
            }
        } catch (RuntimeException e) {
            // the catalogue is context, never a gate
        }
        return out;
    }

    /**
     * <b>Which species a handle names, if it names a wished creature that has a species entry</b>.
     * Asked of the live carrier when there is one — a handle minted before handles were checked recorded
     * the carrier's type — and otherwise of the kind the handle recorded ({@code wishmob:spikor}).
     *
     * @return the species id in lower case, or null for a real mob (or an unknown species)
     */
    private static String creatureSpeciesOf(net.minecraft.server.MinecraftServer server,
                                            com.moddingfromamod.mob.EntityHandles.Handle h) {
        var live = server == null ? null
                : com.moddingfromamod.mob.EntityCapture.findAnywhere(server, h.id());
        String id = null;
        if (live != null) {
            var sp = com.moddingfromamod.mob.Creatures.speciesOf(live);
            id = sp == null ? null : sp.id();
        } else {
            String word = com.moddingfromamod.mob.CreatureMatch.speciesOfKind(h.typeId());
            if (word != null) {
                for (var sp : SpeciesRegistry.all()) {
                    if (com.moddingfromamod.entity.WishMobTags.forSpecies(sp.id())
                            .equals(com.moddingfromamod.entity.WishMobTags.PREFIX + word)) {
                        id = sp.id();
                        break;
                    }
                }
            }
        }
        return id == null ? null : id.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The entry {@link #everything} makes for one wished item — public for the routing harness's
     * world dump ({@code RoutingItemWorldDumpTest}), which has no server to fill the catalogue from
     * and must not write its own copy of how an item is described to the agent.
     */
    public static WorldCatalogue.Entry itemEntry(String key, String name, String owner) {
        return entry(key, "item", name, owner);
    }

    /** Any entry as {@link #everything} makes it — public for the routing harness's referent world. */
    public static WorldCatalogue.Entry entryFor(String id, String kind, String name, String owner) {
        return entry(id, kind, name, owner);
    }

    private static WorldCatalogue.Entry entry(String id, String kind, String name, String owner) {
        return new WorldCatalogue.Entry(id, kind, name, aliasesFor(id), owner);
    }

    /**
     * What a mob answers to: its species word, whatever it is called, and its handle without the
     * {@code @}.
     *
     * <p>Built here rather than through {@link #aliasesFor} because that one derives names from the
     * id, and a mob's id is a handle — deriving from {@code "@panda"} yields {@code "@panda"}, which
     * is not a thing a seven-year-old ever says.
     */
    private static List<String> mobAliases(com.moddingfromamod.mob.EntityHandles.Handle h) {
        List<String> out = new ArrayList<>();
        out.add(h.name().startsWith("@") ? h.name().substring(1).replace('_', ' ') : h.name());
        String type = h.typeId();
        int colon = type.indexOf(':');
        if (colon >= 0) {
            out.add(type.substring(colon + 1).replace('_', ' '));
        }
        if (!h.label().isBlank()) {
            out.add(h.label());
        }
        return out.stream().distinct().filter(a -> !a.isBlank()).toList();
    }

    /**
     * Which of these a wish is currently being granted about.
     *
     * <p>Rung 2 — "it's what we're already making" — is the rung that needs no question at all, so
     * getting this wrong costs a question that did not need asking.
     */
    private static List<WishLadder.Candidate> markInFlight(List<WishLadder.Candidate> all,
                                                           ServerPlayer player) {
        List<WishRun> live = Marshal.global().liveFor(player.getUUID());
        if (live.isEmpty()) {
            return all;
        }
        List<String> beingMade = new ArrayList<>();
        for (WishRun run : live) {
            for (WishRun.Made made : run.made()) {
                beingMade.add(made.id().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (beingMade.isEmpty()) {
            return all;
        }
        List<WishLadder.Candidate> out = new ArrayList<>();
        for (WishLadder.Candidate c : all) {
            boolean flying = beingMade.contains(c.id().toLowerCase(java.util.Locale.ROOT));
            out.add(flying
                    ? new WishLadder.Candidate(c.id(), c.kind(), c.name(), c.aliases(),
                            c.distance(), true, c.lastTouchedMs(), c.inView(), c.ownerId())
                    : c);
        }
        return out;
    }

    private static void addAt(List<WishLadder.Candidate> out, String id, String kind, String name,
                              BlockPos at, ServerPlayer player, String dim) {
        addAt(out, id, kind, name, at, player, dim, "");
    }

    private static void addAt(List<WishLadder.Candidate> out, String id, String kind, String name,
                              BlockPos at, ServerPlayer player, String dim, String owner) {
        if (at == null) {
            add(out, id, kind, name, -1, false, dim, owner);
            return;
        }
        double d = Math.sqrt(player.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        add(out, id, kind, name, d,
                d <= IN_VIEW_RADIUS && looksAt(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5),
                dim, owner);
    }

    private static void add(List<WishLadder.Candidate> out, String id, String kind, String name,
                            double distance, boolean inView, String dim) {
        add(out, id, kind, name, distance, inView, dim, "");
    }

    private static void add(List<WishLadder.Candidate> out, String id, String kind, String name,
                            double distance, boolean inView, String dim, String owner) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (distance > CONSIDER_RADIUS) {
            return;   // not one of the things he might mean; it is somewhere else entirely
        }
        out.add(new WishLadder.Candidate(id, kind, name, aliasesFor(id), distance, false, 0L,
                inView, owner));
    }

    /**
     * The names this thing answers to.
     *
     * <p>From the handover record when there is one — those were authored by the strong model that
     * made the thing, and are the good ones — and derived from the id otherwise, so a thing made
     * before any of this existed still answers to its own name.
     */
    private static List<String> aliasesFor(String id) {
        HandoverRecord record = HandoverStore.read(id);
        if (record != null && !record.aliases().isEmpty()) {
            return record.aliases();
        }
        return AliasBackfill.from(id, nameOf(id), List.of());
    }

    /** The names of the event designs this world keeps; none without a server. */
    private static List<String> worldEventDesigns(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        return new ArrayList<>(com.moddingfromamod.event.design.EventDesigns.store(server).names());
    }

    /**
     * {@code sky_castle} → {@code sky castle}: how he says it out loud. A Builder make's id suffix
     * goes too ({@code cozy_house_3fa2b1} → {@code cozy house}) — it reached his progress bar.
     */
    private static String nameOf(String id) {
        return ChildSubject.words(id);
    }

    /**
     * Is this roughly in front of him?
     *
     * <p>A dot product against his look vector rather than a raycast: a raycast answers "is it
     * unobstructed", which is a different and much stricter question — a dragon behind a tree is
     * still something he can see happen, and the ask policy only needs to know whether a mistake
     * would be visible.
     */
    private static boolean looksAt(ServerPlayer player, double x, double y, double z) {
        var look = player.getLookAngle();
        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) {
            return true;
        }
        double dot = (look.x * dx + look.y * dy + look.z * dz) / len;
        return dot > 0.4;   // a generous cone, because "could he see it" is not "is it centred"
    }
}
