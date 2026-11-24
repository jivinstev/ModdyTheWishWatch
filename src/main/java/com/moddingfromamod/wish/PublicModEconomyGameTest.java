package com.moddingfromamod.wish;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.world.WorldRules;
import com.moddingfromamod.world.WorldRulesLog;
import com.moddingfromamod.world.WorldRulesService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>: the public mod, where it runs — no wish charge.</b> The private plug-ins are loaded on this server, so
 * each test HIDES the charge hook ({@link Plugins#hideForTest}) and drives the real entry points as the public mod
 * would run them: the real {@link WishService#submit} of a recipe press, the real {@link WishGiveAgain#give},
 * the real {@link AwaitWishTool} pickup, the real {@code /moddy wishes}.
 *
 * <p>The world's rules declare an EMPTY wish bank (an islands world's rule, start 0): with the charge plug-in that refuses
 * every one of these (pinned by {@code bank/WishBankGameTest} and {@code bank/RewishPriceGameTest}, which now reach
 * the bank only through the hook). With no plug-in the rule means nothing: every wish is free, and the child hears
 * no credit, price or balance. (The Wish Bag is part of the public mod, D30.)
 *
 * <p>ONE BATCH PER TEST: the hidden hooks, the rules in force and the learned recipes are process-wide statics.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PublicModEconomyGameTest {

    /** An islands world's bank, empty: what would refuse every wish if a charge were in force. */
    private static final String EMPTY_BANK = """
            {"id":"wish-bank","type":"wish_bank","params":{"start":0,"refillMinutes":30,"cap":10,"pricePerWish":1}}""";

    /** Words only a charge says (WishBankWords, RewishPrice); none may reach the child with no plug-in. */
    private static final List<String> PRICE_WORDS = List.of("/10", "wishes left", "wish left", "next wish",
            "uses ½", "uses 1", "Uses ", "unlimited", "get it back");

    private record Setup(ServerPlayer player, StringBuilder said, String wish, String thing) { }

    private static WorldRules rules(String rule) {
        WorldRules.Parsed p = WorldRules.parse("{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
                + rule + "]}", "closed");
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.rules();
    }

    /**
     * A mock child on a world whose rules declare an empty bank, with the charge plug-in hidden, one
     * catalogued thing he can ask for back, and one learned recipe he can re-wish. All put back at the end.
     */
    private static Setup setup(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        try {
            WorldRulesLog.fileForTest(Files.createTempFile("world-rules-", ".jsonl"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Plugins.shared().hideForTest(WishCharge.class, true);
        WorldRulesService.forTest(rules(EMPTY_BANK));
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        String tag = UUID.randomUUID().toString().substring(0, 6);
        String thing = "Public Mod Wand " + tag;
        ItemStack made = new ItemStack(Items.STICK);
        made.set(DataComponents.CUSTOM_NAME, Component.literal(thing));
        var server = helper.getLevel().getServer();
        WishItemCatalogue.remember(server, made);

        String wish = "gt2360_menagerie_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        with.add("{\"id\":\"" + wish + "\",\"all\":[\"gt2360menagerie" + tag + "\"],\"ownerId\":\""
                + p.getStringUUID() + "\",\"commands\":[\"say a whole menagerie\"]}");
        LearnedTemplates.restoreForTest(with, server);

        Setup s = new Setup(p, new StringBuilder(), wish, thing);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end();
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end();
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                end();
            }

            private void end() {
                Plugins.shared().hideForTest(WishCharge.class, false);
                WorldRulesService.forTest(null);
                SafetyGrader.forTest(null);
                WorldRulesLog.fileForTest(null);
                LearnedTemplates.restoreForTest(had, server);
                WishItemCatalogue.forget(thing);
                MockPlayerNetwork.remove(p);
            }
        });
        return s;
    }

    /** Chat on the wire, accumulated (readOutbound is destructive and succeedWhen re-runs). */
    private static String chat(Setup s) {
        var channel = s.player().connection.getConnection().channel();
        if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
            Object o;
            while ((o = embedded.readOutbound()) != null) {
                if (o instanceof ClientboundSystemChatPacket c
                        && !(c.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents)) {
                    s.said().append(c.content().getString()).append('\n');
                }
            }
        }
        return s.said().toString();
    }

    private static void saysNoPrice(GameTestHelper helper, String where, String text) {
        for (String w : PRICE_WORDS) {
            helper.assertFalse(text.contains(w), Component.literal(where + " says '" + w + "' with no charge plug-in: "
                    + text));
        }
    }

    private static int held(Setup s, String name) {
        int n = 0;
        for (ItemStack st : s.player().getInventory().getNonEquipmentItems()) {
            if (!st.isEmpty() && st.getHoverName().getString().equals(name)) {
                n += st.getCount();
            }
        }
        return n;
    }

    // ── a recipe press, "give me one" and /moddy wishes: free, and no price said ─────────────────

    @GameTest(template = "empty_test", batch = "publicmod_free", timeoutTicks = 400)
    public static void withNoChargePlugInEveryWishIsFreeAndNoPriceIsShown(GameTestHelper helper) {
        Setup s = setup(helper);
        ServerPlayer p = s.player();
        helper.assertTrue(!WishCharge.registered(), Component.literal("the charge plug-in is hidden"));
        // What he sees before he wishes: the watch's and the box's own titles, and no price on a button.
        helper.assertTrue(WatchMenu.title(p).equals(WatchMenu.TITLE),
                Component.literal("the watch panel's plain title: " + WatchMenu.title(p)));
        helper.assertTrue(WishCompose.title(p).equals(WishCompose.TITLE),
                Component.literal("the wish box's plain title: " + WishCompose.title(p)));
        saysNoPrice(helper, "the wish box's label", WishCompose.label(p));
        for (WishCharge.Again a : WishCharge.Again.values()) {
            helper.assertTrue(WishCharge.get().preview(a).isEmpty() && WishCharge.get().loreLine(a).isEmpty(),
                    Component.literal("no price before a press: " + a));
        }
        // An empty bank in the rules refuses nothing: a recipe re-wish is served, "give me one" hands over.
        WishService.Outcome out = WishService.submit(p, "menagerie", WishSource.PRESET, s.wish());
        helper.assertTrue(out == WishService.Outcome.FULFILLING, Component.literal("the recipe was served free: " + out));
        WishGiveAgain.Result r = WishGiveAgain.give(p, s.thing());
        helper.assertTrue(r == WishGiveAgain.Result.GAVE, Component.literal("give me one, free: " + r));
        helper.assertTrue(held(s, s.thing()) == 1, Component.literal("handed over: " + held(s, s.thing())));
        helper.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "moddy wishes");
        helper.succeedWhen(() -> {
            String said = chat(s);
            helper.assertTrue(said.contains("Here's your " + s.thing()), Component.literal("he was given it: " + said));
            helper.assertTrue(said.contains("✨ Wish away!"), Component.literal("/moddy wishes answers: " + said));
            saysNoPrice(helper, "the chat", said);
        });
    }

    // ── the agent's pickup: never refused, never charged ────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "publicmod_pickup", timeoutTicks = 600)
    public static void withNoChargePlugInAnAgentWishIsPickedUpFree(GameTestHelper helper) {
        Setup s = setup(helper);
        ServerPlayer p = s.player();
        String text = "make me a crystal tower " + UUID.randomUUID().toString().substring(0, 8);
        helper.assertFalse(WishCharge.get().refusesAtSubmit(p, false, text, false),
                Component.literal("an empty bank in the rules refuses nothing at submit"));
        Wish wish = WishBook.get(helper.getLevel().getServer()).add(Wish.create(text, WishSource.COMMAND, System.currentTimeMillis(),
                p.getUUID(), p.getGameProfile().name(), p.level().dimension().location().toString(),
                p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                stop();
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                stop();
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                stop();
            }

            private void stop() {
                try {
                    Marshal.global().cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
                } catch (RuntimeException ignored) {
                    // already over
                }
                WishProgress.clearWishBar(wish.id());
            }
        });
        AtomicReference<ToolResult> got = new AtomicReference<>();
        AtomicReference<Throwable> blew = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                got.set(new AwaitWishTool(queue).call(new JsonObject()));
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-test-await-publicmod");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("await_wish threw: " + blew.get()));
            ToolResult r = got.get();
            helper.assertTrue(r != null, Component.literal("await_wish has not returned yet"));
            helper.assertFalse(r.isError(), Component.literal("granted, not refused for a bank: " + r.text()));
            helper.assertTrue(r.text().contains(wish.text()), Component.literal("the agent got it: " + r.text()));
            saysNoPrice(helper, "the chat", chat(s));
        });
    }
}
