package com.moddingfromamod.marshal;

import java.util.Collection;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * <b>After {@code finish_building} the wish is over for the child: nothing more is built</b>
 * (2026-10-21). Minecraft-free; {@code McpProtocol}'s {@code tools/call} only asks it.
 *
 * <p>{@code finish_building} now plays the end straight away: the shimmer stops, the "all done"
 * sound plays and he is told. Only then is the final photograph taken, and that photograph is
 * for GRADING (the agent's verdict, the description line, the picture on his account page), never
 * for another turn of the crank. The tool result already said "do NOT go back and build more" and
 * that was advice. Measured before this: after {@code finish_building}, about 2% of 1,120 archived
 * runs and 1 of 93 real wishes changed the world again, each time after he had been told it was
 * done. So it is a rule now: a tool that is not on {@link #ALLOWED} refuses, naming
 * {@code finish_wish}.
 *
 * <p>Looking is still allowed (the read tools), and so is teaching the watch the grant
 * ({@code add_template}). An agent that wants to LOOK before it is done asks for the camera
 * itself, with {@code photograph}, before it calls {@code finish_building}.
 *
 * <p><b>POLISH IN PLACE, NEVER REPLACE</b> (2026-10-22). The second look sometimes finds
 * something small, and changing a thing he already has, where it is, is fine as long as nothing
 * disappears, reappears or arrives twice. So three calls run after the finish when they change
 * THIS wish's own thing in place ({@link #polish}):
 * <ul>
 *   <li>{@code modify_mob} on one of its creatures, for what it does (follow, friendly, health,
 *       speed, a glow, a name), never its size or a saddle: those rebuild the creature, which is
 *       a replacement he can see;</li>
 *   <li>{@code make_item} starting from its item, which rewrites the stacks he is holding in
 *       their own slots and, after the finish, never hands him another;</li>
 *   <li>{@code author_datapack} rewriting its own feature, which changes what the thing does and
 *       nothing he can see.</li>
 * </ul>
 * Everything that adds, removes, spawns, gives or rebuilds still refuses: a building's blocks, a
 * creature's shape or colours, an event, a picture. Measured over 1,128 archived wishes and 64 real
 * ones, nothing the agent changed after a finish was the camera catching a flaw; it was duplicates,
 * extra gifts and one second volcano, all of which this refuses.
 */
public final class AfterFinish {

    private AfterFinish() {
    }

    /** What a wish may still do once {@code finish_building} has ended it for the child. */
    public static final Set<String> ALLOWED = Set.of(
            "finish_wish",
            "add_template",
            "await_wish",
            "set_status",
            // reading changes nothing he can see
            "list_templates",
            "list_wishes",
            "list_builds",
            "list_creatures",
            "list_datapacks",
            "list_events",
            "list_items",
            "list_paintings",
            "inspect_build",
            "inspect_creature",
            "inspect_event",
            "inspect_item",
            "inspect_painting",
            "inspect_player",
            "find_content",
            "look_around",
            "cbscript_help");

    /**
     * The refusal for {@code tool}, or null when it may run.
     *
     * @param finished has this wish's {@code finish_building} already run?
     */
    public static String refusal(String tool, boolean finished) {
        return refusal(tool, finished, null, Set.of());
    }

    /**
     * @param args  the call's arguments, or null
     * @param made  the ids of what this wish made ({@code WishRun.made()})
     */
    public static String refusal(String tool, boolean finished, JsonObject args, Collection<String> made) {
        if (!finished || tool == null || ALLOWED.contains(tool) || polish(tool, args, made) != null) {
            return null;
        }
        return "This wish is finished: the child has already been shown it and told it is done, so "
                + "nothing more can be added, removed, spawned, given or rebuilt for it (" + tool
                + " is not available now). Only a small polish IN PLACE of this wish's own thing "
                + "still runs: modify_mob scope 'one' on its creature (no scale, no rideable), "
                + "make_item start_from its item (no count, variants, as_new, from_scratch or at), "
                + "or author_datapack rewriting its own feature_id. Otherwise call finish_wish with "
                + "your honest verdict and one short sentence describing what the photo shows.";
    }

    /** Tools that may polish this wish's own thing after the finish. */
    public static final Set<String> POLISHERS = Set.of("modify_mob", "make_item", "author_datapack");

    /**
     * <b>Is this call a polish in place of this wish's own thing?</b> Returns the id of the thing
     * it polishes, or null when it is not one. Pure.
     */
    public static String polish(String tool, JsonObject args, Collection<String> made) {
        if (tool == null || args == null || made == null || made.isEmpty()) {
            return null;
        }
        switch (tool) {
            case "modify_mob": {
                if (!"one".equals(str(args, "scope")) || args.has("scale") || args.has("rideable")
                        || (args.has("count") && !"1".equals(str(args, "count")))) {
                    return null;
                }
                return madeTarget(str(args, "target"), made);
            }
            case "make_item": {
                // More of it, a new family, somewhere else or someone else: not this one, in place.
                for (String f : new String[] {"count", "variants", "at", "recipient"}) {
                    if (args.has(f)) {
                        return null;
                    }
                }
                if ("true".equals(str(args, "as_new")) || "true".equals(str(args, "from_scratch"))) {
                    return null;
                }
                String from = str(args, "start_from");
                return from != null && made.contains(from) ? from : null;
            }
            case "author_datapack": {
                String feature = str(args, "feature_id");
                return feature != null && made.contains(feature) ? feature : null;
            }
            default:
                return null;
        }
    }

    /**
     * Which of this wish's creatures a {@code modify_mob} target names: its id, its tag
     * ({@code wishmob_<id>}) or a handle minted for it ({@code @<id>}, {@code @<id>2}). Null for
     * anything else, {@code @last} and a type word included: those can name somebody else's mob.
     * Pure.
     */
    static String madeTarget(String target, Collection<String> made) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String t = target.strip();
        boolean handle = t.startsWith("@");
        if (handle) {
            t = t.substring(1);
        }
        if (t.startsWith("wishmob_")) {
            t = t.substring("wishmob_".length());
        }
        if (made.contains(t)) {
            return t;
        }
        // A second handle for the same creature carries a number ("@seahorse2"); an id may end in
        // one too, so the exact id is tried first.
        String first = handle ? t.replaceAll("\\d+$", "") : t;
        return made.contains(first) ? first : null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().strip() : null;
    }
}
