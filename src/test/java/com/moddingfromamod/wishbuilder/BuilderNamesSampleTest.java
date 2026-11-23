package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Writes build/builder-names/sample.jsonl: random builds and the names Moddy gave them. */
class BuilderNamesSampleTest {

    @Test
    void writeTheSample() throws Exception {
        Random r = new Random(Long.getLong("namesample.seed", 2160L));
        int want = Integer.getInteger("namesample.n", 240);
        List<String> lines = new ArrayList<>();
        int tries = 0;
        while (lines.size() < want && tries++ < want * 20) {
            BuilderRandomWalk.Walk w = BuilderRandomWalk.walk(r, BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            if (w == null || w.kind() == BuilderSpec.Kind.RULE) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("kind", w.kind().id());
            JsonArray p = new JsonArray();
            w.picked().forEach(p::add);
            o.add("picked", p);
            JsonObject t = new JsonObject();
            w.tuned().forEach(t::addProperty);
            o.add("tuned", t);
            o.add("spec", w.spec().toJson());
            o.addProperty("name", w.spec().name());
            o.addProperty("shown", BuilderLowering.label(w.spec()));   // what he sees it called (an event: its title)
            o.addProperty("old", w.oldDefault());
            lines.add(o.toString());
        }
        Path out = Path.of("build/builder-names/sample.jsonl");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }
}
