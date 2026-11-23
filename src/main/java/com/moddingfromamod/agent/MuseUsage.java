package com.moddingfromamod.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * <b>What a Muse Code worker spent</b>: every {@code model_completed} step of a pinned session, read from
 * Muse Code's own session log ({@code $XDG_DATA_HOME/muse/sessions/YYYY/MM/DD/<session>/session.jsonl}, else
 * {@code ~/.local/share/…}) — the same events offline cost tooling reads. A step is
 * {@code payload.event} with {@code kind: model_completed} and its {@code usage} (input, cached, output tokens); the
 * record carries {@code recorded_at} (microseconds).
 *
 * <p>Priced at the API's list price when the worker ran on {@code MUSE_API_KEY}; on a subscription the tokens are
 * counted and the price is 0 (the plan's flat fee is never priced as API spend). Pure apart from the file read:
 * {@link #sum} takes the lines ({@code MuseUsageTest}).
 */
public final class MuseUsage {

    private MuseUsage() {
    }

    /** Tokens over the steps counted, and the dollars (0 on a subscription). */
    public record Total(int steps, long inputTokens, long cachedTokens, long outputTokens, double costUsd) {

        public static final Total NONE = new Total(0, 0, 0, 0, 0);

        Total plus(Total o) {
            return new Total(steps + o.steps, inputTokens + o.inputTokens, cachedTokens + o.cachedTokens,
                    outputTokens + o.outputTokens, costUsd + o.costUsd);
        }
    }

    /** Sum the steps recorded in {@code [fromMs, toMs]}; priced at {@code model}'s list price when {@code priced}. */
    static Total sum(List<String> lines, long fromMs, long toMs, String model, boolean priced) {
        int steps = 0;
        long in = 0;
        long cached = 0;
        long out = 0;
        for (String line : lines) {
            JsonObject rec;
            try {
                JsonElement el = JsonParser.parseString(line.strip());
                if (!el.isJsonObject()) {
                    continue;
                }
                rec = el.getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            JsonObject p = rec.has("payload") && rec.get("payload").isJsonObject() ? rec.getAsJsonObject("payload")
                    : null;
            // The step sits at payload.event (measured on a real worker's log, 1.4.23), as tools/wish-cost.py finds it.
            if (p != null && p.has("event") && p.get("event").isJsonObject()) {
                p = p.getAsJsonObject("event");
            }
            if (p == null || !p.has("kind") || !"model_completed".equals(p.get("kind").getAsString())
                    || !rec.has("recorded_at") || !p.has("usage") || !p.get("usage").isJsonObject()) {
                continue;
            }
            long atMs = rec.get("recorded_at").getAsLong() / 1000L;
            if (atMs < fromMs || atMs > toMs) {
                continue;
            }
            JsonObject u = p.getAsJsonObject("usage");
            steps++;
            in += num(u, "input_tokens");
            // 1.4.23 records the cache hit as `cached_tokens` and leaves `cache_read_tokens` 0; take either.
            cached += Math.max(num(u, "cached_tokens"), num(u, "cache_read_tokens"));
            out += num(u, "output_tokens");
        }
        double cost = priced ? MuseGrade.price(model, new MuseGrade.Run(null, in, cached, out, null)) : 0.0;
        return new Total(steps, in, cached, out, cost);
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Muse Code's session-log root. */
    static Path sessionsRoot(Function<String, String> env, String home) {
        String xdg = env.apply("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg.strip()) : Path.of(home, ".local", "share");
        return base.resolve("muse").resolve("sessions");
    }

    /** One session's steps in {@code [fromMs, toMs]}, or {@link Total#NONE} when its log cannot be found. */
    static Total of(Path root, String session, long fromMs, long toMs, String model, boolean priced) {
        if (!Files.isDirectory(root)) {
            return Total.NONE;
        }
        try (Stream<Path> s = Files.walk(root, 6)) {
            List<Path> logs = s.filter(f -> f.getFileName().toString().equals("session.jsonl")
                    && f.getParent() != null && f.getParent().getFileName().toString().equals(session)).toList();
            Total t = Total.NONE;
            for (Path f : logs) {
                t = t.plus(sum(Files.readAllLines(f, StandardCharsets.UTF_8), fromMs, toMs, model, priced));
            }
            return t;
        } catch (IOException | RuntimeException e) {
            return Total.NONE;
        }
    }

    /** What every Muse Code worker launched since {@code fromMs} spent up to {@code toMs}. */
    public static Total spentSince(long fromMs, long toMs) {
        Path root = sessionsRoot(System::getenv, System.getProperty("user.home", ""));
        Total t = Total.NONE;
        for (MuseWorker.Launch l : MuseWorker.launchedSince(fromMs)) {
            t = t.plus(of(root, l.museSession(), fromMs, toMs, l.model(), l.apiKey()));
        }
        return t;
    }
}
