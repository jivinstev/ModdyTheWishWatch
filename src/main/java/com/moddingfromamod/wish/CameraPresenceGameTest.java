package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The camera is not a person in the child's world</b>, asserted on the wire.
 *
 * <p>Found in a photograph rather than by a gate, which is the point: every existing check asks
 * whether the camera JOINED, and a camera standing in shot with a name tag is the most emphatic
 * possible yes.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CameraPresenceGameTest {

    private static final String BATCH = "camerapresence";

    private static final List<UUID> REGISTERED = new ArrayList<>();

    private static List<Object> drain(GameTestHelper helper, ServerPlayer p) {
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("the instrument cannot see the wire: the mock's channel is a "
                        + (channel == null ? "null" : channel.getClass().getName())));
        var embedded = (io.netty.channel.embedded.EmbeddedChannel) channel;
        List<Object> out = new ArrayList<>();
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            out.add(o);
        }
        return out;
    }

    private static boolean toldToForget(List<Object> packets, int id) {
        for (Object o : packets) {
            if (o instanceof ClientboundRemoveEntitiesPacket rm && rm.getEntityIds().contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** The shipped sweep, not a copy of it. The listener that calls it is held by a source rule. */
    private static void tick(GameTestHelper helper) {
        CameraPresence.sweep(helper.getLevel().getServer());
    }

    private static void isolated(GameTestHelper helper, Runnable body) {
        MockPlayerNetwork.clearStaleMocks(helper);
        REGISTERED.clear();
        try {
            body.run();
        } finally {
            for (UUID id : REGISTERED) {
                PlayerKind.forgetServiceClient(id);
            }
            REGISTERED.clear();
        }
    }

    private static ServerPlayer camera(GameTestHelper helper) {
        ServerPlayer cam = MockPlayerNetwork.inLevel(helper);
        PlayerKind.registerServiceClient(cam.getUUID());
        REGISTERED.add(cam.getUUID());
        return cam;
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public void theChildIsNeverShownTheCamera(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            try {
                drain(helper, child);
                tick(helper);
                helper.assertTrue(toldToForget(drain(helper, child), cam.getId()),
                        Component.literal("the child was left able to see the camera (entity " + cam.getId()
                                + "). It is a real player with a body and a floating name tag, "
                                + "and it stands where the creature is being built"));
            } finally {
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public void oneCameraStillSeesAnother(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer a = camera(helper);
            ServerPlayer b = camera(helper);
            try {
                drain(helper, a);
                tick(helper);
                helper.assertTrue(!toldToForget(drain(helper, a), b.getId()),
                        Component.literal("one camera was hidden from another. #510 puts several in a world and a "
                                + "queue you cannot see from inside is a queue you cannot debug"));
            } finally {
                MockPlayerNetwork.remove(a);
                MockPlayerNetwork.remove(b);
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public void theCameraStillSeesTheChild(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer cam = camera(helper);
            ServerPlayer child = MockPlayerNetwork.inLevel(helper);
            try {
                drain(helper, cam);
                tick(helper);
                helper.assertTrue(!toldToForget(drain(helper, cam), child.getId()),
                        Component.literal("the camera was told to forget the child. He is in the establishing shot, "
                                + "and #503's subject search reads the world the camera can see"));
            } finally {
                MockPlayerNetwork.remove(child);
                MockPlayerNetwork.remove(cam);
            }
        });
        helper.succeed();
    }

    /** Two children in one world are not a crowd to be hidden from each other. */
    @GameTest(template = "empty_test", batch = BATCH)
    public void twoChildrenStillSeeEachOther(GameTestHelper helper) {
        isolated(helper, () -> {
            ServerPlayer one = MockPlayerNetwork.inLevel(helper);
            ServerPlayer two = MockPlayerNetwork.inLevel(helper);
            try {
                drain(helper, one);
                tick(helper);
                helper.assertTrue(!toldToForget(drain(helper, one), two.getId()),
                        Component.literal("a second child was hidden from the first. This rule is about the mod's "
                                + "own clients, never about who else is playing"));
            } finally {
                MockPlayerNetwork.remove(one);
                MockPlayerNetwork.remove(two);
            }
        });
        helper.succeed();
    }
}
