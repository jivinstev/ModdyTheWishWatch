package com.moddingfromamod.wish;

import com.moddingfromamod.runtime.Pixels;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishParts;
import com.moddingfromamod.marshal.WishRun;

/**
 * <b>A wish that made two things is photographed as two things</b>. Minecraft-free.
 *
 * <p>The sandcastle-and-seahorses wish (2026-10-21) was graded on one picture. {@code finish_building}
 * named no subject, so {@link PhotoSubject#choose} aimed at the NEWEST thing it made — a seahorse,
 * standing inside the castle's walls — and the camera photographed walls. The verdict, the
 * description and the picture on his account page were all of the castle alone.
 *
 * <p>So when a wish made more than one thing a camera can frame (a building and a creature, two
 * creatures), each is photographed and the shots are stacked into the one picture the agent grades:
 * the PLACE first (a building), then what lives there, at most {@link #MAX} parts. One thing, or a
 * subject the agent named, is the single photograph it always was.
 */
public final class PhotoParts {

    private PhotoParts() {
    }

    /** At most this many parts: each is a camera shot, and the stacked picture must stay readable. */
    public static final int MAX = 3;

    /** The kinds that are a PART of a compound wish: a place, and what lives in it — in that order. */
    private static final List<String> ORDER = List.of(MadeKind.BUILD, MadeKind.CREATURE);

    /**
     * The parts to photograph, place first and at most {@link #MAX}; empty when there is only one
     * (photograph it as before).
     *
     * <p>A part is what {@link WishParts#of} says it is, never a second definition: a {@code mob}
     * handle beside its own creature (a sculpt, then {@code modify_mob rideable} — the same pegasus
     * twice) and a building the wish took away again are NOT parts. Reading {@code made()} raw
     * photographed the pegasus twice and a removed first draft of a castle (an earlier issue's review; both
     * shapes are in real play). A thing an earlier wish made that this one CHANGED is photographed:
     * leaving it out photographed the seahorses without the castle they were put in (an earlier issue's R4 film).
     *
     * @param newestFirst   what the wish made ({@code WishRun.made()})
     * @param removed       what it took away again ({@code WishRun.removedThings()})
     * @param existedBefore what it changed that was already there — photographed all the same
     */
    public static List<String> parts(List<WishRun.Made> newestFirst, Set<WishRun.Made> removed,
                                     Set<WishRun.Made> existedBefore) {
        // An EDITED thing is photographed: "put seahorses in my castle" is judged on the castle
        // with its seahorses. (Only the hand-over and recipe questions care whether it is new.)
        List<String> list = ordered(newestFirst, removed, Set.of());
        if (list.size() < 2) {
            return List.of();
        }
        return list.size() > MAX ? List.copyOf(list.subList(0, MAX)) : List.copyOf(list);
    }

    /**
     * The things a compound wish's hand-over notes are for: every part, place first, with no cap
     * (a note is a store row, not a camera shot), the things it EDITED included: the next wish about
     * an edited castle deserves to know what this one tried. Empty when there is only one.
     */
    public static List<String> noted(List<WishRun.Made> newestFirst, Set<WishRun.Made> removed,
                                     Set<WishRun.Made> existedBefore) {
        List<String> list = ordered(newestFirst, removed, Set.of());
        return list.size() < 2 ? List.of() : List.copyOf(list);
    }

    /** A run's parts, as {@code made()}, {@code removedThings()} and {@code existedBefore()} say. */
    public static List<String> parts(WishRun run) {
        return run == null ? List.of() : parts(run.made(), run.removedThings(), run.existedBefore());
    }

    /** A run's hand-over targets, as {@link #noted}. */
    public static List<String> noted(WishRun run) {
        return run == null ? List.of() : noted(run.made(), run.removedThings(), run.existedBefore());
    }

    private static List<String> ordered(List<WishRun.Made> newestFirst, Set<WishRun.Made> removed,
                                        Set<WishRun.Made> existedBefore) {
        if (newestFirst == null) {
            return List.of();
        }
        List<WishRun.Made> kept = WishParts.of(newestFirst, removed == null ? Set.of() : removed,
                existedBefore == null ? Set.of() : existedBefore, null);
        Set<String> out = new LinkedHashSet<>();
        for (String kind : ORDER) {
            for (WishRun.Made m : kept) {
                if (kind.equals(m.kind())) {
                    out.add(m.id().trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Stack these base64 pictures top to bottom, each scaled to the widest's width, into one PNG
     * (base64). A picture that cannot be read is skipped; null when none can.
     */
    public static String stack(List<String> base64Pictures) {
        List<BufferedImage> imgs = new ArrayList<>();
        for (String b64 : base64Pictures) {
            if (b64 == null || b64.isEmpty()) {
                continue;
            }
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
                if (img != null) {
                    imgs.add(img);
                }
            } catch (Exception e) {
                // an unreadable shot is left out; the others still describe the wish
            }
        }
        if (imgs.isEmpty()) {
            return null;
        }
        int w = 0;
        for (BufferedImage i : imgs) {
            w = Math.max(w, i.getWidth());
        }
        int h = 0;
        for (BufferedImage i : imgs) {
            h += Math.round(i.getHeight() * (float) w / i.getWidth());
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int y = 0;
        for (BufferedImage i : imgs) {
            int ih = Math.round(i.getHeight() * (float) w / i.getWidth());
            Pixels.draw(out, i, 0, y, w, ih);   // no Java2D: it froze the game window on macOS (runtime/Pixels)
            y += ih;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "PNG", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
