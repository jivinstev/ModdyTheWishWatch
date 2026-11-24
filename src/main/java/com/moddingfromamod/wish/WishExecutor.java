package com.moddingfromamod.wish;

import com.moddingfromamod.agent.PersonSelectors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a wish's commands on the server thread, a few per tick.
 *
 * <p><b>Why drip-feed instead of running them all at once:</b> a wish can expand to a hundred
 * commands ("spawn 60 chickens"), and doing that inside one tick is a visible stutter. Spreading the
 * work keeps the game smooth and — more importantly — makes the wish <em>look</em> like magic
 * happening rather than a freeze followed by a pile of chickens.
 *
 * <p><b>Command output is captured, not suppressed.</b> The obvious
 * {@code CommandSourceStack.withSuppressedOutput()} hides failures: a mistyped entity id would
 * silently do nothing while we cheerfully told the kid (and later, the AI agent) that it worked.
 * Instead a capturing {@link CommandSource} collects the real feedback so failures are visible,
 * loggable, and — once Claude is in the loop — self-correctable.
 */
public final class WishExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Commands executed per tick. Small enough to stay smooth, big enough to feel immediate. */
    private static final int COMMANDS_PER_TICK = 4;

    private static final Deque<Job> JOBS = new ArrayDeque<>();

    private WishExecutor() {
    }

    /**
     * Queue a resolved plan. A served wish (a button, a cached wish, a starter) gets the same
     * pillar-and-reveal moment an agent wish gets, so the plan is handed to {@link ServedFanfare},
     * which runs it at the reveal's climax. {@link ServedFanfare#enabled} off runs
     * it at once, as before.
     */
    public static void submit(ServerPlayer player, Wish wish, CommandPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        if (ServedFanfare.enabled) {
            ServedFanfare.submit(player, wish, plan);
            return;
        }
        enqueue(new Job(player.getUUID(), player.level(), player.position(), wish, plan,
                null, false));
    }

    /** Hand a job to the drip. Package-private: {@link ServedFanfare} calls it at the climax. */
    static void enqueue(Job job) {
        JOBS.add(job);
    }

    /** How many jobs are waiting, a served wish's fanfare included (for {@code /moddy status}). */
    public static int pendingJobs() {
        return JOBS.size() + ServedFanfare.pending();
    }

    /** Drop everything — used on server stop so jobs never straddle a world unload. */
    public static void clear() {
        JOBS.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Job job = JOBS.peek();
        int ran = 0;
        while (job != null && ran < COMMANDS_PER_TICK && job.hasNext()) {
            job.runNext(server);
            ran++;
        }
        if (job != null && !job.hasNext()) {
            JOBS.poll();
            job.finish(server);
        }
    }

    /**
     * Execute one command immediately on the calling (server) thread and return what it said. Used by
     * the tests and, later, by the agent's {@code run_command} tool — which is precisely why the
     * output is returned rather than swallowed.
     */
    /**
     * What the agent is told when it summons somewhere nothing is keeping loaded —.
     *
     * <p>Names the remedy rather than the problem, because the problem has no fix: the entity is
     * real and on disk and no selector will ever reach it.
     */
    public static final String UNREACHABLE_SUMMON =
            "\u26a0 That spot is not loaded, so NOTHING can address what you just summoned — a "
            + "selector like @e[tag=...] will say \"No entity was found\", now and later, and the "
            + "entity is still there. Summon it near the child (~ ~ ~, or somewhere he can see) "
            + "and the next command will find it.";

    public static Result runOne(MinecraftServer server, ServerLevel level, Vec3 pos,
                                ServerPlayer asPlayer, String command) {
        // EVERY CREATURE THIS COMMAND MAKES IS A WISHED ONE. The scope is what the world
        // guard (ops.WishMobLimit) reads when an entity joins: a `summon`, an `execute` that fans out
        // to hundreds, a button replay, a rule — they all end here, so one scope covers them all. A
        // caller that already opened one (a whole run_command batch, a served wish's job) keeps it,
        // and then says what was turned away once, itself.
        boolean opened = com.moddingfromamod.mob.SpawnScope.open(wishKeyOrNone(),
                com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER);
        try {
            // WHAT IT HANDED OVER, AND WHERE. A `give` — raw, or a made item's
            // `wishdatapack <id> give`, whose output is suppressed — used to leave the agent
            // "(no output)", which is how a lost item read as a granted one. Every landing inside
            // this command is collected and reported in its result.
            List<com.moddingfromamod.item.delivery.DeliveryWords.Landed> landed = new ArrayList<>();
            Result r = com.moddingfromamod.item.delivery.WishDelivery.collect(landed,
                    () -> runOneInScope(server, level, pos, asPlayer, command));
            if (!landed.isEmpty()) {
                List<String> out = new ArrayList<>(r.output());
                out.add(com.moddingfromamod.item.delivery.DeliveryWords.forAgent(landed));
                r = r.saying(out);
            }
            var scope = com.moddingfromamod.mob.SpawnScope.current();
            if (!opened || scope == null || !scope.droppedAny()) {
                return r;
            }
            List<String> out = new ArrayList<>(r.output());
            scope.lines().forEach(l -> out.add(com.moddingfromamod.mob.SpawnBudget.forTheAgent(l)));
            return r.saying(out);
        } finally {
            com.moddingfromamod.mob.SpawnScope.close(opened);
        }
    }

    /**
     * The wish the calling agent worker is granting, as a spawn-budget key, or
     * {@link com.moddingfromamod.mob.SpawnScope#NO_WISH} when no wish is behind the call (a world
     * rule firing, a gate). Only a BOUND worker counts: {@code AgentContext.currentRun()}'s
     * one-live-run fallback would charge a rule's spawns to whichever wish happens to be running.
     */
    public static String wishKeyOrNone() {
        if (com.moddingfromamod.agent.AgentContext.workerId().isEmpty()) {
            return com.moddingfromamod.mob.SpawnScope.NO_WISH;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? com.moddingfromamod.mob.SpawnScope.NO_WISH : run.wishId().toString();
    }

    /** An item's stack size by id (64 when unknown) — the clamp's measure of "a stack". */
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

    private static Result runOneInScope(MinecraftServer server, ServerLevel level, Vec3 pos,
                                        ServerPlayer asPlayer, String command) {
        if (!CommandGuard.isAllowed(command)) {
            return new Result(false, List.of("That command isn't allowed: " + command));
        }
        // ANOTHER PLAYER IS A PLACE TO GO, NEVER A SUBJECT — again at RUN time, because a
        // template's button replays here with no agent, maybe on another world than it was taught on
        // (a shelf follows a child from one world to another). The presser is self.
        String reach = reachRefusal(server, asPlayer, command);
        if (reach != null) {
            return new Result(false, List.of(reach));
        }
        // A WISH TOUCHES ONLY THE CHILD WHO MADE IT — at replay too. run_command narrows a
        // player selector to the wisher; a button or a rule replays here with no agent, and until
        // a taught `clear @a` or `spreadplayers … @a` would have reached every child online.
        // The presser is the wisher: the same rule, the same code.
        PersonSelectors.Decision narrowed = narrowForReplay(server, asPlayer, command);
        if (narrowed.isRefused()) {
            com.moddingfromamod.agent.ActionLog.reach("replay", "refused", narrowed.why(), narrowed.refused(),
                    command, 0);
            return new Result(false, List.of("That button reaches another player (" + narrowed.refused()
                    + "), and a wish only ever touches the player who pressed it: " + command));
        }
        command = narrowed.command();
        // THE WORLD'S RULES — at replay too. The agent's commands are judged at
        // McpProtocol's tools/call (BaseReach.refusal); a button, a template or a rule replays here
        // with no agent, and a child's buttons follow him from world to world. So with no
        // worker bound, the same rule is asked here, measured from where the command runs.
        com.moddingfromamod.world.Bases bases = com.moddingfromamod.world.WorldModuleHost.liveBases();
        if (bases != null && com.moddingfromamod.agent.AgentContext.workerId().isEmpty()) {
            com.moddingfromamod.world.WorldRules rules = com.moddingfromamod.world.WorldRulesService.loadedOrNull();
            String dim = level.dimension().location().toString();
            // A standing rule can fire with nobody near (asPlayer null): it is then the rule of whoever's
            // base it fires on, and on a world that fences wishes to bases, nobody's base is refused.
            java.util.UUID caster = asPlayer != null ? asPlayer.getUUID() : bases.ownerAt(dim, pos.x, pos.z);
            com.moddingfromamod.world.WorldRules in = rules == null ? com.moddingfromamod.world.WorldRules.CLOSED : rules;
            com.moddingfromamod.world.WorldEffect effect = caster == null
                    ? (in.baseReach() ? com.moddingfromamod.world.WorldEffect.OUTSIDE_OWN_BASE : null)
                    : com.moddingfromamod.world.BaseReach.replayRefusal(command, caster, pos.x, pos.z, dim, bases, rules);
            if (effect != null) {
                com.moddingfromamod.world.WorldRulesLog.backstop("", caster == null ? "" : caster.toString(),
                        "replay", effect, rules);
                com.moddingfromamod.world.WorldRule rule = in.ruleFor(effect);
                return new Result(false, List.of("That button can't work on this world"
                        + (rule == null ? "" : ": " + rule.rule() + " — " + rule.why())
                        + ". It works in " + in.workshop() + ". (" + command + ")"), List.of(),
                        com.moddingfromamod.world.WorldRefusal.of(effect, in, bases.noun()));
            }
        }
        // A SUMMON NOBODY CAN ADDRESS —, and it is a report because there is no fix.
        //
        // Measured, three treatments and three delays. A summon into a chunk nothing is keeping
        // loaded SUCCEEDS and produces an entity no selector ever reaches:
        //
        //   near (chunk loaded)   summon ok   @e[tag=…] FOUND
        //   far, untouched        summon ok   @e[tag=…] "No entity was found"
        //   far + getChunk        summon ok   still not found; chunk unloads again within 2 ticks
        //   far + setChunkForced  summon ok   still not found on the same tick
        //   far, +2 and +22 ticks             still not found, chunk loaded=false
        //
        // A chunk ticket is only processed on the next chunk-manager tick, so a chunk cannot be
        // raised to entity-ticking WITHIN the tick that summons into it — and left alone it
        // unloads again, taking the entity to disk. So there is nothing to repair here; what was
        // missing is that anybody said so. The agent read "Ran, OK", could not address what it had
        // made, and summoned a second zombie and then a third for a child who asked for one.
        //
        // Only for an ABSOLUTE target: ~ and ^ resolve against the child, who is standing in a
        // loaded chunk by definition.
        // a learned button must not get round the world's clamp (a forever power, a big gift, a pile of
        // XP replayed on a clamped world). One PRESS is one ledger: its commands run in one tick, so the key is the
        // presser and the tick.
        // Only with no agent bound: run_command (the agent's path, which executes through here) has already
        // clamped this exact command against the wish's ledger, and a second pass would charge it twice.
        var clamp = com.moddingfromamod.agent.AgentContext.workerId().isEmpty()
                ? com.moddingfromamod.world.WishClamp.of(com.moddingfromamod.world.WorldRulesService.loadedOrNull()) : null;
        if (clamp != null) {
            String key = "press:" + (asPlayer == null ? "rule" : asPlayer.getUUID()) + ":" + server.getTickCount();
            var r = com.moddingfromamod.world.WishClamp.command(command, clamp,
                    com.moddingfromamod.world.WishClamp.ledger(key), WishExecutor::maxStack);
            if (r.refused()) {
                return new Result(false, List.of("That button can't work on this world: " + r.refusal()));
            }
            if (r.changed(command)) {
                command = r.command();
                if (asPlayer != null && r.words() != null) {
                    asPlayer.sendSystemMessage(Component.literal("✨ " + r.words())
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                }
            }
        }
        final String asSent = command;
        java.util.Optional<SummonTarget.At> summonAt = SummonTarget.of(asSent);

        List<String> captured = new ArrayList<>();
        // Whether the command said anything the way a REFUSAL is said. CommandSourceStack.sendFailure
        // wraps its message in RED, and that is the only mark a refusal leaves when a command returns
        // 0 instead of throwing -- which is how every one of our own commands refuses (see below).
        boolean[] refusedOutLoud = {false};
        net.minecraft.network.chat.TextColor red =
                net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED);
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                captured.add(message.getString());
                if (red != null && red.equals(message.getStyle().getColor())) {
                    refusedOutLoud[0] = true;
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack stack = new CommandSourceStack(sink, pos, Vec2.ZERO, level, 2,
                "wish", Component.literal("wish"), server, asPlayer);

        // REPAIR THE RENAMED ATTRIBUTE IDS FIRST — see AttributeIds, and.
        //
        // Here rather than in one tool because both paths that reach a command have produced the
        // broken form: `run_command` at grant time, and the COMMANDS SAVED INTO A TEMPLATE, which
        // is worse — the wish itself works and the child's way back silently does nothing while
        // announcing "BOOM! You're a GIANT!". One choke point covers both, and any future caller.
        AttributeIds.Fixed repaired = repairAttributeIds(command);
        if (!repaired.notes().isEmpty()) {
            command = repaired.command();
            // Said out loud, not silently: the agent should learn the right id, and a correction
            // nobody can see is indistinguishable from the command having been right all along.
            captured.add("(corrected " + String.join(", ", repaired.notes())
                    + " — this build spells it that way)");
        }

        // Drive Brigadier directly rather than performPrefixedCommand, because that method swallows
        // CommandSyntaxException and reports failures only as chat text — leaving the caller to
        // guess from message wording whether the command worked. Parsing and executing ourselves
        // gives a DETERMINISTIC answer, which matters enormously once an AI agent is reading it:
        // a wrong "success" would have it tell the kid a broken wish came true.
        com.mojang.brigadier.ParseResults<CommandSourceStack> parsed =
                server.getCommands().getDispatcher().parse(command, stack);
        if (!parsed.getExceptions().isEmpty()) {
            parsed.getExceptions().values().forEach(e -> captured.add(e.getMessage()));
            return new Result(false, captured);
        }
        if (parsed.getReader().canRead()) {
            captured.add("I didn't understand this bit: '" + parsed.getReader().getRemaining().trim() + "'");
            return new Result(false, captured);
        }
        // WHAT MINECRAFT SAID IN ITS LOG while it ran this. A summon whose NBT names an
        // attribute this build does not know SUCCEEDS and applies none of it; the only evidence is a
        // WARN in the server log. Captured per thread, so another thread's warning never lands here.
        List<String> logged = List.of();
        int returned;
        try {
            WarnCapture.Captured<Integer> ran = WarnCapture.during(() -> {
                try {
                    return server.getCommands().getDispatcher().execute(parsed);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    throw new SyntaxFailure(e);
                }
            });
            logged = ran.warnings();
            returned = ran.value() == null ? 0 : ran.value();
        } catch (SyntaxFailure wrapped) {
            com.mojang.brigadier.exceptions.CommandSyntaxException e = wrapped.cause;
            captured.add(e.getMessage());
            return new Result(false, captured);
        } catch (RuntimeException e) {
            LOGGER.warn("wish command '{}' blew up: {}", command, e.toString());
            captured.add(e.toString());
            return new Result(false, captured);
        }
        // A REFUSAL THAT DID NOT THROW. Vanilla refuses by throwing; our own commands
        // (wishdatapack, wishmob, wishbuild, ...) refuse by sendFailure + `return 0`, and this used
        // to call that a success -- so `wishdatapack <id> give` on a pack whose give function never
        // loaded was a GRANTED wish and a "Ran, OK" to the agent. Both marks are required: a 0 with
        // no red message is a command that legitimately did nothing, and a red line from a command
        // that returned a count is a partial report, not a refusal.
        if (returned == 0 && refusedOutLoud[0]) {
            return new Result(false, captured);
        }
        // The summon worked and nothing can reach what it made. Said on the SUCCESS path on
        // purpose: the command did not fail, and a note attached to a failure would never appear.
        summonAt.ifPresent(at -> {
            if (!level.isLoaded(new net.minecraft.core.BlockPos(at.x(), at.y(), at.z()))) {
                captured.add(UNREACHABLE_SUMMON);
            }
        });
        // Still a SUCCESS — the command ran and what it made exists, so retrying would make a second
        // one — but said out loud, where the agent and the replay log both read it.
        List<String> ignored = new ArrayList<>();
        for (String w : logged) {
            if (WarnCapture.dropsPartOfCommand(w)) {
                ignored.add(w);
                captured.add("\u26a0 It ran, but Minecraft IGNORED part of it: \"" + w + "\" — "
                        + "fix that part (it did not happen), don't run the whole command again.");
            } else {
                captured.add("(Minecraft logged while it ran: \"" + w + "\")");
            }
        }
        return new Result(true, captured, List.copyOf(ignored));
    }

    /**
     * {@link PersonSelectors#narrow} for a replay, with the presser as the wisher. A visit (which
     * names the other child on purpose, and which {@link #reachRefusal} already allowed) runs as
     * written; alone in the world nothing changes.
     */
    static PersonSelectors.Decision narrowForReplay(MinecraftServer server, ServerPlayer asPlayer, String command) {
        if (server == null || asPlayer == null) {
            return new PersonSelectors.Decision(command, List.of(), null, null);
        }
        List<String> others = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(asPlayer.getUUID())
                    && com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                others.add(p.getScoreboardName());
            }
        }
        if (others.isEmpty() || com.moddingfromamod.agent.PlayerReach.check(command,
                com.moddingfromamod.agent.PlayerReach.Who.wisher(asPlayer.getScoreboardName(), others)).visit()) {
            return new PersonSelectors.Decision(command, List.of(), null, null);
        }
        return PersonSelectors.narrow(command, asPlayer.getScoreboardName(), others);
    }

    /**
     * The other-player rule for one command run as {@code asPlayer}, or null when it may run: names
     * no other child online, or is a visit on a world that allows one. Alone in the world, nothing.
     */
    static String reachRefusal(MinecraftServer server, ServerPlayer asPlayer, String command) {
        if (server == null || asPlayer == null) {
            return null;
        }
        List<String> others = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(asPlayer.getUUID())
                    && com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                others.add(p.getScoreboardName());
            }
        }
        if (others.isEmpty()) {
            return null;
        }
        var v = com.moddingfromamod.agent.PlayerReach.check(command,
                com.moddingfromamod.agent.PlayerReach.Who.wisher(asPlayer.getScoreboardName(), others));
        var mode = com.moddingfromamod.world.PlayerVisits.mode();
        if (com.moddingfromamod.agent.PlayerReach.allows(v, mode)) {
            return null;
        }
        com.moddingfromamod.agent.ActionLog.reach("replay", "refused",
                v.refused() ? v.why() : com.moddingfromamod.agent.PlayerReach.WHY_REACH_OFF,
                v.other(), command, others.size());
        return com.moddingfromamod.agent.PlayerReach.refusal(v,
                com.moddingfromamod.world.WorldRulesService.current().workshop());
    }

    /** Carries a Brigadier failure out of {@link WarnCapture#during}'s Supplier. */
    private static final class SyntaxFailure extends RuntimeException {
        final com.mojang.brigadier.exceptions.CommandSyntaxException cause;

        SyntaxFailure(com.mojang.brigadier.exceptions.CommandSyntaxException cause) {
            super(cause.getMessage(), null, false, false);
            this.cause = cause;
        }
    }

    /**
     * The renamed-attribute repair every command gets before it runs, decided by the live
     * registry. Shared with {@link CommandTrial} so a trial judges exactly what will execute.
     */
    public static AttributeIds.Fixed repairAttributeIds(String command) {
        // Every version-specific spelling, not only attributes: see CommandDialect.
        return CommandDialect.correct(command);
    }

    /** Outcome of one command: whether it looked successful, plus whatever it printed. */
    /**
     * @param refusedBy the world rule that refused the command, as data ({@code null} when none did): a replayed wish
     *                  has no agent to read the refusal's words, so its job tells the child this rule instead
     */
    public record Result(boolean success, List<String> output, List<String> ignored,
                         com.moddingfromamod.world.WorldRefusal refusedBy) {
        public Result(boolean success, List<String> output) {
            this(success, output, List.of());
        }

        public Result(boolean success, List<String> output, List<String> ignored) {
            this(success, output, ignored, null);
        }

        /** The same outcome, saying {@code more}. */
        Result saying(List<String> more) {
            return new Result(success, more, ignored, refusedBy);
        }

        public String joined() {
            return String.join(" | ", output);
        }
    }

    static final class Job {
        private final UUID playerId;
        final ServerLevel level;
        private final Vec3 pos;
        final Wish wish;
        final CommandPlan plan;
        /** A served wish's locked spot: where its builds, creatures and pictures land. May be null. */
        private final net.minecraft.core.BlockPos spot;
        /** True when {@link ServedFanfare} already gave it its reveal. */
        private final boolean fanfare;
        private int index;
        private int failures;
        /** The first world rule that refused one of its commands, said once at the end in place of "some bits". */
        private com.moddingfromamod.world.WorldRefusal refusedBy;
        /** What the wand turned away, said once at the end. */
        private final java.util.Set<String> waveLines = new java.util.LinkedHashSet<>();

        Job(UUID playerId, ServerLevel level, Vec3 pos, Wish wish, CommandPlan plan,
            net.minecraft.core.BlockPos spot, boolean fanfare) {
            this.playerId = playerId;
            this.level = level;
            this.pos = pos;
            this.wish = wish;
            this.plan = plan;
            this.spot = spot;
            this.fanfare = fanfare;
        }

        /** Run every command now, in this tick (a served ITEM, whose reveal needs the item). */
        void runAll(MinecraftServer server) {
            while (hasNext()) {
                runNext(server);
            }
        }

        boolean hasNext() {
            return index < plan.commands().size();
        }

        void runNext(MinecraftServer server) {
            String cmd = plan.commands().get(index++);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            // Handle anything this creates, exactly as the agent's own run_command does. A wish
            // granted OFFLINE still produces things the child will want to talk about afterwards —
            // "make it bigger" doesn't care which path built it.
            var before = com.moddingfromamod.mob.EntityCapture.snapshot(level, pos);
            // The served wish's locked spot, for exactly as long as ITS commands run: a build, a
            // creature or a picture lands where the pillar stood, not wherever he has walked since,
            // and the spot never leaks into his next wish (WishProgress.servedSpot).
            // The served wish's own key: its spawns meet ITS wave, not a running agent's.
            Result r = com.moddingfromamod.mob.SpawnScope.during(wish.id().toString(),
                    com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () -> {
                Result ran = WishProgress.withServedSpot(spot, fanfare ? wish.id() : null,
                        () -> runOne(server, level, pos, player, cmd));
                waveLines.addAll(com.moddingfromamod.mob.SpawnScope.current().lines());
                return ran;
            });
            com.moddingfromamod.mob.EntityCapture.rememberNew(level, pos, before);
            if (!r.success()) {
                failures++;
                if (refusedBy == null) {
                    refusedBy = r.refusedBy();
                }
                LOGGER.warn("wish {} command failed: {} -> {}", wish.shortId(), cmd, r.joined());
            } else if (!r.ignored().isEmpty()) {
                // A replay that half-worked: counted as a failure, because the child was
                // promised the whole thing and "Your Great Big Shark is here!" over an ordinary
                // shark is the exact bug. The summon itself stands; the wish reads PARTIAL.
                failures++;
                LOGGER.warn("wish {} command half-worked: {} -> Minecraft ignored {}",
                        wish.shortId(), cmd, r.ignored());
            }
        }

        void finish(MinecraftServer server) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            boolean ok = failures == 0;
            WishBook book = WishBook.get(server);
            book.complete(wish.id(), ok ? WishStatus.DONE : WishStatus.FAILED,
                    ok ? plan.sayToPlayer()
                       : failures + " of " + plan.commands().size() + " parts didn't work");
            if (player != null) {
                if (ok) {
                    WishFeedback.granted(player, plan.sayToPlayer());
                } else if (refusedBy != null) {
                    // a replay has no agent to explain a world rule's refusal: he is told the rule itself
                    WishFeedback.refusedByWorld(player,
                            com.moddingfromamod.world.WorldRulesWords.madeRefused("That wish", refusedBy),
                            plan.sayToPlayer(), wish.shortId());
                } else {
                    WishFeedback.partial(player, plan.sayToPlayer());
                }
                waveLines.forEach(l -> WishFeedback.wave(player, l));
            }
            // THE REVEAL, for a wish no agent granted. A starter, a cached wish or a
            // library entry that spawns a creature records its rig for the reveal
            // (VanillaRigAnimator -> RigReveal.publish), and while a camera client serves,
            // DisplayRig.spawn VEILS it from every stock child until the reveal. The reveal was
            // only ever called by finish_wish and finish_building -- the agent's last word -- so a
            // served creature was never released: once a dedicated server turned its cameras on the
            // starter dragon was invisible to every stock child, only its fire breath showing.
            // This is the served wish's last word. AFTER the announce, as finish_building does, so
            // the good news lands first. Idempotent and free everywhere else: no camera, a modded
            // child, or a state already revealed all return at once (RigReveal.reveal).
            com.moddingfromamod.vanilla.RigReveal.reveal(server);
            // And its grand reveal, which a served wish never had (the Opus demo's sword).
            // A served wish under ServedFanfare has had its reveal already, at the climax.
            if (ok && !fanfare) {
                com.moddingfromamod.wish.reveal.Reveals.served(wish.id(), playerId, plan.commands());
            }
        }
    }
}
