package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the wish flow: plan → build → finish_building → judge → finish.
 *
 * <p>The unit tests cover the arithmetic ({@code WishPlanTest}) and the four verdict behaviours
 * ({@code WishVerdictTest}). What only a running server proves is that the flow EXISTS as far as the
 * agent is concerned — that the tools are registered, advertised, and pre-approved.
 *
 * <p>That last one is not hypothetical. The agent runs with {@code --permission-mode dontAsk} and an
 * allowlist derived from the registry, so a tool that is registered but missing from the allowlist is
 * silently DENIED — indistinguishable, from the child's side, from the feature not existing. And a
 * tool the prompt never mentions will never be reached for, however well it works. Both have
 * happened here before.
 */
@GameTestHolder(GateShards.CI)
public class WishFlowGameTest {

    private static boolean hasTool(String name) {
        return WishAgent.buildTools().all().stream().anyMatch(t -> t.name().equals(name));
    }

    /** Every step of the flow is a real, registered tool. */
    @GameTest(template = "empty_test")
    public static void theWholeFlowIsRegistered(GameTestHelper helper) {
        for (String tool : new String[]{"plan_wish", "finish_building", "finish_wish", "set_status"}) {
            helper.assertTrue(hasTool(tool), Component.literal(tool + " must be registered or the flow has a hole"));
        }
        helper.succeed();
    }

    /**
     * Registered is not enough — an un-allowlisted tool is silently denied at runtime, which looks
     * exactly like a broken feature.
     */
    @GameTest(template = "empty_test")
    public static void theNewToolsArePreApproved(GameTestHelper helper) {
        String allowed = com.moddingfromamod.agent.AgentSession.allowedTools();
        for (String tool : new String[]{"plan_wish", "finish_building", "finish_wish"}) {
            helper.assertTrue(allowed.contains("mcp__wishwatch__" + tool),
                    Component.literal(tool + " is registered but NOT pre-approved — it would be denied in play"));
        }
        helper.succeed();
    }

    /**
     * The prompt has to actually route to them. A tool nobody is told about is nearly as invisible
     * as one that is denied — which is how sculpt_creature once shipped registered, permitted and
     * never used.
     */
    @GameTest(template = "empty_test")
    public static void thePromptTeachesTheFlowAndForbidsTheOldLoop(GameTestHelper helper) {
        String prompt = com.moddingfromamod.agent.AgentSession.prompt();
        for (String mustName : new String[]{"plan_wish", "finish_building", "finish_wish"}) {
            helper.assertTrue(prompt.contains(mustName),
                    Component.literal("the brief never mentions " + mustName + ", so it will not be used"));
        }
        // The behaviour change this whole phase is about, stated where the agent will read it.
        // Case-insensitive on purpose: the assertion is about the RULE being present, not about
        // how emphatically it happens to be shouted this week.
        String lower = prompt.toLowerCase(java.util.Locale.ROOT);
        helper.assertTrue(lower.contains("one attempt"),
                Component.literal("the brief must say the agent gets one in-world attempt"));
        helper.assertTrue(prompt.contains("could_be_better"),
                Component.literal("the brief must offer could_be_better as the alternative to polishing"));
        // the one-attempt boundary must be stated as WORLD-VISIBILITY, not as
        // finish_building. Stating it the other way is what let a wish keep building for 128s.
        helper.assertTrue(lower.contains("instant he can see"),
                Component.literal("the brief must anchor the one-attempt rule to when the CHILD can see something"));
        // The verdict skew: lead with the fix, not the apology.
        helper.assertTrue(lower.contains("lead with the fix"),
                Component.literal("the brief must prefer could_be_better over rough"));
        helper.assertTrue(lower.contains("spawn:false"),
                Component.literal("the brief must teach spawn-last, or creatures keep arriving powerless"));
        helper.succeed();
    }

    /**
     * The verdicts the tool advertises and the ones the code understands must be the same set.
     * A verdict the agent is offered but that parses to something else would silently mislabel — and
     * in the failed case, silently skip the cleanup.
     */
    @GameTest(template = "empty_test")
    public static void everyAdvertisedVerdictParsesToItself(GameTestHelper helper) {
        for (String name : WishVerdict.choices().split("\\|")) {
            String v = name.trim();
            helper.assertTrue(WishVerdict.parse(v).name().equalsIgnoreCase(v),
                    Component.literal("advertised verdict '" + v + "' parsed to " + WishVerdict.parse(v)));
        }
        helper.succeed();
    }

    /**
     * A suggestion is going into a 260px box in front of a child. Over-long text must be cut on a
     * word boundary, because "make Spikor's fire ev" reads as broken rather than as an offer.
     */
    @GameTest(template = "empty_test")
    public static void aSeededSuggestionFitsTheChildsWishBox(GameTestHelper helper) {
        String rambling = "make Spikor's enormous roaring fire breath even bigger and greener and "
                + "give him a crown as well please";
        String trimmed = WishText.trimSuggestion(rambling);
        helper.assertTrue(trimmed.length() <= WishText.SUGGESTION_MAX,
                Component.literal("suggestion too long for the box: " + trimmed.length()));
        helper.assertTrue(!trimmed.endsWith(" "), Component.literal("no trailing space: '" + trimmed + "'"));
        helper.assertTrue(rambling.startsWith(trimmed),
                Component.literal("must be a clean prefix of what the agent meant, was: " + trimmed));
        helper.succeed();
    }
}
