package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.marshal.Spot;

/**
 * <b>The reach rule: on a world with bases, a wish lands on its caster's base</b> (lifted
 * behind {@link Bases} — it was {@code isle.IslandReach}, and speaks to no island now) —
 * Minecraft-free, so the rule and its per-tool table are Gate A tests, and the one place it runs
 * ({@code McpProtocol}'s {@code tools/call}, beside {@code PlanFirst} and {@code SafetyPlanGate})
 * only asks it.
 *
 * <p>A shared world can be ONE world shared by every child, each on their own island. Without this, a
 * wish is aimed wherever the watch points and built wherever the tools put it — so one child's
 * castle could go up on another's island, or a command could reach across the void. The rule:
 * <ul>
 *   <li>a wish's <b>anchor</b> (its locked spot, where every build tool lands) must be inside the
 *       caster's island REACH — the cell less a margin wide enough for the biggest build;</li>
 *   <li>every place a call names or implies (a child-relative offset, a radius around him, an
 *       absolute x/y/z, every coordinate a command's grammar shows) must be inside the caster's
 *       island CELL;</li>
 *   <li>a tool whose effect is the whole world (a rule, a datapack, a crafting recipe, drops) is
 *       refused outright: it would land on every island at once.</li>
 * </ul>
 *
 * <p><b>Which coordinates count is decided per tool</b>, in {@link #RULES}, and
 * {@code BaseReachTest} fails a worker tool with no entry — the same "a new tool with no
 * decision" gate {@code PlanFirst} and {@code UndoContract} have. A refusal names the island's
 * square and what to do, so the agent can act on it rather than retry.
 *
 * <p><b>What it does not cover, said out loud</b>:
 * <ul>
 *   <li>{@code run_command} is read by {@link CommandCoords}: the positions the command grammar
 *       shows plus where it runs — never a position inside NBT/JSON, never where a thing goes
 *       afterwards, never a modded command's own coordinates;</li>
 *   <li>things that MOVE after they are made: a creature walks off, a thrown item flies — the rule places them, it does not fence them in;</li>
 *   <li>tools acting on an EXISTING artifact ({@link Kind#ARTIFACT}) are judged by ownership, not
 *       place: the artifact was placed under this rule when it was made;</li>
 *   <li>a call with no wish bound is not judged (like {@code PlanFirst}): there is no caster.</li>
 * </ul>
 */
public final class BaseReach {

    private BaseReach() {
    }

    /** What a tool's effect is placed by. */
    public enum Kind {
        /** Reads, talks or photographs: changes nothing anyone stands on. */
        NONE,
        /** Changes only the child himself or his inventory: lands on no island. */
        SELF,
        /** Lands at the wish's locked anchor, or at child-relative offsets when those are given. */
        ANCHORED,
        /** Lands around the child: his position plus the named offsets or radius. */
        CHILD_RELATIVE,
        /** Names absolute coordinates; with none, it is ANCHORED. */
        ABSOLUTE,
        /** Acts on something he already made, by id: judged by ownership, placed when it was made. */
        ARTIFACT,
        /** Its destination is resolved inside the tool (take_me_to); the tool checks it. */
        DESTINATION,
        /**
         * Changes every base at once: refused where the world's rules deny its effect
         * ({@link WorldEffect#ofTool}); on a world with bases and no rules file, always.
         */
        WORLD_WIDE,
        /** A command line: {@link CommandCoords}. */
        COMMAND
    }

    /**
     * One tool's decision. {@code fields} are the argument names whose values are distances from
     * the child (summed as a radius); {@code defaultRadius} is used for them when absent.
     */
    public record Rule(Kind kind, List<String> fields, int defaultRadius, String why) {
        static Rule of(Kind kind, String why) {
            return new Rule(kind, List.of(), 0, why);
        }

        static Rule of(Kind kind, int defaultRadius, String why, String... fields) {
            return new Rule(kind, List.of(fields), defaultRadius, why);
        }
    }

    /** Every worker tool, and which of its coordinates count. {@code BaseReachTest} keeps it whole. */
    public static final Map<String, Rule> RULES = Map.ofEntries(
            // ── reading, talking, the wish's own bookkeeping ─────────────────────────────────────
            Map.entry("await_wish", Rule.of(Kind.NONE, "waits for work")),
            Map.entry("list_wishes", Rule.of(Kind.NONE, "reads")),
            Map.entry("ask_which", Rule.of(Kind.NONE, "asks the child who wished; changes nothing")),
            Map.entry("find_content", Rule.of(Kind.NONE, "reads")),
            Map.entry("look_around", Rule.of(Kind.NONE, "reads")),
            Map.entry("look_up_character", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_builds", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_build", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_events", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_event", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_templates", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_datapacks", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_items", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_item", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_creatures", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_creature", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_player", Rule.of(Kind.NONE, "reads")),
            Map.entry("list_paintings", Rule.of(Kind.NONE, "reads")),
            Map.entry("inspect_painting", Rule.of(Kind.NONE, "reads")),
            Map.entry("cbscript_help", Rule.of(Kind.NONE, "reads")),
            Map.entry("verify_datapack", Rule.of(Kind.NONE,
                    "tests a datapack; authoring one is refused here, so there is none of his to run")),
            Map.entry("reload_datapacks", Rule.of(Kind.NONE, "re-reads what is already there")),
            Map.entry("plan_wish", Rule.of(Kind.NONE, "a plan changes nothing")),
            Map.entry("finish_building", Rule.of(Kind.NONE, "talks")),
            Map.entry("finish_wish", Rule.of(Kind.NONE, "talks")),
            Map.entry("set_status", Rule.of(Kind.NONE, "talks")),
            Map.entry("notify_player", Rule.of(Kind.NONE, "talks")),
            Map.entry("photograph", Rule.of(Kind.NONE, "the camera looks; it changes nothing")),
            Map.entry("add_template", Rule.of(Kind.NONE, "teaches the watch; replays go through the rule again")),
            // ── the child himself ────────────────────────────────────────────────────────────────
            Map.entry("give_items", Rule.of(Kind.SELF, "into his inventory")),
            Map.entry("modify_player", Rule.of(Kind.SELF, "his own powers")),
            Map.entry("make_item", Rule.of(Kind.SELF, 24,
                    "into his hands; with a recipient, the nearest such creature within 24 blocks of him",
                    "recipient")),
            // ── placed at the wish's anchor ──────────────────────────────────────────────────────
            Map.entry("build_structure", Rule.of(Kind.ANCHORED, 0,
                    "at the locked anchor (offsetY lifts it there), or offsetForward in front of him when "
                            + "nothing was aimed", "offsetForward", "offsetY")),
            Map.entry("sculpt_creature", Rule.of(Kind.ANCHORED, "spawns at the wish's spot unless spawn=false")),
            Map.entry("define_creature", Rule.of(Kind.ANCHORED, "spawns next to him unless spawn=false")),
            Map.entry("stage_event", Rule.of(Kind.ANCHORED,
                    "at the locked anchor; its spread (at most EventScale.GIGANTIC.radius, 64) is inside "
                    + "the 96-block margin every anchor keeps, so it stays on his island")),
            Map.entry("build_mechanism", Rule.of(Kind.DESTINATION,
                    "in the wall of the place its `at` names (his building, or the wall he faces), with its "
                            + "switches within a few blocks of it")),
            Map.entry("paint_surface", Rule.of(Kind.ABSOLUTE,
                    "x/y/z when given (checked), else a board at the anchor")),
            // ── around the child ─────────────────────────────────────────────────────────────────
            Map.entry("conjure_at", Rule.of(Kind.CHILD_RELATIVE, 0,
                    "moves the anchor to forward/up from him: the next build lands there", "forward", "up")),
            Map.entry("modify_mob", Rule.of(Kind.CHILD_RELATIVE, 24,
                    "the mobs within `radius` of him (default 24) that are on his base (KEEPS_TO_BASE, #2806); "
                            + "scope=category is every one of that kind, forever, on every island — refused", "radius")),
            // ── things he already made ───────────────────────────────────────────────────────────
            Map.entry("paint_creature", Rule.of(Kind.ARTIFACT, "his creature's colours")),
            Map.entry("apply_effect", Rule.of(Kind.ARTIFACT,
                    "an ability for his creature by tag; it acts wherever the creature goes")),
            Map.entry("remove_item", Rule.of(Kind.ARTIFACT, "takes away his own")),
            Map.entry("remove_build", Rule.of(Kind.ARTIFACT, "takes away his own")),
            Map.entry("remove_creature", Rule.of(Kind.ARTIFACT, "takes away his own")),
            Map.entry("remove_datapack", Rule.of(Kind.ARTIFACT, "takes away his own")),
            Map.entry("remove_painting", Rule.of(Kind.ARTIFACT, "takes away his own")),
            Map.entry("remove_event", Rule.of(Kind.ARTIFACT, "gives back what his own event changed")),
            Map.entry("remove_mechanism", Rule.of(Kind.ARTIFACT, "puts back what his own mechanism replaced")),
            Map.entry("take_me_to", Rule.of(Kind.DESTINATION,
                    "teleports him to a thing by name: TakeMeToTool refuses a thing off his island")),
            // ── every island at once ─────────────────────────────────────────────────────────────
            Map.entry("set_rule", Rule.of(Kind.WORLD_WIDE,
                    "a rule fires for every matching mob or player in the world, forever")),
            Map.entry("author_datapack", Rule.of(Kind.WORLD_WIDE,
                    "a datapack's functions run world-wide, on every island")),
            Map.entry("change_drops", Rule.of(Kind.WORLD_WIDE, "every mob or block of that kind, everywhere")),
            // ── a command line ───────────────────────────────────────────────────────────────────
            Map.entry("run_command", Rule.of(Kind.COMMAND, "read by CommandCoords")));

    public static final String OVERWORLD = Bases.OVERWORLD;

    /**
     * whose creature a tag names — the tags of the creatures {@code caster} owns. A selector scoped by one of
     * them is his creature, wherever it walked, and counts as on his base ({@link CommandCoords.Area#onHisCreature}).
     * Set by the running mod ({@code species.SpeciesRegistry}); a test or a world with none knows no tags.
     */
    public static volatile java.util.function.Function<UUID, java.util.function.Predicate<String>> hisCreatureTags =
            caster -> tag -> false;

    public static java.util.function.Predicate<String> owned(UUID caster) {
        try {
            var p = caster == null ? null : hisCreatureTags.apply(caster);
            return p == null ? tag -> false : p;
        } catch (RuntimeException e) {
            return tag -> false;
        }
    }

    /**
     * The refusal for this call, or {@code null} to let it run.
     *
     * @param tool   the tool's name
     * @param args   its arguments, after {@code ArgAliases} has normalised the names
     * @param caster the child whose wish this is; null = no wish bound, never judged
     * @param anchor the wish's locked spot, or null
     * @param world  this world's bases; null = no bases here, never judged
     */
    public static String refusal(String tool, JsonObject args, UUID caster, Spot anchor, Bases world) {
        return refusal(tool, args, caster, anchor, world, null);
    }

    /**
     * …under this world's rules. {@code rules} null means none has been read, which on a
     * world with bases is {@link WorldRules#CLOSED} — the earlier behaviour exactly, so a world that has
     * not received its file yet is never looser than it was. A world-wide tool or command is refused
     * only when the rules deny what it changes; the place checks run only when the base-reach rule is
     * on.
     */
    public static String refusal(String tool, JsonObject args, UUID caster, Spot anchor, Bases world,
                                 WorldRules rules) {
        Refusal r = judge(tool, args, caster, anchor, world, rules);
        return r == null ? null : r.words();
    }

    /**
     * <b>The refusal and the effect it enforced</b>, or {@code null} to let the call run: {@link #refusal}'s words for
     * the agent, and, as data, which of this world's effects refused it ({@link Refusal#effect}), so the log counts the
     * catch against its rule and a make with no agent tells the child that rule ({@link WorldRefusal}) without anyone
     * reading the words back. Each place that refuses names its effect here, beside its words.
     */
    public static Refusal judge(String tool, JsonObject args, UUID caster, Spot anchor, Bases world,
                                WorldRules rules) {
        if (world == null || caster == null || tool == null) {
            return null;
        }
        WorldRules in = rules == null ? WorldRules.CLOSED : rules;
        String noun = world.noun();
        Rule rule = RULES.get(tool);
        if (rule == null) {
            // Fail closed: a tool nobody decided about could land anywhere. BaseReachTest keeps
            // this unreachable for every tool the worker is offered.
            return no(null, message(tool, noun, null, "this tool has no " + noun + " decision yet (BaseReach.RULES), "
                    + "so it cannot run on a world with bases"));
        }
        JsonObject a = args == null ? new JsonObject() : args;
        switch (rule.kind()) {
            case NONE, ARTIFACT, DESTINATION -> {
                return null;
            }
            case WORLD_WIDE -> {
                WorldEffect effect = WorldEffect.ofTool(tool);
                if (effect != null && !in.denies(effect)) {
                    return null;                   // this world's rules allow what it changes
                }
                // allow_base_scoped — a pack or a rule scoped to HIS base or HIS creatures is his alone.
                String scoped = scoped(tool, a, caster, world, in);
                if (scoped == null) {
                    return null;
                }
                if (!scoped.isEmpty()) {
                    return no(WorldEffect.ofTool(tool), message(tool, noun, world.baseOf(caster), scoped));
                }
                return no(WorldEffect.ofTool(tool), message(tool, noun, null, tool + " changes the whole world — " + rule.why()
                        + " — so on this world it would land on every child's " + noun + ", not just his. "
                        + "Grant the wish with something placed on his " + noun + " instead (a building, a "
                        + "machine, a creature, an item for him), or tell him this kind of wish is for "
                        + in.workshop()));
            }
            default -> {
            }
        }
        if (rule.kind() == Kind.SELF && (rule.fields().isEmpty() || !has(a, rule.fields().get(0)))) {
            return null;
        }
        if ("modify_mob".equals(tool) && "category".equalsIgnoreCase(str(a, "scope"))
                && in.denies(WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES)) {
            Base b = world.baseOf(caster);
            return no(WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES, message(tool, noun, b, "scope=category changes every mob of that kind, forever, "
                    + "on every " + noun + ". Use scope 'one' or 'group' for the mobs on his " + noun));
        }
        if (rule.kind() == Kind.COMMAND && !in.baseReach()) {
            return commandWorldWide(tool, a, noun, world.baseOf(caster), in);
        }
        if (!in.baseReach()) {
            return null;                           // this world's rules do not fence wishes to a base
        }
        Base island = world.baseOf(caster);
        if (island == null) {
            return no(null, message(tool, noun, null, "he has no " + noun + " yet — it is made the moment he first joins "
                    + "this world. Nothing can be placed until it exists"));
        }
        Region reach = island.reach();
        Bases.Pos child = world.positionOf(caster);

        if (rule.kind() == Kind.SELF) {
            // make_item's recipient: a creature near him
            return nearChild(tool, noun, island, child, rule.defaultRadius(), "the recipient is looked for");
        }
        if (rule.kind() == Kind.CHILD_RELATIVE) {
            double r = radius(a, rule);
            if (KEEPS_TO_BASE.contains(tool)) {
                // the radius is where the tool LOOKS, and the tool keeps the look to his base (fence):
                // what it changes is the creatures inside his base, so what is asked is that he stands on it. Asking
                // whether the whole square round him fits refused every such call on a base smaller than the radius.
                return onBase(tool, noun, island, child, "it acts on the creatures within " + (int) r
                        + " blocks of him that are on his " + noun);
            }
            return nearChild(tool, noun, island, child, r, "it acts within " + (int) r + " blocks of him");
        }
        if (rule.kind() == Kind.ABSOLUTE) {
            if (has(a, "x") && has(a, "z")) {
                double x = num(a, "x");
                double z = num(a, "z");
                if (child != null && !OVERWORLD.equals(child.dimension())) {
                    return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island, "he is not in the overworld, where the " + noun + "s are"));
                }
                if (!reach.contains(x, z)) {
                    return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "(" + (int) x + ", " + (int) z + ") is off his " + noun));
                }
                return null;
            }
            // otherwise: a board at the anchor, as ANCHORED
        }
        if (rule.kind() == Kind.COMMAND) {
            return command(tool, a, noun, island, child, anchor, in);
        }
        // ANCHORED (and ABSOLUTE with no coordinates)
        if (("sculpt_creature".equals(tool) || "define_creature".equals(tool))
                && a.has("spawn") && !a.get("spawn").isJsonNull() && !truthy(a.get("spawn"))) {
            return null;                           // defined, not placed
        }
        boolean offsets = false;
        for (String f : rule.fields()) {
            if (has(a, f)) {
                offsets = true;
            }
        }
        if (anchor == null) {
            // chooseAnchor: with no locked spot it goes in front of him, offsetForward away. With
            // one, an offset never moves it off the aim, so the aimed spot is checked below.
            if (child == null) {
                return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "he is not in the world, and the wish has no aimed "
                        + "spot on his " + noun + ", so it would land at the world spawn — nobody's " + noun));
            }
            if (!OVERWORLD.equals(child.dimension())) {
                return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island, "he is not in the overworld, where the " + noun + "s are"));
            }
            double r = offsets ? radius(a, rule) : 0;
            if (!reach.containsBox(child.x(), child.z(), r)) {
                return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "it would be placed in front of him at up to " + (int) r
                        + " blocks from (" + (int) child.x() + ", " + (int) child.z() + "), off his " + noun));
            }
        }
        if (anchor != null) {
            if (!anchor.dimension().isEmpty() && !OVERWORLD.equals(anchor.dimension())) {
                return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island, "the wish is aimed in " + anchor.dimension()
                        + ", and the " + noun + "s are in the overworld"));
            }
            if (!reach.contains(anchor.x() + 0.5, anchor.z() + 0.5)) {
                return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "the spot this wish is aimed at, (" + anchor.x() + ", "
                        + anchor.y() + ", " + anchor.z() + "), is off his " + noun));
            }
        }
        return null;
    }

    /**
     * may this world-wide tool run because it is scoped to his base or his pets? {@code null} = yes;
     * {@code ""} = this world allows no scoped rules (the ordinary world-wide refusal follows); otherwise the
     * refusal, with the fix.
     * <ul>
     *   <li>{@code author_datapack}: let through here when {@code own_base} or {@code pets} is allowed — what its
     *       functions reach is judged on the COMPILED pack ({@link DatapackScope}, in the tool), where every line is
     *       readable;</li>
     *   <li>{@code set_rule}: {@code where: "his_island"} bounds it to his base ({@code own_base}), or {@code who}
     *       names one of HIS creatures by its tag ({@code pets}). Anything else — every zombie, everywhere — is
     *       world-wide and refused, as before.</li>
     * </ul>
     */
    static String scoped(String tool, JsonObject a, UUID caster, Bases world, WorldRules in) {
        List<String> scopes = in.allowedScopes();
        if (scopes.isEmpty() || !in.baseReach()) {
            return "";
        }
        if (world.baseOf(caster) == null) {
            return "he has no " + world.noun() + " yet, so nothing can be scoped to it";
        }
        if ("author_datapack".equals(tool)) {
            return null;
        }
        if ("set_rule".equals(tool)) {
            if ((has(a, "remove") && truthy(a.get("remove"))) || (has(a, "list") && truthy(a.get("list")))) {
                return null;                       // taking one away, or reading them, changes no island
            }
            String where = str(a, "where");
            String who = str(a, "who");
            boolean pets = scopes.contains("pets") && !who.isEmpty()
                    && owned(caster).test(who.startsWith("tag=") ? who.substring(4) : who);
            boolean island = scopes.contains("own_base")
                    && ("his_island".equalsIgnoreCase(where) || "my_island".equalsIgnoreCase(where));
            if (island || pets) {
                return ruleCommands(a, caster, world, in, who, pets);
            }
            return "a rule here fires on every " + world.noun() + " unless it is scoped: pass where: \"his_island\" so "
                    + "it only fires on his " + world.noun() + ", or set who to one of his own creatures' tags (his pets)";
        }
        return "";
    }

    /**
     * A scoped rule's {@code do} commands, judged like a pack's lines ({@link DatapackScope}) from his base: places
     * on his base or his creatures, nothing world-wide, and never hurting him. {@code @s} is him unless the rule is
     * about a mob ({@code who} a mob id or his creature's tag) — a rule on his jumps or his sneaking runs as him.
     */
    private static String ruleCommands(JsonObject a, UUID caster, Bases world, WorldRules in, String who, boolean pets) {
        List<String> cmds = commands(a.get("do"));
        boolean mob = pets || (!who.isEmpty() && !who.equalsIgnoreCase("any") && !who.equalsIgnoreCase("player")
                && !who.equalsIgnoreCase("minecraft:player"));
        List<DatapackScope.Line> lines = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            String c = cmds.get(i);
            lines.add(new DatapackScope.Line("do", i + 1, mob ? c : "execute as @p run " + c));
        }
        String why = DatapackScope.refusal(lines, "", world.baseOf(caster), owned(caster), in,
                h -> com.moddingfromamod.wish.CommandGuard.rule(h) != null);
        return why == null ? null : why.replace("this datapack's function do", "this rule's `do`");
    }

    /**
     * the tools whose radius is a SEARCH round the child ("the creatures within N blocks") and that keep the
     * search to his base themselves, by {@link #fence}: they act only on what stands inside it. For these the reach
     * rule asks whether he stands on his base, not whether the whole search square fits inside it, which no search
     * wider than the base ever did. A tool joins this set only once it reads the fence (BaseReachTest holds each to it).
     */
    public static final java.util.Set<String> KEEPS_TO_BASE = java.util.Set.of("modify_mob");

    /**
     * <b>where a search round the child may act</b> — his base's area on a world whose rules fence wishes to a
     * base, else null (no fence: the search is not limited here). A caster with no base gets a region holding nothing,
     * so a search that somehow ran finds nothing to change. Pure: {@code WorldModuleHost.fenceFor} asks it of the
     * running world.
     *
     * @param caster the child whose wish this is; null = no wish bound, no fence (as {@link #refusal} judges none)
     */
    public static Region fence(UUID caster, Bases world, WorldRules rules) {
        if (world == null || caster == null) {
            return null;
        }
        WorldRules in = rules == null ? WorldRules.CLOSED : rules;
        if (!in.baseReach()) {
            return null;
        }
        Base b = world.baseOf(caster);
        return b == null ? NOWHERE : b.area();
    }

    /** A region no column is inside (min above max). */
    static final Region NOWHERE = new Region(1, 0, 1, 0);

    /** He stands on his base (the overworld, inside its area): a search from there is kept to it by the tool. */
    private static Refusal onBase(String tool, String noun, Base island, Bases.Pos child, String what) {
        if (child == null) {
            return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "he is not in the world, so there is nowhere near him"));
        }
        if (!OVERWORLD.equals(child.dimension())) {
            return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island, "he is not in the overworld, where the " + noun + "s are"));
        }
        if (!island.area().contains(child.x(), child.z())) {
            return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, what + ", and he is standing at (" + (int) child.x() + ", "
                    + (int) child.z() + "), off his " + noun));
        }
        return null;
    }

    private static Refusal nearChild(String tool, String noun, Base island, Bases.Pos child, double r,
                                    String what) {
        if (child == null) {
            return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "he is not in the world, so there is nowhere near him"));
        }
        if (!OVERWORLD.equals(child.dimension())) {
            return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island, "he is not in the overworld, where the " + noun + "s are"));
        }
        if (!island.area().containsBox(child.x(), child.z(), r)) {
            return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, what + ", and from (" + (int) child.x() + ", "
                    + (int) child.z() + ") that reaches off his " + noun));
        }
        return null;
    }

    /** A command line on a world with no base reach: only its world-wide heads are judged. */
    /**
     * <b>The same rule for a command REPLAYED with no agent</b> — a button, a template, a standing
     * rule. {@link #refusal} runs at {@code McpProtocol}'s {@code tools/call}, so it judges the
     * agent; a child's buttons follow him from world to world and replay through
     * {@code WishExecutor.runOne}, where nothing read the world's rules. Measured from WHERE THE
     * COMMAND RUNS ({@code x, z, dimension}: the presser for a button, the pet for a rule), not from
     * the child, because that is the origin its {@code ~ ~ ~} resolves against.
     *
     * @return null when it may run; else the effect it would change, for the child's words and the log
     */
    public static WorldEffect replayRefusal(String command, UUID caster, double x, double z, String dimension,
                                            Bases world, WorldRules rules) {
        if (command == null || caster == null || world == null) {
            return null;
        }
        // No file read yet on a world with bases is CLOSED, exactly as refusal() treats it.
        rules = rules == null ? WorldRules.CLOSED : rules;
        if (!rules.deniesAnything()) {
            return null;
        }
        if (!rules.baseReach()) {
            // Only the world-wide heads this world denies (a world with no rules denies nothing and never gets here).
            WorldRules in = rules;
            CommandCoords.Result r = CommandCoords.analyse(command, new CommandCoords.Origin(x, z, 0, true),
                    in::allowsCommand);
            return r.refusal() != null && r.refusal().contains("changes the whole world")
                    ? worldWide(command) : null;
        }
        if (dimension != null && !dimension.isEmpty() && !OVERWORLD.equals(dimension)) {
            return WorldEffect.OTHER_DIMENSIONS;
        }
        Base island = world.baseOf(caster);
        if (island == null) {
            return WorldEffect.OUTSIDE_OWN_BASE;
        }
        WorldRules in = rules;
        CommandCoords.Result r = CommandCoords.analyse(command, new CommandCoords.Origin(x, z, 0, true),
                in::allowsCommand, owned(caster));
        if (r.refusal() != null) {
            return commandEffect(command, r.refusal());
        }
        for (CommandCoords.Area area : r.areas()) {
            if (area.onHisCreature()) {
                continue;
            }
            if (!island.area().containsBox(area.x(), area.z(), area.radius())) {
                return WorldEffect.OUTSIDE_OWN_BASE;
            }
        }
        return null;
    }

    /** The innermost command word ({@code execute … run time set day} → {@code time}), for {@link #worldWide}. */
    private static String head(String command) {
        String c = command.strip();
        int run;
        while ((run = c.toLowerCase(java.util.Locale.ROOT).indexOf(" run ")) >= 0 && c.toLowerCase(
                java.util.Locale.ROOT).startsWith("execute")) {
            c = c.substring(run + " run ".length()).strip();
        }
        String w = c.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (w.startsWith("/")) {
            w = w.substring(1);
        }
        return w.contains(":") ? w.substring(w.indexOf(':') + 1) : w;
    }

    private static Refusal commandWorldWide(String tool, JsonObject a, String noun, Base island, WorldRules in) {
        List<String> lines = commands(a.get("command"));
        for (int i = 0; i < lines.size(); i++) {
            String c = lines.get(i);
            CommandCoords.Result r = CommandCoords.analyse(c, new CommandCoords.Origin(0, 0, 0, true),
                    in::allowsCommand);
            if (r.refusal() != null && r.refusal().contains("changes the whole world")) {
                return no(worldWide(c), message(tool, noun, island, "'" + c + "': " + r.refusal()
                        + (lines.size() == 1 ? "" : " (command " + (i + 1) + " of " + lines.size()
                        + "; nothing in the batch was run)")));
            }
        }
        return null;
    }

    private static Refusal command(String tool, JsonObject a, String noun, Base island, Bases.Pos child,
                                  Spot anchor, WorldRules in) {
        CommandCoords.Origin origin;
        if (child != null) {
            if (!OVERWORLD.equals(child.dimension())) {
                return no(WorldEffect.OTHER_DIMENSIONS, message(tool, noun, island,
                        "he is not in the overworld, where the " + noun + "s are"));
            }
            origin = new CommandCoords.Origin(child.x(), child.z(), 0, true);
        } else if (anchor != null && (anchor.dimension().isEmpty() || OVERWORLD.equals(anchor.dimension()))) {
            origin = new CommandCoords.Origin(anchor.x() + 0.5, anchor.z() + 0.5, 0, true);
        } else {
            origin = new CommandCoords.Origin(0, 0, 0, false);
        }
        Region cell = island.area();
        List<String> lines = commands(a.get("command"));
        for (int i = 0; i < lines.size(); i++) {
            String c = lines.get(i);
            String where = lines.size() == 1 ? "" : " (command " + (i + 1) + " of " + lines.size()
                    + "; nothing in the batch was run)";
            CommandCoords.Result r = CommandCoords.analyse(c, origin, in::allowsCommand, owned(island.owner()));
            if (r.refusal() != null) {
                return no(commandEffect(c, r.refusal()), message(tool, noun, island, "'" + c + "': " + r.refusal() + where));
            }
            for (CommandCoords.Area area : r.areas()) {
                if (area.onHisCreature()) {
                    continue;                      // his own creature, by its tag
                }
                if (!cell.containsBox(area.x(), area.z(), area.radius())) {
                    return no(WorldEffect.OUTSIDE_OWN_BASE, message(tool, noun, island, "'" + c + "' reaches ("
                            + (int) area.x() + ", " + (int) area.z() + ")"
                            + (area.radius() > 0 ? " ± " + (int) area.radius() : "")
                            + " — " + area.what() + " — which is off his " + noun + where));
                }
            }
        }
        return null;
    }

    private static List<String> commands(JsonElement raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isJsonNull()) {
            return out;
        }
        if (raw.isJsonArray()) {
            JsonArray arr = raw.getAsJsonArray();
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    out.add(e.getAsString());
                }
            }
        } else if (raw.isJsonPrimitive() && !raw.getAsString().isBlank()) {
            out.add(raw.getAsString());
        }
        return out;
    }

    /** Fields that lift a thing rather than move it across the map: they count as given, never as distance. */
    static final java.util.Set<String> VERTICAL = java.util.Set.of("up", "offsetY");

    /** The radius a rule's fields add up to across the map, with the default for any that are absent. */
    static double radius(JsonObject a, Rule rule) {
        if (rule.fields().isEmpty()) {
            return rule.defaultRadius();
        }
        double r = 0;
        boolean any = false;
        for (String f : rule.fields()) {
            if (has(a, f)) {
                // a height is not a distance across his base — "up 20" lands no further from him on the map.
                r += VERTICAL.contains(f) ? 0 : Math.abs(num(a, f));
                any = true;
            }
        }
        return any ? r : rule.defaultRadius();
    }

    /**
     * A refusal of the reach rule: the words the agent reads, and the world effect it enforced — what a backstop catch
     * is counted against on the Worlds page, and which of this world's rules a child is told
     * ({@link WorldRules#ruleFor}). {@code effect} is null for a refusal that is no rule's (a tool with no decision, a
     * child with no base yet).
     */
    public record Refusal(String words, WorldEffect effect) {
    }

    private static Refusal no(WorldEffect effect, String words) {
        return new Refusal(words, effect);
    }

    /**
     * The effect a command {@link CommandCoords} refused enforces: a world-wide head's own effect, a command run in
     * another dimension's, else a place off his base.
     */
    private static WorldEffect commandEffect(String command, String refusal) {
        if (refusal.contains("changes the whole world")) {
            return worldWide(command);
        }
        if (refusal.contains("it runs in ")) {
            return WorldEffect.OTHER_DIMENSIONS;
        }
        return WorldEffect.OUTSIDE_OWN_BASE;
    }

    /** The effect of a command whose head changes the whole world; a head no effect lists is still a place. */
    private static WorldEffect worldWide(String command) {
        WorldEffect e = WorldEffect.ofCommand(head(command));
        return e == null ? WorldEffect.OUTSIDE_OWN_BASE : e;
    }

    /** The refusal the agent reads: what did not happen, why, where his base is, what to do. */
    static String message(String tool, String noun, Base island, String why) {
        String where = island == null ? ""
                : " His " + noun + " is at " + island.origin()
                  + "; a wish may be aimed anywhere from " + island.reach().describe()
                  + " and may not reach past " + island.area().describe() + ".";
        return "REFUSED — NOT HIS " + noun.toUpperCase(java.util.Locale.ROOT) + ": " + tool
                + " did not run and nothing changed. On this world "
                + "each child has their own " + noun + ", and a wish may only change the " + noun
                + " of the child who made it: " + why + "." + where
                + " Aim inside his " + noun + " (conjure_at moves the spot) and call " + tool
                + " again — or tell him, in a sentence, why this one cannot be granted here.";
    }

    private static boolean has(JsonObject a, String f) {
        return a.has(f) && !a.get(f).isJsonNull();
    }

    private static String str(JsonObject a, String f) {
        try {
            return has(a, f) ? a.get(f).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static double num(JsonObject a, String f) {
        try {
            return a.get(f).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean truthy(JsonElement e) {
        try {
            return e.getAsBoolean() || "true".equalsIgnoreCase(e.getAsString());
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
