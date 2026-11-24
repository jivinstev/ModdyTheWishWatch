package com.moddingfromamod.vanilla;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Damage;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.species.Species;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * <b>A species' instincts, carried out by its rig carrier</b>: a {@code coward}
 * runs, a {@code guardian} hunts monsters, a {@code hunter} or a hostile creature hunts players,
 * and a {@code leaps} creature pounces. {@link RigInstinct} says which; this class does it.
 *
 * <p><b>Why not goals.</b> {@code WishMobEntity} got all of this from vanilla goals, and a carrier
 * cannot take them: half the carriers run a {@code Brain}, and a goal added beside a brain fights
 * it ({@code PetBehaviours} says the same). Worse, a cow, a pig or a panda has <b>no attack damage
 * attribute</b>, so a {@code MeleeAttackGoal} on one throws. So, the way {@code PetBehaviours}
 * follows:
 * <ul>
 *   <li><b>moving</b> is the carrier's own navigation, plus a brain's {@code WALK_TARGET} when it
 *       has one;</li>
 *   <li><b>the target</b> is held HERE, never in the carrier's {@code target} field. An iron golem
 *       carrier's own melee goal reads that field and would land a golem's punch (7 to 21) on top
 *       of the species' own;</li>
 *   <li><b>the hit</b> is ours: the species' {@code damage}, once a second, from within a melee
 *       mob's reach, as a {@code mobAttack} so vanilla's difficulty scaling applies. The swing
 *       plays the rig's attack clip, because the animator reads {@code swinging}.</li>
 * </ul>
 *
 * <p><b>Who it never hurts.</b> A player in creative or spectator; any player on Peaceful (vanilla
 * deletes a Monster there, and this creature is not one); its own rider; and any player at all
 * when it is friendly, by its species or by the child's own {@code modify_mob}, read through
 * {@link PetBehaviours#of}, so "make it friendly" wins over a hunter.
 *
 * <p>Driven from {@code VanillaRigAnimator}'s tick, so every rig the animator drives has its
 * instincts, from whichever of the three birth points it came. Nothing has to register.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigInstincts {

    /** How often it looks around and steers; a hit is checked every tick. */
    static final int STEP = 5;
    /** How long it remembers who hurt it, in ticks. */
    static final int HURT_MEMORY = 100;

    private static final class State {
        UUID target;
        long lastHit = Long.MIN_VALUE / 2;
        long lastLeap = Long.MIN_VALUE / 2;
        UUID hurtBy;
        /** Who it is running from, until it is {@link RigInstinct#FLEE_DISTANCE} clear. */
        UUID fleeFrom;
        long hurtAt = Long.MIN_VALUE / 2;
        /** The chase's nearest distance so far and when, to give up a target it cannot get to. */
        UUID chasing;
        double best = Double.MAX_VALUE;
        long bestAt;
        /** A target it gave up on, and until when it leaves it alone. */
        UUID gaveUp;
        long gaveUpUntil;
    }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();

    private RigInstincts() {
    }

    /** One tick of one carrier. {@code sp} is the species the rig was built from. */
    public static void tick(Mob carrier, Species sp) {
        drive(carrier, sp, false);
    }

    /**
     * One tick of a REAL mob with a behaviour profile: the same code, with the real-mob
     * instinct rule ({@link RigInstinct#forRealMob}) — its own nature stands in for "wild".
     */
    public static void tickReal(Mob mob, Species sp) {
        drive(mob, sp, true);
    }

    private static void drive(Mob carrier, Species sp, boolean realMob) {
        // NoAI is vanilla's "this mob does nothing of its own accord": it neither hunts nor runs,
        // and a pounce (a velocity we set) would move it anyway, so it is honoured here too.
        if (!(carrier.level() instanceof ServerLevel level) || !carrier.isAlive()
                || carrier.isNoAi()) {
            return;
        }
        if (RigAbilities.ownerOf(carrier) != null) {
            helper(level, carrier, sp);
            return;
        }
        RigInstinct in = realMob ? RigInstinct.forRealMob(sp.base(), sp.powers()) : RigInstinct.of(sp);
        // how its ARCHETYPE fights, when its temperament says it does — a pack hunter or a
        // climber pounces; and a grazer, a mount or a villager that does not fight bolts when it is
        // HURT (vanilla's PanicGoal), though it never runs from a player who only comes close.
        com.moddingfromamod.species.Archetype arch = realMob || sp == null ? null : sp.archetype();
        boolean panicOnly = false;
        if (arch != null) {
            var fight = arch.life().fight();
            if (in.fights() && fight == com.moddingfromamod.species.Archetype.Fight.LEAP && !in.leaps()) {
                in = new RigInstinct(in.flees(), true, in.huntsPlayers(), in.huntsMonsters(), in.huntsAnimals(), true);
            } else if (!in.fights() && !in.flees() && fight == com.moddingfromamod.species.Archetype.Fight.FLEE) {
                in = new RigInstinct(true, false, false, false, false, false);
                panicOnly = true;
            }
        }
        if (in.isEmpty()) {
            return;
        }
        State st = STATE.computeIfAbsent(carrier.getUUID(), k -> new State());
        long now = level.getGameTime();
        PetBehaviour pet = PetBehaviours.of(carrier);
        boolean friendly = Boolean.TRUE.equals(pet.friendly());
        boolean follows = Boolean.TRUE.equals(pet.follow());
        if (carrier.isVehicle() && Boolean.TRUE.equals(pet.rideable())) {
            return;   // ridden: the rider steers (PetBehaviours.steer)
        }

        if (in.fights()) {
            LivingEntity target = resolve(level, st.target);
            if (target != null && !allowed(carrier, target, friendly, in, true)) {
                target = null;
            }
            if (target == null && st.hurtBy != null && now - st.hurtAt <= HURT_MEMORY) {
                LivingEntity attacker = resolve(level, st.hurtBy);
                if (attacker != null && allowed(carrier, attacker, friendly, in, true)) {
                    target = attacker;
                }
            }
            if (target == null && in.hunts() && now % STEP == 0) {
                target = look(level, carrier, friendly, in);
            }
            if (target != null && target.getUUID().equals(st.gaveUp) && now < st.gaveUpUntil) {
                target = null;   // one it could not get to: left alone a while (RigInstinct.givesUp)
            }
            st.target = target == null ? null : target.getUUID();
            if (target != null) {
                chase(carrier, target, in, st, now);
                return;
            }
        }

        if (in.flees() && now % STEP == 0) {
            Entity threat = null;
            if (st.hurtBy != null && now - st.hurtAt <= HURT_MEMORY) {
                threat = resolve(level, st.hurtBy);
            }
            if (threat == null && !follows && !panicOnly) {
                threat = resolve(level, st.fleeFrom);
                if (threat == null) {
                    threat = level.getNearestPlayer(carrier.getX(), carrier.getY(),
                            carrier.getZ(), RigInstinct.FLEE_START, p -> !p.isSpectator());
                }
            }
            // Once it has started it keeps going until it is well clear, the way vanilla's
            // AvoidEntityGoal does, rather than stopping on the edge of FLEE_START and dithering
            // there while the child stands still.
            if (threat != null && carrier.distanceTo(threat) < RigInstinct.FLEE_DISTANCE) {
                // Aimed when the run STARTS (the navigation may be busy with the carrier's own
                // wandering) and again whenever it arrives -- never re-rolled mid-run, which left
                // a camel dithering in place (measured: 1.4 blocks from the child, navigating).
                boolean starting = !threat.getUUID().equals(st.fleeFrom);
                st.fleeFrom = threat.getUUID();
                if (starting || carrier.getNavigation().isDone()) {
                    flee(carrier, threat);
                }
            } else {
                st.fleeFrom = null;
            }
        }
    }

    // ── a summoned helper ──────────────────────────────────────────────────────────────────────

    /** How far a helper strays from its summoner before it goes back to it (a wolf's 10, roughly). */
    static final double HELPER_LEASH = 8.0;

    /**
     * A summoned helper is the evoker's vex and the tamed wolf, nothing more: it goes for whoever
     * hurt its summoner ({@code OwnerHurtByTargetGoal}), else whatever its summoner is going for
     * ({@code VexCopyOwnerTargetGoal}), else whoever hurt IT; otherwise it keeps near its summoner
     * ({@code FollowOwnerGoal}); and it leaves when a vex's life is up. Its own species' instincts
     * do not apply — "calls in helpers to fight beside it" is what the summon is for, and a
     * friendly species' helpers stood about while the summoner was punched (on film).
     * Friendly if its summoner is, so a friendly creature's helpers never go for a
     * player even when the child is the one punching.
     */
    private static void helper(ServerLevel level, Mob carrier, Species sp) {
        long now = level.getGameTime();
        if (now >= RigAbilities.expiresAt(carrier)) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, carrier.getX(),
                    carrier.getY() + carrier.getBbHeight() / 2, carrier.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
            carrier.kill(level);
            return;
        }
        Mob owner = level.getEntity(RigAbilities.ownerOf(carrier)) instanceof Mob m && m.isAlive() ? m : null;
        boolean friendly = Boolean.TRUE.equals(PetBehaviours.of(carrier).friendly())
                || owner != null && Boolean.TRUE.equals(PetBehaviours.of(owner).friendly());
        RigInstinct in = new RigInstinct(false, true, false, false, false, RigInstinct.of(sp).leaps());
        State st = STATE.computeIfAbsent(carrier.getUUID(), k -> new State());
        LivingEntity target = resolve(level, st.target);
        if (target != null && !allowed(carrier, target, friendly, in, true)) {
            target = null;
        }
        if (owner != null && now % STEP == 0) {
            // The summoner's fight comes first and is re-read, so the helpers turn with it.
            State os = STATE.get(owner.getUUID());
            LivingEntity pick = null;
            if (os != null && os.hurtBy != null && now - os.hurtAt <= HURT_MEMORY) {
                pick = resolve(level, os.hurtBy);
            }
            if (pick == null && os != null) {
                pick = resolve(level, os.target);
            }
            if (pick == null) {
                pick = resolve(level, RigAbilities.aimOf(owner));
            }
            if (pick != null && allowed(carrier, pick, friendly, in, true)) {
                target = pick;
            }
        }
        if (target == null && st.hurtBy != null && now - st.hurtAt <= HURT_MEMORY) {
            LivingEntity attacker = resolve(level, st.hurtBy);
            if (attacker != null && allowed(carrier, attacker, friendly, in, true)) {
                target = attacker;
            }
        }
        st.target = target == null ? null : target.getUUID();
        if (target != null) {
            chase(carrier, target, in, st, now);
            return;
        }
        if (owner != null && now % STEP == 0 && carrier.distanceTo(owner) > HELPER_LEASH) {
            walk(carrier, owner.position(), owner, RigInstinct.CHASE_SPEED);
        }
    }

    /** Is it chasing or running right now? {@code PetBehaviours} does not follow while it is. */
    public static boolean busy(Mob carrier) {
        State st = STATE.get(carrier.getUUID());
        return st != null && st.target != null;
    }

    /** Who it is hunting, for a test. */
    public static UUID targetOf(Mob carrier) {
        State st = STATE.get(carrier.getUUID());
        return st == null ? null : st.target;
    }

    public static void forget(UUID carrier) {
        STATE.remove(carrier);
    }

    // ── hunting ────────────────────────────────────────────────────────────────────────────────

    private static LivingEntity look(ServerLevel level, Mob carrier, boolean friendly,
                                     RigInstinct in) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                carrier.getBoundingBox().inflate(RigInstinct.SIGHT),
                e -> e != carrier && e.isAlive())) {
            if (!allowed(carrier, e, friendly, in, false) || !carrier.hasLineOfSight(e)) {
                continue;
            }
            double d = carrier.distanceToSqr(e);
            if (d < bestD && d <= RigInstinct.SIGHT * RigInstinct.SIGHT) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * May it go for {@code e}? {@code hitBack} widens "what it hunts" to anything that hurt it
     * ({@code HurtByTargetGoal}), but never past who it must never hurt.
     */
    static boolean allowed(Mob carrier, LivingEntity e, boolean friendly, RigInstinct in,
                           boolean hitBack) {
        // an earlier issue's hit proxies are invisible, invulnerable vanilla mobs (cows, horses…) standing in
        // for a creature's far parts. They are not creatures: a hunter must never lock onto one
        // and punch it for ever, nor a coward run from its own head. Anything invulnerable is
        // skipped for the same reason. (Callers resolve a proxy to its carrier first where that
        // is what they mean -- see onHurt.)
        if (HitProxies.isProxy(e) || e.isInvulnerable()) {
            return false;
        }
        // A summoner and its helpers never go for each other, nor its slam land on them.
        if (RigAbilities.family(carrier, e)) {
            return false;
        }
        if (e == carrier || !e.isAlive() || e.level() != carrier.level()
                || com.moddingfromamod.mob.RideSeat.mountOf(e) == carrier || carrier.getVehicle() == e) {
            return false;
        }
        if (carrier.distanceTo(e) > RigInstinct.SIGHT * 1.5) {
            return false;
        }
        if (e instanceof Player p) {
            return !friendly && !p.isCreative() && !p.isSpectator()
                    && carrier.level().getDifficulty() != Difficulty.PEACEFUL
                    && (hitBack || in.huntsPlayers());
        }
        // a real mob with a behaviour profile, or anyone's friendly pet, is a wished creature
        // too: an army of guardian endermen does not hunt itself because endermen are monsters.
        if (e.entityTags().contains(DisplayRig.RIG_TAG)
                || com.moddingfromamod.mob.RealMobBehaviours.isDriven(e)
                || (!com.moddingfromamod.mob.PetBehaviours.stored(e).isEmpty()
                        && Boolean.TRUE.equals(com.moddingfromamod.mob.PetBehaviours.storedOf(e).friendly()))) {
            // Another wished creature: only when it started it. Wished creatures do not hunt
            // each other on sight, the way they did not on the mod path (neither is a Monster
            // nor an Animal there).
            return hitBack;
        }
        if (hitBack) {
            return true;
        }
        return (in.huntsMonsters() && e instanceof Enemy)
                || (in.huntsAnimals() && e instanceof Animal);
    }

    private static void chase(Mob carrier, LivingEntity target, RigInstinct in, State st,
                              long now) {
        carrier.getLookControl().setLookAt(target, 30f, 30f);
        // an ARCHER keeps its distance and strafes round its target; its bow does the hurting
        // (RigAbilities' ranged, which its archetype brings when it fights). The skeleton's footwork.
        Species mine = DisplayRig.speciesOn(carrier);
        if (mine != null && mine.archetype() == com.moddingfromamod.species.Archetype.ARCHER) {
            if (now % STEP == 0) {
                double[] to = com.moddingfromamod.species.ArchetypeLife.keepDistance(carrier.getX(), carrier.getZ(),
                        target.getX(), target.getZ(), (now / 60) % 2 == 0);
                walk(carrier, new Vec3(to[0], carrier.getY(), to[1]), null, RigInstinct.CHASE_SPEED);
            }
            return;
        }
        double dx = target.getX() - carrier.getX();
        double dz = target.getZ() - carrier.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        boolean reach = carrier.getBoundingBox().inflate(RigInstinct.REACH, 0, RigInstinct.REACH)
                .intersects(target.getBoundingBox());
        if (!target.getUUID().equals(st.chasing)) {
            st.chasing = target.getUUID();
            st.best = Double.MAX_VALUE;
        }
        if (reach || flat < st.best - RigInstinct.GIVE_UP_PROGRESS) {
            st.best = Math.min(st.best, flat);
            st.bestAt = now;
        } else if (RigInstinct.givesUp(st.best, flat, now - st.bestAt)) {
            // It cannot get to it: let it go, and go back to its own life.
            st.gaveUp = target.getUUID();
            st.gaveUpUntil = now + RigInstinct.GIVE_UP_FOR;
            st.target = null;
            st.chasing = null;
            carrier.getNavigation().stop();
            return;
        }
        if (reach) {
            if (now - st.lastHit >= RigInstinct.HIT_EVERY) {
                st.lastHit = now;
                carrier.swing(InteractionHand.MAIN_HAND);
                // A rig's species damage, or a real mob's own attack damage.
                Species sp = com.moddingfromamod.mob.RealMobBehaviours.speciesFor(carrier);
                float dmg = RigInstinct.hit(sp == null ? 3.0 : sp.damage());
                if (dmg > 0) {
                    Damage.hurt(target, carrier.damageSources().mobAttack(carrier), dmg);
                }
            }
            return;
        }
        if (in.leaps() && carrier.onGround() && RigInstinct.canLeap(flat)
                && now - st.lastLeap >= RigInstinct.HIT_EVERY) {
            st.lastLeap = now;
            Vec3 m = carrier.getDeltaMovement();
            double[] v = RigInstinct.leap(dx, dz, m.x, m.z);
            carrier.setDeltaMovement(v[0], v[1], v[2]);
            carrier.needsSync = true;
            return;
        }
        if (now % STEP == 0) {
            walk(carrier, target.position(), target, RigInstinct.CHASE_SPEED);
        }
    }

    // ── running away ───────────────────────────────────────────────────────────────────────────

    /**
     * Straight away from the threat; when there is no path that way (a wall, a cliff), vanilla's
     * own "somewhere away from it" ({@code AvoidEntityGoal}'s {@code DefaultRandomPos}).
     */
    private static void flee(Mob carrier, Entity threat) {
        double[] xz = RigInstinct.awayFrom(carrier.getX(), carrier.getZ(), threat.getX(),
                threat.getZ());
        Vec3 to = new Vec3(xz[0], carrier.getY(), xz[1]);
        if (carrier.getNavigation().createPath(to.x, to.y, to.z, 0) == null
                && carrier instanceof PathfinderMob pm) {
            Vec3 alt = DefaultRandomPos.getPosAway(pm, 16, 7, threat.position());
            if (alt != null) {
                to = alt;
            }
        }
        walk(carrier, to, null, RigInstinct.FLEE_SPEED);
    }

    private static void walk(Mob carrier, Vec3 to, Entity towards, double speed) {
        if (RigPowers.flies(carrier)) {
            // A bat ignores navigation; a flier is flown there.
            Vec3 aim = towards != null ? towards.position() : to;
            Species mine = DisplayRig.speciesOn(carrier);
            if (towards != null && mine != null && mine.archetype() != null
                    && mine.archetype().life().fight() == com.moddingfromamod.species.Archetype.Fight.SWOOP) {
                // a swooper comes at it from above while far, and dives once close.
                aim = aim.add(0, com.moddingfromamod.species.ArchetypeLife.swoopHeight(
                        Math.sqrt(carrier.distanceToSqr(towards.getX(), carrier.getY(), towards.getZ()))), 0);
            }
            RigPowers.flyTo(carrier, aim, speed);
            return;
        }
        unsit(carrier);
        var brain = carrier.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, towards != null
                    ? new WalkTarget(towards, (float) speed, 0)
                    : new WalkTarget(to, (float) speed, 0));
        }
        if (towards != null) {
            carrier.getNavigation().moveTo(towards, speed);
        } else {
            carrier.getNavigation().moveTo(to.x, to.y, to.z, speed);
        }
    }

    /**
     * A camel sits down at random, and while it sits OR is getting up ({@code refuseToMove}) it
     * will not take a step. A coward carried by one stopped running: measured 3.0 -> 4.6 blocks,
     * then sat, and {@code standUp()} did not help because standing is a two-second pose
     * transition that also refuses to move -- and its brain sat it down again. So instantly.
     */
    public static void unsit(Mob carrier) {
        if (carrier instanceof Camel camel && camel.refuseToMove()) {
            camel.standUpInstantly();
        }
    }

    // ── being hurt ─────────────────────────────────────────────────────────────────────────────

    /** Remember who hurt a driven carrier: a fighter hits back, a coward runs from them. */
    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        LivingEntity self = event.getEntity();
        if (!(self instanceof Mob mob)
                || !(VanillaRigAnimator.isDriving(mob) || com.moddingfromamod.mob.RealMobBehaviours.isDriven(mob))
                || !(event.getSource().getEntity() instanceof LivingEntity attacker)
                || attacker == mob) {
            return;
        }
        State st = STATE.computeIfAbsent(mob.getUUID(), k -> new State());
        st.hurtBy = attacker.getUUID();
        st.hurtAt = mob.level().getGameTime();
    }

    private static LivingEntity resolve(ServerLevel level, UUID id) {
        if (id == null) {
            return null;
        }
        Entity e = level.getEntity(id);
        return e instanceof LivingEntity l && l.isAlive() ? l : null;
    }
}
