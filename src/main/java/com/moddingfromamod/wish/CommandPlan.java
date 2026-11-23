package com.moddingfromamod.wish;

import java.util.List;

/**
 * What a wish actually does, once resolved: a short list of Minecraft commands to run, a friendly
 * line to tell the player, and how many blocks/entities of budget it's allowed to consume.
 *
 * <p>Pure data — no Minecraft imports — so the whole "wish → commands" decision is unit-testable
 * without booting the game. {@code WishExecutor} is the only thing that turns this into world state.
 *
 * <p>Commands are written WITHOUT a leading slash (that's what {@code Commands#performPrefixedCommand}
 * expects) and are validated by {@link CommandGuard} before anything runs.
 */
public record CommandPlan(List<String> commands, String sayToPlayer, int budget) {

    /** Hard ceiling on commands in one plan — a runaway template can't flood the server thread. */
    public static final int MAX_COMMANDS = 256;

    public CommandPlan {
        commands = List.copyOf(commands);
        if (commands.size() > MAX_COMMANDS) {
            commands = commands.subList(0, MAX_COMMANDS);
        }
        sayToPlayer = sayToPlayer == null ? "" : sayToPlayer;
        budget = Math.max(1, budget);
    }

    public static CommandPlan of(List<String> commands, String say) {
        return new CommandPlan(commands, say, Math.max(1, commands.size()));
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }
}
