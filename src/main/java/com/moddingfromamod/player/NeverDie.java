package com.moddingfromamod.player;

import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * <b>"let me get hurt but NEVER die."</b>
 *
 * <p>A real wish, made in those words, and the most expensive one in the whole measured matrix:
 * 29 tool calls and 162 seconds in one run, 111 in another. In BOTH the agent tested its own
 * hand-written revive script by attacking the child — {@code damage @p 19},
 * {@code damage @p 1000}, {@code data modify entity @p Health set value 2.0f} — and then saved no
 * way back to it. See.
 *
 * <p>The wish is precise and this honours it exactly: <b>damage still lands and the health bar
 * still drops</b>. Only the killing blow is refused, and it leaves him on a sliver of health rather
 * than topped up, so getting hurt still means something. Answering this with invincibility would
 * remove the half he actually asked for.
 *
 * <p>The flag lives in the player's persistent data, so it survives death and relog — a power that
 * quietly lapsed the first time it was needed would be worse than never granting it.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class NeverDie {

    /** Under the vanilla-persisted tag, so it is not lost on respawn. */
    private static final String KEY = "mfam_never_die";

    /** What he is left standing on after a blow that would have killed him. */
    private static final float SURVIVE_ON = 1.0f;

    private NeverDie() {
    }

    /** Turn it on or off for this player, permanently. */
    /**
     * Turn it on or off for this player, permanently.
     *
     * <p>{@code Player}, not {@code ServerPlayer}: nothing here needs the server subclass — the flag
     * lives in persistent data that any entity has — and narrowing it would put the whole feature
     * out of reach of a headless test.
     */
    public static void set(Player player, boolean on) {
        var data = player.getPersistentData();
        var tag = data.getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        tag.putBoolean(KEY, on);
        data.put(Player.PERSISTED_NBT_TAG, tag);
    }

    /** Whether this player currently cannot die. */
    public static boolean isOn(Player player) {
        return player.getPersistentData()
                .getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).getBooleanOr(KEY, false);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        // `Player`, not `ServerPlayer`. Only a server player can die in a real wish, so this costs
        // nothing in the game and it is the difference between this branch having a headless gate
        // and having none at all.
        if (!(event.getEntity() instanceof Player player) || !isOn(player)) {
            return;
        }
        event.setCanceled(true);
        // Left on a sliver rather than healed to full: he asked to be HURT, and a full heal on
        // every lethal hit turns the wish into invincibility by the back door.
        player.setHealth(SURVIVE_ON);
        player.clearFire();
        // Without this the next tick of whatever was killing him kills him again on the same tick
        // budget, which reads as the power not working rather than as a very fast second death.
        player.setInvulnerableTime(40);
    }
}
