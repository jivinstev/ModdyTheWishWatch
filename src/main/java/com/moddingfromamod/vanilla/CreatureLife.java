package com.moddingfromamod.vanilla;

import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.species.Archetype;
import com.moddingfromamod.species.ArchetypeLife;
import com.moddingfromamod.species.Species;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A sculpted creature lives its {@link Archetype}, not its carrier's life</b>.
 *
 * <p>Ticked beside {@link RigInstincts} for every rig whose species names an archetype. It carries out
 * {@link ArchetypeLife}'s decisions — stand and look about, wander near home and herd, flit, circle,
 * perch, swim — through the carrier's navigation, or {@link RigPowers#flyTo} for a flyer. It stands
 * aside whenever something else steers the creature: a rider, waiting for one, the leash, staying on a
 * spot, following an owner in reach ({@code PetBehaviours.steeredElsewhere}), or a fight
 * ({@link RigInstincts#busy}, whose own moves the archetype shapes — see RigInstincts).
 *
 * <p>The carrier's OWN wandering is switched off the first time: its goals are removed (all but
 * {@code FloatGoal}, so it does not drown), and a bat carrier's random flight is skipped by
 * {@code BatRideMixin} ({@link #drives}). A creature with no archetype is untouched: it lives as its
 * carrier makes it, exactly as before.
 */
public final class CreatureLife {

    private record State(ArchetypeLife.Intent intent, long until, long issued, boolean pinned) {
        State(ArchetypeLife.Intent intent, long until, long issued) {
            this(intent, until, issued, false);
        }
    }

    /**
     * Have it carry out THIS intent for up to {@code ticks}, instead of choosing its own — for a test
     * that proves a gait carries a decision out, and for a film that wants one moment on camera. Its
     * own choices resume when the time is up.
     */
    public static void pin(Mob mob, ArchetypeLife.Intent intent, int ticks) {
        STATE.put(mob.getUUID(), new State(intent, mob.level().getGameTime() + ticks, -1000, true));
    }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();
    private static final Set<UUID> STRIPPED = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();

    private CreatureLife() {
    }

    /** Does an archetype drive this mob (its carrier's own wandering is then off)? */
    public static boolean drives(Mob mob) {
        Species sp = DisplayRig.speciesOn(mob);
        return sp != null && sp.archetype() != null;
    }

    /** What it is doing now, for a test or a film; null when nothing drives it. */
    public static ArchetypeLife.Act doing(Mob mob) {
        State st = STATE.get(mob.getUUID());
        return st == null ? null : st.intent().act();
    }

    /** Its current intent, for a test or a film; null when nothing drives it. */
    public static ArchetypeLife.Intent intentOf(Mob mob) {
        State st = STATE.get(mob.getUUID());
        return st == null ? null : st.intent();
    }

    public static void forget(UUID id) {
        STATE.remove(id);
        STRIPPED.remove(id);
        MOVES.remove(id);
        WALL.remove(id);
    }

    private record Moves(Species from, Species with) {
    }

    private static final Map<UUID, Moves> MOVES = new ConcurrentHashMap<>();

    /**
     * {@code sp} with the moves its archetype brings ({@link Archetype#impliedAbilities}) — ONLY when
     * its temperament fights, so a peaceful dragon never breathes fire on anything. Cached per carrier
     * against the species it was built from, so nothing is made on a tick where nothing changed.
     */
    public static Species withImpliedMoves(Mob carrier, Species sp) {
        Archetype a = sp == null ? null : sp.archetype();
        if (a == null || a.impliedAbilities().isEmpty() || sp.abilities().containsAll(a.impliedAbilities())
                || !RigInstinct.of(sp).fights()) {
            return sp;
        }
        Moves m = MOVES.get(carrier.getUUID());
        if (m != null && m.from() == sp) {
            return m.with();
        }
        Species with = sp.withBehaviour(null, java.util.Set.of(), a.impliedAbilities());
        MOVES.put(carrier.getUUID(), new Moves(sp, with));
        return with;
    }

    /** One tick of one carrier. */
    public static void tick(Mob carrier, Species sp) {
        Archetype a = sp == null ? null : sp.archetype();
        if (a == null || !(carrier.level() instanceof ServerLevel level) || !carrier.isAlive()
                || carrier.isNoAi() || carrier.deathTime > 0) {
            return;
        }
        if (STRIPPED.add(carrier.getUUID())) {
            // Its carrier's own life off, once: what it does is its archetype's now.
            carrier.goalSelector.removeAllGoals(g -> !(g instanceof FloatGoal));
            carrier.targetSelector.removeAllGoals(g -> true);
        }
        climb(carrier, a);
        // A held facing (/wishmob face) means he stands where he is: his life issues no walk
        // while it lasts. It runs after the hold's own navigation stop, so a walk issued here moved
        // him a step on the next tick, every re-plan (an earlier issue's test caught 2.8 degrees of it).
        if (PetBehaviours.steeredElsewhere(level, carrier) || RigInstincts.busy(carrier)
                || RigFacing.holding(carrier.getUUID())) {
            STATE.remove(carrier.getUUID());
            return;
        }
        long now = level.getGameTime();
        State st = STATE.get(carrier.getUUID());
        // A walk whose path has ended short of the spot (a fence, water, a cliff, a wall) is over: it
        // moves on to its next decision instead of standing idle for the rest of the walk's time while
        // it still "walks" (measured, 2026-11-02). A climber pushes on at its wall instead.
        boolean pathEnded = st != null && !RigPowers.flies(carrier)
                && a.life().gait() != Archetype.Gait.CLIMB
                && (st.intent().act() == ArchetypeLife.Act.WALK || st.intent().act() == ArchetypeLife.Act.TO_RIDER)
                && now - st.issued() >= 10 && st.issued() >= 0 && carrier.getNavigation().isDone();
        boolean pinned = st != null && st.pinned() && now < st.until();
        if (!pinned && (st == null || now >= st.until() || pathEnded || arrived(carrier, st.intent()))) {
            ArchetypeLife.Intent next = ArchetypeLife.next(a, world(level, carrier, sp), RANDOM);
            st = new State(next, now + Math.max(20, next.ticks()), -1000);
            STATE.put(carrier.getUUID(), st);
        }
        // A climber whose path stopped at a wall keeps pressing on at it, so it goes up (climb()).
        if (a.life().gait() == Archetype.Gait.CLIMB && st.intent().act() == ArchetypeLife.Act.WALK
                && carrier.getNavigation().isDone()) {
            carrier.getMoveControl().setWantedPosition(st.intent().x(), st.intent().y(), st.intent().z(),
                    Math.max(0.8, st.intent().speed()));
        }
        // Re-issue the move now and then, so a path that ended early or a flight that timed out goes on.
        if (now - st.issued() >= 20) {
            carry(level, carrier, st.intent());
            STATE.put(carrier.getUUID(), new State(st.intent(), st.until(), now, st.pinned()));
        }
    }

    private static boolean arrived(Mob m, ArchetypeLife.Intent i) {
        if (i.act() == ArchetypeLife.Act.STAND || i.act() == ArchetypeLife.Act.PERCH) {
            return false;   // they last their time out
        }
        double dx = m.getX() - i.x(), dz = m.getZ() - i.z();
        double near = i.act() == ArchetypeLife.Act.TO_RIDER ? 3.5 : 1.5;
        return dx * dx + dz * dz < near * near
                && (i.act() != ArchetypeLife.Act.FLY || Math.abs(m.getY() - i.y()) < 2);
    }

    private static void carry(ServerLevel level, Mob m, ArchetypeLife.Intent i) {
        Vec3 to = new Vec3(i.x(), i.y(), i.z());
        boolean flies = RigPowers.flies(m);
        switch (i.act()) {
            case STAND -> {
                m.getNavigation().stop();
                if (flies) {
                    RigPowers.flyTo(m, m.position(), 0.2);   // hold where it is
                }
                m.getLookControl().setLookAt(i.x(), i.y(), i.z(), 30f, 30f);
            }
            case PARK -> {
                // Parked: no path, no look; a flyer comes down to the ground and stops there.
                m.getNavigation().stop();
                if (flies) {
                    // Aimed ARRIVED below the ground, because a flight stops pushing within ARRIVED of
                    // its target: aimed AT the ground it hovered 1.5 up, and only the carrier's own
                    // wandering ever finished the landing (a bat on 1.21.8 does not).
                    RigPowers.flyTo(m, new Vec3(m.getX(), i.y() + 0.1 - RigFlight.ARRIVED, m.getZ()), 0.3);
                }
            }
            case FLY, PERCH -> {
                if (flies) {
                    RigPowers.flyTo(m, to, i.speed());
                } else {
                    m.getNavigation().moveTo(i.x(), i.y(), i.z(), i.speed());
                }
            }
            case WALK, TO_RIDER, SWIM -> {
                if (flies) {
                    // A flying creature told to walk (a pegasus mount between flights) skims low instead.
                    RigPowers.flyTo(m, to.add(0, 1, 0), i.speed());
                } else if (!m.getNavigation().moveTo(i.x(), i.y(), i.z(), i.speed())) {
                    RigInstincts.unsit(m);
                    Species sp = DisplayRig.speciesOn(m);
                    if (sp != null && sp.archetype() != null && sp.archetype().life().gait() == Archetype.Gait.CLIMB) {
                        // No path — a wall in the way. A climber goes straight at it and up (climb()).
                        m.getMoveControl().setWantedPosition(i.x(), i.y(), i.z(), i.speed());
                    }
                }
            }
        }
    }

    /** When each climber last touched its wall: a short grip, so a flicker of contact does not drop it. */
    private static final Map<UUID, Long> WALL = new ConcurrentHashMap<>();
    /** Ticks a climber keeps going up after its last touch of the wall (a big body slides along one). */
    static final int GRIP_TICKS = 6;

    /** A climber walking into a wall goes up it. */
    private static void climb(Mob m, Archetype a) {
        if (a.life().gait() != Archetype.Gait.CLIMB) {
            return;
        }
        long now = m.level().getGameTime();
        if (m.horizontalCollision) {
            WALL.put(m.getUUID(), now);
        }
        // Or a solid block straight ahead of where it is GOING: a big body pushing at an angle slides
        // along a wall and its collision flag flickers (measured: a 3-high pit climbed 3 times in 5).
        // Where it is going is its own intent, never the move control's wanted spot: that is cleared
        // every tick the control runs, so reading it made the climb depend on tick order (a pig
        // carrier rose 1.3 of 2 blocks in two runs of six, with "wanted false", 2026-11-02).
        State st = STATE.get(m.getUUID());
        boolean going = false;
        if (st != null && (st.intent().act() == ArchetypeLife.Act.WALK || st.intent().act() == ArchetypeLife.Act.TO_RIDER)) {
            double dx = st.intent().x() - m.getX(), dz = st.intent().z() - m.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.75) {
                going = true;
                double reach = m.getBbWidth() / 2 + 0.6;
                for (int h = 0; h <= 1; h++) {
                    var front = net.minecraft.core.BlockPos.containing(m.getX() + dx / len * reach,
                            m.getY() + 0.5 + h, m.getZ() + dz / len * reach);
                    if (!m.level().getBlockState(front).getCollisionShape(m.level(), front).isEmpty()) {
                        WALL.put(m.getUUID(), now);
                    }
                }
            }
        }
        Long last = WALL.get(m.getUUID());
        boolean against = last != null && now - last <= GRIP_TICKS;
        // "Moving" is wanting to get somewhere: a climber stopped at a wall still is.
        double up = ArchetypeLife.climb(against, going || !m.getNavigation().isDone()
                || m.getDeltaMovement().horizontalDistanceSqr() > 1e-4);
        if (!Double.isNaN(up)) {
            Vec3 v = m.getDeltaMovement();
            m.setDeltaMovement(v.x, Math.max(v.y, up), v.z);
            m.fallDistance = 0;
        }
    }

    /**
     * The first standing place at or below {@code fromY} at (x, z): the top of the first block with a
     * collision shape, or the surface of water. Searched down 48 blocks; the start height if nothing.
     */
    static double groundBelow(ServerLevel level, double x, double fromY, double z) {
        var p = new net.minecraft.core.BlockPos.MutableBlockPos((int) Math.floor(x), (int) Math.floor(fromY),
                (int) Math.floor(z));
        int stop = Math.max(level.getMinY(), p.getY() - 48);
        for (; p.getY() > stop; p.move(0, -1, 0)) {
            var st = level.getBlockState(p);
            if (!st.getFluidState().isEmpty() || !st.getCollisionShape(level, p).isEmpty()) {
                return p.getY() + 1;
            }
        }
        return fromY;
    }

    /**
     * The ground at (x, z) for a creature whose feet are at {@code feet}: scanned down from two blocks
     * above them, so a step up is found — but a "ground" more than a block above its feet is a CEILING
     * or an overhang (a roof, a test plot's barrier lid), and the scan goes again from its feet. A bee
     * under a roof was sent to perch on top of it (the release gate, 2026-11-02: an intent at y -49 on
     * the plot's lid, the bee 6.8 up).
     */
    static double groundUnder(ServerLevel level, double x, double from, double feet, double z) {
        double g = groundBelow(level, x, from, z);
        return g > feet + 1.01 ? groundBelow(level, x, feet + 0.5, z) : g;
    }

    /** What it knows, for {@link ArchetypeLife#next}. */
    private static ArchetypeLife.World world(ServerLevel level, Mob m, Species sp) {
        var home = PetBehaviours.leashHome(m);
        // THE GROUND UNDER IT, never the top of the world: the heightmap is the HIGHEST block, which
        // under a roof (a cave, his house, a tree, a test plot's ceiling) is the roof — and a walker
        // sent there has no path and stands still, a flyer flies into it (measured, 2026-11-02).
        double ref = m.getY() + 2;
        double feet = m.getY();
        java.util.function.DoubleBinaryOperator groundAt = (x, z) -> groundUnder(level, x, ref, feet, z);
        // The highest block near home: where a glider perches.
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        Archetype.Life life = sp.archetype().life();
        if (life.perchEvery() > 0) {
            for (int k = 0; k < 12; k++) {
                double ang = k * Math.PI / 6, r = 4 + (k % 3) * 4;
                double x = home.getX() + Math.cos(ang) * r, z = home.getZ() + Math.sin(ang) * r;
                // A perch is the top of whatever stands near home (a tower, a tree, his roof).
                double y = groundBelow(level, x, home.getY() + 16, z);
                if (Double.isNaN(py) || y > py) {
                    px = x;
                    py = y;
                    pz = z;
                }
            }
        }
        // Its own kind nearby: the middle of its herd.
        double hx = Double.NaN, hz = Double.NaN;
        if (life.herdRadius() > 0) {
            String tag = com.moddingfromamod.entity.WishMobTags.forSpecies(sp.id());
            var mates = level.getEntitiesOfClass(Mob.class, m.getBoundingBox().inflate(life.herdRadius() * 2),
                    e -> e != m && e.isAlive() && e.entityTags().contains(tag) && e.entityTags().contains(DisplayRig.RIG_TAG));
            if (!mates.isEmpty()) {
                hx = 0;
                hz = 0;
                for (Mob o : mates) {
                    hx += o.getX();
                    hz += o.getZ();
                }
                hx /= mates.size();
                hz /= mates.size();
            }
        }
        Entity who = PetBehaviours.ownerOrNearest(level, m);
        double rx = Double.NaN, ry = Double.NaN, rz = Double.NaN, rd = Double.NaN;
        if (who != null && who.level() == level) {
            rx = who.getX();
            ry = who.getY();
            rz = who.getZ();
            rd = m.distanceTo(who);
        }
        return new ArchetypeLife.World(m.getX(), m.getY(), m.getZ(), home.getX() + 0.5, home.getY(),
                home.getZ() + 0.5, groundAt.applyAsDouble(m.getX(), m.getZ()), groundAt, px, py, pz, hx, hz,
                m.isInWater(), rx, ry, rz, rd, RigPowers.flies(m), level.getGameTime());
    }
}
