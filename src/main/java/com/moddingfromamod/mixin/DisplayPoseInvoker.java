package com.moddingfromamod.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>Lets {@code DisplayRig.apply} pose a display by setting its three pose fields</b>,
 * instead of a whole-entity {@code saveWithoutId} → {@code load} round trip.
 *
 * <p>The round trip re-read EVERYTHING the display saves, and on 1.21.1 it made the server send the
 * posed displays a position update with an unchanged target: measured in {@code GateC:vanillawalklook},
 * the 36 posed displays of a 40-display rig restarted their position/rotation easing in bursts
 * while the 4 never-posed ones did not. A standing creature turning its body then drew those two
 * groups up to ~5° apart in yaw. These three setters are exactly what a pose is, and they are
 * {@code private} on {@code Display} in 1.21.4 and 1.21.1 alike (same names in both).
 */
@Mixin(Display.class)
public interface DisplayPoseInvoker {

    @Invoker("setTransformation")
    void moddingfromamod$setTransformation(Transformation transformation);

    @Invoker("setTransformationInterpolationDuration")
    void moddingfromamod$setTransformationInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void moddingfromamod$setTransformationInterpolationDelay(int ticks);

    /** {@code teleport_duration} by its one field: {@code DisplayRig.smoothMotion} read it
     *  through a whole-entity NBT save on every display of every adopted rig. Private in 1.21.8. */
    @Invoker("getPosRotInterpolationDuration")
    int moddingfromamod$getPosRotInterpolationDuration();

    @Invoker("setPosRotInterpolationDuration")
    void moddingfromamod$setPosRotInterpolationDuration(int ticks);

    /**
     * The transform a display holds NOW, read the way vanilla reads it. {@code private
     * static} on {@code Display} in 1.21.4 and 1.21.1 alike. {@code DisplayRig.apply} asks it so a
     * write that changes nothing sends nothing: the delay setter above FORCES a resend, which
     * restarts the client's tween from the display's previous pose -- a snap back on every write.
     */
    @Invoker("createTransformation")
    static Transformation moddingfromamod$createTransformation(
            net.minecraft.network.syncher.SynchedEntityData data) {
        throw new AssertionError("mixin invoker");
    }
}
