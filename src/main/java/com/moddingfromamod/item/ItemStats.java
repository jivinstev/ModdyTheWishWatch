package com.moddingfromamod.item;

import java.util.OptionalDouble;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * <b>An item's attack damage, read from the game itself</b> (#14): what hitting with it in the main hand deals, in
 * health points, from the item's own default attribute modifiers and the player's own base attack damage. Nothing of
 * the game's numbers is copied into this tree, so another Minecraft version, or another mod's item, answers for itself.
 * Installed into {@code MakeFacts.itemAttack} by the server when a world answers makes.
 */
public final class ItemStats {

    private ItemStats() {
    }

    /** The attack damage of item {@code id} in the main hand, or empty when it is no item or adds no attack damage. */
    public static OptionalDouble attackDamage(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return OptionalDouble.empty();
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        ItemAttributeModifiers mods = item.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double add = 0;
        boolean any = false;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute().is(Attributes.ATTACK_DAMAGE) && e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE
                    && (e.slot() == EquipmentSlotGroup.MAINHAND || e.slot() == EquipmentSlotGroup.HAND
                    || e.slot() == EquipmentSlotGroup.ANY)) {
                add += e.modifier().amount();
                any = true;
            }
        }
        if (!any) {
            return OptionalDouble.empty();
        }
        double base = net.minecraft.world.entity.player.Player.createAttributes().build()
                .getBaseValue(Attributes.ATTACK_DAMAGE);
        return OptionalDouble.of(base + add);
    }
}
