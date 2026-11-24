package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>One instrument: how many things are in his inventory afterwards.</b>
 *
 * <p>"Does a wish leave the right number of things" is one question with three faces, and it names one number for all of them:
 *
 * <pre>
 *   "make my sword unbreakable"            edit it          items after: 1
 *   "and one that shoves the noob in lava"  mint a second    items after: 2, the first untouched
 *   refines a thing he never made here      edit THAT stack  items after: 1, same stack
 * </pre>
 *
 * <p><b>Every earlier gate on this asserted the STORE instead</b> — {@code ItemEditGameTest} says
 * so in as many words: <i>"no tier in this repo can see a child's inventory. Design count is the
 * closest honest proxy."</i> It is not quite honest, and the evidence was already on file:
 * {@code docs/rollout/ITEMS.md} records {@code heldCount} dropping 672 → 192 across a rename rung
 * <i>"because the stacks handed over under the old name stopped matching"</i> — i.e. the old
 * stacks were STILL IN HIS BAG beside the new ones. One design in the store, two things in his
 * hands. The store cannot see that, and the child can see nothing else.
 *
 * <p>So this uses a real {@link ServerPlayer} ({@link MockPlayerNetwork}) and drives the whole
 * path an agent drives: the real {@code make_item}, the real datapack reload, and then the exact
 * command the tool tells the agent to run — {@code wishdatapack <id> give} — dispatched as the
 * child. What is counted is what he is holding.
 *
 * <h2>Its own batch, and why that is not optional</h2>
 *
 * <p>A datapack reload is asynchronous: {@code make_item} kicks it and the server thread finishes
 * it over later ticks, so every step waits for the reload it caused before handing anything over.
 * That wait is only well defined if nobody ELSE is reloading, and tests in one batch run
 * concurrently. Batches are strictly sequential (CLAUDE.md), so a named batch is real isolation.
 * The mock player is the other reason: tools resolve "the child" as the first player in the list,
 * so a mock alive during somebody else's test moves where their tool looks.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class InventoryCountGameTest {

    private static final String BATCH = "inventoryCount";

    private InventoryCountGameTest() {
    }

    // ── the three faces ──────────────────────────────────────────────────────────────────────

    /**
     * <b>A refinement leaves him ONE, and it is the changed one.</b> (an earlier issue's face on a thing the
     * engine made — the case the lesson calls "the whole of what was wrong with items".)
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void aRefinementLeavesHimOneAndItIsTheNewOne(GameTestHelper helper) {
        ServerPlayer child = child(helper);
        String name = "Gt Refine " + helper.getLevel().getGameTime();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> said = new AtomicReference<>();
        AtomicReference<Optional<CommandFunction<net.minecraft.commands.CommandSourceStack>>> before =
                new AtomicReference<>(Optional.empty());

        helper.startSequence()
                .thenExecute(() -> id.set(make(helper, newItem(name, "purple"), said)))
                .thenWaitUntil(() -> awaitGive(helper, id.get(), before.get()))
                .thenExecute(() -> {
                    handOverAsTold(helper, child, id.get(), said);
                    helper.assertValueEqual(stacksOf(child, id.get()), 1,
                            Component.literal("stacks of '" + id.get() + "' after the FIRST wish"));
                    before.set(giveFn(helper, id.get()));
                    JsonObject edit = new JsonObject();
                    edit.addProperty("start_from", id.get());
                    edit.addProperty("colour", "green");
                    edit.addProperty("name", name + " Mega");
                    String again = make(helper, edit, said);
                    helper.assertValueEqual(again, id.get(),
                            Component.literal("a refinement minted a different id, so it is a second item by "
                                    + "construction"));
                })
                .thenWaitUntil(() -> awaitGive(helper, id.get(), before.get()))
                .thenExecute(() -> {
                    handOverAsTold(helper, child, id.get(), said);
                    int held = stacksOf(child, id.get());
                    helper.assertTrue(held == 1,
                            Component.literal("he asked to CHANGE his item and is now holding " + held + " of it: "
                                    + describe(child, id.get()) + ". The store says one design; "
                                    + "his bag says otherwise, and his bag is what he sees."));
                    ItemStack only = firstOf(child, id.get());
                    helper.assertTrue(only.getHoverName().getString().endsWith("Mega"),
                            Component.literal("the one he kept is the OLD one: " + only.getHoverName().getString()));
                })
                .thenSucceed();
    }

    /**
     * <b>"and one that…" leaves him TWO, and the first is exactly as it was.</b>
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void andOneThatLeavesHimTwoAndTheFirstUntouched(GameTestHelper helper) {
        ServerPlayer child = child(helper);
        String name = "Gt Pearl " + helper.getLevel().getGameTime();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> said = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        AtomicReference<ItemStack> original = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> first.set(make(helper, newItem(name, "purple"), said)))
                .thenWaitUntil(() -> awaitGive(helper, first.get(), Optional.empty()))
                .thenExecute(() -> {
                    handOverAsTold(helper, child, first.get(), said);
                    original.set(firstOf(child, first.get()).copy());
                    JsonObject copy = new JsonObject();
                    copy.addProperty("start_from", first.get());
                    copy.addProperty("as_new", true);
                    copy.addProperty("name", name + " Lava");
                    copy.addProperty("colour", "red");
                    second.set(make(helper, copy, said));
                    helper.assertTrue(!second.get().equals(first.get()),
                            Component.literal("'and one that…' wrote under the ORIGINAL's id: " + second.get()));
                })
                .thenWaitUntil(() -> awaitGive(helper, second.get(), Optional.empty()))
                .thenExecute(() -> {
                    handOverAsTold(helper, child, second.get(), said);
                    int a = stacksOf(child, first.get());
                    int b = stacksOf(child, second.get());
                    helper.assertTrue(a == 1 && b == 1,
                            Component.literal("he asked for ANOTHER one and holds " + a + " of the first and " + b
                                    + " of the second: " + describe(child, null)));
                    ItemStack still = firstOf(child, first.get());
                    helper.assertTrue(ItemStack.isSameItemSameComponents(still, original.get()),
                            Component.literal("his FIRST item changed when he asked for a second one — "
                                    + original.get().getComponents() + " -> "
                                    + still.getComponents()));
                })
                .thenSucceed();
    }

    /**
     * <b>A thing he did not make here is changed where he holds it, and a SECOND refinement finds
     * the first.</b> ("recorded so the next refinement finds it" — the stack is its own
     * record, so the proof is that two edits in a row land cumulatively on one sword.)
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aForeignItemIsChangedInHisHandTwiceAndStaysOne(GameTestHelper helper) {
        ServerPlayer child = child(helper);
        child.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        ItemStack held = child.getInventory().getItem(0);

        JsonObject unbreakable = new JsonObject();
        unbreakable.addProperty("start_from", "minecraft:diamond_sword");
        JsonObject props = new JsonObject();
        props.addProperty("unbreakable", 1);
        unbreakable.add("properties", props);
        ToolResult r1 = new MakeItemTool().call(unbreakable);
        helper.assertTrue(!r1.isError(), Component.literal("'make my diamond sword unbreakable' failed: " + r1.text()));

        JsonObject rename = new JsonObject();
        rename.addProperty("start_from", "minecraft:diamond_sword");
        rename.addProperty("name", "Bonk");
        ToolResult r2 = new MakeItemTool().call(rename);
        helper.assertTrue(!r2.isError(), Component.literal("the SECOND refinement failed: " + r2.text()));

        helper.assertValueEqual(count(child, Items.DIAMOND_SWORD), 1, Component.literal("diamond swords he holds"));
        helper.assertTrue(child.getInventory().getItem(0) == held,
                Component.literal("the sword in his hand was replaced by a different stack — whatever was on the "
                        + "old one (enchantments, its wear) went with it"));
        helper.assertTrue(held.has(DataComponents.UNBREAKABLE),
                Component.literal("the second refinement undid the first: the name landed and 'unbreakable' is gone"));
        helper.assertTrue("Bonk".equals(held.getHoverName().getString()),
                Component.literal("the rename did not land: " + held.getHoverName().getString()));
        helper.succeed();
    }

    // ── the child and his bag ────────────────────────────────────────────────────────────────

    private static ServerPlayer child(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        return p;
    }

    /** Stacks carrying this made item's identity. Stacks, not items: "one sword" is one stack. */
    static int stacksOf(ServerPlayer p, String featureId) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && featureId.equals(WishItem.wishFeature(st))) {
                n++;
            }
        }
        return n;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) {
                n++;
            }
        }
        return n;
    }

    private static ItemStack firstOf(ServerPlayer p, String featureId) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && featureId.equals(WishItem.wishFeature(st))) {
                return st;
            }
        }
        throw new AssertionError("he holds no '" + featureId + "' at all: " + describe(p, null));
    }

    private static String describe(ServerPlayer p, String featureId) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || (featureId != null && !featureId.equals(WishItem.wishFeature(st)))) {
                continue;
            }
            sb.append(sb.length() > 1 ? ", " : "").append(st.getCount()).append("x '")
                    .append(st.getHoverName().getString()).append("' (")
                    .append(WishItem.wishFeature(st)).append(')');
        }
        return sb.append(']').toString();
    }

    // ── the agent's half, through the real tool and the real command ─────────────────────────

    private static JsonObject newItem(String name, String colour) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", colour);
        o.addProperty("shape", com.moddingfromamod.datapack.ItemCatalogue.PLAIN);
        o.addProperty("count", 1);
        return o;
    }

    /** The real tool; returns the id it made, exactly as the dispatch seam reads it. */
    private static String make(GameTestHelper helper, JsonObject args,
                               AtomicReference<String> said) {
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(args);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused: " + r.text()));
        helper.assertTrue(tool.lastMadeId() != null, Component.literal("make_item reported no id: " + r.text()));
        said.set(r.text());
        return tool.lastMadeId();
    }

    /**
     * Do what an agent that OBEYS the tool does: hand one over when {@code make_item} says "NOW
     * PUT IT IN HIS HAND", and not otherwise. The count afterwards is then a fact about the tool's
     * instruction as well as its action, which is the pair an agent actually meets.
     */
    private static void handOverAsTold(GameTestHelper helper, ServerPlayer child, String id,
                                       AtomicReference<String> said) {
        helper.assertTrue(said.get() != null, Component.literal("no make_item result to obey"));
        if (said.get().contains("NOW PUT IT IN HIS HAND")) {
            give(helper, child, id);
        }
    }

    private static Optional<CommandFunction<net.minecraft.commands.CommandSourceStack>> giveFn(
            GameTestHelper helper, String id) {
        return helper.getLevel().getServer().getFunctions()
                .get(Identifier.fromNamespaceAndPath(id, "give"));
    }

    /**
     * The reload THIS step caused has landed: none in flight, and the give function is a new
     * object. A reload makes new function instances, so "the same instance as before" means the
     * edit has not been read yet and handing it over now would hand over the OLD item.
     */
    private static void awaitGive(GameTestHelper helper, String id,
            Optional<CommandFunction<net.minecraft.commands.CommandSourceStack>> before) {
        helper.assertFalse(DatapackReloader.reloadInProgress(), Component.literal("reload still in flight"));
        var now = giveFn(helper, id);
        helper.assertTrue(now.isPresent(), Component.literal("no " + id + ":give yet"));
        helper.assertTrue(before.isEmpty() || now.get() != before.get(),
                Component.literal("the edit has not been reloaded yet"));
    }

    /**
     * Exactly what the tool tells the agent to run, as the child — at the permission the agent's
     * {@code run_command} really runs it at: the executor's 2. Since the replay commands are
     * op-only, because they are the stored form of a button and not something a child types; a
     * child's own permission 0 would get "unknown command" here, which is the point.
     */
    private static void give(GameTestHelper helper, ServerPlayer child, String id) {
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                child.createCommandSourceStack().withSuppressedOutput().withPermission(2),
                "wishdatapack " + id + " give");
    }
}
