package com.moddingfromamod.vanilla;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.compat.Damage;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.runtime.Abilities;
import com.moddingfromamod.species.Species;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A sculpted creature's {@code abilities} — slam, ranged, charge, summon, teleport, heal — on its
 * rig carrier</b> (chunk 1).
 *
 * <p>The moves were written for {@code WishMobEntity} ({@code tickAbilities} and the six methods
 * below it) and worked there. Since the creature that stands in the world is the rig's vanilla
 * carrier and the wish mob is cancelled before it ever ticks, so every move was dead while the tool
 * schema kept offering it and the ready-made index kept recommending it: the baseline found
 * every ability any agent used did nothing (slam, teleport, summon, heal, ranged). This is a PORT,
 * not a redesign: the same timing ({@link Abilities#choose}, pure and tested by
 * {@code AbilitiesTest}), the same moves, sizes and sounds, carried out on the carrier.
 *
 * <p>What changed in carrying them over, and why:
 * <ul>
 *   <li><b>the target</b>: a wish mob had a {@code target} field its goals filled; a carrier's
 *       target is held by {@link RigInstincts} (a creature that hunts or guards). When it has none
 *       — a {@code friendly} creature, a {@code wild} one — the move looks for the nearest monster
 *       in its range, because a creature wished to "stomp the monsters" with a slam and no hunting
 *       instinct would otherwise never use the move it was given;</li>
 *   <li><b>who a slam lands on</b>: what the creature may go for ({@link RigInstincts#allowed},
 *       hit-back wide), so never its own rider, its seat, its hit proxies, anything invulnerable, a
 *       creative child — and never a player at all from a {@code friendly} creature. The wider
 *       question (never the child unless asked, "enemies" means monsters) is chunk 2's;</li>
 *   <li><b>sounds</b> are played by the LEVEL at the carrier, because a carrier is silent
 *       ({@code DisplayRig}) and a silent entity's own {@code playSound} is dropped;</li>
 *   <li><b>summon</b> calls in two small rig creatures of its own species, marked so they never
 *       summon in turn, capped at twelve rig creatures within 24 blocks.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.moddingfromamod.ModdingFromAModMod.MODID)
public final class RigAbilities {

    /** Marks a summoned helper: it never summons its own. */
    public static final String MINION_TAG = "mfam_minion";
    static final double SLAM_RADIUS = 5.0;
    static final int SUMMON_CAP = 12;
    /** How long after a charge a body-hit still lands, in ticks. */
    static final int CHARGE_HIT_TICKS = 25;
    /** Blocks a tick while charging: a ravager-like rush, about three times a walk. */
    static final double CHARGE_SPEED = 0.8;
    /** Ticks a ranged creature turns and aims before the shot leaves: the turn has to be SEEN. */
    static final int AIM_TICKS = 6;
    /** A slam's hop: about a vanilla jump (0.42), so the creature's own jump pose plays. */
    static final double SLAM_HOP_VY = 0.5;
    /** A slam that has not come down by now lands anyway (a ledge, a wall, a stuck carrier). */
    static final int SLAM_MAX_AIR_TICKS = 30;
    /** Ticks it keeps facing what it shot, or blinked to, afterwards. */
    static final int FACE_AFTER_TICKS = 12;

    private static final class State {
        final Abilities.Cooldowns cooldowns = new Abilities.Cooldowns();
        UUID charging;
        int chargeUntil;
        /** Who it is turned to: aiming at, or just acted on. */
        UUID facing;
        int faceUntil;
        /** When the aimed move (a shot, a rush) goes, or -1; and which it is. */
        int shootAt = -1;
        Abilities.Kind pending;
        /** A slam in the air, waiting to land: the tick it went up, or -1. */
        int slamFrom = -1;
        /** Has the slamming creature left the ground yet? */
        boolean slamAirborne;
    }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();

    private RigAbilities() {
    }

    /** One tick of one driven carrier; {@code sp} is the species its rig was built from. */
    public static void tick(Mob carrier, Species sp) {
        if (sp == null || sp.abilities() == null || sp.abilities().isEmpty()
                || !(carrier.level() instanceof ServerLevel level) || !carrier.isAlive()
                || carrier.isNoAi() || carrier.deathTime > 0) {
            return;
        }
        State st = STATE.computeIfAbsent(carrier.getUUID(), k -> new State());
        finishCharge(level, carrier, sp, st);
        if (st.slamFrom >= 0) {
            landSlam(level, carrier, sp, st);
            return;   // in the air: nothing else starts until it comes down
        }
        if (holdFacing(level, carrier, st)) {
            return;   // mid-aim: the shot leaves when the turn has been seen
        }
        if (!Abilities.anyReady(sp.abilities(), carrier.tickCount, st.cooldowns)) {
            return;   // every move cooling down: choose() would pick nothing, so look for nobody
        }
        LivingEntity target = target(level, carrier, sp, false);
        double distance = target == null ? -1 : carrier.distanceTo(target);
        Abilities.Kind kind = Abilities.choose(sp.abilities(), carrier.tickCount, distance, st.cooldowns);
        if (kind == null && RigInstincts.targetOf(carrier) == null) {
            // Nothing fits the NEAREST monster — a blink or a shot wants one further off (the
            // re-measure: a teleporter with monsters at its feet never blinked, 0 of 2). Its own
            // hunt target is never second-guessed; only the fallback widens.
            LivingEntity far = target(level, carrier, sp, true);
            if (far != null && far != target) {
                kind = Abilities.choose(sp.abilities(), carrier.tickCount, carrier.distanceTo(far), st.cooldowns);
                target = far;
            }
        }
        if (kind == null) {
            return;
        }
        boolean fired = switch (kind) {
            case RANGED -> aim(carrier, target, st, Abilities.Kind.RANGED);
            case SLAM -> slam(level, carrier, sp, target, st);
            case CHARGE -> aim(carrier, target, st, Abilities.Kind.CHARGE);
            case SUMMON -> summon(level, carrier, sp);
            case TELEPORT -> blink(level, carrier, target, st);
            case HEAL -> heal(level, carrier);
        };
        if (!fired && kind != Abilities.Kind.HEAL && sp.abilities().contains(Abilities.Kind.HEAL)
                && st.cooldowns.ready(Abilities.Kind.HEAL, carrier.tickCount)) {
            // A move that found nothing to do (a slam with nothing in reach) costs no cooldown, so
            // choose() offers it again next tick, ahead of HEAL, for ever: a slam-and-heal creature
            // never healed (re-measure). Heal gets its turn when the first pick came to nothing.
            kind = Abilities.Kind.HEAL;
            fired = heal(level, carrier);
        }
        if (fired) {
            st.cooldowns.use(kind, carrier.tickCount);
            // A slam swings when it lands and a charge when it hits: the attack clip is the blow.
        }
    }

    public static void forget(UUID carrier) {
        STATE.remove(carrier);
    }

    /**
     * The body yaw the rig should DRAW this tick: toward what a move is aimed at or rushing, while
     * it is, else {@code bodyYaw} untouched (the animator passes NaN to ask "is it aiming"). The animator draws the rig before the moves tick, and
     * by then the carrier's own look goals (a llama looking around, a horse at the nearest player)
     * have turned the body again: a turn made only in {@link #tick} was undone before it was ever
     * drawn (measured: a shot left 47 degrees off the husk with the turn in place).
     */
    public static float drawnYaw(Mob carrier, float bodyYaw) {
        State st = STATE.get(carrier.getUUID());
        if (st == null || !(carrier.level() instanceof ServerLevel level)) {
            return bodyYaw;
        }
        UUID at = st.charging != null && carrier.tickCount <= st.chargeUntil ? st.charging
                : st.facing != null && carrier.tickCount <= st.faceUntil ? st.facing : null;
        if (at == null || !(level.getEntity(at) instanceof LivingEntity t) || !t.isAlive()) {
            return bodyYaw;
        }
        face(carrier, t.getX(), t.getZ());
        return carrier.yBodyRot;
    }

    // ── who it is aiming at ─────────────────────────────────────────────────────────────────────

    /**
     * The instinct's target if it has one, else the nearest monster it may go for, in sight — or,
     * with {@code farthest}, the farthest one in sight.
     */
    static LivingEntity target(ServerLevel level, Mob carrier, Species sp, boolean farthest) {
        UUID held = RigInstincts.targetOf(carrier);
        if (held != null && level.getEntity(held) instanceof LivingEntity e && e.isAlive()) {
            return e;
        }
        boolean friendly = Boolean.TRUE.equals(PetBehaviours.of(carrier).friendly());
        RigInstinct in = RigInstinct.of(sp);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        double range = 24.0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                carrier.getBoundingBox().inflate(range), x -> x != carrier && x.isAlive())) {
            if (!(e instanceof Enemy) || !RigInstincts.allowed(carrier, e, friendly, in, true)
                    || !carrier.hasLineOfSight(e)) {
                continue;
            }
            double d = carrier.distanceToSqr(e) * (farthest ? -1 : 1);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ── the six moves, as WishMobEntity had them ────────────────────────────────────────────────

    /** Marks a ball a rig creature threw: it hurts, and only what its thrower may hurt. */
    public static final String THROW_TAG = "mfam_rig_throw";

    /** A ball thrown by this creature, not yet in the world. */
    public static net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball throwBall(ServerLevel level, Mob carrier) {
        var ball = com.moddingfromamod.compat.Projectiles.snowball(level, carrier);
        ball.addTag(THROW_TAG);
        // What it throws: the snowball is drawn as the species' item, so "it throws fireballs"
        // is a fire charge flying at the monster. Only the look and the burn below change; a ball
        // still breaks no block and still hits only what mayThrowAt allows.
        var item = thrownItem(com.moddingfromamod.mob.RealMobBehaviours.speciesFor(carrier));
        if (item != null) {
            ball.setItem(new net.minecraft.world.item.ItemStack(item));
            // A fireball BURNS (ThrowLook): every client draws a burning entity wrapped in flames, which
            // is what turns the fire charge's dark sprite into fire. A projectile takes no harm from it.
            if (ThrowLook.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()).burning()) {
                ball.setRemainingFireTicks(BURN_IN_FLIGHT_TICKS);
            }
        }
        return ball;
    }

    /** How long a burning ball stays alight: longer than the longest throw (24 blocks, 19 ticks). */
    static final int BURN_IN_FLIGHT_TICKS = 200;

    /**
     * A thrown ball's trail ({@link ThrowLook}): flames behind a fireball, the ender shimmer behind a
     * pearl, coloured dust behind a dark thing with a colour of its own. Sent by the server along the
     * ball's path each tick, so a stock client sees it.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onThrowTicks(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball ball)
                || !(ball.level() instanceof ServerLevel level) || !ball.getTags().contains(THROW_TAG)
                || ball.isRemoved()) {
            return;
        }
        ThrowLook.Look look = ThrowLook.of(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ball.getItem().getItem()).toString());
        net.minecraft.core.particles.ParticleOptions p = switch (look.trail()) {
            case FLAME -> ParticleTypes.FLAME;
            case PORTAL -> ParticleTypes.PORTAL;
            case DUST -> new net.minecraft.core.particles.DustParticleOptions(look.rgb(), 1.0F);
            case NONE -> null;
        };
        if (p == null) {
            return;
        }
        // ONE burst a tick, at the middle of the move it just made and spread over half of it, so a
        // fast ball leaves a line rather than dots. One, not two: on a Bedrock phone each burst is a
        // packet (ModdyParticleBursts), and packet count is what floods a phone. Count 3 and a
        // spread inside 0.05-0.35 are baked burst buckets; never count 0 (directional: it falls back).
        double spread = trailSpread(ball.getX() - ball.xo, ball.getY() - ball.yo, ball.getZ() - ball.zo);
        level.sendParticles(p, (ball.getX() + ball.xo) / 2, (ball.getY() + ball.yo) / 2, (ball.getZ() + ball.zo) / 2,
                TRAIL_COUNT, spread, spread, spread, 0.005);
    }

    /** Particles in a trail burst: a baked Bedrock bucket ({@code ModdyParticleBursts.COUNTS}). */
    static final int TRAIL_COUNT = 3;

    /** A trail burst's spread: half the ball's last move, kept inside the baked buckets' 0.05-0.35. */
    static double trailSpread(double dx, double dy, double dz) {
        return Math.max(0.05, Math.min(0.35, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2));
    }

    /** The item a species' ball is drawn as; null for the plain snowball (none named, or unknown). */
    static net.minecraft.world.item.Item thrownItem(Species sp) {
        if (sp != null && sp.throwsItem().isEmpty()
                && sp.archetype() == com.moddingfromamod.species.Archetype.DRAGON) {
            return net.minecraft.world.item.Items.FIRE_CHARGE;   //: a dragon breathes fire
        }
        if (sp == null || sp.throwsItem().isEmpty()) {
            return null;
        }
        var id = net.minecraft.resources.Identifier.tryParse(sp.throwsItem());
        return id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
                .filter(i -> i != net.minecraft.world.item.Items.AIR).orElse(null);
    }

    /** A thrown fireball sets what it hits alight for this long, and never a block. */
    static final int FIREBALL_BURN_TICKS = 60;

    /**
     * May a ball this creature threw touch {@code target}? Only what the creature may hurt
     * ({@link RigInstincts#allowed}) — never its rider, its seat, its family, anything
     * invulnerable, and never a player from a friendly creature. A hit box is asked about as the
     * creature it stands for, so a ball never strikes its own thrower's body. Read by
     * {@code ProxyProjectileMixin} in {@code canHitEntity}, so a refused target is flown through.
     */
    public static boolean mayThrowAt(net.minecraft.world.entity.projectile.Projectile ball,
                                     net.minecraft.world.entity.Entity target) {
        if (!(ball.getOwner() instanceof Mob carrier)) {
            return true;
        }
        var t = HitProxies.isProxy(target) ? HitProxies.carrierOf(target) : target;
        if (!(t instanceof LivingEntity victim) || victim == carrier) {
            return false;
        }
        Species sp = com.moddingfromamod.mob.RealMobBehaviours.speciesFor(carrier);
        boolean friendly = Boolean.TRUE.equals(PetBehaviours.of(carrier).friendly());
        return RigInstincts.allowed(carrier, victim, friendly, RigInstinct.of(sp), true);
    }

    /**
     * <b>A thrown ball hurts</b> (chunk 2: "handle throw damage
     * explicitly"). The move throws a snowball because it is harmless to the world, and vanilla's
     * snowball hurts only blazes, so on every other mob the move was a push. It now lands half the
     * species' damage, as a slam does, as a {@code thrown} hit so vanilla's knock-back and
     * difficulty scaling apply. Who it may land on is {@link #mayThrowAt}, decided before the hit.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onThrowLands(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        var ball = event.getProjectile();
        if (ball.level().isClientSide() || !ball.getTags().contains(THROW_TAG)
                || !(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit)
                || !(ball.getOwner() instanceof Mob carrier)
                || !(hit.getEntity() instanceof LivingEntity victim)
                || !mayThrowAt(ball, victim)) {
            return;
        }
        Species sp = com.moddingfromamod.mob.RealMobBehaviours.speciesFor(carrier);
        float dmg = (float) Math.max(1.0, sp == null ? 2.0 : sp.damage()) * 0.5F;
        Damage.hurt(victim, carrier.damageSources().thrown(ball, carrier), dmg);
        if (thrownItem(sp) == net.minecraft.world.item.Items.FIRE_CHARGE) {
            victim.igniteForTicks(FIREBALL_BURN_TICKS);
        }
    }

    /**
     * Turn to the target and wind up; {@link #holdFacing} lets the move go {@link #AIM_TICKS} later.
     * A shot or a rush that left the same tick it turned went before the turn was drawn — the
     * first tick of every charge was tail first (measured, 180 degrees).
     */
    static boolean aim(Mob carrier, LivingEntity target, State st, Abilities.Kind kind) {
        if (target == null) {
            return false;
        }
        face(carrier, target);
        st.facing = target.getUUID();
        st.pending = kind;
        st.shootAt = carrier.tickCount + AIM_TICKS;
        st.faceUntil = st.shootAt + FACE_AFTER_TICKS;
        if (kind == Abilities.Kind.CHARGE && carrier.level() instanceof ServerLevel level) {
            sound(level, carrier, SoundEvents.RAVAGER_ROAR, 1.2F);   // the wind-up is heard
        }
        return true;
    }

    /**
     * Keep facing whoever it aimed at or acted on, and let an aimed shot go. True while it is still
     * aiming, so no other move starts on top of the shot.
     */
    private static boolean holdFacing(ServerLevel level, Mob carrier, State st) {
        if (st.facing == null) {
            return false;
        }
        if (carrier.tickCount > st.faceUntil
                || !(level.getEntity(st.facing) instanceof LivingEntity t) || !t.isAlive()) {
            st.facing = null;
            st.shootAt = -1;
            return false;
        }
        face(carrier, t);
        if (st.shootAt >= 0) {
            if (carrier.tickCount < st.shootAt) {
                return true;
            }
            st.shootAt = -1;
            if (st.pending == Abilities.Kind.CHARGE) {
                charge(level, carrier, t, st);
            } else {
                shoot(level, carrier, t);
            }
            st.pending = null;
        }
        return false;
    }

    /**
     * Throw a snowball-ish projectile, harmless to the world, so it is SEEN going: it leaves
     * from where the body is drawn holding or throwing it ({@link RigLaunch#from} — a held thing, a
     * hand, the mouth, the front of the body), never the invisible carrier's eye inside the body;
     * and it takes at least {@link RigLaunch#MIN_FLIGHT_TICKS} to arrive, landing on the target in
     * exactly that many ticks. The arm swings as it goes.
     */
    static boolean shoot(ServerLevel level, Mob carrier, LivingEntity target) {
        if (target == null) {
            return false;
        }
        face(carrier, target);
        var ball = throwBall(level, carrier);
        Vec3 aim = new Vec3(target.getX(), target.getY(0.5), target.getZ());
        Vec3 from = launchPoint(carrier, aim);
        if (from != null) {
            ball.setPos(from);
        }
        Vec3 start = ball.position();
        double[] v = RigLaunch.velocity(new double[]{start.x, start.y, start.z},
                new double[]{aim.x, aim.y, aim.z}, RigLaunch.flightTicks(start.distanceTo(aim)));
        ball.setDeltaMovement(v[0], v[1], v[2]);
        double h = Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        ball.setYRot((float) (Math.atan2(v[0], v[2]) * 180.0 / Math.PI));
        ball.setXRot((float) (Math.atan2(v[1], h) * 180.0 / Math.PI));
        ball.yRotO = ball.getYRot();
        ball.xRotO = ball.getXRot();
        level.addFreshEntity(ball);
        carrier.swing(InteractionHand.MAIN_HAND);   // its attack clip, if it has one: the throw
        sound(level, carrier, SoundEvents.SNOW_GOLEM_SHOOT, 1.0F);
        return true;
    }

    /**
     * Where this creature's throw leaves its DRAWN body, in the world, aiming at {@code aim}; null
     * when it has no live rig (the ball then stays at the carrier's eye, as vanilla puts it).
     */
    static Vec3 launchPoint(Mob carrier, Vec3 aim) {
        java.util.List<RigLaunch.Part> parts = drawnParts(carrier);
        if (parts.isEmpty()) {
            return null;
        }
        float yaw = carrier.yBodyRot;
        double[] t = intoRig(aim, carrier.position(), yaw);
        Species sp = com.moddingfromamod.mob.RealMobBehaviours.speciesFor(carrier);
        var item = thrownItem(sp);
        String thrown = item == null ? ""
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        RigLaunch.Launch l = RigLaunch.from(parts, thrown, t);
        return HitProxies.toWorld(l.x(), l.y(), l.z(), carrier.position(), yaw);
    }

    /** A world point in the rig's frame (the inverse of {@link HitProxies#toWorld}). */
    static double[] intoRig(Vec3 p, Vec3 feet, float bodyYaw) {
        double a = Math.toRadians(DisplayRig.displayYaw(bodyYaw));
        double c = Math.cos(a);
        double s = Math.sin(a);
        double dx = p.x - feet.x;
        double dz = p.z - feet.z;
        return new double[]{dx * c + dz * s, p.y - feet.y, -dx * s + dz * c};
    }

    /** The carrier's rig as drawn at rest: one named box per bone, rig frame, blocks over its feet. */
    static java.util.List<RigLaunch.Part> drawnParts(Mob carrier) {
        DisplayRig rig = VanillaRigAnimator.rigOf(carrier);
        java.util.List<RigLaunch.Part> out = new java.util.ArrayList<>();
        if (rig == null) {
            return out;
        }
        for (RigPlan.Group g : rig.groups()) {
            if (g.boxCount() != 1) {
                continue;
            }
            RigTransform.Placed p = RigTransform.placeAtomAsAuthored(g, rig.scale());
            double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int k = 0; k < 8; k++) {
                org.joml.Vector3f m = new org.joml.Vector3f((k & 1) == 0 ? -0.5f : 0.5f,
                        (k & 2) == 0 ? -0.5f : 0.5f, (k & 4) == 0 ? -0.5f : 0.5f);
                m.rotate(p.rightRotation()).mul(p.scale()).rotate(p.leftRotation()).add(p.translation());
                lo[0] = Math.min(lo[0], m.x);
                lo[1] = Math.min(lo[1], m.y);
                lo[2] = Math.min(lo[2], m.z);
                hi[0] = Math.max(hi[0], m.x);
                hi[1] = Math.max(hi[1], m.y);
                hi[2] = Math.max(hi[2], m.z);
            }
            var part = g.parts().get(0);
            out.add(new RigLaunch.Part(part.name(), part.parent(), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        }
        return out;
    }

    /**
     * A slam is a STOMP: it goes up and the blow lands when it comes down. A film
     * showed the old one as nothing at all — monsters round a creature that stood
     * still were knocked over. Rather than one canned animation, the slam rides the creature's own
     * jump: {@link RigGait} already draws a take-off, an arc and a landing crouch from the
     * carrier's real motion, solved on its own legs, so a dog, a dinosaur and a spider each stomp
     * the way they jump. And what puts it in the air depends on what it was doing:
     * <ul>
     *   <li>standing: it hops ({@link #SLAM_HOP_VY});</li>
     *   <li>already in the air (a jump, a fall, its rider's jump): no hop — the slam is that
     *       landing;</li>
     *   <li>ridden and on the ground: the blast is immediate — it never bounces its rider;</li>
     *   <li>a flier: there is no ground to stomp, so the blast is immediate.</li>
     * </ul>
     * Nothing to hit, nothing happens: it never hops at empty air.
     */
    static boolean slam(ServerLevel level, Mob carrier, Species sp, LivingEntity target, State st) {
        if (victims(level, carrier, sp).isEmpty()) {
            return false;
        }
        if (target != null) {
            face(carrier, target);
            st.facing = target.getUUID();   // what it is going for, which its helpers copy
            st.faceUntil = carrier.tickCount + SLAM_MAX_AIR_TICKS;
        }
        if (RigPowers.flies(carrier) || carrier.isVehicle() && carrier.onGround()) {
            // A flier has no ground to stomp. A creature with a child on its back never bounces him
            // on its own (a stray hurt to the rider was seen once); when HE jumps it, the landing
            // is the slam, below.
            return slamLands(level, carrier, sp);
        }
        st.slamFrom = carrier.tickCount;
        st.slamAirborne = !carrier.onGround();
        if (carrier.onGround()) {
            var v = carrier.getDeltaMovement();
            carrier.setDeltaMovement(v.x * 0.3, SLAM_HOP_VY, v.z * 0.3);
            carrier.needsSync = true;
        }
        return true;
    }

    /** Waiting for the slam to come down; the blow lands on the first tick back on the ground. */
    private static void landSlam(ServerLevel level, Mob carrier, Species sp, State st) {
        if (!carrier.onGround() || carrier.isInWater()) {
            st.slamAirborne = true;
            if (carrier.tickCount - st.slamFrom < SLAM_MAX_AIR_TICKS) {
                return;
            }
        } else if (!st.slamAirborne && carrier.tickCount - st.slamFrom < 3) {
            return;   // the hop has not left the ground yet (the velocity applies next tick)
        }
        st.slamFrom = -1;
        st.slamAirborne = false;
        slamLands(level, carrier, sp);
    }

    /** What a slam may land on, round it now. */
    private static java.util.List<LivingEntity> victims(ServerLevel level, Mob carrier, Species sp) {
        boolean friendly = Boolean.TRUE.equals(PetBehaviours.of(carrier).friendly());
        RigInstinct in = RigInstinct.of(sp);
        return level.getEntitiesOfClass(LivingEntity.class,
                carrier.getBoundingBox().inflate(SLAM_RADIUS),
                e -> e != carrier && e.isAlive() && RigInstincts.allowed(carrier, e, friendly, in, true));
    }

    /** The impact: what it may hit, close by, is knocked away. Never breaks blocks. */
    static boolean slamLands(ServerLevel level, Mob carrier, Species sp) {
        float dmg = (float) Math.max(1.0, sp.damage()) * 0.5F;
        for (LivingEntity victim : victims(level, carrier, sp)) {
            double dx = victim.getX() - carrier.getX();
            double dz = victim.getZ() - carrier.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            victim.push(dx / len * 0.8, 0.5, dz / len * 0.8);
            victim.hurtMarked = true;
            Damage.hurt(victim, carrier.damageSources().mobAttack(carrier), dmg);
        }
        carrier.swing(InteractionHand.MAIN_HAND);   // its attack clip, if it has one, on the blow
        shockwave(level, carrier);
        return true;
    }

    /**
     * The ground answers: a ring of the block it landed on, thrown up out to the slam's reach, and
     * the mace's own heavy smash. Shows where the slam reached, which is who it can hit.
     */
    private static void shockwave(ServerLevel level, Mob carrier) {
        var below = carrier.getBlockPosBelowThatAffectsMyMovement();
        var ground = level.getBlockState(below);
        double y = carrier.getY() + 0.1;
        double r0 = carrier.getBbWidth() / 2 + 0.5;
        if (!ground.isAir()) {
            var dust = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);
            for (double r = r0; r <= SLAM_RADIUS + carrier.getBbWidth() / 2; r += 1.0) {
                int n = (int) Math.ceil(r * 6);
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    level.sendParticles(dust, carrier.getX() + Math.cos(a) * r, y,
                            carrier.getZ() + Math.sin(a) * r, 2, 0.1, 0.05, 0.1, 0.15);
                }
            }
        }
        level.sendParticles(ParticleTypes.DUST_PLUME, carrier.getX(), y, carrier.getZ(),
                12, r0, 0.1, r0, 0.05);
        level.playSound(null, carrier.getX(), carrier.getY(), carrier.getZ(),
                SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 1.4F, 0.8F);
        sound(level, carrier, SoundEvents.GENERIC_EXPLODE.value(), 1.4F);
    }

    /** A burst of speed straight at the target; its body lands a hit if it arrives. */
    static boolean charge(ServerLevel level, Mob carrier, LivingEntity target, State st) {
        if (target == null) {
            return false;
        }
        double dx = target.getX() - carrier.getX();
        double dz = target.getZ() - carrier.getZ();
        double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        face(carrier, target);
        carrier.push(dx / len * 1.2, 0.35, dz / len * 1.2);
        carrier.needsSync = true;
        st.charging = target.getUUID();
        st.chargeUntil = carrier.tickCount + CHARGE_HIT_TICKS;
        return true;
    }

    /**
     * The charge's hit. A wish mob's own melee goal landed it; a carrier's melee is
     * {@link RigInstincts}' and only a creature that fights has one, so the charge carries its own.
     */
    private static void finishCharge(ServerLevel level, Mob carrier, Species sp, State st) {
        if (st.charging == null) {
            return;
        }
        if (carrier.tickCount > st.chargeUntil) {
            stopCharge(carrier, st, level.getEntity(st.charging) instanceof LivingEntity t ? t : null);
            return;
        }
        if (!(level.getEntity(st.charging) instanceof LivingEntity t) || !t.isAlive()) {
            st.charging = null;
            return;
        }
        if (!carrier.getBoundingBox().inflate(0.75).intersects(t.getBoundingBox())) {
            // KEEP GOING until it lands or the window closes. One push is ~2.5 blocks before friction
            // stops it; on WishMobEntity its melee AI ran the rest of the way, and a rig carrier with
            // no hunting instinct has none, so a charge from 8 blocks off stopped short (measured).
            double dx = t.getX() - carrier.getX();
            double dz = t.getZ() - carrier.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            carrier.setDeltaMovement(dx / len * CHARGE_SPEED, carrier.getDeltaMovement().y, dz / len * CHARGE_SPEED);
            face(carrier, t);   // head first, every tick of the rush
            carrier.needsSync = true;
            return;
        }
        double dx = t.getX() - carrier.getX();
        double dz = t.getZ() - carrier.getZ();
        double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        t.push(dx / len * 0.9, 0.3, dz / len * 0.9);
        t.hurtMarked = true;
        Damage.hurt(t, carrier.damageSources().mobAttack(carrier), (float) Math.max(2.0, sp.damage()));
        carrier.swing(InteractionHand.MAIN_HAND);
        stopCharge(carrier, st, t);
    }

    /**
     * A rush ends where it ends: it pulls up, still facing what it ran at. Left to coast, it slid on
     * past for a tick or two while its own look goals turned it, and read as skidding sideways.
     */
    private static void stopCharge(Mob carrier, State st, LivingEntity at) {
        st.charging = null;
        var v = carrier.getDeltaMovement();
        carrier.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
        if (at != null && at.isAlive()) {
            st.facing = at.getUUID();
            st.faceUntil = carrier.tickCount + FACE_AFTER_TICKS;
        }
    }

    /** A helper's summoner: {@code mfam_minion_of_<uuid>}. Tags, so it survives a save. */
    static final String MINION_OF = "mfam_minion_of_";
    /** When a helper leaves: {@code mfam_minion_until_<game time>}. */
    static final String MINION_UNTIL = "mfam_minion_until_";

    /**
     * Call in two small copies of itself, at its sides. Capped so a summoner can't fork-bomb the
     * world. They are the evoker's vexes, as far as vanilla goes: they fight whatever their
     * summoner fights and whoever hurts it, and they leave after a vex's lifetime
     * ({@link RigInstincts} carries that out, {@link #ownerOf}, {@link #expiresAt}). A
     * film had them standing about while the summoner was punched, and appearing from
     * nowhere: a trail of sparkles now runs from the summoner to each one as it arrives.
     */
    static boolean summon(ServerLevel level, Mob carrier, Species sp) {
        if (carrier.getTags().contains(MINION_TAG)) {
            return false;
        }
        if (!carrier.getTags().contains(DisplayRig.RIG_TAG)) {
            return summonReal(level, carrier);
        }
        int nearby = level.getEntitiesOfClass(Mob.class, carrier.getBoundingBox().inflate(24.0),
                e -> e.getTags().contains(DisplayRig.RIG_TAG)).size();
        if (nearby > SUMMON_CAP) {
            return false;
        }
        // Its two sides, clear of its own body: a fixed 1.5 blocks put them inside a big one.
        double yaw = Math.toRadians(carrier.yBodyRot);
        double sideX = Math.cos(yaw), sideZ = Math.sin(yaw);
        double gap = carrier.getBbWidth() / 2 + 0.5 + sp.scale() * 0.5;
        boolean any = false;
        for (int i = 0; i < 2; i++) {
            var mob = com.moddingfromamod.entity.ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
            if (mob == null) {
                continue;
            }
            double sgn = i == 0 ? 1 : -1;
            Vec3 at = carrier.position().add(sideX * gap * sgn, 0, sideZ * gap * sgn);
            mob.snapTo(at.x, at.y, at.z, carrier.getYRot(), 0);
            mob.setSpeciesId(sp.id());
            mob.setMinion(true);
            // Minions are small and weak, so summoning is dramatic rather than a difficulty spike.
            mob.setBaseScale(sp.scale() * 0.5);
            LivingEntity helper = VanillaRigBridge.manifestNow(level, mob);
            if (helper != null) {
                // Never into a wall: its side may be a tree, a cliff or a fence. The spot the
                // follower hop and the spawn already trust (SafeSpot), nearest first.
                var w = SafePlacement.world(level);
                var body = SafePlacement.body(helper);
                if (com.moddingfromamod.runtime.SafeSpot.check(w, body, helper.getX(), helper.getY(), helper.getZ())
                        != com.moddingfromamod.runtime.SafeSpot.Problem.NONE) {
                    var spot = com.moddingfromamod.runtime.SafeSpot.nearest(w, body,
                            carrier.getX(), carrier.getY(), carrier.getZ(), 4, 2, 3);
                    if (spot != null) {
                        helper.teleportTo(spot.x(), spot.y(), spot.z());
                    }
                }
                helper.addTag(MINION_TAG);
                helper.addTag(MINION_OF + carrier.getUUID());
                // A vex's life: 20 * (30 + random(90)) ticks (Evoker.EvokerSummonSpellGoal).
                helper.addTag(MINION_UNTIL + (level.getGameTime() + 20L * (30 + level.random.nextInt(90))));
                arrive(level, carrier, helper.position());
                any = true;
            }
        }
        if (any) {
            sound(level, carrier, SoundEvents.EVOKER_PREPARE_SUMMON, 1.0F);
            sound(level, carrier, SoundEvents.EVOKER_CAST_SPELL, 1.0F);
            level.sendParticles(ParticleTypes.WITCH, carrier.getX(), carrier.getY() + carrier.getBbHeight() * 0.8,
                    carrier.getZ(), 20, carrier.getBbWidth() / 2, 0.4, carrier.getBbWidth() / 2, 0.05);
        }
        return any;
    }

    /**
     * <b>A real mob's summon</b>: two more of its OWN kind, the way a rig creature calls in two
     * small copies of itself. Same cap, same marks (they never summon in turn, they leave after a
     * vex's life, they are its family), and its pet behaviour and profile copied over so a friendly
     * enderman's helpers are friendly endermen. Never a sculpted creature: a real mob has no species
     * to copy.
     */
    static boolean summonReal(ServerLevel level, Mob carrier) {
        int nearby = level.getEntitiesOfClass(Mob.class, carrier.getBoundingBox().inflate(24.0),
                e -> carrier.getUUID().equals(ownerOf(e))).size();
        if (nearby >= 2) {
            return false;   // its last two are still about
        }
        boolean any = false;
        double yaw = Math.toRadians(carrier.yBodyRot);
        double sideX = Math.cos(yaw), sideZ = Math.sin(yaw);
        double gap = carrier.getBbWidth() / 2 + 1.0;
        for (int i = 0; i < 2; i++) {
            var made = carrier.getType().create(level, EntitySpawnReason.MOB_SUMMONED);
            if (!(made instanceof Mob helper)) {
                if (made != null) {
                    made.discard();
                }
                continue;
            }
            double sgn = i == 0 ? 1 : -1;
            Vec3 at = carrier.position().add(sideX * gap * sgn, 0, sideZ * gap * sgn);
            helper.snapTo(at.x, at.y, at.z, carrier.getYRot(), 0);
            var w = SafePlacement.world(level);
            var body = SafePlacement.body(helper);
            if (com.moddingfromamod.runtime.SafeSpot.check(w, body, helper.getX(), helper.getY(), helper.getZ())
                    != com.moddingfromamod.runtime.SafeSpot.Problem.NONE) {
                var spot = com.moddingfromamod.runtime.SafeSpot.nearest(w, body,
                        carrier.getX(), carrier.getY(), carrier.getZ(), 4, 2, 3);
                if (spot == null) {
                    helper.discard();
                    continue;
                }
                helper.snapTo(spot.x(), spot.y(), spot.z(), carrier.getYRot(), 0);
            }
            helper.addTag(MINION_TAG);
            helper.addTag(MINION_OF + carrier.getUUID());
            helper.addTag(MINION_UNTIL + (level.getGameTime() + 20L * (30 + level.random.nextInt(90))));
            level.addFreshEntity(helper);
            com.moddingfromamod.mob.PetBehaviours.putBack(helper,
                    com.moddingfromamod.mob.PetBehaviours.stored(carrier),
                    com.moddingfromamod.mob.PetBehaviours.ownerStored(carrier));
            com.moddingfromamod.mob.RealMobBehaviours.restore(helper,
                    com.moddingfromamod.mob.RealMobBehaviours.stored(carrier));
            arrive(level, carrier, helper.position());
            any = true;
        }
        if (any) {
            sound(level, carrier, SoundEvents.EVOKER_PREPARE_SUMMON, 1.0F);
            sound(level, carrier, SoundEvents.EVOKER_CAST_SPELL, 1.0F);
        }
        return any;
    }

    /** Where a helper came from: sparkles from the summoner to it, and a puff where it stands. */
    private static void arrive(ServerLevel level, Mob from, Vec3 at) {
        Vec3 a = from.position().add(0, from.getBbHeight() * 0.6, 0);
        Vec3 b = at.add(0, 0.4, 0);
        for (int k = 1; k <= 10; k++) {
            Vec3 p = a.lerp(b, k / 10.0);
            level.sendParticles(ParticleTypes.WITCH, p.x, p.y + Math.sin(Math.PI * k / 10.0) * 0.8, p.z,
                    2, 0.05, 0.05, 0.05, 0.0);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.5, at.z, 30, 0.3, 0.5, 0.3, 0.05);
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.3, at.z, 8, 0.3, 0.2, 0.3, 0.02);
    }

    /** The summoner of a helper, or null for a creature that is not one. */
    public static UUID ownerOf(net.minecraft.world.entity.Entity e) {
        for (String t : e.getTags()) {
            if (t.startsWith(MINION_OF)) {
                try {
                    return UUID.fromString(t.substring(MINION_OF.length()));
                } catch (IllegalArgumentException bad) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The game time a helper leaves at, or {@link Long#MAX_VALUE} for one that never does. */
    public static long expiresAt(net.minecraft.world.entity.Entity e) {
        for (String t : e.getTags()) {
            if (t.startsWith(MINION_UNTIL)) {
                try {
                    return Long.parseLong(t.substring(MINION_UNTIL.length()));
                } catch (NumberFormatException bad) {
                    return Long.MAX_VALUE;
                }
            }
        }
        return Long.MAX_VALUE;
    }

    /** One family: a summoner and its helpers, or two helpers of one summoner. Never each other's victims. */
    public static boolean family(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
        UUID oa = ownerOf(a), ob = ownerOf(b);
        return (oa != null && oa.equals(b.getUUID())) || (ob != null && ob.equals(a.getUUID()))
                || (oa != null && oa.equals(ob));
    }

    /** What a creature is acting on right now — aiming at, rushing, or just hit — or null. */
    public static UUID aimOf(Mob carrier) {
        State st = STATE.get(carrier.getUUID());
        if (st == null) {
            return null;
        }
        if (st.charging != null) {
            return st.charging;
        }
        return st.facing != null && carrier.tickCount <= st.faceUntil ? st.facing : null;
    }

    /** Blink next to the target. */
    static boolean blink(ServerLevel level, Mob carrier, LivingEntity target, State st) {
        if (target == null) {
            return false;
        }
        if (carrier.isVehicle()) {
            return false;   // never out from under its rider
        }
        level.sendParticles(ParticleTypes.PORTAL, carrier.getX(), carrier.getY() + 1, carrier.getZ(),
                24, 0.4, 0.8, 0.4, 0.4);
        // Beside it, not inside it, and facing it: arriving on top of the monster read as nothing.
        double dx = carrier.getX() - target.getX();
        double dz = carrier.getZ() - target.getZ();
        double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        double gap = target.getBbWidth() / 2 + carrier.getBbWidth() / 2 + 0.5;
        carrier.teleportTo(target.getX() + dx / len * gap, target.getY(), target.getZ() + dz / len * gap);
        face(carrier, target);
        st.facing = target.getUUID();
        st.faceUntil = carrier.tickCount + FACE_AFTER_TICKS;
        level.sendParticles(ParticleTypes.PORTAL, carrier.getX(), carrier.getY() + 1, carrier.getZ(),
                24, 0.4, 0.8, 0.4, 0.4);
        sound(level, carrier, SoundEvents.ENDERMAN_TELEPORT, 1.0F);
        return true;
    }

    static boolean heal(ServerLevel level, Mob carrier) {
        if (carrier.getHealth() >= carrier.getMaxHealth()) {
            return false;
        }
        // A tenth of its health, at least the wish mob's flat 2: two points every five seconds was
        // made for a 20-health mob, and on a 120-health creature it could not be seen (
        // re-measure: 0 of 2 heal wishes showed any).
        carrier.heal(Math.max(2.0F, carrier.getMaxHealth() * 0.1F));
        level.sendParticles(ParticleTypes.HEART, carrier.getX(), carrier.getY() + 1, carrier.getZ(),
                3, 0.3, 0.3, 0.3, 0.0);
        return true;
    }

    /**
     * Turn the whole creature to face {@code target}. The rig draws the carrier's BODY yaw, and only
     * a carrier's own MoveControl turns that: a move that shoots, or pushes the carrier itself,
     * left it facing wherever it last walked — films showed a ranged
     * creature throwing over its shoulder and a charge running tail first. Head and look too, so
     * the carrier's BodyRotationControl does not turn it straight back to an old look.
     */
    static void face(Mob carrier, double tx, double tz) {
        double dx = tx - carrier.getX();
        double dz = tz - carrier.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        carrier.setYRot(yaw);
        carrier.setYBodyRot(yaw);
        carrier.setYHeadRot(yaw);
    }

    static void face(Mob carrier, LivingEntity target) {
        face(carrier, target.getX(), target.getZ());
        carrier.getLookControl().setLookAt(target, 180f, 40f);
    }

    /** By the level, because the carrier is silent and a silent entity's own sounds are dropped. */
    private static void sound(ServerLevel level, Mob carrier, SoundEvent sound, float pitch) {
        level.playSound(null, carrier.getX(), carrier.getY(), carrier.getZ(), sound, SoundSource.HOSTILE,
                1.0F, pitch);
    }
}
