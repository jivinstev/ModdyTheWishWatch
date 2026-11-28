package com.moddingfromamod.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.provenance.WishProvenance;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>{@code /moddyop promote <key>} — a child's fork becomes everybody's</b> (plan §9 item 5).
 *
 * <p>The in-game half of a library promotion: it writes three things into the
 * library layout — the artifact's files, a {@code manifest.json} in
 * {@link WishProvenance}'s shape, and a row in {@code templates.json} — so a thing promoted from
 * the console and a thing promoted by a script from the dev machine are indistinguishable afterwards,
 * and a {@code git commit} over the library directory covers either.
 *
 * <p>This reads the LIVE WORLD, which is the only place a child's fork exists at all: a
 * {@code spikor~1f2e3d4c} made by a wish on a server was never a candidate anywhere else.
 *
 * <h2>Promotion is a human step, and stays one</h2>
 *
 * <p>Nothing here is reachable by a child or by the agent: {@code /moddyop} is permission level 3,
 * which is what an RCON console has and a player does not. A promoted creature is content served to
 * <i>other people's children</i>, and on a server its operator is the moderation queue.
 *
 * <h2>Three refusals, each of which is a refusal rather than a warning</h2>
 *
 * <ul>
 *   <li><b>An id the library already serves is never replaced</b> without {@code --force}'s
 *       equivalent here, {@code promote <key> as <id> force}. It is somebody's wish already being
 *       served and replacing one by accident cannot be undone from in-game.</li>
 *   <li><b>The id is cleaned of its fork suffix</b> unless one is given: {@code spikor~1f2e3d4c}
 *       promotes as {@code spikor}. A library id carrying a child's uuid would put his name — and
 *       an artifact key nothing else can derive — in front of every other child.</li>
 *   <li><b>A creature with no geometry is refused.</b> {@code LibrarySnapshot} proves a species
 *       directory by {@code geometry.json}, so a promotion that copied a record and no art would
 *       write a library entry the library itself cannot see.</li>
 * </ul>
 */
public final class LibraryPromotion {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Same list, same order, as {@code CreatureFork} — the files a creature's directory holds. */
    private static final String[] SPECIES_FILES = {
        "geometry.json", "texture.png", "sculpt.json", "species.json"
    };

    /** What a promotion did, as one line for RCON plus a flag so a caller can tell red from green. */
    public record Result(boolean ok, String line) {
    }

    private LibraryPromotion() {
    }

    /**
     * Promote one creature into the library.
     *
     * @param key   the world's key — normally a fork, e.g. {@code spikor~1f2e3d4c}
     * @param asId  the library id to promote it under, or {@code ""} to use the fork's base name
     * @param force replace an id the library already serves
     */
    public static Result promote(MinecraftServer server, String key, String asId, boolean force) {
        if (key == null || key.isBlank()) {
            return new Result(false, "promote: name the thing to promote, e.g. /moddyop promote"
                    + " spikor~1f2e3d4c");
        }
        Species species = SpeciesRegistry.get(key);
        if (species == null) {
            return new Result(false, "promote: this world has no creature called '" + key + "'");
        }
        String id = asId == null || asId.isBlank() ? ArtifactOwner.baseOf(key) : asId.trim();
        if (!ModelRepository.isValidKey(id) || ArtifactOwner.isFork(id)) {
            return new Result(false, "promote: '" + id + "' is not a library id — it must be"
                    + " lower-case letters, digits, _ or -, and must not carry a fork suffix");
        }
        if (com.moddingfromamod.store.SafeFileName.isWindowsReserved(id)) {
            // the library ships to every player, and on Windows species/con/ is a device, not a folder
            return new Result(false, "promote: '" + id + "' is a name Windows keeps for a device (con, nul, aux,"
                    + " prn, com1, lpt1...); pick another library id with as=<id>");
        }
        Path library = WishLibrary.dir();
        Path dest = library.resolve("species").resolve(id);
        if (Files.exists(dest) && !force) {
            return new Result(false, "promote: the library already serves a creature called '" + id
                    + "'. That is somebody's wish already being served — add 'force' only if you"
                    + " mean to replace it.");
        }
        List<String> copied = copySpecies(species.modelKey().isEmpty() ? key : species.modelKey(),
                dest);
        if (!copied.contains("geometry.json")) {
            return new Result(false, "promote: '" + key + "' has no geometry.json, so the library"
                    + " could not see it even once it was copied — nothing was written");
        }
        try {
            writeSpeciesRecord(dest, species, id);
            writeManifest(dest, id, key, species);
            mergeTemplate(library, id, species);
        } catch (IOException | RuntimeException e) {
            return new Result(false, "promote: could not finish writing '" + id + "' — "
                    + e);
        }
        LOGGER.info("promoted '{}' into the wish library as '{}' ({} file(s))", key, id,
                copied.size());
        // The funnel's last step, in the action log where an operator's tools read it.
        com.moddingfromamod.agent.ActionLog.promotion(key, id, "moddyop", force);
        // AND SERVE IT NOW. Promotion that needed a `library reload` afterwards would be two steps
        // an operator has to remember in order, and the second one is the one that is forgotten —
        // the library is hot-reloadable precisely so this can be one action.
        String summary = LibraryService.reloadNow(server);
        return new Result(true, "promoted '" + key + "' as '" + id + "'; " + summary);
    }

    /** Copy a creature's art. Returns which files were really written. */
    private static List<String> copySpecies(String modelKey, Path dest) {
        List<String> written = new ArrayList<>();
        for (String name : SPECIES_FILES) {
            Path src = ModelRepository.readable(modelKey, name);
            if (src == null) {
                continue;
            }
            try {
                Files.createDirectories(dest);
                Files.copy(src, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                written.add(name);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("promote: could not copy {} — {}", name, e.toString());
            }
        }
        return written;
    }

    /**
     * The creature's own record, under the LIBRARY id and with <b>no owner</b>.
     *
     * <p>Both halves matter. A promoted record still carrying {@code spikor~1f2e3d4c} would spawn
     * under a name the library does not serve; and one still carrying the child's uuid would be
     * read by everything downstream as HIS — so his brother's panel would hide the very thing the
     * owner just promoted for everybody, and a refinement of it would fork rather than edit.
     * Promotion is the one operation that deliberately CLEARS an owner.
     */
    private static void writeSpeciesRecord(Path dest, Species species, String id)
            throws IOException {
        Species shared = species.forkedAs(id, id, "");
        Files.writeString(dest.resolve("species.json"),
                GSON.toJson(shared.toJson()), StandardCharsets.UTF_8);
    }

    /** Provenance in {@link WishProvenance}'s shape — the same one {@code promote.py} writes. */
    private static void writeManifest(Path dest, String id, String fromKey, Species species)
            throws IOException {
        String intent = "promoted from the world's '" + fromKey + "'"
                + (species.name().isBlank() ? "" : " (" + species.name() + ")");
        // THE MODEL STAMP TRAVELS WITH IT: whatever this server recorded for the world's
        // key goes into the library's manifest, so a promoted wish says which model made it for as
        // long as the library serves it. None recorded = granted before the stamp, and it says so
        // by carrying no `grants` rather than one guessed from today's configuration.
        WishProvenance prov = WishProvenance.first(id, "species", intent,
                "promoted into the curated library by an operator", System.currentTimeMillis(),
                "species/" + id + "/geometry.json")
                // this server's ledger AND the owner's shelf — a creature made on another
                // server has its grants only on the shelf.
                .withGrants(com.moddingfromamod.provenance.ShelfProvenance.grantsFor(
                        com.moddingfromamod.provenance.StampLedger.file(),
                        com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                        species.ownerId(), "species", fromKey));
        Files.writeString(dest.resolve("manifest.json"),
                GSON.toJson(prov.toJson()), StandardCharsets.UTF_8);
    }

    /**
     * Merge a button for it into {@code templates.json}, replacing any row with the same id.
     *
     * <p>Read-modify-write of the whole file rather than an append, because that file is a JSON
     * document and because {@code LibrarySnapshot} takes the FIRST row for an id and says so out
     * loud — leaving a duplicate would produce a warning on every reload for as long as it sat
     * there.
     */
    private static void mergeTemplate(Path library, String id, Species species) throws IOException {
        Path file = library.resolve("templates.json");
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        if (Files.isRegularFile(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (parsed != null && parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                    JsonElement had = root.get("templates");
                    if (had != null && had.isJsonArray()) {
                        for (JsonElement el : had.getAsJsonArray()) {
                            if (!(el.isJsonObject() && el.getAsJsonObject().has("id")
                                    && id.equals(el.getAsJsonObject().get("id").getAsString()))) {
                                rows.add(el);
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("templates.json is not readable JSON: " + e, e);
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        JsonArray all = new JsonArray();
        all.add(id.replace('_', ' '));
        row.add("all", all);
        JsonArray commands = new JsonArray();
        commands.add("wishmob spawn " + id);
        row.add("commands", commands);
        row.addProperty("say", "Here is your " + species.name() + "!");
        row.addProperty("label", species.name().isBlank()
                ? com.moddingfromamod.wish.WishPresets.labelFor(id) : species.name());
        rows.add(row);
        root.add("templates", rows);
        Files.createDirectories(library);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }
}
