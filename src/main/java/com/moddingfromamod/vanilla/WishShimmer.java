package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A creature that is still being wished into shape: shown, shimmering and sparkling</b>.
 *
 * <p>Until this, a creature was hidden from a stock child until {@code finish_building} played the
 * reveal (an earlier issue's veil), and the pillar stood in its place. On one filmed demo the camera never
 * joined, the veil never went up, and he watched the dragon appear 35 s in and get painted gold:
 * it felt fast. This makes that the rule on every server. The wish's FIRST creature standing in the
 * world plays the pillar's reveal ({@code WishEarlyReveal}); from then until the wish ends, its
 * species is IN PROGRESS. A creature of that species:
 * <ul>
 *   <li>is never veiled — a repaint appears at once ({@link RigVeilService#veil});</li>
 *   <li>shimmers ({@link RigShimmer}) and gives off sparkles, so "still being made" is legible
 *       without reading — the chat line says the same in words;</li>
 *   <li>is drawn in its own colours whenever a camera is shooting, because a rainbow-tinted
 *       creature in a mid-build photograph would give the agent the wrong colours to judge.</li>
 * </ul>
 *
 * <p>The veil also reaped SCAFFOLDING at the reveal (ATOM-P3c: an agent that could not confirm its
 * own spawn ran it four more times). A shown creature is not in the veil's ledger, so the same rule
 * — per species, the newest tick wins — runs here at {@link #end}, over the rigs this wish stood up.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishShimmer {

    private WishShimmer() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-shimmer");

    /** Species id → the wish that is still shaping it. */
    private static final Map<String, UUID> IN_PROGRESS = new ConcurrentHashMap<>();

    /** Wish → the game tick its first creature stood, so older creatures are never its scaffolding. */
    private static final Map<UUID, Long> SINCE = new ConcurrentHashMap<>();

    /** Every rig stood up, by carrier: its species and its tick. Server thread; pruned. */
    private static final Map<UUID, Spawned> SPAWNED = new HashMap<>();

    /**
     * {@code joins}: added to a herd by {@code wishmob spawn} ({@link #joinsHerd}) — never anybody's scaffolding, and
     * never the "newer copy" that makes an older one scaffolding.
     */
    private record Spawned(String species, long tick, boolean joins) {
    }

    /** Sparkles every this many ticks, on the ticks the colour does not change. */
    static final int SPARKLE_EVERY = 4;

    /** Species → the game tick a polish glint ends (WishPolish). Server thread. */
    private static final Map<String, Long> POLISH = new ConcurrentHashMap<>();

    /** How long a polish glints: two turns of the rainbow, then its own colours again. */
    public static final int POLISH_TICKS = 2 * RigShimmer.PERIOD_TICKS;

    /**
     * <b>A short glint over a creature just polished in place</b> after the wish ended: "something subtle like shimmering and sparkles, just to indicate a change was
     * made". Unlike {@link #begin} it hides nothing and ends nothing; it simply runs out.
     */
    public static void pulse(String species, long gameTime) {
        if (species != null) {
            POLISH.put(species, gameTime + POLISH_TICKS);
        }
    }

    private static boolean polishing(String species, long gameTime) {
        Long until = species == null ? null : POLISH.get(species);
        if (until == null) {
            return false;
        }
        if (gameTime > until) {
            POLISH.remove(species, until);
            return false;
        }
        return true;
    }

    /** Is this species being shaped by a wish that has already shown it? */
    public static boolean inProgress(String species) {
        return species != null && IN_PROGRESS.containsKey(species);
    }

    /** The species a wish is still shaping. */
    public static Set<String> speciesOf(UUID wishId) {
        Set<String> out = new HashSet<>();
        for (var e : IN_PROGRESS.entrySet()) {
            if (e.getValue().equals(wishId)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ── THE PREVIEW ─────────────────────────────────────────────────────────
    //
    // sculpt_creature's own schema tells the agent to pass spawn:false for any creature that still
    // needs powers, and to spawn it "only at the end … so it arrives already able to do the thing
    // the child asked for": a creature standing around with no powers is one he can kill before it
    // ever works. Filmed on Spikor (2026-10-22): Opus sculpted at 0:48 and spawned at 0:59, and Spark
    // sculpted four times and wrote a datapack before spawning at 1:20, 2 s before "All done". The
    // early reveal waits for a creature to STAND, so for every creature with a power it came at the
    // end. The fix keeps the rule's reason and drops its cost: a creature designed but not spawned
    // stands as a PREVIEW, at the wish's own spot, shimmering, invulnerable and unable to act (no
    // AI, silent), so it can be watched but not killed or set off. When the agent spawns the real
    // one, the preview is replaced on the next tick; if the agent never spawns one, the preview IS
    // the creature at the end, made whole and recorded as the wish's own.

    /** Worn by a preview's carrier (and carried across a rebuild by CarrierState, like any tag). */
    public static final String PREVIEW_TAG = "mfam_wish_preview";

    /** Species with a preview standing somewhere. Server thread. */
    private static final Set<String> PREVIEW_SPECIES = ConcurrentHashMap.newKeySet();

    /** Species → the carrier that just stood: any OTHER preview of that species goes next tick. */
    private static final Map<String, UUID> SUPERSEDE = new ConcurrentHashMap<>();

    /**
     * Stand a preview of {@code species} at the wish's spot for {@code playerId}. Server thread.
     *
     * @return the preview's carrier, or null if it could not be stood up (nobody there, no room)
     */
    public static net.minecraft.world.entity.LivingEntity preview(MinecraftServer server, UUID playerId,
            String species, String at) {
        var player = com.moddingfromamod.agent.ActingChild.of(server, playerId);
        if (player == null || species == null || species.isBlank()) {
            return null;
        }
        var seat = SpotSeat.choose(server, player, at == null ? "" : at, species, 0);
        // forTheWish=false: a preview is not the wish's spawn (finish_wish's "is the creature it
        // spawned standing", an undo). It becomes one at end() if the agent never spawns the real one.
        var carrier = CreatureSpawn.spawn(seat.level(), species, seat.x(), seat.y(), seat.z(), seat.yaw(),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND, false);
        if (carrier == null) {
            return null;
        }
        carrier.addTag(PREVIEW_TAG);
        carrier.setInvulnerable(true);
        carrier.setSilent(true);
        if (carrier instanceof Mob m) {
            m.setNoAi(true);
        }
        PREVIEW_SPECIES.add(species);
        SafePlacement.settleSpawn(player, carrier, true);
        return carrier;
    }

    private static boolean isPreview(Entity e) {
        return e != null && e.getTags().contains(PREVIEW_TAG);
    }

    /** A preview made whole: it can be hurt, moves and makes its sounds. */
    private static void makeReal(Entity e) {
        e.removeTag(PREVIEW_TAG);
        e.setInvulnerable(false);
        e.setSilent(false);
        if (e instanceof Mob m) {
            m.setNoAi(false);
        }
    }

    /** Called from {@code DisplayRig.spawn}, for every rig. Server thread. */
    static void spawned(String species, UUID carrier, long tick) {
        if (species == null || carrier == null) {
            return;
        }
        if (PREVIEW_SPECIES.contains(species)) {
            SUPERSEDE.put(species, carrier);
        }
        synchronized (SPAWNED) {
            SPAWNED.put(carrier, new Spawned(species, tick, false));
            if (SPAWNED.size() > 4096) {
                SPAWNED.entrySet().removeIf(e -> e.getValue().tick() < tick - 72_000L);
            }
        }
    }

    /**
     * <b>This carrier was ADDED to a herd</b> ({@code wishmob spawn <id> [count]}). Server thread.
     *
     * <p>A herd is made in two steps — the sculpt stands the first, then {@code wishmob spawn <id> N-1} the rest a few
     * ticks later (the Builder's lowering, and a wish worker the same way). The reaper at {@link #end} keeps only the
     * NEWEST copy of a species the wish stood up, so it took the herd's first member for scaffolding whenever the wish's
     * start ({@link #begin}) was stamped on that member's own tick — a race with the early reveal's task, lost about one
     * run in six in a gate run, and a herd of three became two. A copy asked for by count is one MORE, never a
     * replacement: it is not scaffolding, and it does not make an earlier copy scaffolding.
     */
    public static void joinsHerd(UUID carrier) {
        if (carrier == null) {
            return;
        }
        synchronized (SPAWNED) {
            Spawned s = SPAWNED.get(carrier);
            if (s != null) {
                SPAWNED.put(carrier, new Spawned(s.species(), s.tick(), true));
            }
        }
    }

    /**
     * <b>A creature that was already standing was rebuilt as a new carrier</b> (an edit's new shape,
     * or a save rebuilt on load; {@code VanillaRigAnimator.respawn}). Server thread.
     *
     * <p>A rebuild discards the carrier and stands a new one, which {@link #spawned} cannot tell from
     * a birth. Taken for one, an edited creature looked like something the wish stood up: it
     * superseded a preview, and at {@link #end} every copy but the newest was reaped as scaffolding,
     * so editing his dragon could take his other dragons with it (any copy rebuilt a tick later,
     * one whose chunk loaded late, was "newer"). So the new carrier keeps the birth the old one had,
     * and one with no recorded birth (older than this server's run) is never anybody's scaffolding.
     */
    static void rebuilt(UUID was, UUID now) {
        if (was == null || now == null) {
            return;
        }
        Spawned made;
        synchronized (SPAWNED) {
            Spawned born = SPAWNED.remove(was);
            made = SPAWNED.remove(now);
            if (born != null) {
                SPAWNED.put(now, born);
            }
        }
        if (made != null) {
            SUPERSEDE.remove(made.species(), now);
        }
    }

    /** For tests: the game tick this carrier's creature was born, carried across rebuilds; or null. */
    static Long bornTick(UUID carrier) {
        synchronized (SPAWNED) {
            Spawned s = SPAWNED.get(carrier);
            return s == null ? null : s.tick();
        }
    }

    /** Does any creature of this species stand in a loaded world, a preview included? Server thread. */
    public static boolean standing(MinecraftServer server, String species) {
        if (server == null || species == null) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob m) {
                    var sp = DisplayRig.speciesOn(m);
                    if (sp != null && species.equals(sp.id())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * These species now stand in the world for this wish and he has been shown them. From here they
     * shimmer until {@link #end}.
     *
     * @param sinceTick the game tick the wish's first creature stood
     */
    public static void begin(UUID wishId, Collection<String> species, long sinceTick) {
        if (wishId == null || species == null) {
            return;
        }
        SINCE.putIfAbsent(wishId, sinceTick);
        for (String s : species) {
            IN_PROGRESS.put(s, wishId);
        }
    }

    /**
     * The wish is finished (or gone): its creatures stop shimmering and stand in their own colours,
     * and anything it stood up and then superseded with a later copy of the same species is
     * reaped. Server thread.
     *
     * @return how many scaffolding creatures were reaped
     */
    public static int end(MinecraftServer server, UUID wishId) {
        if (wishId == null) {
            return 0;
        }
        Set<String> species = speciesOf(wishId);
        IN_PROGRESS.values().removeIf(wishId::equals);
        Long since = SINCE.remove(wishId);
        if (server == null || species.isEmpty()) {
            return 0;
        }
        // 1. every live rig of these species back to its own colours
        Map<UUID, Entity> carriers = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob m) {
                    var sp = DisplayRig.speciesOn(m);
                    if (sp != null && species.contains(sp.id())) {
                        carriers.put(m.getUUID(), m);
                        if (isPreview(m)) {
                            // The agent never spawned the real one: the preview is the creature.
                            makeReal(m);
                            PREVIEW_SPECIES.remove(sp.id());
                            for (var run : com.moddingfromamod.marshal.Marshal.global().all()) {
                                if (run.wishId().equals(wishId)) {
                                    run.creatureSpawned(sp.id(), m.getUUID());
                                    if (run.playerId() != null) {
                                        com.moddingfromamod.mob.Creatures.stampSpawner(m,
                                                run.playerId().toString());
                                    }
                                }
                            }
                        }
                        DisplayRig rig = VanillaRigAnimator.rigOf(m);
                        if (rig != null) {
                            rig.clearShimmer();
                        }
                    }
                }
            }
        }
        // 2. scaffolding: per species, of the rigs THIS wish stood up, the newest tick wins
        int reaped = 0;
        if (since != null) {
            Map<String, Long> newest = new HashMap<>();
            List<Map.Entry<UUID, Spawned>> mine = new ArrayList<>();
            synchronized (SPAWNED) {
                for (var e : SPAWNED.entrySet()) {
                    Spawned s = e.getValue();
                    if (!s.joins() && s.tick() >= since && species.contains(s.species())
                            && carriers.containsKey(e.getKey())) {
                        mine.add(Map.entry(e.getKey(), s));
                        newest.merge(s.species(), s.tick(), Math::max);
                    }
                }
            }
            for (var e : mine) {
                if (e.getValue().tick() < newest.get(e.getValue().species())) {
                    Entity carrier = carriers.get(e.getKey());
                    if (carrier.level() instanceof ServerLevel level) {
                        DisplayRig.reap(level, carrier.getUUID());
                        carrier.discard();
                        reaped++;
                    }
                }
            }
        }
        LOG.info("shimmer: wish {} finished shaping {} -- back to its own colours, reaped {} "
                + "superseded copy(ies)", wishId.toString().substring(0, 8), species, reaped);
        return reaped;
    }

    /**
     * One tick of a driven rig ({@code VanillaRigAnimator}), after the hurt flash had its chance.
     *
     * @return how many displays were rewritten
     */
    public static int drive(DisplayRig rig, Mob base, String species, long gameTime) {
        if (!(inProgress(species) || polishing(species, gameTime))
                || com.moddingfromamod.wish.WishCapture.anyShooting()) {
            // Its own colours. Free when they already are (DisplayRig.tint skips an unchanged
            // tint), and it also puts back the last frame of a hurt flash: RigDriver.dying stops
            // being called on the tick hurtTime reaches 0, so nothing else would.
            return rig.tint(RigTint.NONE);
        }
        if (Math.floorMod(gameTime, SPARKLE_EVERY) == SPARKLE_EVERY / 2
                && base.level() instanceof ServerLevel level) {
            sparkle(level, base);
        }
        return RigShimmer.writesOn(gameTime) ? rig.shimmer(gameTime) : 0;
    }

    /** A few glints over the creature's body: vanilla particles, so every client draws them. */
    private static void sparkle(ServerLevel level, Mob base) {
        var box = base.getBoundingBox();
        double w = Math.max(0.5, box.getXsize() / 2), h = Math.max(0.5, box.getYsize() / 2);
        double d = Math.max(0.5, box.getZsize() / 2);
        var c = box.getCenter();
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 2, w, h, d, 0.01);
        level.sendParticles(ParticleTypes.WAX_ON, c.x, c.y, c.z, 3, w, h, d, 0.5);
    }

    /**
     * Backstop: a wish that ended without {@code finish_building} (cancelled, failed, settled by
     * {@code finish_wish} alone) still stops shimmering. Twice a second is plenty.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SUPERSEDE.isEmpty()) {
            replacePreviews(event.getServer());
        }
        if (IN_PROGRESS.isEmpty() || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        Set<UUID> live = new HashSet<>();
        for (var run : com.moddingfromamod.marshal.Marshal.global().all()) {
            if (run.state().isLive()) {
                live.add(run.wishId());
            }
        }
        for (UUID wish : new HashSet<>(IN_PROGRESS.values())) {
            if (!live.contains(wish)) {
                end(event.getServer(), wish);
            }
        }
    }

    /** The real creature stood: every OTHER preview of its species goes, in a puff. */
    private static void replacePreviews(MinecraftServer server) {
        Map<String, UUID> now = new HashMap<>(SUPERSEDE);
        SUPERSEDE.keySet().removeAll(now.keySet());
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> gone = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Mob m) || !isPreview(m)) {
                    continue;
                }
                var sp = DisplayRig.speciesOn(m);
                if (sp != null && now.containsKey(sp.id()) && !now.get(sp.id()).equals(m.getUUID())) {
                    gone.add(m);
                }
            }
            for (Entity e : gone) {
                level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1, e.getZ(), 12, 0.6, 0.8, 0.6, 0.02);
                DisplayRig.reap(level, e.getUUID());
                e.discard();
            }
        }
        for (var e : now.entrySet()) {
            Entity stood = null;
            for (ServerLevel level : server.getAllLevels()) {
                stood = stood != null ? stood : level.getEntity(e.getValue());
            }
            if (stood != null && !isPreview(stood)) {
                PREVIEW_SPECIES.remove(e.getKey());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PREVIEW_SPECIES.clear();
        SUPERSEDE.clear();
        IN_PROGRESS.clear();
        POLISH.clear();
        SINCE.clear();
        synchronized (SPAWNED) {
            SPAWNED.clear();
        }
    }

    /** For tests: forget everything. */
    public static void forgetForTest() {
        PREVIEW_SPECIES.clear();
        SUPERSEDE.clear();
        IN_PROGRESS.clear();
        POLISH.clear();
        SINCE.clear();
        synchronized (SPAWNED) {
            SPAWNED.clear();
        }
    }
}
