package com.moddingfromamod.gatec;


import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * <b>Does this stack's art resolve, and to which sprite?</b>
 *
 * <p>The Gate-C question behind "the magenta cube reaches a kid before anyone notices". 1.21.1 asked
 * {@code ItemRenderer.getModel} and compared against the missing BakedModel; 1.21.2 removed both --
 * an item resolves through {@code ItemModelResolver} into an {@code ItemStackRenderState}, and an item
 * with no binding resolves to the missing item model, whose particle is {@code missingno}. So the
 * sprite is the one question both targets can answer the same way.
 */
public final class ItemModelProbe {
    private ItemModelProbe() {
    }

    /** The particle sprite this stack renders with, or "none" when it renders nothing at all. */
    public static String sprite(ItemStack stack, Level level, LivingEntity holder) {
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver()
                .updateForTopItem(state, stack, ItemDisplayContext.GUI, level, holder, 0);
        if (state.isEmpty()) {
            return "none";
        }
        var sprite = state.pickParticleIcon(RandomSource.create(0L));
        return sprite == null ? "none" : sprite.contents().name().toString();
    }

    /** True when the stack renders as the missing model (or not at all). */
    public static boolean missing(ItemStack stack, Level level, LivingEntity holder) {
        String s = sprite(stack, level, holder);
        return "none".equals(s) || s.contains("missingno");
    }
}
