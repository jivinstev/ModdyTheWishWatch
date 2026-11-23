package com.moddingfromamod.wish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WishRun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which wishes are we granting live but not saving, and how often? —.
 *
 * <p>One JSONL line per wish that reached DONE having persisted nothing re-runnable. That question
 * had no answer at all before this: the Spikor fireball was found by reading a world save by hand,
 * afterwards, once somebody already suspected it.
 *
 * <p>Machine-local beside the other logs, and stamped with world + sitting so it joins to
 * {@code agent-actions.jsonl} and to the provenance commits —, where four worlds flattened
 * into one history because nothing recorded which world an action happened in.
 *
 * <p>Best-effort by design: a wish that has just come true is not worth failing over a log line.
 */
public final class OneOffWishLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private OneOffWishLog() {}

    /** Beside the action log, under the <b>configured</b> home — see CbScriptCompileLog#file. */
    public static Path file() {
        return com.moddingfromamod.runtime.RuntimeConfig.home()
                .resolve("one-off-wishes.jsonl");
    }

    /**
     * The line for one unpersisted grant. Pure — takes the clock rather than reading it, so a test
     * can assert on the whole record.
     */
    public static JsonObject record(String wishId, String wishText, List<WishRun.Made> made,
                                    String world, String session, long timeMs) {
        return record(wishId, wishText, made, world, session, timeMs, false);
    }

    /**
     * The same, marking a HELP wish (a question, a complaint, a fix, or no world change) — it
     * was not told "I can't save it", and a saveable-share count leaves it out of the denominator.
     */
    public static JsonObject record(String wishId, String wishText, List<WishRun.Made> made,
                                    String world, String session, long timeMs, boolean help) {
        JsonObject o = new JsonObject();
        if (help) {
            o.addProperty("help", true);
        }
        o.addProperty("at", timeMs);
        o.addProperty("wishId", wishId == null ? "" : wishId);
        o.addProperty("wish", wishText == null ? "" : wishText);
        // Omitted rather than "null" when there is no world, the rule ActionLog already follows.
        if (world != null && !world.isBlank()) {
            o.addProperty("world", world);
        }
        if (session != null && !session.isBlank()) {
            o.addProperty("session", session);
        }
        JsonArray arr = new JsonArray();
        if (made != null) {
            for (WishRun.Made m : made) {
                JsonObject e = new JsonObject();
                e.addProperty("kind", m.kind());
                e.addProperty("id", m.id());
                arr.add(e);
            }
        }
        // Recorded even when empty, and that is the commonest case worth reading: a wish that made
        // NOTHING is the hand-built fallback this exists to count.
        o.add("made", arr);
        return o;
    }

    /** Append one record to the machine-local log. Never throws. */
    public static void append(JsonObject line) {
        append(file(), line);
    }

    /** The same, to a named file — the seam a test uses so it never writes to $HOME. */
    public static void append(Path f, JsonObject line) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("could not record a one-off wish — {}", e.toString());
        }
    }
}
