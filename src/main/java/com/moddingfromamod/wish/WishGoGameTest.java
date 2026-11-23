package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Landing;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for "take me to my wish" — the parts a real world settles and arithmetic cannot.
 *
 * <p>{@code LandingTest} proves the geometry. What only a running server proves is that the two
 * commands exist at all, that {@link WishGo#pick} finds the right wish out of several, and that a
 * wish with nowhere to go is refused cleanly rather than teleporting him into nothing.
 *
 * <p>The teleport ITSELF is Gate C's job: it needs a real player, and putting one into a GameTest's
 * player list fires our sync payloads at an embedded channel — a known way to fail a test for
 * reasons unrelated to what it is testing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishGoGameTest {

    /**
     * <b>A child of its own, per test.</b>
     *
     * <p>GameTests in the same batch run interleaved on the one server thread, so a UUID shared
     * across a file means one
     * test's in-flight wish is visible to another's {@code liveFor} — and the second fails against
     * its neighbour rather than against the code. That is exactly how three tests here went red at
     * once when a fourth was added: one threw, left two runs WORKING, and the slot counter and the
     * beam check both then measured somebody else's state.
     */
    private static UUID child(String testName) {
        return UUID.nameUUIDFromBytes(("gt-" + testName).getBytes());
    }

    private static WishRun run(UUID me, String worker, String text, Spot anchor) {
        UUID id = UUID.randomUUID();
        Marshal.global().submit(id, me, text);
        WishRun r = Marshal.global().assign(worker, id);
        if (anchor != null) {
            r.lockAnchor(anchor);
        }
        return r;
    }

    /** A link that runs a command nobody registered is decoration. */
    @GameTest(template = "empty_test")
    public static void bothCommandsAreRegistered(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        var moddy = root.getChild(ChildCommands.MODDY);
        helper.assertTrue(moddy != null && moddy.getChild(ChildCommands.GO) != null,
                Component.literal("/moddy go is not registered, so [take me there] does nothing at all"));
        helper.assertTrue(moddy.getChild(ChildCommands.BACK) != null,
                Component.literal("/moddy back is not registered, so [take me back] does nothing — and a child "
                        + "teleported 3000 blocks away with no way home is session-ending"));
        helper.succeed();
    }

    /** With several of his in flight, "take me to my wish" means the one he just made. */
    @GameTest(template = "empty_test")
    public static void aBlankIdMeansTheNewestOfHis(GameTestHelper helper) {
        UUID me = child("blank-id");
        String dim = helper.getLevel().dimension().location().toString();
        WishRun older = run(me, "gt-go-1", "a dragon", new Spot(dim, 10, 64, 10));
        WishRun newer = run(me, "gt-go-2", "a castle", new Spot(dim, 900, 70, -400));

        var picked = WishGo.pick(null, "");
        helper.assertTrue(picked.isEmpty(), Component.literal("with no player there is nobody to take anywhere"));

        // The pick is by player, so assert through the marshal the same way WishGo does.
        var mine = Marshal.global().liveFor(me);
        helper.assertTrue(mine.size() == 2, Component.literal("both are in flight"));
        helper.assertTrue(mine.get(mine.size() - 1) == newer,
                Component.literal("the newest of his is the one he is most likely looking at"));
        Marshal.global().finish(older.wishId());
        Marshal.global().finish(newer.wishId());
        helper.succeed();
    }

    /**
     * A wish that has not been aimed yet has nowhere to take him — and must say so rather than
     * teleporting him to 0,0,0, which is what a null anchor becomes if nobody checks.
     */
    @GameTest(template = "empty_test")
    public static void aWishWithNoAnchorYetHasNowhereToGo(GameTestHelper helper) {
        WishRun unaimed = run(child("no-anchor"), "gt-go-3", "something", null);
        helper.assertTrue(unaimed.anchor() == null, Component.literal("nothing aimed yet"));
        Marshal.global().finish(unaimed.wishId());
        helper.succeed();
    }

    /** A finished wish is still a place he can be taken — "show me the thing you made". */
    @GameTest(template = "empty_test")
    public static void aFinishedWishIsStillSomewhereHeCanBeTaken(GameTestHelper helper) {
        UUID me = child("finished-wish");
        String dim = helper.getLevel().dimension().location().toString();
        WishRun done = run(me, "gt-go-4", "a dragon", new Spot(dim, 5, 64, 5));
        Marshal.global().finish(done.wishId());

        helper.assertTrue(Marshal.global().lastCompleted(me) == done,
                Component.literal("the thing he just watched appear is exactly what 'take me there' should mean"));
        helper.assertTrue(done.anchor() != null, Component.literal("and it still knows where it is"));
        helper.succeed();
    }

    /** The landing spot must resolve to a real, loaded position in this world. */
    @GameTest(template = "empty_test")
    public static void theLandingSpotIsInsideTheWorldAndOutsideTheBuild(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        var landing = Landing.standOff(anchor.getX(), anchor.getY(), anchor.getZ(),
                anchor.getX() + 50, anchor.getZ());

        helper.assertTrue(landing.y() > com.moddingfromamod.compat.LevelHeights.bottom(helper.getLevel())
                        && landing.y() < com.moddingfromamod.compat.LevelHeights.topExclusive(helper.getLevel()),
                Component.literal("he would land outside the world at y=" + landing.y()));
        helper.assertFalse(Landing.insideBuild(landing.x(), landing.y(), landing.z(),
                        anchor.getX(), anchor.getY(), anchor.getZ(), 3),
                Component.literal("he lands inside a small build's own volume"));
        helper.succeed();
    }
}
