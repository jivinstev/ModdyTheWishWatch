package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.BuildFingerprint;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import net.minecraft.core.BlockPos;

/**
 * <b>What a building actually IS, so it can be edited rather than replaced.</b>
 *
 * <p>The buildings twin of {@code inspect_creature}, and it exists for the same measured reason.
 * {@code inspect_creature} reported no colour at all, so <i>"keep him orange"</i> was unknowable and
 * a second worker simply invented a palette. {@code list_builds} reported
 * <b>{@code id, name, 22 shapes, 19661 blocks, at x,y,z}</b> — which says a building exists and
 * nothing whatever about what it looks like or what it is made of.
 *
 * <p>What that cost, measured on the refinement ladder: asked to <i>"put a big glass dome on top of
 * it"</i>, a worker that had not built the castle built <b>a second building</b> beside it. Four
 * wishes later the child had four buildings instead of one castle. From the agent's position that
 * was the conservative choice — it could not describe the castle, so it could not safely rewrite it.
 *
 * <p>So this reports the plan the registry really holds: the block palette weighted by what is
 * standing, the real dimensions, and <b>the shapes themselves, by name</b>, which is what
 * {@code build_structure}'s {@code start_from} needs in order to address one.
 */
public final class InspectBuildTool implements McpTool {

    /** Enough for a detailed castle without turning one tool result into the whole context. */
    private static final int MAX_SHAPES_SHOWN = 40;

    @Override
    public String name() {
        return "inspect_build";
    }

    @Override
    public String description() {
        return "Read a building you already made — its real size, what it is made of, and every "
                + "shape in it by name. CALL THIS BEFORE CHANGING ONE. To add to it or alter it, "
                + "pass build_structure the same id with start_from set to that id and send ONLY "
                + "the shapes that change: a shape whose name matches one here replaces it where "
                + "it stands, a new name is added on top, and everything you do not mention is "
                + "kept. Building a second structure beside it is almost never what the child "
                + "meant.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "The building's id, as shown by list_builds.");
        return Schemas.required(s, "id");
    }

    /**
     * <b>The shape whose top is highest — the one "make it taller" has to move.</b>
     *
     * <p>Measured on the buildings ladder, where it is the whole of the owner-vs-stranger
     * gap. Asked to <i>"make it much taller"</i>, a worker that had not built the castle raised the
     * keep from 28 to 56 and the towers from 36 to 72 — a real, correct-looking edit — and the
     * building's height did not move by one block. It was 98 before and 98 after, three cells out
     * of four, byte-identical.
     *
     * <p>The reason is that the tallest thing was {@code glass_dome}, reaching y=98, added by the
     * PREVIOUS rung's wish. The stranger raised what it would itself have built and left the one
     * shape that was actually setting the height. The owner built the dome, so the owner knew.
     *
     * <p>Nothing was hidden: the dome was in this tool's own shape list all along, twenty-seven
     * entries into a single line, with its {@code dy} and its height. Being present is not the same
     * as being findable — which is this repo's oldest lesson wearing new clothes, and the same shape
     * as {@code inspect_creature} listing a palette nobody could act on until it said "KEEP THESE"
     *.
     *
     * <p>So the height and the shape that owns it are reported in one sentence. Ties go to the
     * first shape placed, which is stable and arbitrary — the point is to name A tallest, not to
     * adjudicate between two.
     */
    static BuildShape tallestShape(BuildPlan plan) {
        BuildShape best = null;
        int bestTop = Integer.MIN_VALUE;
        for (BuildShape s : plan.shapes()) {
            if (AIR.equals(s.block())) {
                continue;   // a carved-out space is not what makes a building tall
            }
            int top = s.dy() + s.height();
            if (top > bestTop) {
                bestTop = top;
                best = s;
            }
        }
        return best;
    }

    /** Air removes rather than fills, so it can never be the thing setting the height. */
    private static final String AIR = "minecraft:air";

    @Override
    public ToolResult call(JsonObject args) {
        String id = args != null && args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                : "";
        if (id.isEmpty()) {
            return ToolResult.error("Which building? Pass the id list_builds showed.");
        }
        try {
            return AgentBridge.onServer(() -> {
                BuildPlan plan = BuildRegistry.get(id);
                if (plan == null) {
                    // Name what DOES exist. "Not found" plus the real list is one round trip; "not
                    // found" alone is two, on a clock a child is watching.
                    return ToolResult.error("There is no building called '" + id + "'. There is: "
                            + (BuildRegistry.ids().isEmpty() ? "nothing yet"
                               : String.join(", ", BuildRegistry.ids())));
                }
                BuildFingerprint.Fingerprint f = BuildFingerprint.of(plan);
                StringBuilder sb = new StringBuilder();
                sb.append(id).append("  \"").append(plan.name()).append("\"\n");
                sb.append("  ").append(f.volume()).append(" blocks standing, ")
                  .append(f.height()).append(" tall, ").append(f.span()).append(" across, ")
                  .append(f.rooms()).append(" room(s)\n");
                // WHICH shape is the tall one — measured, because "make it much taller" failed
                // without it. See tallestShape().
                BuildShape tallest = tallestShape(plan);
                if (tallest != null && tallest.name() != null && !tallest.name().isBlank()) {
                    sb.append("  the TALLEST thing in it is '").append(tallest.name())
                      .append("', reaching y=").append(tallest.dy() + tallest.height())
                      .append(" — to make the building taller you have to raise THAT one; "
                              + "raising anything shorter leaves the building exactly as tall as "
                              + "it was.\n");
                }
                sb.append("  made of: ")
                  .append(f.palette().isEmpty() ? "(nothing)" : String.join(", ", f.palette()))
                  .append("   (share of the blocks you can see)\n");
                Long anchor = BuildRegistry.anchorOf(id);
                if (anchor != null && BuildRegistry.standing(id)) {
                    BlockPos at = BlockPos.of(anchor);
                    sb.append("  standing at ").append(at.getX()).append(',')
                      .append(at.getY()).append(',').append(at.getZ()).append('\n');
                } else {
                    // say WHY there is no position — never a guessed one.
                    sb.append("  not standing here: ").append(BuildRegistry.whyNotHere(id)).append('\n');
                }
                // where things go in it — a boss on the throne, a dragon on the roof.
                // and the ones every building has without marking them, for anything.
                int[] toward = BuildRegistry.towardOf(id);
                sb.append("  spots (put anything there with at:\"").append(id)
                  .append(":<spot>\" — a creature, a thing, an event, a building beside it, a "
                          + "picture on a wall): ");
                boolean firstSpot = true;
                for (var spot : com.moddingfromamod.builder.BuildSpots.all(plan, toward[0], toward[1])) {
                    sb.append(firstSpot ? "" : "; ").append(spot.name()).append(" at ")
                      .append(spot.dx()).append(',').append(spot.dy()).append(',').append(spot.dz())
                      .append(spot.facing().isEmpty() ? "" : " facing " + spot.facing())
                      .append(plan.spots().contains(spot) ? "" : " (automatic)")
                      .append(com.moddingfromamod.builder.BuildAutoSpots.AROUND.equals(spot.name())
                              ? " (a ring " + (int) com.moddingfromamod.builder.BuildAutoSpots.aroundRadius(plan)
                                      + " blocks out)" : "");
                    firstSpot = false;
                }
                sb.append('\n');
                sb.append("  shapes, in the order they are placed (a later one overwrites an "
                        + "earlier one):\n");
                int shown = 0;
                for (BuildShape s : plan.shapes()) {
                    if (shown++ >= MAX_SHAPES_SHOWN) {
                        sb.append("    …and ").append(plan.shapes().size() - MAX_SHAPES_SHOWN)
                          .append(" more.\n");
                        break;
                    }
                    sb.append("    ")
                      .append(s.name() == null || s.name().isBlank()
                              ? "(unnamed)" : s.name())
                      .append("  ").append(s.kind().name().toLowerCase(java.util.Locale.ROOT))
                      .append(' ').append(s.width()).append('x').append(s.height())
                      .append('x').append(s.depth())
                      .append(" at ").append(s.dx()).append(',').append(s.dy()).append(',')
                      .append(s.dz())
                      .append(s.anchor() == BuildShape.Anchor.CENTRE ? " (centre)" : "")
                      .append(s.hollow() ? " hollow " : " solid ")
                      .append(s.block()).append('\n');
                }
                return ToolResult.ok(sb.toString());
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
