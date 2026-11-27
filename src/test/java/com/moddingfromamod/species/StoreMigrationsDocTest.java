package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/STORE_MIGRATIONS.md cannot drift</b> (#99, #126). The source is read for EVERY versioned store:
 * <ul>
 *   <li>an enveloped store: a class naming its {@code STORE} beside its {@code SCHEMA} or {@code VERSION} (found, so a
 *       new store or a new version needs nothing added here);</li>
 *   <li>a store guarded by its own {@code "version"} (it calls {@code WishStoreFile.writeBlocker}) and a versioned
 *       record (a {@code RECORD_VERSION}): each named in {@link #OWN_VERSION}, and one this test does not know fails by
 *       its class.</li>
 * </ul>
 * Every version above 1 that a store writes has its row; a row for a version nothing writes, or for a store nothing
 * writes, fails; and every row names a class (a backticked {@code package/Class}) that exists.
 */
class StoreMigrationsDocTest {

    private static final Path DOC = Path.of("docs", "STORE_MIGRATIONS.md");
    private static final Path SRC = Path.of("src", "main", "java", "com", "moddingfromamod");

    /**
     * The stores and records that keep their own version, not the envelope's: the class (its path under
     * {@code com/moddingfromamod}), its register name, and the constant that holds its version.
     */
    static final Map<String, String[]> OWN_VERSION = new LinkedHashMap<>();

    static {
        OWN_VERSION.put("species/Species", new String[]{"species (record)", "species/StatLineRepair.VERSION"});   // #104: RECORD_VERSION = StatLineRepair.VERSION
        OWN_VERSION.put("mob/MobState", new String[]{"mob undo record", "RECORD_VERSION"});
        OWN_VERSION.put("mob/MobHandleStore", new String[]{"mob handles", "VERSION"});
        OWN_VERSION.put("lite/MintStore", new String[]{"mint marks", "lite/MintMarks.VERSION"});
        OWN_VERSION.put("wish/HandoverStore", new String[]{"hand-over notes", "VERSION"});
        OWN_VERSION.put("wish/ArtifactMemoryStore", new String[]{"artifact memory", "VERSION"});
    }

    private static final Pattern STORE = Pattern.compile("(?:ICON_)?STORE\\s*=\\s*\"([a-z0-9-]+)\"");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int intConstant(String src, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*;").matcher(src);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Every versioned store and record this build writes: register name → version. */
    static Map<String, Integer> stores() throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("GameTest")).toList()) {
                String src = read(f);
                String rel = SRC.relativize(f).toString().replace('\\', '/').replaceAll("\\.java$", "");
                Matcher m = STORE.matcher(src);
                if (m.find()) {
                    int v = Math.max(intConstant(src, "SCHEMA"), Math.max(intConstant(src, "ICON_SCHEMA"),
                            intConstant(src, "VERSION")));
                    if (v > 0) {
                        out.merge(m.group(1), v, Math::max);
                        continue;
                    }
                }
                boolean own = src.contains("WishStoreFile.writeBlocker(") || src.contains("RECORD_VERSION =");
                if (!own || rel.equals("store/WishStoreFile")) {
                    continue;
                }
                String[] known = OWN_VERSION.get(rel);
                if (known == null) {
                    unknown.add(rel);
                    continue;
                }
                String constant = known[1];
                int v = constant.contains(".")
                        ? intConstant(read(SRC.resolve(constant.substring(0, constant.indexOf('.')) + ".java")),
                        constant.substring(constant.indexOf('.') + 1))
                        : intConstant(src, constant);
                assertTrue(v > 0, rel + ": its version constant " + constant + " was not found");
                out.merge(known[0], v, Math::max);
            }
        }
        assertTrue(unknown.isEmpty(), "a store or record that keeps its own version, not named in OWN_VERSION "
                + "(add it there, with its register name, and its rows): " + unknown);
        return out;
    }

    /** The register's rows, by store name → the versions it lists. */
    private static Map<String, List<Integer>> register() throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\| ([^|]+?) \\| v(\\d+) \\|");
        for (String line : Files.readAllLines(DOC)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                out.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(Integer.parseInt(m.group(2)));
            }
        }
        return out;
    }

    @Test
    void everyVersionAStoreWritesHasItsRowAndEveryRowAStore() throws Exception {
        Map<String, Integer> stores = stores();
        assertTrue(stores.containsKey("species (record)") && stores.containsKey("item-specs"),
                "the source scan found the stores it must: " + stores.keySet());
        Map<String, List<Integer>> rows = register();
        for (var e : stores.entrySet()) {
            for (int v = 2; v <= e.getValue(); v++) {
                assertTrue(rows.getOrDefault(e.getKey(), List.of()).contains(v),
                        "docs/STORE_MIGRATIONS.md has no row for " + e.getKey() + " v" + v
                                + ": a version a store writes with no row is undeclared");
            }
        }
        for (var e : rows.entrySet()) {
            Integer writes = stores.get(e.getKey());
            assertTrue(writes != null, "a row for a store nothing writes: " + e.getKey());
            for (int v : e.getValue()) {
                assertTrue(v <= writes, "a row for a version nothing writes: " + e.getKey() + " v" + v
                        + " (it writes v" + writes + ")");
            }
        }
    }

    @Test
    void theMobStoreIsNeverWrittenOverByAnOlderBuild() {
        assertTrue(com.moddingfromamod.mob.MobHandleStore.VERSION >= com.moddingfromamod.mob.MobState.RECORD_VERSION,
                "mobs.json's version moves with its records, so an older build's writeBlocker refuses a newer file");
    }

    @Test
    void everyRowNamesAClassThatExists() throws Exception {
        for (String r : Files.readAllLines(DOC)) {
            if (!r.matches("^\\| [^|]+ \\| v\\d+ \\|.*")) {
                continue;
            }
            Matcher m = Pattern.compile("`([a-z]+/[A-Z][A-Za-z]+)`").matcher(r);
            boolean named = false;
            while (m.find()) {
                if (Files.isRegularFile(SRC.resolve(m.group(1) + ".java"))) {
                    named = true;
                }
            }
            assertTrue(named, "a register row names no class that exists: " + r);
        }
    }
}
