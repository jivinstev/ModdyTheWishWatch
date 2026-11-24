package com.moddingfromamod.vanilla;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * <b>{@code boss}, {@code explodes} and {@code flies}, on a creature's rig carrier</b>
 *. {@code WishMobEntity} had each as an override (a boss bar in its tick, a bang in
 * {@code die}, {@code isNoGravity}); the bridge cancels that entity, so on the rig none of them
 * happened.
 *
 * <ul>
 *   <li><b>boss</b>: a {@link ServerBossEvent} held here per carrier, named for the species and
 *       showing its health to every player within {@value #BOSS_RANGE} blocks, as the old one
 *       did. It goes when the rig goes ({@link #forget});</li>
 *   <li><b>explodes</b>: the same parting bang, on the carrier's death: radius 2, no fire,
 *       {@link Level.ExplosionInteraction#NONE}, so it never breaks a block of a child's
 *       world;</li>
 *   <li><b>flies</b>: the carrier is already a bat, which stays in the air on its own.
 *       What it could not do is go anywhere on purpose, because a bat ignores navigation. So a
 *       destination set by {@link #flyTo} (a pet following, {@code RigInstincts} chasing or
 *       running) is FLOWN, by {@link RigFlight}, every tick;</li>
 *   <li><b>fireproof</b>: its fire is put out, so it is never shown burning.</li>
 * </ul>
 *
 * <p>Ticked from {@code VanillaRigAnimator}, beside {@code RigInstincts}, so every rig it drives
 * has these whichever birth point it came from.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigPowers {

    static final double BOSS_RANGE = 48.0;
    /** How long a destination is flown towards without being asked again, in ticks. */
    static final int FLY_FOR = 40;

    private record Flight(Vec3 to, double speed, long until) {
    }

    private static final Map<UUID, ServerBossEvent> BARS = new ConcurrentHashMap<>();
    private static final Map<UUID, Flight> FLIGHTS = new ConcurrentHashMap<>();

    private RigPowers() {
    }

    /** One tick of one driven carrier; {@code sp} is the species its rig was built from. */
    public static void tick(Mob carrier, Species sp) {
        if (!(carrier.level() instanceof ServerLevel level)) {
            return;
        }
        boss(level, carrier, sp);
        fly(level, carrier, sp);
        fireproof(carrier);
    }

    /**
     * A fireproof creature is never SHOWN burning. Its hidden fire resistance keeps
     * it unharmed, but the carrier still caught fire, so the film showed it at full health in
     * a column of flames; a blaze is never on fire. Read from the registry, not the species the rig
     * was built from, because a fire-breath pack compiled after the rig still makes it fireproof.
     */
    private static void fireproof(Mob carrier) {
        if (carrier.getRemainingFireTicks() <= 0) {
            return;
        }
        Species now = DisplayRig.speciesOn(carrier);
        if (now != null && now.has(Species.Power.FIREPROOF)) {
            carrier.clearFire();
        }
    }

    /** Stop everything held for a carrier: its bar leaves every screen. */
    public static void forget(UUID carrier) {
        ServerBossEvent bar = BARS.remove(carrier);
        if (bar != null) {
            bar.removeAllPlayers();
        }
        FLIGHTS.remove(carrier);
    }

    // ── boss ───────────────────────────────────────────────────────────────────────────────────

    /** This carrier's boss bar, for a test; null when it has none. */
    public static ServerBossEvent barOf(Mob carrier) {
        return BARS.get(carrier.getUUID());
    }

    private static void boss(ServerLevel level, Mob carrier, Species sp) {
        // NOT WHILE IT IS STILL BEING WISHED: a boss shown early (shimmering,
        // or a preview) put its health bar under the wish's own, two bars for one wish. The boss
        // arrives, bar and all, when the wish ends.
        if (sp == null || !sp.has(Species.Power.BOSS) || stillBeingWished(carrier, sp)) {
            ServerBossEvent gone = BARS.remove(carrier.getUUID());
            if (gone != null) {
                gone.removeAllPlayers();
            }
            return;
        }
        ServerBossEvent bar = BARS.computeIfAbsent(carrier.getUUID(), k -> new ServerBossEvent(java.util.UUID.randomUUID(),
                Component.literal(sp.name()), BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS));
        // Its NAME as well: a reshape in place keeps the carrier, so a renamed boss keeps its bar.
        if (!bar.getName().getString().equals(sp.name())) {
            bar.setName(Component.literal(sp.name()));
        }
        bar.setProgress(Math.max(0f, Math.min(1f, carrier.getHealth() / carrier.getMaxHealth())));
        // Only players who could see it carry the bar, as WishMobEntity did.
        Set<ServerPlayer> near = new HashSet<>(level.getEntitiesOfClass(ServerPlayer.class,
                carrier.getBoundingBox().inflate(BOSS_RANGE)));
        for (ServerPlayer p : new HashSet<>(bar.getPlayers())) {
            if (!near.contains(p)) {
                bar.removePlayer(p);
            }
        }
        for (ServerPlayer p : near) {
            bar.addPlayer(p);
        }
    }

    /**
     * Shimmering for a wish still in flight, a preview standing in for the real one, or the creature
     * a live wish has said it is ABOUT (an edit, from its plan on: filmed 2026-10-22, "make my Spikor
     * bigger" showed his bar under the wish's while Moddy planned).
     */
    static boolean stillBeingWished(Mob carrier, Species sp) {
        return WishShimmer.inProgress(sp.id()) || carrier.entityTags().contains(WishShimmer.PREVIEW_TAG)
                || aboutOfALiveWish(sp.id());
    }

    private static boolean aboutOfALiveWish(String species) {
        return liveAbouts().contains(species);
    }

    /**
     * What every live wish is ABOUT, worked out once per server tick (2026-10-31). Each voiced or boss
     * creature asked every tick, and each ask copied the marshal's whole history — every wish since
     * the server started, which it never forgets — under the lock the wish workers use.
     */
    private record Abouts(long tick, java.util.Set<String> ids) {
    }

    private static volatile Abouts abouts;

    static java.util.Set<String> liveAbouts() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long tick = server == null ? -1 : server.getTickCount();
        Abouts a = abouts;
        if (a != null && a.tick() == tick && tick >= 0) {
            return a.ids();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var run : com.moddingfromamod.marshal.Marshal.global().live()) {
            String about = run.aboutArtifact();
            if (about != null && !about.isEmpty()) {
                ids.add(about);
            }
        }
        abouts = new Abouts(tick, ids);
        return ids;
    }

    /** The per-creature scan {@link #liveAbouts} replaced — its test's oracle. */
    static boolean aboutOfALiveWishByScan(String species) {
        for (var run : com.moddingfromamod.marshal.Marshal.global().all()) {
            if (run.state().isLive() && species.equals(run.aboutArtifact())) {
                return true;
            }
        }
        return false;
    }

    // ── explodes ───────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob carrier)
                || !(carrier.level() instanceof ServerLevel level)
                || !VanillaRigAnimator.isDriving(carrier)) {
            return;
        }
        Species sp = DisplayRig.speciesOn(carrier);
        if (sp != null && sp.has(Species.Power.EXPLODES)) {
            // A parting bang, block-safe: this is a child's world, not a griefing tool.
            level.explode(carrier, carrier.getX(), carrier.getY(), carrier.getZ(), 2.0F, false,
                    Level.ExplosionInteraction.NONE);
        }
        forget(carrier.getUUID());
    }

    // ── flies ──────────────────────────────────────────────────────────────────────────────────

    /** Does this carrier's species fly? Then "go there" is {@link #flyTo}, not navigation. */
    public static boolean flies(Mob carrier) {
        Species sp = DisplayRig.speciesOn(carrier);
        return sp != null && sp.has(Species.Power.FLIES);
    }

    /** Fly towards a point for the next {@value #FLY_FOR} ticks (or until asked again). */
    public static void flyTo(Mob carrier, Vec3 to, double speed) {
        FLIGHTS.put(carrier.getUUID(),
                new Flight(to, speed, carrier.level().getGameTime() + FLY_FOR));
    }

    private static void fly(ServerLevel level, Mob carrier, Species sp) {
        Flight f = FLIGHTS.get(carrier.getUUID());
        if (f == null) {
            return;
        }
        if (sp == null || !sp.has(Species.Power.FLIES) || level.getGameTime() > f.until()
                || carrier.isNoAi()) {
            FLIGHTS.remove(carrier.getUUID());
            return;
        }
        if (carrier instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);   // a bat asleep under a ceiling does not follow anyone
        }
        Vec3 v = carrier.getDeltaMovement();
        double[] n = RigFlight.velocity(carrier.getX(), carrier.getY(), carrier.getZ(),
                f.to().x, f.to().y, f.to().z, v.x, v.y, v.z, f.speed());
        carrier.setDeltaMovement(n[0], n[1], n[2]);
        // Face where it is going, or the rig flies sideways (the rig reads the body's yaw).
        if (n[0] * n[0] + n[2] * n[2] > 1e-4) {
            float yaw = (float) (Math.atan2(n[2], n[0]) * 180.0 / Math.PI) - 90f;
            carrier.setYRot(yaw);
            carrier.setYBodyRot(yaw);
        }
    }
}
