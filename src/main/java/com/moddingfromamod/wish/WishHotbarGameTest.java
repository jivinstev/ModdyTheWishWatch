package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;

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
            // the Moddy badge marks it a wish now, so the glint has gone
            check(helper, WishHotbar.badged(proxy) && !proxy.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                    "the proxy wears the Moddy badge and no glint: " + proxy.getComponentsPatch());
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
            // A VANILLA picture the rule draws goes under the badge, named by its id; one the rule cannot draw (a
            // tinted potion, an exception) keeps its own picture and the glint (pack/HotbarBadge).
            ItemStack cake = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                    WatchPanel.Action.CARD, "Cake " + tag, "Cake " + tag, recipe + "c", "Cake " + tag,
                    "minecraft:cake", List.of()), p.getUUID());
            var cakeModel = cake.get(DataComponents.CUSTOM_MODEL_DATA);
            check(helper, com.moddingfromamod.pack.HotbarBadge.ITEM_MODEL.equals(String.valueOf(cake.get(DataComponents.ITEM_MODEL)))
                    && cakeModel != null && "minecraft:cake".equals(cakeModel.getString(
                            com.moddingfromamod.pack.HotbarBadge.VANILLA_INDEX)) && WishHotbar.badged(cake),
                    "a vanilla picture is drawn by the hotbar definition, named by its id: " + cake.getComponentsPatch());
            check(helper, !WishHotbar.badged(potion) && Boolean.TRUE.equals(potion.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))
                    && "minecraft:potion".equals(String.valueOf(potion.get(DataComponents.ITEM_MODEL))),
                    "an exception (a tinted potion) keeps its own picture and the glint: " + potion.getComponentsPatch());
            // THE RULE AGREES WITH THE ITEM REGISTRY: every id it draws is a real item, drawn as a block exactly
            // when it is one (or on the flat list), and no exception is drawn.
            for (String id : com.moddingfromamod.pack.HotbarBadge.vanillaIds()) {
                var rl = net.minecraft.resources.ResourceLocation.parse(id);
                boolean block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl);
                check(helper, net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl), id + " is not an item here");
                check(helper, com.moddingfromamod.pack.HotbarBadge.ruleModel(id, block,
                                com.moddingfromamod.pack.HotbarBadge.flatBlocks().contains(id))
                                .equals(com.moddingfromamod.pack.HotbarBadge.modelOf(id)),
                        id + ": the registry says block=" + block + " but the definition draws " + com.moddingfromamod.pack.HotbarBadge.modelOf(id));
            }

            // A DYE-TINTED picture loses the glint (it washed the sprite out); the "Which slot?" tile draws each
            // stack as a picture a tile accepts: the watch, a sprite as its wish: string, a proxy as its picture.
            String spriteIcon = WatchIcons.sprite("baby_dragon", 0x55CC33);
            ItemStack dyedPicture = WatchMenu.iconStack(spriteIcon);
            ItemStack dragon = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                    WatchPanel.Action.CARD, "Dragon " + tag, "Dragon " + tag, recipe + "d", "Dragon " + tag,
                    spriteIcon, List.of()), p.getUUID());
            if (dyedPicture.has(DataComponents.DYED_COLOR)) {   // a world with the carrier off draws no sprite
                check(helper, !dragon.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "a dyed sprite's proxy has no glint");
                // A SPRITE keeps its own definition and number, and sets the badge flag; its picture in the
                // history does not, so the history draws no badge.
                check(helper, WishHotbar.badged(dragon) && !WishHotbar.badged(dyedPicture)
                        && java.util.Objects.equals(dragon.get(DataComponents.ITEM_MODEL), dyedPicture.get(DataComponents.ITEM_MODEL))
                        && java.util.Objects.equals(dragon.get(DataComponents.CUSTOM_MODEL_DATA).floats(),
                                dyedPicture.get(DataComponents.CUSTOM_MODEL_DATA).floats()),
                        "a sprite's proxy wears the badge over the same picture: " + dragon.getComponentsPatch());
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

    // ── knocked out ────────────────────────────────────────────────────────────────────────

    /**
     * A REAL death and respawn ({@code PlayerList.respawn}, which fires the Clone from vanilla's own
     * {@code restoreFrom}): two wishes in slots 2 and 6 are back in exactly those slots, one of each, and none lay in
     * the world, with {@code keepInventory} as given. Each arm in a batch of its own: the game rule is the server's.
     */
    private static void knockedOut(GameTestHelper helper, String name, boolean keepInventory) {
        ServerLevel level = helper.getLevel();
        var rule = level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean was = rule.get();
        rule.set(keepInventory, level.getServer());
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, name));
        ServerPlayer reborn = null;
        BlockPos where = p.blockPosition();
        try {
            String tag = UUID.randomUUID().toString().substring(0, 6);
            String a = "gt2804_a_" + tag;
            String b = "gt2804_b_" + tag;
            var inv = p.getInventory();
            inv.clearContent();
            inv.setItem(2, WishHotbar.proxy(cell("Knocked A " + tag, a), p.getUUID()));
            inv.setItem(3, new ItemStack(Items.DIRT, 5));
            inv.setItem(6, WishHotbar.proxy(cell("Knocked B " + tag, b), p.getUUID()));

            p.die(p.damageSources().generic());
            check(helper, WishHotbar.keeping(p), "his wishes were not written down at the death");
            check(helper, level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8),
                    e -> WishHotbar.recipeOf(e.getItem()) != null).isEmpty(),
                    "keepInventory=" + keepInventory + ": a wish lay in the world after he was knocked out");
            boolean dirtDropped = !level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8),
                    e -> e.getItem().is(Items.DIRT)).isEmpty();
            check(helper, dirtDropped == !keepInventory, "keepInventory=" + keepInventory
                    + " but the dirt " + (dirtDropped ? "dropped" : "did not drop") + ", so the rule was not in force");

            reborn = p.getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
            check(helper, reborn != p, "respawn returned the same body, so nothing was tested");
            var after = reborn.getInventory();
            check(helper, a.equals(WishHotbar.recipeOf(after.getItem(2))) && b.equals(WishHotbar.recipeOf(after.getItem(6))),
                    "keepInventory=" + keepInventory + ": his wishes are not back in slots 3 and 7: "
                            + after.getItem(2) + ", " + after.getItem(6));
            int count = 0;
            for (int i = 0; i < after.getContainerSize(); i++) {
                count += WishHotbar.recipeOf(after.getItem(i)) != null ? 1 : 0;
            }
            check(helper, count == 2, "keepInventory=" + keepInventory + ": " + count + " wishes, not one of each");
            check(helper, after.getItem(3).is(Items.DIRT) == keepInventory,
                    "keepInventory=" + keepInventory + ": the rest of his inventory did not follow the rule");
            check(helper, !WishHotbar.keeping(reborn), "the stash was given back and kept, so the next death doubles it");
            check(helper, reborn.getStringUUID().equals(WishHotbar.ownerOf(after.getItem(2))), "the wish is still his");
            helper.succeed();
        } finally {
            rule.set(was, level.getServer());
            level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8)).forEach(Entity::discard);
            if (reborn != null && reborn != p) {
                MockPlayerNetwork.remove(reborn);
            }
        }
    }

    @GameTest(template = "empty_test", batch = "wishhotbar_death_drop", timeoutTicks = 100)
    public static void knockedOutWithoutKeepInventoryKeepsHisWishesInTheirSlots(GameTestHelper helper) {
        knockedOut(helper, "mfam-hotbar-ko", false);
    }

    @GameTest(template = "empty_test", batch = "wishhotbar_death_keep", timeoutTicks = 100)
    public static void knockedOutWithKeepInventoryKeepsExactlyOneOfEach(GameTestHelper helper) {
        knockedOut(helper, "mfam-hotbar-keep", true);
    }

    // ── the world's rest ───────────────────────────────────────────────────────────────────

    /**
     * A world's charge that rests a wish for ten seconds after a fire it allows, on a clock the test moves, and always
     * refuses one wish for a reason that is not a rest. It keys its rest by the wish's words, which for a plain recipe
     * are the recipe id ({@code WishCommands.again}).
     */
    static final class TenSecondRest implements WishCharge {
        static final int REST = 200;
        final java.util.Map<String, Long> until = new java.util.HashMap<>();
        final String refused;
        long now;

        TenSecondRest(String refused) {
            this.refused = refused;
        }

        private String key(ServerPlayer p, String wish) {
            return p.getStringUUID() + "/" + wish;
        }

        @Override
        public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
            if (text.equals(refused)) {
                player.sendSystemMessage(Component.literal("Not here."));
                return true;
            }
            long end = until.getOrDefault(key(player, text), 0L);
            if (end > now) {
                player.sendSystemMessage(Component.literal("Still resting."));
                return true;
            }
            until.put(key(player, text), now + REST);
            return false;
        }

        @Override
        public int restLeft(ServerPlayer player, String recipeId) {
            if (recipeId.equals(refused)) {
                return 0;
            }
            return (int) Math.max(0, until.getOrDefault(key(player, recipeId), 0L) - now);
        }
    }

    /** Ticks this proxy's vanilla cooldown has left, read off the game's own {@code ItemCooldowns} by ticking it on. */
    private static int ticksLeft(ServerPlayer p, ItemStack proxy, int atMost) {
        // A copy would be better, but ItemCooldowns has no reader of the end tick: tick the real one and count. A
        // GameTest body runs inside one server tick, so nothing else ticks it meanwhile. Only ever the last read of a
        // rest in this test, since it uses the rest up.
        var cd = p.getCooldowns();
        int n = 0;
        while (cd.isOnCooldown(proxy) && n <= atMost) {
            cd.tick();
            n++;
        }
        return n;
    }

    /** Move the world's clock and the game's cooldowns on by the same ticks. */
    private static void pass(ServerPlayer p, TenSecondRest charge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            p.getCooldowns().tick();
        }
        charge.now += ticks;
    }

    @GameTest(template = "empty_test", batch = "wishhotbar_rest", timeoutTicks = 200)
    public static void theSweepShowsTheRestTheWorldsChargeNames(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        var server = helper.getLevel().getServer();
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        String tag = UUID.randomUUID().toString().substring(0, 6);
        String strong = "gt15_rest_" + tag;
        String free = "gt15_free_" + tag;
        String nope = "gt15_nope_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        for (String r : List.of(strong, free, nope)) {
            with.add("{\"id\":\"" + r + "\",\"all\":[\"" + r.replace("_", "") + "\"],\"ownerId\":\""
                    + p.getStringUUID() + "\",\"commands\":[\"say a resting wish\"]}");
        }
        LearnedTemplates.restoreForTest(with, server);
        TenSecondRest charge = new TenSecondRest(nope);
        ServerPlayer[] rebornHolder = new ServerPlayer[1];
        MockPlayerNetwork.afterTest(helper, () -> {
            if (rebornHolder[0] != null && rebornHolder[0] != p) {
                MockPlayerNetwork.remove(rebornHolder[0]);
            }
            Plugins.shared().replaceForTest(WishCharge.class, null);
            SafetyGrader.forTest(null);
            LearnedTemplates.restoreForTest(had, server);
            MockPlayerNetwork.remove(p);
        });

        MockPlayerNetwork.withoutChannels(p, () -> {
            var inv = p.getInventory();
            inv.clearContent();

            // NO CHARGE: the one second, as before.
            Plugins.shared().replaceForTest(WishCharge.class, null);
            check(helper, WishCharge.get() == WishCharge.NONE, "a charge is in force here, so the default is not tested");
            ItemStack plain = WishHotbar.proxy(cell("Free " + tag, free), p.getUUID());
            WishHotbar.Use use = WishHotbar.use(p, plain);
            check(helper, use.outcome() == WishService.Outcome.FULFILLING, "with no charge the wish is made: " + use);
            int left = ticksLeft(p, plain, 1000);
            check(helper, left == WishHotbar.REST_TICKS, "with no charge the rest is one second, not " + left + " ticks");

            // A FIRE IT ALLOWS: the sweep shows the ten seconds it names.
            Plugins.shared().replaceForTest(WishCharge.class, charge);
            WishHotbar.add(p, cell("Strong " + tag, strong));
            ItemStack proxy = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                proxy = strong.equals(WishHotbar.recipeOf(inv.getItem(i))) ? inv.getItem(i) : proxy;
            }
            check(helper, proxy != null, "the wish did not go on his hotbar");
            check(helper, !p.getCooldowns().isOnCooldown(proxy), "a wish that never fired is ready when added");
            use = WishHotbar.use(p, proxy);
            check(helper, use.outcome() == WishService.Outcome.FULFILLING, "the charge allows the first fire: " + use);
            check(helper, p.getCooldowns().isOnCooldown(proxy), "the proxy rests after the fire");
            pass(p, charge, TenSecondRest.REST - 1);
            check(helper, p.getCooldowns().isOnCooldown(proxy),
                    "one tick before the world's ten seconds are up, the sweep has already finished");

            // A CLICK WHILE THE SWEEP RUNS: nothing is made and the rest is not restarted.
            check(helper, WishHotbar.use(p, proxy).used() == WishHotbar.Used.RESTING, "a resting proxy makes nothing");
            pass(p, charge, 1);
            check(helper, !p.getCooldowns().isOnCooldown(proxy), "the sweep outlasted the world's ten seconds");

            // A FIRE IT REFUSES BECAUSE IT IS STILL RESTING shows the time LEFT, not a fresh ten seconds: here the
            // game's sweep was lost (fired from My wishes, a new client) while the world's rest ran on.
            WishService.forget(p);   // ten seconds have passed for the world: the limiter's double-click guard is not under test
            use = WishHotbar.use(p, proxy);
            check(helper, use.outcome() == WishService.Outcome.FULFILLING, "the second rest starts: " + use);
            pass(p, charge, 60);
            p.getCooldowns().removeCooldown(WishHotbar.group(strong));
            use = WishHotbar.use(p, proxy);
            check(helper, use.outcome() == WishService.Outcome.BANK_EMPTY, "the charge refuses a resting wish: " + use);
            left = ticksLeft(p, proxy, 1000);
            check(helper, left == TenSecondRest.REST - 60, "refused 60 ticks into a ten-second rest, the sweep has "
                    + left + " ticks left, not " + (TenSecondRest.REST - 60));
            charge.now += left;   // the world's clock catches up with the ticks counted

            WishService.forget(p);   // ten seconds have passed for the world: the limiter's double-click guard is not under test
            use = WishHotbar.use(p, proxy);
            check(helper, use.outcome() == WishService.Outcome.FULFILLING, "the third rest starts: " + use);
            pass(p, charge, 70);

            // TAKEN OFF AND ADDED AGAIN: the rest is neither dodged nor lost, even with the game's own sweep gone.
            WishHotbar.remove(p, strong);
            p.getCooldowns().removeCooldown(WishHotbar.group(strong));
            WishHotbar.add(p, cell("Strong " + tag, strong));
            ItemStack again = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                again = strong.equals(WishHotbar.recipeOf(inv.getItem(i))) ? inv.getItem(i) : again;
            }
            check(helper, again != null, "the wish did not go back on his hotbar");
            left = ticksLeft(p, again, 1000);
            check(helper, left == TenSecondRest.REST - 70, "re-added 70 ticks into a ten-second rest, the sweep has "
                    + left + " ticks left, not " + (TenSecondRest.REST - 70));
            charge.now += left;

            // A FIRE IT REFUSES FOR ANOTHER REASON: no long sweep (the charge says it is ready).
            ItemStack refused = WishHotbar.proxy(cell("Nope " + tag, nope), p.getUUID());
            use = WishHotbar.use(p, refused);
            check(helper, use.outcome() == WishService.Outcome.BANK_EMPTY, "the charge refuses this wish: " + use);
            check(helper, !p.getCooldowns().isOnCooldown(refused), "a wish refused for another reason shows a sweep");

            // KNOCKED OUT MID-REST: the new body has no cooldowns of its own, and the wish comes back still resting.
            WishService.forget(p);
            use = WishHotbar.use(p, again);
            check(helper, use.outcome() == WishService.Outcome.FULFILLING, "the fourth rest starts: " + use);
            pass(p, charge, 30);
            ServerPlayer survivor = MockPlayerNetwork.asSurvival(p);
            survivor.die(survivor.damageSources().generic());
            ServerPlayer reborn = server.getPlayerList().respawn(survivor, false, Entity.RemovalReason.KILLED);
            rebornHolder[0] = reborn;
            check(helper, reborn != survivor, "respawn returned the same body, so nothing was tested");
            ItemStack back = null;
            for (int i = 0; i < reborn.getInventory().getContainerSize(); i++) {
                back = strong.equals(WishHotbar.recipeOf(reborn.getInventory().getItem(i)))
                        ? reborn.getInventory().getItem(i) : back;
            }
            check(helper, back != null, "the wish did not come back after the knock-out");
            left = ticksLeft(reborn, back, 1000);
            check(helper, left == TenSecondRest.REST - 30, "knocked out 30 ticks into a ten-second rest, the sweep has "
                    + left + " ticks left, not " + (TenSecondRest.REST - 30));
        });
        helper.succeed();
    }
}
