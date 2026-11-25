package com.moddingfromamod.wish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyLexicon;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wishbuilder.ApprenticeWords;
import com.moddingfromamod.wishbuilder.BuilderAgain;
import com.moddingfromamod.wishbuilder.BuilderFlow;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderMenus;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>What a world's {@link WishCharge} is told, when it is asked, and whose words a refusal says</b> (#13), driven
 * through the real entry points: the wish box, {@link WishService#submit}, the Builder's menus and Make command, the
 * Apprentice's page, {@code /moddy again} ("Make another one"), a hotbar proxy's fire and "Give me one". A stand-in
 * charge records every {@link WishAsk} it is shown and refuses what the test says.
 *
 * <p>Every test is a batch of its own: the charge stands in process-wide ({@link Plugins#replaceForTest}).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishChargeAskGameTest {

    private WishChargeAskGameTest() {
    }

    /** A charge written against the new hooks: records every ask, refuses what {@code refuse} answers. */
    static final class Told implements WishCharge {
        final List<WishAsk> asked = new CopyOnWriteArrayList<>();
        final List<WishAsk> charged = new CopyOnWriteArrayList<>();
        final List<WishAsk> again = new CopyOnWriteArrayList<>();
        volatile Function<WishAsk, Optional<String>> refuse;

        Told(Function<WishAsk, Optional<String>> refuse) {
            this.refuse = refuse;
        }

        @Override
        public Optional<String> refuses(WishAsk ask) {
            asked.add(ask);
            return refuse.apply(ask);
        }

        @Override
        public Paid charge(WishAsk ask, UUID wishId) {
            charged.add(ask);
            return Paid.FREE;
        }

        @Override
        public AgainPaid chargeAgain(ServerPlayer player, Again kind, WishAsk ask) {
            again.add(ask);
            return AgainPaid.FREE;
        }
    }

    /** A charge written before #13: only the boolean hook, saying its own words, as the example charge does. */
    static final class OldStyle implements WishCharge {
        static final String OWN = "Old charge: no wishes today.";
        final List<String> texts = new CopyOnWriteArrayList<>();

        @Override
        public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
            texts.add(text + (recipe ? " [recipe]" : ""));
            player.sendSystemMessage(Component.literal(OWN));
            return true;
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private static <C extends WishCharge> C standIn(GameTestHelper helper, C charge) {
        Plugins.shared().replaceForTest(WishCharge.class, charge);
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().replaceForTest(WishCharge.class, null);
            SafetyGrader.forTest(null);
        });
        return charge;
    }

    /** The Builder on, no card, no rise; put back after the test. */
    private static void builderOn(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        BuilderMake.takeCard = false;
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.takeCard = true;
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
    }

    /** The Apprentice answers typed words here (an open world's Crafter), as BuilderAgainGameTest sets it up. */
    private static void apprenticeAnswers(GameTestHelper helper, ServerPlayer p) {
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        System.setProperty(com.moddingfromamod.world.WorldCrafters.OPEN_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            System.clearProperty(com.moddingfromamod.world.WorldCrafters.OPEN_PROPERTY);
            p.closeContainer();
        });
    }

    private static ServerPlayer child(GameTestHelper helper, String name) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        WishService.forget(p);
        return p;
    }

    private static List<String> chatSince(ServerPlayer p, int mark) {
        List<String> all = MockPlayerNetwork.chat(p);
        return new ArrayList<>(all.subList(Math.min(mark, all.size()), all.size()));
    }

    private static long count(List<String> lines, String line) {
        return lines.stream().filter(s -> s.contains(line)).count();
    }

    private static void run(GameTestHelper helper, ServerPlayer p, String command) {
        p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
    }

    private static void assertAsk(GameTestHelper helper, WishAsk a, ServerPlayer p, WishSource source, String recipe,
                                  WishAsk.Magic magic, String what) {
        helper.assertTrue(a != null, Component.literal(what + ": the charge was never asked"));
        helper.assertTrue(p.getUUID().equals(a.player()) && a.source() == source && a.recipe().equals(recipe)
                        && a.magic() == magic,
                Component.literal(what + ": expected " + source + " '" + recipe + "' " + magic + ", was " + a));
    }

    private static WishAsk last(List<WishAsk> asks) {
        return asks.isEmpty() ? null : asks.get(asks.size() - 1);
    }

    /** Press the tile (or control) meaning {@code id} on the open pick chest, as a click. */
    private static void click(GameTestHelper helper, ServerPlayer p, String id) {
        if (!(p.containerMenu instanceof MenuKit.PickMenu menu)) {
            helper.fail(Component.literal("no pick chest is open (pressing " + id + "): " + p.containerMenu));
            return;
        }
        Integer slot = null;
        for (Map.Entry<Integer, String> e : menu.page().slots().entrySet()) {
            if (e.getValue().equals(id)) {
                slot = e.getKey();
            }
        }
        if (slot == null) {
            helper.fail(Component.literal("'" + id + "' is not on the page: " + menu.page().slots().values()));
            return;
        }
        menu.clicked(slot, 0, ClickType.PICKUP, p);
    }

    /** The Builder's menus to a cat's tune dialog, then its Make command, as a client sends them. */
    private static void makeACat(GameTestHelper helper, ServerPlayer p) {
        helper.assertTrue(BuilderMenus.open(p), Component.literal("the Builder opens when it is on"));
        click(helper, p, "creature");
        click(helper, p, "species/cat");
        click(helper, p, PickPage.NEXT_ID);
        click(helper, p, PickPage.NEXT_ID);
        click(helper, p, PickPage.NEXT_ID);
        BuilderFlow flow = BuilderMenus.flowOf(p);
        helper.assertTrue(flow != null && flow.step() == BuilderFlow.Step.TUNE,
                Component.literal("Next opens the tune dialog: " + (flow == null ? "no flow" : flow.step())));
        try {
            p.level().getServer().getCommands().getDispatcher().execute(
                    "moddy builder make " + flow.token + " 2 1 ocean", p.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("the Make command did not parse: " + e.getMessage()));
        }
    }

    // ── typed words ───────────────────────────────────────────────────────────────────────────────

    /**
     * A world that refuses typed words right now refuses them BEFORE the Apprentice's page opens: no page, and the
     * player reads the charge's line once and no other line. The charge was told: typed, no recipe, the Apprentice.
     */
    @GameTest(template = "empty_test", batch = "chargeAskTyped", timeoutTicks = 200)
    public static void typedWordsAreRefusedBeforeTheApprenticeOpensAPage(GameTestHelper helper) {
        String line = "No new wishes during a round. Use your kept wishes!";
        Told charge = standIn(helper, new Told(a -> a.source() == WishSource.TYPED ? Optional.of(line) : Optional.empty()));
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge1");
        apprenticeAnswers(helper, p);
        int mark = MockPlayerNetwork.chat(p).size();

        run(helper, p, "moddy wishbox a green dino i can ride");

        helper.assertFalse(p.containerMenu instanceof MenuKit.PickMenu,
                Component.literal("the Apprentice's page opened for a refused wish: " + p.containerMenu));
        List<String> said = chatSince(p, mark);
        helper.assertValueEqual(said, List.of(line), Component.literal("he reads the charge's line once, and nothing else"));
        WishAsk a = last(charge.asked);
        assertAsk(helper, a, p, WishSource.TYPED, "", WishAsk.Magic.APPRENTICE, "typed words the Apprentice answers");
        helper.assertValueEqual(a.text(), "a green dino i can ride", Component.literal("the words the charge was told"));
        helper.assertTrue(charge.charged.isEmpty(), Component.literal("a refused wish is charged nothing"));
        helper.succeed();
    }

    /** Typed words that go to the Magician are told MAGICIAN (refused here, so nothing is left for an agent). */
    @GameTest(template = "empty_test", batch = "chargeAskMagician", timeoutTicks = 200)
    public static void typedWordsForTheMagicianAreToldMagician(GameTestHelper helper) {
        String line = "The Magician is asleep on this world.";
        Told charge = standIn(helper, new Told(a -> a.magic() == WishAsk.Magic.MAGICIAN ? Optional.of(line)
                : Optional.empty()));
        ServerPlayer p = child(helper, "gtcharge2");
        UUID him = p.getUUID();
        Plugins.shared().replaceForTest(AlwaysMagician.class, him::equals);
        MockPlayerNetwork.afterTest(helper, () -> Plugins.shared().replaceForTest(AlwaysMagician.class, null));
        int mark = MockPlayerNetwork.chat(p).size();
        String words = "zibble quorfen glimmerwump " + UUID.randomUUID().toString().substring(0, 6);

        WishService.Outcome out = WishService.submit(p, words, WishSource.TYPED);

        helper.assertValueEqual(out, WishService.Outcome.BANK_EMPTY, Component.literal("the charge refused it"));
        WishAsk a = last(charge.asked);
        assertAsk(helper, a, p, WishSource.TYPED, "", WishAsk.Magic.MAGICIAN, "typed words for the Magician");
        helper.assertValueEqual(chatSince(p, mark), List.of(line), Component.literal("his chat"));
        helper.succeed();
    }

    /** A charge's line the lexicon flags is not said: the fixed line is, once, and the wish is still refused. */
    @GameTest(template = "empty_test", batch = "chargeAskSwept", timeoutTicks = 200)
    public static void aChargeLineTheLexiconFlagsIsReplaced(GameTestHelper helper) {
        Path override = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(SafetyLexicon.OVERRIDE_DIR).resolve(SafetyLexicon.FILE_NAME);
        String before;
        try {
            before = Files.isRegularFile(override) ? Files.readString(override, StandardCharsets.UTF_8) : null;
            String bundled;
            try (var in = SafetyLexicon.class.getResourceAsStream("/safety/" + SafetyLexicon.FILE_NAME)) {
                bundled = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Files.createDirectories(override.getParent());
            Files.writeString(override, (before != null ? before : bundled) + "\nVIOLENCE\tzorblaxian\tOTHER\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not write the lexicon override", e);
        }
        SafetyLexicon.reload();
        MockPlayerNetwork.afterTest(helper, () -> {
            try {
                if (before == null) {
                    Files.deleteIfExists(override);
                } else {
                    Files.writeString(override, before, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // the next reload reads whatever is there
            }
            SafetyLexicon.reload();
        });
        String flagged = "No zorblaxian wishes here!";
        standIn(helper, new Told(a -> Optional.of(flagged)));
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge3");
        apprenticeAnswers(helper, p);
        int mark = MockPlayerNetwork.chat(p).size();

        run(helper, p, "moddy wishbox a green dino i can ride");

        List<String> said = chatSince(p, mark);
        helper.assertValueEqual(said, List.of(ChargeRefusal.FALLBACK), Component.literal("the fixed line, once"));
        helper.assertFalse(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("still refused"));
        helper.succeed();
    }

    // ── a Builder make ───────────────────────────────────────────────────────────────────────────

    /** A refused Builder make reads the charge's line, not the wish bank's; the charge was told: a Builder make. */
    @GameTest(template = "empty_test", batch = "chargeAskBuilderRefused", timeoutTicks = 400)
    public static void aRefusedBuilderMakeReadsTheChargesLine(GameTestHelper helper) {
        String line = "The Builder rests during a round.";
        Told charge = standIn(helper, new Told(a -> a.magic() == WishAsk.Magic.BUILDER ? Optional.of(line)
                : Optional.empty()));
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge5");
        Set<String> had = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        int mark = MockPlayerNetwork.chat(p).size();

        makeACat(helper, p);

        helper.succeedWhen(() -> {
            List<String> said = chatSince(p, mark);
            helper.assertValueEqual(count(said, line), 1L, Component.literal("the charge's line once: " + said));
            helper.assertValueEqual(count(said, ChargeRefusal.BANK_EMPTY), 0L,
                    Component.literal("the wish bank's line was said: " + said));
            WishAsk a = last(charge.asked);
            assertAsk(helper, a, p, WishSource.BUILDER, "", WishAsk.Magic.BUILDER, "a Builder make");
            helper.assertTrue(a.text().startsWith(BuilderMake.TEXT_PREFIX), Component.literal("its words: " + a.text()));
            helper.assertValueEqual(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet(), had,
                    Component.literal("a refused make keeps nothing"));
        });
    }

    /** A charge written before #13 is unchanged: typed words read its own line only; a Builder make adds the bank line. */
    @GameTest(template = "empty_test", batch = "chargeAskOldStyle", timeoutTicks = 400)
    public static void anOlderChargeBehavesAsBefore(GameTestHelper helper) {
        OldStyle charge = standIn(helper, new OldStyle());
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge6");
        apprenticeAnswers(helper, p);
        int mark = MockPlayerNetwork.chat(p).size();

        run(helper, p, "moddy wishbox a green dino i can ride");
        helper.assertValueEqual(chatSince(p, mark), List.of(OldStyle.OWN),
                Component.literal("typed words: its own line, nothing more"));
        helper.assertValueEqual(charge.texts, List.of("a green dino i can ride"),
                Component.literal("told the words, no recipe flag"));
        helper.assertFalse(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("no page for a refused wish"));
        p.closeContainer();

        int mark2 = MockPlayerNetwork.chat(p).size();
        makeACat(helper, p);
        helper.succeedWhen(() -> {
            List<String> said = chatSince(p, mark2);
            helper.assertValueEqual(count(said, OldStyle.OWN), 1L, Component.literal("its own line once: " + said));
            helper.assertValueEqual(count(said, ChargeRefusal.BANK_EMPTY), 1L,
                    Component.literal("and the Builder's default line once: " + said));
            helper.assertTrue(charge.texts.size() == 2 && charge.texts.get(1).startsWith(BuilderMake.TEXT_PREFIX)
                            && charge.texts.get(1).endsWith(" [recipe]"),
                    Component.literal("a Builder make: its words and the recipe flag, as before: " + charge.texts));
        });
    }

    // ── an Apprentice make ───────────────────────────────────────────────────────────────────────

    /**
     * Typed words the Apprentice answers are told APPRENTICE and open its page; the make a tile presses is told an
     * APPRENTICE make from the Builder, and refused in the charge's words.
     */
    @GameTest(template = "empty_test", batch = "chargeAskApprentice", timeoutTicks = 400)
    public static void anApprenticeMakeIsToldApprentice(GameTestHelper helper) {
        String line = "The Apprentice is resting.";
        Told charge = standIn(helper, new Told(a -> a.source() == WishSource.BUILDER ? Optional.of(line)
                : Optional.empty()));
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge7");
        apprenticeAnswers(helper, p);

        run(helper, p, "moddy wishbox a green dino i can ride");
        helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu,
                Component.literal("the Apprentice's page opened: " + p.containerMenu));
        assertAsk(helper, last(charge.asked), p, WishSource.TYPED, "", WishAsk.Magic.APPRENTICE, "the typed words");
        int mark = MockPlayerNetwork.chat(p).size();
        ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);

        helper.succeedWhen(() -> {
            assertAsk(helper, last(charge.asked), p, WishSource.BUILDER, "", WishAsk.Magic.APPRENTICE,
                    "the make an Apprentice tile pressed");
            List<String> said = chatSince(p, mark);
            helper.assertValueEqual(count(said, line), 1L, Component.literal("the charge's line once: " + said));
            helper.assertValueEqual(count(said, ChargeRefusal.BANK_EMPTY), 0L, Component.literal("no bank line: " + said));
        });
    }

    // ── a recipe, "Make another one", a hotbar fire, "Give me one" ───────────────────────────────

    /**
     * A recipe press is a REPLAY of its id, from a button, and is charged as one after the rate limiter; "Give me one"
     * tells the charge which thing.
     */
    @GameTest(template = "empty_test", batch = "chargeAskRecipe", timeoutTicks = 400)
    public static void aRecipePressAndGiveMeOneAreTold(GameTestHelper helper) {
        Told charge = standIn(helper, new Told(a -> Optional.empty()));
        ServerPlayer p = child(helper, "gtcharge8");
        var server = helper.getLevel().getServer();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String id = "gt13_recipe_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        with.add("{\"id\":\"" + id + "\",\"all\":[\"gt13recipe" + tag + "\"],\"ownerId\":\""
                + p.getStringUUID() + "\",\"commands\":[\"say a told wish\"]}");
        LearnedTemplates.restoreForTest(with, server);
        String name = "Gtcharge Told Sword " + tag;
        MockPlayerNetwork.afterTest(helper, () -> {
            LearnedTemplates.restoreForTest(had, server);
            WishItemCatalogue.forget(name);
        });

        WishService.Outcome out = WishService.submit(p, "gt13recipe" + tag, WishSource.PRESET, id);
        helper.assertValueEqual(out, WishService.Outcome.FULFILLING, Component.literal("the recipe was served"));
        assertAsk(helper, last(charge.asked), p, WishSource.PRESET, id, WishAsk.Magic.REPLAY, "a recipe press");
        assertAsk(helper, last(charge.charged), p, WishSource.PRESET, id, WishAsk.Magic.REPLAY, "its charge");

        ItemStack made = new ItemStack(Items.STICK);
        made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        WishItemCatalogue.remember(server, made);
        var entries = WishItemCatalogue.resolve(name);
        helper.assertTrue(entries.size() == 1, Component.literal("the catalogue did not remember the fixture"));
        WishGiveAgain.Result r = WishGiveAgain.give(p, name);
        helper.assertValueEqual(r, WishGiveAgain.Result.GAVE, Component.literal("Give me one"));
        assertAsk(helper, last(charge.again), p, WishSource.PRESET, entries.get(0).key(), WishAsk.Magic.REPLAY,
                "Give me one");
        helper.succeed();
    }

    /**
     * A real Builder make (told BUILDER, charged as one), then "Make another one" ({@code /moddy again}) and a hotbar
     * fire of it: each told PRESET, its {@code builder:} recipe and REPLAY, and refused in the charge's words.
     */
    @GameTest(template = "empty_test", batch = "chargeAskReplays", timeoutTicks = 40000)
    public static void makeAnotherOneAndAHotbarFireAreToldAReplay(GameTestHelper helper) {
        String line = "That one is still resting.";
        Told charge = standIn(helper, new Told(a -> a.magic() == WishAsk.Magic.REPLAY ? Optional.of(line)
                : Optional.empty()));
        builderOn(helper);
        ServerPlayer p = child(helper, "gtcharge9");
        Set<String> had = Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
        makeACat(helper, p);
        AtomicReference<String> recipe = new AtomicReference<>();
        // Each poll waits for the make; once it stands, the replays below are refused at once, so a poll that throws
        // after the make simply tries them again (a new proxy each time) until the test passes or times out.
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            {
                BuilderSpecStore.Made m = null;
                for (BuilderSpecStore.Made k : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
                    if (!had.contains(k.id())) {
                        m = k;
                    }
                }
                helper.assertTrue(m != null, Component.literal("the make is not on his shelf yet"));
                assertAsk(helper, charge.asked.get(0), p, WishSource.BUILDER, "", WishAsk.Magic.BUILDER, "the make");
                assertAsk(helper, last(charge.charged), p, WishSource.BUILDER, "", WishAsk.Magic.BUILDER, "its charge");
                recipe.set(BuilderAgain.recipeId(m.id()));

                // "Make another one": the page's command.
                int mark = MockPlayerNetwork.chat(p).size();
                run(helper, p, ChildCommands.again(recipe.get()).substring(1));
                assertAsk(helper, last(charge.asked), p, WishSource.PRESET, recipe.get(), WishAsk.Magic.REPLAY,
                        "Make another one");
                helper.assertValueEqual(count(chatSince(p, mark), line), 1L, Component.literal("refused, said once"));

                // a hotbar fire of the same thing
                int mark2 = MockPlayerNetwork.chat(p).size();
                ItemStack proxy = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                        WatchPanel.Action.CARD, m.id(), m.id(), recipe.get(), m.id(), "", List.of()), p.getUUID());
                WishHotbar.Use used = WishHotbar.use(p, proxy);
                helper.assertTrue(used.used() == WishHotbar.Used.WISHED
                                && used.outcome() == WishService.Outcome.BANK_EMPTY,
                        Component.literal("the fire was not refused by the charge: " + used));
                assertAsk(helper, last(charge.asked), p, WishSource.PRESET, recipe.get(), WishAsk.Magic.REPLAY,
                        "a hotbar fire");
                helper.assertValueEqual(count(chatSince(p, mark2), line), 1L, Component.literal("refused, said once"));
                helper.assertValueEqual(charge.charged.size(), 1, Component.literal("only the make was charged"));
            }
        });
    }
}
