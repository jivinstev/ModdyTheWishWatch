package com.moddingfromamod.agent;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * <b>Which world does this artifact live in?</b> — asked of the artifact, never of a player.
 *
 * <p>Every tool that acts on something already built (verify it, feed it, modify it, remove it)
 * needs a {@link ServerLevel}, and the obvious way to get one is wrong:
 *
 * <pre>{@code
 * // WRONG. Do not reintroduce this.
 * ServerLevel level = server.getPlayerList().getPlayers().stream()
 *         .findFirst().map(p -> p.serverLevel()).orElse(server.overworld());
 * }</pre>
 *
 * <p>That resolves to <i>whichever player happens to be online</i>. It is right only while exactly
 * one player is logged in and standing in the world the artifact was built in — and it fails
 * <b>silently</b> the moment they step through a portal, log out, or a second player joins. A
 * machine probed in the wrong world reads as "no moving parts"; a train checked in the wrong world
 * reads as "never built"; a build cleared in the wrong world is terrain vandalism somewhere else.
 *
 * <p>This is the level half of the defect class behind (the other half is asserting on a
 * spatial scan instead of the owning system's model). The rule, Phase 1:
 *
 * <blockquote>Pick the anchor — and the world — from the artifact's own recorded origin. Never from
 * spawn, never from a player.</blockquote>
 *
 * <p>So each registry records the dimension its artifact was built in, and every such tool resolves
 * through here. Named and centralised deliberately: a grep for {@code ArtifactLevel} finds every
 * place that gets this right, which is what makes the remaining places findable.
 */
public final class ArtifactLevel {

    private ArtifactLevel() {
    }

    /**
     * The world an artifact was recorded in.
     *
     * @param recorded the dimension stored when the artifact was built; null for artifacts saved
     *                 before dimensions were recorded, or whose stored value did not parse
     * @return that world, or the overworld when nothing usable was recorded — a stable, explainable
     *         guess, rather than one that changes with who is logged in
     */
    public static ServerLevel resolve(MinecraftServer server, ResourceKey<Level> recorded) {
        if (server == null) {
            return null;
        }
        ServerLevel level = recorded == null ? null : server.getLevel(recorded);
        return level != null ? level : server.overworld();
    }

    /** True when we are guessing rather than using a recorded dimension — worth telling the agent. */
    public static boolean isGuess(MinecraftServer server, ResourceKey<Level> recorded) {
        return server == null || recorded == null || server.getLevel(recorded) == null;
    }
}
