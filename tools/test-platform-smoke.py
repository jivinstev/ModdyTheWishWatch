#!/usr/bin/env python3
"""Tests for tools/platform-smoke.py's boot-log check (bad(), first_bad()): the one stock-server report it lets
through, with its stack trace, and that everything near it still fails the boot.

    python3 tools/test-platform-smoke.py
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("platform_smoke", os.path.join(HERE, "platform-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

PFX = "[27Nov2026 10:00:00.000] [Server thread/ERROR] [STDERR/]: "
APPENDER = "org.apache.logging.log4j.core.appender.AppenderLoggingException: "
CASES = [
    # (line, fails the boot?)
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native", False),
    (APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native  ", False),
    ("[Server thread/INFO] [minecraft/DedicatedServer]: Done (4.2s)! For help, type \"help\"", False),
    (PFX + "java.lang.NoClassDefFoundError: com/moddingfromamod/wish/WarmUp$Outcome", True),
    ("java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: com/moddingfromamod/wish/WarmUp$Outcome", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.epoll.Native", True),
    (PFX + APPENDER + "java.io.IOException: No space left on device", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native"
     " -- java.lang.UnsatisfiedLinkError: no onnxruntime", True),
    ("Exception in server tick loop " + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class "
     "io.netty.channel.kqueue.Native", True),
]

# The stock report as a stock NeoForge 21.11.45 server prints it on Linux (shortened): the appender line, its trace,
# and the two causes under it.
TRACE = [
    "Server thread ERROR An exception occurred processing Appender DebugFile",
    APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native",
    "\tat org.apache.logging.log4j.core.config.AppenderControl.tryCallAppender(AppenderControl.java:164)",
    "\tat io.netty.channel.kqueue.KQueue.<clinit>(KQueue.java:54)",
    "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
    "Caused by: java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native",
    "\tat java.base/java.lang.Class.forName0(Native Method)",
    "\t... 19 more",
    "Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.IllegalStateException: "
    "Only supported on OSX/BSD [in thread \"Server thread\"]",
    "\tat io.netty.channel.kqueue.Native.loadNativeLibrary(Native.java:159)",
    "\t... 6 more",
    "",
]
DONE = "[Server thread/INFO] [minecraft/DedicatedServer]: Done (6.880s)! For help, type \"help\""
OTHER_CAUSE = "Caused by: java.lang.UnsatisfiedLinkError: no onnxruntime in java.library.path"
LOGS = [
    # (log lines, the line that fails the boot or None)
    (TRACE + [DONE], None),
    (TRACE + TRACE + [DONE], None),
    (TRACE[:-1] + [OTHER_CAUSE, DONE], OTHER_CAUSE),
    # a stock cause line with no stock report above it is not let through
    ([DONE, TRACE[5]], TRACE[5]),
    # the stock report ends at the first line that is not part of its trace
    (TRACE + [DONE, TRACE[5]], TRACE[5]),
    (TRACE[:3] + ["[Server thread/INFO] [x/]: hello", "\tat com.moddingfromamod.X.y(X.java:1)", TRACE[5]], TRACE[5]),
]

failed = 0
for line, want in CASES:
    got = smoke.bad(line)
    if got != want:
        failed += 1
        print("FAIL: bad(%r) = %s, want %s" % (line, got, want))
for lines, want in LOGS:
    got = smoke.first_bad(lines)
    if got != want:
        failed += 1
        print("FAIL: first_bad(%r) = %r, want %r" % (lines, got, want))
total = len(CASES) + len(LOGS)
print("%d of %d cases %s" % (total - failed, total, "passed" if not failed else "right"))
sys.exit(1 if failed else 0)
