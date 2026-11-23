package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildGrounding;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Which sizes of a ready-made building WORK</b> (an interim answer). A building is
 * offered at a size only when that size was measured and passed three checks against size 2, its own size: nothing
 * floats, it keeps its doors, beds and chests (a row of them stays the row), and a player walks into at least as many
 * rooms. The measurement is {@code building-sizes.json}, written from the shipped presets by
 * {@code BuildScalingCorpusTest} in Gate A, which fails when the file and the presets disagree.
 *
 * <p>Floating is judged as the build will really be placed: with {@link BuildGrounding#enabled() the grounding fix} if
 * it is on, without it if not. Pure: no Minecraft.
 */
public final class BuilderSizes {

    private BuilderSizes() {
    }

    public static final String RESOURCE = "/builder-library/building-sizes.json";

    /**
     * the WORKING builds, offered at their own size only. Their layout is what works (water within four blocks of
     * every farmland, a tunnel long enough that no daylight reaches the cave), and scaling a layout stretches exactly
     * those distances, so no other size is offered whatever the measurement says.
     */
    public static final java.util.Set<String> FIXED = java.util.Set.of("farm_plot", "animal_pen", "tree_nursery", "monster_cave");

    /** The dialog's line for a working build's one size. */
    public static final String FIXED_LINE = "One size: the size it works at";

    /** One size of one building, as measured. {@code why} is what went wrong, in a child's words, or empty. */
    public record Cell(int size, boolean walks, boolean keepsItems, int floats, int floatsFixed, String walkWhy, String itemsWhy) {
        public boolean works(boolean fixOn) {
            return walks && keepsItems && (fixOn ? floatsFixed : floats) == 0;
        }

        /** Why it is not offered, in a child's words (a tune dialog line is 60 characters at most). */
        public String why(boolean fixOn) {
            if (!walks) {
                return walkWhy;
            }
            if (!keepsItems) {
                return itemsWhy;
            }
            return (fixOn ? floatsFixed : floats) > 0 ? "parts of it would float" : "";
        }
    }

    /** The same measurement with {@code partscale=on} (BuildScale): the sizes that scaler makes work. */
    public static final String RESOURCE_PARTS = "/builder-library/building-sizes-parts.json";

    private static Map<String, List<Cell>> table;
    private static Map<String, List<Cell>> tableParts;

    static Map<String, List<Cell>> table() {
        return table(com.moddingfromamod.builder.BuildScale.enabled());
    }

    static synchronized Map<String, List<Cell>> table(boolean byParts) {
        if (byParts) {
            if (tableParts == null) {
                tableParts = read(BuilderSizes.class.getResourceAsStream(RESOURCE_PARTS));
            }
            return tableParts;
        }
        if (table == null) {
            table = read(BuilderSizes.class.getResourceAsStream(RESOURCE));
        }
        return table;
    }

    static Map<String, List<Cell>> read(InputStream in) {
        Map<String, List<Cell>> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            for (String type : o.keySet()) {
                List<Cell> cells = new ArrayList<>();
                for (var e : o.getAsJsonArray(type)) {
                    JsonObject c = e.getAsJsonObject();
                    cells.add(new Cell(c.get("size").getAsInt(), c.get("walks").getAsBoolean(), c.get("keepsItems").getAsBoolean(),
                            c.get("floats").getAsInt(), c.get("floatsFixed").getAsInt(),
                            c.has("walkWhy") ? c.get("walkWhy").getAsString() : "",
                            c.has("itemsWhy") ? c.get("itemsWhy").getAsString() : ""));
                }
                out.put(type, cells);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
        return out;
    }

    /**
     * The sizes this building is offered at, smallest first: the run of working sizes that holds size 2 (a slider is
     * a run). A type that was never measured is offered every size, as — {@code BuildScalingCorpusTest}
     * fails a shipped type with no row.
     */
    public static List<Integer> offered(String type) {
        return offered(type, BuildGrounding.enabled());
    }

    static List<Integer> offered(String type, boolean fixOn) {
        if (FIXED.contains(type)) {
            return List.of(2);
        }
        List<Cell> cells = table().get(type);
        if (cells == null) {
            return List.of(1, 2, 3, 4);
        }
        int lo = 2;
        int hi = 2;
        while (lo > 1 && cell(cells, lo - 1).works(fixOn)) {
            lo--;
        }
        while (hi < 4 && cell(cells, hi + 1).works(fixOn)) {
            hi++;
        }
        List<Integer> out = new ArrayList<>();
        for (int s = lo; s <= hi; s++) {
            out.add(s);
        }
        return out;
    }

    private static Cell cell(List<Cell> cells, int size) {
        return cells.stream().filter(c -> c.size() == size).findFirst().orElseThrow();
    }

    /** {@code size} moved into what this building is offered at (an old spec, a suggestion, a typed command). */
    public static int clamp(String type, int size) {
        List<Integer> o = offered(type);
        return Math.max(o.get(0), Math.min(o.get(o.size() - 1), size));
    }

    /**
     * The tune dialog's lines for the sizes it does not offer, at most two (below, above): e.g. "Size 3–4 aren't ready:
     * you couldn't walk in". Empty when every size is offered.
     */
    public static List<String> lines(String type) {
        return lines(type, BuildGrounding.enabled());
    }

    static List<String> lines(String type, boolean fixOn) {
        if (FIXED.contains(type)) {
            return List.of(FIXED_LINE);
        }
        List<Cell> cells = table().get(type);
        List<Integer> o = offered(type, fixOn);
        List<String> out = new ArrayList<>();
        if (cells == null) {
            return out;
        }
        int lo = o.get(0);
        int hi = o.get(o.size() - 1);
        if (lo > 1) {
            out.add(line(1, lo - 1, cell(cells, lo - 1).why(fixOn)));
        }
        if (hi < 4) {
            out.add(line(hi + 1, 4, cell(cells, hi + 1).why(fixOn)));
        }
        return out;
    }

    private static String line(int from, int to, String why) {
        String sizes = from == to ? "Size " + from + " isn't" : "Size " + from + "–" + to + " aren't";
        return sizes + " ready: " + why;
    }
}
