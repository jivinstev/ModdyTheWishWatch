package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: ONE press of a starter item button gives, even when its pack was not loaded yet.</b>
 *
 * <p>Red on {@code main}: {@code wishdatapack tiny_cookie_grow give} on an unloaded pack installed
 * it, answered "Loaded — do that again!" and gave nothing, and nothing ever pressed again — the
 * earlier pass saw "Here are your Tiny Cookies!" and empty hands 4 times of 4. The pack is switched
 * OFF first (Gate B reuses its world, so it may be on from an earlier run), then pressed exactly
 * once, and the test waits for the cookies to arrive.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PendingPressGameTest {

    static final String ID = "tiny_cookie_grow";
    /** Ticks are not seconds here; a reload is bounded by work, and this is generous. */
    static final int LIMIT_TICKS = 1600;

    @GameTest(template = "empty_test", batch = "pendingpress", timeoutTicks = 2400)
    public static void aFirstPressOnAnUnloadedPackStillGives(GameTestHelper helper) {
        if (!DatapackStore.exists(ID)) {
            helper.fail(Component.literal("the starter pack '" + ID + "' is not servable — the jar's starter library is missing it"));
            return;
        }
        var server = helper.getLevel().getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        child.getInventory().clearContent();
        DatapackReloader.disableAndReload(server, ID);

        int[] phase = {0};
        int[] waited = {0};
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            boolean busy = DatapackReloader.reloadInProgress() || DatapackReloader.reloadDeferred();
            if (phase[0] == 0) {
                if (!busy && !WishDatapackCommand.loaded(server, ID)) {
                    phase[0] = 1;
                    WishExecutor.Result r = WishExecutor.runOne(server, helper.getLevel(),
                            child.position(), child, "wishdatapack " + ID + " give");
                    if (!r.success()) {
                        MockPlayerNetwork.remove(child);
                        helper.fail(Component.literal("the press itself failed: " + r.joined()));
                        return;
                    }
                }
            } else if (holdsTheCookie(child)) {
                MockPlayerNetwork.remove(child);
                helper.succeed();
                return;
            }
            waited[0] += 10;
            if (waited[0] > LIMIT_TICKS) {
                MockPlayerNetwork.remove(child);
                helper.fail(Component.literal(phase[0] == 0
                        ? "could not get '" + ID + "' unloaded to start from the first-press state"
                        : "ONE press of '" + ID + " give' on an unloaded pack gave nothing, "
                        + waited[0] + " ticks later — the child is told it worked and gets empty hands"));
                return;
            }
            // A FRESH Runnable each time: GameTestInfo keys its schedule by the Runnable object and
            // removes the entry after running it, so a poll that re-schedules ITSELF is dropped the
            // moment it returns and never runs again (measured: one poll, then the test timed out).
            helper.runAfterDelay(10, () -> poll[0].run());
        };
        helper.runAfterDelay(1, () -> poll[0].run());
    }

    /**
     * THE COOKIE, by name — not "anything". A mock player is handed Moddy's watch a tick after it
     * joins, so "his hands are not empty" passed with the fix switched off (measured: the red arm
     * went green). The give names its item, so that is what is asked for.
     */
    private static boolean holdsTheCookie(ServerPlayer p) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().contains("Tiny Cookie")) {
                return true;
            }
        }
        return false;
    }
}
