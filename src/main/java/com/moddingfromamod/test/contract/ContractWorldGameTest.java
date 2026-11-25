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
        var channel = p.connection.getConnection().channel();
        if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
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
}
