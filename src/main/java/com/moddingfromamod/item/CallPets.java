package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>His own pets come when he calls (T6F follow-up).</b>
 *
 * <p>The {@code call_pets} effect's function tags him {@code mfam_call_pets_<kind>}; at the end of
 * the tick this finds every loaded mob he OWNS of that kind (an {@link OwnableEntity} whose owner is
 * his UUID — a wolf, cat, parrot or tamed horse), stands up any that were told to sit, and brings it
 * to his side. A stranger's pet stays put, and nothing is ever created: in an earlier pass one run
 * answered "a whistle that calls my wolf" with a freshly summoned, untamed wolf.
 *
 * <p>Only LOADED pets can come — a dog in a chunk nobody is near is not in the world to move. The
 * tag is always taken off, so a call on a wish that never finds a pet does not linger.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class CallPets {

    /** E1's label (tools/parts-coverage.py reads this set): the behaviour this class grants. */
    public static final Set<String> PARTS = Set.of("call_pets");

    /** The tag prefix the effect writes; the rest is the kind. */
    public static final String TAG = "mfam_call_pets_";

    /** The kinds a call may name — the effect's KIND param is validated against this. */
    public static final List<String> KINDS = List.of("any", "wolf", "cat", "parrot", "horse");

    /**
     * and the creatures he WISHED — {@code creature_<species id>} for one of his wished
     * creatures (its named horn, or one he asked for), {@code event_<event id>} for a crowd a staged
     * event brought him. The id alphabet is a tag's, because the kind becomes part of one.
     */
    public static boolean isKindWord(String k) {
        return k != null && (KINDS.contains(k) || k.matches("creature_[a-z0-9_]{1,48}")
                || k.matches("event_[a-z0-9_]{1,64}"));
    }

    private CallPets() {
    }

    /**
     * The word a creature's call is kept as: {@code creature_<its BASE id>}. A creature he changed is
     * a fork ({@code fierce_red_dragon~1a2b3c4d}, ArtifactOwner), and the call must find it still
     * (2026-11-13: after "make him giant" his horn heard nothing). The fork's {@code ~} could not ride
     * a tag in a datapack's function either.
     */
    public static String creatureKind(String speciesId) {
        return "creature_" + com.moddingfromamod.marshal.ArtifactOwner.baseOf(speciesId);
    }

    /**
     * A stored kind as it is called today: a horn handed over for a fork before 2026-11-13 carries
     * {@code creature_<id>~<who>}, which calls the creature's base like every other horn now does.
     * Anything else comes back unchanged.
     */
    public static String normalise(String kind) {
        if (kind != null && kind.startsWith("creature_") && kind.indexOf(com.moddingfromamod.marshal.ArtifactOwner.FORK) > 0) {
            return creatureKind(kind.substring("creature_".length()));
        }
        return kind;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            for (String tag : List.copyOf(p.getTags())) {
                if (tag.startsWith(TAG)) {
                    p.removeTag(tag);
                    call(p, tag.substring(TAG.length()));
                }
            }
        }
    }

    /** Bring his pets of this kind to him; how many came. */
    public static int call(ServerPlayer owner, String kind) {
        kind = normalise(kind);
        if (!(owner.level() instanceof ServerLevel level)) {
            return 0;
        }
        List<Entity> mine = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!e.isAlive() || e.hasPassenger(owner) || owner.isPassengerOfSameVehicle(e)) {
                continue;
            }
            if (e instanceof OwnableEntity o && owner.getUUID().equals(com.moddingfromamod.compat.Owners.uuid(o)) && isKind(e, kind)) {
                mine.add(e);
            } else if (e instanceof net.minecraft.world.entity.Mob m && wished(m, owner, kind)) {
                mine.add(e);
            }
        }
        if (mine.isEmpty() && (kind.startsWith("creature_") || kind.startsWith("event_"))) {
            // Said, never silent: a horn that does nothing reads as a broken horn.
            owner.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Nothing of yours heard the horn — it is too far away to hear it, or it is gone."), true);
        }
        int n = 0;
        for (Entity pet : mine) {
            // A ring round him, so three wolves do not arrive inside each other.
            double angle = (Math.PI * 2 * n) / Math.max(1, mine.size());
            double x = owner.getX() + Math.cos(angle) * 1.5;
            double z = owner.getZ() + Math.sin(angle) * 1.5;
            if (pet instanceof TamableAnimal t) {
                t.setOrderedToSit(false);
                t.setInSittingPose(false);
            }
            pet.stopRiding();
            pet.teleportTo(x, owner.getY(), z);
            if (pet instanceof net.minecraft.world.entity.Mob m) {
                m.getNavigation().stop();
                // it lives where he called it now — its leash is round him, not its old spot.
                com.moddingfromamod.mob.PetBehaviours.setLeashHome(m, owner.blockPosition());
            }
            n++;
        }
        return n;
    }

    /**
     * a wished creature or a mob made his — his by its stored pet owner, never by being near
     * him — of the kind called: {@code creature_<id>} by its species, {@code event_<id>} by the
     * event's tag, {@code any} every one of them. The vanilla words stay with tamed pets.
     */
    static boolean wished(net.minecraft.world.entity.Mob m, ServerPlayer owner, String kind) {
        if (!owner.getUUID().toString().equals(com.moddingfromamod.mob.PetBehaviours.ownerStored(m))) {
            return false;
        }
        if (kind.startsWith("creature_")) {
            // By the creature's BASE: his fork of it ("make him giant") is still the creature he called.
            var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(m);
            return sp != null && com.moddingfromamod.marshal.ArtifactOwner.baseOf(sp.id())
                    .equals(com.moddingfromamod.marshal.ArtifactOwner.baseOf(kind.substring("creature_".length())));
        }
        if (kind.startsWith("event_")) {
            return m.getTags().contains(com.moddingfromamod.event.EventDirector.entityTag(kind.substring("event_".length())));
        }
        return "any".equals(kind);
    }

    static boolean isKind(Entity e, String kind) {
        return switch (kind) {
            case "wolf" -> e instanceof Wolf;
            case "cat" -> e instanceof Cat;
            case "parrot" -> e instanceof Parrot;
            case "horse" -> e instanceof AbstractHorse;
            default -> true;
        };
    }
}
