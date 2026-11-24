package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * step 1 (docs/OPEN_DOOR_PLAN.md §8.1), driven through the REAL {@code WishService.submit}:
 * pressing a recipe starts no safety grade, and a typed wish still does.
 *
 * <p>The probe is {@link SafetyGrader#startedFor}, read with words unique to this run, so a grade
 * started by any other test cannot be mistaken for ours. The TYPED arm is the control: it proves
 * the probe can see a grade at all (a probe that always says "none" would pass the press arm on
 * its own). The world-rules grade and the reference prefetch sit behind the same boolean in
 * {@code submit} ({@link PreAgentModels#wanted}); the world-rules grade has nothing to observe on a
 * world that denies nothing, which a GameTest world is.
 *
 * <p>Its own batch: batches run one after another (CLAUDE.md), and this test sets the
 * child-safety arm, a system property every other test shares.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PreAgentModelsGameTest {

    @GameTest(template = "empty_test", batch = "preagent_models")
    public static void aPressedRecipeStartsNoGradeAndATypedWishDoes(GameTestHelper helper) {
        System.setProperty(ModdingFromAModMod.MODID + ".childsafety", "on");
        RuntimeConfig.invalidate();
        // No real model: a grade still STARTS (the memo entry is written before its thread runs),
        // and answers "unavailable" — which is all the probe needs, and costs nothing.
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        WishService.forget(p);

        var known = WishCatalog.templates().known().stream()
                .filter(k -> !k.phrase().isBlank()).toList();
        String run = Long.toString(System.nanoTime(), 36);
        // A button's words can be anything a client sends (SubmitWishPayload) — unique here so the
        // probe answers about THIS press only.
        String pressed = "a pressed recipe " + run;
        String typed = "a typed wish about a kite " + run;

        WishService.Outcome press = known.isEmpty() ? null
                : WishService.submit(p, pressed, WishSource.PRESET, known.get(0).id());
        boolean pressGraded = SafetyGrader.startedFor(pressed);
        WishService.submit(p, typed, WishSource.TYPED);
        boolean typedGraded = SafetyGrader.startedFor(typed);

        // Leave nothing behind for a later batch: the typed wish waits for an agent that never
        // comes, and a live run left to age tires Moddy for whoever runs next (ModdyTiredGameTest).
        for (Wish w : WishBook.get(helper.getLevel().getServer()).recent(40)) {
            if (w.text().equals(typed) || w.text().equals(pressed)) {
                try {
                    Marshal.global().cancel(w.id(), CancelMode.STOP, "", "");
                } catch (RuntimeException ignored) {
                    // already over
                }
                WishProgress.clearWishBar(w.id());
            }
        }
        WishQueue.global().clear();
        SafetyGrader.transportForTest(null);
        System.clearProperty(ModdingFromAModMod.MODID + ".childsafety");
        RuntimeConfig.invalidate();

        helper.succeedWhen(() -> {
            helper.assertTrue(!known.isEmpty(), Component.literal("no built-in recipe to press"));
            helper.assertValueEqual(press, WishService.Outcome.FULFILLING, Component.literal("the press is served"));
            helper.assertFalse(pressGraded, Component.literal("a pressed recipe must start no safety grade"));
            helper.assertTrue(typedGraded,
                    Component.literal("control: a typed wish must still start one, or the probe sees nothing"));
        });
    }
}
