package com.moddingfromamod.paint;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.ModelRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * <b>Put a picture on a panel.</b> The one server-side entry point — the command uses it today and
 * the {@code paint} wish tool will use it tomorrow.
 *
 * <p>It exists so that "paint this" is written once. The alternative shape — a command that bakes
 * and sets, and later a tool that bakes and sets — is two copies of an ordering that has to be
 * right: <b>bake first, then set</b>, because the block entity's update packet reaches clients
 * within a tick and a client that looks for the image before it is on disk caches the unpainted
 * fallback for as long as the file's mtime says nothing changed.
 */
public final class PaintPanels {

    private PaintPanels() {
    }

    /** What happened, in words a command can print and a tool can hand to a child. */
    public record Result(boolean ok, String message) {
    }

    /**
     * <b>Does a picture on a STATIC surface get a panel?</b> The path-2 routing decision, in one
     * place so that nobody has to write it twice.
     *
     * <p>It reads as a double negative on purpose: the panel is now the exception. Since the
     * reclassification the default view of a picture is vanilla map art, which every client can see
     * with no mod, and the panel is the shim for the one surface vanilla structurally cannot draw
     * on — a Create contraption, which carries blocks and cannot carry an item frame (&sect;19).
     * That case never reaches this method; it goes through {@code PaintOnCar} and always gets a
     * panel, so this answers only for a wall.
     *
     * <p>Separate from {@link VanillaArt#enabled()} even though it is one negation of it, because
     * the two are different questions and will not always have the same answer: one is "are we
     * serving vanilla clients", the other is "what draws this particular surface". Keeping the
     * second here is what stops {@code PaintSurfaceTool} growing its own copy of the first
     * (methodology &sect;3.B.7), which is how the paths drift.
     */
    // ⚰️ LEGACY (panel-as-static-view): answers false in every shipped run. Kept as the
    // switch point of a LIVE A/B -- six Gate C comparisons drive both arms through it. Row and
    // blocker: docs/COMPAT_SHIMS.md.
    public static boolean panelOnStaticSurface() {
        return !VanillaArt.enabled();
    }

    /**
     * Paint the panel at {@code pos}.
     *
     * @param key    names the baked image. Validated, because it becomes a path.
     * @param recipe the shapes to paint, in order.
     * @param width  how many blocks wide to hang it, or <b>0 to keep the size it already has</b>.
     * @param height likewise.
     *
     *     <p><b>Keeping is why 0 exists, and it is a bug fix.</b> {@code /paint <key> <json>} passed
     *     1x1, so repainting a three-block mural silently shrank it to a single block — the picture
     *     changed correctly and the thing it was painted on quietly stopped being the size somebody
     *     had asked for. Nothing failed; every server-side assertion still passed. The rule lives
     *     here rather than in the command so the wish tool cannot reintroduce it.
     */
    public static Result paint(ServerLevel level, BlockPos pos, String key,
                               PaintRecipe recipe, int width, int height) {
        return paint(level, pos, null, key, recipe, width, height);
    }

    /**
     * Paint at {@code pos}, saying which way the picture looks.
     *
     * <p>The facing is a parameter rather than something read off the block because <b>after the
     * path-2 flip there may be no block to read it from</b>: a picture on a static wall is now
     * vanilla map art, and an item frame is hung against the wall rather than standing in front of
     * it on a panel. The no-facing overload above keeps every caller that does have a panel.
     *
     * <h2>ONE VIEW PER SURFACE — the routing rule, stated once</h2>
     *
     * <p><b>A panel at {@code pos} means the mod renderer draws this picture; no panel means vanilla
     * map art does.</b> Not two views of one picture: the panel draws on its block's <i>face</i> and
     * a framed map hangs flat on the <i>wall</i>, a block apart, so a client with the mod would see
     * the panel picture with the map picture behind it. The spike ran them additively on purpose —
     * that is what let one wish be photographed both ways (&sect;18) — and production must not.
     *
     * <p>Which means the routing DECISION is not taken here. It is taken by whoever decides to place
     * a panel, which is {@code PaintSurfaceTool}: a contraption gets one because a Create carriage
     * carries a block and cannot carry an item frame (&sect;19), and a static wall does not. This
     * method only reads the consequence, so the two can never disagree.
     */
    public static Result paint(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing,
                               String key, PaintRecipe recipe, int width, int height) {
        if (level == null || pos == null) {
            return new Result(false, "no world");
        }
        if (!ModelRepository.isValidKey(key)) {
            return new Result(false, "\"" + key + "\" cannot name a picture — use lower-case "
                    + "letters, digits, _ or -");
        }
        PaintPanelBlockEntity panel =
                level.getBlockEntity(pos) instanceof PaintPanelBlockEntity p ? p : null;
        net.minecraft.core.Direction face = facing;
        if (face == null && level.getBlockState(pos).hasProperty(
                net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
            face = level.getBlockState(pos).getValue(
                    net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
        }
        if (panel == null && !VanillaArt.enabled()) {
            return new Result(false, "there is no painted panel at " + pos.toShortString());
        }
        // ASKED BEFORE THE BAKE, not after. Every refusal above this line leaves nothing behind,
        // and the vanilla route's own refusal has to keep that promise: writing the PNG first and
        // then discovering there is no wall to hang it on leaves a baked image for a picture that
        // was never painted.
        if (panel == null && face == null) {
            return new Result(false, "a picture on a wall has to face north, south, east or west "
                    + "— say which way it looks");
        }
        PaintRecipe r = recipe == null ? PaintRecipe.blank() : recipe;
        if (r.isEmpty()) {
            return new Result(false, "that picture has no shapes in it, so it would paint nothing");
        }
        try {
            // BAKE FIRST. See the class note: the client is told about the block entity within a
            // tick and will cache the unpainted fallback if the image is not there yet. It stays
            // first on the vanilla route too -- the image on disk is what a repaint, an undo and
            // the published manifest all read back, whichever view drew it.
            if (PaintArt.write(key, r) == null) {
                return new Result(false, "could not write the picture for \"" + key + "\"");
            }
        } catch (IOException e) {
            return new Result(false, "could not write the picture: " + e.getMessage());
        }
        int w = width > 0 ? width : panel != null ? panel.widthBlocks() : 1;
        int h = height > 0 ? height : panel != null ? panel.heightBlocks() : 1;
        String size = r.rects().size() + " shape(s) at " + r.size() + "px across "
                + PanelGeometry.clampSpan(w) + "x" + PanelGeometry.clampSpan(h) + " block(s)";

        if (panel != null) {
            panel.setArt(key, r, w, h);
            return new Result(true, "painted \"" + key + "\" — " + size
                    + " on a panel (this surface moves, so the picture rides with it)");
        }
        // A FAILURE TO HANG IS A FAILURE TO PAINT, now that this is the only view. Under the spike
        // it was reported and swallowed, because the panel had already drawn the picture and the
        // map art was a second copy; with the panel gone there is nothing left, so a swallowed
        // failure would be a wish that reports success over a blank wall.
        MapArtPanels.Result hung = MapArtPanels.hang(level, pos, face, r, w, h);
        if (!hung.ok()) {
            return new Result(false, hung.message());
        }
        return new Result(true, "painted \"" + key + "\" — " + size + " as " + hung.tiles()
                + " map tile(s) every client can see, with or without the mod");
    }

    /** Paint from the JSON an agent writes. Malformed JSON is a message, never an exception. */
    public static Result paint(ServerLevel level, BlockPos pos, String key, String recipeJson,
                               int width, int height) {
        JsonObject doc;
        try {
            doc = JsonParser.parseString(recipeJson == null ? "" : recipeJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return new Result(false, "that picture is not readable JSON: " + e.getMessage()
                    + ". It looks like {\"size\":256,\"rects\":[{\"x\":0,\"y\":0,\"w\":256,"
                    + "\"h\":256,\"colour\":\"red\"}]}");
        }
        return paint(level, pos, key, PaintRecipe.parse(doc), width, height);
    }

    /**
     * A picture to try the thing with: a simple face.
     *
     * <p>Deliberately a real picture rather than the calibration card — the card answers "is it the
     * right way up", which is a question for a gate, and this answers "does a person see a face",
     * which is the question the feature exists for. Drawn from shapes so it is also a worked example
     * of what a recipe looks like.
     */
    public static PaintRecipe demoFace() {
        int s = PaintArt.SIZE;
        return new PaintRecipe(s, java.util.List.of(
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(0, 0, s, s, 0x2FA84F),
                // eyes
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s / 6, s / 4, s / 4, s / 5, 0xFFFFFF),
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s - s / 6 - s / 4, s / 4, s / 4, s / 5, 0xFFFFFF),
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s / 6 + s / 16, s / 4 + s / 32, s / 8, s / 8, 0x101010),
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s - s / 6 - s / 4 + s / 16, s / 4 + s / 32, s / 8, s / 8, 0x101010),
                // mouth
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s / 4, s - s / 3, s / 2, s / 8, 0xC01818),
                new com.moddingfromamod.runtime.CreatureSculpt.PaintRect(
                        s / 4 + s / 16, s - s / 3 + s / 8, s / 2 - s / 8, s / 16, 0xC01818)));
    }
}
