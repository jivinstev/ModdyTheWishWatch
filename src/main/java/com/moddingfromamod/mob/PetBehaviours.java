package com.moddingfromamod.mob;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>The half of {@link PetBehaviour} that moves mobs</b>: follow the child, never attack a
 * player, carry him, and find things for him.
 *
 * <p>How it stays cheap: a mob with behaviour carries it in its persistent data, so it survives
 * saves and chunk unloads, and it is TRACKED from the moment it joins a level until it leaves.
 * Only tracked mobs are looked at, and only every {@value #EVERY} ticks. A ridden one is looked at
 * every tick, because steering at a quarter of the frame rate feels broken.
 *
 * <p>Two things that are NOT done here, on purpose:
 * <ul>
 *   <li><b>No goals are added to the mob.</b> Half the mobs a child names (warden, hoglin, axolotl,
 *       frog, allay) run a {@code Brain}, not goals, and a goal added beside a brain fights it.
 *       Moving the mob's navigation, and setting a brain's {@code WALK_TARGET} when it has one,
 *       works for both kinds.</li>
 *   <li><b>No client code.</b> A mob a player rides without being its controlling passenger is
 *       moved by the server, so a stock client (Deployment 1) steers it by holding W. A player's
 *       BOAT is the opposite (the client simulates it), which is why {@code boat_physics} is not a
 *       part anyone can build server-side ({@code docs/WISH_PARTS_KITS.md}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PetBehaviours {

    /** The behaviour, as JSON, on the mob. */
    static final String KEY = "mfam_pet";
    /** Whose pet it is (a UUID), when it was made for one child. */
    public static final String OWNER = "mfam_pet_owner";

    /** How often, in ticks, a pet is looked at when nobody is riding it. */
    static final int EVERY = 5;
    /** How often a finder looks around; scanning a few thousand blocks is not a per-tick job. */
    static final int FIND_EVERY = 40;

    /** Tracked pets, by level. Entities, not ids, so a lookup is not needed every tick. */
    private static final Map<ServerLevel, Set<Mob>> PETS = new HashMap<>();
    /** What each finder is currently showing him, and whether WE switched its glow on. */
    private static final Map<UUID, BlockPos> FOUND = new HashMap<>();
    private static final Set<UUID> GLOW_BY_US = new HashSet<>();

    private PetBehaviours() {
    }

    // ── writing it onto a mob ───────────────────────────────────────────────────────────────────

    /**
     * What this mob does now: what was set on it ({@link #storedOf}) laid over what its species
     * asks for when it is a creature's rig carrier ({@code SpeciesOnRig}). The species half
     * is read live and never written onto the mob, so a re-sculpt changes it and a child's own
     * "stop following me" wins.
     */
    public static PetBehaviour of(Entity e) {
        if (e == null) {
            return PetBehaviour.NONE;
        }
        PetBehaviour stored = storedOf(e);
        PetBehaviour species = com.moddingfromamod.vanilla.SpeciesOnRig.defaultFor(e);
        return species.isEmpty() ? stored : stored.over(species);
    }

    /** Only what was set on this mob itself, without its species' default. */
    public static PetBehaviour storedOf(Entity e) {
        return e == null ? PetBehaviour.NONE
                : PetBehaviour.fromStored(e.getPersistentData().getStringOr(KEY, ""));
    }

    /**
     * Start looking after a mob now, rather than when it next joins a level. A rig's carrier joins
     * before its species tag is on it, so the join event cannot see what its species asks for.
     */
    public static void track(Mob mob) {
        if (mob.level() instanceof ServerLevel level && of(mob).isActive()) {
            PETS.computeIfAbsent(level, l -> new HashSet<>()).add(mob);
        }
    }

    /** The raw stored copy, for the undo record (empty when the mob does nothing). */
    public static String stored(Entity e) {
        return e == null ? "" : e.getPersistentData().getStringOr(KEY, "");
    }

    /** Whose pet it is, as stored ({@code ""} when it follows whoever is nearest). */
    public static String ownerStored(Entity e) {
        return e == null ? "" : e.getPersistentData().getStringOr(OWNER, "");
    }

    /**
     * Put what {@link #stored} and {@link #ownerStored} read off one carrier onto the carrier a rig
     * rebuild made in its place ("follow me" then "make him bigger" must still follow,
     * and "stop following me" must still not). Nothing stored is nothing to do: the species'
     * default is read live.
     */
    public static void putBack(Mob mob, String pet, String owner) {
        // the OWNER comes across even when nothing else was set on the mob — a rideable
        // creature spawned for him carries whose it is (claim) with only its species' behaviour,
        // and its horn finds it by that.
        if (owner != null && !owner.isEmpty()) {
            mob.getPersistentData().putString(OWNER, owner);
        }
        if (pet == null || pet.isEmpty()) {
            return;
        }
        restore(mob, pet);
        if (owner != null && !owner.isEmpty()) {
            mob.getPersistentData().putString(OWNER, owner);
        }
    }

    /**
     * say whose this creature is without setting any behaviour on it — a rideable creature
     * spawned for him. Its species' own behaviour (friendly, rideable) is read live, as ever.
     */
    public static void claim(Mob mob, UUID owner) {
        if (owner != null) {
            mob.getPersistentData().putString(OWNER, owner.toString());
            track(mob);
        }
    }

    /**
     * Lay {@code asked} over what the mob already does, and start looking after it.
     *
     * @param owner the child it is a pet of, or null to follow whichever player is nearest
     * @return a note for the agent when something could not be done; empty when it all was
     */
    public static List<String> apply(LivingEntity e, PetBehaviour asked, UUID owner) {
        if (asked == null || asked.isEmpty()) {
            return List.of();
        }
        if (!(e instanceof Mob mob)) {
            return List.of("(a " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                    + " cannot be given pet behaviour.)");
        }
        // Over what was SET on it, not its species' default: the default is read live, and
        // copying it in here would freeze it against the next re-sculpt.
        PetBehaviour now = asked.over(storedOf(mob));
        if (asked.asksToStay()) {
            // home is where it stands now — the spot it was put. Staying and following are
            // opposites (the tool refuses both in one call); told to stay, a follower stops.
            now = new PetBehaviour(Boolean.TRUE.equals(asked.follow()), now.friendly(), now.rideable(),
                    now.finds(), now.stay(), null).homedAt(mob.blockPosition().asLong());
            if (Boolean.TRUE.equals(now.follow())) {
                now = new PetBehaviour(false, now.friendly(), now.rideable(), now.finds(), now.stay(), now.home());
            }
        } else if (Boolean.TRUE.equals(asked.follow()) && now.stays()) {
            // Told to follow, a stayer leaves its spot.
            now = new PetBehaviour(true, now.friendly(), now.rideable(), now.finds(), PetBehaviour.STAY_OFF, null);
        }
        restore(mob, now.toStored());
        if (owner != null && now.isActive()) {
            mob.getPersistentData().putString(OWNER, owner.toString());
        }
        track(mob);
        return List.of();
    }

    /**
     * Put a stored behaviour back exactly (the undo path uses this with what was there before).
     * An empty string takes the behaviour off.
     */
    public static void restore(Mob mob, String stored) {
        PetBehaviour now = PetBehaviour.fromStored(stored);
        if (now.isEmpty()) {
            mob.getPersistentData().remove(KEY);
            mob.getPersistentData().remove(OWNER);
            untrack(mob);
            track(mob);   // its species may still ask for something
            return;
        }
        // An explicit OFF is kept, not dropped: on a rig carrier, "stop following me" is a false
        // laid over a species that follows, and dropping it would bring the following back.
        mob.getPersistentData().putString(KEY, now.toStored());
        if (!of(mob).isActive()) {
            untrack(mob);
            return;
        }
        // A pet that despawns when he walks away is a broken pet.
        mob.setPersistenceRequired();
        if (Boolean.TRUE.equals(now.friendly())) {
            calmDown(mob);
        }
        if (mob.level() instanceof ServerLevel level) {
            PETS.computeIfAbsent(level, l -> new HashSet<>()).add(mob);
        }
    }

    private static void calmDown(Mob mob) {
        if (mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
    }

    // ── tracking ────────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Mob mob
                && of(mob).isActive()) {
            PETS.computeIfAbsent(level, l -> new HashSet<>()).add(mob);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            untrack(mob);
        }
    }

    private static void untrack(Mob mob) {
        for (Set<Mob> set : PETS.values()) {
            set.remove(mob);
        }
        FOUND.remove(mob.getUUID());
        GLOW_BY_US.remove(mob.getUUID());
        FOLLOW_PROGRESS.remove(mob.getUUID());
    }

    /** How many pets are being looked after in this level (for a test and for a report). */
    public static int tracked(ServerLevel level) {
        return PETS.getOrDefault(level, Set.of()).size();
    }

    // ── friendly: never target a player ─────────────────────────────────────────────────────────

    /**
     * The one choke point every mob's aggression passes: {@code Mob.setTarget} fires it as
     * MOB_TARGET, and a brain's attack memory fires it as BEHAVIOR_TARGET. A friendly mob never
     * gets a player as its target, and no mob attacks its own rider.
     */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null) {
            return;
        }
        Entity self = event.getEntity();
        PetBehaviour pet = of(self);
        if (!pet.isActive()) {
            return;
        }
        boolean rider = RideSeat.mountOf(target) == self;
        if (rider || (Boolean.TRUE.equals(pet.friendly()) && target instanceof Player)) {
            event.setCanceled(true);
        }
    }

    // ── rideable: a free hand climbs on ─────────────────────────────────────────────────────────

    /**
     * Right-click with a FREE hand climbs on: empty, or holding a wish carrier (Moddy's watch, a
     * wished item). HIGH priority, because {@code VanillaCarrierAbilities} cancels every entity
     * interaction made with a carrier (so a wished item is never equipped on a horse), and a joining
     * child is HOLDING the watch. Measured: the first cut required an empty hand at normal priority,
     * and a player who had just joined could not climb on at all.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Mob mob) || event.getLevel().isClientSide()
                || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        var held = player.getMainHandItem();
        boolean freeHand = handFreeToRide(held);
        boolean vanilla = RideSeat.vanillaMount(mob);
        if (!(Boolean.TRUE.equals(of(mob).rideable()) || vanilla) || player.isShiftKeyDown() || !freeHand
                || RideSeat.riderOf(mob) != null) {
            return;
        }
        // A tamed, saddled horse he climbs on with the WATCH in hand (2026-10-12): the watch refuses
        // every entity click (VanillaCarrierAbilities), so before this a joining child -- who holds
        // the watch -- could not get on his own horse. His client has already put the watch on the
        // horse as armour in its prediction, which is undone here as the watch's own refusal does.
        if (vanilla && !held.isEmpty()) {
            com.moddingfromamod.item.VanillaCarrierAbilities.undoEntityPrediction(player, mob);
        }
        // On a seat that lifts him to the creature's back, when the carrier alone would sit him
        // lower (RideSeat).
        if (RideSeat.mount(player, mob)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ── the tick ────────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        long tick = event.getServer().getTickCount();
        for (var entry : PETS.entrySet()) {
            ServerLevel level = entry.getKey();
            for (Mob mob : List.copyOf(entry.getValue())) {
                if (!mob.isAlive() || mob.level() != level) {
                    untrack(mob);
                    continue;
                }
                PetBehaviour pet = of(mob);
                if (!pet.isActive()) {
                    untrack(mob);
                    continue;
                }
                if (Boolean.TRUE.equals(pet.rideable())
                        && RideSeat.riderOf(mob) instanceof ServerPlayer rider) {
                    RideSeat.flightOn(mob);
                    steer(mob, rider);
                    continue;
                }
                RideSeat.flightOff(mob);
                // a mount nobody is on waits — stands still, a flyer comes down — while its
                // rider is close with a free hand. Every tick, so it never slips off as he reaches it.
                if (Boolean.TRUE.equals(pet.rideable()) && waitForRider(level, mob)) {
                    continue;
                }
                if (tick % EVERY != 0) {
                    continue;
                }
                if (pet.stays()) {
                    keepHome(level, mob, pet);
                    continue;
                }
                // anything of his that neither follows nor stays roams within its leash — and
                // so does a follower with nobody in reach to follow (he logged off, he went through a
                // portal), which otherwise drifted wherever its carrier's own AI took it. Not while it
                // is chasing something: it comes back when the fight is over.
                boolean following = Boolean.TRUE.equals(pet.follow()) && ownerOf(level, mob) != null;
                if (!following && !com.moddingfromamod.vanilla.RigInstincts.busy(mob) && !parksWhereLeft(mob)
                        && leash(level, mob)) {
                    continue;
                }
                boolean busyFinding = false;
                if (pet.finds() != null && !pet.finds().isEmpty()) {
                    busyFinding = find(level, mob, pet.finds(), tick % FIND_EVERY == 0);
                }
                // A rig carrier chasing what its species hunts is not also trotting after him
                // (RigInstincts).
                if (!busyFinding && Boolean.TRUE.equals(pet.follow())
                        && !com.moddingfromamod.vanilla.RigInstincts.busy(mob)) {
                    follow(level, mob);
                }
            }
        }
    }

    // ──: the leash, and a mount that waits ─────────────────────────────────────────────────

    /** Where a leashed creature's home is: where it was first looked after, or where a call put it. */
    static final String LEASH_HOME = "mfam_leash_home";
    private static final Set<UUID> WAITING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RETURNING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Per returning creature: its nearest distance home so far, and when. */
    private static final java.util.Map<UUID, double[]> PROGRESS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * True while this mob's own wandering must not run: it is waiting for its rider, or coming back
     * inside its leash. Read by {@code BatRideMixin}: a bat's random flight is not a goal anything can
     * remove, and it would fight both.
     */
    public static boolean holding(Mob mob) {
        UUID id = mob.getUUID();
        return WAITING.contains(id) || RETURNING.contains(id);
    }

    /**
     * is something else steering it right now — a rider, waiting for one, coming back inside
     * its leash, staying on its spot, or following an owner who is in reach? Then its archetype's own
     * life (CreatureLife) stands aside.
     */
    public static boolean steeredElsewhere(ServerLevel level, Mob mob) {
        if (RideSeat.riderOf(mob) != null || holding(mob)) {
            return true;
        }
        PetBehaviour pet = of(mob);
        return pet.stays() || (Boolean.TRUE.equals(pet.follow()) && ownerOf(level, mob) != null);
    }

    /** Its owner if he is here, else the nearest player within reach, else null — for a life to look at. */
    public static Entity ownerOrNearest(ServerLevel level, Mob mob) {
        return ownerOf(level, mob);
    }

    /** Is it waiting for its rider right now? (tests, the films) */
    public static boolean waiting(Mob mob) {
        return WAITING.contains(mob.getUUID());
    }

    /** Its leash's home, set to where it stands the first time it is asked. */
    public static BlockPos leashHome(Mob mob) {
        if (!mob.getPersistentData().contains(LEASH_HOME)) {
            mob.getPersistentData().putLong(LEASH_HOME, mob.blockPosition().asLong());
        }
        return BlockPos.of(mob.getPersistentData().getLongOr(LEASH_HOME, 0L));
    }

    /** Move its home: a horn called it to him, so where he stood is where it lives now. */
    public static void setLeashHome(Mob mob, BlockPos at) {
        mob.getPersistentData().putLong(LEASH_HOME, at.asLong());
        RETURNING.remove(mob.getUUID());
    }

    /** A vehicle stays where he left it: it is never pulled home by a leash. */
    static boolean parksWhereLeft(Mob mob) {
        var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(mob);
        return sp != null && sp.archetype() == com.moddingfromamod.species.Archetype.VEHICLE;
    }

    /** Pull it back inside its leash; true while that is what it is doing. */
    static boolean leash(ServerLevel level, Mob mob) {
        BlockPos home = leashHome(mob);
        double dx = mob.getX() - (home.getX() + 0.5), dz = mob.getZ() - (home.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        UUID id = mob.getUUID();
        switch (PetMoves.leash(d, RETURNING.contains(id))) {
            case FREE -> {
                RETURNING.remove(id);
                PROGRESS.remove(id);
                return false;
            }
            case RETURN -> {
                long now = level.getGameTime();
                double[] best = PROGRESS.get(id);
                if (!RETURNING.contains(id) || best == null || d < best[0] - PetMoves.STUCK_PROGRESS) {
                    PROGRESS.put(id, new double[] {d, now});
                } else if (PetMoves.stuck(best[0], d, now - (long) best[1])) {
                    // Getting no closer — a wall, a roof, a fence in the way: put it back.
                    RETURNING.remove(id);
                    PROGRESS.remove(id);
                    mob.getNavigation().stop();
                    mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                    return true;
                }
                RETURNING.add(id);
                walkTo(mob, home.getX() + 0.5, home.getY() + (com.moddingfromamod.vanilla.RigPowers.flies(mob) ? 2 : 0),
                        home.getZ() + 0.5, null);
                return true;
            }
            default -> {
                RETURNING.remove(id);
                mob.getNavigation().stop();
                mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                return true;
            }
        }
    }

    /** Hold still — or come down — for a rider who is close with a free hand; true while it does. */
    static boolean waitForRider(ServerLevel level, Mob mob) {
        UUID id = mob.getUUID();
        Entity who = ownerOf(level, mob);
        if (!(who instanceof Player p) || p.isSpectator()) {
            WAITING.remove(id);
            return false;
        }
        boolean free = handFreeToRide(p.getMainHandItem());
        if (!PetMoves.waitsForRider(true, RideSeat.riderOf(mob) != null, mob.distanceTo(p), free)) {
            WAITING.remove(id);
            return false;
        }
        WAITING.add(id);
        RETURNING.remove(id);
        mob.getNavigation().stop();
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        var v = mob.getDeltaMovement();
        if (com.moddingfromamod.vanilla.RigPowers.flies(mob)) {
            int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    mob.getBlockX(), mob.getBlockZ());
            if (mob.getY() > ground + 0.2) {
                com.moddingfromamod.vanilla.RigPowers.flyTo(mob,
                        // ARRIVED below the ground: a flight stops pushing within ARRIVED of its
                        // target, so aimed AT the ground it hovered 1.5 up (CreatureLife PARK).
                        new net.minecraft.world.phys.Vec3(mob.getX(),
                                ground - com.moddingfromamod.vanilla.RigFlight.ARRIVED, mob.getZ()), 0.6);
            } else {
                mob.setDeltaMovement(0, Math.min(0, v.y), 0);
            }
        } else {
            mob.setDeltaMovement(0, v.y, 0);
        }
        mob.getLookControl().setLookAt(p, 30f, 30f);
        return true;
    }

    /** Move a ridden mob the way its rider is looking, for the keys he holds. */
    static void steer(Mob mob, ServerPlayer rider) {
        mob.getNavigation().stop();
        float yaw = rider.getYRot();
        mob.setYRot(yaw);
        mob.yRotO = yaw;
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        boolean free3d = mob.isNoGravity() || com.moddingfromamod.compat.Mobs.isFlyingMob(mob)
                || mob.getNavigation() instanceof FlyingPathNavigation
                || (mob.isInWater() && mob.getNavigation() instanceof WaterBoundPathNavigation);
        double[] v = PetMoves.ride(yaw, rider.getXRot(), RiderInput.forward(rider),
                RiderInput.strafe(rider), mob.getAttributeValue(Attributes.MOVEMENT_SPEED), free3d);
        double y = Double.isNaN(v[1]) ? mob.getDeltaMovement().y : v[1];
        mob.setDeltaMovement(v[0], y, v[2]);
        // A walker that hits a one-block step hops it, the way a horse does.
        if (!free3d && mob.horizontalCollision && mob.onGround()) {
            mob.getJumpControl().jump();
        }
        // HIS jump makes it jump (chunk 3). Read nowhere before: "when I jump it jumps super
        // high" got jump boost on the mount from both models, and the mount never left the ground.
        // Vanilla's own jump: the mob's jump strength plus its jump boost, so a potion, an attribute
        // or a datapack's effect all make it higher, as they do for anything else that jumps.
        if (!free3d && RiderInput.jump(rider) && mob.onGround()) {
            double vy = mob.getAttributeValue(Attributes.JUMP_STRENGTH) + mob.getJumpBoostPower();
            var m = mob.getDeltaMovement();
            mob.setDeltaMovement(m.x, vy, m.z);
            mob.needsSync = true;
        }
    }

    /** Keep to the owner: walk when a little way off, hop when far. */
    static void follow(ServerLevel level, Mob mob) {
        Entity owner = ownerOf(level, mob);
        if (owner == null) {
            return;
        }
        double d = mob.distanceTo(owner);
        switch (PetMoves.follow(d)) {
            case STAY -> {
            }
            case HOP -> {
                // VANILLA'S HOP, CHECKED. This put it at his exact spot plus 1.5, with no
                // floor, room or hazard check: a walker hopped to him while he flew fell to its
                // death, and one hopped beside the lava castle burned. Now ten tries within three
                // blocks, each a SafeSpot, and none while he is airborne unless it flies — it
                // waits, and catches up once he lands. No safe spot: keep walking.
                if (!com.moddingfromamod.vanilla.SafePlacement.hop(level, mob, owner)) {
                    walkTo(mob, owner.getX(), owner.getY(), owner.getZ(), owner);
                }
            }
            case WALK -> {
                // a walk that ends short of him (he climbed what it cannot) is not retried for
                // ever: getting no nearer for a while, it hops to him the way a far one does.
                UUID id = mob.getUUID();
                long now = level.getGameTime();
                double[] best = FOLLOW_PROGRESS.get(id);
                if (best == null || d < best[0] - PetMoves.STUCK_PROGRESS) {
                    FOLLOW_PROGRESS.put(id, new double[] {d, now});
                } else if (PetMoves.followStuck(best[0], d, now - (long) best[1])) {
                    FOLLOW_PROGRESS.remove(id);   // hopped or not, the next try is a fresh walk
                    if (com.moddingfromamod.vanilla.SafePlacement.hop(level, mob, owner)) {
                        return;
                    }
                }
                walkTo(mob, owner.getX(), owner.getY(), owner.getZ(), owner);
            }
        }
        if (PetMoves.follow(d) != PetMoves.Follow.WALK) {
            FOLLOW_PROGRESS.remove(mob.getUUID());
        }
    }

    /** per walking follower, its nearest distance to him so far, and when. */
    private static final java.util.Map<UUID, double[]> FOLLOW_PROGRESS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * keep a staying mob at home. Pushed, lured or knocked off its spot, it walks back; far
     * away (it fell off the castle), it is put back. Within its radius it is left to itself — a
     * puppy in a kennel may turn round, a goalie stays in his goal.
     */
    static void keepHome(ServerLevel level, Mob mob, PetBehaviour pet) {
        BlockPos home = BlockPos.of(pet.home());
        double dx = mob.getX() - (home.getX() + 0.5), dz = mob.getZ() - (home.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        switch (PetMoves.home(d, pet.stay())) {
            case STAY -> {
                if (pet.stay() == 0 && d > 0.6 && mob.getNavigation().isDone()) {
                    mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
                }
            }
            case WALK -> walkTo(mob, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, null);
            case HOP -> {
                mob.getNavigation().stop();
                mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            }
        }
    }

    /** The owner if it is here, else the nearest player within the hop distance. */
    /**
     * <b>What he may hold and still climb on</b> — and what a mount waits for. An empty hand, Moddy's watch,
     * or a creature's HORN (2026-11-13, a Bedrock player: he blew his dragon's horn, then pressed the
     * dragon's Ride button with the horn still in hand, and nothing happened — Geyser draws that button
     * whatever he holds). A horn does nothing to a creature, and calling a mount and climbing on it are one
     * act. Anything else stays his to use on the creature: a lead, a name tag, food.
     */
    public static boolean handFreeToRide(net.minecraft.world.item.ItemStack held) {
        return held.isEmpty() || com.moddingfromamod.item.VanillaCarrierAbilities.isCarrier(held)
                || com.moddingfromamod.item.CreatureHorn.callsOf(held) != null;
    }

    static Entity ownerOf(ServerLevel level, Mob mob) {
        String id = mob.getPersistentData().getStringOr(OWNER, "");
        if (!id.isEmpty()) {
            try {
                Entity e = level.getEntity(UUID.fromString(id));
                if (e != null) {
                    return e;
                }
                Player p = level.getPlayerByUUID(UUID.fromString(id));
                if (p != null) {
                    return p;
                }
                // HIS creature with him away is nobody's to follow. Falling through to the
                // nearest player made somebody else's pet follow, and teleport to, whichever child
                // stood closest — a stranger's dragon arrived at his horn's call.
                return null;
            } catch (IllegalArgumentException ignored) {
                // Unreadable owner: fall back to the nearest player rather than doing nothing.
            }
        }
        return level.getNearestPlayer(mob, PetMoves.FOLLOW_TELEPORT * 2);
    }

    private static void walkTo(Mob mob, double x, double y, double z, Entity towards) {
        if (com.moddingfromamod.vanilla.RigPowers.flies(mob)) {
            // A flying creature rides a bat, and a bat ignores navigation: fly it there, hovering
            // over him rather than in his face.
            double up = towards instanceof Player ? com.moddingfromamod.vanilla.RigFlight.HOVER : 0;
            com.moddingfromamod.vanilla.RigPowers.flyTo(mob,
                    new net.minecraft.world.phys.Vec3(x, y + up, z), 1.0);
            return;
        }
        com.moddingfromamod.vanilla.RigInstincts.unsit(mob);   // a sitting camel will not follow
        var brain = mob.getBrain();
        if (towards != null && brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(towards, 1.2f, 2));
        }
        if (com.moddingfromamod.compat.Mobs.isFlyingMob(mob)) {
            // A ghast has no path to follow; it floats to a wanted position.
            mob.getMoveControl().setWantedPosition(x, y + 2, z, 1.0);
        } else if (towards != null) {
            mob.getNavigation().moveTo(towards, 1.2);
        } else {
            mob.getNavigation().moveTo(x, y, z, 1.1);
        }
    }

    /**
     * Look for the named block near the mob; walk to the nearest and show it with sparkles and a
     * glow. Returns true while it has something to show, so it does not wander back to following.
     */
    static boolean find(ServerLevel level, Mob mob, String what, boolean scanNow) {
        UUID id = mob.getUUID();
        BlockPos at = FOUND.get(id);
        if (scanNow || (at != null && !matches(level.getBlockState(at), what))) {
            at = scan(level, mob.blockPosition(), what);
            if (at == null) {
                FOUND.remove(id);
                if (GLOW_BY_US.remove(id)) {
                    mob.setGlowingTag(false);
                }
                return false;
            }
            FOUND.put(id, at);
        }
        if (at == null) {
            return false;
        }
        if (!mob.isCurrentlyGlowing() && GLOW_BY_US.add(id)) {
            mob.setGlowingTag(true);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 1.1,
                at.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
        if (mob.blockPosition().distSqr(at) > 4) {
            walkTo(mob, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, null);
        }
        return true;
    }

    /** The nearest matching block within the finder's reach, or null. */
    static BlockPos scan(ServerLevel level, BlockPos centre, String what) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int r = PetMoves.FIND_RADIUS;
        int h = PetMoves.FIND_HEIGHT;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -h, -r), centre.offset(r, h, r))) {
            if (matches(level.getBlockState(p), what)) {
                double d = p.distSqr(centre);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    static boolean matches(BlockState state, String what) {
        if (what.startsWith("#")) {
            Identifier tag = Identifier.tryParse(what.substring(1));
            return tag != null && state.is(TagKey.create(Registries.BLOCK, tag));
        }
        Block b = state.getBlock();
        return what.equals(String.valueOf(BuiltInRegistries.BLOCK.getKey(b)));
    }

    /** Does this build know the block or tag a finder was asked for? (Checked when it is set.) */
    public static boolean knows(String what) {
        if (what == null || what.isEmpty()) {
            return true;
        }
        if (what.startsWith("#")) {
            Identifier tag = Identifier.tryParse(what.substring(1));
            if (tag == null) {
                return false;
            }
            TagKey<Block> key = TagKey.create(Registries.BLOCK, tag);
            return BuiltInRegistries.BLOCK.stream().anyMatch(b -> b.builtInRegistryHolder().is(key));
        }
        Identifier id = Identifier.tryParse(what);
        return id != null && BuiltInRegistries.BLOCK.getOptional(id).isPresent();
    }
}
