package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.WishEggItem;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Give me that one again" — the three claims that need a world.</b>
 *
 * <p>an earlier issue's evidence tier says everything it established was READ rather than RUN, and that
 * <i>"there is no gate on the [Make another one] → item path at all."</i> These run it.
 *
 * <p><b>Deliberately only three tests, and only two of them make a player.</b> The lookup rules —
 * precedence, ambiguity, case, blank input — are strings in and entries out, and they live in
 * {@code NameMatchTest} and {@code GiveAgainResolveTest} at Gate A. The first cut of this file put
 * all of them here, each minting a mock player, and it turned a creature test three batches away
 * red: {@code modify_mob} resolves "the child" as the first player in the list, so extra mocks move
 * where it looks. That is an earlier issue's shape and this repo's §X18, and it cost an A/B to attribute — <b>the
 * control on {@code main} was green at the same tier, which is the only thing that separated "my
 * change" from "already broken".</b> The cheapest gate that can hold a rule should hold it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class GiveAgainGameTest {

    private static final String BATCH = "giveagain";

    /**
     * The command as Brigadier holds it — no slash. Derived from the one constant rather than
     * retyped, so a rename cannot leave this test asserting the old name is present (which it
     * would still do, happily, on a tree where the link sends the new one).
     */
    private static final String NODE = ChildCommands.GIVE;

    private GiveAgainGameTest() {
    }

    /**
     * <b>The command exists — {@code /moddy give} — and is NOT a {@code /wish} literal.</b>
     *
     * <p>The second half is the one that would be easy to lose. {@code /wish} ends in a greedy
     * {@code text} argument and Brigadier prefers a literal over an argument, so a {@code /wish
     * give} subcommand would swallow <i>"give me a sword"</i> — about as natural a wish as a child
     * can type — and answer it by hunting the catalogue for a thing called "me a sword". Since
     * no word is a {@code /wish} literal at all ({@code WishChatGameTest}); this keeps the
     * give-back's own half of that.
     *
     * <p>Needs no player, which is why it is separate: a test that costs a mock should only be
     * written when the claim needs one.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theCommandIsTopLevelAndReachableByAStockClient(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var moddy = dispatcher.getRoot().getChild(ChildCommands.MODDY);
        helper.assertTrue(moddy != null && moddy.getChild(NODE) != null,
                Component.literal("/moddy " + NODE + " is not in the command tree Brigadier syncs to a vanilla client, so "
                        + "the [🎁 Give me one] link on the card runs nothing"));
        var wish = dispatcher.getRoot().getChild("wish");
        helper.assertTrue(wish != null, Component.literal("/wish is gone entirely"));
        helper.assertTrue(wish.getChild("give") == null,
                Component.literal("a 'give' literal has appeared under /wish. Brigadier prefers a literal over the "
                        + "greedy text argument, so this now swallows 'give me a sword' and answers "
                        + "it by looking for an item called 'me a sword'. Keep it under /moddy"));
        helper.succeed();
    }

    /**
     * <b>The primary call, on a client with no mod at all.</b> That is the whole point: a
     * give is a server call any client accepts, so this half was never a vanilla gap — only unbuilt.
     * A test that proved it on a modded player would have measured the arm nobody doubted.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aChildWithNoModIsHandedTheRealStack(GameTestHelper helper) {
        String name = "Test Give Again Sword";
        ItemStack made = new ItemStack(Items.STICK);
        made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        WishItemCatalogue.remember(helper.getLevel().getServer(), made);
        helper.assertTrue(WishItemCatalogue.resolve(name).size() == 1,
                Component.literal("the catalogue did not remember the fixture, so every assertion below would be "
                        + "measuring an empty catalogue rather than the give path"));

        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        try {
            MockPlayerNetwork.withoutChannels(p, () -> {
                helper.assertTrue(countNamed(p, name) == 0,
                        Component.literal("the player already holds one before anything asked, so the assertion "
                                + "below could not tell a working give from a dead one"));
                WishGiveAgain.Result r = WishGiveAgain.give(p, name);
                helper.assertTrue(r == WishGiveAgain.Result.GAVE,
                        Component.literal("asking for a remembered thing by name did not give it: " + r));
                helper.assertTrue(countNamed(p, name) == 1,
                        Component.literal("the give reported success and nothing reached the inventory — serving and "
                                + "receiving are different claims, which is the whole reason #511 "
                                + "exists for the pack and this test exists for the stack"));
            });
        } finally {
            // TAKE THE MOCK BACK OUT. inLevel arms its own removal on pass/fail, and this is the
            // belt beside it: a mock outliving its test is what moves "the child" for everything
            // after it, and the failure lands on somebody else's test with no mention of players.
            MockPlayerNetwork.remove(p);
            WishItemCatalogue.forget(name);
        }
        helper.succeed();
    }

    /**
     * <b>A creature, by the name the child would say, on the same channel-less client.</b>
     *
     * <p>This is the half that already existed and was <i>unreachable</i>: {@code /wishmob egg
     * <key>} is {@code requires(hasPermission(2))}, so the give-back was built for creatures and
     * then handed to a console rather than to the person it was for. It also asserts the shape that
     * makes it usable — the child says <b>the name</b> ("ZOGGLE HEN"), never the registry id
     * ("giveagain_probe_hen"), and one verb serves both kinds so he never has to know which registry his
     * thing lives in.
     *
     * <p>The fixture is held IN MEMORY for the test and never saved
     * ({@link SpeciesRegistry#withSpeciesForTest}): a planted, saved species would stay in a
     * GameTest world that is reused and never reset, and a stray creature is exactly the thing that
     * turns a neighbouring test red weeks later (§X19). Until this used a shipped built-in for
     * the same reason; the built-ins are retired.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aChildWithNoModCanAskForACreatureByItsNameAndGetTheEgg(GameTestHelper helper) {
        Species fixture = Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"giveagain_probe_hen\",\"name\":\"ZOGGLE HEN\",\"body\":\"bird\","
                        + "\"texture\":\"chicken\",\"scale\":2.0,\"health\":20}").getAsJsonObject());
        String said = fixture.name();

        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        try {
            SpeciesRegistry.withSpeciesForTest(java.util.List.of(fixture), () ->
                    MockPlayerNetwork.withoutChannels(p, () -> {
                helper.assertTrue(eggsFor(p, fixture.id()) == 0,
                        Component.literal("the player already holds one before anything asked, so the assertion "
                                + "below could not tell a working give from a dead one"));
                WishGiveAgain.Result r = WishGiveAgain.give(p, said);
                helper.assertTrue(r == WishGiveAgain.Result.GAVE,
                        Component.literal("asking for a creature by the name a child would say ('" + said + "') did "
                                + "not give it: " + r + ". If this is AMBIGUOUS the world has "
                                + "picked up a second creature whose name contains the fixture's"));
                helper.assertTrue(eggsFor(p, fixture.id()) == 1,
                        Component.literal("the give reported success and no egg for '" + fixture.id() + "' reached "
                                + "the inventory"));
            }));
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /**
     * <b>: two things answer to his words → a PICK PAGE, and its tile gives that exact one.</b> It was
     * chat links, which a Bedrock phone popped up as a form. Driven through the real command and the real
     * chest click, on a client with no mod.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void twoMatchesOpenAPickPageAndATileGivesThatOne(GameTestHelper helper) {
        String a = "Gtpick Twinsword Alpha";
        String b = "Gtpick Twinsword Beta";
        var server = helper.getLevel().getServer();
        for (String name : java.util.List.of(a, b)) {
            ItemStack made = new ItemStack(Items.STICK);
            made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            WishItemCatalogue.remember(server, made);
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            MockPlayerNetwork.withoutChannels(p, () -> {
                helper.assertTrue(WishItemCatalogue.resolve("Gtpick Twinsword").size() == 2,
                        Component.literal("the fixture does not make the words ambiguous, so this would test nothing: "
                                + WishItemCatalogue.resolve("Gtpick Twinsword")));
                WishGiveAgain.Result r = WishGiveAgain.give(p, "Gtpick Twinsword");
                helper.assertTrue(r == WishGiveAgain.Result.AMBIGUOUS, Component.literal("not ambiguous: " + r));
                helper.assertTrue(p.containerMenu instanceof com.moddingfromamod.ui.MenuKit.PickMenu,
                        Component.literal("two matches did not open a pick page: " + p.containerMenu));
                var page = ((com.moddingfromamod.ui.MenuKit.PickMenu) p.containerMenu).page();
                helper.assertTrue(page.tiles().size() == 2, Component.literal("tiles: " + page.tiles()));
                int beta = -1;
                for (var e : page.slots().entrySet()) {
                    if (page.tiles().stream().anyMatch(t -> t.id().equals(e.getValue()) && t.label().contains("Beta"))) {
                        beta = e.getKey();
                    }
                }
                helper.assertTrue(beta >= 0, Component.literal("no Beta tile: " + page.tiles()));
                p.containerMenu.clicked(beta, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
                helper.assertTrue(countNamed(p, b) == 1 && countNamed(p, a) == 0,
                        Component.literal("pressing Beta did not give exactly Beta (Alpha " + countNamed(p, a)
                                + ", Beta " + countNamed(p, b) + ")"));
            });
        } finally {
            WishItemCatalogue.forget(a);
            WishItemCatalogue.forget(b);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    private static int countNamed(ServerPlayer p, String name) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                n++;
            }
        }
        return n;
    }

    private static int eggsFor(ServerPlayer p, String speciesId) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && speciesId.equals(WishEggItem.speciesId(s))) {
                n++;
            }
        }
        return n;
    }
    /**
     * <b>The link a child actually clicks appears exactly when it can be served — and not
     * otherwise.</b>
     *
     * <p>This is the half an earlier issue's evidence note asked for and the one that was still missing after
     * it landed: <i>"there is no gate on the [Make another one] → item path at all."</i> The
     * COMMAND was gated by the three tests above; its only route onto a child's screen was not, and
     * the two are different claims. A link that fails to appear is the quietest defect on this
     * surface — nothing errors, nothing logs, and the child simply never learns the affordance
     * exists. The register row for {@code findability.give-back} rests on this being reachable, so
     * it should not rest on a conditional nothing exercises.
     *
     * <p><b>Both polarities, because either alone passes on a broken tree.</b> A row that always
     * appended the give-back would satisfy the first assertion — and would offer <i>"[🎁 Give me
     * one]"</i> on a preset or a control cell the catalogue cannot resolve, so the click runs a
     * command that answers "I couldn't find that". A row that never appended it would satisfy the
     * second.
     *
     * <p>It asserts on the component rather than on delivery because a mock player's connection
     * swallows a system chat packet: a test that waited for the message would be asserting on the
     * shim. {@code ThingPage.verbs} is the page's data, and it runs the real
     * {@link WishItemCatalogue} on a real server — where it runs, not where it is defined.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theCardOffersTheGiveBackOnlyForAThingTheCatalogueKnows(GameTestHelper helper) {
        String name = "Test Card Link Sword";
        ItemStack made = new ItemStack(Items.STICK);
        made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        WishItemCatalogue.remember(helper.getLevel().getServer(), made);
        try {
            helper.assertTrue(WishItemCatalogue.resolve(name).size() == 1,
                    Component.literal("the fixture is not in the catalogue, so the 'it appears' assertion would be "
                            + "measuring an empty catalogue rather than the condition"));

            String known = ThingPage.describe(ThingPage.verbs(cell(name, ""), null));
            helper.assertTrue(known.contains("Give me one"),
                    Component.literal("a thing the catalogue can serve was offered no give-back on its own card, so "
                            + "the only route to /moddy give a child ever sees is missing: " + known));
            helper.assertTrue(known.contains(WishGiveAgain.COMMAND + " " + name),
                    Component.literal("the link is there and does not run the give command with this thing's own "
                            + "argument, which is a link that looks right and does nothing: "
                            + known));

            // A CONTROL CELL, which is what half the panel is. Nothing in the catalogue answers to
            // it, so an unconditional row would hand a child a button that always fails.
            String unknown = ThingPage.describe(ThingPage.verbs(cell("Nothing Ever Made This " + name, ""), null));
            helper.assertFalse(unknown.contains("Give me one"),
                    Component.literal("a give-back was offered for something the catalogue cannot resolve: "
                            + unknown));

            // And the OTHER conditional verb on the same row, named 's evidence note.
            helper.assertFalse(known.contains("Make another one"),
                    Component.literal("a cell with no recipeId must not offer to remake it — canRemake() is what "
                            + "gates that link and an empty id means no template was learned"));
            String remakeable = ThingPage.describe(ThingPage.verbs(cell(name, "recipe_fixture"), null));
            helper.assertTrue(remakeable.contains("Make another one")
                            && remakeable.contains(ChildCommands.again("recipe_fixture")),
                    Component.literal("a cell that CAN be remade was not offered it: " + remakeable));
        } finally {
            WishItemCatalogue.forget(name);
        }
        helper.succeed();
    }

    /** A creation cell for a thing by this name, optionally with a learned recipe behind it. */
    private static WatchPanel.Cell cell(String name, String recipeId) {
        return new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                name, name, recipeId, name, WatchIcons.UNKNOWN, java.util.List.of());
    }

}
