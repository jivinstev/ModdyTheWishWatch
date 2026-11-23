package com.moddingfromamod.mob;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * Applies {@link SummonSetup}'s decision to a wished mob as it joins the level — the Minecraft-facing
 * half. Called by {@code ops.WishMobLimit} for every creature a wish (a command, a button, a
 * datapack, an event) brings into the world, AFTER it has been admitted, and never for one loaded
 * from disk: a saved warden's cooldown, a saved phantom's anchor and a saved turtle's home are its
 * own. A mob that went through {@code finalizeSpawn} already holds every value, so this is a
 * no-op for it; it only fills what an NBT {@code summon} skipped.
 */
public final class SummonRepairs {

    private SummonRepairs() {
    }

    /** Give {@code e} back the spawn setup an NBT summon skipped, if it is one that needs it. */
    public static SummonSetup.Repair apply(Entity e) {
        SummonSetup.Repair r = SummonSetup.forType(
                BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        if (r == null) {
            return null;
        }
        switch (r) {
            case WARDEN_DIG_COOLDOWN -> {
                if (e instanceof Warden w && SummonSetup.needed(r,
                        w.getBrain().hasMemoryValue(MemoryModuleType.DIG_COOLDOWN), 0, 0, 0)) {
                    w.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN,
                            net.minecraft.util.Unit.INSTANCE, SummonSetup.WARDEN_DIG_COOLDOWN_TICKS);
                    return r;
                }
            }
            case PHANTOM_ANCHOR -> {
                if (e instanceof Phantom p) {
                    // The anchor is package-private; its only door is the save tag — "anchor_pos", a
                    // BlockPos, since 1.21.5 (it was AX/AY/AZ, read in Phantom's 1.21.8 source).
                    CompoundTag own = com.moddingfromamod.compat.EntityNbt.save(p);
                    BlockPos was = own.read("anchor_pos", BlockPos.CODEC).orElse(BlockPos.ZERO);
                    if (SummonSetup.needed(r, true, was.getX(), was.getY(), was.getZ())) {
                        BlockPos a = p.blockPosition().above(SummonSetup.PHANTOM_ANCHOR_ABOVE);
                        own.store("anchor_pos", BlockPos.CODEC, a);
                        com.moddingfromamod.compat.EntityNbt.load(p, own);
                        return r;
                    }
                }
            }
            case TURTLE_HOME -> {
                if (e instanceof Turtle t) {
                    CompoundTag own = com.moddingfromamod.compat.EntityNbt.save(t);
                    // "home_pos", a BlockPos, since 1.21.5 (it was HomePosX/Y/Z)
                    BlockPos home = own.read("home_pos", BlockPos.CODEC).orElse(BlockPos.ZERO);
                    if (SummonSetup.needed(r, true, home.getX(), home.getY(), home.getZ())) {
                        t.setHomePos(t.blockPosition());
                        return r;
                    }
                }
            }
            default -> {
            }
        }
        return null;
    }
}
