package com.moddingfromamod.test.example;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.WishCharge;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.core.BlockPos;
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
 * <b>{@code docs/EXTENDING.md}'s example plug-in, driven through the core</b>: {@link EmeraldPlugin} registers
 * {@link EmeraldWishCharge}, which stands in for any registered charge ({@link Plugins#replaceForTest}), and the test
 * presses a recipe through the real {@link WishService#submit} twice and asks the real {@code /moddy wishes}. One
 * emerald pays for the first wish; the second is refused in the plug-in's words. If the page's example stops
 * working, this goes red.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class EmeraldWishChargeGameTest {

    @GameTest(template = "empty_test", batch = "extending_emerald", timeoutTicks = 400)
    public static void oneEmeraldPaysForOneWishAndTheNextIsRefused(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        var server = helper.getLevel().getServer();

        // Register the example as a plug-in would, then stand it in for whatever charge is loaded here.
        Plugins mine = new Plugins();
        new EmeraldPlugin().register(mine);
        Plugins.shared().replaceForTest(WishCharge.class, mine.first(WishCharge.class).orElseThrow());
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        String tag = UUID.randomUUID().toString().substring(0, 6);
        String wish = "gt2459_emerald_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        with.add("{\"id\":\"" + wish + "\",\"all\":[\"gt2459emerald" + tag + "\"],\"ownerId\":\""
                + p.getStringUUID() + "\",\"commands\":[\"say an emerald wish\"]}");
        LearnedTemplates.restoreForTest(with, server);
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
                Plugins.shared().replaceForTest(WishCharge.class, null);
                SafetyGrader.forTest(null);
                LearnedTemplates.restoreForTest(had, server);
                MockPlayerNetwork.remove(p);
            }
        });

        p.getInventory().setItem(0, new ItemStack(Items.EMERALD, 1));
        helper.assertTrue(WishCharge.get().priced(p.getUUID()), Component.literal("the example charge is in force"));
        WishService.Outcome first = WishService.submit(p, "emerald wish", WishSource.PRESET, wish);
        helper.assertTrue(first == WishService.Outcome.FULFILLING,
                Component.literal("one emerald pays for a wish: " + first));
        helper.assertTrue(p.getInventory().countItem(Items.EMERALD) == 0,
                Component.literal("the emerald was spent: " + p.getInventory().countItem(Items.EMERALD)));
        WishService.Outcome second = WishService.submit(p, "emerald wish", WishSource.PRESET, wish);
        helper.assertTrue(second == WishService.Outcome.BANK_EMPTY,
                Component.literal("no emerald, no wish: " + second));
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "moddy wishes");

        StringBuilder said = new StringBuilder();
        helper.succeedWhen(() -> {
            var channel = p.connection.getConnection().channel();
            if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
                Object o;
                while ((o = embedded.readOutbound()) != null) {
                    if (o instanceof ClientboundSystemChatPacket c) {
                        said.append(c.content().getString()).append('\n');
                    }
                }
            }
            String s = said.toString();
            helper.assertTrue(s.contains(EmeraldWishCharge.NO_EMERALD),
                    Component.literal("the refusal is said in the plug-in's words: " + s));
            helper.assertTrue(s.contains(EmeraldWishCharge.PRICE_LINE),
                    Component.literal("/moddy wishes answers with the plug-in's line: " + s));
        });
    }
}
