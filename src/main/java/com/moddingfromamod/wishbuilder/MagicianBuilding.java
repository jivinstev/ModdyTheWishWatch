package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>The Builder's changes on a building the MAGICIAN made</b> (slice 2). Of the Builder's
 * choices for a building, two work on any building, so they work on a hand-made one too: its COLOURS (the Builder's own
 * {@link Schemes#reblock} over its blocks: biggest to the scheme's main block, then accent and trim; glass, light,
 * leaves, logs, doors and the ground never) and what goes OUTSIDE it (a garden, lamp posts, a path, a pool, a flag:
 * {@link BuilderBuildings#shapes}, laid round whatever shapes it has). Its SIZE is never one of them: a hand-made plan
 * breaks when it is scaled (docs/MODIFY_LANGUAGE.md §9), so the page shows "Size ✪" blocked, with why.
 *
 * <p>Only what changed is sent: {@code build_structure} under its own id, which rebuilds it where it stands, with its own
 * shapes (re-blocked) and the new outside; its marked spots are kept. Pure: no Minecraft.
 */
public final class MagicianBuilding {

    private MagicianBuilding() {
    }

    /** Its size, shown and never changed: a blocked tile on the outside page. */
    public static final String SIZE_ID = "magician:size";
    public static final String SIZE_LABEL = "Size " + MagicianCreature.MARK;
    public static final String SIZE_LINE = "The Magician's, it stays";
    public static final String SIZE_SAY = "The Magician built this by hand, so the Builder can't make it bigger or smaller.";

    /** The tune dialog's note: two lines of at most 60 characters, no ✪. */
    public static final List<String> DIALOG_LINES = List.of("The Magician made this: the Builder changes some of it.",
            "Its size and the rest stay as the Magician made it.");

    /** A Magician building: its id, name, and its stored plan ({@code BuildPlan.toJson}). */
    public record Thing(String id, String name, JsonObject plan) {
    }

    /**
     * The calls for {@code scheme} ("" / "as_is": as it is) and the {@code outside} pieces added (ids from
     * {@link BuilderBuildings#OUTSIDE}, "nothing" ignored). Empty when nothing changes.
     */
    public static List<BuilderLowering.Call> calls(Thing t, String scheme, List<String> outside) {
        Schemes.Scheme sc = scheme == null || scheme.isEmpty() || scheme.equals("as_is") ? null : Schemes.byId(scheme);
        if (scheme != null && !scheme.isEmpty() && !scheme.equals("as_is") && sc == null) {
            throw new IllegalArgumentException("no colours called '" + scheme + "'");
        }
        List<String> add = new ArrayList<>();
        for (String o : outside == null ? List.<String>of() : outside) {
            String bare = Catalogue.bare(o);
            if (BuilderBuildings.outside(bare) == null) {
                throw new IllegalArgumentException("nothing outside called '" + o + "'");
            }
            if (!bare.equals(BuilderBuildings.NOTHING) && !add.contains(bare)) {
                add.add(bare);
            }
        }
        JsonArray shapes = t.plan().has("shapes") ? t.plan().getAsJsonArray("shapes").deepCopy() : new JsonArray();
        Map<String, String> reblock = sc == null ? Map.of() : Schemes.reblock(shapes, sc);
        boolean changed = !add.isEmpty();
        for (JsonElement e : shapes) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("block") && reblock.containsKey(s.get("block").getAsString())
                    && !reblock.get(s.get("block").getAsString()).equals(s.get("block").getAsString())) {
                s.addProperty("block", reblock.get(s.get("block").getAsString()));
                changed = true;
            }
        }
        if (!changed) {
            return List.of();
        }
        shapes.addAll(BuilderBuildings.shapes(BuilderBuildings.join(add), shapes));
        JsonObject a = new JsonObject();
        a.addProperty("id", t.id());
        a.addProperty("name", t.name().isEmpty() ? t.id() : t.name());
        a.add("shapes", shapes);
        if (t.plan().has("spots")) {
            a.add("spots", t.plan().get("spots").deepCopy());
        }
        a.addProperty("note", "Builder change on a Magician building: only what the Builder can choose"
                + (sc == null ? "" : ", in " + sc.label() + " colours")
                + (add.isEmpty() ? "" : ", with " + BuilderBuildings.says(BuilderBuildings.join(add))) + ".");
        List<BuilderLowering.Call> out = new ArrayList<>();
        JsonObject plan = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add("change " + (t.name().isEmpty() ? t.id() : t.name()));
        plan.add("steps", steps);
        plan.addProperty("about", t.id());
        plan.addProperty("kind", "building");
        out.add(new BuilderLowering.Call("plan_wish", plan));
        out.add(new BuilderLowering.Call("build_structure", a));
        JsonObject fin = new JsonObject();
        fin.addProperty("label", t.name().isEmpty() ? t.id() : t.name());
        out.add(new BuilderLowering.Call("finish_building", fin));
        return out;
    }
}
