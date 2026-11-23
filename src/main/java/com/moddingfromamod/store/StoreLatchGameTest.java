package com.moddingfromamod.store;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.mob.MobHandleStore;
import com.moddingfromamod.paint.PaintRecipe;
import com.moddingfromamod.paint.PaintRecord;
import com.moddingfromamod.paint.PaintRegistry;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B: the refusal latch where it RUNS, on the stores that did not have one.</b>
 *
 * <p>Before this, {@code paintings.json} (and {@code machines.json}, a store that went with Create
 *) were read the way every store
 * was before the store envelope: a file that did not parse was logged, the map came back empty, and the next save
 * rewrote the file from nothing. {@code mobs.json} carried a version but read AND wrote whatever it
 * said, so an older build saved its narrower view over a newer file. Each test here stages exactly
 * that file, makes an ordinary change through the store's public API, and requires the bytes on
 * disk to be the ones it staged.
 *
 * <p>{@code WishStoreFileTest} proves the rules on the helpers; this proves the stores CALL them —
 * a rule tested only where it is defined is not tested where it runs.
 *
 * <p>Each test is its own batch and PUTS THE WORLD BACK — the staged file is replaced by what was
 * there before and the store reloaded, which also resets its latch (the latch is per load). Every
 * store saves on every change, so the reload restores exactly what neighbours had written.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StoreLatchGameTest {

    private static final String DAMAGED = "{\"paintings\": this is not json";

    @GameTest(template = "empty_test", batch = "storelatchpaint", timeoutTicks = 200)
    public static void aDamagedPaintingsFileIsNeverOverwritten(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path f = WorldPaths.wishesDir().resolve("paintings.json");
        for (String staged : List.of(DAMAGED,
                "{\"store\":\"paintings\",\"v\":99,\"paintings\":[]}")) {
            String before = read(f);
            try {
                write(f, staged);
                PaintRegistry.load(server);
                PaintRegistry.put(server, new PaintRecord("latch_" + System.nanoTime(),
                        "minecraft:overworld", 0, 64, 0, "latch", false, "", PaintRecipe.blank(),
                        0, 0));
                String after = read(f);
                if (!staged.equals(after)) {
                    helper.fail(Component.literal("paintings.json was rewritten over a file this build could not "
                            + "read. Staged: " + staged + "\n  found: " + clip(after)));
                    return;
                }
            } finally {
                restore(f, before);
                PaintRegistry.load(server);
            }
        }
        // And the latch is per LOAD: with the file put back, an ordinary save stamps it.
        PaintRegistry.put(server, new PaintRecord("latch_ok_" + System.nanoTime(),
                "minecraft:overworld", 0, 64, 0, "latch", false, "", PaintRecipe.blank(), 0, 0));
        helper.assertTrue(read(f).contains("\"store\":\"paintings\""),
                Component.literal("after a clean reload the store writes again, stamped: " + clip(read(f))));
        helper.succeed();
    }

    /**
     * {@code mobs.json} still READS a newer file best-effort — an older build showing a child's
     * mob names beats showing none — but must never WRITE over it.
     */
    @GameTest(template = "empty_test", batch = "storelatchmobs", timeoutTicks = 200)
    public static void anOlderBuildNeverWritesOverANewerMobsFile(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path f = WorldPaths.wishesDir().resolve("mobs.json");
        String before = read(f);
        String newer = "{\"version\":99,\"handles\":[],\"somethingNew\":{\"kept\":true}}";
        try {
            write(f, newer);
            MobHandleStore.save(server);
            helper.assertTrue(newer.equals(read(f)),
                    Component.literal("an older build saved over a newer mobs.json, dropping what it did not "
                            + "understand: " + clip(read(f))));
        } finally {
            restore(f, before);
        }
        helper.succeed();
    }

    private static String read(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Path f, String text) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not stage " + f + ": " + e, e);
        }
    }

    private static void restore(Path f, String before) {
        try {
            if (before == null) {
                Files.deleteIfExists(f);
            } else {
                Files.writeString(f, before, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // best effort; the next load reports whatever is there
        }
    }

    private static String clip(String s) {
        return s == null ? "(no file)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
