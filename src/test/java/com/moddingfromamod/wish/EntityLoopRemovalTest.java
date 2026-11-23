package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>No loop removes entities from the level it is iterating</b>.
 *
 * <p>{@code for (Entity e : level.getAllEntities()) { e.discard(); }} works almost every time: a
 * discard takes the entity out of the map being iterated, and the iterator usually survives. When
 * the map shrinks mid-loop it does not — {@code ArrayIndexOutOfBoundsException} out of fastutil,
 * dependent on how many entities the world happens to hold. {@code Fx.sweep} did this and failed
 * once in 44 Gate B runs. Collect first, remove after. A loop that removes ONE entity and breaks is fine.
 */
class EntityLoopRemovalTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/moddingfromamod");
    private static final Pattern LOOP = Pattern.compile("for \\([^)]*:[^)]*\\.getAllEntities\\(\\)\\) \\{");
    private static final Pattern REMOVES = Pattern.compile(
            "\\.(discard|kill)\\(|\\.remove\\(\\s*(?:[\\w.]*\\.)?(?:Entity\\.)?RemovalReason");

    /** Each loop over getAllEntities() whose body removes an entity and does not break out. */
    static List<String> offenders(String name, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = LOOP.matcher(text);
        while (m.find()) {
            int i = m.end();
            for (int depth = 1; depth > 0 && i < text.length(); i++) {
                char c = text.charAt(i);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
            String body = text.substring(m.end(), i);
            if (REMOVES.matcher(body).find() && !body.contains("break;")) {
                out.add(name + ":" + (text.substring(0, m.start()).split("\n", -1).length));
            }
        }
        return out;
    }

    @Test
    void noLoopOverTheLevelsEntitiesRemovesThem() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(SOURCE_ROOT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                found.addAll(offenders(SourceRoots.relativize(SOURCE_ROOT, p).toString(), Files.readString(p)));
            }
        }
        assertEquals(List.of(), found, "a loop over level.getAllEntities() removes entities as it goes — "
                + "collect them into a list, then remove (#2044)");
    }

    @Test
    void theRuleCatchesTheLoopItForbidsAndAllowsTheOnesItShould() {
        String bad = "for (Entity e : level.getAllEntities()) {\n    if (e != null) {\n        e.discard();\n    }\n}\n";
        assertEquals(List.of("X.java:1"), offenders("X.java", bad));
        String unload = "for (Entity e : level.getAllEntities()) {\n    e.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);\n}\n";
        assertEquals(1, offenders("X.java", unload).size());
        String oneAndOut = "for (Entity e : level.getAllEntities()) {\n    e.discard();\n    break;\n}\n";
        assertTrue(offenders("X.java", oneAndOut).isEmpty());
        String collected = "for (Entity e : level.getAllEntities()) {\n    found.add(e);\n}\nfound.forEach(Entity::discard);\n";
        assertTrue(offenders("X.java", collected).isEmpty());
        String aMapRemove = "for (Entity e : level.getAllEntities()) {\n    SEEN.remove(e.getUUID());\n}\n";
        assertTrue(offenders("X.java", aMapRemove).isEmpty());
    }
}
