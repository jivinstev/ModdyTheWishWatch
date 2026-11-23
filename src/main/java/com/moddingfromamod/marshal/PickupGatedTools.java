package com.moddingfromamod.marshal;

import java.util.Set;

/**
 * <b>Which tools may run before a wish's pickup verdicts have landed</b> ({@link PickupHold}).
 *
 * <p>Only LOOKING: reading the world, the child's things and the catalogues. Everything else parks —
 * every tool that puts a word in front of the child (a status line, a plan line, the sparkles' "it is
 * coming!", chat, a question), every tool that changes the world, and {@code look_up_character},
 * which sends the wish's words out to a web search. The list is the short, safe side on purpose: a
 * tool added tomorrow is GATED until somebody decides it only looks, so forgetting to classify one
 * costs a few seconds of parking and never a word a refused wish should not have produced.
 *
 * <p>{@code PickupGatedToolsTest} fails a read-only name the registry no longer serves, so the list
 * cannot rot into names that let nothing through.
 */
// verify_datapack is NOT here: with run:true it executes a pack's functions in the world (audit).
public final class PickupGatedTools {

    /** The tools that only look. Everything not here waits for the pickup verdicts. */
    public static final Set<String> READ_ONLY = Set.of(
            "await_wish", "look_around", "find_content", "find_ready_made", "describe_ready_made",
            "cbscript_help",
            "list_creatures", "list_builds", "list_items", "list_paintings", "list_events",
            "list_templates", "list_wishes", "list_datapacks",
            "inspect_creature", "inspect_build", "inspect_item", "inspect_event", "inspect_painting",
            "inspect_player");

    private PickupGatedTools() {
    }

    /** Must this tool wait for the wish's pickup verdicts? */
    public static boolean gated(String toolName) {
        return toolName == null || !READ_ONLY.contains(toolName);
    }
}
