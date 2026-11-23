package com.moddingfromamod.paint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;

/**
 * <b>Puts a picture on disk under a key, using the creature sculptor's own painter.</b>
 *
 * <p>There is deliberately no second texture pipeline here. A creature's skin is already "a list of
 * coloured rectangles painted in order into a square PNG", which is exactly what a picture is, so
 * this calls {@link CreatureFiles#writeTexture} and adds only the two things a panel needs that a
 * creature does not: which folder the key names, and dropping the client's texture cache so the
 * repaint shows up on the next frame rather than the next restart.
 *
 * <p><b>The picture lives on disk and the panel stores only its key.</b> A 256&sup2; RGBA image is
 * 256 KB, and Minecraft's network frame gives up around 2 MB; {@code CapturedStructure} in the mod
 * repo already learned this the expensive way and put a UUID on the item instead of the blueprint.
 * The same rule, one repo along.
 *
 * <p><b>The standing assumption, written down rather than discovered:</b> {@code DiskTextures} reads
 * the PNG straight off the filesystem, so client and server must share one — i.e. <b>singleplayer</b>.
 * That is the creature app's existing assumption and the panel inherits it. On a dedicated server a
 * panel would need its recipe sent and painted client-side; the recipe is small enough for that to
 * be a change of plumbing rather than of design, which is the other reason to sync a recipe.
 */
public final class PaintArt {

    private PaintArt() {
    }

    /**
     * The resolution a picture is painted at.
     *
     * <p>256 is 65,536 pixels in the space of one block, against nine for a face made of coloured
     * blocks. It is a runtime texture either way, so this is memory rather than latency, and
     * the cost grows with the square — 512 is four times the memory for detail nobody standing on a
     * platform can resolve.
     */
    public static final int SIZE = 256;

    /**
     * Paint {@code rects} into the picture named by {@code key}.
     *
     * @return the file written, or null when the key could not name one
     */
    public static Path write(String key, int size, List<CreatureSculpt.PaintRect> rects)
            throws IOException {
        if (!ModelRepository.isValidKey(key)) {
            return null;
        }
        Path dir = ModelRepository.dir(key);
        Files.createDirectories(dir);
        Path file = ModelRepository.textureFile(key);
        CreatureFiles.writeTexture(file, Math.max(1, size), rects);
        // The client caches a texture on the file's mtime, so a repaint of the same key is picked
        // up on the next frame. Clearing here as well costs nothing and covers a filesystem whose
        // mtime resolution is coarser than the gap between two writes in a test.
        ModelRepository.clearCache();
        return file;
    }

    /** Paint at the standard resolution. */
    public static Path write(String key, List<CreatureSculpt.PaintRect> rects) throws IOException {
        return write(key, SIZE, rects);
    }

    /**
     * Bake a {@link PaintRecipe} into the image the panel draws.
     *
     * <p>The recipe carries its own resolution, so re-baking the same stored picture at 512 is a
     * change of one number and nothing on the block moves — which is the property that made storing
     * shapes rather than pixels worth doing in the first place.
     */
    public static Path write(String key, PaintRecipe recipe) throws IOException {
        PaintRecipe r = recipe == null ? PaintRecipe.blank() : recipe;
        return write(key, r.size(), r.rects());
    }

    /**
     * <b>A calibration card: four coloured quadrants and a black frame.</b>
     *
     * <p>Not decoration. Upside-down and mirrored are the two ways a textured quad goes wrong, and a
     * photograph of a symmetrical picture cannot tell you about either — so the gate photographs
     * <i>this</i>, whose four quadrants are all different, and reads the pixels back. The frame is
     * there so a frame shot from the wrong place is obvious rather than plausible.
     *
     * <p>Kept beside the panel rather than inside the test because a card that lives in the fixture
     * is a card only the fixture can draw; this one can be hung on a wall by hand when something
     * looks wrong in a real world.
     *
     * @param topLeft the four quadrant colours, clockwise from the top left as a viewer sees them.
     */
    public static List<CreatureSculpt.PaintRect> testCard(int size, int topLeft, int topRight,
                                                          int bottomRight, int bottomLeft) {
        int half = size / 2;
        int frame = Math.max(2, size / 32);
        return List.of(
                // The whole card black first, so the frame is simply what the quadrants leave.
                new CreatureSculpt.PaintRect(0, 0, size, size, 0x000000),
                new CreatureSculpt.PaintRect(frame, frame, half - frame, half - frame, topLeft),
                new CreatureSculpt.PaintRect(half, frame, half - frame, half - frame, topRight),
                new CreatureSculpt.PaintRect(frame, half, half - frame, half - frame, bottomLeft),
                new CreatureSculpt.PaintRect(half, half, half - frame, half - frame, bottomRight));
    }
}
