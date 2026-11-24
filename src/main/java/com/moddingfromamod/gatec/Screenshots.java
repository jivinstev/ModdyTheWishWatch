package com.moddingfromamod.gatec;

import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

/**
 * <b>A screenshot, now</b>. 1.21.5 made {@code Screenshot.takeScreenshot} hand its image to a
 * callback (the GPU-readback API), and the callback is a FENCED task: it runs from
 * {@code RenderSystem.executePendingTasks()} once the GPU has finished the copy — normally on a later
 * frame. The first cut assumed it ran inside the call; on 1.21.8 it never does, so every Gate C film
 * failed with the exception below (2026-11-08). Every caller here wants the image now, so this finishes
 * the GL work and drains the fenced queue until the image arrives, and says so loudly if it does not —
 * a camera that silently returned nothing would read as a black photograph, which the camera notes lists
 * among the bugs that look like a pass.
 */
public final class Screenshots {

    /** How long to drain the fenced queue for the readback; glFinish makes it near-instant in practice. */
    private static final long READBACK_WAIT_NS = 5_000_000_000L;

    private Screenshots() {
    }

    /** What {@code Screenshot.takeScreenshot(target)} returned on 1.21.4. Call on the render thread. */
    public static NativeImage take(RenderTarget target) {
        AtomicReference<NativeImage> got = new AtomicReference<>();
        Screenshot.takeScreenshot(target, got::set);
        org.lwjgl.opengl.GL11.glFinish();   // the copy is done once this returns, so its fence has signalled
        long deadline = System.nanoTime() + READBACK_WAIT_NS;
        while (got.get() == null && System.nanoTime() < deadline) {
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks();
            if (got.get() == null) {
                Thread.onSpinWait();
            }
        }
        NativeImage image = got.get();
        if (image == null) {
            throw new IllegalStateException("Screenshots.take: the GPU readback did not complete within "
                    + READBACK_WAIT_NS / 1_000_000 + " ms");
        }
        return image;
    }
}
