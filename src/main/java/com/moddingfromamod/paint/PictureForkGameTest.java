package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.PaintSurfaceTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>an earlier issue's gate: a picture can be refined, and another child's is forked, never painted over.</b>
 *
 * <p>Two children, each a real player standing in the plot, each acting on his own thread bound to
 * his own wish run, through the REAL {@code paint_surface} — the shape of {@code ChildForkGameTest}
 *. The claim is checked on the thing a child SEES: the map art's pixel bytes, hashed before
 * and after. A record saying "untouched" while the maps were recoloured is exactly the failure a
 * position-keyed map reuse can produce, so the record is not the evidence.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PictureForkGameTest {

    /**
     * <b>One batch PER TEST, deliberately.</b> Tests in one batch run concurrently, and each of
     * these starts with {@code MockPlayerNetwork.clearStaleMocks}, which removes EVERY mock in the
     * player list — including a sibling test's live children. Measured 2026-09-23 with all three in
     * one batch: <i>"bob failed: Nobody is in the world to hang the picture in front of"</i>, Bob
     * having been cleared by the creeper test starting.
     */
    private static final String BATCH = "pictureFork";
    private static final String BATCH_SHELF = "pictureForkShelf";
    private static final String BATCH_CREEPER = "pictureForkCreeper";

    private PictureForkGameTest() {
    }

    /** Run {@code body} as an agent granting a wish for {@code child}, off the server thread. */
    private static Thread as(UUID child, String label, Runnable body) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-picture-" + label + "-" + wish;
        Marshal.global().submit(wish, child, "gt picture " + label);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-picture-" + label);
        t.setDaemon(true);
        return t;
    }

    private static JsonObject paint(String id, String startFrom, String rects) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("note", "gametest");
        o.addProperty("wide", 2);
        o.addProperty("tall", 2);
        if (startFrom != null) {
            o.addProperty("start_from", startFrom);
        }
        if (rects != null) {
            o.addProperty("picture", "{\"size\":64,\"rects\":[" + rects + "]}");
        }
        return o;
    }

    private static final String SUN = "{\"x\":16,\"y\":16,\"w\":32,\"h\":32,\"colour\":\"yellow\"}";
    private static final String SKY = "{\"x\":0,\"y\":0,\"w\":64,\"h\":12,\"colour\":\"blue\"}";
    private static final String HAT = "{\"x\":20,\"y\":4,\"w\":24,\"h\":10,\"colour\":\"black\"}";

    /**
     * <b>The picture's map art, frame by frame: position → hash of (map id, pixels).</b>
     *
     * <p>{@code onlyAt} null takes every filled map within 6 blocks of the record, which is only
     * right while this picture is the only one there. Pass the positions from that first snapshot
     * to read THE SAME frames again later. The first version hashed everything in the box both
     * times, and Bob's own fork, hung facing the other way from the same spot, lands inside it:
     * <i>"Alice's map art CHANGED after Bob's wish"</i> with her maps untouched (2026-09-23, 2 of 2
     * runs). A real overpaint still shows: her frames' ids or pixels change at her positions.
     */
    private static java.util.Map<Long, Integer> maps(ServerLevel level, PaintRecord r,
            java.util.Set<Long> onlyAt) {
        java.util.Map<Long, Integer> out = new java.util.TreeMap<>();
        if (r == null || !r.hasVanillaCopy()) {
            return out;
        }
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class,
                new AABB(new BlockPos(r.x(), r.y(), r.z())).inflate(6),
                f -> onlyAt == null ? f.getItem().is(Items.FILLED_MAP)
                        : onlyAt.contains(f.blockPosition().asLong()));
        for (ItemFrame f : frames) {
            MapId id = f.getItem().get(net.minecraft.core.component.DataComponents.MAP_ID);
            MapItemSavedData d = id == null ? null : level.getMapData(id);
            out.put(f.blockPosition().asLong(),
                    31 * (id == null ? 0 : id.id()) + (d == null ? 0 : Arrays.hashCode(d.colors)));
        }
        return out;
    }

    private static ServerPlayer child(GameTestHelper helper, BlockPos at, float yaw) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos abs = helper.absolutePos(at);
        p.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, yaw, 0f);
        return p;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void aRefiningBobsCopyLeavesAlicesPictureByteIdentical(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer alice = child(helper, new BlockPos(1, 2, 1), 0f);      // faces south
        ServerPlayer bob = child(helper, new BlockPos(1, 2, 1), 180f);      // faces north
        String id = "gt_sun_" + helper.getLevel().getGameTime();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<java.util.Map<Long, Integer>> before = new AtomicReference<>();
        AtomicReference<String> bobSaid = new AtomicReference<>();
        ServerLevel level = helper.getLevel();

        Thread b = as(bob.getUUID(), "bob", () -> {
            ToolResult r = new PaintSurfaceTool().call(paint(id, id, HAT));
            bobSaid.set((r.isError() ? "ERROR " : "") + r.text());
            done.set(r.isError() ? "bob failed: " + r.text() : "yes");
        });
        Thread a = as(alice.getUUID(), "alice", () -> {
            ToolResult r = new PaintSurfaceTool().call(paint(id, null, SKY + "," + SUN));
            if (r.isError()) {
                done.set("alice failed: " + r.text());
                return;
            }
            // Her single refinement: start_from + one rect keeps every other rect, in place.
            ToolResult again = new PaintSurfaceTool().call(paint(id, id, HAT));
            if (again.isError()) {
                done.set("alice's refinement failed: " + again.text());
                return;
            }
            // Hashed ON the server thread, from here, so it is certainly taken before Bob starts.
            before.set(com.moddingfromamod.agent.AgentBridge.onServer(
                    () -> maps(level, PaintRegistry.get(id), null)));
            b.start();
        });
        a.start();

        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for both children"));
            try {
                helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
                PaintRecord hers = PaintRegistry.get(id);
                helper.assertTrue(hers != null && hers.hasVanillaCopy(), Component.literal("Alice's picture is gone"));
                helper.assertTrue(alice.getUUID().toString().equals(hers.ownerId()),
                        Component.literal("Alice's picture is no longer hers: " + hers.ownerId()));
                helper.assertTrue(PictureStore.get(id).recipe().rects().size() == 3,
                        Component.literal("Alice's start_from kept her two shapes and added the hat: "
                                + PictureStore.get(id).recipe().rects().size()));
                String hisId = com.moddingfromamod.marshal.ArtifactOwner.forkKey(id,
                        bob.getUUID().toString());
                PaintRecord his = PaintRegistry.get(hisId);
                helper.assertTrue(his != null && his.hasVanillaCopy(),
                        Component.literal("Bob has no picture of his own. He was told: " + bobSaid.get()));
                helper.assertTrue(his.x() != hers.x() || his.z() != hers.z(),
                        Component.literal("Bob's copy hangs where Alice's does"));
                helper.assertTrue(PictureStore.get(hisId).recipe().rects().size() == 4,
                        Component.literal("Bob's copy is Alice's picture WITH his hat, not a lone hat"));
                helper.assertTrue(before.get() != null && !before.get().isEmpty(),
                        Component.literal("no map art was found over Alice's picture, so this asserts nothing"));
                helper.assertValueEqual(maps(level, hers, before.get().keySet()), before.get(),
                        Component.literal("Alice's map art CHANGED after Bob's wish — her picture was painted over"));
            } finally {
                if (done.get() != null) {
                    MockPlayerNetwork.remove(alice);
                    MockPlayerNetwork.remove(bob);
                }
            }
        });
    }

    /**
     * item 5: a picture on his SHELF that has never hung in this world is hung fresh at his
     * focus by start_from with no coordinates — never a no-op "that picture is not here".
     */
    @GameTest(template = "empty_test", batch = BATCH_SHELF, timeoutTicks = 3000)
    public static void aShelfPictureWithNoPlacementHereIsHungFresh(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer kid = child(helper, new BlockPos(1, 2, 1), 0f);
        String id = "gt_shelf_" + helper.getLevel().getGameTime();
        PictureStore.put(new PictureStore.Picture(id, kid.getUUID().toString(),
                PaintRecipe.parse(com.google.gson.JsonParser.parseString(
                        "{\"size\":64,\"rects\":[" + SUN + "]}").getAsJsonObject()), 2, 2));
        AtomicReference<String> done = new AtomicReference<>();
        as(kid.getUUID(), "shelf", () -> {
            ToolResult r = new PaintSurfaceTool().call(paint(id, id, null));
            done.set(r.isError() ? "failed: " + r.text() : "yes");
        }).start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting"));
            try {
                helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
                PaintRecord r = PaintRegistry.get(id);
                helper.assertTrue(r != null && r.hasVanillaCopy(),
                        Component.literal("a shelf picture was not hung in a world it had never been in"));
            } finally {
                MockPlayerNetwork.remove(kid);
            }
        });
    }

    /**
     * an earlier issue's second half: the STOCK creeper face is a base like any picture. Hung by the starter
     * button, then refined by a child, it gives him his own copy on a new board, and the starter's
     * own map art is untouched.
     */
    @GameTest(template = "empty_test", batch = BATCH_CREEPER, timeoutTicks = 3000)
    public static void theStarterCreeperFaceForksWhenAChildChangesIt(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer kid = child(helper, new BlockPos(1, 2, 1), 0f);
        ServerLevel level = helper.getLevel();
        String err = PictureCommand.hang(kid, "starter_creeper_face");
        helper.assertTrue(err == null, Component.literal("the starter button did not hang the picture: " + err));
        java.util.Map<Long, Integer> before =
                maps(level, PaintRegistry.get("starter_creeper_face"), null);
        kid.setYRot(180f);   // turn round, so his copy is aimed somewhere else
        AtomicReference<String> done = new AtomicReference<>();
        as(kid.getUUID(), "creeper", () -> {
            ToolResult r = new PaintSurfaceTool().call(paint("starter_creeper_face",
                    "starter_creeper_face", HAT));
            done.set(r.isError() ? "failed: " + r.text() : "yes");
        }).start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting"));
            try {
                helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
                String his = com.moddingfromamod.marshal.ArtifactOwner.forkKey(
                        "starter_creeper_face", kid.getUUID().toString());
                PaintRecord r = PaintRegistry.get(his);
                helper.assertTrue(r != null && r.hasVanillaCopy(),
                        Component.literal("he has no creeper face of his own after changing the starter one"));
                helper.assertTrue(PictureStore.get(his).recipe().rects().size() == 65,
                        Component.literal("his copy is the whole creeper face WITH the hat"));
                helper.assertTrue(!before.isEmpty(),
                        Component.literal("no map art was found over the starter picture, so this asserts nothing"));
                helper.assertValueEqual(maps(level, PaintRegistry.get("starter_creeper_face"),
                                before.keySet()), before,
                        Component.literal("the starter picture's map art CHANGED — the stock wish was painted over"));
            } finally {
                MockPlayerNetwork.remove(kid);
            }
        });
    }
}
