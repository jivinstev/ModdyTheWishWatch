package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Invisible proxy mobs that catch the clicks and arrows a rig's one carrier cannot </b>,
 * and forward every one of them to the carrier.
 *
 * <p>How many a creature gets, which mobs and where is {@link HitProxyPlan}'s decision, made when
 * the rig is spawned (cached per shape in memory, and SAVED with the carrier so a creature coming
 * back from disk is not planned again — {@link #remember}, {@link #recall}). This class is the
 * Minecraft half: it spawns them,
 * keeps them over the parts they stand for as the body walks and turns, and makes sure nothing
 * that lands on one lands on it.
 *
 * <h2>A proxy is a hitbox and nothing else</h2>
 * <ul>
 *   <li><b>A click on it is a click on the carrier.</b> {@link AttackEntityEvent} is cancelled and
 *       the player attacks the carrier instead; {@link PlayerInteractEvent.EntityInteract} (a lead,
 *       a name tag, food) is cancelled and made on the carrier.</li>
 *   <li><b>A projectile that reaches it reaches the carrier.</b> {@link ProjectileImpactEvent} is
 *       cancelled and a fresh impact on the carrier is posted and applied, so every other listener
 *       (a wished weapon's on-hit function) sees the carrier, not a cow. A projectile may hit a
 *       proxy exactly when it may hit the carrier ({@code ProxyProjectileMixin}), so a creature's
 *       own fire breath, born inside its own head, does not strike it.</li>
 *   <li><b>Nothing else touches it.</b> Invulnerable (lava, a fall, an explosion, suffocation),
 *       {@code noPhysics} (it neither pushes nor is pushed, trips no pressure plate, and cannot
 *       suffocate in a wall — {@code Entity.push}, {@code isAffectedByBlocks} and
 *       {@code isInWall} each test it), no AI, no gravity, silent, invisible by the effect (the
 *       flag alone is overwritten every tick — {@code DisplayRig.spawn}), never glowing
 *       ({@code CarrierGlowFlagMixin}), and persistent, which also keeps it out of the natural
 *       spawn cap ({@code NaturalSpawner}: a mob that {@code isPersistenceRequired()} is not
 *       counted).</li>
 *   <li><b>It is never loaded from a save.</b> A rig's proxies are spawned fresh every time the
 *       rig is built or adopted, so one read back from disk is a leftover by definition and is
 *       refused at the door ({@link EntityJoinLevelEvent}); nothing has to reconcile it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class HitProxies {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/HitProxies");

    private HitProxies() {
    }

    /** Every proxy wears this. Deliberately NOT {@link DisplayRig#RIG_TAG}: that marks carriers. */
    public static final String PROXY_TAG = com.moddingfromamod.mob.CreatureMatch.PROXY_TAG;

    /** And this, naming its carrier — a proxy's one link to the creature it stands for. */
    public static final String PROXY_OWNER_PREFIX = "mfampxof_";

    // ---- planning, cached per shape ----

    /** Plans already made, by shape: a herd of one wish plans once. Bounded, oldest out. */
    private static final Map<String, HitProxyPlan.Plan> PLANS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HitProxyPlan.Plan> e) {
                    return size() > 256;
                }
            });

    /** How long the last uncached plan took, in ms — for the perf probe and the log. */
    static volatile double lastPlanMs;

    /** Choose the carrier and its proxies for a rig about to be spawned. */
    public static HitProxyPlan.Plan plan(List<RigFit.Atom> atoms,
                                         List<RigBase.Candidate> candidates,
                                         Collection<String> carrierAllowed) {
        return plan(atoms, candidates, carrierAllowed, Double.NaN);
    }

    /**
     * @param seatCap for a creature he rides, the top of its back over the carrier's feet: no
     *                carrier whose own seat is higher is chosen, because nothing can lower a seat
     *                ({@code RideSeat}); NaN for none
     */
    public static HitProxyPlan.Plan plan(List<RigFit.Atom> atoms,
                                         List<RigBase.Candidate> candidates,
                                         Collection<String> carrierAllowed, double seatCap) {
        String key = key(atoms, candidates, carrierAllowed, null) + "|seat" + seatCap;
        HitProxyPlan.Plan cached = PLANS.get(key);
        if (cached != null) {
            return cached;
        }
        long t0 = System.nanoTime();
        // seatCap is set exactly for a creature he rides; any other never gets a hitbox Bedrock offers to ride.
        HitProxyPlan.Plan p = HitProxyPlan.plan(atoms, candidates, carrierAllowed,
                HitProxyPlan.proxyPool(!Double.isNaN(seatCap)), HitProxyPlan.Bounds.VANILLA, seatCap);
        lastPlanMs = (System.nanoTime() - t0) / 1e6;
        log(p);
        if (p != null) {
            PLANS.put(key, p);
        }
        return p;
    }

    /**
     * Proxies for a rig whose carrier already stands (adopted on load). When the carrier is
     * the one the full plan picks — any rig spawned by this build — the full plan IS the answer,
     * and the proxies come back exactly as they were. Re-planning around the carrier alone scores
     * fewer candidates on a differently sized ray grid, and a borderline creature then came back
     * with one proxy where it had three. Only a carrier some other build chose is planned around.
     *
     * @param carrierAllowed the carriers its movement class allows where it stands, or null when
     *                       that is not known (then it is always planned around)
     */
    public static HitProxyPlan.Plan planFor(List<RigFit.Atom> atoms, Mob carrier,
                                            List<RigBase.Candidate> candidates,
                                            Collection<String> carrierAllowed) {
        if (carrierAllowed != null) {
            HitProxyPlan.Plan full = plan(atoms, candidates, carrierAllowed);
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(carrier.getType()).getPath();
            if (full != null && full.core().id().equals(id)
                    && Math.abs(full.core().scale() - carrier.getScale()) < 1e-3) {
                return full;
            }
        }
        return planAround(atoms, carrier, candidates);
    }

    /** Proxies for a rig whose carrier already stands (adopted on load): the carrier is kept. */
    public static HitProxyPlan.Plan planAround(List<RigFit.Atom> atoms, Mob carrier,
                                               List<RigBase.Candidate> candidates) {
        return planAround(atoms, carrier, candidates, HitProxyPlan.proxyPool());
    }

    /** {@link #planAround(List, Mob, List)} from these proxy mobs only. */
    public static HitProxyPlan.Plan planAround(List<RigFit.Atom> atoms, Mob carrier,
                                               List<RigBase.Candidate> candidates, List<String> pool) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(carrier.getType()).getPath();
        RigBase.Choice core = new RigBase.Choice(id, carrier.getScale(), carrier.getBbWidth(),
                carrier.getBbWidth(), carrier.getBbHeight());
        String key = key(atoms, candidates, null, core) + "|pool" + pool;
        HitProxyPlan.Plan cached = PLANS.get(key);
        if (cached != null) {
            return cached;
        }
        long t0 = System.nanoTime();
        HitProxyPlan.Plan p = HitProxyPlan.planAround(atoms, core, candidates,
                pool, HitProxyPlan.Bounds.VANILLA);
        lastPlanMs = (System.nanoTime() - t0) / 1e6;
        log(p);
        if (p != null) {
            PLANS.put(key, p);
        }
        return p;
    }

    private static void log(HitProxyPlan.Plan p) {
        if (p == null) {
            return;
        }
        LOG.info("hit plan: carrier {} x{} + {} prox{} {} -- click {}/{} arrow {}/{} (hit/air), "
                        + "{} vanilla bounds, {} candidates in {} ms",
                p.core().id(), String.format(java.util.Locale.ROOT, "%.2f", p.core().scale()),
                p.proxies().size(), p.proxies().size() == 1 ? "y" : "ies",
                p.proxies().stream().map(HitProxyPlan.Proxy::id).toList(),
                pct(p.click().hit()), pct(p.click().air()), pct(p.arrow().hit()),
                pct(p.arrow().air()), p.met() ? "within" : "OUTSIDE", p.tried(),
                String.format(java.util.Locale.ROOT, "%.1f", lastPlanMs));
    }

    private static String pct(double x) {
        return String.format(java.util.Locale.ROOT, "%.2f", x);
    }

    private static String key(List<RigFit.Atom> atoms, List<RigBase.Candidate> candidates,
                              Collection<String> allowed, RigBase.Choice core) {
        StringBuilder sb = new StringBuilder();
        for (RigFit.Atom a : atoms) {
            sb.append(a.t()).append(a.l()).append(a.s()).append(a.r()).append(';');
        }
        for (RigBase.Candidate c : candidates) {
            sb.append(c.id()).append(c.peacefulSafe() ? '+' : '-');
        }
        sb.append('|').append(allowed).append('|').append(core);
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    // ---- the plan saved with the creature ----

    /** Where a carrier keeps its plan (NeoForge's per-entity data, saved with the entity). */
    static final String SAVED = "mfam_hitplan";

    /** Plans taken from a carrier's save rather than planned, and re-planned on load (for the probe). */
    static final java.util.concurrent.atomic.AtomicInteger RECALLED = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger REPLANNED = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * <b>Everything a plan was made from, as one fingerprint</b>: the planner's
     * {@link HitProxyPlan#VERSION}, the carrier it stands on (type and scale), every drawn box the
     * creature is made of, and every candidate mob's live size. A saved plan is reused only when
     * this matches exactly, so a re-sculpt, a new scale, a Minecraft update that resizes a mob or a
     * planner change each re-plan by themselves — nothing has to remember to clear a cache.
     *
     * <p>Exact, not rounded: every float goes in as its bit pattern, and the digest is SHA-256 —
     * unlike the in-memory {@link #PLANS} key, this one outlives the process, so a collision would
     * hand one creature another's hitboxes for good.
     */
    static String inputKey(List<RigFit.Atom> atoms, List<RigBase.Candidate> candidates,
                           String carrierId, float carrierScale) {
        StringBuilder sb = new StringBuilder();
        sb.append('v').append(HitProxyPlan.VERSION).append('|').append(carrierId).append('@')
                .append(Float.floatToIntBits(carrierScale)).append('|');
        for (RigFit.Atom a : atoms) {
            bits(sb, a.t().x, a.t().y, a.t().z);
            bits(sb, a.l().x, a.l().y, a.l().z, a.l().w);
            bits(sb, a.s().x, a.s().y, a.s().z);
            bits(sb, a.r().x, a.r().y, a.r().z, a.r().w);
            sb.append(';');
        }
        sb.append('|');
        for (RigBase.Candidate c : candidates) {
            sb.append(c.id()).append(':');
            bits(sb, c.width(), c.height());
            sb.append(c.peacefulSafe() ? '+' : '-');
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JVM ships SHA-256
        }
    }

    private static void bits(StringBuilder sb, float... v) {
        for (float f : v) {
            sb.append(Integer.toHexString(Float.floatToIntBits(f))).append(',');
        }
    }

    static String carrierId(Mob carrier) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(carrier.getType()).getPath();
    }

    /**
     * Save a rig's plan on its carrier — every rig that is given proxies ({@code takeProxies}) does,
     * so a spawn, a reshape and an adoption all leave the plan the creature really has. A null plan
     * clears it.
     */
    static void remember(Mob carrier, List<RigFit.Atom> atoms, List<RigBase.Candidate> candidates,
                         HitProxyPlan.Plan plan) {
        net.minecraft.nbt.CompoundTag data = carrier.getPersistentData();
        if (plan == null) {
            data.remove(SAVED);
            return;
        }
        data.put(SAVED, encode(inputKey(atoms, candidates, carrierId(carrier), carrier.getScale()), plan));
    }

    /**
     * The plan saved with this carrier, if it was made from exactly these inputs on this carrier;
     * otherwise null and the caller plans. Never throws: an unreadable save is a re-plan.
     */
    static HitProxyPlan.Plan recall(Mob carrier, List<RigFit.Atom> atoms,
                                    List<RigBase.Candidate> candidates) {
        net.minecraft.nbt.CompoundTag data = carrier.getPersistentData();
        if (!data.contains(SAVED)) {
            return null;
        }
        try {
            net.minecraft.nbt.CompoundTag t = data.getCompoundOrEmpty(SAVED);
            String id = carrierId(carrier);
            if (!t.getStringOr("key", "").equals(inputKey(atoms, candidates, id, carrier.getScale()))) {
                return null;   // made from something else: an older planner, another shape, another carrier
            }
            HitProxyPlan.Plan p = decode(t);
            return p != null && p.core().id().equals(id) ? p : null;
        } catch (RuntimeException e) {
            LOG.warn("saved hit plan on {} is unreadable ({}); planning it again", carrier.getUUID(), e.toString());
            return null;
        }
    }

    static net.minecraft.nbt.CompoundTag encode(String key, HitProxyPlan.Plan p) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putString("key", key);
        RigBase.Choice c = p.core();
        t.putString("core", c.id());
        t.putDouble("scale", c.scale());
        t.putDouble("actualWidth", c.actualWidth());
        t.putDouble("wantWidth", c.wantWidth());
        t.putDouble("height", c.height());
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (HitProxyPlan.Proxy x : p.proxies()) {
            net.minecraft.nbt.CompoundTag q = new net.minecraft.nbt.CompoundTag();
            q.putString("id", x.id());
            q.putDouble("scale", x.scale());
            q.putDouble("w", x.width());
            q.putDouble("h", x.height());
            q.putDouble("x", x.x());
            q.putDouble("y", x.y());
            q.putDouble("z", x.z());
            list.add(q);
        }
        t.put("proxies", list);
        t.putDouble("clickHit", p.click().hit());
        t.putDouble("clickAir", p.click().air());
        t.putDouble("arrowHit", p.arrow().hit());
        t.putDouble("arrowAir", p.arrow().air());
        t.putBoolean("met", p.met());
        t.putInt("tried", p.tried());
        return t;
    }

    static HitProxyPlan.Plan decode(net.minecraft.nbt.CompoundTag t) {
        String core = t.getStringOr("core", "");
        net.minecraft.nbt.ListTag list = t.getListOrEmpty("proxies");
        if (core.isEmpty() || list.size() > HitProxyPlan.MAX_PROXIES) {
            return null;
        }
        List<HitProxyPlan.Proxy> proxies = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.CompoundTag q = list.getCompoundOrEmpty(i);
            String id = q.getStringOr("id", "");
            if (id.isEmpty()) {
                return null;
            }
            proxies.add(new HitProxyPlan.Proxy(id, q.getDoubleOr("scale", 1), q.getDoubleOr("w", 0),
                    q.getDoubleOr("h", 0), q.getDoubleOr("x", 0), q.getDoubleOr("y", 0),
                    q.getDoubleOr("z", 0)));
        }
        return new HitProxyPlan.Plan(
                new RigBase.Choice(core, t.getDoubleOr("scale", 1), t.getDoubleOr("actualWidth", 0),
                        t.getDoubleOr("wantWidth", 0), t.getDoubleOr("height", 0)),
                List.copyOf(proxies),
                new HitProxyPlan.Score(t.getDoubleOr("clickHit", 0), t.getDoubleOr("clickAir", 0)),
                new HitProxyPlan.Score(t.getDoubleOr("arrowHit", 0), t.getDoubleOr("arrowAir", 0)),
                t.getBooleanOr("met", false), t.getIntOr("tried", 0));
    }

    // ---- the entities ----

    /** Spawn a rig's proxies over their parts, for a body facing {@code bodyYaw}. */
    public static List<Mob> spawn(ServerLevel level, Mob carrier, List<HitProxyPlan.Proxy> plan,
                                  float bodyYaw) {
        List<Mob> out = new ArrayList<>(plan.size());
        for (HitProxyPlan.Proxy p : plan) {
            Mob m = spawnOne(level, carrier, p, bodyYaw);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    static Mob spawnOne(ServerLevel level, Mob carrier, HitProxyPlan.Proxy p, float bodyYaw) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                ResourceLocation.withDefaultNamespace(p.id()));
        Entity raw = type.create(level, EntitySpawnReason.COMMAND);
        if (!(raw instanceof Mob mob)) {
            if (raw != null) {
                raw.discard();
            }
            return null;
        }
        Vec3 at = at(p, carrier.position(), bodyYaw);
        mob.snapTo(at.x, at.y, at.z, bodyYaw, 0f);
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, true, false, false));
        mob.setInvisible(true);
        mob.setSilent(true);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setInvulnerable(true);
        mob.noPhysics = true;
        mob.setPersistenceRequired();
        var attr = mob.getAttribute(Attributes.SCALE);
        if (attr != null) {
            attr.setBaseValue(p.scale());
            mob.refreshDimensions();   // the cached box only moves on a refresh (DisplayRig.spawn)
        }
        mob.addTag(PROXY_TAG);
        mob.addTag(PROXY_OWNER_PREFIX + carrier.getUUID());
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * Where a proxy's feet go: its offset in the rig's frame, turned the way a display is turned.
     * A display draws its rig rotated by {@code rotationYXZ(-yaw, ...)} ({@code DisplayRenderer},
     * FIXED billboard) at {@link DisplayRig#displayYaw}, and a proxy must stand where its part is
     * DRAWN, so it takes the same turn.
     */
    public static Vec3 at(HitProxyPlan.Proxy p, Vec3 feet, float bodyYaw) {
        return toWorld(p.x(), p.y(), p.z(), feet, bodyYaw);
    }

    /** A point in the rig's frame, in the world, for a rig standing at {@code feet}. */
    public static Vec3 toWorld(double x, double y, double z, Vec3 feet, float bodyYaw) {
        double t = Math.toRadians(DisplayRig.displayYaw(bodyYaw));
        double c = Math.cos(t);
        double s = Math.sin(t);
        return new Vec3(feet.x + x * c - z * s, feet.y + y, feet.z + x * s + z * c);
    }

    /**
     * Keep a rig's proxies over their parts. A proxy that has gone (its chunk unloaded under it, a
     * {@code /kill}) is replaced where it should be, so a creature never quietly loses a limb's
     * hitbox.
     */
    static void follow(List<Mob> proxies, List<HitProxyPlan.Proxy> plan, Mob carrier, Vec3 feet,
                       float bodyYaw) {
        for (int i = 0; i < proxies.size() && i < plan.size(); i++) {
            Mob m = proxies.get(i);
            if (m.isRemoved()) {
                if (!(carrier.level() instanceof ServerLevel level) || carrier.isRemoved()) {
                    continue;
                }
                // Never into a chunk that is not loaded: the new proxy would be saved into
                // it at once and this would spawn another next tick, filling that chunk's save
                // with proxies that are only refused when it next loads. It is replaced once its
                // spot is loaded again.
                if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(
                        at(plan.get(i), feet, bodyYaw)))) {
                    continue;
                }
                Mob again = spawnOne(level, carrier, plan.get(i), bodyYaw);
                if (again == null) {
                    continue;
                }
                proxies.set(i, again);
                m = again;
            }
            Vec3 at = at(plan.get(i), feet, bodyYaw);
            m.snapTo(at.x, at.y, at.z, bodyYaw, 0f);
        }
    }

    // ---- who is what ----

    public static boolean isProxy(Entity e) {
        return e != null && e.getTags().contains(PROXY_TAG);
    }

    /** The carrier this proxy stands for, if it is alive in the proxy's level. */
    public static Entity carrierOf(Entity proxy) {
        if (!(proxy.level() instanceof ServerLevel level)) {
            return null;
        }
        for (String tag : proxy.getTags()) {
            if (tag.startsWith(PROXY_OWNER_PREFIX)) {
                try {
                    Entity c = level.getEntity(UUID.fromString(
                            tag.substring(PROXY_OWNER_PREFIX.length())));
                    return c != null && c.isAlive() ? c : null;
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Is this entity one of that carrier's proxies? For the reaper's sweep. */
    static boolean belongsTo(Entity e, UUID carrier) {
        return e.getTags().contains(PROXY_OWNER_PREFIX + carrier);
    }

    // ---- forwarding ----

    /** A punch on a proxy is a punch on the carrier. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (!isProxy(event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        Entity carrier = carrierOf(event.getTarget());
        if (carrier != null && com.moddingfromamod.mob.RideSeat.mountOf(event.getEntity()) == carrier) {
            // He is RIDING it: its head and wings are right in front of him, and a swing at
            // something past them must not land on his own mount (measured: every rideable
            // creature with proxies was hurt by its own rider, RideFitProbeGameTest).
            return;
        }
        if (carrier != null) {
            event.getEntity().attack(carrier);
        } else {
            orphan(event.getTarget());
        }
    }

    /** A lead, a name tag or food held out to a proxy is held out to the carrier. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isProxy(event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        if (event.getLevel().isClientSide()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        Entity carrier = carrierOf(event.getTarget());
        if (carrier == null) {
            orphan(event.getTarget());
            event.setCancellationResult(InteractionResult.PASS);
            return;
        }
        if (com.moddingfromamod.mob.RideSeat.mountOf(event.getEntity()) == carrier) {
            event.setCancellationResult(InteractionResult.PASS);   // his own mount: nothing to do
            return;
        }
        event.setCancellationResult(event.getEntity().interactOn(carrier, event.getHand()));
    }

    /** A projectile that reaches a proxy reaches the carrier, seen by every other listener too. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !isProxy(hit.getEntity())) {
            return;
        }
        event.setCanceled(true);
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide()) {
            return;
        }
        Entity carrier = carrierOf(hit.getEntity());
        if (carrier == null) {
            orphan(hit.getEntity());
            return;
        }
        EntityHitResult onCarrier = new EntityHitResult(carrier, hit.getLocation());
        if (!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(projectile, onCarrier)) {
            ((com.moddingfromamod.mixin.ProjectileHitInvoker) projectile)
                    .moddingfromamod$hitTargetOrDeflectSelf(onCarrier);
        }
    }

    /** A proxy whose carrier is gone is a leftover: take it out of the world. */
    private static void orphan(Entity proxy) {
        LOG.info("discarding an orphaned hit proxy {} at {}", proxy.getUUID(),
                proxy.blockPosition());
        proxy.discard();
    }

    /**
     * A proxy read back from a save is a leftover — rigs spawn theirs fresh on every build and
     * every adopt — so it never gets back into the world.
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && isProxy(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
