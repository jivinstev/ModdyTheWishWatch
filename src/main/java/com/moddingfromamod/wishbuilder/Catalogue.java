package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.rules.WorldRule;
import com.moddingfromamod.species.Species;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Where every Builder choice comes from</b> (docs/WISH_BUILDER_PLAN.md §2). A spec value is
 * {@code <prefix>:<id>}, and the prefix names the SAME source the tool that will run it validates
 * against — so "the Builder offers it" and "the tool takes it" cannot drift (principle 6):
 *
 * <table>
 *   <tr><th>prefix</th><th>source</th><th>run by</th></tr>
 *   <tr><td>{@code power:}</td><td>{@link Species.Power}</td><td>{@code sculpt_creature.powers}</td></tr>
 *   <tr><td>{@code ability:}</td><td>{@link EffectCatalogue}</td><td>{@code apply_effect.effect}</td></tr>
 *   <tr><td>{@code effect:}</td><td>{@link ItemCatalogue}</td><td>{@code make_item.effect}</td></tr>
 *   <tr><td>{@code shape:}</td><td>{@link ItemCatalogue#EFFECT_SHAPES}</td><td>{@code make_item.shape}</td></tr>
 *   <tr><td>{@code icon:}</td><td>{@link ItemCatalogue#ICONS}</td><td>{@code make_item.icon}</td></tr>
 *   <tr><td>{@code motion:}</td><td>{@link EventDesign.Motion}</td><td>{@code stage_event.motion}</td></tr>
 *   <tr><td>{@code when:}</td><td>{@link WorldRule.When}</td><td>{@code set_rule.when}</td></tr>
 *   <tr><td>{@code preset:}</td><td>the starter library ({@code starter-library/index.txt})</td><td>start_from / its stored args</td></tr>
 *   <tr><td>{@code mob:}</td><td>a vanilla entity id (checked against the live registry at make time)</td><td>{@code run_command summon}</td></tr>
 *   <tr><td>{@code rule:}</td><td>{@link RuleEffects} (Builder-owned: a rule's effect as set_rule arguments)</td><td>{@code set_rule}</td></tr>
 *   <tr><td>{@code look:}</td><td>{@link BuilderSnacks#LOOKS} (Builder-owned,: a food's sprite, tint and base)</td><td>{@code make_item} icon/colour/base_item</td></tr>
 *   <tr><td>{@code ingredient:}</td><td>{@link BuilderSnacks#INGREDIENTS} (Builder-owned,: what may go in a snack machine)</td><td>{@code make_item} load_and_pour INGREDIENTS</td></tr>
 *   <tr><td>{@code supply:}</td><td>{@link BuilderSupplies#ALL} (Builder-owned,: a vanilla item and how many)</td><td>{@code give_items}</td></tr>
 *   <tr><td>{@code scheme:}</td><td>{@link Schemes} (Builder-owned: a translation to colours)</td><td>{@code colour}/{@code recolour}</td></tr>
 * </table>
 *
 * Minecraft-free, so the whole check is Gate A ({@code CatalogueTest}).
 */
public final class Catalogue {

    private Catalogue() {
    }

    public static final List<String> PREFIXES =
            List.of("power", "ability", "effect", "shape", "icon", "motion", "when", "preset", "mob", "scheme", "rule", "kit", "look", "ingredient", "supply", "item");

    /** Null when {@code id} names something its catalogue really has; otherwise what is wrong. */
    public static String problem(String id) {
        if (id == null || id.indexOf(':') < 1) {
            return "'" + id + "' has no catalogue prefix (one of " + PREFIXES + ")";
        }
        String prefix = id.substring(0, id.indexOf(':'));
        String rest = id.substring(id.indexOf(':') + 1);
        if (rest.isBlank()) {
            return "'" + id + "' names nothing";
        }
        return switch (prefix) {
            case "power" -> enumHas(Species.Power.class, rest) ? null : "no creature power '" + rest + "'";
            case "ability" -> abilities().contains(rest) || BuilderAbilities.byId(rest) != null ? null
                    : "no creature ability '" + rest + "'";
            case "effect" -> ItemCatalogue.load().get(rest) != null ? null : "no item effect '" + rest + "'";
            case "shape" -> ItemCatalogue.EFFECT_SHAPES.contains(rest) ? null : "no item shape '" + rest + "'";
            case "icon" -> ItemCatalogue.ICONS.containsKey(rest) ? null : "no item icon '" + rest + "'";
            case "motion" -> enumHas(EventDesign.Motion.class, rest) ? null : "no event motion '" + rest + "'";
            case "when" -> enumHas(WorldRule.When.class, rest) ? null : "no rule trigger '" + rest + "'";
            case "preset" -> presets().contains(rest) ? null : "no ready-made '" + rest + "' in the starter library";
            case "mob" -> rest.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") ? null : "'" + rest + "' is not an entity id";
            case "scheme" -> Schemes.byId(rest) != null ? null : "no colour scheme '" + rest + "'";
            case "rule" -> RuleEffects.byId(rest) != null ? null : "no rule effect '" + rest + "'";
            case "kit" -> com.moddingfromamod.runtime.SculptKits.available().contains(rest) || BuilderParts.byId(rest) != null ? null : "no sculpt kit '" + rest + "'";
            case "look" -> BuilderSnacks.look(rest) != null ? null : "no food look '" + rest + "'";
            case "ingredient" -> BuilderSnacks.INGREDIENTS.contains(rest) ? null : "'" + rest + "' cannot go in a snack machine";
            case "supply" -> BuilderSupplies.supply(rest) != null ? null : "no supply '" + rest + "'";
            //: any installed item, found by name (content/Found), handed over as a supply
            case "item" -> BuilderSupplies.found(id) ? null : "no installed item '" + rest + "'";
            default -> "unknown catalogue '" + prefix + "'";
        };
    }

    /** The id after the prefix ({@code power:flies} → {@code flies}). */
    public static String bare(String id) {
        return id == null || id.indexOf(':') < 0 ? id : id.substring(id.indexOf(':') + 1);
    }

    public static String prefix(String id) {
        return id == null || id.indexOf(':') < 0 ? "" : id.substring(0, id.indexOf(':'));
    }

    private static <E extends Enum<E>> boolean enumHas(Class<E> type, String word) {
        String w = word.toUpperCase(Locale.ROOT);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(w)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> abilities() {
        Set<String> out = new LinkedHashSet<>();
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            out.add(e.id());
        }
        return out;
    }

    private static volatile List<String> presetCache;

    /**
     * Every ready-made in the starter library, as {@code <kind>/<id>} ({@code species/pet_dragon},
     * {@code buildings/secret_treehouse}, {@code pictures/castle}, {@code events/meteor},
     * {@code items/teleport_wand_teleport}) — read from the index the library itself ships by.
     */
    public static List<String> presets() {
        List<String> c = presetCache;
        if (c != null) {
            return c;
        }
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = Catalogue.class.getClassLoader().getResourceAsStream("starter-library/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String[] parts = line.strip().split("/");
                    if (line.startsWith("#") || parts.length < 3) {
                        continue;
                    }
                    if (List.of("species", "buildings", "pictures", "events", "items").contains(parts[0])) {
                        out.add(parts[0] + "/" + parts[1]);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the starter library index", e);
        }
        // The Builder's own buildings, pictures and snack machines: builder-library/, the same
        // Builder-only rule.
        try (InputStream in = Catalogue.class.getClassLoader().getResourceAsStream("builder-library/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String[] parts = line.strip().split("/");
                    if (!line.startsWith("#") && parts.length >= 3
                            && List.of("buildings", "pictures", "items").contains(parts[0])) {
                        out.add(parts[0] + "/" + parts[1]);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the Builder library index", e);
        }
        // The Builder's own bodies: not in the starter library, so the wish worker never sees them.
        try (InputStream in = Catalogue.class.getClassLoader().getResourceAsStream("builder-bodies/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String l = line.strip();
                    if (!l.isEmpty() && !l.startsWith("#") && l.startsWith("species/")) {
                        out.add("bodies/" + l.substring("species/".length()));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the Builder bodies index", e);
        }
        presetCache = c = List.copyOf(new ArrayList<>(out));
        return c;
    }
}
