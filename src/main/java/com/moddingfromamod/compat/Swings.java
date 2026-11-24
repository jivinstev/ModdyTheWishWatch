package com.moddingfromamod.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * A creature's arm swing. 26.3 gave a swing its own {@link SwingAnimation} (the held item's, or
 * vanilla's default whack), and dropped {@code swing(hand)} and {@code swing(hand, toSelf)}.
 */
public final class Swings {
    private Swings() {
    }

    /** {@code swing(hand, toSelf)} as before 26.3: the default whack, sent to the trackers (and the entity itself). */
    public static boolean swing(LivingEntity entity, InteractionHand hand, boolean toSelf) {
        return entity.swing(hand, SwingAnimation.DEFAULT, toSelf);
    }
}
