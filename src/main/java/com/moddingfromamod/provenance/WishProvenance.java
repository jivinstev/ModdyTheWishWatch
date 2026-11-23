package com.moddingfromamod.provenance;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The prompt <b>lineage</b> of one persisted wish Artifact, stored beside it in the Mod Repo so a
 * future "upgrade to a real Java mod" has both the inspiration (the exact wishes) and an objective
 * intent to test against. See {@code docs/REPO_LAYERING.md} (in the mod repo) for the design.
 *
 * <ul>
 *   <li>{@code intent} — the first wish that produced the artifact (the goal to satisfy).</li>
 *   <li>{@code refinements} — every later wish that tuned the same id, in order.</li>
 *   <li>{@code designNote} — the agent's own rationale for how it built it (the REASONING the raw
 *       wish text doesn't carry). First non-blank note wins, mirroring {@code intent}.</li>
 *   <li>{@code artifactRef} — a hint at where the artifact bits live (e.g. {@code wish-datapacks/<id>}).</li>
 *   <li>{@code grants} — which model made it and each refinement, on which build
 *       ({@link ModelStamp}), oldest first. Empty for anything granted before the stamp existed:
 *       unattributable, never guessed.</li>
 * </ul>
 *
 * <p>Immutable; {@link #withRefinement} returns a new record. Pure and JSON-serializable so it is
 * unit-testable with no game.
 */
public record WishProvenance(
        String id,
        String kind,
        String intent,
        List<String> refinements,
        String designNote,
        long createdMs,
        String artifactRef,
        List<ModelStamp> grants) {

    public WishProvenance {
        refinements = List.copyOf(refinements == null ? List.of() : refinements);
        designNote = designNote == null ? "" : designNote;
        grants = List.copyOf(grants == null ? List.of() : grants);
    }

    /** A provenance with no model stamps — every one written before model stamps. */
    public WishProvenance(String id, String kind, String intent, List<String> refinements,
            String designNote, long createdMs, String artifactRef) {
        this(id, kind, intent, refinements, designNote, createdMs, artifactRef, List.of());
    }

    /** The same lineage with one more grant appended. */
    public WishProvenance withGrant(ModelStamp stamp) {
        if (stamp == null) {
            return this;
        }
        List<ModelStamp> next = new ArrayList<>(grants);
        next.add(stamp);
        return new WishProvenance(id, kind, intent, refinements, designNote, createdMs, artifactRef,
                next);
    }

    /** The same lineage carrying exactly these grants — how a promotion hands them on. */
    public WishProvenance withGrants(List<ModelStamp> all) {
        return new WishProvenance(id, kind, intent, refinements, designNote, createdMs, artifactRef,
                all);
    }

    /** A fresh provenance for a first-time grant. */
    public static WishProvenance first(String id, String kind, String intent, String designNote,
            long createdMs, String artifactRef) {
        return new WishProvenance(id, kind, intent == null ? "" : intent.strip(),
                List.of(), designNote == null ? "" : designNote.strip(), createdMs,
                artifactRef == null ? "" : artifactRef);
    }

    /**
     * Record a later wish that tuned this artifact. No-ops on blank input or an exact duplicate of
     * the intent or the most-recent refinement, so pressing/re-typing the same wish doesn't bloat
     * the lineage. Does not change the design note (use {@link #withRefinement(String, String)}).
     */
    public WishProvenance withRefinement(String prompt) {
        return withRefinement(prompt, null);
    }

    /**
     * Record a later wish that tuned this artifact, plus an optional new design note. The note only
     * fills in when the current one is blank (first non-blank wins, like {@code intent}) — so the
     * original rationale is never silently overwritten by a terse edit.
     */
    public WishProvenance withRefinement(String prompt, String newNote) {
        String note = (designNote.isBlank() && newNote != null && !newNote.isBlank())
                ? newNote.strip() : designNote;
        if (prompt == null || prompt.isBlank()) {
            return note.equals(designNote) ? this
                    : new WishProvenance(id, kind, intent, refinements, note, createdMs, artifactRef, grants);
        }
        String p = prompt.strip();
        boolean dup = p.equals(intent)
                || (!refinements.isEmpty() && refinements.get(refinements.size() - 1).equals(p));
        if (dup) {
            return note.equals(designNote) ? this
                    : new WishProvenance(id, kind, intent, refinements, note, createdMs, artifactRef, grants);
        }
        List<String> next = new ArrayList<>(refinements);
        next.add(p);
        return new WishProvenance(id, kind, intent, next, note, createdMs, artifactRef, grants);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.addProperty("intent", intent);
        JsonArray arr = new JsonArray();
        for (String r : refinements) {
            arr.add(r);
        }
        o.add("refinements", arr);
        o.addProperty("designNote", designNote);
        o.addProperty("createdMs", createdMs);
        o.addProperty("artifactRef", artifactRef);
        if (!grants.isEmpty()) {
            // Omitted when empty, so a manifest written before the stamp re-serialises byte for byte.
            JsonArray g = new JsonArray();
            for (ModelStamp st : grants) {
                g.add(st.toJson());
            }
            o.add("grants", g);
        }
        return o;
    }

    public static WishProvenance fromJson(JsonObject o) {
        List<String> refs = new ArrayList<>();
        if (o.has("refinements") && o.get("refinements").isJsonArray()) {
            for (var el : o.getAsJsonArray("refinements")) {
                if (el.isJsonPrimitive()) {
                    refs.add(el.getAsString());
                }
            }
        }
        List<ModelStamp> grants = new ArrayList<>();
        if (o.has("grants") && o.get("grants").isJsonArray()) {
            for (var el : o.getAsJsonArray("grants")) {
                if (el.isJsonObject()) {
                    grants.add(ModelStamp.fromJson(el.getAsJsonObject()));
                }
            }
        }
        return new WishProvenance(
                str(o, "id"),
                str(o, "kind"),
                str(o, "intent"),
                refs,
                str(o, "designNote"),
                o.has("createdMs") ? o.get("createdMs").getAsLong() : 0L,
                str(o, "artifactRef"),
                grants);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
