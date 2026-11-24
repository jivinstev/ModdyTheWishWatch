package com.moddingfromamod.runtime;

/**
 * <b>AWT stays headless inside the game</b>.
 *
 * <p>Moddy draws into {@code java.awt.image.BufferedImage}s off the main thread: a wish brief scales its reference
 * pictures ({@code reference/BriefLooks}), photos are cropped and composed ({@code wish/PhotoParts},
 * {@code agent/tools/PhotographTool}, {@code ContactSheet}, {@code runtime/SpinFilm}). The first
 * {@code BufferedImage.createGraphics()} creates AWT's {@code GraphicsEnvironment}; unless AWT is headless, on macOS
 * that starts the desktop toolkit ({@code LWCToolkit.initAppkit}) from that thread, inside a process whose AppKit
 * main thread already belongs to the game's GLFW window. The window then gets no more events: the game freezes for
 * good while its integrated server carries on (Isle 11-ladder-magician, 2 of 10 runs, at a wish whose brief fetched
 * pictures). A dedicated server is normally headless already; a single-player game on a Mac was not.
 *
 * <p>Headless AWT still draws into images and renders fonts; it only refuses windows, which nothing here opens. Set
 * before any AWT class is used, so it runs where the mod class loads. A launcher or player that set the property
 * either way keeps their choice.
 */
public final class HeadlessAwt {

    public static final String PROPERTY = "java.awt.headless";

    private HeadlessAwt() {
    }

    /** Make AWT headless unless the property was already set; true when this call set it. */
    public static boolean ensure() {
        if (System.getProperty(PROPERTY) != null) {
            return false;
        }
        System.setProperty(PROPERTY, "true");
        return true;
    }
}
