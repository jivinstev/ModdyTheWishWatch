package com.moddingfromamod.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>What a player is told about the world he is standing in</b> — its name, how a stranger
 * gets onto its list, its age rule and its first-join intro, read from the world's own manifest.
 *
 * <p>The manifests ({@code worlds/<id>.json}) ship IN THE JAR as {@code /worlds/<id>.json}
 * ({@code build.gradle} processResources), and the world is chosen by the id a server's launcher
 * already passes ({@code -Dmoddingfromamod.world.id}, {@link WorldModuleHost#worldId()}). So a
 * manifest's words reach a server with the release that carries them — no launch-config line, no
 * second copy on disk, and no JVM-argument quoting of a sentence with an apostrophe in it.
 *
 * <p>A world that names no id (the dev client, a GameTest server) or an id with no bundled manifest
 * is {@link #NONE}: invite-only wording and no world intro — exactly what every world said before.
 * Minecraft-free; {@link #parse} is the Gate A surface ({@code WorldFactsTest}).
 *
 * @param id     the manifest's id, or empty
 * @param name   what a player calls it ("Pebble Bay"), or empty
 * @param join   {@code access.join}: {@code "request"} (sign in and ask for a seat) or
 *               {@code "invite"} (an invitation link) — empty when the manifest says nothing
 * @param minAge {@code access.minAge}, or 0 for no age rule
 * @param intro  the manifest's {@code intro}: "what this world is", shown on a first arrival
 * @param guide  the manifest's {@code guide}: the guide book and the goal tree, or
 *               {@link WorldGuide#NONE}
 */
public record WorldFacts(String id, String name, String join, int minAge, List<String> intro, WorldGuide guide) {

    /** No manifest: the words every world said before manifests. */
    public static final WorldFacts NONE = new WorldFacts("", "", "", 0, List.of());

    public static final String REQUEST = "request";
    public static final String INVITE = "invite";

    /** Where the bundled manifests live on the classpath. */
    public static final String RESOURCE_DIR = "/worlds/";

    private static final Map<String, WorldFacts> CACHE = new ConcurrentHashMap<>();

    public WorldFacts {
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        join = join == null ? "" : join.strip();
        minAge = Math.max(0, minAge);
        intro = intro == null ? List.of() : List.copyOf(intro);
        guide = guide == null ? WorldGuide.NONE : guide;
    }

    /** A world with no guide (the shape every caller used before guides). */
    public WorldFacts(String id, String name, String join, int minAge, List<String> intro) {
        this(id, name, join, minAge, intro, WorldGuide.NONE);
    }

    /** True for a world a stranger joins by asking for a seat (an invite-by-seat world). */
    public boolean bySeat() {
        return REQUEST.equals(join);
    }

    /**
     * The JVM property naming which manifest's words are this world's, on a server whose
     * launch config names no world (one installed before worlds had ids): the launch script
     * derives it. Deliberately NOT
     * {@link WorldModuleHost#WORLD_PROPERTY}: that id also keys the wish bag's file and a building's
     * placed row ({@link WorldModuleHost#worldKey}), and setting it there would move his things.
     */
    public static final String MANIFEST_PROPERTY = "moddingfromamod.world.manifest";

    /**
     * This world's manifest id: the world's own id when it names one, else {@link #MANIFEST_PROPERTY}, else the
     * running world module's id (a level's world switched on by its world type names no id; the module that
     * claimed it is the level, and its manifest is {@code worlds/<module id>.json}).
     */
    public static String manifestId() {
        String id = manifestId(WorldModuleHost.worldId(), System.getProperty(MANIFEST_PROPERTY));
        if (id.isEmpty()) {
            WorldModule m = WorldModuleHost.liveModule();
            id = m == null || m.id() == null ? "" : m.id().strip();
        }
        return id;
    }

    /** The rule of {@link #manifestId()}: the world id wins; the derived manifest fills a blank. Pure. */
    static String manifestId(String worldId, String derived) {
        if (worldId != null && !worldId.isBlank()) {
            return worldId.strip();
        }
        return derived == null ? "" : derived.strip();
    }

    /** This world's facts — {@link #NONE} on a world with no bundled manifest. Cached per id (the jar cannot change under us). */
    public static WorldFacts current() {
        return forId(manifestId());
    }

    /** The bundled manifest for {@code id}, or {@link #NONE}. */
    public static WorldFacts forId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return NONE;
        }
        return CACHE.computeIfAbsent(id, WorldFacts::load);
    }

    private static WorldFacts load(String id) {
        String text = manifestText(id);
        return text == null ? NONE : parse(text);
    }

    /**
     * The text of the bundled manifest {@code worlds/<id>.json}, or null when no jar carries one. The core's own jar
     * first, then every loaded mod's file: a LEVEL jar ships its own world's manifest, and under FML each mod
     * is its own module, so {@code Class.getResourceAsStream} from here never sees a resource in another mod's jar.
     * Every reader of a manifest (this class, {@code WorldCrafters}, {@code WorldPeople}, {@code BuilderSettings},
     * {@code DatapackDefaults}) goes through here.
     */
    public static String manifestText(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return null;
        }
        return firstOf(List.of(() -> fromOwnJar(id), () -> fromModFiles(id)));
    }

    /** The first source that answers with text; one that throws is skipped (a broken mod file is not this world's). */
    static String firstOf(List<java.util.function.Supplier<String>> sources) {
        for (java.util.function.Supplier<String> s : sources) {
            try {
                String text = s.get();
                if (text != null) {
                    return text;
                }
            } catch (RuntimeException e) {
                // the next source
            }
        }
        return null;
    }

    private static String fromOwnJar(String id) {
        try (InputStream in = WorldFacts.class.getResourceAsStream(RESOURCE_DIR + id + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String fromModFiles(String id) {
        net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
        if (mods == null) {
            return null;   // no mod loader (a unit test): the own jar was the only place to look
        }
        for (net.neoforged.neoforgespi.language.IModFileInfo info : mods.getModFiles()) {
            java.nio.file.Path p = info.getFile().findResource("worlds", id + ".json");
            if (p != null && java.nio.file.Files.isRegularFile(p)) {
                try {
                    return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // the next mod
                }
            }
        }
        return null;
    }

    /**
     * A manifest's text → its facts. Anything malformed falls back field by field (a missing intro is
     * no intro; an unreadable document is {@link #NONE}) — never an exception at a login. Pure.
     */
    public static WorldFacts parse(String json) {
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(json == null ? "" : json);
            if (!el.isJsonObject()) {
                return NONE;
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return NONE;
        }
        String id = str(o, "id");
        String name = str(o, "name");
        String join = "";
        int minAge = 0;
        JsonElement acc = o.get("access");
        if (acc != null && acc.isJsonObject()) {
            join = str(acc.getAsJsonObject(), "join");
            JsonElement age = acc.getAsJsonObject().get("minAge");
            if (age != null && age.isJsonPrimitive() && age.getAsJsonPrimitive().isNumber()) {
                minAge = age.getAsInt();
            }
        }
        List<String> intro = new ArrayList<>();
        JsonElement in = o.get("intro");
        if (in != null && in.isJsonArray()) {
            for (JsonElement line : in.getAsJsonArray()) {
                if (line.isJsonPrimitive() && !line.getAsString().isBlank()) {
                    intro.add(line.getAsString().strip());
                }
            }
        }
        return new WorldFacts(id, name, join, minAge, intro, WorldGuide.parse(o.get("guide")));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
