package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.rules.WorldRule;
import com.moddingfromamod.rules.WorldRules;
import com.moddingfromamod.wish.CommandGuard;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;

/**
 * <b>"When this happens, do that", forever, in one call</b> (wave 2): a mob appears, dies
 * or lands; he jumps, sneaks or sprints; something comes near him; he comes near a block; the sun
 * comes up or goes down; the rain starts or stops; something is hit by a thrown thing; he catches a
 * fish; something explodes; every few seconds.
 *
 * <p>Before this, each of those was a hand-written tick-loop datapack. {@link WorldRules} fires them
 * from Minecraft's own events instead, so there is no scoreboard polling to get wrong, and a rule
 * the child no longer wants is one {@code remove}.
 *
 * <p>Commands are checked NOW, not when the rule first fires: every one must pass
 * {@link CommandGuard} and parse. A rule's commands run later, when nobody is watching the tool
 * output, so a typo caught here is the only time the agent can hear about it.
 */
public final class SetRuleTool implements McpTool {

    /** The parts this tool IS, from {@link WorldRule#PARTS}; read by tools/parts-coverage.py. */
    public static final Set<String> PARTS = WorldRule.PARTS;

    @Override
    public String name() {
        return "set_rule";
    }

    @Override
    public String description() {
        return "A new RULE for his world, forever: WHEN something happens, DO something. ONE CALL, "
                + "and it keeps working after the wish, after he dies and after the world reloads. "
                + "Use it for anything shaped 'when X, Y' or 'every so often, Y': a kind of mob "
                + "appearing (mob_spawns), dying (mob_dies) or landing (lands); him jumping, "
                + "sneaking or sprinting; a mob coming near him (near_player); him coming near or "
                + "standing on a block (near_block); sunrise or sunset; rain starting or stopping; "
                + "a thrown thing hitting something (projectile_hits); a fish caught; an explosion; "
                + "or `every` N seconds. Do NOT write a datapack or a tick loop for these. "
                + "`do` is a list of commands run AS the thing it happened to and AT it, so @s and "
                + "~ ~ ~ mean that mob (or him). Two things commands cannot do are fields: `push` "
                + "shoves it (up / away / forward, 1.0 is a big launch) and `grow` bone-meals plants "
                + "around the spot. An explosion rule can be `harmless`: it still goes off but "
                + "breaks nothing and hurts nobody. `who` picks the mob ('zombie', a tag, 'any') or "
                + "'player'; `only` limits it to day, night, rain or clear; `chance` makes it "
                + "sometimes; `block` on any trigger means 'only while standing on this'. The same "
                + "`name` again changes a rule; `remove: true` takes it away; `list: true` shows the "
                + "rules and the last error each one hit. A rule that summons pauses while 120 of that "
                + "kind are already near the spot, so it cannot flood his world. A rule and a rule you watched fire are "
                + "different claims: make it happen once before telling him it works.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "when", "string", "REQUIRED (unless remove/list). One of: "
                + WorldRule.whenList() + ".");
        Schemas.prop(s, "who", "string", "Which one it happens to: a mob id ('cow', "
                + "'minecraft:creeper'), a tag ('#minecraft:skeletons'), 'any' (every mob), "
                + "'player', or for a timed rule 'world' (once, not once per player). Timed rules "
                + "with a mob run as EACH of those mobs near him. A wished creature is NOT a mob id (its "
                + "type is the mob it is built on): name it by its tag, 'tag=wishmob_<species>' — on a world "
                + "that keeps a wish to his own things, that is how a rule for HIS pets passes.");
        Schemas.list(s, "do", "Commands, no leading slash, run as and at the thing "
                + "(@s, ~ ~ ~).", WorldRule.MAX_COMMANDS);
        Schemas.prop(s, "push", "object", "A shove: {\"up\": 1.2} launches, {\"away\": 1.5} knocks "
                + "back from what caused it, {\"forward\": 1} dashes the way it faces. Blocks per tick, "
                + "up to " + WorldRule.Push.MAX + ".");
        Schemas.prop(s, "grow", "integer", "Bone-meal plants within this many blocks (1-"
                + WorldRule.MAX_GROW + "): crops grow, grass sprouts flowers.");
        Schemas.prop(s, "harmless", "boolean", "Explosions only: it still booms, breaks no blocks "
                + "and hurts nobody.");
        Schemas.prop(s, "block", "string", "near_block: the block he comes near. Any other trigger: "
                + "only while the thing stands on this block ('slime_block', '#minecraft:beds').");
        Schemas.prop(s, "projectile", "string", "projectile_hits: which thrown thing ('snowball', "
                + "'arrow', 'egg'). Default any.");
        Schemas.prop(s, "radius", "integer", "near_player / near_block: how close, in blocks (0-"
                + WorldRule.MAX_RADIUS + "; near_block 0 = standing on it).");
        Schemas.prop(s, "seconds", "integer", "every: how often, in seconds (default 10).");
        Schemas.prop(s, "chance", "number", "0.01 to 1; 'sometimes' is about 0.1. Default 1.");
        Schemas.prop(s, "only", "string", "day, night, rain or clear.");
        Schemas.prop(s, "name", "string", "A short name, e.g. 'slime_launch'. The same name later "
                + "changes this rule.");
        Schemas.prop(s, "remove", "boolean", "True = take the rule with this `name` away.");
        Schemas.prop(s, "list", "boolean", "True = list the rules in this world.");
        Schemas.prop(s, "where", "string", "On a shared world of islands: \"his_island\" makes the rule fire on "
                + "HIS island only — the way to grant 'zombies on my island don't burn'. A rule there must be scoped: "
                + "this, or `who` set to one of his own creatures' tags (his pets). Ignored where there are no islands.");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        boolean remove = flag(args, "remove");
        boolean list = flag(args, "list");
        final WorldRule rule;
        if (remove || list) {
            rule = null;
        } else {
            try {
                rule = WorldRule.parse(args);
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                return ToolResult.error(e.getMessage());
            }
            for (String c : rule.commands()) {
                if (!CommandGuard.isAllowed(c)) {
                    return ToolResult.error("That command isn't allowed in a rule: " + c);
                }
            }
        }
        String name = WorldRule.clean(args.has("name") ? args.get("name").getAsString() : "");
        try {
            return AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                if (list) {
                    return ToolResult.ok(listing(server));
                }
                try {
                    if (remove) {
                        if (name.isEmpty()) {
                            return ToolResult.error("Say which rule to remove with its `name`.");
                        }
                        com.moddingfromamod.world.StandalonePacks.forget("rule", name);   //
                        if (!WorldRules.remove(server, name)) {
                            return ToolResult.error("There is no rule called '" + name + "'. "
                                    + listing(server));
                        }
                        return ToolResult.ok("Removed the rule '" + name + "'. It no longer happens.");
                    }
                    // a `who` that names no entity type is a rule that never fires.
                    String badWho = WorldRule.whoProblem(rule.who(),
                            id -> {
                                var rl = net.minecraft.resources.Identifier.tryParse(id);
                                return rl != null && net.minecraft.core.registries.BuiltInRegistries
                                        .ENTITY_TYPE.containsKey(rl);
                            },
                            com.moddingfromamod.species.SpeciesRegistry::has);
                    if (badWho != null) {
                        return ToolResult.error(badWho);
                    }
                    String bad = parseProblems(server, rule.commands());
                    if (bad != null) {
                        return ToolResult.error(bad);
                    }
                    WorldRules.put(server, rule);
                    registerScope(rule, args);
                } catch (IllegalStateException e) {
                    return ToolResult.error(e.getMessage());
                }
                return ToolResult.ok("SET. " + rule.describe() + " (rule '" + rule.name() + "'; "
                        + WorldRules.all(server).size() + " rule(s) in this world.) Make it happen "
                        + "once and look, then list: true shows whether its commands hit an error.");
            }, 30_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * Parse every command the way it will run, as the child and at him. Selectors resolve later, so
     * this catches spelling, not targets; that is still every typo, which is what a later firing
     * would otherwise swallow.
     */
    /**
     * on a world of bases whose rules allow scoped rules, remember whose this rule is and its scope — so it
     * fires only on his island ({@code where: his_island}), and so it has the world's lifetime for a standalone
     * rule ({@link com.moddingfromamod.world.StandalonePacks}). BaseReach already refused an unscoped one.
     */
    static void registerScope(WorldRule rule, JsonObject args) {
        var rules = com.moddingfromamod.world.WorldRulesService.loadedOrNull();
        var bases = com.moddingfromamod.world.WorldModuleHost.liveBases();
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (rules == null || bases == null || run == null || !rules.baseReach() || rules.allowedScopes().isEmpty()) {
            return;
        }
        String where = args.has("where") && !args.get("where").isJsonNull() ? args.get("where").getAsString() : "";
        String scope = "his_island".equalsIgnoreCase(where) || "my_island".equalsIgnoreCase(where) ? "his_island" : "pets";
        com.moddingfromamod.world.StandalonePacks.register("rule", rule.name(), run.playerId(),
                com.moddingfromamod.world.StandalonePacks.of(rules), scope);
    }

    static String parseProblems(MinecraftServer server, List<String> commands) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        ServerPlayer p = players.isEmpty() ? null : players.get(0);
        CommandSourceStack stack = new CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
                p == null ? net.minecraft.world.phys.Vec3.ZERO
                        : p.position(), Vec2.ZERO, server.overworld(), 2, "rule", Component.literal("rule"),
                server, p);
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            var parsed = server.getCommands().getDispatcher().parse(c, stack);
            if (!parsed.getExceptions().isEmpty()) {
                parsed.getExceptions().values().forEach(e -> out.add(c + " → " + e.getMessage()));
            } else if (parsed.getReader().canRead()) {
                out.add(c + " → I didn't understand '" + parsed.getReader().getRemaining().trim() + "'");
            }
        }
        return out.isEmpty() ? null : "Not set: a command would fail every time the rule fires. "
                + String.join("; ", out);
    }

    static String listing(MinecraftServer server) {
        List<WorldRule> all = WorldRules.all(server);
        if (all.isEmpty()) {
            return "No rules in this world yet.";
        }
        StringBuilder b = new StringBuilder(all.size() + " rule(s):");
        for (WorldRule r : all) {
            b.append("\n- ").append(r.name()).append(": ").append(r.describe());
            String err = WorldRules.lastError(r.name());
            if (err != null) {
                b.append(" LAST ERROR: ").append(err);
            }
        }
        return b.toString();
    }

    private static boolean flag(JsonObject a, String k) {
        return a.has(k) && a.get(k).isJsonPrimitive() && a.get(k).getAsBoolean();
    }
}
