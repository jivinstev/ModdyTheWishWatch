package com.moddingfromamod.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.moddingfromamod.wishbuilder.Apprentice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The installed things the Builder and the Apprentice may make besides their own picks (any item,
 * any creature): the SAME {@link ContentIndex} the Magician's {@code find_content} searches, so the two paths can never
 * find different things. In the game it is {@link ContentIndexes#live()} (set at mod construction, built on first
 * use once the registries are frozen); off the game (Gate A) it is the committed snapshot,
 * {@code corpus/apprentice/content-index.json}, which {@code ContentIndexGameTest} holds equal to the live game.
 * Pure: no Minecraft type. tools/apprentice_lib.py {@code found_entry} is the Python twin.
 */
public final class Found {

    /** Moddy's own things (his watch, a wish carrier) are made, never found. */
    public static final String OWN = "moddingfromamod:";

    private static volatile Supplier<ContentIndex> source;
    private static volatile ContentIndex snapshot;
    private static volatile ContentIndex namedFor;
    private static volatile Map<String, String> names = Map.of();

    private Found() {
    }

    /** The game's own index; called once at mod construction. */
    public static void use(Supplier<ContentIndex> live) {
        source = live;
    }

    public static ContentIndex index() {
        Supplier<ContentIndex> s = source;
        if (s != null) {
            return s.get();
        }
        ContentIndex c = snapshot;
        if (c == null) {
            synchronized (Found.class) {
                if (snapshot == null) {
                    snapshot = new ContentIndex(readSnapshot(), Apprentice.english());
                }
                c = snapshot;
            }
        }
        return c;
    }

    /** The English name of an installed thing of this kind, or null when there is none (or it is Moddy's own). */
    public static String name(ContentIndex.Kind kind, String id) {
        if (id == null || id.startsWith(OWN)) {
            return null;
        }
        ContentIndex ix = index();
        if (namedFor != ix) {
            synchronized (Found.class) {
                if (namedFor != ix) {
                    Map<String, String> m = new HashMap<>();
                    for (ContentIndex.Entry e : ix.entries()) {
                        m.putIfAbsent(e.kind() + "|" + e.id(), e.name());
                    }
                    names = m;
                    namedFor = ix;
                }
            }
        }
        return names.get(kind + "|" + id);
    }

    /** Is there an installed thing of this kind with this id (Moddy's own never)? */
    public static boolean is(ContentIndex.Kind kind, String id) {
        return name(kind, id) != null;
    }

    /**
     * {@code {"found": "1", "one": name, "many": its plural, "usual": "1", "most": "64"}}: the facts of a found entry
     * ({@code many} through {@link com.moddingfromamod.wishbuilder.English#plural},: "3 real foxes").
     */
    public static Map<String, String> facts(String name, boolean mob) {
        String low = name.toLowerCase(Locale.ROOT);
        String many = com.moddingfromamod.wishbuilder.English.plural(name, mob).toLowerCase(Locale.ROOT);
        return Map.of("found", "1", "one", low, "many", many, "usual", "1", "most", "64");
    }

    private static List<ContentIndex.Entry> readSnapshot() {
        List<ContentIndex.Entry> out = new ArrayList<>();
        Path dir = Path.of("").toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path f = d.resolve("corpus/apprentice/content-index.json");
            if (Files.exists(f)) {
                try {
                    for (JsonElement r : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonArray("entries")) {
                        var a = r.getAsJsonArray();
                        out.add(new ContentIndex.Entry(a.get(0).getAsString(),
                                ContentIndex.Kind.valueOf(a.get(1).getAsString()), a.get(2).getAsString()));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(f + " could not be read", e);
                }
                break;
            }
        }
        return out;
    }
}
