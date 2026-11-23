package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.TaughtJoin;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a wish and its parts</b>, where it runs — the watch the child opens and the recipe a
 * press resolves to, not the rule ({@code TaughtJoinTest}).
 *
 * <ul>
 *   <li>a PART's tile re-wishes only that part: its recipe resolves to the wish's commands that
 *       name it and no others, and the chat command that carries it parses;</li>
 *   <li>the WISH's own button re-wishes all of it, and says what it makes;</li>
 *   <li>an item made into a FULL BAG still gets its tile, owned by the wisher — it is recorded at
 *       the grant, not when the inventory scan finally sees it.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishPartsGameTest {

    private WishPartsGameTest() {
    }

    private static final String WISH = "gt1349_menagerie";
    private static final String CAT = "gt1349_cat";
    private static final String DOG = "gt1349_dog";

    private static Species species(String id, String owner) {
        return Species.fromJson(JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + id
                + "\",\"body\":\"quadruped\",\"texture\":\"cow\",\"health\":20}").getAsJsonObject())
                .withOwner(owner);
    }

    /** Its own batch: it mints a mock player and swaps the learned recipes for the duration. */
    @GameTest(template = "empty_test", batch = "wishParts")
    public static void aPartReWishesOnlyItselfAndTheWishReWishesAll(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String me = p.getStringUUID();
        var server = helper.getLevel().getServer();
        List<String> had = LearnedTemplates.savedJsonForTest();
        try {
            List<String> with = new ArrayList<>(had);
            with.add("{\"id\":\"" + WISH + "\",\"all\":[\"gt1349menagerie\"],\"ownerId\":\"" + me
                    + "\",\"commands\":[\"wishmob spawn " + CAT + "\",\"wishmob spawn " + DOG
                    + "\",\"say a whole menagerie\"]}");
            LearnedTemplates.restoreForTest(with, server);
            SpeciesRegistry.withSpeciesForTest(List.of(species(CAT, me), species(DOG, me)), () -> {
                // Newest of all, so a busy gate world's history cannot cap them off it.
                long ahead = System.currentTimeMillis() + 86_400_000L;
                SpeciesRegistry.timesForTest(CAT, new com.moddingfromamod.store.ArtifactTimes.Times(
                        ahead, ahead));
                SpeciesRegistry.timesForTest(DOG, new com.moddingfromamod.store.ArtifactTimes.Times(
                        ahead, ahead));
                WatchRow.Creation cat = null;
                for (String row : WatchSnapshot.forPlayer(p).creations()) {
                    WatchRow.Creation c = WatchRow.Creation.decode(row);
                    if (CAT.equals(c.id())) {
                        cat = c;
                    }
                }
                helper.assertTrue(cat != null, Component.literal("the cat is not on his watch"));
                String partRecipe = TaughtJoin.partRecipe(WISH, CAT);
                helper.assertTrue(partRecipe.equals(cat.recipeId()), Component.literal("the cat's tile carries '"
                        + cat.recipeId() + "' — pressing it must remake the CAT, not the menagerie"));
                helper.assertTrue(cat.partOf().contains(WishPresets.labelFor(WISH)),
                        Component.literal("the cat's card does not say which wish it is part of: " + cat.partOf()));
                var part = WishCatalog.templates().byId(partRecipe, "").orElse(null);
                helper.assertTrue(part != null
                                && part.plan().commands().equals(List.of("wishmob spawn " + CAT)),
                        Component.literal("the part's recipe must run the cat's command and nothing else: "
                                + (part == null ? "none" : part.plan().commands())));
                // The press travels as chat: its argument must parse whole, quotes and all.
                var parse = server.getCommands().getDispatcher().parse(
                        ChildCommands.again(partRecipe).substring(1), p.createCommandSourceStack());
                helper.assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead()
                                && partRecipe.equals(parse.getContext().getArguments()
                                        .get("recipe").getResult()),
                        Component.literal("'" + ChildCommands.again(partRecipe) + "' does not parse to the part recipe"));

                // THE WISH: its own button, which makes all of it.
                WatchPanel.Cell wishButton = null;
                int pages = WatchMenu.planFor(p, 0).pageCount();
                for (int page = 0; page < pages && wishButton == null; page++) {
                    for (WatchPanel.Cell c : WatchMenu.planFor(p, page).cells()) {
                        if (c.family() == WatchPanel.Family.PRESET && WISH.equals(c.recipeId())) {
                            wishButton = c;
                        }
                    }
                }
                helper.assertTrue(wishButton != null,
                        Component.literal("the wish of two parts lost its own button to its parts' tiles"));
                helper.assertTrue(wishButton.lore().contains("Makes all of it:"),
                        Component.literal("the wish button does not say what it makes: " + wishButton.lore()));
                var whole = WishCatalog.templates().byId(WISH, "").orElse(null);
                helper.assertTrue(whole != null && whole.plan().commands().size() == 3,
                        Component.literal("the wish entry must make ALL of it"));
            });
        } finally {
            SpeciesRegistry.timesForTest(CAT, com.moddingfromamod.store.ArtifactTimes.Times.NONE);
            SpeciesRegistry.timesForTest(DOG, com.moddingfromamod.store.ArtifactTimes.Times.NONE);
            LearnedTemplates.restoreForTest(had, server);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /**
     * An item made into a FULL BAG: the scan never sees it, and it still gets its tile, owned by
     * the child whose wish made it.
     */
    @GameTest(template = "empty_test", batch = "wishPartsBag", timeoutTicks = 200)
    public static void anItemGrantedIntoAFullBagStillGetsItsTile(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            inv.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        UUID wish = UUID.randomUUID();
        String worker = "gt-1349-bag-" + wish;
        Marshal.global().submit(wish, p.getUUID(), "a wand for a full bag");
        Marshal.global().assign(worker, wish);
        String name = "Bagful Wand " + (helper.getLevel().getGameTime() % 100000);
        var tool = new com.moddingfromamod.agent.tools.MakeItemTool();
        String feature = null;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("icon", "wand");
            o.addProperty("colour", "#40C0E0");
            o.addProperty("shape", "plain");
            o.addProperty("count", 1);
            var res = AgentContext.with(worker, () -> tool.call(o));
            helper.assertTrue(!res.isError(), Component.literal("make_item refused: " + res.text()));
            feature = tool.lastMadeId();
            helper.assertTrue(feature != null && !feature.isBlank(), Component.literal("make_item reported no id"));
            String key = "id:" + feature;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                helper.assertTrue(inv.getItem(i).is(Items.COBBLESTONE),
                        Component.literal("the bag was meant to stay full, so the scan could never see the wand"));
            }
            var entry = com.moddingfromamod.item.WishItemCatalogue.all().stream()
                    .filter(e -> key.equals(e.key())).findFirst().orElse(null);
            helper.assertTrue(entry != null, Component.literal("a wand made into a full bag has no catalogue row, so "
                    + "no tile, no 'Give me one' and no creative entry"));
            helper.assertTrue(p.getStringUUID().equals(entry.ownerId()),
                    Component.literal("the row is filed under '" + entry.ownerId() + "', not the wisher"));
            boolean onWatch = WatchSnapshot.forPlayer(p).creations().stream()
                    .map(WatchRow.Creation::decode).anyMatch(c -> key.equals(c.id()));
            helper.assertTrue(onWatch, Component.literal("the wand has no tile on his watch"));
        } finally {
            if (feature != null) {
                com.moddingfromamod.item.WishItemCatalogue.forget("id:" + feature);
                com.moddingfromamod.item.ItemSpecStore.remove(feature);
            }
            Marshal.global().finish(wish);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }
}
