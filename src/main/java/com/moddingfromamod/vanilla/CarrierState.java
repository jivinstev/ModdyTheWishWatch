package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.species.Species;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.PlayerTeam;

/**
 * <b>Everything about ONE animal that is not its species</b> — what a rig rebuild
 * must carry from the carrier it throws away to the one it makes.
 *
 * <p>A rebuild (a re-sculpt, "make him bigger", a carrier that could not be adopted) reaps the
 * carrier and manifests a new entity. {@code respawn} carried the name, the effects, the glow and
 * the team by hand, and nothing else — so "follow me" then "make him bigger" was a dragon that
 * stopped following, with nobody told (a plausible cause of a reported <i>"it stopped
 * following me"</i>). The pet behaviour, its owner, a nametag's visibility,
 * {@code invulnerable}, {@code NoAI} and a datapack's own tags were all state on the old carrier.
 *
 * <p>So the list is this record, read in one place ({@link #of}) and written in one place
 * ({@link #putOn}). Its equality IS the test: {@code RigRebuildKeepsTheAnimalGameTest} reads it off
 * the carrier before a rebuild and off the new one after, so a field added here is checked with no
 * edit to the test, and a field {@code modify_mob} learns to set per animal belongs here too.
 *
 * <p>Not here, on purpose: health (it depends on the species — {@code RigVitals.carrying}), and
 * whatever the species decides ({@code SpeciesOnRig.applyAtBirth}): its fire resistance, its
 * glow, its default behaviour. Those are re-derived from the edited species, which is the point of
 * the edit.
 */
public record CarrierState(List<MobEffectInstance> effects, Component name, boolean nameVisible,
                           boolean glowing, String team, String pet, String petOwner,
                           boolean noAi, boolean invulnerable, Set<String> tags,
                           Long home, int homeRadius) {

    public static CarrierState of(Mob carrier) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance e : carrier.getActiveEffects()) {
            // The rig's own invisibility is the rig's, and a species' fire resistance is the
            // species' — both are put back by the rebuild itself.
            if (!e.getEffect().is(MobEffects.INVISIBILITY) && !SpeciesOnRig.isOurs(e)) {
                effects.add(new MobEffectInstance(e));
            }
        }
        effects.sort(java.util.Comparator.comparing(e -> e.getEffect().getRegisteredName()));
        Set<String> tags = new TreeSet<>();
        for (String t : carrier.entityTags()) {
            if (!rigsOwn(t)) {
                tags.add(t);
            }
        }
        return new CarrierState(List.copyOf(effects),
                carrier.hasCustomName() ? carrier.getCustomName() : null,
                carrier.isCustomNameVisible(),
                carrier.hasGlowingTag(),
                carrier.getTeam() instanceof PlayerTeam t ? t.getName() : null,
                PetBehaviours.stored(carrier),
                PetBehaviours.ownerStored(carrier),
                carrier.isNoAi(),
                carrier.isInvulnerable(),
                java.util.Collections.unmodifiableSet(tags),
                // WHERE IT IS KEPT: a creature seated on a building's spot (a boss on his throne,
                // SpotSeat.remember) is restricted to it, and that leash is the carrier's -- a rebuild
                // dropped it, and the boss strolled off the throne (the 2026-10-25 release's red test)
                carrier.hasHome() ? carrier.getHomePosition().asLong() : null,
                carrier.hasHome() ? (int) carrier.getHomeRadius() : 0);
    }

    /**
     * Put this animal back on a carrier the rig just built, with {@code sp} — the edited species —
     * deciding everything that is the species'.
     */
    public void putOn(ServerLevel level, Mob rebuilt, Species sp) {
        for (MobEffectInstance e : effects) {
            rebuilt.addEffect(new MobEffectInstance(e));
        }
        if (name != null) {
            rebuilt.setCustomName(name);
        }
        rebuilt.setCustomNameVisible(nameVisible);
        // The species again, with the child's own glow carried over: a re-sculpt that made
        // it fireproof must stop it burning, and one that made it glow must light it.
        SpeciesOnRig.applyAtBirth(rebuilt, sp, glowing);
        if (team != null) {
            PlayerTeam t = level.getScoreboard().getPlayerTeam(team);
            if (t != null) {
                level.getScoreboard().addPlayerToTeam(rebuilt.getScoreboardName(), t);
            }
        }
        rebuilt.setNoAi(noAi);
        rebuilt.setInvulnerable(invulnerable);
        if (home != null) {
            rebuilt.setHomeTo(net.minecraft.core.BlockPos.of(home), homeRadius);
        }
        for (String t : tags) {
            rebuilt.addTag(t);
        }
        PetBehaviours.putBack(rebuilt, pet, petOwner);
    }

    /**
     * A tag the rig writes on every carrier itself (so the new one already has its own), or one
     * that names the OLD carrier or a species the edit may have changed.
     */
    static boolean rigsOwn(String tag) {
        return tag.equals(DisplayRig.RIG_TAG)
                || tag.startsWith(DisplayRig.RIG_OWNER_PREFIX)
                || tag.startsWith(DisplayRig.RIG_INDEX_PREFIX)
                || tag.equals(WishMobTags.ALL)
                || tag.startsWith(WishMobTags.PREFIX)
                || tag.equals(RigVeilService.VEILED_TAG);
    }
}
