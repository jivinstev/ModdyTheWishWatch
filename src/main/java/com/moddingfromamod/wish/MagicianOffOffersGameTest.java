package com.moddingfromamod.wish;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.config.Magician;
import com.moddingfromamod.config.SettingsCommand;
import com.moddingfromamod.config.SettingsOverride;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import com.moddingfromamod.wishbuilder.ApprenticeWords;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.world.WorldCrafters;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Hardening: the Magician is offered only when it will answer this player</b> (#78). Seen on 1.0.9: with the
 * Magician never set up, the Apprentice's page had no fitting make and offered "✪ Moddy wishes it from scratch";
 * pressing it sent the words back to the Apprentice, which opened the same page again.
 *
 * <p>Walks every place that offers the Magician as the next step, for the host and a guest (a Wish Magician by role
 * whose world has no Magician) and for a Crafter: the Apprentice's no-fit answer and its page's last tile (and what
 * pressing it does), the wish box, the 📖 page's "Typed wishes" toggle, the watch's "✍ Wish for something new"
 * tooltip and a thing's "Wish about this"; and, in its own batch, the re-wish reader's
 * "Did you mean…" page. Each asks {@link MagicianDoor#offered}. The host's no-fit answer names the setup.
 *
 * <p>The overrides are hidden, so the mod runs as the public one does (Magician off) whatever plug-ins are loaded:
 * process-wide, so each test has its own batch.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MagicianOffOffersGameTest {

    private MagicianOffOffersGameTest() {
    }

    private static List<String> since(ServerPlayer p, int mark) {
        List<String> all = MockPlayerNetwork.chat(p);
        return all.subList(Math.min(mark, all.size()), all.size());
    }

    private static boolean saysMagicianWorks(String s) {
        return s.contains(ApprenticeWords.MAGICIAN_INSTEAD_LABEL) || s.contains(Rewish.ASK_MAGICIAN)
                || s.contains("Magician magic") || s.contains(ThingPage.WISH_ABOUT_MAGICIAN)
                || s.contains(ApprenticeWords.TO_THE_MAGICIAN);
    }

    private static String tilesOf(ServerPlayer p) {
        return p.containerMenu instanceof MenuKit.PickMenu m ? m.page().tiles().toString() : "";
    }

    @GameTest(template = "empty_test", batch = "magician_off_offers", timeoutTicks = 400)
    public static void noPageOrLineOffersTheMagicianWhileItIsOff(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "magician_off_offers");
        NextWish.quietForTest(p.getUUID());
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);   // the keyword page, answered at once
        String tag = Long.toString(System.nanoTime(), 36);
        Plugins.shared().hideForTest(SettingsOverride.class, true);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "false");   // the door shut: a Wish Magician by role
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
            helper.assertFalse(Magician.allowsAi(), Component.literal("control: Magician mode is off"));
            helper.assertFalse(CrafterService.isCrafter(p), Component.literal("control: a Wish Magician by role"));
            helper.assertFalse(MagicianDoor.offered(p), Component.literal("the one answer: no Magician to offer"));

            for (boolean host : new boolean[] {false, true}) {
                SettingsCommand.hostForTest(host ? p.getUUID() : null);
                String who = host ? "the host" : "a guest";
                helper.assertValueEqual(MagicianDoor.hostMayTurnOn(p), host,
                        Component.literal("control: " + who + (host ? " may" : " may not") + " turn the Magician on"));

                // ── the Apprentice, no fitting make ──────────────────────────────────────────────
                p.closeContainer();
                int mark = MockPlayerNetwork.chat(p).size();
                WishService.Outcome out = WishService.submit(p, "do my homework " + tag, WishSource.TYPED);
                helper.assertValueEqual(out, WishService.Outcome.APPRENTICE, Component.literal("the Apprentice answers"));
                List<String> said = since(p, mark);
                helper.assertFalse(saysMagicianWorks(tilesOf(p)),
                        Component.literal(who + ": no-fit page offers no Magician: " + tilesOf(p)));
                helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1,
                        Component.literal(who + ": no page with nothing on it but a Magician who is not there"));
                helper.assertTrue(said.stream().anyMatch(l -> l.contains(ApprenticeWords.NO_MAGICIAN_TRY)),
                        Component.literal(who + ": told what to do instead (the Builder, other words): " + said));
                helper.assertValueEqual(said.stream().anyMatch(l -> l.contains(ApprenticeWords.HOST_MAGICIAN_OFF)), host,
                        Component.literal(who + ": the setup is named for the host only: " + said));
                helper.assertTrue(said.stream().noneMatch(MagicianOffOffersGameTest::saysMagicianWorks),
                        Component.literal(who + ": no line promises the Magician: " + said));

                // ── the Apprentice's page of makes, and its last tile ────────────────────────────
                p.closeContainer();
                WishService.submit(p, "a lion", WishSource.TYPED);
                helper.assertTrue(ApprenticeService.pendingOffers(p.getUUID()) >= 1,
                        Component.literal(who + ": control: the Apprentice's page opened"));
                String tiles = tilesOf(p);
                helper.assertFalse(saysMagicianWorks(tiles), Component.literal(who + ": no ✪ tile on the page: " + tiles));
                helper.assertTrue(tiles.contains(ApprenticeWords.NONE_LABEL), Component.literal(who + ": its last tile is "
                        + "\"None of these\": " + tiles));
                mark = MockPlayerNetwork.chat(p).size();
                int book = WishBook.get(p.level().getServer()).recent(40).size();
                ApprenticeService.pressForTest(p, ApprenticeWords.NONE);
                said = since(p, mark);
                helper.assertTrue(said.stream().anyMatch(l -> l.contains(ApprenticeWords.NONE_SAID)),
                        Component.literal(who + ": pressed, it points at the Builder: " + said));
                helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1,
                        Component.literal(who + ": and does not open the same page again"));
                helper.assertValueEqual(WishBook.get(p.level().getServer()).recent(40).size(), book,
                        Component.literal(who + ": no wish recorded for an agent"));

                // ── the wish box ─────────────────────────────────────────────────────────────────
                TuneDialog box = WishCompose.boxFor(p, "");
                helper.assertFalse(saysMagicianWorks(box.toString()) || box.body().stream().anyMatch(l -> l.words()
                                .startsWith("✪ Magician:") && !l.words().contains("off")),
                        Component.literal(who + ": the wish box says the Magician is off: " + box.body()));
                helper.assertTrue(box.inputs().stream().noneMatch(i -> i instanceof TuneDialog.Choice),
                        Component.literal(who + ": and has no Magician choice: " + box.inputs()));

                // ── the 📖 page's toggle, the watch's compose tile, a thing's page ────────────────
                helper.assertTrue(WatchMenu.guideFor(p).cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.WISH_MODE),
                        Component.literal(who + ": no \"Typed wishes: … Magician ✪\" toggle on the 📖 page"));
                WatchPanel.Cell compose = WatchMenu.planFor(p).cells().stream()
                        .filter(c -> c.action() == WatchPanel.Action.COMPOSE).findFirst().orElse(null);
                helper.assertTrue(compose != null && compose.lore().equals(Crafters.COMPOSE_LORE),
                        Component.literal(who + ": the compose tile says the Apprentice answers: " + compose));
                WatchPanel.Cell thing = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                        "Rex", "Rex", "", "Rex", WatchIcons.UNKNOWN, List.of());
                String verbs = ThingPage.describe(ThingPage.verbs(thing, p.getUUID(), p));
                helper.assertTrue(!saysMagicianWorks(verbs) && verbs.contains(ThingPage.WISH_ABOUT),
                        Component.literal(who + ": a thing's page wishes the Apprentice's way: " + verbs));
            }
            SettingsCommand.hostForTest(null);

            // ── a Crafter (the door open): never offered the Magician, as before (his sign-up lines are not an offer) ──
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
            CrafterService.forgetForTest(p.getUUID());
            helper.assertTrue(CrafterService.isCrafter(p), Component.literal("control: off the list, door open: a Crafter"));
            helper.assertFalse(MagicianDoor.offered(p), Component.literal("a Crafter is never offered the Magician"));
            p.closeContainer();
            WishService.submit(p, "a lion", WishSource.TYPED);
            String crafterTiles = tilesOf(p);
            helper.assertTrue(crafterTiles.contains(ApprenticeWords.NONE_LABEL) && !saysMagicianWorks(crafterTiles),
                    Component.literal("a Crafter's page ends in \"None of these\": " + crafterTiles));
            p.closeContainer();
        } finally {
            SettingsCommand.hostForTest(null);
            Plugins.shared().hideForTest(SettingsOverride.class, false);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
            p.closeContainer();
            ApprenticeService.forget(p.getUUID());
            CrafterService.forgetForTest(p.getUUID());
            WishService.forget(p);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /**
     * The re-wish reader's CLOSE page ("make fluffy bigger" with two Fluffys): with the Magician off its way on is the
     * Apprentice's "✨ Something else", never "✪ Ask the Magician".
     */
    @GameTest(template = "empty_test", batch = "magician_off_rewish", timeoutTicks = 12000)
    public static void theReWishPageOffersNoMagicianWhileItIsOff(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "magician_off_rewish");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        System.setProperty(WorldCrafters.OPEN_PROPERTY, "false");
        CrafterService.forgetForTest(p.getUUID());
        Plugins.shared().hideForTest(SettingsOverride.class, true);
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            Plugins.shared().hideForTest(SettingsOverride.class, false);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
        });
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<Boolean> checked = new AtomicReference<>(false);
        BuilderSpec b = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"name\":\"Fluffy Blue\"}").getAsJsonObject());
        BuilderSpec r = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"name\":\"Fluffy Red\"}").getAsJsonObject());
        var who = BuilderMake.Who.of(p);
        BuilderMake.run(server, who, b, null, BuilderMake.protocolForTest(), false, false)
                .thenComposeAsync(m1 -> {
                    java.util.concurrent.CompletableFuture<BuilderMake.Result> f = new java.util.concurrent.CompletableFuture<>();
                    server.execute(() -> BuilderMake.run(server, who, r, null, BuilderMake.protocolForTest(), false, false)
                            .whenComplete((m2, e) -> { if (e != null) f.completeExceptionally(e); else f.complete(m2); }));
                    return f;
                })
                .thenAcceptAsync(m2 -> server.execute(() -> {
                    try {
                        if (Magician.allowsAi() || MagicianDoor.offered(p)) {
                            wrong.set("control: the Magician should be off");
                            return;
                        }
                        Rewish.onForTest(true);
                        if (!Rewish.tryAnswer(p, "make fluffy bigger", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                            wrong.set("'make fluffy bigger' with two Fluffys was not offered");
                            return;
                        }
                        if (!(p.containerMenu instanceof MenuKit.PickMenu menu)) {
                            wrong.set("no offer page opened: " + p.containerMenu);
                            return;
                        }
                        PickPage.Tile other = menu.page().tiles().stream().filter(t -> t.id().equals(Rewish.ELSE_ID))
                                .findFirst().orElse(null);
                        if (other == null || !other.label().equals(Rewish.SOMETHING_ELSE)
                                || saysMagicianWorks(menu.page().tiles().toString())) {
                            wrong.set("the way on should be the Apprentice's, not the Magician's: " + menu.page().tiles());
                        }
                    } finally {
                        p.closeContainer();
                        checked.set(true);
                    }
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(checked.get(), Component.literal("still making the cats"));
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
        });
    }
}
