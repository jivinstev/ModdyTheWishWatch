package com.moddingfromamod.compat;

import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Which keys a riding child is holding, read on the SERVER</b> (1.21.4 half).
 *
 * <p>1.21.2 replaced the rider's two movement floats with one {@code Input} record, and the server
 * stopped writing {@code xxa}/{@code zza} from the input packet: it keeps the record in
 * {@code getLastClientInput()}. The 1.21.1 half reads the floats {@code setPlayerInput} still sets.
 */
public final class RiderInput {
    private RiderInput() {
    }

    /** +1 forward (W), -1 back (S), 0 neither. */
    public static float forward(ServerPlayer p) {
        var in = p.getLastClientInput();
        return (in.forward() ? 1f : 0f) - (in.backward() ? 1f : 0f);
    }

    /** +1 left (A), -1 right (D), 0 neither. */
    public static float strafe(ServerPlayer p) {
        var in = p.getLastClientInput();
        return (in.left() ? 1f : 0f) - (in.right() ? 1f : 0f);
    }

    /** Is he holding jump (space)? */
    public static boolean jump(ServerPlayer p) {
        return p.getLastClientInput().jump();
    }

    /** Hold these keys, as the input packet would (for a GameTest's mock rider). */
    public static void hold(ServerPlayer p, float forward, float strafe) {
        p.setLastClientInput(new net.minecraft.world.entity.player.Input(forward > 0, forward < 0,
                strafe > 0, strafe < 0, false, false, false));
    }
}
