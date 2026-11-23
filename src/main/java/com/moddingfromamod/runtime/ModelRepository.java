package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The on-disk library of custom creature shapes. One folder per creature id under
 * {@link RuntimeConfig#modelDir()}:
 *
 * <pre>
 *   &lt;modelDir&gt;/&lt;key&gt;/geometry.json   ← Blockbench "Bedrock" export (or a GeckoLib .geo.json)
 *   &lt;modelDir&gt;/&lt;key&gt;/texture.png     ← the skin
 *   &lt;modelDir&gt;/&lt;key&gt;/project.bbmodel ← the editable source, kept so "make it cuter" re-edits it
 *   &lt;modelDir&gt;/&lt;key&gt;/species.json    ← optional stat overrides
 * </pre>
 *
 * <p>Parsed geometry is cached and <b>keyed on the file's last-modified time</b>, which is the whole
 * trick behind live iteration: when Blockbench re-exports over {@code geometry.json}, the mtime moves,
 * the cache misses, and the very next frame draws the new shape — no restart, no resource reload.
 *
 * <p>Pure-ish: this reads files and parses JSON but imports no Minecraft, so a headless test can
 * point {@code moddingfromamod.modelDir} at a temp folder and exercise the whole resolve/parse/cache
 * path (see {@code ModelRepositoryTest}).
 */
public final class ModelRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private ModelRepository() {
    }

    private record Cached(long mtime, BedrockGeometry geometry) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * Ids may only be a safe path segment; anything else can't name a real model.
     *
     * <p>{@code ~} is in the alphabet because it is the fork separator
     * ({@link com.moddingfromamod.marshal.ArtifactOwner}), and a fork's key IS its model directory.
     * It cannot form {@code ..} or a separator, so it escapes a directory no more than {@code -}
     * does — and leaving it out would have meant two spellings of one key, since
     * {@code Species}'s {@code sanitizeKey} silently DROPS a character it does not like rather
     * than refusing the key.
     */
    public static boolean isValidKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            return false;
        }
        for (char c : key.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == com.moddingfromamod.marshal.ArtifactOwner.FORK;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * The folder a creature's files are WRITTEN to (and read from first): its owner's shelf when
     * the creature is a child's, else this world's model folder.
     */
    public static Path dir(String key) {
        Path shelf = shelfDir(key);
        return shelf != null ? shelf : RuntimeConfig.modelDir().resolve(key);
    }

    // ── A child's models live on his shelf, beside his creatures ─────────────────────────

    /** Model key to owner, for every owned creature the species store knows about. */
    private static volatile Map<String, String> shelved = Map.of();

    /**
     * {@code <players>/<owner>/models/<key>} for a child's creature, or null.
     *
     * <p>Null whenever {@code modelDir} is configured explicitly: that override is how a test points
     * this whole class at one temp folder, and splitting its writes across a second root would make
     * every such test's "the file is in the folder I gave you" wrong.
     */
    static Path shelfDir(String key) {
        if (RuntimeConfig.modelDirOverride() != null || key == null) {
            return null;
        }
        String owner = shelved.get(key);
        return owner == null ? null
                : RuntimeConfig.playersDir().resolve(owner).resolve("models").resolve(key);
    }

    /** Forget whose each model is — the world closed. Moves nothing. */
    public static void forgetShelved() {
        shelved = Map.of();
    }

    /** The shelved keys that have something drawable — for {@code /wishmob} suggestions. */
    public static List<String> shelvedKeys() {
        List<String> out = new java.util.ArrayList<>();
        for (String key : shelved.keySet()) {
            Path d = shelfDir(key);
            if (d != null && Files.isRegularFile(d.resolve("geometry.json"))) {
                out.add(key);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * <b>Tell the repository whose each model is, and move any still in the world onto its
     * owner's shelf</b> — called by {@code SpeciesRegistry} after it loads and after every save.
     *
     * <p>The move is how a world from before shelves migrates, and it is also the ordinary path:
     * {@code sculpt_creature} writes the model BEFORE the species record that names its owner
     * exists, so the first files of a new creature land in the world folder and are carried over
     * here the moment the record is saved. A shelf folder that already exists wins and the world
     * copy is left where it is — the shelf is what {@link #dir} now reads, and deleting a folder
     * that might be the only copy of something is not this method's call.
     */
    public static synchronized void shelveOwnedModels(Map<String, String> ownerByKey) {
        shelved = Map.copyOf(ownerByKey);
        if (RuntimeConfig.modelDirOverride() != null) {
            return;
        }
        Path world = WorldPaths.modelDir();
        if (world == null) {
            return;
        }
        int moved = 0;
        for (var e : shelved.entrySet()) {
            if (!isValidKey(e.getKey())
                    || !com.moddingfromamod.store.PlayerShelf.isShelvable(e.getValue())) {
                continue;
            }
            Path from = world.resolve(e.getKey());
            Path to = shelfDir(e.getKey());
            if (!Files.isDirectory(from) || to == null || Files.exists(to)) {
                continue;
            }
            try {
                Files.createDirectories(to.getParent());
                try {
                    Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException crossDevice) {
                    copyTree(from, to);
                    deleteTree(from);
                }
                moved++;
            } catch (IOException ex) {
                LOGGER.warn("could not move the model for '{}' onto its owner's shelf: {}",
                        e.getKey(), ex.toString());
            }
        }
        if (moved > 0) {
            clearCache();
            LOGGER.info("moved {} creature model(s) onto their owners' shelves", moved);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dest = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public static Path geometryFile(String key) {
        return dir(key).resolve("geometry.json");
    }

    public static Path textureFile(String key) {
        return dir(key).resolve("texture.png");
    }

    public static Path projectFile(String key) {
        return dir(key).resolve("project.bbmodel");
    }

    public static Path speciesFile(String key) {
        return dir(key).resolve("species.json");
    }

    /**
     * <b>Where to READ one of a creature's files from: this world first, then the library</b>.
     *
     * <p>{@link #dir} stays the world's own folder and is the only place anything WRITES. This is
     * the read path, and it has exactly one extra step: a key this world has never seen resolves
     * out of {@code <libraryDir>/species/<key>/}, the server owner's curated set, read-only. That is what
     * makes {@code /wishmob spawn spikor} work in a world opened five minutes ago, which is the
     * exit criterion the whole library exists for — the published Mod-Repo fallback below already
     * did this for the SPEC and could not do it for the model.
     *
     * <p>Returns null when neither has the file, so a caller can tell "no such creature" from "a
     * creature with no texture" instead of getting a path that was never going to exist.
     */
    public static Path readable(String key, String filename) {
        if (!isValidKey(key) || filename == null || filename.isBlank()) {
            return null;
        }
        Path own = dir(key).resolve(filename);
        if (Files.isRegularFile(own)) {
            return own;
        }
        return com.moddingfromamod.library.WishLibrary.artifactFile("species", key, filename);
    }

    /** The geometry to DRAW: this world's, else the library's. Null when there is none. */
    public static Path readableGeometryFile(String key) {
        return readable(key, "geometry.json");
    }

    /** The skin to draw: this world's, else the library's. Null when there is none. */
    public static Path readableTextureFile(String key) {
        return readable(key, "texture.png");
    }

    /**
     * The clip file for a key. Blockbench exports {@code <name>.animation.json}; we prefer a plain
     * {@code animations.json} but accept whatever {@code *.animation.json} is in the folder, because
     * the export name follows the project name and nobody should have to rename a file by hand.
     */
    public static Path animationFile(String key) {
        Path preferred = dir(key).resolve("animations.json");
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        Path exported = null;
        try (java.util.stream.Stream<Path> s = Files.list(dir(key))) {
            exported = s.filter(p -> p.getFileName().toString().endsWith(".animation.json"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            // no folder in this world: fall through
        }
        if (exported != null) {
            return exported;
        }
        // This world has no clips for the key: read the library's, exactly as the geometry, skin and
        // species already are. Since the curated library landed, the library read path covered every
        // creature file EXCEPT this one, so starters and other library creatures spawned with no
        // keyframe clips while a creature wished live in the world (CreatureFiles writes its
        // animations.json) had them. Library creatures now load the same files a live wish writes.
        Path lib = readable(key, "animations.json");
        return lib != null ? lib : preferred;
    }

    /** True when there's at least a geometry file to draw for this key. */
    public static boolean has(String key) {
        return readableGeometryFile(key) != null;
    }

    /**
     * Parse (or return cached) geometry for a key. Returns {@code null} when the key is invalid, the
     * file is missing, or the JSON has nothing drawable — callers fall back to a vanilla body, never
     * a crash.
     */
    public static BedrockGeometry geometry(String key) {
        if (!isValidKey(key)) {
            return null;
        }
        Path f = readableGeometryFile(key);
        long mtime;
        try {
            if (f == null || !Files.isRegularFile(f)) {
                CACHE.remove(key);
                return null;
            }
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return null;
        }
        Cached c = CACHE.get(key);
        if (c != null && c.mtime() == mtime) {
            return c.geometry();
        }
        BedrockGeometry g = parse(f);
        // Cache even a null parse (as an empty marker) so we don't re-read a broken file every frame.
        CACHE.put(key, new Cached(mtime, g));
        return g;
    }

    private static BedrockGeometry parse(Path f) {
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            BedrockGeometry g = BedrockGeometry.fromJson(root);
            if (g == null) {
                LOGGER.warn("custom model at {} had no drawable geometry", f);
            }
            return g;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read custom geometry {}: {}", f, e.toString());
            return null;
        }
    }

    private record CachedAnims(long mtime, Map<String, BedrockAnimation> clips) {
    }

    private static final Map<String, CachedAnims> ANIM_CACHE = new ConcurrentHashMap<>();

    /**
     * The animation clips for a key, or an empty map when the model has none (a statue is a valid
     * creature). Cached on the file's mtime like the geometry, so re-exporting a clip from Blockbench
     * takes effect on the next frame.
     */
    public static Map<String, BedrockAnimation> animations(String key) {
        if (!isValidKey(key)) {
            return BedrockAnimation.none();
        }
        Path f = animationFile(key);
        long mtime;
        try {
            if (!Files.isRegularFile(f)) {
                ANIM_CACHE.remove(key);
                return BedrockAnimation.none();
            }
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return BedrockAnimation.none();
        }
        CachedAnims c = ANIM_CACHE.get(key);
        if (c != null && c.mtime() == mtime) {
            return c.clips();
        }
        Map<String, BedrockAnimation> clips;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (AttackClipMigration.migrate(root)) {
                mtime = writeMigrated(key, f, root, mtime);
            }
            clips = BedrockAnimation.parseAll(root);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read animations {}: {}", f, e.toString());
            clips = BedrockAnimation.none();
        }
        ANIM_CACHE.put(key, new CachedAnims(mtime, clips));
        return clips;
    }

    /**
     * write a migrated clip file back where the creature LIVES (this world's or a child's shelf folder), so the
     * old attack is rewritten once; a library file is the owner's and is migrated in memory only. Returns the mtime
     * the cache should key on.
     */
    private static long writeMigrated(String key, Path f, JsonObject root, long mtime) {
        if (!f.toAbsolutePath().normalize().startsWith(dir(key).toAbsolutePath().normalize())) {
            return mtime;
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("migrated the old attack clip in {} (#2724)", f);
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            LOGGER.warn("could not write the migrated attack clip to {}: {}", f, e.toString());
            return mtime;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a leftover temp file is harmless
            }
        }
    }

    /** Read a per-model {@code species.json} if present (stat overrides authored alongside the art). */
    public static JsonObject speciesOverrides(String key) {
        if (!isValidKey(key)) {
            return null;
        }
        Path f = readable(key, "species.json");
        try {
            if (f != null && Files.isRegularFile(f)) {
                return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read species overrides {}: {}", f, e.toString());
        }
        return null;
    }

    /** The editable {@code sculpt.json} spec beside a model, or null. */
    public static Path sculptFile(String key) {
        return dir(key).resolve("sculpt.json");
    }

    /**
     * The parts a creature was sculpted from, for use as a base by another creature.
     *
     * <p>Read from the saved spec rather than reconstructed from the baked geometry, so a base is
     * <b>lossless</b> — the exact list the agent originally passed, with its rotations, patterns and
     * parents intact. Reconstructing from geometry would mean sampling colours back out of a texture
     * and guessing which cube belonged to which bone.
     *
     * <p>Returns an empty list for anything with no spec — an imported vanilla model, or a creature
     * predating the spec being saved. Never throws: a base that cannot be read must degrade to
     * "build it from scratch", not fail the wish.
     */
    /**
     * <b>The creature as it is DRAWN</b>: {@link #sculptedParts}, turned the right way round.
     *
     * <p>The spec is the agent's input, and the agent builds a face at +Z about one creature in
     * five. The baked model has always been put right by {@link SculptOrientation#faceForward} on
     * the way through {@code SculptCreatureTool.withKits} -- but the rig, which is every
     * creature a child meets, was built from {@link #sculptedParts} and skipped it. So every
     * creature built face-at-+Z walked tail-first, aimed its breath behind it, and the wish
     * camera's "face" photograph was of the back of its head (9 of 26 creatures on one
     * contact sheet). Use this for anything that PLACES the creature in the world; keep
     * {@link #sculptedParts} for anything that edits or paints the agent's source, which must stay
     * in the space the agent wrote it in.
     */
    public static List<CreatureSculpt.Part> drawnParts(String key) {
        // a creature saved hangs its claws, hands and paws as roots beside the limb, and its
        // clips leave them in the air. adopts them at sculpt time only; this adopts them where the creature
        // is PLACED, so every saved creature is drawn whole without rewriting its source. The same rule, and a
        // no-op on a creature already adopted (an adopted part is no longer a root).
        return SculptLimbAdoption.adopt(SculptOrientation.faceForward(sculptedParts(key)));
    }

    public static List<CreatureSculpt.Part> sculptedParts(String key) {
        if (!isValidKey(key)) {
            return List.of();
        }
        // (the source resolution is sculptSource: this world, then the library, then the Mod Repo)
        Path f = sculptSource(key);
        if (f == null) {
            return List.of();
        }
        try {
            JsonObject spec = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!spec.has("parts") || !spec.get("parts").isJsonArray()) {
                return List.of();
            }
            List<CreatureSculpt.Part> parts =
                    CreatureSculpt.parseParts(spec.getAsJsonArray("parts"));
            // A BASE HAS TO BE WHAT THE CREATURE ACTUALLY IS.
            //
            // The spec stores the agent's INPUT -- its hand-written parts plus a `kits` directive --
            // and kit expansion happens afterwards, on the way to the baked geometry. So a
            // kit-built creature has its spikes in the MODEL and not in this array. Reading `parts`
            // alone made "another Spikor but blue" come back with no spikes, no claws and no shell,
            // and nothing said so; the creature the child pointed at and the creature the agent
            // received were different animals.
            //
            // Expanded here rather than persisted, so the spec stays the re-runnable source it
            // promises to be and a kit-built creature's file does not double in size.
            if (spec.has("kits") && spec.get("kits").isJsonArray()) {
                List<CreatureSculpt.Part> grown = new java.util.ArrayList<>(parts);
                grown.addAll(SculptKits.expand(spec.getAsJsonArray("kits"), parts));
                return grown;
            }
            return parts;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read sculpt spec {}: {}", f, e.toString());
            return List.of();
        }
    }

    /**
     * <b>What colour a creature actually is</b>, biggest share first, as {@code "orange 55%"}.
     *
     * <p>Measured need, not a nicety. {@code inspect_creature} reported height, width and
     * every part position and said nothing about colour, so a worker that had not personally
     * sculpted the thing asked what it was, was told its shape, then had to declare a palette for
     * {@code sculpt_creature} and invented one. On an invented creature that is fatal: two of three
     * cross-worker ladders repainted a bright-orange Snorble white, and never recovered.
     *
     * <p>Same source and same published fallback as {@link #sculptedParts}, so it works in a world
     * that did not make the creature. Empty for anything with no spec; never throws, because a
     * missing colour must degrade to today's behaviour rather than fail an inspection.
     */
    public static List<String> palette(String key) {
        if (!isValidKey(key)) {
            return List.of();
        }
        Path f = sculptSource(key);
        if (f == null) {
            return List.of();
        }
        try {
            JsonObject spec = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!spec.has("palette") || !spec.get("palette").isJsonArray()) {
                return List.of();
            }
            record Share(String colour, int percent) { }
            List<Share> shares = new java.util.ArrayList<>();
            for (var e : spec.getAsJsonArray("palette")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject c = e.getAsJsonObject();
                String colour = c.has("colour") ? c.get("colour").getAsString()
                        : c.has("color") ? c.get("color").getAsString() : "";
                if (colour.isBlank()) {
                    continue;
                }
                int pct = 0;
                try {
                    pct = c.has("percent") ? c.get("percent").getAsInt() : 0;
                } catch (RuntimeException ignored) {
                    pct = 0;
                }
                shares.add(new Share(colour.strip(), pct));
            }
            // Biggest first: "what colour IS it" is answered by the dominant share, and an agent
            // skimming a list takes the first thing it reads.
            shares.sort((a, b) -> Integer.compare(b.percent(), a.percent()));
            List<String> out = new java.util.ArrayList<>();
            for (Share sh : shares) {
                out.add(sh.percent() > 0 ? sh.colour() + " " + sh.percent() + "%" : sh.colour());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read palette from {}: {}", f, e.toString());
            return List.of();
        }
    }

    /**
     * <b>The three places a creature's spec can come from, in order: this world, the curated
     * library, the published Mod Repo.</b> Null when none of them has one.
     *
     * <p>One method so the two readers cannot drift — {@link #sculptedParts} and {@link #palette}
     * each had their own copy of the two-step version and the comment explaining it, which is
     * exactly how a third step gets added to one of them. Every step is read-only: a sculpt writes
     * to the world and nowhere else, so the library stays "a source of bases, never a write target"
     * however a refinement goes.
     */
    public static Path sculptSource(String key) {
        if (!isValidKey(key)) {
            return null;
        }
        Path own = sculptFile(key);
        if (Files.isRegularFile(own)) {
            return own;
        }
        Path library = com.moddingfromamod.library.WishLibrary.artifactFile(
                "species", key, "sculpt.json");
        if (library != null) {
            return library;
        }
        // THE PUBLISHED ARCHIVE. Every granted species wish is published to the Mod Repo as
        // wishes/species/<id>/sculpt.json, which is a real parts spec and exactly the thing a child
        // most wants to riff on ("another troll but blue"). The models directory is per-WORLD, so
        // without this a new world can see none of the creatures already made -- the specs existed
        // and were simply out of reach.
        Path published = publishedSculptFile(key);
        return published != null && Files.isRegularFile(published) ? published : null;
    }

    /**
     * Where a granted species wish was published, or null when no Mod Repo is configured.
     *
     * <p>Read-only from here: this is a fallback source of bases, never a write target.
     */
    public static Path publishedSculptFile(String key) {
        Path repo = RuntimeConfig.persistDir();
        if (repo == null) {
            return null;
        }
        return repo.resolve("wishes").resolve("species").resolve(key).resolve("sculpt.json");
    }

    /** For diagnostics/tests. */
    public static void clearCache() {
        CACHE.clear();
    }

    public static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        // A FORK KEY IS KEPT WHOLE. `<base>~<who>` is a valid key (isValidKey) and the
        // only spelling ForkPolicy recognises as his copy; stripping the '~' stored every
        // sculpt_creature fork under "pet_dragon50992296", which the tool then told the agent to
        // call "pet_dragon~50992296", no carrier could be pointed at, and the NEXT reshape did not
        // find — so it started from the original again.
        String asIs = raw.trim().toLowerCase(Locale.ROOT);
        if (isValidKey(asIs) && com.moddingfromamod.marshal.ArtifactOwner.isFork(asIs)) {
            return asIs;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
            if (sb.length() >= 64) {
                break;
            }
        }
        return sb.toString();
    }
}
