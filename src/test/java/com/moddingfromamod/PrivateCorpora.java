package com.moddingfromamod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * <b>The corpora that never go public, and how a test that reads one behaves without it</b>.
 *
 * <p>A child's real wishes, the agent's real tool calls and the buildings really built are private resources
 * ({@code split.json} {@code private_resources}): the public cut ({@code tools/public-tree.py}) leaves them out. A
 * public test that reads one SKIPS there, by name, and nowhere else: in this repo the file is required, and a missing
 * one FAILS, so a deleted corpus can never pass as a skip. The public cut is told apart by what it lacks: it has no
 * {@code docs/public-repo/split.json} (docs are private). {@code PublicSplitTest} keeps {@link #NAMES} equal to
 * {@code split.json}'s list.
 */
public final class PrivateCorpora {

    /** The private resources under {@code src/main/resources/corpus/}, by file name. */
    public static final List<String> NAMES = List.of("bol-wishes.jsonl", "real-builds.jsonl", "real-tool-calls.jsonl", "real-wishes.jsonl",
            "verbatim-creatures.jsonl", "verbatim-wishes.jsonl");

    private static final Path SPLIT = Path.of("docs/public-repo/split.json");

    private PrivateCorpora() {
    }

    /** True in the public cut, which carries no split.json. */
    public static boolean publicCut() {
        return !Files.exists(SPLIT);
    }

    /** Present → returns; absent in the public cut → the test is skipped, by name; absent here → it fails. */
    public static void require(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException(name + " is not a private corpus; read it normally");
        }
        if (PrivateCorpora.class.getResource("/corpus/" + name) != null) {
            return;
        }
        if (publicCut()) {
            Assumptions.abort("corpus/" + name + " is private (#2310): the public cut has no copy, so this is skipped");
        }
        Assertions.fail("corpus/" + name + " is missing from this repo's resources; it is required here (only the "
                + "public cut may lack it)");
    }
}
