package com.moddingfromamod.reference;

/**
 * <b>What sort of thing a reference is for </b>, because the right pictures differ.
 *
 * <p>A CHARACTER (Spikor, a brainrot shark, a Minecraft mob) has a franchise wiki full of cut-out
 * renders, and those are its only trustworthy pictures — a photo of a plush or a game screenshot
 * is a picture of something else. A CREATURE (an axolotl, a pegasus) has no renders at all: its
 * pictures are photographs and paintings, so only for it are opaque images masked and Commons
 * searched. Measured on the frozen corpus: masking opaque pictures for EVERY subject added one
 * correct palette and three confidently wrong ones, two of them characters (Puffo read grey off
 * masked screenshots).
 *
 * <p>A PERSON is a real, PUBLIC person — a YouTuber, a streamer, an athlete. It is the one
 * kind the resolver may not decide on its own: it is kept only when {@link PublicFigure} finds a
 * Wikipedia article for the name, because a child naming a classmate must start no search at all.
 * And its pictures are never photographs: what makes a Minecraft YouTuber recognisable is his
 * Minecraft SKIN ({@code paint_surface from_skin}), and a likeness built from a photo of a real face
 * is not built. So a PERSON starts no researcher run over his photographs.
 * The skin is fetched only for an account an outside source LINKS to him
 * ({@link MinecraftWikiPerson}); with no link, {@code look_up_character} asks for
 * <i>"&lt;name&gt;'s Minecraft skin"</i> as a {@link #CHARACTER} — renders of his look in the game.
 *
 * <p>Anything else — an object, a place, a material — is not looked up at all
 * ({@link CharacterResolver}).
 */
public enum SubjectKind {
    CHARACTER,
    CREATURE,
    PERSON
}
