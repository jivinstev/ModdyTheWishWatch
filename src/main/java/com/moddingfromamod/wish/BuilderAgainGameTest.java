package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.runtime.RuntimeConfig;
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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * <b>A Builder or Apprentice make, made again and fired from the hotbar, where it runs.</b> The make goes through
 * the real entry point (the Builder's menus and its Make command; the Apprentice's page), its tile is found on his real
 * watch, the real page offers "Make another one" and "Add to hotbar", the proxy is put there by the page's own press
 * and fired through {@link WishHotbar#use}, and a SECOND creature of the same kind stands in the world. A stand-in
 * charge records what the world's charge is shown for the make and for the fire: the make says "Builder: …", the fire
 * is a replay in the make's own name, with the recipe flag set both times.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderAgainGameTest {

    private BuilderAgainGameTest() {
    }

    /** What a stand-in charge was asked: the submit check (text, recipe) and the charge (wish, agent). */
    private record Asked(String what, String text, boolean flag, UUID wishId) {
    }

    /** A charge that refuses nothing and remembers every question it was asked. */
    private static final class Recording implements WishCharge {
        final List<Asked> asked = new CopyOnWriteArrayList<>();

        @Override
        public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
            asked.add(new Asked("submit", text, recipe, null));
            return false;
        }

        @Override
        public Paid charge(UUID player, UUID wishId, boolean agent) {
            asked.add(new Asked("charge", "", agent, wishId));
            return Paid.FREE;
        }
    }

    private static List<net.minecraft.world.entity.Entity> tagged(GameTestHelper helper, String id) {
        List<net.minecraft.world.entity.Entity> out = new ArrayList<>();
        for (var e : helper.getLevel().getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.Mob && e.isAlive() && e.getTags().contains("wishmob_" + id)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The tile his watch shows for thing {@code id}, on whichever page it is, or null. */
    private static WatchPanel.Cell tileOf(ServerPlayer p, String id) {
        String me = p.getStringUUID();
        WatchPanel.Layout first = WatchMenu.hisOwnFor(me, 0);
        for (int page = 0; page < first.pageCount(); page++) {
            WatchPanel.Layout l = page == 0 ? first : WatchMenu.hisOwnFor(me, page);
            for (WatchPanel.Cell c : l.cells()) {
                if (c.family() == WatchPanel.Family.CREATION && c.arg().equals(id)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** The make he keeps that was not on his shelf before ({@code had}), newest, or null. */
    private static BuilderSpecStore.Made newMake(ServerPlayer p, Set<String> had) {
        BuilderSpecStore.Made newest = null;
        for (BuilderSpecStore.Made m : BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).values()) {
            if (!had.contains(m.id()) && (newest == null || m.at() > newest.at())) {
                newest = m;
            }
        }
        return newest;
    }

    private static Set<String> keptNow(ServerPlayer p) {
        return Set.copyOf(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()).keySet());
    }

    private static ItemStack proxyIn(ServerPlayer p, String recipe) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (recipe.equals(WishHotbar.recipeOf(inv.getItem(i)))) {
                return inv.getItem(i);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Builder on, no card, no rise; a recording charge in force. Undone after the test. */
    private static Recording setUp(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        BuilderMake.takeCard = false;
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        Recording charge = new Recording();
        Plugins.shared().replaceForTest(WishCharge.class, charge);
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().replaceForTest(WishCharge.class, null);
            BuilderMake.takeCard = true;
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        return charge;
    }

    /**
     * Stage 1 (once the make stands): his tile carries the make's recipe, its page offers both tiles, "Add to hotbar"
     * puts the proxy in his hotbar, and using it is a recipe press. Returns the replay's outcome.
     */
    private static WishService.Outcome addAndFire(GameTestHelper helper, ServerPlayer p, BuilderSpecStore.Made made) {
        String recipe = BuilderAgain.recipeId(made.id());
        WatchPanel.Cell tile = tileOf(p, made.id());
        helper.assertTrue(tile != null, Component.literal("his watch has no tile for '" + made.id() + "'"));
        helper.assertValueEqual(tile.recipeId(), recipe, Component.literal("the tile's recipe"));
        String page = ThingPage.describe(ThingPage.verbs(tile, p.getUUID(), p));
        helper.assertTrue(page.contains("✨ Make another one"), Component.literal("no Make another one: " + page));
        helper.assertTrue(page.contains(WishHotbar.ADD_LABEL), Component.literal("no Add to hotbar: " + page));
        p.getInventory().clearContent();
        helper.assertTrue(WishHotbar.press(p, WishHotbar.ADD_ID, tile), Component.literal("the page handles Add to hotbar"));
        ItemStack proxy = proxyIn(p, recipe);
        helper.assertTrue(!proxy.isEmpty(), Component.literal("no proxy on his hotbar for " + recipe));
        WishHotbar.Use used = WishHotbar.use(p, proxy);
        helper.assertTrue(used.used() == WishHotbar.Used.WISHED, Component.literal("the proxy was not used: " + used));
        return used.outcome();
    }

    /** What a stand-in charge saw for the replay wish {@code w}: a replay, in the make's own name, never "Builder: …". */
    private static void chargeSawAReplay(GameTestHelper helper, Recording charge, Wish w, String words) {
        helper.assertTrue(charge.asked.stream().anyMatch(a -> a.what().equals("submit") && a.flag()
                        && a.text().equals(words) && !a.text().startsWith(BuilderMake.TEXT_PREFIX)),
                Component.literal("the charge's submit check did not see a replay of '" + words + "': " + charge.asked));
        helper.assertTrue(charge.asked.stream().anyMatch(a -> a.what().equals("charge") && !a.flag()
                        && w.id().equals(a.wishId())),
                Component.literal("the replay was not charged as a no-model wish: " + charge.asked));
        helper.assertTrue(w.source() == WishSource.PRESET && words.equals(w.text())
                        && !w.text().startsWith(BuilderMake.TEXT_PREFIX),
                Component.literal("the replay's wish reads as a Builder make: " + w.source() + " '" + w.text() + "'"));
    }

    /** The replay wish of {@code recipe}, his, newest; or null. */
    private static Wish replayOf(GameTestHelper helper, ServerPlayer p, String recipe) {
        return WishBook.get(helper.getLevel().getServer()).recent(50).stream()
                .filter(w -> p.getUUID().equals(w.playerId()) && recipe.equals(w.templateId()))
                .reduce((a, b) -> b).orElse(null);
    }

    // ── a Builder make ────────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "builderAgainMenus", timeoutTicks = 40000)
    public static void aBuilderMakeGoesOnTheHotbarAndFiresAnotherOne(GameTestHelper helper) {
        Recording charge = setUp(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtagain");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        WishService.forget(child);
        Set<String> had = keptNow(child);

        // THE REAL BUILDER: its menus, then the tune dialog's Make, as a client sends it.
        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens when it is on"));
        click(helper, child, "creature");
        click(helper, child, "species/cat");
        click(helper, child, PickPage.NEXT_ID);   // past the parts
        click(helper, child, PickPage.NEXT_ID);   // no powers
        click(helper, child, PickPage.NEXT_ID);   // past the special moves
        BuilderFlow flow = BuilderMenus.flowOf(child);
        helper.assertTrue(flow != null && flow.step() == BuilderFlow.Step.TUNE,
                Component.literal("Next opens the tune dialog: " + (flow == null ? "no flow" : flow.step())));
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "moddy builder make " + flow.token + " 2 ocean 2 2", child.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("the Make command did not parse: " + e.getMessage()));
            return;
        }

        AtomicReference<BuilderSpecStore.Made> made = new AtomicReference<>();
        AtomicReference<String> words = new AtomicReference<>();
        AtomicInteger stage = new AtomicInteger();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            if (stage.get() == 0) {
                BuilderSpecStore.Made m = newMake(child, had);
                helper.assertTrue(m != null, Component.literal("the make is not on his shelf yet"));
                helper.assertTrue(tagged(helper, m.id()).size() == 1,
                        Component.literal("one '" + m.id() + "' stands: " + tagged(helper, m.id()).size()));
                // The MAKE itself was shown to the charge as a Builder make: "Builder: …", recipe flag set.
                helper.assertTrue(charge.asked.stream().anyMatch(a -> a.what().equals("submit") && a.flag()
                        && a.text().startsWith(BuilderMake.TEXT_PREFIX)),
                        Component.literal("the make's submit check: " + charge.asked));
                made.set(m);
                words.set(BuilderAgain.resolve(BuilderSpecStore.all(RuntimeConfig.playersDir(), child.getStringUUID()),
                        BuilderAgain.recipeId(m.id())).words());
                charge.asked.clear();
                stage.set(1);
                WishService.Outcome fired = addAndFire(helper, child, m);
                helper.assertTrue(fired == WishService.Outcome.FULFILLING,
                        Component.literal("the fire was not served as a recipe: " + fired));
            }
            BuilderSpecStore.Made m = made.get();
            String recipe = BuilderAgain.recipeId(m.id());
            Wish w = replayOf(helper, child, recipe);
            helper.assertTrue(w != null && w.status() == WishStatus.DONE,
                    Component.literal("the fire's wish: " + (w == null ? "none" : w.status() + " " + w.result())));
            helper.assertValueEqual(tagged(helper, m.id()).size(), 2, Component.literal("cats of '" + m.id() + "' standing"));
            chargeSawAReplay(helper, charge, w, words.get());
            helper.assertTrue(keptNow(child).size() == had.size() + 1,
                    Component.literal("a fire keeps no new make: another one of the same thing"));
        });
    }

    // ── an Apprentice make ────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "builderAgainApprentice", timeoutTicks = 40000)
    public static void anApprenticeMakeGoesOnTheHotbarAndFiresAnotherOne(GameTestHelper helper) {
        Recording charge = setUp(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtagainappr");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        System.setProperty(com.moddingfromamod.world.WorldCrafters.OPEN_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            System.clearProperty(com.moddingfromamod.world.WorldCrafters.OPEN_PROPERTY);
            p.closeContainer();
        });
        Set<String> had = keptNow(p);
        long refusedBefore = com.moddingfromamod.marshal.LocalOnly.refusedSoFar();

        // THE REAL APPRENTICE: typed words, its page, the first tile pressed.
        p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "moddy wishbox a green dino i can ride");
        helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("the Apprentice's page opened: " + p.containerMenu));
        ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);

        AtomicReference<BuilderSpecStore.Made> made = new AtomicReference<>();
        AtomicReference<String> words = new AtomicReference<>();
        AtomicInteger stage = new AtomicInteger();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            if (stage.get() == 0) {
                BuilderSpecStore.Made m = newMake(p, had);
                helper.assertTrue(m != null, Component.literal("the Apprentice make is not on his shelf yet"));
                helper.assertTrue(tagged(helper, m.id()).size() == 1,
                        Component.literal("one '" + m.id() + "' stands: " + tagged(helper, m.id()).size()));
                var r = BuilderAgain.resolve(BuilderSpecStore.all(RuntimeConfig.playersDir(), p.getStringUUID()),
                        BuilderAgain.recipeId(m.id()));
                helper.assertTrue(r.ok(), Component.literal("its recipe does not resolve: " + r.refusal()));
                made.set(m);
                words.set(r.words());
                charge.asked.clear();
                stage.set(1);
                WishService.Outcome fired = addAndFire(helper, p, m);
                helper.assertTrue(fired == WishService.Outcome.FULFILLING,
                        Component.literal("the fire was not served as a recipe: " + fired));
            }
            BuilderSpecStore.Made m = made.get();
            Wish w = replayOf(helper, p, BuilderAgain.recipeId(m.id()));
            helper.assertTrue(w != null && w.status() == WishStatus.DONE,
                    Component.literal("the fire's wish: " + (w == null ? "none" : w.status() + " " + w.result())));
            helper.assertValueEqual(tagged(helper, m.id()).size(), 2, Component.literal("rides of '" + m.id() + "' standing"));
            chargeSawAReplay(helper, charge, w, words.get());
            long refused = com.moddingfromamod.marshal.LocalOnly.refusedSoFar() - refusedBefore;
            helper.assertTrue(refused == 0, Component.literal(refused + " model or web call(s) asked"));
        });
    }

    // ── refusals ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A recipe that is not his, or whose make is gone, makes nothing and starts nothing (no wish in the book, no
     * agent): told once, in the same words.
     */
    @GameTest(template = "empty_test", batch = "builderAgainRefused", timeoutTicks = 400)
    public static void anotherChildsMakeAndAGoneOneAreRefused(GameTestHelper helper) throws Exception {
        Recording charge = setUp(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtagainno");
        WishService.forget(p);
        String brother = UUID.randomUUID().toString();
        String hisId = "gt2803_brothers_cat_" + brother.substring(0, 6);
        var spec = com.moddingfromamod.wishbuilder.BuilderSpec.read(com.google.gson.JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Bro\"}")
                .getAsJsonObject());
        helper.assertTrue(BuilderSpecStore.put(RuntimeConfig.playersDir(), brother, new BuilderSpecStore.Made(hisId, hisId,
                "creature", spec.toJson(), "w", System.currentTimeMillis())), Component.literal("the brother's make was kept"));
        var book = WishBook.get(helper.getLevel().getServer());
        int mark = MockPlayerNetwork.chat(p).size();
        for (String recipe : List.of(BuilderAgain.recipeId(hisId), BuilderAgain.recipeId("gt2803_never_made"))) {
            // A proxy carrying it, his own: the hotbar is where a forged or stale recipe would arrive.
            ItemStack proxy = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                    "Not Mine", "Not Mine", recipe, "Not Mine", "", List.of()), p.getUUID());
            WishHotbar.Use used = WishHotbar.use(p, proxy);
            helper.assertTrue(used.used() == WishHotbar.Used.WISHED && used.outcome() == WishService.Outcome.EMPTY,
                    Component.literal(recipe + " was not refused: " + used));
            helper.assertTrue(book.recent(50).stream().noneMatch(w -> recipe.equals(w.templateId())),
                    Component.literal(recipe + " filed a wish"));
        }
        helper.assertTrue(charge.asked.isEmpty(), Component.literal("a refused recipe asks the charge nothing: " + charge.asked));
        List<String> said = MockPlayerNetwork.chat(p);
        long told = said.subList(Math.min(mark, said.size()), said.size()).stream()
                .filter(s -> s.contains(BuilderAgain.GONE)).count();
        helper.assertValueEqual((int) told, 2, Component.literal("told once per press: " + said));
        helper.succeed();
    }

    /** Press the tile (or control) meaning {@code id} on the open pick chest, as a click. */
    private static void click(GameTestHelper helper, ServerPlayer child, String id) {
        if (!(child.containerMenu instanceof MenuKit.PickMenu menu)) {
            helper.fail(Component.literal("no pick chest is open (pressing " + id + "): " + child.containerMenu));
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
        menu.clicked(slot, 0, ClickType.PICKUP, child);
    }
}
