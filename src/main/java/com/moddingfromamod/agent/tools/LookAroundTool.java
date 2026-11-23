package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Situational awareness. Without this the agent is writing commands blind — it wouldn't know whether
 * the child is underwater, in a cave, or standing on a mountain, and "build me a tower" would land
 * inside a hill.
 */
public final class LookAroundTool implements McpTool {

    @Override
    public String name() {
        return "look_around";
    }

    @Override
    public String description() {
        return "See where the child is, what is around them, and the CURRENT size and health of every nearby mob — read this before changing anything, because modify_mob takes absolute numbers. Also lists saved buildings. Plus position, dimension, biome, "
                + "time of day, weather, what they are standing on, and how much open space is "
                + "above them. Use this before building or spawning anything.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    /**
     * <b>Wait for his body to be his own before describing where he is.</b>
     *
     * <p>A capture session outlives the {@code photograph} call that opened it — it ends on a
     * timer, not on release — so without this every positional answer below can be about a
     * photography seat instead of about home: his position, the block under him, the biome, and
     * above all what is around him. See {@link com.moddingfromamod.runtime.CaptureSettle} for why
     * waiting beats answering from the remembered origin (the short version: block reads
     * force-load and answer correctly while entity reads come back silently EMPTY, so a
     * report built that way says "nothing is around him" with a farm standing there).
     *
     * <p>It costs nothing in the ordinary case — no session, no wait — and the ceiling is
     * {@link com.moddingfromamod.runtime.CaptureTimings#settleWaitMs()}, derived from the hard cap
     * it is waiting on. Note it is done in TWO hops on purpose: resolve who, wait OFF the server
     * thread, then read the world. Waiting inside a single hop would stop the tick that has to
     * release him.
     */
    @Override
    public ToolResult call(JsonObject args) {
        try {
            java.util.UUID who = AgentBridge.onServer(() -> {
                ServerPlayer p = com.moddingfromamod.agent.ActingChild.person(
                        AgentBridge.serverOrNull());
                return p == null ? null : p.getUUID();
            });
            var settled = who == null
                    ? com.moddingfromamod.runtime.CaptureSettle.Verdict.ALREADY_FREE
                    : com.moddingfromamod.wish.WishCapture.awaitSettled(who);
            return ToolResult.ok(AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.person(server);
                if (player == null) {
                    return com.moddingfromamod.agent.ActingChild.whyNobody(server, name(), "Nobody is in the world right now.");
                }
                // THE FALLBACK, and it deliberately does NOT answer from the remembered origin.
                // Reaching here means the watchdog that "ALWAYS releases him" did not, i.e. a real
                // fault -- and in a fault the live body is the one place the world is completely
                // readable (its chunks are loaded and its entities exist), while home is the place
                // where the mob list would come back empty and read as fact. So the report stays
                // live, self-consistent and complete, and SAYS the position is not his own.
                var home = settled.settled() ? null
                        : com.moddingfromamod.wish.WishCapture.homeOf(player.getUUID());
                String held = com.moddingfromamod.runtime.CaptureSettle.notice(settled,
                        home == null ? null : Math.round(home.x) + " " + Math.round(home.y) + " "
                                + Math.round(home.z));
                ServerLevel level = player.level();
                BlockPos pos = player.blockPosition();
                BlockPos below = pos.below();
                int headroom = 0;
                for (int i = 1; i <= 24; i++) {
                    if (!level.getBlockState(pos.above(i)).isAir()) {
                        break;
                    }
                    headroom++;
                }
                long dayTime = level.getDayTime() % 24000L;
                String base = "player: " + player.getGameProfile().getName()
                        + "\nposition: " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + "\ndimension: " + level.dimension().location()
                        + "\nbiome: " + level.getBiome(pos).unwrapKey()
                            .map(k -> k.location().toString()).orElse("unknown")
                        + "\nstanding on: " + level.getBlockState(below).getBlock()
                            .getDescriptionId()
                        + "\nopen space above: " + headroom + " blocks"
                        + "\ntime: " + dayTime + (dayTime > 13000 ? " (night)" : " (day)")
                        + "\nweather: " + (level.isThundering() ? "thunder"
                            : level.isRaining() ? "rain" : "clear")
                        + "\ngamemode: " + player.gameMode.getGameModeForPlayer().getName();
                return held + base + "\n\n"
                        + com.moddingfromamod.agent.Surroundings.describe(
                                player.level(), player.position(), seenWhenWished());
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** §3: what the wish this worker grants saw when he wished, or null (no wish, or none read). */
    private static com.moddingfromamod.wishbuilder.BuilderSuggest.Seen seenWhenWished() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : run.sawWhenWished();
    }
}
