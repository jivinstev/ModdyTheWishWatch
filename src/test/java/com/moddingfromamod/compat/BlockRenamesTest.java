package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** {@link BlockRenames}: the table, and that nothing a fresh world is handed names a block it renamed. */
class BlockRenamesTest {

    @Test
    void the_table_names_the_chain_rename_at_1_21_9() {
        assertTrue(BlockRenames.ROWS.contains(new BlockRenames.Row("minecraft:chain", "minecraft:iron_chain", "1.21.9", true)));
        for (BlockRenames.Row r : BlockRenames.ROWS) {
            assertTrue(r.from().contains(":") && r.to().contains(":"), "a row names namespaced ids: " + r);
            assertTrue(r.since().matches("\\d+(\\.\\d+)*"), "a row's version is a version: " + r);
        }
        assertTrue(BlockRenames.inForce(Target.MINECRAFT).stream().anyMatch(r -> r.from().equals("minecraft:chain")),
                "the chain rename is in force on " + Target.MINECRAFT);
        assertTrue(BlockRenames.inForce("1.21.8").isEmpty(), "nothing was renamed yet on 1.21.8");
    }

    @Test
    void versions_compare_numerically() {
        assertTrue(BlockRenames.compare("1.21.10", "1.21.9") > 0);
        assertTrue(BlockRenames.compare("1.21.8", "1.21.9") < 0);
        assertEquals(0, BlockRenames.compare("1.21", "1.21.0"));
        assertTrue(BlockRenames.compare("1.22", "1.21.10") > 0);
    }

    /**
     * The port renamed every id already in force in what ships ({@code renames.tsv}), so a fresh world, its starter
     * and owner-library presets and the Builder's catalogues never name a block this Minecraft does not have. A
     * namespaced id anywhere in a shipped text resource or a Java string literal; a bare id as a whole JSON string.
     */
    @Test
    void nothing_shipped_names_a_block_renamed_before_this_build() throws IOException {
        List<String> hits = new ArrayList<>();
        for (BlockRenames.Row r : BlockRenames.inForce(Target.MINECRAFT)) {
            String bare = r.from().substring(r.from().indexOf(':') + 1);
            Pattern namespaced = Pattern.compile(Pattern.quote(r.from()) + "(?![a-z0-9_/.-])");
            Pattern bareJson = Pattern.compile("\"" + Pattern.quote(bare) + "(\\[[^\"]*)?\"");
            Pattern javaLiteral = Pattern.compile("\"" + Pattern.quote(r.from()) + "(\\[[^\"]*)?\"");
            for (Path f : files(Path.of("src/main/resources"))) {
                String text = text(f);
                if (text == null) {
                    continue;   // a binary resource (a texture, a zip)
                }
                if (namespaced.matcher(text).find()
                        || (f.toString().endsWith(".json") && bareJson.matcher(text).find())) {
                    hits.add(f + ": " + r.from());
                }
            }
            for (Path f : files(Path.of("src/main/java"))) {
                if (f.endsWith(Path.of("compat", "BlockRenames.java"))) {
                    continue;   // the table itself
                }
                if (javaLiteral.matcher(text(f)).find()) {
                    hits.add(f + ": " + r.from());
                }
            }
        }
        assertTrue(hits.isEmpty(), "shipped files still name renamed blocks (add a renames.tsv row and apply it): " + hits);
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static String text(Path f) throws IOException {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return null;
        }
    }
}
