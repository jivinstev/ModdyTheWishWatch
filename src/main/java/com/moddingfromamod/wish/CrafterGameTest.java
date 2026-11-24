package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.world.WorldCrafters;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserWhiteListEntry;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * step 5 through the REAL paths (docs/OPEN_DOOR_PLAN.md §2): with the door open, a player off the
 * world's list is a Wish Crafter — a typed wish and a question are refused at {@code WishService.submit}
 * before anything is spent or graded, a pressed recipe is served, his panel's compose tile is the
 * locked ✪ one, and the pause menu carries the ✪ link. Seated while he plays (the whitelist FILE the
 * account service writes), he is a Magician at the next check, told so, and his typed wish goes on.
 * The control: the door shut, the same player is a Magician.
 *
 * <p>Its own batch: it sets the door as a JVM property and writes the server's whitelist.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class CrafterGameTest {

    @GameTest(template = "empty_test", batch = "crafters", timeoutTicks = 400)
    public static void aCrafterCraftsReadyMadeWishesAndASeatMakesHimAMagician(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "crafter_kid");
        var list = helper.getLevel().getServer().getPlayerList();
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        String run = Long.toString(System.nanoTime(), 36);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "false");
            Crafters.Role shut = CrafterService.role(p);

            System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
            Crafters.Role open = CrafterService.role(p);
            String title = CrafterService.title(p);
            String typed = "a flying pig with a top hat " + run;
            WishService.Outcome typedOut = WishService.submit(p, typed, WishSource.TYPED);
            boolean typedGraded = SafetyGrader.startedFor(typed);
            WishService.Outcome askOut = WishService.submit(p, "how do I get sand " + run + "?", WishSource.TYPED);
            var known = WishCatalog.templates().known().stream().filter(k -> !k.phrase().isBlank()).toList();
            WishService.forget(p);
            WishService.Outcome pressOut = known.isEmpty() ? null
                    : WishService.submit(p, known.get(0).phrase(), WishSource.PRESET, known.get(0).id());
            WatchPanel.Cell compose = WatchMenu.planFor(p).cells().stream()
                    .filter(c -> c.action() == WatchPanel.Action.COMPOSE).findFirst().orElse(null);
            // An islands world's links where the jar carries its manifest; the public mod has none, so a world shaped like it.
            WorldCrafters linked = WorldCrafters.forId("isle").links().isEmpty() ? WorldCrafters.parse("""
                    {"crafters": {"open": true, "links": [
                      {"label": "✪ Become a Wish Magician", "url": "https://example.org/signin", "for": "crafters"},
                      {"label": "How Moddy works", "url": "https://example.org/guide.html"}]}}""")
                    : WorldCrafters.forId("isle");
            int crafterLinks = CrafterService.links(linked, true).size();
            int magicianLinks = CrafterService.links(linked, false).size();

            // A SEAT, given while he plays: the line the account service's whitelist.add writes
            list.getWhiteList().add(new UserWhiteListEntry(p.nameAndId()));
            CrafterService.check(p);
            Crafters.Role seated = CrafterService.role(p);
            WatchPanel.Cell composeAfter = WatchMenu.planFor(p).cells().stream()
                    .filter(c -> c.action() == WatchPanel.Action.COMPOSE).findFirst().orElse(null);
            WishService.forget(p);
            String magicianWish = "a magician's own wish " + run;
            WishService.Outcome magicianOut = WishService.submit(p, magicianWish, WishSource.TYPED);
            var chat = MockPlayerNetwork.chat(p);

            helper.assertTrue(shut == Crafters.Role.MAGICIAN, Component.literal("control: the door shut, he is a Magician"));
            helper.assertTrue(open == Crafters.Role.CRAFTER, Component.literal("the door open, off the list: a Crafter"));
            helper.assertTrue(title.startsWith("✦ "), Component.literal("his watch says his rank: " + title));
            // a Crafter's typed words go to the APPRENTICE (a local matcher), never to the agent or a grader.
            helper.assertValueEqual(typedOut, WishService.Outcome.APPRENTICE, Component.literal("a typed wish"));
            helper.assertFalse(typedGraded, Component.literal("no grader started: nothing he typed went anywhere"));
            helper.assertValueEqual(askOut, WishService.Outcome.APPRENTICE, Component.literal("a question"));
            helper.assertTrue(!known.isEmpty(), Component.literal("no ready-made wish to press"));
            helper.assertValueEqual(pressOut, WishService.Outcome.FULFILLING, Component.literal("a ready-made wish is served"));
            helper.assertTrue(compose != null && compose.label().contains("Wish for something new")
                            && compose.lore().equals(Crafters.COMPOSE_LORE),
                    Component.literal("his compose tile is the Magician's own tile, and says the Apprentice answers: " + compose));
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains("Moddy's Apprentice")),
                    Component.literal("a typed wish is answered by Moddy's Apprentice, said by name: " + chat));
            helper.assertTrue(chat.stream().noneMatch(l -> l.contains(run)),
                    Component.literal("nothing he typed is said back or kept: " + chat));
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains("What do I do now?")), Component.literal("and where a question's answer is"));
            helper.assertTrue(crafterLinks == magicianLinks + 1, Component.literal("the ✪ link is a Crafter's only: "
                    + crafterLinks + " vs " + magicianLinks));
            helper.assertTrue(seated == Crafters.Role.MAGICIAN, Component.literal("seated: a Magician"));
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains("You're a Wish Magician now")),
                    Component.literal("and told so, with no rejoin"));
            helper.assertTrue(composeAfter != null && !composeAfter.lore().equals(Crafters.COMPOSE_LORE),
                    Component.literal("a Magician's compose tile says Moddy makes it"));
            helper.assertTrue(magicianOut != WishService.Outcome.APPRENTICE, Component.literal("a Magician's typed wish goes to the agent: " + magicianOut));
            helper.succeed();
        } finally {
            list.getWhiteList().remove(p.nameAndId());
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            SafetyGrader.transportForTest(null);
            for (Wish w : WishBook.get(helper.getLevel().getServer()).recent(40)) {
                if (w.text().contains(run) || w.playerId() != null && w.playerId().equals(p.getUUID())) {
                    try {
                        Marshal.global().cancel(w.id(), CancelMode.STOP, "", "");
                    } catch (RuntimeException ignored) {
                        // already over
                    }
                    WishProgress.clearWishBar(w.id());
                }
            }
            WishQueue.global().clear();
            CrafterService.forgetForTest(p.getUUID());
            MockPlayerNetwork.remove(p);
        }
    }
}
