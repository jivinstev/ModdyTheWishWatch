package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.PaintSurfaceTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.reference.MinecraftWikiPerson;
import com.moddingfromamod.reference.PublicFigure;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>an earlier issue's mechanism: {@code paint_surface from_skin} hangs a public figure's skin, square,
 * and a replay never fetches it again.</b>
 *
 * <p>Through the REAL tool, for a real (mock) player on his own agent thread — the shape of
 * {@link PictureForkGameTest}. The two outside services are replaced, because a gate must not
 * depend on Wikipedia's rate limits or Mojang's uptime: the PUBLIC check answers yes only for
 * {@code Smajor} and {@code Stampy}, only Smajor is LINKED to an account, and the skin
 * is a 64×64 built here. What is proven is everything on our side
 * of those two calls: the gate is asked before any fetch, the board is forced to the view's shape,
 * the maps go up, the recipe is on the shelf, and {@code /wishpaint} hangs it again with the skin
 * service throwing.
 *
 * <p>Its own batch: {@link PublicFigure} and {@link SkinFetch} seams are statics, and GameTests in
 * one batch run concurrently and share every static (CLAUDE.md).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SkinPictureGameTest {

    private static final String BATCH = "skinPicture";

    private SkinPictureGameTest() {
    }

    /** A recognisable 64×64 skin: a red cap, a pale face, blue body, grey limbs. */
    static byte[] fixtureSkin() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int c = 0xFF808080;
                if (x >= 32 && y < 16) {
                    c = y < 10 ? 0xFFD02020 : 0x00000000;   // the hat: a red cap, holes below
                } else if (y >= 32 && y < 48 || y >= 48 && (x < 16 || x >= 48)) {
                    c = 0x00000000;                          // empty second layers
                } else if (y < 16) {
                    c = 0xFFE8C0A0;                          // the head
                } else if (x >= 16 && x < 40 && y < 32) {
                    c = 0xFF2040C0;                          // the body
                }
                img.setRGB(x, y, c);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject args(String id, String who) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("note", "gametest");
        o.addProperty("from_skin", who);
        o.addProperty("skin_view", "front");
        o.addProperty("wide", 2);
        o.addProperty("tall", 2);       // asked for a square: a FRONT view must override it to 1:2
        return o;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void aPublicFiguresSkinHangsSquareAndReplaysWithoutAFetch(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos stand = helper.absolutePos(new BlockPos(2, 2, 2));
        p.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 0f);
        byte[] png = fixtureSkin();
        AtomicInteger fetches = new AtomicInteger();
        // "Smajor" is LINKED to Smajor1995 by his page; "Stampy" is public (an article)
        // with no linked Java account; "MyBrotherJake" is nobody. Only the linked one may reach
        // Mojang, and only as the linked username.
        PublicFigure.useOracle(n -> n.equals("Smajor") || n.equals("Stampy")
                ? PublicFigure.Verdict.PUBLIC : PublicFigure.Verdict.NO_ARTICLE);
        MinecraftWikiPerson.useOracle(n -> n.equals("Smajor")
                ? new MinecraftWikiPerson.Linked(n, "Smajor", 3774932L, "minecraft_name",
                        "Smajor1995")
                : new MinecraftWikiPerson.Unmapped(n, MinecraftWikiPerson.Why.NO_JAVA_NAME, n));
        // The generated creator list is pinned EMPTY here, so what the shipped table happens to
        // map this month cannot change this test's world.
        com.moddingfromamod.reference.CreatorAccounts.useRows(java.util.List.of());
        java.util.List<String> asked = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        SkinFetch.useSource(n -> {
            fetches.incrementAndGet();
            asked.add(n);
            return new SkinFetch.Found(new SkinFetch.Skin(n,
                    UUID.fromString("55a2e72e-0161-4c85-b191-a0b227ff758a"),
                    "https://textures.minecraft.net/texture/0123456789abcdef", "0123456789abcdef",
                    false, png));
        });
        String id = "gt_skin_" + helper.getLevel().getGameTime();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> privateAnswer = new AtomicReference<>();
        AtomicReference<String> unlinkedAnswer = new AtomicReference<>();

        UUID wish = UUID.randomUUID();
        String worker = "gt-skin-" + wish;
        Marshal.global().submit(wish, p.getUUID(), "gt skin picture");
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                // A name with no article: refused, and Mojang is never asked.
                ToolResult refused = new PaintSurfaceTool().call(args(id + "_x", "MyBrotherJake"));
                privateAnswer.set((refused.isError() ? "ERROR " : "") + refused.text()
                        + " [fetches=" + fetches.get() + "]");
                // A PUBLIC name with no linked account: refused, and Mojang is never asked —
                // the account called "Stampy" is a stranger's.
                ToolResult unlinked = new PaintSurfaceTool().call(args(id + "_u", "Stampy"));
                unlinkedAnswer.set((unlinked.isError() ? "ERROR " : "") + unlinked.text()
                        + " [fetches=" + fetches.get() + "]");
                ToolResult r = new PaintSurfaceTool().call(args(id, "Smajor"));
                done.set(r.isError() ? "failed: " + r.text() : "yes");
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-skin");
        t.setDaemon(true);
        t.start();

        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done.get() != null, Component.literal("waiting for the wish"));
            try {
                helper.assertTrue(privateAnswer.get().startsWith("ERROR ")
                                && privateAnswer.get().contains("not a public figure")
                                && privateAnswer.get().endsWith("[fetches=0]"),
                        Component.literal("a name with no Wikipedia article reached the skin service: "
                                + privateAnswer.get()));
                helper.assertTrue(unlinkedAnswer.get().startsWith("ERROR ")
                                && unlinkedAnswer.get().contains("No outside source links")
                                && unlinkedAnswer.get().endsWith("[fetches=0]"),
                        Component.literal("a public name with no linked account reached the skin service: "
                                + unlinkedAnswer.get()));
                helper.assertTrue("yes".equals(done.get()), Component.literal(done.get()));
                helper.assertTrue(fetches.get() == 1, Component.literal("fetched " + fetches.get() + " times"));
                helper.assertTrue(asked.equals(java.util.List.of("Smajor1995")),
                        Component.literal("Mojang must be asked for the LINKED account only, never the public "
                                + "name: " + asked));
                PictureStore.Picture shelf = PictureStore.get(id);
                helper.assertTrue(shelf != null, Component.literal("the skin picture is not on his shelf"));
                helper.assertTrue(shelf.wide() == 2 && shelf.tall() == 4, Component.literal("a front view must hang "
                        + "on a 1:2 board, whatever was asked; it hangs " + shelf.wide() + "x"
                        + shelf.tall()));
                PaintRecipe expected;
                try {
                    expected = SkinPicture.recipe(png, false, SkinPicture.View.FRONT);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                helper.assertValueEqual(shelf.recipe(), expected,
                        Component.literal("the shelf must hold the skin's recipe, so a replay needs no fetch"));
                PaintRecord rec = PaintRegistry.get(id);
                helper.assertTrue(rec != null && rec.hasVanillaCopy(), Component.literal("no map art went up"));
                long maps = helper.getLevel().getEntitiesOfClass(ItemFrame.class,
                        new AABB(new BlockPos(rec.x(), rec.y(), rec.z())).inflate(8),
                        f -> f.getItem().is(Items.FILLED_MAP)).size();
                helper.assertTrue(maps > 0, Component.literal("the skin picture left no maps on the wall"));

                // THE REPLAY: the skin service now throws, and /wishpaint must not care.
                SkinFetch.useSource(n -> {
                    throw new AssertionError("a replay fetched the skin again");
                });
                helper.assertTrue(com.moddingfromamod.wish.WishUndo.undoOne(
                        helper.getLevel().getServer(), "painting", id), Component.literal("undo reported nothing"));
                String err = PictureCommand.hang(p, id);
                helper.assertTrue(err == null, Component.literal("the skin picture did not hang again: " + err));
            } finally {
                PublicFigure.reset();
                MinecraftWikiPerson.reset();
                com.moddingfromamod.reference.CreatorAccounts.reset();
                SkinFetch.reset();
                MockPlayerNetwork.remove(p);
            }
        });
    }
}
