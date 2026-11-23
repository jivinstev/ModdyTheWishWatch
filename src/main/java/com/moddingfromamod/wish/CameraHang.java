package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * <b>A camera body that LEFT the game while its process is still alive</b> — the rule, and the
 * hands that write down why before the body is put down.
 *
 * <p>Measured on a dedicated server (2026-10-17 twice, 2026-10-21 and 2026-10-29): {@code Moddy-Camera lost
 * connection: Timed out} ~45 s after {@code joined the game}, and no {@code the camera process
 * exited} line, because the launcher was still running. {@link CameraLifecycle} read that as "not
 * up, not starting" and started a SECOND launcher over the first — whose handle it then dropped, so
 * the first was never stopped — and when no wish wanted a camera at that moment nothing happened at
 * all until one did. And because nothing looked before it was gone, the only record of why the
 * client stopped answering was the absence of a line.
 *
 * <p>So the supervisor asks {@link #judge} on every look and, on {@link Verdict#RECYCLE}, hands the
 * body to {@link #recycle} on a daemon thread: one loud line, a thread dump of the body's own JVM
 * into {@link #LOG}, then the stop.
 * The next look starts a fresh body if one is wanted.
 *
 * <p>⚠ On a server whose cameras are camera VMs the launcher is a VM launcher script and
 * the client's JVM is on another machine, so there is no local JVM to dump and {@link #recycle}
 * says so. The VM agent's own lines are already in the world's {@code logs/camera-launch.log}; the
 * 2026-10 timeouts were that agent suspending its own VM mid-session, which no thread dump would
 * have shown.
 *
 * <p>Minecraft-free: the rule is unit-tested, and the hands are driven against a real JVM by
 * {@code CameraHangTest}.
 */
public final class CameraHang {

    private CameraHang() {
    }

    /** Where every recycle is written, relative to the server's directory (beside latest.log). */
    public static final Path LOG = Path.of("logs", "camera-hang.log");

    /** One older copy (.1) is kept past this, like camera-launch.sh's own log. */
    static final long ROTATE_BYTES = 4L * 1024 * 1024;

    /** A hung JVM can hang its attach too; the dump is worth this long and no longer. */
    static final long DUMP_TIMEOUT_MS = 20_000;

    public enum Verdict {
        /** Joined, or starting, or gone — nothing for this rule to do. */
        FINE,
        /** Alive and joined once since it was started, and not joined now: dump it and put it down. */
        RECYCLE
    }

    /**
     * Decide.
     *
     * @param alive       the launcher process is still running
     * @param joinedNow   the body's own name is in the player list right now
     * @param joinedSince the body has been seen joined since it was started
     * @param stopping    the supervisor itself is putting it down (an idle stop, a shrink)
     */
    public static Verdict judge(boolean alive, boolean joinedNow, boolean joinedSince, boolean stopping) {
        // Not "alive and not joined": a body that has never joined is a START in progress, and its
        // overrun is CameraLifecycle's start timeout. Only a body that WAS in the world and left on
        // its own while its launcher lives is a body nothing else will ever put down.
        return alive && joinedSince && !joinedNow && !stopping ? Verdict.RECYCLE : Verdict.FINE;
    }

    /**
     * Write down the body's state, then stop it. Blocking (the dump can take seconds): call it off
     * the server thread.
     *
     * @return the one-line outcome, for the caller's loud log line
     */
    public static String recycle(Process body, String who, Path log, Consumer<Process> stop) {
        StringBuilder out = new StringBuilder();
        out.append("── ").append(Instant.now()).append(' ').append(who)
                .append(" left the game while its launcher (pid ").append(body.pid())
                .append(") was still running — a hung or frozen client (keep-alives unanswered)\n");
        String outcome;
        Optional<ProcessHandle> jvm = localJvm(body.toHandle());
        if (jvm.isEmpty()) {
            outcome = "no local JVM under the launcher — the client runs on a camera VM; its agent's "
                    + "lines are in logs/camera-launch.log";
            out.append(outcome).append('\n');
        } else {
            long pid = jvm.get().pid();
            String dump = run(List.of(jcmd(), String.valueOf(pid), "Thread.print", "-l"));
            if (dump != null && dump.contains("\"")) {
                outcome = "thread dump of JVM " + pid + " (" + dump.lines().count() + " lines)";
                out.append("jcmd ").append(pid).append(" Thread.print:\n").append(dump);
            } else {
                // SIGQUIT: the JVM prints its own dump to its stdout, which is the camera's run.log.
                run(List.of("kill", "-3", String.valueOf(pid)));
                outcome = "jcmd could not attach to JVM " + pid + "; sent SIGQUIT — the dump is in "
                        + "the camera's run.log";
                out.append(outcome).append(dump == null ? "" : "\njcmd said: " + dump.strip()).append('\n');
            }
        }
        append(log, out.toString());
        stop.accept(body);
        return outcome;
    }

    /** The launcher itself if it is a JVM, else the first JVM below it (camera-launch.sh's client). */
    static Optional<ProcessHandle> localJvm(ProcessHandle root) {
        if (isJava(root)) {
            return Optional.of(root);
        }
        return root.descendants().filter(CameraHang::isJava).findFirst();
    }

    private static boolean isJava(ProcessHandle h) {
        return h.info().command().map(c -> Path.of(c).getFileName().toString().equals("java"))
                .orElse(false);
    }

    /** The jcmd beside the JVM this server runs on (the same JDK); else whatever PATH finds. */
    static String jcmd() {
        return ProcessHandle.current().info().command()
                .map(c -> Path.of(c).resolveSibling("jcmd"))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .orElse("jcmd");
    }

    /** stdout+stderr of a command, or null when it could not run or overran. */
    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    sb.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process died mid-read; what arrived is what there is
                }
            }, "mfam-camera-hang-read");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(2_000);
            return sb.toString();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static void append(Path log, String text) {
        try {
            Path dir = log.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (Files.exists(log) && Files.size(log) > ROTATE_BYTES) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(log, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the caller's loud line still says what happened; a missing log must not stop the stop
        }
    }
}
