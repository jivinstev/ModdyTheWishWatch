package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: one wish leaves ONE of a thing</b>, driven through the real tools inside a bound wish —
 * the rule itself is {@code NoDuplicateTest}; this proves the tools use it.
 *
 * <ul>
 *   <li>the volcano: a second {@code build_structure} from nothing in one wish is refused and saves
 *       nothing, the same id replaces the first, and {@code as_new} makes a second on purpose;</li>
 *   <li>the grappling hook: a refused {@code make_item start_from} says so, a make_item from
 *       nothing afterwards mints nothing, and {@code as_new} does.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class NoDuplicateGameTest {

    private NoDuplicateGameTest() {
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String building(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"A Volcano\",\"build\":false,\"note\":\"gt\",\"shapes\":["
                + "{\"kind\":\"pyramid\",\"name\":\"cone\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":9,"
                + "\"height\":6,\"depth\":9,\"block\":\"minecraft:basalt\"}]}";
    }

    /**
     * build_structure hops onto the server thread and refuses to be called FROM it, so it runs
     * where the real agent's calls come from — another thread, bound to the wish's worker.
     */
    private static AtomicReference<ToolResult> buildAsync(String worker, String args) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(AgentContext.with(worker, () -> new BuildStructureTool().call(json(args))));
            } catch (RuntimeException e) {
                out.set(ToolResult.error("threw: " + e));
            }
        }, "no-duplicate-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    @GameTest(template = "empty_test", batch = "noDuplicateBuild", timeoutTicks = 600)
    public static void aSecondBuildInOneWishLeavesOneBuilding(GameTestHelper helper) {
        String tag = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String first = "gt1353_volcano_" + tag;
        String second = "gt1353_ocean_volcano_" + tag;
        String other = "gt1353_tower_" + tag;
        UUID wish = UUID.randomUUID();
        String worker = "gt-1353-build-" + wish;
        Marshal.global().submit(wish, UUID.randomUUID(), "a volcano");
        Marshal.global().assign(worker, wish);
        AtomicReference<ToolResult> made = buildAsync(worker, building(first));
        AtomicReference<AtomicReference<ToolResult>> rebuild = new AtomicReference<>();
        AtomicReference<AtomicReference<ToolResult>> sameId = new AtomicReference<>();
        AtomicReference<AtomicReference<ToolResult>> asNew = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null, Component.literal("waiting for the first volcano"));
            helper.assertTrue(!made.get().isError(), Component.literal("the first build was refused: " + made.get().text()));
            if (rebuild.get() == null) {
                rebuild.set(buildAsync(worker, building(second)));
            }
            ToolResult r = rebuild.get().get();
            helper.assertTrue(r != null, Component.literal("waiting for the rebuild under a new id"));
            helper.assertTrue(r.isError() && r.text().contains("id '" + first + "'"),
                    Component.literal("a second building from nothing in one wish must be refused, naming the first: "
                            + r.text()));
            helper.assertTrue(BuildRegistry.get(second) == null,
                    Component.literal("the refused rebuild was SAVED anyway — he has two volcanoes again"));
            if (sameId.get() == null) {
                sameId.set(buildAsync(worker, building(first).replace("minecraft:basalt", "minecraft:magma_block")));
            }
            ToolResult same = sameId.get().get();
            helper.assertTrue(same != null, Component.literal("waiting for the redo under the same id"));
            helper.assertTrue(!same.isError(), Component.literal("the redo under the same id was refused: " + same.text()));
            helper.assertTrue(BuildRegistry.get(first).shapes().get(0).block().contains("magma"),
                    Component.literal("the same id must REPLACE the first volcano"));
            if (asNew.get() == null) {
                JsonObject o = json(building(other));
                o.addProperty("as_new", true);
                asNew.set(buildAsync(worker, o.toString()));
            }
            ToolResult again = asNew.get().get();
            helper.assertTrue(again != null, Component.literal("waiting for the deliberate second building"));
            helper.assertTrue(!again.isError() && BuildRegistry.get(other) != null,
                    Component.literal("as_new must make a second building on purpose: " + again.text()));
            Marshal.global().finish(wish);
        });
    }

    /** Every saved item design's id — the store is shared, so a COUNT reads other tests' items too. */
    private static java.util.Set<String> ids() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (var spec : ItemSpecStore.all()) {
            out.add(spec.id());
        }
        return out;
    }

    @GameTest(template = "empty_test", batch = "noDuplicateItem", timeoutTicks = 200)
    public static void aRefusedEditMintsNothingNewAndAsNewDoes(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        UUID wish = UUID.randomUUID();
        String worker = "gt-1353-item-" + wish;
        Marshal.global().submit(wish, p.getUUID(), "a grappling hook");
        Marshal.global().assign(worker, wish);
        String name = "Hook " + Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        MakeItemTool tool = new MakeItemTool();
        String hook = null;
        String second = null;
        try {
            JsonObject make = new JsonObject();
            make.addProperty("name", name);
            make.addProperty("icon", "wand");
            make.addProperty("colour", "#808080");
            make.addProperty("shape", "plain");
            make.addProperty("count", 1);
            ToolResult made = AgentContext.with(worker, () -> tool.call(make));
            helper.assertTrue(!made.isError(), Component.literal("the hook was refused: " + made.text()));
            hook = tool.lastMadeId();

            // A change the item cannot take — refused, after its base resolved, like the real one.
            JsonObject edit = json("{\"start_from\":\"" + hook + "\",\"block\":{\"when\":\"step\"}}");
            ToolResult refused = AgentContext.with(worker, () -> tool.call(edit));
            helper.assertTrue(refused.isError() && refused.text().contains("EDIT REFUSED")
                            && refused.text().contains("Do NOT make a new item"),
                    Component.literal("a refused edit must say it was refused and not to make a new one: " + refused.text()));

            java.util.Set<String> before = ids();
            JsonObject fresh = make.deepCopy();
            fresh.addProperty("name", name + " Grapple");
            ToolResult blocked = AgentContext.with(worker, () -> tool.call(fresh));
            helper.assertTrue(blocked.isError() && blocked.text().contains("'" + hook + "'")
                            && blocked.text().contains("as_new: true"),
                    Component.literal("a make_item from nothing after a refused edit must be refused: " + blocked.text()));
            java.util.Set<String> added = ids();
            added.removeAll(before);
            helper.assertTrue(added.stream().noneMatch(i -> i.contains("grapple")),
                    Component.literal("the refused fresh item was SAVED anyway — he has two hooks again: " + added));

            fresh.addProperty("as_new", true);
            ToolResult deliberate = AgentContext.with(worker, () -> tool.call(fresh));
            helper.assertTrue(!deliberate.isError(), Component.literal("as_new must make the second item: " + deliberate.text()));
            second = tool.lastMadeId();
            helper.assertTrue(second != null && !second.equals(hook) && !before.contains(second)
                            && ItemSpecStore.get(second) != null && ItemSpecStore.get(hook) != null,
                    Component.literal("as_new must mint a NEW item under its own id beside the first, had " + second));
        } finally {
            for (String id : new String[] {hook, second}) {
                if (id != null) {
                    com.moddingfromamod.item.WishItemCatalogue.forget("id:" + id);
                    ItemSpecStore.remove(id);
                }
            }
            Marshal.global().finish(wish);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }
}
