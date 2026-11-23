package com.moddingfromamod.agent.tools;

import java.util.LinkedHashSet;
import java.util.Set;

import com.moddingfromamod.agent.AgentBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * <b>What is anything in this world actually tagged with?</b>
 *
 * <p>The one question that separates a datapack which works from one that is permanently inert,
 * and the one nothing in the stack was asking. A selector filtering on a tag nothing wears is not
 * an error at any layer — {@code execute as @e[tag=spikor] run …} with no matches is a SUCCESSFUL
 * no-op — so the pack compiles, loads, verifies, runs, and does nothing, for ever.
 *
 * <p>Shared by {@code apply_effect}, which knows the tag it just substituted, and
 * {@code verify_datapack}, which can read the selectors out of any pack including a hand-written
 * one. Two callers rather than one because the failure has two doors into it.
 */
final class WorldTags {

    private WorldTags() {
    }

    /** Is anything, in any dimension, wearing this tag right now? False if there is no server. */
    static boolean wornSomewhere(String tag) {
        return !worn(Set.of(tag)).isEmpty();
    }

    /**
     * <b>What kind of mob is wearing this tag</b> — its entity id, e.g. {@code minecraft:axolotl} —
     * or null when nothing is, or there is no world to ask. Lets {@code colour_cycle} work out
     * which palette to use when the agent tagged a mob and did not say what it was (it did
     * exactly that on its first live call).
     */
    static String typeWearing(String tag) {
        try {
            MinecraftServer srv = AgentBridge.serverOrNull();
            if (srv == null || tag == null) {
                return null;
            }
            java.util.function.Supplier<String> look = () -> {
                for (ServerLevel level : srv.getAllLevels()) {
                    for (Entity e : level.getEntities().getAll()) {
                        if (e.getTags().contains(tag)) {
                            return net.minecraft.world.entity.EntityType.getKey(e.getType())
                                    .toString();
                        }
                    }
                }
                return null;
            };
            return srv.isSameThread() ? look.get() : AgentBridge.onServer(look::get);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Which of these tags is something actually wearing?
     *
     * <p>One sweep for the whole set rather than one per tag: a pack can name a dozen, and walking
     * every entity in every dimension per tag turns a check into a stall.
     */
    static Set<String> worn(Set<String> tags) {
        Set<String> found = new LinkedHashSet<>();
        if (tags == null || tags.isEmpty()) {
            return found;
        }
        try {
            MinecraftServer srv = AgentBridge.serverOrNull();
            if (srv == null) {
                return found;
            }
            Set<String> want = Set.copyOf(tags);
            java.util.function.Supplier<Set<String>> look = () -> {
                Set<String> seen = new LinkedHashSet<>();
                for (ServerLevel level : srv.getAllLevels()) {
                    for (Entity e : level.getEntities().getAll()) {
                        for (String t : e.getTags()) {
                            if (want.contains(t)) {
                                seen.add(t);
                            }
                        }
                    }
                    if (seen.size() == want.size()) {
                        break;      // nothing left to look for
                    }
                }
                return seen;
            };
            // Already ON the server thread (a GameTest calling apply_effect): AgentBridge refuses
            // to hand over from there, and the refusal used to read as "nothing wears it" — so the
            // pack was aimed at the species tag while the mob wore the literal one.
            Set<String> hit = srv.isSameThread() ? look.get() : AgentBridge.onServer(look::get);
            if (hit != null) {
                found.addAll(hit);
            }
        } catch (RuntimeException e) {
            return found;   // no world to ask; the caller treats that as "cannot tell"
        }
        return found;
    }
}
