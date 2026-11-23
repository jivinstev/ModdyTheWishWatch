package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import net.minecraft.core.BlockPos;

/**
 * What has already been built, and <b>where</b>.
 *
 * <p><b>Why this exists.</b> A child looked at a castle the agent had just built for him and said:
 * <i>"that's a cool castle in the sky, can you add a lighthouse on top of it?"</i> — and got a
 * second, entirely new castle somewhere else. Nothing was broken; the agent simply had no way to
 * learn that the castle existed, let alone where it stood. It could see mobs (handles) and its own
 * surroundings, but the things it had <em>built</em> were invisible to it the moment the turn ended.
 *
 * <p>So this reports every saved plan with its real anchor. With a position, "on top of it" becomes
 * arithmetic: read the anchor, add the height, build the lighthouse there. Extending what the child
 * is proud of is almost always the right answer — replacing it is almost always the wrong one, and
 * it is the answer an agent gives when it cannot see.
 */
public final class ListBuildsTool implements McpTool {

    @Override
    public String name() {
        return "list_builds";
    }

    @Override
    public String description() {
        return "List the buildings already made for this child, with their position, size and "
                + "what they are made of. ALWAYS call this when they refer to something that "
                + "already exists — \"the castle\", \"it\", \"that tower\" — before you build "
                + "anything. To CHANGE one: call inspect_build to see its shapes, then "
                + "build_structure with the same id, start_from set to that id, and only the "
                + "shapes that change. Do NOT build a separate structure next to it — \"put a "
                + "dome on it\" means the castle has a dome, not that the castle now has a "
                + "neighbour.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        try {
            return ToolResult.ok(AgentBridge.onServer(() -> {
                var ids = BuildRegistry.ids();
                if (ids.isEmpty()) {
                    return "Nothing has been built yet."
                            + com.moddingfromamod.wish.WishLessons.appendTo("building");
                }
                StringBuilder sb = new StringBuilder();
                for (String id : ids) {
                    BuildPlan plan = BuildRegistry.get(id);
                    Long anchor = BuildRegistry.anchorOf(id);
                    sb.append("- id=").append(id);
                    if (plan != null) {
                        // WHAT IT IS, not just that it exists. "22 shapes, 19661 blocks" is a fact
                        // about a building nobody can picture, and a refinement asked against it
                        // was answered by building a second building in every arm-B ladder cell
                        // measured. The materials are the cheap half of the fix; the shapes
                        // themselves are in inspect_build.
                        var f = com.moddingfromamod.builder.BuildFingerprint.of(plan);
                        sb.append(" name=\"").append(plan.name()).append('"')
                          .append(" shapes=").append(plan.shapes().size())
                          .append(" blocks=").append(f.volume())
                          .append(" size=").append(f.span()).append("wide/")
                          .append(f.height()).append("tall");
                        if (!f.palette().isEmpty()) {
                            // "+N more" rather than a bare list: three of eleven blocks reads as
                            // the whole palette, and an agent deciding whether a building already
                            // uses glass would conclude it does not.
                            int show = Math.min(3, f.palette().size());
                            sb.append(" madeOf=")
                              .append(String.join(",", f.palette().subList(0, show)));
                            if (f.palette().size() > show) {
                                sb.append("+").append(f.palette().size() - show).append("more");
                            }
                        }
                    }
                    if (anchor != null && BuildRegistry.standing(id)) {
                        BlockPos at = BlockPos.of(anchor);
                        sb.append(" builtAt=(").append(at.getX()).append(',')
                          .append(at.getY()).append(',').append(at.getZ()).append(')');
                    } else if (anchor != null) {
                        sb.append(" builtAt=(taken down)");
                    } else {
                        // "never placed" was not true of one built before positions were
                        // saved — we simply do not know where it is.
                        sb.append(" builtAt=(not standing anywhere I know of in this world)");
                    }
                    sb.append('\n');
                }
                return sb + com.moddingfromamod.wish.WishLessons.appendTo("building");
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
