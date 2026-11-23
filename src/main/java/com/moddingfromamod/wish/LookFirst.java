package com.moddingfromamod.wish;

import java.util.Set;

/**
 * <b>A question is answered after LOOKING, never before</b>.
 *
 * <p>Live, 2026-09-25: <i>"spikor stopped following me, why?"</i> was routed as a question — the
 * child was told <i>"Good question! Let me have a look…"</i> — and the agent's whole run was
 * {@code notify_player → finish_wish → finish_building → finish_wish}. It looked at nothing and told
 * him Spikor <i>"wandered too far away and got lost"</i>. The real causes (two Spikors, follow set by
 * addressing a llama, one of them killed) were all in the world, one {@code list_creatures} away.
 * "Let me have a look" was a promise the code made on the agent's behalf and nothing kept.
 *
 * <p>So for a wish {@link WishIntent} classes as a QUESTION, the tools that ANSWER it are refused
 * until the wish has made one successful read. The same shape as {@code finish_wish} demanding
 * {@code finish_building}, and like every guard here it gives way ({@link #MAX_REFUSALS}): a
 * question that ends without looking is worse than one that looked, but better than one that
 * cannot end. Minecraft-free; {@code LookFirstTest} pins it, and {@code McpProtocol} applies it
 * where every tool call passes.
 */
public final class LookFirst {

    private LookFirst() {
    }

    /** The tools that look at the world or its records and change nothing. */
    public static final Set<String> READS = Set.of(
            "list_creatures", "inspect_creature", "look_around", "inspect_build", "list_builds",
            "list_datapacks", "verify_datapack", "list_items", "inspect_item", "inspect_player",
            "list_paintings", "inspect_painting", "list_events", "inspect_event", "list_wishes", "list_templates", "photograph");

    /** The tools that give the child an answer. */
    public static final Set<String> ANSWERS = Set.of("notify_player", "finish_building", "finish_wish");

    /** After this many refusals the answer goes through, looked or not. */
    public static final int MAX_REFUSALS = 2;

    public static boolean isRead(String tool) {
        return tool != null && READS.contains(tool);
    }

    /**
     * The refusal for {@code tool}, or null to let it through.
     *
     * @param question     the wish is a question ({@link WishIntent#isQuestion})
     * @param looked       the wish has made at least one successful read
     * @param refusedSoFar how many times this wish has already been refused for it
     */
    public static String refusal(String tool, boolean question, boolean looked, int refusedSoFar) {
        if (!question || looked || tool == null || !ANSWERS.contains(tool)
                || refusedSoFar >= MAX_REFUSALS) {
            return null;
        }
        return "Not yet — this wish is a QUESTION and you have not looked at anything, so the child "
                + "has not been told anything. He was promised \"let me have a look\". Look first: "
                + "list_creatures / inspect_creature for a creature (is it there? how many? is it set "
                + "to follow?), look_around for what is near him, list_builds / inspect_build for a "
                + "build, list_datapacks for a rule, list_items for an item. Then answer from what you SAW — say what you found, "
                + "not a guess about what probably happened.";
    }
}
