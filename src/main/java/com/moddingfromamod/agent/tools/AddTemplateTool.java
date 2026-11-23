package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.CommandGuard;
import com.moddingfromamod.wish.LearnedTemplates;

/**
 * <b>The point of the whole project.</b> Teaching the watch a wish, so next time the child asks for
 * it the answer is instant, offline and free — no agent, no waiting, no cost.
 *
 * <p>Fulfilling a wish with {@code run_command} helps once. Adding a template helps forever, which
 * is why the tool description pushes the agent to do this every time.
 */
public final class AddTemplateTool implements McpTool {

    @Override
    public String name() {
        return "add_template";
    }

    @Override
    public String description() {
        return "Teach the watch a new wish so it can grant it INSTANTLY next time, with no help "
                + "from you. Do this whenever you work out how to grant something new — it is the "
                + "most valuable thing you can do. Give the keywords a child might say and the "
                + "commands that make it happen. Commands must have no leading slash and are "
                + "checked against the safe list, then TRIED: one Minecraft would refuse or "
                + "silently half-ignore is not saved. Granted it with modify_mob? Teach "
                + "`wishsummon <mob> <the same JSON you gave modify_mob>`, e.g. "
                + "`wishsummon hybrid-aquatic:great_white_shark {\"scale\":4,\"health\":300}` — never "
                + "hand-written summon NBT. Saved to the world and used immediately.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "Short unique name, e.g. 'rainbow_bridge'.");
        Schemas.prop(s, "all", "array",
                "[all|any REQUIRED — give at least one] Words that must ALL appear in "
                + "the wish, e.g. ['dragon'].");
        Schemas.prop(s, "any", "array",
                "[all|any REQUIRED — give at least one] Words where at least ONE must "
                + "appear, e.g. ['turtle_king','spiky'].");
        Schemas.prop(s, "commands", "array", "Commands to run, in order, no leading slash.");
        Schemas.prop(s, "say", "string", "Friendly line to tell the child when it works.");
        // The BUTTON'S PICTURE. Without one the panel keyword-scans the label, and an invented id
        // like `sparkly_cheese_island` matches no rule at all — so it, and every other unmatched
        // recipe, draws the same amethyst shard and the row stops being readable as a menu.
        Schemas.prop(s, "icon", "string",
                "Optional: a vanilla item id for this wish's button, e.g. "
                + "'minecraft:cake' or 'minecraft:cow_spawn_egg'. Pick something a child would "
                + "recognise as this wish at a glance; without it the button's picture is guessed "
                + "from the words and often lands on a generic one.");
        Schemas.prop(s, "repeat", "integer", "Optional: run the command list this many times.");
        Schemas.prop(s, "countable", "boolean",
                "Optional: let a number in the wish ('spawn 12 pigs') set the repeat count.");
        Schemas.prop(s, "maxRepeat", "integer", "Optional cap when countable is true.");
        Schemas.prop(s, "note", "string",
                com.moddingfromamod.provenance.ProvenanceContext.NOTE_HINT);
        Schemas.required(s, "id", "commands", "say", "note");
        // Machine-readable "one of these two", which `required` cannot say. A real
        // wish was lost to this being prose only -- see Schemas.requireOneOf.
        return Schemas.requireOneOf(s, "all", "any");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("id") ? args.get("id").getAsString() : "";
        if (id.isBlank()) {
            return ToolResult.error("A template needs an id.");
        }
        if (!args.has("commands") || !args.get("commands").isJsonArray()
                || args.getAsJsonArray("commands").isEmpty()) {
            return ToolResult.error("A template needs at least one command.");
        }
        boolean hasMatcher = (args.has("all") && args.get("all").isJsonArray()
                        && !args.getAsJsonArray("all").isEmpty())
                || (args.has("any") && args.get("any").isJsonArray()
                        && !args.getAsJsonArray("any").isEmpty());
        if (!hasMatcher) {
            return ToolResult.error(
                    "A template needs keywords or it would match EVERY wish. Pass `all` (every word must appear) or `any` (at least one must), or both. Call again with the same id/commands/say plus one of them.");
        }
        // Replay a datapack the ROBUST way. If the agent teaches a datapack feature with a raw
        // `function <id>:<entry>`, it runs with no reload guard — and calling a function while the
        // datapack is still hot-reloading throws "This function should not run" (a real Gate D
        // failure). /wishdatapack ensures the pack is loaded first, then runs the entry elevated, so
        // rewrite to it whenever the referenced namespace is a saved datapack.
        com.google.gson.JsonArray commands = args.getAsJsonArray("commands");
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i).isJsonPrimitive() ? commands.get(i).getAsString() : "";
            String robust = rewriteDatapackFunction(cmd);
            if (!robust.equals(cmd)) {
                commands.set(i, new com.google.gson.JsonPrimitive(robust));
            }
        }
        // A HAND-WRITTEN modify_mob IS A TOOL CALL, NOT A COMMAND. Fold it into the summon
        // it changes as `wishsummon <mob> {spec}` — the replay that applies modify_mob's own code —
        // or refuse naming that line. Refused bare ("Command not allowed"), the agent dropped the
        // line and the button's pet came back without follow/stay.
        java.util.List<String> asTaught = new java.util.ArrayList<>();
        for (var el : commands) {
            asTaught.add(el.isJsonPrimitive() ? el.getAsString() : "");
        }
        var folded = com.moddingfromamod.wish.ModifyMobFold.fold(asTaught);
        if (folded.refusal() != null) {
            return ToolResult.error(folded.refusal());
        }
        if (!folded.notes().isEmpty()) {
            com.google.gson.JsonArray rewritten = new com.google.gson.JsonArray();
            folded.commands().forEach(rewritten::add);
            args.add("commands", rewritten);
            commands = rewritten;
        }
        // Refuse unsafe commands at teach time, not just at run time — a bad template would
        // otherwise sit in the world failing silently every time the child used it.
        for (var el : commands) {
            String cmd = el.isJsonPrimitive() ? el.getAsString() : "";
            if (!CommandGuard.isAllowed(cmd)) {
                return ToolResult.error("Command not allowed in a template: " + cmd);
            }
            // ANOTHER PLAYER IS A PLACE TO GO, NEVER A SUBJECT: a button replays for whoever
            // presses it, so only @s is self and any name in it is somebody else. Checked again at
            // every press (WishExecutor.runOne), where the world's player_reach may differ.
            var reach = com.moddingfromamod.agent.PlayerReach.check(cmd,
                    com.moddingfromamod.agent.PlayerReach.Who.presser(
                            com.moddingfromamod.world.PlayerVisits.knownNames()));
            if (!com.moddingfromamod.agent.PlayerReach.allows(reach,
                    com.moddingfromamod.world.PlayerVisits.mode())) {
                com.moddingfromamod.agent.ActionLog.reach(name(), "refused",
                        reach.refused() ? reach.why() : com.moddingfromamod.agent.PlayerReach.WHY_REACH_OFF,
                        reach.other(), cmd, 0);
                return ToolResult.error("NOT SAVED — " + cmd + "\n" + com.moddingfromamod.agent.PlayerReach
                        .refusal(reach, com.moddingfromamod.world.WorldRulesService.current().workshop())
                        + " In a button, the child who presses it is @s.");
            }
        }
        // TRY IT BEFORE SAVING IT. A template is the agent's RE-EXPRESSION of what worked,
        // never a recording, and nothing used to run it before the child pressed the button — which
        // is how a summon whose attributes Minecraft ignores became Steve's ordinary "Great Big Shark"
        // on every re-wish. CommandTrial parses every command and loads a summon's NBT into a
        // throwaway entity, so what Minecraft would refuse or silently drop is refused HERE.
        java.util.List<String> toTry = new java.util.ArrayList<>();
        for (var el : commands) {
            toTry.add(el.isJsonPrimitive() ? el.getAsString() : "");
        }
        try {
            java.util.List<String> problems = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                var level = server.overworld();
                return com.moddingfromamod.wish.CommandTrial.problems(server, level,
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getSharedSpawnPos()),
                        toTry);
            });
            if (!problems.isEmpty()) {
                return ToolResult.error("NOT SAVED — I tried these commands and Minecraft would not do "
                        + "all of them, so the button would quietly do LESS than this wish did:\n - "
                        + String.join("\n - ", problems)
                        + "\nFix those commands and call add_template again. If you granted this with "
                        + "modify_mob, teach `wishsummon <mob> <the same JSON you gave modify_mob>` "
                        + "instead of summon NBT — it replays exactly what worked.");
            }
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        try {
            String summary = AgentBridge.onServer(() ->
                    LearnedTemplates.addAndSave(AgentBridge.serverOrNull(), args));
            return ToolResult.ok(folded.notes().isEmpty() ? summary
                    : summary + "\n(I " + String.join("; ", folded.notes()) + ".)");
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * Rewrite {@code function <id>:<entry>} to {@code wishdatapack <id> <entry>} when {@code <id>} is
     * a saved CBScript datapack — so a datapack button always replays through the guarded, reload-safe
     * command instead of a bare {@code /function} that can hit "This function should not run" mid-reload.
     * Any other command (including a {@code /function} into a namespace that isn't a datapack) is left
     * untouched. Package-visible for unit testing.
     */
    static String rewriteDatapackFunction(String cmd) {
        if (cmd == null) {
            return "";
        }
        String t = cmd.strip();
        if (!t.startsWith("function ")) {
            return cmd;
        }
        String ref = t.substring("function ".length()).strip();
        int colon = ref.indexOf(':');
        if (colon <= 0) {
            return cmd;
        }
        String id = ref.substring(0, colon).strip();
        String entry = ref.substring(colon + 1).strip();
        if (entry.contains(" ")) {
            return cmd;   // a `function … with …` macro call — leave it alone
        }
        if (com.moddingfromamod.datapack.DatapackStore.exists(id)
                && entry.matches("[a-z][a-z0-9_/]*")) {
            return "wishdatapack " + id + " " + entry;
        }
        return cmd;
    }
}
