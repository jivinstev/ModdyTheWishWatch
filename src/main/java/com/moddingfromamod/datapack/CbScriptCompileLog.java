package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only record of every CBScript compile the agent attempts — the RAW SIGNAL for the
 * "outer loop" that continuously makes the CBScript author more robust.
 *
 * <p>Each failed draft, its exact error, and the eventual success are the training data for
 * improving the context we hand the model ({@link CbScriptLessons}) and for finding compiler
 * robustness bugs to fix in the fork. Every iteration the agent burns is time-to-goal spent, so the
 * whole point is to mine these logs and drive the loop count down over time. Analyse with
 * {@code tools/cbscript-outer-loop.sh}.
 *
 * <p>Machine-local JSONL at {@code ~/.moddingfromamod/cbscript-compiles.jsonl} (it can grow and it
 * contains generated source, so it's kept out of the repo — the distilled lessons are what gets
 * committed). One JSON object per line:
 * {@code {time_ms, world, session, feature_id, ok, error, source}}.
 *
 * <p><b>{@code world} and {@code session} are what make attribution a FACT rather than a guess</b>
 *. Without them the only way to say which run a compile belonged to was "the most recent run
 * that started before it" — sound for a serial harness and wrong the moment two overlap. It
 * produced a wrong number on its first outing: with a second run in flight, one arm read as 7
 * compile attempts / 5 failed when the truth was 3 / 2. Every gated run gets its own world, so this
 * one field turns that inference into a join. Stamped from the same ambient accessor
 * {@code ActionLog} uses, so the two logs can be joined to each other as well.
 */
public final class CbScriptCompileLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private CbScriptCompileLog() {
    }

    /**
     * Beside the action log, under the <b>configured</b> home.
     *
     * <p>It read {@code user.home} directly until the home became relocatable, which made {@code -Dmoddingfromamod.home}
     * a half-move: the action log went to the new home and this file stayed in the old one, so a
     * harvest of one home held the wishes without the compiles that belong to them, and a test
     * that relocated the home still appended to the real machine's file.
     */
    public static Path file() {
        return com.moddingfromamod.runtime.RuntimeConfig.home()
                .resolve("cbscript-compiles.jsonl");
    }

    /**
     * Record one compile attempt. {@code timeMs} is passed in (never {@code System.currentTimeMillis()}
     * captured deep in library code) so callers control ordering; the source is stored verbatim
     * because a failed draft is only useful next to the exact text that produced it.
     */
    public static void record(long timeMs, String featureId, boolean ok, String error, String source) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("time_ms", timeMs);
            // ⚠ IS THIS PLAY OR A GATE? The sibling log has answered that since RunContext existed
            // and this one never did, so anything computed here -- above all the compile FAILURE
            // RATE that CLAUDE.md quotes as a headline -- mixed the agent's real attempts with
            // CbScriptCorpusTest replaying the corpus at itself. Measured on a fresh container:
            // 6,852 compiles logged, every one of them gate traffic, and no field said so.
            //
            // This is exactly what RunContext was written for -- "the improvement loop was being
            // fed its own test traffic" -- applied to ActionLog's seven record types and never to
            // this one. A defect found is a defect CLASS found.
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("feature_id", featureId == null ? "" : featureId);
            o.addProperty("ok", ok);
            if (error != null && !error.isBlank()) {
                o.addProperty("error", error);
            }
            o.addProperty("source", source == null ? "" : source);
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Telemetry must never break a build. Losing a log line is acceptable.
            LOGGER.debug("could not append to the CBScript compile log: {}", e.toString());
        }
    }

    /**
     * Which world and which sitting this compile happened in — omitted rather than written as
     * {@code "null"} when no world is open, so a consumer can tell "no world" from a world
     * genuinely called that. Same rule, and the same accessors, as {@code ActionLog.stampLineage}.
     *
     * <p>Never throws. A compile must not fail because telemetry could not name its world, and a
     * datapack compiles fine outside a world (the GameTests do it constantly).
     */
    private static void stampLineage(JsonObject o) {
        try {
            String world = com.moddingfromamod.runtime.WorldPaths.worldName();
            if (world != null) {
                o.addProperty("world", world);
            }
            String session = com.moddingfromamod.runtime.WorldPaths.sessionId();
            if (session != null) {
                o.addProperty("session", session);
            }
        } catch (RuntimeException ignored) {
            // lineage is a convenience for the outer loop, never a precondition for compiling
        }
    }
}
