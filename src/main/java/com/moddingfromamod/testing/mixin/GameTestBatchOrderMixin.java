package com.moddingfromamod.testing.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.moddingfromamod.testing.gt.CoverageMap;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * <b>On a coverage-mapping run, batches run in the order of their names</b> (docs/GATE_B.md, "The coverage map").
 *
 * <p>{@code GameTestBatchFactory.divideIntoBatches} groups tests by environment with {@code Collectors.groupingBy}, a
 * HashMap keyed by holders that hash by identity, so the batches of a run come out in a different order in every JVM
 * (measured: two mapping runs of one commit started with different classes, and one class's batches were not even
 * next to each other). What a class leaves running then landed in a different neighbour each run, and two maps of one
 * commit disagreed for 264 of 360 classes. Sorted by environment name, a mapping run's batches are
 * {@code covmap_0idle}, then each class's own batches and its settle window together, class after class, the same
 * every time. Any other run is untouched: {@link CoverageMap#on()} is false.
 */
@Mixin(GameTestBatchFactory.class)
public abstract class GameTestBatchOrderMixin {

    @ModifyReturnValue(method = "divideIntoBatches", at = @At("RETURN"))
    private static List<GameTestBatch> moddingfromamod$inNameOrder(List<GameTestBatch> batches) {
        return CoverageMap.on() ? CoverageMap.inOrder(batches) : batches;
    }
}
