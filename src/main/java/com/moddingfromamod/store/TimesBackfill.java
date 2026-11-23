package com.moddingfromamod.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>When was an old row made</b> — the backfill, for shelf rows written before
 * {@link ArtifactTimes} existed.
 *
 * <p>Two records already carry the answer, and neither is a shelf:
 * <ul>
 *   <li>the action log's {@code made} records — one per granted wish, naming the exact
 *       {@code (kind, id)} of everything it made and the wish's short id, at {@code at};</li>
 *   <li>the world journal ({@code journal.jsonl}) — each wish's {@code createdMs} and
 *       {@code settledMs}, joined to a {@code made} record by the wish's short id. When the join
 *       finds it, the journal's settle time is used: it is the moment the child saw the thing.</li>
 * </ul>
 * A taught button has no {@code made} record (a template is not a {@code MadeKind}); its time is
 * the {@code add_template} tool call that taught it, read off the same log.
 *
 * <p>The first sighting is the creation, the last is the update. A row with no sighting stays
 * unknown — this never invents a time.
 *
 * <p>Pure: {@link #fromLines} is the whole rule and is unit-tested. {@link #forFiles} is the one
 * place that reads files, once per (file, mtime), because six stores ask at every world load.
 */
public final class TimesBackfill {

    /** {@code made} kinds, as each shelved store's backfill asks for them. */
    public static final String CREATURE = "creature";
    public static final String BUILD = "build";
    public static final String ITEM = "item";
    public static final String PAINTING = "painting";
    public static final String TEMPLATE = "template";

    private static final Pattern TEMPLATE_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final Map<String, ArtifactTimes.Times> byKindAndId;

    private TimesBackfill(Map<String, ArtifactTimes.Times> byKindAndId) {
        this.byKindAndId = byKindAndId;
    }

    public static final TimesBackfill EMPTY = new TimesBackfill(Map.of());

    /** What the records say about {@code kind:id}, or {@link ArtifactTimes.Times#NONE}. */
    public ArtifactTimes.Times timesOf(String kind, String id) {
        ArtifactTimes.Times t = byKindAndId.get(key(kind, id));
        return t == null ? ArtifactTimes.Times.NONE : t;
    }

    public int size() {
        return byKindAndId.size();
    }

    private static String key(String kind, String id) {
        String i = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (i.startsWith("id:")) {
            i = i.substring(3);   // the item catalogue's key for a thing with a wish feature
        }
        return (kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT)) + ":" + i;
    }

    /** The whole rule, over the two files' lines. Malformed lines are skipped, never fatal. */
    public static TimesBackfill fromLines(Iterable<String> actionLog, Iterable<String> journal) {
        Map<String, Long> settledByShortId = new HashMap<>();
        if (journal != null) {
            for (String line : journal) {
                JsonObject o = parse(line);
                if (o == null || !o.has("id")) {
                    continue;
                }
                String id = str(o, "id");
                long when = Math.max(num(o, "settledMs"), num(o, "createdMs"));
                if (id.length() >= 8 && when > 0) {
                    settledByShortId.merge(id.substring(0, 8).toLowerCase(Locale.ROOT), when,
                            Math::max);
                }
            }
        }
        Map<String, ArtifactTimes.Times> out = new HashMap<>();
        if (actionLog != null) {
            for (String line : actionLog) {
                if (!mayMatter(line)) {
                    continue;
                }
                JsonObject o = parse(line);
                if (o == null) {
                    continue;
                }
                long at = instant(str(o, "at"));
                String kind = str(o, "kind");
                if (kind.equals("made") && o.has("made") && o.get("made").isJsonArray()) {
                    String wish = str(o, "wishId").toLowerCase(Locale.ROOT);
                    Long settled = wish.length() >= 8
                            ? settledByShortId.get(wish.substring(0, 8)) : null;
                    long when = settled != null && settled > 0 ? settled : at;
                    for (JsonElement el : o.getAsJsonArray("made")) {
                        if (el.isJsonObject()) {
                            see(out, key(str(el.getAsJsonObject(), "kind"),
                                    str(el.getAsJsonObject(), "id")), when);
                        }
                    }
                } else if (kind.equals("tool_call") && str(o, "tool").endsWith("add_template")) {
                    Matcher m = TEMPLATE_ID.matcher(str(o, "args"));
                    if (m.find()) {
                        see(out, key(TEMPLATE, m.group(1)), at);
                    }
                }
            }
        }
        return new TimesBackfill(out);
    }

    private static void see(Map<String, ArtifactTimes.Times> out, String key, long when) {
        if (when <= 0 || key.endsWith(":")) {
            return;
        }
        ArtifactTimes.Times had = out.get(key);
        out.put(key, had == null ? new ArtifactTimes.Times(when, when)
                : new ArtifactTimes.Times(Math.min(had.createdAt(), when),
                        Math.max(had.updatedAt(), when)));
    }

    // ── the files ───────────────────────────────────────────────────────────────────────────

    private static String cachedFor = "";
    private static TimesBackfill cached = EMPTY;

    /**
     * The backfill for this action log and this world's journal, read once per pair of mtimes.
     * Either may be absent or null; nothing here throws.
     */
    public static synchronized TimesBackfill forFiles(Path actionLog, Path journal) {
        String stamp = stampOf(actionLog) + "|" + stampOf(journal);
        if (stamp.equals(cachedFor)) {
            return cached;
        }
        cached = fromLines(lines(actionLog, TimesBackfill::mayMatter), lines(journal, l -> true));
        cachedFor = stamp;
        return cached;
    }

    private static String stampOf(Path f) {
        try {
            return f == null || !Files.isRegularFile(f) ? "-"
                    : f + "@" + Files.getLastModifiedTime(f).toMillis() + "#" + Files.size(f);
        } catch (IOException e) {
            return "-";
        }
    }

    /** Cheap pre-filter: the log is mostly tool calls this does not want, so keep few lines. */
    private static boolean mayMatter(String line) {
        return line != null && (line.contains("\"made\"") || line.contains("add_template"));
    }

    private static java.util.List<String> lines(Path f, java.util.function.Predicate<String> keep) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (f == null || !Files.isRegularFile(f)) {
            return out;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (keep.test(line)) {
                    out.add(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A partial read is still a backfill of what was read; an unknown time stays unknown.
        }
        return out;
    }

    private static JsonObject parse(String line) {
        try {
            JsonElement e = line == null || line.isBlank() ? null : JsonParser.parseString(line);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String field) {
        JsonElement e = o.get(field);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static long num(JsonObject o, String field) {
        JsonElement e = o.get(field);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0L;
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static long instant(String iso) {
        try {
            return iso.isEmpty() ? 0L : java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
