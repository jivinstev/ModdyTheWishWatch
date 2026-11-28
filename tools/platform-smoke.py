#!/usr/bin/env python3
"""platform-smoke.py -- the release jar on a real NeoForge server, on THIS machine's OS and CPU.

Every other gate runs on ubuntu x64. The parts of the mod that can differ by platform are a native library (ONNX
Runtime, nested in the jar with one native set per platform), files (the model copied out of the jar, the world, the
datapacks a make writes) and the JDK. This boots the jar the way a player's server does, and the GameTest smoke
(.github/workflows/platform-smoke.yml, `gate.py run`) drives the rest from source on the same machine.

    python tools/platform-smoke.py server --jar build/store-release/<jar> --out <dir>
        --neoforge <neo_version>
        Installs that NeoForge server (the version the jar was built against: the official installer from
        maven.neoforged.net, --installServer), puts the jar in mods/, and boots it TWICE in one folder:
          boot 1 makes the world; the mod copies its sentence model out of the jar and loads ONNX Runtime's native
                 library for this platform, then reads one clause through the model (LayerHost's warm-up);
                 `save-all flush` and `stop` must end it cleanly;
          boot 2 loads that world back (the same checks, and the model is NOT copied out again).
        RED, loudly, on: no "apprentice layer: on" line (the native library did not load: the line it logged
        instead is quoted), no native library file for this platform where the mod unpacked it, a linkage error or a
        crash in the log, a wish datapack the boot could not put in place, the world missing after boot 1, or a boot that does not reach "Done" or stop in time.
    python tools/platform-smoke.py client --mode <testmode> --out <dir>
        A Gate C film (tools/gate-c-rig.sh's client test, without bash) through ./gradlew runClient on this machine's
        display: PASS is the film's own verdict. The workflow's Windows job runs it under Mesa's software OpenGL.

Exit: 0 green · 1 a check failed · 2 could not run (download, install, timeout before the first line).
Standard library only; never needs a shell. docs/RELEASING.md ("Platform smoke") says what runs where.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile

MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/{v}/neoforge-{v}-installer.jar"
MOD_ID = "moddingfromamod"
DONE = re.compile(r"Done \(\d+[.,]\d+s\)!")
LAYER = re.compile(r"apprentice layer: (on|off)\b.*")
SAVED = re.compile(r"Saved the game")
# A line that fails the boot whatever else it says. A missing or wrong-architecture native library is the first.
BAD = re.compile(r"UnsatisfiedLinkError|NoClassDefFoundError|ExceptionInInitializerError|Mixin apply failed"
                 r"|AppenderLoggingException"
                 r"|Encountered an unexpected exception|Exception in server tick loop|Failed to load (?:the )?world"
                 r"|Missing or unsupported mandatory dependencies|Mod loading has failed"
                 # a wish pack the boot could not put in place: on Windows, a loaded pack's zip held open, and the world
                 # kept running the old pack (DatapackStore.installAllIntoWorld)
                 r"|could not stage wish datapack")
# The one report BAD would match that a stock server logs too: vanilla 1.21.11 (and later) asks Netty's native
# transports whether they are available, KQueue.isAvailable() and Epoll.isAvailable(), on every OS, and where one
# cannot load (kqueue off macOS/BSD, epoll off Linux) the log appender reports the class it could not initialize.
# Only this exact cause is let through; any other appender error or NoClassDefFoundError still fails the boot.
STOCK = re.compile(r"AppenderLoggingException: java\.lang\.NoClassDefFoundError: "
                   r"Could not initialize class io\.netty\.channel\.(kqueue|epoll)\.Native\s*$")
# The reason Netty's transport refuses this OS (its Native.loadNativeLibrary), by transport.
REFUSAL = {"kqueue": "Only supported on OSX/BSD", "epoll": "Only supported on Linux"}


# That line comes with its stack trace, whose cause chain is the same stock failure: log4j's renderer meets the
# uninitialisable class again, and under it the reason Netty's transport refuses this OS. Only these two causes (of
# the same transport), the trace's own frames and its "... N more" lines are part of the stock report; anything else
# ends it.
def stock_cause(transport: str):
    return re.compile(r"^Caused by: (?:java\.lang\.NoClassDefFoundError: Could not initialize class "
                      r"io\.netty\.channel\." + transport + r"\.Native"
                      r"|java\.lang\.ExceptionInInitializerError: Exception java\.lang\.IllegalStateException: "
                      + re.escape(REFUSAL[transport]) + r" \[in thread \"[^\"]*\"\])\s*$")


STOCK_CAUSE = {t: stock_cause(t) for t in REFUSAL}
FRAME = re.compile(r"^\s+(?:at \S|\.\.\. \d+ more\s*$)")


def bad(line: str) -> bool:
    """Whether a server log line fails the boot."""
    return bool(BAD.search(STOCK.sub("", line)))


def first_bad(lines: list):
    """The first line of a boot's log that fails it, or None: bad() line by line, except that a stock native-transport
    report's own stack trace (frames and the stock causes only) is let through with it."""
    in_stock = None
    for line in lines:
        if in_stock and (FRAME.search(line) or STOCK_CAUSE[in_stock].search(line)):
            continue
        in_stock = None
        if bad(line):
            return line
        m = STOCK.search(line)
        if m:
            in_stock = m.group(1)
    return None


NATIVE = {"Windows": "onnxruntime.dll", "Darwin": "libonnxruntime.dylib", "Linux": "libonnxruntime.so"}


def say(msg: str) -> None:
    print("platform-smoke: " + msg, flush=True)


def where() -> str:
    return "%s %s (Java from %s)" % (platform.system(), platform.machine(), java())


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    exe = "java.exe" if os.name == "nt" else "java"
    if home and os.path.isfile(os.path.join(home, "bin", exe)):
        return os.path.join(home, "bin", exe)
    found = shutil.which("java")
    if not found:
        raise SystemExit(2)
    return found


def jar_versions(jar: str) -> tuple:
    """(mod version, Minecraft range) from the jar's own neoforge.mods.toml."""
    with zipfile.ZipFile(jar) as z:
        toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
    ver = re.search(r'(?m)^version\s*=\s*"([^"]+)"', toml)
    mc = None
    for block in re.split(r"(?m)^\[\[dependencies\.", toml)[1:]:
        if re.search(r'(?m)^\s*modId\s*=\s*"minecraft"', block):
            m = re.search(r'(?m)^\s*versionRange\s*=\s*"([^"]+)"', block)
            mc = m.group(1) if m else None
    return (ver.group(1) if ver else "?"), mc


def download(url: str, dest: str) -> None:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(dest, "wb") as f:
                shutil.copyfileobj(r, f)
            return
        except OSError as e:
            say("download failed (%s), attempt %d of 4: %s" % (url, attempt + 1, e))
            time.sleep(5 * (attempt + 1))
    say("could not download %s" % url)
    raise SystemExit(2)


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Server:
    """One boot of the installed server: its console lines on a queue, its stdin to type commands into."""

    def __init__(self, folder: str, args_file: str, log_path: str):
        cmd = [java(), "@user_jvm_args.txt", "@" + args_file, "nogui"]
        self.log = open(log_path, "w", encoding="utf-8")
        self.lines: "queue.Queue[str]" = queue.Queue()
        self.all: list = []
        self.proc = subprocess.Popen(cmd, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        assert self.proc.stdout is not None
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            self.log.write(line + "\n")
            self.log.flush()
            self.all.append(line)
            self.lines.put(line)
        self.lines.put(None)  # type: ignore[arg-type]

    def wait_for(self, pattern: re.Pattern, seconds: float):
        """The first line matching pattern, or None at the deadline or when the server exits."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return None
            if pattern.search(line):
                return line
        return None

    def type(self, command: str) -> None:
        assert self.proc.stdin is not None
        self.proc.stdin.write((command + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def stop(self, seconds: float) -> int | None:
        try:
            self.type("stop")
        except OSError:
            pass
        try:
            return self.proc.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
            return None
        finally:
            self.log.flush()

    def kill(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait()


def boot(folder: str, args_file: str, out: str, n: int, problems: list, timeout: int) -> dict:
    t0 = time.time()
    log_path = os.path.join(out, "boot%d.log" % n)
    s = Server(folder, args_file, log_path)
    row = {"boot": n, "log": log_path}
    try:
        done = s.wait_for(DONE, timeout)
        row["doneSeconds"] = round(time.time() - t0, 1)
        if not done:
            problems.append("boot %d: the server never printed Done within %ds (log: %s)" % (n, timeout, log_path))
            return row
        layer = s.wait_for(LAYER, 300)
        row["layer"] = layer
        if layer is None or " on " not in layer + " ":
            said = layer or "no 'apprentice layer:' line within 300 s of Done"
            problems.append("boot %d: the Apprentice's model did not load on %s -- %s" % (n, where(), said.strip()))
        s.type("save-all flush")
        if not s.wait_for(SAVED, 120):
            problems.append("boot %d: save-all flush never said 'Saved the game'" % n)
        rc = s.stop(120)
        row["exit"] = rc
        row["seconds"] = round(time.time() - t0, 1)
        if rc is None:
            problems.append("boot %d: the server did not stop within 120 s of `stop`" % n)
    finally:
        s.kill()
    line = first_bad(s.all)
    if line is not None:
        problems.append("boot %d: %s" % (n, line.strip()[:400]))
    row["copiedModel"] = any("apprentice layer:" in ln and "copied" in ln.lower() for ln in s.all)
    return row


def cmd_server(a) -> int:
    out = os.path.abspath(a.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    jar = os.path.abspath(a.jar)
    version, mc = jar_versions(jar)
    neo = a.neoforge
    say("%s: mod %s, Minecraft %s, NeoForge %s, on %s" % (os.path.basename(jar), version, mc, neo, where()))
    folder = os.path.join(out, "server")
    os.makedirs(folder)
    installer = os.path.join(out, "neoforge-installer.jar")
    t0 = time.time()
    download(MAVEN.format(v=neo), installer)
    inst = subprocess.run([java(), "-jar", installer, "--installServer", folder], capture_output=True, text=True)
    with open(os.path.join(out, "install.log"), "w", encoding="utf-8") as f:
        f.write(inst.stdout + inst.stderr)
    args_file = "libraries/net/neoforged/neoforge/%s/%s_args.txt" % (neo, "win" if os.name == "nt" else "unix")
    if inst.returncode != 0 or not os.path.isfile(os.path.join(folder, args_file)):
        say("the NeoForge %s installer failed (exit %s; %s)" % (neo, inst.returncode, os.path.join(out, "install.log")))
        return 2
    installed = round(time.time() - t0, 1)
    os.makedirs(os.path.join(folder, "mods"))
    shutil.copy2(jar, os.path.join(folder, "mods", os.path.basename(jar)))
    with open(os.path.join(folder, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(folder, "server.properties"), "w") as f:
        f.write("online-mode=false\nserver-port=%d\nmotd=platform smoke\nsync-chunk-writes=true\n" % free_port())
    if not os.path.isfile(os.path.join(folder, "user_jvm_args.txt")):
        open(os.path.join(folder, "user_jvm_args.txt"), "w").close()
    problems: list = []
    rows = [boot(folder, args_file, out, 1, problems, a.timeout)]
    world = os.path.join(folder, "world")
    if not os.path.isfile(os.path.join(world, "level.dat")):
        problems.append("boot 1 left no world/level.dat: the world was not saved")
    native = os.path.join(folder, MOD_ID, "onnxruntime-native", NATIVE.get(platform.system(), "?"))
    if not os.path.isfile(native):
        problems.append("no ONNX Runtime native library for %s where the mod unpacks it (%s)" % (where(), native))
    if not problems:
        rows.append(boot(folder, args_file, out, 2, problems, a.timeout))
        if rows[-1].get("copiedModel"):
            problems.append("boot 2 copied the model out of the jar again: the first boot's copy was not kept")
    result = {"platform": where(), "jar": os.path.basename(jar), "mod": version, "minecraft": mc, "neoforge": neo,
              "installSeconds": installed, "boots": rows, "native": native if os.path.isfile(native) else None,
              "problems": problems, "verdict": "green" if not problems else "red"}
    with open(os.path.join(out, "server.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    say("installed in %ss; %s" % (installed, "; ".join(
        "boot %d: Done in %ss, %ss in all, %s" % (r["boot"], r.get("doneSeconds"), r.get("seconds"),
                                                 (r.get("layer") or "no layer line").strip()[-140:]) for r in rows)))
    for p in problems:
        say("RED -- " + p)
    say("GREEN" if not problems else "RED")
    return 0 if not problems else 1


def cmd_client(a) -> int:
    out = os.path.abspath(a.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    sig = os.path.join(out, "sig")
    os.makedirs(sig)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    wrapper = os.path.join(root, "gradlew.bat") if os.name == "nt" else "./gradlew"
    cmd = [wrapper, "runClient", "-Pboottest", "-Ptestmode=" + a.mode, "-Psigdir=" + sig, "-Prundir=client-smoke",
           "--no-daemon", "--console=plain"] + list(a.gradle_arg or [])
    env = dict(os.environ)
    env.setdefault("ALSOFT_DRIVERS", "null")
    t0 = time.time()
    log_path = os.path.join(out, "run.log")
    say("client film %s on %s" % (a.mode, where()))
    with open(log_path, "w", encoding="utf-8") as log:
        p = subprocess.Popen(cmd, cwd=root, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            p.wait(timeout=a.timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
            p.kill()
            p.wait()
            say("TIMEOUT after %ds (log: %s)" % (a.timeout, log_path))
            return 2
    secs = round(time.time() - t0, 1)
    text = open(log_path, encoding="utf-8", errors="replace").read()
    gl = re.search(r"(?:GL info|Backend library|OpenGL)[^\n]*", text)
    say("renderer: %s" % (gl.group(0).strip()[:200] if gl else "not logged"))
    result_file = os.path.join(sig, "result")
    if not os.path.isfile(result_file):
        say("NO RESULT after %ss -- the client exited before its film reported (log: %s)" % (secs, log_path))
        for line in text.splitlines():
            if re.search(r"Caused by:|Exception in thread|FATAL|pixel format|WGL|OpenGL|GLFW error", line):
                say("  " + line.strip()[:300])
        return 2
    verdict = open(result_file, encoding="utf-8", errors="replace").read().strip()
    shots = sorted(n for n in os.listdir(sig) if n.endswith(".png"))
    say("film %s in %ss: %s" % (a.mode, secs, verdict.splitlines()[0][:400] if verdict else "(empty)"))
    say("%d frame(s) captured in %s" % (len(shots), sig))
    return 0 if verdict.startswith("PASS") else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("server")
    s.add_argument("--jar", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--neoforge", required=True,
                   help="the NeoForge version the jar was built against (neo_version in the built ref's gradle.properties)")
    s.add_argument("--timeout", type=int, default=900, help="seconds a boot may take to reach Done")
    c = sub.add_parser("client")
    c.add_argument("--mode", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--timeout", type=int, default=1800)
    c.add_argument("--gradle-arg", action="append")
    a = ap.parse_args()
    return cmd_server(a) if a.cmd == "server" else cmd_client(a)


if __name__ == "__main__":
    sys.exit(main())
