package com.moddingfromamod.wish;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.marshal.InWorld;
import com.moddingfromamod.marshal.WorldCatalogue;
import com.moddingfromamod.mob.Creatures;
import com.moddingfromamod.mob.EntityCapture;
import com.moddingfromamod.mob.EntityHandles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * <b>How many of each of his things are OUT in the world right now </b> — the server half of
 * {@link InWorld}. One snapshot per catalogue or per {@code ask_which}: every level's loaded
 * entities are walked ONCE ({@link Creatures#carriersBySpecies}) rather than once per entry.
 *
 * <p>The rule is what the acting tools find, deliberately: a creature counts when it is a live
 * carrier of its design in a loaded chunk — the same walk {@code remove_creature} removes from — a
 * building when {@link BuildRegistry#standsHere} says it stands (and is left unmarked when no
 * position was ever saved, rather than called gone), a changed mob when it is still alive. A
 * creature in a far, unloaded chunk counts as not out; the tools that would act on it cannot reach
 * it either, so the picker and the tool agree.
 *
 * <p>Server thread only.
 */
public final class InWorldCounts {

    private InWorldCounts() {
    }

    /** A snapshot: what each catalogue entry's mark should be. */
    public static Function<WorldCatalogue.Entry, InWorld> snapshot(MinecraftServer server) {
        Map<String, Integer> creatures = new HashMap<>();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Creatures.carriersBySpecies(level).forEach((id, list) ->
                        creatures.merge(id, list.size(), Integer::sum));
            }
        }
        return e -> of(server, creatures, e.id(), e.kind());
    }

    /** One id of a given kind, against a snapshot's creature counts. */
    static InWorld of(MinecraftServer server, Map<String, Integer> creatures, String id, String kind) {
        if (id == null || kind == null) {
            return InWorld.NOT_A_THING;
        }
        String key = AboutIds.key(id);
        return switch (kind) {
            case "species" -> InWorld.of(creatures.getOrDefault(key, 0));
            case "build" -> {
                Boolean here = BuildRegistry.standsHere(key);
                yield here == null ? InWorld.NOT_A_THING : InWorld.standing(here);
            }
            case "mob" -> {
                var h = EntityHandles.global().resolve(id);
                var live = h.isPresent() && server != null
                        ? EntityCapture.findAnywhere(server, h.get().id()) : null;
                yield InWorld.standing(live != null && live.isAlive());
            }
            default -> InWorld.NOT_A_THING;
        };
    }
}
