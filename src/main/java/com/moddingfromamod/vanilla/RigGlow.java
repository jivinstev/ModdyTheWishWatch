package com.moddingfromamod.vanilla;

/**
 * <b>What a rig's outline should be, given the carrier's glow state</b>. Pure, so the
 * decision is unit-tested without booting the game; {@code DisplayRig.mirrorGlow} reads the three
 * inputs off the carrier and writes the displays only when this value CHANGES.
 *
 * <p>The carrier is the one source of truth: {@code modify_mob}, {@code MobStates}, the GLOWING
 * effect (a datapack's {@code effect give … glowing}, a spectral arrow) and {@code /team} all write
 * the ENTITY, exactly as they do for any vanilla mob, and this mirrors what they wrote. The inputs
 * are the two things vanilla's own {@code LivingEntity.isCurrentlyGlowing} is built from on the
 * server — {@code hasGlowingTag()} OR the GLOWING effect — plus the team colour
 * {@code LevelRenderer} would have drawn the outline in.
 *
 * <p><b>Colour.</b> Vanilla draws an outline in {@code entity.getTeamColor()}; the displays are on
 * no team, and {@code Display.getTeamColor()} returns {@code glow_color_override} whenever it is not
 * -1. So the carrier's team colour becomes the displays' override, and "no team" stays -1 (vanilla's
 * white). The scoreboard is server-global and synced to every client, so a team colour is the same
 * for every viewer — there is no per-viewer colour to lose by baking it into the override.
 *
 * @param on  whether every display should carry the {@code Glowing} flag
 * @param rgb the {@code glow_color_override}, or -1 for vanilla's default; always -1 when off, so a
 *            team change on a creature that is not glowing writes nothing
 */
public record RigGlow(boolean on, int rgb) {

    /** A rig with no outline — what a freshly spawned rig already is. */
    public static final RigGlow OFF = new RigGlow(false, -1);

    /**
     * @param glowingTag  the carrier's {@code hasGlowingTag()}
     * @param glowEffect  whether the carrier has the GLOWING mob effect
     * @param teamRgb     the carrier's team colour as 0xRRGGBB, or -1 when it is on no team
     */
    public static RigGlow of(boolean glowingTag, boolean glowEffect, int teamRgb) {
        boolean on = glowingTag || glowEffect;
        return on ? new RigGlow(true, teamRgb == -1 ? -1 : teamRgb & 0xFFFFFF) : OFF;
    }
}
