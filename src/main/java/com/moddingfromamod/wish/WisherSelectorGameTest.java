package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActingChild;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>An agent-authored {@code @p} reaches the child who WISHED, not whoever is nearer —.</b>
 *
 * <p>This gate exists for an unusual reason: <b>the behaviour is already correct and entirely
 * undefended.</b> asks which remedy to buy, and the measurement says none — the cheapest
 * option it lists ("set the command source to the owner") was already implemented one layer below
 * where an earlier issue's sweep was described, and it makes {@code @p} <i>exact</i> rather than approximate.
 *
 * <p>Nothing anywhere says so. If {@code RunCommandTool} stopped resolving the owner, or
 * {@code WishExecutor} stopped being handed the owner's position, every gate in the repo would stay
 * green and A's {@code /give @p diamond} would start handing the diamond to B. So this pins the two
 * facts that make it work, and the second test is a CONTROL proving the first can tell the
 * difference at all.
 *
 * <h2>Why {@code @p} is exact and not merely "usually right"</h2>
 *
 * <p>{@code @p} is <i>nearest player to the command's source position</i>, and the source position
 * is the owner's own position — so the owner is at distance <b>zero</b> from himself. Another child
 * would have to be standing in the same block to tie. Measured on the real corpus, all 21 uses of
 * {@code @p} in agent-authored commands are top-level {@code execute at @p run …}, and there are
 * <b>zero</b> bare {@code @s} (all 85 occurrences are rebound by an {@code execute as}), so this is
 * the whole exposure rather than half of it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WisherSelectorGameTest {

    private static final String BATCH = "wisherselector";
    private static final String REACHED = "mfam_at_selector_reached";

    /**
     * <b>B joins FIRST and stands three blocks away; the command still reaches A.</b>
     *
     * <p>Join order is what thirty-odd sites used, and it is the specific wrong answer
     * this asserts against — so B is created first on purpose.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void anAgentsAtPReachesTheChildWhoWished(GameTestHelper helper) {
        ServerPlayer bee = MockPlayerNetwork.inLevel(helper);      // joined FIRST
        ServerPlayer ay = MockPlayerNetwork.inLevel(helper);       // ...and A is the one who wished
        try {
            var here = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            ay.connection.teleport(here.getX() + 0.5, here.getY(), here.getZ() + 0.5, 0f, 0f);
            bee.connection.teleport(here.getX() + 3.5, here.getY(), here.getZ() + 0.5, 0f, 0f);

            // 1. The right child is resolved from the WISH, not from who joined first.
            ServerPlayer acting = ActingChild.of(helper.getLevel().getServer(), ay.getUUID());
            helper.assertTrue(acting == ay,
                    Component.literal("ActingChild must answer the wish's OWNER; it answered "
                            + (acting == null ? "nobody" : acting.getScoreboardName())
                            + ", which with two children is join order wearing a better name"));

            // 2. ...and a command run for him resolves @p to him, with B standing nearer to
            //    everything else in the world than he is to B.
            ay.removeTag(REACHED);
            bee.removeTag(REACHED);
            WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(),
                    acting.position(), acting, "tag @p add " + REACHED);

            helper.assertTrue(ay.getTags().contains(REACHED),
                    Component.literal("the wisher was not reached — an agent-authored @p in A's wish must land on A"));
            helper.assertFalse(bee.getTags().contains(REACHED),
                    Component.literal("the OTHER child was reached: this is #529's bug, and it is silent — the "
                            + "command succeeds, the agent is told 'Ran: OK', and the diamond is "
                            + "in the wrong inventory"));
        } finally {
            MockPlayerNetwork.remove(ay);
            MockPlayerNetwork.remove(bee);
        }
        helper.succeed();
    }

    /**
     * <b>The control, and without it the test above is worthless.</b>
     *
     * <p>A gate that only ever asserts "A was reached" passes just as happily against a
     * {@code @p} that cannot discriminate at all. This runs the identical command from B's
     * position and requires it to reach B — so the first test's green means <i>the source decides
     * it</i> rather than <i>@p happens to pick A</i>.
     *
     * <p>This is the same discipline the repo records for the freeze gate: a control that is TRUE
     * OF THE BUG is not a control.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void andTheSelectorReallyDoesFollowTheSource(GameTestHelper helper) {
        ServerPlayer ay = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bee = MockPlayerNetwork.inLevel(helper);
        try {
            var here = helper.absolutePos(new net.minecraft.core.BlockPos(5, 2, 5));
            ay.connection.teleport(here.getX() + 0.5, here.getY(), here.getZ() + 0.5, 0f, 0f);
            bee.connection.teleport(here.getX() + 3.5, here.getY(), here.getZ() + 0.5, 0f, 0f);
            ay.removeTag(REACHED);
            bee.removeTag(REACHED);

            WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(),
                    bee.position(), bee, "tag @p add " + REACHED);

            helper.assertTrue(bee.getTags().contains(REACHED) && !ay.getTags().contains(REACHED),
                    Component.literal("run from B's position, @p must reach B — if it cannot tell the two apart then "
                            + "the test above proves nothing about who the source is"));
        } finally {
            MockPlayerNetwork.remove(ay);
            MockPlayerNetwork.remove(bee);
        }
        helper.succeed();
    }
}
