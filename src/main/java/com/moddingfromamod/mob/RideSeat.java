package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>Where a rider sits, and what happens when he rides a flier</b> (2026-10-11).
 *
 * <h2>The seat</h2>
 * A stock client seats a rider on his mount's own vanilla passenger point — for a rig, the
 * INVISIBLE CARRIER's, which says nothing about the creature drawn around it: measured
 * ({@code RideFitProbeGameTest}), riders sat from 0.62 blocks inside a body to 0.85 above a back.
 * The rig records where the back is ({@link #SEAT_KEY}, {@code RigFit.backTop}, in blocks over the
 * carrier's feet) and chooses a carrier whose own seat is not above it; climbing on then puts a
 * seat on the carrier, sized to exactly the missing height ({@link #seat}), and the rider on that.
 * A seat's passenger point is computed by every client from its synced scale, so the lift is the
 * same on a stock client. Nothing in vanilla can LOWER a seat, which is why the carrier's choice
 * carries half of this — and why a carrier chosen before its creature was rideable, whose seat is
 * above the back (a flier's bat, sized over the head and wings), is resized under the back when he
 * climbs on ({@link #fitCarrier}).
 *
 * <p>The seat is a passenger and nothing else: invisible, never saved (one read back is refused),
 * and gone the tick its rider is.
 *
 * <h2>Flying</h2>
 * A flier's carrier (a bat) is neither a {@link FlyingMob} nor on a flying path navigation, so it
 * steered like a walker: measured, looking up and holding W raised it -0.5/+2.2 blocks — its own
 * flutter. With its gravity off while he rides it climbs and dives where he looks
 * ({@code CarrierFlyRideProbeGameTest}). Gravity comes back when he gets off, and a rider who gets
 * off high up is given slow falling, because his own fall is simulated by his client and a mount
 * that hovers would otherwise drop him.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RideSeat {

    private RideSeat() {
    }

    /** Vanilla bosses: their AI is their own, so no pet behaviour (follow, rideable) reaches them. */
    public static final java.util.Set<String> BOSSES =
            java.util.Set.of("ender_dragon", "wither", "elder_guardian", "warden");

    /** On a carrier: the top of its creature's back, in blocks over its feet. */
    public static final String SEAT_KEY = "mfam_seat";
    /** On a carrier: its gravity was switched off by a ride, and must come back. */
    public static final String FLIGHT_KEY = "mfam_ride_flight";
    /** On a seat. */
    public static final String SEAT_TAG = "mfam_ride_seat";
    /**
     * What a seat is. The Bedrock extension seats a rider on it where Java does from a table that must name it
     * (bedrock/geyser-extension/resources/seat-points.txt; SeatPointsMatchJavaGameTest fails if it does not).
     */
    public static final EntityType<?> SEAT_TYPE = EntityTypes.ARMADILLO;
    /** A lift smaller than this is not worth an entity (and is under the seat's smallest size). */
    static final double MIN_LIFT = 0.05;
    /** The scale range {@code minecraft:scale} allows. */
    static final double SEAT_SCALE_MIN = 0.0625;
    static final double SEAT_SCALE_MAX = 16;
    /** Getting off a flier higher than this over the ground earns slow falling. */
    static final double SAFE_DROP = 3.0;
    /** Ticks of slow falling: long enough to come down from any height a ride reaches. */
    static final int SLOW_FALL_TICKS = 20 * 30;

    /** Seats alive, so an empty one is cleared even if its rider left without an event. */
    private static final List<Mob> SEATS = new ArrayList<>();
    /** Riders who just got off a seat, and the carrier under it, to be set down beside it. */
    private static final Map<LivingEntity, Entity> GOT_OFF = new HashMap<>();

    // ── the seat ────────────────────────────────────────────────────────────────────────────────

    public static void setSeat(Entity carrier, double blocksOverFeet) {
        if (Double.isFinite(blocksOverFeet)) {
            carrier.getPersistentData().putDouble(SEAT_KEY, blocksOverFeet);
        }
    }

    /**
     * The back's height over the carrier's feet, or NaN when it has none. A rig spawned before the
     * seat was recorded (a creature already standing in a world) has it read off the live rig the
     * first time it is asked, and kept.
     */
    public static double seatOf(Entity carrier) {
        var tag = carrier.getPersistentData();
        if (tag.contains(SEAT_KEY)) {
            return tag.getDoubleOr(SEAT_KEY, 0d);
        }
        var rig = com.moddingfromamod.vanilla.VanillaRigAnimator.rigOf(carrier);
        if (rig == null) {
            return Double.NaN;
        }
        double back = com.moddingfromamod.vanilla.RigFit.backTop(
                com.moddingfromamod.vanilla.RigFit.atoms(rig.groups(), rig.scale()),
                com.moddingfromamod.vanilla.DisplayRig.BACK_COLUMN);
        setSeat(carrier, back);
        return back;
    }

    public static boolean isSeat(Entity e) {
        return e != null && e.entityTags().contains(SEAT_TAG);
    }

    /** How high over its feet the carrier would seat {@code rider} by itself. */
    public static double carrierSeat(Entity carrier, Entity rider) {
        return carrier.getPassengerRidingPosition(rider).y - carrier.getY();
    }

    /** How much the seat must lift him: the back, less where the carrier would put him. */
    public static double lift(Entity carrier, Entity rider) {
        return seatOf(carrier) - carrierSeat(carrier, rider);
    }

    /** Climb on: onto the carrier itself, or onto a seat that lifts him to the creature's back. */
    public static boolean mount(Player player, Mob mob) {
        // A horse, donkey, mule, llama or skeleton/zombie horse THROWS an untamed rider (measured,
        // VanillaRideProbeGameTest: every one of them "NO"), so one he was told he can ride is his:
        // tamed to him the first time he climbs on. Not saddled -- a saddle hands the steering to his
        // client, and every other mount is steered by PetBehaviours.steer, the same for all of them.
        if (mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse && !horse.isTamed()) {
            horse.tameWithName(player);
        }
        double lift = lift(mob, player);
        if (fitCarrier(mob, player)) {
            lift = lift(mob, player);
        }
        boolean on;
        Mob seat = Double.isFinite(lift) && lift >= MIN_LIFT && mob.level() instanceof ServerLevel level
                ? seat(level, mob, player, lift) : null;
        if (seat == null) {
            on = player.startRiding(mob, true, true);
        } else {
            on = player.startRiding(seat, true, true);
            if (on) {
                SEATS.add(seat);
            } else {
                seat.discard();
            }
        }
        if (on) {
            flightOn(mob);
            JUST_ON.put(player.getUUID(), player.level().getGameTime());
            logSeat(player, mob, seat);
        }
        return on;
    }

    /**
     * A seat only lifts, so a carrier that would seat him ABOVE the creature's back is brought down
     * to it first: a creature made rideable after it was made keeps a carrier fitted to its whole
     * body, and a flier's bat stands over its head and wings. Then he sits on the back as on any
     * creature rideable from the start ({@code DisplayRig.fitSeatToBack}).
     */
    static boolean fitCarrier(Mob mob, Player rider) {
        double back = seatOf(mob);
        double carrierSeat = carrierSeat(mob, rider);
        if (!com.moddingfromamod.vanilla.HitProxyPlan.seatsAboveBack(carrierSeat, back)
                || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        var rig = com.moddingfromamod.vanilla.VanillaRigAnimator.rigOf(mob);
        boolean fitted = rig != null && rig.fitSeatToBack(level, carrierSeat, back);
        if (fitted) {
            LOG.info(String.format(java.util.Locale.ROOT, "ride fit: carrier seat %.3f over a back at %.3f, "
                    + "carrier now x%.3f, seat %.3f", carrierSeat, back, mob.getScale(), carrierSeat(mob, rider)));
        }
        return fitted;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mfam-ride");

    /**
     * Where he sits, as every Java client places him: his feet over the creature's carrier's feet. The Bedrock gate
     * compares it with where Geyser tells a Bedrock client to draw him (a rider sat INSIDE a Builder turtle).
     */
    static void logSeat(Player player, Mob mob, Mob seat) {
        if (seat != null) {
            mob.positionRider(seat);
            seat.positionRider(player);
        } else {
            mob.positionRider(player);
        }
        LOG.info(String.format(java.util.Locale.ROOT, "ride seat %s: on %s x%.3f, seat %s, his feet %.3f over its feet",
                player.getGameProfile().name(), net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()),
                mob.getScale(), seat == null ? "none" : String.format(java.util.Locale.ROOT, "x%.3f", seat.getScale()),
                player.getY() - mob.getY()));
    }

    /**
     * <b>The click that climbed him on is not ALSO a use of what he holds</b> (measured, Gate C
     * {@code ride}: climbing on with Moddy's watch in hand opened the wish menu too, every time). A
     * stock client sends the entity click, and when its OWN copy of the click says the creature did
     * nothing — it never can, because the proxy and seat tags are server-side only — it sends a
     * use-item packet for the same press. That one is dropped here, within a couple of ticks of
     * climbing on.
     */
    @SubscribeEvent
    public static void onUseItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Long at = JUST_ON.get(event.getEntity().getUUID());
        if (at != null && event.getLevel().getGameTime() - at <= JUST_ON_TICKS) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    /** When each player last climbed on, by game time. */
    private static final Map<java.util.UUID, Long> JUST_ON = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long after climbing on a use-item is the same click. */
    static final int JUST_ON_TICKS = 2;

    /**
     * The seat: an armadillo, invisible, without AI, gravity or a voice, invulnerable, riding the
     * carrier and scaled so its own passenger point (its height) is exactly the lift. A MOB, sized
     * by {@code minecraft:scale}, because every client recomputes a living entity's size when its
     * scale changes — an {@code interaction} sized by its synced height was the first cut, and a
     * 1.21.1 client (and server) never re-reads that height for a passenger point, so it seated him
     * where the carrier did. It wears the carrier's proxy tags, so a punch or a click that ever
     * lands on it lands on the creature (HitProxies), a hunter ignores it, the reaper takes it with
     * the rig, and it is never read back from a save.
     */
    static Mob seat(ServerLevel level, Mob carrier, Player rider, double lift) {
        Entity raw = SEAT_TYPE.create(level, EntitySpawnReason.COMMAND);
        if (!(raw instanceof Mob seat)) {
            return null;
        }
        seat.snapTo(carrier.getX(), carrier.getY(), carrier.getZ(), carrier.getYRot(), 0f);
        double unit = seat.getPassengerRidingPosition(rider).y - seat.getY();
        var attr = seat.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        double scale = unit <= 0 ? 0 : lift / unit;
        if (attr == null || scale < SEAT_SCALE_MIN || scale > SEAT_SCALE_MAX) {
            seat.discard();
            return null;
        }
        attr.setBaseValue(scale);
        seat.refreshDimensions();
        seat.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, true, false, false));
        seat.setInvisible(true);
        seat.setSilent(true);
        seat.setNoAi(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.noPhysics = true;
        seat.addTag(SEAT_TAG);
        seat.addTag(CreatureMatch.PROXY_TAG);
        seat.addTag(com.moddingfromamod.vanilla.HitProxies.PROXY_OWNER_PREFIX + carrier.getUUID());
        level.addFreshEntity(seat);
        if (!seat.startRiding(carrier, true, true)) {
            seat.discard();
            return null;
        }
        return seat;
    }

    /**
     * A vanilla mount he can already ride the vanilla way: a TAMED, SADDLED, grown horse, donkey,
     * mule, camel, skeleton or zombie horse (2026-10-12).
     */
    public static boolean vanillaMount(Entity m) {
        return m instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h
                && h.isTamed() && h.isSaddled() && !h.isBaby();
    }

    /** The player riding this mount, directly or on its seat; null when nobody is. */
    public static Player riderOf(Entity mount) {
        for (Entity p : mount.getPassengers()) {
            if (p instanceof Player player) {
                return player;
            }
            if (isSeat(p)) {
                for (Entity pp : p.getPassengers()) {
                    if (pp instanceof Player player) {
                        return player;
                    }
                }
            }
        }
        return null;
    }

    /** What this entity rides, looking through a seat; null when it rides nothing. */
    public static Entity mountOf(Entity rider) {
        Entity v = rider.getVehicle();
        return v != null && isSeat(v) ? v.getVehicle() : v;
    }

    // ── flying ──────────────────────────────────────────────────────────────────────────────────

    /** A mount that flies: what a flying creature's carrier is. */
    public static boolean flies(Entity mob) {
        return mob instanceof Bat || com.moddingfromamod.compat.Mobs.isFlyingMob(mob) || com.moddingfromamod.compat.Mobs.isFlyingAnimal(mob);
    }

    /**
     * While he rides a flier its gravity is off, so it goes up and down where he looks. (Its own
     * flutter, which picks a new random spot every second or so and pulled a ridden bat 0.63
     * blocks down in 2 s once he let go, is skipped while it is ridden: {@code BatRideMixin}.
     * Pausing its whole AI does not do: with no AI a mob does not travel at all.)
     */
    public static void flightOn(Mob mob) {
        if (flies(mob) && !mob.getPersistentData().contains(FLIGHT_KEY)) {
            mob.getPersistentData().putBoolean(FLIGHT_KEY, !mob.isNoGravity());
            mob.setNoGravity(true);
        }
    }

    /** And its gravity back, as it was, once nobody rides it. */
    public static void flightOff(Entity mob) {
        var data = mob.getPersistentData();
        if (data.contains(FLIGHT_KEY)) {
            if (data.getBooleanOr(FLIGHT_KEY, false)) {
                mob.setNoGravity(false);
            }
            data.remove(FLIGHT_KEY);
        }
    }

    /** Blocks between his feet and the first solid block under him (64 if none that far). */
    public static double overGround(Entity e) {
        BlockPos.MutableBlockPos p = BlockPos.containing(e.position()).mutable();
        double feet = e.getY();
        for (int i = 0; i < 64; i++) {
            if (!e.level().getBlockState(p).getCollisionShape(e.level(), p).isEmpty()) {
                return Math.max(0, feet - (p.getY() + 1));
            }
            p.move(0, -1, 0);
        }
        return 64;
    }

    // ── getting off ─────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide()
                || !(event.getEntityMounting() instanceof LivingEntity rider)) {
            return;
        }
        Entity from = event.getEntityBeingMounted();
        Entity mount = from != null && isSeat(from) ? from.getVehicle() : from;
        if (mount == null) {
            return;
        }
        if (isSeat(from)) {
            GOT_OFF.put(rider, mount);
        }
        if (rider instanceof Player && mount.getPersistentData().contains(FLIGHT_KEY)
                && overGround(rider) > SAFE_DROP) {
            rider.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALL_TICKS, 0,
                    false, true, true));
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!GOT_OFF.isEmpty()) {
            for (var e : List.copyOf(GOT_OFF.entrySet())) {
                LivingEntity rider = e.getKey();
                Entity mount = e.getValue();
                // Set down beside the creature, as vanilla does off a horse — off a seat he would
                // otherwise be dropped on the seat's own spot, on top of the creature.
                if (!rider.isPassenger() && mount.isAlive() && rider.level() == mount.level()) {
                    Vec3 to = mount.getDismountLocationForPassenger(rider);
                    rider.teleportTo(to.x, to.y, to.z);
                }
            }
            GOT_OFF.clear();
        }
        if (!JUST_ON.isEmpty()) {
            long now = event.getServer().overworld().getGameTime();
            JUST_ON.values().removeIf(t -> now - t > JUST_ON_TICKS);
        }
        SEATS.removeIf(seat -> {
            if (seat.isRemoved()) {
                return true;
            }
            if (seat.getPassengers().isEmpty() || !seat.isPassenger()) {
                seat.discard();
                return true;
            }
            return false;
        });
    }

    /**
     * <b>Logging off mid-ride leaves the creature in the world</b> (2026-10-11). Vanilla saves a
     * player's mount INTO his player file and takes it out of the world ({@code PlayerList.remove},
     * {@code UNLOADED_WITH_PLAYER}) — right for a horse, and for a rig it would carry off the
     * invisible carrier and leave every display part standing where it was. So a child riding a
     * rigged creature gets off first (this fires before that save); off a flier up high that is
     * the dismount above, which gives him slow falling, and the effect is saved with him.
     */
    @SubscribeEvent
    public static void onLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        Player p = event.getEntity();
        Entity mount = mountOf(p);
        if (mount != null && mount.entityTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG)) {
            p.stopRiding();
        }
    }

    /** A seat is never read back from a save: it belongs to a ride, and the ride is over. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && isSeat(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
