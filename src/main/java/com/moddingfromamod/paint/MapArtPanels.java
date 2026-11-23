package com.moddingfromamod.paint;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

// ── TRANSITIONAL — art spike. Not production. See docs/VANILLA_ART_SPIKE_RESULTS.md

/**
 * <b>Hangs a picture where a client with no mod can see it.</b> The view layer, and only the view
 * layer.
 *
 * <p>A painted panel is a registered block with a block entity and a client renderer, so on a stock
 * client it has no counterpart at all — this is the from-scratch substitute. Everything ABOVE it is
 * untouched: the same {@link PaintRecipe} the panel stores is the input, the same
 * {@link PaintRegistry} records it, the same {@code paint_surface} call produced it. Methodology
 * &sect;0.5 — only the drawing may differ.
 *
 * <h2>Why maps, in one line</h2>
 *
 * <p>Because vanilla itself pushes them. {@code ServerEntity.sendChanges} spots a {@link MapItem}
 * in an {@link ItemFrame} and sends {@code getUpdatePacket} to every tracking player, unprompted —
 * read out of the bytecode, not assumed. So the picture reaches a stock client on a packet it
 * already parses, with no mod class on the client, no resource pack and no new channel. The other
 * three candidate routes all needed client-side art delivered by a pack this mod has no way to
 * push (docs/VANILLA_ART_SPIKE_RESULTS.md &sect;1).
 *
 * <h2>The four neutralisations, each one an axis off the &sect;3.B.10 checklist</h2>
 *
 * <ol>
 *   <li><b>LOCKED.</b> The one that is not obvious and the one the precedent does not do. A map in
 *       a player's inventory is redrawn from terrain by {@code MapItem.inventoryTick} — which would
 *       replace the picture with a picture of the ground. The precedent reasons that its maps never
 *       leave their frames, which is true of a shopping mall and false of a child's wish he can mine.
 *       Verified from bytecode: {@code inventoryTick} reads {@code locked} at offset 44 and
 *       {@code ifne} jumps past the {@code update()} call, so locking is a real, total block on the
 *       redraw. ({@code tickCarriedBy} still runs — from {@code ServerEntity} above — but it only
 *       touches decorations and frame markers.)</li>
 *   <li><b>trackingPosition off.</b> Otherwise the game draws a white player-arrow across the art.</li>
 *   <li><b>INVISIBLE frames.</b> The picture, not the picture frame — and without it every tiling
 *       seam is a wooden border.</li>
 *   <li><b>Invulnerable and silent.</b> A picture should not be destroyable by a stray arrow or
 *       announce itself. It is still an entity, so a piston or an explosion can still move or
 *       remove it; that is recorded as a real disparity rather than papered over.</li>
 * </ol>
 *
 * <h2>Repainting reuses the map ids</h2>
 *
 * <p>Map data is a file in level data and the id counter is world-global, so minting fresh ids on
 * every repaint leaks a {@code map_N.dat} per "make him bluer". {@code paint_surface} explicitly
 * supports repainting the same id, so the frames already hanging at a spot are found, their maps
 * re-coloured in place, and only the shortfall is created. That is also why a repaint needs no
 * client message: the same {@link MapId} with new bytes is exactly the case
 * {@code getUpdatePacket} exists for.
 */
public final class MapArtPanels {

    private MapArtPanels() {
    }

    /** What happened, for a gate to assert on and a tool to report. */
    public record Result(boolean ok, String message, int tiles, int reused, List<MapId> ids) {
    }

    private static Result no(String why) {
        return new Result(false, why, 0, 0, List.of());
    }

    /**
     * Hang {@code recipe} as map art over the block face at {@code at} looking {@code facing}.
     *
     * <p>Coordinates match {@link PanelGeometry} exactly: {@code at} is the picture's BOTTOM-LEFT
     * block as a viewer standing in front of it sees it, the picture grows to that viewer's right
     * and upward. Any other choice would put a mural drawn by this route somewhere else from one
     * drawn by the panel renderer, and a spike whose two arms are in different places measures
     * nothing.
     */
    public static Result hang(ServerLevel level, BlockPos at, Direction facing, PaintRecipe recipe,
                              int wide, int tall) {
        if (level == null || at == null || facing == null) {
            return no("no world");
        }
        if (!facing.getAxis().isHorizontal()) {
            return no("a picture hangs on a wall, so it must face north, south, east or west");
        }
        PaintRecipe r = recipe == null ? PaintRecipe.blank() : recipe;
        if (r.isEmpty()) {
            return no("that picture has no shapes in it, so it would paint nothing");
        }
        int cols = VanillaArt.tiles(wide);
        int rows = VanillaArt.tiles(tall);
        byte[][] tiles = MapArt.tilesOf(r, cols, rows, MapArtPalette.get());

        // The viewer stands in front and looks at the wall, so their right is the face's
        // COUNTER-clockwise neighbour -- the same handedness PanelGeometry derives from a cross
        // product. Stated as the same claim in two unit systems so a test can pin them together.
        Direction right = facing.getCounterClockWise();

        // ── A FRAME WITH NOTHING BEHIND IT IS A PICTURE WITH A FIVE-SECOND LIFE, so ask BEFORE
        // building anything. `BlockAttachedEntity.tick` checks `survives()` every 100 ticks and,
        // when the support has gone, discards the frame and drops the map (§19) -- and it makes no
        // distinction between a wall a child mined and a wall that was never there. Under the spike
        // that was invisible: the panel drew the picture too, so a map art copy that quietly
        // evaporated cost nothing and NOTHING IN THE GATE NOTICED -- every test in
        // VanillaArtGameTest hung its frames in mid-air 24 blocks above an empty plot and read them
        // back within the same tick. With the panel gone this is the only view, so it has to fail
        // loudly at hang time instead of succeeding and then disappearing.
        //
        // Asked with vanilla's own `survives()` on a probe frame that is never added to the world,
        // which is exactly what `HangingEntityItem.useOn` does -- a reimplementation of the
        // predicate here would be one more table that agrees with itself (§12.1).
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BlockPos pos = at.relative(right, col).above(rows - 1 - row);
                if (existingFrameAt(level, pos, facing) != null) {
                    continue;   // our own frame is already there and already survived
                }
                if (!new ItemFrame(level, pos, facing).survives()) {
                    return no("there is nothing at " + pos.relative(facing.getOpposite())
                            .toShortString() + " for the picture to hang on. A picture needs a "
                            + "solid wall behind every block of it — the whole " + cols + "x" + rows
                            + " of it, not just the corner you named.");
                }
            }
        }

        List<MapId> ids = new ArrayList<>();
        int reused = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                // Row 0 is the TOP of the picture and `at` is its BOTTOM-left, so row 0 hangs at
                // the HIGHEST block. Getting this backwards flips the picture vertically, which
                // photographs perfectly (PanelGeometry's own warning, one unit system along).
                BlockPos pos = at.relative(right, col).above(rows - 1 - row);
                ItemFrame frame = existingFrameAt(level, pos, facing);
                // A FRAME THAT IS NOT OURS IS NOT OURS TO TAKE. Only one frame fits a block face,
                // so a child's own frame in the picture's footprint cannot be worked around -- and
                // quietly replacing whatever was in it is exactly the negative this route must not
                // violate (§3.C.15). Refuse and say where, which is the same rule the PANEL path
                // already follows for a block that is not a panel.
                if (frame != null && !isOurs(level, frame)) {
                    return no("there is already an item frame at " + pos.toShortString()
                            + " that is not part of a picture. Pick a clear stretch of wall — the "
                            + "picture needs " + cols + "x" + rows + " block faces to itself.");
                }
                MapId id = frame == null ? null
                        : frame.getItem().get(DataComponents.MAP_ID);
                MapItemSavedData data = id == null ? null : level.getMapData(id);
                if (data == null) {
                    ItemStack map = fresh(level, tiles[row * cols + col]);
                    if (map.isEmpty()) {
                        return no("could not create the map for tile " + col + "," + row);
                    }
                    if (frame == null) {
                        frame = new ItemFrame(level, pos, facing);
                        dress(frame);
                        frame.setItem(map, false);
                        // A FRAME THE WORLD REFUSES IS A PICTURE THAT IS NOT THERE. addFreshEntity
                        // returns false for a chunk whose entities are not loaded, and ignoring it
                        // reported "hung" over an empty wall (the stress corpus caught it).
                        if (!level.addFreshEntity(frame)) {
                            return no("the wall at " + pos.toShortString() + " is not loaded, so "
                                    + "the picture could not be hung there");
                        }
                    } else {
                        frame.setItem(map, false);
                        dress(frame);
                    }
                    id = map.get(DataComponents.MAP_ID);
                } else {
                    // REPAINT IN PLACE. See the class note -- a fresh id per repaint leaks a file
                    // in level data every time a child says "make him bluer".
                    System.arraycopy(tiles[row * cols + col], 0, data.colors, 0,
                            data.colors.length);
                    data.setDirty();
                    dress(frame);
                    reused++;
                }
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return new Result(true, "hung \"" + cols + "x" + rows + "\" as " + ids.size()
                + " map tile(s) (" + reused + " reused)", ids.size(), reused, List.copyOf(ids));
    }

    /**
     * Take a picture down again — the inverse {@code WishUndo} needs.
     *
     * <p>Only ever removes a frame this route would have hung: invisible, holding a locked map, at a
     * position inside the picture's own footprint. A child's own item frame that happens to be on
     * the same wall is not ours to delete, and "the negatives" (&sect;3.C.15) is where the
     * regressions in this kind of work live.
     */
    public static int takeDown(ServerLevel level, BlockPos at, Direction facing, int wide,
                               int tall) {
        if (level == null || at == null || facing == null) {
            return 0;
        }
        int cols = VanillaArt.tiles(wide);
        int rows = VanillaArt.tiles(tall);
        Direction right = facing.getCounterClockWise();
        int removed = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BlockPos pos = at.relative(right, col).above(rows - 1 - row);
                ItemFrame frame = existingFrameAt(level, pos, facing);
                if (frame != null && isOurs(level, frame)) {
                    frame.discard();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Is this frame one of ours?
     *
     * <p>Three conditions, all of which we set and none of which a child's frame has by accident:
     * invisible, holding a filled map, and that map LOCKED. Locking is doing double duty here — it
     * is the redraw neutralisation and it is also the mark, which is why it is checked rather than
     * inferred. Deliberately NOT a name or a custom-data tag: a vanilla client must be able to see
     * everything about this picture, and anything mod-shaped on the stack is a component a stock
     * client would have to be taught.
     */
    public static boolean isOurs(ServerLevel level, ItemFrame frame) {
        if (frame == null || !frame.isInvisible()) {
            return false;
        }
        MapId id = frame.getItem().get(DataComponents.MAP_ID);
        if (id == null) {
            return false;
        }
        MapItemSavedData data = level.getMapData(id);
        return data != null && data.locked;
    }

    private static ItemFrame existingFrameAt(ServerLevel level, BlockPos pos, Direction facing) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(pos).inflate(0.4))) {
            if (f.getDirection() == facing && f.getPos().equals(pos)) {
                return f;
            }
        }
        return null;
    }

    /** A fresh, locked, position-blind map holding one tile. */
    private static ItemStack fresh(ServerLevel level, byte[] tile) {
        // trackingPosition FALSE (arg 5) -- see the class note: a tracking map draws a player arrow
        // across the art. unlimitedTracking false likewise.
        ItemStack stack = MapItem.create(level, 0, 0, (byte) 0, false, false);
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        MapItemSavedData data = level.getMapData(id);
        if (data == null) {
            return ItemStack.EMPTY;
        }
        System.arraycopy(tile, 0, data.colors, 0, data.colors.length);
        // LOCK IT. Not decoration: this is what stops MapItem.inventoryTick redrawing our picture
        // as terrain the moment a child breaks the frame and picks the map up. `locked` is final,
        // so the locked copy has to REPLACE the stored one under the same id.
        MapItemSavedData locked = data.locked();
        System.arraycopy(tile, 0, locked.colors, 0, locked.colors.length);
        level.setMapData(id, locked);
        locked.setDirty();
        return stack;
    }

    private static void dress(ItemFrame frame) {
        frame.setInvisible(true);       // the picture, not the picture frame
        frame.setInvulnerable(true);
        frame.setSilent(true);
        frame.setRotation(0);           // 8 positions exist; a rotated tile is a rotated 1/n of art
    }
}
