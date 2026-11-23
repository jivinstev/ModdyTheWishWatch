package com.moddingfromamod.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>One reading of the wish library, frozen.</b> Minecraft-free on purpose.
 *
 * <p>The library is the server owner's curated set: a directory (it may be a checkout the server
 * refreshes) holding
 *
 * <pre>
 *   templates.json            same schema as learned.json — the WISH
 *   icons.json                each wish's button picture, by template id — its ICON
 *   species/&lt;key&gt;/…      a creature: geometry.json, texture.png, sculpt.json, species.json
 *   buildings/&lt;id&gt;/…     build.json
 *   items/&lt;id&gt;/…         item.json
 *   datapacks/&lt;id&gt;/…     &lt;id&gt;.zip
 * </pre>
 *
 * <p><b>It is a VALUE, and that is the whole hot-reload design.</b> A reload builds a new snapshot
 * and swaps the reference; nothing is ever mutated in place. So a wish that matched a recipe a
 * moment ago keeps running the recipe it matched, even if the owner pulls a new library underneath
 * it — the alternative is a replay whose command list changes halfway through, which is the one
 * failure a library must not be able to cause.
 *
 * <p><b>The wish and its icon are two files.</b> {@code templates.json} says what a wish
 * does; {@code icons.json} says what its button looks like:
 *
 * <pre>
 *   {"store":"library-icons","v":1,"icons":[{"id":"cake_party","item":"minecraft:cake"}]}
 * </pre>
 *
 * <p>So a cached wish can be authored before its artwork is settled, and the icon revamp
 * can re-dress every button — even change what an icon IS, by bumping that file's {@code v} —
 * without touching a single recipe. {@code icons.json} wins; a recipe that still carries an inline
 * {@code icon} (every library written before the split, and every {@code learned.json} row, which
 * shares the recipe schema) is read as before. {@code promote.py --split-icons} moves a library's
 * inline icons across.
 *
 * <p><b>A missing directory is an EMPTY library, never an error.</b> Most installs have no library
 * at all; making that a failure would mean every caller handling a case that is really the default.
 */
public final class LibrarySnapshot {

    /** The name {@code icons.json} carries in the envelope, and the newest schema read. */
    public static final String ICON_STORE = "library-icons";
    public static final int ICON_SCHEMA = 1;

    /**
     * One curated recipe: its raw JSON exactly as written, plus its icon — from {@code icons.json}
     * when that names it, else the recipe's own inline {@code icon}.
     */
    public record Entry(String id, String json, String icon) {
    }

    private final Path dir;
    private final List<Entry> templates;
    private final Map<String, Entry> byId;
    private final Map<String, Set<String>> keysByKind;
    private final long fingerprint;
    private final List<String> problems;
    /** kind → key → the LAYER directory holding it; null for a single-layer snapshot. */
    private final Map<String, Map<String, Path>> homes;
    /**
     * Ids this layer RETIRES — a {@code {"id": "starter_x", "retired": true}} row. It serves
     * nothing itself, and in a {@link #layered} snapshot it shadows the same id in every later layer,
     * which is how the owner takes one of the starter nine off the panel without a new jar.
     */
    private Set<String> retired = Set.of();
    /**
     * The owner's WATCH ORDER: template ids in the order a child sees them, from a
     * top-level {@code "order"} array in the owner's templates.json. Written by the operator's
     * reorder tool. Empty means "starters first, then file order", as before.
     */
    private List<String> order = List.of();

    private LibrarySnapshot(Path dir, List<Entry> templates, Map<String, Set<String>> keysByKind,
                            long fingerprint, List<String> problems) {
        this(dir, templates, keysByKind, fingerprint, problems, null);
    }

    private LibrarySnapshot(Path dir, List<Entry> templates, Map<String, Set<String>> keysByKind,
                            long fingerprint, List<String> problems,
                            Map<String, Map<String, Path>> homes) {
        this.homes = homes == null ? null : Map.copyOf(homes);
        this.dir = dir;
        this.templates = List.copyOf(templates);
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry e : this.templates) {
            index.putIfAbsent(e.id(), e);
        }
        this.byId = Map.copyOf(index);
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        keysByKind.forEach((k, v) -> kinds.put(k, Set.copyOf(v)));
        this.keysByKind = Map.copyOf(kinds);
        this.fingerprint = fingerprint;
        this.problems = List.copyOf(problems);
    }

    /** The artifact directories a library may hold, and the file that proves one is really there. */
    static final Map<String, String> KIND_MARKER = Map.of(
            "species", "geometry.json",
            "buildings", "build.json",
            "items", "item.json",
            "pictures", "painting.json",   //: a picture is replayable by /wishpaint
            "events", "event.json",        //: an event design, replayable by /moddyevent stage
            "datapacks", "");          // "" = any directory counts; the zip is named after the id

    /** An empty library — what you get with no directory, and what every default resolves to. */
    public static LibrarySnapshot empty(Path dir) {
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        for (String kind : KIND_MARKER.keySet()) {
            kinds.put(kind, Set.of());
        }
        return new LibrarySnapshot(dir, List.of(), kinds, 0L, List.of());
    }

    /**
     * Read a library off disk. Never throws: a damaged {@code templates.json} costs its entries and
     * is reported in {@link #problems()}, and everything else in the library still serves.
     */
    public static LibrarySnapshot read(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return empty(dir);
        }
        List<String> problems = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        // What only a left-off entry hands out is withheld with it (StarterNeeds.packsNamed).
        Set<String> withheldPacks = new LinkedHashSet<>();
        Set<String> usedPacks = new LinkedHashSet<>();
        Set<String> retired = new LinkedHashSet<>();
        Map<String, String> icons = readIcons(dir.resolve("icons.json"), problems);
        Path templatesFile = dir.resolve("templates.json");
        if (Files.isRegularFile(templatesFile)) {
            try {
                JsonElement root = JsonParser.parseString(
                        Files.readString(templatesFile, StandardCharsets.UTF_8));
                JsonElement arr = root != null && root.isJsonObject()
                        ? root.getAsJsonObject().get("templates") : null;
                if (arr != null && arr.isJsonArray()) {
                    Set<String> seen = new LinkedHashSet<>();
                    for (JsonElement el : arr.getAsJsonArray()) {
                        if (!el.isJsonObject()) {
                            problems.add("a templates.json entry is not an object");
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        String id = o.has("id") && o.get("id").isJsonPrimitive()
                                ? o.get("id").getAsString().trim() : "";
                        if (id.isEmpty()) {
                            problems.add("a templates.json entry has no id");
                            continue;
                        }
                        if (!seen.add(id)) {
                            // FIRST WINS, and it is said out loud. Two curated entries with one id
                            // is an editing mistake in a hand-written file, and silently serving
                            // whichever came last is exactly the kind of thing nobody notices.
                            problems.add("templates.json names '" + id + "' twice — the first wins");
                            continue;
                        }
                        if (o.has("retired") && o.get("retired").isJsonPrimitive()
                                && o.get("retired").getAsBoolean()) {
                            retired.add(id);
                            continue;
                        }
                        if (!StarterNeeds.servable(id, o)) {
                            withheldPacks.addAll(StarterNeeds.packsNamed(o));
                            continue;   // E.g. worn boots on a build that cannot wear them
                        }
                        usedPacks.addAll(StarterNeeds.packsNamed(o));
                        String icon = icons.getOrDefault(id, "");
                        entries.add(new Entry(id, o.toString(), icon.isEmpty() ? iconOf(o) : icon));
                    }
                } else {
                    problems.add("templates.json has no `templates` array");
                }
            } catch (IOException | RuntimeException e) {
                problems.add("templates.json unreadable: " + e);
            }
        }
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        for (Map.Entry<String, String> kind : KIND_MARKER.entrySet()) {
            kinds.put(kind.getKey(), keysUnder(dir.resolve(kind.getKey()), kind.getValue()));
        }
        withheldPacks.removeAll(usedPacks);
        if (!withheldPacks.isEmpty()) {
            for (String kind : List.of("datapacks", "items")) {
                Set<String> keep = new LinkedHashSet<>(kinds.get(kind));
                keep.removeAll(withheldPacks);
                kinds.put(kind, keep);
            }
        }
        LibrarySnapshot snap = new LibrarySnapshot(dir, entries, kinds, fingerprintOf(dir), problems);
        snap.retired = Set.copyOf(retired);
        snap.order = orderOf(dir);
        return snap;
    }

    /** The top-level {@code "order"} of a library's templates.json, or empty. Never throws. */
    static List<String> orderOf(Path dir) {
        if (dir == null) {
            return List.of();
        }
        Path f = dir.resolve("templates.json");
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("order")
                    || !root.getAsJsonObject().get("order").isJsonArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray("order")) {
                if (el.isJsonPrimitive() && !el.getAsString().isBlank()
                        && !out.contains(el.getAsString().trim())) {
                    out.add(el.getAsString().trim());
                }
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The owner's watch order, or empty for the default. */
    public List<String> order() {
        return order;
    }

    /** The ids this library retires — served by no layer below it. */
    public Set<String> retired() {
        return retired;
    }

    /**
     * {@code icons.json}, as template id → normalised item id. Absent is empty; a file that cannot
     * be read is a PROBLEM, said out loud, and every inline icon still serves — a library never
     * throws, and nothing here ever writes the file, so there is no latch to hold.
     */
    static Map<String, String> readIcons(Path file, List<String> problems) {
        Map<String, String> out = new LinkedHashMap<>();
        String text;
        try {
            text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            problems.add("icons.json unreadable: " + e);
            return out;
        }
        com.moddingfromamod.store.WishStoreFile.Read read =
                com.moddingfromamod.store.WishStoreFile.read(text, ICON_STORE, ICON_SCHEMA);
        JsonObject root;
        if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Refused r) {
            problems.add("icons.json refused: " + r.reason());
            return out;
        } else if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Ok ok) {
            root = ok.root();
        } else if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Legacy l) {
            root = l.root();
        } else {
            return out;     // Absent
        }
        JsonElement arr = root.get("icons");
        if (arr == null || !arr.isJsonArray()) {
            problems.add("icons.json has no `icons` array");
            return out;
        }
        for (JsonElement el : arr.getAsJsonArray()) {
            JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : null;
            String id = o != null && o.has("id") && o.get("id").isJsonPrimitive()
                    ? o.get("id").getAsString().trim() : "";
            String item = o != null && o.has("item") && o.get("item").isJsonPrimitive()
                    ? normaliseIcon(o.get("item").getAsString()) : "";
            if (id.isEmpty() || item.isEmpty()) {
                problems.add("an icons.json entry has no id or no usable item");
                continue;
            }
            if (out.putIfAbsent(id, item) != null) {
                problems.add("icons.json names '" + id + "' twice — the first wins");
            }
        }
        return out;
    }

    /** A library entry's inline `icon`, normalised to a namespaced item id, or {@code ""}. */
    public static String iconOf(JsonObject o) {
        if (o == null || !o.has("icon") || !o.get("icon").isJsonPrimitive()) {
            return "";
        }
        return normaliseIcon(o.get("icon").getAsString());
    }

    /**
     * {@code "bricks"} → {@code "minecraft:bricks"}; anything that cannot be an item id → {@code ""}.
     *
     * <p>Refusing the nonsense here rather than at the panel matters: an unresolvable id renders as
     * a missing-model cube on some paths and as nothing at all on others, and a curated row that
     * silently shows nothing is worse than one that falls back to the keyword scan.
     */
    public static String normaliseIcon(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return "";
        }
        if (t.indexOf(':') < 0) {
            t = "minecraft:" + t;
        }
        int colon = t.indexOf(':');
        String ns = t.substring(0, colon);
        String path = t.substring(colon + 1);
        if (ns.isEmpty() || path.isEmpty() || t.indexOf(':', colon + 1) >= 0) {
            return "";
        }
        for (char c : ns.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-'
                    && c != '.') {
                return "";
            }
        }
        for (char c : path.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-'
                    && c != '.' && c != '/') {
                return "";
            }
        }
        return t;
    }

    /**
     * <b>A directory name the mod could actually serve as an id.</b>
     *
     * <p>The same shape {@code ModelRepository.isValidKey} enforces, and it is here because a real
     * authoring run produced the counter-example: a sculpt leaves its undo snapshots beside the
     * model as {@code <key>.undo-<nanos>}, each a complete creature directory with a geometry file.
     * Anything walking the directory sees two creatures; the mod can only ever spawn one of them,
     * because a dot is not a legal key. So a library holding one would offer a key in
     * {@code /wishmob list} that {@code /wishmob spawn} then refuses — a working command that looks
     * like a typo.
     */
    static boolean isServableKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            return false;
        }
        for (char c : key.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static Set<String> keysUnder(Path kindDir, String marker) {
        Set<String> out = new LinkedHashSet<>();
        if (kindDir == null || !Files.isDirectory(kindDir)) {
            return out;
        }
        try (var children = Files.list(kindDir)) {
            for (Path child : children.sorted().toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String key = child.getFileName().toString();
                if (!isServableKey(key)) {
                    continue;
                }
                boolean present = marker.isEmpty()
                        ? Files.isRegularFile(child.resolve(key + ".zip"))
                        : Files.isRegularFile(child.resolve(marker));
                if (present) {
                    out.add(key);
                }
            }
        } catch (IOException ignored) {
            // A listing failure means an empty kind, never a broken library.
        }
        return out;
    }

    /**
     * What the 30-second poll compares: {@code templates.json}'s and {@code icons.json}'s mtime and
     * size, plus every artifact
     * directory's own mtime.
     *
     * <p>Deliberately SHALLOW — the kind directory and each artifact directory, not every file under
     * them. A library is refreshed by a pull, which rewrites whole directories, so their mtimes
     * move; walking the tree on every poll would cost real IO on a server that is doing something
     * else. A file edited in place inside an artifact directory without touching its directory mtime
     * is the known blind spot, and {@code /moddyop library reload} is the answer to it.
     */
    static long fingerprintOf(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        long h = 1125899906842597L;
        // icons.json too, or re-dressing a button — the whole point of splitting it out — would
        // be the one library edit the 30-second poll never noticed.
        for (String name : List.of("templates.json", "icons.json")) {
            Path f = dir.resolve(name);
            try {
                if (Files.isRegularFile(f)) {
                    h = h * 31 + Files.getLastModifiedTime(f).toMillis();
                    h = h * 31 + Files.size(f);
                }
            } catch (IOException ignored) {
                h = h * 31;
            }
        }
        for (String kind : KIND_MARKER.keySet().stream().sorted().toList()) {
            Path kindDir = dir.resolve(kind);
            if (!Files.isDirectory(kindDir)) {
                continue;
            }
            try {
                h = h * 31 + kindDir.getFileName().toString().hashCode();
                h = h * 31 + Files.getLastModifiedTime(kindDir).toMillis();
                try (var children = Files.list(kindDir)) {
                    for (Path child : children.sorted().toList()) {
                        h = h * 31 + child.getFileName().toString().hashCode();
                        h = h * 31 + Files.getLastModifiedTime(child).toMillis();
                    }
                }
            } catch (IOException ignored) {
                h = h * 31;
            }
        }
        return h;
    }

    public Path dir() {
        return dir;
    }

    public List<Entry> templates() {
        return templates;
    }

    public long fingerprint() {
        return fingerprint;
    }

    /** What could not be read, in words. Empty for a clean library. */
    public List<String> problems() {
        return problems;
    }

    public boolean isEmpty() {
        return templates.isEmpty() && keysByKind.values().stream().allMatch(Set::isEmpty);
    }

    /** The curated icon for this recipe id, or {@code ""} — the panel falls back to the keywords. */
    public String iconFor(String id) {
        Entry e = id == null ? null : byId.get(id);
        return e == null ? "" : e.icon();
    }

    public boolean hasTemplate(String id) {
        return id != null && byId.containsKey(id);
    }

    /** The ids this library curates, in file order. */
    public List<String> templateIds() {
        List<String> out = new ArrayList<>();
        for (Entry e : templates) {
            out.add(e.id());
        }
        return List.copyOf(out);
    }

    /** The keys of one artifact kind ({@code species}, {@code buildings}, {@code items}, …). */
    public Set<String> keys(String kind) {
        return keysByKind.getOrDefault(kind, Set.of());
    }

    public boolean has(String kind, String key) {
        return key != null && keys(kind).contains(key);
    }

    /**
     * {@code <libraryDir>/<kind>/<key>}, whether or not it exists. Null for an unplaced library.
     *
     * <p>For a {@link #layered} snapshot, the directory of the FIRST layer that holds the key — so an
     * owner's artifact wins over a starter one of the same key, exactly as its recipe does.
     */
    public Path artifactDir(String kind, String key) {
        if (kind == null || key == null || key.isBlank()) {
            return null;
        }
        if (homes != null) {
            Path home = homes.getOrDefault(kind, Map.of()).get(key);
            if (home != null) {
                return home.resolve(kind).resolve(key);
            }
        }
        if (dir == null) {
            return null;
        }
        return dir.resolve(kind).resolve(key);
    }

    /**
     * <b>Several libraries read as one, the first winning</b> — the owner's library over the
     * starter library the jar ships.
     *
     * <p>The starter nine are served as cached wishes through THIS path and no other (one
     * path for everything). So they are a library in their own right, bundled in the jar and
     * unpacked at boot, and they sit UNDER the owner's library: an owner entry with a starter's id
     * replaces it — recipe, artifact and icon alike — and a starter can be retired by the owner
     * without a new jar.
     *
     * <p>Per id and per artifact key, the first layer that has it wins; an {@code icons.json} in an
     * earlier layer re-dresses a later layer's entry, so the owner can re-draw a starter button
     * without copying its recipe. Recipes keep their layer order, so the panel shows the owner's
     * pinned choices first and the starter nine after them. Two entries with one id in the SAME
     * layer is still an editing mistake, and still reported; one id across layers is the override
     * working, and is not.
     */
    public static LibrarySnapshot layered(List<Path> dirs) {
        List<Path> present = new ArrayList<>();
        for (Path d : dirs) {
            if (d != null) {
                present.add(d);
            }
        }
        if (present.isEmpty()) {
            return empty(null);
        }
        if (present.size() == 1) {
            return read(present.get(0));
        }
        List<LibrarySnapshot> layers = new ArrayList<>();
        Map<String, String> icons = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Path d : present) {
            LibrarySnapshot one = read(d);
            layers.add(one);
            for (String p : one.problems()) {
                problems.add(d.getFileName() + ": " + p);
            }
            if (Files.isDirectory(d)) {
                readIcons(d.resolve("icons.json"), new ArrayList<>()).forEach(icons::putIfAbsent);
            }
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Map<String, Path>> homes = new LinkedHashMap<>();
        long h = 1125899906842597L;
        for (LibrarySnapshot one : layers) {
            seen.addAll(one.retired());   // A retired id shadows every later layer's recipe
            for (Entry e : one.templates()) {
                if (seen.add(e.id())) {
                    String icon = icons.getOrDefault(e.id(), "");
                    entries.add(new Entry(e.id(), e.json(), icon.isEmpty() ? e.icon() : icon));
                }
            }
            for (String kind : KIND_MARKER.keySet()) {
                for (String key : one.keys(kind)) {
                    homes.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, one.dir());
                }
            }
            h = h * 31 + one.fingerprint();
        }
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        for (String kind : KIND_MARKER.keySet()) {
            kinds.put(kind, homes.getOrDefault(kind, Map.of()).keySet());
        }
        LibrarySnapshot out = new LibrarySnapshot(present.get(0), entries, kinds, h, problems, homes);
        // The first layer with an order decides it: the owner's library sits above the starters.
        for (LibrarySnapshot one : layers) {
            if (!one.order().isEmpty()) {
                out.order = one.order();
                break;
            }
        }
        return out;
    }

    /** The curated templates as one {@code {"templates":[…]}} array, for the matcher to fold in. */
    public JsonArray templatesJson() {
        JsonArray arr = new JsonArray();
        for (Entry e : templates) {
            try {
                arr.add(JsonParser.parseString(e.json()).getAsJsonObject());
            } catch (RuntimeException ignored) {
                // Cannot happen — it parsed on the way in — but a library must never throw.
            }
        }
        return arr;
    }

    /** One line for a log or an RCON answer. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(templates.size()).append(" template(s)");
        for (String kind : KIND_MARKER.keySet().stream().sorted().toList()) {
            sb.append(", ").append(keys(kind).size()).append(' ').append(kind);
        }
        if (!problems.isEmpty()) {
            sb.append(" (").append(problems.size()).append(" problem(s): ")
              .append(String.join("; ", problems)).append(')');
        }
        return sb.toString();
    }
}
