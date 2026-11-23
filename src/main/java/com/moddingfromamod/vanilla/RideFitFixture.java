package com.moddingfromamod.vanilla;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * <b>A rideable test creature, made and spawned</b>. Shared by {@code RideFitProbeGameTest} (Gate B) and the client
 * ride film ({@code client/test/RideClientTest}); outside the GameTest because GameTests never ship and the film's
 * client test does.
 */
public final class RideFitFixture {

    private RideFitFixture() {
    }

    /**
     * A rideable test creature: {@code starter:<name>} (its own sculpt and species) or
     * {@code corpus:<case>} (a committed tier-experiment sculpt on the starter dragon's species),
     * made friendly and rideable, plus {@code extraPowers} (e.g. {@code flies}). Writes its sculpt and RETURNS the
     * species: a GameTest registers it through {@code SpeciesForTest} ({@link #plant}), the client ride film
     * ({@code RideClientTest}) through the registry itself.
     */
    public static Species species(String id, String sculpt, String extraPowers) throws java.io.IOException {
        String text;
        String speciesRes = "/starter-library/species/pet_dragon/species.json";
        if (sculpt.startsWith("starter:")) {
            String n = sculpt.substring(8);
            text = resource("/starter-library/species/" + n + "/sculpt.json");
            speciesRes = "/starter-library/species/" + n + "/species.json";
        } else {
            text = java.nio.file.Files.readString(SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + sculpt.substring(7) + "/sculpt.json"));
        }
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(
                JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("parts"));
        CreatureFiles.write(id, parts, text);
        JsonObject o = JsonParser.parseString(resource(speciesRes)).getAsJsonObject();
        o.addProperty("id", id);
        o.addProperty("modelKey", id);
        o.remove("abilityPacks");
        var powers = new com.google.gson.JsonArray();
        powers.add("friendly");
        powers.add("rideable");
        if (!extraPowers.isEmpty()) {
            powers.add(extraPowers);
        }
        o.add("powers", powers);
        o.addProperty("speed", 0.3);
        if (sculpt.startsWith("corpus:")) {
            o.addProperty("scale", 1.0);
        }
        return Species.fromJson(o);
    }

    /** Spawn one the way a wish does, at a spot; the bridge rigs it a tick later. */
    public static void spawn(ServerLevel level, String id, net.minecraft.world.phys.Vec3 at) {
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(at.x, at.y, at.z, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(mob);
    }

    private static String resource(String path) throws java.io.IOException {
        try (var in = RideFitFixture.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new java.io.FileNotFoundException(path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
