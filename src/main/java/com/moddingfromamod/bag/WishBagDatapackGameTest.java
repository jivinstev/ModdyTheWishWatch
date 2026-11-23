package com.moddingfromamod.bag;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.item.delivery.WishDelivery;
import com.moddingfromamod.wish.WatchPinService;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The path that lost the oasis wish's drinks </b>: a made item handed over the
 * way {@code make_item} tells the agent to — {@code wishdatapack <id> give}, a COMPILED datapack
 * function running vanilla {@code /give} with its output suppressed — into a FULL hotbar, in
 * Creative, where vanilla discards the stack and still says "Gave". It must land in his Wish Bag,
 * be recorded for the wish's undo, and the agent must be told where.
 *
 * <p>Its own batch: {@code make_item} installs and reloads the pack, and the wait for that reload
 * is only well defined while nobody else is reloading (batches are strictly sequential).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishBagDatapackGameTest {

    private static final String BATCH = "wishbag_datapack";

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void aMadeItemsDatapackGiveIntoAFullHotbarLandsInTheBag(GameTestHelper helper) {
        ServerPlayer child = WishBagGameTest.child(helper, "mfam-bag-dp", false);   // Creative
        for (int i = 2; i < 9; i++) {
            child.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        String wishKey = java.util.UUID.randomUUID().toString();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> agentSaw = new AtomicReference<>("");
        helper.startSequence()
                .thenExecute(() -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", "Gt Pineapple Paradise " + helper.getLevel().getGameTime());
                    o.addProperty("icon", "wand");
                    o.addProperty("colour", "yellow");
                    o.addProperty("shape", com.moddingfromamod.datapack.ItemCatalogue.PLAIN);
                    o.addProperty("count", 1);
                    MakeItemTool tool = new MakeItemTool();
                    ToolResult r = tool.call(o);
                    helper.assertTrue(!r.isError(), Component.literal("make_item refused: " + r.text()));
                    id.set(tool.lastMadeId());
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(DatapackReloader.reloadInProgress(), Component.literal("reload still in flight"));
                    helper.assertTrue(helper.getLevel().getServer().getFunctions()
                            .get(ResourceLocation.fromNamespaceAndPath(id.get(), "give")).isPresent(),
                            Component.literal("no " + id.get() + ":give yet"));
                })
                .thenExecute(() -> {
                    // EXACTLY what the agent's run_command does: through WishExecutor, as the
                    // wish, at the executor's permission.
                    var r = com.moddingfromamod.mob.SpawnScope.during(wishKey, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER,
                            () -> com.moddingfromamod.wish.WishExecutor.runOne(child.getServer(),
                                    child.level(), child.position(), child,
                                    "wishdatapack " + id.get() + " give"));
                    agentSaw.set(r.joined());
                    helper.assertTrue(r.success(), Component.literal("the give failed: " + r.joined()));
                })
                .thenExecute(() -> {
                    int bagged = 0;
                    for (ItemStack s : WishBagService.contents(child.getServer(), child.getUUID())) {
                        if (id.get().equals(WishItem.wishFeature(s))) {
                            bagged += s.getCount();
                        }
                    }
                    int main = 0;
                    for (int i = 9; i < 36; i++) {
                        if (id.get().equals(WishItem.wishFeature(child.getInventory().getItem(i)))) {
                            main++;
                        }
                    }
                    helper.assertValueEqual(bagged, 1, Component.literal("made items in his Wish Bag (vanilla /give in Creative"
                            + " DISCARDS into a full hotbar; this is the lost-drinks path)"));
                    helper.assertValueEqual(main, 0, Component.literal("made items in his main inventory"));
                    helper.assertTrue(agentSaw.get().contains("→ his Wish Bag"),
                            Component.literal("the agent was told '(no output)' again: " + agentSaw.get()));
                    helper.assertTrue(WishDelivery.handedOver(wishKey),
                            Component.literal("the hand-over was not recorded for the wish's undo"));
                    WatchPinService.unpinMockForTest(child);
                    com.moddingfromamod.testing.MockPlayerNetwork.remove(child);
                })
                .thenSucceed();
    }
}
