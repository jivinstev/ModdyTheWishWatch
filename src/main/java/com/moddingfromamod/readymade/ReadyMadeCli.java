package com.moddingfromamod.readymade;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>The library layer without a game</b>, for {@code tools/routing-stub-mcp.py}: the stub
 * answers {@code find_ready_made}, {@code describe_ready_made} and the nudges through THIS, so the
 * routing arm measures the shipped ranking, wording and Haiku call rather than a Python copy.
 *
 * <pre>
 *   dump     OUT.json                                  the shipped index (kinds b, c, d + starter buttons)
 *   find     INDEX.json[,WORLD.json] KINDS KIND NEED [CLI CWD WISH]   what find_ready_made answers
 *   describe INDEX.json[,WORLD.json] KINDS KIND ID     what describe_ready_made answers
 *   nudge    INDEX.json[,WORLD.json] KINDS CLI CWD WISH STEPS_JSON [describe] [HANDED_OVER_FILE]
 *                                                      one nudge, as JSON; the last file holds the
 *                                                      wish note the worker was handed (his FACTS)
 * </pre>
 *
 * {@code KINDS} is the arm's letters ({@code bcd}); {@code KIND} is the tool's own argument, {@code -}
 * for none. A second index file after a comma adds a fixture world's (a) entries.
 * Classpath: {@code build/classes/java/main}, {@code build/resources/main} and gson — nothing that
 * needs Minecraft.
 */
public final class ReadyMadeCli {

    private ReadyMadeCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: dump OUT | find IDX KINDS KIND NEED | describe IDX KINDS KIND ID"
                    + " | nudge IDX KINDS CLI CWD WISH STEPS_JSON");
            System.exit(2);
        }
        switch (args[0]) {
            case "dump" -> {
                List<ReadyMade> all = new ArrayList<>(ReadyMadeSources.shipped());
                all.addAll(ReadyMadeSources.starterButtons());
                Files.writeString(Path.of(args[1]), new ReadyMadeIndex(all).toJson().toString(),
                        StandardCharsets.UTF_8);
            }
            case "find" -> {
                // With CLI CWD WISH after the need, the shipped (Haiku) search; without, keywords.
                ReadyMadeIndex index = load(args[1]);
                Set<ReadyMade.Kind> enabled = ReadyMadeConfig.parse(args[2]);
                ReadyMadeNudge.Reply semantic = null;
                if (!arg(args, 5).isBlank()) {
                    semantic = ReadyMadeNudge.search(new ReadyMadeNudge.CliTransport(args[5],
                            ReadyMadeLive.model(), "low", Path.of(arg(args, 6))), index,
                            ReadyMadeAnswers.scope(enabled, dash(args[3])), arg(args, 7), arg(args, 4));
                }
                out(ReadyMadeAnswers.find(index, enabled, arg(args, 4), dash(args[3]), semantic));
            }
            case "describe" -> out(ReadyMadeAnswers.describe(load(args[1]),
                    ReadyMadeConfig.parse(args[2]), arg(args, 4), dash(args[3])));
            case "nudge" -> {
                ReadyMadeIndex index = load(args[1]);
                Set<ReadyMade.Kind> kinds = ReadyMadeConfig.parse(args[2]);
                List<String> steps = new ArrayList<>();
                String stepsJson = arg(args, 6);
                if (!stepsJson.isBlank()) {
                    for (JsonElement e : JsonParser.parseString(stepsJson).getAsJsonArray()) {
                        if (e.isJsonPrimitive()) {
                            steps.add(e.getAsString());
                        }
                    }
                }
                long t0 = System.currentTimeMillis();
                ReadyMadeNudge.Reply r = ReadyMadeNudge.ask(new ReadyMadeNudge.CliTransport(args[3],
                        ReadyMadeLive.model(), "low", Path.of(args[4])), index, kinds, arg(args, 5), steps,
                        ReadyMadeNudge.factsOf(arg(args, 8).isBlank() ? ""
                                : Files.readString(Path.of(arg(args, 8)), StandardCharsets.UTF_8)));
                JsonObject o = new JsonObject();
                JsonArray picks = new JsonArray();
                r.picks().forEach(p -> picks.add(ReadyMadeNudge.key(p)));
                o.add("picks", picks);
                o.addProperty("note", ReadyMadeNudge.note(r.picks(), arg(args, 7).equals("describe")));
                o.addProperty("latencyMs", System.currentTimeMillis() - t0);
                o.addProperty("apiMs", r.apiMs());
                o.addProperty("costUsd", r.costUsd());
                o.addProperty("why", r.note());
                o.addProperty("menu", ReadyMadeNudge.menu(index, kinds).size());
                o.addProperty("facts", ReadyMadeNudge.factsOf(arg(args, 8).isBlank() ? ""
                        : Files.readString(Path.of(arg(args, 8)), StandardCharsets.UTF_8)));
                out(o.toString());
            }
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    /** One index, or several joined with commas (the shipped one, then a fixture world's). */
    static ReadyMadeIndex load(String paths) throws Exception {
        List<ReadyMade> all = new ArrayList<>();
        for (String p : paths.split(",")) {
            if (p.isBlank()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(Files.readString(Path.of(p.strip()),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            all.addAll(ReadyMadeIndex.fromJson(o).all());
        }
        return new ReadyMadeIndex(all);
    }

    private static String arg(String[] a, int i) {
        return a.length > i ? a[i] : "";
    }

    private static String dash(String s) {
        return "-".equals(s) ? "" : s;
    }

    /** UTF-8 whatever the platform default: the menu is full of dashes and arrows. */
    private static void out(String s) throws java.io.IOException {
        System.out.write(s.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
