package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.nav.ArtifactLocator;
import com.moddingfromamod.agent.nav.ArtifactLocator.Artifact;
import com.moddingfromamod.builder.BuildRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Take the child to something he already made.</b>
 *
 * <p>From real play, two consecutive wishes (paraphrased):
 *
 * <blockquote><i>"take me to the closest McTrain"</i> — the agent called {@code list_trains},
 * got the right answer, and then built a <b>second</b> McTrain at his feet, 2,900 blocks from the
 * one he meant. His next wish asked for the long McTrain he had been building, not the new one:
 * find it and take him there.</blockquote>
 *
 * <p>The first wish was marked <b>granted</b>, so no completion metric saw it. Nothing was missing:
 * {@code tp} is on the command allowlist and {@code list_trains} had already printed the
 * coordinates. The whole flow is shaped around <i>manifesting</i> something — {@code conjure_at}
 * locks where the wish will appear and the brief says "just build normally" — so a wish whose
 * answer is <i>move the child</i> had no verb in that shape, and the agent reached for the one it
 * had.
 *
 * <p>This is that verb. It builds nothing, which is the point.
 */
public final class TakeMeToTool implements McpTool {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TakeMeToTool.class);

    @Override
    public String name() {
        return "take_me_to";
    }

    @Override
    public String description() {
        return "Teleport the child to something he ALREADY made — "
                + "a building, a machine. "
                + "Use this whenever the wish is about GOING somewhere rather than making "
                + "something: \"take me to my castle\", \"teleport me to "
                + "my slushie machine\", \"where's "
                + "my dragon\", \"I want to go back to it\". It BUILDS NOTHING. Building a second "
                + "one where he is standing is not an answer to \"take me to it\" — he already has "
                + "the one he means, possibly thousands of blocks away, and a fresh copy is a "
                + "different thing that merely fits the words. If he asks you to build another one "
                + "here, that is a normal build wish, not this. It needs no plan_wish first: "
                + "going somewhere changes nothing in the world.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "what", "string", "What he called it — a name or an id, in his own words "
                + "(\"my castle\", \"the big tower\"). Leave it out only if he has made just one thing.");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String what = args.has("what") && !args.get("what").isJsonNull()
                ? args.get("what").getAsString() : "";
        final com.moddingfromamod.marshal.WishRun run = com.moddingfromamod.agent.AgentContext.currentRun();
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running.");
                }
                List<Artifact> known = everythingWithAPosition();
                ArtifactLocator.Match m = ArtifactLocator.resolve(what, known);

                if (m.isAmbiguous()) {
                    StringBuilder sb = new StringBuilder("Which one does he mean? ");
                    sb.append(m.miss()).append(":\n");
                    for (Artifact a : m.ambiguous()) {
                        sb.append("  - ").append(a.describe()).append('\n');
                    }
                    // ASK, do not pick. He arrives somewhere plausible either way, so a wrong
                    // guess is indistinguishable from a right one from where he is standing.
                    sb.append("Ask him which, then call take_me_to again with that name.");
                    return ToolResult.ok(sb.toString());
                }
                if (!m.found()) {
                    StringBuilder sb = new StringBuilder("Cannot take him there — " + m.miss() + ".");
                    // a building he HAS whose position is not known here — say so, plainly,
                    // rather than let the agent build a second one or guess where the first is.
                    String lost = lostBuilding(what);
                    if (lost != null) {
                        sb.append("\n").append(lost);
                    }
                    if (!known.isEmpty()) {
                        sb.append("\nThings he has made that I can take him to:\n");
                        for (Artifact a : known) {
                            sb.append("  - ").append(a.describe()).append('\n');
                        }
                    }
                    return ToolResult.ok(sb.toString());
                }

                Artifact a = m.best();
                ServerPlayer p = com.moddingfromamod.agent.ActingChild.person(server);
                if (p == null) {
                    return ToolResult.noPlayerOnline(com.moddingfromamod.agent.ActingChild.whyNobody(server, name(),
                            "Nobody is in the world to take anywhere."));
                }
                Went went = take(server, p, a);
                if (went.refused() != null) {
                    return ToolResult.error(went.refused());
                }
                if (run != null) {
                    run.wentTo(a.id());   // so it can be taught as `wishvisit <id>`
                }
                return ToolResult.ok("Took him to " + a.describe() + ".\n"
                        + "He is standing at " + went.at() + ", right by it. Nothing was "
                        + "built — this is the one he already had.\n"
                        + "He came from " + went.from() + "; if he asks to go back, that "
                        + "is the spot.\n"
                        + "To make this a button on the watch, teach add_template commands "
                        + "[\"" + VISIT + " " + a.id() + "\"] — it FINDS the " + a.kind() + " each time, "
                        + "wherever it is then.");
            }, 15_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * Why a building {@code what} names is not somewhere to go — taken down, in another
     * world, or built before positions were saved — or null when no such building is saved.
     */
    static String lostBuilding(String what) {
        List<Artifact> lost = new ArrayList<>();
        for (String id : BuildRegistry.ids()) {
            var plan = BuildRegistry.get(id);
            if (plan != null && !BuildRegistry.standing(id)) {
                lost.add(new Artifact("building", id, plan.name(), 0, 0, 0, ""));
            }
        }
        if (lost.isEmpty()) {
            return null;
        }
        ArtifactLocator.Match m = ArtifactLocator.resolve(what, lost);
        return m.found() ? BuildRegistry.whyNotHere(m.best().id()) : null;
    }

    /** The replay command for this tool: {@code wishvisit <id>}, see {@link #register}. */
    public static final String VISIT = "wishvisit";

    /**
     * Take {@code p} to {@code a}: the reach rule, a safe spot above its anchor, the teleport.
     * Shared by the tool and {@code wishvisit}, so a taught button obeys exactly what the agent did.
     *
     * @return where he went, or why not (he was not moved)
     */
    static Went take(net.minecraft.server.MinecraftServer server, ServerPlayer p, Artifact a) {
        // THE REACH RULE'S DESTINATION HALF. On an islands world every child's things
        // are in one list, so "take me to the castle" could land him on a neighbour's island.
        // BaseReach cannot see the destination before this tool resolves it, so the check
        // is here, where it is known — and it refuses rather than picks another. Asked of
        // Bases, so a world whose bases are plots rather than islands is judged alike.
        var bases = com.moddingfromamod.world.WorldModuleHost.liveBases();
        var rulesNow = com.moddingfromamod.world.WorldRulesService.loadedOrNull();
        if (bases != null && (rulesNow == null || rulesNow.baseReach())) {
            var mine = bases.baseOf(p.getUUID());
            boolean home = mine != null
                    // the recorded dimension is String.valueOf(a key), "null" when unknown
                    && (a.dimension() == null || a.dimension().isEmpty()
                        || "null".equals(a.dimension()) || a.dimension().contains("overworld"))
                    && mine.area().contains(a.x() + 0.5, a.z() + 0.5);
            if (!home) {
                String noun = bases.noun();
                return Went.refused("REFUSED — NOT HIS " + noun.toUpperCase(java.util.Locale.ROOT)
                        + ": take_me_to did not run and he "
                        + "was not moved. " + a.describe() + " is not on his own " + noun + ", and on "
                        + "this world a wish only takes him around his own. Tell him so, or take "
                        + "him to something he made on his " + noun + ".");
            }
        }
        ServerLevel level = com.moddingfromamod.agent.ArtifactLevel
                .resolve(server, dimensionKeyOf(a));
        BlockPos at = safeSpotAbove(level, new BlockPos(a.x(), a.y(), a.z()));
        // WHERE HE WAS, read before he is moved. This is what makes "take me back" a real
        // answer rather than a second guess, and it is why this tool is REVERSIBLE in
        // UndoContract: the inverse exists and is in the agent's hands.
        BlockPos from = p.blockPosition();
        com.moddingfromamod.compat.Teleports.player(p, level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                p.getYRot(), p.getXRot());
        LOGGER.info("took {} to {} at {} (from {})", p.getScoreboardName(), a.id(),
                at.toShortString(), from.toShortString());
        return new Went(null, at.toShortString(), from.toShortString());
    }

    /** What {@link #take} did: {@code refused} is null when he went. */
    record Went(String refused, String at, String from) {
        static Went refused(String why) {
            return new Went(why, null, null);
        }
    }

    /**
     * {@code /wishvisit <id>} — the button form of {@code take_me_to}. A REPLAY command
     * (permission 2, the executor's level; hidden from a child's tab list): it looks the build
     * up by id EVERY time and takes him to where it is now, never to remembered
     * coordinates, so a button keeps working after the thing is moved or rebuilt.
     */
    public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d) {
        d.register(net.minecraft.commands.Commands.literal(VISIT)
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                // greedyString, not word(): a forked building is `<id>~<short uuid>` and
                // brigadier's word() refuses `~`.
                .then(net.minecraft.commands.Commands.argument("id",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id").trim();
                            Artifact a = byId(everythingWithAPosition(), id);
                            if (a == null) {
                                ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                                        "I can't find '" + id + "' any more."));
                                return 0;
                            }
                            if (take(ctx.getSource().getServer(), p, a).refused() != null) {
                                ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                                        "That one isn't somewhere I can take you."));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                                    "✨ Here's your " + a.name() + "!"), false);
                            return 1;
                        })));
    }

    /** Exact id, case-insensitive; null when nothing has it. Pure. */
    static Artifact byId(List<Artifact> known, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Artifact a : known) {
            if (a.id().equalsIgnoreCase(id.strip())) {
                return a;
            }
        }
        return null;
    }

    /**
     * Everything he has made that recorded WHERE it is.
     *
     * <p>Creatures are deliberately absent: {@code SpeciesRegistry} stores what a species IS, not
     * where any individual of it is standing, and a creature walks. "Where's my dragon" needs an
     * entity lookup rather than a registry read, and answering it with a stale spawn point would
     * be the same kind of confident-but-wrong answer this tool exists to stop.
     */
    static List<Artifact> everythingWithAPosition() {
        List<Artifact> out = new ArrayList<>();
        for (String id : BuildRegistry.ids()) {
            Long packed = BuildRegistry.anchorOf(id);
            var plan = BuildRegistry.get(id);
            // STANDING, not merely remembered: one taken down is air at a remembered corner.
            if (packed != null && plan != null && BuildRegistry.standing(id)) {
                BlockPos at = BlockPos.of(packed);
                out.add(new Artifact("building", id, plan.name(), at.getX(), at.getY(), at.getZ(),
                        String.valueOf(BuildRegistry.dimensionOf(id))));
            }
        }
        return out;
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
            dimensionKeyOf(Artifact a) {
        try {
            return net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.parse(a.dimension()));
        } catch (Exception e) {
            return null;                    // ArtifactLevel.resolve falls back to the overworld
        }
    }

    /**
     * A spot he can stand in, just above the recorded anchor.
     *
     * <p>An artifact's anchor is its own origin — for a building it is a corner of the foundation, so teleporting exactly onto it can drop him inside solid
     * blocks. Climbing to the first free pair of blocks costs nothing and is the difference between
     * arriving and suffocating.
     */
    private static BlockPos safeSpotAbove(ServerLevel level, BlockPos anchor) {
        if (level == null) {
            return anchor.above();
        }
        BlockPos at = anchor;
        for (int i = 0; i < 24; i++) {
            if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()) {
                return at;
            }
            at = at.above();
        }
        return anchor.above();
    }
}
