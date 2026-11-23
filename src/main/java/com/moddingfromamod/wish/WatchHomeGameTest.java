package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.WishBoxDialog;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;

/**
 * the text-first watch, through the real doors. Vanilla's own codec reads the Java wish box
 * with its buttons; "📚 My wishes" is his things and the ready-made wishes and NO action; and with the knob off the watch is the chest it
 * always was. A Bedrock player's watch (the button page) is the private Bedrock module's own test:
 * WatchHomeBedrockGameTest.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WatchHomeGameTest {

    @GameTest(template = "empty_test", batch = "watchHome")   // a knob seam: its own batch
    public static void theTextFirstWatchOpensTheBoxAndTheLibrary(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer java = MockPlayerNetwork.inLevel(helper, "home_java");
        MockPlayerNetwork.afterTest(helper, () -> WatchHome.onForTest(null));
        try {
            // ON BY DEFAULT since 2026-11-16. OFF (`watch.textFirst off`, a server's way back):
            // the watch is the old chest, with its Wish-for-something-new tile.
            WatchHome.onForTest(null);
            helper.assertTrue(WatchHome.on(), Component.literal("the text-first watch is the default"));
            WatchHome.onForTest(false);
            WatchHome.openWatch(java);
            helper.assertTrue(java.containerMenu instanceof WatchMenu w && w.layout().cells().stream()
                    .anyMatch(c -> c.action() == WatchPanel.Action.COMPOSE), Component.literal("off: the old chest"));
            java.closeContainer();

            WatchHome.onForTest(true);
            // JAVA: the box with its buttons, read by vanilla's own codec (a malformed one throws).
            List<WishBoxDialog.Button> buttons = new ArrayList<>();
            for (WatchHome.Act a : WatchHome.acts(WatchHome.facts(java))) {
                buttons.add(new WishBoxDialog.Button(a.label(), a.command()));
            }
            WishBoxDialog box = WishCompose.home(WishCompose.dialog(WishCompose.TITLE, ""), buttons);
            MenuKit.decode(RegistryOps.create(JsonOps.INSTANCE, java.registryAccess()), box);
            helper.assertTrue(WatchHome.openWatch(java), Component.literal("the Java watch opens the box"));
            // Opened again with the idea unused: the same idea, and "I put an idea in your wish box!" is said ONCE.
            NextWish.Idea first = NextWish.offered(java.getUUID());
            java.closeContainer();
            WatchHome.openWatch(java);
            WatchHome.openWatch(java);
            long said = MockPlayerNetwork.chat(java).stream().filter(l -> l.contains(NextWish.SAID)).count();
            helper.assertTrue(said <= 1, Component.literal("the idea line said " + said + " times"));
            helper.assertTrue(first == null || first.equals(NextWish.offered(java.getUUID())),
                    Component.literal("the box keeps the same idea while it is unused"));
            helper.assertFalse(java.containerMenu instanceof WatchMenu, Component.literal("on: no chest under the box"));

            // 📚 MY WISHES: his things and the ready-made wishes, never an action.
            helper.assertTrue(WatchMenu.openLibrary(java) && java.containerMenu instanceof WatchMenu,
                    Component.literal("the library opens"));
            // Exactly the old chest's things and ready-made wishes, on every page (the test world's shelf varies).
            List<WatchPanel.Cell> cells = new ArrayList<>();
            int pages = WatchMenu.libraryFor(java, 0).pageCount();
            for (int pg = 0; pg < pages; pg++) {
                cells.addAll(WatchMenu.libraryFor(java, pg).cells());
            }
            List<String> chest = new ArrayList<>();
            int chestPages = WatchMenu.planFor(java, 0).pageCount();
            for (int pg = 0; pg < chestPages; pg++) {
                WatchMenu.planFor(java, pg).cells().stream()
                        .filter(c -> c.action() == WatchPanel.Action.WISH || c.action() == WatchPanel.Action.CARD)
                        .forEach(c -> chest.add(c.label()));
            }
            List<String> lib = cells.stream()
                    .filter(c -> c.action() == WatchPanel.Action.WISH || c.action() == WatchPanel.Action.CARD)
                    .map(WatchPanel.Cell::label).toList();
            helper.assertTrue(new java.util.HashSet<>(lib).equals(new java.util.HashSet<>(chest)),
                    Component.literal("the library holds the chest's things and ready-made wishes: " + lib.size()
                            + " vs " + chest.size()));
            helper.assertTrue(cells.stream().noneMatch(c -> WatchMenu.NOT_IN_LIBRARY.contains(c.action())),
                    Component.literal("no action is in the library: " + cells.stream().map(WatchPanel.Cell::action).toList()));
            java.closeContainer();

            // "📖 What do I do now?": the guide page.
            helper.assertTrue(WatchHome.openNow(java) && java.containerMenu instanceof WatchMenu w2
                    && w2.layout().cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.COMPOSE),
                    Component.literal("What do I do now? opens the guide page"));
            // Its "◀ Back to my wishes" goes back to the text-first watch (the box), never the old mixed chest.
            ((WatchMenu) java.containerMenu).showPage(java, 0);
            helper.assertFalse(java.containerMenu instanceof WatchMenu,
                    Component.literal("the guide's Back opened the old chest: " + java.containerMenu));
            java.closeContainer();

            helper.succeed();
        } finally {
            WatchHome.onForTest(null);
        }
    }
}
