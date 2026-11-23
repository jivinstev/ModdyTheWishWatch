package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.WishIcons;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> on a live server: the item pack is really published, and a client with no mod is really
 * told where it is.</b>
 *
 * <p>{@code StockClientIconTest} proves the BYTES carry every icon. It cannot prove anybody ever
 * builds them, hosts them or offers them — and that is precisely the half that was missing for the
 * whole life of the vanilla-lite path: {@code WishPackWriter} worked perfectly and was reachable
 * only from a probe and a {@code Dist.CLIENT} spike, so a stock child got a leather horse armour
 * with every gate green. <i>"There is no check" and "the check is not wired to this path" look
 * identical from outside.</i>
 *
 * <p>So everything here is read off a real dedicated server and off a real client's own
 * {@code EmbeddedChannel} rather than from a counter the service keeps about itself. The first test
 * asserts nothing this class did — it asks whether {@code ServerStartedEvent} fired the publish on
 * the server that is running the test, which no grep can answer.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ItemPackGameTest {

    /** Its own batch: the mock players are process-wide, same reason as {@code rigveil}. */
    public static final String BATCH = "itempack";

    /** The carrier's item definition — the one binding table the pack writes (step 3). */
    private static final String CARRIER_DEF = com.moddingfromamod.pack.VanillaItemPack
            .itemDefinitionPath(com.moddingfromamod.pack.WishPackWriter.BASE_ITEM);

    public static List<Object> drain(GameTestHelper helper, ServerPlayer p) {
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("the instrument cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())
                        + ". A capture that silently records nothing reports a perfect control"));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        List<Object> out = new ArrayList<>();
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            out.add(o);
        }
        return out;
    }

    public static List<ClientboundResourcePackPushPacket> pushes(List<Object> packets) {
        List<ClientboundResourcePackPushPacket> out = new ArrayList<>();
        for (Object o : packets) {
            if (o instanceof ClientboundResourcePackPushPacket push) {
                out.add(push);
            }
        }
        return out;
    }

    public static String[] published(GameTestHelper helper) {
        String sha1 = ItemPackService.currentSha1();
        helper.assertTrue(sha1 != null,
                Component.literal("this server published no item pack, so every wished thing and Moddy himself "
                        + "render as a leather horse armour for any client without the mod. Either "
                        + "ItemPackService is off the event bus, or ServerStartedEvent's publish "
                        + "threw -- the log says which (#519)"));
        return new String[] { sha1 };
    }

    // ── it exists ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>The running server has an item pack.</b> Not "the writer works" — that is a unit test —
     * but "something called it, on this server, without anybody asking".
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theServerPublishesAnItemPackWithoutBeingAsked(GameTestHelper helper) {
        helper.assertTrue(VanillaCarrier.enabled(),
                Component.literal("the carrier route is off in this run, so the publish is correctly skipped and "
                        + "this test measured nothing. It is on by default; something forced it"));
        published(helper);
        helper.succeed();
    }

    /**
     * <b>And the bytes at the other end of the URL are the ones a child needs.</b>
     *
     * <p>Fetched over HTTP through the real host, so this covers the whole delivery a stock client
     * makes bar the rendering: publish, zip, hash, serve, and the binding table inside. A pack
     * that is published and unreachable is the same to a child as no pack at all.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theUrlReallyServesThePackWithEveryIcon(GameTestHelper helper) {
        published(helper);
        try {
            String url = com.moddingfromamod.vanilla.PackDelivery.url(
                    PackHostConfig.LOOPBACK, PackHost.port(), ItemPackService.currentSha1());
            byte[] zip;
            try (var in = URI.create(url).toURL().openStream()) {
                zip = in.readAllBytes();
            }
            String carrier = null;
            List<String> withOverrides = new ArrayList<>();
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (!e.getName().endsWith(".json") && !e.getName().endsWith(".mcmeta")) {
                        continue;
                    }
                    String text = new String(zin.readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (CARRIER_DEF.equals(e.getName())) {
                        carrier = text;
                    }
                    if (text.contains("\"overrides\"")) {
                        withOverrides.add(e.getName());
                    }
                }
            }
            helper.assertTrue(carrier != null,
                    Component.literal("the served pack (" + zip.length + " bytes) has no " + CARRIER_DEF
                            + ", so a client that fetched and applied it still renders every "
                            + "wished thing as a plain leather horse armour"));
            helper.assertTrue(withOverrides.isEmpty(),
                    Component.literal("#1585 step 3: the served pack carries a pre-1.21.4 overrides table again, in "
                            + withOverrides + " -- no admitted client reads one"));
            // Moddy specifically, by number: he is the one icon the wish library must never be
            // able to reach, and the one whose absence closes the zero-typing door entirely.
            helper.assertTrue(carrier.contains(
                            "\"threshold\": " + VanillaCarrier.WATCH_MODEL_DATA + ","),
                    Component.literal("the served pack has no range_dispatch entry at " + VanillaCarrier.WATCH_MODEL_DATA
                            + " -- Moddy, i.e. the button inside the wish panel"));
            int icons = 0;
            for (WishIcons.Icon icon : WishIcons.all()) {
                if (carrier.contains("\"threshold\": " + icon.id() + ",")) {
                    icons++;
                }
            }
            helper.assertValueEqual(icons, WishIcons.all().size(), Component.literal("wish icons in the served pack"));
        } catch (Exception e) {
            helper.fail(Component.literal("the published item pack could not be fetched over its own URL: " + e));
        }
        helper.succeed();
    }

    // ── it is offered to the client that needs it, and not to the one that does not ─────────

    @GameTest(template = "empty_test", batch = BATCH)
    public void aClientWithNoModIsToldWhereThePackIs(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        published(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        MockPlayerNetwork.withoutChannels(child, () -> {
            drain(helper, child);
            boolean pushed = ItemPackService.offerTo(child,
                    "http://127.0.0.1/pack/" + ItemPackService.currentSha1() + ".zip",
                    ItemPackService.currentSha1());
            helper.assertTrue(pushed, Component.literal("offerTo refused a client with no mod"));
            List<ClientboundResourcePackPushPacket> seen = pushes(drain(helper, child));
            helper.assertValueEqual(seen.size(), 1, Component.literal("resource-pack pushes the stock child received"));
            helper.assertValueEqual(seen.get(0).hash(), ItemPackService.currentSha1(),
                    Component.literal("the hash the stock child was told to expect"));
            // REQUIRED, read off the packet the client would really receive rather than off the
            // constant that produced it -- a constant asserted against itself proves nothing about
            // what went down the wire. Without the artwork every wished thing and Moddy himself is
            // a leather horse armour, so a child who declined would be playing a different game in
            // silence.
            helper.assertTrue(seen.get(0).required(),
                    Component.literal("the item pack was pushed as OPTIONAL. See PackDelivery.REQUIRED -- the "
                            + "vanilla-compat path exists so a child who installs nothing gets the "
                            + "same game, and the icons are not a garnish on that"));
        });
        helper.succeed();
    }

    /**
     * D32: the integrated server's HOST is never pushed the item pack, even with
     * no client channel (the public mod has none): their game already draws the art from the jar,
     * and a push only shows them Minecraft's "requires a custom resource pack" prompt on their own
     * world. The stock child in {@link #aClientWithNoModIsToldWhereThePackIs} is the LAN guest's
     * half, and it is still pushed.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theIntegratedServersHostIsNotAskedForThePack(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        published(helper);
        ServerPlayer host = MockPlayerNetwork.inLevel(helper);
        helper.assertFalse(ItemPackService.isIntegratedHost(host),
                Component.literal("a mock on the GameTest server read as the integrated server's host "
                        + "before it was made one: isSingleplayerOwner answered true for a stranger"));
        ItemPackService.hostForTest(host.getUUID());
        try {
            MockPlayerNetwork.withoutChannels(host, () -> {
                drain(helper, host);
                boolean pushed = ItemPackService.offerTo(host,
                        "http://127.0.0.1/pack/" + ItemPackService.currentSha1() + ".zip",
                        ItemPackService.currentSha1());
                helper.assertFalse(pushed, Component.literal("the integrated server's host was pushed the item "
                        + "pack: they would be asked to accept a server resource pack on their own world (D32)"));
                helper.assertValueEqual(pushes(drain(helper, host)).size(), 0,
                        Component.literal("resource-pack pushes the host received"));
            });
        } finally {
            ItemPackService.hostForTest(null);
        }
        helper.succeed();
    }

    // A client WITH the mod is ItemPackModdedClientGameTest: only the private modded-client plug-in recognises one.

    /**
     * <b>The two packs are a STACK, not one slot.</b>
     *
     * <p>A client keys a server pack by the UUID in the push and pops the one that id names, so two
     * packs sharing an id would silently mean one artwork table — the item pack arriving would
     * replace the creature's models, or the reverse, depending on order. This is the property that
     * makes "item pack at JOIN, rig pack at SCULPT" possible at all.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public void theItemPackAndTheRigPackDoNotShareAnId(GameTestHelper helper) {
        String item = ItemPackService.currentSha1();
        published(helper);
        try {
            RigReveal.publish(helper.getLevel().getServer(), "gt_itempack_rig",
                    List.of(new com.moddingfromamod.runtime.CreatureSculpt.Part(
                            "body", null, new float[] { 0, 0, 0 },
                            new float[] { -4, 0, -4 }, new float[] { 4, 8, 4 }, 0x8844aa)), 1.0);
        } catch (Exception e) {
            helper.fail(Component.literal("publishing a rig pack failed, so this test compared nothing: " + e));
        }
        String rig = RigReveal.currentSha1();
        helper.assertTrue(rig != null, Component.literal("no rig pack was published, so there is nothing to compare"));
        helper.assertFalse(item.equals(rig),
                Component.literal("the item pack and the rig pack hashed the same, so they would carry the same "
                        + "resource-pack UUID and the second would REPLACE the first on the client"));
        helper.succeed();
    }
}
