package com.moddingfromamod.vanilla;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;
import com.moddingfromamod.runtime.SculptOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>A diagnostic, not a gate</b>: the boxes of a sculpt as the rig draws them through one
 * walk cycle, written as JSON for {@code tools/rig-walk-preview.py} to draw from the side, the
 * front and above. It is how a gait is LOOKED at in seconds, before paying for a client film.
 *
 * <pre>
 * RIG_DUMP_SCULPT=a.sculpt.json,b.sculpt.json RIG_DUMP_OUT=/tmp/walk \
 *     ./gradlew test --tests '*RigWalkDumpTest' --offline
 * python3 tools/rig-walk-preview.py /tmp/walk
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RIG_DUMP_SCULPT", matches = ".+")
class RigWalkDumpTest {

    static final int FRAMES = 24;

    @Test
    void dump() throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("RIG_DUMP_OUT", "/tmp/rig-walk-dump"));
        Files.createDirectories(out);
        for (String file : System.getenv("RIG_DUMP_SCULPT").split(",")) {
            Path src = Path.of(file.trim());
            JsonObject d = JsonParser.parseString(Files.readString(src, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(d.getAsJsonArray("parts"));
            if (d.has("kits") && d.get("kits").isJsonArray()) {
                List<CreatureSculpt.Part> grown = new ArrayList<>(parts);
                grown.addAll(SculptKits.expand(d.getAsJsonArray("kits"), parts));
                parts = grown;
            }
            List<CreatureSculpt.Part> rig = RigFit.intoRigSpace(SculptOrientation.faceForward(parts));
            RigLocomotion.Plan plan = RigLocomotion.plan(RigAnatomy.of(rig));
            JsonObject doc = new JsonObject();
            JsonArray ps = new JsonArray();
            for (CreatureSculpt.Part p : rig) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name());
                o.addProperty("colour", p.colour());
                ps.add(o);
            }
            doc.add("parts", ps);
            JsonArray legs = new JsonArray();
            Map<String, Float> off = RigLocomotion.strideOffsets(plan.anatomy());
            for (RigAnatomy.Leg l : plan.anatomy().legs()) {
                JsonObject o = new JsonObject();
                o.addProperty("bone", l.bone());
                o.addProperty("front", l.front());
                o.addProperty("sprawl", l.sprawl());
                o.addProperty("offset", off.get(l.bone()));
                legs.add(o);
            }
            doc.add("legs", legs);
            doc.addProperty("front", plan.anatomy().front());
            JsonArray frames = new JsonArray();
            for (int f = 0; f < FRAMES; f++) {
                float phase = f / (float) FRAMES;
                RigLocomotion.Out o = RigLocomotion.pose(plan, Map.of(), RigLocomotion.Weights.NONE,
                        0f, new RigLocomotion.Stride(phase, 1f));
                Map<String, float[][]> c = RigLocomotion.corners(rig, o.pose(), o.root());
                JsonObject fr = new JsonObject();
                for (CreatureSculpt.Part p : rig) {
                    JsonArray cs = new JsonArray();
                    for (float[] v : c.get(p.name())) {
                        JsonArray xyz = new JsonArray();
                        for (float x : v) {
                            xyz.add(x);
                        }
                        cs.add(xyz);
                    }
                    fr.add(p.name(), cs);
                }
                frames.add(fr);
            }
            doc.add("frames", frames);
            String name = src.getFileName().toString().replace(".sculpt.json", "").replace(".json", "");
            Files.writeString(out.resolve(name + ".walk.json"), doc.toString(), StandardCharsets.UTF_8);
        }
    }
}
