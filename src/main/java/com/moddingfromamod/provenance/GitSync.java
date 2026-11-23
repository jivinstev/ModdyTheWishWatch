package com.moddingfromamod.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The engine git-manages the wish content it publishes into the Mod Repo</b> — so it doesn't get
 * swept into another agent's commit in a shared checkout, or lost uncommitted. Opt-in
 * ({@code wish_git_sync=true}); OFF by default. Design + setup: {@code docs/WISH_GIT_SYNC.md} (in the mod repo).
 *
 * <p><b>The mod runs git, not the wish agent</b> — deterministic, and the agent needs no extra
 * permissions. Uses the user's ambient git credentials (non-interactive: SSH key / credential helper).
 *
 * <p><b>Safety:</b> only ever acts when the checkout is ALREADY on the Moddy branch (so it can never
 * commit to a shared main working tree), and stages ONLY the wish paths — never {@code -A}. All
 * network/commit work runs off the game thread on one serial executor; failures are best-effort and
 * never block a grant.
 */
public final class GitSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String[] WISH_PATHS = {"wish-datapacks", "wishes"};

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "moddy-git-sync");
        t.setDaemon(true);
        return t;
    });

    private GitSync() {
    }

    /** Non-blocking (production path): enqueue a commit+push of wish content. No-op if disabled. */
    public static void requestPublish(String message) {
        if (!RuntimeConfig.gitSyncEnabled()) {
            return;
        }
        Path repo = RuntimeConfig.persistDir();
        if (repo == null) {
            return;
        }
        String branch = RuntimeConfig.gitBranch();
        EXEC.submit(() -> {
            try {
                commitAndPush(repo, branch, message);
            } catch (Exception e) {
                LOGGER.warn("git-sync publish failed: {}", e.toString());
            }
        });
    }

    /**
     * Block until the sync executor has drained. Test seam only — lets an e2e assert on the result of
     * the async {@link #requestPublish}. The executor is single-threaded, so a no-op that completes
     * means every prior publish task finished. Never used on the game thread in production.
     */
    public static void awaitIdle(long timeoutMs) {
        try {
            EXEC.submit(() -> {
            }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // best-effort — a test asserts on the real end state (the remote), not on this
        }
    }

    /** Non-blocking startup reconcile: fetch + fast-forward the Moddy branch. Best-effort. */
    public static void syncAtStartup() {
        if (!RuntimeConfig.gitSyncEnabled()) {
            return;
        }
        Path repo = RuntimeConfig.persistDir();
        if (repo == null) {
            return;
        }
        String branch = RuntimeConfig.gitBranch();
        EXEC.submit(() -> {
            try {
                if (!isGitRepo(repo) || !branch.equals(currentBranch(repo))) {
                    return;
                }
                run(repo, List.of("fetch", "origin", branch), false);
                run(repo, List.of("merge", "--ff-only", "origin/" + branch), false);
            } catch (Exception e) {
                LOGGER.warn("git-sync startup reconcile failed: {}", e.toString());
            }
        });
    }

    /**
     * SYNC core (testable). Stages the wish paths, commits on the Moddy branch, and pushes. Refuses
     * to act unless the checkout is already ON {@code branch}. Returns true iff a commit was made.
     */
    public static boolean commitAndPush(Path repo, String branch, String message)
            throws IOException, InterruptedException {
        Result probe = run(repo, List.of("rev-parse", "--is-inside-work-tree"), false);
        if (probe.exit() != 0) {
            // The exact failure we hit once: a repo under ~/Desktop that the game's sandboxed git
            // subprocess couldn't read. Log WHAT git said so the cause is obvious next time.
            LOGGER.warn("git-sync: {} is not a usable git repo — skipping. git said: {}",
                    repo, probe.out().strip());
            return false;
        }
        String cur = currentBranch(repo);
        if (!branch.equals(cur)) {
            LOGGER.warn("git-sync: {} is on '{}', not the Moddy branch '{}' — skipping. Point "
                    + "persist_dir at a dedicated Moddy checkout on that branch.", repo, cur, branch);
            return false;
        }
        // Stage ONLY the wish paths that exist — never `-A`, so unrelated files are never swept in.
        // FORCE-add (`-f`): Moddy OWNS these paths, so their contents must be tracked regardless of
        // the target repo's .gitignore. This matters because `wishes/build/<id>/` (the buildings
        // kind) is caught by the near-universal Gradle `build` ignore rule — without `-f`, plain
        // `git add wishes` silently skips every build-kind wish, publishing it to disk but never
        // committing it. Forcing only these two explicit pathspecs can't sweep in unrelated ignored
        // files (that would need `-A` or a broader pathspec).
        List<String> add = new ArrayList<>(List.of("add", "-f", "--"));
        boolean any = false;
        for (String p : WISH_PATHS) {
            if (Files.exists(repo.resolve(p))) {
                add.add(p);
                any = true;
            }
        }
        if (!any) {
            return false;
        }
        run(repo, add, true);
        if (run(repo, List.of("diff", "--cached", "--quiet"), false).exit == 0) {
            return false; // nothing staged -> nothing to commit
        }
        run(repo, List.of("-c", "user.name=Moddy", "-c", "user.email=moddy@modding-from-a-mod",
                "commit", "-m", message), true);
        Result push = run(repo, List.of("push", "origin", "HEAD:" + branch), false);
        if (push.exit != 0) {
            LOGGER.warn("git-sync: committed locally but push failed (offline/auth?): {}",
                    push.out.strip());
        } else {
            LOGGER.info("git-sync: committed + pushed to {} — {}", branch, message);
        }
        return true;
    }

    // ---- helpers ----

    static boolean isGitRepo(Path dir) throws IOException, InterruptedException {
        return run(dir, List.of("rev-parse", "--is-inside-work-tree"), false).exit == 0;
    }

    /** Current branch name, even on an unborn branch; "" if detached. */
    static String currentBranch(Path dir) throws IOException, InterruptedException {
        return run(dir, List.of("symbolic-ref", "--short", "-q", "HEAD"), false).out.strip();
    }

    record Result(int exit, String out) {
    }

    private static Result run(Path dir, List<String> args, boolean checked)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        // Never let git block on a credential/askpass prompt (there's no terminal) — fail fast so a
        // stuck push can't wedge the single-thread executor and starve later publishes.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (checked && exit != 0) {
            throw new IOException("git " + args + " failed (" + exit + "): " + out.strip());
        }
        return new Result(exit, out);
    }
}
