package com.moddingfromamod.item;

import java.util.List;

/**
 * The shipped icon library — the numbers a wish picks its artwork from.
 *
 * <p><b>Why a library and not artwork generated at wish time.</b> An item texture is stitched into
 * the item atlas when resources load. Adding one later needs a full resource reload: a multi-second
 * stall that would land in the middle of granting a wish, which is the worst possible moment. Drawn
 * ahead of time, an icon costs nothing at wish time, is deterministic, and can be photographed and
 * judged before a child ever sees it.
 *
 * <p><b>The numbers are a contract.</b> A wish stores its icon id on the stack and in its provenance,
 * so a number must never be reused for different artwork — an old wish would silently change shape.
 * Retire a number rather than repoint it.
 *
 * <p>Keep this in step with {@code tools/gen-wish-icons.py}, which draws the sprites and writes the
 * model overrides; {@code WishIconsTest} fails if the two ever disagree, or if an icon has no texture
 * on the classpath — a wish wearing a missing icon renders as the magenta missing-texture cube while
 * every server-side test still passes.
 */
public final class WishIcons {

    /** One icon: the number a stack carries, and the word a wish asks for it by. */
    public record Icon(int id, String name) { }

    /**
     * <b>Derived from {@link IconSprites}, not typed here.</b>
     *
     * <p>This used to be 27 hand-written {@code (number, name)} pairs, which is the authoring shape
     * {@code docs/VERSION_STRATEGY.md} §6.2 names as the difference between the 1.21.4 port being a second
     * emitter and it being a rewrite: a number in the source is a 1.21.1 override table leaking
     * into what an icon IS. An icon is a sprite with a name; the number is what today's emitter
     * happens to bind it at, and {@link IconSprites} derives it from position and refuses to let it
     * move. Every id here is byte-identical to the one that shipped — that is the whole content of
     * {@code IconSprites}' frozen table.
     */
    private static final List<Icon> ALL = derive();

    private static List<Icon> derive() {
        List<Icon> out = new java.util.ArrayList<>();
        for (IconSprites.Sprite s : IconSprites.library()) {
            out.add(new Icon(s.customModelData(), s.name()));
        }
        return List.copyOf(out);
    }

    private WishIcons() {
    }

    public static List<Icon> all() {
        return ALL;
    }

    /** The icon a wish asked for by name, or null — never a silent default (playbook §4f). */
    public static Icon byName(String name) {
        if (name == null) {
            return null;
        }
        String want = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (Icon i : ALL) {
            if (i.name().equals(want)) {
                return i;
            }
        }
        return null;
    }
}
