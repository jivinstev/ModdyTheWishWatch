package com.moddingfromamod.datapack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wish.AttributeIds;
import com.moddingfromamod.wish.CommandDialect;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

/**
 * <b>A STORED datapack is text in the dialect of the build that compiled it</b>, and it is
 * put into this build's dialect on its way into a world.
 *
 * <p>{@link CommandDialect} corrects command text at the places it is written: a command the agent
 * runs, a CBScript program before it compiles. A datapack compiled on another day by another build
 * never passes through either. The starter library ships five of them, compiled on 1.21.1, and on
 * the 1.21.4 build two failed at pack load: {@code give …[minecraft:custom_model_data=8]} is
 * 1.21.1's spelling, and 1.21.4 refused four functions with
 * {@code Malformed 'minecraft:custom_model_data' component: 'Not a map: 8'}. Nothing else noticed.
 * The pack still reported enabled, so the child's tiny cookie and teleport wand did nothing, and
 * Gate B stayed green because no test parsed a stored function on the target it ran on.
 *
 * <p>So the correction sits on the COPY into the world (both {@link DatapackStore#installAllIntoWorld}
 * and {@link DatapackReloader#installAndReload}). The stored zip is left alone, so it stays readable
 * by the build that wrote it. Recompiling from CBScript would be tidier, but the starter zips carry
 * no source. {@code pack.mcmeta}'s {@code pack_format} is set to this build's own number as well, so
 * a pack is never labelled as made for another version.
 */
public final class DatapackDialect {

    private DatapackDialect() {
    }

    /** What {@link #retarget} made: the bytes to install, and every correction it applied. */
    public record Retargeted(byte[] zip, List<String> notes) {
        public boolean changed() {
            return !notes.isEmpty();
        }
    }

    /**
     * Rewrite a datapack zip. Pure: the corrector and the pack format are handed in, so it runs
     * without a server. Every {@code .mcfunction} goes through {@code correctFunction};
     * {@code pack.mcmeta} gets {@code packFormat}; everything else is copied byte for byte. The
     * output zip is deterministic (fixed entry times, input order).
     */
    public static Retargeted retarget(byte[] zip, UnaryOperator<String> correctFunction,
                                      int packFormat, List<String> notes) throws IOException {
        List<String> applied = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream(zip.length + 256);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
             ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                String name = e.getName();
                if (!e.isDirectory() && name.endsWith(".mcfunction")) {
                    String before = new String(data, StandardCharsets.UTF_8);
                    String after = correctFunction.apply(before);
                    if (!after.equals(before)) {
                        applied.add(name);
                        data = after.getBytes(StandardCharsets.UTF_8);
                    }
                } else if (!e.isDirectory() && name.endsWith(".json") && name.contains("/advancement")) {
                    String before = new String(data, StandardCharsets.UTF_8);
                    String after = correctNbtPredicates(before);
                    if (!after.equals(before)) {
                        applied.add(name + " (sign text in an nbt predicate)");
                        data = after.getBytes(StandardCharsets.UTF_8);
                    }
                } else if (name.equals("pack.mcmeta")) {
                    byte[] fixed = withPackFormat(data, packFormat);
                    if (fixed != data) {
                        applied.add("pack.mcmeta pack_format -> " + packFormat);
                        data = fixed;
                    }
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(0L);
                zos.putNextEntry(copy);
                if (!e.isDirectory()) {
                    zos.write(data);
                }
                zos.closeEntry();
            }
        }
        notes.addAll(applied);
        return new Retargeted(applied.isEmpty() ? zip : out.toByteArray(), applied);
    }

    /**
     * An advancement's {@code "nbt"} predicates in the 1.21.5 text spelling. A fill machine's
     * handle is told apart from every other lever by the id on its sign's back,
     * {@code {back_text:{messages:['"id"']}}} — JSON text in a string, which is how 1.21.4 stored a
     * sign line. 1.21.5 stores it as the bare string, so the old predicate matches no sign and no
     * stored machine's handle answers. The same {@link ComponentSpelling} correction a command gets
     * is applied to every {@code nbt} string; anything unreadable is returned unchanged.
     */
    static String correctNbtPredicates(String json) {
        if (!json.contains("\"nbt\"")) {
            return json;
        }
        try {
            com.google.gson.JsonElement root = JsonParser.parseString(json);
            boolean[] changed = {false};
            fixNbt(root, changed);
            return changed[0] ? root.toString() : json;
        } catch (RuntimeException unreadable) {
            return json;
        }
    }

    private static void fixNbt(com.google.gson.JsonElement e, boolean[] changed) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (var entry : o.entrySet()) {
                var v = entry.getValue();
                if (entry.getKey().equals("nbt") && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String before = v.getAsString();
                    String after = com.moddingfromamod.compat.ComponentSpelling.correct(before, new ArrayList<>());
                    if (!after.equals(before)) {
                        entry.setValue(new com.google.gson.JsonPrimitive(after));
                        changed[0] = true;
                    }
                } else {
                    fixNbt(v, changed);
                }
            }
        } else if (e.isJsonArray()) {
            for (var item : e.getAsJsonArray()) {
                fixNbt(item, changed);
            }
        }
    }

    /** {@code pack.mcmeta} with {@code pack.pack_format} set, or the same array if it already was. */
    static byte[] withPackFormat(byte[] mcmeta, int packFormat) {
        try {
            JsonObject root = JsonParser.parseString(new String(mcmeta, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject pack = root.getAsJsonObject("pack");
            if (pack == null || (pack.has("pack_format")
                    && pack.get("pack_format").getAsInt() == packFormat)) {
                return mcmeta;
            }
            pack.addProperty("pack_format", packFormat);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException unreadable) {
            // Not ours to rewrite: Minecraft will report an unreadable mcmeta itself.
            return mcmeta;
        }
    }

    /** This build's server-data pack format (48 on 1.21.1, 61 on 1.21.4). */
    public static int packFormat() {
        return SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
    }

    /**
     * One function's text in this build's dialect, judged by the live attribute registry — and an
     * impulse ability compiled given the fixed template's telegraph
     * ({@link ImpulseTelegraphFix}), so every creature that already has one stops stomping at
     * nothing the next time its pack is installed — and a grow or impulse pack compiled
     * given its victims' tag ({@link VictimTagFix}), so it stops looking at every entity each tick; and
     * an on_step or on_land pack compiled has its reach counted from the creature's side
     * ({@link ReachFromBodyFix}).
     */
    public static String correctFunction(String text, List<String> notes) {
        AttributeIds.Fixed fixed = CommandDialect.correct(text);
        notes.addAll(fixed.notes());
        return ReachFromBodyFix.fix(VictimTagFix.fix(ImpulseTelegraphFix.fix(fixed.command(), notes), notes), notes,
                com.moddingfromamod.vanilla.CreatureSpawn::halfWidth);
    }

    /** {@code src} rewritten for this build: what an install copies into a world. */
    public static Retargeted forThisBuild(Path src) throws IOException {
        List<String> corrections = new ArrayList<>();
        List<String> files = new ArrayList<>();
        Retargeted r = retarget(Files.readAllBytes(src), t -> correctFunction(t, corrections),
                packFormat(), files);
        List<String> all = new ArrayList<>(files);
        all.addAll(corrections);
        return new Retargeted(r.zip(), r.changed() ? all : List.of());
    }
}
