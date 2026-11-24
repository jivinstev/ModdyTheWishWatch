package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Keeping the half-built creature out of the child's sight until the reveal</b> — #515b, the
 * hands for {@link RigVeil}'s rule.
 *
 * <h2>What it is for</h2>
 *
 * <p> stopped pushing the pack to a stock child mid-wish, which removed 2 of his 3 freezes.
 * It did not stop the rig being <em>built in front of him</em>: without the pack those forty-odd
 * {@code item_display} entities render as their base item, and the base item is {@code paper}. The
 * child stood next to a growing heap of white cards for the whole wish. Fixing the freeze and
 * leaving that is fixing the symptom that was measured and not the one he can see.
 *
 * <h2>🔴 Why packets, and not any of the three things you would try first</h2>
 *
 * <ul>
 *   <li><b>Not a per-entity flag.</b> {@code Entity.broadcastToPlayer} is exactly this hook and is
 *       consulted every tick by {@code ChunkMap.TrackedEntity.updatePlayer} — but overriding it
 *       needs our own entity class, and the whole vanilla-lite path exists because the child's
 *       client has never heard of our entity types. A modded {@code EntityType} is unspawnable on
 *       a stock client by construction.</li>
 *   <li><b>Not invisibility or an empty item.</b> Both are properties of the ENTITY, so they hide
 *       it from the camera too — and the camera is the one client that must see it.</li>
 *   <li><b>Not staging it a thousand blocks away and moving it at the reveal.</b> Measured feasible
 *       (docs/CAMERA_CLIENT.md §8) and it breaks the rule CLAUDE.md states outright: a wish
 *       manifests at the spot it was AIMED, locked at wish time. A rig that is somewhere else for
 *       most of its life is a rig whose final position is computed by a move, which is the
 *       mid-air-McTrain bug with extra steps.</li>
 * </ul>
 *
 * <p>So: the entity really is there, at the anchor, the whole time — the child is simply not told
 * about it. That keeps placement, physics, reaping and the photograph path completely unchanged.
 *
 * <h2>Why there is no flash, which is not obvious</h2>
 *
 * <p>{@code ServerLevel}'s entity callbacks call {@code ChunkMap.addEntity}, which calls
 * {@code updatePlayers(level.players())} <b>synchronously inside {@code addFreshEntity}</b>. So the
 * spawn packet is already on the child's connection by the time {@code addFreshEntity} returns —
 * and {@link #veil} runs on the next line, in the same server tick, so the remove rides out in the
 * same flush. The client applies add-then-remove in one packet-handling pass and never renders a
 * frame in between. Measured: no paper in any child screenshot.
 *
 * <p>The per-tick sweep exists for the one case that is not the spawn: a child who walks out of
 * tracking range and back gets a fresh pairing from vanilla, and the sweep answers it within the
 * same tick for the same reason. It costs one packet per veiled client per tick and nothing at all
 * when nothing is veiled.
 *
 * <h2>Why the un-hide hand-rolls the pairing</h2>
 *
 * <p>{@code ChunkMap} still believes the child can see these entities ({@code seenBy} was never
 * touched), so vanilla will not re-send them — and the two methods that would force it
 * ({@code ChunkMap.addEntity}/{@code removeEntity}) are {@code protected}, which would mean an
 * access transformer for two lines. Instead the reveal sends what
 * {@code ServerEntity.sendPairingData} sends for a non-living entity: the add, then the synched
 * data. A {@code Display} has no equipment, no attributes, no effects and no passengers, and its
 * entire appearance — item, transform, view range, brightness — lives in that data. <b>This is why
 * only the DISPLAYS are veiled and never the carrier:</b> a {@code LivingEntity}'s pairing carries
 * effects and attributes too, and the carrier is already invisible by effect, so veiling it would
 * buy nothing and put a much larger packet set on the reveal path.
 */
public final class RigVeilService {

    private RigVeilService() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-rig-veil");

    /** On a veiled creature's carrier: its ability templates stop showing the child. */
    public static final String VEILED_TAG = com.moddingfromamod.datapack.SelectorTags.VEILED_TAG;

    /** On a player the veil hides from: a veiled creature's particles and sounds skip him. */
    public static final String BLIND_TAG = com.moddingfromamod.datapack.SelectorTags.BLIND_TAG;

    /**
     * <b>Hide these parts from anyone who is not getting the pack.</b> Called from
     * {@code DisplayRig.spawn} rather than from a wish tool, because every rig that ever exists
     * goes through that one method — "there is no check" and "the check is not wired to this path"
     * look identical from outside, and this is the only place that cannot be bypassed.
     */
    public static void veil(ServerLevel level, List<Entity> parts) {
        veil(level, parts, null);
    }

    /**
     * The same, for a rig of a known species — and NOT veiled when that species belongs to a wish
     * that has already shown it to him (the early reveal, {@link WishShimmer#inProgress}): he is
     * watching it shimmer into shape, and a repaint that vanished until the end would undo that.
     */
    public static void veil(ServerLevel level, List<Entity> parts, String species) {
        if (level == null || parts == null || parts.isEmpty()) {
            return;
        }
        if (WishShimmer.inProgress(species)) {
            logPlacement(level.getServer(), parts);
            return;
        }
        MinecraftServer server = level.getServer();
        logPlacement(server, parts);
        List<ServerPlayer> hidden = hiddenFrom(server);
        if (hidden.isEmpty()) {
            return;   // nobody is being withheld from: singleplayer, no camera, or already revealed
        }
        List<Integer> ids = new ArrayList<>(parts.size());
        for (Entity e : parts) {
            ids.add(e.getId());
        }
        // The TICK, not just the ids -- ATOM-P3c. `reveal` needs to tell the wish's last word on
        // a creature from the four attempts before it, and WHEN is the only thing that can.
        RigVeil.veil(ids, level.getGameTime());
        int[] all = RigVeil.veiled();
        for (ServerPlayer p : hidden) {
            p.connection.send(new ClientboundRemoveEntitiesPacket(all));
        }
        markEffects(server, parts, hidden);
        LOG.info("veiled {} rig part(s) from {} stock client(s) until the reveal",
                ids.size(), hidden.size());
    }

    /**
     * Answer a re-pairing before the next frame. Runs at the END of the tick, so vanilla's own
     * tracking pass (which runs inside the tick) has already sent whatever it was going to send.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!RigVeil.isVeiling()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<ServerPlayer> hidden = hiddenFrom(server);
        if (hidden.isEmpty()) {
            return;
        }
        int[] all = RigVeil.veiled();
        for (ServerPlayer p : hidden) {
            p.connection.send(new ClientboundRemoveEntitiesPacket(all));
            p.addTag(BLIND_TAG);   // a child who joined mid-wish is spared the effects too
        }
    }

    // ──: what a veiled creature DOES, not just what it looks like ──────────────────────────

    /**
     * Carriers whose abilities are currently withheld from the veiled clients. Held here, not only
     * as a tag, because an entity's tags are SAVED — a server stopped mid-wish would otherwise
     * reload a creature whose fire the child never sees again. {@link #onEntityJoin} strips any
     * {@link #VEILED_TAG} not in this set, and {@link #BLIND_TAG} whenever nothing is veiled.
     */
    private static final java.util.Set<java.util.UUID> VEILED_CARRIERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * <b>Hide the creature's particles and sounds from exactly the players its body is hidden
     * from</b> —, the safe half.
     *
     * <p>Veiling the displays hid the body and nothing it did: every ability template fires
     * {@code particle}/{@code playsound} from the carrier, which stands at the anchor the whole
     * time, so the child watched fire pour out of empty air. The templates now send those two
     * commands, while their carrier wears {@link #VEILED_TAG}, only to players NOT wearing
     * {@link #BLIND_TAG} — which is everyone but the clients this veil withholds from, the camera
     * included. Nothing else changes: the agent's own {@code COMMAND}, and every datapack it writes
     * by hand, run exactly as before (the rest is a T6 milestone with an eval cycle).
     *
     * <p><b>It fails open.</b> A missing tag on either side means the effect reaches everyone,
     * which is the behaviour before this existed — never a camera photographing no fire.
     */
    private static void markEffects(MinecraftServer server, List<Entity> parts,
            List<ServerPlayer> hidden) {
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        for (Entity e : parts) {
            java.util.UUID owner = DisplayRig.ownerOf(e);
            if (owner != null) {
                owners.add(owner);
            }
        }
        for (java.util.UUID owner : owners) {
            Entity carrier = findCarrier(server, owner);
            if (carrier != null) {
                carrier.addTag(VEILED_TAG);
                VEILED_CARRIERS.add(owner);
            }
        }
        for (ServerPlayer p : hidden) {
            p.addTag(BLIND_TAG);
        }
    }

    /** Take both tags off everything that wears them. Called at the reveal. */
    private static void unmarkEffects(MinecraftServer server) {
        for (java.util.UUID owner : List.copyOf(VEILED_CARRIERS)) {
            Entity carrier = findCarrier(server, owner);
            if (carrier != null) {
                carrier.removeTag(VEILED_TAG);
            }
        }
        VEILED_CARRIERS.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.removeTag(BLIND_TAG);
        }
    }

    /**
     * Strip a tag that outlived the veil that set it — a server stopped mid-wish saves both. A
     * stale one fails open on its own (see {@link #markEffects}); this keeps the pair honest.
     */
    @SubscribeEvent
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (e.getTags().contains(VEILED_TAG) && !VEILED_CARRIERS.contains(e.getUUID())) {
            e.removeTag(VEILED_TAG);
        }
        if (e instanceof ServerPlayer && e.getTags().contains(BLIND_TAG) && !RigVeil.isVeiling()) {
            e.removeTag(BLIND_TAG);
        }
    }

    /**
     * <b>The reveal: everyone sees the creature — and ONLY the creature.</b> Called from
     * {@code RigReveal.reveal} so the pack and the sight are released in the same instant — a
     * child who got the models but not the entities would be standing next to nothing, and one who
     * got the entities but not the models would get the paper this class exists to prevent.
     *
     * <h2>🔴 ATOM-P3c — why this reaps before it releases</h2>
     *
     * <p>{@link RigVeil}'s contract is <i>"un-hide everything still veiled"</i>, and the first cut
     * read that as "send an add for every id in the ledger". Measured on a real five-sculpt wish:
     * <b>290 parts released at once</b>, so the child was shown four spare winged Spikors standing
     * beside the real one on the very beat he is told to look, and they stayed for nineteen seconds
     * ({@code docs/img/atom-rig/veil/atom2-child-reveal-022406.png}).
     *
     * <p><b>They were not interim sculpts.</b> A sculpt superseded in place is already gone —
     * {@code VanillaRigAnimator.rebuildIfRewished} reaps its displays and discards its carrier, so
     * {@link #findEntity} answers null and nothing is sent for it. What was released was five
     * <em>live creatures</em>: the agent could not confirm its own spawn and ran
     * {@code wishmob spawn winged_spikor} four times in sixteen seconds, and the "reaper" that
     * cleared them nineteen seconds later was the agent's own {@code kill @e[tag=…]} reaching
     * {@code VanillaRigBridge.onLeave}. <b>Nothing in the mod owned their lifetime.</b>
     *
     * <h2>The rule, and the two rules it is deliberately not</h2>
     *
     * <p><b>Per species, the newest TICK wins; everything older is the wish's own scaffolding and
     * is reaped.</b> Three things make that safe rather than a guess:
     *
     * <ul>
     *   <li><b>Only a VEILED rig is a candidate.</b> A veiled rig is by construction one the child
     *       has never seen — it was built while he was being withheld from, during the wish being
     *       revealed. Reaping one takes away nothing he knows about. Anything from an earlier wish
     *       left the ledger at that wish's reveal.</li>
     *   <li><b>Per SPECIES.</b> "Make me a Spikor and a Kroak" must end with two creatures, so a
     *       rule that kept only the newest rig outright would grant half the wish, invisibly and
     *       for ever. Measured cost of getting this wrong: worse than the defect being fixed.</li>
     *   <li><b>By TICK, not by rig.</b> {@code /wishmob spawn spikor 5} is five rigs in one tick and
     *       all five were asked for; five spawns four seconds apart are one creature and four
     *       retries.</li>
     * </ul>
     *
     * <p>It <b>reaps</b> rather than leaving the extras veiled, and that is not a preference: a
     * veiled creature is solid, hostile and invisible. A 300-health Spikor the child can walk into
     * and be hit by and never see is a worse wish than the duplicate.
     *
     * <p>Known cost, stated rather than hidden: a wish that deliberately spawns several of ONE
     * species in separate ticks while the child is veiled ends with the last batch. Nothing on the
     * shipped path does that — {@code sculpt_creature} spawns exactly one, at the locked anchor —
     * and a child can spawn as many as he likes from the egg once the wish is over.
     *
     * <p><b>That cost was put to a real agent rather than left as an argument.</b>
     * {@code make me three Spikors with huge wings}, stock child, camera serving: the agent spawned
     * and killed its way through <b>eighteen</b> rigs to land on three, and the reveal released
     * {@code 198 of 1188} parts with {@code reaped 0} — all three survived, because the final
     * {@code sculpt_creature} rebuilt them together in one animator pass and they therefore share a
     * tick. That is what the tick grouping is for. (docs/img/atom-rig/veil/README.md)
     */
    public static void reveal(MinecraftServer server) {
        java.util.Map<Integer, Long> ledger = RigVeil.unveil();
        if (server != null) {
            unmarkEffects(server);   //: the child sees and hears it again from now on
        }
        if (server == null || ledger.isEmpty()) {
            return;
        }
        // ── 1. what is still standing, and which creature does each part hang off ──────────────
        java.util.Map<java.util.UUID, Standing> rigs = new java.util.LinkedHashMap<>();
        List<Entity> orphans = new ArrayList<>();
        for (var row : ledger.entrySet()) {
            Entity e = findEntity(server, row.getKey());
            if (e == null) {
                continue;   // reaped, or its sculpt was superseded in place: nothing to show
            }
            java.util.UUID owner = DisplayRig.ownerOf(e);
            if (owner == null) {
                orphans.add(e);   // not part of a rig we can group; released untouched
                continue;
            }
            Standing rig = rigs.get(owner);
            if (rig == null) {
                rig = new Standing(owner, findCarrier(server, owner), row.getValue());
                rigs.put(owner, rig);
            }
            rig.parts.add(e);
            rig.tick = Math.min(rig.tick, row.getValue());
        }
        // ── 2. per creature, the newest tick is the wish's last word ──────────────────────────
        java.util.Map<String, Long> newest = new java.util.HashMap<>();
        for (Standing rig : rigs.values()) {
            newest.merge(rig.key(), rig.tick, Math::max);
        }
        // ── 3. reap the scaffolding, BEFORE anything goes out on a wire ───────────────────────
        int reaped = 0;
        for (Standing rig : rigs.values()) {
            if (rig.tick < newest.get(rig.key())) {
                rig.reap();
                reaped++;
            }
        }
        // ── 4. release what survived ──────────────────────────────────────────────────────────
        List<Entity> release = new ArrayList<>(orphans);
        for (Standing rig : rigs.values()) {
            if (rig.tick >= newest.get(rig.key())) {
                release.addAll(rig.parts);
            }
        }
        int sent = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            // Only the stock route. A modded client was never veiled -- it has been rendering
            // these parts all along -- and re-sending an add for an entity a client already holds
            // makes it replace the instance, which is a visible blink in the camera's frame at the
            // exact moment the agent may be photographing. Narrow rather than idempotent.
            if (PackDelivery.routeFor(com.moddingfromamod.net.Send.hasOurJar(p))
                    == PackDelivery.Route.DIRECT) {
                continue;
            }
            for (Entity e : release) {
                if (!e.isAlive()) {
                    continue;
                }
                // The exact form: (entity, data, blockPosition) is the HANGING-entity
                // constructor and sends the block's corner, so a part that holds still would
                // stay up to a block off on a stock client until something moved it (#59).
                p.connection.send(new ClientboundAddEntityPacket(e.getId(), e.getUUID(),
                        e.getX(), e.getY(), e.getZ(), e.getXRot(), e.getYRot(), e.getType(), 0,
                        e.getDeltaMovement(), e.getYHeadRot()));
                p.connection.send(new ClientboundSetEntityDataPacket(
                        e.getId(), e.getEntityData().getNonDefaultValues()));
                sent++;
            }
        }
        LOG.info("REVEAL: released {} of {} veiled rig part(s) to the world ({} pairing(s) sent); "
                        + "reaped {} superseded rig(s) the child was never shown",
                release.size(), ledger.size(), sent, reaped);
    }

    /**
     * One rig standing in the world at the reveal: its carrier, its still-live parts, and the tick
     * it was veiled at. Built from the world rather than from the ledger, so {@link RigVeil} stays
     * a ledger of ids that has never heard of a creature.
     */
    private static final class Standing {
        private final java.util.UUID owner;
        private final Entity carrier;
        private final List<Entity> parts = new ArrayList<>();
        private long tick;

        private Standing(java.util.UUID owner, Entity carrier, long tick) {
            this.owner = owner;
            this.carrier = carrier;
            this.tick = tick;
        }

        /**
         * What counts as "the same creature". The species when the carrier can name one; otherwise
         * the carrier's own id, which is unique — so a rig we cannot identify is never grouped with
         * anything and therefore never reaped. Wrong in the direction that shows too much.
         */
        private String key() {
            var sp = DisplayRig.speciesOn(carrier);
            return sp != null ? "species:" + sp.id() : "carrier:" + owner;
        }

        /**
         * Take it out of the world. The carrier goes first through {@code DisplayRig.reap} and then
         * {@code discard()} — reaping explicitly rather than trusting
         * {@code VanillaRigBridge.onLeave} to fire, because "there is no check" and "the check is
         * not wired to this path" look identical from outside; the reaper is idempotent.
         */
        private void reap() {
            if (carrier != null && carrier.level() instanceof ServerLevel level) {
                DisplayRig.reap(level, owner);
                carrier.discard();
            }
            for (Entity e : parts) {
                if (e.isAlive()) {
                    e.discard();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RigVeil.forget();
        VEILED_CARRIERS.clear();
    }

    /**
     * <b>Where the creature landed, and how far that is from everybody watching.</b>
     *
     * <p>Not decoration. The question a parent asks of this feature is "did it appear where he
     * pointed", and the answer has to survive the reveal — a design that hid the sculpt by staging
     * it somewhere else and moving it at the end would look identical in every photograph and be
     * wrong about the only thing that matters. One line per sculpt, naming the spot and the
     * distance to each viewer with the camera marked, is what makes that checkable from a log
     * rather than from a screenshot somebody has to interpret.
     *
     * <p>The camera's distance is expected to be LARGE and to vary: it is teleported to frame each
     * shot. The child's is expected to be small and CONSTANT across every sculpt of one wish,
     * because the anchor is locked at wish time.
     */
    private static void logPlacement(MinecraftServer server, List<Entity> parts) {
        if (server == null || parts.isEmpty()) {
            return;
        }
        Entity any = parts.get(0);
        StringBuilder who = new StringBuilder();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean camera = !com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID());
            who.append(who.isEmpty() ? "" : ", ")
                    .append(p.getGameProfile().getName())
                    .append(camera ? " (camera)" : " (child)")
                    .append(" ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", p.distanceTo(any)))
                    .append("m");
        }
        LOG.info("PLACEMENT: {} part(s) at {} {} {} -- {}",
                parts.size(),
                String.format(java.util.Locale.ROOT, "%.2f", any.getX()),
                String.format(java.util.Locale.ROOT, "%.2f", any.getY()),
                String.format(java.util.Locale.ROOT, "%.2f", any.getZ()),
                who.isEmpty() ? "nobody connected" : who);
    }

    /** Every connected player the pack is currently being withheld from. */
    private static List<ServerPlayer> hiddenFrom(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        // The LIVE answer, for the same reason RigReveal reads it live: a camera spends ~13 s
        // joining, can die, and can be given up on, and in every one of those windows the child IS
        // the camera and must see what he is photographing.
        boolean cameraServing = com.moddingfromamod.runtime.RuntimeConfig.deferPack()
                && com.moddingfromamod.wish.CameraSupervisor.serving(server);
        boolean revealed = RigVeil.alreadyRevealed(
                RigReveal.currentSha1(), RigReveal.revealedSha1());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean hasChannel = com.moddingfromamod.net.Send.hasOurJar(p);
            boolean modded = PackDelivery.routeFor(hasChannel) == PackDelivery.Route.DIRECT;
            if (RigVeil.hiddenFrom(modded, cameraServing, revealed)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The carrier by uuid, across every loaded level. Null when it has already gone. */
    private static Entity findCarrier(MinecraftServer server, java.util.UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) {
                return e;
            }
        }
        return null;
    }

    private static Entity findEntity(MinecraftServer server, int id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) {
                return e;
            }
        }
        return null;
    }
}
