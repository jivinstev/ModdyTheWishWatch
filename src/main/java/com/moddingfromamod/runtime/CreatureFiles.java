package com.moddingfromamod.runtime;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;

/**
 * Writes a sculpted creature into the model repository: its shape, its skin, and a starter set of
 * animations so it moves the moment it's spawned.
 *
 * <p>Separated from {@link CreatureSculpt} so all the geometry and packing maths stays pure and
 * testable, and only the unavoidable file and image work lives here. Uses {@code javax.imageio}
 * rather than Minecraft's {@code NativeImage} because this runs on the <b>server</b> side of an agent
 * tool, where there is no render thread and no GL context.
 */
public final class CreatureFiles {

    private CreatureFiles() {
    }

    /**
     * Write geometry, skin and animations for a creature.
     *
     * @return the folder written, or null if the parts produced nothing drawable
     */
    public static Path write(String key, List<CreatureSculpt.Part> parts) throws IOException {
        return write(key, parts, null);
    }

    /**
     * Write geometry, skin and animations for a creature, and — when given — its editable source
     * spec ({@code sculpt.json}) beside them. The baked model is a re-import baseline; the spec is
     * the re-editable one (see {@link SculptSpec}), so a wished creature can always be tweaked later.
     *
     * @return the folder written, or null if the parts produced nothing drawable
     */
    public static Path write(String key, List<CreatureSculpt.Part> parts, String editableSpecJson)
            throws IOException {
        CreatureSculpt.Baked baked = CreatureSculpt.bake(parts);
        if (baked == null) {
            return null;
        }
        Path dir = ModelRepository.dir(key);
        Files.createDirectories(dir);

        writeString(dir.resolve("geometry.json"), baked.geometry().toString());
        writeSkin(dir.resolve("texture.png"), baked);
        writeString(dir.resolve("animations.json"), animations().toString());
        if (editableSpecJson != null && !editableSpecJson.isBlank()) {
            writeString(dir.resolve("sculpt.json"), editableSpecJson);
        }
        // Drop the caches so the very next frame draws the new creature rather than a stale parse.
        ModelRepository.clearCache();
        return dir;
    }

    /** Fill each part's UV net with its colour. Transparent elsewhere, so nothing bleeds. */
    private static void writeSkin(Path file, CreatureSculpt.Baked baked) throws IOException {
        writeTexture(file, baked.textureSize(), baked.paint());
    }

    /**
     * <b>Paint a list of rectangles into a PNG.</b> The one painter in this repo.
     *
     * <p>Split out of {@link #writeSkin} so a creature's skin and a painted panel's picture come out
     * of the <i>same</i> code. They are the same job — a {@link CreatureSculpt.PaintRect} list is
     * already a compact, syncable recipe for an image — and a second copy would drift on the two
     * details that are easy to get wrong and silent when wrong: rectangles are painted <b>in order</b>
     * (so a base colour first and a pattern on top is what makes a striped part), and everything
     * unpainted stays <b>transparent</b> rather than black, so nothing bleeds into a neighbour.
     *
     * <p>Uses {@code javax.imageio} rather than Minecraft's {@code NativeImage} because this runs on
     * the <b>server</b> side of an agent tool, where there is no render thread and no GL context.
     *
     * <p>Written to a temp file and moved into place: the client's mtime watcher is perfectly
     * capable of picking up a half-written PNG.
     */
    public static void writeTexture(Path file, int size, List<CreatureSculpt.PaintRect> rects)
            throws IOException {
        // ONE PAINTER, and it is CreatureSculpt.rasterise -- shared with the vanilla map-art route
        // so an A/B between the two views compares a quantisation rather than two rasterisers that
        // have drifted (methodology 3.B.8). This method keeps only the encode-and-move.
        int s = Math.max(1, size);
        int[] px = CreatureSculpt.rasterise(s, rects);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, s, s, px, 0, s);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        try {
            ImageIO.write(img, "PNG", tmp.toFile());
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A starter animation set, written for the conventional bone names.
     *
     * <p>Clips may name bones the creature doesn't have — that's harmless, since a pose entry for an
     * unknown bone is simply never looked up — so one standard set covers every shape. It means a
     * sculpted creature breathes and walks immediately instead of standing there like a statue,
     * which is most of the difference between "a model" and "alive".
     *
     * <p><b>No clip moves {@code body}</b>. Legs, arms and a head are usually ROOT bones
     * beside the body, not its children, so a body that bobbed half a pixel lifted off its own legs
     * twice a stride: measured over 57 archived creatures, 46 opened a gap at every walk and idle
     * cycle and 16 more when the attack pitched the body 12 degrees. The owner saw them as "gaps
     * between joints". The limbs and the head still move; the body holds still under them.
     *
     * <p><b>The attack is timed for what the screen is SENT</b>. The rig plays a swing over
     * {@code VanillaRigAnimator.SWING_TICKS} (6) ticks and writes a pose every {@code RigAnimator.DEFAULT_INTERVAL} (3),
     * so a swing reaches a client as one or two poses. A small, quick wind-up (−40 at 0.13 of the swing) and a strike
     * past horizontal (+95 at 0.33) held to 0.7 put the arm in front on every phase; the clip before it
     * ({@link AttackClipMigration#OLD}) showed only the arm going BACK on two phases of three. Positive x is FORWARD
     * on the rig. {@code ClipCadenceTest} holds every attack to that.
     */
    public static JsonObject animations() {
        String json = """
            {
              "format_version": "1.8.0",
              "animations": {
                "animation.sculpted.idle": {
                  "loop": true, "animation_length": 3.0,
                  "bones": {
                    "head": {"rotation": {"0.0": [0,-4,0], "1.5": [2,4,0], "3.0": [0,-4,0]}},
                    "armR": {"rotation": {"0.0": [-3,0,0], "1.5": [4,0,0], "3.0": [-3,0,0]}},
                    "armL": {"rotation": {"0.0": [4,0,0], "1.5": [-3,0,0], "3.0": [4,0,0]}}
                  }
                },
                "animation.sculpted.walk": {
                  "loop": true, "animation_length": 1.0,
                  "bones": {
                    "legR": {"rotation": {"0.0": [25,0,0], "0.5": [-25,0,0], "1.0": [25,0,0]}},
                    "legL": {"rotation": {"0.0": [-25,0,0], "0.5": [25,0,0], "1.0": [-25,0,0]}},
                    "armR": {"rotation": {"0.0": [-20,0,0], "0.5": [20,0,0], "1.0": [-20,0,0]}},
                    "armL": {"rotation": {"0.0": [20,0,0], "0.5": [-20,0,0], "1.0": [20,0,0]}}
                  }
                },
                "animation.sculpted.attack": {
                  "loop": false, "animation_length": 0.6,
                  "bones": {
                    "armR": {"rotation": {"0.0": [0,0,0], "0.08": [-40,0,0], "0.2": [95,0,0],
                                          "0.42": [80,0,0], "0.6": [0,0,0]}}
                  }
                }
              }
            }
            """;
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    private static void writeString(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Atomic where possible: a half-written file would be read by the client's mtime watcher. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
