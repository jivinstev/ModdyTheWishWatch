package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.mob.SpawnBudget;
import com.moddingfromamod.wish.CommandGuard;
import com.moddingfromamod.wish.WishExecutor;
import net.minecraft.server.level.ServerPlayer;

/**
 * The agent's hands. Runs one Minecraft command — or a whole scripted sequence — at the child's
 * position.
 *
 * <p>Two things make this safe enough to hand to an AI: every command is checked against
 * {@link CommandGuard}'s allowlist first, and the real result is returned — so a mistyped command
 * comes back as an error the agent can fix rather than a silent no-op it will report as success.
 *
 * <h2>Why it takes a list</h2>
 * A model turn costs ~4s and this tool used to run exactly one line per turn. Measured over 354
 * wishes: <b>1436 of 2731 calls immediately followed another call with no other tool in between,
 * costing 121 minutes of model time</b>, with observed runs of 19, 21 and 27 commands typed one at
 * a time. A streak of two is often real thinking — the tool returns each command's output precisely
 * so the agent can read one result before choosing the next, and that loop must survive — but a
 * streak of 27 is a script being dictated a line per round trip.
 *
 * <p>So: a bare string still works exactly as before, and a list runs in ONE turn and one hop to
 * the server thread. The rule the description teaches is the one that matters — batch a sequence
 * you already know; keep it one at a time when the next command depends on what the last one said.
 */
public final class RunCommandTool implements McpTool {

    /**
     * Longest batch accepted. The whole batch runs inside a single server-thread hop, so this is
     * what stops one tool call from stalling the tick loop; the longest sequence ever observed from
     * a real agent was 27, so this leaves room without being unbounded.
     */
    public static final int MAX_BATCH = 32;

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "Run Minecraft commands in the child's world, positioned where they are standing "
                + "(so ~ ~ ~ means 'at the player'). Write them WITHOUT a leading slash, e.g. "
                + "'summon minecraft:chicken ~ ~5 ~'. ALWAYS pass a LIST, even for a single "
                + "command, and put the whole sequence in ONE call whenever you already know it — "
                + "a list of 10 costs one step where 10 separate calls cost ten, and the child is "
                + "watching a ticker for every one of them. Keep them separate only when you need "
                + "to READ one command's output before deciding the next. If a command in a list fails, the rest are not run and "
                + "you are told exactly where it stopped. Only safe, fun commands are allowed; "
                + "server control commands are refused. "
                // three live "take me to a <biome>" wishes threw the child round the map
                // guessing coordinates after locate was refused.
                + "To FIND a place (\"take me to a jungle\", \"the nearest village\") send "
                + "`locate biome <id>` or `locate structure <id>` alone in a call: it answers where the "
                + "nearest one is and the ground height there, so one tp lands him on it — never guess "
                + "coordinates. Real output is returned, so if something is "
                + "wrong you will see the error and can correct it. Max " + MAX_BATCH + " per call. "
                // the 20 a child once met was the model's own guess, told as "the game would
                // crash". The real numbers live in SpawnBudget and are enforced wherever a creature joins.
                // This WORLD's numbers (each world sets its own).
                + "CREATURES COME IN WAVES: one wish brings up to "
                + SpawnBudget.perWish(SpawnBudget.Kind.VANILLA) + " ordinary mobs, "
                + SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE) + " that explode, "
                + SpawnBudget.perWish(SpawnBudget.Kind.FLYING) + " that fly and "
                + SpawnBudget.perWish(SpawnBudget.Kind.BOSS) + " boss(es); summon the whole ask and the rest are simply not made — the result gives "
                + "you the exact words to say. Never invent a limit and never say the game would "
                + "crash or explode. MANY of one mob he wants to keep ('lots of sheep in my field', "
                + "'a flock of chickens', 'twenty pigs all round me') is not summon lines: it is stage_event "
                + "motion crowd, one call, spaced out and undone as one. "
                // A wished item — a real one enchanted included — belongs to make_item.
                + "A THING HE WISHED FOR is never a `give` here, even a real game item with an "
                + "enchantment: that is make_item with base_item, which names it, remembers it "
                + "and writes the enchantment in the spelling this version accepts. "
                // a power a child asked for is permanent. Measured: `effect give … 1000000`
                // reads as forever in every check and then stops one day, for no reason he can see
                // or describe. `infinite` is a real duration keyword and says what was meant.
                + "When an effect is meant to LAST, write 'infinite' as the duration — never a big "
                + "number of seconds. A power the child asked for should not wear off in the middle "
                + "of playing. "
                // the agent left three zombies because it could not remove two. Saying the
                // verb exists is the whole fix — showed it does not reach a verb it has not
                // been told about, in words close to the job.
                + "If something you summoned went wrong, TAG it when you summon it and you can "
                + "'kill @e[tag=yourtag]' to clear it away — that is the only kill allowed, and it "
                + "means a failed attempt does not have to be left standing in his world. "
                // the end-of-wish tidy took three minecarts a wish had parked "forever".
                + "When the wish ends, what your commands summoned that is not alive (armour "
                + "stands, minecarts, boats, items) is TIDIED AWAY as a test prop, unless that "
                + "call passed keep:true — pass it when the thing IS part of what he asked for, "
                + "never for a test prop: anything you summon to TRY the wish on is one, even a "
                + "thing he might like. keep never keeps a mob frozen with NoAI (a statue he asked "
                + "for stays by its CustomName), a dropped item or a thing in flight. "
                // measured twice. Testing a revive rule by attacking the child means he
                // watches Moddy hurt him, drop him to one heart and change his game mode, with no
                // way to know it is a test. He asked not to die; being repeatedly killed to prove
                // it is the opposite of the wish.
                + "NEVER test something by hurting the child — no 'damage', no setting his Health, "
                + "no changing his gamemode. If you need something to happen TO someone to check a "
                + "rule works, summon a stand-in (an armour stand or a mob with a tag) and do it to "
                + "that instead. He is playing, not debugging.";
    }

    @Override
    public JsonObject inputSchema() {
        // A LIST, and only a list — even for one command. Offering "string or list" as well got
        // the list form used ZERO times in 752 real calls; the description explained batching
        // perfectly and changed nothing. The body still accepts a bare string, so the old shape
        // degrades to working rather than to an error.
        JsonObject s = Schemas.list(Schemas.object(), "command",
                "The commands to run IN ORDER, as a list — always a list, even for one. "
                        + "No leading slash. A list of 10 costs one step where 10 separate calls "
                        + "cost ten. Split into separate calls ONLY when you must read one "
                        + "command's output before choosing the next.",
                MAX_BATCH);
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("every command in this call",
                "its front (a building) or in front of him (me)",
                "Each command then runs ON that place: ~ ~ ~ is the spot (its floor, its roof), facing its "
                        + "way. \"Cars in my garage\" → summon minecraft:minecart ~ ~ ~ with "
                        + "at:\"<garage id>:inside\"; a chest on the counter → setblock ~ ~ ~ minecraft:chest "
                        + "with at:\"<shop id>:<counter spot>\". A thing summoned with an at is what he "
                        + "asked for, so it is never tidied away as a test prop."));
        // per CALL, never per wish — what over-keeping can cost is this one call's spawns.
        Schemas.prop(s, "keep", "boolean",
                "true when what this call summons IS part of the wish (a car, a boat, a scarecrow, "
                        + "a decoration he should keep): it is never tidied away. Leave it out for "
                        + "test props — anything summoned to TRY the wish on (a target, a mob to "
                        + "hit, items to drop in), even if he might like them. It cannot keep a mob "
                        + "frozen with NoAI, a dropped item or a thing in flight. (A living mob with "
                        + "its AI on is never tidied, keep or not.)");
        return Schemas.required(s, "command");
    }

    /**
     * How many commands this call carries — the seam a gate uses to prove the body stayed tolerant
     * of a bare string even after the schema stopped offering one.
     */
    public static int commandCount(JsonObject args) {
        return commandsIn(args).size();
    }

    /**
     * The commands in this call, in order. Accepts a bare string or a list; skips blanks.
     *
     * <p>Each is passed through {@link AddTemplateTool#rewriteDatapackFunction} on the way — see the
     * note at the call site in {@link #call}. Rewriting here rather than in {@code call} keeps the
     * list effectively final for the server-thread lambda.
     */
    /** An item's stack size by id (64 when it is not an item this game has). */
    static int maxStack(String id) {
        try {
            String s = id == null ? "" : id.strip();
            int cut = s.indexOf('[');
            if (cut >= 0) {
                s = s.substring(0, cut);
            }
            var key = net.minecraft.resources.ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s);
            return key == null ? 64 : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key)
                    .map(i -> i.getDefaultMaxStackSize()).orElse(64);
        } catch (RuntimeException e) {
            return 64;
        }
    }

    private static List<String> commandsIn(JsonObject args) {
        List<String> out = new ArrayList<>();
        JsonElement raw = args.get("command");
        if (raw == null) {
            return out;
        }
        if (raw.isJsonArray()) {
            for (JsonElement e : raw.getAsJsonArray()) {
                if (e != null && e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    out.add(AddTemplateTool.rewriteDatapackFunction(e.getAsString()));
                }
            }
        } else if (raw.isJsonPrimitive() && !raw.getAsString().isBlank()) {
            out.add(AddTemplateTool.rewriteDatapackFunction(raw.getAsString()));
        }
        return out;
    }

    /**
     * Whether this call said {@code keep: true}. A boolean, or the string {@code "true"} a
     * model sometimes sends for one; anything else — absent included — is {@code false}, which is
     * exactly the behaviour before the mark existed.
     */
    static boolean keepIn(JsonObject args) {
        JsonElement k = args == null ? null : args.get("keep");
        if (k == null || !k.isJsonPrimitive()) {
            return false;
        }
        return k.getAsJsonPrimitive().isBoolean() ? k.getAsBoolean()
                : "true".equalsIgnoreCase(k.getAsString().trim());
    }

    /** The refusal message for a blocked command, naming a better route when we have one. */
    private static String refusal(String command) {
        // POINT AT THE TOOL THAT DOES IT, when the refusal is about one of this mod's own
        // capabilities. Measured: an agent tried `wishmob list`, was told to stick to summon /
        // give / effect -- none of which list creatures -- and improvised by re-emitting a
        // whole define_creature for a creature that already existed. That cost 21 SECONDS of a
        // 64-second build, and the cheap path (`wishmob spawn <id>`) had already worked one
        // call earlier. A refusal that names no alternative is an invitation to improvise.
        String hint = "";
        String head = command.trim().toLowerCase(java.util.Locale.ROOT);
        if (head.startsWith("wishmob") || head.startsWith("/wishmob")) {
            hint = " To work with creatures use the TOOLS, not commands: list_creatures to see "
                    + "them, inspect_creature to look at one, define_creature or "
                    + "sculpt_creature to make one, and modify_mob to change one. To spawn one "
                    + "that already exists, `wishmob spawn <id>` IS allowed — you do not need "
                    + "to define it again.";
        }
        // the row's own reason, so a refusal says WHY and the agent does not improvise
        // around a rule it cannot see.
        return "That command isn't allowed for wishes: " + command + ". " + CommandGuard.whyRefused(command)
                + " Stick to things like summon, give, effect, setblock, fill, particle, "
                + "playsound, time, weather." + hint;
    }

    @Override
    public ToolResult call(JsonObject args) {
        List<String> asked = commandsIn(args);
        if (asked.isEmpty()) {
            return ToolResult.error("No command given.");
        }
        // ON a named place — each command becomes `wishspot <at> run <command>`, so ~ ~ ~ is
        // the garage's floor, the roof, in front of him; it waits for a building still going up.
        String at = args.has("at") && args.get("at").isJsonPrimitive() ? args.get("at").getAsString() : "";
        List<String> wrapped = com.moddingfromamod.place.AtCommands.wrap(asked, at);
        final List<String> commands = wrapped == null ? null : new ArrayList<>(wrapped);   // rewrites in place
        if (commands == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" — a building's id, a creature or me, "
                    + "then a spot (top, inside, front, beside, around, …), not '" + at + "'.");
        }
        // `locate structure|biome <id>` is ANSWERED here, never dispatched — vanilla's search
        // runs on the server thread and once froze it 59 s. On its own in a call, because whatever
        // comes next (a tp, a build) needs the coordinates it answers with.
        if (commands.stream().anyMatch(CommandGuard::answeredNotRun)) {
            if (commands.size() != 1) {
                return ToolResult.error("Send `locate` in a call of its own: nothing was run. What you do "
                        + "next needs the coordinates it answers with.");
            }
            return WishLocate.answer(commands.get(0));
        }
        final boolean keep = keepIn(args);
        if (commands.size() > MAX_BATCH) {
            return ToolResult.error("That is " + commands.size() + " commands; the most I can run "
                    + "in one step is " + MAX_BATCH + ". Split it across two calls.");
        }
        // PUTTING BACK SOMETHING KEPT MAKES NOTHING NEW (2026-10-03): its pictures from when it
        // was made stand, so the wish lets its camera go now and takes no photograph — unless it also
        // makes something new (CameraNeed.reused). Read here, on the MCP thread the wish is bound to.
        var replayRun = com.moddingfromamod.agent.AgentContext.currentRun();
        if (replayRun != null) {
            for (String c : commands) {
                if (com.moddingfromamod.wish.ReplayCommand.isReplay(c)) {
                    com.moddingfromamod.wish.CameraNeed.shared().reused(replayRun.wishId(), null, null,
                            System.currentTimeMillis());
                    break;
                }
            }
        }
        // ── A BARE /function INTO ONE OF OUR OWN DATAPACKS IS REWRITTEN TO `wishdatapack`.
        //
        // Calling a datapack function directly can throw "This function should not run", and
        // retrying does not help. That was already known and already fixed -- but only in the two
        // places that write such a command FOR the agent: AddTemplateTool rewrites it at teach
        // time, MakeItemTool spells out the working form in its result text.
        //
        // Nothing covered the agent TYPING one, which is what it does when it tests its own work.
        // Live 2026-08-14, having just authored the pack and had verify_datapack come back clean:
        //     FAILED: function metal_grinder:tick   ...UnsupportedOperationException
        //     FAILED: function metal_grinder:reset  ...UnsupportedOperationException
        // It gave up on the datapack and hand-placed a repeating command block instead -- the one
        // that then spammed the child's screen.
        //
        // run_command is the funnel every hand-typed command passes through, so the rewrite belongs
        // here rather than in a third tool. Anything that is not a `function` into a saved datapack
        // is left exactly as written. Applied in commandsIn so `commands` stays effectively final
        // for the server-thread lambda below.
        // EVERY command is checked BEFORE any of them runs. Whether a command is allowed is a
        // static property needing no execution, so a batch that was always going to be rejected
        // should change nothing at all — half-applying one and then refusing leaves the world in a
        // state neither the agent nor the child asked for, and the agent cannot see what stuck.
        for (String c : commands) {
            if (!CommandGuard.isAllowed(c)) {
                String where = commands.size() == 1 ? ""
                        : " (nothing was run — it is command " + (commands.indexOf(c) + 1) + " of "
                          + commands.size() + "; send the rest without it)";
                return ToolResult.error(refusal(c) + where);
            }
        }
        // on a world whose rules clamp what a wish hands HIM — a power's length, a gift's size,
        // XP — each command is shortened or shrunk (and said), or, where nothing can be counted, refused with the
        // way that can. Per WISH: ten `give`s in ten calls share one ledger. Mobs and his creatures are never touched.
        final List<String> clampWords = new ArrayList<>();
        var clamp = com.moddingfromamod.world.WishClamp.of(com.moddingfromamod.world.WorldRulesService.loadedOrNull());
        if (clamp != null) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            var ledger = com.moddingfromamod.world.WishClamp.ledger(run == null ? "nowish" : run.wishId().toString());
            for (int i = 0; i < commands.size(); i++) {
                var r = com.moddingfromamod.world.WishClamp.command(commands.get(i), clamp, ledger, RunCommandTool::maxStack);
                if (r.refused()) {
                    return ToolResult.error(r.refusal() + (commands.size() == 1 ? "" : " (it is command " + (i + 1)
                            + " of " + commands.size() + "; nothing in the batch was run)"));
                }
                if (r.changed(commands.get(i))) {
                    commands.set(i, r.command());
                }
                if (r.words() != null && !clampWords.contains(r.words())) {
                    clampWords.add(r.words());
                }
            }
            if (!clampWords.isEmpty()) {
                GiveItemsTool.tellChild(String.join(" ", clampWords));
            }
        }
        try {
            StringBuilder made = new StringBuilder();
            List<String> lines = new ArrayList<>();
            // ⚠ Read on THIS thread, not inside the lambda — and NOT for the reason it looks
            // like. AgentContext is a ThreadLocal and the work below runs on the server thread,
            // but AgentBridge.onServer already re-binds the worker across that hop precisely so a
            // tool asking "whose wish is this?" gets the right answer at N slots. So both spellings
            // are correct; this one is simply the one that does not depend on that, and it sits
            // beside the identity the rest of this method already resolved.
            final com.moddingfromamod.marshal.WishRun propRun =
                    com.moddingfromamod.agent.AgentContext.currentRun();
            final List<java.util.UUID> spawnedNow = new ArrayList<>();
            // One hop to the server thread for the whole batch, not one per command.
            boolean allOk = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // THE CHILD WHO WISHED HAS LEFT (decisions 1 and 3). A command still runs —
                // this is a WORLD tool, and what she asked for should be standing when she comes
                // back — but never AS another child, and never through a selector that can only
                // land on one: @p is whoever is nearest, @a is everybody else. That command is
                // refused before anything in the batch runs; the rest is sent again without it.
                boolean ownerLeft = propRun != null && propRun.playerId() != null
                        && com.moddingfromamod.agent.ActingChild.person(server) == null;
                if (ownerLeft) {
                    for (int i = 0; i < commands.size(); i++) {
                        String picks = com.moddingfromamod.agent.PersonSelectors
                                .firstPlayerSelector(commands.get(i));
                        if (picks != null) {
                            com.moddingfromamod.agent.ActionLog.reach(name(), "refused",
                                    "owner_left", picks, commands.get(i),
                                    com.moddingfromamod.wish.CameraPresence.childrenOnline(server));
                            lines.add("REFUSED: " + commands.get(i) + "\n"
                                    + com.moddingfromamod.agent.ActingChild.whyNobody(server,
                                            "The child who made this wish is not in the world.")
                                    + " '" + picks + "' would pick out another player instead."
                                    + " Nothing was run"
                                    + (commands.size() == 1 ? "."
                                       : " — it is command " + (i + 1) + " of " + commands.size()
                                         + "; send the rest without it."));
                            return false;
                        }
                    }
                }
                ServerPlayer player = ownerLeft ? null
                        : com.moddingfromamod.agent.ActingChild.of(server);
                // A WISH TOUCHES ONLY THE CHILD WHO MADE IT (option 2, 2026-09-24).
                // With another child in the world, a selector aimed at players is narrowed to the
                // wisher and a command naming another child is refused — before anything in the
                // batch runs. The shared world (time, weather, blocks, mobs) is untouched, and
                // alone in the world every command runs byte for byte as before.
                List<String> toRun = new ArrayList<>(commands);
                List<String> notes = new ArrayList<>();
                if (player != null && propRun != null && player.getUUID().equals(propRun.playerId())) {
                    List<String> others = new ArrayList<>();
                    var kids = com.moddingfromamod.marshal.PlayerKind.children(
                            server.getPlayerList().getPlayers().stream()
                                    .map(ServerPlayer::getUUID).toList(),
                            com.moddingfromamod.marshal.PlayerKind.serviceClients());
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (!p.getUUID().equals(player.getUUID()) && kids.contains(p.getUUID())) {
                            others.add(p.getScoreboardName());
                        }
                    }
                    var reachMode = com.moddingfromamod.world.PlayerVisits.mode();
                    var who = com.moddingfromamod.agent.PlayerReach.Who.wisher(
                            player.getScoreboardName(), others);
                    for (int i = 0; i < toRun.size(); i++) {
                        // ANOTHER PLAYER IS A PLACE TO GO, NEVER A SUBJECT. First,
                        // because a VISIT names the other child and is still allowed where the world
                        // says so — `narrow` alone refused every mention of him, which is how Moddy
                        // came to offer a "teleport me to him" button and then refuse it.
                        var reach = com.moddingfromamod.agent.PlayerReach.check(toRun.get(i), who);
                        if (!com.moddingfromamod.agent.PlayerReach.allows(reach, reachMode)) {
                            String why = reach.refused() ? reach.why()
                                    : com.moddingfromamod.agent.PlayerReach.WHY_REACH_OFF;
                            com.moddingfromamod.agent.ActionLog.reach(name(), "refused", why,
                                    reach.other(), toRun.get(i), others.size());
                            lines.add("REFUSED: " + toRun.get(i) + "\n"
                                    + com.moddingfromamod.agent.PlayerReach.refusal(reach,
                                            com.moddingfromamod.world.WorldRulesService.current().workshop())
                                    + " Nothing was run"
                                    + (toRun.size() == 1 ? "."
                                       : " — it is command " + (i + 1) + " of " + toRun.size()
                                         + "; send the rest without it."));
                            return false;
                        }
                        if (reach.visit()) {
                            // Runs as written; the teleport itself asks PlayerVisits (cooldown, and
                            // the "came to visit" line to the other child).
                            com.moddingfromamod.agent.ActionLog.reach(name(), "allowed", "visit",
                                    reach.other(), toRun.get(i), others.size());
                            continue;
                        }
                        var d = com.moddingfromamod.agent.PersonSelectors.narrow(
                                toRun.get(i), player.getScoreboardName(), others);
                        if (d.isRefused()) {
                            com.moddingfromamod.agent.ActionLog.reach(name(), "refused", d.why(),
                                    d.refused(), toRun.get(i), others.size());
                            lines.add("REFUSED: " + toRun.get(i) + "\n"
                                    + refusalFor(d.why(), d.refused())
                                    + " Nothing was run"
                                    + (toRun.size() == 1 ? "."
                                       : " — it is command " + (i + 1) + " of " + toRun.size()
                                         + "; send the rest without it."));
                            return false;
                        }
                        if (!d.narrowed().isEmpty()) {
                            com.moddingfromamod.agent.ActionLog.reach(name(), "narrowed", d.why(),
                                    String.join(", ", d.narrowed()), toRun.get(i), others.size());
                            notes.add(String.join(", ", d.narrowed()));
                            toRun.set(i, d.command());
                        }
                    }
                }
                // With nobody to stand beside, a command runs where the wish was AIMED (its locked
                // anchor, in the dimension it was aimed in), and only failing that at the spawn.
                var spot = ownerLeft ? propRun.anchor() : null;
                net.minecraft.core.BlockPos aimed = spot == null ? null
                        : new net.minecraft.core.BlockPos(spot.x(), spot.y(), spot.z());
                var dim = spot == null || spot.dimension().isEmpty() ? null
                        : net.minecraft.resources.ResourceLocation.tryParse(spot.dimension());
                var level = player != null ? player.level()
                        : com.moddingfromamod.agent.ArtifactLevel.resolve(server, dim == null
                                ? null
                                : net.minecraft.resources.ResourceKey.create(
                                        net.minecraft.core.registries.Registries.DIMENSION, dim));
                var where = player != null ? player.position()
                        : net.minecraft.world.phys.Vec3.atBottomCenterOf(
                                aimed != null ? aimed : level.getSharedSpawnPos());
                if (!notes.isEmpty()) {
                    lines.add("(Only " + player.getScoreboardName() + ", who made this wish: "
                            + String.join("; ", notes) + ". Another child is in the world, and a"
                            + " wish touches only the child who made it — the shared world is"
                            + " still everyone's.)");
                }
                // ONE SPAWN SCOPE FOR THE WHOLE BATCH, so twenty `summon creeper` lines
                // meet one wave and the agent is told once, at the end, what the wand held back.
                boolean scoped = com.moddingfromamod.mob.SpawnScope.open(WishExecutor.wishKeyOrNone(),
                        com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER);
                try {
                    for (int i = 0; i < toRun.size(); i++) {
                        String command = toRun.get(i);
                        // Diff around the child so anything this command creates gets a HANDLE, and a
                        // later "make it bigger" can name it exactly instead of guessing by distance.
                        var before = com.moddingfromamod.mob.EntityCapture.snapshot(level, where);
                        // ...and a WIDER diff beside it 's bracket. EntityCapture's is
                        // LivingEntity-only because a handle is for modify_mob; a test prop is just as
                        // often a marker or an area-effect cloud, which are entities and not living
                        // ones. Same band, same tick, one extra AABB query.
                        var beforeAll = com.moddingfromamod.wish.WishProps.snapshot(level, where);
                        // Nobody online (the child stepped away, or this is a headless server): run at
                        // the world spawn rather than refusing. Refusing would look to the agent like a
                        // bad command and send it round a pointless retry loop.
                        WishExecutor.Result r =
                                WishExecutor.runOne(server, level, where, player, command);
                        for (String handle : com.moddingfromamod.mob.EntityCapture.rememberNew(
                                level, where, before)) {
                            made.append(made.length() == 0 ? "" : ", ").append(handle);
                        }
                        // STAMP WHAT THIS COMMAND SPAWNED. Recorded on the RUN, so a second
                        // wish's tidy-up can never reach this one's props, and read back at
                        // finish_wish. Order matters: the handle diff runs first, so a prop that got a
                        // handle is recorded WITH it and can be recognised later if the wish keeps it.
                        // ...EXCEPT a command put ON a named place: "cars in the
                        // garage" are what he asked for, and an unnamed minecart with no handle is
                        // exactly what the sweep takes down — it deleted cars it had just promised
                        // would stay. A probe is summoned beside him, never at a named place. Anything
                        // else is kept only when the call said keep:true.
                        if (!com.moddingfromamod.place.AtCommands.isOnASpot(command)) {
                            spawnedNow.addAll(com.moddingfromamod.wish.WishProps.recordNew(level,
                                    where, beforeAll, propRun, keep));
                        }
                        String output = r.output().isEmpty() ? "(no output)" : r.joined();

                        // "IT WAS ALREADY LIKE THAT" IS NOT A FAILURE, and stopping on it inverts the
                        // rule below: the postcondition the agent wanted already holds, so the rest of
                        // the batch is standing on exactly the ground it expected. Live, one
                        // `fill … air` over air discarded the four commands behind it — including the
                        // repeating command block that WAS the child's grinder. See CommandOutcome.
                        if (!r.success() && CommandOutcome.isAlreadyDone(command, output)) {
                            lines.add(CommandOutcome.alreadyDoneLine(command));
                            continue;
                        }

                        // A raw /function into one of our own packs throws a bare Java exception, and
                        // the agent's recorded response to an unexplained one was to abandon the
                        // datapack for a repeating command block. Say what to do instead.
                        if (r.success() && propRun != null) {
                            // What a "teach it" draft is built from: only lines that worked.
                            propRun.ranCommand(command);
                        }
                        lines.add((r.success() ? "Ran: " : "FAILED: ") + command + "\n" + output
                                + CommandOutcome.datapackFunctionAdvice(command, output));
                        if (!r.success()) {
                            // STOP. Continuing past a failure builds on a state the agent believes
                            // exists and does not, and the report would blame the wrong command.
                            int skipped = toRun.size() - i - 1;
                            if (skipped > 0) {
                                lines.add("Stopped here — the remaining " + skipped + " command"
                                        + (skipped == 1 ? " was" : "s were") + " NOT run.");
                            }
                            return false;
                        }
                    }
                    return true;
                } finally {
                    var scope = com.moddingfromamod.mob.SpawnScope.current();
                    if (scoped && scope != null && scope.droppedAny()) {
                        scope.lines().forEach(l -> lines.add(
                                com.moddingfromamod.mob.SpawnBudget.forTheAgent(l)));
                    }
                    com.moddingfromamod.mob.SpawnScope.close(scoped);
                    // said once the whole batch has run, of what is still standing, so a
                    // probe the same batch already killed is never mentioned.
                    String tidy = com.moddingfromamod.wish.WishProps.warning(server, propRun,
                            spawnedNow, toRun);
                    if (!tidy.isEmpty()) {
                        lines.add(tidy.strip());
                    }
                }
            });
            String handles = made.length() == 0 ? ""
                    : "\nYou created: " + made + " — use that name as modify_mob's target to change"
                      + " exactly this one later.";
            String body = String.join("\n", lines) + handles;
            return allOk ? ToolResult.ok(body) : ToolResult.error(body);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** What the agent is told when a command would reach a child who did not make the wish. */
    static String refusalFor(String why, String what) {
        if (com.moddingfromamod.agent.PersonSelectors.WHY_ANY_ENTITY.equals(why)) {
            return "'" + what + "' could pick out another child, who did not make this wish. A wish"
                    + " touches only the child who made it: use @s for them, or add a type= for"
                    + " creatures (e.g. @e[type=zombie]).";
        }
        if (com.moddingfromamod.agent.PersonSelectors.WHY_EXCLUDES.equals(why)) {
            return "'" + what + "' leaves someone out by name, so it picks out another child, who did"
                    + " not make this wish. To take this child to another player, use `tp @s <their"
                    + " name>`; to reach only this child, use @s.";
        }
        return "'" + what + "' is another child, who did not make this wish. A wish touches only"
                + " the child who made it — tell them it is their brother's or sister's to wish"
                + " for. Changes to the shared world (time, weather, blocks, creatures) are fine.";
    }
}
