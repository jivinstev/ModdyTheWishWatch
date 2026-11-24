package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.gamerules.GameRules;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The child must not be reading command output while he plays.</b>
 *
 * <p>Reported from a live session, with a screenshot: a block of white text across the lower half of
 * the screen, several lines a second, over the game.
 *
 * <pre>
 * [Butt Cheek Water Bottle: Teleported Butt Cheek Water Bottle to -1767.5, 6.958008, 1005.596553]
 * [Butt Cheek Water Bottle: Displaying particle minecraft:portal]
 * [Butt Cheek Water Bottle: Running function metal_grinder:convert]
 * </pre>
 *
 * <p>Vanilla broadcasts admin command usage to every operator, and the child is one. Every wish is
 * granted with admin commands, so the machinery of granting is permanently in his face — in that
 * session a repeating command block was firing it every tick, but the source is not the point.
 *
 * <p>Asserted on the SERVER's own rules rather than on a screenshot, because that is the thing that
 * decides it and it can be checked in a second without a client.
 */
@GameTestHolder("moddingfromamod")
public class QuietWorldGameTest {

    /**
     * <b>{@code logAdminCommands} is off by the time a world is running.</b>
     *
     * <p>Set on server start (see {@code WishAgent.quietenAdminCommandSpam}), so by the time any
     * test — or any child — is in a world, it is already off.
     */
    @GameTest(template = "empty_test", timeoutTicks = 40)
    public void adminCommandOutputIsNotBroadcastToTheChild(GameTestHelper helper) {
        boolean loud = helper.getLevel().getServer().overworld().getGameRules()
                .get(GameRules.LOG_ADMIN_COMMANDS);
        if (loud) {
            helper.fail(Component.literal("logAdminCommands is still ON, so every command a wish runs is printed over "
                    + "the child's game. He reported it as a wall of white text he could not see "
                    + "past — '[Butt Cheek Water Bottle: Teleported ...]' several times a second"));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>...and {@code sendCommandFeedback} is left ALONE.</b>
     *
     * <p>The other half of the decision, asserted so nobody "tidies up" by switching both off. That
     * rule is the reply to whoever ran the command, and {@code run_command} plus the datapack
     * verifier read it back to decide whether a wish actually worked. Silencing it would blind the
     * agent to its own results — the machine would still be broken, and nothing would be able to
     * tell.
     */
    @GameTest(template = "empty_test", timeoutTicks = 40)
    public void theAgentCanStillSeeItsOwnCommandResults(GameTestHelper helper) {
        boolean feedback = helper.getLevel().getServer().overworld().getGameRules()
                .get(GameRules.SEND_COMMAND_FEEDBACK);
        if (!feedback) {
            helper.fail(Component.literal("sendCommandFeedback has been turned off. That is not the rule that was "
                    + "spamming the child — it is the one the agent reads its own results through, "
                    + "so a machine that does not work would now be indistinguishable from one that "
                    + "does"));
            return;
        }
        helper.succeed();
    }
}
