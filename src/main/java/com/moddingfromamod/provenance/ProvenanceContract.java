package com.moddingfromamod.provenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;

/**
 * <b>The standing obligation, mirroring {@link com.moddingfromamod.wish.ReplayContract}.</b> Every
 * wish <i>Kind</i> that persists an Artifact must also publish a provenance <i>Manifest</i> (with a
 * non-empty intent) into the configured Mod Repo, so any persisted wish carries the prompts that
 * produced it and can later be upgraded to a Java construct. See {@code docs/REPO_LAYERING.md}
 * (in the mod repo) + {@code docs/WISH_PROVENANCE.md}.
 *
 * <p><b>Adding a new Kind that persists an Artifact means three things, and only three:</b>
 * <ol>
 *   <li>Expose an optional {@code note} arg on the authoring tool ({@link ProvenanceContext#NOTE_HINT}
 *       is the shared wording) and set {@link ProvenanceContext#note} from it, so the agent's design
 *       reasoning is captured alongside the wish text.</li>
 *   <li>Call {@link ProvenancePublisher#publish} once with the artifact's EDITABLE SOURCE (the design
 *       the agent authored, as JSON/text) — this writes the source + the manifest and git-syncs it.</li>
 *   <li>Add the kind string to {@link #PUBLISHING_KINDS} below — the contract test then holds it to
 *       the same bar as every other kind.</li>
 * </ol>
 * Datapacks are the one variation: their bits live in {@code wish-datapacks/}, so they call
 * {@code record} directly rather than {@code publish}.
 */
public final class ProvenanceContract {

    /**
     * Kinds that persist an Artifact and therefore MUST publish a manifest, each with its editable
     * source: {@code datapack} (.cbscript in {@code wish-datapacks/}), {@code template}
     * (template.json), {@code species} (sculpt.json), {@code build} (build.json), {@code painting}
     * (painting.json — the paint
     * RECIPE, which is the re-editable source: shapes and colours, never the image they draw), {@code event} (event.json — the kept
     * design, which {@code moddyevent stage <name>} replays).
     * Every persisting wish kind is now wired. ({@code contraption} and {@code train} were here
     * until Create went,.)
     */
    public static final List<String> PUBLISHING_KINDS =
            List.of("datapack", "template", "species", "build", "painting", "event");

    private ProvenanceContract() {
    }

    /**
     * True when {@code (kind, id)} has a published manifest whose intent is non-blank — i.e. the
     * artifact was captured WITH the wish that produced it. False if there's no repo, no manifest,
     * or the intent wasn't stamped (the exact failure this contract exists to catch).
     */
    public static boolean satisfied(String kind, String id) {
        Path m = ProvenancePublisher.manifestPath(kind, id);
        if (m == null || !Files.isRegularFile(m)) {
            return false;
        }
        try {
            WishProvenance p = WishProvenance.fromJson(
                    JsonParser.parseString(Files.readString(m, StandardCharsets.UTF_8)).getAsJsonObject());
            return p.intent() != null && !p.intent().isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
