package com.moddingfromamod.compat;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>Tell a client, again, whether its player is using an item.</b>
 *
 * <p>A stock client that starts using an item (a bundle emptying, a bow drawing, food) does so on
 * its OWN prediction and keeps going every tick until it hears otherwise. When the server refuses
 * that use, its own copy of the flag never changed, so {@code SynchedEntityData} has nothing dirty
 * to send and the client keeps "using" — a bundle it believes is still dumping its contents. This
 * sends the server's real value anyway; {@code LocalPlayer.onSyncedDataUpdated} stops the use when
 * the bit is clear.
 *
 * <p>Abstract and never constructed: extending {@link LivingEntity} is only what makes its
 * {@code protected static} accessor readable here without an access transformer.
 */
public abstract class LivingFlags extends LivingEntity {

    private LivingFlags() {
        super(null, null);
    }

    /** Re-send his living-entity flags (bit 1 = using an item) to his own client. */
    public static void resend(ServerPlayer player) {
        EntityDataAccessor<Byte> flags = DATA_LIVING_ENTITY_FLAGS;
        player.connection.send(new ClientboundSetEntityDataPacket(player.getId(),
                List.of(SynchedEntityData.DataValue.create(flags, player.getEntityData().get(flags)))));
    }
}
