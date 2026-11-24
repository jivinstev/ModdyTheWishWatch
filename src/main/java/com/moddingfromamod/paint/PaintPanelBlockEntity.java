package com.moddingfromamod.paint;

import com.moddingfromamod.runtime.ModelRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>What picture this panel is showing, and how big it is.</b>
 *
 * <p>The whole point of the panel is that this holds a <b>key</b> — a short name — and never an
 * image. {@code CapturedStructure} in the mod repo already paid for the alternative: a village
 * template is megabytes, which blows Minecraft's ~2 MB network frame, so the item carries a UUID and
 * the blueprint is looked up server-side. A 256&sup2; RGBA picture is 256 KB and a train car might
 * carry six of them; the key that names it is a few dozen bytes and survives a change of resolution
 * for free.
 *
 * <p><b>Everything here has to live in NBT, not in a client-side cache.</b> Create rebuilds a
 * contraption's block entities from their saved tags when it assembles the train
 * ({@code ClientContraption.readBlockEntity}), so a panel whose picture was only ever set by a
 * packet would ride the train blank. That is also why {@link #getUpdateTag} exists: without it the
 * server knows the picture and the client draws nothing, which is the grand-prize item frame all
 * over again — server right, child sees nothing.
 */
public class PaintPanelBlockEntity extends BlockEntity {

    /** The tag the key rides in, on the block entity and in a {@code ContraptionPart}'s nbt. */
    public static final String ART_KEY = "ArtKey";
    public static final String RECIPE = "Paint";
    public static final String WIDTH = "PanelWidth";
    public static final String HEIGHT = "PanelHeight";

    private String artKey = "";
    /**
     * The picture itself, as the shapes that make it.
     *
     * <p>The KEY says where the baked image lives; this says what the image <b>is</b>, and it is the
     * durable half. A key alone is a pointer into a folder — copy the block to another world, or bake
     * at a different resolution, and it points at nothing. The recipe regenerates the picture from
     * first principles, costs a few hundred bytes, and is what will cross a network the day one is
     * involved.
     */
    private PaintRecipe recipe = PaintRecipe.blank();
    private int widthBlocks = 1;
    private int heightBlocks = 1;

    public PaintPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModPaint.PAINT_PANEL_BE.get(), pos, state);
    }

    /** The art this panel names, or {@code ""} for a blank canvas. Never null. */
    public String artKey() {
        return artKey;
    }

    public int widthBlocks() {
        return widthBlocks;
    }

    public int heightBlocks() {
        return heightBlocks;
    }

    /** The shapes this picture is made of. Never null; empty means a blank canvas. */
    public PaintRecipe recipe() {
        return recipe;
    }

    /**
     * Hang a picture on this panel.
     *
     * <p>Rejects a key that could not name a file — {@link ModelRepository#isValidKey} is the same
     * validator the creature models use, and it is there because a key becomes a path.
     */
    public void setArt(String key, int width, int height) {
        setArt(key, recipe, width, height);
    }

    /** Hang a picture, and remember what it is made of. */
    public void setArt(String key, PaintRecipe painting, int width, int height) {
        this.recipe = painting == null ? PaintRecipe.blank() : painting;
        this.artKey = key != null && ModelRepository.isValidKey(key) ? key : "";
        this.widthBlocks = PanelGeometry.clampSpan(width);
        this.heightBlocks = PanelGeometry.clampSpan(height);
        setChanged();
        if (level != null && !level.isClientSide()) {
            // Flag 3 = notify neighbours + send to clients. Without the client half the picture is
            // only ever right on the server.
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput tag) {
        super.loadAdditional(tag);
        String key = tag.getStringOr(ART_KEY, "");
        // Degrade to blank rather than throw: damaged data must cost the picture, not the world.
        artKey = ModelRepository.isValidKey(key) ? key : "";
        recipe = PaintRecipe.fromInts(tag.getIntArray(RECIPE).orElse(null));
        widthBlocks = PanelGeometry.clampSpan(tag.getIntOr(WIDTH, 1));
        heightBlocks = PanelGeometry.clampSpan(tag.getIntOr(HEIGHT, 1));
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putString(ART_KEY, artKey);
        // An int array, not a list of compounds: this is re-read on every contraption assembly and
        // a compound per rectangle would carry five tag names with it — most of the payload of the
        // one thing whose entire justification is being small.
        tag.putIntArray(RECIPE, recipe.toInts());
        tag.putInt(WIDTH, widthBlocks);
        tag.putInt(HEIGHT, heightBlocks);
    }

    /** What a client is told when the chunk arrives, or when a contraption reads this block back. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
