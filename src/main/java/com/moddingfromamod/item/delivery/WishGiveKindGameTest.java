package com.moddingfromamod.item.delivery;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.GiveItemsTool;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.bag.WishBagItem;
import com.moddingfromamod.bag.WishBagService;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.item.delivery.DeliveryPlan.Kind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.mob.SpawnScope;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.VanillaWatchItem;
import com.moddingfromamod.wish.WatchPin;
import com.moddingfromamod.wish.WatchPinService;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishGiveAgain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>If Moddy says it, it can go in the Wish Bag; nothing else can</b> — every source of a
 * give, in both directions, through the path it really takes.
 *
 * <p><b>A hand-over</b> (the granting of a wish) fills the hotbar and then the bag, and Moddy says
 * where: the agent's {@code run_command}, "Give me one", {@code give_items}, and a function run
 * inside a hand-over. A served wish's job (a button, a stock wish, a template) is
 * {@code wish.WishDeliveryGameTest}, where the job is reachable; {@code make_item}'s
 * {@code wishdatapack <id> give} is {@code bag.WishBagDatapackGameTest}.
 *
 * <p><b>A thing's own behaviour</b> fills the hotbar, then his main inventory, then the floor at his
 * feet — never the bag, never a Moddy line, never an undo record: a datapack function nothing else
 * scoped (the entry every item use, placed machine, refund, creature ability and clock shares —
 * {@code FunctionSpawnScopeMixin}) and a REAL {@code rich} item made by {@code make_item} and used the
 * way a right-click uses it. A world rule's is {@code rules.WorldRulesGameTest}, in that batch
 * because a rule is world-wide.
 *
 * <p>Each behaviour row gives 80 into a full hotbar and a main inventory with ONE free slot, so one
 * give proves both directions: 64 land in that slot, 16 on the floor at his feet.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishGiveKindGameTest {

    private static final String BATCH = "wishgivekind";

    /** The main-inventory slot left free in a behaviour row. */
    public static final int FREE = 20;

    /** A pinned child: Moddy in slot 1, the bag in slot 2, cobblestone in every other hotbar slot from 3 to {@code to}. */
    public static ServerPlayer child(GameTestHelper helper, String name, int filledThrough, boolean survival) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        if (survival) {
            MockPlayerNetwork.asSurvival(p);
        }
        WatchPinService.pinBagMockForTest(p);
        p.getInventory().clearContent();
        p.getInventory().setItem(WatchPin.HOME, VanillaWatchItem.stack());
        p.getInventory().setItem(WatchPin.BAG_HOME, WishBagItem.stack());
        for (int i = 2; i < filledThrough; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        WishBagService.clearForTest(p.level().getServer(), p.getUUID());
        sweep(p);
        return p;
    }

    /** Cookies on the floor around him: a reused GameTest world keeps what an earlier run dropped. */
    static void sweep(ServerPlayer p) {
        p.level().getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(4),
                e -> e.getItem().is(Items.COOKIE)).forEach(ItemEntity::discard);
    }

    /** A full hotbar, and a main inventory full but for slot {@link #FREE}. */
    public static ServerPlayer packed(GameTestHelper helper, String name, boolean survival) {
        ServerPlayer p = child(helper, name, 9, survival);
        for (int i = 9; i < 36; i++) {
            if (i != FREE) {
                p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            }
        }
        return p;
    }

    public static void done(ServerPlayer p) {
        sweep(p);                 // what a behaviour row dropped must not outlive it in the reused world
        WatchPinService.unpinMockForTest(p);
        MockPlayerNetwork.remove(p);
    }

    public static int inBag(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : WishBagService.contents(p.level().getServer(), p.getUUID())) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    public static int inMain(ServerPlayer p, Item item) {
        int n = 0;
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) {
                n += p.getInventory().getItem(i).getCount();
            }
        }
        return n;
    }

    /**
     * What is on the floor for him to pick up. Not vanilla's own pickup-animation items: a
     * successful {@code /give} drops a "fake" one-count copy ({@code makeFakeItem}: never
     * collectable, gone next tick) per stack it gives, and our drops have no pickup delay at all.
     */
    static List<ItemEntity> onFloor(ServerPlayer p, Item item) {
        return p.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(p.blockPosition()).inflate(3), e -> e.getItem().is(item) && !e.hasPickUpDelay());
    }

    public static int countOnFloor(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemEntity e : onFloor(p, item)) {
            n += e.getItem().getCount();
        }
        return n;
    }

    /** Every line Moddy has said to him that names {@code what}, after the end-of-tick flush. */
    public static List<String> moddySaid(ServerPlayer p, String what) {
        WishDelivery.flush(p.level().getServer());
        return MockPlayerNetwork.chat(p).stream().filter(l -> l.contains(what)).toList();
    }

    /** The source an item's use runs its function with ({@code item.WishItemActions.runFunction}). */
    static CommandSourceStack useSource(ServerPlayer p) {
        return com.moddingfromamod.compat.EntityCommands.source(p).withPermission(2).withSuppressedOutput();
    }

    /** Run {@code lines} as a datapack function, as the player, the way an item's use runs one. */
    static void runAsFunction(ServerPlayer p, String... lines) {
        var functions = p.level().getServer().getFunctions();
        CommandSourceStack source = useSource(p);
        CommandFunction<CommandSourceStack> fn = CommandFunction.fromLines(
                Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "gt_give_kind"),
                functions.getDispatcher(), source, List.of(lines));
        functions.execute(fn, source);
    }

    /**
     * The behaviour half of every row: of 80, 64 in the free main slot, 16 on the floor at his feet
     * (his, with no pickup delay), none in the bag, no Moddy line, nothing for an undo.
     */
    static void assertBehaviour(GameTestHelper helper, ServerPlayer p, Item item, String name) {
        helper.assertValueEqual(inMain(p, item), 64, Component.literal("of 80 " + name + "s, in his main inventory (the hotbar is full)"));
        helper.assertTrue(p.getInventory().getItem(FREE).is(item), Component.literal("slot " + FREE + " holds " + p.getInventory().getItem(FREE)));
        helper.assertValueEqual(countOnFloor(p, item), 16, Component.literal("of 80 " + name + "s, on the floor at his feet "
                + "(his inventory is full), with no pickup delay"));
        for (ItemEntity e : onFloor(p, item)) {
            var saved = com.moddingfromamod.compat.EntityNbt.save(e);
            helper.assertTrue(com.moddingfromamod.compat.EntityNbt.hasUuid(saved, "Owner") && p.getUUID().equals(com.moddingfromamod.compat.EntityNbt.getUuid(saved, "Owner")),
                    Component.literal("the drop at his feet is not his (no Owner): " + saved));
        }
        helper.assertValueEqual(inBag(p, item), 0, Component.literal(name + "s in his Wish Bag — a thing's own give may NEVER go there"));
        helper.assertValueEqual(moddySaid(p, name).size(), 0, Component.literal("Moddy announced a thing's own give: " + MockPlayerNetwork.chat(p)));
    }

    // ── a hand-over: the hotbar, then the bag, said ──────────────────────────────────────────

    /** The agent's run_command (no scope open: WishExecutor.runOne opens a hand-over). */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theAgentsGiveFillsTheHotbarThenTheBagAndIsSaid(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-kind-cmd", 8, false);       // slot 9 free
        try {
            var r = WishExecutor.runOne(p.level().getServer(), p.level(), p.position(), p, "give @s minecraft:cookie 80");
            helper.assertTrue(r.success(), Component.literal("the give failed: " + r.joined()));
            helper.assertValueEqual(p.getInventory().getItem(8).getCount(), 64, Component.literal("cookies in slot 9"));
            helper.assertValueEqual(inBag(p, Items.COOKIE), 16, Component.literal("cookies in his Wish Bag"));
            helper.assertValueEqual(inMain(p, Items.COOKIE), 0, Component.literal("cookies in his MAIN inventory"));
            helper.assertValueEqual(countOnFloor(p, Items.COOKIE), 0, Component.literal("cookies on the floor"));
            List<String> said = moddySaid(p, "Cookies");
            helper.assertTrue(said.size() == 1 && said.get(0).contains("slot 9") && said.get(0).contains("Wish Bag"),
                    Component.literal("Moddy did not say where the hand-over landed: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    /** "Give me one" — the card's link, and /moddy give. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void giveMeOneIntoAFullHotbarGoesToTheBagAndIsSaid(GameTestHelper helper) {
        String name = "Gt Kind Lucky Coin";
        ItemStack made = new ItemStack(Items.GOLD_NUGGET);
        made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        WishItemCatalogue.remember(helper.getLevel().getServer(), made);
        ServerPlayer p = child(helper, "mfam-kind-again", 9, false);
        try {
            WishGiveAgain.Result r = WishGiveAgain.give(p, name);
            helper.assertTrue(r == WishGiveAgain.Result.GAVE, Component.literal("Give me one gave nothing: " + r));
            int bagged = 0;
            for (ItemStack s : WishBagService.contents(p.level().getServer(), p.getUUID())) {
                if (s.getHoverName().getString().equals(name)) {
                    bagged += s.getCount();
                }
            }
            helper.assertValueEqual(bagged, 1, Component.literal("lucky coins in his Wish Bag"));
            helper.assertTrue(MockPlayerNetwork.chat(p).stream().anyMatch(l -> l.contains("Wish Bag")),
                    Component.literal("Moddy did not say it is in his Wish Bag: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            WishItemCatalogue.forget(name);
            done(p);
        }
    }

    /** A made item's give is a FUNCTION; run inside a wish it stays that wish's hand-over. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aFunctionRunByAWishKeepsTheHandOver(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-kind-fnwish", 9, false);
        try {
            String wish = UUID.randomUUID().toString();
            SpawnScope.during(wish, Kind.HANDOVER, () -> {
                runAsFunction(p, "give @s minecraft:cookie 80");
                return null;
            });
            helper.assertValueEqual(inBag(p, Items.COOKIE), 80, Component.literal("cookies in his Wish Bag"));
            helper.assertValueEqual(inMain(p, Items.COOKIE), 0, Component.literal("cookies in his main inventory"));
            helper.assertTrue(WishDelivery.handedOver(wish), Component.literal("the hand-over was not recorded for the wish's undo"));
            helper.assertTrue(moddySaid(p, "Cookies").size() == 1, Component.literal("Moddy did not say it: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    // ── a thing's own behaviour: the hotbar, the inventory, his feet; never the bag, never said ─

    /**
     * A datapack function nothing else scoped — the entry an item's use, a placed machine's pull or
     * refund, a creature's ability and a clock all go through ({@code FunctionSpawnScopeMixin}).
     */
    private static void aFunctionsOwnGive(GameTestHelper helper, boolean survival) {
        ServerPlayer p = packed(helper, survival ? "mfam-kind-fn-s" : "mfam-kind-fn-c", survival);
        try {
            helper.assertTrue(SpawnScope.current() == null, Component.literal("a scope is already open, so this would not measure the entry"));
            runAsFunction(p, "give @s minecraft:cookie 80");
            assertBehaviour(helper, p, Items.COOKIE, "Cookie");
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aFunctionsOwnGiveGoesToTheInventoryThenHisFeetInCreative(GameTestHelper helper) {
        aFunctionsOwnGive(helper, false);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aFunctionsOwnGiveGoesToTheInventoryThenHisFeetInSurvival(GameTestHelper helper) {
        aFunctionsOwnGive(helper, true);
    }

    /**
     * Vanilla's Creative pickup DELETES what it cannot fit. The drop at his feet must survive his
     * touching it while he is full, and come to him the moment he has room.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aDropAtHisFeetSurvivesACreativePickupWhileHeIsFull(GameTestHelper helper) {
        ServerPlayer p = packed(helper, "mfam-kind-pickup", false);       // Creative
        try {
            runAsFunction(p, "give @s minecraft:cookie 80");
            List<ItemEntity> floor = onFloor(p, Items.COOKIE);
            helper.assertValueEqual(floor.size(), 1, Component.literal("item entities at his feet"));
            ItemEntity e = floor.get(0);
            e.playerTouch(p);                       // the real pickup, with every slot full
            helper.assertTrue(e.isAlive() && e.getItem().getCount() == 16,
                    Component.literal("a Creative pickup with a full inventory voided the drop: alive=" + e.isAlive()
                            + " count=" + e.getItem().getCount()));
            p.getInventory().setItem(30, ItemStack.EMPTY);        // he makes room
            e.playerTouch(p);
            helper.assertFalse(e.isAlive(), Component.literal("the drop did not come to him once he had room"));
            helper.assertValueEqual(p.getInventory().getItem(30).getCount(), 16, Component.literal("cookies picked up into slot 30"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    // ── give_items, and a REAL rich item: their own batches ─────────────────────────────────

    /**
     * {@code give_items}, called the way the agent calls it: off the server thread, as a bound
     * worker granting HIS wish. Its own batch, because the Marshal is process-wide.
     */
    @GameTest(template = "empty_test", batch = BATCH + "_giveitems", timeoutTicks = 4000)
    public static void giveItemsFillsTheHotbarThenTheBagAndIsSaid(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = child(helper, "mfam-kind-items", 8, false);
        UUID wish = UUID.randomUUID();
        String worker = "gt-givekind-" + wish;
        Marshal.global().submit(wish, p.getUUID(), "gt give kind");
        Marshal.global().assign(worker, wish);
        AtomicReference<ToolResult> given = new AtomicReference<>();
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                given.set(new GiveItemsTool().call(JsonParser.parseString(
                        "{\"item\":\"minecraft:cookie\",\"count\":80}").getAsJsonObject()));
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-givekind-items");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(given.get() != null, Component.literal("waiting for give_items"));
            try {
                helper.assertFalse(given.get().isError(), Component.literal("give_items refused: " + given.get().text()));
                helper.assertValueEqual(p.getInventory().getItem(8).getCount(), 64, Component.literal("cookies in slot 9"));
                helper.assertValueEqual(inBag(p, Items.COOKIE), 16, Component.literal("cookies in his Wish Bag"));
                helper.assertValueEqual(inMain(p, Items.COOKIE), 0, Component.literal("cookies in his main inventory"));
                helper.assertTrue(WishDelivery.handedOver(wish.toString()), Component.literal("not recorded for the wish's undo"));
                helper.assertTrue(moddySaid(p, "Wish Bag").size() == 1,
                        Component.literal("Moddy did not say where give_items' things landed: " + MockPlayerNetwork.chat(p)));
            } finally {
                done(p);
            }
        });
    }

    /**
     * A REAL treasure item: {@code make_item} with {@code effect: rich} (a vending machine's and a
     * lucky coin's effect), right-clicked the way {@code WishItemActions} runs its use. Its own batch:
     * {@code make_item} installs and reloads a pack.
     */
    @GameTest(template = "empty_test", batch = BATCH + "_rich", timeoutTicks = 4000)
    public static void aRichItemsUseGoesToTheInventoryThenHisFeetNeverTheBag(GameTestHelper helper) {
        ServerPlayer p = packed(helper, "mfam-kind-rich", false);          // Creative: the case that deletes
        AtomicReference<String> id = new AtomicReference<>();
        helper.startSequence()
                .thenExecute(() -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", "Gt Treasure Wand " + helper.getLevel().getGameTime());
                    o.addProperty("icon", "orb");
                    o.addProperty("colour", "yellow");
                    o.addProperty("shape", "held");
                    o.addProperty("effect", "rich");
                    o.addProperty("count", 1);
                    JsonObject params = new JsonObject();
                    params.addProperty("ITEM", "minecraft:cookie");
                    params.addProperty("COUNT", "80");
                    o.add("params", params);
                    MakeItemTool tool = new MakeItemTool();
                    ToolResult r = tool.call(o);
                    helper.assertTrue(!r.isError() && tool.lastMadeId() != null,
                            Component.literal("make_item made nothing: " + r.text()));
                    id.set(tool.lastMadeId());
                })
                .thenWaitUntil(() -> {
                    // An exception that is not an assertion crashes the whole server, so never
                    // let a missing id reach Identifier.
                    helper.assertTrue(id.get() != null, Component.literal("make_item made nothing"));
                    helper.assertFalse(DatapackReloader.reloadInProgress(), Component.literal("reload still in flight"));
                    helper.assertTrue(helper.getLevel().getServer().getFunctions()
                            .get(Identifier.fromNamespaceAndPath(id.get(), "act")).isPresent(),
                            Component.literal("no " + id.get() + ":act yet"));
                })
                .thenExecute(() -> {
                    // Exactly what a right-click runs: the made item's own use function, as him.
                    var functions = p.level().getServer().getFunctions();
                    var act = functions.get(Identifier.fromNamespaceAndPath(id.get(), "act")).orElseThrow();
                    functions.execute(act, useSource(p));
                })
                .thenExecute(() -> {
                    try {
                        assertBehaviour(helper, p, Items.COOKIE, "Cookie");
                    } finally {
                        done(p);
                    }
                })
                .thenSucceed();
    }
}
