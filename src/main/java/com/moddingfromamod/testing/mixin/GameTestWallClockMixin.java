package com.moddingfromamod.testing.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>A test's {@code timeoutTicks} is at least that many ticks AND that much time at 20 ticks a second</b>
 *. A GameTest server ticks as fast as it can — 200 to 500 ticks a second in a quiet batch
 * (CLAUDE.md, docs/GATE_PERFORMANCE.md) — so {@code timeoutTicks = 1200}, which its author read as a
 * minute, was 2.4 seconds when the batch ran alone and a minute when it was crowded. A test whose subject
 * does real work in real time (a tool on a driver thread hopping onto the server thread, finish_building's
 * photograph waiting out its quiet period) then timed out or passed according to how busy the SERVER
 * was, not how its code behaved: {@code KeptIsNotAPropGameTest} failed "waiting for finish_wish on tick
 * 1202" in four runs that way, and its timeout left its wish in flight for every later test to read.
 *
 * <p>So the timeout fires only once both have passed: the ticks (unchanged, so a tick-timed test is
 * exactly as strict as before) and their wall-clock length at 20 ticks a second, capped at
 * {@code WallClockTimeout.MOST_MS} so a test with a huge tick budget (one that keeps its own wall-clock deadline) cannot
 * hold a run for hours. Nothing is assumed about game time: a test that asserts something happens within
 * N GAME ticks still measures game ticks, and only a test that would otherwise time out waits longer.
 * {@code GameTestInfo} exists only in a test run, so a player's server never ticks one.
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestWallClockMixin {
    @Shadow
    private int tickCount;

    @Unique
    private long moddingfromamod$startedMs;

    @Unique
    private boolean moddingfromamod$said;

    @Inject(method = "startTest", at = @At("HEAD"))
    private void moddingfromamod$markStart(CallbackInfo ci) {
        if (moddingfromamod$startedMs == 0L) {
            moddingfromamod$startedMs = System.currentTimeMillis();
        }
    }

    @ModifyExpressionValue(method = "tickInternal",
            at = @At(value = "FIELD", target = "Lnet/minecraft/gametest/framework/GameTestInfo;timeoutTicks:I"))
    private int moddingfromamod$notBeforeItsTime(int timeoutTicks) {
        if (tickCount <= timeoutTicks || moddingfromamod$startedMs == 0L) {
            return timeoutTicks;
        }
        long ranMs = System.currentTimeMillis() - moddingfromamod$startedMs;
        if (com.moddingfromamod.seam.WallClockTimeout.timedOut(tickCount, timeoutTicks, ranMs)) {
            return timeoutTicks;
        }
        long floorMs = com.moddingfromamod.seam.WallClockTimeout.floorMs(timeoutTicks);
        if (!moddingfromamod$said) {
            moddingfromamod$said = true;
            org.slf4j.LoggerFactory.getLogger("gt-wallclock").info(
                    "[gt-wallclock] {} reached its {} ticks after {} ms; held to {} ms of wall clock before it times out",
                    ((GameTestInfo) (Object) this).id(), timeoutTicks, ranMs, floorMs);
        }
        return tickCount;   // not past it yet
    }
}
