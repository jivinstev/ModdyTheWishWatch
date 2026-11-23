package com.moddingfromamod.testing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moddingfromamod.seam.GameTestFailure;
import com.moddingfromamod.seam.GameTestStall;
import net.minecraft.gametest.framework.GameTestException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import org.spongepowered.asm.mixin.Mixin;

/**
 * <b>One test's bug fails that test, not the whole run</b>. On 1.21.4 an exception thrown from a
 * test's sequence (a {@code runAfterDelay}, a {@code succeedWhen}, a {@code startSequence} step) reached
 * {@code GameTestInfo.fail(Throwable)} and failed that test. 1.21.8's {@code fail} takes only a
 * {@code GameTestException}, so anything else — an NPE in a test body — escapes the tick and crashes the
 * test SERVER: measured on the first 1.21.8 Gate B, one test's NullPointerException ended the run with
 * "Exception in server tick loop" and no result line for any test. This puts the 1.21.4 behaviour back.
 * {@code GameTestInfo} only exists in a test run, so a player's server never ticks one.
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestCrashGuardMixin {

    @WrapMethod(method = "tick(Lnet/minecraft/gametest/framework/GameTestRunner;)V")
    private void moddingfromamod$failTheTestNotTheServer(GameTestRunner runner, Operation<Void> original) {
        // which test's tick the Server thread is in, for the Gate B stall watchdog to name.
        GameTestStall.enter(((GameTestInfo) (Object) this).id().toString());
        try {
            original.call(runner);
        } catch (GameTestException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            ((GameTestInfo) (Object) this).fail(GameTestFailure.of(e));
        } finally {
            GameTestStall.exit();
        }
    }
}
