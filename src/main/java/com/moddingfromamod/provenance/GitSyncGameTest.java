package com.moddingfromamod.provenance;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — a granted wish reaches the git repo automatically, no manual step, no reboot.</b>
 *
 * <p>On a real dedicated server with git-sync <b>enabled</b>, granting a wish through the REAL
 * production path ({@link LearnedTemplates#addAndSave} → {@code ProvenancePublisher.record} →
 * {@code GitSync.requestPublish}, all async) results in a commit <b>pushed to a remote</b>. The
 * "remote" is a local <b>bare</b> repo, so this is fully offline — no GitHub, no credentials. The
 * "will my kid's wishes actually land in the repo?" test, automated end to end.
 *
 * <p>git-sync is server-side (never touches the client), so a headless GameTest is the faithful gate.
 * Skips (passes) if git isn't installed.
 *
 * <p><b>Threading:</b> the background waiter does NO global mutation — it only reads the remote and
 * sets the verdict. ALL config-property/cleanup happens back on the server thread in {@code
 * succeedWhen}, so nothing races the next gametest (a real bug an earlier version of this test hit).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class GitSyncGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 100000)
    public static void aGrantedWishIsAutoCommittedAndPushed(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        if (!gitAvailable()) {
            helper.succeed();
            return;
        }
        final String pPersist = System.getProperty("moddingfromamod.persistDir");
        final String pSync = System.getProperty("moddingfromamod.wishGitSync");
        final String pHome = System.getProperty("moddingfromamod.home");
        final List<String> savedTemplates = new ArrayList<>(LearnedTemplates.savedJsonForTest());
        final AtomicReference<String> verdict = new AtomicReference<>(); // null=pending, "OK", else why

        final Path tmp;
        final Path bare;
        try {
            tmp = Files.createTempDirectory("gitsync_gt_");
            bare = tmp.resolve("remote.git");
            Path work = tmp.resolve("moddy-worktree");
            git(tmp, "init", "--bare", bare.toString());
            git(tmp, "clone", bare.toString(), work.toString());
            git(work, "checkout", "-b", "moddy/wishes");

            System.setProperty("moddingfromamod.home", tmp.resolve("home").toString());
            System.setProperty("moddingfromamod.persistDir", work.toString());
            System.setProperty("moddingfromamod.wishGitSync", "true");
            RuntimeConfig.invalidate();

            // GRANT A WISH the real way — teaching a template publishes + fires async git-sync.
            JsonObject t = JsonParser.parseString(
                    "{\"id\":\"gitsync_wish\",\"all\":[\"gitsyncwish\"],"
                    + "\"commands\":[\"say hi\"],\"say\":\"here\"}").getAsJsonObject();
            LearnedTemplates.addAndSave(server, t);
        } catch (Exception e) {
            restoreProps(pPersist, pSync, pHome);
            RuntimeConfig.invalidate();
            helper.fail(Component.literal("git-sync gametest setup failed: " + e));
            return;
        }

        // Off the server thread: wait for the async publish, read the remote. NO global mutation here.
        Thread waiter = new Thread(() -> {
            try {
                GitSync.awaitIdle(30_000);
                String log = out(bare, "log", "--oneline", "moddy/wishes");
                verdict.set(log.contains("gitsync_wish") ? "OK"
                        : "the granted wish was NOT pushed to the remote; log: [" + log.strip() + "]");
            } catch (Exception e) {
                verdict.set("checking the remote threw: " + e);
            }
        }, "gitsync-gt-waiter");
        waiter.setDaemon(true);
        waiter.start();

        helper.succeedWhen(() -> {
            String v = verdict.get();
            helper.assertTrue(v != null, Component.literal("waiting for git-sync to commit + push the granted wish"));
            // Verdict is in — do ALL cleanup here on the server thread, then assert.
            restoreProps(pPersist, pSync, pHome);
            RuntimeConfig.invalidate();
            LearnedTemplates.restoreForTest(savedTemplates, server);
            deleteQuietly(tmp);
            helper.assertTrue("OK".equals(v), Component.literal(v));
        });
    }

    // ---- helpers ----

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").redirectErrorStream(true).start()
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void restoreProps(String persist, String sync, String home) {
        setOrClear("moddingfromamod.persistDir", persist);
        setOrClear("moddingfromamod.wishGitSync", sync);
        setOrClear("moddingfromamod.home", home);
    }

    private static void setOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    private static String out(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return s;
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
