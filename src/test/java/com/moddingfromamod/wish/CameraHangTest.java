package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The camera that timed out ~45 s after joining while its launcher lived (a live server, 2026-10-17 and
 * -21): the rule that notices it, and the hands that dump it and put it down, driven against REAL
 * processes — a JVM to dump, and a client that ignores TERM after its shell has gone.
 */
class CameraHangTest {

    // ── the rule ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aBodyThatWasJoinedAndLeftWhileAliveIsRecycled() {
        assertEquals(CameraHang.Verdict.RECYCLE, CameraHang.judge(true, false, true, false));
    }

    @Test
    void aBodyStillStartingIsTheStartTimeoutsBusinessNotThisRule() {
        // never joined: CameraLifecycle's start timeout owns it, or every slow boot is a "hang"
        assertEquals(CameraHang.Verdict.FINE, CameraHang.judge(true, false, false, false));
    }

    @Test
    void aJoinedBodyADeadBodyAndADeliberateStopAreLeftAlone() {
        assertEquals(CameraHang.Verdict.FINE, CameraHang.judge(true, true, true, false));
        assertEquals(CameraHang.Verdict.FINE, CameraHang.judge(false, false, true, false),
                "a body whose process exited is the 'camera process exited' path, already loud");
        assertEquals(CameraHang.Verdict.FINE, CameraHang.judge(true, false, true, true),
                "the supervisor's own idle stop disconnects it first; that is not a hang");
    }

    // ── the hands ───────────────────────────────────────────────────────────────────────────────

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** A shell launcher with a sleeping JVM under it — camera-launch.sh's shape. */
    private static Process launcherWithJvm(Path dir) throws Exception {
        Path src = dir.resolve("Hung.java");
        Files.writeString(src, "public class Hung { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(120_000); } }");
        // `; :` keeps sh from exec'ing java, so the JVM is a DESCENDANT, as under camera-launch.sh
        Process p = new ProcessBuilder("sh", "-c", "'" + java() + "' '" + src + "'; :")
                .redirectErrorStream(true).redirectOutput(dir.resolve("run.log").toFile()).start();
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            if (CameraHang.localJvm(p.toHandle()).isPresent()) {
                return p;
            }
            Thread.sleep(200);
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        throw new AssertionError("the test JVM never started under its launcher");
    }

    @Test
    void aRecycleWritesTheJvmsThreadsThenStopsIt(@TempDir Path dir) throws Exception {
        Process body = launcherWithJvm(dir);
        long jvm = CameraHang.localJvm(body.toHandle()).orElseThrow().pid();
        Thread.sleep(3_000);   // past the source compile, into Thread.sleep
        Path log = dir.resolve("logs").resolve("camera-hang.log");
        List<Process> stopped = new ArrayList<>();
        String outcome = CameraHang.recycle(body, "camera body 1 ('Moddy-Camera')", log, b -> {
            stopped.add(b);
            CameraSupervisor.endPatiently(b, 5_000);
        });
        String written = Files.readString(log);
        assertTrue(written.contains("camera body 1 ('Moddy-Camera') left the game while its launcher"),
                "the hang log must say WHO and WHY before anything else: " + written);
        // jcmd is beside a JDK; a JRE falls back to SIGQUIT into run.log. Either way the dump exists.
        String dump = outcome.startsWith("thread dump") ? written : Files.readString(dir.resolve("run.log"));
        assertTrue(dump.contains("\"main\"") && dump.contains("Thread.sleep"),
                "the dump must show the JVM's own threads (" + outcome + "):\n" + dump);
        assertEquals(List.of(body), stopped, "dumped FIRST, then put down — once");
        assertTrue(body.waitFor(10, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(jvm).map(ProcessHandle::isAlive).orElse(false),
                "the JVM under the launcher must die with it, not be orphaned");
    }

    @Test
    void aBodyWithNoLocalJvmSaysItRunsOnACameraVm(@TempDir Path dir) throws Exception {
        // A remote camera launcher's shape: a launcher with no JVM below it — the client is on another machine
        Process body = new ProcessBuilder("sh", "-c", "sleep 60").start();
        Path log = dir.resolve("camera-hang.log");
        String outcome = CameraHang.recycle(body, "camera body 2 ('Moddy-Camera-2')", log,
                b -> CameraSupervisor.endPatiently(b, 2_000));
        assertTrue(outcome.contains("camera VM") && Files.readString(log).contains("camera-launch.log"), outcome);
        assertTrue(body.waitFor(5, TimeUnit.SECONDS));
    }

    @Test
    void aClientThatIgnoresTermIsKilledAfterItsShellHasGone(@TempDir Path dir) throws Exception {
        // camera-launch.sh's trap exits at once on TERM; a hung client can outlive it. The tree is read
        // before the TERM, so the orphan is still found and forced.
        Path pid = dir.resolve("child.pid");
        Process body = new ProcessBuilder("sh", "-c",
                "sh -c 'trap \"\" TERM; echo $$ > " + pid + "; exec sleep 300' & trap 'exit 143' TERM; wait")
                .start();
        long until = System.currentTimeMillis() + 5_000;
        while (!Files.exists(pid) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        Thread.sleep(200);
        long child = Long.parseLong(Files.readString(pid).strip());
        CameraSupervisor.endPatiently(body, 1_000);
        long gone = System.currentTimeMillis() + 5_000;
        while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)
                && System.currentTimeMillis() < gone) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false),
                "a client that ignored TERM must not be left running after its launcher exited");
    }
}
