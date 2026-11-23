package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.PaintSurfaceTool;
import com.moddingfromamod.paint.MapArt;
import com.moddingfromamod.paint.MapArtPanels;
import com.moddingfromamod.paint.PaintRecipe;
import com.moddingfromamod.paint.PanelGeometry;
import com.moddingfromamod.paint.PictureEdit;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

/**
 * Stresses {@code kind=pictures}: a picture a child wished for, hung as vanilla map
 * art on a real wall, read back, and taken down again.
 *
 * <p>It drives the path a stock client sees — {@link MapArtPanels#hang} — and the three pure checks
 * the tool runs before anything is painted, so a cap cannot come back silently:
 * <ul>
 *   <li>{@code refused}: more shapes than a recipe keeps is refused, never painted in part;</li>
 *   <li>{@code spanReported}: a board bigger than {@link PanelGeometry#MAX_SPAN} is hung at the cap
 *       AND the tool says so;</li>
 *   <li>{@code editKeepsChange}: a change layered on a picture already at the cap keeps the change
 *       and drops the oldest shapes underneath.</li>
 * </ul>
 * Every hung case also proves P5 both ways: taking it down removes every frame we put up, and
 * leaves the wall it hung on.
 */
public final class PicturesHarness implements StressHarness.Staged {

    /**
     * STAGED, so a case hangs above the stress test's own plot, whose chunks are loaded and
     * ticking — the far-away grid is loaded for blocks and not for entities, and a frame added
     * there is queued rather than placed, so the first unstaged version counted 0 of 1 and 112 of
     * 256 frames on a picture that had hung correctly. A child is always standing in a loaded chunk.
     */
    @Override
    public String kind() {
        return "pictures";
    }

    @Override
    public int settleTicks() {
        return 5;
    }

    /** What {@link #place} did, for the later phases. {@code early} is a verdict already reached. */
    private record Hung(Outcome.Builder out, Outcome early, BlockPos at, Direction facing, int wide,
                        int tall, List<BlockPos> wall, MapArtPanels.Result result) {
    }

    @Override
    public Object place(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        JsonObject spec = c.spec();
        JsonObject args = new JsonObject();
        if (spec.has("picture")) {
            args.add("picture", spec.get("picture"));
        }
        for (String k : new String[]{"wide", "tall"}) {
            if (spec.has(k)) {
                args.add(k, spec.get(k));
            }
        }

        if (c.flag("refused")) {
            return new Hung(out, out.require(Invariants.NO_SILENT_CAPS,
                    PaintSurfaceTool.tooManyShapes(args) != null,
                    "a picture with " + PaintRecipe.rectsSent(spec.getAsJsonObject("picture"))
                            + " shapes was not refused, so the recipe's cap would cut it short "
                            + "and the tool would report a picture he never gets").build(),
                    null, null, 0, 0, List.of(), null);
        }
        if (c.flag("editKeepsChange")) {
            return new Hung(out, edit(out, spec).build(), null, null, 0, 0, List.of(), null);
        }

        PaintRecipe recipe = PaintRecipe.parse(spec.getAsJsonObject("picture"));
        int asksWide = Math.max(1, spec.has("wide") ? spec.get("wide").getAsInt() : 1);
        int asksTall = Math.max(1, spec.has("tall") ? spec.get("tall").getAsInt() : 1);
        int wide = PanelGeometry.clampSpan(asksWide);
        int tall = PanelGeometry.clampSpan(asksTall);
        if (c.flag("spanReported")) {
            out.require(Invariants.NO_SILENT_CAPS, PaintSurfaceTool.spanNote(args) != null,
                    "a " + asksWide + "x" + asksTall + " board is hung at " + wide + "x" + tall
                            + " and the tool said nothing, so he gets a smaller picture than he "
                            + "asked for and nobody tells him why");
        }

        ServerLevel level = ctx.level();
        BlockPos at = ctx.anchor().above(2);
        Direction facing = Direction.NORTH;
        List<BlockPos> wall = backing(level, at, facing, wide, tall);
        long t0 = System.nanoTime();
        MapArtPanels.Result r = MapArtPanels.hang(level, at, facing, recipe, wide, tall);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // C16's number: the worst case (16x16, 256 tiles) is hung in ONE server tick, so its cost
        // is read here rather than assumed. A tick is 50 ms.
        com.mojang.logging.LogUtils.getLogger().info("PICTURE HANG {}: {}x{} = {} tile(s), {} "
                + "shape(s), {} ms in one tick", c.id(), wide, tall, r.tiles(),
                recipe.rects().size(), ms);
        out.require(Invariants.MATERIALISES, r.ok() && r.tiles() == wide * tall,
                "hanging a " + wide + "x" + tall + " picture gave "
                        + (r.ok() ? r.tiles() + " tile(s)" : "a refusal: " + r.message()));
        return new Hung(out, null, at, facing, wide, tall, wall, r);
    }

    @Override
    public Outcome assertSettled(Context ctx, WishCorpus.Case c, Object state) {
        Hung h = (Hung) state;
        if (h.early() != null) {
            return h.early();
        }
        Outcome.Builder out = h.out();
        if (!h.result().ok()) {
            return out.build();
        }
        ServerLevel level = ctx.level();
        int painted = 0;
        for (MapId id : h.result().ids()) {
            MapItemSavedData d = level.getMapData(id);
            if (d == null) {
                continue;
            }
            for (byte b : d.colors) {
                if (b != MapArt.TRANSPARENT) {
                    painted++;
                }
            }
        }
        out.require(Invariants.MATERIALISES, painted > 0,
                "every map on the wall is blank — a stock client sees empty frames");
        int want = h.wide() * h.tall();
        int frames = ours(level, h.at(), h.wide(), h.tall());
        out.require(Invariants.MATERIALISES, frames == want,
                "expected " + want + " invisible frames holding the picture, found " + frames);

        int removed = MapArtPanels.takeDown(level, h.at(), h.facing(), h.wide(), h.tall());
        int left = ours(level, h.at(), h.wide(), h.tall());
        out.require(Invariants.P5_REMOVES_CLEAN, removed == want && left == 0,
                "taking the picture down removed " + removed + " and left " + left
                        + " of our frames — orphans accumulate forever");
        long wallLeft = h.wall().stream()
                .filter(p -> level.getBlockState(p).is(Blocks.STONE)).count();
        out.require(Invariants.P5_NON_DESTRUCTIVE, wallLeft == h.wall().size(),
                (h.wall().size() - wallLeft) + " block(s) of the wall it hung on went with it");
        return out.build();
    }

    @Override
    public void cleanUp(Context ctx, WishCorpus.Case c, Object state) {
        Hung h = (Hung) state;
        if (h.at() == null) {
            return;
        }
        MapArtPanels.takeDown(ctx.level(), h.at(), h.facing(), h.wide(), h.tall());
        for (BlockPos p : h.wall()) {
            ctx.level().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        }
    }

    /** A picture already at the cap, then one change on top: the change must be what survives. */
    private static Outcome.Builder edit(Outcome.Builder out, JsonObject spec) {
        int n = spec.has("baseShapes") ? spec.get("baseShapes").getAsInt() : PaintRecipe.MAX_RECTS;
        List<CreatureSculpt.PaintRect> rects = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rects.add(new CreatureSculpt.PaintRect(i % 64, i / 64, 4, 4, 0xFF00AA00));
        }
        PaintRecipe base = new PaintRecipe(256, rects);
        PaintRecipe change = PaintRecipe.parse(spec.getAsJsonObject("picture"));
        PaintRecipe after = PictureEdit.layer(base, change);
        List<CreatureSculpt.PaintRect> got = after.rects();
        List<CreatureSculpt.PaintRect> sent = change.rects();
        boolean kept = got.size() >= sent.size()
                && got.subList(got.size() - sent.size(), got.size()).equals(sent);
        return out.require(Invariants.PICTURE_EDIT_KEEPS_CHANGE,
                kept && got.size() <= PaintRecipe.MAX_RECTS,
                "a " + sent.size() + "-shape change on a " + n + "-shape picture came out with "
                        + got.size() + " shapes and the change "
                        + (kept ? "on top" : "NOT on top — the cap cut the edit he asked for"));
    }

    /** Stone behind every block the board covers; returns what was placed, to clean it up. */
    private static List<BlockPos> backing(ServerLevel level, BlockPos at, Direction facing,
                                          int wide, int tall) {
        List<BlockPos> placed = new ArrayList<>();
        Direction behind = facing.getOpposite();
        for (int side = -wide - 1; side <= wide + 1; side++) {
            for (int up = 0; up <= tall; up++) {
                BlockPos p = at.relative(Direction.EAST, side).above(up).relative(behind);
                if (level.getBlockState(p).isAir()) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                    placed.add(p);
                }
            }
        }
        return placed;
    }

    private static int ours(ServerLevel level, BlockPos at, int wide, int tall) {
        AABB box = new AABB(at).inflate(Math.max(wide, tall) + 2);
        int n = 0;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (!f.isRemoved() && MapArtPanels.isOurs(level, f)) {
                n++;
            }
        }
        return n;
    }
}
