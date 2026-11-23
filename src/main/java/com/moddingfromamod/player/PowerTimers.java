package com.moddingfromamod.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>A power with a time limit really ends</b>. {@code modify_player}'s potion buffs carry their own
 * duration, but a size ({@code giant}, {@code tiny}), a stat ({@code no_fall_damage}, {@code no_knockback}, …)
 * and {@code never_die} are attribute base values and a flag: they have no clock, and vanilla copies attributes
 * onto the new body at every respawn ({@link SurviveDeath}). So a "short boost" of one of those lasted forever.
 *
 * <p>Each timed power is written down here, with its wall-clock end, under the persisted tag NeoForge copies onto
 * the new body — so the end survives a death and a log-out, and is never extended by either. Once a second the
 * server takes off every power whose time is up ({@code ModifyPlayerTool.applyOne} with remove) and tells him.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PowerTimers {

    static final String KEY = "mfam_power_timers";

    private PowerTimers() {
    }

    /** Remember that {@code power} (at {@code level}) comes off {@code player} at {@code untilMs}. */
    public static void schedule(Player player, String power, String level, long untilMs) {
        CompoundTag persisted = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getListOrEmpty(KEY);
        ListTag out = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            if (!power.equals(t.getStringOr("power", ""))) {
                out.add(t);
            }
        }
        CompoundTag t = new CompoundTag();
        t.putString("power", power);
        t.putString("level", level);
        t.putLong("until", untilMs);
        out.add(t);
        persisted.put(KEY, out);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Forget a power's end (it was taken away by hand, or given again forever). */
    public static void cancel(Player player, String power) {
        CompoundTag persisted = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getListOrEmpty(KEY);
        ListTag out = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            if (!power.equals(list.getCompoundOrEmpty(i).getStringOr("power", ""))) {
                out.add(list.getCompoundOrEmpty(i));
            }
        }
        persisted.put(KEY, out);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** The powers on a timer, for a test or inspect_player. */
    public static List<String> timed(Player player) {
        ListTag list = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).getListOrEmpty(KEY);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getCompoundOrEmpty(i).getStringOr("power", ""));
        }
        return out;
    }

    /** Take off every power of his whose time is up at {@code nowMs}. Returns their ids. */
    public static List<String> expire(ServerPlayer player, long nowMs) {
        CompoundTag persisted = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getListOrEmpty(KEY);
        if (list.isEmpty()) {
            return List.of();
        }
        ListTag keep = new ListTag();
        List<String> ended = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            if (t.getLongOr("until", 0L) > nowMs) {
                keep.add(t);
                continue;
            }
            PlayerPowers.Spec spec = PlayerPowers.find(t.getStringOr("power", ""));
            if (spec != null) {
                com.moddingfromamod.agent.tools.ModifyPlayerTool.applyOne(player, spec, true, 0,
                        PlayerPowers.level(t.getStringOr("level", "normal")), new ArrayList<>());
                ended.add(spec.id());
            }
        }
        persisted.put(KEY, keep);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        if (!ended.isEmpty()) {
            player.sendSystemMessage(Component.literal("✨ Your " + String.join(", ", ended).replace('_', ' ')
                    .toLowerCase(Locale.ROOT) + " boost wore off.").withStyle(ChatFormatting.AQUA));
        }
        return ended;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            expire(p, now);
        }
    }
}
