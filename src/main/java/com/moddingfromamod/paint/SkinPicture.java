package com.moddingfromamod.paint;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>A Minecraft player's skin, turned into a picture recipe.</b>
 *
 * <p>Why it exists: a picture of BlockyBob was painted from the model's memory — 17 hand-placed
 * rectangles, brown hair and a red shirt — and looked nothing like him. What makes a Minecraft
 * YouTuber recognisable to a child is not his real face, it is his SKIN, and a skin is already a
 * picture made of square pixels. So this needs no model knowledge at all: the PNG is the spec, and
 * only colours and numbers come out.
 *
 * <p>Pure — {@code javax.imageio} and nothing of Minecraft — so every rule below is a Gate A test.
 * The rules are vanilla's, read from {@code SkinTextureDownloader.processLegacySkin}, because a
 * picture of his skin that disagrees with what the game draws on him is a picture of somebody else:
 *
 * <ul>
 *   <li><b>The first layer is opaque.</b> Head, body, arms and legs are forced to alpha 255 (vanilla
 *       {@code setNoAlpha}); a skin's transparent pixel there is drawn black-ish by its own colour,
 *       never as a hole.</li>
 *   <li><b>A legacy 64×32 skin's hat counts only if it has a hole in it.</b> Old skins often filled
 *       the hat region with an opaque colour; vanilla's "Notch transparency hack" ignores a hat with
 *       no pixel below alpha 128, otherwise every such player would wear a solid helmet.</li>
 *   <li><b>A legacy skin has no left limbs</b>: they are the right ones mirrored.</li>
 *   <li><b>Overlays</b> (hat, jacket, sleeves, trousers) draw where alpha ≥ {@link #ALPHA_CUTOFF}.</li>
 *   <li><b>Slim arms are 3 pixels</b>, read from the profile's {@code metadata.model}, never
 *       guessed from the pixels.</li>
 * </ul>
 *
 * <p>The recipe it returns is always {@link PaintArt#SIZE} square — a recipe is square and is
 * stretched over its board — so a {@link View#FRONT} view (16×32 skin pixels) is drawn with each
 * pixel twice as wide as tall, and comes out square only on a board one wide by two tall.
 * {@link View#tallFor} is that rule, and {@code paint_surface} forces it.
 */
public final class SkinPicture {

    /** Below this alpha an overlay pixel is not drawn. Vanilla's cutout is the same half-way mark. */
    public static final int ALPHA_CUTOFF = 128;

    /** What the picture shows. {@link #FACE} is the default: a skin can contain anything below it. */
    public enum View {
        /** The 8×8 front of the head, with the hat on top. A square board. */
        FACE(8, 8),
        /** The whole player from the front, 16 wide by 32 tall. A board twice as tall as wide. */
        FRONT(16, 32);

        public final int width;
        public final int height;

        View(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /** The board height that keeps this view's pixels square on a board {@code wide} across. */
        public int tallFor(int wide) {
            return Math.max(1, wide) * height / width;
        }

        /** {@code face} or {@code front}; anything else (or nothing) is the safe default, FACE. */
        public static View of(String raw) {
            return raw != null && raw.trim().toLowerCase(Locale.ROOT).equals("front") ? FRONT : FACE;
        }
    }

    private SkinPicture() {
    }

    /** The PNG was not a skin Minecraft would draw. Its message is safe to show the agent. */
    public static final class NotASkin extends IOException {
        public NotASkin(String message) {
            super(message);
        }
    }

    /** Decode a skin PNG and paint the view. Throws {@link NotASkin} for anything not 64×32/64×64. */
    public static PaintRecipe recipe(byte[] png, boolean slim, View view) throws IOException {
        return recipe(grid(decode(png), slim, view));
    }

    /** The skin as ARGB rows, {@code [y][x]}, after vanilla's processing. 64 wide, 64 tall. */
    static int[][] decode(byte[] png) throws IOException {
        if (png == null || png.length == 0) {
            throw new NotASkin("the skin file was empty");
        }
        BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) {
            throw new NotASkin("the skin file is not a picture");
        }
        return normalise(img);
    }

    /**
     * The 64×64 skin vanilla actually draws, from either format. Package-visible so a test can
     * build a skin pixel by pixel instead of committing somebody's PNG.
     */
    static int[][] normalise(BufferedImage img) throws NotASkin {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w != 64 || (h != 32 && h != 64)) {
            throw new NotASkin("a skin is 64 by 64 or 64 by 32 pixels, and this one is " + w
                    + " by " + h);
        }
        boolean legacy = h == 32;
        int[][] px = new int[64][64];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < 64; x++) {
                px[y][x] = img.getRGB(x, y);
            }
        }
        if (legacy) {
            // The left leg and the left arm, mirrored from the right ones -- vanilla copies every
            // face of the limb box; only the FRONT face is ever read here, and that is a flip.
            mirror(px, 4, 20, 20, 52, 4, 12);    // right leg front  -> left leg front
            mirror(px, 44, 20, 36, 52, 4, 12);   // right arm front  -> left arm front
            notchHack(px, 32, 0, 64, 32);
        }
        opaque(px, 0, 0, 32, 16);
        opaque(px, 0, 16, 64, 32);
        opaque(px, 16, 48, 48, 64);
        return px;
    }

    /** Copy a {@code w×h} block from {@code (sx,sy)} to {@code (dx,dy)}, flipped left to right. */
    private static void mirror(int[][] px, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[dy + y][dx + x] = px[sy + y][sx + (w - 1 - x)];
            }
        }
    }

    /** Vanilla's {@code doNotchTransparencyHack}: an overlay with no hole in it is not drawn. */
    private static void notchHack(int[][] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((px[y][x] >>> 24) < ALPHA_CUTOFF) {
                    return;     // it has transparency, so it is a real hat
                }
            }
        }
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y][x] &= 0x00FFFFFF;
            }
        }
    }

    /** Vanilla's {@code setNoAlpha}. */
    private static void opaque(int[][] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y][x] |= 0xFF000000;
            }
        }
    }

    /**
     * The view as a grid of ARGB, {@code [y][x]}, as a viewer facing him sees it — so HIS right arm
     * is on the viewer's LEFT. Alpha 0 is a hole.
     */
    static int[][] grid(int[][] skin, boolean slim, View view) {
        int[][] g = new int[view.height][view.width];
        if (view == View.FACE) {
            stamp(g, skin, 8, 8, 0, 0, 8, 8);       // head front
            stamp(g, skin, 40, 8, 0, 0, 8, 8);      // hat front
            return g;
        }
        int arm = slim ? 3 : 4;
        stamp(g, skin, 8, 8, 4, 0, 8, 8);           // head
        stamp(g, skin, 40, 8, 4, 0, 8, 8);          // hat
        stamp(g, skin, 20, 20, 4, 8, 8, 12);        // body
        stamp(g, skin, 20, 36, 4, 8, 8, 12);        // jacket
        stamp(g, skin, 44, 20, 4 - arm, 8, arm, 12);    // his right arm, the viewer's left
        stamp(g, skin, 44, 36, 4 - arm, 8, arm, 12);    // its sleeve
        stamp(g, skin, 36, 52, 12, 8, arm, 12);     // his left arm
        stamp(g, skin, 52, 52, 12, 8, arm, 12);     // its sleeve
        stamp(g, skin, 4, 20, 4, 20, 4, 12);        // his right leg
        stamp(g, skin, 4, 36, 4, 20, 4, 12);        // its trouser layer
        stamp(g, skin, 20, 52, 8, 20, 4, 12);       // his left leg
        stamp(g, skin, 4, 52, 8, 20, 4, 12);        // its trouser layer
        return g;
    }

    /** Draw a skin region onto the grid; a pixel below the cutout leaves what is underneath. */
    private static void stamp(int[][] g, int[][] skin, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = skin[sy + y][sx + x];
                if ((c >>> 24) >= ALPHA_CUTOFF) {
                    g[dy + y][dx + x] = c | 0xFF000000;
                }
            }
        }
    }

    /**
     * The grid as rectangles, scaled up to fill a {@link PaintArt#SIZE} recipe. Greedy: each
     * unclaimed pixel grows right while the colour holds, then down while the whole run holds. Not
     * the fewest rectangles possible, but deterministic and far under the cap — measured on a real
     * skin, 31 for a face and 139 for the front against {@link PaintRecipe#MAX_RECTS} 1024.
     */
    static PaintRecipe recipe(int[][] g) {
        int gh = g.length;
        int gw = g[0].length;
        int sx = PaintArt.SIZE / gw;
        int sy = PaintArt.SIZE / gh;
        boolean[][] done = new boolean[gh][gw];
        List<CreatureSculpt.PaintRect> rects = new ArrayList<>();
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                if (done[y][x] || (g[y][x] >>> 24) < ALPHA_CUTOFF) {
                    continue;
                }
                int c = g[y][x];
                int w = 1;
                while (x + w < gw && !done[y][x + w] && g[y][x + w] == c) {
                    w++;
                }
                int h = 1;
                grow:
                while (y + h < gh) {
                    for (int i = 0; i < w; i++) {
                        if (done[y + h][x + i] || g[y + h][x + i] != c) {
                            break grow;
                        }
                    }
                    h++;
                }
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        done[y + j][x + i] = true;
                    }
                }
                rects.add(new CreatureSculpt.PaintRect(x * sx, y * sy, w * sx, h * sy,
                        c & 0xFFFFFF));
            }
        }
        return new PaintRecipe(PaintArt.SIZE, rects);
    }
}
