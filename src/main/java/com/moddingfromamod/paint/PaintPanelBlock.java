package com.moddingfromamod.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * <b>A blank canvas you can hang a real picture on.</b>
 *
 * <p>A picture made of coloured blocks is nine pixels, and that is not tunable — a block
 * model can only draw what is in the stitched block atlas. A block <i>entity renderer</i> is not
 * bound by the atlas: it draws a quad with whatever texture it is handed, so the picture's
 * resolution stops having anything to do with how many blocks it is made of. One of these at
 * 256&sup2; is 65,536 pixels in the space that used to hold nine.
 *
 * <p>It is a <b>block</b> rather than an item frame or a mob for one reason: an assembled Create
 * train is a contraption, and a contraption carries its blocks. Map art rides in an item frame and a
 * creature is an entity, so neither can be on a moving train — see {@code docs/HIGH_RES_ART.md}.
 *
 * <p>Ordinary in every other way on purpose: a full cube of normal hardness, with no
 * {@code MovementBehaviour}, so Create's assembly accepts it (a command block, whose destroy speed
 * is -1, is refused outright) and its block entity lands in the contraption's own render list.
 */
public class PaintPanelBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public PaintPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * Face the person who put it down.
     *
     * <p>{@code getHorizontalDirection} is where the player is LOOKING, so the picture has to take
     * its opposite — the same thing a furnace does. Getting this backwards hangs every picture
     * against the wall, which looks exactly like the renderer not working.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * <b>Nothing but the picture.</b> The block contributes no quads of its own; only
     * {@code PaintPanelRenderer}'s draws.
     *
     * <p>The model was {@code cube_all} in white concrete, so a picture with a transparent
     * background revealed a white block rather than the carriage behind it — which is not what
     * "transparent" means to the person who asked for it. Standard vanilla mechanism, not a trick:
     * a chest and a sign are drawn entirely by their block-entity renderers the same way.
     *
     * <p>An UNPAINTED panel is still visible, because the renderer falls back to a
     * visibly-not-a-picture texture rather than to nothing. That distinction is load-bearing — see
     * {@code PaintPanelRenderer.drawnUnpainted} — and invisibility here would have destroyed it if
     * the fallback were a block model instead.
     */
    /**
     * A/B seam: {@code -Ptestprops=panelmodel=cube} restores the visible block model, so the
     * question "did hiding the block break Create's assembly?" can be answered by running the same
     * ladder twice rather than by reasoning about Create's bytecode.
     */
    private static final boolean VISIBLE_CUBE = "cube".equalsIgnoreCase(
            System.getProperty("moddingfromamod.panelmodel", ""));

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return VISIBLE_CUBE ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PaintPanelBlockEntity(pos, state);
    }
}
