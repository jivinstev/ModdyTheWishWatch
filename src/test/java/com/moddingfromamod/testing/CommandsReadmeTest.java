package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The pure halves of the README-vs-command-tree check. The check itself is {@code PublicCommandsReadmeGameTest}
 * (Gate B): from Minecraft 1.21.11 the real tree cannot be built without a bootstrapped game.
 */
class CommandsReadmeTest {

    @Test
    void aReadmeRowNamesItsRootAndModdysFirstBranch() {
        String readme = "# Moddy\n\n## Commands\n\n| Command | What |\n|---|---|\n"
                + "| `/wish <words>` | wish |\n| `/moddy magician` | setup |\n| `/moddy pets list` | pets |\n"
                + "| not a row |\n\n## Other\n\n| `/elsewhere` | no |\n";
        assertEquals(new TreeSet<>(java.util.List.of("wish", "moddy magician", "moddy pets")),
                CommandsReadme.documented(readme, "Commands"));
    }

    @Test
    void aReadmeWithoutTheSectionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CommandsReadme.documented("# nothing\n", "Commands"));
    }

    @Test
    void theTreesPathsAreRootsAndModdysLiteralBranches() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(LiteralArgumentBuilder.literal("wish"));
        d.register(LiteralArgumentBuilder.<Object>literal("moddy")
                .then(LiteralArgumentBuilder.literal("magician"))
                .then(LiteralArgumentBuilder.literal("pets")));
        d.register(LiteralArgumentBuilder.<Object>literal("paint").then(LiteralArgumentBuilder.literal("undo")));
        assertEquals(new TreeSet<>(java.util.List.of("wish", "moddy", "moddy magician", "moddy pets", "paint")),
                CommandTree.paths(d));
    }
}
