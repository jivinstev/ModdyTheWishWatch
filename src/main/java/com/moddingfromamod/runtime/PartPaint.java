package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>"Make his wings purple" on the atom rig</b> — which parts a child's word names,
 * and the creature with those parts recoloured.
 *
 * <p>The rig draws every box in its own part's colour ({@code AtomLibrary}); it has never read a
 * texture. {@code paint_creature} used to paint {@code texture.png} ({@code TexturePainter}) and
 * say <i>"It changed on the ones already in the world"</i> over a creature that had not changed at
 * all — since the rig became the only renderer, a paint tool that did nothing. So a paint is now
 * what the rig can show: a whole PART, one flat colour. A band across a part ("a belt") is pixels
 * on a texture, and the rig has no texture; it is refused by name, with the parts it could paint.
 *
 * <p>Matching is the same rule {@link UvRegions#matchBones} uses for bones, because a child names
 * body parts and an agent guesses names: exact, else prefix/suffix, else substring — "wing" paints
 * {@code wingL} and {@code wingR}. Pure: no Minecraft.
 */
public final class PartPaint {

    private PartPaint() {
    }

    /** The painted creature, or why not. {@code painted} names the parts that changed. */
    public record Result(List<CreatureSculpt.Part> parts, List<String> painted, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** The indices of the parts {@code query} names, by the loose rule above; empty if none. */
    public static List<Integer> match(List<CreatureSculpt.Part> parts, String query) {
        List<Integer> exact = new ArrayList<>();
        List<Integer> edge = new ArrayList<>();
        List<Integer> loose = new ArrayList<>();
        if (parts == null || query == null || query.isBlank()) {
            return exact;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < parts.size(); i++) {
            String name = parts.get(i).name() == null ? ""
                    : parts.get(i).name().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals(q)) {
                exact.add(i);
            } else if (name.startsWith(q) || name.endsWith(q)) {
                edge.add(i);
            } else if (name.contains(q)) {
                loose.add(i);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        return edge.isEmpty() ? loose : edge;
    }

    public static Result paint(List<CreatureSculpt.Part> parts, String query, int rgb) {
        if (parts == null || parts.isEmpty()) {
            return new Result(parts, List.of(), "this creature has no parts to paint");
        }
        List<Integer> hit = match(parts, query);
        if (hit.isEmpty()) {
            String band = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            int colon = band.indexOf(':');
            if (colon >= 0) {
                band = band.substring(0, colon);
            }
            String names = String.join(", ", names(parts));
            if (UvRegions.BANDS.containsKey(band)) {
                return new Result(parts, List.of(), "A creature is drawn from whole parts, one "
                        + "colour each, so a " + band + " across a part can't be painted on its "
                        + "own. Paint a whole part (" + names + "), or add a " + band + " part to "
                        + "it with sculpt_creature (start_from its id).");
            }
            return new Result(parts, List.of(), "It has no part called '" + query + "'. Its parts "
                    + "are: " + names);
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(parts);
        List<String> painted = new ArrayList<>();
        for (int i : hit) {
            CreatureSculpt.Part p = parts.get(i);
            out.set(i, new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    rgb & 0xFFFFFF, p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
            painted.add(p.name());
        }
        return new Result(List.copyOf(out), List.copyOf(painted), null);
    }

    /** Every part's name, once each, in order. */
    public static List<String> names(List<CreatureSculpt.Part> parts) {
        List<String> out = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            if (p.name() != null && !p.name().isEmpty() && !out.contains(p.name())) {
                out.add(p.name());
            }
        }
        return out;
    }
}
