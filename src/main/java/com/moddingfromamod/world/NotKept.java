package com.moddingfromamod.world;

import java.util.UUID;

/**
 * <b>A make this world asked to keep, and the core did not</b> (#70, {@link WorldMakes#notKept}): whose it was, its
 * kind ({@code creature}, {@code item}, {@code building}, {@code event}, {@code picture} or {@code other}), what the
 * world asked, and why it was not kept. Data only: never a world's words or anything he typed.
 */
public record NotKept(UUID player, String kind, Landing asked, Why why) {

    /** Why a make the world asked to keep was not kept. */
    public enum Why {
        /** No replay recipe can make this kind again (a picture, a rule, a command, a real mob, supplies): it landed. */
        NO_REPLAY_RECIPE,
        /** A change to something he already has: it is changed where it stands, so it landed. */
        A_CHANGE,
        /** A kept Magician wish finished having taught no recipe: nothing could be handed over (nothing was placed). */
        NOTHING_TAUGHT
    }
}
