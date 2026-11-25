package com.moddingfromamod.wish;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.wishbuilder.BuilderAgain;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import com.moddingfromamod.wishbuilder.MakeFacts;
import net.minecraft.server.MinecraftServer;

/**
 * <b>{@link MakeFacts} for what the server holds</b>: a recipe id (a kept Builder make's {@code builder:<id>}, a taught
 * or library recipe, one part of a wish {@code <wish>@<part>}) and a thing on a child's shelf, read from the live
 * stores (species, items, buildings, pictures, event designs). The pure rules are {@link MakeFacts}; this only fetches
 * what they read. Read-only: nothing is written, cached or stamped anywhere, so the same thing has the same facts in
 * every world it is carried to. Server thread (the event designs are the world's).
 */
public final class MakeFactsLookup {

    private MakeFactsLookup() {
    }

    /**
     * What making {@code recipe} again for {@code player} makes. A kept Builder make is rated by its kept spec, or, once
     * a Magician wish has changed the thing since, by the thing as it is now (and never below its spec); a taught or
     * library recipe by its commands. A recipe that names nothing the server holds is {@link MakeFacts#cautious}.
     */
    public static MakeFacts forRecipe(MinecraftServer server, UUID player, String recipe) {
        if (recipe == null || recipe.isBlank()) {
            return MakeFacts.cautious(MakeFacts.OTHER);
        }
        MakeFacts.Artifacts artifacts = artifacts(server);
        if (BuilderAgain.is(recipe)) {
            BuilderSpecStore.Made m = player == null ? null
                    : BuilderSpecStore.get(player.toString(), BuilderAgain.makeIdOf(recipe));
            if (m == null) {
                return MakeFacts.cautious(MakeFacts.OTHER);
            }
            MakeFacts kept = MakeFacts.ofStoredSpec(m.spec());
            String again = BuilderAgain.command(m.kind(), m.id(), m.alias());
            if (!m.magicianTouched() || again == null) {
                return kept;
            }
            MakeFacts now = MakeFacts.ofCommands(List.of(again), artifacts);
            return MakeFacts.combine(List.of(now, kept));
        }
        var match = WishCatalog.templates().byId(recipe, "");
        if (match.isEmpty()) {
            return MakeFacts.cautious(MakeFacts.OTHER);
        }
        return MakeFacts.ofCommands(match.get().plan().commands(), artifacts);
    }

    /**
     * A thing on a shelf by its kind ({@code creature}, {@code item}, {@code building}, {@code picture},
     * {@code event}) and id, read from its store; {@link MakeFacts#cautious} when it is not there.
     */
    public static MakeFacts forThing(MinecraftServer server, String kind, String id) {
        return MakeFacts.ofArtifact(kind, artifacts(server).read(kind, id));
    }

    /** The live stores, as {@link MakeFacts.Artifacts} reads them. */
    public static MakeFacts.Artifacts artifacts(MinecraftServer server) {
        return (kind, id) -> read(server, kind, id);
    }

    private static JsonObject read(MinecraftServer server, String kind, String id) {
        if (kind == null || id == null || id.isBlank()) {
            return null;
        }
        BuilderSpec.Kind k = BuilderSpec.Kind.of(kind);
        if (k == null) {
            return null;
        }
        return switch (k) {
            case CREATURE, RIDE -> {
                var s = com.moddingfromamod.species.SpeciesRegistry.get(id);
                yield s == null ? null : s.toJson();
            }
            case ITEM, MACHINE, MAGIC_BLOCK -> {
                var spec = com.moddingfromamod.item.ItemSpecStore.get(id);
                if (spec == null) {
                    spec = com.moddingfromamod.item.ItemDesignId.designFor(id);
                }
                if (spec == null) {
                    yield null;   // an authored datapack, not an item design: its kind's cautious facts
                }
                JsonObject o = new JsonObject();
                o.addProperty("shape", spec.shape() == null ? "" : spec.shape());
                JsonArray effects = new JsonArray();
                (spec.effects() == null ? List.<String>of() : spec.effects()).forEach(effects::add);
                o.add("effects", effects);
                if (spec.baseItem() != null && !spec.baseItem().isBlank()) {
                    o.addProperty("base_item", spec.baseItem());
                }
                if (spec.params() != null) {
                    o.add("params", spec.params().deepCopy());
                }
                yield o;
            }
            case BUILDING -> {
                var plan = com.moddingfromamod.builder.BuildRegistry.get(id);
                yield plan == null ? null : plan.toJson();
            }
            case PICTURE -> {
                var pic = com.moddingfromamod.paint.PictureStore.get(id);
                yield pic == null ? null : pic.toJson();
            }
            case EVENT -> {
                var design = server == null ? null : com.moddingfromamod.event.design.EventDesigns.get(server, id);
                yield design == null ? null : design.toJson();
            }
            case RULE, SUPPLIES -> null;
        };
    }

    /**
     * Have the make facts read the game itself (#14): an item's attack damage from its own attribute modifiers, and the
     * health and attack damage a creature made from another one inherits, from this world's record of it (else a new
     * creature's own numbers, as the sculpt gives it). Installed when a world answers makes.
     */
    public static void readTheGame() {
        MakeFacts.itemAttack(com.moddingfromamod.item.ItemStats::attackDamage);
        MakeFacts.inherits(MakeFactsLookup::inherited);
    }

    static com.google.gson.JsonObject inherited(String startFrom) {
        var base = com.moddingfromamod.species.SpeciesRegistry.get(
                com.moddingfromamod.runtime.ModelRepository.sanitizeKey(startFrom == null ? "" : startFrom));
        if (base != null) {
            return base.toJson();
        }
        com.google.gson.JsonObject none = new com.google.gson.JsonObject();
        none.addProperty("id", "inherits_nothing");
        var fresh = com.moddingfromamod.species.Species.fromJson(none);
        return fresh == null ? null : fresh.toJson();
    }
}
