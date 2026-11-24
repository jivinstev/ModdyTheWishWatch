package com.moddingfromamod.mob;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reading a {@link MobState} off a live mob, and putting one back.
 *
 * <p>The Minecraft half of the undo record. Split from {@link MobState} so that record stays free of
 * game imports and its rules stay unit-testable — the same split as {@code MobHandleStore} and
 * {@code EntityHandles}.
 *
 * <p><b>Absent is not zero.</b> Every read returns null for an attribute the mob does not have, and
 * every write skips a null. A cow with no attack damage must come back out of an undo as a cow with
 * no attack damage, not as a cow that hits for nothing — those are different animals to every mod
 * that reads the attribute.
 */
public final class MobStates {

    /** Must match {@code ModifyMobTool}'s, or a restored glow would land on a second team. */
    static final String TEAM_PREFIX = "wish_glow_";

    private MobStates() {
    }

    /** Everything {@code modify_mob} can write, as this mob has it right now. */
    public static MobState read(Scoreboard scoreboard, LivingEntity e) {
        if (e == null) {
            return MobState.UNKNOWN;
        }
        return new MobState(
                value(e, Attributes.SCALE),
                value(e, Attributes.MAX_HEALTH),
                value(e, Attributes.ATTACK_DAMAGE),
                value(e, Attributes.MOVEMENT_SPEED),
                glowOf(scoreboard, e),
                e.getCustomName() == null ? null : e.getCustomName().getString(),
                e.isInvulnerable(),
                PetBehaviours.stored(e));
    }

    /**
     * Put it back.
     *
     * @return true if anything was actually restored — false is the honest answer for a mob we know
     *         nothing about, and it is what makes the undo report say "could not" rather than
     *         claiming a reversal that did nothing
     */
    public static boolean restore(Scoreboard scoreboard, LivingEntity e, MobState was) {
        if (e == null || was == null || was.isEmpty()) {
            return false;
        }
        boolean any = false;
        if (was.scale() != null && set(e, Attributes.SCALE, was.scale())) {
            // Same reason modify_mob does it: without this the hitbox keeps the size it had while
            // the model shrinks back, so the mob is punchable where its bigger self used to be.
            e.refreshDimensions();
            any = true;
        }
        if (was.maxHealth() != null && set(e, Attributes.MAX_HEALTH, was.maxHealth())) {
            e.setHealth(Math.min(e.getHealth(), (float) (double) was.maxHealth()));
            any = true;
        }
        if (was.damage() != null && set(e, Attributes.ATTACK_DAMAGE, was.damage())) {
            any = true;
        }
        if (was.speed() != null && set(e, Attributes.MOVEMENT_SPEED, was.speed())) {
            any = true;
        }
        if (was.name() == null) {
            // It had no name before the wish gave it one, so taking the name off IS the reversal.
            if (e.getCustomName() != null) {
                e.setCustomName(null);
                e.setCustomNameVisible(false);
                any = true;
            }
        } else {
            e.setCustomName(Component.literal(was.name()));
            e.setCustomNameVisible(true);
            any = true;
        }
        if (was.invulnerable() != null) {
            e.setInvulnerable(was.invulnerable());
            any = true;
        }
        any |= restoreGlow(scoreboard, e, was.glow());
        if (was.pet() != null && e instanceof net.minecraft.world.entity.Mob mob
                && !was.pet().equals(PetBehaviours.stored(mob))) {
            // Undoing "follow me" stops the following: the behaviour goes back to what it was,
            // including nothing at all.
            PetBehaviours.restore(mob, was.pet());
            any = true;
        }
        return any;
    }

    /**
     * The colour name of the glow team this mob is on, or null if it is not glowing for us.
     *
     * <p>Public because {@code Surroundings} reports it too: {@code modify_mob} takes an absolute
     * colour, so "make him a different colour" cannot be answered without knowing what colour he is
     *. Same read in both places, or the report and the undo record could disagree.
     */
    public static String glowOf(Scoreboard scoreboard, LivingEntity e) {
        if (scoreboard == null || !e.hasGlowingTag()) {
            return null;
        }
        PlayerTeam team = scoreboard.getPlayersTeam(e.getScoreboardName());
        if (team == null || !team.getName().startsWith(TEAM_PREFIX)) {
            // Glowing for some other reason entirely — a beacon, another mod, a potion. Not ours to
            // record and definitely not ours to switch off on an undo.
            return null;
        }
        return team.getName().substring(TEAM_PREFIX.length());
    }

    private static boolean restoreGlow(Scoreboard scoreboard, LivingEntity e, String colour) {
        if (scoreboard == null) {
            return false;
        }
        String member = e.getScoreboardName();
        PlayerTeam current = scoreboard.getPlayersTeam(member);
        boolean onOurTeam = current != null && current.getName().startsWith(TEAM_PREFIX);
        if (colour == null) {
            if (!onOurTeam) {
                return false;
            }
            scoreboard.removePlayerFromTeam(member, current);
            e.setGlowingTag(false);
            return true;
        }
        String teamName = TEAM_PREFIX + colour;
        PlayerTeam team = scoreboard.getPlayersTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
            java.util.Optional<net.minecraft.world.scores.TeamColor> named =
                    com.moddingfromamod.compat.TeamColors.byName(colour);
            if (named.isPresent()) {
                team.setColor(named);
            }
        }
        scoreboard.addPlayerToTeam(member, team);
        e.setGlowingTag(true);
        return true;
    }

    private static Double value(LivingEntity e, Holder<Attribute> attribute) {
        AttributeInstance instance = e.getAttribute(attribute);
        return instance == null ? null : instance.getBaseValue();
    }

    private static boolean set(LivingEntity e, Holder<Attribute> attribute, double v) {
        AttributeInstance instance = e.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.setBaseValue(v);
        return true;
    }
}
