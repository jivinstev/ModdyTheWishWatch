package com.moddingfromamod.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.WishExecutor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>The world's rules, carried out</b> (wave 2): every {@link WorldRule} the agent set with
 * {@code set_rule}, stored with the world and fired from Minecraft's own events.
 *
 * <p>Stored as vanilla {@link SavedData} on the overworld, so the rules travel with the world save
 * and follow it through a backup and restore. Versioned ({@link Store#VERSION}): a copy written by a
 * newer build is REFUSED rather than read as empty and written over (the store latch).
 *
 * <p><b>Nothing fires inside the event that caused it.</b> Every firing is queued and carried out on
 * the next server tick, under a per-tick budget. A death, a landing or a spawn happens in the middle
 * of Minecraft's own entity loop; running commands there is how a rule summons into a world that is
 * halfway through a tick. The one exception is {@code harmless}, which must empty the explosion's
 * block list before the blocks break.
 *
 * <p><b>A rule cannot feed itself.</b> Entities made while rules run do not count as spawning, so
 * "when a slime appears, summon a slime" is one slime, not a server that stops.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WorldRules {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("moddingfromamod/rules");

    private WorldRules() {
    }

    /** SavedData file name, under {@code <world>/data/}. */
    public static final String DATA = "moddingfromamod_rules";

    /** Firings carried out per tick; the rest wait for the next. */
    static final int BUDGET = 64;
    /** Subjects one timed rule reaches per firing (every cow near him, not every cow loaded). */
    static final int MAX_SUBJECTS = 48;
    /** How far from a player a mob can be and still be reached by a timed rule. */
    static final double REACH = 48;
    /**
     * A rule that SUMMONS a kind of thing stops summoning it while this many of that kind are
     * already within {@link #CROWD_RADIUS} of the spot. Measured on the first routing arm: a rain-frog rule summoned
     * three frogs every three seconds, forever, and nothing else ever stops a timed rule. Mutable only
     * so the GameTest can reach it without summoning 120 things.
     */
    static int crowd = 120;
    static final double CROWD_RADIUS = 32;
    /** Bone meal applied per firing of a `grow`, so grass does not become a flower carpet at once. */
    static final int GROW_PER_FIRING = 6;

    private record Firing(WorldRule rule, ServerLevel level, Entity subject, Vec3 pos, Entity cause) {
    }

    private static volatile List<WorldRule> live = List.of();
    private static volatile boolean loaded;
    private static final Deque<Firing> QUEUE = new ArrayDeque<>();
    private static boolean running;
    private static final Map<UUID, Boolean> SNEAKING = new HashMap<>();
    private static long lastDayTime = -1;
    private static Boolean lastRaining;
    /** The last thing each rule's commands said when they FAILED, for the agent to read back. */
    private static final Map<String, String> LAST_ERROR = new HashMap<>();

    // ── the store ─────────────────────────────────────────────────────────────────────────────

    /** Every rule in this world, by name. */
    public static final class Store extends SavedData {
        public static final int VERSION = 1;
        final Map<String, WorldRule> rules = new LinkedHashMap<>();
        /** A copy written by a newer build: read nothing, write nothing. */
        String refused;

        static Store load(CompoundTag tag, HolderLookup.Provider lookup) {
            Store s = new Store();
            int v = tag.getIntOr("v", 0);
            if (v > VERSION) {
                s.refused = "the rules were saved by a newer Moddy (v" + v + ")";
                return s;
            }
            ListTag list = tag.getListOrEmpty("rules");
            for (int i = 0; i < list.size(); i++) {
                try {
                    WorldRule r = WorldRule.fromJson(JsonParser.parseString(list.getStringOr(i, ""))
                            .getAsJsonObject());
                    s.rules.put(r.name(), r);
                } catch (RuntimeException e) {
                    LOGGER.warn("[rules] an unreadable rule was skipped: {}", e.toString());
                }
            }
            return s;
        }
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
            tag.putInt("v", VERSION);
            ListTag list = new ListTag();
            for (WorldRule r : rules.values()) {
                list.add(StringTag.valueOf(r.toJson().toString()));
            }
            tag.put("rules", list);
            return tag;
        }

        static net.minecraft.world.level.saveddata.SavedDataType<Store> factory() {
            return com.moddingfromamod.compat.SavedDataTypes.of(DATA, Store::new, Store::load,
                    (s, lookup) -> s.save(new CompoundTag(), lookup));
        }
    }

    public static Store store(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Store.factory());
    }

    /** Every rule, in the order they were set. */
    public static List<WorldRule> all(MinecraftServer server) {
        return List.copyOf(store(server).rules.values());
    }

    /** Add or replace a rule by its name. Throws when the store refused to load. */
    public static void put(MinecraftServer server, WorldRule rule) {
        Store s = writable(server);
        s.rules.put(rule.name(), rule);
        s.setDirty();
        LAST_ERROR.remove(rule.name());
        refresh(server);
    }

    /** Take a rule out; false when there was none by that name. */
    public static boolean remove(MinecraftServer server, String name) {
        Store s = writable(server);
        boolean had = s.rules.remove(name) != null;
        if (had) {
            s.setDirty();
            LAST_ERROR.remove(name);
            refresh(server);
        }
        return had;
    }

    /** The last failure a rule's commands reported when it fired, or null. */
    public static String lastError(String name) {
        return LAST_ERROR.get(name);
    }

    private static Store writable(MinecraftServer server) {
        Store s = store(server);
        if (s.refused != null) {
            throw new IllegalStateException(s.refused + "; this build will not write over them.");
        }
        return s;
    }

    private static void refresh(MinecraftServer server) {
        Store s = store(server);
        live = s.refused != null ? List.of() : List.copyOf(s.rules.values());
        loaded = true;
    }

    private static List<WorldRule> rules(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        if (!loaded) {
            refresh(server);
        }
        return live;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        live = List.of();
        loaded = false;
        QUEUE.clear();
        SNEAKING.clear();
        LAST_ERROR.clear();
        lastDayTime = -1;
        lastRaining = null;
    }

    // ── matching ──────────────────────────────────────────────────────────────────────────────

    static String typeId(Entity e) {
        return e == null ? "" : String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
    }

    static boolean who(WorldRule r, Entity e) {
        if (e == null) {
            return r.who() == null;
        }
        return r.whoMatches(e instanceof Player, typeId(e), tag -> e.getType().is(
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(tag))), e.getTags()::contains);
    }

    static boolean blockMatches(BlockState state, String block) {
        if (block.startsWith("#")) {
            return state.is(TagKey.create(Registries.BLOCK, ResourceLocation.parse(block.substring(1))));
        }
        return block.equals(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
    }

    private static void fire(WorldRule r, ServerLevel level, Entity subject, Vec3 pos, Entity cause) {
        if (r.block() != null && r.when() != WorldRule.When.NEAR_BLOCK && subject != null) {
            BlockPos under = BlockPos.containing(subject.position().x, subject.position().y - 0.2,
                    subject.position().z);
            if (!blockMatches(level.getBlockState(under), r.block())
                    && !blockMatches(level.getBlockState(subject.blockPosition()), r.block())) {
                return;
            }
        }
        if (!r.only().holds(level.isBrightOutside(), level.isRaining())) {
            return;
        }
        if (r.chance() < 1 && level.getRandom().nextDouble() >= r.chance()) {
            return;
        }
        QUEUE.add(new Firing(r, level, subject, pos, cause));
    }

    private static void fireAll(WorldRule.When when, ServerLevel level, Entity subject, Vec3 pos,
                                Entity cause) {
        for (WorldRule r : rules(level.getServer())) {
            if (r.when() == when && who(r, subject)) {
                fire(r, level, subject, pos, cause);
            }
        }
    }

    // ── the events ────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (running || e.loadedFromDisk() || !(e.getLevel() instanceof ServerLevel level)
                || !(e.getEntity() instanceof Mob mob)) {
            return;
        }
        fireAll(WorldRule.When.MOB_SPAWNS, level, mob, mob.position(), null);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity().level() instanceof ServerLevel level && !(e.getEntity() instanceof Player)) {
            fireAll(WorldRule.When.MOB_DIES, level, e.getEntity(), e.getEntity().position(),
                    e.getSource().getEntity());
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (e.getDistance() > 0.5f && e.getEntity().level() instanceof ServerLevel level) {
            fireAll(WorldRule.When.LANDS, level, e.getEntity(), e.getEntity().position(), null);
        }
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity().level() instanceof ServerLevel level) {
            fireAll(WorldRule.When.JUMPS, level, e.getEntity(), e.getEntity().position(), null);
        }
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent e) {
        if (!(e.getProjectile().level() instanceof ServerLevel level)) {
            return;
        }
        Entity hit = e.getRayTraceResult() instanceof EntityHitResult eh ? eh.getEntity() : null;
        String pid = typeId(e.getProjectile());
        for (WorldRule r : rules(level.getServer())) {
            if (r.when() != WorldRule.When.PROJECTILE_HITS) {
                continue;
            }
            if (r.projectile() != null && !projectileMatches(r.projectile(), e.getProjectile(), pid)) {
                continue;
            }
            if (hit == null ? r.who() != null && !WorldRule.ANY.equals(r.who()) : !who(r, hit)) {
                continue;
            }
            fire(r, level, hit, e.getRayTraceResult().getLocation(), e.getProjectile());
        }
    }

    private static boolean projectileMatches(String want, Entity p, String pid) {
        return want.startsWith("#")
                ? p.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(want.substring(1))))
                : want.equals(pid);
    }

    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.level() instanceof ServerLevel level) {
            Vec3 at = e.getHookEntity() == null ? p.position() : e.getHookEntity().position();
            fireAll(WorldRule.When.FISH_CAUGHT, level, p, at, e.getHookEntity());
        }
    }

    @SubscribeEvent
    public static void onExplode(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity by = e.getExplosion().getDirectSourceEntity();
        Vec3 at = e.getExplosion().center();
        for (WorldRule r : rules(level.getServer())) {
            if (r.when() != WorldRule.When.EXPLODES) {
                continue;
            }
            if (by == null ? !WorldRule.ANY.equals(r.who()) : !who(r, by)) {
                continue;
            }
            if (!r.only().holds(level.isBrightOutside(), level.isRaining())
                    || r.chance() < 1 && level.getRandom().nextDouble() >= r.chance()) {
                continue;
            }
            if (r.harmless()) {
                e.getAffectedBlocks().clear();
                e.getAffectedEntities().clear();
            }
            QUEUE.add(new Firing(r, level, null, at, by));
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        List<WorldRule> rules = rules(server);
        if (!rules.isEmpty()) {
            poll(server, rules, server.getTickCount());
        }
        run(server);
    }

    private static void poll(MinecraftServer server, List<WorldRule> rules, long tick) {
        ServerLevel over = server.overworld();
        long day = over.getDayTime();
        boolean sunrise = RuleClock.crossed(lastDayTime, day, RuleClock.SUNRISE);
        boolean sunset = RuleClock.crossed(lastDayTime, day, RuleClock.SUNSET);
        lastDayTime = day;
        boolean raining = over.isRaining();
        boolean rainStarts = lastRaining != null && !lastRaining && raining;
        boolean rainStops = lastRaining != null && lastRaining && !raining;
        lastRaining = raining;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (WorldRule r : rules) {
            switch (r.when()) {
                case EVERY -> {
                    if (RuleClock.due(tick, r.seconds())) {
                        timed(r, server, players);
                    }
                }
                case SUNRISE -> {
                    if (sunrise) {
                        timed(r, server, players);
                    }
                }
                case SUNSET -> {
                    if (sunset) {
                        timed(r, server, players);
                    }
                }
                case RAIN_STARTS -> {
                    if (rainStarts) {
                        timed(r, server, players);
                    }
                }
                case RAIN_STOPS -> {
                    if (rainStops) {
                        timed(r, server, players);
                    }
                }
                case SNEAKS -> {
                    for (ServerPlayer p : players) {
                        boolean now = p.isShiftKeyDown();
                        Boolean was = SNEAKING.put(p.getUUID(), now);
                        if (now && !Boolean.TRUE.equals(was)) {
                            fire(r, p.level(), p, p.position(), null);
                        }
                    }
                }
                case SPRINTS -> {
                    if (tick % 5 == 0) {
                        for (ServerPlayer p : players) {
                            if (p.isSprinting()) {
                                fire(r, p.level(), p, p.position(), null);
                            }
                        }
                    }
                }
                case NEAR_PLAYER -> {
                    if (tick % 20 == 0) {
                        Set<UUID> seen = new HashSet<>();
                        for (ServerPlayer p : players) {
                            double rad = Math.max(1, r.radius());
                            for (Entity m : p.level().getEntities(p, p.getBoundingBox().inflate(rad),
                                    x -> x instanceof LivingEntity && !(x instanceof Player) && who(r, x))) {
                                if (seen.add(m.getUUID()) && seen.size() <= MAX_SUBJECTS) {
                                    fire(r, p.level(), m, m.position(), p);
                                }
                            }
                        }
                    }
                }
                case NEAR_BLOCK -> {
                    if (tick % 20 == 0) {
                        for (ServerPlayer p : players) {
                            BlockPos found = nearestBlock(p.level(), p, r);
                            if (found != null) {
                                fire(r, p.level(), p, Vec3.atCenterOf(found), null);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    /** A timed rule: once to the world, once per player, or to each matching mob near a player. */
    private static void timed(WorldRule r, MinecraftServer server, List<ServerPlayer> players) {
        if (WorldRule.WORLD.equals(r.who())) {
            ServerLevel over = server.overworld();
            Vec3 at = players.isEmpty() ? Vec3.atCenterOf(over.getSharedSpawnPos())
                    : players.get(0).position();
            fire(r, over, null, at, null);
            return;
        }
        if (WorldRule.PLAYER.equals(r.who())) {
            for (ServerPlayer p : players) {
                fire(r, p.level(), p, p.position(), null);
            }
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : players) {
            for (Entity m : p.level().getEntities(p, p.getBoundingBox().inflate(REACH),
                    x -> x instanceof LivingEntity && !(x instanceof Player) && who(r, x))) {
                if (seen.add(m.getUUID()) && seen.size() <= MAX_SUBJECTS) {
                    fire(r, p.level(), m, m.position(), p);
                }
            }
        }
    }

    /** The nearest matching block within the rule's radius; radius 0 means under or at his feet. */
    static BlockPos nearestBlock(ServerLevel level, Entity p, WorldRule r) {
        BlockPos feet = p.blockPosition();
        if (r.radius() == 0) {
            BlockPos under = BlockPos.containing(p.position().x, p.position().y - 0.2, p.position().z);
            if (blockMatches(level.getBlockState(under), r.block())) {
                return under;
            }
            return blockMatches(level.getBlockState(feet), r.block()) ? feet : null;
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int rad = r.radius();
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-rad, -rad, -rad), feet.offset(rad, rad, rad))) {
            double d = q.distSqr(feet);
            if (d < bestD && blockMatches(level.getBlockState(q), r.block())) {
                best = q.immutable();
                bestD = d;
            }
        }
        return best;
    }

    // ── carrying a firing out ─────────────────────────────────────────────────────────────────

    private static void run(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        running = true;
        try {
            for (int n = 0; n < BUDGET && !QUEUE.isEmpty(); n++) {
                Firing f = QUEUE.poll();
                try {
                    carryOut(server, f);
                } catch (RuntimeException ex) {
                    LAST_ERROR.put(f.rule().name(), ex.toString());
                    LOGGER.warn("[rules] '{}' failed: {}", f.rule().name(), ex.toString());
                }
            }
        } finally {
            running = false;
        }
    }

    private static void carryOut(MinecraftServer server, Firing f) {
        WorldRule r = f.rule();
        Entity subject = f.subject() != null && f.subject().isRemoved() ? null : f.subject();
        Vec3 pos = subject != null ? subject.position() : f.pos();
        // a rule set "on his island" fires on his island only — not on a neighbour's, not in the void.
        var scoped = com.moddingfromamod.world.StandalonePacks.scopeOf("rule", r.name());
        if (scoped != null && "his_island".equals(scoped.scope())) {
            var bases = com.moddingfromamod.world.WorldModuleHost.liveBases();
            var base = bases == null ? null : bases.baseOf(java.util.UUID.fromString(scoped.playerId()));
            if (base == null || !base.holds(f.level().dimension().location().toString(), pos.x, pos.z)) {
                return;
            }
        }
        if (r.push() != null && subject != null) {
            push(subject, r.push(), f.cause());
        }
        if (r.grow() > 0) {
            grow(f.level(), BlockPos.containing(pos), r.grow());
        }
        for (String c : r.commands()) {
            String made = summons(c);
            Vec3 lands = made == null ? pos : summonedAt(c, pos);
            if (made != null && f.level().getEntities((Entity) null,
                    new AABB(lands, lands).inflate(CROWD_RADIUS), x -> made.equals(typeId(x))).size() >= crowd) {
                LAST_ERROR.put(r.name(), "summoning paused: " + crowd + "+ " + made + " already nearby");
                continue;
            }
            ServerPlayer as = subject instanceof ServerPlayer sp ? sp : nearestPlayer(f.level(), pos);
            String cmd = subject != null && !(subject instanceof ServerPlayer)
                    ? "execute as " + subject.getStringUUID() + " at @s run " + c : c;
            // THE WORLD'S DOING, NOT A HAND-OVER: a rule's `give` goes to his hotbar, then
            // his inventory, then his feet — never the Wish Bag, never a Moddy line — in a scope of
            // its own, even when a wish's command is what set the rule off.
            WishExecutor.Result res = com.moddingfromamod.mob.SpawnScope.own(
                    com.moddingfromamod.mob.SpawnScope.NO_WISH,
                    com.moddingfromamod.item.delivery.DeliveryPlan.Kind.BEHAVIOUR,
                    () -> WishExecutor.runOne(server, f.level(), pos, as, cmd));
            if (!res.success()) {
                LAST_ERROR.put(r.name(), c + " → " + res.joined());
                break;
            }
        }
    }

    /**
     * The entity type this command makes, or null: {@code summon frog ...} → "minecraft:frog",
     * {@code loot spawn ...} → "minecraft:item". Looks through {@code execute … run}.
     */
    static String summons(String command) {
        String[] t = command.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+");
        for (int i = 0; i < t.length; i++) {
            if (t[i].equals("summon") && i + 1 < t.length) {
                String id = t[i + 1].replaceAll("[{\\[].*$", "");
                return id.contains(":") ? id : "minecraft:" + id;
            }
            if (t[i].equals("loot") && i + 1 < t.length && t[i + 1].equals("spawn")) {
                return "minecraft:item";
            }
        }
        return null;
    }

    /**
     * Where a {@code summon <type> <x> <y> <z>} lands, from the spot the rule fired at: absolute
     * numbers are absolute, {@code ~n} is relative, and anything else ({@code ^}, missing) is taken
     * as the spot. Only for the crowd cap, so an approximate answer is a safe one.
     */
    static Vec3 summonedAt(String command, Vec3 pos) {
        String[] t = command.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            if (t[i].equals("summon") && i + 4 < t.length) {
                double[] out = {pos.x, pos.y, pos.z};
                for (int k = 0; k < 3; k++) {
                    String c = t[i + 2 + k];
                    try {
                        if (c.startsWith("~")) {
                            out[k] += c.length() == 1 ? 0 : Double.parseDouble(c.substring(1));
                        } else if (!c.startsWith("^")) {
                            out[k] = Double.parseDouble(c);
                        }
                    } catch (NumberFormatException e) {
                        return pos;
                    }
                }
                return new Vec3(out[0], out[1], out[2]);
            }
        }
        return pos;
    }

    private static ServerPlayer nearestPlayer(ServerLevel level, Vec3 pos) {
        Player p = level.getNearestPlayer(pos.x, pos.y, pos.z, 256, false);
        return p instanceof ServerPlayer sp ? sp : null;
    }

    /** Add the rule's shove to what the thing is already doing. */
    static void push(Entity e, WorldRule.Push p, Entity cause) {
        Vec3 add = new Vec3(0, p.up(), 0);
        if (p.away() != 0 && cause != null) {
            Vec3 d = e.position().subtract(cause.position()).multiply(1, 0, 1);
            if (d.lengthSqr() > 1.0e-6) {
                add = add.add(d.normalize().scale(p.away()));
            }
        }
        if (p.forward() != 0) {
            Vec3 look = e.getLookAngle().multiply(1, 0, 1);
            if (look.lengthSqr() > 1.0e-6) {
                add = add.add(look.normalize().scale(p.forward()));
            }
        }
        e.setDeltaMovement(e.getDeltaMovement().add(add));
        e.hurtMarked = true;
        if (p.up() > 0) {
            e.resetFallDistance();
        }
    }

    /** Bone meal on up to {@link #GROW_PER_FIRING} growable blocks around the spot. */
    static int grow(ServerLevel level, BlockPos at, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-radius, -2, -radius), at.offset(radius, 2, radius))) {
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, q, s)) {
                candidates.add(q.immutable());
            }
        }
        Collections.shuffle(candidates, new java.util.Random(level.getRandom().nextLong()));
        RandomSource rnd = level.getRandom();
        int grown = 0;
        for (BlockPos q : candidates) {
            if (grown >= GROW_PER_FIRING) {
                break;
            }
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, q, s)) {
                if (b.isBonemealSuccess(level, rnd, q, s)) {
                    b.performBonemeal(level, rnd, q, s);
                }
                level.levelEvent(1505, q, 15);
                grown++;
            }
        }
        return grown;
    }

    /** For the GameTest: carry out everything queued now, as the next tick would. */
    static void drainForTest(MinecraftServer server) {
        run(server);
    }
}
