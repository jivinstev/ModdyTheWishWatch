package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI must be findable by a user who installed it the documented way and then started Minecraft
 * from the LAUNCHER rather than from a shell.
 *
 * <p>Why this test exists at all: {@code ClaudeCli} searched {@code PATH} and then jumped to the VS
 * Code extension, and every developer machine has the extension — so the gap was invisible to us and
 * fatal for exactly the audience the installer is for. The official installer writes
 * {@code ~/.local/bin/claude} and puts it on {@code PATH} by editing a shell profile; a GUI-launched
 * process on macOS inherits launchd's {@code PATH} and never reads that profile. {@code claude} then
 * works in Terminal while the mod reports no agent.
 *
 * <p>These assertions run against a temp home rather than the machine's, so they say the same thing
 * on a developer laptop and on a fresh install.
 */
class ClaudeCliResolutionTest {

    @Test
    void theNativeInstallersLocationIsFound(@TempDir Path home) throws IOException {
        Path cli = executableAt(home.resolve(".local/bin/claude"));
        assertEquals(cli.toString(), ClaudeCli.fromWellKnown(home),
                "~/.local/bin/claude is where the official installer puts it -- this is the case "
                        + "that made the mod look broken to a user whose CLI worked fine");
    }

    @Test
    void theNativeInstallerWinsOverTheOlderOne() throws IOException {
        // Both present is the ordinary state after an upgrade. Picking the stale one is a silent
        // downgrade: it launches, so nothing reports a problem.
        Path home = Files.createTempDirectory("mfam-cli-order");
        executableAt(home.resolve(".claude/local/claude"));
        Path preferred = executableAt(home.resolve(".local/bin/claude"));
        assertEquals(preferred.toString(), ClaudeCli.fromWellKnown(home));
    }

    @Test
    void aFileThatIsNotExecutableIsNotTheCli(@TempDir Path home) throws IOException {
        Path notExec = home.resolve(".local/bin/claude");
        Files.createDirectories(notExec.getParent());
        Files.writeString(notExec, "");
        Files.setPosixFilePermissions(notExec, Set.of(PosixFilePermission.OWNER_READ));
        // Falls through rather than returning a path that cannot be run. Returning it would turn a
        // clean "no agent" into a launch failure deep inside AgentSession.
        assertNull(ClaudeCli.fromWellKnown(home));
    }

    @Test
    void anEmptyHomeIsSurvivable(@TempDir Path home) {
        assertNull(ClaudeCli.fromWellKnown(home), "no CLI is a normal outcome -- the mod plays "
                + "without an agent, so this must never throw");
        assertNull(ClaudeCli.fromWellKnown(null));
        assertNull(ClaudeCli.fromWellKnown(Path.of("")));
    }

    @Test
    void resolveConsultsWellKnownLocations() {
        // The wiring, not the search: a method nobody calls is the failure mode this class already
        // had once. Asserted on the SOURCE because resolve() reads process-global state we must not
        // mutate in a unit test -- the alternative is a test that passes because the developer
        // happens to have the CLI installed.
        String src = readSource();
        int path = src.indexOf("String onPath = fromPath();");
        int wellKnown = src.indexOf("fromWellKnown(Path.of(System.getProperty(\"user.home\"");
        int vscode = src.indexOf("return fromVsCodeExtension();");
        assertTrue(path >= 0 && wellKnown >= 0 && vscode >= 0,
                "resolve() must consult PATH, the well-known locations, then VS Code");
        assertTrue(path < wellKnown && wellKnown < vscode,
                "the well-known locations go AFTER PATH (an explicit install wins) and BEFORE the "
                        + "VS Code extension (a real install beats an editor's bundled copy)");
    }

    private static Path executableAt(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "#!/bin/sh\n");
        Files.setPosixFilePermissions(p, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));
        return p;
    }

    private static String readSource() {
        try {
            return Files.readString(
                    Path.of("src/main/java/com/moddingfromamod/agent/ClaudeCli.java"));
        } catch (IOException e) {
            throw new AssertionError("could not read ClaudeCli.java", e);
        }
    }
}
