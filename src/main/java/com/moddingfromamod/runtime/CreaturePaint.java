package com.moddingfromamod.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.ForkPolicy;
import com.moddingfromamod.mob.Creatures;
import com.moddingfromamod.species.CreatureFork;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>Repaint one part of a creature, on what the rig draws</b> — the one path
 * {@code paint_creature} and {@code /wishmob paint} share.
 *
 * <p>The rule of what can be painted is {@link PartPaint}; this finds the creature's sculpt,
 * decides WHOSE design the paint goes into, writes it, and moves the revision so every rig of it
 * is rebuilt in the new colours on the next tick.
 *
 * <p>Whose, because a paint is a design edit like "make him bigger" and has the same hazard:
 * written under the library's id, the curated creature changes for every child on the server. So
 * it asks {@code ForkPolicy}, as {@code modify_mob} and {@code sculpt_creature} do. A FORK paints
 * HIS copy, and the one of its kind standing nearest him moves onto it — the same default
 * {@code modify_mob} has for "him" (one, nearest) — so the paint is on the creature he is looking
 * at and on nobody else's.
 *
 * <p>Runs on the server thread: it moves an entity.
 */
public final class CreaturePaint {

    /** How far from him "the one he means" may stand — {@code modify_mob}'s own default reach. */
    static final double NEAR = com.moddingfromamod.mob.MobTweak.DEFAULT_RADIUS;

    private CreaturePaint() {
    }

    public record Outcome(boolean ok, String message, String painted, boolean forked) {

        /** A refusal, or a result that painted nothing. */
        public Outcome(boolean ok, String message) {
            this(ok, message, "", false);
        }
    }

    /**
     * @param asker the child whose wish it is ({@code ""} for an operator's command)
     * @param child where he is, for "the one he means" after a fork; may be null
     */
    public static Outcome paint(MinecraftServer server, String key, String part, int rgb,
                                String asker, ServerPlayer child) {
        Species sp = SpeciesRegistry.get(key);
        if (sp == null || ModelRepository.sculptedParts(sp.modelKey()).isEmpty()) {
            return new Outcome(false, "There's no sculpted creature called '" + key + "'. Only "
                    + "creatures made with sculpt_creature can be repainted.");
        }
        // Check the paint BEFORE deciding whose it is, so a word that names no part forks nothing.
        PartPaint.Result dry = PartPaint.paint(ModelRepository.sculptedParts(sp.modelKey()),
                part, rgb);
        if (!dry.ok()) {
            return new Outcome(false, dry.error());
        }
        var decision = CreatureFork.decide(sp, asker);
        if (decision == ForkPolicy.Decision.REFUSE) {
            return new Outcome(false, "'" + key + "' is not this child's to change, and with no "
                    + "child bound to this wish there is nobody to make a copy for. Nothing was "
                    + "painted.");
        }
        Species target = sp;
        String moved = "";
        if (decision == ForkPolicy.Decision.FORK) {
            LivingEntity his = nearestTo(sp.id(), child);
            if (his == null) {
                return new Outcome(false, "'" + key + "' is not this child's — it belongs to "
                        + (sp.ownerId().isEmpty() ? "the shared library" : "another child")
                        + " — so a paint makes HIS OWN copy of the one he is looking at, and "
                        + "there is no " + sp.name() + " near him. Nothing was painted.");
            }
            target = CreatureFork.fork(server, sp, asker);
            if (target == null) {
                return new Outcome(false, "Could not make his own copy of '" + key + "'. Nothing "
                        + "was painted.");
            }
            Creatures.wearSpecies(his, target.id());
            moved = " '" + key + "' is not his, so this is HIS OWN copy, '" + target.id()
                    + "': the one standing nearest him wears it now, and every other " + sp.name()
                    + " is unchanged. Use '" + target.id() + "' for his next change.";
        }
        List<CreatureSculpt.Part> now = ModelRepository.sculptedParts(target.modelKey());
        PartPaint.Result r = PartPaint.paint(now, part, rgb);
        if (!r.ok()) {
            return new Outcome(false, r.error());
        }
        try {
            CreatureFiles.write(target.modelKey(), r.parts(),
                    specFor(target.modelKey(), r.parts()));
        } catch (Exception e) {
            return new Outcome(false, "Could not save the paint: " + e.getMessage());
        }
        SpeciesRegistry.touch();
        String said = "Painted " + String.join(", ", r.painted()) + " "
                + String.format("#%06X", rgb & 0xFFFFFF) + " on " + target.id() + ". The "
                + "creatures of it in the world are rebuilt in the new colour in a moment." + moved
                + " Its parts are: " + String.join(", ", PartPaint.names(r.parts()));
        return new Outcome(true, said, target.id(), decision == ForkPolicy.Decision.FORK);
    }

    /** The editable sculpt, with its parts as they are now (and its palette re-measured). */
    private static String specFor(String modelKey, List<CreatureSculpt.Part> parts) {
        JsonObject spec = new JsonObject();
        Path src = ModelRepository.sculptSource(modelKey);
        if (src != null) {
            try {
                spec = JsonParser.parseString(Files.readString(src, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (Exception ignored) {
                // An unreadable spec is rewritten from the parts, which is all the rig reads.
            }
        }
        // start_from marks it as a resolved creature, so the parts are written as they now are
        // rather than as a diff (SculptSpec.editable), and the marker itself is dropped.
        spec.addProperty("start_from", modelKey);
        return SculptSpec.editable(spec, modelKey, parts);
    }

    /**
     * The creature of {@code speciesId} he means, within {@link #NEAR}: his own nearest if he has
     * one there, else the nearest. Null if none. Shared with {@code sculpt_creature}'s fork.
     */
    public static LivingEntity nearestTo(String speciesId,
                                          ServerPlayer child) {
        if (child == null) {
            return null;
        }
        java.util.List<LivingEntity> near = new java.util.ArrayList<>();
        for (LivingEntity c : Creatures.carriersOf(child.level(), speciesId)) {
            if (c.distanceToSqr(child) <= NEAR * NEAR) {
                near.add(c);
            }
        }
        near.sort(java.util.Comparator.comparingDouble(c -> c.distanceToSqr(child)));
        // His own first, else the nearest — the same rule modify_mob uses for "the one".
        near = Creatures.hisFirst(near, child.getUUID().toString());
        return near.isEmpty() ? null : near.get(0);
    }
}
