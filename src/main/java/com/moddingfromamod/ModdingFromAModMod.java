package com.moddingfromamod;

import com.moddingfromamod.item.ModItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod class — the entry point NeoForge constructs at load.
 *
 * <p><b>Two buses, two jobs</b> (getting this wrong is the most common cause of a mod that
 * compiles but does nothing):
 * <ul>
 *   <li><b>mod event bus</b> ({@code modEventBus}, passed in here) — one-time <i>registration</i>
 *       and setup: {@code DeferredRegister}s, creative tabs, client renderer/extension binding,
 *       network payload registration. Anything that "declares what exists."</li>
 *   <li><b>game event bus</b> ({@code NeoForge.EVENT_BUS}) — ongoing <i>gameplay</i>: commands,
 *       ticks, player/entity/block events. Register handlers with
 *       {@code NeoForge.EVENT_BUS.register(MyHandler.class)} (or {@code @EventBusSubscriber}).</li>
 * </ul>
 *
 * <p>Client-only code (renderers, screens, key handling) MUST be guarded by
 * {@code FMLEnvironment.getDist() == Dist.CLIENT} (or live in a {@code Dist.CLIENT}-scoped
 * subscriber), or a dedicated server will crash trying to load client classes.
 */
@Mod(ModdingFromAModMod.MODID)
public class ModdingFromAModMod {
    /** Must match {@code mod_id} in gradle.properties and the id in neoforge.mods.toml. */
    public static final String MODID = "moddingfromamod";

    private static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public ModdingFromAModMod(IEventBus modEventBus, ModContainer container) {
        // Private plug-ins first: the core reads their hooks from here on, and behaves as the public
        // single-player mod when none are listed.
        com.moddingfromamod.plugin.Plugins.shared().load(ModdingFromAModMod.class.getClassLoader(), LOGGER::info);
        // whose creature a tag names, for the reach rule — the tags of the creatures a player owns.
        com.moddingfromamod.world.BaseReach.hisCreatureTags = caster -> {
            String id = caster.toString();
            java.util.Set<String> tags = new java.util.HashSet<>();
            for (var sp : com.moddingfromamod.species.SpeciesRegistry.all()) {
                if (id.equals(sp.ownerId())) {
                    tags.addAll(com.moddingfromamod.vanilla.DisplayRig.speciesTags(sp.id()));
                }
            }
            return tags::contains;
        };
        // D28: a dedicated server's settings come from config/moddingfromamod-server.toml. Only there: a
        // single-player game never grows the file.
        if (net.neoforged.fml.loading.FMLEnvironment.getDist() == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER) {
            container.registerConfig(net.neoforged.fml.config.ModConfig.Type.LOCAL,
                    com.moddingfromamod.config.ServerConfigFile.SPEC, com.moddingfromamod.config.ServerConfigFile.FILE);
        }
        // the local logs that can hold a player's words are pruned after the privacy notice's period, now and
        // daily (a server's settings override may turn it off when it prunes them another way).
        com.moddingfromamod.runtime.LogRetention.start();
        // ── Registration (mod bus) ────────────────────────────────────────────
        com.moddingfromamod.content.Found.use(com.moddingfromamod.content.ContentIndexes::live);   //
        ModItems.register(modEventBus);
        // The GameTest harness (GameTests, per-test timings, the behaviour exercise) when this build carries it; a shipped
        // jar does not, and then this does nothing.
        com.moddingfromamod.seam.TestHarness.install(modEventBus);
        com.moddingfromamod.entity.ModEntities.register(modEventBus);
        com.moddingfromamod.paint.ModPaint.register(modEventBus);
        modEventBus.addListener(com.moddingfromamod.net.ModNetwork::register);
        // A plug-in's own mod-bus listeners, e.g. a service-client gate (D14) and join timings.
        for (var w : com.moddingfromamod.plugin.Plugins.shared().all(com.moddingfromamod.plugin.BusWiring.class)) {
            w.modBus(modEventBus);
        }
        // The agent endpoint (Claude connects here). Process-lifetime, started at common setup.
        modEventBus.addListener(com.moddingfromamod.agent.WishAgent::onCommonSetup);


        // ⚠ THE CAMERA IS DECLARED A NON-CHILD HERE, at construction, and the timing is the point.
        // An offline-mode UUID is derivable from the username alone, so the agent's camera client
        // can be marked "not a child" while it is still a string in a launch script —
        // BEFORE it joins. Anything that learned the role from a handshake would leave it a child
        // for its first few ticks, which is exactly the window a tool asks in.
        // Empty means no camera, which is the singleplayer case and needs no branch anywhere else.
        String cameraName = com.moddingfromamod.runtime.RuntimeConfig.cameraName();
        if (!cameraName.isEmpty()) {
            // EVERY body the pool could run, not only the ones it runs today — the
            // camera.concurrent knob can raise the pool without a restart, and a body registered
            // late is a child for its first ticks. One stem, one keypair (D14): N names, not N keys.
            for (String name : com.moddingfromamod.wish.CameraPool.names(cameraName,
                    com.moddingfromamod.wish.CameraPool.MAX)) {
                com.moddingfromamod.marshal.PlayerKind.registerServiceClient(
                        com.moddingfromamod.marshal.PlayerKind.offlineId(name));
            }
            LOGGER.info("camera client '{}' (and its pool, up to {} bodies) is registered as the "
                    + "mod's own, not a child", cameraName, com.moddingfromamod.wish.CameraPool.MAX);
        }

        // ── Gameplay handlers (game bus) ──────────────────────────────────────
        // one line at boot saying what shape of server this is and who can join it.
        // A REPORT, deliberately not a switch -- see Deployment's javadoc and docs/DEPLOYMENTS.md.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.net.Deployment.class);
        // the dedicated server's settings, read from its file as it starts (D28).
        NeoForge.EVENT_BUS.register(com.moddingfromamod.config.ServerSettings.class);
        // A plug-in's own game-bus handlers, here where the first of them always went (a dedicated
        // server's guards, operator commands, health thread, AFK kick and join timings live in plug-ins).
        for (var w : com.moddingfromamod.plugin.Plugins.shared().all(com.moddingfromamod.plugin.BusWiring.class)) {
            w.gameBus(NeoForge.EVENT_BUS);
        }
        // (ServiceClientGate has no game-bus listener any more: it reads the joining name in
        // registerTasks, on the mod bus above — PlayerNegotiationEvent is never fired.)
        // The server list's "Trouble joining?" line (the not-on-the-list refusal is a mixin).
        NeoForge.EVENT_BUS.register(com.moddingfromamod.net.JoinHelp.class);
        // /wish + its lifecycle cleanup, and the tick-driven wish executor.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.WishCommands.class);
        // A world's chat sweep (its manifest's `people.chatSweep`) and the chat memory a
        // /moddy report attaches.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.chat.ChatGuard.class);
        // /moddyop filmcam's keyframed camera path, evaluated every server tick.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.film.FilmPathPlayer.class);
        // B0b: the Wish Builder's commands are branches of /moddy and /moddyop, so nothing to register here.
        com.moddingfromamod.marshal.WatchPanel.builderOn =
                () -> com.moddingfromamod.wishbuilder.BuilderSettings.current().enabled();
        // The wish sweeper: settles a stuck wish; drives itself unless a plug-in's health thread does.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.health.WishSweeper.class);
        // a TNT chain must never stop the server (the watchdog crash-loop of 2026-09-27).
        NeoForge.EVENT_BUS.register(com.moddingfromamod.mob.PrimedTntLimit.class);
        // the same backstop for WISHED creatures, per class (SpawnBudget).
        NeoForge.EVENT_BUS.register(com.moddingfromamod.mob.WishMobLimit.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.WishProgress.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.WishCapture.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.WatchSnapshot.class);
        // A client that joins after a sculpt has to be told where the pack is too.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.vanilla.RigReveal.class);
        // the ITEM artwork, which nothing had ever pushed. A client with the mod resolves
        // it out of the jar, so this was invisible on every dev client while every wished thing --
        // and Moddy himself, the button the zero-typing path is built on -- rendered as a leather
        // horse armour for a child with no mod. Separate from the rig pack because the lifecycles
        // are: this is static and goes out at JOIN; a rig pack exists only once a creature does.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.vanilla.ItemPackService.class);
        // a wish pack switched off stays off across a restart (seeded from level.dat).
        NeoForge.EVENT_BUS.register(com.moddingfromamod.datapack.DatapackReloader.class);
        // The camera client's process lifecycle: start one when a child arrives, stop it
        // when the last child leaves AND the last wish drains. Inert on a singleplayer world and
        // when no camera name is configured -- see CameraSupervisor for both gates.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.CameraSupervisor.class);
        // #515b: while the stock child is being withheld from, he is not shown the half-built rig
        // either -- otherwise he watches forty paper cards pile up. Needs the tick to answer a
        // re-pairing when he walks back into range; see RigVeilService.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.vanilla.RigVeilService.class);
        // The camera is not a person in the child's world: settled who may be acted on and
        // photographed, and never asked who may be SEEN. See CameraPresence.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.CameraPresence.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.builder.BuildCommand.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.mob.WishSummonCommand.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.datapack.WishDatapackCommand.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.builder.Builder.class);
        // 1.21.9 removed spawn chunks: an action that falls back to the world spawn holds a short
        // ticket there, refreshed and let go from the server tick. See place/SpawnHold.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.place.SpawnHold.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.WishExecutor.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.ServedFanfare.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.agent.AgentBridge.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.agent.WishAgent.class);
        // Gate D with no client: a real agent granting a real wish on a dedicated server.
        // REGISTERED ONLY WHEN ASKED FOR. Gating here rather than inside the handler means the
        // class is not on the bus at all in normal play, so it cannot cost a tick or fire by
        // accident — the probe submits a wish and then HALTS THE SERVER, which is not something to
        // leave one un-set system property away from a child's game.
        if (com.moddingfromamod.agent.HeadlessWishProbe.active()) {
            NeoForge.EVENT_BUS.register(com.moddingfromamod.agent.HeadlessWishProbe.class);
        }
        // what a STOCK client actually gets, measured while one is connected.
        // Same gating and the same reason as the probe above: a class that is not on the bus
        // cannot cost a tick or fire by accident in a child's game. Unlike them it halts nothing,
        // but it does hand out an item, which is not something to leave one unset property away.
        // Can the two of them see each other? A probe, not a feature: it teleports both players
        // and sculpts a creature, so it is registered only when a signal directory names it.
        if (com.moddingfromamod.wish.SightProbe.active()) {
            NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.SightProbe.class);
        }
        // The real-client arm: two bound wishes orbit two creatures at once through two real
        // cameras. Same rule — it spawns creatures and moves cameras, so only when named.
        if (com.moddingfromamod.wish.PoolProbe.active()) {
            NeoForge.EVENT_BUS.register(com.moddingfromamod.wish.PoolProbe.class);
        }
        // an earlier issue's Gate C: stage a stock client's hotbar and his wish panel so the icons can be
        // PHOTOGRAPHED. It rewrites a player's inventory and opens a screen on him, so it is one
        // unset property away from nothing rather than from a child's game.
        if (com.moddingfromamod.lite.StockIconProbe.active()) {
            NeoForge.EVENT_BUS.register(com.moddingfromamod.lite.StockIconProbe.class);
        }
        if (com.moddingfromamod.lite.LiteProbe.active()) {
            NeoForge.EVENT_BUS.register(com.moddingfromamod.lite.LiteProbe.class);
        }
        // /wishmob — spawn/list/reload/persist Blockbench-sculpted custom-geometry creatures.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.runtime.WishMobCommands.class);
        // /paint — put a picture on a panel. The seam the `paint` wish tool sits on.
        NeoForge.EVENT_BUS.register(com.moddingfromamod.paint.PaintCommand.class);
        NeoForge.EVENT_BUS.register(com.moddingfromamod.paint.PictureCommand.class);

        // ── Client-only wiring ────────────────────────────────────────────────
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            // ClientRenderers.register(modEventBus);
        }

        LOGGER.info("{} loaded", MODID);
    }
}
