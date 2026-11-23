package com.moddingfromamod.entity;

import java.util.function.Supplier;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity registration. Exactly ONE creature type is registered — {@link WishMobEntity} — because
 * registries freeze at startup and every invented creature has to be an instance of something that
 * already exists.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ModdingFromAModMod.MODID);

    /** The universal wish creature. Its size is a placeholder — SCALE resizes it per species. */
    public static final Supplier<EntityType<WishMobEntity>> WISH_MOB = ENTITIES.register("wish_mob",
            id -> EntityType.Builder.of(WishMobEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(12)
                    .build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, id)));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
        modEventBus.addListener(ModEntities::createAttributes);
    }

    /** Without this the entity crashes the moment it spawns. */
    private static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(WISH_MOB.get(), WishMobEntity.createAttributes().build());
    }
}
