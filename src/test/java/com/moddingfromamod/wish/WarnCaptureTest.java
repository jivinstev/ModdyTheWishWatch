package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * an earlier issue's capture, without a game: it collects this thread's WARNs, only inside the window, and
 * never another thread's — the property that keeps a neighbour's complaint out of a child's wish.
 */
class WarnCaptureTest {

    private static final Logger LOG = LoggerFactory.getLogger("net.minecraft.world.entity.ai.attributes.AttributeMap");

    @Test
    void collectsThisThreadsWarningsInsideTheWindowOnly() throws Exception {
        LOG.warn("before the window");
        AtomicReference<Thread> other = new AtomicReference<>();
        WarnCapture.Captured<String> c = WarnCapture.during(() -> {
            LOG.info("an info line is not a warning");
            LOG.warn("Ignoring unknown attribute '{}'", "minecraft:generic.bigness");
            LOG.error("Failed to parse entity custom name {}", "{");
            Thread t = new Thread(() -> LOG.warn("another thread's warning"));
            other.set(t);
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "done";
        });
        LOG.warn("after the window");
        if (!WarnCapture.active()) {
            return;   // no log4j-core on this classpath: the capture is inert by design
        }
        assertEquals("done", c.value());
        assertEquals(List.of("Ignoring unknown attribute 'minecraft:generic.bigness'",
                "Failed to parse entity custom name {"), c.warnings());
    }

    @Test
    void anOuterWindowSeesWhatAnInnerOneCaught() {
        WarnCapture.Captured<List<String>> outer = WarnCapture.during(() ->
                WarnCapture.during(() -> {
                    LOG.warn("Ignoring malformed attribute 'x'");
                    return 0;
                }).warnings());
        if (!WarnCapture.active()) {
            return;
        }
        assertEquals(List.of("Ignoring malformed attribute 'x'"), outer.value());
        assertEquals(List.of("Ignoring malformed attribute 'x'"), outer.warnings());
    }

    @Test
    void onlyMinecraftsDroppedPhrasingsCountAgainstAWish() {
        assertTrue(WarnCapture.dropsPartOfCommand("Ignoring unknown attribute 'minecraft:scale'"));
        assertTrue(WarnCapture.dropsPartOfCommand("Ignoring malformed attribute 'x'"));
        assertTrue(WarnCapture.dropsPartOfCommand("Failed to parse entity custom name {"));
        assertFalse(WarnCapture.dropsPartOfCommand("Can't keep up! Is the server overloaded?"));
        assertFalse(WarnCapture.dropsPartOfCommand("Create 6.0.10 initializing!"));
        assertFalse(WarnCapture.dropsPartOfCommand(null));
    }
}
