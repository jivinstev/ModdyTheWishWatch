package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Shows him where the ore is — the half that touches the game.</b> While a thing
 * carrying {@code reveals} is in his hand or on his body:
 * <ul>
 *   <li><b>glow</b> — the nearest ores within {@link RevealRule#RADIUS} blocks each wear a glowing
 *       block display of themselves, so their outline shows through the stone (a stock client draws a
 *       glowing entity's outline through walls). Only what changed is added or removed; when he puts
 *       it away they go.</li>
 *   <li><b>beep</b> — a bell only HE hears, coming faster and higher the closer the nearest ore.</li>
 * </ul>
 * A display is never saved into his world for good: one that loads from disk with our tag is
 * refused, so a crash cannot leave ghosts.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Reveal {

    /** E1's label: the map row names {@code locate_ore} (syn-2-036). */
    public static final Set<String> PARTS = Set.of("locate_ore");

    public static final String TAG = "mfam_reveal";
    static final int PERIOD = 10;

    private static final Map<UUID, Map<BlockPos, UUID>> GLOWING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_BEEP = new ConcurrentHashMap<>();
    /** The pitch of his last beep, for the gate: a beep is a sound, and a sound cannot be asserted. */
    static final Map<UUID, Float> LAST_PITCH = new ConcurrentHashMap<>();

    private Reveal() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (now % PERIOD == 0) {
                look(p);
            }
            beep(p, now);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity().getTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    /** Glow what is near, or clear it when he no longer carries the thing. */
    public static void look(ServerPlayer p) {
        String[] r = carried(p);
        Map<BlockPos, UUID> had = GLOWING.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        List<BlockPos> ores = r == null || !"glow".equals(r[1]) ? List.of() : nearest(p, r[0], RevealRule.MAX_GLOW);
        ServerLevel level = p.level();
        for (var it = had.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!ores.contains(e.getKey())) {
                Entity d = level.getEntity(e.getValue());
                if (d != null) {
                    d.discard();
                }
                it.remove();
            }
        }
        for (BlockPos at : ores) {
            if (!had.containsKey(at)) {
                Entity d = display(level, at, level.getBlockState(at));
                if (d != null) {
                    had.put(at.immutable(), d.getUUID());
                }
            }
        }
    }

    /** How many are glowing for him right now. */
    public static int glowing(ServerPlayer p) {
        Map<BlockPos, UUID> had = GLOWING.get(p.getUUID());
        return had == null ? 0 : had.size();
    }

    static void beep(ServerPlayer p, long now) {
        Long due = NEXT_BEEP.get(p.getUUID());
        if (due != null && now < due) {
            return;
        }
        String[] r = carried(p);
        if (r == null || !"beep".equals(r[1])) {
            NEXT_BEEP.remove(p.getUUID());
            return;
        }
        List<BlockPos> near = nearest(p, r[0], 1);
        if (near.isEmpty()) {
            NEXT_BEEP.put(p.getUUID(), now + 20);
            return;
        }
        double d = Math.sqrt(near.get(0).distToCenterSqr(p.getX(), p.getEyeY(), p.getZ()));
        float pitch = RevealRule.beepPitch(d);
        com.moddingfromamod.mv.NotifySound.play(p,SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, pitch);
        LAST_PITCH.put(p.getUUID(), pitch);
        NEXT_BEEP.put(p.getUUID(), now + RevealRule.beepInterval(d));
    }

    /** The nearest ores of this kind around him, nearest first. */
    static List<BlockPos> nearest(ServerPlayer p, String ore, int max) {
        ServerLevel level = p.level();
        BlockPos c = p.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        int r = RevealRule.RADIUS;
        for (BlockPos at : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (matches(level.getBlockState(at), ore)) {
                out.add(at.immutable());
            }
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(c)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static boolean matches(BlockState state, String ore) {
        for (TagKey<Block> t : tagsOf(ore)) {
            if (state.is(t)) {
                return true;
            }
        }
        return false;
    }

    private static List<TagKey<Block>> tagsOf(String ore) {
        List<TagKey<Block>> out = new ArrayList<>();
        for (String id : RevealRule.ORES.getOrDefault(ore, RevealRule.ORES.get("any"))) {
            out.add(TagKey.create(Registries.BLOCK, Identifier.parse(id)));
        }
        return out;
    }

    private static Entity display(ServerLevel level, BlockPos at, BlockState state) {
        Entity e = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (e == null) {
            return null;
        }
        e.snapTo(at.getX(), at.getY(), at.getZ(), 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(e);
        tag.put("block_state", NbtUtils.writeBlockState(state));
        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);
        com.moddingfromamod.compat.EntityNbt.load(e, tag);
        e.setGlowingTag(true);
        e.addTag(TAG);
        level.addFreshEntity(e);
        return e;
    }

    /** {ore, how} on anything in his hands or on his body, or null. */
    static String[] carried(ServerPlayer p) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = p.getItemBySlot(slot);
            CustomData data = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
            if (data == null) {
                continue;
            }
            CompoundTag all = data.copyTag();
            if (all.contains(com.moddingfromamod.datapack.ItemProperties.REVEALS_DATA)) {
                CompoundTag t = all.getCompoundOrEmpty(com.moddingfromamod.datapack.ItemProperties.REVEALS_DATA);
                String ore = RevealRule.ore(t.getStringOr("ore", ""));
                String how = t.getStringOr("how", "");
                if (ore != null && RevealRule.HOW.contains(how)) {
                    return new String[]{ore, how};
                }
            }
        }
        return null;
    }
}
