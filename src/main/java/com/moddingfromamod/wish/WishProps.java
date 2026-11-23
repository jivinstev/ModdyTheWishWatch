package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.WishRun;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The bracket around a wish: what its commands spawned, and what is still standing at the
 * end —.</b>
 *
 * <p>{@link PropSweep} is the decision and is Minecraft-free; this is the two lines of the game it
 * needs. Stamping happens where the wish already diffs the world — {@code RunCommandTool} snapshots
 * around the child before every command so anything new gets a handle — so this adds a second,
 * wider diff beside it rather than a new mechanism.
 *
 * <h2>Why the bracket is on {@code run_command} and deliberately nowhere else</h2>
 *
 * <p> names that path, and the measurement agrees: every one of the seventeen real summons in
 * the action log came through it. The two paths NOT bracketed are not oversights:
 *
 * <ul>
 *   <li><b>The tools.</b> {@code sculpt_creature} and its rig displays are what the wish is FOR.
 *       They are not spawned inside a command, so they are never stamped, and the sweep cannot
 *       reach them however wrong its keep rules were. That is a structural guarantee rather than a
 *       rule somebody has to maintain, which is why the bracket sits here and not on
 *       {@code EntityJoinLevelEvent} — a join hook would catch the creature too and leave the
 *       creature's survival depending on a keep list being right.</li>
 *   <li><b>A datapack.</b> Its summons happen on a later tick, outside the call, so this cannot see
 *       them — and it should not: a spell that spawns things IS the wish, and sweeping its output
 *       would delete what the child asked for.</li>
 * </ul>
 *
 * <p><b>The radius is the same {@code 48} the handle diff already uses</b>, and the band was
 * checked against the corpus rather than inherited. Of the seventeen real summons, sixteen are
 * within a few blocks of the child — {@code ^0 ^1 ^4}, {@code ~ ~ ~}, or absolute coordinates beside
 * him. <b>Exactly one is outside it:</b> {@code summon minecraft:armor_stand ~ ~200 ~}, a probe two
 * hundred blocks overhead.
 *
 * <p>That one is deliberately not chased, and the reason is what is actually about: a prop is a
 * defect because <i>it stands in the child's line of sight after the wish is over</i>. Two hundred
 * blocks up it is invisible from the ground, so widening the band to reach it would buy nothing a
 * child can see and would cost every sweep a larger query. If somebody later wants the entity count
 * tidy as well as the view, that is a measurement to take, not a constant to raise.
 */
public final class WishProps {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-wish-props");

    /** The same band {@code EntityCapture} diffs for handles — see the class note. */
    private static final double RADIUS = 48;

    private WishProps() {
    }

    /** Every entity in the band right now. Cheap: one AABB query, no NBT, no ticking. */
    public static Set<UUID> snapshot(ServerLevel level, Vec3 origin) {
        Set<UUID> ids = new HashSet<>();
        if (level == null || origin == null) {
            return ids;
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(origin, origin).inflate(RADIUS))) {
            ids.add(e.getUUID());
        }
        return ids;
    }

    /**
     * Record everything that appeared since {@code before} on this wish's prop ledger.
     *
     * <p>A player is skipped here as well as in {@link PropSweep#verdict}, belt and braces: a body
     * that walked into the band mid-command is not something a command spawned, and the cheapest
     * place to be sure of that is before it is ever written down.
     */
    public static int recordNew(ServerLevel level, Vec3 origin, Set<UUID> before, WishRun run) {
        return recordNew(level, origin, before, run, false).size();
    }

    /**
     * As {@link #recordNew(ServerLevel, Vec3, Set, WishRun)}, stamping each new entity with whether
     * the command that spawned it carried {@code keep: true}, and handing back what was
     * recorded so {@code run_command} can warn about it.
     */
    public static List<UUID> recordNew(ServerLevel level, Vec3 origin, Set<UUID> before, WishRun run,
                                       boolean keep) {
        List<UUID> n = new java.util.ArrayList<>();
        if (level == null || origin == null || before == null || run == null) {
            return n;
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(origin, origin).inflate(RADIUS))) {
            if (before.contains(e.getUUID()) || e instanceof Player) {
                continue;
            }
            // A CREATURE'S BODY IS NOT A PROP. Since CreatureSpawn a wished creature is
            // built INSIDE the command that spawns it, so its displays and hit proxies are new
            // entities in this band — unnamed, handle-less, and exactly what the sweep removes.
            // (Before, the bridge deferred the build past the command on production's path —
            // a reentrant server task is queued, not run — which is why the count read 0 and why
            // nothing was swept. Where the bridge ran inline, a GameTest's tick, the sweep did
            // strip the creature: SpawnTellsTheTruthGameTest.) The carrier stands for them all.
            if (com.moddingfromamod.mob.Creatures.isRigInternal(e)) {
                continue;
            }
            String handle = com.moddingfromamod.mob.EntityHandles.global().handleFor(e.getUUID());
            run.commandSpawned(e.getUUID(), handle,
                    EntityType.getKey(e.getType()).toString(), keep);
            n.add(e.getUUID());
        }
        return n;
    }

    /** What one sweep did, so the caller can report it rather than a boolean. */
    public record Swept(int removed, int alreadyGone, int kept, List<String> removedTypes) {
    }

    /**
     * <b>Take down what this wish built to look at and did not keep.</b>
     *
     * <p>Every removal is logged with its type and handle. That is not decoration: a sweep that
     * deletes the wrong thing silently is exactly the failure warns about, and the log line is
     * the only thing that would let somebody find it afterwards.
     */
    public static Swept sweep(MinecraftServer server, WishRun run) {
        if (server == null || run == null) {
            return new Swept(0, 0, 0, List.of());
        }
        List<String> kept = run.made().stream().map(WishRun.Made::id).toList();
        int removed = 0;
        int gone = 0;
        int keptCount = 0;
        List<String> types = new java.util.ArrayList<>();
        for (WishRun.Prop p : run.commandSpawned()) {
            Entity e = findAnywhere(server, p.id());
            if (e == null || e.isRemoved()) {
                gone++;                     // the agent tidied up after itself, or it despawned
                continue;
            }
            PropSweep.Spawned s = spawned(p, e);
            PropSweep.Kept verdict = PropSweep.verdict(s, kept);
            if (verdict != PropSweep.Kept.NOT_KEPT) {
                keptCount++;
                LOG.info("wish {} left {} ({}) standing: {}", run.shortId(), p.typeId(),
                        p.handle().isBlank() ? "no handle" : p.handle(), verdict);
                continue;
            }
            LOG.info("wish {} removed the test prop {} ({})", run.shortId(), p.typeId(),
                    p.handle().isBlank() ? "no handle" : p.handle());
            e.discard();
            types.add(p.typeId());
            removed++;
        }
        return new Swept(removed, gone, keptCount, types);
    }

    /**
     * <b>What {@code run_command} says about the things THIS call spawned that the sweep will take
     * </b> — "" when there are none. Asked after the whole batch, of what is still standing,
     * with the same {@link #spawned} the sweep itself uses, so the warning and the sweep cannot
     * disagree about a keep rule. Only types the commands SUMMON by name are counted
     * ({@link PropSweep#summonedTypes}): a kill's dropped loot is tidied but never named.
     */
    public static String warning(MinecraftServer server, WishRun run, List<UUID> spawnedNow,
                                 List<String> commands) {
        if (server == null || run == null || spawnedNow == null || spawnedNow.isEmpty()) {
            return "";
        }
        Set<String> summoned = PropSweep.summonedTypes(commands);
        Set<UUID> now = new HashSet<>(spawnedNow);
        List<PropSweep.Spawned> standing = new java.util.ArrayList<>();
        for (WishRun.Prop p : run.commandSpawned()) {
            if (!now.contains(p.id()) || !summoned.contains(p.typeId())) {
                continue;
            }
            Entity e = findAnywhere(server, p.id());
            if (e != null && !e.isRemoved()) {
                standing.add(spawned(p, e));
            }
        }
        return PropSweep.warning(standing, run.made().stream().map(WishRun.Made::id).toList());
    }

    /** One recorded prop, as {@link PropSweep} reasons about it, read off the live entity. */
    private static PropSweep.Spawned spawned(WishRun.Prop p, Entity e) {
        return new PropSweep.Spawned(p.id(), p.handle(), p.typeId(),
                e instanceof Player, e.isPassenger() || !e.getPassengers().isEmpty(),
                e.hasCustomName(),
                // a mob with its AI on is never a prop — alive, or DYING. A dying
                // mob takes itself away in 20 ticks; discarding it instead reported a child's
                // creepers that something else killed as "test props your commands built".
                e instanceof Mob m && !m.isRemoved() && !m.isNoAi(),
                // the command that spawned it said keep …
                p.kept(),
                // … and it is a thing the mark can keep: not frozen, not a drop, not in flight.
                markable(e));
    }

    /**
     * Whether {@code keep: true} can keep this entity ({@link PropSweep.Kept#MARKED}): false
     * for a mob frozen with {@code NoAI} and for a dropped item, an experience orb or a projectile.
     * Read off the entity's own class and state, never its type id, so a modded projectile or a
     * modded mob is judged by what it is.
     */
    static boolean markable(Entity e) {
        if (e instanceof net.minecraft.world.entity.item.ItemEntity
                || e instanceof net.minecraft.world.entity.ExperienceOrb
                || e instanceof net.minecraft.world.entity.projectile.Projectile) {
            return false;
        }
        return !(e instanceof Mob m && m.isNoAi());
    }

    private static Entity findAnywhere(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }
}
