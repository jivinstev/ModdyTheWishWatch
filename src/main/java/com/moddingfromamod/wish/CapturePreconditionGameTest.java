package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The capture ritual must not start when the shutter cannot fire</b> — Part A.
 *
 * <p>Traced line by line in that issue and never observed, which in a programme built on the
 * RUN/READ distinction made it the weakest claim in the write-up it came from. This is the run.
 *
 * <p><b>What made it a bug rather than a missing photograph.</b> {@code WishCapture} has two
 * mechanisms and only one of them is a payload of ours. The chat line, the invulnerability, the
 * spun camera and the yank-back all reach a vanilla client — {@code connection.teleport} is a
 * vanilla packet — while {@code CaptureModePayload}, the client input lock, is silently dropped by
 * {@code Send.to}. So the half that ENFORCES the freeze survives and the half that EXPLAINS it does
 * not: the child is told to hold still, has his view turned for him, and rubber-bands if he walks.
 * Which reads as lag, where a clean freeze at least reads as something happening.
 *
 * <p>{@code MockPlayerNetwork.withoutChannels} is the vanilla client, headlessly: a real player in
 * the real list whose payload setup carries none of our channels, which is exactly what a child who
 * never installed the mod is.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class CapturePreconditionGameTest {
    // The modded-client half (a client that CAN receive the freeze, and what a real session gives back) is
    // CaptureModdedClientGameTest: only the private modded-client plug-in has the channels a session needs.

    private static final String BATCH = "captureprecondition";

    /**
     * <b>Verified to fail</b> with the {@code Send.canReceive} guard removed from
     * {@code WishCapture.enter}: the lease reads {@code TAKEN} and the session is open, i.e. a
     * child with no mod is frozen for a photograph that can never be taken.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aClientWithNoShutterIsNeverFrozen(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        try {
            MockPlayerNetwork.withoutChannels(child, () -> {
                WishCapture.Lease lease = WishCapture.enter(child);
                helper.assertTrue(lease == WishCapture.Lease.NO_CAMERA,
                        Component.literal("a client that cannot receive the freeze must be told NO_CAMERA, not "
                                + "BUSY — BUSY tells the agent to try again, and it never will "
                                + "become true. Got " + lease));
                // The claim that matters is not the return value but that NOTHING HAPPENED: no
                // session, so no invulnerability, no chat line, and above all no spun camera to
                // rubber-band him back to.
                helper.assertFalse(WishCapture.isCapturing(child.getUUID()),
                        Component.literal("the ritual must not have started at all"));
                helper.assertFalse(child.isInvulnerable(),
                        Component.literal("he must not have been made invulnerable for a photo nobody can take"));
            });
        } finally {
            WishCapture.exit(child);
            MockPlayerNetwork.remove(child);
        }
        helper.succeed();
    }
}
