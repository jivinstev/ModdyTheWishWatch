package com.moddingfromamod.test.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.MakeVerdict;
import com.moddingfromamod.wish.WatchMenu;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A world jar's answers, honoured by the core's real entry points</b> (#14, #63): {@link ContractWorld} stands in
 * for the registered {@link WorldMakes} ({@link Plugins#replaceForTest}) and each test presses through the path a
 * child's press takes: {@code BuilderMake.make} (every Builder and Apprentice make) and {@code WishService.submit} with
 * a recipe (a button, "Make another one", a hotbar fire). HIDDEN is said and made nowhere; LOCKED is said in the world's
 * words and made nowhere; FIT is made within the limits and told once; a thing on his watch the world hides is not on
 * this world's watch and one it locks shows the words, while his shelf is never written.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ContractWorldGameTest {

    private ContractWorldGameTest() {
    }

    private static ServerPlayer setUp(GameTestHelper helper, String name) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        WishService.forget(p);
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        BuilderMake.takeCard = false;
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        Plugins mine = new Plugins();
        new ContractWorld().register(mine);
        Plugins.shared().replaceForTest(WorldMakes.class, mine.first(WorldMakes.class).orElseThrow());
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            BuilderMake.takeCard = true;
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        return p;
    }

    private static String said(ServerPlayer p, StringBuilder into) {
        p.connection.getConnection().flushChannel();   // what was sent this tick, not only what a tick end flushed
        var channel = p.connection.getConnection().channel();
        if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            Object o;
            while ((o = embedded.readOutbound()) != null) {
                if (o instanceof ClientboundSystemChatPacket c) {
                    into.append(c.content().getString()).append('\n');
                }
            }
        }
        return into.toString();
    }

    private static BuilderSpec cats(String temper, int size, int count) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", size, List.of(),
                temper, count, "", "", Map.of());
    }

    /** How many of this creature stand, by the core's own selector (a spelling every Minecraft rung takes). */
    private static int tagged(GameTestHelper helper, String id) {
        var server = helper.getLevel().getServer();
        try {
            return server.getCommands().getDispatcher().execute("execute if entity "
                    + com.moddingfromamod.entity.WishMobTags.selector(id), server.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;   // none: "execute if" fails when nothing matches
        }
    }


    @GameTest(template = "empty_test", batch = "contract_world_hidden", timeoutTicks = 400)
    public static void aMakeTheWorldHidesIsSaidAndMadeNowhere(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontracthide");
        // a boss is hostile: this world does not have it
        BuilderMake.Result r = BuilderMake.make(p, cats("boss", 2, 1), BuilderAllowlist.REVIEWED).join();
        helper.assertTrue(r.outcome() == BuilderMake.Outcome.NOT_HERE && r.detail().equals(MakeVerdict.HIDDEN_LINE),
                Component.literal("a hidden make: " + r));
        helper.assertTrue(r.wishId() == null, Component.literal("no wish was started for it"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "contract_world_locked", timeoutTicks = 400)
    public static void aRecipeTheWorldLocksIsSaidInItsWordsAndRunsNothing(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractlock");
        var server = helper.getLevel().getServer();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String recipe = "gt_contract_lock_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        // its item is gone: the facts cannot be read, and this world locks what it cannot see
        with.add("{\"id\":\"" + recipe + "\",\"all\":[\"gtcontractlock" + tag + "\"],\"ownerId\":\"" + p.getStringUUID()
                + "\",\"commands\":[\"wishdatapack gt_nothing_" + tag + " give\"]}");
        LearnedTemplates.restoreForTest(with, server);
        MockPlayerNetwork.afterTest(helper, () -> LearnedTemplates.restoreForTest(had, server));
        WishService.Outcome o = WishService.submit(p, "gt contract lock", WishSource.PRESET, recipe);
        helper.assertTrue(o == WishService.Outcome.NOT_HERE, Component.literal("a locked recipe: " + o));
        StringBuilder chat = new StringBuilder();
        helper.succeedWhen(() -> helper.assertTrue(said(p, chat).contains(ContractWorld.LOCKED_WORDS),
                Component.literal("the world's words are said: " + chat)));
    }

    @GameTest(template = "empty_test", batch = "contract_world_fit_make", timeoutTicks = 40000)
    public static void aBuilderMakeIsFittedToTheWorldsLimitsAndToldOnce(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractfit");
        Set<String> had = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        // six big cats: this world makes at most three at once, no bigger than size step 3
        AtomicReference<BuilderMake.Result> result = new AtomicReference<>();
        BuilderMake.make(p, cats("friendly", 4, 6), BuilderAllowlist.REVIEWED).thenAcceptAsync(result::set);
        StringBuilder chat = new StringBuilder();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = result.get();
            helper.assertTrue(r != null, Component.literal("the make is still running"));
            helper.assertTrue(r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the fitted make: " + r));
            BuilderSpecStore.Made kept = null;
            for (var m : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
                if (!had.contains(m.id())) {
                    kept = m;
                }
            }
            helper.assertTrue(kept != null, Component.literal("the make is on his shelf"));
            BuilderSpec s = kept.read();
            helper.assertTrue(s.count() == 3 && s.size() <= 3,
                    Component.literal("made within the limits: count " + s.count() + ", size " + s.size()));
            helper.assertValueEqual(tagged(helper, kept.id()), 3, Component.literal("cats standing"));
            String words = said(p, chat);
            helper.assertTrue(words.contains("for this world"), Component.literal("he is told it was fitted: " + words));
        });
    }

    @GameTest(template = "empty_test", batch = "contract_world_fit_replay", timeoutTicks = 4000)
    public static void aReplayIsFittedAtFireAndTheWatchShowsTheWorldsView(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractreplay");
        var server = helper.getLevel().getServer();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Species wolf = Species.fromJson(com.google.gson.JsonParser.parseString("{\"id\":\"gt_contract_wolf_" + tag
                + "\",\"name\":\"Wolf\",\"powers\":[\"hostile\"],\"health\":10}").getAsJsonObject());
        MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(List.of(wolf)));
        List<String> had = LearnedTemplates.savedJsonForTest();
        MockPlayerNetwork.afterTest(helper, () -> LearnedTemplates.restoreForTest(had, server));
        Set<String> kept = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        String recipe = "gt_contract_fit_" + tag;

        // the watch: the wolf is hostile, so it is not on this world's watch; the bunny is
        WatchRow.Creation wolfTile = new WatchRow.Creation(wolf.id(), "species", "Wolf", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "");
        helper.assertTrue(WatchMenu.viewOf(p.getStringUUID(), wolfTile).hidden(),
                Component.literal("a hostile thing is hidden on this world's watch"));

        // one cat, made through the Builder (within the limits); then a recipe that spawns six of it, fired: this world
        // makes three at once, so four stand
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        BuilderMake.make(p, cats("friendly", 2, 1), BuilderAllowlist.REVIEWED).thenAcceptAsync(made::set);
        AtomicReference<String> cat = new AtomicReference<>();
        StringBuilder chat = new StringBuilder();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            if (cat.get() == null) {
                helper.assertTrue(made.get() != null && made.get().outcome() == BuilderMake.Outcome.MADE,
                        Component.literal("the cat is still being made: " + made.get()));
                String id = null;
                for (var m : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
                    if (!kept.contains(m.id())) {
                        id = m.id();
                    }
                }
                helper.assertTrue(id != null, Component.literal("the cat is on his shelf"));
                WatchRow.Creation catTile = new WatchRow.Creation(id, "species", "Cat", List.of(), List.of(), "",
                        List.of(), "", 1, false, List.of(), recipe, "");
                helper.assertTrue(WatchMenu.viewOf(p.getStringUUID(), catTile).makes(),
                        Component.literal("a friendly one is shown"));
                List<String> with = new ArrayList<>(had);
                with.add("{\"id\":\"" + recipe + "\",\"all\":[\"gtcontractfit" + tag + "\"],\"ownerId\":\""
                        + p.getStringUUID() + "\",\"commands\":[\"wishmob spawn " + id + " 6\"]}");
                LearnedTemplates.restoreForTest(with, server);
                cat.set(id);
                WishService.Outcome o = WishService.submit(p, "gt contract fit", WishSource.PRESET, recipe);
                helper.assertTrue(o == WishService.Outcome.FULFILLING, Component.literal("a fitted recipe is made: " + o));
            }
            helper.assertTrue(tagged(helper, cat.get()) == 4, Component.literal("cats standing: " + tagged(helper, cat.get())
                    + ", want the first one and three more"));
            String words = said(p, chat);
            helper.assertTrue(words.contains("fewer for this world"), Component.literal("he is told: " + words));
        });
    }

    @GameTest(template = "empty_test", batch = "contract_world_no_world_pages", timeoutTicks = 400)
    public static void withNoWorldTheBuilderPagesCostNothing(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtcontractnone");
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        Plugins.shared().hideForTest(WorldMakes.class, true);
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().hideForTest(WorldMakes.class, false);
            p.closeContainer();
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        helper.assertTrue(!com.moddingfromamod.wish.MakeGate.anyWorld(), Component.literal("no world answers here"));
        long derived = com.moddingfromamod.wishbuilder.MakeFacts.derivations();
        helper.assertTrue(com.moddingfromamod.wishbuilder.BuilderMenus.open(p), Component.literal("the Builder opens"));
        com.moddingfromamod.wishbuilder.BuilderMenus.press(p, "creature");
        com.moddingfromamod.wishbuilder.BuilderMenus.press(p, "species/cat");
        helper.assertValueEqual(com.moddingfromamod.wishbuilder.MakeFacts.derivations(), derived,
                Component.literal("makes read while drawing three Builder pages with no world"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "contract_world_pages", timeoutTicks = 40000)
    public static void aWorldsPagesAreWorkedOutInTheBackgroundAndADrawReadsNothing(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractpages");
        var flow = new com.moddingfromamod.wishbuilder.BuilderFlow(
                BuilderSettings.forPlayer(p.getUUID()).allow(), com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED);
        var page = flow.page();
        var world = com.moddingfromamod.wishbuilder.BuilderMenus.world(p, flow);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            var index = com.moddingfromamod.wishbuilder.BuilderPageFacts.forWorld(BuilderSettings.forPlayer(p.getUUID()).allow());
            helper.assertTrue(index != null && index.page(com.moddingfromamod.wishbuilder.BuilderPageFacts.stateKey(flow)) != null,
                    Component.literal("the first page is still being worked out"));
            helper.assertTrue(com.moddingfromamod.wishbuilder.BuilderPageFacts.started(),
                    Component.literal("a world answers, so the worker runs"));
            com.moddingfromamod.wishbuilder.BuilderPageFit.draw(flow, page, index, world);   // warm
            // (the worker is still deriving the rest of the tree beside this, so a draw's own work is counted in Gate A,
            // BuilderPageFitTest; here the real server's draw is timed)
            long t0 = System.nanoTime();
            var drawn = com.moddingfromamod.wishbuilder.BuilderPageFit.draw(flow, page, index, world);
            long took = System.nanoTime() - t0;
            helper.assertTrue(!com.moddingfromamod.wishbuilder.BuilderPageFit.gettingReady(drawn),
                    Component.literal("drawn from its facts"));
            org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID).info("[contract world] a page draw took {} ms",
                    String.format("%.2f", took / 1e6));
        });
    }

    @GameTest(template = "empty_test", batch = "contract_world_strongest_sword", timeoutTicks = 40000)
    public static void theStrongestSwordEverGivesTheStrongestSwordThisWorldAllows(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractsword");
        Set<String> had = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        Map<String, String> o = new java.util.LinkedHashMap<>();
        o.put("stock", "sword");
        o.put("shape", "shape:on_hit");
        o.put("effect", "effect:lightning");
        o.put("power", "lightning");
        o.put("strength", "3");
        BuilderSpec strongest = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", "", 2, List.of(), "", 1, "",
                "", o);
        // the game's own numbers, read at run time: this sword hits harder than this world allows
        var facts = com.moddingfromamod.wishbuilder.MakeFacts.of(BuilderAllowlist.REVIEWED.capped(strongest));
        helper.assertTrue(com.moddingfromamod.wish.MakeGate.anyWorld() && facts.number(
                com.moddingfromamod.wishbuilder.MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(0) > ContractWorld.HARDEST,
                Component.literal("the strongest sword is over the limit here: " + facts));
        AtomicReference<BuilderMake.Result> result = new AtomicReference<>();
        BuilderMake.make(p, strongest, BuilderAllowlist.REVIEWED).thenAcceptAsync(result::set);
        StringBuilder chat = new StringBuilder();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = result.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the sword: " + r));
            BuilderSpecStore.Made kept = null;
            for (var m : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
                if (!had.contains(m.id())) {
                    kept = m;
                }
            }
            helper.assertTrue(kept != null, Component.literal("the sword is on his shelf"));
            BuilderSpec made = kept.read();
            var madeFacts = com.moddingfromamod.wishbuilder.MakeFacts.of(made);
            helper.assertTrue(made.opt("strength").equals("3") && !made.opt("tier").isEmpty()
                            && madeFacts.number(com.moddingfromamod.wishbuilder.MakeFacts.ATTACK_DAMAGE_HALF_HEARTS)
                            .orElse(99) <= ContractWorld.HARDEST,
                    Component.literal("the strongest sword this world allows, at full strength: " + made.toJson() + " " + madeFacts));
            helper.assertTrue(said(p, chat).contains("for this world"), Component.literal("he is told: " + chat));
        });
    }

    @GameTest(template = "empty_test", batch = "contract_world_tough_creature", timeoutTicks = 40000)
    public static void aGiantToughCreatureIsFittedOnItsStatsHolderAndNowhereElse(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontracttough");
        Set<String> had = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        Set<String> speciesHad = new java.util.HashSet<>();
        SpeciesRegistry.all().forEach(sp -> speciesHad.add(sp.id()));
        // a giant long-neck dinosaur: tougher than this world allows, and no size or strength step lowers its health
        BuilderSpec dragon = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE,
                "preset:bodies/long_neck_dinosaur", "", 3, List.of(), "friendly", 1, "", "", Map.of());
        AtomicReference<BuilderMake.Result> result = new AtomicReference<>();
        AtomicReference<Integer> another = new AtomicReference<>();
        BuilderMake.make(p, dragon, BuilderAllowlist.REVIEWED).thenAcceptAsync(result::set);
        StringBuilder chat = new StringBuilder();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = result.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the dinosaur: " + r));
            BuilderSpecStore.Made kept = null;
            for (var m : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
                if (!had.contains(m.id())) {
                    kept = m;
                }
            }
            helper.assertTrue(kept != null, Component.literal("the dinosaur is on his shelf"));
            // the stats holder: the living entity with the fit on it (never a display part, which is not living)
            List<net.minecraft.world.entity.LivingEntity> fitted = new ArrayList<>();
            for (var e : helper.getLevel().getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.LivingEntity le && !(e instanceof ServerPlayer)
                        && com.moddingfromamod.wish.LiveFit.modifierOn(le, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                        com.moddingfromamod.wish.LiveFit.MAX_HEALTH_ID) != null && le.distanceTo(p) < 64 && his(le, p)) {
                    fitted.add(le);
                }
            }
            helper.assertTrue(another.get() == null ? fitted.size() == 1 : !fitted.isEmpty(), Component.literal("one creature carries the fit: " + fitted.size()));
            var holder = fitted.get(0);
            helper.assertTrue(Math.abs(holder.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01,
                    Component.literal("its health is this world's most: " + holder.getMaxHealth()));
            // fitted again (a reload, an edit): replaced, never stacked
            String speciesId = SpeciesRegistry.all().stream().map(sp -> sp.id())
                    .filter(id -> !speciesHad.contains(id)).findFirst().orElse("");
            com.moddingfromamod.wish.LiveFit.apply(holder, speciesId);
            long mods = holder.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).getModifiers()
                    .stream().filter(m -> m.id().equals(com.moddingfromamod.wish.LiveFit.MAX_HEALTH_ID)).count();
            helper.assertTrue(mods == 1 && Math.abs(holder.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01,
                    Component.literal("one fit after fitting again: " + mods + ", health " + holder.getMaxHealth()));
            // and nowhere else: the kept make and the creature's species keep its own strength for every other world
            helper.assertTrue(!kept.spec().toString().contains("world_fit") && kept.read().size() == 3,
                    Component.literal("the shelf keeps the make as made: " + kept.spec()));
            var species = SpeciesRegistry.get(speciesId);
            helper.assertTrue(species != null && species.health() > ContractWorld.TOUGHEST,
                    Component.literal("the species keeps its own health: " + (species == null ? "none" : species.health())));
            helper.assertTrue(said(p, chat).contains("for this world"), Component.literal("he is told: " + chat));
            // made another way (a recipe's button, "Make another one", the Magician's spawn): fitted and told, once
            if (another.get() == null) {
                com.moddingfromamod.wish.MakeGate.forgetToldForTest();
                int before = count(said(p, chat), "for this world");
                var server = helper.getLevel().getServer();
                try {
                    server.getCommands().getDispatcher().execute("execute as " + p.getScoreboardName() + " at @s run wishmob spawn "
                            + speciesId + " 1", server.createCommandSourceStack().withSuppressedOutput());
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    helper.fail(Component.literal("the spawn: " + e.getMessage()));
                }
                another.set(before);
            }
            int fits = 0;
            for (var e : helper.getLevel().getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.LivingEntity le && !(e instanceof ServerPlayer)
                        && com.moddingfromamod.wish.LiveFit.modifierOn(le, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                        com.moddingfromamod.wish.LiveFit.MAX_HEALTH_ID) != null && le.distanceTo(p) < 64 && his(le, p)) {
                    fits++;
                }
            }
            helper.assertTrue(fits == 2, Component.literal("the second one carries its fit too: " + fits));
            helper.assertTrue(count(said(p, chat), "for this world") == another.get() + 1,
                    Component.literal("told once more, once: " + chat));
        });
    }

    /** Typed words that reach a hidden, a locked and a kept make in {@link #BY_SIZE}. */
    static final List<String> APPRENTICE_WORDS = List.of("five cats", "a giant frog", "a frog", "a pet wolf", "five giant cats",
            "a lightning sword");

    /** A second world, for the Apprentice: it has no items and locks a big creature, by the facts. */
    static final class BySize implements WorldMakes {
        @Override
        public com.moddingfromamod.world.MakeView view(com.moddingfromamod.wishbuilder.MakeFacts f,
                                                       com.moddingfromamod.wish.WishAsk ask) {
            if ("item".equals(f.kind())) {
                return com.moddingfromamod.world.MakeView.HIDDEN;
            }
            if (f.number(com.moddingfromamod.wishbuilder.MakeFacts.SCALE_TIMES).orElse(1) >= 1.6) {
                return com.moddingfromamod.world.MakeView.locked(ContractWorld.LOCKED_WORDS);
            }
            return com.moddingfromamod.world.MakeView.ALLOWED;
        }
    }

    static final BySize BY_SIZE = new BySize();

    /** The Apprentice's tiles for {@code words}: its picks, in its tiles' order. */
    private static List<com.moddingfromamod.wishbuilder.Apprentice.Pick> offered(ServerPlayer p, String words) {
        com.moddingfromamod.wish.ApprenticeService.forget(p.getUUID());
        com.moddingfromamod.wish.ApprenticeService.offer(p, words, WishSource.BUILDER);
        return com.moddingfromamod.wish.ApprenticeService.pendingPicksForTest(p.getUUID());
    }

    @GameTest(template = "empty_test", batch = "contract_world_apprentice", timeoutTicks = 40000)
    public static void theApprenticesRankedMakesAreFittedAfterRanking(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractapp");
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);   // the keyword matcher alone: the same ranking twice
        var settings = BuilderSettings.forPlayer(p.getUUID());
        var tree = com.moddingfromamod.wishbuilder.BuilderTree.of(settings.allow(),
                com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED);
        int dropped = 0;
        int locked = 0;
        int kept = 0;
        StringBuilder seen = new StringBuilder();
        Plugins.shared().replaceForTest(WorldMakes.class, BY_SIZE);
        for (String words : APPRENTICE_WORDS) {
            // the ranking with no world answering: today's tiles
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            Plugins.shared().hideForTest(WorldMakes.class, true);
            List<com.moddingfromamod.wishbuilder.Apprentice.Pick> ranked;
            try {
                helper.assertTrue(!com.moddingfromamod.wish.MakeGate.anyWorld(), Component.literal("no world answers"));
                ranked = offered(p, words);
            } finally {
                Plugins.shared().hideForTest(WorldMakes.class, false);
                Plugins.shared().replaceForTest(WorldMakes.class, BY_SIZE);
            }
            // what the world says of each, by the facts of the make its tile presses
            List<com.moddingfromamod.wishbuilder.Apprentice.Pick> expect = new ArrayList<>();
            List<Integer> lockedAt = new ArrayList<>();
            for (var pick : ranked) {
                BuilderSpec spec = com.moddingfromamod.wishbuilder.BuilderMenus.apprenticeSpec(p, settings, tree, pick);
                var v = BY_SIZE.view(com.moddingfromamod.wishbuilder.MakeFacts.of(settings.allow().capped(spec)), null);
                seen.append(v.state()).append(' ');
                switch (v.state()) {
                    case HIDDEN -> dropped++;
                    case LOCKED -> {
                        lockedAt.add(expect.size());
                        locked++;
                        expect.add(pick);
                    }
                    default -> {
                        kept++;
                        expect.add(pick);
                    }
                }
            }
            // the same words, the world answering: the same ranking, fitted after it
            List<com.moddingfromamod.wishbuilder.Apprentice.Pick> shown = offered(p, words);
            seen.append(words).append(": ").append(ranked.size()).append(" → ").append(shown.size()).append("; ");
            helper.assertTrue(shown.equals(expect), Component.literal("'" + words + "': the ranked makes the world keeps, in order: "
                    + shown + " vs " + expect));
            if (!shown.isEmpty()) {
                helper.assertTrue(p.containerMenu instanceof com.moddingfromamod.ui.MenuKit.PickMenu,
                        Component.literal("the page opened for '" + words + "'"));
                var page = ((com.moddingfromamod.ui.MenuKit.PickMenu) p.containerMenu).page();
                for (int i = 0; i < shown.size(); i++) {
                    String id = com.moddingfromamod.wishbuilder.ApprenticeWords.OFFER + i;
                    var tile = page.tiles().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
                    boolean isLocked = tile.label().endsWith("🔒");
                    helper.assertTrue(isLocked == lockedAt.contains(i), Component.literal("'" + words + "' tile " + i
                            + " locked: " + isLocked + " (" + tile.lore() + ")"));
                    if (isLocked) {
                        helper.assertTrue(tile.lore().get(0).equals(ContractWorld.LOCKED_WORDS),
                                Component.literal("in the world's words: " + tile.lore()));
                    }
                }
            }
        }
        helper.assertTrue(dropped > 0 && locked > 0 && kept > 0, Component.literal("the words reach every answer: dropped "
                + dropped + ", locked " + locked + ", kept " + kept + " — " + seen));
        // the contract world: five giant cats are fitted, and pressed, made within its limits and told once
        Plugins mine = new Plugins();
        new ContractWorld().register(mine);
        Plugins.shared().replaceForTest(WorldMakes.class, mine.first(WorldMakes.class).orElseThrow());
        List<com.moddingfromamod.wishbuilder.Apprentice.Pick> cats = offered(p, "five giant cats");
        helper.assertTrue(!cats.isEmpty(), Component.literal("the cats are offered"));
        AtomicReference<BuilderSpec> made = new AtomicReference<>();
        BuilderSpec pressed = com.moddingfromamod.wishbuilder.BuilderMenus.apprenticeSpec(p, settings, tree, cats.get(0));
        var built = BuilderMake.askApprentice(p.getUUID(), pressed, settings.allow());
        helper.assertTrue(built.makes() && built.spec().count() <= ContractWorld.MOST_AT_ONCE
                        && built.spec().size() <= ContractWorld.BIGGEST_STEP && !built.lowered().isEmpty(),
                Component.literal("five giant cats are fitted: " + built.spec().toJson() + " from " + pressed.toJson()));
        StringBuilder chat = new StringBuilder();
        said(p, chat);
        int before = count(chat.toString(), "for this world");
        com.moddingfromamod.wish.ApprenticeService.pressForTest(p, com.moddingfromamod.wishbuilder.ApprenticeWords.OFFER + 0);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            String now = said(p, chat);
            helper.assertTrue(count(now, "for this world") == before + 1, Component.literal("told once: " + now));
        });
    }

    /** The bodies near {@code p} that {@link com.moddingfromamod.world.Wished} says a wish made, by entity id. */
    private static Set<java.util.UUID> wishedNear(GameTestHelper helper, ServerPlayer p) {
        Set<java.util.UUID> out = new java.util.HashSet<>();
        for (var e : helper.getLevel().getAllEntities()) {
            if (e.distanceTo(p) < 48 && com.moddingfromamod.world.Wished.isWished(e)) {
                out.add(e.getUUID());
            }
        }
        return out;
    }

    @GameTest(template = "empty_test", batch = "contract_world_wished", timeoutTicks = 40000)
    public static void everyBodyAWishMadeIsReadAsWishedByEveryPath(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractwished");
        var level = helper.getLevel();
        var server = level.getServer();
        net.minecraft.world.phys.Vec3 at = p.position();
        // a natural spawn, and a spawn egg's: neither is wished
        // (a plain server command: no wish, no event, no function, so no spawn scope)
        try {
            server.getCommands().getDispatcher().execute("execute as " + p.getScoreboardName()
                    + " at @s run summon minecraft:pig ~3 ~ ~ {NoAI:1b,Tags:[\"gtcontract_natural\"]}", server.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("the natural pig: " + e.getMessage()));
        }
        net.minecraft.world.entity.Entity natural = null;
        for (var e : level.getAllEntities()) {
            if (e.distanceTo(p) < 16 && e.getTags().contains("gtcontract_natural")) {
                natural = e;
            }
        }
        helper.assertTrue(natural != null, Component.literal("the natural pig stands"));
        final net.minecraft.world.entity.Entity naturalPig = natural;
        helper.assertFalse(com.moddingfromamod.world.Wished.isWished(naturalPig), Component.literal("a natural pig is nobody's wish"));
        // the Magician: a plain mob its command summons, inside its wish's scope
        String wish = "gtcontract-wished-" + System.nanoTime();
        Set<java.util.UUID> before = wishedNear(helper, p);
        com.moddingfromamod.mob.SpawnScope.during(wish, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () ->
                com.moddingfromamod.wish.WishExecutor.runOne(server, level, at, null, "summon minecraft:pig ~2 ~ ~ {NoAI:1b}"));
        Set<java.util.UUID> magician = new java.util.HashSet<>(wishedNear(helper, p));
        magician.removeAll(before);
        helper.assertTrue(magician.size() == 1, Component.literal("the Magician's summoned pig is wished: " + magician));
        // a recipe, "Make another one" or a hotbar fire: the stored command, run in its wish's scope
        BuilderSpec one = cats("friendly", 1, 1);
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        AtomicReference<Set<java.util.UUID>> builder = new AtomicReference<>();
        AtomicReference<Set<java.util.UUID>> replay = new AtomicReference<>();
        AtomicReference<Set<java.util.UUID>> apprentice = new AtomicReference<>();
        Set<java.util.UUID> beforeBuilder = wishedNear(helper, p);
        Set<String> speciesHad = new java.util.HashSet<>();
        SpeciesRegistry.all().forEach(sp -> speciesHad.add(sp.id()));
        BuilderMake.make(p, one, BuilderAllowlist.REVIEWED).thenAcceptAsync(made::set);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the Builder cat: " + r));
            if (builder.get() == null) {
                Set<java.util.UUID> now = new java.util.HashSet<>(wishedNear(helper, p));
                now.removeAll(beforeBuilder);
                helper.assertTrue(now.size() == 1, Component.literal("the Builder's cat is one wished body: " + now));
                // its species, spawned again as a recipe would
                String species = SpeciesRegistry.all().stream().map(sp -> sp.id())
                        .filter(sid -> !speciesHad.contains(sid)).findFirst().orElse("");
                helper.assertTrue(!species.isEmpty(), Component.literal("the cat is a new species"));
                Set<java.util.UUID> b2 = wishedNear(helper, p);
                String sp = species;
                var ran = com.moddingfromamod.mob.SpawnScope.during(wish + "-again",
                        com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER,
                        () -> com.moddingfromamod.wish.WishExecutor.runOne(server, level, at, p, "wishmob spawn " + sp + " 1"));
                helper.assertTrue(ran.success(), Component.literal("the recipe's spawn ran: " + ran.joined()));
                replay.set(b2);
                builder.set(now);
            }
            if (apprentice.get() == null) {
                helper.assertTrue(replay.get() != null, Component.literal("the recipe ran"));
                Set<java.util.UUID> again = new java.util.HashSet<>(wishedNear(helper, p));
                again.removeAll(replay.get());
                helper.assertTrue(again.size() == 1, Component.literal("the recipe's cat is one more wished body: " + again));
                // the Apprentice: a typed wish, its first tile pressed
                apprentice.set(wishedNear(helper, p));
                offered(p, "a frog");
                com.moddingfromamod.wish.ApprenticeService.pressForTest(p, com.moddingfromamod.wishbuilder.ApprenticeWords.OFFER + 0);
            }
            Set<java.util.UUID> last = new java.util.HashSet<>(wishedNear(helper, p));
            last.removeAll(apprentice.get());
            helper.assertTrue(last.size() == 1, Component.literal("the Apprentice's frog is one more wished body: " + last));
            // and no display part of any of them is a body; the natural pig is still nobody's
            for (var e : level.getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.Display && e.distanceTo(p) < 48) {
                    helper.assertFalse(com.moddingfromamod.world.Wished.isWished(e), Component.literal("a display part is not a body"));
                }
            }
            helper.assertFalse(com.moddingfromamod.world.Wished.isWished(naturalPig), Component.literal("the natural pig still is not"));
        });
    }

    /** The living creatures near {@code p} that carry this world's health fit, each with its own UUID. */
    private static List<net.minecraft.world.entity.LivingEntity> fittedNear(GameTestHelper helper, ServerPlayer p) {
        List<net.minecraft.world.entity.LivingEntity> out = new ArrayList<>();
        for (var e : helper.getLevel().getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.LivingEntity le && !(e instanceof ServerPlayer) && le.distanceTo(p) < 64 && his(le, p)
                    && com.moddingfromamod.wish.LiveFit.modifierOn(le, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                    com.moddingfromamod.wish.LiveFit.MAX_HEALTH_ID) != null) {
                out.add(le);
            }
        }
        out.sort(java.util.Comparator.comparing(e -> e.getUUID().toString()));
        return out;
    }

    @GameTest(template = "empty_test", batch = "contract_world_rounds", timeoutTicks = 40000)
    public static void aRoundThatChangesTheLimitsRefitsHisCreaturesAndTellsHimOnce(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractrounds");
        MockPlayerNetwork.afterTest(helper, ContractWorld::forgetRounds);
        // two giant long-neck dinosaurs (120 health each) in the first round: held to 40
        BuilderSpec two = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/long_neck_dinosaur", "",
                3, List.of(), "friendly", 2, "", "", Map.of());
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        BuilderMake.make(p, two, BuilderAllowlist.REVIEWED).thenAcceptAsync(made::set);
        StringBuilder chat = new StringBuilder();
        AtomicReference<Integer> step = new AtomicReference<>(0);
        AtomicReference<String> firstFail = new AtomicReference<>();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the dinosaurs: " + r));
            List<net.minecraft.world.entity.LivingEntity> them = fittedNear(helper, p);
            helper.assertTrue(them.size() == 2, Component.literal("two creatures carry the fit: " + them.size()));
            var hurt = them.get(0);
            var whole = them.get(1);
            helper.assertTrue(firstFail.get() == null, Component.literal(String.valueOf(firstFail.get())));
            if (step.get() == 0) {
                helper.assertTrue(Math.abs(hurt.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01
                        && Math.abs(whole.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01,
                        Component.literal("the first round holds them to 40: " + hurt.getMaxHealth() + ", " + whole.getMaxHealth()));
                step.set(1);   // from here the world has changed: the first failure is the one reported
                try {
                hurt.setHealth(20);   // half its hearts
                whole.setHealth(whole.getMaxHealth());
                said(p, chat);
                // RAISE: a later round lets them have 80
                int before = count(chat.toString(), com.moddingfromamod.wish.LiveFit.UNLEASHED);
                ContractWorld.round(p.getUUID(), 2);
                helper.assertTrue(Math.abs(hurt.getMaxHealth() - ContractWorld.TOUGHEST_LATER) < 0.01
                                && Math.abs(hurt.getHealth() - 40) < 0.01,
                        Component.literal("raised, as hurt as it was (half): " + hurt.getHealth() + "/" + hurt.getMaxHealth()));
                helper.assertTrue(Math.abs(whole.getHealth() - ContractWorld.TOUGHEST_LATER) < 0.01,
                        Component.literal("raised, a whole one stays whole: " + whole.getHealth() + "/" + whole.getMaxHealth()));
                helper.assertTrue(count(said(p, chat), com.moddingfromamod.wish.LiveFit.UNLEASHED) == before + 1,
                        Component.literal("told once for two creatures: " + chat));
                // LOWER: back to the first round
                int held = count(chat.toString(), com.moddingfromamod.wish.LiveFit.HELD_DOWN);
                ContractWorld.round(p.getUUID(), 1);
                helper.assertTrue(Math.abs(hurt.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01
                                && Math.abs(hurt.getHealth() - 40) < 0.01,
                        Component.literal("lowered, it keeps what it has up to 40: " + hurt.getHealth() + "/" + hurt.getMaxHealth()));
                helper.assertTrue(Math.abs(whole.getHealth() - ContractWorld.TOUGHEST) < 0.01,
                        Component.literal("lowered, held to its new most: " + whole.getHealth() + "/" + whole.getMaxHealth()));
                helper.assertTrue(count(said(p, chat), com.moddingfromamod.wish.LiveFit.HELD_DOWN) == held + 1,
                        Component.literal("held down, told once: " + chat));
                // RAISE BACK, from a hurt creature again
                hurt.setHealth(10);
                int again = count(chat.toString(), com.moddingfromamod.wish.LiveFit.UNLEASHED);
                ContractWorld.round(p.getUUID(), 2);
                helper.assertTrue(Math.abs(hurt.getMaxHealth() - ContractWorld.TOUGHEST_LATER) < 0.01
                                && Math.abs(hurt.getHealth() - 20) < 0.01,
                        Component.literal("raised back, a quarter stays a quarter: " + hurt.getHealth() + "/" + hurt.getMaxHealth()));
                helper.assertTrue(count(said(p, chat), com.moddingfromamod.wish.LiveFit.UNLEASHED) == again + 1,
                        Component.literal("told once again: " + chat));
                long mods = hurt.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).getModifiers()
                        .stream().filter(m -> m.id().equals(com.moddingfromamod.wish.LiveFit.MAX_HEALTH_ID)).count();
                helper.assertTrue(mods == 1, Component.literal("one fit after three changes, never stacked: " + mods));
                // armour and knockback resistance: a creature that carries them is held to this world's most, on its stats holder
                var armor = whole.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                var steady = whole.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
                helper.assertTrue(armor != null && steady != null, Component.literal("the stats holder holds armour and knockback"));
                armor.setBaseValue(10);
                steady.setBaseValue(1);
                ContractWorld.round(p.getUUID(), 2);
                helper.assertTrue(Math.abs(armor.getValue() - ContractWorld.HARDEST_SHELL) < 0.01
                                && Math.abs(steady.getValue() - ContractWorld.STEADIEST) < 0.01
                                && armor.getModifier(com.moddingfromamod.wish.LiveFit.ARMOR_ID) != null
                                && steady.getModifier(com.moddingfromamod.wish.LiveFit.KNOCKBACK_RESISTANCE_ID) != null,
                        Component.literal("armour " + armor.getValue() + ", knockback " + steady.getValue()));
                } catch (RuntimeException | AssertionError e) {
                    firstFail.set(e.getMessage() == null ? e.toString() : e.getMessage());
                    throw e;
                }
            }
        });
    }

    /** Is {@code le} a creature {@code p}'s wishes made (its species is his)? Other tests' creatures may stand near. */
    private static boolean his(net.minecraft.world.entity.LivingEntity le, ServerPlayer p) {
        String sid = com.moddingfromamod.wish.LiveFit.speciesOf(le);
        var sp = sid == null ? null : SpeciesRegistry.get(sid);
        return sp != null && p.getStringUUID().equals(sp.ownerId());
    }

    @GameTest(template = "empty_test", batch = "contract_world_shipped_starter", timeoutTicks = 40000)
    public static void aBuilderStarterHasItsShippedStatsAndTheWorldHoldsThemDown(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractshipped");
        // this world has its own record of the pet dragon (a customised one): 100 health
        Species custom = Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"pet_dragon\",\"name\":\"Pet Dragon\",\"powers\":[\"friendly\"],\"health\":100,\"damage\":9}")
                .getAsJsonObject());
        MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(List.of(custom)));
        Set<String> had = new java.util.HashSet<>();
        SpeciesRegistry.all().forEach(sp -> had.add(sp.id()));
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        BuilderMake.make(p, new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/pet_dragon", "",
                2, List.of(), "friendly", 1, "", "", Map.of()), BuilderAllowlist.REVIEWED).thenAcceptAsync(made::set);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the dragon: " + r));
            Species dragon = null;
            for (Species sp : SpeciesRegistry.all()) {
                if (!had.contains(sp.id()) && sp.id().startsWith("pet_dragon")) {
                    dragon = sp;
                }
            }
            helper.assertTrue(dragon != null, Component.literal("the Builder's pet dragon is made"));
            // #74: the SHIPPED ready-made's numbers, not this world's record of the species (and not a fresh world's 20/3)
            var shipped = com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED.species("species/pet_dragon");
            helper.assertTrue(Math.abs(dragon.health() - shipped.get("health").getAsDouble()) < 1e-6
                            && Math.abs(dragon.damage() - shipped.get("damage").getAsDouble()) < 1e-6,
                    Component.literal("its own stats are the shipped ones (" + shipped.get("health") + "/" + shipped.get("damage")
                            + "): " + dragon.health() + "/" + dragon.damage()));
            // and this world's limits hold it down where it stands (60 health over a limit of 40)
            List<net.minecraft.world.entity.LivingEntity> fitted = fittedNear(helper, p);
            helper.assertTrue(!fitted.isEmpty() && Math.abs(fitted.get(0).getMaxHealth() - ContractWorld.TOUGHEST) < 0.01,
                    Component.literal("held to this world's most: " + (fitted.isEmpty() ? "none fitted" : fitted.get(0).getMaxHealth())));
        });
    }

    @GameTest(template = "empty_test", batch = "contract_world_magician_unleashed", timeoutTicks = 40000)
    public static void aMagicianCreatureKeepsItsAskedHealthAndIsUnleashedOnARaise(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtcontractmagician");
        MockPlayerNetwork.afterTest(helper, ContractWorld::forgetRounds);
        var server = helper.getLevel().getServer();
        // a creature the Magician sculpted under a tight limit: sculpt_creature is given 120 health, which the dispatch
        // keeps as asked (ContractWorldMagicianGameTest), so its record holds 120, his
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Species base = SpeciesRegistry.get("cat");
        Species asked = Species.fromJson(com.google.gson.JsonParser.parseString("{\"id\":\"gt_magician_beast_" + tag
                + "\",\"name\":\"Beast\",\"powers\":[\"friendly\"],\"health\":120,\"damage\":2,\"ownerId\":\""
                + p.getStringUUID() + "\"" + (base != null && base.modelKey() != null ? ",\"modelKey\":\"" + base.modelKey() + "\"" : "")
                + "}").getAsJsonObject());
        MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(List.of(asked)));
        var ran = com.moddingfromamod.mob.SpawnScope.during("gt-contract-magician-" + tag,
                com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER,
                () -> com.moddingfromamod.wish.WishExecutor.runOne(server, helper.getLevel(), p.position(), p,
                        "wishmob spawn " + asked.id() + " 1"));
        helper.assertTrue(ran.success(), Component.literal("the beast spawns: " + ran.joined()));
        AtomicReference<Integer> step = new AtomicReference<>(0);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            net.minecraft.world.entity.LivingEntity beast = null;
            for (var e : helper.getLevel().getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.LivingEntity le && le.distanceTo(p) < 64
                        && asked.id().equals(com.moddingfromamod.wish.LiveFit.speciesOf(le))) {
                    beast = le;
                }
            }
            helper.assertTrue(beast != null, Component.literal("the beast stands"));
            var record = SpeciesRegistry.get(asked.id());
            helper.assertTrue(record != null && Math.abs(record.health() - 120) < 1e-6,
                    Component.literal("its record keeps the health it was asked for: " + (record == null ? "none" : record.health())));
            if (step.get() == 0) {
                helper.assertTrue(Math.abs(beast.getMaxHealth() - ContractWorld.TOUGHEST) < 0.01,
                        Component.literal("held to this world's most where it stands: " + beast.getMaxHealth()));
                ContractWorld.round(p.getUUID(), 2);
                step.set(1);
            }
            helper.assertTrue(Math.abs(beast.getMaxHealth() - ContractWorld.TOUGHEST_LATER) < 0.01,
                    Component.literal("a later round unleashes it: " + beast.getMaxHealth()));
        });
    }

    private static int count(String in, String what) {
        int n = 0;
        for (int i = in.indexOf(what); i >= 0; i = in.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }
}
