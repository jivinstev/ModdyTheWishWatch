package com.moddingfromamod.world;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The one set of event handlers every world module is reached through</b>: picks the
 * module the manifest names, starts it, hands it logins and ticks, holds its {@link Bases}, starts
 * the world-rules reloader, and applies the GENERAL respawn-home rule — a player whose respawn lands
 * off his own base is moved home — which asks {@link Bases} and nothing island-shaped.
 *
 * <p>Gameplay events, so {@code NeoForge.EVENT_BUS} (the default bus of
 * {@link EventBusSubscriber}), like every handler it replaced in {@code IslandService}.
 */
@EventBusSubscriber(modid = com.moddingfromamod.ModdingFromAModMod.MODID)
public final class WorldModuleHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/worlds");

    /** The JVM property naming the module ({@code MODDY_WORLD_MODULE}, from the manifest's {@code module}). */
    public static final String MODULE_PROPERTY = "moddingfromamod.world.module";
    /** The JVM property naming the world ({@code MODDY_WORLD}). */
    public static final String WORLD_PROPERTY = "moddingfromamod.world.id";

    /**
     * Every module there is: the {@link WorldModuleType}s plug-ins registered. The core names none; a
     * world module is registered by its plug-in. Empty in the public single-player mod.
     */
    public static Map<String, WorldModuleType> modules() {
        return modules(com.moddingfromamod.plugin.Plugins.shared());
    }

    /** {@link #modules()}, asked of {@code plugins}. */
    public static Map<String, WorldModuleType> modules(com.moddingfromamod.plugin.Plugins plugins) {
        Map<String, WorldModuleType> out = new java.util.LinkedHashMap<>();
        for (WorldModuleType t : plugins.all(WorldModuleType.class)) {
            out.putIfAbsent(t.id(), t);
        }
        return out;
    }

    /** The id of the first registered module that says THIS world is one of its own ({@link WorldModuleType#claims}), or empty. */
    static String claimedModule(Map<String, WorldModuleType> types, net.minecraft.server.MinecraftServer server) {
        for (WorldModuleType t : types.values()) {
            try {
                if (t.claims(server)) {
                    return t.id();
                }
            } catch (RuntimeException e) {
                LOGGER.error("world module {} could not say whether this world is its own: {}", t.id(), e.toString());
            }
        }
        return "";
    }

    /** The id of the first registered module that runs on a world naming none, or empty. */
    static String impliedModule(Map<String, WorldModuleType> types) {
        for (WorldModuleType t : types.values()) {
            if (t.impliedWhenUnnamed()) {
                return t.id();
            }
        }
        return "";
    }

    private static volatile WorldModule module;
    private static volatile Bases bases;

    private WorldModuleHost() {
    }

    /**
     * Which module runs here: the named one (a house's JVM property), else {@code claimed} — the module whose world
     * this is (a level jar's world type, per world), else {@code implied} — the module a plug-in says runs on a
     * world that names none (a world rendered before modules had names — so a server whose launch config predates
     * the {@code module} line keeps its module), else none.
     * Pure.
     */
    static String chooseModule(String named, String claimed, String implied) {
        if (named != null && "none".equalsIgnoreCase(named.strip())) {
            return "";                                 // said out loud: no module, islands or not
        }
        if (named != null && !named.isBlank()) {
            return named.strip();
        }
        if (claimed != null && !claimed.isBlank()) {
            return claimed.strip();
        }
        return implied == null ? "" : implied.strip();
    }

    /** {@link #chooseModule(String, String, String)} with no claim (the shape every caller used). */
    static String chooseModule(String named, String implied) {
        return chooseModule(named, "", implied);
    }

    /** This world's bases, or null on a world without them (a world with no module). */
    public static Bases liveBases() {
        Bases t = testBases;
        return t != null ? t : bases;
    }

    private static volatile Bases testBases;

    /**
     * where a search round {@code caster} may act on the running world — {@link BaseReach#fence} over
     * {@link #liveBases()} and the loaded world rules, the same two the reach rule reads at {@code tools/call}. Null
     * when nothing fences it (no bases, no base-reach rule, no wish bound).
     */
    public static Region fenceFor(java.util.UUID caster) {
        return BaseReach.fence(caster, liveBases(), WorldRulesService.loadedOrNull());
    }

    /** Pin the bases for a test (null restores) — like {@code WorldRulesService.forTest}; a batch of its own. */
    public static void forTest(Bases b) {
        testBases = b;
    }

    /** The running module, or null. */
    public static WorldModule liveModule() {
        WorldModule t = testModule;
        return t != null ? t : module;
    }

    private static volatile WorldModule testModule;

    /** Pin the running module for a test (null restores), like {@link #forTest}; a batch of its own. */
    public static void moduleForTest(WorldModule m) {
        testModule = m;
    }

    /** The running module's extra help lines for {@code player} ({@link WorldModule#helpLines}); none without one. */
    public static java.util.List<net.minecraft.network.chat.Component> helpLines(ServerPlayer player) {
        WorldModule m = liveModule();
        return m == null ? java.util.List.of() : m.helpLines(player);
    }

    /** The running module's {@link WorldModule#keepTicking}, or null (no module, or it threw). */
    public static String keepTicking() {
        WorldModule m = liveModule();
        if (m == null) {
            return null;
        }
        try {
            return m.keepTicking();
        } catch (RuntimeException e) {
            return null;                    // never hold an empty server up on a module's fault
        }
    }

    /** Send {@link #helpLines} to him. */
    public static void sayHelp(ServerPlayer player) {
        for (net.minecraft.network.chat.Component c : helpLines(player)) {
            player.sendSystemMessage(c);
        }
    }

    /** This world's id, as its manifest names it, or empty. */
    public static String worldId() {
        String w = System.getProperty(WORLD_PROPERTY, "");
        return w == null ? "" : w.strip();
    }

    /**
     * Which world this is, as a key on a child's shelf: the manifest id when it has one, else the world folder's name. Lower-case, {@code [a-z0-9_-]} only.
     * The wish bag's file name (§19) and a building's {@code placed} row both use it.
     */
    public static String worldKey(net.minecraft.server.MinecraftServer server) {
        String id = worldId();
        if (id.isEmpty() && server != null) {
            java.nio.file.Path root = server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            id = root.getFileName() == null ? "" : root.getFileName().toString();
        }
        String clean = id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return clean.isEmpty() ? "world" : clean;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        stopAll();
        MinecraftServer server = event.getServer();
        Map<String, WorldModuleType> types = modules();
        String id = chooseModule(System.getProperty(MODULE_PROPERTY), claimedModule(types, server), impliedModule(types));
        WorldModule m = null;
        if (!id.isEmpty()) {
            WorldModuleType make = types.get(id);
            if (make == null) {
                LOGGER.error("MODDY-ALERT world module unknown: '{}' (known: {}); this world runs with no module",
                        id, types.keySet());
            } else {
                m = make.create();
            }
        }
        Bases b = null;
        if (m != null) {
            try {
                b = m.start(server);
            } catch (RuntimeException e) {
                LOGGER.error("world module {} failed to start: {}", id, e.toString());
            }
            LOGGER.info("world module {} started{}", id, b == null ? " (no bases)" : " with " + b.noun() + "s");
        }
        module = m;
        bases = b;
        WorldRules fallback = m == null ? WorldRules.ALLOW_ALL : m.fallbackRules(b);
        WorldRulesService.start(WorldRulesService.defaultFile(), worldId(), fallback);
        // #14: a world that answers makes has its Builder pages' facts worked out now, in the background, so a page drawn
        // later is a comparison. A world that answers none starts nothing.
        if (WorldMakes.get() != WorldMakes.NONE) {
            // an item's attack damage and a creature's inherited numbers are the game's own, read at run time
            com.moddingfromamod.wish.MakeFactsLookup.readTheGame();
            com.moddingfromamod.wishbuilder.BuilderPageFacts.forWorld(
                    com.moddingfromamod.wishbuilder.BuilderSettings.current().allow());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        stopAll();
    }

    private static void stopAll() {
        WorldModule m = module;
        module = null;
        bases = null;
        if (m != null) {
            try {
                m.stop();
            } catch (RuntimeException e) {
                LOGGER.debug("world module stop: {}", e.toString());
            }
        }
        WorldRulesService.stop();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        WorldModule m = module;
        if (m != null && event.getEntity() instanceof ServerPlayer p) {
            m.onLogin(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WorldModule m = module;
        if (m != null && event.getEntity() instanceof ServerPlayer p) {
            m.onLogout(p);
        }
    }

    /**
     * <b>A death on a base respawns him at home in ONE step</b> (2026-10-31). Minecraft respawned a
     * player whose base-set point was obstructed (a wished volcano on it) at the WORLD SPAWN, ~1,450
     * blocks away, and did not copy the point to the new player — so it was gone, every later death
     * went the same way, and {@link #onRespawn} then teleported him home: two terrain loads, up to
     * ~35 s of loading on a live server. Here, before Minecraft builds the new player, a respawn
     * whose point is the base's (or that has none) is sent straight to the home spot — or the nearest
     * safe spot when it is buried — and his point is kept. A bed or an anchor he set is never touched.
     */
    @SubscribeEvent
    public static void onRespawnPosition(net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent event) {
        Bases b = liveBases();
        if (b == null || event.isFromEndFight() || !(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        var target = respawnAtHome(p, b);
        if (target != null) {
            var was = event.getTeleportTransition();
            event.setTeleportTransition(new net.minecraft.world.level.portal.TeleportTransition(target.level(),
                    new net.minecraft.world.phys.Vec3(target.x(), target.y(), target.z()),
                    net.minecraft.world.phys.Vec3.ZERO, was.yRot(), was.xRot(), was.postTeleportTransition()));
            event.setCopyOriginalSpawnPosition(true);
        }
    }

    /** Where a respawn on a base should land: its home spot (safe), or null to leave it to Minecraft. */
    public record HomeSpot(net.minecraft.server.level.ServerLevel level, double x, double y, double z) {
    }

    public static HomeSpot respawnAtHome(ServerPlayer p, Bases b) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base == null || !b.standsYet(p.getUUID())) {
            return null;
        }
        if (com.moddingfromamod.compat.Respawn.pos(p) != null && !ourRespawn(p, base)) {
            return null;                    // his own bed or anchor
        }
        MinecraftServer server = p.getServer();
        if (server == null) {
            return null;
        }
        var level = server.overworld();
        var spot = com.moddingfromamod.vanilla.SafePlacement.safeHome(level, p, base.x() + 0.5, base.homeY(),
                base.z() + 0.5);
        return new HomeSpot(level, spot.x(), spot.y(), spot.z());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        Bases b = bases;
        if (b != null && !event.isEndConquered() && event.getEntity() instanceof ServerPlayer p) {
            sendHomeIfAway(p, b);
            fallHomeIfUnsafe(p, b);
            RespawnGuard.afterRespawn(p, b);      //: the grace, and the loop's way out
        }
    }

    /**
     * After a respawn ON his base at a point a buried home moved: once the home spot is clear again
     * (the build was taken away), or where he landed is no longer safe to stand on, he goes through
     * {@link #goHome} — onto the home spot, and his respawn point with him.
     */
    public static void fallHomeIfUnsafe(ServerPlayer p, Bases b) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base == null || !b.standsYet(p.getUUID()) || !ourRespawn(p, base)) {
            return;
        }
        var level = p.level();
        var world = com.moddingfromamod.vanilla.SafePlacement.world(level);
        var body = com.moddingfromamod.vanilla.SafePlacement.body(p);
        var here = com.moddingfromamod.runtime.SafeSpot.check(world, body, p.getX(), p.getY(), p.getZ());
        boolean unsafe = here == com.moddingfromamod.runtime.SafeSpot.Problem.NO_FLOOR
                || com.moddingfromamod.runtime.SafeSpot.buried(here);
        BlockPos home = BlockPos.containing(base.x() + 0.5, base.homeY(), base.z() + 0.5);
        boolean awayFromAClearHome = !home.equals(com.moddingfromamod.compat.Respawn.pos(p))
                && com.moddingfromamod.runtime.SafeSpot.check(world, body, base.x() + 0.5, base.homeY(),
                        base.z() + 0.5) == com.moddingfromamod.runtime.SafeSpot.Problem.NONE;
        if (unsafe || awayFromAClearHome) {
            goHome(p, b, level, base.x() + 0.5, base.homeY(), base.z() + 0.5);
        }
    }

    /**
     * Is his respawn point the one a base set — forced (not a bed or an anchor), in the base's
     * dimension, inside the base? Only that one is ever moved; a bed he slept in is his.
     */
    static boolean ourRespawn(ServerPlayer p, Base base) {
        var at = com.moddingfromamod.compat.Respawn.pos(p);
        return at != null && com.moddingfromamod.compat.Respawn.forced(p)
                && com.moddingfromamod.compat.Respawn.dimension(p).location().toString().equals(base.dimension())
                && base.holds(base.dimension(), at.getX() + 0.5, at.getZ() + 0.5);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        WorldModule m = module;
        if (m != null) {
            m.onTick();
        }
    }

    /**
     * <b>Respawn-home</b>, for any world with bases: a player with a base who is not on it (a broken
     * bed sends him to the world spawn, which is nobody's) is moved to its home spot. Public so a
     * provider's own test drives the same path the event does.
     */
    public static void sendHomeIfAway(ServerPlayer p, Bases b) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base == null || !b.standsYet(p.getUUID())) {
            return;
        }
        String dim = p.level().dimension().location().toString();
        if (base.holds(dim, p.getX(), p.getZ())) {
            return;
        }
        MinecraftServer server = p.getServer();
        if (server == null) {
            return;
        }
        var level = Bases.OVERWORLD.equals(base.dimension()) ? server.overworld() : server.getLevel(Level.OVERWORLD);
        goHome(p, b, level, base.x() + 0.5, base.homeY(), base.z() + 0.5);
    }

    /**
     * Put him on his home spot — or, when a wish has built on it, on the nearest safe spot and tell
     * him so (a volcano on a child's home suffocated him on every respawn). Every "send him
     * home" goes through here.
     */
    public static void goHome(ServerPlayer p, net.minecraft.server.level.ServerLevel level, double x, double y,
                              double z) {
        goHome(p, bases, level, x, y, z);
    }

    /** {@link #goHome}, with the {@link Bases} whose respawn point it may move (a test hands its own). */
    public static void goHome(ServerPlayer p, Bases b, net.minecraft.server.level.ServerLevel level, double x,
                              double y, double z) {
        var home = com.moddingfromamod.vanilla.SafePlacement.safeHome(level, p, x, y, z);
        boolean padded = false;
        if (home.x() == x && home.y() == y && home.z() == z
                && com.moddingfromamod.vanilla.SafePlacement.buriedAt(level, p, x, y, z)) {
            // the home is lava (or buried) and nothing within reach is safe either — the lava-covered
            // island. A glass pad in clear air above it, so a respawn is never straight back into it.
            BlockPos pad = RespawnGuard.rescuePad(level, x, y, z);
            if (pad != null) {
                home = new com.moddingfromamod.runtime.SafeSpot.Spot(pad.getX() + 0.5, pad.getY(), pad.getZ() + 0.5);
                padded = true;
            }
        }
        com.moddingfromamod.compat.Teleports.player(p, level, home.x(), home.y(), home.z(),
                p.getYRot(), p.getXRot());
        p.fallDistance = 0;
        // HIS RESPAWN POINT FOLLOWS (2026-10-30): left on the buried home, vanilla refuses it and
        // respawns him at the world spawn — the arrival island, ~1,450 blocks away — and this then
        // teleports him back: two terrain loads, ~15 s of "loading" on a live server. So the point
        // a base set moves to where he was just put, and back to the home spot once it is clear.
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base != null && ourRespawn(p, base)) {
            BlockPos to = BlockPos.containing(home.x(), home.y(), home.z());
            if (!to.equals(com.moddingfromamod.compat.Respawn.pos(p))) {
                com.moddingfromamod.compat.Respawn.set(p, level.dimension(), to, 0f, true, false);
            }
        }
        if (padded) {
            LOGGER.info("{}'s home spot {} {} {} is unsafe and nothing near is safe -> a rescue pad at {} {} {}",
                    p.getScoreboardName(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                    (int) Math.floor(home.x()), (int) Math.floor(home.y()), (int) Math.floor(home.z()));
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(RespawnGuard.PAD_LINE)
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
        } else if (home.x() != x || home.y() != y || home.z() != z) {
            LOGGER.info("{}'s home spot {} {} {} is buried -> sent to {} {} {}", p.getScoreboardName(),
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                    (int) Math.floor(home.x()), (int) Math.floor(home.y()), (int) Math.floor(home.z()));
            com.moddingfromamod.wish.WishFeedback.moved(p, com.moddingfromamod.runtime.SafeSpot.toldYou(
                    new com.moddingfromamod.runtime.SafeSpot.Spot(x, y, z), home,
                    com.moddingfromamod.runtime.SafeSpot.YouWhy.HOME_BURIED));
        }
    }
}
