package com.moddingfromamod.paint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wishpaint <id>} — hang a picture from the wish library again.
 *
 * <p>The library could replay a creature, a building, an item and a datapack, and a picture — about
 * 8% of what children ask for — not at all, so no picture could ever be a cached wish. This is the
 * command a template names, exactly as {@code wishbuild} is for a building: it reads
 * {@code pictures/<id>/painting.json} and needs no agent.
 *
 * <p><b>It brings its own wall.</b> A painting tool call names the wall it paints on; a cached wish
 * is pressed from a menu wherever the child happens to stand, and a picture on vanilla map art
 * needs a solid block behind every tile or it falls off within five seconds
 * ({@code MapArtPanels}). So it stands a dark-oak board a few steps in front of the child, facing
 * them, and hangs the picture on it — the same shape as a building that puts itself up.
 *
 * <p>Permission 0, like {@code /wishbuild}: it can only hang a picture the library already holds.
 */
public final class PictureCommand {

    /** How far in front of the child the board stands. Close enough to see whole, far enough to read. */
    static final int FORWARD = 5;

    private PictureCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wishpaint")
                // A REPLAY command, not something a child types: the stored form of every
                // saved button, run by the executor at permission 2. Hidden from a child's tab list.
                .requires(src -> src.hasPermission(2))
                // greedyString, not word(): a child's fork of a picture is `<id>~<his short
                // uuid>`, and brigadier's word() refuses `~` — as found for `wishbuild`.
                .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            com.moddingfromamod.library.WishLibrary.keys("pictures")
                                    .forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String id = StringArgumentType.getString(ctx, "id").trim();
                            String err = hang(player, id);
                            if (err != null) {
                                ctx.getSource().sendFailure(Component.literal(err));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "🎨 Here's your picture!"), false);
                            return 1;
                        })));
    }

    /** Hang library picture {@code id} in front of {@code player}. Null on success, else why not. */
    public static String hang(ServerPlayer player, String id) {
        Path file = com.moddingfromamod.library.WishLibrary.artifactFile("pictures", id,
                LibraryPicture.FILE);
        if (file == null) {
            // NOT IN THE LIBRARY: a picture a child painted, from the shelves. This is
            // how `/moddy try <wish id>` hands another child a copy of it.
            PictureStore.Picture shelf = PictureStore.get(id);
            if (shelf == null || shelf.recipe().isEmpty()) {
                return "No picture called '" + id + "'. Known: "
                        + String.join(", ", com.moddingfromamod.library.WishLibrary.keys("pictures"));
            }
            String me = com.moddingfromamod.marshal.ArtifactOwner.normalise(player.getStringUUID());
            String owner = shelf.ownerId();
            // A COPY HANGS UNDER ITS OWN NAME. The placement ledger is keyed by this id, and so
            // are the map tiles: hung under the picture's own id, a copy would replace the
            // painter's placement record (so HIS undo took down the copy) and share his tiles.
            // His own picture, or a shared one, keeps the id it has always had.
            boolean mine = owner.isEmpty() || owner.equals(me);
            String placement = mine ? shelf.id() : copyId(shelf.id(), me);
            return hangBoard(player, placement, shelf.recipe(), shelf.wide(), shelf.tall(), me);
        }
        LibraryPicture pic;
        try {
            pic = LibraryPicture.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (java.io.IOException | IllegalArgumentException e) {
            return "The picture '" + id + "' could not be read: " + e.getMessage();
        }
        return hangBoard(player, id, pic.recipe(), pic.wide(), pic.tall(), "");
    }

    /**
     * <b>Stand a board up where the wish was aimed and hang {@code recipe} on it</b>, facing the
     * child. Null on success, else why not.
     *
     * <p>Shared by {@code /wishpaint} and by {@code paint_surface} whenever a picture must not go
     * where another hangs: a fork of somebody else's picture, an {@code as_new} copy, or a
     * picture from his shelf that has never hung in this world. The board is the reason a picture
     * can be drawn "here" at all — vanilla map art needs a solid block behind every tile.
     *
     * @param owner stamped on the placement record, so the next edit knows whose it is
     */
    public static String hangBoard(ServerPlayer player, String id, PaintRecipe recipe, int spanWide,
                                   int spanTall, String owner) {
        ServerLevel level = player.level();
        // Where the wish was AIMED when there is one (the locked FOCUS), else a few steps ahead.
        BlockPos aimed = com.moddingfromamod.wish.WishProgress.manifestAnchor(player);
        BlockPos spot = aimed != null ? aimed
                : com.moddingfromamod.builder.Builder.anchorInFrontOf(player, FORWARD, 0);
        spot = com.moddingfromamod.builder.Builder.groundAnchor(level, spot);
        // The picture looks back at the child.
        Direction facing = Direction.getApproximateNearest(player.getX() - (spot.getX() + 0.5), 0,
                player.getZ() - (spot.getZ() + 0.5));
        if (!facing.getAxis().isHorizontal()) {
            facing = player.getDirection().getOpposite();
        }
        int wide = VanillaArt.tiles(spanWide);
        int tall = VanillaArt.tiles(spanTall);
        Direction right = facing.getCounterClockWise();
        // Centred on the spot, standing on the ground.
        BlockPos at = spot.relative(right, -((wide - 1) / 2));
        // ⚠ NEVER ONTO ANOTHER PICTURE'S FRAMES. Map art re-colours whatever frames already hang
        // at a spot (MapArtPanels reuses them), so a fork's "fresh" board aimed where the original
        // hangs would repaint the original — the exact thing exists to stop. Step sideways,
        // a picture's width plus a gap at a time, until the area is clear.
        for (int tries = 0; tries < 8 && framesIn(level, at, right, wide, tall); tries++) {
            at = at.relative(right, wide + 1);
        }
        if (framesIn(level, at, right, wide, tall)) {
            return "There is no clear wall-space near here for a new picture — every spot already "
                    + "has one hanging. Move somewhere more open and try again.";
        }
        BlockState board = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        // EVERY BLOCK THE BOARD CHANGES IS WRITTEN DOWN, so an undo can put the world
        // back: the cleared cells in front and the planks behind were recorded nowhere before,
        // and undoing a hung picture took its frames down and left the board standing.
        java.util.List<PaintRecord.BoardCell> changed = new java.util.ArrayList<>();
        for (int col = 0; col < wide; col++) {
            for (int row = 0; row < tall; row++) {
                BlockPos cell = at.relative(right, col).above(row);
                BlockPos back = cell.relative(facing.getOpposite());
                BlockState was = level.getBlockState(cell);
                if (!was.isAir() && !(level.getBlockEntity(cell) instanceof PaintPanelBlockEntity)) {
                    changed.add(cellOf(cell, was, Blocks.AIR.defaultBlockState()));
                    level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
                }
                BlockState behind = level.getBlockState(back);
                if (!com.moddingfromamod.compat.BlockStates.solidRender(behind, level, back)) {
                    changed.add(cellOf(back, behind, board));
                    level.setBlockAndUpdate(back, board);
                }
            }
        }
        PaintPanels.Result r = PaintPanels.paint(level, at, facing, id, recipe, wide, tall);
        if (!r.ok()) {
            return r.message();
        }
        // In the painting ledger like any painted wish, so the same undo finds it.
        PaintRegistry.put(level.getServer(), new PaintRecord(id,
                level.dimension().location().toString(), at.getX(), at.getY(), at.getZ(),
                id, false, "", PaintRecipe.blank(), 1, 1,
                facing.getName(), wide, tall, recipe).withOwner(owner).withBoard(changed));
        return null;
    }

    private static PaintRecord.BoardCell cellOf(BlockPos pos, BlockState was, BlockState put) {
        return new PaintRecord.BoardCell(pos.getX(), pos.getY(), pos.getZ(),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(was),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(put));
    }

    /**
     * <b>Take a picture's board down</b>: every block it changed goes back to what it
     * was, newest first, but ONLY where the block is still what the board made it. A block the
     * child has changed since is his, and is left exactly as he left it.
     *
     * @return how many blocks were put back
     */
    public static int takeDownBoard(ServerLevel level, PaintRecord record) {
        if (level == null || record == null || record.board().isEmpty()) {
            return 0;
        }
        int restored = 0;
        var cells = new java.util.ArrayList<>(record.board());
        java.util.Collections.reverse(cells);
        for (PaintRecord.BoardCell c : cells) {
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            String now = net.minecraft.commands.arguments.blocks.BlockStateParser
                    .serialize(level.getBlockState(pos));
            if (!now.equals(c.put())) {
                continue;
            }
            BlockState was = parseBlock(level, c.was());
            if (was != null) {
                level.setBlockAndUpdate(pos, was);
                restored++;
            }
        }
        return restored;
    }

    /** A block spec ({@code minecraft:stone}, with or without properties) or null if it is unknown. */
    private static BlockState parseBlock(ServerLevel level, String blockSpec) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                    blockSpec, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The placement id a copy of somebody else's picture hangs under: the picture's id plus the
     * new owner's short id, clipped to what a key may be ({@code ModelRepository.isValidKey}).
     */
    public static String copyId(String pictureId, String ownerId) {
        String tail = "_" + (ownerId.length() >= 8 ? ownerId.substring(0, 8) : ownerId);
        String head = pictureId.length() + tail.length() > 64
                ? pictureId.substring(0, 64 - tail.length()) : pictureId;
        return head + tail;
    }

    /** Is any item frame already hanging over the {@code wide}×{@code tall} area at {@code at}? */
    static boolean framesIn(ServerLevel level, BlockPos at, Direction right, int wide, int tall) {
        BlockPos far = at.relative(right, wide - 1).above(tall - 1);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                centre(at), centre(far)).inflate(0.6);
        return !level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                box).isEmpty();
    }

    private static net.minecraft.world.phys.Vec3 centre(BlockPos p) {
        return net.minecraft.world.phys.Vec3.atCenterOf(p);
    }
}
