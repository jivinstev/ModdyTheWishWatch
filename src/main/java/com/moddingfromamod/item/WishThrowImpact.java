package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/**
 * <b>"…and it explodes when it lands."</b>
 *
 * <p>Landing is the hard half of a throwable, and it was the last thing in this class a wish could not
 * express cheaply. In CBScript the only route is to trail a marker entity behind the projectile every
 * tick and, when the projectile stops existing, fire at the marker and tidy it up — which every
 * throwable wish would have to re-implement, per wish, correctly, including the tidy-up.
 *
 * <p>So it is done once, here. A thrown wish item carries the function to run on impact, and this
 * runs it <b>at the point of impact</b>, which is the piece the datapack cannot easily get for
 * itself. The wish then writes one ordinary function and never thinks about markers.
 *
 * <p>The function id rides in the projectile's own persistent data rather than in a tag, because a
 * tag is a bare string in a shared namespace and an id contains a colon — encoding one into the other
 * is the kind of thing that works until a wish is called {@code my:pack}.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishThrowImpact {

    /** Where the function id is stashed on the flying projectile. */
    public static final String HIT_FN = "WishHitFunction";

    /** Set on a projectile whose landing wish must be nudged out of the block face. */
    public static final String NUDGE = "WishNudge";

    private WishThrowImpact() {
    }

    /** Where a thrower's last landing wish landed, and when (game time). */
    public record Landing(Vec3 at, long when) {
    }

    private static final java.util.Map<java.util.UUID, Landing> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Where THIS thrower's last landing wish landed, if it was within {@code maxAge} ticks — for a
     * wish that needs the point itself (an earlier issue's grapple). Per thrower, so two children throwing at
     * once never take each other's landing: the first design left a shared marker, and a concurrent
     * GameTest took the other's (1 run in 20).
     */
    public static Vec3 lastLanding(java.util.UUID thrower, long now, long maxAge) {
        Landing l = LAST.get(thrower);
        return l == null || now - l.when() > maxAge ? null : l.at();
    }

    /** For a test that throws nothing: as if his last throw landed here, now. */
    public static void recordLanding(java.util.UUID thrower, Vec3 at, long when) {
        LAST.put(thrower, new Landing(at, when));
    }

    /** Remember, on the projectile, what to run when it lands. */
    public static void arm(Entity projectile, String functionId) {
        if (functionId != null && !functionId.isEmpty()) {
            projectile.getPersistentData().putString(HIT_FN, functionId);
        }
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)) {
            return;
        }
        String fn = projectile.getPersistentData().getStringOr(HIT_FN, "");
        if (fn.isEmpty()) {
            return;
        }
        Identifier id = Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        Vec3 at = event.getRayTraceResult().getLocation();
        // AN ARROW STOPS ON THE FACE: its impact point lies exactly on the block's surface,
        // and a command positioned there floors INTO the block, so an effect that fills the air
        // where it landed ("place") found stone. Nudged a hair out of the face it hit, into the air
        // in front. Arrows only: the thrown shape's own geometry is unchanged.
        // A real throwable's landing wish (on_throw_land) has the same geometry, and asks.
        if ((projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
                || projectile.getPersistentData().getBooleanOr(NUDGE, false))
                && event.getRayTraceResult() instanceof net.minecraft.world.phys.BlockHitResult hit) {
            var d = hit.getDirection();
            at = at.add(d.getStepX() * 0.05, d.getStepY() * 0.05, d.getStepZ() * 0.05);
        }
        var server = level.getServer();
        // AT the impact, but as the THROWER where we still have them: a wish that wants to hurt "the
        // player who threw it" or hand something back needs @s to mean somebody.
        Entity owner = projectile.getOwner();
        CommandSourceStack source = (owner != null ? com.moddingfromamod.compat.EntityCommands.source(owner)
                : com.moddingfromamod.compat.EntityCommands.source(projectile))
                .withPosition(at)
                .withPermission(2)
                .withSuppressedOutput();
        if (owner != null) {
            LAST.put(owner.getUUID(), new Landing(at, level.getGameTime()));
        }
        server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f, source));
        // AN ARROW STICKS AROUND: it can hit again as it settles, and a piercing one hits
        // several things. The wish happens once, where it first landed — a snowball is gone anyway.
        if (projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
            projectile.getPersistentData().remove(HIT_FN);
        }
    }
}
