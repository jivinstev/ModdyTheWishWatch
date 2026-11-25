package com.moddingfromamod.wish;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.health.WishSweeper;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.world.WorldCrafters;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Hardening: a re-aimed or re-queued wish is routed the same way as a fresh one</b> (#81).
 *
 * <p>Gate B runs with the Magician on. A player the Magician answers (the door shut) makes a wish that goes to the
 * workers' queue; then their standing changes (the door opens and they are a Crafter, whom the Apprentice answers).
 * The sweeper's put-back and a re-aim ask the same route decision as a fresh wish ({@link MagicianDoor#answersNow}):
 * neither puts the wish back in the queue, and the child is answered the Apprentice's way. The same steps for a
 * player the Magician still answers behave as before. Also as a Crafter: choosing the Magician in the wish box, and
 * (its own batch) the change-a-wish page's last tile; neither reaches the queue.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RouteRecheckGameTest {

    private RouteRecheckGameTest() {
    }

    private static boolean queued(UUID wishId) {
        return WishQueue.global().peekRest(WishQueue.MAX_WAITING).stream().anyMatch(w -> w.id().equals(wishId));
    }

    private static boolean queuedFor(UUID player) {
        return WishQueue.global().peekRest(WishQueue.MAX_WAITING).stream().anyMatch(w -> player.equals(w.playerId()));
    }

    private static void door(ServerPlayer p, boolean open) {
        System.setProperty(WorldCrafters.OPEN_PROPERTY, Boolean.toString(open));
        CrafterService.forgetForTest(p.getUUID());
    }

    /** A wish the Magician takes: words no recipe or Builder pick answers, so it goes to the workers' queue. */
    private static Wish magicianWish(GameTestHelper helper, ServerPlayer p, String words) {
        WishService.forget(p);
        WishService.Outcome out = WishService.submit(p, words, WishSource.TYPED);
        helper.assertValueEqual(out, WishService.Outcome.PENDING_ESCALATION,
                Component.literal("control: '" + words + "' goes to the Magician"));
        Wish w = WishBook.get(p.level().getServer()).recent(40).stream().filter(x -> x.text().equals(words)).findFirst().orElse(null);
        helper.assertTrue(w != null, Component.literal("control: the wish is in the book"));
        return w;
    }

    @GameTest(template = "empty_test", batch = "route_recheck", timeoutTicks = 400)
    public static void aWishPutBackInLineAsksTheSameRouteAsAFreshOne(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "route_recheck_kid");
        var server = p.level().getServer();
        NextWish.quietForTest(p.getUUID());
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        String tag = Long.toString(System.nanoTime(), 36);
        try {
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
            door(p, false);
            helper.assertTrue(MagicianDoor.offered(p), Component.literal("control: the Magician answers them (door shut)"));

            // ── the sweeper's put-back ────────────────────────────────────────────────────────
            Wish swept = magicianWish(helper, p, "a humming teapot lighthouse " + tag);
            WishQueue.global().clear();
            WishSweeper.putBack(server, swept);
            helper.assertTrue(queued(swept.id()), Component.literal("the Magician still answers them: put back in line, as before"));
            WishQueue.global().clear();
            door(p, true);
            helper.assertFalse(MagicianDoor.answersNow(server, p.getUUID()),
                    Component.literal("control: the door open, they are a Crafter: the Magician no longer answers them"));
            int mark = MockPlayerNetwork.chat(p).size();
            WishSweeper.putBack(server, swept);
            helper.assertFalse(queuedFor(p.getUUID()), Component.literal("the sweeper did not put it back in line"));
            helper.assertValueEqual(WishBook.get(server).byId(swept.id()).map(Wish::status).orElse(null), WishStatus.REJECTED,
                    Component.literal("the wish is ended"));
            helper.assertTrue(MockPlayerNetwork.chat(p).subList(mark, MockPlayerNetwork.chat(p).size()).stream()
                            .anyMatch(l -> l.contains(MagicianDoor.NOT_THE_MAGICIANS)),
                    Component.literal("and they are told, in child words: " + MockPlayerNetwork.chat(p)));

            // ── a re-aim ─────────────────────────────────────────────────────────────────────
            door(p, false);
            Wish kept = magicianWish(helper, p, "a whistling pebble moon " + tag);
            WishQueue.global().clear();
            WishCancel.stop(p, kept.shortId(), CancelMode.REDIRECT, "the blue one");
            helper.assertTrue(queued(kept.id()), Component.literal("the Magician still answers them: the re-aim is queued, as before"));
            WishQueue.global().clear();
            Wish reaimed = magicianWish(helper, p, "a giggling cloud harbour " + tag);
            WishQueue.global().clear();
            door(p, true);
            WishCancel.stop(p, reaimed.shortId(), CancelMode.REDIRECT, "the green one");
            helper.assertFalse(queuedFor(p.getUUID()), Component.literal("a Crafter's re-aim is not put back in line"));
            helper.assertTrue(WishBook.get(server).byId(reaimed.id()).map(w -> w.status().isFinished()).orElse(true),
                    Component.literal("the stopped wish stays stopped"));
            p.closeContainer();

            // ── the wish box's Magician choice, as a Crafter ──────────────────────────────────
            WishService.forget(p);
            String boxed = "a dancing marble whale " + tag;
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(),
                    "moddy wishbox " + WishCompose.MAGIC_KEY + " " + WishCompose.MAGIC_MAGICIAN + " " + boxed);
            helper.assertFalse(queuedFor(p.getUUID()), Component.literal("choosing the Magician as a Crafter reaches no queue"));
            helper.assertTrue(WishBook.get(server).recent(40).stream().noneMatch(w -> w.text().equals(boxed)),
                    Component.literal("and records no wish for a worker"));
        } finally {
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
            for (Wish w : WishBook.get(server).recent(40)) {
                if (w.text().contains(tag)) {
                    try {
                        Marshal.global().cancel(w.id(), CancelMode.STOP, "", "");
                    } catch (RuntimeException ignored) {
                        // already over
                    }
                    WishProgress.clearWishBar(w.id());
                }
            }
            WishQueue.global().clear();
            p.closeContainer();
            ApprenticeService.forget(p.getUUID());
            CrafterService.forgetForTest(p.getUUID());
            WishService.forget(p);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /** The change-a-wish page's last tile, pressed by a Crafter (Magician on): the words never reach the queue. */
    @GameTest(template = "empty_test", batch = "route_recheck_rewish", timeoutTicks = 12000)
    public static void aCraftersChangeAWishLastTileReachesNoQueue(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "route_recheck_rewish");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");   // the door open: a Crafter
        CrafterService.forgetForTest(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            SafetyGrader.transportForTest(null);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            WishQueue.global().clear();
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
                        if (!CrafterService.isCrafter(p) || MagicianDoor.offered(p)) {
                            wrong.set("control: they should be a Crafter, whom the Magician does not answer");
                            return;
                        }
                        Rewish.onForTest(true);
                        WishQueue.global().clear();
                        if (!Rewish.tryAnswer(p, "make fluffy bigger", WishSource.TYPED, WishRoute.Hint.AUTO)
                                || !(p.containerMenu instanceof MenuKit.PickMenu menu)) {
                            wrong.set("'make fluffy bigger' with two Fluffys did not open the page: " + p.containerMenu);
                            return;
                        }
                        int slot = menu.page().slots().entrySet().stream().filter(e -> e.getValue().equals(Rewish.ELSE_ID))
                                .map(java.util.Map.Entry::getKey).findFirst().orElse(-1);
                        if (slot < 0) {
                            wrong.set("no last tile on the page: " + menu.page().tiles());
                            return;
                        }
                        menu.clicked(slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
                        if (queuedFor(p.getUUID())) {
                            wrong.set("the last tile put their words in the workers' queue");
                        } else if (WishBook.get(server).recent(40).stream().anyMatch(w -> p.getUUID().equals(w.playerId())
                                && w.text().equals("make fluffy bigger"))) {
                            wrong.set("the last tile recorded a wish for a worker");
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
