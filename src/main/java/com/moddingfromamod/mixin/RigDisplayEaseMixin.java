package com.moddingfromamod.mixin;

import com.moddingfromamod.vanilla.RigEaseState;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * every display notes, on the SERVER, whether its pose ease was restarted and whether a field that makes a client
 * rebuild its render state changed ({@link RigEaseState}), so the tracker can re-arm an ease a look change would
 * otherwise jump. Checked against 1.21.8's {@code Display}: {@code RENDER_STATE_IDS} (transform, billboard, light,
 * shadow) plus {@code ItemDisplay}'s item and item context, which {@code ItemDisplay.onSyncedDataUpdated} adds after
 * calling this method. Nothing changes on a client, and nothing is sent: it only remembers.
 */
@Mixin(Display.class)
public abstract class RigDisplayEaseMixin implements RigEaseState {

    @Shadow
    @Final
    private static IntSet RENDER_STATE_IDS;

    @Shadow
    @Final
    private static EntityDataAccessor<Integer> DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID;

    @Shadow
    @Final
    private static EntityDataAccessor<Integer> DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID;

    @Unique
    private boolean moddingfromamod$started;

    @Unique
    private boolean moddingfromamod$look;

    @Unique
    private long moddingfromamod$easeEnd = Long.MIN_VALUE;

    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void moddingfromamod$noteEase(EntityDataAccessor<?> key, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() == null || self.level().isClientSide()) {
            return;
        }
        if (DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID.equals(key)) {
            moddingfromamod$started = true;
        } else if (RENDER_STATE_IDS.contains(key.id()) || moddingfromamod$itemLook(self, key)) {
            moddingfromamod$look = true;
        }
    }

    @Unique
    private static boolean moddingfromamod$itemLook(Entity display, EntityDataAccessor<?> key) {
        return display instanceof Display.ItemDisplay
                && (ItemDisplayLookKeys.moddingfromamod$itemKey().equals(key)
                        || ItemDisplayLookKeys.moddingfromamod$contextKey().equals(key));
    }

    @Override
    public boolean moddingfromamod$easeStarted() {
        return moddingfromamod$started;
    }

    @Override
    public boolean moddingfromamod$lookChanged() {
        return moddingfromamod$look;
    }

    @Override
    public void moddingfromamod$clearEaseFlags() {
        moddingfromamod$started = false;
        moddingfromamod$look = false;
    }

    @Override
    public int moddingfromamod$easeDuration() {
        return ((Entity) (Object) this).getEntityData().get(DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID);
    }

    @Override
    public long moddingfromamod$easeEnd() {
        return moddingfromamod$easeEnd;
    }

    @Override
    public void moddingfromamod$setEaseEnd(long trackerTick) {
        moddingfromamod$easeEnd = trackerTick;
    }

    @Override
    public void moddingfromamod$rearm(int ticks) {
        var data = ((Entity) (Object) this).getEntityData();
        // Forced, as vanilla's own delay setter is: the client must see a NEW start even when the value is equal.
        data.set(DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID, ticks, true);
        data.set(DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID, 0, true);
    }
}
