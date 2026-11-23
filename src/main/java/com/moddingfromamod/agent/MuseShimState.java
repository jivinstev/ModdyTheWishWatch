package com.moddingfromamod.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>What {@code tools/muse-cli-shim.py} decided for each worker</b> — read, never written.
 *
 * <p>With a Muse Code subscription and an API fallback, the shim chooses the arm at each worker's
 * launch, so the server-wide {@link AgentSession#resolvedModel()} no longer says which model served
 * a given wish. The shim writes two small files per pool slot under {@code MUSE_SHIM_STATE}:
 *
 * <ul>
 *   <li>{@code served/<worker>.json} — the arm of that slot's LATEST launch:
 *       {@code {"model": "muse:muse-wonder-1.0", "arm": "subscription", "billing": "subscription"}}.
 *       {@link com.moddingfromamod.provenance.ModelStamp} and the {@code settled} record name that
 *       model instead of the requested one, so a thing Wonder made is not stamped "Spark".</li>
 *   <li>{@code deaths/<worker>.json} — the slot's worker died of a spent quota or a failed login:
 *       {@code {"reason": "quota", "atMs": …}}. {@code HealthService} re-queues the wish that worker
 *       held instead of waiting out the no-progress timeout and telling the child Moddy fell asleep.</li>
 * </ul>
 *
 * <p><b>Absent unless the server turned it on</b>: {@code MUSE_SHIM_STATE} is set only by
 * a dedicated server's launcher when the world's {@code bin/} carries the shim, so with it unset every method here
 * answers empty and the server behaves exactly as it did.
 *
 * <p>Pure apart from reading the files: the environment arrives as a lookup and the directory as a
 * parameter, so every rule is a unit test ({@code MuseShimStateTest}).
 */
public final class MuseShimState {

    /** The shim's per-world state directory variable — the same name the shim reads. */
    public static final String ENV = "MUSE_SHIM_STATE";

    /** Why a worker died, as the shim records it. */
    public record Death(String worker, String reason, long atMs) {
    }

    private MuseShimState() {
    }

    /** The state directory, or empty when the subscription path is not in use on this server. */
    public static Optional<Path> dir(UnaryOperator<String> env) {
        String v = env.apply(ENV);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(Path.of(v.trim()));
    }

    /** {@link #dir(UnaryOperator)} over this process's environment. */
    public static Optional<Path> dir() {
        return dir(System::getenv);
    }

    /**
     * The model spec that served this worker's latest launch ({@code muse:muse-wonder-1.0}), or
     * empty. A name that could escape the directory is refused rather than resolved.
     */
    public static Optional<String> servedSpec(Path dir, String worker) {
        return read(dir, "served", worker).map(o -> str(o, "model")).filter(s -> !s.isBlank());
    }

    /** The served spec for the worker on THIS thread (a tool call), when the shim is in use. */
    public static Optional<String> servedSpecForCurrentWorker() {
        try {
            String worker = AgentContext.workerName();
            return dir().flatMap(d -> servedSpec(d, worker));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** This worker's recorded death, if the shim saw one. */
    public static Optional<Death> death(Path dir, String worker) {
        return read(dir, "deaths", worker).map(o -> new Death(worker, str(o, "reason"),
                o.has("atMs") && o.get("atMs").isJsonPrimitive() ? o.get("atMs").getAsLong() : 0L))
                .filter(d -> !d.reason().isBlank());
    }

    /** Forget a death once its wish has been re-queued, so it is acted on once. */
    public static void consumeDeath(Path dir, String worker) {
        if (!safeName(worker)) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve("deaths").resolve(worker + ".json"));
        } catch (IOException e) {
            // A death left behind is harmless: it predates every later claim, and only a death
            // AFTER a claim re-queues that claim's wish.
        }
    }

    private static Optional<JsonObject> read(Path dir, String sub, String worker) {
        if (dir == null || !safeName(worker)) {
            return Optional.empty();
        }
        Path f = dir.resolve(sub).resolve(worker + ".json");
        try {
            if (!Files.isRegularFile(f)) {
                return Optional.empty();
            }
            JsonElement e = JsonParser.parseString(Files.readString(f));
            return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();       // torn or unreadable: no answer, never a throw
        }
    }

    static boolean safeName(String worker) {
        return worker != null && worker.matches("[A-Za-z0-9._-]{1,80}") && !worker.contains("..");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
