package com.moddingfromamod.datapack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.agent.PlayerReach;

/**
 * <b>The other-player rule, applied to a compiled datapack</b>. Every line of every function
 * in the zip goes through {@link PlayerReach}, as the PRESSER ({@link PlayerReach.Who#presser}): only
 * {@code @s} is self, and any name written into a function is somebody else — because a function runs
 * long after the wish, for whoever presses the button or holds the item.
 *
 * <p>Read from the COMPILED output, not the CBScript source, because the output is what will run:
 * CBScript's own syntax and the effect templates' {@code ${…}} slots are already resolved there, so
 * one reader covers every way a line can be written. A {@code $} macro line is read without its
 * {@code $}; what a macro argument fills in at run time is the run-time hook's
 * ({@code world.PlayerVisits}).
 *
 * <p>Pure apart from reading the zip: {@code DatapackReachTest}.
 */
public final class DatapackReach {

    private DatapackReach() {
    }

    /** Null when every function complies under {@code mode}; else the refusal, naming the line. */
    public static String check(Path zip, PlayerReach.Who who, PlayerReach.Mode mode, String workshop) {
        if (zip == null || !Files.isRegularFile(zip)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".mcfunction")) {
                    continue;
                }
                String text = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                String bad = checkFunction(name, text, who, mode, workshop);
                if (bad != null) {
                    return bad;
                }
            }
        } catch (IOException ex) {
            return null;   // an unreadable zip fails to load on its own; this rule is not that check
        }
        return null;
    }

    /** One function's text: null when it complies, else the refusal. */
    public static String checkFunction(String file, String text, PlayerReach.Who who,
                                       PlayerReach.Mode mode, String workshop) {
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("$")) {
                line = line.substring(1).strip();
            }
            PlayerReach.Verdict v = PlayerReach.check(line, who);
            if (!PlayerReach.allows(v, mode)) {
                return "NOT COMPILED — the function " + functionId(file) + " says `" + line + "`. "
                        + PlayerReach.refusal(v, workshop);
            }
        }
        return null;
    }

    /** {@code data/ns/function/a/b.mcfunction} → {@code ns:a/b}. */
    static String functionId(String entry) {
        String[] p = entry.split("/");
        if (p.length >= 4 && p[0].equals("data")) {
            StringBuilder path = new StringBuilder();
            for (int i = 3; i < p.length; i++) {
                path.append(i == 3 ? "" : "/").append(p[i]);
            }
            return p[1] + ":" + path.toString().replaceFirst("\\.mcfunction$", "");
        }
        return entry;
    }
}
