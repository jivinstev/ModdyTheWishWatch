package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A wished food began to be given with can_always_eat by a change to make_item's give line alone. The precompiled
 * store's fingerprint covered templates, catalogues and the compiler, not the tools' rendering, so a branch that took
 * the change kept a store compiled for the old give line, and only the release's full tier saw it. The rendering code
 * is fingerprinted now, by its code alone.
 */
class RenderingSourceTest {

    @Test
    @DisplayName("every rendering source named is in the tree")
    void everyFileExists() {
        for (String f : RenderingSource.FILES) {
            assertTrue(Files.isRegularFile(Path.of("src/main/java").resolve(f)), f);
        }
    }

    @Test
    @DisplayName("a comment, a blank line or an issue reference changes nothing; code does")
    void onlyCodeCounts() {
        String code = "class A {\n  String give() {\n    return \"food\";\n  }\n}\n";
        String commented = "// the give line (#173)\nclass A {\n\n  /** why, see #12 */\n  String give() {   // here\n"
                + "    return \"food\"; /* note */\n  }\n}\n";
        assertEquals(RenderingSource.normalise(code), RenderingSource.normalise(commented));
        assertNotEquals(RenderingSource.normalise(code),
                RenderingSource.normalise(code.replace("\"food\"", "\"food,can_always_eat\"")));
    }

    @Test
    @DisplayName("comment markers inside a string or a text block are text, not comments")
    void stringsKeepTheirText() {
        String a = "String s = \"a // b /* c */\"; char q = '\"'; String t = \"\"\"\n  x // y\n  \"\"\";";
        String n = RenderingSource.normalise(a);
        assertTrue(n.contains("\"a // b /* c */\""), n);
        assertTrue(n.contains("x // y"), n);
        assertTrue(n.contains("'\"'"), n);
        assertNotEquals(n, RenderingSource.normalise(a.replace("x // y", "x // z")));
    }

    @Test
    @DisplayName("an issue reference in a string is dropped as the public render drops it, so both builds agree")
    void issueReferencesInStringsAreDropped() {
        assertEquals(RenderingSource.normalise("String s = \"see the fix \";"),
                RenderingSource.normalise("String s = \"see the fix #173\";"));
        assertEquals("x = \"#FFAA00\";", RenderingSource.normalise("x = \"#FFAA00\";"), "a colour is not an issue");
    }
}
