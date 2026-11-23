package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>e2e for opt-in git-sync</b> — the "prod-representative agent executes a dummy wish commit" test.
 * A dummy wish published into a Moddy worktree is committed on the Moddy branch and pushed to a local
 * <b>bare</b> remote. Real git, fully offline, no GitHub/credentials. Skips if git isn't installed.
 */
class GitSyncTest {

    @AfterEach
    void reset() {
        System.clearProperty("moddingfromamod.persistDir");
        System.clearProperty("moddingfromamod.home");
        RuntimeConfig.invalidate();
        ProvenanceContext.clear();
    }

    @Test
    void publishedWishIsCommittedAndPushed(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(tmp), "git not installed");
        Path bare = tmp.resolve("remote.git");
        Path work = tmp.resolve("moddy-worktree");
        run(tmp, List.of("init", "--bare", bare.toString()));
        run(tmp, List.of("clone", bare.toString(), work.toString()));
        run(work, List.of("checkout", "-b", "moddy/wishes")); // dedicated Moddy branch

        // Publish a dummy wish into the worktree (the real publisher).
        System.setProperty("moddingfromamod.home", tmp.resolve("home").toString());
        System.setProperty("moddingfromamod.persistDir", work.toString());
        RuntimeConfig.invalidate();
        ProvenancePublisher.record("datapack", "dummy_wish",
                "wish-datapacks/dummy_wish", "a dummy wish for the e2e");
        // Datapack bits normally written by DatapackForge — simulate them.
        Files.createDirectories(work.resolve("wish-datapacks/dummy_wish"));
        Files.writeString(work.resolve("wish-datapacks/dummy_wish/give.mcfunction"), "say hi\n");

        // The mod's git action.
        boolean committed = GitSync.commitAndPush(work, "moddy/wishes",
                "Moddy wish (datapack): dummy_wish");
        assertTrue(committed, "a publish must produce a commit");

        // It reached the bare remote, with the manifest + self-describing README + datapack bits.
        String log = out(bare, List.of("log", "--oneline", "moddy/wishes"));
        assertTrue(log.contains("Moddy wish (datapack): dummy_wish"),
                "the commit must be pushed to the remote: " + log);
        String tree = out(bare, List.of("ls-tree", "-r", "--name-only", "moddy/wishes"));
        assertTrue(tree.contains("wishes/datapack/dummy_wish/manifest.json"), tree);
        assertTrue(tree.contains("wishes/README.md"), "the self-describing README rides along: " + tree);
        assertTrue(tree.contains("wish-datapacks/dummy_wish/give.mcfunction"), tree);
    }

    @Test
    void commitsBuildKindWishesEvenWhenTheRepoIgnoresBuild(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(tmp), "git not installed");
        Path bare = tmp.resolve("remote.git");
        Path work = tmp.resolve("moddy-worktree");
        run(tmp, List.of("init", "--bare", bare.toString()));
        run(tmp, List.of("clone", bare.toString(), work.toString()));
        run(work, List.of("checkout", "-b", "moddy/wishes"));
        // The near-universal Gradle ignore — exactly what caught wishes/build once.
        Files.writeString(work.resolve(".gitignore"), "build\n");

        // A build-kind wish: editable source + manifest under wishes/build/<id>/.
        System.setProperty("moddingfromamod.home", tmp.resolve("home").toString());
        System.setProperty("moddingfromamod.persistDir", work.toString());
        RuntimeConfig.invalidate();
        ProvenancePublisher.publish("build", "sky_castle", "build.json",
                "{\"id\":\"sky_castle\",\"shapes\":[]}");

        boolean committed = GitSync.commitAndPush(work, "moddy/wishes",
                "Moddy wish (build): sky_castle");
        assertTrue(committed, "a build-kind wish must commit even though the repo ignores 'build'");

        String tree = out(bare, List.of("ls-tree", "-r", "--name-only", "moddy/wishes"));
        assertTrue(tree.contains("wishes/build/sky_castle/manifest.json"),
                "the build manifest must reach the remote despite the ignore: " + tree);
        assertTrue(tree.contains("wishes/build/sky_castle/build.json"),
                "the editable build source must reach the remote: " + tree);
    }

    @Test
    void refusesToCommitWhenNotOnTheModdyBranch(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(tmp), "git not installed");
        Path work = tmp.resolve("shared");
        run(tmp, List.of("init", work.toString())); // default branch (master/main), NOT moddy/wishes
        run(work, List.of("config", "user.email", "t@t"));
        run(work, List.of("config", "user.name", "t"));
        Files.writeString(work.resolve("README.md"), "a shared checkout\n");
        run(work, List.of("add", "-A"));
        run(work, List.of("commit", "-m", "init"));
        Files.createDirectories(work.resolve("wishes"));
        Files.writeString(work.resolve("wishes/x.json"), "{}");

        boolean committed = GitSync.commitAndPush(work, "moddy/wishes", "should never happen");
        assertFalse(committed, "must refuse to commit when the checkout is not on the Moddy branch");
        assertTrue(out(work, List.of("status", "--porcelain")).contains("wishes/"),
                "the wish files stay uncommitted — never swept onto the shared branch");
    }

    // ---- git helpers ----

    private static boolean gitAvailable(Path dir) {
        try {
            return exit(dir, List.of("--version")) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path dir, List<String> args) throws Exception {
        int e = exit(dir, args);
        if (e != 0) {
            throw new IllegalStateException("git " + args + " -> exit " + e);
        }
    }

    private static int exit(Path dir, List<String> args) throws Exception {
        Process p = start(dir, args);
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private static String out(Path dir, List<String> args) throws Exception {
        Process p = start(dir, args);
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return s;
    }

    private static Process start(Path dir, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(args);
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }
}
