package com.moddingfromamod.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** 1.21.5 moved armour and the offhand out of {@code Inventory} into the entity's {@code EntityEquipment}. */
public final class Equipment {

    private Equipment() {
    }

    /** What {@code inventory.armor} held: feet, legs, chest, head. */
    public static List<ItemStack> armour(LivingEntity e) {
        List<ItemStack> out = new ArrayList<>(4);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
                EquipmentSlot.HEAD}) {
            out.add(e.getItemBySlot(slot));
        }
        return out;
    }
}
