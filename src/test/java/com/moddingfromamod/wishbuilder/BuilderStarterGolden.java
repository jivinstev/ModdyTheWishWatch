package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** the saved-make shapes the golden fixture pins, and their lowering as one JSON object per starter. */
final class BuilderStarterGolden {

    static final String FIXTURE = "src/test/resources/wishbuilder/starter-makes-before-1991.json.gz";

    private BuilderStarterGolden() {
    }

    /** Every creature/ride ready-made a Crafter could pick. */
    static List<String> starters(BuilderSpec.Kind kind) {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).fromIds(kind);
    }

    /** A make as BuilderSpecStore kept it: the ready-made, no name, no powers, his size and mood. */
    static JsonObject saved(BuilderSpec.Kind kind, String preset) {
        JsonObject j = new JsonObject();
        j.addProperty("builder", 1);
        j.addProperty("kind", kind.id());
        j.addProperty("from", "preset:" + preset);
        j.addProperty("size", 2);
        j.addProperty("temper", "friendly");
        j.addProperty("count", 1);
        return j;
    }

    static JsonArray lowered(BuilderSpec spec) {
        JsonArray out = new JsonArray();
        for (BuilderLowering.Call c : BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED)) {
            JsonObject o = new JsonObject();
            o.addProperty("tool", c.tool());
            o.add("args", c.args());
            out.add(o);
        }
        return out;
    }

    static JsonObject all() {
        JsonObject out = new JsonObject();
        for (BuilderSpec.Kind kind : List.of(BuilderSpec.Kind.CREATURE, BuilderSpec.Kind.RIDE)) {
            for (String p : starters(kind)) {
                out.add(kind.id() + "/" + p, lowered(BuilderSpec.read(saved(kind, p))));
            }
        }
        return out;
    }
}
