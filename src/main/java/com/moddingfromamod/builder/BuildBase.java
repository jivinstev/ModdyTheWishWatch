package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Start from a building that already exists, and send only the difference.</b>
 *
 * <p>The buildings half of {@link com.moddingfromamod.runtime.SculptBase}, and written
 * deliberately to the same shape so the two classes fail the same way and are fixed once.
 *
 * <h2>Why</h2>
 *
 * <p>Measured on the buildings refinement ladder: asked to <i>"put a big glass dome on top of it"</i>
 * a worker that had not built the castle produced <b>a second building</b>, `glimmerhold_dome`, and
 * left the castle untouched. Four wishes later the child had four buildings where he asked for one
 * castle. It is the only thing the tool set allowed — {@code build_structure} replaces a plan
 * wholesale, and the agent is told a building is "22 shapes, 19661 blocks", which is not something
 * you can edit.
 *
 * <p>So a refinement becomes a diff: two shapes instead of twenty-four, against a base the tool
 * already has.
 *
 * <h2>The edit language</h2>
 *
 * <p>Three operations, one fewer than the creature version — a shape's block <i>is</i> its colour,
 * so replacing a named shape covers what {@code recolour} does there.
 *
 * <ul>
 *   <li><b>add</b> — any shape the base does not have a name for; appended, so it is placed LAST and
 *       therefore overwrites, which is how a dome sits on a keep and a doorway cuts a wall</li>
 *   <li><b>replace</b> — a shape whose {@code name} matches a base shape wins <b>in its original
 *       position</b>; that is what makes "make it much taller" a one-shape edit</li>
 *   <li><b>remove</b> — drop base shapes by name</li>
 * </ul>
 *
 * <h2>Position, not just membership</h2>
 *
 * <p>Order is load-bearing here in a way it is not for a creature: shapes are placed in sequence and
 * later ones overwrite earlier ones, so a replaced wall must go back <b>where it was</b>. Appending
 * it instead would put the wall on top of the doorway that was carved through it — the building
 * would come out subtly wrong, look fine from three sides, and nothing would report it. That is the
 * same class of silent geometry failure the {@code dx/dz is a corner} lesson is about.
 *
 * <p>Pure and Minecraft-free, so the merge is unit-tested without a game.
 */
public final class BuildBase {

    private BuildBase() {
    }

    /**
     * The base, edited.
     *
     * @param base   the shapes of the building being started from
     * @param added  the agent's own shapes; one whose name matches a base shape REPLACES it in place
     * @param remove base shape names to drop
     */
    public static List<BuildShape> merge(List<BuildShape> base, List<BuildShape> added,
                                         Set<String> remove) {
        List<BuildShape> safeBase = base == null ? List.of() : base;
        List<BuildShape> safeAdded = added == null ? List.of() : added;

        Map<String, BuildShape> replacements = new LinkedHashMap<>();
        for (BuildShape s : safeAdded) {
            String n = key(s.name());
            if (!n.isEmpty()) {
                replacements.put(n, s);
            }
        }
        Set<String> drop = new LinkedHashSet<>();
        if (remove != null) {
            for (String r : remove) {
                String n = key(r);
                if (!n.isEmpty()) {
                    drop.add(n);
                }
            }
        }

        List<BuildShape> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (BuildShape s : safeBase) {
            String n = key(s.name());
            if (!n.isEmpty() && drop.contains(n)) {
                continue;
            }
            BuildShape replacement = n.isEmpty() ? null : replacements.get(n);
            if (replacement != null) {
                out.add(replacement);          // IN PLACE — see the class note on ordering
                used.add(n);
            } else {
                out.add(s);
            }
        }
        // Everything the base had no name for goes on the end, in the order it was sent, so it is
        // placed last and overwrites — a dome on a keep, a gate through a wall.
        for (BuildShape s : safeAdded) {
            String n = key(s.name());
            if (n.isEmpty() || !used.contains(n)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Names match case- and space-insensitively, because a child's agent is not a compiler. */
    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
