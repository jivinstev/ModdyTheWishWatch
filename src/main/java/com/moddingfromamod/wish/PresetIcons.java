package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WatchIcons;

/**
 * <b>A remembered wish shows what its recipe MAKES</b> — the server half of
 * {@link WatchIcons#recipeTarget}.
 *
 * <p>A button for a taught wish that carries no icon of its own used to be iconised from its words,
 * which sends 40% of the wishes a child has actually typed to the amethyst fallback. Its recipe
 * names the thing it makes, and the server knows what that thing looks like — the same answer the
 * creation rows give ({@code WatchSnapshot}): an item's own sprite and colour, a building's main
 * block, a creature's colour, a vanilla mob's spawn egg. The first command that makes a picturable
 * thing decides; a recipe that makes none of them keeps the old words-based answer.
 */
public final class PresetIcons {

    private PresetIcons() {
    }

    /** The icon for this template's recipe, or "" when it makes nothing we can picture. */
    public static String forRecipe(String templateId, String phrase) {
        if (templateId == null || templateId.isBlank()) {
            return "";
        }
        var match = WishCatalog.templates().byId(templateId, phrase == null ? "" : phrase);
        if (match.isEmpty()) {
            return "";
        }
        for (String command : match.get().plan().commands()) {
            String icon = forTarget(WatchIcons.recipeTarget(command));
            if (!icon.isEmpty()) {
                return icon;
            }
        }
        return "";
    }

    /**
     * <b>The icon a creature is shown as</b> — shared by the creation rows ({@code WatchSnapshot})
     * and the preset buttons, so the two cannot disagree. In order:
     * <ol>
     *   <li><b>sculpted</b>: an egg in the colour its parts are mostly made of. Its species
     *       {@code tint} is the white default and says nothing — asking it drew every creature a
     *       child made as the same white egg;</li>
     *   <li><b>tinted</b> (a texture creature given a colour, the Pink Spider Queen): an egg in
     *       that colour;</li>
     *   <li><b>a vanilla skin, untinted</b> (Guard Bot wears the skeleton's): that mob's own spawn
     *       egg, when the game has one;</li>
     *   <li>otherwise a white egg.</li>
     * </ol>
     */
    public static String creatureIcon(com.moddingfromamod.species.Species species) {
        if (species.hasCustomModel()) {
            int rgb = com.moddingfromamod.runtime.SculptCritique.dominantColour(
                    com.moddingfromamod.runtime.ModelRepository.sculptedParts(species.modelKey()));
            if (rgb >= 0) {
                return WatchIcons.forCreature(rgb);
            }
        }
        if ((species.tint() & 0xFFFFFF) != com.moddingfromamod.species.Species.NO_TINT) {
            return WatchIcons.forCreature(species.tint());
        }
        String egg = WatchIcons.forVanillaMob(species.texture());
        try {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(
                    net.minecraft.resources.Identifier.parse(egg))) {
                return egg;
            }
        } catch (RuntimeException notAnId) {
            // a texture key that is not an id falls through to the white egg
        }
        return WatchIcons.forCreature(species.tint());
    }

    static String forTarget(WatchIcons.RecipeTarget t) {
        if (t == null) {
            return "";
        }
        switch (t.kind()) {
            case MadeKind.ITEM -> {
                var spec = com.moddingfromamod.item.ItemSpecStore.get(t.id());
                if (spec == null || spec.look() == null
                        || com.moddingfromamod.item.IconSprites.byName(spec.look().sprite()) == null) {
                    return "";
                }
                int rgb = com.moddingfromamod.species.Species.parseColour(spec.look().tint());
                return WatchIcons.sprite(spec.look().sprite(),
                        rgb < 0 ? 0xFFFFFF : rgb);
            }
            case MadeKind.BUILD -> {
                var plan = com.moddingfromamod.builder.BuildRegistry.get(t.id());
                if (plan == null) {
                    return "";
                }
                // counted from the building as it stands, not from shape volumes (ComposedPlan).
                java.util.Map<String, Integer> volume =
                        com.moddingfromamod.builder.ComposedPlan.blockCounts(plan);
                return WatchIcons.forBuild(volume);
            }
            case WatchIcons.SPECIES -> {
                // Not only the registry: a library creature is registered at its first SPAWN, so a
                // starter button drawn before anyone spawned it had no icon (found by a targeted
                // Gate B run, 2026-09-26, where no earlier test had spawned the dragon).
                var species = com.moddingfromamod.runtime.WishMobCommands.peekSpecies(t.id());
                return species == null ? "" : creatureIcon(species);
            }
            case MadeKind.PAINTING -> {
                return WatchIcons.forKind(MadeKind.PAINTING);
            }
            case MadeKind.EVENT -> {
                return WatchIcons.forKind(MadeKind.EVENT);
            }
            case MadeKind.MOB -> {
                return WatchIcons.forVanillaMob(t.id());
            }
            default -> {
                return "";
            }
        }
    }
}
