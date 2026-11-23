package com.moddingfromamod.agent.tools;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * <b>Hold the creature still for the four stills, then give it back exactly as it was.</b>
 *
 * <p>The orbit's four stops take about two seconds, and a live mob does not hold still that long.
 * The eval harness always froze its subject ({@code RephotoClientTest}: "four angles or four
 * accidents"), which is why the harness's contact cards looked right while a REAL wish's did not —
 * nothing on the shipped path froze anything. Measured on the round: the "left" stop shot
 * from 6° beside the face, the "back" from 166°, a frame of empty ground where the creature had
 * walked off, and seats 1–3 blocks from where the creature had got to.
 *
 * <p>And the harness's own freeze had quietly stopped working as well: it froze
 * {@code WishMobEntity}, and since the rig became the default a creature is a vanilla
 * CARRIER, so it froze nothing. A hold that names the entity type is a hold that rots; this one
 * takes whatever {@code findSubject} found.
 *
 * <p>Freezing stops the AI and nothing else: the creature is not moved, turned or re-posed, so the
 * photographs are still the angles the child will see. The prior AI state is restored, so a
 * creature that was ALREADY still (a statue the agent made on purpose, a harness's frozen
 * subject) stays still.
 */
final class SubjectHold implements AutoCloseable {

    private final Mob mob;
    private final boolean wasNoAi;

    private SubjectHold(Mob mob) {
        this.mob = mob;
        this.wasNoAi = mob == null || mob.isNoAi();
    }

    /** Freeze {@code subject} if it has an AI to freeze. Must be called on the server thread. */
    static SubjectHold freeze(LivingEntity subject) {
        Mob mob = subject instanceof Mob m ? m : null;
        SubjectHold hold = new SubjectHold(mob);
        if (mob != null) {
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
            // AND STOP IT TURNING. NoAi stops the brain, not the neck: LivingEntity still eases
            // yBodyRot towards yRot and the head every tick, so a body caught mid-turn keeps
            // turning after the orbit has read its facing -- measured, a penguin frozen at 45
            // degrees photographed in profile at both its "face" and its "back" stops. Bring the
            // entity yaw and the head to where the body already is, so there is nothing to ease
            // towards; the body, which is what is drawn, does not move.
            mob.setYRot(mob.yBodyRot);
            mob.setYHeadRot(mob.yBodyRot);
            mob.yRotO = mob.yBodyRot;
            mob.yBodyRotO = mob.yBodyRot;
            mob.yHeadRotO = mob.yBodyRot;
        }
        return hold;
    }

    /** Put the AI back as it was. Must be called on the server thread; safe to call twice. */
    @Override
    public void close() {
        if (mob != null && !wasNoAi && mob.isAlive()) {
            mob.setNoAi(false);
        }
    }
}
