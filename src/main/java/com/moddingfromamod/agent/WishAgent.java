package com.moddingfromamod.agent;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.AddTemplateTool;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.agent.tools.DefineSpeciesTool;
import com.moddingfromamod.agent.tools.FindContentTool;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.agent.tools.ListWishesTool;
import com.moddingfromamod.agent.tools.LookAroundTool;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.agent.tools.ModifyPlayerTool;
import com.moddingfromamod.agent.tools.NotifyPlayerTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.wish.LearnedTemplates;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Wires the agent endpoint into the mod's lifecycle.
 *
 * <p>The HTTP server starts at <b>common setup</b> and lives for the whole process, deliberately
 * outliving individual worlds — see {@link McpHttpServer} for why. Only the learned-wish set is
 * per-world, so it loads on server start and resets on stop.
 */
public final class WishAgent {

    /** {@code -Dmoddingfromamod.mcp.port=0} gives an ephemeral port (tests, or a second game). */
    private static final String PORT_PROPERTY = ModdingFromAModMod.MODID + ".mcp.port";

    private WishAgent() {
    }

    /**
     * The tool set, in the order the agent reads it.
     *
     * <p>Order is not cosmetic. An agent works down the list looking for the first tool that could
     * plausibly satisfy the wish, so this encodes the house strategy: <b>find what's installed →
     * summon the real thing → adjust the real thing</b>, with inventing a creature from vanilla
     * parts placed last, where a last resort belongs. When {@code define_creature} sat higher, a
     * child who asked for a wither storm got a black spider in a wither-skeleton costume while the
     * mod providing the real boss was installed the whole time.
     */
    public static ToolRegistry buildTools() {
        return new ToolRegistry()
                // First, because it IS the agent's main loop: park here, act, come back.
                .add(new AwaitWishTool())
                .add(new ListWishesTool())
                .add(new FindContentTool())
                .add(new LookAroundTool())
                // NAVIGATION, not creation — and it sits HERE, with the orientation verbs
                // and ahead of every building verb, because routing is emergent from the order the
                // tools are offered in. "Take me to my castle" is the same shape of question as
                // "what is around me", and it must be seen before build_* or the agent reaches for
                // the manifesting verb it saw first. That is not hypothetical: asked to take him
                // to the closest McTrain, the agent called list_trains, got the right
                // coordinates, and then built a SECOND McTrain at the child's feet 2,900 blocks
                // away — and the wish was marked granted, so no completion metric saw it.
                // 2 of the 9 distinct train wishes he has ever typed are navigation.
                .add(new com.moddingfromamod.agent.tools.TakeMeToTool())
                // Before sculpting anything real: what does it ACTUALLY look like? The agent has
                // no web access of its own (it can write unguarded datapacks into a live world),
                // so this is the only route a reference gets in, and it arrives as enums.
                .add(new com.moddingfromamod.agent.tools.LookUpCharacterTool())
                // Before building anything: what already exists, and what the watch already knows.
                // High in the order because "add a lighthouse to it" must resolve the EXISTING
                // castle rather than reflexively building a second one somewhere else.
                .add(new com.moddingfromamod.agent.tools.ListBuildsTool())
                // …and READ one before changing it. list_builds says a castle exists; this says
                // what it is made of and names its shapes, which is what an edit needs. Without
                // it, "put a dome on top of it" was answered by building a SECOND building in
                // every arm-B ladder cell measured.
                .add(new com.moddingfromamod.agent.tools.InspectBuildTool())
                .add(new com.moddingfromamod.agent.tools.ListTemplatesTool())
                .add(new com.moddingfromamod.agent.tools.ListDatapacksTool())
                // the READY-MADE library's search, ahead of the ready-made verbs it points
                // at. Only in an arm that switches a kind's search on — off, the tool list is the one
                // that shipped before it.
                .addIf(!com.moddingfromamod.readymade.ReadyMadeConfig.searches().isEmpty(),
                        new com.moddingfromamod.agent.tools.FindReadyMadeTool())
                .addIf(!com.moddingfromamod.readymade.ReadyMadeConfig.searches().isEmpty(),
                        new com.moddingfromamod.agent.tools.DescribeReadyMadeTool())
                // READY-MADE ABILITIES. Registered next to the datapack tools on purpose: it is
                // the cheap path to the same outcome, and an agent that never sees it will author
                // from scratch every time -- which is the 112s median this exists to shorten.
                .addIf(!"off".equals(System.getProperty(
                                ModdingFromAModMod.MODID + ".effectCatalogue")),
                        new com.moddingfromamod.agent.tools.ApplyEffectTool())
                // READY-MADE THINGS, beside ready-made abilities for the same reason: an agent that
                // never sees it authors an item from scratch every time. The A/B arm switch mirrors
                // the effect catalogue's so the control arm is the agent WITHOUT the catalogue
                // rather than a different agent.
                .addIf(!"off".equals(System.getProperty(
                                ModdingFromAModMod.MODID + ".itemCatalogue")),
                        new com.moddingfromamod.agent.tools.MakeItemTool())
                // AND A WAY TO SEE WHAT HE ALREADY HAS. An agent cannot edit what it cannot read
                // back, so without this the only correct-looking answer to "make it green" is to
                // describe a whole new wand — which is exactly what eight ladder cells measured.
                //
                // ⚠ list_items comes BEFORE inspect_item on purpose. inspect_item answers
                // "tell me about this id", which is no use to a wish that never names one — and
                // "and one that shoves the noob in LAVA" never does. Items was the only class with
                // a store and no list at all, so that wish reached an agent with nothing to
                // retrieve from. Order matters here for the reason recorded above the navigation
                // verbs: routing is emergent from the order the tools are offered in.
                .addIf(!"off".equals(System.getProperty(
                                ModdingFromAModMod.MODID + ".itemCatalogue")),
                        new com.moddingfromamod.agent.tools.ListItemsTool())
                .addIf(!"off".equals(System.getProperty(
                                ModdingFromAModMod.MODID + ".itemCatalogue")),
                        new com.moddingfromamod.agent.tools.InspectItemTool())
                // Same gate as inspect_item: with the catalogue off there is nothing to look at and
                // nothing to take back.
                .addIf(!"off".equals(System.getProperty(
                                ModdingFromAModMod.MODID + ".itemCatalogue")),
                        new com.moddingfromamod.agent.tools.RemoveItemTool())
                // The spine of a wish, in the order it happens: plan it, build it, finish_building it, judge
                // it. plan_wish sits high because it is the FIRST thing a building wish does.
                .add(new com.moddingfromamod.agent.tools.PlanWishTool())
                // Beside plan_wish: the agent decides what a wish is about, and asks him only when a
                // wrong pick would destroy something.
                .add(new com.moddingfromamod.agent.tools.AskWhichTool())
                .add(new com.moddingfromamod.agent.tools.FinishBuildingTool())
                .add(new com.moddingfromamod.agent.tools.ListCreaturesTool())
                // Right after list_creatures: knowing a creature EXISTS is not knowing its shape,
                // and every session but the sculpting one starts blind about where its mouth is.
                .add(new com.moddingfromamod.agent.tools.InspectCreatureTool())
                .add(new com.moddingfromamod.agent.tools.RemoveCreatureTool())
                .add(new com.moddingfromamod.agent.tools.RemoveBuildTool())
                .add(new com.moddingfromamod.agent.tools.GiveItemsTool())
                .add(new RunCommandTool())
                .add(new ModifyMobTool())
                // Before modify_player for the same reason inspect_creature sits before the
                // sculpting verbs: "make me EVEN stronger" is unanswerable without the level he is
                // already on, and the agent that granted it in an earlier session is the only one
                // that knew. Measured 6/6 vs 0/6 on exactly that rung.
                .add(new com.moddingfromamod.agent.tools.InspectPlayerTool())
                .add(new ModifyPlayerTool())
                .add(new com.moddingfromamod.agent.tools.BuildStructureTool())
                .add(new com.moddingfromamod.agent.tools.ConjureAtTool())
                .add(new com.moddingfromamod.agent.tools.SetStatusTool())
                .add(new NotifyPlayerTool())
                .add(new AddTemplateTool())
                .add(new FinishWishTool())
                // Inventing a creature, in the order to try it. Both sit below find_content and
                // run_command, because a child who asks for a wither storm wants the real boss — but
                // SCULPTING outranks costuming, because costuming is what turned a request for
                // Chucko McBurger into a blood-red zombie and a pig-man. Every humanoid skin the
                // game ships is a monster, and a tint can only DARKEN, so a cheerful character is
                // unreachable that way no matter how well the agent reasons about it.
                .add(new com.moddingfromamod.agent.tools.SculptCreatureTool())
                // Fix a colour without re-sculpting — the commonest follow-up a child has.
                .add(new com.moddingfromamod.agent.tools.PaintCreatureTool())
                // A real PICTURE on a surface. Registered near the creature painter
                // because it answers the neighbouring question — a colour on a creature, a
                // picture on a wall — and because a wish that asks for a face on something
                // must not be answered with coloured blocks: that is nine pixels.
                // READ A PICTURE BACK BEFORE CHANGING IT — list before paint, for the same
                // reason list_items comes before make_item: "make the sun bigger" names no
                // id, and an agent with nothing to retrieve from redraws the picture from nothing.
                .add(new com.moddingfromamod.agent.tools.ListPaintingsTool())
                .add(new com.moddingfromamod.agent.tools.InspectPaintingTool())
                .add(new com.moddingfromamod.agent.tools.PaintSurfaceTool())
                .add(new com.moddingfromamod.agent.tools.RemovePaintingTool())
                // STAGED EVENTS: a wave, a flood, a meteor — something that HAPPENS to the
                // world and gives it back. List and inspect come first for the same reason as the
                // pictures above: "make the wave bigger" names no id.
                .add(new com.moddingfromamod.agent.tools.ListEventsTool())
                .add(new com.moddingfromamod.agent.tools.InspectEventTool())
                .add(new com.moddingfromamod.agent.tools.StageEventTool())
                .add(new com.moddingfromamod.agent.tools.RemoveEventTool())
                // And LOOK at the result. Every other check is a number, and none of them can tell
                // whimsical from terrifying; asked for Chucko McBurger this system produced a
                // blood-red zombie and reported success, because nothing ever looked at it.
                .add(new com.moddingfromamod.agent.tools.PhotographTool())
                // Below sculpting: nothing installed fits AND the creature is vague enough
                // ("a big bouncy slime") that a costumed vanilla look is fine and instant.
                .add(new DefineSpeciesTool())
                // Tier 1 escape hatch — genuinely new mechanics as a CBScript datapack. Registered
                // AFTER everything else because it is the most powerful and the most effort: only
                // reach for it when no summon / modify_mob / build / creature can express the wish.
                // A READY-MADE RULE before the escape hatch: "zombies drop diamonds" is one
                // call here, and a hand-written loot table in a datapack would REPLACE the zombie's
                // own drops rather than add to them.
                .add(new com.moddingfromamod.agent.tools.ChangeDropsTool())
                // "When X happens, do Y", forever: one call instead of a tick-loop datapack.
                .add(new com.moddingfromamod.agent.tools.SetRuleTool())
                .add(new com.moddingfromamod.agent.tools.CbScriptHelpTool())
                .add(new com.moddingfromamod.agent.tools.ReloadDatapacksTool())
                .add(new com.moddingfromamod.agent.tools.RemoveDatapackTool())
                .add(new com.moddingfromamod.agent.tools.VerifyDatapackTool())
                .add(new com.moddingfromamod.agent.tools.AuthorDatapackTool());
    }

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // Config/system properties are read HERE, not in a static initialiser — reading them at
        // class-init time is the classic way to break a headless server boot.
        // A test run defaults to an OS-assigned ephemeral port, never the live-game DEFAULT_PORT —
        // so a client test running the real agent can't fight a child's live session for 25585 (the
        // incident that swallowed his "blue lava" wish), and any number of concurrent test JVMs
        // (multiple branches building at once) each get their own free port. An explicit
        // -Dmoddingfromamod.mcp.port still wins, so this only changes the DEFAULT.
        int defaultPort =
                McpHttpServer.defaultPortFor(com.moddingfromamod.RunContext.isTestRun());
        int port = Integer.getInteger(PORT_PROPERTY, defaultPort);
        event.enqueueWork(() -> {
            McpHttpServer.start(buildTools(), port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // Order matters: stop the agent BEFORE the endpoint, so its parked await_wish
                // returns cleanly instead of dying on a refused connection.
                AgentSession.stop();
                McpHttpServer.stop();
            }, "wish-mcp-shutdown"));
        });
    }

    /**
     * <b>A child must never be shown command output.</b>
     *
     * <p>Every wish is granted with admin commands, and vanilla broadcasts admin command usage to
     * every operator — which he is. Reported from a live session as a wall of white text across the
     * lower half of the screen while he was trying to play:
     *
     * <pre>
     * [Butt Cheek Water Bottle: Teleported Butt Cheek Water Bottle to -1767.5, 6.958008, 1005.59]
     * [Butt Cheek Water Bottle: Displaying particle minecraft:portal]
     * [Butt Cheek Water Bottle: Running function metal_grinder:convert]
     * </pre>
     *
     * <p>That was a repeating command block firing every tick, but the source hardly matters: the
     * machinery of granting a wish is not something he should be reading.
     *
     * <p><b>{@code logAdminCommands}, deliberately, and NOT {@code sendCommandFeedback}.</b> The
     * first is the broadcast to other operators — the thing on his screen. The second is the reply
     * to whoever ran the command, which {@code run_command} and the datapack verifier read back to
     * decide whether a wish worked; switching that off would blind the agent to its own results and
     * cost far more than it saved.
     *
     * <p>Only ever turned OFF, and only when it is on: a grown-up who deliberately re-enables it in
     * their own world will have it switched off again next load, which is a small enough surprise
     * to accept against a screen a child cannot see past.
     */
    private static void quietenAdminCommandSpam(net.minecraft.server.MinecraftServer server) {
        try {
            var rules = server.getGameRules();
            if (rules.getBoolean(net.minecraft.world.level.gamerules.GameRules.RULE_LOGADMINCOMMANDS)) {
                rules.getRule(net.minecraft.world.level.gamerules.GameRules.RULE_LOGADMINCOMMANDS)
                        .set(false, server);
                org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                        .info("turned logAdminCommands off — a child should not be reading command "
                        + "output while he plays");
            }
        } catch (RuntimeException e) {
            // Never let a comfort setting stop a world loading.
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                    .debug("could not quieten admin command output: {}", e.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // FIRST, before anything resolves a path: everything a wish makes now lives under the world
        // that made it (see WorldPaths). Models and datapacks used to be shared across every world,
        // which is how re-sculpting Spikor in one world silently rewrote another world's Spikor.
        com.moddingfromamod.runtime.WorldPaths.open(event.getServer());
        // (The runtime knobs, are a server's own: a plug-in installs them as the server starts,.)
        // Teach the wake channel what a wish is ABOUT, so WishAffinity can route on it. Wired here
        // rather than baked in because WishQueue is deliberately Minecraft-free and the resolver
        // reads this world's handover store — which does not exist until the line above has run.
        WishQueue.global().resolvingSubjectsWith(WishAgent::subjectOf);
        // D10: the queue caps each child at FairShare.perChild() wishes in flight, counted
        // off the Marshal's WORKING runs — the one place that knows which are being granted.
        WishQueue.global().countingWorkWith(player -> {
            java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
            for (var r : com.moddingfromamod.marshal.Marshal.global().working()) {
                if (java.util.Objects.equals(r.playerId(), player)) {
                    ids.add(r.wishId());
                }
            }
            return ids;
        });
        quietenAdminCommandSpam(event.getServer());
        // A world that predates this keeps what the child already made, as its own private copy.
        com.moddingfromamod.runtime.WorldPaths.migrateLegacyGlobalData();
        // BEFORE the learned set, because loading that rebuilds the active templates and the rebuild
        // folds the library in (library > learned > built-in). The other order leaves a window where
        // the curated wishes are on disk and not being served.
        com.moddingfromamod.library.LibraryService.open(event.getServer());
        LearnedTemplates.load(event.getServer());
        com.moddingfromamod.species.SpeciesRegistry.load(event.getServer());
        // a Builder or Apprentice creature saved before it had a behaviour gets the one its picks choose.
        com.moddingfromamod.species.SpeciesRegistry.backfillBehaviour(event.getServer(),
                com.moddingfromamod.wishbuilder.BuilderBehaviourBackfill::choose);
        // Things wishes have handed over, so they are still searchable in the creative menu a session
        // later — the item half of what SpeciesRegistry does for creatures.
        com.moddingfromamod.item.WishItemCatalogue.load(event.getServer());
        com.moddingfromamod.builder.BuildRegistry.load(event.getServer());
        // The names of the animals he has wished on. Without this, "make him even bigger"
        // after lunch is back to growing whichever mob happens to be nearest.
        com.moddingfromamod.mob.MobHandleStore.load(event.getServer());
        // Pictures painted on surfaces, so an undo a session later can still put one back.
        com.moddingfromamod.paint.PaintRegistry.load(event.getServer());
        // And their RECIPES — this world's and every child's shelf — so "make the sun on my
        // picture bigger" edits the picture instead of redrawing it, even in a world where it has
        // never hung.
        com.moddingfromamod.paint.PictureStore.load();
        // WHICH MODE MINTED THE THINGS IN THIS WORLD. Loaded early and LOGGED, because the
        // failure it exists for is a world minted on the substituted path and opened without it --
        // the items are all still there and are quietly not the things they were, and nothing else
        // in a world load would say so.
        com.moddingfromamod.lite.MintStore.load(event.getServer());
        // The DESIGNS behind those items, so a refinement days later can change one field of one
        // rather than making a second wand. Without this the store is empty on every world load and
        // start_from silently degrades back into make-a-new-one.
        com.moddingfromamod.item.ItemSpecStore.load();
        // Pull every previously-built CBScript datapack into this world so the accumulated wish
        // features are present instantly and offline — the Tier-1 equivalent of learned templates.
        // Skipped in the gates so test worlds stay deterministic; a GameTest drives DatapackForge
        // directly instead. Never let a datapack problem abort world load.
        // Reconcile the Moddy branch of the Mod Repo up front (opt-in git-sync; no-op if disabled).
        com.moddingfromamod.provenance.GitSync.syncAtStartup();
        if (!com.moddingfromamod.RunContext.isTestRun()) {
            try {
                com.moddingfromamod.datapack.DatapackStore.installAllIntoWorld(event.getServer());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                        .warn("could not install saved datapacks into the world: {}", e.toString());
            }
        }
        // Start the agent as the world opens, not on the first wish: start-up costs ~40s (measured),
        // and paying it here means it is already parked on await_wish before anyone reaches the
        // watch. SINGLEPLAYER only: a dedicated server opens empty, a pool
        // booted there holds a sleeping server awake for the whole reap grace after every restart,
        // and the first join boots it anyway (onPlayerJoin, below). IdlePolicy.bootAtWorldOpen.
        if (com.moddingfromamod.marshal.IdlePolicy.bootAtWorldOpen(event.getServer().isDedicatedServer())) {
            AgentSession.wake("the world opening");   //: off the server thread, as at a join
        } else {
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID).info(
                    "wish pool not started as the world opens: a dedicated server opens empty, and the "
                    + "first join (or wish) starts it");
        }
    }

    /**
     * <b>What a wish is about, for routing</b> — named first, referred-to second.
     *
     * <p>Rung 0 alone is not enough and the first real ladder run proved it: every routing decision
     * came back with no artifact, so every arm collapsed to {@code anyone} and A and B were the
     * same experiment. A child refining a thing says "him", and rung 0 resolves only wishes that
     * NAME something — which is to say, not the ones affinity exists for.
     *
     * <p>So a wish that {@link com.moddingfromamod.marshal.RefersBack refers back} and names nothing
     * is taken to mean the last thing this child finished. That is what "him" means in practice, and
     * it is the only source available at routing time: {@code WishResolver}'s stickiness map is
     * never written to by any production path.
     *
     * <p>Never throws. Routing must never be able to break a wake — an unresolvable subject just
     * means "nothing owns this", which is the same answer a creation gives.
     */
    static String subjectOf(com.moddingfromamod.wish.Wish wish) {
        return bindingOf(wish).subjectId();
    }

    /**
     * <b>The subject AND how it was arrived at</b>.
     *
     * <p>This used to return "" whenever the wish had no referring word, which is why <i>"put a
     * flag on the very top"</i> routed {@code anyone}: {@code RefersBack} is a gate on grammar, and
     * a building has parts you can point at, so the child names the part. Measured 11/16 for
     * buildings against 16/16, 8/8, 12/12 and 4/4 for every other class — and machines have exactly
     * that shape, so they would have hit the same wall.
     *
     * <p><b>Both signals are returned, never one substituted for the other.</b> A RECENT binding is
     * a convenience and must not be counted as evidence that the grammar worked; see
     * {@link com.moddingfromamod.marshal.SubjectBinding} for the whole argument, which is Dad's,
     * stated twice.
     */
    static com.moddingfromamod.marshal.SubjectBinding.Binding bindingOf(
            com.moddingfromamod.wish.Wish wish) {
        if (wish == null) {
            return new com.moddingfromamod.marshal.SubjectBinding.Binding(
                    "", com.moddingfromamod.marshal.SubjectBinding.How.NONE);
        }
        try {
            String named = com.moddingfromamod.wish.HandoverBrief.artifactFor(wish.text());
            boolean refersBack = com.moddingfromamod.marshal.RefersBack.yes(wish.text());
            // THE RECENT THING, and which source answers it matters. The sticky subject is what
            // made a live production signal — the comment this code used to carry ("never
            // written to by any production path") was stale — and it is the only one of the two
            // carrying a time, which is what the window needs. Marshal's last finished artifact is
            // the fallback for a refer-back, which is not window-limited.
            String sticky = com.moddingfromamod.wish.WishTopic.lastOf(wish.playerId());
            long age = com.moddingfromamod.wish.WishTopic.ageMs(wish.playerId());
            String recent = sticky == null || sticky.isBlank()
                    ? com.moddingfromamod.marshal.Marshal.global()
                            .lastArtifactFor(wish.playerId())
                    : sticky;
            // No sticky means no age, and a refer-back does not need one. A recency binding with
            // no measurable age is refused by the window rather than allowed through it.
            long ageForWindow = sticky == null || sticky.isBlank() ? -1L : age;
            return com.moddingfromamod.marshal.SubjectBinding.decide(
                    named, refersBack, recent, ageForWindow);
        } catch (RuntimeException e) {
            return new com.moddingfromamod.marshal.SubjectBinding.Binding(
                    "", com.moddingfromamod.marshal.SubjectBinding.How.NONE);
        }
    }

    /**
     * A joining player needs the creature definitions before it can draw any of them — without this
     * a reconnecting player sees every wish creature as a default humanoid.
     */
    @SubscribeEvent
    public static void onPlayerJoin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // HIS SHELF FIRST: if he made things on the other server since this one last looked at
            // his shelf, reload them before the three snapshots below tell him what he has.
            com.moddingfromamod.store.PlayerShelves.refreshFor(player.level().getServer(),
                    player.getUUID().toString());
            com.moddingfromamod.net.ModdedClient.sendSpecies(player);
            // Moddy's buttons lead with what he most recently made, so they have to arrive too —
            // and since per-child ownership "he" is this child rather than the world, so the set is built for him.
            com.moddingfromamod.net.ModdedClient.sendPresets(player);
            // And the things he has been given: the creative menu is built client-side, so without
            // this a reconnecting player cannot search for anything a past wish handed over.
            com.moddingfromamod.net.ModdedClient.sendWishItems(player);
            resumeInterruptedWishes(player);
            // AND START THE POOL, if an earlier issue's reaper stopped it while the world was empty.
            //
            // This is what makes reaping free at the child's end. The ~10-13 s launch happens
            // behind the loading screen, so by the time they have found the watch a worker is
            // parked on await_wish exactly as before — which is the promise AgentSession's own
            // class comment makes and the one thing reaping could have broken.
            //
            // OFF THE SERVER THREAD. This used to call AgentSession.start here, inline: it
            // writes the MCP config, reaps finished CLI sessions and launches a process, and the
            // live log showed the server thread spending ~1.7 s in it on every first join (and on
            // the camera's), then "Can't keep up! … 2164ms" — the world frozen for the child who
            // had just arrived. wake() runs the same start on a daemon thread, one at a time, and
            // is a field read when the pool is up. It also touches the activity clock (step 4):
            // a join is ACTIVITY, so a child joining a quiet pool does not find it reaped moments
            // later. A wish made before the start finishes is queued (WishQueue) and the child is
            // told Moddy is waking up (wakeForWish) — the same path a reaped pool always took.
            AgentSession.wake("a join");
        }
    }

    /**
     * Re-offer any wish that was mid-flight when the world last closed (flagged {@link #INTERRUPTED_NOTE}
     * on stop) so unfinished work isn't silently lost. Capped and cleared-once so a rejoin can't
     * replay the same wish twice or flood the agent with an old backlog.
     */
    private static void resumeInterruptedWishes(net.minecraft.server.level.ServerPlayer player) {
        try {
            com.moddingfromamod.wish.WishBook book =
                    com.moddingfromamod.wish.WishBook.get(player.level().getServer());
            java.util.List<com.moddingfromamod.wish.Wish> resumable = book.pending().stream()
                    .filter(w -> player.getUUID().equals(w.playerId()))
                    .filter(w -> INTERRUPTED_NOTE.equals(w.result()))
                    .sorted(java.util.Comparator.comparingLong(com.moddingfromamod.wish.Wish::createdMs))
                    .limit(RESUME_MAX)
                    .toList();
            for (com.moddingfromamod.wish.Wish w : resumable) {
                // Swap the internal marker for a note the AGENT can act on. Clearing it outright —
                // which is what this used to do — handed a half-built wish back looking brand new,
                // so the agent started again from scratch: on 2026-08-04 a quit mid-wish produced a
                // SECOND Spikor, then spent minutes hunting for which one was "its" one.
                // describe() renders result() as a ⚠ line, so this is the channel that already
                // exists for telling the agent why a wish came back (rejections use it too).
                com.moddingfromamod.wish.Wish clean = markResumed(book, w);
                WishQueue.global().offer(clean);
                com.moddingfromamod.wish.WishProgress.started(player, clean);
            }
            if (!resumable.isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                        .info("resumed {} interrupted wish(es) for {}", resumable.size(),
                                player.getName().getString());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "🪄 I didn't finish one of your wishes last time — I'm on it now!"));
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                    .warn("could not resume interrupted wishes: {}", e.toString());
        }
    }

    /** Marker on a wish that was mid-flight when the world closed, so it can be resumed on rejoin. */
    private static final String INTERRUPTED_NOTE = "__interrupted__";

    /**
     * What a resumed wish tells the agent. Public so the test that guards this can assert the agent
     * is actually warned rather than just that a wish came back.
     *
     * <p>The whole point: a resumed wish is NOT a new one. Handed back clean, the agent rebuilds
     * from scratch — which is how quitting mid-wish left a child with two Spikors and an agent that
     * could no longer tell which was which.
     */
    public static final String RESUME_NOTE =
            "YOU ALREADY STARTED THIS WISH before the world closed, so some or all of it may "
            + "ALREADY EXIST. Check first — list_creatures, list_datapacks, list_builds, and an "
            + "@e[tag=wishmob_<id>] search — and do NOT rebuild anything that is already there. "
            + "If what you find looks finished, go straight to finish_building and finish_wish.";
    /** Cap on how many interrupted wishes we replay for a player on join, so a backlog can't flood. */
    private static final int RESUME_MAX = 5;

    /**
     * Re-open an interrupted wish carrying the warning, and hand back the updated record.
     *
     * <p>A separate method purely so a test can drive the REAL transformation. Asserting the text of
     * RESUME_NOTE proves nothing — the bug was never a bad note, it was that no note was attached at
     * all — so the guard has to exercise the same call the resume path makes.
     */
    public static com.moddingfromamod.wish.Wish markResumed(
            com.moddingfromamod.wish.WishBook book, com.moddingfromamod.wish.Wish w) {
        return book.complete(w.id(), com.moddingfromamod.wish.WishStatus.PENDING, RESUME_NOTE)
                .orElse(w);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Any wish the agent was actively working (RUNNING) when the world closed is demoted back to
        // PENDING and flagged, so it isn't stuck RUNNING forever and can be resumed on rejoin instead
        // of being silently lost (the stomp wish, queued behind the fireball, vanished this way).
        try {
            com.moddingfromamod.wish.WishBook book =
                    com.moddingfromamod.wish.WishBook.get(event.getServer());
            for (com.moddingfromamod.wish.Wish w : book.recent(500)) {
                if (w.status() == com.moddingfromamod.wish.WishStatus.RUNNING) {
                    book.complete(w.id(), com.moddingfromamod.wish.WishStatus.PENDING, INTERRUPTED_NOTE);
                }
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID)
                    .warn("could not demote in-flight wishes on stop: {}", e.toString());
        }
        AgentSession.stop();
        // Last, after everything that might still resolve a world path: the world is closing, so
        // model/datapack lookups must stop pointing into a save that is no longer open.
        com.moddingfromamod.runtime.WorldPaths.close();
        com.moddingfromamod.runtime.ModelRepository.clearCache();
        WishQueue.global().clear();
        // One world's ownership must never route another world's wishes: artifact ids are per-world,
        // so a stale "spikor belongs to w2" would force a brand-new Spikor to a worker for no reason.
        com.moddingfromamod.marshal.ArtifactAffinity.global().clear();
        com.moddingfromamod.mob.EntityHandles.global().clear();
        com.moddingfromamod.library.LibraryService.close();
        LearnedTemplates.reset();
        com.moddingfromamod.species.SpeciesRegistry.clear();
        com.moddingfromamod.builder.BuildRegistry.clear();
        com.moddingfromamod.builder.Builder.clear();
    }
}
