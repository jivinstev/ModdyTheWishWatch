package com.moddingfromamod.provenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a wish Artifact's {@link WishProvenance} into the configured Mod Repo at
 * {@code <repo>/wishes/<kind>/<id>/manifest.json}. First grant writes the intent; a later grant of an
 * id that already has a manifest appends a refinement.
 *
 * <p><b>Layering:</b> the target is {@link RuntimeConfig#persistDir()} — the machine-local Mod Repo,
 * never a hardcoded path. If no repo is configured this is a quiet no-op (the wish still works; it
 * just isn't published). Best-effort: a publish failure never breaks a grant.
 */
public final class ProvenancePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ProvenancePublisher() {
    }

    /**
     * <b>THE one call every persisting wish Kind makes.</b> Writes the artifact's editable source
     * beside its manifest AND records provenance (intent + design note) in a single step, using the
     * wish + design note currently being granted ({@link ProvenanceContext}). Future wish types
     * should call this and nothing else. No-op with no repo configured; best-effort.
     *
     * @param kind     the provenance kind (e.g. {@code build}, {@code species}) — add it to
     *                 {@link ProvenanceContract#PUBLISHING_KINDS}
     * @param id       the artifact id
     * @param filename the editable-source filename (e.g. {@code build.json}, {@code sculpt.json})
     * @param content  the editable source itself (the design the agent authored, as JSON/text)
     */
    public static boolean publish(String kind, String id, String filename, String content) {
        boolean wrote = writeArtifact(kind, id, filename, content);
        boolean recorded = record(kind, id, "wishes/" + kind + "/" + id + "/" + filename);
        return wrote && recorded;
    }

    /**
     * Publish with an EXPLICIT intent and note, rather than reading whichever wish happens to be
     * in {@link ProvenanceContext} at this instant.
     *
     * <p><b>Why this overload exists.</b> The context holds the current wish in static
     * fields. One agent grants one wish at a time, so that is accurate in a real session — but it
     * is a shared global, and anything else running concurrently can move it between a caller's
     * {@code begin()} and its {@code publish()}. A GameTest that set its own intent two lines
     * earlier read back {@code "make a wither storm"} from an unrelated test, and provenance is
     * precisely the record that must not be able to attribute one wish's reasoning to another
     * wish's artifact.
     *
     * <p>Callers that KNOW their intent should say so. The global remains for the agent path,
     * where the wish really is ambient.
     */
    public static boolean publish(String kind, String id, String filename, String content,
                                  String intent, String note) {
        boolean wrote = writeArtifact(kind, id, filename, content);
        // whose it is, so its model stamp can go on his shelf. The artifact's own JSON when
        // it says (a building, an item); else the wish being granted, which is how the stores
        // themselves stamp an owner (SpeciesRegistry) — a sculpt spec is parts and names nobody.
        String owner = ShelfProvenance.ownerOf(content);
        if (owner.isBlank()) {
            owner = com.moddingfromamod.marshal.ArtifactOwner.normalise(
                    com.moddingfromamod.agent.AgentContext.currentPlayerId());
        }
        boolean recorded = record(kind, id, "wishes/" + kind + "/" + id + "/" + filename,
                intent, note, owner);
        return wrote && recorded;
    }

    /** Record provenance using the wish + design note currently being granted ({@link ProvenanceContext}). */
    public static boolean record(String kind, String id, String artifactRef) {
        return record(kind, id, artifactRef, ProvenanceContext.currentIntent(),
                ProvenanceContext.currentNote());
    }

    /** Record with an explicit intent; the design note still comes from the {@link ProvenanceContext}. */
    public static boolean record(String kind, String id, String artifactRef, String intent) {
        return record(kind, id, artifactRef, intent, ProvenanceContext.currentNote());
    }

    /**
     * Record provenance with an explicit intent + design note (the testable seam). First time →
     * writes intent + note; an existing manifest → appends {@code intent} as a refinement and fills
     * the note only if it was blank. Returns true if written.
     */
    public static boolean record(String kind, String id, String artifactRef, String intent,
            String note) {
        return record(kind, id, artifactRef, intent, note, "");
    }

    /**
     * As {@link #record(String, String, String, String, String)}, and when {@code owner} names a
     * child the model stamp is ALSO written to his shelf ({@link ShelfProvenance}), so it
     * follows the thing to another world or server. Blank owner = a shared thing: server ledger only.
     */
    public static boolean record(String kind, String id, String artifactRef, String intent,
            String note, String owner) {
        if (kind == null || kind.isBlank() || id == null || id.isBlank()) {
            return false;
        }
        // WHICH MODEL MADE IT, on EVERY server — before the Mod Repo check below, which is
        // false on the home server and would otherwise leave every real grant unstamped.
        ModelStamp stamp = ModelStamp.current();
        try {
            if (!StampLedger.writable()) {
                LOGGER.debug("a gate run with the real mod home — no model stamp for {}:{}", kind, id);
            } else if (!StampLedger.record(StampLedger.file(), kind, id, stamp)) {
                LOGGER.warn("the model stamp for {}:{} was not recorded — {} was written by a newer "
                        + "Moddy or cannot be read, and is left alone", kind, id, StampLedger.file());
            }
        } catch (Exception e) {
            LOGGER.warn("could not record the model stamp for {}:{} — {}", kind, id, e.toString());
        }
        // No writable() guard: RuntimeConfig.playersDir() already keeps a test run's shelves in its
        // own world, so a gate exercises this path without touching a real child's shelf.
        if (owner != null && !owner.isBlank()) {
            try {
                if (!ShelfProvenance.record(RuntimeConfig.playersDir(), owner, kind, id, stamp)) {
                    LOGGER.warn("the model stamp for {}:{} was not put on {}'s shelf — its "
                            + "provenance.json was written by a newer Moddy, cannot be read, or the "
                            + "owner names no shelf", kind, id, owner);
                }
            } catch (Exception e) {
                LOGGER.warn("could not put the model stamp for {}:{} on {}'s shelf — {}", kind, id,
                        owner, e.toString());
            }
        }
        Path repo = RuntimeConfig.persistDir();
        if (repo == null) {
            LOGGER.debug("no mod repo configured — provenance for {}:{} not published", kind, id);
            return false;
        }
        Path wishes = repo.resolve("wishes");
        Path dir = wishes.resolve(kind).resolve(com.moddingfromamod.store.SafeFileName.segment(id));   // a building id may hold ':' (Windows)
        Path manifest = dir.resolve("manifest.json");
        try {
            Files.createDirectories(dir);
            // Keep the published folder self-describing + pointing back to the engine (optional dep).
            ProvenanceReadme.ensure(wishes);
            WishProvenance prov = readExisting(manifest);
            if (prov == null) {
                prov = WishProvenance.first(id, kind, intent, note, System.currentTimeMillis(),
                        artifactRef);
            } else {
                prov = prov.withRefinement(intent, note);
            }
            prov = prov.withGrant(stamp);
            Files.writeString(manifest, GSON.toJson(prov.toJson()), StandardCharsets.UTF_8);
            // Optionally git-commit/push the published wish content (opt-in; no-op if disabled).
            // WHICH WORLD, in the commit --. These commits are the source the lineage
            // analyses are recovered from, and a git log flattens four worlds into one
            // linear history by construction, which is what made read as six revisions
            // of one Spikor when it was four separate ones.
            GitSync.requestPublish(ProvenanceCommitMessage.of(kind, id,
                    com.moddingfromamod.runtime.WorldPaths.worldName(),
                    com.moddingfromamod.runtime.WorldPaths.sessionId()));
            return true;
        } catch (Exception e) {
            LOGGER.warn("could not publish provenance for {}:{} — {}", kind, id, e.toString());
            return false;
        }
    }

    /** The manifest currently at {@code path}, or null if none / unreadable (treated as first grant). */
    private static WishProvenance readExisting(Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            return WishProvenance.fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.warn("provenance manifest {} unreadable, rewriting fresh — {}", manifest, e.toString());
            return null;
        }
    }

    /** Where a given artifact's manifest lives (null if no repo configured). Handy for tests + gates. */
    public static Path manifestPath(String kind, String id) {
        Path repo = RuntimeConfig.persistDir();
        return repo == null ? null : repo.resolve("wishes").resolve(kind).resolve(com.moddingfromamod.store.SafeFileName.segment(id)).resolve("manifest.json");
    }

    /**
     * Write an artifact bit next to its manifest — e.g. a learned template's recipe, so the artifact
     * itself (not just its provenance) lives in the Mod Repo and can be recompiled/upgraded later.
     * Datapacks already store their bits in {@code wish-datapacks/}, so they don't need this. No-op
     * when no repo is configured; best-effort.
     */
    public static boolean writeArtifact(String kind, String id, String filename, String content) {
        if (kind == null || id == null || filename == null || content == null) {
            return false;
        }
        Path repo = RuntimeConfig.persistDir();
        if (repo == null) {
            return false;
        }
        Path wishes = repo.resolve("wishes");
        Path dir = wishes.resolve(kind).resolve(com.moddingfromamod.store.SafeFileName.segment(id));   // a building id may hold ':' (Windows)
        try {
            Files.createDirectories(dir);
            ProvenanceReadme.ensure(wishes);
            Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOGGER.warn("could not write artifact {}/{}/{} — {}", kind, id, filename, e.toString());
            return false;
        }
    }
}
