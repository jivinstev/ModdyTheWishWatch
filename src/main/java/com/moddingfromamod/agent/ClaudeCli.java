package com.moddingfromamod.agent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * Finds the Claude CLI. The Java twin of {@code tools/resolve-claude.sh}, for when the mod launches
 * its own agent.
 *
 * <p>Resolution order, most explicit first: a system property, the {@code CLAUDE_CLI} environment
 * variable, {@code PATH}, the well-known install locations, then the binary bundled with the
 * newest installed VS Code extension.
 *
 * <p><b>{@code PATH} is not enough, and that is the bug this class was carrying.</b> The official
 * installer puts the CLI at {@code ~/.local/bin/claude} and gets it onto {@code PATH} by editing a
 * <i>shell profile</i>. Minecraft is not started from a shell: launched from the vanilla launcher
 * on macOS it inherits launchd's minimal {@code PATH} ({@code /usr/bin:/bin:/usr/sbin:/sbin}), so
 * {@code ~/.local/bin} is simply absent. The failure is the worst shape available — {@code claude}
 * works perfectly in the user's Terminal, and the mod reports no agent — which reads as our bug in
 * exactly the place a new installer cannot afford one. Hence {@link #fromWellKnown}.
 *
 * <p>The VS Code fallback is last, and is why this searches rather than hardcoding: the extension's
 * path carries its <b>version number</b> and changes on every update, so a symlink or a pinned path
 * silently rots the next time the extension upgrades. We pick the newest by modification time.
 *
 * <p><b>Windows is deliberately not covered here.</b> Resolving {@code claude.exe} would be the
 * smallest part of it — the agent cannot run on Windows at all until the {@code /dev/null} sites in
 * {@link AgentSession} are gone (S0's Windows half in {@code docs/INSTALLER.md}). A half-fix that
 * finds the binary and then fails to launch it is worse than not looking: it moves the error later
 * and makes it harder to read.
 */
public final class ClaudeCli {

    private static final String CLI_PROPERTY = ModdingFromAModMod.MODID + ".cli";

    private ClaudeCli() {
    }

    /** The CLI path, or null if it can't be found. */
    public static String resolve() {
        // Magician off: not even a look for a CLI. A probe is how an idle `claude --bg` came to start in a
        // single-player world whenever a signed-in CLI was on the machine.
        if (!com.moddingfromamod.config.Magician.allowsAi()) {
            return null;
        }
        // A Muse-only Magician setup (D20) never starts Claude Code: the researcher and the Haiku fallbacks
        // ask for it by name and would otherwise run on whatever Claude sign-in this machine has.
        if (!com.moddingfromamod.config.MagicianWiring.claudeCodeChosen()) {
            return null;
        }
        return locate();
    }

    /**
     * Where {@code claude} is, with no Magician gate: only the Magician setup's program check calls this directly
     *, and only when the host asked for it. Everything else goes through {@link #resolve}.
     */
    public static String locate() {
        String explicit = System.getProperty(CLI_PROPERTY);
        // AN EXPLICIT "no CLI", which is what makes a gate hermetic.
        //
        // Setting the property to a bogus path does NOT work: usable() rejects it and resolution
        // falls through to PATH and then to the VS Code extension's bundled binary -- which exists
        // on a developer machine even when `claude` is not on PATH. That is exactly how a unit test
        // came to be making live model calls without anybody intending it.
        if (explicit != null && ("none".equalsIgnoreCase(explicit.trim())
                || "off".equalsIgnoreCase(explicit.trim()) || explicit.isBlank())) {
            return null;
        }
        if (usable(explicit)) {
            return explicit;
        }
        String env = System.getenv("CLAUDE_CLI");
        if (usable(env)) {
            return env;
        }
        String onPath = fromPath();
        if (onPath != null) {
            return onPath;
        }
        String wellKnown = fromWellKnown(Path.of(System.getProperty("user.home", "")));
        if (wellKnown != null) {
            return wellKnown;
        }
        return fromVsCodeExtension();
    }

    private static boolean usable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File f = new File(path);
        return f.isFile() && f.canExecute();
    }

    private static String fromPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, "claude");
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Where the CLI actually lands, for a user who installed it the documented way and then started
     * Minecraft from the launcher rather than from a shell.
     *
     * <p>Ordered most-official first. {@code ~/.local/bin} is the native installer's target and the
     * one that matters; the rest are the npm and Homebrew prefixes, which a developer machine tends
     * to have on {@code PATH} anyway and a fresh install does not.
     *
     * <p>Package-private and taking its home directory as an argument so a unit test can point it
     * at a temp directory instead of the machine's real one — the class otherwise reads only
     * process-global state, which is untestable without leaking the developer's own install into
     * the assertion.
     */
    static String fromWellKnown(Path home) {
        if (home == null || home.toString().isBlank()) {
            return null;
        }
        String[] relative = {
            ".local/bin/claude",      // the native installer (claude.ai/install.sh)
            ".claude/local/claude",   // the older local installer
            ".npm-global/bin/claude", // npm with a user prefix
            ".bun/bin/claude",
        };
        for (String rel : relative) {
            Path candidate = home.resolve(rel);
            if (usable(candidate.toString())) {
                return candidate.toString();
            }
        }
        String[] absolute = {
            "/opt/homebrew/bin/claude", // Homebrew on Apple silicon
            "/usr/local/bin/claude",    // Homebrew on Intel, and npm's default global prefix
        };
        for (String abs : absolute) {
            if (usable(abs)) {
                return abs;
            }
        }
        return null;
    }

    /** The newest {@code anthropic.claude-code-*} extension's bundled binary. */
    private static String fromVsCodeExtension() {
        Path extensions = Path.of(System.getProperty("user.home"), ".vscode", "extensions");
        if (!Files.isDirectory(extensions)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(extensions)) {
            List<Path> candidates = dirs
                    .filter(p -> p.getFileName().toString().startsWith("anthropic.claude-code-"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            for (Path dir : candidates) {
                Path bin = dir.resolve("resources").resolve("native-binary").resolve("claude");
                if (Files.isRegularFile(bin) && Files.isExecutable(bin)) {
                    return bin.toString();
                }
            }
        } catch (Exception ignored) {
            // Not finding the CLI is a normal, survivable outcome: the mod plays fine without an
            // agent, so this must never be fatal.
        }
        return null;
    }
}
