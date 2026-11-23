package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>A wish on the hotbar, where it runs</b>: the tile on a thing's real page, the proxy in a real
 * inventory, and a use that goes through the real {@code WishService.submit} with a recipe the server knows.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishHotbarGameTest {

    private static final String BATCH = "wishhotbar";

    private WishHotbarGameTest() {
    }

    private static WatchPanel.Cell cell(String name, String recipeId) {
        return new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                name, name, recipeId, name, WatchIcons.UNKNOWN, List.of());
    }

    private static void check(GameTestHelper helper, boolean ok, String why) {
        helper.assertTrue(ok, Component.literal(why));
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWishGoesOnTheHotbarFiresOnceAndComesOff(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        var server = helper.getLevel().getServer();
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        String tag = UUID.randomUUID().toString().substring(0, 6);
        String recipe = "gt2755_hotbar_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        with.add("{\"id\":\"" + recipe + "\",\"all\":[\"gt2755hotbar" + tag + "\"],\"ownerId\":\""
                + p.getStringUUID() + "\",\"commands\":[\"say a hotbar wish\"]}");
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
                SafetyGrader.forTest(null);
                LearnedTemplates.restoreForTest(had, server);
                MockPlayerNetwork.remove(p);
            }
        });

        MockPlayerNetwork.withoutChannels(p, () -> {
            WatchPanel.Cell thing = cell("Hotbar Fixture " + tag, recipe);
            var inv = p.getInventory();
            inv.clearContent();

            // THE TILE: only for a thing with a recipe, and only for a player who is here.
            String page = ThingPage.describe(ThingPage.verbs(thing, p.getUUID(), p));
            check(helper, page.contains(WishHotbar.ADD_LABEL), "a thing he can make again offers the hotbar: " + page);
            // (the LABEL, not the word: the fixture's recipe id is in the page's "make another" command)
            check(helper, !ThingPage.describe(ThingPage.verbs(cell("No Recipe " + tag, ""), p.getUUID(), p))
                    .contains(WishHotbar.ADD_LABEL), "a thing with no recipe cannot go on a hotbar");
            check(helper, !ThingPage.describe(ThingPage.verbs(thing, p.getUUID())).contains(WishHotbar.ADD_LABEL),
                    "with no player in hand the page offers no hotbar tile");

            // ADD: slot 0 is taken, so the first EMPTY slot (1) gets the proxy.
            inv.setItem(0, new ItemStack(Items.STICK));
            check(helper, WishHotbar.press(p, WishHotbar.ADD_ID, thing), "the page handles the hotbar press itself");
            ItemStack proxy = inv.getItem(1);
            check(helper, recipe.equals(WishHotbar.recipeOf(proxy)), "the proxy is in the first empty slot: " + proxy);
            check(helper, proxy.is(Items.PAPER) && p.getStringUUID().equals(WishHotbar.ownerOf(proxy)),
                    "the proxy is inert paper, and his: " + proxy);
            check(helper, proxy.getHoverName().getString().equals("✨ Wish: Hotbar Fixture " + tag),
                    "its name says it is a wish for the thing: " + proxy.getHoverName().getString());
            check(helper, proxy.get(DataComponents.CUSTOM_DATA).copyTag().keySet().equals(
                    java.util.Set.of(WishHotbar.RECIPE, WishHotbar.OWNER, WishHotbar.ICON)),
                    "it carries nothing of the thing's own data, only the recipe, the owner and its picture's icon");
            check(helper, WatchIcons.UNKNOWN.equals(WishHotbar.iconOf(proxy)), "it remembers its picture: " + WishHotbar.iconOf(proxy));
            check(helper, Boolean.TRUE.equals(proxy.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                    "an undyed picture keeps the glint that marks it a wish");
            check(helper, ThingPage.describe(ThingPage.verbs(thing, p.getUUID(), p)).contains(WishHotbar.REMOVE_LABEL),
                    "once it is there the tile takes it off");

            // USE: the real submit, once; a second click while it rests does nothing.
            WishHotbar.Use first = WishHotbar.use(p, proxy);
            check(helper, first.used() == WishHotbar.Used.WISHED && first.outcome() == WishService.Outcome.FULFILLING,
                    "using the proxy makes the thing again: " + first);
            check(helper, p.getCooldowns().isOnCooldown(proxy), "the proxy rests after a use (the sweep a client draws)");
            check(helper, WishHotbar.use(p, proxy).used() == WishHotbar.Used.RESTING, "a resting proxy makes nothing");
            check(helper, WishHotbar.use(p, new ItemStack(Items.PAPER)).used() == WishHotbar.Used.NOT_A_PROXY,
                    "plain paper is not a proxy");

            // ITS TOOLTIP is the wish's words only: a copied look adds no line of its own ("No Effects", "Dyed").
            ItemStack potion = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                    WatchPanel.Action.CARD, "Potion " + tag, "Potion " + tag, recipe + "p", "Potion " + tag,
                    "minecraft:potion", List.of()), p.getUUID());
            var shown = potion.get(DataComponents.TOOLTIP_DISPLAY);
            check(helper, potion.has(DataComponents.POTION_CONTENTS) && shown != null
                    && shown.hiddenComponents().contains(DataComponents.POTION_CONTENTS),
                    "a potion's picture brings its colour, not its 'No Effects' line: " + shown);

            // A DYE-TINTED picture loses the glint (it washed the sprite out); the "Which slot?" tile draws each
            // stack as a picture a tile accepts: the watch, a sprite as its wish: string, a proxy as its picture.
            String spriteIcon = WatchIcons.sprite("baby_dragon", 0x55CC33);
            ItemStack dyedPicture = WatchMenu.iconStack(spriteIcon);
            ItemStack dragon = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                    WatchPanel.Action.CARD, "Dragon " + tag, "Dragon " + tag, recipe + "d", "Dragon " + tag,
                    spriteIcon, List.of()), p.getUUID());
            if (dyedPicture.has(DataComponents.DYED_COLOR)) {   // a world with the carrier off draws no sprite
                check(helper, !dragon.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "a dyed sprite's proxy has no glint");
                check(helper, spriteIcon.equals(WishHotbar.tileItem(dyedPicture)), "a sprite tile: " + WishHotbar.tileItem(dyedPicture));
            }
            check(helper, spriteIcon.equals(WishHotbar.tileItem(dragon)), "a proxy's tile is its picture: " + WishHotbar.tileItem(dragon));
            check(helper, com.moddingfromamod.ui.PickPage.WATCH_ICON.equals(WishHotbar.tileItem(VanillaWatchItem.stack())),
                    "Moddy's tile is the watch");
            check(helper, "minecraft:dirt".equals(WishHotbar.tileItem(new ItemStack(Items.DIRT))), "anything else is its item");

            // SOMEONE ELSE'S: told so, nothing made.
            ItemStack theirs = WishHotbar.proxy(cell("Theirs " + tag, recipe + "x"), UUID.randomUUID());
            check(helper, WishHotbar.use(p, theirs).used() == WishHotbar.Used.NOT_YOURS, "another player's proxy is refused");

            // ADD AGAIN never makes two; a FULL hotbar asks, and the slot he gives up moves to his bag.
            for (int i = 0; i < WishHotbarPlan.SLOTS; i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(i, new ItemStack(Items.DIRT));
                }
            }
            WatchPanel.Cell other = cell("Second Fixture " + tag, recipe);
            inv.setItem(1, new ItemStack(Items.DIRT));
            WishHotbar.add(p, other);
            check(helper, !WishHotbar.has(p, recipe), "a full hotbar places nothing until he picks a slot");
            WishHotbar.pickedSlot(p, "slot0", other);
            check(helper, recipe.equals(WishHotbar.recipeOf(inv.getItem(0))), "the picked slot holds the proxy");
            check(helper, inv.getItem(WishHotbarPlan.SLOTS).is(Items.STICK), "what was there moved into his bag");
            WishHotbar.add(p, other);
            WishHotbar.pickedSlot(p, "slot3", other);
            int count = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                count += recipe.equals(WishHotbar.recipeOf(inv.getItem(i))) ? 1 : 0;
            }
            check(helper, count == 1 && recipe.equals(WishHotbar.recipeOf(inv.getItem(3))), "never two of one wish: " + count);

            // OFF.
            check(helper, WishHotbar.press(p, WishHotbar.REMOVE_ID, thing) && !WishHotbar.has(p, recipe),
                    "the tile takes it off again");
        });
        helper.succeed();
    }
}
