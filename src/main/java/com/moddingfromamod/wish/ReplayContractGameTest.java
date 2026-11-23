package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import java.util.List;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every kind of thing the watch can make must be re-makeable by COMMAND.</b>
 *
 * <p>A learned wish is a list of commands, not tool calls, so a design that no command can rebuild
 * can never become a button — {@code add_template} would have nothing to put in its plan. Trains
 * were exactly that for a while: every plan dutifully saved to {@code trains.json} and nothing able
 * to reach it.
 *
 * <p><b>Why this lives here and not in {@code TrainGameTest}, where it used to.</b> That test's own
 * javadoc said it: <i>"Checked against ReplayContract rather than against trains, because the bug
 * is not about trains — it is about forgetting this step when adding the NEXT kind of thing."</i>
 * Agreed, and the shard should say so. In the trains shard the audit ran only when somebody touched
 * a train; here it runs on <b>every</b> change, which is what a cross-cutting contract wants.
 *
 * <p>It was also the ONE thing making {@code AgentSession} a Create-tier file's problem:
 * {@code ReplayContract.violations} calls {@code AgentSession.prompt()} to check each list tool is
 * mentioned, so {@code TrainGameTest} was the only Create/train test that reached the agent's
 * system prompt at all — five PRs paid the train tier for editing prose the train tests could only
 * observe through this one audit. Moving the audit does not weaken it; it widens it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReplayContractGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 200)
    public void everySavedDesignCanBeRebuiltByCommandSoItCanBeTaught(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        List<String> problems = ReplayContract.violations(dispatcher);
        if (!problems.isEmpty()) {
            helper.fail(Component.literal("these wishes can be granted but never REMEMBERED:\n  - "
                    + String.join("\n  - ", problems)));
            return;
        }
        System.out.println("REPLAY CONTRACT: all " + ReplayContract.ALL.size()
                + " kinds of saved design are rebuildable by an allowlisted command");
        helper.succeed();
    }
}
