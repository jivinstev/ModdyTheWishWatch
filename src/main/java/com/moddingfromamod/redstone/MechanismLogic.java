package com.moddingfromamod.redstone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs every mechanism's logic rules ({@link Logic}) in play: four times a second it looks at what each rule reads (who
 * is near its sensor, what is in its coin slot, which code buttons went down, whether it is night, whether its target
 * was hit) and puts or takes away the redstone block at the mechanism's logic cell. Only mechanisms in loaded chunks
 * run; nothing here changes a block but the logic cell itself and a paid coin.
 *
 * <p>A rule is stored with its mechanism ({@link MechanismStore.Entry#logic()}): {@code {"cell":[x,y,z], "rule":{…},
 * "sensor":[x,y,z], "slot":[x,y,z], "buttons":[[x,y,z],…], "target":[x,y,z]}}.
 */
@EventBusSubscriber
public final class MechanismLogic {

    /** How often the rules run, in ticks. */
    public static final int EVERY = 5;

    private static final Map<String, Logic.Memory> MEMORY = new HashMap<>();
    private static final Map<String, Set<Integer>> DOWN = new HashMap<>();
    private static int tick;

    private MechanismLogic() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick % EVERY != 0) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        List<MechanismStore.Entry> all;
        try {
            all = MechanismStore.all(level);
        } catch (RuntimeException e) {
            return;                              // no world folder yet (a test, a menu)
        }
        for (MechanismStore.Entry e : all) {
            if (e.logic() == null || e.logic().isEmpty()) {
                continue;
            }
            for (int i = 0; i < e.logic().size(); i++) {
                run(level, e.id() + "#" + i, e.logic().get(i).getAsJsonObject());
            }
        }
    }

    static BlockPos pos(JsonElement a) {
        JsonArray j = a.getAsJsonArray();
        return new BlockPos(j.get(0).getAsInt(), j.get(1).getAsInt(), j.get(2).getAsInt());
    }

    /** One rule, one step. Server thread. */
    static void run(ServerLevel level, String key, JsonObject o) {
        BlockPos cell = pos(o.get("cell"));
        if (!level.isLoaded(cell)) {
            return;
        }
        Logic.Rule rule = rule(o.getAsJsonObject("rule"));
        BlockPos sensor = o.has("sensor") ? pos(o.get("sensor")) : cell;
        Set<String> players = new HashSet<>(), mobs = new HashSet<>();
        if (rule.kind().equals("player_near") || rule.kind().equals("mob_near")) {
            AABB box = new AABB(sensor).inflate(Math.max(1, rule.radius()));
            for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (le instanceof Player p) {
                    if (!p.isSpectator()) {
                        players.add(p.getGameProfile().getName());
                    }
                } else if (!le.getTags().contains(MechanismWorld.PROOF_TAG)) {
                    mobs.add(BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString());
                }
            }
        }
        int held = 0;
        Container slot = null;
        if (o.has("slot") && level.getBlockEntity(pos(o.get("slot"))) instanceof Container c) {
            slot = c;
            for (int i = 0; i < c.getContainerSize(); i++) {
                var st = c.getItem(i);
                if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(rule.item())) {
                    held += st.getCount();
                }
            }
        }
        List<Integer> pressed = new ArrayList<>();
        if (o.has("buttons")) {
            Set<Integer> was = DOWN.computeIfAbsent(key, k -> new HashSet<>());
            JsonArray bs = o.getAsJsonArray("buttons");
            for (int i = 0; i < bs.size(); i++) {
                BlockState b = level.getBlockState(pos(bs.get(i)));
                boolean down = b.getBlock() instanceof ButtonBlock && b.getValue(ButtonBlock.POWERED)
                        || b.hasProperty(BlockStateProperties.POWERED) && b.getValue(BlockStateProperties.POWERED);
                if (down && !was.contains(i)) {
                    pressed.add(i);
                }
                if (down) {
                    was.add(i);
                } else {
                    was.remove(i);
                }
            }
        }
        int target = 0;
        if (o.has("target")) {
            BlockState t = level.getBlockState(pos(o.get("target")));
            if (t.hasProperty(BlockStateProperties.POWER)) {
                target = t.getValue(BlockStateProperties.POWER);
            }
        }
        boolean night = level.isDarkOutside();
        Logic.Memory m = MEMORY.computeIfAbsent(key, k -> new Logic.Memory());
        boolean on = Logic.step(rule, new Logic.Seen(players, mobs, held, pressed, night, target, level.getGameTime()), m);
        if (m.take > 0 && slot != null) {
            take(slot, rule.item(), m.take);
        }
        BlockState now = level.getBlockState(cell);
        if (on && !now.is(Blocks.REDSTONE_BLOCK)) {
            level.setBlock(cell, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        } else if (!on && now.is(Blocks.REDSTONE_BLOCK)) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    static void take(Container c, String item, int n) {
        for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
            var st = c.getItem(i);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) {
                int k = Math.min(n, st.getCount());
                st.shrink(k);
                c.setItem(i, st);
                n -= k;
            }
        }
        c.setChanged();
    }

    /** The rule from its stored JSON. */
    public static Logic.Rule rule(JsonObject r) {
        List<String> who = new ArrayList<>();
        if (r.has("who")) {
            r.getAsJsonArray("who").forEach(x -> who.add(x.getAsString()));
        }
        List<Integer> code = new ArrayList<>();
        if (r.has("code")) {
            r.getAsJsonArray("code").forEach(x -> code.add(x.getAsInt()));
        }
        return new Logic.Rule(r.get("kind").getAsString(), who, r.has("item") ? r.get("item").getAsString() : null,
                r.has("count") ? r.get("count").getAsInt() : 1, code, r.has("period") ? r.get("period").getAsInt() : 0,
                r.has("onFor") ? r.get("onFor").getAsInt() : 0, r.has("radius") ? r.get("radius").getAsInt() : 4);
    }

    /** Forgets every rule's memory (a world closing). */
    public static void reset() {
        MEMORY.clear();
        DOWN.clear();
    }
}
