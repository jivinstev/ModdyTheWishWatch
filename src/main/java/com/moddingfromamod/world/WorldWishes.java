package com.moddingfromamod.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.reveal.PillarPalette;
import com.moddingfromamod.wish.reveal.WishOrbs;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderMobSizes;
import com.moddingfromamod.wishbuilder.BuilderRealMobs;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.Catalogue;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.wishbuilder.MakeFit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A world module wishes a creature</b> (#178). Documented in {@code docs/EXTENDING.md} ("A world wishing a
 * creature").
 *
 * <p>Every wish used to belong to a player. A world (the arena, wishing its own wave monsters) now wishes through
 * the Builder, exactly as a child's Builder pick is made:
 * <ul>
 *   <li>{@link #creature}: a <b>real mob</b> as a Builder spec ({@code mob:minecraft:husk} with its size, How strong
 *       and speed: {@link BuilderRealMobs}), fitted to the world's limits by the Builder's own
 *       {@link BuilderAllowlist#capped(BuilderSpec, Map)}, then held at them by the Builder's own live fit
 *       ({@code LiveFit.holdWorld});</li>
 *   <li>{@link #species}: a copy of a <b>Builder design</b> the world made ({@code BuilderMake.forWorld}: a canned
 *       body in a scheme, with powers), fitted live by its owner's limits like any Builder creature.</li>
 * </ul>
 * A crimson pillar ({@link PillarPalette#WORLD}, never a player's colour) rises at the spot for {@link #RISE_TICKS};
 * the creature arrives at the climax, tagged {@link #TAG} and {@code TAG:<module>} ({@link Wished#isWorldWished}), and
 * the world's callback runs. A world-wished body is never {@link Wished#isWished}: a monster is not spared, fitted
 * to the players' limits or given a player's creature's role.
 *
 * <p><b>Limits per wish ({@link Limits}).</b> A world names the ceiling each wish is held to, or {@link Limits#UNCAPPED}
 * for one that must not be (a boss: boss fights are meant to be hard). Only a world can ask for that, because only
 * a world calls this; a player's wish never reaches it. A capped real mob is also never made weaker than the plain mob
 * it stands in for: its limits are floored at the mob's own vanilla numbers.
 *
 * <p><b>All at once, without killing the tick.</b> One queue, stepped on the server tick: at most {@link #FULL_PER_TICK}
 * full and {@link #SLIM_PER_TICK} slim pillars (the beam alone, ~3 displays where a full one is ~53) are stood up per
 * tick, and at most {@link #ARRIVE_PER_TICK} creatures arrive per tick. Server thread only.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WorldWishes {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** On every world-wished body. */
    public static final String TAG = "moddy_world_wished";
    /** {@code TAG:<moduleId>}: which world wished it. */
    public static final String MODULE_PREFIX = TAG + ":";
    /** On a world-wished body that was wished {@link Limits#UNCAPPED}. */
    public static final String UNCAPPED_TAG = TAG + "_uncapped";

    /** How long a pillar rises before its creature arrives: two seconds of warning. */
    public static final int RISE_TICKS = 40;
    public static final int FULL_PER_TICK = 4;
    public static final int SLIM_PER_TICK = 12;
    public static final int ARRIVE_PER_TICK = 6;

    /**
     * What one wish is held to: a {@code ceiling} of make facts ({@code MakeFacts.NUMBERS}), or none at all
     * ({@link #UNCAPPED}, a world's boss).
     */
    public record Limits(Map<String, Double> ceiling, boolean uncapped) {
        public Limits {
            ceiling = ceiling == null ? Map.of() : Map.copyOf(ceiling);
        }

        /** Held to these limits (the arena passes the ones a player may wish at this point of its ladder). */
        public static Limits of(Map<String, Double> ceiling) {
            return new Limits(ceiling, false);
        }

        /** Held to nothing: a world's boss. Only a world module asks; nothing a player wishes comes here. */
        public static final Limits UNCAPPED = new Limits(Map.of(), true);
    }

    private static final class Pending {
        final UUID id = UUID.randomUUID();
        final ServerLevel level;
        final String module;
        final BuilderSpec spec;      // a real mob's, or null
        final String speciesId;      // a design's, or null
        final double x, y, z;
        final Limits limits;
        final boolean slim;
        final Consumer<LivingEntity> onArrive;
        long rising = -1;

        Pending(ServerLevel level, String module, BuilderSpec spec, String speciesId, double x, double y, double z,
                Limits limits, boolean slim, Consumer<LivingEntity> onArrive) {
            this.level = level;
            this.module = module == null ? "" : module;
            this.spec = spec;
            this.speciesId = speciesId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.limits = limits == null ? Limits.of(Map.of()) : limits;
            this.slim = slim;
            this.onArrive = onArrive == null ? e -> { } : onArrive;
        }
    }

    private static final Deque<Pending> WAITING = new ArrayDeque<>();
    private static final List<Pending> RISING = new ArrayList<>();

    /** A design's copy spawned and not yet standing as its final body (the rig bridge swaps it on its next turn). */
    private record Settling(Pending p, UUID spawned, long since) {
    }

    private static final List<Settling> SETTLING = new ArrayList<>();
    /** How long a copy may take to stand before it is given up (logged): a rig is built on the server's next turn. */
    static final int SETTLE_TICKS = 100;

    private WorldWishes() {
    }

    /**
     * Wish a <b>real mob</b>: {@code spec} is a Builder creature spec whose {@code from} is {@code mob:<id>}, at the foot
     * {@code (x, y, z)} of {@code level}, for the world module {@code moduleId}. @return the wish's id (its pillar's).
     * @throws IllegalArgumentException for a spec that is not a Builder real mob
     */
    public static UUID creature(ServerLevel level, String moduleId, BuilderSpec spec, double x, double y, double z,
                                Limits limits, boolean slim, Consumer<LivingEntity> onArrive) {
        if (!BuilderRealMobs.isRealMob(spec)) {
            throw new IllegalArgumentException("WorldWishes.creature takes a Builder real mob (mob:<id>), not "
                    + (spec == null ? "null" : spec.from()) + "; a design goes through species()");
        }
        return queue(new Pending(level, moduleId, spec, null, x, y, z, limits, slim, onArrive));
    }

    /** Wish a copy of the Builder design {@code speciesId} (one the world made with {@code BuilderMake.forWorld}). */
    public static UUID species(ServerLevel level, String moduleId, String speciesId, double x, double y, double z,
                               Limits limits, boolean slim, Consumer<LivingEntity> onArrive) {
        return queue(new Pending(level, moduleId, null, speciesId, x, y, z, limits, slim, onArrive));
    }

    private static UUID queue(Pending p) {
        WAITING.add(p);
        return p.id;
    }

    /** Every wish of {@code moduleId} not yet arrived is called off and its pillar taken down. @return how many. */
    public static int cancelAll(String moduleId) {
        int n = 0;
        for (Iterator<Pending> it = WAITING.iterator(); it.hasNext(); ) {
            if (it.next().module.equals(moduleId)) {
                it.remove();
                n++;
            }
        }
        for (Iterator<Pending> it = RISING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.module.equals(moduleId)) {
                WishOrbs.drop(p.id);
                it.remove();
                n++;
            }
        }
        for (Iterator<Settling> it = SETTLING.iterator(); it.hasNext(); ) {
            Settling s = it.next();
            if (s.p().module.equals(moduleId)) {
                it.remove();
                var e = s.p().level.getEntity(com.moddingfromamod.mob.EntityReplacements.settled(s.spawned()));
                if (e != null) {
                    e.discard();   // called off: it never becomes the world's creature
                }
                n++;
            }
        }
        return n;
    }

    /** How many of {@code moduleId}'s wishes have not arrived yet (waiting or rising). */
    public static int pending(String moduleId) {
        int n = 0;
        for (Pending p : WAITING) {
            n += p.module.equals(moduleId) ? 1 : 0;
        }
        for (Pending p : RISING) {
            n += p.module.equals(moduleId) ? 1 : 0;
        }
        for (Settling s : SETTLING) {
            n += s.p().module.equals(moduleId) ? 1 : 0;
        }
        return n;
    }

    /** How many pillars are rising right now (Gate B). */
    public static int rising() {
        return RISING.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        step(event.getServer().getTickCount());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WAITING.clear();
        RISING.clear();
        SETTLING.clear();
    }

    /** One step of the queue at tick {@code now}. Public for a GameTest that drives it. @return arrivals this step. */
    public static int step(long now) {
        int full = 0;
        int slim = 0;
        for (Iterator<Pending> it = WAITING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.slim ? slim >= SLIM_PER_TICK : full >= FULL_PER_TICK) {
                continue;
            }
            WishOrbs.pinServed(p.level, p.id, p.x, p.y, p.z, 3, 0f, PillarPalette.WORLD, p.slim);
            p.rising = now;
            RISING.add(p);
            it.remove();
            if (p.slim) {
                slim++;
            } else {
                full++;
            }
            if (slim >= SLIM_PER_TICK && full >= FULL_PER_TICK) {
                break;
            }
        }
        settle(now);
        int arrived = 0;
        for (Iterator<Pending> it = RISING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            float t = (now - p.rising) / (float) RISE_TICKS;
            if (t < 1f) {
                WishOrbs.pinServed(p.level, p.id, p.x, p.y, p.z, 3, t);
                continue;
            }
            if (arrived >= ARRIVE_PER_TICK) {
                continue;   // its turn comes next tick; the pillar stays at its full height meanwhile
            }
            it.remove();
            WishOrbs.drop(p.id);
            arrive(p);
            arrived++;
        }
        return arrived;
    }

    private static void arrive(Pending p) {
        // a world's wish is made as a Builder make is: LOCAL ONLY (marshal/LocalOnly), so any model or web call asked
        // while its body is made is refused and recorded; WorldWishesGameTest requires none
        com.moddingfromamod.marshal.LocalOnly.run(() -> arriveLocally(p));
    }

    private static void arriveLocally(Pending p) {
        LivingEntity body;
        try {
            if (p.spec == null) {
                // CreatureSpawn swaps a stock client's rig in at once (VanillaRigBridge.manifestNow), so the body
                // normally stands now and is marked in this same call; settle keeps waiting only for one that is not
                if (design(p) != null) {
                    settle(p.level.getServer().getTickCount());
                }
                return;
            }
            body = realMob(p);
        } catch (RuntimeException e) {
            LOGGER.warn("world wish for {} could not be made: {}", p.module, e.toString());
            return;
        }
        if (body == null) {
            return;
        }
        landed(p, body);
    }

    /** The creature stands: its sparkle, and the world's callback. */
    private static void landed(Pending p, LivingEntity body) {
        p.level.sendParticles(ParticleTypes.FLAME, p.x, p.y + 1, p.z, 24, 0.5, 0.8, 0.5, 0.04);
        p.level.sendParticles(ParticleTypes.END_ROD, p.x, p.y + 1, p.z, 12, 0.4, 0.8, 0.4, 0.05);
        try {
            p.onArrive.accept(body);
        } catch (RuntimeException e) {
            LOGGER.warn("world wish callback for {} threw: {}", p.module, e.toString());
        }
    }

    /** A real mob, as the Builder makes it: fitted by the Builder, its numbers by the Builder, held by the live fit. */
    private static LivingEntity realMob(Pending p) {
        BuilderSpec spec = p.spec;
        if (!p.limits.uncapped() && !p.limits.ceiling().isEmpty()) {
            MakeFit.Fitted fitted = BuilderAllowlist.FOR_WORLDS.capped(spec, p.limits.ceiling());
            spec = fitted.spec();
        }
        BuilderSpec made = spec;
        ResourceLocation id = ResourceLocation.tryParse(Catalogue.bare(made.from()));
        EntityType<?> type = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            LOGGER.warn("world wish for {}: no mob called {}", p.module, made.from());
            return null;
        }
        BuilderRealMobs.Numbers n = BuilderRealMobs.numbers(made);
        double scale = made.opt("mobsize").isEmpty() ? 1.0 : BuilderMobSizes.of(made.opt("mobsize")).scale();
        Double scaleCap = p.limits.uncapped() ? null : p.limits.ceiling().get(MakeFacts.SCALE_TIMES);
        double standAt = scaleCap == null ? scale : Math.max(1.0, Math.min(scale, scaleCap));
        net.minecraft.world.entity.Entity e = type.spawn(p.level, m -> mark(m, p, n, standAt),
                BlockPos.containing(p.x, p.y, p.z), EntitySpawnReason.EVENT, false, false);
        if (!(e instanceof LivingEntity living)) {
            if (e != null) {
                e.discard();
            }
            return null;
        }
        if (!p.limits.uncapped()) {
            com.moddingfromamod.wish.LiveFit.holdWorld(living, floored(p.limits.ceiling(), BuilderRealMobs.base(
                    Catalogue.bare(made.from()))));
        }
        return living;
    }

    /** A copy of the world's Builder design, fitted live by the core at spawn as every Builder creature is. */
    private static LivingEntity design(Pending p) {
        LivingEntity carrier = com.moddingfromamod.vanilla.CreatureSpawn.spawn(p.level, p.speciesId, p.x, p.y, p.z,
                p.level.getRandom().nextFloat() * 360f, EntitySpawnReason.EVENT, false);
        if (carrier == null) {
            LOGGER.warn("world wish for {}: the design {} could not be spawned", p.module, p.speciesId);
            return null;
        }
        // On a stock-client server the wish creature never joins: the vanilla rig bridge cancels it and builds the
        // rig on a carrier on its next turn, recording the swap (EntityReplacements). Its marks and its fit go on the
        // body that really stands, so wait for it (step), never on the one that was swapped out.
        SETTLING.add(new Settling(p, carrier.getUUID(), p.level.getServer().getTickCount()));
        return carrier;
    }

    /** Each copy whose final body now stands gets its marks, its fit and the world's callback (local only). */
    private static void settle(long now) {
        com.moddingfromamod.marshal.LocalOnly.run(() -> settleLocally(now));
    }

    private static void settleLocally(long now) {
        for (Iterator<Settling> it = SETTLING.iterator(); it.hasNext(); ) {
            Settling s = it.next();
            java.util.UUID id = com.moddingfromamod.mob.EntityReplacements.settled(s.spawned());
            net.minecraft.world.entity.Entity e = s.p().level.getEntity(id);
            if (e instanceof LivingEntity body && body.isAlive()) {
                it.remove();
                finish(s.p(), body);
                landed(s.p(), body);
            } else if (now - s.since() > SETTLE_TICKS) {
                it.remove();
                LOGGER.warn("world wish for {}: the design {} never stood (no body after {} ticks)", s.p().module,
                        s.p().speciesId, SETTLE_TICKS);
            }
        }
    }

    /** A design copy's marks and fit, on the body that stands. */
    private static void finish(Pending p, LivingEntity carrier) {
        tags(carrier, p);
        LOGGER.info("world wish for {}: the design {} stands ({}), held to {}", p.module, p.speciesId,
                net.minecraft.world.entity.EntityType.getKey(carrier.getType()),
                p.limits.uncapped() ? "nothing" : p.limits.ceiling());
        // held at the ceiling the world named, by the live fit's own modifiers, as a real mob is: a design's copy is
        // owned by the world, and nobody answers for a world's limits but the world's own call here
        if (!p.limits.uncapped() && !p.limits.ceiling().isEmpty()) {
            com.moddingfromamod.wish.LiveFit.holdWorld(carrier, p.limits.ceiling());
        }
        LOGGER.info("world wish for {}: {} at health {}/{}", p.module, p.speciesId, carrier.getHealth(),
                carrier.getMaxHealth());
    }

    /** Its numbers and marks, before it joins the world, so every join hook already sees what it is. */
    private static void mark(net.minecraft.world.entity.Entity e, Pending p, BuilderRealMobs.Numbers n, double scale) {
        if (e instanceof LivingEntity m) {
            if (n != null) {
                set(m, Attributes.MAX_HEALTH, n.health());
                m.setHealth((float) n.health());
                if (n.damage() > 0) {
                    set(m, Attributes.ATTACK_DAMAGE, n.damage());
                }
                if (n.speed() > 0) {
                    set(m, Attributes.MOVEMENT_SPEED, n.speed());
                }
            }
            set(m, Attributes.SCALE, scale);
        }
        if (e instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        tags(e, p);
        e.snapTo(p.x, p.y, p.z, p.level.getRandom().nextFloat() * 360f, 0f);
    }

    private static void tags(net.minecraft.world.entity.Entity e, Pending p) {
        e.addTag(TAG);
        if (!p.module.isEmpty()) {
            e.addTag(MODULE_PREFIX + p.module);
        }
        if (p.limits.uncapped()) {
            e.addTag(UNCAPPED_TAG);
        }
    }

    /** The ceiling with its health and damage never below the mob's own: a wish never makes it weaker than it was. */
    static Map<String, Double> floored(Map<String, Double> ceiling, BuilderRealMobs.Numbers vanilla) {
        if (vanilla == null) {
            return ceiling;
        }
        Map<String, Double> out = new HashMap<>(ceiling);
        out.computeIfPresent(MakeFacts.HEALTH_HALF_HEARTS, (k, v) -> Math.max(v, vanilla.health()));
        out.computeIfPresent(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, (k, v) -> Math.max(v, vanilla.damage()));
        out.computeIfPresent(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, (k, v) -> Math.max(v, vanilla.speed()));
        return out;
    }

    private static void set(LivingEntity m, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a,
                            double v) {
        var inst = m.getAttribute(a);
        if (inst != null) {
            inst.setBaseValue(v);
        }
    }
}
