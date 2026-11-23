package com.moddingfromamod.marshal;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <b>Every tool has a decision, or the build fails.</b>
 *
 * <p>The same rule {@code StressRegistry.unclaimed()} already enforces for corpus cases, for the
 * same reason: a tool with no row would fall through to "unknown", which silently removes the
 * {@code [undo]} button from every wish that touches it. That is the safe direction, but it is also
 * invisible — undo would quietly stop being offered and nothing would say why.
 *
 * <p>It reads the tool classes as <b>files</b>, exactly as {@code AgentKnowsTheTrapsTest} reads
 * {@code BuildContraptionTool.java}: the tools import Minecraft, so a pure unit test cannot load
 * them, and there is no other way to ask what the registry actually contains without booting a game.
 */
class UndoContractCompletenessTest {

    private static final Path TOOLS =
            Path.of("src/main/java/com/moddingfromamod/agent/tools");

    private static final Pattern NAME =
            Pattern.compile("public String name\\(\\)\\s*\\{\\s*return \"([a-z0-9_]+)\";");

    private static List<String> everyRegisteredToolName() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.list(TOOLS)) {
            for (Path p : files.filter(f -> f.toString().endsWith("Tool.java")).toList()) {
                Matcher m = NAME.matcher(Files.readString(p));
                if (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    @Test
    void everyToolIsClassifiedAsReversibleHarmlessOrIrreversible() throws IOException {
        List<String> tools = everyRegisteredToolName();
        assertTrue(tools.size() > 30,
                "the scan found only " + tools.size() + " tools — the regex has drifted, and a "
                        + "check that finds nothing passes for the wrong reason");

        List<String> undecided = new ArrayList<>();
        for (String tool : tools) {
            if (!UndoContract.known(tool)) {
                undecided.add(tool);
            }
        }
        assertTrue(undecided.isEmpty(),
                "these tools have no undo decision, so any wish using them silently loses its "
                        + "[undo] button: " + undecided
                        + " — add each to UndoContract (REVERSIBLE, HARMLESS or IRREVERSIBLE)");
    }

    @Test
    void everyKindOfThingAWishCanMakeHasAToolThatMakesIt() throws IOException {
        List<String> tools = everyRegisteredToolName();
        List<String> kinds = new ArrayList<>();
        for (String tool : tools) {
            String kind = MadeKind.forTool(tool);
            if (kind != null && !kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        // A kind recorded but never reversed makes the undo button lie; a kind reversed but never
        // recorded makes it disappear. Both directions are checked, here and in WishUndo's switch.
        for (String kind : List.of(MadeKind.BUILD, MadeKind.CREATURE, MadeKind.DATAPACK)) {
            assertTrue(kinds.contains(kind),
                    "no registered tool produces a '" + kind + "', so WishUndo can never reverse one");
        }
    }

    @Test
    void aToolThatMakesSomethingIsAlsoTreatedAsChangingTheWorld() throws IOException {
        for (String tool : everyRegisteredToolName()) {
            if (MadeKind.forTool(tool) != null) {
                assertTrue(UndoContract.touchesTheWorld(tool),
                        tool + " creates an artifact but is classified as harmless, so its output "
                                + "would never be recorded and never be undone");
            }
        }
    }

    @Test
    void theIdFieldMatchesWhatTheToolActuallyAsksFor() throws IOException {
        // author_datapack is the one that differs, and it has since it was written. If a second
        // tool ever renames its id argument, this is where it gets caught rather than in a silent
        // no-op at cancel time.
        assertFalse(Files.readString(TOOLS.resolve("AuthorDatapackTool.java"))
                        .contains("Schemas.prop(s, \"id\","),
                "author_datapack now takes a plain 'id' — MadeKind.idField needs updating");
        assertTrue(Files.readString(TOOLS.resolve("BuildStructureTool.java"))
                        .contains("Schemas.prop(s, \"id\","),
                "build_structure no longer takes 'id' — MadeKind.idField needs updating");
    }
}
