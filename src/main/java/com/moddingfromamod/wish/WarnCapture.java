package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * <b>What Minecraft WARNED about while one of our commands ran — kept instead of thrown away.</b>
 *
 * <p>A {@code /summon … {attributes:[{id:"minecraft:scale",base:4}]}} on 1.21.1 SUCCEEDS. It spawns
 * the mob, returns success, and applies none of the attributes; the only evidence is one line in the
 * server log — {@code AttributeMap: Ignoring unknown attribute 'minecraft:scale'} — that nobody
 * reads. That is how a child re-wished his Great Big Shark and got an ordinary one while the game
 * announced the big one. Minecraft is saying "I did not do what you asked"; this hands that sentence
 * to whoever sent the command.
 *
 * <p><b>How:</b> ONE appender on the root logger, installed once, that does nothing unless the
 * logging thread has opened a capture window with {@link #during}. A window is per THREAD, so a
 * warning another thread logs at the same moment (a chunk loader, a second wish) never lands in
 * this command's report — and the per-event cost when nobody is capturing is a ThreadLocal read.
 * Installing per call instead would mean {@code updateLoggers()} on every command.
 *
 * <p>Best-effort by design: if the logging backend is not log4j-core (a stripped runtime), the
 * capture is silently empty and every caller behaves exactly as before — it is evidence, never a
 * gate that can break a wish.
 */
public final class WarnCapture {

    private static final ThreadLocal<List<String>> SINK = new ThreadLocal<>();
    private static volatile boolean installed;

    private WarnCapture() {
    }

    /** What {@link #during} returns: the value, and every WARN/ERROR this thread logged meanwhile. */
    public record Captured<T>(T value, List<String> warnings) {
    }

    /** Run {@code body} on this thread and collect the WARN-or-worse lines it caused to be logged. */
    public static <T> Captured<T> during(Supplier<T> body) {
        install();
        List<String> previous = SINK.get();
        List<String> mine = new ArrayList<>();
        SINK.set(mine);
        try {
            T value = body.get();
            return new Captured<>(value, List.copyOf(mine));
        } finally {
            if (previous == null) {
                SINK.remove();
            } else {
                // A nested window: the outer one sees everything the inner one did, too.
                previous.addAll(mine);
                SINK.set(previous);
            }
        }
    }

    /**
     * Minecraft's own phrasings for "I read your command and threw part of it away", taken from the
     * 1.21.1 source: {@code AttributeMap} ({@code Ignoring unknown/malformed attribute}), {@code Entity}
     * ({@code Failed to parse entity custom name}), {@code Display}, {@code AreaEffectCloud},
     * {@code Villager} gossip, block-entity custom data, and {@code EntityType} skipping a passenger.
     *
     * <p>Deliberately a list rather than "any WARN": a command runs amid everything else the server
     * logs on that thread (a mod announcing itself, a chunk warning), and a child's button must not
     * read PARTIAL because a neighbour complained. Anything else is still REPORTED — just not
     * counted against the wish.
     */
    private static final List<String> DROPPED_PREFIXES = List.of(
            "Ignoring unknown", "Ignoring malformed", "Failed to parse", "Failed to deserialize",
            "Failed to apply custom data", "Skipping Entity with id",
            // since 1.21.6 an entity's load collects its problems in a ProblemReporter, logged as
            // one "Serialization errors:" warning ("Failed to decode value … from field 'attributes':
            // Unknown registry key … attribute") instead of AttributeMap's "Ignoring unknown attribute".
            "Serialization errors");

    /** Does this warning say Minecraft dropped part of what it was asked to do? */
    public static boolean dropsPartOfCommand(String warning) {
        if (warning == null) {
            return false;
        }
        // A capture can carry its logger's name first ("[net.minecraft.world.entity.EntityType]
        // Serialization errors: …", measured on 1.21.8); the phrasing is what follows it.
        String w = warning.startsWith("[") && warning.indexOf("] ") > 0
                ? warning.substring(warning.indexOf("] ") + 2) : warning;
        for (String prefix : DROPPED_PREFIXES) {
            if (w.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True once the appender is on the root logger — false means captures will always be empty. */
    public static boolean active() {
        install();
        return installed;
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            if (!(LogManager.getContext(false) instanceof LoggerContext ctx)) {
                return;
            }
            AbstractAppender appender = new AbstractAppender("moddingfromamod-warn-capture", null,
                    null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    List<String> sink = SINK.get();
                    if (sink != null && event.getLevel().isMoreSpecificThan(Level.WARN)) {
                        sink.add(event.getMessage().getFormattedMessage());
                    }
                }
            };
            appender.start();
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.WARN, null);
            ctx.updateLoggers();
            installed = true;
        } catch (RuntimeException | LinkageError e) {
            // Not log4j-core underneath: stay inert rather than break the command path.
        }
    }
}
