package com.moddingfromamod.builder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * Scores an archived {@code tools/walk-advice-ab.sh} pass from the archive alone:
 * {@code WALKAB=<dir> ./gradlew test --tests '*WalkAdviceAbScore' --rerun}. Per run: the wish's own
 * seconds (its first action record to its last), how many {@code build_structure} calls, and whether the
 * FIRST and the LAST building can be walked into (main room, unreachable rooms by cause). Writes
 * {@code <dir>/score.md}. Does nothing without the variable, so it is never part of a gate.
 */
class WalkAdviceAbScore {

    @Test
    void score() throws Exception {
        String dir = System.getenv().getOrDefault("WALKAB", "");
        if (dir.isBlank()) {
            return;
        }
        Path root = Path.of(dir);
        StringBuilder md = new StringBuilder("| run | arm | wish | seconds | build calls | first: main walkable / unreachable "
                + "| last: main walkable / unreachable | advice fired |\n|---|---|---|---|---|---|---|---|\n");
        for (String line : Files.readAllLines(root.resolve("runs.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject run = JsonParser.parseString(line).getAsJsonObject();
            String id = run.get("run").getAsString();
            Path actions = root.resolve("actions").resolve(id + ".jsonl.gz");
            if (!Files.exists(actions)) {
                md.append("| ").append(id).append(" | | no actions archived |\n");
                continue;
            }
            String text;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(actions))) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Instant first = null;
            Instant last = null;
            Map<String, BuildPlan> built = new HashMap<>();
            List<BuildPlan> plans = new ArrayList<>();
            int fired = 0;
            for (String l : text.split("\n")) {
                if (l.isBlank()) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(l).getAsJsonObject();
                if (o.has("at")) {
                    Instant at = Instant.parse(o.get("at").getAsString());
                    first = first == null ? at : first;
                    last = at;
                }
                String result = o.has("result") ? o.get("result").toString() : "";
                if (result.contains("WALK-THROUGH")) {
                    fired++;
                }
                if (!"tool_call".equals(o.has("kind") ? o.get("kind").getAsString() : "")
                        || !o.has("tool") || !o.get("tool").getAsString().endsWith("build_structure")) {
                    continue;
                }
                JsonObject args;
                try {
                    args = JsonParser.parseString(o.get("args").getAsString()).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                BuildPlan plan = BuildPlan.fromJson(args);
                if (args.has("start_from") && built.containsKey(args.get("start_from").getAsString())) {
                    Set<String> remove = new LinkedHashSet<>();
                    if (args.has("remove") && args.get("remove").isJsonArray()) {
                        args.getAsJsonArray("remove").forEach(e -> remove.add(e.getAsString()));
                    }
                    plan = plan.withShapes(BuildBase.merge(built.get(args.get("start_from").getAsString()).shapes(),
                            plan.shapes(), remove));
                }
                built.put(plan.id(), plan);
                plans.add(plan);
            }
            long secs = first == null ? -1 : Duration.between(first, last).toSeconds();
            md.append("| ").append(id).append(" | ").append(run.get("arm").getAsString()).append(" | ")
                    .append(run.get("wish").getAsString()).append(" | ").append(secs).append(" | ").append(plans.size())
                    .append(" | ").append(plans.isEmpty() ? "-" : verdict(plans.get(0))).append(" | ")
                    .append(plans.isEmpty() ? "-" : verdict(plans.get(plans.size() - 1))).append(" | ").append(fired)
                    .append(" |\n");
        }
        Files.writeString(root.resolve("score.md"), md.toString());
        System.out.println(md);
    }

    private static String verdict(BuildPlan p) {
        WalkThrough.Report r = WalkThrough.of(p);
        return (r.mainWalkable() ? "yes" : "NO") + " / " + r.unreachable().stream().map(x -> x.cause().name()).toList();
    }
}
