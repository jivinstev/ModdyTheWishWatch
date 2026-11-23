package com.moddingfromamod.builder;

import com.moddingfromamod.vanilla.SpotSeat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * <b>{@code wishspot <thing>:<spot> run <command>}</b> (widened to anything an
 * {@code at} names — a building's own or automatic spot, a creature, {@code me}) — run a command ON
 * that spot: {@code wishspot castle:roof run summon minecraft:ender_dragon ~ ~ ~ {NoAI:1b}}, {@code
 * wishspot oasis:counter run setblock ~ ~ ~ minecraft:chest}.
 *
 * <p>Exists because the first routing arm (32 runs) found the agent marking a spot every time and
 * then, for a REAL mob (an ender dragon on the roof, an armour-stand knight at the gate), typing
 * the summon at coordinates it worked out by hand — a REAL mob has no {@code at}. This is the same
 * mechanism for anything: positioned at the spot (its block centre, at its feet), facing the
 * spot's way, in the building's world, and only once the building is finished. A recipe that
 * holds it re-runs it on the spot wherever the building stands next time.
 *
 * <p>The inner command is checked by {@code CommandGuard} like an {@code execute … run} tail.
 */
public final class SpotCommand {

    private SpotCommand() {
    }

    /** {@code "castle:roof run summon …"} → {@code [castle:roof, summon …]}, or null. */
    public static String[] split(String rest) {
        if (rest == null) {
            return null;
        }
        String r = rest.trim();
        int sp = r.indexOf(' ');
        if (sp <= 0) {
            return null;
        }
        String at = r.substring(0, sp);
        String tail = r.substring(sp).trim();
        if (!tail.toLowerCase(java.util.Locale.ROOT).startsWith("run ")) {
            return null;
        }
        String cmd = tail.substring(4).trim();
        return cmd.isEmpty() ? null : new String[]{at, cmd};
    }

    static int run(CommandSourceStack src, String rest) {
        String[] parts = split(rest);
        if (parts == null) {
            src.sendFailure(Component.literal("Use: wishspot <thing>:<spot> run <command> — a building, a creature or me"));
            return 0;
        }
        String at = parts[0], cmd = parts[1];
        if (!com.moddingfromamod.wish.CommandGuard.isAllowed(cmd)) {
            src.sendFailure(Component.literal("That command is not allowed on a spot: " + cmd));
            return 0;
        }
        var server = src.getServer();
        String build = SpotSeat.buildOf(at);
        if (build != null && Builder.isBuilding(build)) {
            // The building this replay is putting up: the link is the one he is watching rise.
            // Captured NOW, with the caller's session and slot name (AgentContext.carried).
            java.util.function.Supplier<Void> go = com.moddingfromamod.agent.AgentContext.carried(() -> {
                runNow(src, at, cmd, true);
                return null;
            });
            Builder.whenBuilt(build, go::get);
            src.sendSuccess(() -> Component.literal("✨ That will happen at " + at
                    + " the moment the building is finished (it is still going up)."), false);
            return 1;
        }
        return runNow(src, at, cmd, false);
    }

    private static int runNow(CommandSourceStack src, String at, String cmd, boolean trustLink) {
        // option C: a replay follows his aim — the spot of that name on the building he is
        // looking at, else where he aims — unless the agent is in the loop (SpotSeat.replay).
        SpotSeat.Place p = SpotSeat.place(src.getServer(), src.getPlayer(), at, trustLink);
        if (p.why() != null) {
            src.sendFailure(Component.literal("Could not use " + at + ": " + p.why()
                    + "."));
            return 0;
        }
        CommandSourceStack there = src.withLevel(p.level()).withPosition(new Vec3(p.x(), p.y(), p.z()))
                .withRotation(new Vec2(0f, p.yaw()));
        src.getServer().getCommands().performPrefixedCommand(there, cmd);
        src.sendSuccess(() -> Component.literal(p.link() == null
                ? "✨ Ran where you are looking: " + cmd + p.note()
                : "✨ Ran on " + p.link() + ": " + cmd + p.note()), false);
        return 1;
    }
}
