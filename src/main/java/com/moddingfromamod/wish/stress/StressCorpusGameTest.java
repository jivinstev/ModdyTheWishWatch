package com.moddingfromamod.wish.stress;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every stress-corpus case</b> — buildings, creatures, datapacks, pictures, placements and
 * composites — in the DEFAULT shard.
 *
 * <p>Until this was the half of the corpus that needed no Create; the machines and trains
 * cases ran in {@code StressGameTest} in the Create shard, and went with it. The driver is
 * {@link StressGameTest#runCorpus}. The method keeps its old name so its gate history carries on.
 *
 * <p>Measured when it moved: ~21 s of test time in its own batch on 1.21.4, and the gate-select
 * backtest since 2026-09-12 has a targeted run pick it up on ~25% of gated PRs.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StressCorpusGameTest {

    /**
     * The harnesses this runs — named so a change to one selects this class in a targeted run
     * (gate-select maps a changed class to the GameTest classes that name it).
     */
    static final List<Class<? extends StressHarness>> HARNESSES = List.of(
            BuildingsHarness.class, CreaturesHarness.class, DatapacksHarness.class,
            CompositeHarness.class, PicturesHarness.class, PlacementHarness.class);

    // 2000 -> 6000: a placement case lives 1200 ticks in its building, per batch it shares.
    @GameTest(template = "empty_test", timeoutTicks = 6000, batch = "stresscorpus")
    public void everyCorpusCaseWithoutCreateHoldsItsInvariants(GameTestHelper helper) {
        System.out.println("STRESS: harnesses " + HARNESSES.stream().map(Class::getSimpleName).toList());
        StressGameTest.runCorpus(helper, c -> true, "every case");
    }
}
