package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <b>What a building clears out of the world when it goes up</b>. Pure, no Minecraft: the world is
 * asked one cell at a time through {@link World}, so every case is a millisecond unit test
 * ({@code BuildClearingTest}); {@link BuildGround} reads the real world and carries the result out as part of
 * the build, first in its job, recorded like a footing so taking the building down gives the ground back.
 *
 * <p><b>A hill is cut into as if the ground were flat.</b> {@link BuildFooting} fills the DIPS under a
 * building's base, but nothing cut into the uphill side, so on a slope the uphill half of a building stood in
 * the hill: the mansion's uphill rooms were full of earth and the bunker's hatch was under two blocks of it
 * (found 's walk on a hill). On flat ground every cell above the floor that the plan leaves
 * empty IS empty, so inside the building's footprint — the columns designed to touch the ground,
 * {@link BuildFooting#footprint}, the enclosed ones included — the natural ground at and above the floor
 * ({@code y = 0}) is cleared, and the grass, flowers and trees that stood on that ground with it, so nothing
 * is left hanging. Only natural blocks: a block a child placed is never cut ({@link Kind#OTHER}), and neither
 * is water or lava.
 *
 * <p>At most {@link #CUT_CAP} blocks above the floor. A hill that rises higher still inside the footprint is
 * left above that height and the agent is told how many columns.
 */
public final class BuildClearing {

    /** How far above its floor a building cuts into a hill. */
    public static final int CUT_CAP = 24;

    private BuildClearing() {
    }

    /** What one cell of the world is, to this rule. */
    public enum Kind {
        /** Nothing there. */
        AIR,
        /** Natural ground: dirt, grass block, stone, sand, gravel, snow block, clay, ore. */
        GROUND,
        /** What grows on the ground: logs, leaves, grass, flowers, vines, saplings, a snow layer. */
        PLANT,
        /** Anything else — a block somebody placed, water, lava. Never cleared. */
        OTHER
    }

    /** The world, one cell at a time, in absolute coordinates. */
    public interface World {
        Kind at(int x, int y, int z);
    }

    /**
     * What was cleared.
     *
     * @param cells   absolute {@code {x, y, z}} to set to air, TOP FIRST, so nothing is left standing on a
     *                cell already emptied
     * @param columns footprint columns the hill rose into
     * @param over    columns where the hill still rises above {@link #CUT_CAP}
     */
    public record Cut(List<int[]> cells, int columns, int over) {

        public static final Cut NONE = new Cut(List.of(), 0, 0);

        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    /**
     * <b>Cut a hill</b> out of a building's footprint.
     *
     * @param footprint  the columns designed to touch the ground, {@code {x, z, base}} relative to the anchor
     *                   ({@link BuildFooting#footprint})
     * @param planCells  every cell the plan itself places (air shapes included), relative, as
     *                   {@link ComposedPlan#key} — the plan's own block goes there, so it is never cut
     */
    public static Cut hillside(World w, int ax, int ay, int az, List<int[]> footprint, Set<Long> planCells) {
        if (footprint == null || footprint.isEmpty()) {
            return Cut.NONE;
        }
        List<int[]> cells = new ArrayList<>();
        int columns = 0;
        int over = 0;
        for (int[] col : footprint) {
            int x = col[0];
            int z = col[1];
            int top = Integer.MIN_VALUE;
            for (int y = CUT_CAP; y >= 0; y--) {
                if (w.at(ax + x, ay + y, az + z) == Kind.GROUND) {
                    top = y;
                    break;
                }
            }
            if (top == Integer.MIN_VALUE) {
                continue;                       // flat or downhill here: nothing above the floor to cut
            }
            columns++;
            if (top == CUT_CAP && w.at(ax + x, ay + CUT_CAP + 1, az + z) == Kind.GROUND) {
                over++;
            }
            List<int[]> column = new ArrayList<>();
            // what stood ON the ground being cut — grass, a flower, a tree's trunk — or it would hang in the air
            for (int y = top + 1; y <= CUT_CAP; y++) {
                Kind k = w.at(ax + x, ay + y, az + z);
                if (k != Kind.PLANT) {
                    break;
                }
                if (!planCells.contains(ComposedPlan.key(x, y, z))) {
                    column.add(new int[]{ax + x, ay + y, az + z});
                }
            }
            for (int y = top; y >= 0; y--) {
                Kind k = w.at(ax + x, ay + y, az + z);
                if ((k == Kind.GROUND || k == Kind.PLANT) && !planCells.contains(ComposedPlan.key(x, y, z))) {
                    column.add(new int[]{ax + x, ay + y, az + z});
                }
            }
            // top first within the column: highest y first
            column.sort((a, b) -> Integer.compare(b[1], a[1]));
            cells.addAll(column);
        }
        if (cells.isEmpty()) {
            return new Cut(List.of(), columns, over);
        }
        // and top first across columns too, so a whole layer goes before the one under it
        cells.sort((a, b) -> Integer.compare(b[1], a[1]));
        return new Cut(Collections.unmodifiableList(cells), columns, over);
    }

    /**
     * <b>Clear what grows in its rooms</b>. A building put up in a forest kept the trees standing inside
     * it — trunks in its rooms, crowns across its stairs (the wizard tower: 31 of 31 landings reachable on flat
     * ground, 5 of 28 in the forest). Decided (owner delegated, 2026-11-10): only NATURAL blocks
     * ({@link Kind#PLANT}: logs, leaves, grass, flowers, vines…) and only in the cells the walk-through checker
     * counts as a room's interior ({@link WalkThrough#roomSpace}) — a room has a roof, so an open courtyard,
     * a yard or a roof terrace keeps its trees.
     *
     * @param roomCells the rooms' air, relative, as {@link ComposedPlan#key} ({@link WalkThrough#roomSpace})
     * @param planCells every cell the plan itself places (air shapes included): its own block goes there
     * @param already   absolute cells another clearing already took (the hillside cut), as {@code x,y,z} keys
     * @return the cells to set to air, top first, and how many
     */
    public static Cut interiors(World w, int ax, int ay, int az, Set<Long> roomCells, Set<Long> planCells,
                                Set<String> already) {
        if (roomCells == null || roomCells.isEmpty()) {
            return Cut.NONE;
        }
        List<int[]> cells = new ArrayList<>();
        for (long k : roomCells) {
            if (planCells.contains(k)) {
                continue;
            }
            int x = ax + ComposedPlan.kx(k);
            int y = ay + ComposedPlan.ky(k);
            int z = az + ComposedPlan.kz(k);
            if (already.contains(x + "," + y + "," + z)) {
                continue;
            }
            if (w.at(x, y, z) == Kind.PLANT) {
                cells.add(new int[]{x, y, z});
            }
        }
        if (cells.isEmpty()) {
            return Cut.NONE;
        }
        cells.sort((a, b) -> Integer.compare(b[1], a[1]));
        return new Cut(Collections.unmodifiableList(cells), 0, 0);
    }

    /** One sentence for the agent about {@link #interiors}, or empty when nothing was cleared. */
    public static String roomsForTheAgent(Cut cleared) {
        if (cleared == null || cleared.isEmpty()) {
            return "";
        }
        return cleared.cells().size() + " natural block(s) — tree trunks, leaves, grass, flowers — stood inside "
                + "its rooms, so I cleared them (only inside rooms with a roof: an open courtyard or yard keeps "
                + "its trees, and nothing anybody built was touched)";
    }

    /** One sentence for the agent's tool result, or empty when nothing was cut. */
    public static String forTheAgent(Cut cut) {
        if (cut == null || cut.isEmpty()) {
            return "";
        }
        String s = "the ground rose into " + cut.columns() + " column(s) of it above its floor, so I cut "
                + cut.cells().size() + " block(s) of hill out of the inside of the building, as if it stood on "
                + "flat ground";
        if (cut.over() > 0) {
            s += " (⚠ in " + cut.over() + " column(s) the hill rises more than " + CUT_CAP
                    + " blocks above the floor; above that it was left)";
        }
        return s;
    }
}
