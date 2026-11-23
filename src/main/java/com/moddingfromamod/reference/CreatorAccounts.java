package com.moddingfromamod.reference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The generated creator list: a Minecraft creator's public name → the Java account an
 * outside, STRUCTURED source links to them, with the UUID pinned.</b>
 *
 * <p>minecraft.wiki's infobox ({@link MinecraftWikiPerson}) links about twenty creators. The rest
 * come from {@code tools/gen-creator-accounts.py}, which reads only curated fields (the MC
 * Championship wiki's {@code ign=}, minecraft.wiki's {@code minecraft_name(s)}, the MCC API's own
 * participant record) or the MCC participation fingerprint (rule A3: the account's exact set of MCC
 * events equals the player page's, ≥3 events, unique both ways, AND the name is the page's title or
 * a redirect to it), or a creator's OWN labelled declaration on their YouTube channel (rules Y1,
 * and Y2 for the softer ones: a video-only declaration or a namemc {@code .N} link), refuses any
 * disagreement, verifies every name on Mojang and pins its UUID. No row is typed by hand; a
 * maintainer keeps a deny list and a file of rows they CONFIRM (rule O1, with who and
 * when), which the generator reads as a source like any other.
 *
 * <p><b>A row licenses painting THAT account's own skin and nothing else.</b> It is not read by
 * {@link PublicFigure}: being in this table does not make anyone a public figure anywhere else
 * either.
 *
 * <p><b>The pinned UUID is checked at fetch time</b> ({@link SkinLink#pinnedUuid()}): if the name
 * now belongs to a different account (a rename, a name taken by somebody else) the skin is not
 * painted, the same stranger problem this whole guard exists for.
 *
 * <p><b>Private data (D33).</b> The list itself is private: the public mod ships
 * this reader but not the table, so a public jar maps nobody here and {@link SkinLink} answers
 * from minecraft.wiki alone, silently. An override file still works there.
 *
 * <p><b>Where the table comes from.</b> The bundled copy ({@code /creators/creator-accounts.jsonl},
 * built from {@code corpus/creator-accounts.jsonl}) ships with every jar, so a refresh rides the
 * release train with no action on a server. A file at {@code <gameDir>/moddingfromamod/
 * creator-accounts.jsonl} REPLACES it when present and is re-read when it changes, so a server can
 * also be refreshed while running, without a release or a restart.
 */
public final class CreatorAccounts {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreatorAccounts.class);

    static final String RESOURCE = "/creators/creator-accounts.jsonl";
    static final String OVERRIDE = "moddingfromamod/creator-accounts.jsonl";
    static final int SCHEMA = 1;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{2,16}");
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-f]{32}");
    /** The rules that name an account: curated fields (A1–A3), a creator's own labelled
     *  declaration (Y1, and Y2 the soft one), and a maintainer's confirmation (O1). */
    private static final Pattern MAPPED_RULES = Pattern.compile("(A1|A2|A3|Y1|Y2|O1):");

    /** One mapped row: the account, and the first source that names it. */
    public record Row(String creator, String username, UUID uuid, String rule, String source,
                      String page, String url, String revision, int claims) implements SkinLink {
        @Override
        public UUID pinnedUuid() {
            return uuid;
        }

        @Override
        public String sourceName() {
            return "the generated creator list (" + source + ")";
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("source", "creator-accounts");
            o.addProperty("rule", rule);
            o.addProperty("creator", creator);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("claimSource", source);
            o.addProperty("page", page);
            o.addProperty("url", url);
            o.addProperty("revision", revision);
            o.addProperty("claims", claims);
            return o;
        }
    }

    /** A loaded table: rows by every normalised name they answer to. */
    record Table(Map<String, Row> byName, int rows, String origin) {
    }

    private static volatile Table table;
    private static volatile Table fixed;
    private static volatile long overrideStamp = Long.MIN_VALUE;
    private static volatile Path overridePath = defaultOverride();

    private CreatorAccounts() {
    }

    /** The mapped row a public name (or one of its aliases) answers to; null when none. */
    public static Row lookup(String publicName) {
        String key = norm(publicName);
        return key.isEmpty() ? null : current().byName().get(key);
    }

    /** For the boot log: where the table came from and how many accounts it maps. */
    public static String describe() {
        Table t = current();
        return "creator list: " + t.byName().values().stream().distinct().count()
                + " mapped accounts from " + t.origin();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Table current() {
        Table pinned = fixed;
        if (pinned != null) {
            return pinned;
        }
        Path f = overridePath;
        long stamp = Long.MIN_VALUE;
        try {
            if (f != null && Files.isRegularFile(f)) {
                stamp = Files.getLastModifiedTime(f).toMillis() ^ Files.size(f);
            }
        } catch (Exception e) {
            stamp = Long.MIN_VALUE;
        }
        Table t = table;
        if (t != null && stamp == overrideStamp) {
            return t;
        }
        synchronized (CreatorAccounts.class) {
            if (table != null && stamp == overrideStamp) {
                return table;
            }
            Table loaded = null;
            if (stamp != Long.MIN_VALUE) {
                try {
                    loaded = parse(Files.readAllLines(f, StandardCharsets.UTF_8), f + " (override)");
                } catch (Exception e) {
                    LOGGER.warn("creator list override {} unreadable, using the bundled copy: {}",
                            f, e.toString());
                }
            }
            if (loaded == null) {
                loaded = bundled();
            }
            table = loaded;
            overrideStamp = stamp;
            LOGGER.info("{}", "creator list: " + loaded.rows() + " mapped rows from "
                    + loaded.origin());
            return loaded;
        }
    }

    private static Table bundled() {
        return bundled(RESOURCE);
    }

    /**
     * The table bundled at {@code resource}, in this mod's jar or another loaded mod's. The list is PRIVATE (D33,
     * docs/public-repo/DECISIONS.md): the public mod's jar ships without it (our servers' house mod carries it), so there it is simply absent and every lookup falls
     * back to the normal skin lookup (minecraft.wiki), with no error.
     */
    static Table bundled(String resource) {
        try {
            // this jar, else the jar of the mod that ships it (on our servers, the house mod's)
            int slash = resource.lastIndexOf('/');
            String text = com.moddingfromamod.plugin.ModResources.text(resource.substring(1, slash), resource.substring(slash + 1));
            if (text == null) {
                return new Table(Map.of(), 0, "nowhere (no bundled table)");
            }
            return parse(List.of(text.split("\n")), "the jar");
        } catch (Exception e) {
            LOGGER.warn("creator list: bundled table unreadable: {}", e.toString());
            return new Table(Map.of(), 0, "nowhere (unreadable)");
        }
    }

    /**
     * Rows → the name index. Reads only MAPPED rows (rule A1–A3, Y1/Y2, O1) with a Java
     * username and a pinned UUID; a line of another schema or a malformed row is skipped, never guessed at. A name two
     * rows give DIFFERENT accounts answers to neither (the generator refuses that too; this is the
     * same rule enforced on a hand-edited override).
     */
    static Table parse(List<String> lines, String origin) {
        Map<String, Row> by = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        int rows = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Row row;
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
                row = row(o);
            } catch (RuntimeException e) {
                continue;
            }
            if (row == null) {
                continue;
            }
            rows++;
            Set<String> names = new HashSet<>();
            names.add(norm(row.creator()));
            if (o.has("aliases") && o.get("aliases").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("aliases")) {
                    names.add(norm(a.getAsString()));
                }
            }
            names.remove("");
            for (String n : names) {
                Row had = by.putIfAbsent(n, row);
                if (had != null && !had.uuid().equals(row.uuid())) {
                    ambiguous.add(n);
                }
            }
        }
        ambiguous.forEach(by::remove);
        return new Table(Map.copyOf(by), rows, origin);
    }

    private static Row row(JsonObject o) {
        if (!o.has("v") || o.get("v").getAsInt() != SCHEMA) {
            return null;
        }
        String rule = str(o, "rule");
        if (!MAPPED_RULES.matcher(rule).lookingAt()) {
            return null;
        }
        String name = str(o, "name");
        String uuid = str(o, "uuid");
        String creator = str(o, "creator");
        if (!USERNAME.matcher(name).matches() || !UUID_HEX.matcher(uuid).matches()
                || creator.isBlank() || !o.has("claims")) {
            return null;
        }
        JsonArray claims = o.getAsJsonArray("claims");
        JsonObject first = null;
        int linking = 0;
        for (JsonElement el : claims) {
            JsonObject c = el.getAsJsonObject();
            JsonArray names = c.has("names") ? c.getAsJsonArray("names") : new JsonArray();
            if (names.size() == 1 && names.get(0).getAsString().equalsIgnoreCase(name)) {
                linking++;
                if (first == null) {
                    first = c;
                }
            }
        }
        if (first == null) {
            return null;    // no structured claim names this account: not a mapping
        }
        UUID id = UUID.fromString(uuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"));
        String rev = first.has("revision") && !first.get("revision").isJsonNull()
                ? first.get("revision").getAsString() : str(first, "fetched");
        return new Row(creator, name, id, rule, str(first, "source"), str(first, "page"),
                str(first, "url"), rev, linking);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static Path defaultOverride() {
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(OVERRIDE);
        } catch (RuntimeException | NoClassDefFoundError e) {
            return null;   // outside a loaded game (a unit test); the bundled copy still works
        }
    }

    /** A test's override file (null: none), and a fresh read. */
    static void useOverride(Path p) {
        overridePath = p;
        table = null;
        overrideStamp = Long.MIN_VALUE;
    }

    /** A test's rows (JSONL lines) instead of any file; {@link #reset()} afterwards. */
    public static void useRows(List<String> jsonl) {
        fixed = jsonl == null ? null : parse(jsonl, "a test");
    }

    public static void reset() {
        fixed = null;
        useOverride(defaultOverride());
    }
}
