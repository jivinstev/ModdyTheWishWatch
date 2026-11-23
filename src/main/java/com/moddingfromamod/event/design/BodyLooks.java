package com.moddingfromamod.event.design;

import java.util.List;

import com.moddingfromamod.event.motion.DisplayBodyMotion.Part;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>How a falling body is drawn</b> (E7b): a lumpy rock of display entities. The owner
 * chose it from four filmed looks (a block ball, one cube, this rock, a bigger rock), 2026-09-29: the
 * block ball jumped and was drawn cut in half; the bigger rock was oversized for its crater.
 *
 * <p>Nothing here is fixed per wish: its size is the design's {@code size} (through
 * {@link DesignSizes#bodyRadius}, in proportion to the crater of the same size), its outside is the
 * design's {@code material} and its lumps are the design's {@code core}.
 */
public final class BodyLooks {

    private BodyLooks() {
    }

    /**
     * A core with six lumps pushed out in different directions and sizes, alternating the material
     * and the core block, so its outline changes as it tumbles and it reads as a rock, not a box.
     */
    public static List<Part> rock(BlockState material, BlockState core, float radius) {
        float r = radius;
        return List.of(
                new Part(core, 0, 0, 0, 1.5f * r),
                new Part(material, 0.55f * r, 0.20f * r, 0.10f * r, 1.1f * r),
                new Part(material, -0.50f * r, 0.35f * r, -0.25f * r, 1.0f * r),
                new Part(core, 0.10f * r, -0.55f * r, 0.40f * r, 1.05f * r),
                new Part(material, -0.20f * r, -0.30f * r, -0.60f * r, 0.9f * r),
                new Part(material, 0.30f * r, 0.60f * r, -0.40f * r, 0.8f * r),
                new Part(core, -0.60f * r, -0.10f * r, 0.45f * r, 0.85f * r));
    }
}
