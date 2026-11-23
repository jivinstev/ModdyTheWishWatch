package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wishbuilder.ApprenticeWords;
import com.moddingfromamod.wishbuilder.BuilderMenus;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.world.WorldCrafters;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserWhiteListEntry;

/**
 * <b>Both wish doors, for every role, end where {@link WishRoute} says</b> (S4). The doors are driven the
 * way a client drives them: the wish box's Submit ({@code /moddy wishbox <words>}) and {@code /wish <words>}, as
 * commands run by the player; a tile press is the chest's own press.
 *
 * <ul>
 *   <li>A CRAFTER: both doors open the Apprentice's pick page; his words are held nowhere; a tile makes its
 *       thing at once (no tune dialog); "None of these" is honest and the Magician line is said at most once
 *       in ten minutes; a wish with nothing in Moddy's Parts opens no page; a question gets the hint.</li>
 *   <li>A MAGICIAN: both doors go to the agent by default; his toggle (kept on his shelf) or
 *       {@code /moddy apprentice} sends a wish to the Apprentice; "Ask Moddy instead" sends those words on to the
 *       agent; a question is the agent's even with the toggle on.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ApprenticeGameTest {

    private ApprenticeGameTest() {
    }

    private static List<String> since(ServerPlayer p, int mark) {
        List<String> all = MockPlayerNetwork.chat(p);
        return all.subList(Math.min(mark, all.size()), all.size());
    }

    private static void run(ServerPlayer p, String command) {
        p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
    }

    /**
     * <b>Words to a finished thing, LOCAL ONLY</b>. A Crafter types a wish, the Apprentice offers its page, he
     * presses the first tile and the REAL make runs (no seam): from the words to DONE, not one model or web call is
     * asked ({@code marshal/LocalOnly} refuses and counts each). The matching, the page and the make are all covered;
     * a regression anywhere on the path turns this red and names itself in the log ("local only: refused …").
     */
    @GameTest(template = "empty_test", batch = "apprentice_local", timeoutTicks = 12000)
    public static void fromTheWordsToTheThingNoModelAndNoWebCall(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "apprentice_local");
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            p.closeContainer();
        });
        long refusedBefore = com.moddingfromamod.marshal.LocalOnly.refusedSoFar();
        long startedMs = System.currentTimeMillis();
        run(p, "moddy wishbox a green dino i can ride");
        helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("the Apprentice's page opened: " + p.containerMenu));
        ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);   // the real make: no seam
        var book = WishBook.get(helper.getLevel().getServer());
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            long refused = com.moddingfromamod.marshal.LocalOnly.refusedSoFar() - refusedBefore;
            helper.assertTrue(refused == 0, Component.literal(refused + " model or web call(s) asked between his words and "
                    + "the finished thing (see the 'local only: refused' lines)"));
            var made = book.recent(50).stream()
                    .filter(w -> p.getUUID().equals(w.playerId()) && w.source() == WishSource.BUILDER
                            && w.createdMs() >= startedMs)
                    .findFirst();
            helper.assertTrue(made.isPresent() && made.get().status() == WishStatus.DONE,
                    Component.literal("the Apprentice make settled DONE: " + made.map(w -> w.status().name()).orElse("not made yet")));
        });
    }

    @GameTest(template = "empty_test", batch = "apprentice", timeoutTicks = 400)
    public static void bothDoorsEndWhereTheRouteSaysForACrafterAndAMagician(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "apprentice_kid");
        var list = helper.getLevel().getServer().getPlayerList();
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);   // this test is the keyword page; the layer's has its own batch
        String tag = Long.toString(System.nanoTime(), 36);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
            helper.assertTrue(CrafterService.isCrafter(p), Component.literal("control: off the list with the door open, a Crafter"));

            // ── A CRAFTER, DOOR 1: the wish box ────────────────────────────────────────────────
            String words = "a green dino i can ride";
            run(p, "moddy wishbox " + words);
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("the wish box opened the Apprentice's page: " + p.containerMenu));
            int offered = ApprenticeService.pendingOffers(p.getUUID());
            helper.assertTrue(offered >= 3 && offered <= 5, Component.literal("three to five makes: " + offered));
            helper.assertValueEqual(ApprenticeService.pendingWords(p.getUUID()), "", Component.literal("a Crafter's words are not held for the page"));
            helper.assertFalse(SafetyGrader.startedFor(words), Component.literal("no grader: nothing he typed went to a model"));
            var chat = MockPlayerNetwork.chat(p);
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains("Moddy's Apprentice")), Component.literal("it says it is Moddy's Apprentice: " + chat));

            // A tile MAKES its thing at once: no tune dialog, the page closes, and the spec is the
            // one the words named (green → Forest). The make itself is BuilderMakeGameTest's.
            java.util.concurrent.atomic.AtomicReference<BuilderSpec> made = new java.util.concurrent.atomic.AtomicReference<>();
            BuilderMenus.apprenticeMadeForTest(p.getUUID(), made::set);
            ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);
            helper.assertTrue(BuilderMenus.flowOf(p) == null, Component.literal("no Builder dialog opens: the tile makes it"));
            helper.assertFalse(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("the page closed"));
            BuilderSpec spec = made.get();
            helper.assertTrue(spec != null, Component.literal("the tile made something"));
            helper.assertValueEqual(spec.kind(), BuilderSpec.Kind.RIDE, Component.literal("a dino to ride"));
            helper.assertTrue(spec.from().contains("rex") || spec.from().contains("dinosaur") || spec.from().contains("raptor")
                    || spec.from().contains("triceratops"), Component.literal("a dinosaur body: " + spec.from()));
            helper.assertValueEqual(spec.scheme(), "green", Component.literal("green: it is made green (#2421, one colour on a creature is that colour)"));
            helper.assertFalse(spec.name().isBlank() || spec.name().toLowerCase(java.util.Locale.ROOT).contains("green dino"),
                    Component.literal("Moddy's name, never his words: " + spec.name()));

            // ── A CRAFTER, DOOR 2: /wish ───────────────────────────────────────────────────────
            p.closeContainer();
            run(p, "wish a pet dragon with wings");
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu && ApprenticeService.pendingOffers(p.getUUID()) >= 1,
                    Component.literal("/wish opened the same page"));

            // "None of these": honest, and the Magician line at most once in ten minutes.
            int mark = MockPlayerNetwork.chat(p).size();
            ApprenticeService.pressForTest(p, ApprenticeWords.NONE);
            long nudges = since(p, mark).stream().filter(l -> l.contains("A Wish Magician could wish for exactly what you typed")).count();
            helper.assertTrue(since(p, mark).stream().anyMatch(l -> l.contains(ApprenticeWords.NONE_SAID)), Component.literal("None of these is answered"));
            helper.assertValueEqual(nudges, 1L, Component.literal("the Magician line, once"));

            // Out of Moddy's Parts: no page, said so, and NO second nudge inside ten minutes.
            mark = MockPlayerNetwork.chat(p).size();
            p.closeContainer();
            WishService.Outcome out = WishService.submit(p, "do my homework " + tag, WishSource.TYPED);
            helper.assertValueEqual(out, WishService.Outcome.APPRENTICE, Component.literal("still the Apprentice's to answer"));
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("nothing stretched to fit: no page"));
            var after = since(p, mark);
            helper.assertTrue(after.stream().anyMatch(l -> l.contains("isn't in Moddy's Parts")), Component.literal("said honestly: " + after));
            helper.assertTrue(after.stream().noneMatch(l -> l.contains("A Wish Magician could")), Component.literal("capped: no second nudge so soon"));
            helper.assertTrue(after.stream().noneMatch(l -> l.contains(tag)), Component.literal("his words are not said back"));

            // A question is not a make.
            mark = MockPlayerNetwork.chat(p).size();
            WishService.submit(p, "how do I get sand " + tag + "?", WishSource.TYPED);
            helper.assertTrue(since(p, mark).stream().anyMatch(l -> l.contains("What do I do now?")), Component.literal("a question gets the hint"));
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("and no page"));

            // ── A MAGICIAN ───────────────────────────────────────────────────────────────────────
            list.getWhiteList().add(new UserWhiteListEntry(p.getGameProfile()));
            CrafterService.check(p);
            ApprenticeService.forget(p.getUUID());
            helper.assertFalse(CrafterService.isCrafter(p), Component.literal("seated: a Magician"));
            helper.assertFalse(ApprenticeService.prefersApprentice(p), Component.literal("a Magician's default is Magician magic"));
            WatchPanel.Cell mode = WatchMenu.guideFor(p).cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH_MODE).findFirst().orElse(null);
            helper.assertTrue(mode != null && mode.label().contains("Magician ✪"), Component.literal("his 📖 page has the toggle, on Magician: " + mode));

            // Door 1 and door 2, default: the agent.
            WishService.forget(p);
            String w1 = "a magician's own dragon " + tag;
            run(p, "moddy wishbox " + w1);
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("the wish box: no Apprentice page for a Magician"));
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().contains(tag)),
                    Component.literal("his wish is in the book, on its way to the agent"));
            WishService.forget(p);
            String w2 = "a second dragon from chat " + tag;
            run(p, "wish " + w2);
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("/wish: the agent too"));

            // /moddy apprentice: this one wish, whatever the toggle says., the collaborate mode: the page only
            // when the Apprentice is SURE, with the Magician's ✪ as the SECOND tile; otherwise straight to the Magician.
            WishService.forget(p);
            run(p, "moddy apprentice a lion");
            helper.assertTrue(ApprenticeService.pendingOffers(p.getUUID()) >= 1, Component.literal("sure: /moddy apprentice opened the page"));
            helper.assertValueEqual(ApprenticeService.pendingWords(p.getUUID()), "a lion", Component.literal("a Magician's words are held for ✪"));
            var wizPage = ((MenuKit.PickMenu) p.containerMenu);
            helper.assertTrue(wizPage != null, Component.literal("a pick page"));
            helper.assertValueEqual(wizPage.page().tiles().get(1).id(), ApprenticeWords.NONE,
                    Component.literal("✪ is the second tile, right after the Apprentice's make: " + wizPage.page().tiles().get(1).label()));
            helper.assertValueEqual(wizPage.page().tiles().get(1).label(), ApprenticeWords.MAGICIAN_INSTEAD_LABEL, Component.literal("its words"));
            // ✪: those words, to the agent.
            WishService.forget(p);
            ApprenticeService.pressForTest(p, ApprenticeWords.NONE);
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().equals("a lion")),
                    Component.literal("✪ sent the words on to the agent as the wish they were"));
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("and the page is gone"));
            // Not sure (some words left unread), and nothing at all: no page, straight to the Magician.
            WishService.forget(p);
            int before = since(p, mark).size();
            run(p, "moddy apprentice a robot dog " + tag);
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("close: no page for a Magician"));
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().equals("a robot dog " + tag)),
                    Component.literal("close: the words went straight to the Magician"));
            helper.assertTrue(since(p, mark).stream().skip(before).anyMatch(l -> l.contains(ApprenticeWords.TO_THE_MAGICIAN)),
                    Component.literal("and he is told, in one line"));
            WishService.forget(p);
            run(p, "moddy apprentice do my homework " + tag);
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("nothing: no page"));
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().contains("homework")),
                    Component.literal("nothing in Moddy's Parts: straight to the Magician"));
            WishService.forget(p);

            // The toggle: kept on his shelf, and it changes where the SAME door goes.
            boolean on = ApprenticeService.toggle(p);
            helper.assertTrue(on && ApprenticeService.prefersApprentice(p), Component.literal("toggled to Apprentice"));
            ApprenticeService.forget(p.getUUID());   // a new session reads it back off the shelf
            helper.assertTrue(ApprenticeService.prefersApprentice(p), Component.literal("remembered on his shelf"));
            mode = WatchMenu.guideFor(p).cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH_MODE).findFirst().orElse(null);
            helper.assertTrue(mode != null && mode.label().contains("Apprentice ✨"), Component.literal("the toggle says so: " + mode));
            helper.assertTrue(WishCompose.offersChoice(p), Component.literal("and his wish box carries the choice (#2001; its own test is below)"));
            WishService.forget(p);
            run(p, "moddy wishbox a castle");
            helper.assertTrue(ApprenticeService.pendingOffers(p.getUUID()) >= 1, Component.literal("the wish box now opens the Apprentice's page"));
            // A question stays the agent's.
            WishService.forget(p);
            ApprenticeService.pressForTest(p, MenuKit.class.getName());   // an unknown tile id closes the page, makes nothing
            WishService.Outcome asked = WishService.submit(p, "how do I tame a wolf " + tag + "?", WishSource.TYPED);
            helper.assertTrue(asked != WishService.Outcome.APPRENTICE, Component.literal("a Magician's question is the agent's: " + asked));
            ApprenticeService.toggle(p);   // back to Magician, so the shelf row a later run reads is the default
            helper.succeed();
        } finally {
            list.getWhiteList().remove(p.getGameProfile());
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
            for (Wish w : WishBook.get(p.getServer()).recent(40)) {
                if (w.text().contains(tag) || w.playerId() != null && w.playerId().equals(p.getUUID())) {
                    try {
                        Marshal.global().cancel(w.id(), CancelMode.STOP, "", "");
                    } catch (RuntimeException ignored) {
                        // already over
                    }
                    WishProgress.clearWishBar(w.id());
                }
            }
            WishQueue.global().clear();
            BuilderMenus.forget(p.getUUID());
            ApprenticeService.forget(p.getUUID());
            CrafterService.forgetForTest(p.getUUID());
            MockPlayerNetwork.remove(p);
        }
    }

    /** The dialog the server just put on the wire for {@code p}, as vanilla's codec writes it. */
    private static com.google.gson.JsonObject shownDialog(GameTestHelper helper, ServerPlayer p) {
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("the control cannot see the wire: " + channel));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        com.google.gson.JsonObject last = null;
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            if (o instanceof net.minecraft.network.protocol.common.ClientboundShowDialogPacket d) {
                var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
                        helper.getLevel().registryAccess());
                last = net.minecraft.server.dialog.Dialog.DIRECT_CODEC.encodeStart(ops, d.dialog().value())
                        .getOrThrow().getAsJsonObject();
            }
        }
        helper.assertTrue(last != null, Component.literal("no dialog was sent to the client"));
        return last;
    }

    /** The id of the "Magic" dropdown's starting option, or null when the box has no dropdown. */
    private static String magicOf(com.google.gson.JsonObject dialog) {
        for (var e : dialog.getAsJsonArray("inputs")) {
            var in = e.getAsJsonObject();
            if (WishCompose.MAGIC_KEY.equals(in.get("key").getAsString())) {
                for (var o : in.getAsJsonArray("options")) {
                    if (o.getAsJsonObject().has("initial") && o.getAsJsonObject().get("initial").getAsBoolean()) {
                        return o.getAsJsonObject().get("id").getAsString();
                    }
                }
                return "";
            }
        }
        return null;
    }

    /**
     * <b>A Magician chooses the magic ON the wish box</b> (not in a separate piece of UI
     * he won't find). His box carries one dropdown, "Magic", above the words. It starts on what he chose last,
     * Submit carries the choice with the words, the choice is kept on his shelf (the SAME setting the 📖 page's
     * tile flips), and who answers is still {@link WishRoute}'s to say: a question stays the agent's. A blank box
     * changes nothing. A Crafter's box has no dropdown and no way round, and neither has a world with no Builder.
     */
    @GameTest(template = "empty_test", batch = "apprentice_wishbox", timeoutTicks = 400)
    public static void aMagicianChoosesTheMagicOnTheWishBoxItself(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "wishbox_wiz");
        NextWish.quietForTest(p.getUUID());   // no next-wish idea or its chat line: this box is about the magic
        var list = helper.getLevel().getServer().getPlayerList();
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        String tag = Long.toString(System.nanoTime(), 36);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");

            // ── A CRAFTER: no dropdown, and the command's choice does nothing for him ──────────
            helper.assertTrue(CrafterService.isCrafter(p), Component.literal("control: a Crafter first"));
            WishCompose.open(p);
            var crafterBox = shownDialog(helper, p);
            helper.assertTrue(magicOf(crafterBox) == null && crafterBox.getAsJsonArray("inputs").size() == 1,
                    Component.literal("a Crafter's wish box has no choice, only the words: " + crafterBox));
            String crafterSays = crafterBox.getAsJsonArray("body").toString();
            helper.assertTrue(crafterSays.contains("✨ Apprentice: ") && crafterSays.contains(WishCompose.MAGICIAN_SIGN_UP)
                            && crafterSays.contains(CrafterService.MAGICIAN_ADDRESS) && crafterSays.contains("open_url"),
                    Component.literal("it says the magic he has, and where a grown-up signs up for the other: " + crafterSays));
            int nudgeMark = MockPlayerNetwork.chat(p).size();
            WishCompose.open(p);
            shownDialog(helper, p);
            helper.assertTrue(since(p, nudgeMark).isEmpty(), Component.literal("the box's line is not a chat nudge: " + since(p, nudgeMark)));
            run(p, "moddy wishbox magic magician |a green dino i can ride");
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu && ApprenticeService.pendingOffers(p.getUUID()) >= 1,
                    Component.literal("a Crafter asking for Magician magic by hand still gets the Apprentice"));
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().noneMatch(w -> w.text().contains("green dino")),
                    Component.literal("and no wish went to the agent"));
            p.closeContainer();

            // ── A MAGICIAN ───────────────────────────────────────────────────────────────────────
            list.getWhiteList().add(new UserWhiteListEntry(p.getGameProfile()));
            CrafterService.check(p);
            ApprenticeService.forget(p.getUUID());
            if (ApprenticeService.prefersApprentice(p)) {
                ApprenticeService.choose(p, false);   // an earlier run's shelf row
            }
            WishCompose.open(p);
            var box = shownDialog(helper, p);
            helper.assertValueEqual(magicOf(box), WishCompose.MAGIC_MAGICIAN, Component.literal("the dropdown starts on Magician: " + box));
            var inputs = box.getAsJsonArray("inputs");
            helper.assertTrue(inputs.size() == 2 && inputs.get(1).getAsJsonObject().get("key").getAsString().equals(WishCompose.KEY),
                    Component.literal("the dropdown, then the words LAST: " + box));
            helper.assertValueEqual(box.getAsJsonObject("yes").getAsJsonObject("action").get("template").getAsString(),
                    "moddy wishbox magic $(magic) " + com.moddingfromamod.ui.TuneDialog.Words.MARK + "$(wish)",
                    Component.literal("Submit carries the choice, then the words"));
            String shown = box.toString();
            helper.assertTrue(shown.contains("✪ Magician: ") && shown.contains("✨ Apprentice: ") && !box.has("body"),
                    Component.literal("each option says which magic it is and why: " + shown));

            // Chosen Magician: the agent, and nothing changes on his shelf.
            WishService.forget(p);
            run(p, "moddy wishbox magic magician |a magician's dragon " + tag);
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("Magician chosen: no Apprentice page"));
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().equals("a magician's dragon " + tag)),
                    Component.literal("the wish is in the book, without the box's mark or the choice in its words"));

            // A BLANK box with Apprentice chosen is a cancel: it must not change what he had.
            run(p, "moddy wishbox magic apprentice |");
            helper.assertFalse(ApprenticeService.prefersApprentice(p), Component.literal("a blank Submit kept his choice as it was"));

            // Chosen Apprentice: the Apprentice's page, said nowhere in chat as a mode change, and REMEMBERED.
            WishService.forget(p);
            int mark = MockPlayerNetwork.chat(p).size();
            run(p, "moddy wishbox magic apprentice |a castle");
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu && ApprenticeService.pendingOffers(p.getUUID()) >= 1,
                    Component.literal("Apprentice chosen: its page of makes"));
            helper.assertTrue(since(p, mark).stream().noneMatch(l -> l.contains("Your typed wishes now use")),
                    Component.literal("the box's choice is quiet: the page itself says Apprentice"));
            ApprenticeService.forget(p.getUUID());   // a new session reads it back off the shelf
            helper.assertTrue(ApprenticeService.prefersApprentice(p), Component.literal("remembered on his shelf"));
            WatchPanel.Cell mode = WatchMenu.guideFor(p).cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH_MODE).findFirst().orElse(null);
            helper.assertTrue(mode != null && mode.label().contains("Apprentice ✨"), Component.literal("the 📖 page's tile is the same setting: " + mode));
            p.closeContainer();
            WishCompose.open(p);
            helper.assertValueEqual(magicOf(shownDialog(helper, p)), WishCompose.MAGIC_APPRENTICE,
                    Component.literal("and the next box starts on what he chose last"));

            // A QUESTION with Apprentice chosen is still the agent's (WishRoute, the one place).
            WishService.forget(p);
            run(p, "moddy wishbox magic apprentice |how do I tame a wolf " + tag + "?");
            helper.assertValueEqual(ApprenticeService.pendingOffers(p.getUUID()), -1, Component.literal("a question opens no Apprentice page"));

            // Words typed by hand that only LOOK like the choice are his words.
            WishService.forget(p);
            ApprenticeService.choose(p, false);
            run(p, "moddy wishbox magic carpet " + tag);
            helper.assertTrue(WishBook.get(p.getServer()).recent(40).stream().anyMatch(w -> w.text().equals("magic carpet " + tag)),
                    Component.literal("'magic carpet …' typed by hand is a wish for a magic carpet"));

            // A world with no Builder has no Apprentice to choose: the plain box.
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "false");
            WishCompose.open(p);
            helper.assertTrue(magicOf(shownDialog(helper, p)) == null, Component.literal("no Builder, no dropdown"));
            helper.succeed();
        } finally {
            try {
                ApprenticeService.choose(p, false);   // leave the shelf row a later run reads on the default
            } catch (RuntimeException ignored) {
                // nothing to put back
            }
            list.getWhiteList().remove(p.getGameProfile());
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
            for (Wish w : WishBook.get(p.getServer()).recent(40)) {
                if (w.text().contains(tag) || w.playerId() != null && w.playerId().equals(p.getUUID())) {
                    try {
                        Marshal.global().cancel(w.id(), CancelMode.STOP, "", "");
                    } catch (RuntimeException ignored) {
                        // already over
                    }
                    WishProgress.clearWishBar(w.id());
                }
            }
            WishQueue.global().clear();
            BuilderMenus.forget(p.getUUID());
            ApprenticeService.forget(p.getUUID());
            CrafterService.forgetForTest(p.getUUID());
            MockPlayerNetwork.remove(p);
        }
    }

    /**
     * <b>The trained layer, in the game</b>, with a reader standing in for the model. A clause the keywords
     * cannot read is read off the server thread and the page opens when it has: the layer's pick is on the top
     * tile, the first line names it as the closest Moddy's Parts have, and each tile says what it is to his words.
     * "None" keeps the flag and adds nothing. A read slower than the cap gets the keyword page. With no reader, or
     * with the world's {@code apprenticeLayer} off, the page and the line are exactly what they were.
     *
     * <p>Its own batch: the reader is a static, and the other Apprentice test's wishes must never meet it.
     */
    @GameTest(template = "empty_test", batch = "apprentice_layer", timeoutTicks = 1200)
    public static void theTrainedLayerAddsItsPickSaysSoAndNeverHoldsThePage(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "layer_kid");
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        System.setProperty(BuilderSettings.LAYER_PROPERTY, "true");
        java.util.Set<String> readOn = java.util.concurrent.ConcurrentHashMap.newKeySet();
        com.moddingfromamod.wishbuilder.ClauseReader reader = clause -> {
            readOn.add(Thread.currentThread().getName());
            if (clause.contains("glimbers")) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return clause.contains("blorps")
                    ? List.of(new com.moddingfromamod.wishbuilder.ClauseReader.Guess(com.moddingfromamod.wishbuilder.ClauseReader.NONE, 0.9))
                    : List.of(new com.moddingfromamod.wishbuilder.ClauseReader.Guess("abilities=ability:fire_breath", 0.9));
        };
        com.moddingfromamod.apprentice.LayerHost.useForTest(reader, 0.5);
        Runnable cleanup = () -> {
            com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
            ApprenticeService.layerWaitForTest(0);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            System.clearProperty(BuilderSettings.LAYER_PROPERTY);
            SafetyGrader.transportForTest(null);
            ApprenticeService.forget(p.getUUID());
            p.closeContainer();
        };
        int[] step = {0};
        int[] mark = {MockPlayerNetwork.chat(p).size()};
        long[] sent = {0};
        try {
            helper.assertTrue(CrafterService.isCrafter(p), Component.literal("control: a Crafter"));
            run(p, "wish a wolf that zorbles");
        } catch (RuntimeException | Error e) {
            cleanup.run();
            throw e;
        }
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            try {
                if (step[0] == 0) {
                    List<PickPage.Tile> tiles = tilesOf(helper, p);
                    helper.assertTrue(tiles.get(0).label().toLowerCase(java.util.Locale.ROOT).contains("wolf"), Component.literal("a wolf: " + tiles.get(0).label()));
                    helper.assertTrue(tiles.get(0).lore().contains(ApprenticeWords.TAG_CLOSEST), Component.literal("the top tile carries the layer's pick and says so: " + tiles.get(0).lore()));
                    helper.assertValueEqual(tiles.get(0).lore().get(0), ApprenticeWords.TILE_LORE, Component.literal("the tag is the SECOND line"));
                    helper.assertTrue(tiles.get(1).lore().stream().anyMatch(ApprenticeWords::leavesOut), Component.literal("the make without it says what it leaves out (#2251: by name): " + tiles.get(1).lore()));
                    List<String> said = since(p, mark[0]);
                    helper.assertTrue(said.stream().anyMatch(l -> l.contains("The closest it has: ") && l.contains("Moddy's Parts") && !l.contains("experimental")),
                            Component.literal("the first line names what the layer took as closest: " + said));
                    helper.assertTrue(said.stream().noneMatch(l -> l.contains("zorbles")), Component.literal("his words are not said back"));
                    helper.assertValueEqual(ApprenticeService.pendingWords(p.getUUID()), "", Component.literal("a Crafter's words are still held nowhere"));
                    helper.assertTrue(readOn.equals(java.util.Set.of("moddy-apprentice-layer")), Component.literal("the clause was read on the layer's own thread, never the server's: " + readOn));
                    helper.assertTrue(tiles.get(1).lore().contains(ApprenticeWords.TAG_CHANGE_AFTER),
                            Component.literal("…and that it can be changed after: " + tiles.get(1).lore()));
                    // the tile is a Builder make like any other: pressed, it makes the pick, the layer's part included
                    java.util.concurrent.atomic.AtomicReference<BuilderSpec> made = new java.util.concurrent.atomic.AtomicReference<>();
                    BuilderMenus.apprenticeMadeForTest(p.getUUID(), made::set);
                    ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);
                    helper.assertTrue(made.get() != null && made.get().toString().contains("fire_breath"),
                            Component.literal("made with the layer's pick in it: " + made.get()));
                    p.closeContainer();
                    mark[0] = MockPlayerNetwork.chat(p).size();
                    run(p, "wish a wolf that blorps");
                    step[0] = 1;
                    throw new IllegalStateException("next: the clause the layer reads as none");
                }
                if (step[0] == 1) {
                    List<PickPage.Tile> tiles = tilesOf(helper, p);
                    for (PickPage.Tile t : tiles.subList(0, tiles.size() - 1)) {
                        helper.assertTrue(t.lore().stream().anyMatch(ApprenticeWords::leavesOut), Component.literal("'none' adds nothing and every tile says it leaves that part out: " + t.lore()));
                    }
                    List<String> said = since(p, mark[0]);
                    helper.assertTrue(said.stream().anyMatch(l -> l.contains(ApprenticeWords.CLOSEST)), Component.literal("the page stays flagged: " + said));
                    helper.assertTrue(said.stream().noneMatch(l -> l.contains("The closest it has: ")), Component.literal("and names no pick: " + said));
                    p.closeContainer();
                    ApprenticeService.forget(p.getUUID());
                    // a read slower than the cap: the keyword page, on time
                    ApprenticeService.layerWaitForTest(40);
                    mark[0] = MockPlayerNetwork.chat(p).size();
                    sent[0] = System.nanoTime();
                    run(p, "wish a wolf that glimbers");   // a word nobody lists ("slowpoke" became a speed word, step 6)
                    step[0] = 2;
                    throw new IllegalStateException("next: the slow read");
                }
                if (step[0] == 2) {
                    List<PickPage.Tile> tiles = tilesOf(helper, p);
                    long ms = (System.nanoTime() - sent[0]) / 1_000_000;
                    helper.assertTrue(ms < 350, Component.literal("the page did not wait for the 400 ms read: " + ms + " ms"));
                    helper.assertTrue(tiles.get(0).lore().stream().anyMatch(ApprenticeWords::leavesOut), Component.literal("the keyword page: " + tiles.get(0).lore()));
                    helper.assertTrue(since(p, mark[0]).stream().noneMatch(l -> l.contains("The closest it has: ")), Component.literal("no layer pick was shown"));
                    p.closeContainer();
                    ApprenticeService.forget(p.getUUID());
                    // No reader at all (a server without the model): the page and the line are exactly as they were.
                    com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
                    todaysPage(helper, p, "no reader");
                    // The world's setting off, with a reader there: the same.
                    com.moddingfromamod.apprentice.LayerHost.useForTest(reader, 0.5);
                    System.setProperty(BuilderSettings.LAYER_PROPERTY, "false");
                    todaysPage(helper, p, "apprenticeLayer off");
                    step[0] = 3;
                }
            } catch (IllegalStateException e) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(Component.literal(e.getMessage()), 0);
            }
            cleanup.run();
        });
    }

    private static List<PickPage.Tile> tilesOf(GameTestHelper helper, ServerPlayer p) {
        helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu && ApprenticeService.pendingOffers(p.getUUID()) >= 1,
                Component.literal("waiting for the Apprentice's page"));
        return ((MenuKit.PickMenu) p.containerMenu).page().tiles();
    }

    /** With no layer in use the wish is answered on the same tick, with the page and line it always had. */
    private static void todaysPage(GameTestHelper helper, ServerPlayer p, String why) {
        int mark = MockPlayerNetwork.chat(p).size();
        run(p, "wish a wolf that zorbles");
        List<PickPage.Tile> tiles = tilesOf(helper, p);
        for (PickPage.Tile t : tiles.subList(0, tiles.size() - 1)) {
            helper.assertValueEqual(t.lore(), List.of(ApprenticeWords.TILE_LORE), Component.literal(why + ": a tile's lore is what it was"));
        }
        helper.assertTrue(since(p, mark).stream().anyMatch(l -> l.contains(ApprenticeWords.HELLO + " " + ApprenticeWords.CLOSEST)),
                Component.literal(why + ": the line is what it was: " + since(p, mark)));
        p.closeContainer();
        ApprenticeService.forget(p.getUUID());
    }
}
