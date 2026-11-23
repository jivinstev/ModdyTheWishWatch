package com.moddingfromamod.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pure half of a one-shot grade through MUSE CODE ({@code muse exec}), the Java twin of
 * {@code tools/muse_grade.py}: which model a spec names, the argv, the strict schema Meta's API
 * demands, reading the run's JSONL events back, and pricing it. {@link MuseExec} runs the process.
 *
 * <p>Why Muse Code and not a direct API call: Muse runs through Muse Code in production, and only Muse
 * Code can run on a server's Muse Code subscription. The evidence that
 * Ember on these flags is acceptable for a job is docs/EMBER_GRADERS.md.
 */
public final class MuseGrade {

    /** A model spec naming a Muse model: {@code muse:<id>}, e.g. {@code muse:muse-ember-1.0}. */
    public static final String PREFIX = "muse:";

    /** The model graders default to where Ember is the accepted default (docs/EMBER_GRADERS.md). */
    public static final String EMBER = PREFIX + "muse-ember-1.0";

    /**
     * The invocation, after {@code exec --api-key-stdin --json --model ID}. Each flag is the analogue
     * of one the Claude-CLI graders pass: the grader's own system prompt replaces Muse Code's
     * ({@code -c run.system_prompt}, added by {@link #argv}), no shell/writes/web/user input
     * ({@code --tools ""}), nothing of this machine's ({@code --no-foreign-personal-context}),
     * three model steps ({@code --max-turns 3}). Contributor mode is OFF: list price, and a grader
     * reads every wish a child types (a maintainer's decision to change).
     */
    public static final List<String> FLAGS = List.of(
            "--preset", "empty", "--no-foreign-personal-context", "--disable-reminders",
            "--disable-web-tools", "--disable-request-user-input", "--disable-shell", "--disable-write",
            "--no-session-log", "--contributor-mode", "off", "--max-model-steps", "3");

    /**
     * <b>The reference researcher's invocation</b> (smoke): the graders' lockdown with exactly the two web
     * tools left. {@code --preset empty} removes every tool, web included, so it is replaced by
     * {@code -c run.toolset=["web_search","web_fetch"]} (measured on Muse Code 1.4.24: the session's
     * {@code active_tools} were exactly those two, corpus/researcher-arms/2026-11-19-2566/) — the same boundary as the
     * Claude researcher's {@code --tools WebSearch,WebFetch}. No shell, no writes, no user input, nothing of this
     * machine's, no MCP server and so no wish tool; contributor mode off. The step cap bounds a runaway loop well past
     * the measured 6–7 web calls a lookup.
     */
    public static final List<String> RESEARCH_FLAGS = List.of(
            "-c", "run.toolset=[\"web_search\",\"web_fetch\"]", "--no-foreign-personal-context",
            "--disable-reminders", "--disable-request-user-input", "--disable-shell", "--disable-write",
            "--no-session-log", "--contributor-mode", "off", "--max-model-steps", "30");

    /** What a grader asks of Muse Code when it has no system prompt of its own (a plain-text ask). */
    public static final String PLAIN_SYSTEM_PROMPT = "You are a helpful assistant.";

    /** $ per 1M tokens: input, output, cached input. Mirrors tools/wish-cost.py's rows. */
    static final Map<String, double[]> RATES = Map.of(
            "muse-ember-1.0", new double[] {0.10, 0.50, 0.004},
            "muse-spark-1.2-contributor", new double[] {0.10, 0.20, 0.002},
            "muse-spark-1.2", new double[] {1.25, 4.25, 0.15});

    private MuseGrade() {
    }

    public static boolean isMuse(String spec) {
        return spec != null && spec.strip().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** {@code muse:muse-ember-1.0} → {@code muse-ember-1.0}. */
    public static String modelId(String spec) {
        return spec.strip().substring(PREFIX.length());
    }

    /** The whole argv; {@code schemaPath} null means a free-text answer. */
    public static List<String> argv(String bin, String modelId, String systemPrompt, String effort,
                                    String schemaPath, boolean keyOnStdin, String userText) {
        return argv(bin, modelId, systemPrompt, effort, schemaPath, keyOnStdin, List.of(), userText);
    }

    /**
     * With pictures attached to the FIRST message ({@code --image}, one per file; an earlier issue's describer): the model
     * sees them in its one turn, so no file tool is needed and none is enabled ({@link #FLAGS}).
     */
    public static List<String> argv(String bin, String modelId, String systemPrompt, String effort,
                                    String schemaPath, boolean keyOnStdin, List<String> images, String userText) {
        return argv(bin, modelId, systemPrompt, effort, schemaPath, keyOnStdin, images, userText, FLAGS);
    }

    /** With the lockdown flags named: {@link #FLAGS} for a grade, {@link #RESEARCH_FLAGS} for the researcher. */
    public static List<String> argv(String bin, String modelId, String systemPrompt, String effort,
                                    String schemaPath, boolean keyOnStdin, List<String> images, String userText,
                                    List<String> flags) {
        java.util.ArrayList<String> a = new java.util.ArrayList<>(List.of(bin, "exec"));
        if (keyOnStdin) {
            a.add("--api-key-stdin");
        }
        a.addAll(List.of("--json", "--model", modelId));
        a.addAll(flags);
        a.add("-c");
        a.add("run.system_prompt=" + new JsonPrimitive(
                systemPrompt == null || systemPrompt.isBlank() ? PLAIN_SYSTEM_PROMPT : systemPrompt));
        if (schemaPath != null) {
            a.add("--output-schema");
            a.add(schemaPath);
        }
        if (effort != null && !effort.isBlank() && !"none".equalsIgnoreCase(effort)) {
            a.add("--reasoning-effort");
            a.add(effort.strip());
        }
        for (String img : images == null ? List.<String>of() : images) {
            a.add("--image");
            a.add(img);
        }
        a.add(userText);
        return a;
    }

    /**
     * Meta's API takes only a STRICT schema: every property of every object in {@code required}
     * (measured: {@code 400 'required' is required … Missing 'redirect'}). An optional property is
     * sent as required-and-nullable, and {@link #dropNulls} removes the null again, so the model is
     * asked the same fields with the same descriptions and limits.
     */
    public static JsonObject strictSchema(JsonObject schema) {
        JsonObject s = schema.deepCopy();
        strict(s);
        return s;
    }

    private static void strict(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("properties") && o.get("properties").isJsonObject()) {
                JsonObject props = o.getAsJsonObject("properties");
                Set<String> req = new HashSet<>();
                JsonArray reqArr = o.has("required") && o.get("required").isJsonArray()
                        ? o.getAsJsonArray("required") : new JsonArray();
                reqArr.forEach(x -> req.add(x.getAsString()));
                for (String name : props.keySet()) {
                    if (!req.contains(name) && props.get(name).isJsonObject()) {
                        JsonObject sub = props.getAsJsonObject(name);
                        JsonElement t = sub.get("type");
                        if (t != null && t.isJsonPrimitive()) {
                            JsonArray both = new JsonArray();
                            both.add(t.getAsString());
                            both.add("null");
                            sub.add("type", both);
                        } else if (t != null && t.isJsonArray() && !t.getAsJsonArray().contains(new JsonPrimitive("null"))) {
                            t.getAsJsonArray().add("null");
                        }
                        reqArr.add(name);
                    }
                }
                o.add("required", reqArr);
            }
            for (String k : o.keySet()) {
                strict(o.get(k));
            }
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(MuseGrade::strict);
        }
    }

    /** Top-level keys that were OPTIONAL in the shipped schema and came back null are removed. */
    public static JsonObject dropNulls(JsonObject verdict, JsonObject originalSchema) {
        Set<String> req = new HashSet<>();
        if (originalSchema.has("required") && originalSchema.get("required").isJsonArray()) {
            originalSchema.getAsJsonArray("required").forEach(x -> req.add(x.getAsString()));
        }
        JsonObject out = new JsonObject();
        for (String k : verdict.keySet()) {
            JsonElement v = verdict.get(k);
            if (!(v == null || v.isJsonNull()) || req.contains(k)) {
                out.add(k, v);
            }
        }
        return out;
    }

    /** What a {@code muse exec --json} run said: its final text, its token use, why it failed. */
    public record Run(String finalText, long inputTokens, long cachedTokens, long outputTokens,
                      String failure) {
    }

    /** Reads the JSONL events. Total: an unreadable line is skipped, never thrown. */
    public static Run parseRun(String jsonl, String modelId) {
        String text = null;
        String failure = null;
        long in = 0;
        long cached = 0;
        long out = 0;
        if (jsonl != null) {
            for (String line : jsonl.split("\n")) {
                JsonObject ev;
                try {
                    JsonElement el = JsonParser.parseString(line.strip());
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    ev = el.getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                String type = ev.has("payload_type") ? ev.get("payload_type").getAsString() : "";
                JsonObject p = ev.has("payload") && ev.get("payload").isJsonObject()
                        ? ev.getAsJsonObject("payload") : new JsonObject();
                if ("run.usage".equals(type) && p.has("models") && p.get("models").isJsonArray()) {
                    for (JsonElement m : p.getAsJsonArray("models")) {
                        JsonObject mo = m.getAsJsonObject();
                        if (mo.has("model") && modelId.equals(mo.get("model").getAsString())) {
                            in = num(mo, "input_tokens");
                            cached = num(mo, "cached_tokens");
                            out = num(mo, "output_tokens");
                        }
                    }
                }
                if (type.startsWith("run.terminal")) {
                    String terminal = p.has("terminal") && !p.get("terminal").isJsonNull()
                            ? p.get("terminal").getAsString() : "";
                    if ("completed".equals(terminal) && p.has("text") && !p.get("text").isJsonNull()) {
                        text = p.get("text").getAsString();
                    } else if (!"completed".equals(terminal)) {
                        failure = terminal + ": " + (p.has("reason") ? p.get("reason").toString() : "");
                    }
                }
            }
        }
        return new Run(text, in, cached, out, failure);
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Dollars at list price; 0 for a model with no row (reported, never priced as something else). */
    public static double price(String modelId, Run r) {
        double[] rate = RATES.get(modelId);
        if (rate == null || r == null) {
            return 0.0;
        }
        long fresh = Math.max(0, r.inputTokens() - r.cachedTokens());
        return (fresh * rate[0] + r.cachedTokens() * rate[2] + r.outputTokens() * rate[1]) / 1e6;
    }
}
