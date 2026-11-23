package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.RemoveItemTool;

import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * The inverse `make_item` never had —.
 *
 * <p><b>Deliberately tests NO player.</b> The tool clears carried stacks as well as the design, but
 * a GameTest that installs a mock player joins the server's one shared player list, which is how
 * came about: six concurrent tests each changed somebody else's player and failing tests
 * leaked theirs into later batches. Everything asserted here is about the STORES, which are
 * plot-independent — so this is safe to run alongside anything, and the inventory half is left to
 * the client probe, which has a real player and no shared-state problem.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RemoveItemGameTest {

    private static JsonObject args(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        return o;
    }

    /**
     * Fire the tool off the server thread and poll for the answer. NEVER join it.
     *
     * <p>A GameTest body runs ON the server thread and {@code AgentBridge.onServer} refuses to be
     * called from there — correctly, since blocking would deadlock the game. The first version of
     * this file called the tool directly and failed with "Could not take that away:
     * IllegalStateException", which reads exactly like the feature being broken.
     */
    private static AtomicReference<ToolResult> callAsync(JsonObject a) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(new RemoveItemTool().call(a)), "remove-item-test");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static ItemSpecStore.Spec spec(String id, String name) {
        return new ItemSpecStore.Spec(id, name, "wand", "purple", "held", 1,
                java.util.List.of("freeze"), new JsonObject());
    }

    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "removeitem")
    public void anItemCanBeTakenBackByIdOrByName(GameTestHelper helper) {
        ItemSpecStore.put(spec("gt_zonkbolt", "Zonkbolt"));
        helper.assertTrue(ItemSpecStore.get("gt_zonkbolt") != null, Component.literal("the fixture item was not stored"));

        ItemSpecStore.put(spec("gt_icewand", "The Ice Wand"));

        // BY ID, and BY THE NAME THE CHILD USES — the second is the handle the agent reliably has,
        // since the id is derived and it may never have seen it.
        AtomicReference<ToolResult> byId = callAsync(args("gt_zonkbolt"));
        AtomicReference<ToolResult> byName = callAsync(args("The Ice Wand"));
        helper.succeedWhen(() -> {
            helper.assertTrue(byId.get() != null && byName.get() != null,
                    Component.literal("remove_item has not answered yet"));
            helper.assertTrue(!byId.get().isError(), Component.literal("by id refused: " + byId.get().text()));
            helper.assertTrue(!byName.get().isError(), Component.literal("by name refused: " + byName.get().text()));
            helper.assertTrue(ItemSpecStore.get("gt_zonkbolt") == null,
                    Component.literal("the design survived being removed — undo would still leave it behind"));
            helper.assertTrue(ItemSpecStore.get("gt_icewand") == null,
                    Component.literal("removal by the child's own name for it did nothing"));
        });
    }

    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "removeitem")
    public void removingSomethingThatIsNotThereSaysSoRatherThanClaimingSuccess(GameTestHelper helper) {
        // An agent told a removal succeeded when nothing matched would report a clean undo over an
        // item still sitting in the child's hand.
        AtomicReference<ToolResult> res = callAsync(args("gt_no_such_item_anywhere"));
        helper.succeedWhen(() -> {
            ToolResult r = res.get();
            helper.assertTrue(r != null, Component.literal("remove_item has not answered yet"));
            helper.assertTrue(r.isError(), Component.literal("removing a nonexistent item reported success: " + r.text()));
            helper.assertTrue(r.text().contains("inspect_item"),
                    Component.literal("the refusal should say how to find the real ones"));
        });
    }

    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "removeitem")
    public void makeItemIsRecordedAsMadeSoUndoStopsLying(GameTestHelper helper) {
        // MadeKind refuses to record a kind nothing can reverse. remove_item is that reverse, so
        // the kind exists now — and if either half is dropped the [undo] button starts lying again.
        helper.assertTrue(
                com.moddingfromamod.marshal.MadeKind.ITEM.equals(
                        com.moddingfromamod.marshal.MadeKind.forTool("make_item")),
                Component.literal("make_item is not recorded as making anything, so undo cannot reach the item"));
        helper.assertTrue(
                com.moddingfromamod.marshal.UndoContract.known("remove_item"),
                Component.literal("remove_item has no undo decision"));
        helper.succeed();
    }
}
