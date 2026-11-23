package com.moddingfromamod.wish.stress;

import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Stresses {@code kind=composite}: two wishes sharing a world.
 *
 * <p>Every other harness builds one artifact in clean space, and real play never does. A castle
 * gets a train station beside it; a machine goes inside the building put up a minute ago; a child
 * calls three different things "castle". The seams are where anchor collisions, name reuse and
 * selective removal live, and they are the least-tested surface in the engine.
 */
public final class CompositeHarness implements StressHarness {

    @Override
    public String kind() {
        return "composite";
    }

    @Override
    public Outcome run(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        var server = ctx.level().getServer();
        String id = "stress_" + c.id();
        List<String> parts = partsOf(c);

        boolean sameId = c.spec().has("sameId") && c.spec().get("sameId").getAsBoolean();
        boolean removeFirst = c.spec().has("removeFirst")
                && c.spec().get("removeFirst").getAsBoolean();

        // ── two artifacts of different kinds, optionally sharing a name
        String buildingId = sameId ? id : id + "_b";

        BuildPlan building = new BuildPlan(buildingId, buildingId, List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 5, 4, 5,
                        "minecraft:stone_bricks", true)));
        BuildRegistry.put(server, building);

        // A "machines" or "trains" part registered a Create contraption or a train here until both
        // went with Create. The iron block set INSIDE the building below still stands in
        // for "a thing he put in his castle", which is the half of those cases that is not Create.
        if (parts.contains("creatures")) {
            Species s = Species.fromJson(creatureJson(id + "_c"));
            if (s != null) {
                SpeciesRegistry.put(server, s);
                out.require("a creature and a building coexist",
                        SpeciesRegistry.has(id + "_c") && BuildRegistry.get(buildingId) != null,
                        "saving a creature alongside a building lost one of them");
            }
        }

        // ── the load-bearing case: remove ONE and the other must survive
        Builder.submit(ctx.level(), ctx.anchor(), building);
        Builder.drainNow(ctx.level());
        BlockPos inside = ctx.anchor().offset(2, 1, 2);
        ctx.level().setBlock(inside, Blocks.IRON_BLOCK.defaultBlockState(), 3);

        if (removeFirst || parts.contains("machines") || parts.contains("creatures")) {
            Builder.clearBuild(ctx.level(), ctx.anchor(), building);
            out.require(Invariants.P5_NON_DESTRUCTIVE,
                    ctx.level().getBlockState(inside).is(Blocks.IRON_BLOCK),
                    "removing the building also destroyed the thing standing INSIDE it — a "
                            + "machine put in a castle is not part of the castle, and losing it to "
                            + "\"get rid of the castle\" is the orphan rule eating a real creation");
            if (parts.contains("creatures")) {
                out.require("removing a building spares the creature",
                        SpeciesRegistry.has(id + "_c"),
                        "removing the building also removed the creature");
            }
        }

        ctx.level().setBlock(inside, Blocks.AIR.defaultBlockState(), 3);
        return out.build();
    }

    private static List<String> partsOf(WishCorpus.Case c) {
        List<String> out = new java.util.ArrayList<>();
        if (c.spec().has("parts") && c.spec().get("parts").isJsonArray()) {
            c.spec().getAsJsonArray("parts").forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    private static com.google.gson.JsonObject creatureJson(String id) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", id);
        o.addProperty("body", "HUMANOID");
        o.addProperty("texture", Species.DEFAULT_TEXTURE);
        o.addProperty("scale", 1.0);
        o.addProperty("health", 20.0);
        o.addProperty("base", "WILD");
        o.add("powers", new com.google.gson.JsonArray());
        return o;
    }
}
