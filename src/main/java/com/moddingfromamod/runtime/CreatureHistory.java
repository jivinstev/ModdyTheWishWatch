package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The creature a re-sculpt replaced, so an undo can put it back —.
 *
 * <p>Undoing a refinement used to discard the mobs and <b>keep the edited design</b>. That is right
 * for undoing a CREATION — <i>"he stopped the wish, he did not ask for the idea to be forgotten"</i>
 * — and exactly wrong for a refinement: after <i>"give him tiny black wings"</i> → undo, the child
 * lost his creature and re-spawning it gave him the winged one back. <b>The thing he asked to undo
 * was the only thing that survived.</b>
 *
 * <p>There was nowhere to go back to: {@code CreatureFiles.write} overwrites the folder in place and
 * nothing snapshotted the prior sculpt. {@code UndoContract}'s javadoc claimed <i>"a creature edit
 * is a revision (the previous one is kept)"</i> and no code kept one. This is that comment becoming
 * true.
 *
 * <h2>A version is TWO things</h2>
 *
 * <p>The folder ({@code geometry.json}, {@code texture.png}, {@code sculpt.json}) <b>and</b> the
 * {@link Species} row. Restoring only the folder leaves the registry's scale and health ahead of the
 * model; restoring only the row leaves the model ahead of the numbers. Either way the record and the
 * world disagree, which is an earlier issue's R3 bug in a new place.
 *
 * <p>In memory + on disk beside the creature, and not persisted across a restart on purpose: the
 * issue says the STACK need not survive, only the RESULT must. Once he undoes, the reverted sculpt
 * is the one that persists and the one his spawn egg makes.
 */
public final class CreatureHistory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How many sculpts back an undo can walk. */
    public static final int MAX_HISTORY = 5;

    /** One earlier version: the folder it was saved to, and the registry row that went with it. */
    public record Version(Path folder, Species species) {}

    private static final Map<String, Deque<Version>> HISTORY = new HashMap<>();

    private CreatureHistory() {}

    /**
     * Snapshot the version about to be overwritten. Call BEFORE writing the new one.
     *
     * <p>No-op when there is nothing there yet, which is what makes a creation still undo by
     * removal rather than by reverting to an empty folder.
     */
    public static synchronized void push(String key, Species current) {
        Path live = ModelRepository.dir(key);
        if (current == null || !Files.isDirectory(live)) {
            return;
        }
        try {
            Path snap = live.resolveSibling(key + ".undo-" + System.nanoTime());
            copyDir(live, snap);
            Deque<Version> stack = HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
            stack.addFirst(new Version(snap, current));
            while (stack.size() > MAX_HISTORY) {
                deleteDir(stack.removeLast().folder());
            }
        } catch (Exception e) {
            // Losing an undo step is not worth failing a sculpt the child is waiting for.
            LOGGER.warn("could not snapshot '{}' before re-sculpting — undo will remove it "
                    + "rather than revert it: {}", key, e.toString());
        }
    }

    /** The version before the current one, removed from the stack, or null for a creation. */
    public static synchronized Version pop(String key) {
        Deque<Version> stack = HISTORY.get(key);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Version v = stack.removeFirst();
        if (stack.isEmpty()) {
            HISTORY.remove(key);
        }
        return v;
    }

    /** How many sculpts back this creature can be walked. Test seam. */
    public static synchronized int depth(String key) {
        Deque<Version> stack = HISTORY.get(key);
        return stack == null ? 0 : stack.size();
    }

    /**
     * Put a snapshot back over the live folder.
     *
     * <p>Clears the model cache, or the next frame draws the sculpt that was just reverted.
     */
    public static synchronized boolean restoreFolder(String key, Version v) {
        if (v == null || !Files.isDirectory(v.folder())) {
            return false;
        }
        try {
            Path live = ModelRepository.dir(key);
            deleteDir(live);
            copyDir(v.folder(), live);
            deleteDir(v.folder());
            ModelRepository.clearCache();
            return true;
        } catch (Exception e) {
            LOGGER.warn("could not restore '{}' — {}", key, e.toString());
            return false;
        }
    }

    /** Forget everything. Test seam, and used when a creature is removed outright. */
    public static synchronized void forget(String key) {
        Deque<Version> stack = HISTORY.remove(key);
        if (stack != null) {
            stack.forEach(v -> deleteDir(v.folder()));
        }
    }

    public static synchronized void clear() {
        java.util.List.copyOf(HISTORY.keySet()).forEach(CreatureHistory::forget);
    }

    private static void copyDir(Path from, Path to) throws java.io.IOException {
        Files.createDirectories(to);
        try (var walk = Files.list(from)) {
            for (Path p : walk.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.copy(p, to.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.list(dir)) {
            for (Path p : walk.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
            // A leftover snapshot folder is untidy, never harmful.
        }
    }
}
